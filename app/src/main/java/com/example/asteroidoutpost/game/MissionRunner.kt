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
import com.example.asteroidoutpost.game.combat.ShieldImpact
import com.example.asteroidoutpost.game.combat.HeavyShellBehavior
import com.example.asteroidoutpost.game.combat.EnemyBolt
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
import com.example.asteroidoutpost.game.content.CENTRAL_CANNON_Z_ABOVE_PLATFORM
import com.example.asteroidoutpost.game.content.TURRET_CANNON_LENGTH
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
    private var bulletRailgunMeshHandle: Long = 0L
    private var asteroidMeshGrey1:       Long = 0L
    private var asteroidMeshGrey2:       Long = 0L
    private var asteroidMeshHeavy:       Long = 0L
    private var asteroidMeshExplosive:   Long = 0L
    private var asteroidMeshEnergy:      Long = 0L
    private var enemyShipMeshHandle:     Long = 0L

    fun attachAssets(
        hud: HudView,
        vfx: VfxSpawner,
        rocketMeshHandle: Long,
        bulletMeshHandle: Long,
        bulletHeavyMeshHandle: Long,
        bulletRailgunMeshHandle: Long,
        asteroidMeshGrey1: Long,
        asteroidMeshGrey2: Long,
        asteroidMeshHeavy: Long,
        asteroidMeshExplosive: Long,
        asteroidMeshEnergy: Long,
        enemyShipMeshHandle: Long,
    ) {
        this.hud = hud
        this.vfx = vfx
        this.rocketMeshHandle      = rocketMeshHandle
        this.bulletMeshHandle        = bulletMeshHandle
        this.bulletHeavyMeshHandle   = bulletHeavyMeshHandle
        this.bulletRailgunMeshHandle = bulletRailgunMeshHandle
        this.asteroidMeshGrey1     = asteroidMeshGrey1
        this.asteroidMeshGrey2     = asteroidMeshGrey2
        this.asteroidMeshHeavy     = asteroidMeshHeavy
        this.asteroidMeshExplosive = asteroidMeshExplosive
        this.asteroidMeshEnergy    = asteroidMeshEnergy
        this.enemyShipMeshHandle   = enemyShipMeshHandle
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
    // E20 — active force-field impacts. SceneAssembler reads (up to
    // SHIELD_MAX_ACTIVE_IMPACTS) entries each frame and packs them into
    // the forcefield draw call's push constants. Tick decrements `life`
    // and removes expired entries.
    val shieldImpacts:     MutableList<ShieldImpact> = mutableListOf()
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
    // Player priority lock — set by tap on an asteroid, cleared by re-tap
    // or by the asteroid dying. Targets laser/rockets/drones (those weapons
    // focus-fire on this asteroid when set); does NOT influence central or
    // side turrets — they remain on pure auto-aim.
    @Volatile private var playerPriorityId: Long? = null
    // Central turret's own sticky auto-aim memory — holds the last
    // highest-HP-in-arc pick so the central doesn't jitter between
    // candidates every frame. Independent from `playerPriorityId`.
    private var centralAutoStickyId: Long? = null
    // Combat-mission enemy ships state. `enemySpawnQueue` is a mutable
    // copy of `mission.enemyShipSpawns` whose `delaySec` is decremented
    // each tick; entries pop when the timer reaches 0 and one ENEMY_SHIP
    // asteroid is spawned. `enemyFireCooldowns` holds per-enemy fire
    // cooldowns keyed by asteroid id — ships fire independently.
    private val enemySpawnQueue: MutableList<EnemyShipSpawn> = mutableListOf()
    private val enemyFireCooldowns: HashMap<Long, Float> = HashMap()
    private var centralFireCooldown: Float = 0f
    private var spawnTimer:          Float = 0f
    private var currentWaveIndex:    Int   = 0
    private var currentWaveSpawned:  Int   = 0
    private var waveBreakTimer:      Float = 0f
    // Route mode (mission.route != null) — `shipPosY` is the SHIP'S
    // ABSOLUTE WORLD Y. Starts at 0, increments by SHIP_CRUISE_SPEED·dt
    // each tick. Asteroids live in their own world coords (placement.absY
    // is their world Y, never mutated). All collision / spawn / despawn
    // / camera-track math uses (asteroid.yPos − shipPosY) for relative
    // distance to the ship.
    //
    // `routeCursor` is the index of the next placement to materialise
    // from MissionRoute.asteroids (sorted by absY ascending).
    //
    // For non-route missions, shipPosY stays 0 and the world matches the
    // legacy stationary-ship layout.
    @Volatile var shipPosY:          Float = 0f
        private set
    private var routeCursor:         Int   = 0
    private var routePctUiLast:      Int   = -1
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
        override val shipPosY: Float            get() = this@MissionRunner.shipPosY
        override fun damageAsteroid(a: Asteroid, amount: Int) {
            if (a.shieldHpMax > 0 && a.shieldHp > 0) {
                if (a.shieldHp >= amount) {
                    a.shieldHp -= amount
                } else {
                    val overflow = amount - a.shieldHp
                    a.shieldHp = 0
                    a.hp -= overflow
                }
            } else {
                a.hp -= amount
            }
        }
        override fun shieldImpactAt(x: Float, y: Float, z: Float) {
            // Project the hit point onto the shield sphere surface so the
            // bloom sits on the shell, not floating in air.
            val cx = 0f
            val cy = this@MissionRunner.shipPosY
            val cz = DraftCombat.SHIELD_CENTER_Z
            val r  = DraftCombat.SHIELD_HEMISPHERE_RADIUS
            val dx = x - cx; val dy = y - cy; val dz = z - cz
            val d2 = dx * dx + dy * dy + dz * dz
            val dist = kotlin.math.sqrt(d2.coerceAtLeast(1e-6f))
            val k = r / dist
            val ix = cx + dx * k
            val iy = cy + dy * k
            val iz = cz + dz * k
            if (shieldImpacts.size >= DraftCombat.SHIELD_MAX_ACTIVE_IMPACTS) {
                shieldImpacts.removeAt(0)
            }
            shieldImpacts.add(ShieldImpact(
                x = ix, y = iy, z = iz,
                life = DraftCombat.SHIELD_IMPACT_LIFE_SEC,
            ))
            val sh = DraftCombat.FLASH_TINT_SHIELD
            flashes.add(Flash(
                x = ix, y = iy, z = iz,
                life = DraftCombat.FLASH_LIFE_SEC,
                maxLife = DraftCombat.FLASH_LIFE_SEC,
                tintR = sh[0], tintG = sh[1], tintB = sh[2], tintA = sh[3],
            ))
        }
        override fun damageShip(amount: Int) {
            val dmgF = amount.toFloat() * (
                if (shieldRecharging) DraftCombat.SHIELD_RECHARGE_DAMAGE_MUL
                else 1f
            )
            if (shieldHp >= dmgF) {
                shieldHp -= dmgF
            } else {
                val overflow = (dmgF - shieldHp).toInt().coerceAtLeast(1)
                shieldHp = 0f
                platformHP -= overflow
            }
            uiHandler.post {
                hud.refreshHp(platformHP)
                hud.refreshShield(shieldHp, DraftCombat.SHIELD_MAX_HP)
                hud.pulseBaseDamage()
            }
        }
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
        shipPosY           = 0f
        routeCursor        = 0
        routePctUiLast     = -1
        flashes.clear()
        fireballs.clear()
        shieldImpacts.clear()
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
        playerPriorityId   = null
        centralAutoStickyId = null
        effects.clear()
        asteroids.clear()
        centralFireCooldown = 0f
        spawnTimer    = 0f
        enemySpawnQueue.clear()
        mission.enemyShipSpawns?.let { enemySpawnQueue.addAll(it) }
        enemyFireCooldowns.clear()
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
            if (mission.route != null) {
                hud.refreshWaveLabel("Маршрут 0%")
            } else {
                hud.refreshWaveLabel("Волна 1/${mission.waves.size}")
                hud.announceWave(1, mission.waves.size)
            }
            hud.refreshShield(shieldHp, DraftCombat.SHIELD_MAX_HP)
            hud.refreshBuff(activeBuffTimer, activeBuffDamageMul)
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
        shieldImpacts.clear()
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
        // Toggle player priority lock. Affects laser/rockets/drones target
        // selection; central + side turrets stay on auto-aim either way.
        playerPriorityId = if (playerPriorityId == picked.id) null else picked.id
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
        playerPriorityId = if (playerPriorityId == live.id) null else live.id
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

    /**
     * Returns true if the asteroid is a current threat to the ship — i.e.
     * it's ahead within WEAPON_ENGAGEMENT_RANGE AND its line-of-flight
     * passes through either the shield sphere or the hull AABB. Asteroids
     * that will sail clear past the ship don't trigger auto-fire.
     *
     * Cheap to call once per asteroid per tick.
     */
    private fun isThreatening(a: Asteroid): Boolean {
        // Enemy ship — always a threat while alive, regardless of strict
        // shield/hull intersection: it sits at the lead distance and
        // shoots, so weapons should treat it as the current priority
        // air contact (red bracket frame visible).
        if (a.type == AsteroidType.ENEMY_SHIP) return a.hp > 0
        val forwardDist = a.yPos - shipPosY
        if (forwardDist <= 0f || forwardDist > DraftCombat.WEAPON_ENGAGEMENT_RANGE) return false
        // Shield contact at the moment of pass-through (ship reaches a.yPos):
        // dist² = a.xPos² + (a.zPos − SHIELD_CENTER_Z)² ≤ R²
        val r  = DraftCombat.SHIELD_HEMISPHERE_RADIUS
        val cz = DraftCombat.SHIELD_CENTER_Z
        val dx = a.xPos
        val dz = a.zPos - cz
        if (dx * dx + dz * dz <= r * r) return true
        // Hull AABB (same gates as collision below).
        val hullHalfW = DraftCombat.SCREEN_HALF_W
        return kotlin.math.abs(a.xPos) <= hullHalfW + a.half &&
               a.zPos + a.half >= DraftCombat.HULL_BOTTOM_Z &&
               a.zPos - a.half <= DraftCombat.PLATFORM_TOP_Z
    }

    // Central-turret target selection — pure auto-aim. Sticky on current
    // pick until it dies; then re-pick highest-current-HP candidate inside
    // the central's arc (the asteroid that takes longest to kill is the
    // most pressing sustained threat), tiebreak nearest. Filtered to
    // active threats only — asteroids that will hit shield/hull within
    // WEAPON_ENGAGEMENT_RANGE; pass-bys are ignored.
    // Side-turrets share this same "auto-aim only" philosophy with their
    // own arc check (they don't use this exact function; their loop picks
    // nearest-in-arc each frame, see line 919).
    private fun centralTurretTarget(): Asteroid? {
        val px = DraftCombat.CENTRAL_TURRET_X
        val pz = DraftCombat.CENTRAL_TURRET_BASE_Z
        val halfArc = centralWeaponHalfArc(currentWeapon.id)
        val tid = centralAutoStickyId
        if (tid != null) {
            for (a in asteroids) {
                if (a.id == tid && a.hp > 0) return a
            }
            centralAutoStickyId = null
        }
        val threats = asteroids.filter { isThreatening(it) }
        val best = bestHpTargetInArc(threats, px, pz, halfArc)
        if (best != null) centralAutoStickyId = best.id
        return best
    }

    /**
     * Player priority lock (tap-selected) — null if nothing locked or
     * the locked asteroid is dead. Auto-clears stale ids. This is the
     * asteroid the laser / rockets / drones focus-fire on; UI also draws
     * the green selection frame around it (see SelectionFrameView).
     */
    private fun playerPriorityTarget(): Asteroid? {
        val tid = playerPriorityId ?: return null
        val live = asteroids.firstOrNull { it.id == tid && it.hp > 0 }
        if (live == null) playerPriorityId = null
        return live
    }

    /**
     * Target dispatch for player-influenced weapons (laser/rockets/drones).
     * Player priority lock first; falls back to the central turret's own
     * auto-aim pick when nothing is locked. Returns null only if neither
     * source has a valid target.
     */
    private fun preferredTarget(): Asteroid? =
        playerPriorityTarget() ?: centralTurretTarget()

    /** Public read for UI (selection-frame overlay). */
    fun currentPriorityTarget(): Asteroid? = playerPriorityTarget()

    /**
     * Combat-mission enemy ship lifecycle. Called once per tick from the
     * main loop when `mission.enemyShipDelaySec != null`. Counts down the
     * spawn timer, materialises the enemy as a special-typed asteroid,
     * pins its position at the lead distance ahead of the ship, and
     * fires one EnemyBolt per ENEMY_SHIP_FIRE_INTERVAL_SEC. Win-check
     * (enemy dead) happens later in the regular win/lose section.
     */
    private fun tickEnemyShip(dt: Float, mission: MissionConfig) {
        // 1. Decrement spawn-queue timers; pop spawns whose timer hit 0.
        if (enemySpawnQueue.isNotEmpty()) {
            val it = enemySpawnQueue.listIterator()
            while (it.hasNext()) {
                val spec = it.next()
                val newDelay = spec.delaySec - dt
                if (newDelay <= 0f) {
                    it.remove()
                    spawnEnemyShip(mission, spec)
                } else {
                    it.set(spec.copy(delaySec = newDelay))
                }
            }
        }
        // 2. Tick each living enemy ship — hold station + fire on cadence.
        for (a in asteroids) {
            if (a.type != AsteroidType.ENEMY_SHIP || a.hp <= 0) continue
            // xPos is val (data class); set at spawn from spec.xOffset
            // and stays put. Y/Z update each tick to track the ship.
            a.yPos = shipPosY + DraftCombat.ENEMY_SHIP_LEAD_DISTANCE
            a.zPos = DraftCombat.ENEMY_SHIP_Z
            val cd = (enemyFireCooldowns[a.id] ?: 0f) - dt
            if (cd <= 0f) {
                enemyFireCooldowns[a.id] = cd + DraftCombat.ENEMY_SHIP_FIRE_INTERVAL_SEC
                fireEnemyBolt(a)
            } else {
                enemyFireCooldowns[a.id] = cd
            }
        }
        // 3. Garbage-collect cooldowns for dead enemies so the map
        //    doesn't grow forever (cheap — at most one dead enemy
        //    per mission frame).
        if (enemyFireCooldowns.isNotEmpty()) {
            val livingIds = asteroids.filter {
                it.type == AsteroidType.ENEMY_SHIP && it.hp > 0
            }.map { it.id }.toHashSet()
            enemyFireCooldowns.keys.retainAll(livingIds)
        }
    }

    private fun spawnEnemyShip(mission: MissionConfig, spec: EnemyShipSpawn) {
        val type = AsteroidType.ENEMY_SHIP
        val hpVal = (mission.asteroidHp * type.hpMul).toInt().coerceAtLeast(1)
        val half  = DraftCombat.ASTEROID_HALF * type.halfMul
        val id    = newAsteroidId()
        // Enemy ship gets its own shield buffer = half of HP. Damage
        // routes through shield first (cyan bar in HUD); when it drops,
        // the green HP bar starts shrinking.
        val shieldVal = (hpVal / 2).coerceAtLeast(1)
        asteroids.add(Asteroid(
            id    = id,
            xPos  = spec.xOffset,
            yPos  = shipPosY + DraftCombat.ENEMY_SHIP_LEAD_DISTANCE,
            zPos  = DraftCombat.ENEMY_SHIP_Z,
            hp    = hpVal,
            maxHp = hpVal,
            shieldHp    = shieldVal,
            shieldHpMax = shieldVal,
            type  = type,
            speed = 0f,
            // Enemy ship rides the cruise speed alongside the player —
            // its position is force-overridden each tick (yPos = shipPosY +
            // LEAD_DISTANCE), so the standard movement integrator's output
            // is discarded anyway. But `leadAimAt` reads `depthSpeed` to
            // predict where the target will be when the bullet arrives;
            // without -SHIP_CRUISE_SPEED here, turrets aim at the current
            // position and bullets always trail behind the moving enemy.
            // (Convention: depthSpeed > 0 = approaches camera by reducing
            // yPos; depthSpeed < 0 = recedes by increasing yPos.)
            depthSpeed = -DraftCombat.SHIP_CRUISE_SPEED,
            half  = half,
            // Holds station 20 units ahead — never actually contacts hull,
            // so no platform damage from accidental brush. Death damage
            // routed through bolt impacts (EnemyBolt) instead.
            platformDmg = 0,
            // Dedicated `Enemy_Ship.glb` (procedural TIE-fighter-ish via
            // tools/build_enemy_ship_glb.py). Falls back to the red HEAVY
            // asteroid mesh if the .glb load failed at startup.
            meshHandle  = if (enemyShipMeshHandle != 0L) enemyShipMeshHandle else asteroidMeshHeavy,
        ))
        enemyFireCooldowns[id] = DraftCombat.ENEMY_SHIP_FIRE_INTERVAL_SEC
    }

    private fun fireEnemyBolt(enemy: Asteroid) {
        val sx = enemy.xPos
        val sy = enemy.yPos
        val sz = enemy.zPos
        // Aim at the ship's centre (hull deck level).
        val tx = 0f
        val ty = shipPosY
        val tz = DraftCombat.PLATFORM_TOP_Z + 0.5f   // slightly above deck so bolts visibly hit
        val dx = tx - sx; val dy = ty - sy; val dz = tz - sz
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        val s = DraftCombat.ENEMY_BOLT_SPEED
        effects.add(EnemyBolt(
            x = sx, y = sy, z = sz,
            vx = dx / len * s,
            vy = dy / len * s,
            vz = dz / len * s,
            damage = DraftCombat.ENEMY_BOLT_DAMAGE,
        ))
    }

    /**
     * All asteroids that will hit either shield sphere or hull AABB
     * within WEAPON_ENGAGEMENT_RANGE — i.e. the things the auto-aim
     * weapons would engage. Used by the UI to outline live threats in
     * red so the player sees what's actually dangerous regardless of
     * whether the central turret has tracked onto them yet.
     */
    fun currentThreatAsteroids(): List<Asteroid> =
        asteroids.filter { isThreatening(it) }

    /**
     * Top N most dangerous asteroids the rocket silo can engage — descending
     * current HP, tiebreak nearest, filtered to the silo's 95% firing arc.
     * Origin is the silo opening, not the central pivot, so a target the
     * central turret can't reach (outside its 80-90% arc) may still be
     * launchable as long as it's inside the silo's wider 95° cone.
     */
    private fun findRocketTargets(maxN: Int): List<Asteroid> {
        if (asteroids.isEmpty() || maxN <= 0) return emptyList()
        // Player-directed dispatch: when priority lock is set OR the central
        // turret has a current auto-aim pick, all rockets focus-fire on
        // that single asteroid (list of N copies of the same target — the
        // silo's `fire` walks the list one rocket at a time). Falls back to
        // the legacy top-N-by-HP scan only if neither has a target.
        val focused = preferredTarget()
        if (focused != null) {
            return List(maxN) { focused }
        }
        val sx = DraftCombat.ROCKET_SILO_X
        val sz = DraftCombat.ROCKET_SILO_Z + DraftCombat.ROCKET_SILO_MUZZLE_OFFSET
        val halfArc = DraftCombat.ARC_ROCKET_HALF_RAD
        return asteroids
            .filter { it.hp > 0 && isThreatening(it) && isWithinArc(it, sx, sz, halfArc) }
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
                // Beam follows the player priority lock if set, otherwise
                // the central turret's auto-aim pick. The dome has a wider
                // arc (95% vs 80-90%), so it may engage flank targets the
                // central can't fire at. canEngage gates rendering /
                // damage to the laser's own arc, with a slight duration
                // skew if the target sits out of arc for a stretch.
                // Refund only if there's no target at all.
                if (preferredTarget() == null) false
                else {
                    val sx = DraftCombat.LASER_INSTALL_X
                    val sz = DraftCombat.LASER_INSTALL_Z +
                             DraftCombat.LASER_DOME_TOP_OFFSET
                    effects.add(Beam(
                        // Source closure re-reads shipPosY every tick — the
                        // dome travels with the ship along the route, so the
                        // beam stays anchored to its emitter, not to the
                        // world position where the ability was triggered.
                        source       = { Vec3(sx, shipPosY, sz) },
                        aimSelector  = { preferredTarget() },
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
                    // Player-directed dispatch: all drones focus-fire on the
                    // priority lock (or central turret's auto-aim pick when
                    // unlocked). Round-robin spread across nearest-live only
                    // applies when neither has a target.
                    val focusedId = preferredTarget()?.id
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
                        val tid = focusedId
                            ?: if (live.isEmpty()) -1L else live[i % live.size].id
                        val drone = Drone(
                            x = rx, y = shipPosY + DraftCombat.DRONE_SPAWN_Y, z = rz,
                            // Zero initial velocity — steering immediately accelerates
                            // each drone toward its (focused) target. Legacy "vy=-DRONE_SPEED
                            // upward fan-out" was tuned for the old top-down camera; under
                            // the current third-person camera it sent drones backward toward
                            // the viewer instead of forward to asteroids.
                            vx = 0f, vy = 0f, vz = 0f,
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
        val pivotY = shipPosY + DraftCombat.CENTRAL_TURRET_Y_OFFSET
        val targetAngleRaw = if (centralTarget != null) {
            // Concept «Вид 3» — yaw is now around world Z (vertical),
            // measured in the XY plane (lateral × depth). atan2(-tx, ty)
            // gives the rotationZ that aligns the cannon's forward axis
            // (post-Rx(-π/2) lay-flat is +Y) with the lead direction.
            val lead = leadAimAt(centralTarget, pivotX, pivotY, pivotZ, currentWeapon.projectileSpeed)
            val tx = lead[0] - pivotX
            val ty = kotlin.math.max(0f, lead[1] - pivotY)
            if (tx == 0f && ty == 0f) centralTurretAngle
            else kotlin.math.atan2(-tx, ty)
        } else 0f
        val targetAngleClamped = targetAngleRaw.coerceIn(-centralHalfArc, centralHalfArc)
        // Smooth tracking toward the CLAMPED angle — turret rotates as
        // far as its arc allows and then sits at the edge, visibly
        // tracking but unable to fire on out-of-arc targets.
        centralTurretAngle += (targetAngleClamped - centralTurretAngle) * 16f * dt

        // Side turret aim — each one tracks the nearest THREATENING
        // asteroid in its arc. Asteroids passing harmlessly past the
        // ship are ignored (same filter as central turret).
        val sideThreats = asteroids.filter { isThreatening(it) }
        for (i in turretXs.indices) {
            val tx = turretXs[i]
            val tz = DraftCombat.TURRET_TOP_Z
            val target = nearestAsteroidInArc(
                sideThreats, tx, tz, DraftCombat.ARC_SIDE_CANNON_HALF_RAD,
            )
            val sidePivotY = shipPosY + DraftCombat.SIDE_TURRET_Y_OFFSET
            val sideTargetAng = if (target != null) {
                val lead = leadAimAt(target, tx, sidePivotY, tz, DraftCombat.SIDE_BULLET_SPEED)
                val dx = lead[0] - tx
                val dy = kotlin.math.max(0f, lead[1] - sidePivotY)
                if (dx == 0f && dy == 0f) sideTurretAngles[i]
                else kotlin.math.atan2(-dx, dy)
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
        if (centralTarget != null && centralFireCooldown <= 0f && aimAligned
            && currentMission?.weaponsDisabled != true) {
            centralFireCooldown = weapon.fireIntervalSec
            val ang = centralTurretAngle
            val sinA = kotlin.math.sin(ang)
            val cosA = kotlin.math.cos(ang)
            // Concept «Вид 3»: cannon lies flat with +Y forward after the
            // pivot's Rx(-π/2). Rz(ang) rotates the forward vector
            // (0,1,0) to (-sin(ang), cos(ang), 0). Muzzle = barrel tip.
            // Distance from yaw pivot = cannon mesh length (0.55).
            // Z = cannon SceneObject Z (top of base + tower + half-thick
            // + anti-Z-fight nudge) — NOT pivotZ, which is at the base
            // collar and ~0.3 units below the barrel.
            val muzzleR = TURRET_CANNON_LENGTH
            val muzzleX = pivotX + (-sinA) * muzzleR
            val muzzleY = pivotY + cosA * muzzleR
            val muzzleZ = DraftCombat.PLATFORM_TOP_Z + CENTRAL_CANNON_Z_ABOVE_PLATFORM
            // Per-weapon mesh + behaviour. Пушка (HEAVY_CANNON) → fat
            // Bullet_Heavy.glb + AoE behaviour. Рельсотрон (RAILGUN) →
            // slim Bullet.glb tinted electric-blue + plain bullet (no AoE).
            // Автомат (AUTOMATIC) → slim Bullet.glb (warm brass) + plain.
            val bulletMesh = when (weapon.id) {
                WeaponId.HEAVY_CANNON -> bulletHeavyMeshHandle
                WeaponId.RAILGUN      -> bulletRailgunMeshHandle
                WeaponId.AUTOMATIC    -> bulletMeshHandle
            }
            val centralBehaviour: ProjectileBehavior =
                if (weapon.aoeRadius > 0f)
                    HeavyShellBehavior(
                        aoeRadius = weapon.aoeRadius,
                        aoeDamage = (weaponDamage * weapon.aoeDamageMultiplier).toInt(),
                    )
                else PlainBulletBehavior()
            // Рельсотрон leaves a long blue tracer beam from muzzle to
            // projectile (drawn by SceneAssembler through the beam
            // pipeline). Other weapons fly bare.
            val trailColor = if (weapon.id == WeaponId.RAILGUN)
                DraftCombat.RAILGUN_TRAIL_TINT else null
            val trailWidth = if (weapon.id == WeaponId.RAILGUN)
                DraftCombat.RAILGUN_TRAIL_HALF_W else 0f
            // 3D-pivot Phase 2/3: aim the bullet at the target's full
            // (x, y, z), not just (x, z). Muzzle stays on the deck
            // plane (Y=0); velocity vector is the unit direction to the
            // asteroid scaled by projectile speed. Lead the target so
            // bullet arrives where asteroid WILL be, not where it IS.
            val pSpeed = weapon.projectileSpeed
            // muzzleY already reflects the cannon's tip world Y (above).
            val lead = leadAimAt(centralTarget, muzzleX, muzzleY, muzzleZ, pSpeed)
            val tdx = lead[0] - muzzleX
            val tdy = lead[1] - muzzleY
            val tdz = lead[2] - muzzleZ
            val tlen = kotlin.math.sqrt(tdx * tdx + tdy * tdy + tdz * tdz).coerceAtLeast(1e-4f)
            // Tracer origin = muzzle nudged forward along the firing
            // direction by RAILGUN_TRAIL_FORWARD_GAP, so the visible
            // tracer starts a beat ahead of the barrel instead of
            // overlapping the cannon and hull. spawnShipPosY captures
            // the ship's Y at fire time so SceneAssembler can track the
            // trail forward with the moving ship.
            val gap = if (weapon.id == WeaponId.RAILGUN)
                DraftCombat.RAILGUN_TRAIL_FORWARD_GAP else 0f
            val unitX = tdx / tlen
            val unitY = tdy / tlen
            val unitZ = tdz / tlen
            effects.add(Projectile(
                x  = muzzleX,
                y  = muzzleY,
                z  = muzzleZ,
                vx = unitX * pSpeed,
                vy = unitY * pSpeed,
                vz = unitZ * pSpeed,
                damage = weaponDamage,
                halfW = weapon.projectileHalfW,
                halfH = weapon.projectileHalfH,
                meshHandle = bulletMesh,
                behaviour  = centralBehaviour,
                originX    = muzzleX + unitX * gap,
                originY    = muzzleY + unitY * gap,
                originZ    = muzzleZ + unitZ * gap,
                trailColor = trailColor,
                trailWidth = trailWidth,
                spawnShipPosY = shipPosY,
            ))
            // Muzzle VFX is the weapon's signature read:
            //   Рельсотрон (RAILGUN) → lightning discharge (cyan-white core
            //     flash + 5-7 procedural electric arcs + cyan sparks). The
            //     "real" electromagnetic launcher visual.
            //   Пушка (HEAVY_CANNON) + Автомат (AUTOMATIC) → warm
            //     cone-trefoil muzzle blast + warm sparks. Size scales by
            //     projectileHalfW, so Пушка naturally pops bigger.
            if (weapon.id == WeaponId.RAILGUN) {
                vfx.spawnRailgunMuzzle(muzzleX, muzzleY, muzzleZ, sinA, cosA,
                                   weapon.projectileHalfW)
                vfx.spawnRailgunSparks(muzzleX, muzzleY, muzzleZ,
                                   sinA * weapon.projectileSpeed,
                                   cosA * weapon.projectileSpeed)
            } else {
                vfx.spawnMuzzleBlast(muzzleX, muzzleY, muzzleZ, sinA, cosA,
                                 weapon.projectileHalfW,
                                 DraftCombat.FLASH_TINT_MUZZLE)
                vfx.spawnMuzzleSparks(muzzleX, muzzleY, muzzleZ,
                                  sinA * weapon.projectileSpeed,
                                  cosA * weapon.projectileSpeed)
            }
        }
        // Turrets fire at the nearest asteroid (if any). Skipped entirely
        // when mission.weaponsDisabled — used by the debug shield-test
        // mode.
        val sideFireAllowed = currentMission?.weaponsDisabled != true
        for (i in turretXs.indices) {
            turretFireT[i] += dt
            while (sideFireAllowed && turretFireT[i] >= DraftCombat.FIRE_INTERVAL_SEC) {
                turretFireT[i] -= DraftCombat.FIRE_INTERVAL_SEC
                val tx = turretXs[i]
                // Side turret on .glb cannon — pivot Z at amburazura height
                // (matches the SceneObject placement in SceneAssembler).
                val tz = DraftCombat.SIDE_CANNON_GLTF_PIVOT_Z
                val target = nearestAsteroidInArc(
                    asteroids.filter { isThreatening(it) },
                    tx, tz, DraftCombat.ARC_SIDE_CANNON_HALF_RAD,
                ) ?: continue
                // Concept «Вид 3» — cannon points along world +Y by
                // default; sideTurretAngles[i] = rotationZ around vertical.
                // Forward direction = (-sin(yaw), cos(yaw), 0).
                val ang = sideTurretAngles[i]
                val nx = -kotlin.math.sin(ang)
                val ny =  kotlin.math.cos(ang)
                val sidePivotY = shipPosY + DraftCombat.SIDE_TURRET_Y_OFFSET
                // Muzzle = tip of the rotated cannon in the XY plane.
                // Length from pivot is the .glb model's barrel length (file
                // -Z → world +Y after Rx(+π/2)).
                val muzzleX = tx + nx * DraftCombat.SIDE_CANNON_GLTF_LENGTH
                val muzzleY = sidePivotY + ny * DraftCombat.SIDE_CANNON_GLTF_LENGTH
                val muzzleZ = tz
                // Side turrets fire heavy AoE shells matching the central
                // HEAVY_CANNON weapon profile — chunky projectile, slow
                // muzzle velocity, splash damage to nearby asteroids. The
                // cadence is paced by FIRE_INTERVAL_SEC (1 shot/sec) so
                // they read as supporting artillery rather than a stream
                // of small projectiles.
                val sideDamage = (effectiveTurretDamage * DraftCombat.SIDE_DAMAGE_MUL).toInt()
                // 3D-pivot Phase 2/3: aim at target's full (x, y, z) with
                // lead correction (same fix as the central turret — route-
                // mode asteroid speeds make a no-lead shot land behind).
                val sideSpeed = DraftCombat.SIDE_BULLET_SPEED
                val lead = leadAimAt(target, muzzleX, muzzleY, muzzleZ, sideSpeed)
                val tdx = lead[0] - muzzleX
                val tdy = lead[1] - muzzleY
                val tdz = lead[2] - muzzleZ
                val tlen = kotlin.math.sqrt(tdx * tdx + tdy * tdy + tdz * tdz).coerceAtLeast(1e-4f)
                effects.add(Projectile(
                    x  = muzzleX, y = muzzleY, z = muzzleZ,
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
                vfx.spawnMuzzleBlast(muzzleX, muzzleY, muzzleZ, nx, ny,
                                 DraftCombat.SIDE_BULLET_HALF_W,
                                 DraftCombat.FLASH_TINT_MUZZLE)
                vfx.spawnMuzzleSparks(muzzleX, muzzleY, muzzleZ,
                                  nx * DraftCombat.BULLET_SPEED,
                                  ny * DraftCombat.BULLET_SPEED)
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
            // Player-directed dispatch: priority lock (or central's pick)
            // overrides the drone's current target every frame — so the
            // swarm collectively switches if the player taps a new asteroid
            // mid-flight. Falls back to nearest-live when neither has a
            // target. The drone's sticky `targetId` only kicks in when the
            // dispatch source returns null and a previous lock is dead.
            val focused = preferredTarget()
            val target = focused ?: asteroids.firstOrNull { it.id == d.targetId && it.hp > 0 }
                ?: asteroids.filter { it.hp > 0 }
                    .minByOrNull {
                        val dx = it.xPos - d.x
                        val dy = it.yPos - d.y
                        val dz = it.zPos - d.z
                        dx * dx + dy * dy + dz * dz
                    }
            d.targetId = target?.id ?: -1L
            // Thrust-based physics — light spacecraft. Each tick the drone
            // applies a constant DRONE_THRUST acceleration toward the
            // current target; velocity integrates over time. Capped at
            // DRONE_SPEED. Inertia naturally produces "ramp up from rest",
            // "overshoot the target", "thrust reverses → slow down → loop
            // back" — no explicit braking code needed.
            if (target != null) {
                val desiredX = target.xPos - d.x
                val desiredY = target.yPos - d.y
                val desiredZ = target.zPos - d.z
                val len = kotlin.math.sqrt(desiredX * desiredX + desiredY * desiredY + desiredZ * desiredZ)
                if (len > 1e-3f) {
                    val nx = desiredX / len
                    val ny = desiredY / len
                    val nz = desiredZ / len
                    val a = DraftCombat.DRONE_THRUST * dt
                    d.vx += nx * a
                    d.vy += ny * a
                    d.vz += nz * a
                    val vlen2 = d.vx * d.vx + d.vy * d.vy + d.vz * d.vz
                    val maxSpeed = DraftCombat.DRONE_SPEED
                    if (vlen2 > maxSpeed * maxSpeed) {
                        val s = maxSpeed / kotlin.math.sqrt(vlen2)
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
                                weaponCtx.damageAsteroid(other, DraftCombat.EXPLOSIVE_AOE_DAMAGE)
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
                    AsteroidType.HEAVY,
                    AsteroidType.ENEMY_SHIP -> {
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
        // E20 — same lifecycle for shield impacts. The forcefield shader
        // reads `life` (normalised) to drive the bump's brightness; once
        // life hits zero the impact bloom is invisible anyway, so we just
        // drop the slot.
        val shieldImpactIter = shieldImpacts.iterator()
        while (shieldImpactIter.hasNext()) {
            val si = shieldImpactIter.next()
            si.life -= dt
            if (si.life <= 0f) shieldImpactIter.remove()
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
        // Combat mission — advance ship along the same +Y axis route
        // mode uses (no asteroid corridor; ship just cruises and the
        // enemy holds station ahead). Then run enemy spawn / position /
        // fire logic before hit detection so the bolt projectiles spawn
        // this frame are picked up by the next effects pass.
        val mc = currentMission
        if (mc?.enemyShipSpawns != null && mc.route == null) {
            shipPosY += DraftCombat.SHIP_CRUISE_SPEED * dt
            tickEnemyShip(dt, mc)
        }
        // Asteroid hit detection — shield first (asteroid breaks ON the
        // arch, never reaches platform if shield is up and the asteroid
        // is under the dome's X coverage), then platform (asteroid
        // landed past where the shield could intercept).
        var platformDamage = 0
        val asteroidIter = asteroids.iterator()
        while (asteroidIter.hasNext()) {
            val a = asteroidIter.next()
            // 1. Force-field hemisphere contact (E20) — 3D sphere test
            //    around the shield centre. Shield travels with the ship,
            //    so centre Y = shipPosY (route mode advances it; wave
            //    mode stays at 0). Inside = within SHIELD_HEMISPHERE_RADIUS
            //    AND in the FRONT hemisphere relative to the ship
            //    (yPos ≥ ship's Y). Asteroid pops on the first frame the
            //    centre crosses inside; impact world-pos is pushed to
            //    shieldImpacts for the forcefield shader to bloom.
            if (shieldHp > 0f && a.yPos >= shipPosY) {
                val r  = DraftCombat.SHIELD_HEMISPHERE_RADIUS
                val cx = 0f
                val cy = shipPosY
                val cz = DraftCombat.SHIELD_CENTER_Z
                val dx = a.xPos - cx
                val dy = a.yPos - cy
                val dz = a.zPos - cz
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 <= r * r) {
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
                    // Project the asteroid centre onto the sphere surface
                    // along the shieldCentre→asteroid ray — that's where
                    // the visual bloom should sit.
                    val dist = kotlin.math.sqrt(d2.coerceAtLeast(1e-6f))
                    val k = r / dist
                    val ix = cx + dx * k
                    val iy = cy + dy * k
                    val iz = cz + dz * k
                    // Cap to SHIELD_MAX_ACTIVE_IMPACTS by dropping the
                    // oldest. Newer impact replaces it visually.
                    if (shieldImpacts.size >= DraftCombat.SHIELD_MAX_ACTIVE_IMPACTS) {
                        shieldImpacts.removeAt(0)
                    }
                    shieldImpacts.add(ShieldImpact(
                        x = ix, y = iy, z = iz,
                        life = DraftCombat.SHIELD_IMPACT_LIFE_SEC,
                    ))
                    // Existing cyan flash still spawned — reads as a quick
                    // sparkle on top of the shader's bloom (complementary
                    // not redundant, since the shader bloom decays over
                    // SHIELD_IMPACT_LIFE_SEC = 0.35s and the flash over
                    // FLASH_LIFE_SEC).
                    val sh = DraftCombat.FLASH_TINT_SHIELD
                    flashes.add(Flash(
                        x = ix, y = iy, z = iz,
                        life = DraftCombat.FLASH_LIFE_SEC,
                        maxLife = DraftCombat.FLASH_LIFE_SEC,
                        tintR = sh[0], tintG = sh[1], tintB = sh[2], tintA = sh[3],
                    ))
                    asteroidIter.remove()
                    continue
                }
            }
            // 2. Hull contact — asteroid actually collides with the ship
            //    body. Four gates form the hull AABB (4D really, with Y
            //    being a thin slice around shipPosY):
            //    - Y in [shipPosY-1, shipPosY+1]    (ship's depth slice)
            //    - |X| ≤ hull half-width + a.half   (within fuselage width)
            //    - Z + half ≥ HULL_BOTTOM_Z         (not entirely beneath hull)
            //    - Z - half ≤ PLATFORM_TOP_Z        (not entirely above deck)
            //
            //    Without the lower-Z gate, asteroids passing FAR below
            //    the ship (e.g. z=-2 in the wider route corridor) still
            //    counted as a hull hit even though physically they fly
            //    several units beneath the hull. User-reported: "ship
            //    takes damage at 57% with shield almost full".
            val hullHalfW = DraftCombat.SCREEN_HALF_W   // hull mesh half-width = 2.47
            if (a.yPos in (shipPosY - 1f)..(shipPosY + 1f) &&
                kotlin.math.abs(a.xPos) <= hullHalfW + a.half &&
                a.zPos + a.half >= DraftCombat.HULL_BOTTOM_Z &&
                a.zPos - a.half <= DraftCombat.PLATFORM_TOP_Z) {
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
        val route = mission?.route
        if (route != null) {
            // Route mode — advance ship along the camera-forward axis,
            // materialise placements as they come within ROUTE_SPAWN_DEPTH
            // ahead. Each placement spawns once and is forgotten (cursor
            // advances).
            //
            // World position of placement at remaining-distance d:
            //   worldY = d · FORWARD_Y
            //   worldZ = d · FORWARD_Z + p.z   (p.z is altitude offset)
            //   worldX = p.x                   (lateral offset)
            //
            // Asteroid velocity = -SHIP_SPEED · forward, so:
            //   depthSpeed = SHIP_SPEED · FORWARD_Y   (vy < 0)
            //   speed      = SHIP_SPEED · FORWARD_Z   (vz < 0)
            // Both forward components are positive → both yPos and zPos
            // decrease over time, asteroid slides along the camera-forward
            // axis toward the viewer.
            shipPosY += DraftCombat.SHIP_CRUISE_SPEED * dt
            while (routeCursor < route.asteroids.size &&
                   route.asteroids[routeCursor].absY <= shipPosY + DraftCombat.ROUTE_SPAWN_DEPTH) {
                val p = route.asteroids[routeCursor]
                val type = p.type
                val half = DraftCombat.ASTEROID_HALF * type.halfMul
                val spinSign = if (Math.random() < 0.5) -1f else 1f
                val spin     = spinSign * (0.5f + Math.random().toFloat() * 1.5f)
                val phase    = (Math.random() * Math.PI * 2).toFloat()
                val mesh = when (type) {
                    AsteroidType.HEAVY      -> asteroidMeshHeavy
                    AsteroidType.EXPLOSIVE  -> asteroidMeshExplosive
                    AsteroidType.ENERGY     -> asteroidMeshEnergy
                    AsteroidType.ENEMY_SHIP -> asteroidMeshHeavy   // route mode shouldn't spawn enemy ships, but be safe
                    AsteroidType.NORMAL,
                    AsteroidType.FAST       ->
                        if (Math.random() < 0.5) asteroidMeshGrey1 else asteroidMeshGrey2
                }
                val hpVal = p.hpOverride
                    ?: (mission.asteroidHp * type.hpMul).toInt().coerceAtLeast(1)
                asteroids.add(
                    Asteroid(
                        id    = newAsteroidId(),
                        xPos  = p.x,
                        // Absolute world coords — asteroid stays put while
                        // the ship moves through it.
                        yPos  = p.absY,
                        zPos  = p.z,
                        hp    = hpVal,
                        maxHp = hpVal,
                        rotation      = phase,
                        rotationSpeed = spin,
                        type          = type,
                        // Route asteroids don't translate — no Z fall, no
                        // Y depth-speed. Wave-mode asteroids still use
                        // these fields (see else-branch below).
                        speed         = 0f,
                        depthSpeed    = 0f,
                        half          = half,
                        platformDmg   = (DraftCombat.PLATFORM_DMG_PER_HIT * type.platformDmgMul)
                                           .toInt().coerceAtLeast(1),
                        meshHandle    = mesh,
                    )
                )
                routeCursor++
            }
            // Despawn asteroids the ship has already passed by more than
            // |THRESHOLD| world units. (No ship-collision in the minimal
            // route slice — they just fade out behind the camera.)
            asteroids.removeAll {
                (it.yPos - shipPosY) < DraftCombat.ROUTE_PASS_BY_THRESHOLD
            }
            // Throttle HUD refresh to integer-percent ticks so we don't
            // post a UI message every frame.
            val pct = ((shipPosY / route.endY) * 100f).toInt().coerceIn(0, 100)
            if (pct != routePctUiLast) {
                routePctUiLast = pct
                uiHandler.post { hud.refreshWaveLabel("Маршрут $pct%") }
            }
        } else if (mission != null) {
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
                            AsteroidType.HEAVY      -> asteroidMeshHeavy
                            AsteroidType.EXPLOSIVE  -> asteroidMeshExplosive
                            AsteroidType.ENERGY     -> asteroidMeshEnergy
                            AsteroidType.ENEMY_SHIP -> asteroidMeshHeavy   // wave mode shouldn't spawn enemy ships, but be safe
                            AsteroidType.NORMAL,
                            AsteroidType.FAST       ->
                                if (Math.random() < 0.5) asteroidMeshGrey1 else asteroidMeshGrey2
                        }
                        val hpVal = (mission.asteroidHp * type.hpMul).toInt()
                            .coerceAtLeast(1)
                        // 3D-pivot Phase 1: pick depthSpeed so the
                        // asteroid reaches yPos = 0 at the same instant
                        // its zPos hits PLATFORM_TOP_Z. Spawn zone is
                        // picked 50/50 between the lower echelon
                        // (ASTEROID_SPAWN_*) and the upper one
                        // (ASTEROID_SPAWN_*_2) — same destination, so
                        // upper-echelon rocks travel a steeper and
                        // longer path to the platform.
                        val useUpperZone = Math.random() < 0.5
                        val zPosSpawn = if (useUpperZone) DraftCombat.ASTEROID_SPAWN_Z_2
                                        else                DraftCombat.ASTEROID_SPAWN_Z
                        val yPosSpawn = if (useUpperZone) DraftCombat.ASTEROID_SPAWN_Y_DEPTH_2
                                        else                DraftCombat.ASTEROID_SPAWN_Y_DEPTH
                        val zFall     = zPosSpawn - DraftCombat.PLATFORM_TOP_Z
                        val yFall     = yPosSpawn
                        val asteroidSpeed = mission.asteroidSpeed * type.speedMul
                        val depthSpeed = if (zFall > 0f) asteroidSpeed * yFall / zFall else 0f
                        asteroids.add(
                            Asteroid(
                                id    = newAsteroidId(),
                                xPos  = rx,
                                yPos  = yPosSpawn,
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

    // Win / lose checks. Win condition depends on mission mode:
    //   - Wave mode: last wave fully spawned AND no asteroids left.
    //   - Route mode: ship has travelled past route.endY AND no asteroids left.
    // Lose is the same for both: platform HP exhausted.
    private fun checkWinLose() {
        val m = currentMission ?: return
        if (platformHP <= 0) { showLose(); return }
        // Combat mission — win = all spawn-queue entries fired AND no
        // living enemy ships remain. Independent from wave/route logic.
        if (m.enemyShipSpawns != null) {
            val allSpawned   = enemySpawnQueue.isEmpty()
            val anyEnemyAlive = asteroids.any {
                it.type == AsteroidType.ENEMY_SHIP && it.hp > 0
            }
            if (allSpawned && !anyEnemyAlive) { showWin(); return }
            return
        }
        val route = m.route
        if (route != null) {
            if (shipPosY >= route.endY && asteroids.isEmpty()) showWin()
            return
        }
        val onLastWave = currentWaveIndex == m.waves.size - 1
        val lastWaveSpawnedOut =
            currentWaveSpawned >= m.waves[currentWaveIndex.coerceAtMost(m.waves.size - 1)].asteroidCount
        if (onLastWave && lastWaveSpawnedOut && asteroids.isEmpty()) showWin()
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
    /**
     * Predict where the asteroid will be when a projectile fired now from
     * `(mx, my, mz)` at `pSpeed` reaches it. Returns (predX, predY, predZ).
     * Asteroid velocity is (0, -depthSpeed, -speed) (xPos doesn't move).
     * One Newton iteration is enough for our speed ratios (bullets are
     * ~5-10× faster than asteroids).
     *
     * Critical for route mode — asteroids move 3-5× faster along -Y than
     * wave-mode falling asteroids, so a no-lead shot lands behind the
     * target in Y and (because the bullet's Z is still climbing toward
     * the original aim z) below it in Z. Visible as "we keep missing".
     */
    private fun leadAimAt(a: Asteroid, mx: Float, my: Float, mz: Float, pSpeed: Float): FloatArray {
        fun timeToDist(tx: Float, ty: Float, tz: Float): Float {
            val dx = tx - mx; val dy = ty - my; val dz = tz - mz
            return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) / pSpeed
        }
        var t = timeToDist(a.xPos, a.yPos, a.zPos)
        // refine: re-solve with the leading prediction.
        val ay1 = a.yPos - a.depthSpeed * t
        val az1 = a.zPos - a.speed * t
        t = timeToDist(a.xPos, ay1, az1)
        return floatArrayOf(a.xPos, a.yPos - a.depthSpeed * t, a.zPos - a.speed * t)
    }

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
            // Silo Y travels with the ship along the route — read shipPosY
            // at launch time so the rocket emerges from the silo's actual
            // world position, not the world origin.
            val spawnY = shipPosY
            val spawnZ = zPos + DraftCombat.ROCKET_BODY_LENGTH * 0.5f
            effects.add(Projectile(
                x = xPos, y = spawnY, z = spawnZ,
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
            vfx.spawnRocketLaunchPuff(xPos, spawnY, zPos)
        }
    }

    companion object {
        private const val TICK_MS = 20L
    }
}
