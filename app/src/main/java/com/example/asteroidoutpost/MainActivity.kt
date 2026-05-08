package com.example.asteroidoutpost

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.asteroidoutpost.ai.MissionController
import com.example.asteroidoutpost.ai.OrbitTarget
import com.example.asteroidoutpost.ai.Vec2
import com.example.asteroidoutpost.game.Ability
import com.example.asteroidoutpost.game.AbilityCatalog
import com.example.asteroidoutpost.game.AbilityId
import com.example.asteroidoutpost.game.AsteroidType
import com.example.asteroidoutpost.game.GameProgress
import com.example.asteroidoutpost.game.MissionConfig
import com.example.asteroidoutpost.game.MissionRun
import com.example.asteroidoutpost.game.Missions
import com.example.asteroidoutpost.game.OverlayFactory
import com.example.asteroidoutpost.game.ProgressRepository
import com.example.asteroidoutpost.game.UpgradeCatalog
import com.example.asteroidoutpost.game.Weapon
import com.example.asteroidoutpost.game.WeaponCatalog
import com.example.asteroidoutpost.game.WeaponId
import com.example.asteroidoutpost.intelligence.CommandClassifier
import com.example.asteroidoutpost.intelligence.FleetRegistry
import com.example.asteroidoutpost.intelligence.FleetUnit
import com.example.asteroidoutpost.intelligence.PlayerCommand
import com.example.asteroidoutpost.intelligence.StationAI
import com.example.asteroidoutpost.sim.CombatEvent
import com.example.asteroidoutpost.sim.CombatStats
import com.example.asteroidoutpost.sim.SceneAdapter
import com.example.asteroidoutpost.sim.ShipState
import com.example.asteroidoutpost.sim.SimulationWorld
import com.example.asteroidoutpost.sim.Team
import com.example.asteroidoutpost.sim.WorldObject
import com.example.asteroidoutpost.sim.WorldObjectType

class MainActivity : AppCompatActivity() {

    private lateinit var engineView:         EngineView
    private lateinit var selectionOverlay:   SelectionOverlayView
    private lateinit var axisIndicator:      AxisIndicatorView
    private lateinit var btnSettings:        ImageButton
    private lateinit var settingsPullTab:    TextView
    private lateinit var axisPanel:          LinearLayout
    private lateinit var btnCommands:        ImageButton
    private lateinit var commandsDrawer:     GridLayout
    private lateinit var btnBuild:           ImageButton
    private lateinit var buildDrawer:        GridLayout
    private lateinit var btnBuildFighter:    ImageButton
    private lateinit var btnAttack:          ImageButton
    private lateinit var btnDefend:          ImageButton
    private lateinit var btnFlyAround:       ImageButton
    private lateinit var btnPatrol:          ImageButton
    private lateinit var btnHome:            ImageButton
    private lateinit var btnMic:             ImageButton
    private lateinit var cameraJoystick:     CameraJoystickView
    private lateinit var shipCard:           LinearLayout
    private lateinit var shipCardTitle:      TextView
    private lateinit var shipCardSubtitle:   TextView
    private lateinit var shipCardHint:       TextView
    private var latestScreenFrames: List<ScreenFrame> = emptyList()
    @Volatile private var latestHealthBars:  List<HealthBarData> = emptyList()

    // ---------------------------------------------------------------------------
    // Production
    // ---------------------------------------------------------------------------
    @Volatile private var buildActive:   Boolean = false
    @Volatile private var buildProgress: Float   = 0f
    private var nextShipId: Int = 12
    private var alliedShips: List<ShipState> = emptyList()

    // ---------------------------------------------------------------------------
    // Scene — owned by Kotlin, submitted to engine each frame
    // ---------------------------------------------------------------------------
    private var shipMeshHandle:           Long = 0L
    private var stationMeshHandle:        Long = 0L
    private var projectileMeshHandle:     Long = 0L
    private var selectionFrameThinHandle: Long = 0L
    private var selectionFrameBoldHandle: Long = 0L

    // DRAFT — Asteroid Outpost placeholders. All reuse the same quad geometry
    // with different tints; real models replace them later.
    private var asteroidMeshHandle: Long = 0L  // grey — legacy station.glb tint, unused by Outpost
    private var quadMeshHandle:     Long = 0L  // unit X-Z quad, red tint (central turret, bullets)
    private var quadGreyHandle:     Long = 0L  // unit X-Z quad, grey tint (platform)
    private var quadBlueHandle:     Long = 0L  // unit X-Z quad, blue tint (side turrets)
    // Per-type asteroid meshes. NORMAL/FAST randomize across two grey variants
    // at spawn so common waves don't look like clones; HEAVY/EXPLOSIVE/ENERGY
    // each get their own silhouette + tint so the type is readable at a glance.
    private var asteroidMeshGrey1:     Long = 0L  // Asteroid_1.glb grey  (NORMAL/FAST variant A)
    private var asteroidMeshGrey2:     Long = 0L  // Asteroid_2.glb grey  (NORMAL/FAST variant B)
    private var asteroidMeshHeavy:     Long = 0L  // Asteroid_3.glb dark red (HEAVY — chunky/round)
    private var asteroidMeshExplosive: Long = 0L  // Asteroid_4.glb orange   (EXPLOSIVE)
    private var asteroidMeshEnergy:    Long = 0L  // Asteroid_9.glb cyan     (ENERGY)
    // Bullet meshes (replace red-quad placeholders).
    private var bulletMeshHandle:      Long = 0L  // Bullet.glb        (automatic + side turrets)
    private var bulletHeavyMeshHandle: Long = 0L  // Bullet_Heavy.glb  (heavy cannon)
    private var quadFlashHandle:    Long = 0L  // unit X-Z quad, bright yellow (destruction flash)
    private var quadHpBgHandle:     Long = 0L  // unit X-Z quad, dark grey (HP-bar background)
    private var quadHpFgHandle:     Long = 0L  // unit X-Z quad, green (HP-bar fill)
    // Background nebulae — soft-edge disks (E1.4) loaded via `loadMeshRaw`.
    // Each disk is a triangle fan: centre vertex alpha=1, rim vertices alpha=0,
    // so when rendered through the translucent pipeline it fades smoothly to
    // the background instead of showing hard quad edges. One handle per tint.
    private val nebulaHandles: LongArray = LongArray(5)
    // Shield dome (E2.2) — procedural half-ring (annular half-disk) mesh.
    // Three concentric arcs over the upper half-circle (θ ∈ [0, π]), built as
    // two triangle strips: alpha 0 at the inner rim, peak alpha at the middle
    // arc, alpha 0 at the outer rim. Drawn through the translucent pipeline →
    // the result reads as a thin energy-membrane outline of the dome instead
    // of a filled blue wash. Interior is fully transparent so the central
    // turret stays visible inside the shield.
    private var domeMembraneHandle: Long = 0L
    // E7.1 — unit Y-axis-aligned sphere for 3D fireball explosions, drawn
    // through the additive pipeline with material=ADDITIVE_FIRE. Y-axis
    // alignment is required: the fragment shader's Fresnel-like fade uses
    // abs(vNormal.y) under this project's fixed pitch=π/2 camera.
    private var fireballMeshHandle: Long = 0L
    // E11 — procedural cone-fan mesh for muzzle blasts. Triangle fan in the
    // X-Z plane, ±15° aperture (30° wedge total) around local +Z, radius 1.
    // Drawn through the plasma pipeline with per-draw rotation so each of
    // the three muzzle flashes points in its own world direction.
    private var muzzleConeMeshHandle: Long = 0L
    // E9 — particle infrastructure. `particleQuadHandle` is the unit X-Z
    // quad shared by all instances (the fragment shader produces shape via
    // soft-fade + heat-ramp). `smokeTextureHandle` and `debrisTextureHandle`
    // are sampled by the alpha-textured particle pipeline; sparks (additive)
    // ignore textures entirely.
    private var particleQuadHandle: Long = 0L
    private var smokeTextureHandle: Long = 0L
    private var debrisTextureHandle: Long = 0L
    // Static translucent scene (background nebulae) — captured once in
    // setupBackgroundNebulae so buildScene can compose it with per-frame
    // dynamic translucent objects (currently just the shield dome).
    private var nebulaeTranslucent: List<SceneObject> = emptyList()

    // Aim state. Player drags on the screen to aim the central turret; while the
    // finger is down, the turret fires along the aim direction at fire-rate.
    // Central turret runs auto-aim with a sticky lock — once it picks a
    // target (auto-rule or player tap), it stays on that target until the
    // asteroid dies, then re-picks. Without stickiness, two HEAVYs at near-
    // equal HP cause the turret to jitter between them as bullets land. The
    // player taps an asteroid to override the auto-pick at any time.
    @Volatile private var centralTargetId: Long? = null
    // Smoothed aim angle of the central turret (radians, atan2(dx,dz)). Tracks
    // the touch position; used to orient the turret model and bullet spawn.
    private var centralTurretAngle: Float = 0f

    // Bullets fly along their (vx, vz) vector from a turret muzzle (central or
    // side); asteroids take damage on hit. Bullets and asteroids are owned and
    // mutated only by the tick thread; buildScene (also called from tick) reads them.
    private data class Bullet(
        var x: Float, var z: Float,
        // M8.5 — vx/vz are mutable so homing missiles can rotate their
        // velocity vector toward a tracked target each tick. Plain bullets
        // never write back, so making them var is invisible elsewhere.
        var vx: Float, var vz: Float,
        val damage: Int,
        val halfW: Float = DraftCombat.BULLET_HALF_W,
        val halfH: Float = DraftCombat.BULLET_HALF_H,
        // Render mesh — Bullet.glb for normal/side, Bullet_Heavy.glb for the
        // heavy cannon. 0 falls back to the red quad on the engine side.
        val meshHandle: Long = 0L,
        // E10.3 — previous-frame position for motion-vector tracking.
        // Updated end-of-tick (snapshot of (x, z) before the next move).
        // First frame after spawn keeps prev = current, producing zero
        // velocity for that frame; from frame 2 onward the bullet's flight
        // direction reads as a real velocity vector at the velocity
        // attachment, which the motion-blur shader (E10.4) will sample.
        var prevX: Float = x, var prevZ: Float = z,
        // AoE on impact. aoeRadius == 0 means single-target (default).
        // aoeDamage applied to every other asteroid within the radius.
        val aoeRadius: Float = 0f,
        val aoeDamage: Int = 0,
        // M8.5 — homing. When non-null, every tick rotates (vx, vz)
        // toward the asteroid with this id (clamped by homingTurnRate).
        // If the target dies before impact, the missile coasts on its
        // current heading and detonates on whatever it next collides with.
        val homingTargetId: Long? = null,
        val homingTurnRate: Float = 0f,
    )
    private data class Flash(
        val x: Float, val z: Float,
        var life: Float, val maxLife: Float,
        // Peak half-size at flash midpoint. Default = small per-asteroid death
        // flash; AoE impacts spawn larger flashes sized to the explosion radius.
        val halfMax: Float = DraftCombat.FLASH_HALF,
        // M8.6 — vertical (screen-up) half-size for non-uniform plasma
        // billboards. Default = halfMax (uniform — all existing flashes
        // unchanged). Laser bolts use halfMax = segment-length/2 and a
        // small halfMaxV for the thin streak look.
        val halfMaxV: Float = halfMax,
        // E5.1 — per-flash tint applied inside the plasma fragment branch.
        // Default white preserves the E4 warm-flame look; non-white recolours
        // by event (cyan ENERGY pickup, blue shield absorb, orange-red AoE).
        val tintR: Float = 1f, val tintG: Float = 1f, val tintB: Float = 1f, val tintA: Float = 1f,
        // E11 — optional non-quad mesh for directional flashes (muzzle cones).
        // 0 = engine fallback to quadFlashHandle in buildScene mapping.
        val meshHandle: Long = 0L,
        // E11 — local Y-axis rotation in radians, applied before billboard
        // alignment. 0 keeps the plasma quad axis-aligned (legacy round flash);
        // muzzle cones set this to atan2(dirX, dirZ) so the wedge points along
        // its world direction.
        val rotation: Float = 0f,
        // E12 — per-bolt seed for the lightning sub-shader. >0 routes the
        // flash through the electric-arc fragment branch (used by the
        // railgun muzzle stack); 0 keeps the legacy plasma heat-ramp flash.
        val lightningSeed: Float = 0f,
    )
    private val flashes: MutableList<Flash> = mutableListOf()

    /**
     * E7.1 — 3D fireball explosion. Spawned by AoE-class events (heavy cannon
     * splash, EXPLOSIVE asteroid death) instead of a flat plasma billboard.
     * Renders as a Y-axis-aligned UV-sphere through the additive pipeline
     * with the fire-material shader branch (`abs(vNormal.y)` Fresnel + heat
     * ramp + FBM turbulence). `baseRadius` matches the AoE damage radius.
     *
     * The scene generator drives three curves over life (t = age/maxLife):
     *  - scale: ease-out quadratic (fast start, asymptotic at end)
     *  - colour: lerp FIREBALL_TINT_START → FIREBALL_TINT_END
     *  - brightness: sqrt(1-t) (holds longer initially so the colour shift
     *    stays visible as the ball cools)
     *
     * `intensity` is a per-event volume knob: future callers (smaller pops,
     * bigger climactic blasts) can pass <1 or >1 to scale the whole curve
     * without touching the constants.
     */
    private data class Fireball(
        val x: Float, val z: Float,
        var life: Float, val maxLife: Float,
        val baseRadius: Float,
        val intensity: Float = 1f,
        // E10.3 — previous-frame `life` value. Fireballs don't translate but
        // their scale curve advances each tick (ease-out quad on age = 1 -
        // life/maxLife), so the rendered model matrix grows between frames.
        // Snapshotting prevLife at end-of-tick lets buildScene reconstruct
        // last frame's scale and feed motion blur a real prev_model.
        var prevLife: Float = life,
    )
    private val fireballs: MutableList<Fireball> = mutableListOf()

    /**
     * E9 — particle. Lives in Kotlin (matches "Kotlin owns scene"
     * architecture); ticked here, packed into a FloatArray once per frame
     * and shipped to the engine in one batched JNI call. `mode` chooses
     * pipeline (additive sparks vs alpha-textured smoke/debris) and
     * picks the texture forwarded to the draw call.
     *
     * Shape (rotation, deformation) is procedural in shaders for additive
     * mode and texture-driven for alpha mode — the runtime only owns
     * position, velocity, age, size, colour.
     */
    private data class Particle(
        var x: Float, var y: Float, var z: Float,
        var vx: Float, var vy: Float, var vz: Float,
        var age: Float, val life: Float,
        val size: Float,
        val r: Float, val g: Float, val b: Float, val a: Float,
        // Optional drag-style velocity damping: vx *= (1 - dragPerSec * dt).
        // 0 = no damping (sparks fly straight). Used for debris that
        // settles and smoke that slows.
        val drag: Float = 0f,
        // Optional vertical (Z) gravity in screen-space — positive pulls
        // particles "down" (toward platform). Sparks drift, debris falls.
        val gravity: Float = 0f,
    )

    // Two pools, one per pipeline. Capped at engine kMaxParticles (4096)
    // each; runaway emitters are limited at the engine boundary too.
    private val sparkParticles:  MutableList<Particle> = mutableListOf()
    private val smokeParticles:  MutableList<Particle> = mutableListOf()  // alpha-textured smoke
    private val debrisParticles: MutableList<Particle> = mutableListOf()  // alpha-textured chunks
    private data class Asteroid(
        // Stable identity for cross-frame references (priority target lock,
        // homing missile target). Asteroids cannot be referenced by list index
        // because the list compacts on death.
        val id: Long,
        val xPos: Float,
        var zPos: Float,
        var hp: Int,
        // Captured at spawn from `hp` so the HP-bar fill can read fraction =
        // hp / maxHp without recomputing from mission × type multipliers.
        val maxHp: Int = hp,
        var rotation: Float = 0f,         // current angle in radians
        val rotationSpeed: Float = 0f,    // radians per second; randomised on spawn
        val type: AsteroidType = AsteroidType.NORMAL,
        // Per-asteroid effective values, derived from mission baseline × type
        // multipliers at spawn. Cached so the tick doesn't recompute every frame.
        val speed: Float = 0f,            // units/sec downward
        val half:  Float = DraftCombat.ASTEROID_HALF,
        val platformDmg: Int = DraftCombat.PLATFORM_DMG_PER_HIT,
        // Picked at spawn from the per-type mesh pool (NORMAL/FAST randomize
        // across two grey variants for visual diversity). 0 = engine fallback.
        val meshHandle: Long = 0L,
        // E10.3 — previous-frame z position and rotation for motion-vector
        // tracking. xPos doesn't change (asteroids fall straight down) so we
        // don't need a prevX; but the spin around Z DOES move screen-pixels
        // around the asteroid silhouette so we cache it here. Snapshotted
        // BEFORE applying the per-frame movement so the next frame's render
        // has the matrix-pair that produced the current visible motion.
        var prevZ: Float = zPos,
        var prevRotation: Float = rotation,
    )
    private val bullets:    MutableList<Bullet>   = mutableListOf()
    private val asteroids:  MutableList<Asteroid> = mutableListOf(
        Asteroid(id = 1L, xPos = -1.5f, zPos = 8.5f, hp = 100),
        Asteroid(id = 2L, xPos =  1.0f, zPos = 8.0f, hp = 100),
    )
    private var nextAsteroidId: Long = 3L
    private fun newAsteroidId(): Long = nextAsteroidId++
    // Central turret fire cooldown — counts DOWN regardless of touch state.
    // A new shot is allowed only when this hits 0; on fire it's reset to the
    // weapon's `fireIntervalSec`. This ensures rapid taps can't bypass the
    // weapon's intended rate of fire (the previous `fireTimer` was primed on
    // every ACTION_DOWN, which made tap-spam fire one shot per tap).
    private var centralFireCooldown: Float = 0f
    private var spawnTimer: Float = 0f
    // Wave runtime state (set by startMission, advanced by tick).
    private var currentMission:    MissionConfig? = null
    private var currentWaveIndex:  Int   = 0
    private var currentWaveSpawned: Int  = 0
    private var waveBreakTimer:    Float = 0f
    // Effective per-run combat values, derived from upgrade levels at startMission.
    // Central turret = main weapon (player-controlled). Side turrets = automatic
    // support (~50% damage of the central turret at the same upgrade level).
    private var effectiveMainWeaponDamage: Int = UpgradeCatalog.mainWeaponDamageAt(1)
    private var effectiveTurretDamage:     Int = UpgradeCatalog.sideTurretDamageAt(1)
    // Active weapon equipped on the central turret. Single-weapon for now;
    // M2.3 will introduce a pre-mission weapon-select screen that sets this.
    @Volatile private var currentWeapon: Weapon = WeaponCatalog.AUTOMATIC

    // Shield — permanent barrier with HP. Asteroids that touch the platform
    // chip its HP first; only when shield HP runs out does the platform take
    // damage. The player holds the shield button to recharge — energy
    // drains while pressed, shield HP refills proportionally. No on/off
    // toggle; the arch is rendered whenever shieldHp > 0.
    @Volatile private var shieldHp:         Float   = DraftCombat.SHIELD_MAX_HP
    @Volatile private var shieldRecharging: Boolean = false
    private var shieldUiPctLast: Int = -1   // last shown HP percentage (UI throttle)

    // Buff system (single slot). When `activeBuffTimer > 0`, the central
    // turret's per-shot damage is multiplied by `activeBuffDamageMul`. Set by
    // ENERGY-asteroid kills; ticked down each frame.
    @Volatile private var activeBuffTimer:       Float = 0f
    @Volatile private var activeBuffDamageMul:   Float = 1f
    private var buffUiSecLast: Int = -1
    // Energy resource (M8.3) — fuel for active abilities (rocket strike,
    // laser strike, future ones). Regenerates passively in-mission;
    // between missions the player will be able to upgrade ENERGY_MAX and
    // ENERGY_REGEN_PER_SEC for metal (separate milestone).
    @Volatile private var energy: Float = DraftCombat.ENERGY_MAX
    private var energyUiLast: Int = -1

    // Ability framework (M8.4). Each slot pairs a static `Ability` descriptor
    // with its runtime cooldown. Slots are created once and never resized;
    // `cdUiLast` throttles the per-second countdown text refresh.
    private data class AbilitySlot(
        val ability: Ability,
        var currentCd: Float = 0f,
        var cdUiLast: Int = -1,
    )
    private val abilitySlots: List<AbilitySlot> = listOf(
        AbilitySlot(AbilityCatalog.ROCKET_STRIKE),
        AbilitySlot(AbilityCatalog.LASER_STRIKE),
    )
    // Targeted abilities (e.g. laser) arm on first tap and consume the
    // following screen interaction. While non-null, the engine surface
    // touch handler routes drags to the armed ability instead of setting
    // a priority target. Cleared on activation, on second tap of the same
    // ability button (cancel), or on mission end.
    @Volatile private var armedAbilityId: AbilityId? = null
    // M8.6 — laser strike gesture endpoints (world coords). Set on
    // ACTION_DOWN while LASER_STRIKE is armed; consumed on ACTION_UP.
    @Volatile private var laserStartX: Float = 0f
    @Volatile private var laserStartZ: Float = 0f
    @Volatile private var laserGestureActive: Boolean = false
    // DRAFT — turret state. Two static blue squares on the platform; each fires
    // at the nearest asteroid. Kept simple (per-turret fire timer only).
    private val turretXs       = floatArrayOf(-1.8f, 1.8f)
    private val turretFireT    = floatArrayOf(0f, 0f)

    @Volatile private var platformHP: Int = 100
    // Persistent player state. Loaded from SharedPreferences in onCreate.
    @Volatile private var gameProgress: GameProgress = GameProgress()
    private lateinit var progressRepo: ProgressRepository
    // In-flight stats for the current mission attempt. Reset on each game start.
    private val missionRun: MissionRun = MissionRun()
    private lateinit var hudPanel:          View
    private lateinit var hudMissionText:    TextView
    private lateinit var hudWaveText:       TextView
    private lateinit var hudScoreText:      TextView
    private lateinit var hudHpText:         TextView
    private lateinit var hudEnergyText:     TextView
    private lateinit var waveAnnounceText:  TextView
    private lateinit var shieldButton:      TextView
    private lateinit var abilityBar:        LinearLayout
    private lateinit var abilityButtons:    List<TextView>
    private lateinit var buffIndicator:     TextView
    private lateinit var abortMissionBtn:   TextView
    private lateinit var fpsLabel:          TextView
    private val fpsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val fpsUpdater = object : Runnable {
        override fun run() {
            fpsLabel.text = "FPS ${engineView.fps.toInt()}"
            fpsHandler.postDelayed(this, 500L)
        }
    }

    // DRAFT — game state machine. MENU on launch; PLAYING starts on Play tap;
    // WON/LOST when conditions hit. Tick only advances when PLAYING.
    private enum class GameState { MENU, PLAYING, WON, LOST }
    @Volatile private var gameState: GameState = GameState.MENU
    private lateinit var menuOverlay:          View
    private lateinit var missionSelectOverlay: View
    // Rebuilt each time it is shown — content depends on the picked mission and
    // the current weapon (to highlight the active card).
    private var weaponSelectOverlay: View? = null
    // Rebuilt on each show — content depends on currentMission and missionRun.
    private var winOverlay:        View? = null
    private var loseOverlay:       View? = null
    private var upgradesOverlay:   View? = null
    private var upgradesReturnTo:  (() -> Unit)? = null
    private object DraftCombat {
        // Side-turret fire rate. The side turrets are cannon-style (heavy
        // bullet + AoE) since the muzzle-blast / motion-blur rework, so
        // this is paced like the central heavy cannon (1 shot/sec) rather
        // than the original M1 machine-gun cadence (~6.7 shots/sec).
        const val FIRE_INTERVAL_SEC: Float = 1.0f
        // Side-turret bullet specs (cannon-style). Mirrors the central
        // HEAVY_CANNON weapon's projectile parameters; differences in damage
        // scaling are handled by SIDE_DAMAGE_MUL applied to the upgrade
        // ladder's effectiveTurretDamage.
        const val SIDE_BULLET_SPEED:    Float = 18f
        const val SIDE_BULLET_HALF_W:   Float = 0.065f
        const val SIDE_BULLET_HALF_H:   Float = 0.117f
        const val SIDE_DAMAGE_MUL:      Float = 3f
        const val SIDE_AOE_RADIUS:      Float = 0.5f
        const val SIDE_AOE_DAMAGE_MUL:  Float = 0.6f
        const val BULLET_SPEED:      Float = 25f     // world units per second
        const val BULLET_HALF_W:     Float = 0.04f   // ~1.6% screen width
        const val BULLET_HALF_H:     Float = 0.18f   // ~3.3% screen height
        const val SCREEN_TOP_Z:      Float = 9.49f
        const val ASTEROID_HALF:     Float = 0.1235f
        const val DAMAGE_PER_HIT:    Int   = 10
        const val ASTEROID_SPEED:    Float = 1.0f   // units/sec downward
        const val ASTEROID_HP_INIT:  Int   = 100
        const val SPAWN_INTERVAL_SEC:Float = 3.0f
        const val SCREEN_BOTTOM_Z:   Float = -1.49f
        const val SCREEN_HALF_W:     Float = 2.47f
        const val PLATFORM_TOP_Z:    Float = -0.94f // matches platform z + halfH
        const val PLATFORM_HP_INIT:  Int   = 100
        const val PLATFORM_DMG_PER_HIT: Int = 20
        const val TURRET_HALF:       Float = 0.10f  // side turret radius — ~4% screen width
        const val TURRET_TOP_Z:      Float = -0.84f // platform top + side-turret half
        const val TURRET_DMG:        Int   = 5      // half of DAMAGE_PER_HIT (legacy)
        // Central turret — main weapon. Tall rectangle (long Z axis = barrel)
        // sitting at platform centre. Pivot is at the base on the platform top;
        // the SceneObject's center is offset along the barrel direction so the
        // base stays glued to the platform when the turret rotates.
        const val CENTRAL_TURRET_X:        Float = 0f
        const val CENTRAL_TURRET_BASE_Z:   Float = -0.94f // platform top — rotation pivot
        const val CENTRAL_TURRET_HALF_W:   Float = 0.10f  // narrow barrel
        const val CENTRAL_TURRET_HALF_H:   Float = 0.30f  // ~3× side turret height
        // Aim-alignment threshold for the central turret. The turret only
        // fires once it's rotated essentially onto the target angle (within
        // ~1.15°). The exponential rotation has a long asymptotic tail, so a
        // loose threshold (e.g. 5°) visibly fires off-aim on big swings —
        // especially with the railgun's 1-sec cooldown, where one off-target
        // shot is very noticeable.
        const val AIM_ALIGN_THRESHOLD_RAD: Float = 0.02f
        // Generous radius around a finger tap for asteroid hit-testing in
        // world units. Asteroids smaller than this still take the full radius
        // (FAST half ≈ 0.21 — too thin for fingertips otherwise).
        const val TAP_PICK_RADIUS:        Float = 0.6f
        // HP-bar over each damaged asteroid (Kotlin-side scene assembly, no
        // engine work). Bar width = asteroid.half * 2 * HP_BAR_HALF_W_MUL;
        // sits HP_BAR_PADDING above the asteroid silhouette.
        const val HP_BAR_HALF_W_MUL:      Float = 0.8f
        const val HP_BAR_HALF_THICK:      Float = 0.04f
        const val HP_BAR_PADDING:         Float = 0.18f
        // Energy (M8.3) — pool size and passive regen rate. Tuned so a single
        // 30-cost rocket strike (M8.5) refills in 3 sec; a 50-cost laser
        // strike refills in 5. Will become per-run effective values once
        // metal-funded base upgrades land.
        const val ENERGY_MAX:             Float = 100f
        const val ENERGY_REGEN_PER_SEC:   Float = 10f
        // Rocket strike (M8.5). Three homing missiles spawned from the
        // central turret muzzle, each tracking one of the top-N most
        // dangerous asteroids. ROCKET_TURN_RATE_RAD_PER_SEC = 4.0 means a
        // missile can flip a full 180° in ~0.78 sec — fast enough to
        // chase moving FAST asteroids, slow enough to look like guided
        // ordnance, not perfect tracking.
        const val ROCKET_COUNT:                Int   = 3
        const val ROCKET_DAMAGE_MUL:           Float = 4f
        const val ROCKET_AOE_RADIUS:           Float = 0.4f
        const val ROCKET_AOE_DAMAGE_MUL:       Float = 0.6f
        const val ROCKET_SPEED:                Float = 14f
        const val ROCKET_HALF_W:               Float = 0.07f
        const val ROCKET_HALF_H:               Float = 0.13f
        const val ROCKET_TURN_RATE_RAD_PER_SEC: Float = 4f
        // Laser strike (M8.6). Player drags a line on the screen between
        // ACTION_DOWN and ACTION_UP; on release every asteroid whose
        // bounding circle intersects the segment takes LASER_DAMAGE.
        // LASER_HIT_PAD widens the segment's effective hit radius — a hair
        // generous so near-misses still count under finger tracking.
        const val LASER_DAMAGE:           Int   = 80
        const val LASER_HIT_PAD:          Float = 0.10f
        const val LASER_BOLT_COUNT:       Int   = 5
        const val LASER_BOLT_OFFSET_STEP: Float = 0.04f
        const val LASER_BOLT_LIFE:        Float = 0.35f
        const val LASER_BOLT_HALF_V:      Float = 0.045f
        // Reuse railgun cyan palette — laser is conceptually the same
        // electromagnetic discharge family.
        val LASER_TINT = floatArrayOf(0.85f, 0.95f, 1.00f, 1.40f)
        // Yaw correction applied to Bullet.glb / Bullet_Heavy.glb when oriented
        // along the velocity vector. atan2(vx, vz) aligns the model's local +Z
        // with the flight direction; the bullet .glbs are authored with their
        // long axis along +X (bbox [0.02..0.72] in X, ±0.18 in Y/Z), so we
        // rotate by -PI/2 so that +X (rest pose nose direction) maps onto +Z
        // (velocity-aligned forward axis).
        const val BULLET_MODEL_YAW_OFFSET: Float = -1.5707963f
        // Uniform scale for the bullet model. The .glb is ~0.7 units long;
        // we want it roughly the size of the previous quad placeholder
        // (≈0.36 units long when scaled by halfH=0.18). 2× brings it visually
        // on par with the trail/muzzle flash so the projectile is readable
        // alongside its VFX instead of vanishing into the additive haze.
        const val BULLET_MODEL_SCALE_MUL: Float = 2.0f
        const val WAVE_BREAK_SEC:    Float = 2.0f
        const val FLASH_LIFE_SEC:    Float = 0.25f
        const val FLASH_HALF:        Float = 0.20f
        // E7.1 — 3D fireball explosion (AoE hits, EXPLOSIVE asteroid deaths).
        // Longer than the regular flash because it's a more substantial event
        // and the additive sphere needs time for the FBM turbulence to read
        // as fire instead of a static blob.
        const val FIREBALL_LIFE_SEC: Float = 0.50f
        // M7.1 VFX — turret muzzle, projectile hit flash, AoE ring.
        const val MUZZLE_FLASH_LIFE: Float = 0.08f
        const val MUZZLE_FLASH_HALF: Float = 0.39f  // 3× of pre-E11 0.13 for cone-shape blast
        // E10.4-trails — direct (non-AoE) hit flash. Sized as
        // `bullet.halfW * HIT_FLASH_SIZE_MUL`, so cannon bullets (halfW≈0.065)
        // get a 0.20-half flash and machine-gun bullets (halfW≈0.04) get a
        // 0.12-half flash without any per-weapon flag. AoE bullets skip
        // this and run spawnExplosion (fireball + spark burst) instead.
        const val HIT_FLASH_LIFE:    Float = 0.12f
        const val HIT_FLASH_SIZE_MUL:Float = 3.0f
        // E5.1 — per-event flash tints (RGBA), multiplied into the plasma
        // fragment heat-ramp. RGB channels recolour the warm-flame baseline;
        // alpha is an overall brightness scalar (>1 = boost). White (default)
        // keeps the E4 look. Tunable; non-const because Kotlin disallows
        // const FloatArray in companion objects.
        val FLASH_TINT_MUZZLE     = floatArrayOf(1.00f, 0.95f, 0.70f, 1.00f)  // warm white-yellow
        val FLASH_TINT_HIT        = floatArrayOf(1.00f, 0.75f, 0.30f, 1.00f)  // warm orange impact
        val FLASH_TINT_ENERGY     = floatArrayOf(0.45f, 0.85f, 1.00f, 1.10f)  // cyan electric, slightly brighter
        val FLASH_TINT_DEATH      = floatArrayOf(1.00f, 0.85f, 0.40f, 1.00f)  // warm yellow burst
        val FLASH_TINT_SHIELD     = floatArrayOf(0.35f, 0.75f, 1.00f, 1.00f)  // blue shield deflection
        // E12 — railgun muzzle stack. Cyan-white core flash (bright Gaussian
        // pop in the barrel mouth) + cluster of procedural electric arcs
        // perpendicular to the barrel direction (the "discharge between the
        // rails" visual cue). Per-bolt parameters live here so the visual
        // can be retuned without touching gameplay code.
        const val RAILGUN_CORE_LIFE:  Float = 0.10f   // core flash lifetime (sec)
        const val RAILGUN_CORE_HALF:  Float = 0.4125f // core flash peak half-size (E12 −25%)
        val FLASH_TINT_RAILGUN_CORE = floatArrayOf(0.85f, 0.95f, 1.00f, 1.80f)  // ice-white, very bright
        const val RAILGUN_BOLT_LIFE_MIN: Float = 0.08f
        const val RAILGUN_BOLT_LIFE_MAX: Float = 0.16f
        const val RAILGUN_BOLT_HALF_MIN: Float = 0.3375f  // E12 −25%
        const val RAILGUN_BOLT_HALF_MAX: Float = 0.6375f  // E12 −25%
        const val RAILGUN_BOLT_COUNT_MIN:Int   = 5
        const val RAILGUN_BOLT_COUNT_MAX:Int   = 7
        // Spread (radians) around perpendicular-to-barrel direction. ±50°
        // gives a visible fan that still reads as "discharges between rails"
        // rather than radiating in all directions. Bias slightly forward
        // is unnecessary — symmetry around the perpendicular is the desired
        // railgun aesthetic.
        const val RAILGUN_BOLT_SPREAD_RAD: Float = 0.87f  // ~50°
        // Offset along the barrel direction so individual bolts root at
        // different points along the muzzle's "rail length", not all at the
        // exact muzzle tip. Tiny offset proportional to bolt size.
        const val RAILGUN_BOLT_BARREL_OFFSET_FRAC: Float = 0.18f
        val FLASH_TINT_RAILGUN_BOLT = floatArrayOf(0.90f, 0.95f, 1.00f, 1.40f)  // cool blue-white, bright
        // Cyan railgun-spark tint for the E9 muzzle-spark burst — replaces
        // the warm muzzle tint when firing the railgun. Slightly brighter
        // than the regular muzzle sparks for the "energetic discharge" read.
        val SPARK_TINT_RAILGUN     = floatArrayOf(0.55f, 0.90f, 1.00f)
        // E7.1 polish — fireball colour curve. Lerp start → end over life
        // gives a "hot fresh blast → cooling embers" read instead of a
        // single static orange. Brightness is handled separately via the
        // pc.plasmaColor.a scalar in buildScene.
        val FIREBALL_TINT_START   = floatArrayOf(1.00f, 0.65f, 0.20f)  // saturated forge-orange
        val FIREBALL_TINT_END     = floatArrayOf(0.90f, 0.18f, 0.05f)  // deep dying-ember red

        // E9 — particle balance. AoE sparks fan out fast and dim quickly;
        // muzzle micro-sparks are short-lived punctuation; asteroid-death
        // debris falls under mild gravity and asteroid-death smoke lingers.
        // Tunable in one place so density/speed feel can be retuned without
        // hunting through spawn sites.
        const val SPARK_AOE_COUNT_MIN:    Int   = 50
        const val SPARK_AOE_COUNT_MAX:    Int   = 70
        const val SPARK_AOE_SPEED_MIN:    Float = 1.6f
        const val SPARK_AOE_SPEED_MAX:    Float = 3.4f
        const val SPARK_AOE_LIFE_MIN:     Float = 0.25f
        const val SPARK_AOE_LIFE_MAX:     Float = 0.55f
        const val SPARK_AOE_SIZE_MIN:     Float = 0.04f
        const val SPARK_AOE_SIZE_MAX:     Float = 0.09f
        const val SPARK_AOE_DRAG:         Float = 1.5f

        const val SPARK_MUZZLE_COUNT_MIN: Int   = 3
        const val SPARK_MUZZLE_COUNT_MAX: Int   = 5
        const val SPARK_MUZZLE_SPEED_MIN: Float = 0.8f
        const val SPARK_MUZZLE_SPEED_MAX: Float = 1.6f
        const val SPARK_MUZZLE_LIFE_MIN:  Float = 0.08f
        const val SPARK_MUZZLE_LIFE_MAX:  Float = 0.16f
        const val SPARK_MUZZLE_SIZE_MIN:  Float = 0.03f
        const val SPARK_MUZZLE_SIZE_MAX:  Float = 0.06f
        const val SPARK_MUZZLE_DRAG:      Float = 2.5f
        // Cone half-angle around the bullet velocity vector so the muzzle
        // sparks shoot mostly forward, not omnidirectional.
        const val SPARK_MUZZLE_CONE_RAD:  Float = 0.7f  // ~40°

        const val DEBRIS_COUNT_MIN:       Int   = 4
        const val DEBRIS_COUNT_MAX:       Int   = 8
        const val DEBRIS_SPEED_MIN:       Float = 0.4f
        const val DEBRIS_SPEED_MAX:       Float = 1.1f
        const val DEBRIS_LIFE_MIN:        Float = 0.50f
        const val DEBRIS_LIFE_MAX:        Float = 0.90f
        const val DEBRIS_SIZE_MIN:        Float = 0.07f
        const val DEBRIS_SIZE_MAX:        Float = 0.15f
        const val DEBRIS_GRAVITY:         Float = 1.2f   // -Z accel
        const val DEBRIS_DRAG:            Float = 0.6f

        const val SMOKE_DEATH_COUNT_MIN:  Int   = 3
        const val SMOKE_DEATH_COUNT_MAX:  Int   = 5
        const val SMOKE_DEATH_SPEED_MIN:  Float = 0.15f
        const val SMOKE_DEATH_SPEED_MAX:  Float = 0.45f
        const val SMOKE_DEATH_LIFE_MIN:   Float = 0.55f
        const val SMOKE_DEATH_LIFE_MAX:   Float = 0.95f
        const val SMOKE_DEATH_SIZE_MIN:   Float = 0.18f
        const val SMOKE_DEATH_SIZE_MAX:   Float = 0.32f
        const val SMOKE_DEATH_DRAG:       Float = 0.8f
        // Reload bar — strip on the lower part of the platform (the upper part
        // is overlapped by the ЩИТ button overlay, which composites on top of
        // the engine surface, so a bar placed there gets hidden). Anchored
        // horizontally under the central turret. Fill width = readiness.
        const val RELOAD_BAR_HALF_W:        Float = 0.40f
        const val RELOAD_BAR_Z:             Float = -0.30f
        const val RELOAD_BAR_HALF_THICK:    Float = 0.04f
        // Shield ability — base protection. Single charge with cooldown.
        // Shield (M9 redesign) — permanent HP-based barrier. Recharge is
        // hold-to-fill: every second the player holds the shield button,
        // SHIELD_RECHARGE_ENERGY_PER_SEC energy drains and SHIELD_RECHARGE_HP_PER_SEC
        // shield-HP is restored (clamped to SHIELD_MAX_HP). 4× ratio means
        // a full energy bar (100) buys 400 shield-HP — close to a full
        // refill from empty.
        const val SHIELD_MAX_HP:                Float = 500f
        const val SHIELD_RECHARGE_ENERGY_PER_SEC: Float = 50f
        const val SHIELD_RECHARGE_HP_PER_SEC:     Float = 200f
        // Arch geometry — wide flat ellipse over the full platform width.
        // halfW ≈ screen-half-width; halfH controls how high the arch
        // peaks above the platform top.
        const val SHIELD_ARCH_HALF_W:    Float = 2.40f
        const val SHIELD_ARCH_HALF_H:    Float = 1.00f
        const val SHIELD_ARCH_THICKNESS: Float = 0.06f
        const val SHIELD_ARCH_PEAK_ALPHA: Float = 0.85f
        // M5 — special asteroid death effects.
        const val EXPLOSIVE_AOE_RADIUS:  Float = 0.5f   // same as heavy cannon AoE
        const val EXPLOSIVE_AOE_DAMAGE:  Int   = 30     // splash damage to neighbours
        const val ENERGY_BUFF_DURATION:  Float = 5.0f   // seconds
        const val ENERGY_BUFF_DAMAGE_MUL:Float = 2.0f   // central turret ×2 damage
    }
    private val selectedShipIds: MutableSet<Int> = linkedSetOf()
    private var selectedTargetId: Int = -1

    // DRAFT — Asteroid Outpost: no allied fighters in the scene. The game is
    // tower-defense around a single platform; the central cannon and 4 turret
    // slots will be added as proper geometry/UI in M1, not as ship placeholders.
    private val shipFormation: List<Triple<Float, Float, Float>> = emptyList()

    // ---------------------------------------------------------------------------
    // Camera orientation tracking (approximate, for axis indicator)
    // ---------------------------------------------------------------------------
    private val camRotMatrix = FloatArray(16)
    private val camRotTemp   = FloatArray(16)

    // ---------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.data?.getBooleanExtra(SettingsActivity.EXTRA_RESTART, false) == true) {
            resetScene()
        }
        applySettings()
        hideSettingsPanel()
    }

    // ---------------------------------------------------------------------------
    // Station AI
    // ---------------------------------------------------------------------------
    private lateinit var stationAI: StationAI
    private lateinit var enemyAI:   StationAI
    private lateinit var simWorld:  SimulationWorld
    private var patrolActive: Boolean = false
    private val classifier: CommandClassifier by lazy { CommandClassifier(this) }
    private var speechRecognizer: SpeechRecognizer? = null
    private var micActive = false

    // ---------------------------------------------------------------------------
    // Flight mission (MissionController — independent ShipAgent physics)
    // ---------------------------------------------------------------------------
    private var missionCtrl: MissionController? = null

    // ---------------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------------
    private var missionThread:  HandlerThread? = null
    private var missionHandler: Handler?       = null

    // ---------------------------------------------------------------------------
    // Sound
    // ---------------------------------------------------------------------------
    private lateinit var soundPool:  SoundPool
    private var soundEngineId: Int = 0
    private var soundStreamId: Int = 0
    private var soundShootId:  Int = 0
    private var bgMusic: MediaPlayer? = null

    companion object {
        private const val TICK_MS = 20L
        private const val BUILD_DURATION_SEC = 10f
        private const val INITIAL_CAM_PITCH         = 0.75f   // radians, matches C++ camera init
        private const val JOYSTICK_PAN_STEP         = 8.0f
        private const val JOYSTICK_ZOOM_FACTOR_STEP = 0.004f
        private const val JOYSTICK_ORBIT_STEP       = 0.035f
        private const val JOYSTICK_ROLL_STEP        = 0.025f

        private val STATION_SHAPE = PolygonShape(
            listOf(
                Vec2(-110f, -115f), Vec2(115f, -115f), Vec2(125f, 0f),
                Vec2(115f, 125f),   Vec2(-110f, 125f), Vec2(-125f, 0f),
            )
        )
        private val FIGHTER_SHAPE = PolygonShape(
            listOf(
                Vec2(0.0f, 1.05f),   Vec2(0.65f, 0.15f),  Vec2(0.45f, -0.85f),
                Vec2(0.0f, -1.05f),  Vec2(-0.45f, -0.85f), Vec2(-0.65f, 0.15f),
            )
        )

        private const val ENEMY_FRAME_R = 1.0f
        private const val ENEMY_FRAME_G = 0.38f
        private const val ENEMY_FRAME_B = 0.34f

        private const val ALLIED_STATION_ID = 5
        // DRAFT — Asteroid Outpost: platform at the world origin, drone spawn area to the north.
        private val ALLIED_STATION_POS   = Vec2(0f,   0f)    // central platform
        private val ENEMY_STATION_POS    = Vec2(0f,  30f)    // drone spawn area, kept in-frame
        private const val PATROL_ORBIT_RADIUS       = 12f
        private const val ENEMY_PATROL_ORBIT_RADIUS = 18f
        /** Centroid of the initial ship formation — the fleet rally point. */
        private val FLEET_RALLY_CENTER  = Vec2(0f, -10f)     // built fighters rally behind platform
    }

    // ---------------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)
        engineView       = findViewById(R.id.engineView)
        selectionOverlay = findViewById(R.id.selectionOverlay)
        axisPanel        = findViewById(R.id.axisPanel)
        axisIndicator    = findViewById(R.id.axisIndicator)
        settingsPullTab  = findViewById(R.id.settingsPullTab)
        rebuildCamMatrix()
        btnSettings      = findViewById(R.id.btnSettings)
        btnCommands      = findViewById(R.id.btnCommands)
        commandsDrawer   = findViewById(R.id.commandsDrawer)
        btnBuild         = findViewById(R.id.btnBuild)
        buildDrawer      = findViewById(R.id.buildDrawer)
        btnBuildFighter  = findViewById(R.id.btnBuildFighter)
        btnAttack        = findViewById(R.id.btnAttack)
        btnDefend        = findViewById(R.id.btnDefend)
        btnFlyAround     = findViewById(R.id.btnFlyAround)
        btnPatrol        = findViewById(R.id.btnPatrol)
        btnHome          = findViewById(R.id.btnHome)
        btnMic           = findViewById(R.id.btnMic)
        cameraJoystick   = findViewById(R.id.cameraJoystick)
        shipCard         = findViewById(R.id.shipCard)
        shipCardTitle    = findViewById(R.id.shipCardTitle)
        shipCardSubtitle = findViewById(R.id.shipCardSubtitle)
        shipCardHint     = findViewById(R.id.shipCardHint)

        // Load persistent player progress (metal, upgrades, etc.).
        progressRepo = ProgressRepository(this)
        gameProgress = progressRepo.load()

        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            ).build()
        soundEngineId = soundPool.load(assets.openFd("sound/sound_engine.ogg"), 1)
        soundShootId  = soundPool.load(assets.openFd("sound/sound_shoot.ogg"),  1)

        bgMusic = MediaPlayer().apply {
            assets.openFd("sound/fon_1.mp3").use { fd ->
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            isLooping = true
            setVolume(0.25f, 0.25f)
            prepare()
            start()
        }

        engineView.initialize()
        loadShaders()

        btnSettings.setOnClickListener { settingsLauncher.launch(Intent(this, SettingsActivity::class.java)) }
        initSettingsPanel()
        btnCommands.setOnClickListener {
            if (commandsDrawer.visibility == View.VISIBLE) {
                commandsDrawer.visibility = View.GONE
            } else {
                adjustCommandsColumns()
                commandsDrawer.visibility = View.VISIBLE
            }
        }
        btnAttack.setOnClickListener    { commandsDrawer.visibility = View.GONE; startAttackMission() }
        btnDefend.setOnClickListener    { commandsDrawer.visibility = View.GONE; onVoiceText("Защищай станцию") }
        btnFlyAround.setOnClickListener { commandsDrawer.visibility = View.GONE; startFlightMission() }
        btnPatrol.setOnClickListener    { commandsDrawer.visibility = View.GONE; startPatrolMission() }
        btnHome.setOnClickListener      { commandsDrawer.visibility = View.GONE; returnToRallyPosition() }
        btnBuild.setOnClickListener {
            commandsDrawer.visibility = View.GONE
            buildDrawer.visibility =
                if (buildDrawer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        btnBuildFighter.setOnClickListener { startBuildFighter() }
        btnMic.setOnClickListener       { startListening() }

        // DRAFT — camera locked. The central turret runs auto-aim: it picks the
        // most dangerous asteroid (highest current HP, ties broken by nearest
        // to the turret) and fires at it whenever the cooldown allows. The
        // player taps an asteroid to override the auto-pick with a priority
        // lock — the turret then prefers that asteroid until it dies or
        // leaves the screen. Tap on empty space is a no-op (so accidental
        // misses don't release a deliberate lock).
        engineView.onCameraOrbited = { _, _ -> }
        engineView.onCameraRolled  = { _ -> }
        engineView.onCameraReset   = { }
        engineView.onTap = { _, _ -> }
        engineView.setOnTouchListener { _, event ->
            if (gameState == GameState.PLAYING) {
                // If a targeted ability is armed, the gesture feeds it
                // (e.g. drag a line for the laser strike) instead of
                // setting a priority target. M8.4 stub — real consumption
                // lands per-ability in M8.5/M8.6.
                if (armedAbilityId != null) {
                    handleArmedAbilityTouch(event)
                } else if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val w = engineView.width.toFloat()
                    val h = engineView.height.toFloat()
                    if (w > 0f && h > 0f) {
                        val worldX = (event.x / w - 0.5f) *
                                     (DraftCombat.SCREEN_HALF_W * 2f)
                        val zSpan  = DraftCombat.SCREEN_TOP_Z -
                                     DraftCombat.SCREEN_BOTTOM_Z
                        val worldZ = DraftCombat.SCREEN_TOP_Z -
                                     (event.y / h) * zSpan
                        val picked = pickAsteroidAt(worldX, worldZ)
                        if (picked != null) {
                            centralTargetId = picked.id
                        }
                    }
                }
            }
            true
        }

        // DRAFT — hide all g3 inherited UI overlays so only the scene is visible.
        listOf(
            cameraJoystick, btnSettings, btnCommands, commandsDrawer,
            btnBuild, buildDrawer, btnMic, shipCard,
            axisPanel, axisIndicator, settingsPullTab,
            findViewById<View>(R.id.statusText),
        ).forEach { it?.visibility = View.GONE }

        // Single sci-fi HUD anchored at top: left = mission + wave, right = score + HP,
        // ✕ embedded as the rightmost child (no separate floating button). Background
        // intentionally absent — HUD shouldn't compete visually with gameplay.
        val root = engineView.parent as FrameLayout
        hudPanel = buildHudPanel()
        val sideMargin = com.example.asteroidoutpost.game.UiTheme.dp(this, 12f)
        val topMargin  = com.example.asteroidoutpost.game.UiTheme.dp(this, 16f)
        val hudParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP,
        ).apply { setMargins(sideMargin, topMargin, sideMargin, 0) }
        root.addView(hudPanel, hudParams)

        // Ability bar — diegetic, sits on the platform area at the bottom
        // centre. Holds the shield button + 2 active-ability buttons (M8.4
        // rocket strike, laser strike) in one horizontal row so they share
        // a single visibility lifecycle and stay grouped on the platform.
        // Each button has its own state machine (shield: READY/ACTIVE/
        // COOLING; ability: READY/COOLING/INSUFFICIENT-ENERGY/ARMED).
        shieldButton = buildShieldButton()
        val theme = com.example.asteroidoutpost.game.UiTheme
        val barH        = theme.dp(this, 36f)
        val btnW        = theme.dp(this, 96f)
        val btnGapDp    = theme.dp(this, 8f)
        val btnLp = LinearLayout.LayoutParams(btnW, barH)
        val btnLpGap = LinearLayout.LayoutParams(btnW, barH).apply {
            marginStart = btnGapDp
        }
        abilityBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        abilityBar.addView(shieldButton, btnLp)
        // Build one button per slot, sharing the same dimensions as the
        // shield button so the row reads as a uniform diegetic control bar.
        val abilityBtns = ArrayList<TextView>(abilitySlots.size)
        abilitySlots.forEachIndexed { i, _ ->
            val btn = buildAbilityButton(i)
            abilityBtns.add(btn)
            abilityBar.addView(btn, btnLpGap)
        }
        abilityButtons = abilityBtns
        val barParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = theme.dp(this@MainActivity, 12f) }
        root.addView(abilityBar, barParams)
        refreshShieldButton()
        refreshAllAbilityButtons()

        // Abort ✕ button is built and added inside buildHudPanel() so it
        // shares the HUD's row and visibility — no separate FrameLayout
        // child here.

        // Buff indicator — small caption that appears under the HUD while a
        // buff (currently only ENERGY-asteroid main-weapon ×2) is active.
        // Hidden otherwise. Full icon + animation lands in M7.
        buffIndicator = TextView(this).apply {
            text = ""
            setTextColor(com.example.asteroidoutpost.game.UiTheme.COL_WARNING)
            textSize = com.example.asteroidoutpost.game.UiTheme.SP_BODY
            visibility = View.GONE
        }
        val buffTopMargin = com.example.asteroidoutpost.game.UiTheme.dp(this, 92f)
        val buffParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(0, buffTopMargin, 0, 0) }
        root.addView(buffIndicator, buffParams)

        // Big centered "Волна N" / "Финальная волна" announce text, fade in/out.
        waveAnnounceText = TextView(this).apply {
            text = ""
            setTextColor(com.example.asteroidoutpost.game.UiTheme.COL_ACCENT_BLUE)
            textSize = 44f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val waveAnnounceParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER,
        )
        root.addView(waveAnnounceText, waveAnnounceParams)

        // Diagnostic FPS readout — bottom-left corner, dim caption-size so it
        // doesn't compete with gameplay. Reads engineView.fps (sliding 1-sec
        // window updated by RenderThread). Polled every 500ms by fpsUpdater.
        fpsLabel = TextView(this).apply {
            text = "FPS —"
            setTextColor(com.example.asteroidoutpost.game.UiTheme.COL_TEXT_DIM)
            textSize = com.example.asteroidoutpost.game.UiTheme.SP_CAPTION * 0.7f
        }
        val fpsParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.START,
        ).apply {
            setMargins(
                com.example.asteroidoutpost.game.UiTheme.dp(this@MainActivity, 8f),
                0, 0,
                com.example.asteroidoutpost.game.UiTheme.dp(this@MainActivity, 8f),
            )
        }
        root.addView(fpsLabel, fpsParams)
        fpsHandler.post(fpsUpdater)

        // DRAFT — full-screen overlays for menu / mission select / win / lose.
        menuOverlay          = OverlayFactory.build(this, "Asteroid Outpost", "Играть")    { showMissionSelect() }
        missionSelectOverlay = OverlayFactory.buildMissionList(
            this,
            Missions.ALL,
            onStart = { showWeaponSelect(it) },
            onBack  = { goToMenu() },
        )
        OverlayFactory.setBody(menuOverlay, "Всего металла: ${gameProgress.metal}")
        // "Улучшения" button on menu — opens the upgrades overlay.
        // Win/lose overlays are built on demand in present*Overlay() and include
        // their own Upgrades button, so we don't add it here.
        OverlayFactory.addButton(menuOverlay, "Улучшения") { showUpgrades { menuOverlay.visibility = View.VISIBLE } }
        val fullScreen = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        root.addView(menuOverlay,          fullScreen)
        root.addView(missionSelectOverlay, fullScreen)
        missionSelectOverlay.visibility = View.GONE
        // Game starts in MENU — hide HUD until Play tapped.
        hudPanel.visibility = View.GONE
        abilityBar.visibility = View.GONE     // hidden outside PLAYING
        abortMissionBtn.visibility = View.GONE
        selectionOverlay.visibility = View.VISIBLE
        engineView.onScreenFrames = { frames ->
            latestScreenFrames = frames
            selectionOverlay.setFramesAndHealthBars(frames, latestHealthBars)
        }
        engineView.onSurfaceReady = { loadShipMesh() }
        updateShipCard()
        applySettings()
    }

    // ---------------------------------------------------------------------------
    // Asset loading
    // ---------------------------------------------------------------------------
    private fun loadShaders() {
        try {
            engineView.engine.setShader("vert", assets.open("shaders/triangle.vert.spv").readBytes())
            engineView.engine.setShader("frag", assets.open("shaders/triangle.frag.spv").readBytes())
            // E9 — particle shaders are optional from the engine's point
            // of view; if the asset is missing the engine still works
            // without particle pipelines. We always upload them here so
            // particle effects are available everywhere.
            engineView.engine.setShader("particle.vert", assets.open("shaders/particle.vert.spv").readBytes())
            engineView.engine.setShader("particle.frag", assets.open("shaders/particle.frag.spv").readBytes())
            // E10.1 — post-process shaders. If absent the engine falls
            // back to direct-to-swapchain rendering (won't happen in
            // practice once E10 is in, but keep the fallback path safe).
            engineView.engine.setShader("post.vert", assets.open("shaders/post.vert.spv").readBytes())
            engineView.engine.setShader("post.frag", assets.open("shaders/post.frag.spv").readBytes())
        } catch (e: Exception) {
            showStatus("Shader load failed: ${e.message}")
        }
    }

    private fun loadShipMesh() {
        try {
            val bytes = assets.open("models/ship.gltf").readBytes()
            shipMeshHandle = engineView.engine.loadMesh(bytes)
            if (shipMeshHandle == 0L) { showStatus("Mesh load failed"); return }
            projectileMeshHandle = engineView.engine.loadMeshColored(bytes, 0.15f, 0.85f, 1.0f)
            loadSelectionFrames()
            loadStation()
            if (!::simWorld.isInitialized) initWorld()
            buildScene()
        } catch (e: Exception) {
            showStatus("Model not found: models/ship.gltf")
        }
    }

    private fun loadStation() {
        try {
            val bytes = assets.open("models/station.glb").readBytes()
            stationMeshHandle = engineView.engine.loadMesh(bytes)
            // DRAFT — legacy tinted variant of station.glb. Outpost no longer
            // renders this; left as a defensive fallback if asteroid loads fail.
            asteroidMeshHandle = engineView.engine.loadMeshColored(bytes, 0.55f, 0.55f, 0.60f)
            if (stationMeshHandle == 0L) showStatus("Station load failed")
        } catch (e: Exception) {
            showStatus("Station not found: models/station.glb")
        }
        try {
            val quadBytes = assets.open("models/quad.gltf").readBytes()
            quadMeshHandle  = engineView.engine.loadMeshColored(quadBytes, 1.0f, 0.30f, 0.30f)
            quadGreyHandle  = engineView.engine.loadMeshColored(quadBytes, 0.55f, 0.55f, 0.60f)
            quadBlueHandle  = engineView.engine.loadMeshColored(quadBytes, 0.30f, 0.55f, 1.00f)
            quadFlashHandle = engineView.engine.loadMeshColored(quadBytes, 1.00f, 0.85f, 0.30f)
            quadHpBgHandle  = engineView.engine.loadMeshColored(quadBytes, 0.18f, 0.20f, 0.22f)
            quadHpFgHandle  = engineView.engine.loadMeshColored(quadBytes, 0.30f, 0.85f, 0.35f)
            if (quadMeshHandle  == 0L) quadMeshHandle  = engineView.engine.loadMesh(quadBytes)
            if (quadGreyHandle  == 0L) quadGreyHandle  = engineView.engine.loadMesh(quadBytes)
            if (quadBlueHandle  == 0L) quadBlueHandle  = engineView.engine.loadMesh(quadBytes)
            if (quadFlashHandle == 0L) quadFlashHandle = engineView.engine.loadMesh(quadBytes)
            if (quadHpBgHandle  == 0L) quadHpBgHandle  = engineView.engine.loadMesh(quadBytes)
            if (quadHpFgHandle  == 0L) quadHpFgHandle  = engineView.engine.loadMesh(quadBytes)
            if (quadMeshHandle == 0L || quadGreyHandle == 0L || quadBlueHandle == 0L || quadFlashHandle == 0L)
                showStatus("Quad load failed (handle=0)")
        } catch (e: Exception) {
            showStatus("Quad load failed: ${e.message}")
        }
        try {
            val a1 = assets.open("models/Asteroid_1.glb").readBytes()
            val a2 = assets.open("models/Asteroid_2.glb").readBytes()
            val a3 = assets.open("models/Asteroid_3.glb").readBytes()
            val a4 = assets.open("models/Asteroid_4.glb").readBytes()
            val a9 = assets.open("models/Asteroid_9.glb").readBytes()
            asteroidMeshGrey1     = engineView.engine.loadMeshColored(a1, 0.55f, 0.55f, 0.60f)
            asteroidMeshGrey2     = engineView.engine.loadMeshColored(a2, 0.55f, 0.55f, 0.60f)
            asteroidMeshHeavy     = engineView.engine.loadMeshColored(a3, 0.70f, 0.20f, 0.20f)
            asteroidMeshExplosive = engineView.engine.loadMeshColored(a4, 0.95f, 0.55f, 0.20f)
            asteroidMeshEnergy    = engineView.engine.loadMeshColored(a9, 0.30f, 0.85f, 0.95f)
            if (asteroidMeshGrey1 == 0L) asteroidMeshGrey1 = engineView.engine.loadMesh(a1)
            // Fallback any failed mesh to the grey-1 baseline so the scene still renders.
            if (asteroidMeshGrey2     == 0L) asteroidMeshGrey2     = asteroidMeshGrey1
            if (asteroidMeshHeavy     == 0L) asteroidMeshHeavy     = asteroidMeshGrey1
            if (asteroidMeshExplosive == 0L) asteroidMeshExplosive = asteroidMeshGrey1
            if (asteroidMeshEnergy    == 0L) asteroidMeshEnergy    = asteroidMeshGrey1
            if (asteroidMeshGrey1 == 0L) showStatus("Asteroid meshes load failed")
        } catch (e: Exception) {
            showStatus("Asteroid mesh load failed: ${e.message}")
        }
        try {
            val bulletBytes = assets.open("models/Bullet.glb").readBytes()
            val heavyBytes  = assets.open("models/Bullet_Heavy.glb").readBytes()
            // Brass-and-copper tint on the regular bullet so it reads warm against
            // the dark space background; heavier shell gets a slightly cooler steely
            // tone for a heftier feel.
            bulletMeshHandle      = engineView.engine.loadMeshColored(bulletBytes, 1.00f, 0.85f, 0.55f)
            bulletHeavyMeshHandle = engineView.engine.loadMeshColored(heavyBytes,  0.90f, 0.80f, 0.60f)
            if (bulletMeshHandle      == 0L) bulletMeshHandle      = engineView.engine.loadMesh(bulletBytes)
            if (bulletHeavyMeshHandle == 0L) bulletHeavyMeshHandle = engineView.engine.loadMesh(heavyBytes)
            if (bulletHeavyMeshHandle == 0L) bulletHeavyMeshHandle = bulletMeshHandle
            if (bulletMeshHandle == 0L) showStatus("Bullet meshes load failed")
        } catch (e: Exception) {
            showStatus("Bullet mesh load failed: ${e.message}")
        }
        setupBackgroundNebulae()
    }

    /**
     * Build a soft-edge disk mesh via `loadMeshRaw` (E1.3): a triangle fan
     * with the centre vertex fully opaque and the rim vertices at alpha=0.
     * Drawn through the translucent pipeline (E1.2) it reads as a soft round
     * blob with no visible quad edges. Disk lies in the X-Z plane to match
     * the existing camera convention.
     */
    private fun buildSoftDiskMesh(r: Float, g: Float, b: Float, sectors: Int = 24): Long {
        val nVerts = sectors + 1
        val vertices = FloatArray(nVerts * 10)
        // Centre vertex: position (0,0,0), RGBA opaque, normal (0,1,0).
        vertices[0] = 0f; vertices[1] = 0f; vertices[2] = 0f
        vertices[3] = r;  vertices[4] = g;  vertices[5] = b; vertices[6] = 1f
        vertices[7] = 0f; vertices[8] = 1f; vertices[9] = 0f
        // Rim vertices on a unit circle, alpha=0 so the colour fades out.
        for (s in 0 until sectors) {
            val ang = (s.toDouble() * 2.0 * Math.PI / sectors).toFloat()
            val off = (s + 1) * 10
            vertices[off + 0] = kotlin.math.cos(ang)
            vertices[off + 1] = 0f
            vertices[off + 2] = kotlin.math.sin(ang)
            vertices[off + 3] = r
            vertices[off + 4] = g
            vertices[off + 5] = b
            vertices[off + 6] = 0f  // transparent rim
            vertices[off + 7] = 0f
            vertices[off + 8] = 1f
            vertices[off + 9] = 0f
        }
        // Triangle fan: centre → rim[s] → rim[s+1]
        val indices = ShortArray(sectors * 3)
        for (s in 0 until sectors) {
            indices[s * 3 + 0] = 0
            indices[s * 3 + 1] = (s + 1).toShort()
            indices[s * 3 + 2] = ((s + 1) % sectors + 1).toShort()
        }
        return engineView.engine.loadMeshRaw(vertices, indices)
    }

    /**
     * Procedural shield dome mesh (E2.2 + E3.3). Supports two topologies:
     *
     *  1. Annular ring (centerAlpha = 0): three concentric half-arcs at
     *     radii `innerR`/`midR`/`outerR` with alphas 0/peakAlpha/0. Used in
     *     E2.2 for the bright-rim-only force-field silhouette.
     *
     *  2. Filled half-disk with rim peak (centerAlpha > 0): adds a centre
     *     vertex with `centerAlpha`, an inner triangle-fan from centre to
     *     the `midR` arc fills the interior at peakAlpha, and the outer
     *     strip from `midR` to `outerR` fades to 0. Used by E3.3 so the
     *     hex-pattern alpha modulation has a continuous surface across the
     *     whole dome (not just a thin ring).
     *
     * Both topologies live on the upper half-circle (θ ∈ [0, π]) in the
     * X-Z plane, drawn through the translucent pipeline. Inner-fan triangles
     * interpolate alpha linearly from `centerAlpha` at the centre to
     * `peakAlpha` at the mid-arc.
     */
    /**
     * Wide flat shield arch. Vertices are placed in **world coordinates**
     * (pre-scaled to SHIELD_ARCH_HALF_W × SHIELD_ARCH_HALF_H), so the
     * SceneObject just needs scale=1 + a translation to platform top.
     * This avoids the directional thickness distortion that comes from
     * scaling a unit half-circle non-uniformly via SceneObject.scaleX/Z.
     *
     * Three concentric rings (inner / mid / outer) offset along the
     * outward ellipse normal by ±thickness/2, with per-vertex alpha
     * 0 / peak / 0 → smooth glow band of constant world-space thickness
     * around the arc. Same triangle-strip wiring as the legacy dome.
     */
    private fun buildShieldArchMesh(): Long {
        val sectors  = 64
        val halfW    = DraftCombat.SHIELD_ARCH_HALF_W
        val halfH    = DraftCombat.SHIELD_ARCH_HALF_H
        val tHalf    = DraftCombat.SHIELD_ARCH_THICKNESS * 0.5f
        val r = 0.45f; val g = 0.75f; val b = 1.00f
        val alphas   = floatArrayOf(0f, DraftCombat.SHIELD_ARCH_PEAK_ALPHA, 0f)

        val nVertsPerArc = sectors + 1
        val nVerts = nVertsPerArc * 3
        val verts  = FloatArray(nVerts * 10)
        for (ring in 0..2) {
            val offMul = (ring - 1).toFloat()  // -1, 0, +1
            for (s in 0..sectors) {
                val ang = (s.toDouble() * Math.PI / sectors).toFloat()
                val cx = kotlin.math.cos(ang)
                val cz = kotlin.math.sin(ang)
                // Outward normal of the ellipse at (cx*halfW, cz*halfH).
                // Gradient (cx/halfW, cz/halfH) normalized.
                val gx = cx / halfW
                val gz = cz / halfH
                val gl = kotlin.math.sqrt(gx * gx + gz * gz).coerceAtLeast(1e-6f)
                val nx = gx / gl
                val nz = gz / gl
                val px = cx * halfW + nx * tHalf * offMul
                val pz = cz * halfH + nz * tHalf * offMul
                val off = (ring * nVertsPerArc + s) * 10
                verts[off + 0] = px
                verts[off + 1] = 0f
                verts[off + 2] = pz
                verts[off + 3] = r; verts[off + 4] = g; verts[off + 5] = b
                verts[off + 6] = alphas[ring]
                verts[off + 7] = 0f; verts[off + 8] = 1f; verts[off + 9] = 0f
            }
        }
        val indices = ShortArray(2 * sectors * 6)
        var idx = 0
        for (strip in 0..1) {
            val r0 = strip; val r1 = strip + 1
            for (s in 0 until sectors) {
                val v0 = (r0 * nVertsPerArc + s    ).toShort()
                val v1 = (r0 * nVertsPerArc + s + 1).toShort()
                val v2 = (r1 * nVertsPerArc + s    ).toShort()
                val v3 = (r1 * nVertsPerArc + s + 1).toShort()
                indices[idx++] = v0; indices[idx++] = v1; indices[idx++] = v2
                indices[idx++] = v1; indices[idx++] = v3; indices[idx++] = v2
            }
        }
        return engineView.engine.loadMeshRaw(verts, indices)
    }

    private fun buildDomeMembraneMesh(
        r: Float, g: Float, b: Float,
        peakAlpha: Float = 0.85f,
        centerAlpha: Float = 0f,
        innerR: Float = 0.85f,
        midR:   Float = 0.92f,
        outerR: Float = 1.00f,
        sectors: Int = 48,
    ): Long {
        return if (centerAlpha > 0f) {
            buildFilledDomeMesh(r, g, b, peakAlpha, centerAlpha, midR, outerR, sectors)
        } else {
            buildAnnularDomeMesh(r, g, b, peakAlpha, innerR, midR, outerR, sectors)
        }
    }

    private fun buildAnnularDomeMesh(
        r: Float, g: Float, b: Float,
        peakAlpha: Float, innerR: Float, midR: Float, outerR: Float, sectors: Int,
    ): Long {
        val nVertsPerArc = sectors + 1
        val nVerts = nVertsPerArc * 3
        val vertices = FloatArray(nVerts * 10)
        val radii  = floatArrayOf(innerR, midR, outerR)
        val alphas = floatArrayOf(0f, peakAlpha, 0f)
        for (ring in 0..2) {
            for (s in 0..sectors) {
                val ang = (s.toDouble() * Math.PI / sectors).toFloat()
                val off = (ring * nVertsPerArc + s) * 10
                vertices[off + 0] = kotlin.math.cos(ang) * radii[ring]
                vertices[off + 1] = 0f
                vertices[off + 2] = kotlin.math.sin(ang) * radii[ring]
                vertices[off + 3] = r; vertices[off + 4] = g; vertices[off + 5] = b
                vertices[off + 6] = alphas[ring]
                vertices[off + 7] = 0f; vertices[off + 8] = 1f; vertices[off + 9] = 0f
            }
        }
        val indices = ShortArray(2 * sectors * 6)
        var idx = 0
        for (strip in 0..1) {
            val ring0 = strip; val ring1 = strip + 1
            for (s in 0 until sectors) {
                val v0 = (ring0 * nVertsPerArc + s    ).toShort()
                val v1 = (ring0 * nVertsPerArc + s + 1).toShort()
                val v2 = (ring1 * nVertsPerArc + s    ).toShort()
                val v3 = (ring1 * nVertsPerArc + s + 1).toShort()
                indices[idx++] = v0; indices[idx++] = v1; indices[idx++] = v2
                indices[idx++] = v1; indices[idx++] = v3; indices[idx++] = v2
            }
        }
        return engineView.engine.loadMeshRaw(vertices, indices)
    }

    private fun buildFilledDomeMesh(
        r: Float, g: Float, b: Float,
        peakAlpha: Float, centerAlpha: Float, midR: Float, outerR: Float, sectors: Int,
    ): Long {
        val ringSize = sectors + 1
        val nVerts = 1 + 2 * ringSize
        val vertices = FloatArray(nVerts * 10)
        fun put(idx: Int, x: Float, z: Float, alpha: Float) {
            val off = idx * 10
            vertices[off + 0] = x; vertices[off + 1] = 0f; vertices[off + 2] = z
            vertices[off + 3] = r; vertices[off + 4] = g; vertices[off + 5] = b
            vertices[off + 6] = alpha
            vertices[off + 7] = 0f; vertices[off + 8] = 1f; vertices[off + 9] = 0f
        }
        put(0, 0f, 0f, centerAlpha)
        for (s in 0..sectors) {
            val ang = (s.toDouble() * Math.PI / sectors).toFloat()
            put(1 + s,
                kotlin.math.cos(ang) * midR,
                kotlin.math.sin(ang) * midR,
                peakAlpha)
        }
        for (s in 0..sectors) {
            val ang = (s.toDouble() * Math.PI / sectors).toFloat()
            put(1 + ringSize + s,
                kotlin.math.cos(ang) * outerR,
                kotlin.math.sin(ang) * outerR,
                0f)
        }
        // sectors triangles in the inner fan + 2*sectors in the outer strip.
        val indices = ShortArray(3 * sectors * 3)
        var idx = 0
        for (s in 0 until sectors) {
            val peakS    = (1 + s).toShort()
            val peakNext = (1 + s + 1).toShort()
            val rimS     = (1 + ringSize + s).toShort()
            val rimNext  = (1 + ringSize + s + 1).toShort()
            indices[idx++] = 0; indices[idx++] = peakS; indices[idx++] = peakNext
            indices[idx++] = peakS;    indices[idx++] = rimS;    indices[idx++] = rimNext
            indices[idx++] = peakS;    indices[idx++] = rimNext; indices[idx++] = peakNext
        }
        return engineView.engine.loadMeshRaw(vertices, indices)
    }

    /**
     * E7.1 — procedural UV-sphere for the 3D fireball. Y-axis aligned (poles
     * at ±Y) so the fragment shader's `abs(vNormal.y)` Fresnel reads as
     * "facing camera" under this project's pitch=π/2 camera (camera looks
     * along ±Y, see Camera::reset). Per-vertex colour white and alpha 1 —
     * tint and overall brightness come from per-draw `pc.plasmaColor`.
     * Default 12×16 = 384 tris (under 65k uint16 index ceiling, cheap).
     */
    private fun buildFireballSphereMesh(stacks: Int = 12, slices: Int = 16): Long {
        val nVerts = (stacks + 1) * (slices + 1)
        val vertices = FloatArray(nVerts * 10)
        var off = 0
        for (i in 0..stacks) {
            val theta = i.toDouble() * Math.PI / stacks
            val sinT = kotlin.math.sin(theta).toFloat()
            val cosT = kotlin.math.cos(theta).toFloat()
            for (j in 0..slices) {
                val phi = j.toDouble() * 2.0 * Math.PI / slices
                val sinP = kotlin.math.sin(phi).toFloat()
                val cosP = kotlin.math.cos(phi).toFloat()
                val x = sinT * cosP
                val y = cosT
                val z = sinT * sinP
                vertices[off + 0] = x
                vertices[off + 1] = y
                vertices[off + 2] = z
                vertices[off + 3] = 1f; vertices[off + 4] = 1f; vertices[off + 5] = 1f
                vertices[off + 6] = 1f
                vertices[off + 7] = x; vertices[off + 8] = y; vertices[off + 9] = z
                off += 10
            }
        }
        val nTris = stacks * slices * 2
        val indices = ShortArray(nTris * 3)
        var idx = 0
        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val a = (i * (slices + 1) + j).toShort()
                val b = (i * (slices + 1) + j + 1).toShort()
                val c = ((i + 1) * (slices + 1) + j).toShort()
                val d = ((i + 1) * (slices + 1) + j + 1).toShort()
                indices[idx++] = a; indices[idx++] = c; indices[idx++] = b
                indices[idx++] = b; indices[idx++] = c; indices[idx++] = d
            }
        }
        return engineView.engine.loadMeshRaw(vertices, indices)
    }

    /**
     * E11 — muzzle-blast cone mesh: triangle fan in the X-Z plane with
     * ±15° aperture (30° total wedge) around local +Z, radius 1, alpha 1
     * everywhere. The plasma fragment shader's radial soft-fade
     * (`plasmaSoftFade()`, smoothstep(0.4, 1.0, length(vLocalXZ))) does the
     * fade — alpha 1 at origin → 0 at perimeter — so each cone reads as a
     * wispy fire wedge fading outward. Forward = +Z so a `rotation=0`
     * cone aligns to the screen-up direction; spawnMuzzleBlast feeds in
     * three rotations 120° apart for the trefoil pattern.
     *
     * Default 12 segments → 12 triangles, ~13 vertices. Sub-pixel detail
     * comes from FBM turbulence in the fragment shader, not from mesh
     * tessellation, so a low segment count is fine.
     */
    private fun buildMuzzleConeMesh(segments: Int = 12): Long {
        val aperture = (30.0 * Math.PI / 180.0).toFloat()  // total cone width in radians
        val halfAp   = aperture * 0.5f

        // 1 centre vertex + (segments+1) perimeter vertices, 10 floats each.
        val nVerts = 1 + (segments + 1)
        val vertices = FloatArray(nVerts * 10)
        // Centre vertex at origin — alpha 1, rest of fields don't matter for the
        // plasma fragment branch (it doesn't read normal, and uv stays default).
        vertices[0] = 0f; vertices[1] = 0f; vertices[2] = 0f
        vertices[3] = 1f; vertices[4] = 1f; vertices[5] = 1f; vertices[6] = 1f
        vertices[7] = 0f; vertices[8] = 1f; vertices[9] = 0f

        var off = 10
        for (i in 0..segments) {
            // phi sweeps from -halfAp to +halfAp; phi=0 puts the vertex at +Z
            // (forward), so the fan opens forward. The cos/sin assignment maps
            // phi=0 → (0, 0, 1), phi=+halfAp → (sin(halfAp), 0, cos(halfAp)).
            val phi  = -halfAp + (aperture / segments) * i
            val px   = kotlin.math.sin(phi)
            val pz   = kotlin.math.cos(phi)
            vertices[off + 0] = px
            vertices[off + 1] = 0f
            vertices[off + 2] = pz
            vertices[off + 3] = 1f; vertices[off + 4] = 1f; vertices[off + 5] = 1f
            vertices[off + 6] = 1f
            vertices[off + 7] = 0f; vertices[off + 8] = 1f; vertices[off + 9] = 0f
            off += 10
        }

        // Triangle fan: (centre=0, perim_i, perim_i+1) for i in 1..segments.
        val indices = ShortArray(segments * 3)
        var idx = 0
        for (i in 1..segments) {
            indices[idx++] = 0
            indices[idx++] = i.toShort()
            indices[idx++] = (i + 1).toShort()
        }

        return engineView.engine.loadMeshRaw(vertices, indices)
    }

    /**
     * E9 — unit UV-mapped X-Z plane quad for particles. Same primitive as
     * the E8.4 textured-quad smoke test, regenerated here because it lives
     * permanently and the smoke-test version was retired. Particle vertex
     * shader uses inPosition.xz for billboarding + soft-fade radius.
     */
    private fun buildParticleQuadMesh(): Long {
        val verts = floatArrayOf(
            //  x,   y,    z,    r, g, b, a,    nx, ny, nz,   u,  v
            -1f, 0f, -1f,  1f, 1f, 1f, 1f,  0f, 1f, 0f,  0f, 0f,
             1f, 0f, -1f,  1f, 1f, 1f, 1f,  0f, 1f, 0f,  1f, 0f,
             1f, 0f,  1f,  1f, 1f, 1f, 1f,  0f, 1f, 0f,  1f, 1f,
            -1f, 0f,  1f,  1f, 1f, 1f, 1f,  0f, 1f, 0f,  0f, 1f,
        )
        val indices = shortArrayOf(0, 1, 2, 0, 2, 3)
        return engineView.engine.loadMeshRawUV(verts, indices)
    }

    /**
     * E9 — procedural smoke puff texture (RGBA8, 64×64). Soft Gaussian-ish
     * radial falloff modulated by 2-octave value noise so the puff has
     * wispy structure instead of being a flat circle. Light gray RGB with
     * a subtle cool tint reads as exhaust/dust against the dark space
     * background. Transparent at the edges so multiple puffs overlap
     * cleanly through SRC_ALPHA blending.
     */
    private fun generateSmokeTexture(): Long {
        val W = 64; val H = 64
        val px = ByteArray(W * H * 4)
        val cx = W * 0.5f; val cy = H * 0.5f
        val maxR = W * 0.5f
        fun hash01(ix: Int, iy: Int): Float {
            val v = kotlin.math.sin(ix * 127.1f + iy * 311.7f) * 43758.547f
            return v - kotlin.math.floor(v)
        }
        fun valueNoise(x: Float, y: Float): Float {
            val ix = kotlin.math.floor(x).toInt()
            val iy = kotlin.math.floor(y).toInt()
            val fx = x - ix; val fy = y - iy
            val ux = fx * fx * (3f - 2f * fx)
            val uy = fy * fy * (3f - 2f * fy)
            val a = hash01(ix,     iy)
            val b = hash01(ix + 1, iy)
            val c = hash01(ix,     iy + 1)
            val d = hash01(ix + 1, iy + 1)
            return (a * (1 - ux) + b * ux) * (1 - uy) +
                   (c * (1 - ux) + d * ux) * uy
        }
        for (y in 0 until H) for (x in 0 until W) {
            val dx = (x + 0.5f) - cx
            val dy = (y + 0.5f) - cy
            val d = kotlin.math.sqrt(dx * dx + dy * dy) / maxR
            val falloff = (1f - d * d).coerceAtLeast(0f)
            val u = x.toFloat() / W * 6f
            val v = y.toFloat() / H * 6f
            val n = valueNoise(u, v) * 0.6f + valueNoise(u * 2.1f, v * 2.1f) * 0.4f
            val alphaF = (falloff * (0.5f + n * 0.7f)).coerceIn(0f, 1f)
            val gray = (0.55f + n * 0.20f).coerceIn(0f, 1f)
            val off = (y * W + x) * 4
            px[off + 0] = (gray * 0.85f * 255f).toInt().toByte()
            px[off + 1] = (gray * 0.83f * 255f).toInt().toByte()
            px[off + 2] = (gray * 0.78f * 255f).toInt().toByte()
            px[off + 3] = (alphaF * 255f).toInt().coerceIn(0, 255).toByte()
        }
        return engineView.engine.loadTextureRaw(px, W, H)
    }

    /**
     * E9 — procedural asteroid-chunk debris texture (RGBA8, 64×64). An
     * irregular polygonal silhouette (radius perturbed by two sine
     * harmonics so it reads as a bumpy rock instead of a circle), warm
     * gray fill with a top-left light gradient (matches the engine's
     * primary light direction in `triangle.frag`). Transparent outside
     * with a 1-pixel AA edge so chunks blend cleanly when overlapping.
     */
    private fun generateDebrisTexture(): Long {
        val W = 64; val H = 64
        val px = ByteArray(W * H * 4)
        val cx = W * 0.5f; val cy = H * 0.5f
        fun radiusAtAngle(theta: Float): Float {
            val base = W * 0.42f
            val warp = kotlin.math.sin(theta * 5f) * 0.10f +
                       kotlin.math.sin(theta * 7f + 1.3f) * 0.07f
            return base * (1f + warp)
        }
        for (y in 0 until H) for (x in 0 until W) {
            val dx = (x + 0.5f) - cx
            val dy = (y + 0.5f) - cy
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            val theta = kotlin.math.atan2(dy, dx)
            val rAtAngle = radiusAtAngle(theta)
            val off = (y * W + x) * 4
            // Top-left light: brighter when (dx, dy) is small.
            val light = (((-dx / rAtAngle) * 0.3f + (-dy / rAtAngle) * 0.3f + 0.6f)).coerceIn(0.35f, 1f)
            val r = (0.55f * light * 255f).toInt().coerceIn(0, 255).toByte()
            val g = (0.48f * light * 255f).toInt().coerceIn(0, 255).toByte()
            val b = (0.43f * light * 255f).toInt().coerceIn(0, 255).toByte()
            when {
                d < rAtAngle - 1f -> {
                    px[off + 0] = r; px[off + 1] = g; px[off + 2] = b; px[off + 3] = 255.toByte()
                }
                d < rAtAngle + 1f -> {
                    val t = ((rAtAngle + 1f - d) * 0.5f).coerceIn(0f, 1f)
                    px[off + 0] = r; px[off + 1] = g; px[off + 2] = b
                    px[off + 3] = (t * 255f).toInt().toByte()
                }
                else -> {
                    px[off + 0] = 0; px[off + 1] = 0; px[off + 2] = 0; px[off + 3] = 0
                }
            }
        }
        return engineView.engine.loadTextureRaw(px, W, H)
    }

    /**
     * Generate the background nebula meshes once and submit them as
     * translucent scene objects. Set once at engine setup; never touched after,
     * so menu / mission select / game / win-lose all share the same backdrop.
     * Also builds the shield-dome half-disk meshes (E2.2) — they're loaded
     * here because they share `loadMeshRaw` and the translucent pipeline.
     */
    private fun setupBackgroundNebulae() {
        val tints = arrayOf(
            floatArrayOf(0.42f, 0.18f, 0.55f),  // 0 — deep purple
            floatArrayOf(0.18f, 0.50f, 0.55f),  // 1 — cyan
            floatArrayOf(0.55f, 0.22f, 0.30f),  // 2 — dim crimson
            floatArrayOf(0.25f, 0.32f, 0.60f),  // 3 — twilight blue
            floatArrayOf(0.50f, 0.42f, 0.22f),  // 4 — warm dust
        )
        for (i in tints.indices) {
            nebulaHandles[i] = buildSoftDiskMesh(tints[i][0], tints[i][1], tints[i][2])
        }
        // Hand-placed positions for visual variety. y=+1 puts the nebulae
        // behind the y=0 gameplay plane (LESS depth test rejects them at any
        // pixel covered by gameplay geometry).
        data class N(val tint: Int, val x: Float, val z: Float, val scale: Float)
        val placements = listOf(
            N(0, -1.7f, 6.2f, 3.6f),  // purple, upper-left
            N(1,  1.5f, 4.0f, 3.0f),  // cyan, mid-right
            N(2, -0.9f, 1.4f, 2.4f),  // crimson, lower-left
            N(3,  1.9f, 7.6f, 2.8f),  // twilight blue, top-right
            N(4,  0.0f, 2.8f, 2.0f),  // warm dust, mid-centre
        )
        nebulaeTranslucent = placements.mapIndexed { i, p ->
            SceneObject(
                id         = 2000 + i,
                meshHandle = nebulaHandles[p.tint],
                x          = p.x, y = 1f, z = p.z,
                scale      = p.scale,
                // E3.2 — fragment shader applies FBM-noise alpha modulation
                // for this material → soft-disk turns into wispy clouds.
                material   = EngineJni.MATERIAL_NEBULA,
            )
        }
        // E3.3 — filled half-disk so the hex shader has a continuous surface
        // to draw onto. centerAlpha is low (subtle interior fill, turret stays
        // visible) and the mid-arc carries the rim glow. midR pulled inward to
        // 0.80 so the falloff from peak to outer rim is wider — softer dome
        // silhouette instead of a hard edge. Hex modulation is intentionally
        // subtle (see hexAlphaMod in triangle.frag).
        // Permanent shield arch (M9 redesign — replaces the on/off
        // hex-dome). Vertices live in world coordinates so the SceneObject
        // just needs a translation to platform top.
        domeMembraneHandle = buildShieldArchMesh()
        // E7.1 — load the fireball UV-sphere once. Drawn through the additive
        // pipeline with ADDITIVE_FIRE material in spawnAoeRing.
        fireballMeshHandle = buildFireballSphereMesh()
        // E11 — load the muzzle cone fan once; spawnMuzzleBlast spawns 3
        // plasma billboards using this mesh with per-flash rotation.
        muzzleConeMeshHandle = buildMuzzleConeMesh()
        // E9 — particle infrastructure. Single shared unit-quad mesh, plus
        // two procedural textures (smoke puff for AoE/death, asteroid-chunk
        // debris for asteroid death). Sparks (additive) ignore textures.
        particleQuadHandle  = buildParticleQuadMesh()
        smokeTextureHandle  = generateSmokeTexture()
        debrisTextureHandle = generateDebrisTexture()
        // Initial assignment so menu / mission-select scenes (which don't run
        // buildScene) still show the nebulae backdrop.
        engineView.translucentObjects = nebulaeTranslucent
    }

    private fun loadSelectionFrames() {
        // DRAFT — selection frames disabled. Without this, every SceneObject
        // gets a green/red rectangle outline drawn around its bounds.
        engineView.highlightMeshes = HighlightMeshes()
    }

    // ---------------------------------------------------------------------------
    // World initialisation
    // ---------------------------------------------------------------------------
    private fun initWorld() {
        alliedShips  = buildInitialAlliedShips()
        nextShipId   = 12
        buildActive  = false
        buildProgress = 0f
        selectionOverlay.setBuildProgress(-1, 0f)
        simWorld = SimulationWorld(buildInitialShips(), buildInitialWorldObjects())
        stationAI = StationAI(
            fleet      = FleetRegistry.default(),
            stationPos = ALLIED_STATION_POS
        )
        stationAI.onReport = { msg -> runOnUiThread { showStatus(msg) } }
        stationAI.onTasksEmpty = {
            runOnUiThread {
                if (missionCtrl == null) stopEngineSound()
                patrolActive = false
                if (missionCtrl == null) btnFlyAround.isActivated = false
                btnAttack.isActivated = false
                btnPatrol.isActivated = false
                btnHome.isActivated = false
                if (missionCtrl == null) {
                    engineView.plasmaBillboards = emptyList()
                    buildScene()
                }
            }
        }

        enemyAI = StationAI(
            fleet      = FleetRegistry.enemy(),
            stationPos = ENEMY_STATION_POS,
            enemyTeam  = Team.ALLY
        )
        enemyAI.receiveCommand(
            PlayerCommand.Patrol(FleetUnit.All, ENEMY_STATION_POS, ENEMY_PATROL_ORBIT_RADIUS),
            simWorld
        )
        applySettings()
        ensureTicking()
    }

    // ---------------------------------------------------------------------------
    // Scene building
    // ---------------------------------------------------------------------------
    // HP-bar over each damaged asteroid. Two flat quads (background + green
    // fill) appended to the opaque scene list. Hidden when hp == maxHp so a
    // fresh asteroid doesn't carry visual clutter; hidden when hp <= 0 so
    // dead asteroids don't render a bar in their last frame before cull. The
    // fill is anchored to the bar's left edge so it shrinks rightward as HP
    // drops (same pattern as the central-turret reload bar). Fill is nudged
    // -Y by 0.01 to pass the LESS depth test against the background quad
    // sharing the same screen position.
    private fun buildHpBars(): List<SceneObject> {
        if (asteroids.isEmpty() ||
            quadHpBgHandle == 0L || quadHpFgHandle == 0L) return emptyList()
        val out = ArrayList<SceneObject>()
        asteroids.forEachIndexed { i, a ->
            if (a.hp <= 0 || a.hp >= a.maxHp) return@forEachIndexed
            val frac      = (a.hp.toFloat() / a.maxHp.toFloat()).coerceIn(0f, 1f)
            val barCx     = a.xPos
            val barCz     = a.zPos + a.half + DraftCombat.HP_BAR_PADDING
            val barHalfW  = a.half * DraftCombat.HP_BAR_HALF_W_MUL
            val fillHalfW = barHalfW * frac
            val fillCx    = barCx - barHalfW * (1f - frac)
            out.add(SceneObject(
                id         = 400 + i * 2,
                meshHandle = quadHpBgHandle,
                x          = barCx, y = 0f, z = barCz,
                scaleX     = barHalfW,
                scaleY     = 1f,
                scaleZ     = DraftCombat.HP_BAR_HALF_THICK,
            ))
            out.add(SceneObject(
                id         = 401 + i * 2,
                meshHandle = quadHpFgHandle,
                x          = fillCx, y = -0.01f, z = barCz,
                scaleX     = fillHalfW,
                scaleY     = 1f,
                scaleZ     = DraftCombat.HP_BAR_HALF_THICK,
            ))
        }
        return out
    }

    private fun buildScene() {
        // DRAFT — single quad sized to fit the camera frustum at the target plane.
        // Camera: eye=(0,-22,4), target=(0,0,4), fovY=28°, looking +Y.
        // At Y=0 the half-height = 22*tan(14°)≈5.49; half-width = halfH * aspect.
        // Aspect ~= 0.45 for a 1080×2400 portrait device.
        // Screen extents at the target plane: X ∈ [-2.47, +2.47], Z ∈ [-1.49, +9.49].
        // Width-to-height world ratio matches pixel ratio, so equal scaleX and
        // scaleZ produce a visually square shape.
        // Shield VFX (E2.2) is built by buildShieldDome() and merged into the
        // translucent list at the end of this function. The platform itself
        // stays grey at all times now; the dome glow over the base
        // communicates the shield state. Background nebulae are translucent
        // meshes built once in setupBackgroundNebulae() and cached in
        // nebulaeTranslucent — composed with the dome each frame.

        // Central-turret reload bar (M7.1c) — sits on the platform face just
        // below the turret base. Backing strip + fill strip; fill width grows
        // proportionally to readiness so the player can read when the next
        // shot is coming up. For the automatic gun (interval 0.15 s) it just
        // looks like a constantly-full bar; for the heavy cannon (1.0 s) it's
        // a meaningful charge meter.
        val reloadInterval = currentWeapon.fireIntervalSec
        val reloadProgress = if (reloadInterval > 0f)
            (1f - centralFireCooldown / reloadInterval).coerceIn(0f, 1f)
        else 1f
        val reloadHalfW = DraftCombat.RELOAD_BAR_HALF_W
        val reloadFillHalfW = reloadHalfW * reloadProgress
        // Fill is anchored to the LEFT edge of the backing so it grows L→R.
        val reloadFillCenterX = -reloadHalfW * (1f - reloadProgress)
        engineView.scene = listOf(
            // Platform — full width, 5% screen height, pinned to bottom.
            SceneObject(
                id         = 100,
                meshHandle = quadGreyHandle,
                x          = 0f, y = 0f, z = -1.215f,
                scaleX     = 2.47f,
                scaleY     = 1f,
                scaleZ     = 0.275f,
            ),
            // Reload bar — backing (dim grey, full width).
            SceneObject(
                id         = 107,
                meshHandle = quadGreyHandle,
                x          = 0f, y = 0f, z = DraftCombat.RELOAD_BAR_Z,
                scaleX     = reloadHalfW,
                scaleY     = 1f,
                scaleZ     = DraftCombat.RELOAD_BAR_HALF_THICK,
            ),
            // Reload bar — fill (yellow, width = progress * full). Nudged
            // slightly forward in Y so it passes the LESS depth test against
            // the backing (both share the y=0 plane otherwise, and the regular
            // scene pipeline rejects equal-depth fragments).
            SceneObject(
                id         = 108,
                meshHandle = quadFlashHandle,
                x          = reloadFillCenterX, y = -0.01f, z = DraftCombat.RELOAD_BAR_Z,
                scaleX     = reloadFillHalfW,
                scaleY     = 1f,
                scaleZ     = DraftCombat.RELOAD_BAR_HALF_THICK,
            ),
            // Central turret — main weapon. Tall red rectangle pivoted at its
            // base on the platform top. The SceneObject's center is offset along
            // the barrel direction so rotation looks like the turret swivelling
            // on its base instead of pivoting around its midpoint.
            run {
                val ang = centralTurretAngle
                val halfH = DraftCombat.CENTRAL_TURRET_HALF_H
                SceneObject(
                    id         = 109,
                    meshHandle = quadMeshHandle,
                    x          = DraftCombat.CENTRAL_TURRET_X + kotlin.math.sin(ang) * halfH,
                    y          = 0f,
                    z          = DraftCombat.CENTRAL_TURRET_BASE_Z + kotlin.math.cos(ang) * halfH,
                    rotationY  = ang,
                    scaleX     = DraftCombat.CENTRAL_TURRET_HALF_W,
                    scaleY     = 1f,
                    scaleZ     = halfH,
                )
            },
            // Two blue side turret squares on the platform, left and right of center.
            SceneObject(
                id         = 110,
                meshHandle = quadBlueHandle,
                x          = turretXs[0], y = 0f, z = DraftCombat.TURRET_TOP_Z,
                scaleX     = DraftCombat.TURRET_HALF,
                scaleY     = 1f,
                scaleZ     = DraftCombat.TURRET_HALF,
            ),
            SceneObject(
                id         = 111,
                meshHandle = quadBlueHandle,
                x          = turretXs[1], y = 0f, z = DraftCombat.TURRET_TOP_Z,
                scaleX     = DraftCombat.TURRET_HALF,
                scaleY     = 1f,
                scaleZ     = DraftCombat.TURRET_HALF,
            ),
        ) + asteroids.mapIndexed { i, a ->
            // Per-asteroid mesh chosen at spawn (5 distinct .glbs across 5 types
            // + grey variant pool). Roughly unit bbox; scale by `half` so FAST
            // asteroids look small and HEAVY ones look chunky.
            // E10.3 — build prev_model from prevZ + prevRotation cached at the
            // top of the asteroid movement step. xPos doesn't change so we
            // reuse it for both matrices; the prev SceneObject is a temporary
            // we only ever ask `modelMatrix()` of.
            val prev = SceneObject(
                id         = 200 + i,
                meshHandle = 0L,
                x          = a.xPos, y = 0f, z = a.prevZ,
                rotationZ  = a.prevRotation,
                scale      = a.half,
            ).modelMatrix()
            SceneObject(
                id              = 200 + i,
                meshHandle      = if (a.meshHandle != 0L) a.meshHandle else asteroidMeshGrey1,
                x               = a.xPos, y = 0f, z = a.zPos,
                rotationZ       = a.rotation,
                scale           = a.half,
                prevModelMatrix = prev,
            )
        } + bullets.mapIndexed { i, b ->
            // Bullet model — long axis aligned with velocity. Y-rotation =
            // atan2(vx, vz) maps the model's local +Z to the velocity vector,
            // plus BULLET_MODEL_YAW_OFFSET so we can correct if the .glb's
            // forward axis turns out not to be +Z.
            //
            // Scale: the .glb has its own intrinsic bbox (~unit), so `b.halfW`
            // (≈0.04..0.10) gives a small bullet sized roughly like the old
            // quad placeholder. Uniform scale keeps the model's proportions.
            // E10.3 — prev_model from prevX/prevZ; rotation is constant for
            // a bullet (fixed velocity vector), so reuse current rotationY.
            val mesh   = if (b.meshHandle != 0L) b.meshHandle else quadMeshHandle
            val rotY   = kotlin.math.atan2(b.vx, b.vz) + DraftCombat.BULLET_MODEL_YAW_OFFSET
            val bScale = b.halfH * DraftCombat.BULLET_MODEL_SCALE_MUL
            val prev   = SceneObject(
                id         = 300 + i,
                meshHandle = 0L,
                x          = b.prevX, y = 0f, z = b.prevZ,
                rotationY  = rotY,
                scale      = bScale,
            ).modelMatrix()
            SceneObject(
                id              = 300 + i,
                meshHandle      = mesh,
                x               = b.x, y = 0f, z = b.z,
                rotationY       = rotY,
                scale           = bScale,
                prevModelMatrix = prev,
            )
        } + buildHpBars()

        // Flash VFX: muzzle flash, bullet trails, asteroid hit, AoE rings, ENERGY-buff
        // pickup. Routed through the additive plasma pipeline (E2.1) so they read as
        // soft circular glows that brighten what's behind them — instead of square
        // yellow placeholders sitting on the dark background. E5.1 — per-flash tint
        // forwarded to the plasma fragment branch via BillboardDraw → drawPlasmaBillboard.
        val flashBillboards = flashes.map { f ->
            val t  = 1f - (f.life / f.maxLife)
            val k  = 0.6f + t * 0.8f
            val s  = f.halfMax  * k
            val sV = f.halfMaxV * k
            // E11 — flashes that specify their own mesh (e.g. muzzle cones)
            // route through it; round flashes fall back to the standard
            // quadFlashHandle. Rotation is plumbed straight through; default
            // 0 leaves quads axis-aligned as before.
            val mesh = if (f.meshHandle != 0L) f.meshHandle else quadFlashHandle
            BillboardDraw(mesh, f.x, 0f, f.z, s, f.tintR, f.tintG, f.tintB, f.tintA,
                          scaleV = sV,
                          rotation = f.rotation, lightningSeed = f.lightningSeed)
        }
        engineView.plasmaBillboards   = flashBillboards
        engineView.translucentObjects = nebulaeTranslucent + buildShieldDome()

        // E7.1 — 3D fireball explosions. Y-axis-aligned UV-sphere through the
        // additive pipeline with the fire-material branch. Three curves on
        // t = age/maxLife give the explosion a real shape:
        //   • scale: ease-out quadratic 0.4 → 1.4 × baseRadius (fast initial
        //     blast, asymptotic settle — shockwaves decelerate as they
        //     expand, mirrored in the curve `1 - (1-t)²`).
        //   • colour: lerp FIREBALL_TINT_START → FIREBALL_TINT_END (forge
        //     orange → dying-ember red) so the ball visibly cools.
        //   • brightness: sqrt(1-t) — holds longer than linear at the start
        //     so the colour shift remains readable; pinches off near end.
        // Depth-test on / write off (E7) means the fireball is occluded by
        // closer opaque geometry (asteroids, turrets) but multiple
        // overlapping fireballs accumulate through additive blend.
        // E9 — pack each particle pool into the engine's instance-buffer
        // layout once per frame and ship a single batch per pool. The
        // engine concatenates batches per pipeline at renderFrame and
        // does one instanced draw per batch. Skip pools when their
        // resources aren't loaded so missing assets degrade gracefully.
        val particleBatches = ArrayList<ParticleBatchKt>(3)
        if (particleQuadHandle != 0L && sparkParticles.isNotEmpty()) {
            particleBatches.add(ParticleBatchKt(
                meshHandle    = particleQuadHandle,
                textureHandle = 0L,
                data          = packParticles(sparkParticles),
                count         = sparkParticles.size,
                mode          = EngineJni.PARTICLE_ADDITIVE,
            ))
        }
        if (particleQuadHandle != 0L && smokeTextureHandle != 0L && smokeParticles.isNotEmpty()) {
            particleBatches.add(ParticleBatchKt(
                meshHandle    = particleQuadHandle,
                textureHandle = smokeTextureHandle,
                data          = packParticles(smokeParticles),
                count         = smokeParticles.size,
                mode          = EngineJni.PARTICLE_ALPHA_TEXTURED,
            ))
        }
        if (particleQuadHandle != 0L && debrisTextureHandle != 0L && debrisParticles.isNotEmpty()) {
            particleBatches.add(ParticleBatchKt(
                meshHandle    = particleQuadHandle,
                textureHandle = debrisTextureHandle,
                data          = packParticles(debrisParticles),
                count         = debrisParticles.size,
                mode          = EngineJni.PARTICLE_ALPHA_TEXTURED,
            ))
        }
        engineView.particleBatches = particleBatches

        val fireballAdditive = if (fireballs.isEmpty() || fireballMeshHandle == 0L) emptyList()
        else {
            val tStart = DraftCombat.FIREBALL_TINT_START
            val tEnd   = DraftCombat.FIREBALL_TINT_END
            fireballs.mapIndexed { i, fb ->
                val t = (1f - fb.life / fb.maxLife).coerceIn(0f, 1f)
                val u = 1f - t
                val scaleCurve = 1f - u * u                       // ease-out quad
                val s = fb.baseRadius * (0.4f + scaleCurve * 1.0f)
                val tintR = tStart[0] + (tEnd[0] - tStart[0]) * t
                val tintG = tStart[1] + (tEnd[1] - tStart[1]) * t
                val tintB = tStart[2] + (tEnd[2] - tStart[2]) * t
                val brightness = kotlin.math.sqrt(u) * fb.intensity
                // E10.3 — same scale curve evaluated against prevLife.
                // Fireballs don't translate so prev_model differs only in
                // scale; the velocity attachment captures the radial
                // expansion as outward motion vectors per surface element.
                val prevT = (1f - fb.prevLife / fb.maxLife).coerceIn(0f, 1f)
                val prevU = 1f - prevT
                val prevScaleCurve = 1f - prevU * prevU
                val prevS = fb.baseRadius * (0.4f + prevScaleCurve * 1.0f)
                val prev = SceneObject(
                    id         = 800 + i,
                    meshHandle = 0L,
                    x          = fb.x, y = 0f, z = fb.z,
                    scale      = prevS,
                ).modelMatrix()
                SceneObject(
                    id               = 800 + i,
                    meshHandle       = fireballMeshHandle,
                    x                = fb.x, y = 0f, z = fb.z,
                    scale            = s,
                    tintR            = tintR, tintG = tintG, tintB = tintB, tintA = brightness,
                    additiveMaterial = EngineJni.ADDITIVE_FIRE,
                    prevModelMatrix  = prev,
                )
            }
        }
        engineView.additiveObjects = fireballAdditive
    }

    /**
     * Shield dome VFX (E2.2). Two stacked half-disk meshes drawn through the
     * translucent pipeline draws the membrane mesh as a thin glowing arc
     * along the dome silhouette: bright energy band where the line of sight
     * is tangent to the (notional 3D) dome surface, fully transparent
     * interior so the central turret remains visible inside the shield.
     *
     * Anchored at platform top (`PLATFORM_TOP_Z`) with y = -0.05 so the
     * translucent depth-test passes against y = 0 gameplay (smaller y = closer
     * to camera given the LESS comparison) — the dome draws over turrets but
     * its transparent interior keeps them visible.
     *
     * scaleX = 2.4, scaleZ = 2.0 → dome occupies x ∈ [-2.4, +2.4] (just
     * inside the visible X = ±2.47 frustum) and z ∈ [PLATFORM_TOP_Z, +1.06]
     * (just over the platform top, well below where asteroids spawn).
     *
     * Animation:
     *  - Subtle pulse modulates scale by ±4% over time so the dome breathes.
     *  - Last 0.6 sec of duration: linear fade-out via scale collapse so the
     *    shield visibly retracts before the COOLING transition. (Per-vertex
     *    alpha is baked into the mesh, so we modulate scale rather than alpha.)
     *
     * Returns empty when the shield isn't active or the membrane mesh failed
     * to load — translucent pass simply skips the dome.
     */
    private fun buildShieldDome(): List<SceneObject> {
        if (shieldHp <= 0f) return emptyList()
        if (domeMembraneHandle == 0L) return emptyList()
        // Arch mesh is pre-scaled in world units (see buildShieldArchMesh),
        // so we only translate to the platform top. Plain translucent
        // material — no hex/nebula overlay; the shape itself is the read.
        val baseZ = DraftCombat.PLATFORM_TOP_Z
        return listOf(
            SceneObject(
                id         = 700,
                meshHandle = domeMembraneHandle,
                x          = 0f, y = -0.05f, z = baseZ,
                scale      = 1f,
            ),
        )
    }

    /**
     * E9 — Euler-integrate particles, decay age, cull dead. Drag is applied
     * frame-rate-independently via 1 - drag*dt (with a clamp so a pathological
     * dt × drag combo can't reverse velocity). Gravity is constant -Z accel
     * (positive value = pulls particles toward the platform / "down").
     */
    private fun tickParticles(pool: MutableList<Particle>, dt: Float) {
        if (pool.isEmpty()) return
        val it = pool.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt
            if (p.age >= p.life) { it.remove(); continue }
            // Drag (per-second) applied per-frame.
            val dragMul = (1f - p.drag * dt).coerceAtLeast(0f)
            p.vx *= dragMul
            p.vy *= dragMul
            p.vz *= dragMul
            // Gravity acts along -Z (pulling toward bottom of screen).
            p.vz -= p.gravity * dt
            p.x  += p.vx * dt
            p.y  += p.vy * dt
            p.z  += p.vz * dt
        }
    }

    /**
     * E9 — pack a particle pool into the FloatArray layout the engine
     * expects: 8 floats per particle (pos.xyz, size, rgba). Alpha is the
     * stored r/g/b/a.a × age-fade so the engine just multiplies it in.
     * The fade curve is sqrt(1-t) — same shape used for fireball brightness
     * (E7.1 polish), keeps things in family.
     */
    private fun packParticles(pool: List<Particle>): FloatArray {
        if (pool.isEmpty()) return FloatArray(0)
        val out = FloatArray(pool.size * 8)
        var w = 0
        for (p in pool) {
            val u    = (1f - p.age / p.life).coerceIn(0f, 1f)
            val fade = kotlin.math.sqrt(u)
            out[w++] = p.x; out[w++] = p.y; out[w++] = p.z
            out[w++] = p.size
            out[w++] = p.r; out[w++] = p.g; out[w++] = p.b
            out[w++] = p.a * fade
        }
        return out
    }

    /**
     * E9 — AoE spark fan. Spawns SPARK_AOE_COUNT_* particles around
     * (cx, cz) on the gameplay plane (y=0), randomised over the X-Z plane
     * with no preferred direction. Tinted by `tintRgb` so callers can
     * recolour per-event (default forge-orange aligns with fireball).
     */
    private fun spawnSparkBurst(cx: Float, cz: Float, tintRgb: FloatArray = DraftCombat.FIREBALL_TINT_START) {
        val n = (DraftCombat.SPARK_AOE_COUNT_MIN..DraftCombat.SPARK_AOE_COUNT_MAX).random()
        for (i in 0 until n) {
            val theta = (Math.random() * 2.0 * Math.PI).toFloat()
            val sp = DraftCombat.SPARK_AOE_SPEED_MIN +
                     Math.random().toFloat() * (DraftCombat.SPARK_AOE_SPEED_MAX - DraftCombat.SPARK_AOE_SPEED_MIN)
            val life = DraftCombat.SPARK_AOE_LIFE_MIN +
                       Math.random().toFloat() * (DraftCombat.SPARK_AOE_LIFE_MAX - DraftCombat.SPARK_AOE_LIFE_MIN)
            val size = DraftCombat.SPARK_AOE_SIZE_MIN +
                       Math.random().toFloat() * (DraftCombat.SPARK_AOE_SIZE_MAX - DraftCombat.SPARK_AOE_SIZE_MIN)
            sparkParticles.add(Particle(
                x = cx, y = 0f, z = cz,
                vx = kotlin.math.cos(theta) * sp,
                vy = 0f,
                vz = kotlin.math.sin(theta) * sp,
                age = 0f, life = life, size = size,
                r = tintRgb[0], g = tintRgb[1], b = tintRgb[2], a = 1.4f,
                drag = DraftCombat.SPARK_AOE_DRAG,
            ))
        }
    }

    /**
     * E9 — Muzzle flash micro-sparks fired in a tight cone around the
     * bullet velocity vector. `(vx, vz)` is the bullet's screen-space
     * velocity (already normalised inside this helper). 3-5 short sparks
     * per shot read as gunpowder kick.
     */
    /**
     * E10.4-trails / E11 — directional muzzle blast as 3 plasma cones spaced
     * 120° apart around the muzzle. Each cone uses the procedural muzzle-cone
     * mesh (30° wedge fan) routed through the plasma pipeline with per-flash
     * rotation, so the wedges point along their respective world directions
     * with the full plasma look (FBM turbulence + heat ramp + soft fade).
     *
     * `dirX, dirZ` is the bullet's unit velocity; `bulletHalfW` scales the
     * blast so a chunkier projectile pops bigger than a slim one. Each cone
     * starts at the muzzle (origin = tip) and flares outward by `mainSize`
     * along its rotation, producing a tri-fork shape rooted at the barrel
     * exit. The plasma soft-fade in the fragment shader handles the alpha
     * gradient from bright tip to transparent rim.
     */
    private fun spawnMuzzleBlast(
        cx: Float, cz: Float,
        dirX: Float, dirZ: Float,
        bulletHalfW: Float,
        tint: FloatArray,
    ) {
        // Reference: BULLET_HALF_W = 0.04 maps to sizeMul ~1.0; clamps stop
        // very-small / very-large bullets from collapsing or overflowing.
        val sizeMul  = (bulletHalfW / DraftCombat.BULLET_HALF_W).coerceIn(0.5f, 2.0f)
        val mainSize = DraftCombat.MUZZLE_FLASH_HALF * sizeMul
        val life     = DraftCombat.MUZZLE_FLASH_LIFE

        // Forward angle in the engine's rotation convention: rotation=0 leaves
        // the cone's local +Z pointing screen-up (= world +Z under the fixed
        // pitch=π/2 camera). To rotate forward to (dirX, dirZ), the angle is
        // atan2(dirX, dirZ): atan2(0, 1) = 0 (no rotation, points up),
        // atan2(1, 0) = π/2 (points right), etc.
        val forwardAngle = kotlin.math.atan2(dirX, dirZ)
        val twoThirdsPi  = (2.0 * Math.PI / 3.0).toFloat()

        fun add(rot: Float) {
            flashes.add(Flash(
                x = cx, z = cz,
                life = life, maxLife = life,
                halfMax = mainSize,
                tintR = tint[0], tintG = tint[1], tintB = tint[2], tintA = tint[3],
                meshHandle = muzzleConeMeshHandle,
                rotation = rot,
            ))
        }

        // Three cones at 0°, +120°, -120° from forward. All rooted at the
        // muzzle position (cone tip = local origin); the rotation parameter
        // points each wedge's apex outward along its share of the trefoil.
        add(forwardAngle)
        add(forwardAngle + twoThirdsPi)
        add(forwardAngle - twoThirdsPi)
    }

    /**
     * E12 — railgun muzzle effect. Replaces spawnMuzzleBlast for the central
     * HEAVY_CANNON (concept-renamed to "Рельсотрон"). Visualises an
     * electromagnetic launcher firing: a single bright cyan-white core flash
     * at the barrel mouth + 5-7 procedural electric arc bolts radiating
     * around the perpendicular-to-barrel direction (= "discharges between
     * the rails"). Each bolt routes through the new lightning sub-shader
     * (E12) — the per-bolt `lightningSeed` decorrelates the FBM noise field
     * so simultaneous bolts look distinct, not stamped from the same mould.
     *
     * Side turrets and the Автомат keep using `spawnMuzzleBlast` (warm cone
     * trefoil). The asymmetry is deliberate: the railgun's discharge profile
     * is a unique read for the player's primary weapon.
     */
    private fun spawnRailgunMuzzle(
        cx: Float, cz: Float,
        dirX: Float, dirZ: Float,
        bulletHalfW: Float,
    ) {
        val sizeMul = (bulletHalfW / DraftCombat.BULLET_HALF_W).coerceIn(0.5f, 2.0f)

        // Bright ice-white core flash at the muzzle. Standard plasma path
        // (no lightningSeed → legacy heat-ramp flash) but with cyan-white
        // tint and high brightness scalar — reads as the barrel-mouth pop.
        val coreT = DraftCombat.FLASH_TINT_RAILGUN_CORE
        flashes.add(Flash(
            x = cx, z = cz,
            life = DraftCombat.RAILGUN_CORE_LIFE,
            maxLife = DraftCombat.RAILGUN_CORE_LIFE,
            halfMax = DraftCombat.RAILGUN_CORE_HALF * sizeMul,
            tintR = coreT[0], tintG = coreT[1], tintB = coreT[2], tintA = coreT[3],
            // round flash → falls back to quadFlashHandle in buildScene
        ))

        // Forward / perpendicular angles. Engine convention: rotation=0 means
        // local +Z = screen-up; barrel direction (dirX, dirZ) maps to angle
        // atan2(dirX, dirZ). Perpendicular to that is +π/2.
        val forwardAngle = kotlin.math.atan2(dirX, dirZ)
        val perpAngle    = forwardAngle + (Math.PI.toFloat() * 0.5f)

        val nBolts = (DraftCombat.RAILGUN_BOLT_COUNT_MIN..DraftCombat.RAILGUN_BOLT_COUNT_MAX).random()
        val boltT  = DraftCombat.FLASH_TINT_RAILGUN_BOLT
        val offsetMul = bulletHalfW.coerceAtLeast(0.04f)
        for (i in 0 until nBolts) {
            // Rotation: perpendicular-to-barrel ± uniform spread. Symmetric
            // around perpAngle so bolts radiate to both sides of the barrel.
            val spread = (Math.random().toFloat() - 0.5f) * 2f * DraftCombat.RAILGUN_BOLT_SPREAD_RAD
            val rot = perpAngle + spread
            // Slight offset along the barrel direction (root each bolt at a
            // different point along the rail length) so the cluster doesn't
            // all originate from a single pixel.
            val barrelOffset = (Math.random().toFloat() - 0.5f) *
                               DraftCombat.RAILGUN_BOLT_BARREL_OFFSET_FRAC *
                               offsetMul * 4f
            val bx = cx + dirX * barrelOffset
            val bz = cz + dirZ * barrelOffset
            // Per-bolt size + life jitter for visual variety. Length scaled
            // by sizeMul so an upgraded chunkier bullet pops bigger arcs.
            val halfMax = DraftCombat.RAILGUN_BOLT_HALF_MIN +
                          Math.random().toFloat() *
                          (DraftCombat.RAILGUN_BOLT_HALF_MAX - DraftCombat.RAILGUN_BOLT_HALF_MIN)
            val life = DraftCombat.RAILGUN_BOLT_LIFE_MIN +
                       Math.random().toFloat() *
                       (DraftCombat.RAILGUN_BOLT_LIFE_MAX - DraftCombat.RAILGUN_BOLT_LIFE_MIN)
            // Per-bolt seed >= 0.5 so the shader's tint.y >= 0.5 gate trips
            // and tint.z reads as a meaningful seed (not collapsed near 0).
            val seed = 1f + Math.random().toFloat() * 999f
            flashes.add(Flash(
                x = bx, z = bz,
                life = life, maxLife = life,
                halfMax = halfMax * sizeMul,
                tintR = boltT[0], tintG = boltT[1], tintB = boltT[2], tintA = boltT[3],
                // Reuse quadFlashHandle (unit X-Z square) — the bolt visual
                // lives entirely in the fragment shader, the mesh is just
                // the canvas. No new mesh asset needed.
                meshHandle = quadFlashHandle,
                rotation = rot,
                lightningSeed = seed,
            ))
        }
    }

    /**
     * E12 — railgun-flavoured muzzle sparks. Same E9 path as spawnMuzzleSparks
     * but cyan-tinted (electromagnetic discharge feel) instead of warm
     * yellow. Spawned alongside spawnRailgunMuzzle for the central railgun.
     */
    private fun spawnRailgunSparks(cx: Float, cz: Float, vx: Float, vz: Float) {
        val baseTheta = kotlin.math.atan2(vz, vx)
        val n = (DraftCombat.SPARK_MUZZLE_COUNT_MIN..DraftCombat.SPARK_MUZZLE_COUNT_MAX).random()
        val tint = DraftCombat.SPARK_TINT_RAILGUN
        for (i in 0 until n) {
            val theta = baseTheta + (Math.random().toFloat() - 0.5f) * 2f * DraftCombat.SPARK_MUZZLE_CONE_RAD
            val sp = DraftCombat.SPARK_MUZZLE_SPEED_MIN +
                     Math.random().toFloat() * (DraftCombat.SPARK_MUZZLE_SPEED_MAX - DraftCombat.SPARK_MUZZLE_SPEED_MIN)
            val life = DraftCombat.SPARK_MUZZLE_LIFE_MIN +
                       Math.random().toFloat() * (DraftCombat.SPARK_MUZZLE_LIFE_MAX - DraftCombat.SPARK_MUZZLE_LIFE_MIN)
            val size = DraftCombat.SPARK_MUZZLE_SIZE_MIN +
                       Math.random().toFloat() * (DraftCombat.SPARK_MUZZLE_SIZE_MAX - DraftCombat.SPARK_MUZZLE_SIZE_MIN)
            sparkParticles.add(Particle(
                x = cx, y = 0f, z = cz,
                vx = kotlin.math.cos(theta) * sp,
                vy = 0f,
                vz = kotlin.math.sin(theta) * sp,
                age = 0f, life = life, size = size,
                r = tint[0], g = tint[1], b = tint[2], a = 1.8f,
                drag = DraftCombat.SPARK_MUZZLE_DRAG,
            ))
        }
    }

    private fun spawnMuzzleSparks(cx: Float, cz: Float, vx: Float, vz: Float) {
        val baseTheta = kotlin.math.atan2(vz, vx)
        val n = (DraftCombat.SPARK_MUZZLE_COUNT_MIN..DraftCombat.SPARK_MUZZLE_COUNT_MAX).random()
        val tint = DraftCombat.FLASH_TINT_MUZZLE
        for (i in 0 until n) {
            val theta = baseTheta + (Math.random().toFloat() - 0.5f) * 2f * DraftCombat.SPARK_MUZZLE_CONE_RAD
            val sp = DraftCombat.SPARK_MUZZLE_SPEED_MIN +
                     Math.random().toFloat() * (DraftCombat.SPARK_MUZZLE_SPEED_MAX - DraftCombat.SPARK_MUZZLE_SPEED_MIN)
            val life = DraftCombat.SPARK_MUZZLE_LIFE_MIN +
                       Math.random().toFloat() * (DraftCombat.SPARK_MUZZLE_LIFE_MAX - DraftCombat.SPARK_MUZZLE_LIFE_MIN)
            val size = DraftCombat.SPARK_MUZZLE_SIZE_MIN +
                       Math.random().toFloat() * (DraftCombat.SPARK_MUZZLE_SIZE_MAX - DraftCombat.SPARK_MUZZLE_SIZE_MIN)
            sparkParticles.add(Particle(
                x = cx, y = 0f, z = cz,
                vx = kotlin.math.cos(theta) * sp,
                vy = 0f,
                vz = kotlin.math.sin(theta) * sp,
                age = 0f, life = life, size = size,
                r = tint[0], g = tint[1], b = tint[2], a = 1.6f,
                drag = DraftCombat.SPARK_MUZZLE_DRAG,
            ))
        }
    }

    /**
     * E9 — Asteroid death VFX. Spawns:
     *   • 4-8 textured chunks (alpha pipeline, debris texture) flying
     *     outward with a touch of -Z gravity → settle visually.
     *   • 3-5 textured smoke puffs (alpha pipeline, smoke texture) drifting
     *     slowly outward → linger after debris is gone.
     * `colorTint` lets callers recolour (e.g., HEAVY death = darker tint,
     * EXPLOSIVE = warmer); defaults to a neutral asteroid tone.
     */
    private fun spawnAsteroidDeathFX(cx: Float, cz: Float,
                                     colorTint: FloatArray = floatArrayOf(0.95f, 0.92f, 0.88f)) {
        val nDebris = (DraftCombat.DEBRIS_COUNT_MIN..DraftCombat.DEBRIS_COUNT_MAX).random()
        for (i in 0 until nDebris) {
            val theta = (Math.random() * 2.0 * Math.PI).toFloat()
            val sp = DraftCombat.DEBRIS_SPEED_MIN +
                     Math.random().toFloat() * (DraftCombat.DEBRIS_SPEED_MAX - DraftCombat.DEBRIS_SPEED_MIN)
            val life = DraftCombat.DEBRIS_LIFE_MIN +
                       Math.random().toFloat() * (DraftCombat.DEBRIS_LIFE_MAX - DraftCombat.DEBRIS_LIFE_MIN)
            val size = DraftCombat.DEBRIS_SIZE_MIN +
                       Math.random().toFloat() * (DraftCombat.DEBRIS_SIZE_MAX - DraftCombat.DEBRIS_SIZE_MIN)
            debrisParticles.add(Particle(
                x = cx, y = 0f, z = cz,
                vx = kotlin.math.cos(theta) * sp,
                vy = 0f,
                vz = kotlin.math.sin(theta) * sp,
                age = 0f, life = life, size = size,
                r = colorTint[0], g = colorTint[1], b = colorTint[2], a = 1.0f,
                drag = DraftCombat.DEBRIS_DRAG,
                gravity = DraftCombat.DEBRIS_GRAVITY,
            ))
        }
        val nSmoke = (DraftCombat.SMOKE_DEATH_COUNT_MIN..DraftCombat.SMOKE_DEATH_COUNT_MAX).random()
        for (i in 0 until nSmoke) {
            val theta = (Math.random() * 2.0 * Math.PI).toFloat()
            val sp = DraftCombat.SMOKE_DEATH_SPEED_MIN +
                     Math.random().toFloat() * (DraftCombat.SMOKE_DEATH_SPEED_MAX - DraftCombat.SMOKE_DEATH_SPEED_MIN)
            val life = DraftCombat.SMOKE_DEATH_LIFE_MIN +
                       Math.random().toFloat() * (DraftCombat.SMOKE_DEATH_LIFE_MAX - DraftCombat.SMOKE_DEATH_LIFE_MIN)
            val size = DraftCombat.SMOKE_DEATH_SIZE_MIN +
                       Math.random().toFloat() * (DraftCombat.SMOKE_DEATH_SIZE_MAX - DraftCombat.SMOKE_DEATH_SIZE_MIN)
            smokeParticles.add(Particle(
                x = cx, y = 0f, z = cz,
                vx = kotlin.math.cos(theta) * sp,
                vy = 0f,
                vz = kotlin.math.sin(theta) * sp,
                age = 0f, life = life, size = size,
                r = 0.85f, g = 0.82f, b = 0.78f, a = 0.65f,
                drag = DraftCombat.SMOKE_DEATH_DRAG,
            ))
        }
    }

    /**
     * Spawn an explosion at (cx, cz) sized to `radius`. Was a single plasma
     * billboard with E4 heat-ramp; now (E7.1) a 3D fireball mesh through the
     * additive pipeline with the fire-material shader. The Y-axis-aligned
     * UV-sphere gives a true volumetric look with Fresnel-soft silhouette,
     * heat ramp from white-hot core to orange edge, and animated FBM
     * turbulence — closer to a real fire than the flat billboard ever
     * managed. Scale grows 0.6 → 1.2 over the lifetime (shockwave
     * expansion); alpha fades to 0 so the ball dissipates instead of
     * snapping out. Also depth-tests against gameplay geometry, so an
     * explosion partly behind an asteroid is correctly occluded.
     */
    // M8.5 — rocket strike helpers.

    /**
     * Top N most dangerous asteroids — descending current HP, tiebreak
     * nearest to the central pivot. Returns up to `maxN` (may be empty if
     * no live asteroids exist).
     */
    private fun findRocketTargets(maxN: Int): List<Asteroid> {
        if (asteroids.isEmpty() || maxN <= 0) return emptyList()
        val px = DraftCombat.CENTRAL_TURRET_X
        val pz = DraftCombat.CENTRAL_TURRET_BASE_Z
        return asteroids
            .filter { it.hp > 0 }
            .sortedWith(compareByDescending<Asteroid> { it.hp }.thenBy {
                val ax = it.xPos - px; val az = it.zPos - pz
                ax * ax + az * az
            })
            .take(maxN)
    }

    /**
     * Spawn `targets.size` homing missiles at the turret muzzle, each
     * tracking one assigned asteroid. Damage uses the same upgrade ladder
     * as the central turret's main weapon, so investing in the
     * Main-Weapon-Damage track scales rockets too. AoE radius means a
     * missile that lands among a cluster splashes neighbours.
     */
    private fun launchRocketStrike(targets: List<Asteroid>) {
        if (targets.isEmpty()) return
        val pivotX = DraftCombat.CENTRAL_TURRET_X
        val muzzleZ = DraftCombat.CENTRAL_TURRET_BASE_Z +
                      DraftCombat.CENTRAL_TURRET_HALF_H * 2f
        val baseDmg = effectiveMainWeaponDamage *
                      DraftCombat.ROCKET_DAMAGE_MUL *
                      activeBuffDamageMul
        val rocketDmg = baseDmg.toInt().coerceAtLeast(1)
        val aoeDmg    = (rocketDmg * DraftCombat.ROCKET_AOE_DAMAGE_MUL)
                          .toInt().coerceAtLeast(1)
        for (target in targets) {
            val tdx = target.xPos - pivotX
            val tdz = target.zPos - muzzleZ
            val tlen = kotlin.math.sqrt(tdx * tdx + tdz * tdz)
            val nx = if (tlen > 1e-4f) tdx / tlen else 0f
            val nz = if (tlen > 1e-4f) tdz / tlen else 1f
            bullets.add(Bullet(
                x = pivotX, z = muzzleZ,
                vx = nx * DraftCombat.ROCKET_SPEED,
                vz = nz * DraftCombat.ROCKET_SPEED,
                damage = rocketDmg,
                halfW = DraftCombat.ROCKET_HALF_W,
                halfH = DraftCombat.ROCKET_HALF_H,
                meshHandle = bulletHeavyMeshHandle,
                aoeRadius  = DraftCombat.ROCKET_AOE_RADIUS,
                aoeDamage  = aoeDmg,
                homingTargetId = target.id,
                homingTurnRate = DraftCombat.ROCKET_TURN_RATE_RAD_PER_SEC,
            ))
            // Per-rocket muzzle puff — warm cone trefoil (E11), sized
            // bigger than the automatic's per-shot blast since this is
            // a salvo punctuation.
            spawnMuzzleBlast(pivotX, muzzleZ, nx, nz,
                             DraftCombat.ROCKET_HALF_W * 1.4f,
                             DraftCombat.FLASH_TINT_MUZZLE)
        }
    }

    /**
     * Rotates (b.vx, b.vz) toward `target`, clamped by `homingTurnRate *
     * dt` per tick. Speed is preserved (purely angular correction). No-op
     * if the missile is essentially on top of the target or has zero
     * speed — both protect against atan2 of (0, 0) and divisions by zero.
     */
    private fun homeBulletTowardsTarget(b: Bullet, target: Asteroid, dt: Float) {
        val tdx = target.xPos - b.x
        val tdz = target.zPos - b.z
        if (tdx * tdx + tdz * tdz < 1e-6f) return
        val speed = kotlin.math.sqrt(b.vx * b.vx + b.vz * b.vz)
        if (speed < 1e-4f) return
        val curAng     = kotlin.math.atan2(b.vx, b.vz)
        val desiredAng = kotlin.math.atan2(tdx, tdz)
        val twoPi      = (2.0 * Math.PI).toFloat()
        val piF        = Math.PI.toFloat()
        var delta = desiredAng - curAng
        while (delta >  piF) delta -= twoPi
        while (delta < -piF) delta += twoPi
        val maxStep = b.homingTurnRate * dt
        val clamped = delta.coerceIn(-maxStep, maxStep)
        val newAng  = curAng + clamped
        b.vx = kotlin.math.sin(newAng) * speed
        b.vz = kotlin.math.cos(newAng) * speed
    }

    /**
     * M8.6 — apply the laser strike along the player-drawn segment. Runs
     * on the mission thread (posted from the touch handler) so list
     * mutations are atomic with the move/collision loop. Damage is dealt
     * to every live asteroid whose centre lies within
     * `asteroid.half + LASER_HIT_PAD` of the segment. The visual is N
     * parallel lightning bolts straddling the segment.
     */
    private fun fireLaserStrike(slot: AbilitySlot,
                                ax: Float, az: Float,
                                bx: Float, bz: Float) {
        val a = slot.ability
        // Same UI-side gates as activateAbility — if the player held the
        // arm long enough that cd or energy changed, abort.
        if (slot.currentCd > 0f) return
        if (energy < a.cost) return
        val dx = bx - ax
        val dz = bz - az
        val len = kotlin.math.sqrt(dx * dx + dz * dz)
        if (len < 0.05f) {
            // Tap (not drag) — too short to read as a strike. Just cancel
            // the arm without spending; UI-thread refresh below.
            armedAbilityId = null
            runOnUiThread { refreshAllAbilityButtons() }
            return
        }
        val nx = dx / len            // unit along segment
        val nz = dz / len
        val padTotal = DraftCombat.LASER_HIT_PAD
        for (asteroid in asteroids) {
            if (asteroid.hp <= 0) continue
            // Project asteroid centre onto the segment, clamp to [0..len].
            val rx = asteroid.xPos - ax
            val rz = asteroid.zPos - az
            val t  = (rx * nx + rz * nz).coerceIn(0f, len)
            val cx = ax + nx * t
            val cz = az + nz * t
            val hx = asteroid.xPos - cx
            val hz = asteroid.zPos - cz
            val d2 = hx * hx + hz * hz
            val r  = asteroid.half + padTotal
            if (d2 <= r * r) {
                asteroid.hp -= DraftCombat.LASER_DAMAGE
            }
        }
        // VFX: parallel lightning bolts straddling the segment. Each bolt
        // is one Flash with halfMax = len/2 (length along segment) and
        // halfMaxV = LASER_BOLT_HALF_V (small, gives the streak shape).
        // Position is the segment midpoint, plus a perpendicular offset
        // per bolt so the bundle reads as a thicker beam.
        val midX = (ax + bx) * 0.5f
        val midZ = (az + bz) * 0.5f
        val perpX = -nz                      // perpendicular unit
        val perpZ =  nx
        val rotation = kotlin.math.atan2(nx, nz)
        val tint = DraftCombat.LASER_TINT
        val bolts = DraftCombat.LASER_BOLT_COUNT
        val centerOffset = (bolts - 1) * 0.5f
        for (i in 0 until bolts) {
            val offset = (i - centerOffset) *
                         DraftCombat.LASER_BOLT_OFFSET_STEP
            val bxc = midX + perpX * offset
            val bzc = midZ + perpZ * offset
            val seed = 1f + Math.random().toFloat() * 999f
            // The lightning sub-shader paints the arc along the quad's
            // local +Z axis (model.z, which maps to scaleV / halfMaxV in
            // BillboardDraw). So the segment-length goes into halfMaxV
            // and the cross-section thickness into halfMax. After
            // rotationY(rotation), the long axis ends up aligned with
            // (nx, nz). Swapping these makes the bolt invisible-thin
            // perpendicular to the gesture instead of a streak along it.
            flashes.add(Flash(
                x = bxc, z = bzc,
                life    = DraftCombat.LASER_BOLT_LIFE,
                maxLife = DraftCombat.LASER_BOLT_LIFE,
                halfMax  = DraftCombat.LASER_BOLT_HALF_V,
                halfMaxV = len * 0.5f,
                tintR = tint[0], tintG = tint[1], tintB = tint[2], tintA = tint[3],
                meshHandle    = quadFlashHandle,
                rotation      = rotation,
                lightningSeed = seed,
            ))
        }
        // Spend + cooldown (mirrors activateAbility's bookkeeping path).
        energy         = (energy - a.cost).coerceAtLeast(0f)
        energyUiLast   = -1
        slot.currentCd = a.cooldownSec
        slot.cdUiLast  = -1
        armedAbilityId = null
        runOnUiThread {
            hudEnergyText.text =
                "⚡ ${energy.toInt()}/${DraftCombat.ENERGY_MAX.toInt()}"
            refreshAllAbilityButtons()
        }
    }

    private fun spawnExplosion(cx: Float, cz: Float, radius: Float) {
        fireballs.add(Fireball(
            x = cx, z = cz,
            life = DraftCombat.FIREBALL_LIFE_SEC,
            maxLife = DraftCombat.FIREBALL_LIFE_SEC,
            baseRadius = radius,
        ))
        // E9 — radial spark fan complementing the 3D fireball. Reads as
        // ejecta flying out of the blast core; sparks fade fast (≤0.55s)
        // so they punctuate the moment without obscuring the fireball.
        spawnSparkBurst(cx, cz)
    }

    private fun sceneAdapter(worldObjects: List<WorldObject> = simWorld.worldObjectSnapshot()): SceneAdapter =
        SceneAdapter(
            shipMeshHandle       = shipMeshHandle,
            stationMeshHandle    = stationMeshHandle,
            projectileMeshHandle = projectileMeshHandle,
            selectedShipIds      = selectedShipIds,
            selectedTargetId     = selectedTargetId,
            homeShips            = alliedShips,
            enemyHomeShips       = buildInitialEnemyShips(),
            worldObjects         = worldObjects,
            fighterShape         = FIGHTER_SHAPE,
            stationShape         = STATION_SHAPE
        )

    private fun selectAt(x: Float, y: Float) {
        val pickedId = engineView.engine.pickObject(x, y, selectedTargetId)
        if (pickedId in 0..4) {
            if (pickedId in selectedShipIds) selectedShipIds.remove(pickedId)
            else selectedShipIds.add(pickedId)
        } else if (pickedId >= 5) {
            selectedTargetId = pickedId
        }
        updateShipCard()
        if (!stationAI.hasActiveTasks()) buildScene()
    }

    // ---------------------------------------------------------------------------
    // Commands → StationAI
    // ---------------------------------------------------------------------------
    private fun startAttackMission() {
        if (selectedShipIds.isEmpty()) { showStatus("Выберите истребитель"); return }
        val target = engineView.scene.firstOrNull { it.id == selectedTargetId && it.id >= 5 }
            ?: run { showStatus("Выберите цель"); return }
        val unit = FleetUnit.ExplicitIds(selectedShipIds.toSet())
        stationAI.receiveCommand(
            PlayerCommand.AttackTarget(unit, target.id, Vec2(target.x, target.y)),
            simWorld
        )
        btnAttack.isActivated = true
        startEngineSound()
        ensureTicking()
    }

    private fun startFlightMission() {
        if (missionCtrl != null) {
            missionCtrl = null
            btnFlyAround.isActivated = false
            showStatus("Миссия отменена")
            return
        }
        val positions = simWorld.shipSnapshot()
            .filter { it.team == Team.ALLY }
            .sortedBy { it.id }
            .map { it.position }
        if (positions.isEmpty()) { showStatus("Нет союзных кораблей"); return }
        val homePositions = buildInitialAlliedShips().sortedBy { it.id }.map { it.homePosition }
        missionCtrl = MissionController(positions, homePositions)
        btnFlyAround.isActivated = true
        startEngineSound()
        ensureTicking()
    }

    private fun returnToRallyPosition() {
        missionCtrl = null
        stationAI.clearAllTasks()
        patrolActive = false
        btnPatrol.isActivated = false
        btnAttack.isActivated = false
        btnFlyAround.isActivated = false
        stationAI.receiveCommand(PlayerCommand.ReturnHome(FleetUnit.All), simWorld)
        showStatus("Флот возвращается на позицию сбора")
        startEngineSound()
        ensureTicking()
    }

    private fun startPatrolMission() {
        if (patrolActive) {
            stationAI.clearAllTasks()
            patrolActive = false
            btnPatrol.isActivated = false
            showStatus("Патруль отменён")
            return
        }
        val unit = if (selectedShipIds.isEmpty()) FleetUnit.All else FleetUnit.ExplicitIds(selectedShipIds.toSet())
        stationAI.receiveCommand(
            PlayerCommand.Patrol(unit, ALLIED_STATION_POS, PATROL_ORBIT_RADIUS),
            simWorld
        )
        patrolActive = true
        btnPatrol.isActivated = true
        startEngineSound()
        ensureTicking()
    }

    // ---------------------------------------------------------------------------
    // Production
    // ---------------------------------------------------------------------------

    private fun startBuildFighter() {
        if (buildActive) { showStatus("Строительство уже идёт"); return }
        buildActive   = true
        buildProgress = 0f
        buildDrawer.visibility = View.GONE
        showStatus("Строительство истребителя начато (10 с)")
        ensureTicking()
    }

    private fun onFighterBuilt() {
        val id = nextShipId++
        val slotIndex = alliedShips.size
        val col = (slotIndex - 5) % 3
        val row = (slotIndex - 5) / 3
        val homePos = Vec2(
            FLEET_RALLY_CENTER.x + (col - 1) * 3.5f,
            FLEET_RALLY_CENTER.y + 5f + row * 3.5f
        )
        val newShip = ShipState(
            id              = id,
            team            = Team.ALLY,
            homePosition    = homePos,
            homeHeading     = 0f,
            position        = ALLIED_STATION_POS,
            heading         = 0f,
            maxSpeed        = 14f,
            maxAcceleration = 10f,
            maxTurnRate     = 4f
        )
        simWorld.addShip(newShip)
        alliedShips = alliedShips + newShip
        stationAI.addShipToFleet(id)
        selectionOverlay.setBuildProgress(-1, 0f)
        stationAI.receiveCommand(PlayerCommand.ReturnHome(FleetUnit.ExplicitIds(setOf(id))), simWorld)
        ensureTicking()
        showStatus("Истребитель ${id + 1} построен!")
    }

    // ---------------------------------------------------------------------------
    // Voice / text command entry point
    // ---------------------------------------------------------------------------
    fun onVoiceText(text: String) {
        val result = classifier.classify(text)
        val unit = if (selectedShipIds.isEmpty()) FleetUnit.All else FleetUnit.ExplicitIds(selectedShipIds.toSet())
        val enemyStation = simWorld.worldObjectSnapshot().firstOrNull { it.id == 6 }

        val command: PlayerCommand? = when (result.label) {
            "ATTACK_NEAREST" -> PlayerCommand.AttackNearest(unit)
            "ATTACK_STATION" -> enemyStation?.let {
                PlayerCommand.AttackTarget(unit, it.id, it.position)
            }
            "DEFEND_STATION" -> PlayerCommand.DefendStation(unit)
            "RETURN_HOME"    -> PlayerCommand.ReturnHome(unit)
            "PATROL"         -> PlayerCommand.Patrol(unit, ALLIED_STATION_POS, 12f)
            else             -> null
        }

        if (command == null) {
            showStatus("Команда не распознана: «$text»")
            return
        }

        showStatus("[${result.label}] $text")
        stationAI.receiveCommand(command, simWorld)

        if (command !is PlayerCommand.ReturnHome) {
            startEngineSound()
            ensureTicking()
        }
    }

    // ---------------------------------------------------------------------------
    // Speech recognition
    // ---------------------------------------------------------------------------
    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 0)
            return
        }
        if (micActive) return
        micActive = true
        btnMic.isActivated = true
        listenOnce()
    }

    private fun stopListening() {
        micActive = false
        btnMic.isActivated = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun applySettings() {
        val prefs = getSharedPreferences(SettingsActivity.PREF_FILE, MODE_PRIVATE)
        val musicVolume = prefs.getFloat(SettingsActivity.KEY_MUSIC_VOLUME, 0.25f)
        bgMusic?.setVolume(musicVolume, musicVolume)
        // DRAFT — mic button forced off; the g3 voice-command path is not used
        // by Outpost. Restore the prefs-driven toggle when input is rebuilt.
        btnMic.visibility = View.GONE
        stopListening()
        if (::enemyAI.isInitialized)
            enemyAI.aggressiveness = prefs.getInt(SettingsActivity.KEY_ENEMY_AGGRESSION, 0)
    }

    private fun listenOnce() {
        if (!micActive) return
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: android.os.Bundle?) {}
                override fun onResults(results: android.os.Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    runOnUiThread {
                        if (text != null) onVoiceText(text)
                        micActive = false
                        btnMic.isActivated = false
                    }
                }
                override fun onError(error: Int) {
                    runOnUiThread {
                        micActive = false
                        btnMic.isActivated = false
                    }
                }
                override fun onBeginningOfSpeech() {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: android.os.Bundle?) {}
                override fun onPartialResults(p: android.os.Bundle?) {}
                override fun onRmsChanged(v: Float) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 0 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            startListening()
    }

    private fun resetScene() {
        stationAI.clearAllTasks()
        enemyAI.clearAllTasks()
        missionCtrl = null
        stopEngineSound()
        missionThread?.quitSafely(); missionThread = null; missionHandler = null
        initWorld()
        patrolActive = false
        latestHealthBars = emptyList()
        selectedShipIds.clear()
        selectedTargetId = -1
        btnFlyAround.isActivated = false
        btnAttack.isActivated    = false
        engineView.plasmaBillboards = emptyList()
        updateShipCard()
        buildScene()
    }

    private fun selectedOrbitTarget(): OrbitTarget? {
        val target = engineView.scene.firstOrNull { it.id == selectedTargetId } ?: return null
        if (target.id in 0..4) return null
        return OrbitTarget(Vec2(target.x, target.y), target.orbitRadius())
    }

    // ---------------------------------------------------------------------------
    // Tick loop
    // ---------------------------------------------------------------------------
    private fun ensureTicking() {
        if (missionThread == null) {
            missionThread  = HandlerThread("DraftTickThread").also { it.start() }
            missionHandler = Handler(missionThread!!.looper)
        }
    }

    // DRAFT — game state transitions.

    /** Mutate persistent progress and write through to disk. Cheap (SharedPreferences.apply). */
    private fun updateProgress(transform: (GameProgress) -> GameProgress) {
        val updated = transform(gameProgress)
        gameProgress = updated
        progressRepo.save(updated)
    }

    /**
     * Pick an asteroid type from a weight map. Empty / zero-sum maps fall back
     * to NORMAL. Weights don't need to be normalised — the function rescales
     * with a single uniform random draw.
     */
    private fun pickAsteroidType(weights: Map<AsteroidType, Float>): AsteroidType {
        if (weights.isEmpty()) return AsteroidType.NORMAL
        var total = 0f
        for (w in weights.values) if (w > 0f) total += w
        if (total <= 0f) return AsteroidType.NORMAL
        var roll = Math.random().toFloat() * total
        for ((type, w) in weights) {
            if (w <= 0f) continue
            roll -= w
            if (roll <= 0f) return type
        }
        return AsteroidType.NORMAL  // fallback for floating-point edge cases
    }

    private fun nearestAsteroid(x: Float, z: Float): Asteroid? {
        var best: Asteroid? = null
        var bestDist = Float.POSITIVE_INFINITY
        for (a in asteroids) {
            if (a.hp <= 0) continue
            val dx = a.xPos - x
            val dz = a.zPos - z
            val d2 = dx * dx + dz * dz
            if (d2 < bestDist) { bestDist = d2; best = a }
        }
        return best
    }

    // Returns the closest live asteroid within TAP_PICK_RADIUS of the given
    // world point, or null if the tap landed in empty space.
    private fun pickAsteroidAt(wx: Float, wz: Float): Asteroid? {
        var best: Asteroid? = null
        var bestD2 = Float.POSITIVE_INFINITY
        val r2 = DraftCombat.TAP_PICK_RADIUS * DraftCombat.TAP_PICK_RADIUS
        for (a in asteroids) {
            if (a.hp <= 0) continue
            val dx = a.xPos - wx
            val dz = a.zPos - wz
            val d2 = dx * dx + dz * dz
            if (d2 <= r2 && d2 < bestD2) { bestD2 = d2; best = a }
        }
        return best
    }

    // Central-turret target selection. Sticky lock: if the current target
    // is still alive, keep firing at it. Otherwise re-pick using the
    // "highest current HP" rule (the asteroid that takes longest to kill
    // is the most pressing sustained threat), tiebreak nearest to pivot.
    // Side effect: stores the new pick into centralTargetId so subsequent
    // frames stay on it until it dies. Clears centralTargetId when the
    // locked asteroid has died and no live targets remain.
    private fun centralTurretTarget(): Asteroid? {
        val tid = centralTargetId
        if (tid != null) {
            for (a in asteroids) {
                if (a.id == tid && a.hp > 0) return a
            }
            centralTargetId = null
        }
        var best: Asteroid? = null
        var bestHp = Int.MIN_VALUE
        var bestD2 = Float.POSITIVE_INFINITY
        val px = DraftCombat.CENTRAL_TURRET_X
        val pz = DraftCombat.CENTRAL_TURRET_BASE_Z
        for (a in asteroids) {
            if (a.hp <= 0) continue
            val dx = a.xPos - px
            val dz = a.zPos - pz
            val d2 = dx * dx + dz * dz
            if (a.hp > bestHp || (a.hp == bestHp && d2 < bestD2)) {
                bestHp = a.hp
                bestD2 = d2
                best   = a
            }
        }
        if (best != null) centralTargetId = best.id
        return best
    }

    private fun startGame() {
        startMission(Missions.ALL[0])
    }

    private fun showMissionSelect() {
        gameState = GameState.MENU
        hudPanel.visibility     = View.GONE
        abilityBar.visibility   = View.GONE
        abortMissionBtn.visibility = View.GONE
        menuOverlay.visibility          = View.GONE
        removeWinLoseOverlays()
        removeWeaponSelectOverlay()
        missionSelectOverlay.visibility = View.VISIBLE
    }

    private fun showWeaponSelect(mission: MissionConfig) {
        gameState = GameState.MENU
        missionSelectOverlay.visibility = View.GONE
        menuOverlay.visibility          = View.GONE
        removeWinLoseOverlays()
        removeWeaponSelectOverlay()
        val root = engineView.parent as FrameLayout
        val overlay = OverlayFactory.buildWeaponSelect(
            context         = this,
            weapons         = WeaponCatalog.ALL,
            currentWeaponId = currentWeapon.id,
            onChoose = { picked ->
                currentWeapon = picked
                removeWeaponSelectOverlay()
                startMission(mission)
            },
            onBack = {
                removeWeaponSelectOverlay()
                missionSelectOverlay.visibility = View.VISIBLE
            },
        )
        weaponSelectOverlay = overlay
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun removeWeaponSelectOverlay() {
        val root = engineView.parent as? FrameLayout ?: return
        weaponSelectOverlay?.let { root.removeView(it); weaponSelectOverlay = null }
    }

    private fun showUpgrades(returnTo: () -> Unit) {
        upgradesReturnTo = returnTo
        // Hide whatever overlay called us; build a fresh upgrades view.
        menuOverlay.visibility          = View.GONE
        missionSelectOverlay.visibility = View.GONE
        removeWinLoseOverlays()
        removeWeaponSelectOverlay()
        rebuildUpgrades()
    }

    private fun removeWinLoseOverlays() {
        val root = engineView.parent as FrameLayout
        winOverlay?.let { root.removeView(it); winOverlay = null }
        loseOverlay?.let { root.removeView(it); loseOverlay = null }
    }

    private fun rebuildUpgrades() {
        val root = engineView.parent as FrameLayout
        upgradesOverlay?.let { root.removeView(it) }
        val newOverlay = OverlayFactory.buildUpgrades(
            this, gameProgress,
            onPurchase = { type, cost ->
                updateProgress { UpgradeCatalog.applyPurchase(it, type, cost) }
                rebuildUpgrades()   // refresh in place
            },
            onBack = {
                upgradesOverlay?.let { root.removeView(it) }
                upgradesOverlay = null
                upgradesReturnTo?.invoke()
                upgradesReturnTo = null
                // If returning to menu, refresh its metal counter.
                if (menuOverlay.visibility == View.VISIBLE) {
                    OverlayFactory.setBody(menuOverlay, "Всего металла: ${gameProgress.metal}")
                }
            },
        )
        upgradesOverlay = newOverlay
        root.addView(newOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun startMission(mission: MissionConfig) {
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
        effectiveMainWeaponDamage = UpgradeCatalog.mainWeaponDamageAt(gameProgress.mainWeaponDamageLevel)
        effectiveTurretDamage     = UpgradeCatalog.sideTurretDamageAt(gameProgress.sideTurretDamageLevel)
        val effectiveBaseHp   = mission.baseHp + UpgradeCatalog.baseHpBonusAt(gameProgress.baseHpLevel)
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
        bullets.clear()
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
        armedAbilityId = null
        refreshAllAbilityButtons()
        hudScoreText.text   = "Score: 0"
        hudHpText.text      = "HP: $effectiveBaseHp"
        hudEnergyText.text  = "⚡ ${energy.toInt()}/${DraftCombat.ENERGY_MAX.toInt()}"
        hudMissionText.text = mission.name
        hudWaveText.text    = "Волна 1/${mission.waves.size}"
        // Reset shield to READY so the new run starts with the ability available.
        shieldHp         = DraftCombat.SHIELD_MAX_HP
        shieldRecharging = false
        shieldUiPctLast  = -1
        refreshShieldButton()
        // Reset any active buff from the previous run.
        activeBuffTimer     = 0f
        activeBuffDamageMul = 1f
        buffUiSecLast       = -1
        refreshBuffIndicator()
        hudPanel.visibility     = View.VISIBLE
        abilityBar.visibility   = View.VISIBLE
        abortMissionBtn.visibility = View.VISIBLE
        menuOverlay.visibility          = View.GONE
        missionSelectOverlay.visibility = View.GONE
        removeWinLoseOverlays()
        removeWeaponSelectOverlay()
        gameState = GameState.PLAYING
        ensureTicking()
        scheduleDraftTick()
        announceWave(1, mission.waves.size)
    }

    private fun goToMenu() {
        gameState = GameState.MENU
        currentMission = null
        bullets.clear()
        asteroids.clear()
        hudPanel.visibility     = View.GONE
        abilityBar.visibility   = View.GONE
        abortMissionBtn.visibility = View.GONE
        missionSelectOverlay.visibility = View.GONE
        removeWinLoseOverlays()
        removeWeaponSelectOverlay()
        OverlayFactory.setBody(menuOverlay, "Всего металла: ${gameProgress.metal}")
        menuOverlay.visibility = View.VISIBLE
        buildScene()
    }

    private fun showWin() {
        gameState = GameState.WON
        // Win bonus: +20 metal, awarded once per victory.
        missionRun.winBonus = 20
        missionRun.metalEarned += missionRun.winBonus
        updateProgress { it.copy(metal = it.metal + missionRun.winBonus) }
        runOnUiThread { presentWinOverlay() }
    }

    private fun showLose() {
        gameState = GameState.LOST
        runOnUiThread { presentLoseOverlay() }
    }

    private fun pulseBaseDamage() {
        hudHpText.setTextColor(com.example.asteroidoutpost.game.UiTheme.COL_ACCENT_RED)
        hudHpText.animate()
            .scaleX(1.3f).scaleY(1.3f)
            .setDuration(80L)
            .withEndAction {
                hudHpText.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(140L)
                    .withEndAction {
                        hudHpText.setTextColor(com.example.asteroidoutpost.game.UiTheme.COL_TEXT)
                    }
                    .start()
            }
            .start()
    }

    private fun announceWave(waveNum: Int, totalWaves: Int) {
        val text = if (waveNum == totalWaves) "Финальная волна" else "Волна $waveNum"
        runOnUiThread {
            waveAnnounceText.text = text
            waveAnnounceText.alpha = 0f
            waveAnnounceText.visibility = View.VISIBLE
            waveAnnounceText.animate()
                .alpha(1f)
                .setDuration(180L)
                .withEndAction {
                    waveAnnounceText.postDelayed({
                        waveAnnounceText.animate()
                            .alpha(0f)
                            .setDuration(280L)
                            .withEndAction { waveAnnounceText.visibility = View.GONE }
                            .start()
                    }, 700L)
                }
                .start()
        }
    }

    // ---- Shield ability ----------------------------------------------------

    private fun buildShieldButton(): TextView {
        val ctx = this
        return TextView(ctx).apply {
            textSize = com.example.asteroidoutpost.game.UiTheme.SP_CAPTION
            setTextColor(com.example.asteroidoutpost.game.UiTheme.COL_TEXT)
            gravity = Gravity.CENTER
            isAllCaps = false
            // Tighter padding than the standard button — the button itself is
            // only 36dp tall, the body padding would eat the whole interior.
            val pad = com.example.asteroidoutpost.game.UiTheme.dp(ctx, 4f)
            setPadding(pad * 2, pad, pad * 2, pad)
            // Hold-to-recharge — touch listener instead of click. ACTION_UP
            // and ACTION_CANCEL both release. Returning false would let the
            // event bubble; we own the gesture so we return true.
            isClickable = true
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> onShieldDown()
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> onShieldUp()
                }
                true
            }
        }
    }

    private fun buildAbilityButton(slotIndex: Int): TextView {
        val ctx = this
        val theme = com.example.asteroidoutpost.game.UiTheme
        return TextView(ctx).apply {
            textSize = theme.SP_CAPTION
            setTextColor(theme.COL_TEXT)
            gravity = Gravity.CENTER
            isAllCaps = false
            val pad = theme.dp(ctx, 4f)
            setPadding(pad * 2, pad, pad * 2, pad)
            setOnClickListener { onAbilityTapped(slotIndex) }
        }
    }

    /**
     * Small "✕" button anchored top-right of the screen during PLAYING — gives
     * the player a way to abandon the current mission and return to the
     * mission-select screen. No confirmation dialog yet; tap = leave.
     */
    private fun buildAbortMissionButton(): TextView {
        val ctx = this
        val theme = com.example.asteroidoutpost.game.UiTheme
        return TextView(ctx).apply {
            text = "✕"
            textSize = theme.SP_HEADING
            setTextColor(theme.COL_TEXT_DIM)
            gravity = Gravity.CENTER
            isAllCaps = false
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = theme.dp(ctx, theme.DP_BUTTON_RADIUS).toFloat()
                setColor(theme.COL_PANEL_BG)
                setStroke(theme.dp(ctx, theme.DP_BORDER_WIDTH), theme.COL_BORDER)
            }
            setOnClickListener { onAbortMissionTapped() }
        }
    }

    /** Player tapped the ✕ button — bail out to mission select. */
    private fun onAbortMissionTapped() {
        if (gameState != GameState.PLAYING) return
        showMissionSelect()
    }

    private fun shieldButtonDrawable(fill: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = com.example.asteroidoutpost.game.UiTheme.dp(
                this@MainActivity, com.example.asteroidoutpost.game.UiTheme.DP_BUTTON_RADIUS,
            ).toFloat()
            setColor(fill)
        }

    /**
     * Shield button — shows current HP and surface state. Three visual
     * cases:
     *  - Recharging (button held, energy + headroom): green accent
     *  - Full HP: dim (no need to top up)
     *  - Damaged: blue accent — tap-and-hold to refill
     * The label is the integer HP value, glance-readable.
     */
    private fun refreshShieldButton() {
        val theme = com.example.asteroidoutpost.game.UiTheme
        val hp    = shieldHp.toInt()
        val full  = hp >= DraftCombat.SHIELD_MAX_HP.toInt()
        shieldButton.text = "ЩИТ $hp"
        when {
            shieldRecharging && energy > 0f && !full -> {
                shieldButton.background = shieldButtonDrawable(theme.COL_ACCENT_GREEN)
                shieldButton.setTextColor(theme.COL_TEXT)
            }
            full -> {
                shieldButton.background = shieldButtonDrawable(theme.COL_PANEL_BG_HI)
                shieldButton.setTextColor(theme.COL_TEXT_DISABLED)
            }
            else -> {
                shieldButton.background = shieldButtonDrawable(theme.COL_ACCENT_BLUE)
                shieldButton.setTextColor(theme.COL_TEXT)
            }
        }
    }

    /**
     * Apply current ability slot state to its button. States:
     *  - ARMED      (this slot is the armedAbilityId): green accent, label "!"
     *  - COOLING    (currentCd > 0): dim panel fill, "${sec}с"
     *  - INSUFFICIENT-ENERGY (energy < cost): dim, label only, disabled
     *  - READY      (otherwise): blue accent, label, enabled
     */
    private fun refreshAbilityButton(slotIndex: Int) {
        if (slotIndex !in abilitySlots.indices) return
        val slot  = abilitySlots[slotIndex]
        val btn   = abilityButtons[slotIndex]
        val theme = com.example.asteroidoutpost.game.UiTheme
        val a     = slot.ability
        when {
            armedAbilityId == a.id -> {
                btn.text       = "${a.shortLabel}!"
                btn.background = shieldButtonDrawable(theme.COL_ACCENT_GREEN)
                btn.setTextColor(theme.COL_TEXT)
                btn.isEnabled  = true
            }
            slot.currentCd > 0f -> {
                val sec = kotlin.math.ceil(slot.currentCd.toDouble()).toInt()
                    .coerceAtLeast(1)
                btn.text       = "${sec}с"
                btn.background = shieldButtonDrawable(theme.COL_PANEL_BG_HI)
                btn.setTextColor(theme.COL_TEXT_DISABLED)
                btn.isEnabled  = false
            }
            energy < a.cost -> {
                btn.text       = a.shortLabel
                btn.background = shieldButtonDrawable(theme.COL_PANEL_BG_HI)
                btn.setTextColor(theme.COL_TEXT_DISABLED)
                btn.isEnabled  = false
            }
            else -> {
                btn.text       = a.shortLabel
                btn.background = shieldButtonDrawable(theme.COL_ACCENT_BLUE)
                btn.setTextColor(theme.COL_TEXT)
                btn.isEnabled  = true
            }
        }
    }

    private fun refreshAllAbilityButtons() {
        abilitySlots.indices.forEach { refreshAbilityButton(it) }
    }

    /**
     * Player tapped an ability button. Ready instant abilities apply
     * immediately; targeted abilities (needsTarget=true) arm and wait for a
     * follow-up screen interaction. Tapping the same armed button cancels
     * the arm. Spend / cooldown application lives in `activateAbility`,
     * which is called by both routes (instant here; targeted from the
     * touch handler when the gesture completes).
     */
    private fun onAbilityTapped(slotIndex: Int) {
        if (gameState != GameState.PLAYING) return
        if (slotIndex !in abilitySlots.indices) return
        val slot = abilitySlots[slotIndex]
        val a    = slot.ability
        // Tapping an already-armed ability cancels the arm.
        if (armedAbilityId == a.id) {
            armedAbilityId = null
            refreshAbilityButton(slotIndex)
            return
        }
        if (slot.currentCd > 0f) return
        if (energy < a.cost) return
        if (a.needsTarget) {
            // Cancel any other armed ability and arm this one. The screen
            // touch handler routes the next gesture into `applyArmedAbility`.
            armedAbilityId = a.id
            refreshAllAbilityButtons()
            return
        }
        // Marshal the actual fire onto the tick thread. The tick mutates
        // `asteroids` and `bullets` on its own thread (DraftTickThread),
        // so spawning rocket bullets from the UI thread directly would
        // race with the tick's iterator and risk CME. Posting through
        // missionHandler runs activation between two ticks, atomic with
        // the rest of the simulation.
        missionHandler?.post { activateAbility(slot) }
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
                else { launchRocketStrike(targets); true }
            }
            AbilityId.LASER_STRIKE  -> false   /* M8.6 */
        }
        if (!fired) return false
        energy         = (energy - a.cost).coerceAtLeast(0f)
        energyUiLast   = -1                    // force HUD energy refresh next tick
        slot.currentCd = a.cooldownSec
        slot.cdUiLast  = -1
        armedAbilityId = null
        runOnUiThread {
            hudEnergyText.text =
                "⚡ ${energy.toInt()}/${DraftCombat.ENERGY_MAX.toInt()}"
            refreshAllAbilityButtons()
        }
        return true
    }

    /**
     * Routes engine-surface touches into the currently armed ability while
     * an arm is active. Per-ability gesture logic is filled in by the
     * specific milestones (M8.6 wires the laser drag-and-release).
     */
    private fun handleArmedAbilityTouch(event: MotionEvent) {
        val armed = armedAbilityId ?: return
        when (armed) {
            AbilityId.LASER_STRIKE -> {
                val w = engineView.width.toFloat()
                val h = engineView.height.toFloat()
                if (w <= 0f || h <= 0f) return
                val worldX = (event.x / w - 0.5f) *
                             (DraftCombat.SCREEN_HALF_W * 2f)
                val zSpan  = DraftCombat.SCREEN_TOP_Z -
                             DraftCombat.SCREEN_BOTTOM_Z
                val worldZ = DraftCombat.SCREEN_TOP_Z -
                             (event.y / h) * zSpan
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        laserStartX = worldX
                        laserStartZ = worldZ
                        laserGestureActive = true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (laserGestureActive) {
                            val sx = laserStartX
                            val sz = laserStartZ
                            laserGestureActive = false
                            // Marshal the actual fire onto the tick thread —
                            // ${rocket strike} pattern: avoids racing with
                            // the move/collision loop's iterators.
                            val slot = abilitySlots.firstOrNull {
                                it.ability.id == AbilityId.LASER_STRIKE
                            } ?: return
                            missionHandler?.post {
                                fireLaserStrike(slot, sx, sz, worldX, worldZ)
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        laserGestureActive = false
                    }
                }
            }
            AbilityId.ROCKET_STRIKE -> {
                // ROCKET_STRIKE is instant (needsTarget=false) — should not
                // arm. Defensive clear in case of a future regression.
                armedAbilityId = null
                refreshAllAbilityButtons()
            }
        }
    }

    /** Refresh the buff indicator to match the active buff (or hide it). */
    private fun refreshBuffIndicator() {
        if (activeBuffTimer > 0f) {
            val sec = kotlin.math.ceil(activeBuffTimer.toDouble()).toInt().coerceAtLeast(1)
            buffIndicator.text       = "⚡ ×${"%.1f".format(activeBuffDamageMul)} урон  ${sec}с"
            buffIndicator.visibility = View.VISIBLE
        } else {
            buffIndicator.visibility = View.GONE
        }
    }

    /**
     * Hold-to-recharge: ACTION_DOWN on the shield button starts the
     * recharge drain (handled by tick), ACTION_UP / CANCEL stops it.
     * This replaces the legacy on/off shield activation.
     */
    private fun onShieldDown() {
        if (gameState != GameState.PLAYING) return
        shieldRecharging = true
        refreshShieldButton()
    }

    private fun onShieldUp() {
        shieldRecharging = false
        refreshShieldButton()
    }

    private fun buildHudPanel(): View {
        val ctx = this
        val theme = com.example.asteroidoutpost.game.UiTheme
        // No stylePanel here — the HUD is intentionally background-less so it
        // doesn't compete visually with gameplay. Abort ✕ button is embedded
        // as the rightmost child of the same horizontal row, so it sits in
        // the (invisible) panel contour rather than floating over the scene.
        // Text sizes are 30% smaller than the regular sci-fi typography to
        // keep the readout unobtrusive (HUD is glanceable, not read).
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val hudScale = 0.7f

        // Left column: mission name (caption) + wave (heading).
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        hudMissionText = com.example.asteroidoutpost.game.UiHelpers
            .buildCaption(ctx, "")
            .apply { textSize = theme.SP_CAPTION * hudScale }
        hudWaveText = com.example.asteroidoutpost.game.UiHelpers
            .buildHeading(ctx, "")
            .apply { textSize = theme.SP_HEADING * hudScale }
        left.addView(hudMissionText)
        left.addView(hudWaveText)

        // Right column: score + HP, right-aligned.
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        hudScoreText = com.example.asteroidoutpost.game.UiHelpers
            .buildHeading(ctx, "Score: 0")
            .apply { gravity = Gravity.END; textSize = theme.SP_HEADING * hudScale }
        hudHpText = com.example.asteroidoutpost.game.UiHelpers
            .buildBody(ctx, "HP: 100", theme.COL_TEXT)
            .apply { gravity = Gravity.END; textSize = theme.SP_BODY * hudScale }
        // Energy — fuel for active abilities. Cyan/blue accent so it reads
        // distinct from white HP (which animates red on damage).
        hudEnergyText = com.example.asteroidoutpost.game.UiHelpers
            .buildBody(ctx, "⚡ ${DraftCombat.ENERGY_MAX.toInt()}/${DraftCombat.ENERGY_MAX.toInt()}",
                       theme.COL_ACCENT_BLUE)
            .apply { gravity = Gravity.END; textSize = theme.SP_BODY * hudScale }
        right.addView(hudScoreText)
        right.addView(hudHpText)
        right.addView(hudEnergyText)

        panel.addView(
            left,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        panel.addView(
            right,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        // Abort ✕ — embedded in the HUD row so it sits inside the (invisible)
        // panel contour rather than floating over the scene. Built here and
        // assigned to `abortMissionBtn` so it shares visibility with the HUD.
        abortMissionBtn = buildAbortMissionButton().apply {
            textSize = theme.SP_HEADING * hudScale
        }
        val abortSize = theme.dp(ctx, 36f)
        val abortLp = LinearLayout.LayoutParams(abortSize, abortSize).apply {
            marginStart = theme.dp(ctx, theme.DP_GAP_TIGHT)
        }
        panel.addView(abortMissionBtn, abortLp)

        return panel
    }

    private fun presentWinOverlay() {
        val mission = currentMission ?: return
        val root = engineView.parent as FrameLayout
        winOverlay?.let { root.removeView(it) }
        val stats = listOf(
            "Уничтожено астероидов" to missionRun.asteroidsDestroyed.toString(),
            "Очки"                  to missionRun.score.toString(),
            "Получено металла"      to "${missionRun.metalEarned} (+${missionRun.winBonus} бонус)",
            "Всего металла"         to gameProgress.metal.toString(),
        )
        val buttons = mutableListOf<Pair<String, () -> Unit>>()
        val nextIdx = Missions.ALL.indexOf(mission) + 1
        if (nextIdx in Missions.ALL.indices) {
            buttons += "Следующая миссия" to { startMission(Missions.ALL[nextIdx]) }
        }
        buttons += "Повторить"       to { startMission(mission) }
        buttons += "Улучшения"       to { showUpgrades { presentWinOverlay() } }
        buttons += "К выбору миссий" to { showMissionSelect() }
        val view = OverlayFactory.buildEndOfMission(
            context  = this,
            title    = "МИССИЯ ВЫПОЛНЕНА",
            subtitle = mission.name,
            stats    = stats,
            accent   = com.example.asteroidoutpost.game.UiTheme.COL_ACCENT_GREEN,
            buttons  = buttons,
        )
        winOverlay = view
        root.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun presentLoseOverlay() {
        val mission = currentMission ?: return
        val root = engineView.parent as FrameLayout
        loseOverlay?.let { root.removeView(it) }
        val stats = listOf(
            "Пройдено волн"         to "${missionRun.currentWaveDisplay - 1}/${missionRun.totalWaves}",
            "Уничтожено астероидов" to missionRun.asteroidsDestroyed.toString(),
            "Очки"                  to missionRun.score.toString(),
            "Получено металла"      to missionRun.metalEarned.toString(),
            "Всего металла"         to gameProgress.metal.toString(),
        )
        val buttons = listOf<Pair<String, () -> Unit>>(
            "Повторить миссию" to { startMission(mission) },
            "Улучшения"        to { showUpgrades { presentLoseOverlay() } },
            "К выбору миссий"  to { showMissionSelect() },
        )
        val view = OverlayFactory.buildEndOfMission(
            context    = this,
            title      = "БАЗА РАЗРУШЕНА",
            subtitle   = mission.name,
            stats      = stats,
            motivation = "Усильте робота или базу и попробуйте снова.",
            accent     = com.example.asteroidoutpost.game.UiTheme.COL_ACCENT_RED,
            buttons    = buttons,
        )
        loseOverlay = view
        root.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun scheduleDraftTick() {
        missionHandler?.postDelayed({
            if (gameState != GameState.PLAYING) return@postDelayed
            val dt = TICK_MS / 1000f

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
            // Shield-HP UI throttle (separate from recharge — also catches
            // damage absorbs): refresh button text when integer-percent
            // changes.
            run {
                val pct = ((shieldHp / DraftCombat.SHIELD_MAX_HP) * 100f).toInt()
                if (pct != shieldUiPctLast) {
                    shieldUiPctLast = pct
                    runOnUiThread { refreshShieldButton() }
                }
            }

            // Auto-aim. The turret picks the most dangerous live asteroid
            // (with optional priority override from a tap) and rotates to
            // face it. With no targets the barrel returns to vertical.
            val pivotX = DraftCombat.CENTRAL_TURRET_X
            val pivotZ = DraftCombat.CENTRAL_TURRET_BASE_Z
            val centralTarget = centralTurretTarget()
            val targetAngle = if (centralTarget != null) {
                val tx = centralTarget.xPos - pivotX
                val tz = kotlin.math.max(0f, centralTarget.zPos - pivotZ)
                if (tx == 0f && tz == 0f) centralTurretAngle
                else kotlin.math.atan2(tx, tz)
            } else 0f
            // Smooth tracking — exponential approach, fast enough to feel
            // responsive but soft enough to read as deliberate motion when
            // the target switches.
            centralTurretAngle += (targetAngle - centralTurretAngle) * 16f * dt

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
                    runOnUiThread {
                        hudEnergyText.text =
                            "⚡ $ei/${DraftCombat.ENERGY_MAX.toInt()}"
                        // Refresh ability buttons too — energy may have just
                        // crossed a cost threshold (disabled→ready transition).
                        refreshAllAbilityButtons()
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
                        runOnUiThread { refreshAbilityButton(i) }
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
                    runOnUiThread { refreshBuffIndicator() }
                } else {
                    val sec = kotlin.math.ceil(activeBuffTimer.toDouble()).toInt()
                    if (sec != buffUiSecLast) {
                        buffUiSecLast = sec
                        runOnUiThread { refreshBuffIndicator() }
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
            val aimAligned = kotlin.math.abs(targetAngle - centralTurretAngle) <
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
                bullets.add(Bullet(
                    x  = muzzleX,
                    z  = muzzleZ,
                    vx = sinA * weapon.projectileSpeed,
                    vz = cosA * weapon.projectileSpeed,
                    damage = weaponDamage,
                    halfW = weapon.projectileHalfW,
                    halfH = weapon.projectileHalfH,
                    meshHandle = bulletMesh,
                    aoeRadius = weapon.aoeRadius,
                    aoeDamage = (weaponDamage * weapon.aoeDamageMultiplier).toInt(),
                ))
                // E12 — Railgun (HEAVY_CANNON) gets the lightning discharge
                // muzzle stack: bright cyan-white core flash + 5-7
                // procedural electric arcs perpendicular to the barrel +
                // cyan sparks. Other weapons (Автомат, future additions)
                // keep the warm cone-trefoil muzzle blast — the asymmetry
                // makes the player's primary railgun read as a unique,
                // higher-tier weapon.
                if (weapon.id == WeaponId.HEAVY_CANNON) {
                    spawnRailgunMuzzle(muzzleX, muzzleZ, sinA, cosA,
                                       weapon.projectileHalfW)
                    spawnRailgunSparks(muzzleX, muzzleZ,
                                       sinA * weapon.projectileSpeed,
                                       cosA * weapon.projectileSpeed)
                } else {
                    // Muzzle blast — cannon-with-brake shape (central pop +
                    // forward plume + 2 perpendicular vents). Sized by the
                    // weapon's projectile half-width so the heavy cannon pops
                    // bigger than the automatic.
                    spawnMuzzleBlast(muzzleX, muzzleZ, sinA, cosA,
                                     weapon.projectileHalfW,
                                     DraftCombat.FLASH_TINT_MUZZLE)
                    // E9 — micro-sparks fanning out of the barrel along the
                    // bullet velocity. Brief (~0.1s) so they punctuate the
                    // shot without obscuring the muzzle blast cluster.
                    spawnMuzzleSparks(muzzleX, muzzleZ,
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
                    val target = nearestAsteroid(tx, tz) ?: continue
                    val dx = target.xPos - tx
                    val dz = target.zPos - tz
                    val len = kotlin.math.sqrt(dx * dx + dz * dz)
                    if (len < 1e-4f) continue
                    val nx = dx / len
                    val nz = dz / len
                    val muzzleX = tx
                    val muzzleZ = tz + DraftCombat.TURRET_HALF
                    // Side turrets fire heavy AoE shells matching the central
                    // HEAVY_CANNON weapon profile — chunky projectile, slow
                    // muzzle velocity, splash damage to nearby asteroids. The
                    // cadence is paced by FIRE_INTERVAL_SEC (1 shot/sec) so
                    // they read as supporting artillery rather than a stream
                    // of small projectiles.
                    val sideDamage = (effectiveTurretDamage * DraftCombat.SIDE_DAMAGE_MUL).toInt()
                    bullets.add(Bullet(
                        x  = muzzleX, z = muzzleZ,
                        vx = nx * DraftCombat.SIDE_BULLET_SPEED,
                        vz = nz * DraftCombat.SIDE_BULLET_SPEED,
                        damage     = sideDamage,
                        halfW      = DraftCombat.SIDE_BULLET_HALF_W,
                        halfH      = DraftCombat.SIDE_BULLET_HALF_H,
                        meshHandle = bulletHeavyMeshHandle,
                        aoeRadius  = DraftCombat.SIDE_AOE_RADIUS,
                        aoeDamage  = (sideDamage * DraftCombat.SIDE_AOE_DAMAGE_MUL).toInt(),
                    ))
                    // Side-turret muzzle blast — same cannon-with-brake
                    // cluster as the central turret, sized by the bullet's
                    // half-width (so an upgraded chunkier projectile gets a
                    // bigger pop without per-turret-type flags).
                    spawnMuzzleBlast(muzzleX, muzzleZ, nx, nz,
                                     bullets.last().halfW,
                                     DraftCombat.FLASH_TINT_MUZZLE)
                    // E9 — side turrets get the same micro-spark fan; the
                    // sparks tell "this is a turret shot" identically across
                    // central and side, the muzzle-blast size tells projectile
                    // weight.
                    spawnMuzzleSparks(muzzleX, muzzleZ,
                                      nx * DraftCombat.BULLET_SPEED,
                                      nz * DraftCombat.BULLET_SPEED)
                }
            }
            // Move bullets along their velocity; cull off-screen, apply damage on hit.
            val bulletIter = bullets.iterator()
            while (bulletIter.hasNext()) {
                val b = bulletIter.next()
                // E10.3 — snapshot prev (x, z) before moving so the next
                // frame's render can build a prev_model matrix that lags
                // current by one tick — the velocity attachment then reads
                // as the bullet's true on-screen velocity.
                b.prevX = b.x
                b.prevZ = b.z
                // M8.5 — homing missiles bend their velocity vector toward
                // the tracked asteroid before applying movement. If the
                // target has died since launch, the missile coasts on its
                // last heading and detonates on the first thing it hits.
                val homeId = b.homingTargetId
                if (homeId != null) {
                    var target: Asteroid? = null
                    for (a in asteroids) {
                        if (a.id == homeId && a.hp > 0) { target = a; break }
                    }
                    if (target != null) homeBulletTowardsTarget(b, target, dt)
                }
                b.x += b.vx * dt
                b.z += b.vz * dt
                if (b.z > DraftCombat.SCREEN_TOP_Z + 1f ||
                    b.z < DraftCombat.SCREEN_BOTTOM_Z - 1f ||
                    b.x < -DraftCombat.SCREEN_HALF_W - 1f ||
                    b.x >  DraftCombat.SCREEN_HALF_W + 1f) {
                    bulletIter.remove()
                    continue
                }
                var hit = false
                var hitX = 0f
                var hitZ = 0f
                for (a in asteroids) {
                    if (a.hp <= 0) continue
                    val dx = kotlin.math.abs(b.x - a.xPos)
                    val dz = kotlin.math.abs(b.z - a.zPos)
                    if (dx < a.half + b.halfW &&
                        dz < a.half + b.halfH) {
                        a.hp -= b.damage
                        hit = true
                        hitX = a.xPos
                        hitZ = a.zPos
                        break
                    }
                }
                if (hit) {
                    if (b.aoeRadius > 0f && b.aoeDamage > 0) {
                        // AoE: apply splash damage to other live asteroids within
                        // aoeRadius (centred on the hit asteroid). The direct
                        // target already took full damage above and is excluded.
                        // The fireball + spark burst inside spawnExplosion is the
                        // cannon's hit-feedback — visibly bigger than the small
                        // hit flash a direct-hit bullet would otherwise spawn.
                        val r2 = b.aoeRadius * b.aoeRadius
                        for (a in asteroids) {
                            if (a.hp <= 0) continue
                            val ax = a.xPos - hitX
                            val az = a.zPos - hitZ
                            val d2 = ax * ax + az * az
                            if (d2 > 1e-6f && d2 <= r2) {
                                a.hp -= b.aoeDamage
                            }
                        }
                        spawnExplosion(hitX, hitZ, b.aoeRadius)
                    } else {
                        // Direct-hit bullet (no AoE) — spawn a small hit flash
                        // sized by the projectile's half-width, so cannon-style
                        // bullets read as a chunkier impact than mg-style ones.
                        // No fireball needed: the flash punctuates the moment,
                        // motion blur (E10.4) carries the trajectory feel.
                        val ht = DraftCombat.FLASH_TINT_HIT
                        flashes.add(Flash(
                            x = hitX, z = hitZ,
                            life = DraftCombat.HIT_FLASH_LIFE,
                            maxLife = DraftCombat.HIT_FLASH_LIFE,
                            halfMax = b.halfW * DraftCombat.HIT_FLASH_SIZE_MUL,
                            tintR = ht[0], tintG = ht[1], tintB = ht[2], tintA = ht[3],
                        ))
                    }
                    bulletIter.remove()
                }
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
                            spawnExplosion(a.xPos, a.zPos, DraftCombat.EXPLOSIVE_AOE_RADIUS)
                        }
                        AsteroidType.ENERGY -> {
                            triggeredBuff = true
                            val et = DraftCombat.FLASH_TINT_ENERGY
                            flashes.add(Flash(
                                x = a.xPos, z = a.zPos,
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
                                x = a.xPos, z = a.zPos,
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
                            spawnAsteroidDeathFX(a.xPos, a.zPos, tint)
                        }
                    }
                }
                if (triggeredBuff) {
                    activeBuffTimer     = DraftCombat.ENERGY_BUFF_DURATION
                    activeBuffDamageMul = DraftCombat.ENERGY_BUFF_DAMAGE_MUL
                    buffUiSecLast = -1
                    runOnUiThread { refreshBuffIndicator() }
                }
                missionRun.score += killed * 10
                missionRun.asteroidsDestroyed += killed
                missionRun.metalEarned += killed   // +1 metal per asteroid
                updateProgress { it.copy(metal = it.metal + killed) }
                runOnUiThread { hudScoreText.text = "Score: ${missionRun.score}" }
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
                // fall + spin (correct delta for motion blur).
                a.prevZ        = a.zPos
                a.prevRotation = a.rotation
                a.zPos     -= a.speed * dt
                a.rotation += a.rotationSpeed * dt
            }
            // Asteroid bottom edge touches platform top → damage and remove.
            var platformDamage = 0
            val asteroidIter = asteroids.iterator()
            while (asteroidIter.hasNext()) {
                val a = asteroidIter.next()
                if (a.zPos - a.half <= DraftCombat.PLATFORM_TOP_Z) {
                    val dmgF = a.platformDmg.toFloat()
                    if (shieldHp >= dmgF) {
                        // Full absorb. Shield drops by the full asteroid
                        // damage, base untouched. Shield-hit flash so the
                        // player reads the deflection.
                        shieldHp -= dmgF
                        val sh = DraftCombat.FLASH_TINT_SHIELD
                        flashes.add(Flash(
                            x = a.xPos,
                            z = DraftCombat.PLATFORM_TOP_Z + a.half,
                            life = DraftCombat.FLASH_LIFE_SEC,
                            maxLife = DraftCombat.FLASH_LIFE_SEC,
                            tintR = sh[0], tintG = sh[1], tintB = sh[2], tintA = sh[3],
                        ))
                    } else if (shieldHp > 0f) {
                        // Partial absorb — shield breaks and overflow hits
                        // the base. The break flash uses the shield tint
                        // (same visual language) but the base also takes
                        // the spill damage this tick.
                        val overflow = (dmgF - shieldHp).toInt().coerceAtLeast(1)
                        shieldHp = 0f
                        val sh = DraftCombat.FLASH_TINT_SHIELD
                        flashes.add(Flash(
                            x = a.xPos,
                            z = DraftCombat.PLATFORM_TOP_Z + a.half,
                            life = DraftCombat.FLASH_LIFE_SEC,
                            maxLife = DraftCombat.FLASH_LIFE_SEC,
                            tintR = sh[0], tintG = sh[1], tintB = sh[2], tintA = sh[3],
                        ))
                        platformDamage += overflow
                    } else {
                        // Shield down — full damage to platform.
                        platformDamage += a.platformDmg
                    }
                    asteroidIter.remove()
                }
            }
            if (platformDamage > 0) {
                platformHP -= platformDamage
                runOnUiThread {
                    hudHpText.text = "HP: $platformHP"
                    pulseBaseDamage()
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
                        runOnUiThread {
                            hudWaveText.text =
                                "Волна ${missionRun.currentWaveDisplay}/${mission.waves.size}"
                        }
                        announceWave(missionRun.currentWaveDisplay, mission.waves.size)
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
                            asteroids.add(
                                Asteroid(
                                    id    = newAsteroidId(),
                                    xPos  = rx,
                                    zPos  = DraftCombat.SCREEN_TOP_Z - half,
                                    hp    = hpVal,
                                    maxHp = hpVal,
                                    rotation      = phase,
                                    rotationSpeed = spin,
                                    type          = type,
                                    speed         = mission.asteroidSpeed * type.speedMul,
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
                        // If this was the last wave, B3 (showWin check) handles it.
                    }
                }
            }

            buildScene()

            // Win / lose checks.
            // Win: last wave fully spawned AND no asteroids left.
            val m = currentMission
            val onLastWave = m != null && currentWaveIndex == m.waves.size - 1
            val lastWaveSpawnedOut = m != null &&
                currentWaveSpawned >= m.waves[currentWaveIndex.coerceAtMost(m.waves.size - 1)].asteroidCount
            when {
                platformHP <= 0 -> { showLose(); return@postDelayed }
                onLastWave && lastWaveSpawnedOut && asteroids.isEmpty() -> {
                    showWin(); return@postDelayed
                }
            }

            scheduleDraftTick()
        }, TICK_MS)
    }

    private fun stopTickingIfIdle() {
        if (!stationAI.hasActiveTasks() && !enemyAI.hasActiveTasks() && missionCtrl == null && !buildActive) {
            missionThread?.quitSafely()
            missionThread  = null
            missionHandler = null
        }
    }

    // Old scheduleMissionTick removed — replaced by scheduleDraftTick above.

    // ---------------------------------------------------------------------------
    // Scene data builders
    // ---------------------------------------------------------------------------
    // DRAFT — Asteroid Outpost: no enemy ships yet. Drones will be spawned by a
    // WaveSpawner in M1.C.2, not preplaced as ship formations.
    private val enemyFormation: List<Triple<Float, Float, Float>> = emptyList()

    private fun buildInitialAlliedShips(): List<ShipState> =
        shipFormation.mapIndexed { index, (x, y, rotZ) ->
            ShipState(
                id           = index,
                team         = Team.ALLY,
                homePosition = Vec2(x, y),
                homeHeading  = rotZ,
                position     = Vec2(x, y),
                heading      = rotZ,
                maxSpeed        = 14f,
                maxAcceleration = 10f,
                maxTurnRate     = 4f
            )
        }

    private fun buildInitialEnemyShips(): List<ShipState> =
        enemyFormation.mapIndexed { index, (x, y, rotZ) ->
            ShipState(
                id           = 7 + index,
                team         = Team.ENEMY,
                homePosition = Vec2(x, y),
                homeHeading  = rotZ,
                position     = Vec2(x, y),
                heading      = rotZ,
                maxSpeed        = 14f,
                maxAcceleration = 10f,
                maxTurnRate     = 4f
            )
        }

    private fun buildInitialShips(): List<ShipState> =
        buildInitialAlliedShips() + buildInitialEnemyShips()

    // DRAFT — Asteroid Outpost: only the central platform exists. Turret slots,
    // central cannon, and drone enemies will be added in M1.
    private fun buildInitialWorldObjects(): List<WorldObject> = listOf(
        WorldObject(id = 5, team = Team.ALLY, objectType = WorldObjectType.STATION, position = Vec2(0f, 0f), z = 0f, combatStats = CombatStats.station()),
    )

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------
    private fun computeHealthBars(
        worldObjects: List<WorldObject>,
        ships: List<ShipState> = emptyList()
    ): List<HealthBarData> {
        fun statsToBar(id: Int, stats: CombatStats): HealthBarData? {
            val shieldFraction = if (stats.maxShield > 0f) stats.shield / stats.maxShield else 1f
            val hullFraction   = if (stats.maxHull   > 0f) stats.hull   / stats.maxHull   else 1f
            val isDamaged  = stats.shield < stats.maxShield || stats.hull < stats.maxHull
            val isSelected = id == selectedTargetId
            if (!isSelected && !isDamaged) return null
            return HealthBarData(id, shieldFraction, hullFraction)
        }
        return worldObjects.mapNotNull { statsToBar(it.id, it.combatStats) } +
               ships.filter { !it.combatStats.isDestroyed }
                    .mapNotNull { statsToBar(it.id, it.combatStats) }
    }

    private fun handleDestroyedObjects(events: List<CombatEvent>) {
        val destroyedIds = events.filterIsInstance<CombatEvent.ObjectDestroyed>().map { it.targetId }.toSet()
        if (destroyedIds.isEmpty()) return
        runOnUiThread {
            if (selectedTargetId in destroyedIds) {
                selectedTargetId = -1
                updateShipCard()
            }
        }
    }

    private fun playCombatEvents(events: List<CombatEvent>) {
        for (event in events) {
            if (event is CombatEvent.ProjectileSpawned)
                soundPool.play(soundShootId, 1f, 1f, 1, 0, 1f)
        }
    }

    private fun startEngineSound() {
        if (soundStreamId == 0) soundStreamId = soundPool.play(soundEngineId, 1f, 1f, 1, -1, 1f)
    }

    private fun stopEngineSound() {
        if (soundStreamId != 0) { soundPool.stop(soundStreamId); soundStreamId = 0 }
    }

    private fun updateShipCard() {
        if (selectedShipIds.isEmpty()) { shipCard.visibility = View.GONE; return }
        val sortedIds = selectedShipIds.sorted()
        shipCard.visibility = View.VISIBLE
        shipCardTitle.text = if (sortedIds.size == 1) "Истребитель ${sortedIds.first() + 1}"
                             else "Группа: ${sortedIds.size}"
        shipCardSubtitle.text = sortedIds.joinToString(prefix = "Борт ", separator = ", ") { (it + 1).toString() }
        val targetObj = engineView.scene.firstOrNull { it.id == selectedTargetId }
        val coords = targetObj?.let { " (%.0f, %.0f)".format(it.x, it.y) } ?: ""
        shipCardHint.text = when (selectedTargetId) {
            -1   -> "Выберите цель"
            5    -> "Цель: союзная станция$coords"
            6    -> "Цель: вражеская станция$coords"
            else -> "Цель: истребитель ${selectedTargetId - 6}$coords"
        }
    }

    // ---------------------------------------------------------------------------
    override fun onResume()  { super.onResume();  engineView.onResume(); bgMusic?.start() }
    override fun onPause()   { super.onPause();   engineView.onPause();  bgMusic?.pause() }
    override fun onDestroy() {
        micActive = false
        speechRecognizer?.destroy()
        super.onDestroy()
        if (::stationAI.isInitialized) stationAI.clearAllTasks()
        missionThread?.quitSafely(); missionThread = null; missionHandler = null
        bgMusic?.release(); bgMusic = null
        soundPool.release()
        engineView.onDestroyView()
    }

    // ---------------------------------------------------------------------------
    // Camera orientation tracking
    // ---------------------------------------------------------------------------
    private fun applyCameraOrbit(yaw: Float, pitch: Float) {
        val deg = Math::toDegrees
        // Yaw around world Y — premultiply (left-multiply) so it acts in world space
        val yawMat = camRotTemp
        android.opengl.Matrix.setRotateM(yawMat, 0, deg(yaw.toDouble()).toFloat(), 0f, 1f, 0f)
        val tmp = FloatArray(16)
        android.opengl.Matrix.multiplyMM(tmp, 0, yawMat, 0, camRotMatrix, 0)
        // Pitch around local X — postmultiply (right-multiply) so it acts in local space
        android.opengl.Matrix.rotateM(tmp, 0, deg(pitch.toDouble()).toFloat(), 1f, 0f, 0f)
        tmp.copyInto(camRotMatrix)
        axisIndicator.setRotationMatrix(camRotMatrix)
    }

    private fun applyCameraRoll(roll: Float) {
        // Roll around local Z — postmultiply
        android.opengl.Matrix.rotateM(camRotMatrix, 0,
            Math.toDegrees(roll.toDouble()).toFloat(), 0f, 0f, 1f)
        axisIndicator.setRotationMatrix(camRotMatrix)
    }

    private fun resetCameraMatrix() {
        android.opengl.Matrix.setIdentityM(camRotMatrix, 0)
        android.opengl.Matrix.rotateM(camRotMatrix, 0,
            Math.toDegrees(INITIAL_CAM_PITCH.toDouble()).toFloat(), 1f, 0f, 0f)
        if (::axisIndicator.isInitialized) axisIndicator.setRotationMatrix(camRotMatrix)
    }

    private fun rebuildCamMatrix() = resetCameraMatrix()

    // ---------------------------------------------------------------------------
    // Settings panel slide-in / slide-out
    // ---------------------------------------------------------------------------
    private fun initSettingsPanel() {
        axisPanel.post {
            // Shift axis panel 5% of screen width to the left
            val screenWidth = resources.displayMetrics.widthPixels
            val params = axisPanel.layoutParams as FrameLayout.LayoutParams
            params.marginEnd = params.marginEnd + (screenWidth * 0.05f).toInt()
            axisPanel.layoutParams = params

            // Start with settings button hidden off-screen to the right
            val offscreen = (btnSettings.width + 32 * resources.displayMetrics.density)
            btnSettings.translationX = offscreen
        }
        var touchStartY = 0f
        settingsPullTab.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { touchStartY = event.rawY; true }
                MotionEvent.ACTION_UP -> {
                    if (event.rawY - touchStartY > 20 * resources.displayMetrics.density) {
                        showSettingsPanel()
                    } else {
                        v.performClick()
                    }
                    true
                }
                else -> true
            }
        }
        settingsPullTab.setOnClickListener { showSettingsPanel() }
    }

    private fun showSettingsPanel() {
        btnSettings.animate().translationX(0f).setDuration(220).start()
        axisIndicator.animate().alpha(0f).setDuration(200).start()
        settingsPullTab.visibility = View.INVISIBLE
    }

    private fun adjustCommandsColumns() {
        val density = resources.displayMetrics.density
        // 40dp button + 4dp total margin (2dp each side)
        val itemH = ((40 + 4) * density + 0.5f).toInt()
        val loc = IntArray(2)
        btnCommands.getLocationInWindow(loc)
        val available = window.decorView.height - loc[1] - btnCommands.height - (8 * density).toInt()
        val itemCount = commandsDrawer.childCount
        commandsDrawer.columnCount = if (itemH * itemCount <= available) 1 else 2
    }

    private fun hideSettingsPanel() {
        val offscreen = (btnSettings.width + 32 * resources.displayMetrics.density)
        btnSettings.animate().translationX(offscreen).setDuration(220).start()
        axisIndicator.animate().alpha(1f).setDuration(200).start()
        settingsPullTab.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------------------
    private fun showStatus(msg: String) {
        runOnUiThread {
            val tv = findViewById<TextView?>(R.id.statusText) ?: return@runOnUiThread
            tv.text       = msg
            tv.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
        }
    }
}
