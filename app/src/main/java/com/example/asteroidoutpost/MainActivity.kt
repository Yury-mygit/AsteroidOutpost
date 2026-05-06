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
    // Static translucent scene (background nebulae) — captured once in
    // setupBackgroundNebulae so buildScene can compose it with per-frame
    // dynamic translucent objects (currently just the shield dome).
    private var nebulaeTranslucent: List<SceneObject> = emptyList()

    // Aim state. Player drags on the screen to aim the central turret; while the
    // finger is down, the turret fires along the aim direction at fire-rate.
    @Volatile private var aimTargetX:    Float = 0f
    @Volatile private var aimTargetZ:    Float = DraftCombat.SCREEN_TOP_Z
    @Volatile private var isTouching:    Boolean = false
    // Smoothed aim angle of the central turret (radians, atan2(dx,dz)). Tracks
    // the touch position; used to orient the turret model and bullet spawn.
    private var centralTurretAngle: Float = 0f

    // Bullets fly along their (vx, vz) vector from a turret muzzle (central or
    // side); asteroids take damage on hit. Bullets and asteroids are owned and
    // mutated only by the tick thread; buildScene (also called from tick) reads them.
    private data class Bullet(
        var x: Float, var z: Float,
        val vx: Float, val vz: Float,
        val damage: Int,
        val halfW: Float = DraftCombat.BULLET_HALF_W,
        val halfH: Float = DraftCombat.BULLET_HALF_H,
        // Render mesh — Bullet.glb for normal/side, Bullet_Heavy.glb for the
        // heavy cannon. 0 falls back to the red quad on the engine side.
        val meshHandle: Long = 0L,
        // AoE on impact. aoeRadius == 0 means single-target (default).
        // aoeDamage applied to every other asteroid within the radius.
        val aoeRadius: Float = 0f,
        val aoeDamage: Int = 0,
        // Bullet-trail VFX (M7.1). Counts down to next trail-particle emission;
        // the tick loop drops a tiny fading flash at the current bullet position
        // each time this hits zero, producing a comet-style streak behind the
        // projectile. Persisted per-bullet so trails stay even-spaced.
        var trailTimer: Float = 0f,
    )
    private data class Flash(
        val x: Float, val z: Float,
        var life: Float, val maxLife: Float,
        // Peak half-size at flash midpoint. Default = small per-asteroid death
        // flash; AoE impacts spawn larger flashes sized to the explosion radius.
        val halfMax: Float = DraftCombat.FLASH_HALF,
        // E5.1 — per-flash tint applied inside the plasma fragment branch.
        // Default white preserves the E4 warm-flame look; non-white recolours
        // by event (cyan ENERGY pickup, blue shield absorb, orange-red AoE).
        val tintR: Float = 1f, val tintG: Float = 1f, val tintB: Float = 1f, val tintA: Float = 1f,
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
    )
    private val fireballs: MutableList<Fireball> = mutableListOf()
    private data class Asteroid(
        val xPos: Float,
        var zPos: Float,
        var hp: Int,
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
    )
    private val bullets:    MutableList<Bullet>   = mutableListOf()
    private val asteroids:  MutableList<Asteroid> = mutableListOf(
        Asteroid(-1.5f, 8.5f, 100),
        Asteroid( 1.0f, 8.0f, 100),
    )
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

    // Shield ability — three-state machine. While ACTIVE, asteroids that touch
    // the platform get absorbed without dealing damage. After it expires the
    // ability enters COOLING for SHIELD_COOLDOWN_SEC, then returns to READY.
    private enum class ShieldState { READY, ACTIVE, COOLING }
    @Volatile private var shieldState:    ShieldState = ShieldState.READY
    @Volatile private var shieldTimer:    Float       = 0f  // seconds left of ACTIVE
    @Volatile private var shieldCooldown: Float       = 0f  // seconds left of COOLING
    private var shieldUiSecLast: Int = -1                   // throttle UI text refreshes

    // Buff system (single slot). When `activeBuffTimer > 0`, the central
    // turret's per-shot damage is multiplied by `activeBuffDamageMul`. Set by
    // ENERGY-asteroid kills; ticked down each frame.
    @Volatile private var activeBuffTimer:       Float = 0f
    @Volatile private var activeBuffDamageMul:   Float = 1f
    private var buffUiSecLast: Int = -1
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
    private lateinit var waveAnnounceText:  TextView
    private lateinit var shieldButton:      TextView
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
        const val FIRE_INTERVAL_SEC: Float = 0.15f   // ~6.7 shots/sec
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
        // Aim-alignment threshold for the central turret. While `isTouching`
        // and the cooldown is ready, the turret only fires once it's rotated
        // essentially onto the target angle (within ~1.15°). The exponential
        // rotation has a long asymptotic tail, so a loose threshold (e.g. 5°)
        // visibly fires off-aim on big swings — especially with the heavy
        // cannon's 1-sec cooldown, where one off-target shot is very noticeable.
        const val AIM_ALIGN_THRESHOLD_RAD: Float = 0.02f
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
        // M7.1 VFX — turret muzzle, projectile trail, AoE ring.
        const val MUZZLE_FLASH_LIFE: Float = 0.08f
        const val MUZZLE_FLASH_HALF: Float = 0.13f
        const val TRAIL_INTERVAL_SEC:Float = 0.04f
        const val TRAIL_LIFE_SEC:    Float = 0.12f
        const val TRAIL_HALF:        Float = 0.05f
        // Perimeter-ring particle constants removed — explosions are now a
        // single AoE-sized billboard (see spawnExplosion).
        // E5.1 — per-event flash tints (RGBA), multiplied into the plasma
        // fragment heat-ramp. RGB channels recolour the warm-flame baseline;
        // alpha is an overall brightness scalar (>1 = boost). White (default)
        // keeps the E4 look. Tunable; non-const because Kotlin disallows
        // const FloatArray in companion objects.
        val FLASH_TINT_MUZZLE     = floatArrayOf(1.00f, 0.95f, 0.70f, 1.00f)  // warm white-yellow
        val FLASH_TINT_TRAIL      = floatArrayOf(1.00f, 0.80f, 0.45f, 0.85f)  // warm trail, slightly dimmer
        val FLASH_TINT_ENERGY     = floatArrayOf(0.45f, 0.85f, 1.00f, 1.10f)  // cyan electric, slightly brighter
        val FLASH_TINT_DEATH      = floatArrayOf(1.00f, 0.85f, 0.40f, 1.00f)  // warm yellow burst
        val FLASH_TINT_SHIELD     = floatArrayOf(0.35f, 0.75f, 1.00f, 1.00f)  // blue shield deflection
        // E7.1 polish — fireball colour curve. Lerp start → end over life
        // gives a "hot fresh blast → cooling embers" read instead of a
        // single static orange. Brightness is handled separately via the
        // pc.plasmaColor.a scalar in buildScene.
        val FIREBALL_TINT_START   = floatArrayOf(1.00f, 0.65f, 0.20f)  // saturated forge-orange
        val FIREBALL_TINT_END     = floatArrayOf(0.90f, 0.18f, 0.05f)  // deep dying-ember red
        // Reload bar — strip on the lower part of the platform (the upper part
        // is overlapped by the ЩИТ button overlay, which composites on top of
        // the engine surface, so a bar placed there gets hidden). Anchored
        // horizontally under the central turret. Fill width = readiness.
        const val RELOAD_BAR_HALF_W:        Float = 0.40f
        const val RELOAD_BAR_Z:             Float = -0.30f
        const val RELOAD_BAR_HALF_THICK:    Float = 0.04f
        // Shield ability — base protection. Single charge with cooldown.
        const val SHIELD_DURATION_SEC: Float = 3.0f
        const val SHIELD_COOLDOWN_SEC: Float = 15.0f
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

        // DRAFT — camera locked. Touch on engine surface aims the central turret.
        // Hold-to-fire: while finger is down, the tick fires bullets whenever the
        // central-turret cooldown hits 0. The cooldown counts down independently
        // of the touch state, so tapping rapidly cannot bypass the weapon's
        // fireIntervalSec (early builds primed the timer per-ACTION_DOWN, which
        // made tap-spam fire once per tap).
        engineView.onCameraOrbited = { _, _ -> }
        engineView.onCameraRolled  = { _ -> }
        engineView.onCameraReset   = { }
        engineView.onTap = { _, _ -> }
        engineView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    val w = engineView.width.toFloat()
                    val h = engineView.height.toFloat()
                    if (w > 0f && h > 0f) {
                        // Map screen X → world X ∈ [-2.47, +2.47]; screen Y →
                        // world Z ∈ [SCREEN_BOTTOM_Z, SCREEN_TOP_Z] (Y=0 is top of screen).
                        aimTargetX = (event.x / w - 0.5f) * (DraftCombat.SCREEN_HALF_W * 2f)
                        val zSpan = DraftCombat.SCREEN_TOP_Z - DraftCombat.SCREEN_BOTTOM_Z
                        aimTargetZ = DraftCombat.SCREEN_TOP_Z - (event.y / h) * zSpan
                    }
                    isTouching = true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    isTouching = false
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

        // Shield ability button — diegetic, sits on the platform area at the
        // bottom centre. Three visual states (READY / ACTIVE / COOLING) are
        // applied via refreshShieldButton() based on the shield state machine.
        shieldButton = buildShieldButton()
        // Sized to fit on the platform — 36dp tall is just under the platform's
        // ~44dp on-screen height so the button sits diegetically on the base.
        val shieldParams = FrameLayout.LayoutParams(
            com.example.asteroidoutpost.game.UiTheme.dp(this, 96f),
            com.example.asteroidoutpost.game.UiTheme.dp(this, 36f),
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL,
        ).apply {
            bottomMargin = com.example.asteroidoutpost.game.UiTheme.dp(this@MainActivity, 12f)
        }
        root.addView(shieldButton, shieldParams)
        refreshShieldButton()

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
        shieldButton.visibility = View.GONE   // hidden outside PLAYING
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
            if (quadMeshHandle  == 0L) quadMeshHandle  = engineView.engine.loadMesh(quadBytes)
            if (quadGreyHandle  == 0L) quadGreyHandle  = engineView.engine.loadMesh(quadBytes)
            if (quadBlueHandle  == 0L) quadBlueHandle  = engineView.engine.loadMesh(quadBytes)
            if (quadFlashHandle == 0L) quadFlashHandle = engineView.engine.loadMesh(quadBytes)
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
        domeMembraneHandle = buildDomeMembraneMesh(
            r = 0.45f, g = 0.75f, b = 1.00f,
            peakAlpha   = 0.55f,
            centerAlpha = 0.22f,
            midR        = 0.80f,
            outerR      = 1.00f,
        )
        // E7.1 — load the fireball UV-sphere once. Drawn through the additive
        // pipeline with ADDITIVE_FIRE material in spawnAoeRing.
        fireballMeshHandle = buildFireballSphereMesh()
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
            SceneObject(
                id         = 200 + i,
                meshHandle = if (a.meshHandle != 0L) a.meshHandle else asteroidMeshGrey1,
                x          = a.xPos, y = 0f, z = a.zPos,
                rotationZ  = a.rotation,
                scale      = a.half,
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
            val mesh = if (b.meshHandle != 0L) b.meshHandle else quadMeshHandle
            SceneObject(
                id         = 300 + i,
                meshHandle = mesh,
                x          = b.x, y = 0f, z = b.z,
                rotationY  = kotlin.math.atan2(b.vx, b.vz) + DraftCombat.BULLET_MODEL_YAW_OFFSET,
                scale      = b.halfH * DraftCombat.BULLET_MODEL_SCALE_MUL,
            )
        }

        // Flash VFX: muzzle flash, bullet trails, asteroid hit, AoE rings, ENERGY-buff
        // pickup. Routed through the additive plasma pipeline (E2.1) so they read as
        // soft circular glows that brighten what's behind them — instead of square
        // yellow placeholders sitting on the dark background. E5.1 — per-flash tint
        // forwarded to the plasma fragment branch via BillboardDraw → drawPlasmaBillboard.
        val flashBillboards = flashes.map { f ->
            val t = 1f - (f.life / f.maxLife)
            val s = f.halfMax * (0.6f + t * 0.8f)
            BillboardDraw(quadFlashHandle, f.x, 0f, f.z, s, f.tintR, f.tintG, f.tintB, f.tintA)
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
                SceneObject(
                    id               = 800 + i,
                    meshHandle       = fireballMeshHandle,
                    x                = fb.x, y = 0f, z = fb.z,
                    scale            = s,
                    tintR            = tintR, tintG = tintG, tintB = tintB, tintA = brightness,
                    additiveMaterial = EngineJni.ADDITIVE_FIRE,
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
        if (shieldState != ShieldState.ACTIVE) return emptyList()
        if (domeMembraneHandle == 0L) return emptyList()

        val elapsed = DraftCombat.SHIELD_DURATION_SEC - shieldTimer
        val pulse   = 1f + 0.04f * kotlin.math.sin(elapsed * 5.0f)
        val fade    = (shieldTimer / 0.6f).coerceIn(0f, 1f)
        val mul     = pulse * fade
        val baseZ   = DraftCombat.PLATFORM_TOP_Z
        return listOf(
            SceneObject(
                id         = 700,
                meshHandle = domeMembraneHandle,
                x          = 0f, y = -0.05f, z = baseZ,
                scaleX     = 2.4f * mul,
                scaleY     = 1f,
                scaleZ     = 2.0f * mul,
                // E3.3 — fragment shader overlays a hex grid on the dome.
                material   = EngineJni.MATERIAL_HEX,
            ),
        )
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
    private fun spawnExplosion(cx: Float, cz: Float, radius: Float) {
        fireballs.add(Fireball(
            x = cx, z = cz,
            life = DraftCombat.FIREBALL_LIFE_SEC,
            maxLife = DraftCombat.FIREBALL_LIFE_SEC,
            baseRadius = radius,
        ))
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

    private fun startGame() {
        startMission(Missions.ALL[0])
    }

    private fun showMissionSelect() {
        gameState = GameState.MENU
        hudPanel.visibility     = View.GONE
        shieldButton.visibility = View.GONE
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
        // Reset aim to straight up. Without a touch the turret stays vertical.
        aimTargetX         = DraftCombat.CENTRAL_TURRET_X
        aimTargetZ         = DraftCombat.SCREEN_TOP_Z
        centralTurretAngle = 0f
        isTouching         = false
        bullets.clear()
        asteroids.clear()
        centralFireCooldown = 0f
        spawnTimer    = 0f
        turretFireT[0] = 0f
        turretFireT[1] = 0f
        hudScoreText.text   = "Score: 0"
        hudHpText.text      = "HP: $effectiveBaseHp"
        hudMissionText.text = mission.name
        hudWaveText.text    = "Волна 1/${mission.waves.size}"
        // Reset shield to READY so the new run starts with the ability available.
        shieldState     = ShieldState.READY
        shieldTimer     = 0f
        shieldCooldown  = 0f
        shieldUiSecLast = -1
        refreshShieldButton()
        // Reset any active buff from the previous run.
        activeBuffTimer     = 0f
        activeBuffDamageMul = 1f
        buffUiSecLast       = -1
        refreshBuffIndicator()
        hudPanel.visibility     = View.VISIBLE
        shieldButton.visibility = View.VISIBLE
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
        shieldButton.visibility = View.GONE
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
            setOnClickListener { onShieldTapped() }
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

    /** Apply current shield state to the button (background, label, enabled). */
    private fun refreshShieldButton() {
        val theme = com.example.asteroidoutpost.game.UiTheme
        when (shieldState) {
            ShieldState.READY -> {
                shieldButton.text       = "ЩИТ"
                shieldButton.background = shieldButtonDrawable(theme.COL_ACCENT_BLUE)
                shieldButton.setTextColor(theme.COL_TEXT)
                shieldButton.isEnabled  = true
            }
            ShieldState.ACTIVE -> {
                val sec = kotlin.math.ceil(shieldTimer.toDouble()).toInt().coerceAtLeast(1)
                shieldButton.text       = "ЩИТ ${sec}с"
                shieldButton.background = shieldButtonDrawable(theme.COL_ACCENT_GREEN)
                shieldButton.setTextColor(theme.COL_TEXT)
                shieldButton.isEnabled  = false
            }
            ShieldState.COOLING -> {
                val sec = kotlin.math.ceil(shieldCooldown.toDouble()).toInt().coerceAtLeast(1)
                shieldButton.text       = "Готов ${sec}с"
                shieldButton.background = shieldButtonDrawable(theme.COL_PANEL_BG_HI)
                shieldButton.setTextColor(theme.COL_TEXT_DISABLED)
                shieldButton.isEnabled  = false
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

    /** Player tapped the shield button. Activate only if currently READY. */
    private fun onShieldTapped() {
        if (gameState != GameState.PLAYING) return
        if (shieldState != ShieldState.READY) return
        shieldState = ShieldState.ACTIVE
        shieldTimer = DraftCombat.SHIELD_DURATION_SEC
        shieldUiSecLast = -1
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
        right.addView(hudScoreText)
        right.addView(hudHpText)

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

            // Shield state machine: count down ACTIVE / COOLING timers and post
            // a UI refresh at most once per integer-second boundary to avoid
            // text-thrash. Transitions (ACTIVE → COOLING → READY) always refresh.
            when (shieldState) {
                ShieldState.ACTIVE -> {
                    shieldTimer -= dt
                    if (shieldTimer <= 0f) {
                        shieldTimer = 0f
                        shieldState = ShieldState.COOLING
                        shieldCooldown = DraftCombat.SHIELD_COOLDOWN_SEC
                        shieldUiSecLast = -1
                        runOnUiThread { refreshShieldButton() }
                    } else {
                        val sec = kotlin.math.ceil(shieldTimer.toDouble()).toInt()
                        if (sec != shieldUiSecLast) {
                            shieldUiSecLast = sec
                            runOnUiThread { refreshShieldButton() }
                        }
                    }
                }
                ShieldState.COOLING -> {
                    shieldCooldown -= dt
                    if (shieldCooldown <= 0f) {
                        shieldCooldown = 0f
                        shieldState = ShieldState.READY
                        shieldUiSecLast = -1
                        runOnUiThread { refreshShieldButton() }
                    } else {
                        val sec = kotlin.math.ceil(shieldCooldown.toDouble()).toInt()
                        if (sec != shieldUiSecLast) {
                            shieldUiSecLast = sec
                            runOnUiThread { refreshShieldButton() }
                        }
                    }
                }
                ShieldState.READY -> { /* idle */ }
            }

            // Aim the central turret at the touch position. dz is clamped to be
            // non-negative so the player can't shoot down through the platform —
            // a touch below the turret resolves to a horizontal aim instead.
            val pivotX = DraftCombat.CENTRAL_TURRET_X
            val pivotZ = DraftCombat.CENTRAL_TURRET_BASE_Z
            val dx = aimTargetX - pivotX
            val dz = kotlin.math.max(0f, aimTargetZ - pivotZ)
            val targetAngle = if (dx == 0f && dz == 0f) 0f else kotlin.math.atan2(dx, dz)
            // Smooth tracking — exponential approach, fast enough to feel
            // responsive but soft enough to avoid jitter on a moving finger.
            centralTurretAngle += (targetAngle - centralTurretAngle) * 16f * dt

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

            // Central-turret cooldown — counts down EVERY tick, regardless of
            // touch state, so a player can't fire faster than the weapon allows
            // by spamming taps. While `isTouching`, fire whenever cooldown <= 0
            // AND the barrel has rotated close enough to the touch direction
            // (AIM_ALIGN_THRESHOLD_RAD). Without that align gate, holding the
            // first frame after a touch fires a shot in the OLD aim direction
            // before the exponential rotation has caught up — which felt like
            // the turret ignoring the player's aim. Active buff multiplies the
            // weapon's per-shot damage.
            val weapon = currentWeapon
            val weaponDamage = (effectiveMainWeaponDamage * weapon.damageMultiplier * activeBuffDamageMul).toInt()
            if (centralFireCooldown > 0f) {
                centralFireCooldown -= dt
                if (centralFireCooldown < 0f) centralFireCooldown = 0f
            }
            val aimAligned = kotlin.math.abs(targetAngle - centralTurretAngle) <
                             DraftCombat.AIM_ALIGN_THRESHOLD_RAD
            if (isTouching && centralFireCooldown <= 0f && aimAligned) {
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
                // Muzzle flash at the barrel tip — short bright pop.
                val mt = DraftCombat.FLASH_TINT_MUZZLE
                flashes.add(Flash(
                    x = muzzleX, z = muzzleZ,
                    life = DraftCombat.MUZZLE_FLASH_LIFE,
                    maxLife = DraftCombat.MUZZLE_FLASH_LIFE,
                    halfMax = DraftCombat.MUZZLE_FLASH_HALF,
                    tintR = mt[0], tintG = mt[1], tintB = mt[2], tintA = mt[3],
                ))
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
                    bullets.add(Bullet(
                        x = muzzleX, z = muzzleZ,
                        vx = nx * DraftCombat.BULLET_SPEED,
                        vz = nz * DraftCombat.BULLET_SPEED,
                        damage = effectiveTurretDamage,
                        meshHandle = bulletMeshHandle,
                    ))
                    // Side-turret muzzle flash — same look as the central
                    // turret's, slightly smaller to keep them visually secondary.
                    val st = DraftCombat.FLASH_TINT_MUZZLE
                    flashes.add(Flash(
                        x = muzzleX, z = muzzleZ,
                        life = DraftCombat.MUZZLE_FLASH_LIFE,
                        maxLife = DraftCombat.MUZZLE_FLASH_LIFE,
                        halfMax = DraftCombat.MUZZLE_FLASH_HALF * 0.7f,
                        tintR = st[0], tintG = st[1], tintB = st[2], tintA = st[3],
                    ))
                }
            }
            // Move bullets along their velocity; cull off-screen, apply damage on hit.
            val bulletIter = bullets.iterator()
            while (bulletIter.hasNext()) {
                val b = bulletIter.next()
                b.x += b.vx * dt
                b.z += b.vz * dt
                // Drop a tiny fading flash at the current position at fixed
                // intervals so each bullet visibly trails a comet streak.
                b.trailTimer -= dt
                if (b.trailTimer <= 0f) {
                    b.trailTimer += DraftCombat.TRAIL_INTERVAL_SEC
                    val tt = DraftCombat.FLASH_TINT_TRAIL
                    flashes.add(Flash(
                        x = b.x, z = b.z,
                        life = DraftCombat.TRAIL_LIFE_SEC,
                        maxLife = DraftCombat.TRAIL_LIFE_SEC,
                        halfMax = DraftCombat.TRAIL_HALF,
                        tintR = tt[0], tintG = tt[1], tintB = tt[2], tintA = tt[3],
                    ))
                }
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
                    // AoE: apply splash damage to other live asteroids within
                    // aoeRadius (centred on the hit asteroid). The direct target
                    // already took full damage above and is excluded.
                    if (b.aoeRadius > 0f && b.aoeDamage > 0) {
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
                        // M7.1 — ring of small flashes traces the AoE radius
                        // so the player can read the explosion's reach.
                        spawnExplosion(hitX, hitZ, b.aoeRadius)
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
            val fireballIter = fireballs.iterator()
            while (fireballIter.hasNext()) {
                val fb = fireballIter.next()
                fb.life -= dt
                if (fb.life <= 0f) fireballIter.remove()
            }

            // Move asteroids down at their own speed (mission baseline × type
            // multiplier, captured at spawn); spin around their own axis.
            for (a in asteroids) {
                a.zPos     -= a.speed * dt
                a.rotation += a.rotationSpeed * dt
            }
            // Asteroid bottom edge touches platform top → damage and remove.
            var platformDamage = 0
            val asteroidIter = asteroids.iterator()
            while (asteroidIter.hasNext()) {
                val a = asteroidIter.next()
                if (a.zPos - a.half <= DraftCombat.PLATFORM_TOP_Z) {
                    if (shieldState == ShieldState.ACTIVE) {
                        // Shield absorbs: asteroid is consumed without damaging
                        // the base. Spawn a small flash at the impact point so
                        // the player sees the hit being deflected.
                        val sh = DraftCombat.FLASH_TINT_SHIELD
                        flashes.add(Flash(
                            x = a.xPos,
                            z = DraftCombat.PLATFORM_TOP_Z + a.half,
                            life = DraftCombat.FLASH_LIFE_SEC,
                            maxLife = DraftCombat.FLASH_LIFE_SEC,
                            tintR = sh[0], tintG = sh[1], tintB = sh[2], tintA = sh[3],
                        ))
                    } else {
                        // Per-type platform damage (HEAVY hits twice as hard).
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
                            asteroids.add(
                                Asteroid(
                                    xPos = rx,
                                    zPos = DraftCombat.SCREEN_TOP_Z - half,
                                    hp   = (mission.asteroidHp * type.hpMul).toInt().coerceAtLeast(1),
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
