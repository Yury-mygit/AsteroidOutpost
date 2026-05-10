package com.example.asteroidoutpost.game

import android.os.Handler
import android.os.HandlerThread
import kotlin.math.pow
import com.example.asteroidoutpost.game.combat.Asteroid
import com.example.asteroidoutpost.game.combat.Beam
import com.example.asteroidoutpost.game.combat.DraftCombat
import com.example.asteroidoutpost.game.combat.Drone
import com.example.asteroidoutpost.game.combat.Fireball
import com.example.asteroidoutpost.game.combat.Flash
import com.example.asteroidoutpost.game.combat.HeavyShellBehavior
import com.example.asteroidoutpost.game.combat.HomingRocketBehavior
import com.example.asteroidoutpost.game.combat.Particle
import com.example.asteroidoutpost.game.combat.PlainBulletBehavior
import com.example.asteroidoutpost.game.combat.Projectile
import com.example.asteroidoutpost.game.combat.ProjectileBehavior
import com.example.asteroidoutpost.game.combat.RocketPhase
import com.example.asteroidoutpost.game.combat.Vec3
import com.example.asteroidoutpost.game.combat.VfxSpawner
import com.example.asteroidoutpost.game.combat.WeaponEffect
import com.example.asteroidoutpost.game.combat.WeaponEffectContext
import com.example.asteroidoutpost.game.combat.bestHpTargetInArc
import com.example.asteroidoutpost.game.combat.centralWeaponHalfArc
import com.example.asteroidoutpost.game.combat.isWithinArc
import com.example.asteroidoutpost.game.combat.nearestAsteroidInArc
import com.example.asteroidoutpost.game.combat.pickAsteroidAt
import com.example.asteroidoutpost.game.combat.pickAsteroidType
import com.example.asteroidoutpost.game.combat.tickParticles
import com.example.asteroidoutpost.game.ui.HudView

/**
 * High-level game state machine. Tick advances only in PLAYING; MENU is the
 * idle / overlay state; WON/LOST gate the end-of-mission overlays.
 */
internal enum class GameState { MENU, PLAYING, WON, LOST }

/**
 * Owns the mission gameplay state, the per-tick simulation loop, ability
 * activation, target selection and win/lose detection. Constructed once per
 * Activity; the host (`MainActivity`) drives lifecycle via
 * `startMission/stopMission/destroy` and forwards UI events via
 * `handleWorldTap/handleShieldDown/handleShieldUp/handleAbilityTap`.
 *
 * Threading. The tick runs on its own `HandlerThread` ("DraftTickThread"),
 * created lazily on first `startMission`. UI-thread mutations (ability
 * presses, world taps) are marshalled onto that thread when they need to
 * land atomically between two ticks. HUD refreshes from the tick are
 * marshalled back via `uiHandler` (main looper).
 *
 * State exposure. Collections (asteroids, effects, flashes, fireballs,
 * particle pools) and read-only scalars (`centralTurretAngle`, `shieldHp`,
 * `currentWeapon`) are public so `SceneAssembler` can
 * reference them and produce its `SceneFrame` each frame. The runner emits
 * no engine calls itself — `onRender` (a callback supplied by the host) is
 * invoked at the end of every tick to drive scene composition.
 */
internal class MissionRunner(
    private val uiHandler: Handler,
    private val progressRepo: ProgressRepository,
    private val onRender: () -> Unit,
    private val host: Host,
) {
    /** Activity-side hooks: end-of-mission overlay flow. */
    interface Host {
        fun onMissionWon()
        fun onMissionLost()
    }

    // ------------------------------------------------------------------
    // Late-bound deps — set by attachAssets() after asset load. The mesh
    // handles are 0L until the engine has loaded the assets, so any tick
    // would spawn invisible projectiles; attachAssets MUST run before the
    // first startMission. Assets the runner doesn't actually need at
    // construct time (HudView, VfxSpawner) take this path too so onCreate
    // can build them after MissionRunner exists (HudView needs
    // `abilitySlots`, which lives on the runner).
    // ------------------------------------------------------------------
    private lateinit var hud: HudView
    private lateinit var vfx: VfxSpawner
    private var rocketMeshHandle:        Long = 0L
    private var bulletMeshHandle:        Long = 0L
    private var bulletHeavyMeshHandle:   Long = 0L
    private var asteroidMeshGrey1:       Long = 0L
    private var asteroidMeshGrey2:       Long = 0L
    private var asteroidMeshHeavy:       Long = 0L
    private var asteroidMeshExplosive:   Long = 0L
    private var asteroidMeshEnergy:      Long = 0L

    fun attachAssets(
        hud: HudView,
        vfx: VfxSpawner,
        rocketMeshHandle: Long,
        bulletMeshHandle: Long,
        bulletHeavyMeshHandle: Long,
        asteroidMeshGrey1: Long,
        asteroidMeshGrey2: Long,
        asteroidMeshHeavy: Long,
        asteroidMeshExplosive: Long,
        asteroidMeshEnergy: Long,
    ) {
        this.hud = hud
        this.vfx = vfx
        this.rocketMeshHandle      = rocketMeshHandle
        this.bulletMeshHandle      = bulletMeshHandle
        this.bulletHeavyMeshHandle = bulletHeavyMeshHandle
        this.asteroidMeshGrey1     = asteroidMeshGrey1
        this.asteroidMeshGrey2     = asteroidMeshGrey2
        this.asteroidMeshHeavy     = asteroidMeshHeavy
        this.asteroidMeshExplosive = asteroidMeshExplosive
        this.asteroidMeshEnergy    = asteroidMeshEnergy
    }

    // ------------------------------------------------------------------
    // Tick thread (lazy-created on first startMission, quit on destroy()).
    // ------------------------------------------------------------------
    private var missionThread:  HandlerThread? = null
    private var missionHandler: Handler?       = null

    // ------------------------------------------------------------------
    // Public game state — read by SceneAssembler each frame and by the
    // Activity for end-of-mission overlay stats. Collections are mutated
    // only on the tick thread; scalars marked @Volatile when the UI thread
    // reads or writes them.
    // ------------------------------------------------------------------
    val asteroids: MutableList<Asteroid> = mutableListOf(
        Asteroid(id = 1L, xPos = -1.5f, zPos = 8.5f, hp = 100),
        Asteroid(id = 2L, xPos =  1.0f, zPos = 8.0f, hp = 100),
    )
    val effects:           MutableList<WeaponEffect> = mutableListOf()
    val drones:            MutableList<Drone>        = mutableListOf()
    val flashes:           MutableList<Flash>        = mutableListOf()
    val fireballs:         MutableList<Fireball>     = mutableListOf()
    val sparkParticles:    MutableList<Particle>     = mutableListOf()
    val smokeParticles:    MutableList<Particle>     = mutableListOf()
    val debrisParticles:   MutableList<Particle>     = mutableListOf()

    // DRAFT — turret state. Two static blue squares on the platform; each fires
    // at the nearest asteroid. Kept simple (per-turret fire timer only).
    // Side deck guns — port and starboard mounts amidships, flanking
    // the centerline. Both sit at `DraftCombat.TURRET_TOP_Z`. The bow
    // gun (central turret) is forward of them on the centerline.
    val turretXs       = floatArrayOf(-1.10f, 1.10f)
    // Side turret aim angles — independent of the firing routine, smoothed
    // every tick toward the nearest asteroid so the rotating barrel mesh
    // tracks a target visually rather than snapping at fire-time only.
    val sideTurretAngles = floatArrayOf(0f, 0f)

    val abilitySlots: List<AbilitySlot> = listOf(
        AbilitySlot(AbilityCatalog.ROCKET_STRIKE),
        AbilitySlot(AbilityCatalog.LASER_STRIKE),
        AbilitySlot(AbilityCatalog.DRONES),
    )
    val missionRun: MissionRun = MissionRun()

    @Volatile var gameState: GameState = GameState.MENU
        private set
    @Volatile var currentMission: MissionConfig? = null
        private set
    // Active weapon equipped on the central turret. Set by startMission and
    // exposed so the weapon-select overlay can highlight the active card.
    @Volatile var currentWeapon: Weapon = WeaponCatalog.AUTOMATIC
    // Smoothed aim angle of the central turret (radians, atan2(dx,dz)).
    // Tracks the touch position; used by SceneAssembler to orient the
    // turret model and by the tick to spawn bullets.
    @Volatile var centralTurretAngle: Float = 0f
        private set
    @Volatile var shieldHp: Float = DraftCombat.SHIELD_MAX_HP
        private set
    @Volatile var platformHP: Int = 100
        private set

    // ------------------------------------------------------------------
    // Internal tick state — mutated on DraftTickThread only (unless marked
    // @Volatile, in which case the UI thread also reads or writes it).
    // ------------------------------------------------------------------
    @Volatile private var centralTargetId: Long? = null
    private var centralFireCooldown: Float = 0f
    private var spawnTimer:          Float = 0f
    private var currentWaveIndex:    Int   = 0
    private var currentWaveSpawned:  Int   = 0
    private var waveBreakTimer:      Float = 0f
    private var effectiveMainWeaponDamage: Int = UpgradeCatalog.mainWeaponDamageAt(1)
    private var effectiveTurretDamage:     Int = UpgradeCatalog.sideTurretDamageAt(1)
    private val turretFireT  = floatArrayOf(0f, 0f)
    private var nextAsteroidId: Long = 3L
    private fun newAsteroidId(): Long = nextAsteroidId++

    // Shield — permanent barrier with HP. Asteroids that touch the platform
    // chip its HP first; only when shield HP runs out does the platform take
    // damage. The player holds the shield button to recharge — energy
    // drains while pressed, shield HP refills proportionally. No on/off
    // toggle; the arch is rendered whenever shieldHp > 0.
    @Volatile private var shieldRecharging: Boolean = false
    private var shieldUiPctLast: Int = -1   // last shown HP percentage (UI throttle)

    // Buff system (single slot). When `activeBuffTimer > 0`, the central
    // turret's per-shot damage is multiplied by `activeBuffDamageMul`. Set by
    // ENERGY-asteroid kills; ticked down each frame.
    @Volatile private var activeBuffTimer:     Float = 0f
    @Volatile private var activeBuffDamageMul: Float = 1f
    private var buffUiSecLast: Int = -1
    // Energy resource (M8.3) — fuel for active abilities (rocket strike,
    // laser strike, future ones). Regenerates passively in-mission.
    @Volatile private var energy: Float = DraftCombat.ENERGY_MAX
    private var energyUiLast: Int = -1

    // Weapon-installation owner — tied to a specific projectile type via
    // its fire() method. RocketSilo can ONLY produce HomingRocketBehavior
    // projectiles (see class definition); the structural binding prevents
    // accidental "rocket silo fires bullets" regressions.
    private val rocketSilo: RocketSilo = RocketSilo(
        xPos = DraftCombat.ROCKET_SILO_X,
        zPos = DraftCombat.ROCKET_SILO_Z + DraftCombat.ROCKET_SILO_MUZZLE_OFFSET,
    )

    // Per-tick context handed to every WeaponEffect.tick() call. Exposes
    // the bits of world state effects need (asteroids for collision/aiming,
    // vfx for impact/jet/trail flashes) without coupling the effect classes
    // to MainActivity. Reads vfx/asteroids by ref so attachAssets() running
    // after construction is fine.
    private val weaponCtx: WeaponEffectContext = object : WeaponEffectContext {
        override val asteroids: List<Asteroid> get() = this@MissionRunner.asteroids
        override val vfx: VfxSpawner            get() = this@MissionRunner.vfx
    }

    // ------------------------------------------------------------------
    // Public API — lifecycle.
    // ------------------------------------------------------------------

    fun startMission(mission: MissionConfig, weapon: Weapon) {
        currentWeapon      = weapon
        currentMission     = mission
        currentWaveIndex   = 0
        currentWaveSpawned = 0
        waveBreakTimer     = 0f
        flashes.clear()
        fireballs.clear()
        sparkParticles.clear()
        smokeParticles.clear()
        debrisParticles.clear()
        // Apply upgrade levels — values frozen for the duration of this run.
        effectiveMainWeaponDamage = UpgradeCatalog.mainWeaponDamageAt(progressRepo.current.mainWeaponDamageLevel)
        effectiveTurretDamage     = UpgradeCatalog.sideTurretDamageAt(progressRepo.current.sideTurretDamageLevel)
        val effectiveBaseHp   = mission.baseHp + UpgradeCatalog.baseHpBonusAt(progressRepo.current.baseHpLevel)
        missionRun.score              = 0
        missionRun.asteroidsDestroyed = 0
        missionRun.metalEarned        = 0
        missionRun.winBonus           = 0
        missionRun.currentWaveDisplay = 1
        missionRun.totalWaves         = mission.waves.size
        missionRun.missionName        = mission.name
        platformHP    = effectiveBaseHp
        // Reset aim to straight up. With no live asteroids the turret idles
        // vertical until the first wave spawns.
        centralTurretAngle = 0f
        centralTargetId    = null
        effects.clear()
        asteroids.clear()
        centralFireCooldown = 0f
        spawnTimer    = 0f
        turretFireT[0] = 0f
        turretFireT[1] = 0f
        // Energy starts the run full so the first ability is immediately
        // available for early waves.
        energy        = DraftCombat.ENERGY_MAX
        energyUiLast  = -1
        // Reset all ability cooldowns to READY for a clean run.
        abilitySlots.forEach { it.currentCd = 0f; it.cdUiLast = -1 }
        // Reset shield to full so the new run starts with the barrier up.
        shieldHp         = DraftCombat.SHIELD_MAX_HP
        shieldRecharging = false
        shieldUiPctLast  = -1
        // Reset any active buff from the previous run.
        activeBuffTimer     = 0f
        activeBuffDamageMul = 1f
        buffUiSecLast       = -1
        // Push initial HUD state.
        uiHandler.post {
            hud.refreshAllAbilities(energy)
            hud.refreshScore(0)
            hud.refreshHp(effectiveBaseHp)
            hud.refreshEnergy(energy, DraftCombat.ENERGY_MAX)
            hud.refreshMissionLabel(mission.name)
            hud.refreshWaveLabel("Волна 1/${mission.waves.size}")
            hud.refreshShield(shieldHp, DraftCombat.SHIELD_MAX_HP)
            hud.refreshBuff(activeBuffTimer, activeBuffDamageMul)
            hud.announceWave(1, mission.waves.size)
        }
        gameState = GameState.PLAYING
        ensureTicking()
        scheduleTick()
    }

    /**
     * Transition out of a mission. `clearScene = true` (called from goToMenu)
     * wipes asteroids/effects/particles so the menu shows an empty platform;
     * `clearScene = false` (called from showMissionSelect on abort) leaves
     * the in-flight state in place — it freezes once `gameState` flips out
     * of PLAYING, and is cleared on next `startMission` anyway.
     */
    fun stopMission(clearScene: Boolean = false) {
        gameState = GameState.MENU
        if (clearScene) {
            currentMission = null
            clearCombatScene()
        }
    }

    /**
     * Wipe enemies + projectiles + VFX without touching mission state. Used
     * by overlays that show the base as a calm "construction" preview
     * (e.g. База after a Win) — the player keeps seeing the platform and
     * turrets, but no asteroids or mid-flight bullets, regardless of how
     * the previous mission ended.
     */
    fun clearCombatScene() {
        effects.clear()
        asteroids.clear()
        flashes.clear()
        fireballs.clear()
        sparkParticles.clear()
        smokeParticles.clear()
        debrisParticles.clear()
    }

    fun destroy() {
        missionThread?.quitSafely()
        missionThread  = null
        missionHandler = null
    }

    // ------------------------------------------------------------------
    // Public API — UI events forwarded by Activity.
    // ------------------------------------------------------------------

    /**
     * Player tapped a world coordinate (event already converted from screen
     * to world coords by the caller). If the tap hits an asteroid, toggle
     * the priority lock on it; misses are no-ops so accidental taps don't
     * release a deliberate lock.
     */
    fun handleWorldTap(worldX: Float, worldZ: Float) {
        if (gameState != GameState.PLAYING) return
        val picked = pickAsteroidAt(asteroids, worldX, worldZ) ?: return
        // Re-tapping the same locked asteroid releases the priority lock —
        // auto-pick takes over within the central's arc. Tap on a different
        // asteroid sets a new lock.
        centralTargetId = if (centralTargetId == picked.id) null else picked.id
    }

    /**
     * 3D-pivot Phase 2/3: priority-lock by stable asteroid id. The
     * Activity touch handler asks the engine's pickable buffer which
     * SceneObject is under the finger, decodes back to the asteroid id,
     * and routes here. Toggle behaviour matches `handleWorldTap`:
     * tapping the currently-locked asteroid releases the lock, tapping
     * a different one switches.
     */
    fun handleAsteroidPickedById(asteroidId: Long) {
        if (gameState != GameState.PLAYING) return
        // Only honour live asteroids — defensive against the picking
        // buffer being a frame stale and pointing at one that just died.
        val live = asteroids.firstOrNull { it.id == asteroidId && it.hp > 0 } ?: return
        centralTargetId = if (centralTargetId == live.id) null else live.id
    }

    fun handleShieldDown() {
        if (gameState != GameState.PLAYING) return
        shieldRecharging = true
        uiHandler.post { hud.refreshShield(shieldHp, DraftCombat.SHIELD_MAX_HP) }
    }

    fun handleShieldUp() {
        shieldRecharging = false
        uiHandler.post { hud.refreshShield(shieldHp, DraftCombat.SHIELD_MAX_HP) }
    }

    fun handleAbilityTap(slotIndex: Int) {
        if (gameState != GameState.PLAYING) return
        if (slotIndex !in abilitySlots.indices) return
        val slot = abilitySlots[slotIndex]
        if (slot.currentCd > 0f) return
        if (energy < slot.ability.cost) return
        // Marshal to the tick thread so the spawn lands between two ticks
        // atomically with the rest of the simulation (effects/asteroids
        // are mutated on DraftTickThread; activateAbility appends to them).
        missionHandler?.post { activateAbility(slot) }
    }

    // ------------------------------------------------------------------
    // Target selection.
    // ------------------------------------------------------------------

    // Central-turret target selection. Sticky lock: if the current target
    // is still alive, keep firing at it. Otherwise re-pick using the
    // "highest current HP" rule (the asteroid that takes longest to kill
    // is the most pressing sustained threat), tiebreak nearest to pivot.
    // Side effect: stores the new pick into centralTargetId so subsequent
    // frames stay on it until it dies. Clears centralTargetId when the
    // locked asteroid has died and no live targets remain.
    private fun centralTurretTarget(): Asteroid? {
        val px = DraftCombat.CENTRAL_TURRET_X
        val pz = DraftCombat.CENTRAL_TURRET_BASE_Z
        val halfArc = centralWeaponHalfArc(currentWeapon.id)
        // Sticky lock — when the player has priority-locked an asteroid
        // (via tap), it's the master target for BOTH the central turret and
        // any active laser beam, regardless of the central's arc. The
        // central turret will still track and fire only when the target
        // happens to be in arc; the laser (95% arc) may engage even targets
        // outside the central's arc. Lock is released only when the
        // asteroid dies; tapping the same asteroid again toggles it off
        // (handled by handleWorldTap).
        val tid = centralTargetId
        if (tid != null) {
            for (a in asteroids) {
                if (a.id == tid && a.hp > 0) return a
            }
            centralTargetId = null
        }
        // Auto-pick (no priority lock): highest-current-HP candidate inside
        // the central's own arc, tiebreak nearest. Auto-picks are gated by
        // arc — a sticky auto-lock won't form on something the central
        // can't engage.
        val best = bestHpTargetInArc(asteroids, px, pz, halfArc)
        if (best != null) centralTargetId = best.id
        return best
    }

    /**
     * Top N most dangerous asteroids the rocket silo can engage — descending
     * current HP, tiebreak nearest, filtered to the silo's 95% firing arc.
     * Origin is the silo opening, not the central pivot, so a target the
     * central turret can't reach (outside its 80-90% arc) may still be
     * launchable as long as it's inside the silo's wider 95° cone.
     */
    private fun findRocketTargets(maxN: Int): List<Asteroid> {
        if (asteroids.isEmpty() || maxN <= 0) return emptyList()
        val sx = DraftCombat.ROCKET_SILO_X
        val sz = DraftCombat.ROCKET_SILO_Z + DraftCombat.ROCKET_SILO_MUZZLE_OFFSET
        val halfArc = DraftCombat.ARC_ROCKET_HALF_RAD
        return asteroids
            .filter { it.hp > 0 && isWithinArc(it, sx, sz, halfArc) }
            .sortedWith(compareByDescending<Asteroid> { it.hp }.thenBy {
                val ax = it.xPos - sx; val az = it.zPos - sz
                ax * ax + az * az
            })
            .take(maxN)
    }

    /**
     * Dispatch effect first, then spend energy + start cooldown. The
     * dispatch can fail silently (e.g. rocket strike with no asteroids on
     * screen — refund the spend so the player keeps the resource for when
     * targets are available). Returns true if the ability fired.
     */
    private fun activateAbility(slot: AbilitySlot): Boolean {
        val a = slot.ability
        val fired = when (a.id) {
            AbilityId.ROCKET_STRIKE -> {
                val targets = findRocketTargets(DraftCombat.ROCKET_COUNT)
                if (targets.isEmpty()) false
                else { rocketSilo.fire(targets); true }
            }
            AbilityId.LASER_STRIKE  -> {
                // Beam follows the SAME priority target as the central
                // turret. The dome has a wider arc (95% vs 80-90%), so it
                // may engage flank targets the central can't fire at — but
                // both share the master pick. canEngage gates rendering /
                // damage to the laser's own arc, with a slight duration
                // skew if the target sits out of arc for a stretch.
                // Refund only if there's no master target at all.
                if (centralTurretTarget() == null) false
                else {
                    val sx = DraftCombat.LASER_INSTALL_X
                    val sz = DraftCombat.LASER_INSTALL_Z +
                             DraftCombat.LASER_DOME_TOP_OFFSET
                    effects.add(Beam(
                        source       = { Vec3(sx, 0f, sz) },
                        aimSelector  = { centralTurretTarget() },
                        durationSec  = DraftCombat.LASER_BEAM_DURATION_SEC,
                        dps          = DraftCombat.LASER_BEAM_DPS,
                        width        = DraftCombat.LASER_BEAM_WIDTH,
                        color        = DraftCombat.LASER_TINT,
                        canEngage    = { ast ->
                            isWithinArc(ast, sx, sz, DraftCombat.ARC_LASER_HALF_RAD)
                        },
                    ))
                    true
                }
            }
            AbilityId.DRONES        -> {
                // Spawn DRONE_COUNT interceptors around the ship's bow,
                // each with its own continuous laser Beam tied to the drone's
                // position via a closure. canEngage gates damage to range —
                // beams render only when the drone is within attack range
                // of its current target, but the beam keeps existing for
                // the full drone lifetime so the drone can engage new
                // targets across the 10-second window.
                if (asteroids.isEmpty()) false
                else {
                    val n = DraftCombat.DRONE_COUNT
                    val baseX = 0f                                  // ship centreline
                    val baseZ = DraftCombat.CENTRAL_BASE_Z + 0.2f   // just behind central turret
                    // Pick initial nearest asteroid id so the linked Beam
                    // resolves a target on the very first tick (else the
                    // beam aimSelector returns null → beam removed).
                    val initialTargetId = asteroids
                        .filter { it.hp > 0 }
                        .minByOrNull {
                            val dx = it.xPos - baseX
                            val dz = it.zPos - baseZ
                            dx * dx + dz * dz
                        }?.id ?: -1L
                    // Sort live asteroids so multiple drones initially target
                    // DIFFERENT ones when possible (round-robin across the
                    // sorted list), instead of all converging on the single
                    // nearest. Reduces the "drones merge into one model"
                    // effect at swarm spawn. As targets die, drones re-pick
                    // independently in the tick loop.
                    val live = asteroids.filter { it.hp > 0 }
                        .sortedBy {
                            val dx = it.xPos - baseX
                            val dz = it.zPos - baseZ
                            dx * dx + dz * dz
                        }
                    for (i in 0 until n) {
                        val angle = (i.toFloat() / n) * (2.0 * Math.PI).toFloat()
                        val rx = baseX + DraftCombat.DRONE_SPAWN_SPREAD * kotlin.math.cos(angle)
                        val rz = baseZ + DraftCombat.DRONE_SPAWN_SPREAD * kotlin.math.sin(angle)
                        val tid = if (live.isEmpty()) -1L else live[i % live.size].id
                        val drone = Drone(
                            x = rx, y = DraftCombat.DRONE_SPAWN_Y, z = rz,
                            // Mostly upward (vy < 0 = toward camera) with a hint of
                            // radial xz spread so the swarm fans out as it emerges.
                            vx = kotlin.math.cos(angle) * DraftCombat.DRONE_SPEED * 0.3f,
                            vy = -DraftCombat.DRONE_SPEED,
                            vz = kotlin.math.sin(angle) * DraftCombat.DRONE_SPEED * 0.3f,
                            heading = angle,
                            targetId = tid,
                            swarmIndex = i,
                        )
                        drones.add(drone)
                        // Beam closures point back at this specific drone
                        // and resolve target dynamically via drone.targetId.
                        effects.add(Beam(
                            source       = { Vec3(drone.x, drone.y, drone.z) },
                            aimSelector  = {
                                asteroids.firstOrNull { it.id == drone.targetId && it.hp > 0 }
                            },
                            durationSec  = DraftCombat.DRONE_LIFETIME_SEC,
                            dps          = DraftCombat.DRONE_LASER_DPS,
                            width        = DraftCombat.DRONE_LASER_WIDTH,
                            color        = DraftCombat.DRONE_LASER_COLOR,
                            canEngage    = { ast ->
                                val dx = ast.xPos - drone.x
                                val dy = ast.yPos - drone.y
                                val dz = ast.zPos - drone.z
                                val d2 = dx * dx + dy * dy + dz * dz
                                d2 <= DraftCombat.DRONE_ATTACK_RANGE * DraftCombat.DRONE_ATTACK_RANGE
                            },
                        ))
                    }
                    true
                }
            }
        }
        if (!fired) return false
        energy         = (energy - a.cost).coerceAtLeast(0f)
        energyUiLast   = -1                    // force HUD energy refresh next tick
        slot.currentCd = a.cooldownSec
        slot.cdUiLast  = -1
        uiHandler.post {
            hud.refreshEnergy(energy, DraftCombat.ENERGY_MAX)
            hud.refreshAllAbilities(energy)
        }
        return true
    }

    // ------------------------------------------------------------------
    // Tick loop.
    // ------------------------------------------------------------------

    private fun ensureTicking() {
        if (missionThread == null) {
            missionThread  = HandlerThread("DraftTickThread").also { it.start() }
            missionHandler = Handler(missionThread!!.looper)
        }
    }

    private fun scheduleTick() {
        missionHandler?.postDelayed({
            if (gameState != GameState.PLAYING) return@postDelayed
            val dt = TICK_MS / 1000f
            tick(dt)
            onRender()
            checkWinLose()
            scheduleTick()
        }, TICK_MS)
    }

    private fun tick(dt: Float) {
        // Shield: hold-to-recharge. While the player holds the shield
        // button AND has energy AND HP isn't capped, energy drains at
        // SHIELD_RECHARGE_ENERGY_PER_SEC and HP rises proportionally.
        // The ratio is fixed (4× HP per energy point) — easier to read
        // than two separate rates the player has to model. UI refresh
        // is throttled to integer-percent transitions to avoid spam.
        if (shieldRecharging &&
            energy > 0f &&
            shieldHp < DraftCombat.SHIELD_MAX_HP) {
            val maxEnergyDrain = DraftCombat.SHIELD_RECHARGE_ENERGY_PER_SEC * dt
            val maxHpFill      = DraftCombat.SHIELD_MAX_HP - shieldHp
            val maxHpFromEnergy = (DraftCombat.SHIELD_RECHARGE_HP_PER_SEC /
                                   DraftCombat.SHIELD_RECHARGE_ENERGY_PER_SEC) * energy
            val hpAdd  = kotlin.math.min(
                DraftCombat.SHIELD_RECHARGE_HP_PER_SEC * dt,
                kotlin.math.min(maxHpFill, maxHpFromEnergy)
            )
            val energyCost = hpAdd / DraftCombat.SHIELD_RECHARGE_HP_PER_SEC *
                             DraftCombat.SHIELD_RECHARGE_ENERGY_PER_SEC
            shieldHp += hpAdd
            energy   -= energyCost
            if (energy < 0f) energy = 0f
            energyUiLast = -1   // force HUD refresh
        }
        // Recharge VFX — while the button is held AND the shield is
        // up (visible to anchor the sparks against), emit a stream of
        // tangential sparks along the arch. Independent of energy /
        // HP state: the visual cue tells the player "I'm actively
        // shielding right now", which is also when the damage-mul
        // reduction kicks in inside the asteroid-shield collision.
        if (shieldRecharging && shieldHp > 0f) {
            vfx.emitShieldRechargeSparks(dt)
        }
        // Shield-HP UI throttle (separate from recharge — also catches
        // damage absorbs): refresh button text when integer-percent
        // changes.
        run {
            val pct = ((shieldHp / DraftCombat.SHIELD_MAX_HP) * 100f).toInt()
            if (pct != shieldUiPctLast) {
                shieldUiPctLast = pct
                uiHandler.post { hud.refreshShield(shieldHp, DraftCombat.SHIELD_MAX_HP) }
            }
        }

        // Auto-aim. The turret picks the most dangerous live asteroid
        // (with optional priority override from a tap) and rotates to
        // face it. With no targets the barrel returns to vertical.
        val pivotX = DraftCombat.CENTRAL_TURRET_X
        val pivotZ = DraftCombat.CENTRAL_TURRET_BASE_Z
        val centralTarget = centralTurretTarget()
        val centralHalfArc = centralWeaponHalfArc(currentWeapon.id)
        // Raw target angle = where the turret WOULD point at the target
        // if the arc were unbounded. Clamped angle = where it's actually
        // allowed to rotate (within ±halfArc). Aim-alignment compares
        // against raw, so a target that's locked but out of arc never
        // counts as "aligned" → fire gate naturally fails.
        val targetAngleRaw = if (centralTarget != null) {
            val tx = centralTarget.xPos - pivotX
            val tz = kotlin.math.max(0f, centralTarget.zPos - pivotZ)
            if (tx == 0f && tz == 0f) centralTurretAngle
            else kotlin.math.atan2(tx, tz)
        } else 0f
        val targetAngleClamped = targetAngleRaw.coerceIn(-centralHalfArc, centralHalfArc)
        // Smooth tracking toward the CLAMPED angle — turret rotates as
        // far as its arc allows and then sits at the edge, visibly
        // tracking but unable to fire on out-of-arc targets.
        centralTurretAngle += (targetAngleClamped - centralTurretAngle) * 16f * dt

        // Side turret aim — each one tracks the asteroid nearest its
        // own pivot, holds the last angle when the field is empty.
        for (i in turretXs.indices) {
            val tx = turretXs[i]
            val tz = DraftCombat.TURRET_TOP_Z
            val target = nearestAsteroidInArc(
                asteroids, tx, tz, DraftCombat.ARC_SIDE_CANNON_HALF_RAD,
            )
            val sideTargetAng = if (target != null) {
                val dx = target.xPos - tx
                val dz = kotlin.math.max(0f, target.zPos - tz)
                if (dx == 0f && dz == 0f) sideTurretAngles[i]
                else kotlin.math.atan2(dx, dz)
            } else 0f
            sideTurretAngles[i] +=
                (sideTargetAng - sideTurretAngles[i]) * 16f * dt
        }

        // Energy regen. UI text updates only when the integer-floor
        // value changes (regen of 10/sec → ~10 UI updates per second
        // tops, not 60). On ability activation the spend will trigger
        // a UI refresh directly.
        if (energy < DraftCombat.ENERGY_MAX) {
            energy = kotlin.math.min(DraftCombat.ENERGY_MAX,
                                     energy + DraftCombat.ENERGY_REGEN_PER_SEC * dt)
            val ei = energy.toInt()
            if (ei != energyUiLast) {
                energyUiLast = ei
                uiHandler.post {
                    hud.refreshEnergy(energy, DraftCombat.ENERGY_MAX)
                    // Refresh ability buttons too — energy may have just
                    // crossed a cost threshold (disabled→ready transition).
                    hud.refreshAllAbilities(energy)
                }
            }
        }

        // Ability cooldowns. Refresh the button only when the displayed
        // ceiling-second changes, or on the final tick that crosses 0
        // (so the button text flips back to its READY label).
        abilitySlots.forEachIndexed { i, slot ->
            if (slot.currentCd > 0f) {
                slot.currentCd = kotlin.math.max(0f, slot.currentCd - dt)
                val sec = if (slot.currentCd > 0f)
                    kotlin.math.ceil(slot.currentCd.toDouble()).toInt()
                else 0
                if (sec != slot.cdUiLast) {
                    slot.cdUiLast = sec
                    uiHandler.post { hud.refreshAbility(i, energy) }
                }
            }
        }

        // Active buff (single slot) — countdown + auto-clear.
        if (activeBuffTimer > 0f) {
            activeBuffTimer -= dt
            if (activeBuffTimer <= 0f) {
                activeBuffTimer = 0f
                activeBuffDamageMul = 1f
                buffUiSecLast = -1
                uiHandler.post { hud.refreshBuff(activeBuffTimer, activeBuffDamageMul) }
            } else {
                val sec = kotlin.math.ceil(activeBuffTimer.toDouble()).toInt()
                if (sec != buffUiSecLast) {
                    buffUiSecLast = sec
                    uiHandler.post { hud.refreshBuff(activeBuffTimer, activeBuffDamageMul) }
                }
            }
        }

        // Central-turret cooldown — counts down every tick. The turret
        // fires when it has a target, the cooldown is up, and the barrel
        // has rotated close enough to the target direction
        // (AIM_ALIGN_THRESHOLD_RAD). The align gate prevents off-target
        // shots during the first frames after a target switch, while the
        // exponential rotation catches up. Active buff multiplies the
        // weapon's per-shot damage.
        val weapon = currentWeapon
        val weaponDamage = (effectiveMainWeaponDamage * weapon.damageMultiplier * activeBuffDamageMul).toInt()
        if (centralFireCooldown > 0f) {
            centralFireCooldown -= dt
            if (centralFireCooldown < 0f) centralFireCooldown = 0f
        }
        val aimAligned = kotlin.math.abs(targetAngleRaw - centralTurretAngle) <
                         DraftCombat.AIM_ALIGN_THRESHOLD_RAD
        if (centralTarget != null && centralFireCooldown <= 0f && aimAligned) {
            centralFireCooldown = weapon.fireIntervalSec
            val ang = centralTurretAngle
            val sinA = kotlin.math.sin(ang)
            val cosA = kotlin.math.cos(ang)
            val muzzleR = DraftCombat.CENTRAL_TURRET_HALF_H * 2f
            val muzzleX = pivotX + sinA * muzzleR
            val muzzleZ = pivotZ + cosA * muzzleR
            // Heavy cannon → Bullet_Heavy.glb (chunky shell), automatic →
            // Bullet.glb (slim round). aoeRadius is the fire-mode tell —
            // only the heavy cannon ships AoE.
            val bulletMesh = if (weapon.aoeRadius > 0f) bulletHeavyMeshHandle
                             else                       bulletMeshHandle
            val centralBehaviour: ProjectileBehavior =
                if (weapon.aoeRadius > 0f)
                    HeavyShellBehavior(
                        aoeRadius = weapon.aoeRadius,
                        aoeDamage = (weaponDamage * weapon.aoeDamageMultiplier).toInt(),
                    )
                else PlainBulletBehavior()
            // 3D-pivot Phase 2/3: aim the bullet at the target's full
            // (x, y, z), not just (x, z). Muzzle stays on the deck
            // plane (Y=0); velocity vector is the unit direction to the
            // asteroid scaled by projectile speed.
            val tdx = centralTarget.xPos - muzzleX
            val tdy = centralTarget.yPos - 0f
            val tdz = centralTarget.zPos - muzzleZ
            val tlen = kotlin.math.sqrt(tdx * tdx + tdy * tdy + tdz * tdz).coerceAtLeast(1e-4f)
            val pSpeed = weapon.projectileSpeed
            effects.add(Projectile(
                x  = muzzleX,
                y  = 0f,
                z  = muzzleZ,
                vx = tdx / tlen * pSpeed,
                vy = tdy / tlen * pSpeed,
                vz = tdz / tlen * pSpeed,
                damage = weaponDamage,
                halfW = weapon.projectileHalfW,
                halfH = weapon.projectileHalfH,
                meshHandle = bulletMesh,
                behaviour  = centralBehaviour,
            ))
            // E12 — Railgun (HEAVY_CANNON) gets the lightning discharge
            // muzzle stack: bright cyan-white core flash + 5-7
            // procedural electric arcs perpendicular to the barrel +
            // cyan sparks. Other weapons (Автомат, future additions)
            // keep the warm cone-trefoil muzzle blast — the asymmetry
            // makes the player's primary railgun read as a unique,
            // higher-tier weapon.
            if (weapon.id == WeaponId.HEAVY_CANNON) {
                vfx.spawnRailgunMuzzle(muzzleX, muzzleZ, sinA, cosA,
                                   weapon.projectileHalfW)
                vfx.spawnRailgunSparks(muzzleX, muzzleZ,
                                   sinA * weapon.projectileSpeed,
                                   cosA * weapon.projectileSpeed)
            } else {
                // Muzzle blast — cannon-with-brake shape (central pop +
                // forward plume + 2 perpendicular vents). Sized by the
                // weapon's projectile half-width so the heavy cannon pops
                // bigger than the automatic.
                vfx.spawnMuzzleBlast(muzzleX, muzzleZ, sinA, cosA,
                                 weapon.projectileHalfW,
                                 DraftCombat.FLASH_TINT_MUZZLE)
                // E9 — micro-sparks fanning out of the barrel along the
                // bullet velocity. Brief (~0.1s) so they punctuate the
                // shot without obscuring the muzzle blast cluster.
                vfx.spawnMuzzleSparks(muzzleX, muzzleZ,
                                  sinA * weapon.projectileSpeed,
                                  cosA * weapon.projectileSpeed)
            }
        }
        // Turrets fire at the nearest asteroid (if any).
        for (i in turretXs.indices) {
            turretFireT[i] += dt
            while (turretFireT[i] >= DraftCombat.FIRE_INTERVAL_SEC) {
                turretFireT[i] -= DraftCombat.FIRE_INTERVAL_SEC
                val tx = turretXs[i]
                val tz = DraftCombat.TURRET_TOP_Z
                val target = nearestAsteroidInArc(
                    asteroids, tx, tz, DraftCombat.ARC_SIDE_CANNON_HALF_RAD,
                ) ?: continue
                val dx = target.xPos - tx
                val dz = target.zPos - tz
                val len = kotlin.math.sqrt(dx * dx + dz * dz)
                if (len < 1e-4f) continue
                val nx = dx / len
                val nz = dz / len
                // Muzzle = tip of the rotated barrel (housing + barrel +
                // muzzle ring). Bullet emerges along the barrel axis, not
                // straight up like the legacy quad-square layout.
                val muzzleX = tx + nx * DraftCombat.SIDE_TOTAL_LEN
                val muzzleZ = tz + nz * DraftCombat.SIDE_TOTAL_LEN
                // Side turrets fire heavy AoE shells matching the central
                // HEAVY_CANNON weapon profile — chunky projectile, slow
                // muzzle velocity, splash damage to nearby asteroids. The
                // cadence is paced by FIRE_INTERVAL_SEC (1 shot/sec) so
                // they read as supporting artillery rather than a stream
                // of small projectiles.
                val sideDamage = (effectiveTurretDamage * DraftCombat.SIDE_DAMAGE_MUL).toInt()
                // 3D-pivot Phase 2/3: aim at target's full (x, y, z).
                val tdx = target.xPos - muzzleX
                val tdy = target.yPos - 0f
                val tdz = target.zPos - muzzleZ
                val tlen = kotlin.math.sqrt(tdx * tdx + tdy * tdy + tdz * tdz).coerceAtLeast(1e-4f)
                val sideSpeed = DraftCombat.SIDE_BULLET_SPEED
                effects.add(Projectile(
                    x  = muzzleX, y = 0f, z = muzzleZ,
                    vx = tdx / tlen * sideSpeed,
                    vy = tdy / tlen * sideSpeed,
                    vz = tdz / tlen * sideSpeed,
                    damage     = sideDamage,
                    halfW      = DraftCombat.SIDE_BULLET_HALF_W,
                    halfH      = DraftCombat.SIDE_BULLET_HALF_H,
                    meshHandle = bulletHeavyMeshHandle,
                    behaviour  = HeavyShellBehavior(
                        aoeRadius = DraftCombat.SIDE_AOE_RADIUS,
                        aoeDamage = (sideDamage * DraftCombat.SIDE_AOE_DAMAGE_MUL).toInt(),
                    ),
                ))
                // Side-turret muzzle blast — same cannon-with-brake
                // cluster as the central turret, sized by the bullet's
                // half-width (so an upgraded chunkier projectile gets a
                // bigger pop without per-turret-type flags).
                vfx.spawnMuzzleBlast(muzzleX, muzzleZ, nx, nz,
                                 DraftCombat.SIDE_BULLET_HALF_W,
                                 DraftCombat.FLASH_TINT_MUZZLE)
                // E9 — side turrets get the same micro-spark fan; the
                // sparks tell "this is a turret shot" identically across
                // central and side, the muzzle-blast size tells projectile
                // weight.
                vfx.spawnMuzzleSparks(muzzleX, muzzleZ,
                                  nx * DraftCombat.BULLET_SPEED,
                                  nz * DraftCombat.BULLET_SPEED)
            }
        }
        // Drain the rocket silo's launch queue — pops one rocket per
        // tick once the previous one has cleared the tube (transitioned
        // out of ASCENDING). Activated salvos enter the queue inside
        // activateAbility; the rocket-strike button stays usable
        // immediately, but the rockets themselves leave the silo
        // sequentially.
        rocketSilo.tick()

        // E19 — drones AI tick. MUST run BEFORE the effects iterator below:
        // each drone updates its targetId (sticky lock; re-pick nearest if
        // current dies) so the linked Beam's aimSelector closure resolves
        // to a live asteroid this frame instead of returning null and
        // self-destructing the beam.
        val droneIter = drones.iterator()
        while (droneIter.hasNext()) {
            val d = droneIter.next()
            d.lifeRemaining -= dt
            if (d.lifeRemaining <= 0f) { droneIter.remove(); continue }
            // Re-pick target if current is dead / missing.
            val current = asteroids.firstOrNull { it.id == d.targetId && it.hp > 0 }
            val target = current ?: run {
                val nearest = asteroids.filter { it.hp > 0 }
                    .minByOrNull {
                        val dx = it.xPos - d.x
                        val dy = it.yPos - d.y
                        val dz = it.zPos - d.z
                        dx * dx + dy * dy + dz * dz
                    }
                d.targetId = nearest?.id ?: -1L
                nearest
            }
            // Steer toward an offset orbit point around the target — each
            // drone has a unique angular offset (swarmIndex × 2π/N) so the
            // swarm forms a small ring around the asteroid instead of
            // stacking at the same xyz. Offset distance is KEEP_DISTANCE
            // which keeps drones outside the asteroid hull.
            if (target != null) {
                val n = DraftCombat.DRONE_COUNT
                val offsetAngle = d.swarmIndex.toFloat() * (2.0 * Math.PI / n).toFloat()
                val r = DraftCombat.DRONE_KEEP_DISTANCE
                val orbitX = kotlin.math.cos(offsetAngle) * r
                val orbitY = 0.15f                                  // slight vertical lift so drones aren't all on a plane
                val orbitZ = kotlin.math.sin(offsetAngle) * r
                val desiredX = (target.xPos + orbitX) - d.x
                val desiredY = (target.yPos + orbitY) - d.y
                val desiredZ = (target.zPos + orbitZ) - d.z
                val len = kotlin.math.sqrt(desiredX * desiredX + desiredY * desiredY + desiredZ * desiredZ)
                if (len > 1e-3f) {
                    val nx = desiredX / len
                    val ny = desiredY / len
                    val nz = desiredZ / len
                    // Speed scales with how far we are from the orbit point —
                    // close to the orbit slot, slow down so drones don't
                    // overshoot back and forth. Far away → full speed.
                    val approachSpeed =
                        DraftCombat.DRONE_SPEED * (len / r).coerceAtMost(1f).coerceAtLeast(0.2f)
                    val k = (DraftCombat.DRONE_TURN_RATE * dt).coerceAtMost(1f)
                    d.vx = (d.vx * (1f - k) + nx * approachSpeed * k)
                    d.vy = (d.vy * (1f - k) + ny * approachSpeed * k)
                    d.vz = (d.vz * (1f - k) + nz * approachSpeed * k)
                    val vlen = kotlin.math.sqrt(d.vx * d.vx + d.vy * d.vy + d.vz * d.vz)
                    if (vlen > DraftCombat.DRONE_SPEED) {
                        val s = DraftCombat.DRONE_SPEED / vlen
                        d.vx *= s; d.vy *= s; d.vz *= s
                    }
                }
            }
            // Advance position.
            d.x += d.vx * dt
            d.y += d.vy * dt
            d.z += d.vz * dt
            // Heading for rendering — yaw matches xz velocity. Mesh authored
            // with +Z as forward axis, so rotationY = atan2(vx, vz) with no
            // model offset (same convention as the homing rocket).
            if (d.vx * d.vx + d.vz * d.vz > 1e-4f) {
                d.heading = kotlin.math.atan2(d.vx, d.vz)
            }
        }

        // Tick all active weapon effects (projectiles + beams + future
        // shockwaves / EMP / etc.). Each effect's tick() owns its own
        // movement, collision, damage and lifetime; returns true to be
        // removed. Order after drones above so beam aimSelectors see the
        // freshly-updated drone.targetId values.
        val effectIter = effects.iterator()
        while (effectIter.hasNext()) {
            if (effectIter.next().tick(dt, weaponCtx)) effectIter.remove()
        }

        val deadAsteroids = asteroids.filter { it.hp <= 0 }
        val killed = deadAsteroids.size
        if (killed > 0) {
            // Per-asteroid death effects: NORMAL/FAST/HEAVY drop a small flash;
            // EXPLOSIVE deals AoE damage to neighbours + spawns a bigger flash;
            // ENERGY triggers the main-weapon damage buff. Effects are applied
            // BEFORE removing the dead asteroids so EXPLOSIVE chains don't
            // double-fire on already-dead targets.
            var triggeredBuff = false
            for (a in deadAsteroids) {
                when (a.type) {
                    AsteroidType.EXPLOSIVE -> {
                        val r2 = DraftCombat.EXPLOSIVE_AOE_RADIUS *
                                DraftCombat.EXPLOSIVE_AOE_RADIUS
                        for (other in asteroids) {
                            if (other === a || other.hp <= 0) continue
                            val dxA = other.xPos - a.xPos
                            val dzA = other.zPos - a.zPos
                            if (dxA * dxA + dzA * dzA <= r2) {
                                other.hp -= DraftCombat.EXPLOSIVE_AOE_DAMAGE
                            }
                        }
                        vfx.spawnExplosion(a.xPos, a.yPos, a.zPos, DraftCombat.EXPLOSIVE_AOE_RADIUS)
                    }
                    AsteroidType.ENERGY -> {
                        triggeredBuff = true
                        val et = DraftCombat.FLASH_TINT_ENERGY
                        flashes.add(Flash(
                            x = a.xPos, y = a.yPos, z = a.zPos,
                            life = DraftCombat.FLASH_LIFE_SEC,
                            maxLife = DraftCombat.FLASH_LIFE_SEC,
                            halfMax = DraftCombat.FLASH_HALF * 1.5f,
                            tintR = et[0], tintG = et[1], tintB = et[2], tintA = et[3],
                        ))
                    }
                    AsteroidType.NORMAL,
                    AsteroidType.FAST,
                    AsteroidType.HEAVY -> {
                        val dt2 = DraftCombat.FLASH_TINT_DEATH
                        flashes.add(Flash(
                            x = a.xPos, y = a.yPos, z = a.zPos,
                            life = DraftCombat.FLASH_LIFE_SEC,
                            maxLife = DraftCombat.FLASH_LIFE_SEC,
                            tintR = dt2[0], tintG = dt2[1], tintB = dt2[2], tintA = dt2[3],
                        ))
                        // E9 — debris + smoke. HEAVY gets a darker/redder
                        // tint matching its dark-red mesh; NORMAL/FAST are
                        // neutral warm gray. Sized by asteroid half so
                        // small fast asteroids don't drop boulder chunks.
                        val tint = when (a.type) {
                            AsteroidType.HEAVY -> floatArrayOf(0.85f, 0.55f, 0.50f)
                            else               -> floatArrayOf(0.95f, 0.92f, 0.88f)
                        }
                        vfx.spawnAsteroidDeathFX(a.xPos, a.yPos, a.zPos, tint)
                    }
                }
            }
            if (triggeredBuff) {
                activeBuffTimer     = DraftCombat.ENERGY_BUFF_DURATION
                activeBuffDamageMul = DraftCombat.ENERGY_BUFF_DAMAGE_MUL
                buffUiSecLast = -1
                uiHandler.post { hud.refreshBuff(activeBuffTimer, activeBuffDamageMul) }
            }
            missionRun.score += killed * 10
            missionRun.asteroidsDestroyed += killed
            missionRun.metalEarned += killed   // +1 metal per asteroid
            progressRepo.update { it.copy(metal = it.metal + killed) }
            uiHandler.post { hud.refreshScore(missionRun.score) }
        }
        asteroids.removeAll { it.hp <= 0 }

        // Age flashes; cull when life expires.
        val flashIter = flashes.iterator()
        while (flashIter.hasNext()) {
            val f = flashIter.next()
            f.life -= dt
            if (f.life <= 0f) flashIter.remove()
        }
        // E7.1 — same lifecycle for 3D fireballs.
        // E10.3 — snapshot prevLife before tick so buildScene can
        // reconstruct last frame's scale curve for motion-vector input.
        val fireballIter = fireballs.iterator()
        while (fireballIter.hasNext()) {
            val fb = fireballIter.next()
            fb.prevLife = fb.life
            fb.life -= dt
            if (fb.life <= 0f) fireballIter.remove()
        }
        // E9 — particle pools. Same physics across pools (Euler step
        // + drag + gravity), only the pipeline binding differs.
        tickParticles(sparkParticles,  dt)
        tickParticles(smokeParticles,  dt)
        tickParticles(debrisParticles, dt)

        // Move asteroids down at their own speed (mission baseline × type
        // multiplier, captured at spawn); spin around their own axis.
        for (a in asteroids) {
            // E10.3 — snapshot prev z + rotation before mutating so the
            // motion-vector at this frame's render reads as one tick of
            // fall + spin (correct delta for motion blur). 3D pivot
            // Phase 1: also track prevY so motion-blur catches the
            // depth approach.
            a.prevZ        = a.zPos
            a.prevY        = a.yPos
            a.prevRotation = a.rotation
            a.zPos     -= a.speed * dt
            a.yPos     -= a.depthSpeed * dt
            a.rotation += a.rotationSpeed * dt
        }
        // Asteroid hit detection — shield first (asteroid breaks ON the
        // arch, never reaches platform if shield is up and the asteroid
        // is under the dome's X coverage), then platform (asteroid
        // landed past where the shield could intercept).
        var platformDamage = 0
        val asteroidIter = asteroids.iterator()
        while (asteroidIter.hasNext()) {
            val a = asteroidIter.next()
            // 1. Shield-dome contact — 3D check (asteroid centre inside the
            //    dome's hemispherical volume). The dome's footprint at deck
            //    level is the half-superellipse |x/a|^n + |z/b|^n ≤ 1, z≥0;
            //    each height y within [0, SHIELD_DOME_HEIGHT] has a smaller
            //    footprint scaled by sqrt(1 − (y/H)²). Asteroids descend
            //    through y ∈ [0, ASTEROID_SPAWN_Y_DEPTH] in concert with
            //    z ∈ [ASTEROID_SPAWN_Z, PLATFORM_TOP_Z], so their trajectory
            //    pierces the dome surface mid-air rather than at deck level
            //    — which is exactly what the visual demands now that the
            //    dome is a real 3D bubble instead of a flat 2D arch.
            if (shieldHp > 0f) {
                val H = DraftCombat.SHIELD_DOME_HEIGHT
                val baseZ = DraftCombat.PLATFORM_TOP_Z +
                            DraftCombat.SHIELD_ARCH_LIFT_FRAC *
                                DraftCombat.SHIELD_ARCH_HALF_H
                // Dome SceneObject sits at world y = SHIELD_DOME_LIFT_Y so its
                // base ring just clears the deck top. We undo that lift to
                // get mesh-local Y for the cross-section formula.
                val meshY = a.yPos - DraftCombat.SHIELD_DOME_LIFT_Y
                if (meshY in 0f..H) {
                    val tv = meshY / H
                    val scale = kotlin.math.sqrt((1f - tv * tv).coerceAtLeast(0f))
                    if (scale > 0f) {
                        val halfW = DraftCombat.SHIELD_ARCH_HALF_W * scale
                        val halfH = DraftCombat.SHIELD_ARCH_HALF_H * scale
                        val zLocal = a.zPos - baseZ
                        if (zLocal in 0f..halfH) {
                            val xRatio = kotlin.math.abs(a.xPos) / halfW
                            val zRatio = zLocal / halfH
                            val n = DraftCombat.SHIELD_ARCH_SHARPNESS
                            val inside = xRatio.pow(n) + zRatio.pow(n) <= 1f
                            if (inside) {
                                // Asteroid centre is inside the dome volume —
                                // it pierced the outer surface this tick. Same
                                // damage routing as before; flash now placed at
                                // the asteroid's actual 3D contact position so
                                // the explosion is anchored to the dome surface
                                // it broke against (not at deck level).
                                val dmgF = a.platformDmg.toFloat() * (
                                    if (shieldRecharging) DraftCombat.SHIELD_RECHARGE_DAMAGE_MUL
                                    else 1f
                                )
                                if (shieldHp >= dmgF) {
                                    shieldHp -= dmgF
                                } else {
                                    val overflow = (dmgF - shieldHp).toInt().coerceAtLeast(1)
                                    shieldHp = 0f
                                    platformDamage += overflow
                                }
                                val sh = DraftCombat.FLASH_TINT_SHIELD
                                flashes.add(Flash(
                                    x = a.xPos, y = a.yPos, z = a.zPos,
                                    life = DraftCombat.FLASH_LIFE_SEC,
                                    maxLife = DraftCombat.FLASH_LIFE_SEC,
                                    tintR = sh[0], tintG = sh[1], tintB = sh[2], tintA = sh[3],
                                ))
                                asteroidIter.remove()
                                continue
                            }
                        }
                    }
                }
            }
            // 2. Platform hit — shield is down or asteroid fell past the
            //    shield's X coverage (edge of platform, beyond the arch).
            if (a.zPos - a.half <= DraftCombat.PLATFORM_TOP_Z) {
                platformDamage += a.platformDmg
                asteroidIter.remove()
            }
        }
        if (platformDamage > 0) {
            platformHP -= platformDamage
            uiHandler.post {
                hud.refreshHp(platformHP)
                hud.pulseBaseDamage()
            }
        }
        // Safety net: anything else below the screen.
        asteroids.removeAll { it.zPos < DraftCombat.SCREEN_BOTTOM_Z - 1f }

        // Wave-based spawning. The mission has a list of waves; each wave spawns
        // asteroidCount asteroids at spawnIntervalSec, then waits for them to be
        // gone, then a 2 sec break, then the next wave starts.
        val mission = currentMission
        if (mission != null) {
            if (waveBreakTimer > 0f) {
                waveBreakTimer -= dt
                if (waveBreakTimer <= 0f) {
                    waveBreakTimer = 0f
                    currentWaveIndex++
                    currentWaveSpawned = 0
                    spawnTimer = 0f
                    missionRun.currentWaveDisplay = currentWaveIndex + 1
                    val display = missionRun.currentWaveDisplay
                    val total   = mission.waves.size
                    uiHandler.post {
                        hud.refreshWaveLabel("Волна $display/$total")
                        hud.announceWave(display, total)
                    }
                }
            } else if (currentWaveIndex < mission.waves.size) {
                val wave = mission.waves[currentWaveIndex]
                if (currentWaveSpawned < wave.asteroidCount) {
                    spawnTimer += dt
                    while (spawnTimer >= wave.spawnIntervalSec &&
                           currentWaveSpawned < wave.asteroidCount) {
                        spawnTimer -= wave.spawnIntervalSec
                        val type = pickAsteroidType(wave.typeWeights)
                        val half = DraftCombat.ASTEROID_HALF * type.halfMul
                        val margin = half
                        val xMin = -DraftCombat.SCREEN_HALF_W + margin
                        val xMax =  DraftCombat.SCREEN_HALF_W - margin
                        val rx   = xMin + Math.random().toFloat() * (xMax - xMin)
                        // Random spin: ±0.5..±2.0 rad/sec, random starting phase.
                        val spinSign = if (Math.random() < 0.5) -1f else 1f
                        val spin     = spinSign * (0.5f + Math.random().toFloat() * 1.5f)
                        val phase    = (Math.random() * Math.PI * 2).toFloat()
                        // Pick mesh by type. NORMAL/FAST randomize between
                        // two grey variants so common waves don't look like
                        // copy-pastes; HEAVY/EXPLOSIVE/ENERGY get unique
                        // silhouettes + tints.
                        val mesh = when (type) {
                            AsteroidType.HEAVY     -> asteroidMeshHeavy
                            AsteroidType.EXPLOSIVE -> asteroidMeshExplosive
                            AsteroidType.ENERGY    -> asteroidMeshEnergy
                            AsteroidType.NORMAL,
                            AsteroidType.FAST      ->
                                if (Math.random() < 0.5) asteroidMeshGrey1 else asteroidMeshGrey2
                        }
                        val hpVal = (mission.asteroidHp * type.hpMul).toInt()
                            .coerceAtLeast(1)
                        // 3D-pivot Phase 1: pick depthSpeed so the
                        // asteroid reaches yPos = 0 at the same instant
                        // its zPos hits PLATFORM_TOP_Z. Spawn at
                        // ASTEROID_SPAWN_Z (lower than SCREEN_TOP_Z —
                        // see Combat.kt comment about FOV cone).
                        val zPosSpawn = DraftCombat.ASTEROID_SPAWN_Z
                        val zFall     = zPosSpawn - DraftCombat.PLATFORM_TOP_Z
                        val yFall     = DraftCombat.ASTEROID_SPAWN_Y_DEPTH
                        val asteroidSpeed = mission.asteroidSpeed * type.speedMul
                        val depthSpeed = if (zFall > 0f) asteroidSpeed * yFall / zFall else 0f
                        asteroids.add(
                            Asteroid(
                                id    = newAsteroidId(),
                                xPos  = rx,
                                yPos  = DraftCombat.ASTEROID_SPAWN_Y_DEPTH,
                                zPos  = zPosSpawn,
                                hp    = hpVal,
                                maxHp = hpVal,
                                rotation      = phase,
                                rotationSpeed = spin,
                                type          = type,
                                speed         = asteroidSpeed,
                                depthSpeed    = depthSpeed,
                                half          = half,
                                platformDmg   = (DraftCombat.PLATFORM_DMG_PER_HIT * type.platformDmgMul)
                                                   .toInt().coerceAtLeast(1),
                                meshHandle    = mesh,
                            )
                        )
                        currentWaveSpawned++
                    }
                } else if (asteroids.isEmpty()) {
                    // Wave fully spawned and cleared — start break unless this is the last.
                    if (currentWaveIndex + 1 < mission.waves.size) {
                        waveBreakTimer = DraftCombat.WAVE_BREAK_SEC
                    }
                    // If this was the last wave, checkWinLose handles it.
                }
            }
        }
    }

    // Win / lose checks. Win: last wave fully spawned AND no asteroids left.
    private fun checkWinLose() {
        val m = currentMission
        val onLastWave = m != null && currentWaveIndex == m.waves.size - 1
        val lastWaveSpawnedOut = m != null &&
            currentWaveSpawned >= m.waves[currentWaveIndex.coerceAtMost(m.waves.size - 1)].asteroidCount
        when {
            platformHP <= 0 -> showLose()
            onLastWave && lastWaveSpawnedOut && asteroids.isEmpty() -> showWin()
        }
    }

    private fun showWin() {
        gameState = GameState.WON
        // Win bonus: +20 metal, awarded once per victory.
        missionRun.winBonus = 20
        missionRun.metalEarned += missionRun.winBonus
        progressRepo.update { it.copy(metal = it.metal + missionRun.winBonus) }
        resetTurretAngles()
        onRender()   // tick is about to stop — repaint once so the reset shows
        uiHandler.post { host.onMissionWon() }
    }

    private fun showLose() {
        gameState = GameState.LOST
        resetTurretAngles()
        onRender()
        uiHandler.post { host.onMissionLost() }
    }

    /**
     * Snap the central + side turrets to vertical (0 rad). Called when a
     * mission ends regardless of outcome — without it the barrels would
     * freeze pointing at whatever asteroid was being tracked at the
     * decisive moment, which reads as "still aiming" on the win/lose
     * screen.
     */
    private fun resetTurretAngles() {
        centralTurretAngle = 0f
        for (i in sideTurretAngles.indices) sideTurretAngles[i] = 0f
    }

    // ------------------------------------------------------------------
    // Spring-launched rocket silo. fire() doesn't immediately spawn rockets;
    // it queues them. Each tick, if no rocket is currently in the tube
    // (i.e. nothing in ASCENDING phase), the next queued target is
    // launched: the rocket emerges from the silo opening rising straight
    // up. Once the rocket has climbed by ROCKET_ASCENT_HEIGHT it transitions
    // to FLYING (engine ignites, homing kicks in) — and only then does the
    // next rocket pop from the queue. Sequential, never two rockets
    // sharing the tube.
    //
    // Lives as an inner class so it captures MissionRunner for `effects`,
    // helpers (vfx.spawnMuzzleBlast), and current upgrade state
    // (effectiveMainWeaponDamage / activeBuffDamageMul read at fire time,
    // not at construction). Future weapon classes (LaserDome, EmpEmitter,
    // ...) will follow the same pattern — each one's fire() locks in its
    // own projectile/effect type.
    // ------------------------------------------------------------------
    private inner class RocketSilo(val xPos: Float, val zPos: Float) {
        // Queue holds target IDs (not Asteroid refs) so dead targets are
        // detected at launch time; a rocket whose target died before it
        // popped just launches and coasts (HomingRocketBehavior handles
        // missing target gracefully).
        private val pending: ArrayDeque<Long> = ArrayDeque()

        fun fire(targets: List<Asteroid>) {
            for (t in targets) pending.addLast(t.id)
        }

        fun tick() {
            if (pending.isEmpty()) return
            if (tubeBlocked()) return
            val targetId = pending.removeFirst()
            launchOne(targetId)
        }

        /** True if there's a rocket still inside the tube (ASCENDING phase). */
        private fun tubeBlocked(): Boolean {
            for (e in effects) {
                if (e is Projectile) {
                    val bh = e.behaviour
                    if (bh is HomingRocketBehavior &&
                        bh.phase == RocketPhase.ASCENDING) {
                        return true
                    }
                }
            }
            return false
        }

        private fun launchOne(targetId: Long) {
            val baseDmg = effectiveMainWeaponDamage *
                          DraftCombat.ROCKET_DAMAGE_MUL *
                          activeBuffDamageMul
            val rocketDmg = baseDmg.toInt().coerceAtLeast(1)
            val aoeDmg    = (rocketDmg * DraftCombat.ROCKET_AOE_DAMAGE_MUL)
                              .toInt().coerceAtLeast(1)
            // Spawn with the rocket's BASE at the silo opening; mesh is
            // origin-at-centre so we offset the spawn Z by half-length.
            val spawnZ = zPos + DraftCombat.ROCKET_BODY_LENGTH * 0.5f
            effects.add(Projectile(
                x = xPos, z = spawnZ,
                vx = 0f,
                vz = DraftCombat.ROCKET_ASCENT_SPEED,
                damage     = rocketDmg,
                halfW      = DraftCombat.ROCKET_HALF_W,
                halfH      = DraftCombat.ROCKET_HALF_H,
                meshHandle = rocketMeshHandle,
                modelScale = 1f,                // procedural, world-unit authored
                modelYawOffset = 0f,            // +Z = forward, no offset
                behaviour = HomingRocketBehavior(
                    targetId     = targetId,
                    turnRate     = DraftCombat.ROCKET_TURN_RATE_RAD_PER_SEC,
                    aoeRadius    = DraftCombat.ROCKET_AOE_RADIUS,
                    aoeDamage    = aoeDmg,
                    cruiseSpeed  = DraftCombat.ROCKET_CRUISE_SPEED,
                    boostAccel   = DraftCombat.ROCKET_BOOST_ACCEL,
                    ascentHeight = DraftCombat.ROCKET_ASCENT_HEIGHT,
                    ascentSpeed  = DraftCombat.ROCKET_ASCENT_SPEED,
                    launchZ      = spawnZ,
                ),
            ))
            // Spring-launch dust puff at the silo opening — radial smoke
            // cloud, NOT a gunshot muzzle blast (rockets are mechanically
            // ejected, ignition happens later in the FLYING phase).
            vfx.spawnRocketLaunchPuff(xPos, 0f, zPos)
        }
    }

    companion object {
        private const val TICK_MS = 20L
    }
}
