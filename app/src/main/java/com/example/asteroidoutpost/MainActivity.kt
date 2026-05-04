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
    private var quadDomeHandle:     Long = 0L  // unit X-Z quad, soft cyan-blue (M7 shield-dome plasma billboard)
    private var asteroidMesh3D:        Long = 0L  // Asteroid_1.glb tinted grey  (NORMAL / FAST)
    private var asteroidMeshHeavy:     Long = 0L  // Asteroid_1.glb tinted dark red (HEAVY)
    private var asteroidMeshExplosive: Long = 0L  // Asteroid_1.glb tinted orange   (EXPLOSIVE)
    private var asteroidMeshEnergy:    Long = 0L  // Asteroid_1.glb tinted cyan     (ENERGY)
    private var quadFlashHandle:    Long = 0L  // unit X-Z quad, bright yellow (destruction flash)
    // Background nebulae — soft-edge disks (E1.4) loaded via `loadMeshRaw`.
    // Each disk is a triangle fan: centre vertex alpha=1, rim vertices alpha=0,
    // so when rendered through the translucent pipeline it fades smoothly to
    // the background instead of showing hard quad edges. One handle per tint.
    private val nebulaHandles: LongArray = LongArray(5)

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
    )
    private val flashes: MutableList<Flash> = mutableListOf()
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
        const val WAVE_BREAK_SEC:    Float = 2.0f
        const val FLASH_LIFE_SEC:    Float = 0.25f
        const val FLASH_HALF:        Float = 0.20f
        // M7.1 VFX — turret muzzle, projectile trail, AoE ring.
        const val MUZZLE_FLASH_LIFE: Float = 0.08f
        const val MUZZLE_FLASH_HALF: Float = 0.13f
        const val TRAIL_INTERVAL_SEC:Float = 0.04f
        const val TRAIL_LIFE_SEC:    Float = 0.12f
        const val TRAIL_HALF:        Float = 0.05f
        const val AOE_RING_PARTICLES:Int   = 10
        const val AOE_RING_PARTICLE_HALF: Float = 0.08f
        const val AOE_RING_LIFE_SEC: Float = 0.30f
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

        // Single sci-fi HUD panel anchored at top: left = mission + wave, right = score + HP.
        val root = engineView.parent as FrameLayout
        hudPanel = buildHudPanel()
        val sideMargin = com.example.asteroidoutpost.game.UiTheme.dp(this, 12f)
        val topMargin  = com.example.asteroidoutpost.game.UiTheme.dp(this, 16f)
        // Reserve room on the right for the ✕ button (added below) so the HUD
        // panel's right edge stops before the abort button instead of getting
        // clipped behind it.
        val hudRightInset = com.example.asteroidoutpost.game.UiTheme.dp(this, 56f)
        val hudParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP,
        ).apply { setMargins(sideMargin, topMargin, hudRightInset, 0) }
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

        // Abort-mission button — small "✕" pill in the top-right corner. Lets
        // the player bail out of a mission without playing it through. Shares
        // visibility with the HUD (only visible during PLAYING).
        abortMissionBtn = buildAbortMissionButton()
        val abortSize = com.example.asteroidoutpost.game.UiTheme.dp(this, 36f)
        val abortParams = FrameLayout.LayoutParams(
            abortSize, abortSize,
            android.view.Gravity.TOP or android.view.Gravity.END,
        ).apply {
            setMargins(0, topMargin, sideMargin, 0)
        }
        root.addView(abortMissionBtn, abortParams)

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
            // renders this; left for the asteroidMesh3D fallback path only.
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
            // Soft cyan-blue tint kept moderate so additive stacks read as glow
            // rather than blowing out to white. Used by the shield dome.
            quadDomeHandle  = engineView.engine.loadMeshColored(quadBytes, 0.18f, 0.45f, 0.85f)
            if (quadMeshHandle  == 0L) quadMeshHandle  = engineView.engine.loadMesh(quadBytes)
            if (quadGreyHandle  == 0L) quadGreyHandle  = engineView.engine.loadMesh(quadBytes)
            if (quadBlueHandle  == 0L) quadBlueHandle  = engineView.engine.loadMesh(quadBytes)
            if (quadFlashHandle == 0L) quadFlashHandle = engineView.engine.loadMesh(quadBytes)
            if (quadDomeHandle  == 0L) quadDomeHandle  = quadBlueHandle  // graceful fallback
            if (quadMeshHandle == 0L || quadGreyHandle == 0L || quadBlueHandle == 0L || quadFlashHandle == 0L)
                showStatus("Quad load failed (handle=0)")
        } catch (e: Exception) {
            showStatus("Quad load failed: ${e.message}")
        }
        try {
            val asteroidBytes = assets.open("models/Asteroid_1.glb").readBytes()
            asteroidMesh3D        = engineView.engine.loadMeshColored(asteroidBytes, 0.55f, 0.55f, 0.60f)
            asteroidMeshHeavy     = engineView.engine.loadMeshColored(asteroidBytes, 0.70f, 0.20f, 0.20f)
            asteroidMeshExplosive = engineView.engine.loadMeshColored(asteroidBytes, 0.95f, 0.55f, 0.20f)
            asteroidMeshEnergy    = engineView.engine.loadMeshColored(asteroidBytes, 0.30f, 0.85f, 0.95f)
            if (asteroidMesh3D == 0L) asteroidMesh3D = engineView.engine.loadMesh(asteroidBytes)
            // Fallback any failed tint to the grey mesh so the scene still renders.
            if (asteroidMeshHeavy     == 0L) asteroidMeshHeavy     = asteroidMesh3D
            if (asteroidMeshExplosive == 0L) asteroidMeshExplosive = asteroidMesh3D
            if (asteroidMeshEnergy    == 0L) asteroidMeshEnergy    = asteroidMesh3D
            if (asteroidMesh3D == 0L) showStatus("Asteroid_1.glb load failed")
        } catch (e: Exception) {
            showStatus("Asteroid_1.glb load failed: ${e.message}")
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
     * Generate the background nebula meshes once and submit them as
     * translucent scene objects. Set once at engine setup; never touched after,
     * so menu / mission select / game / win-lose all share the same backdrop.
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
        engineView.translucentObjects = placements.mapIndexed { i, p ->
            SceneObject(
                id         = 2000 + i,
                meshHandle = nebulaHandles[p.tint],
                x          = p.x, y = 1f, z = p.z,
                scale      = p.scale,
            )
        }
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
        // Shield VFX (M7) lives on the additive plasma pipeline below — see
        // buildShieldDomeBillboards(). The platform itself stays grey at all
        // times now; the dome glow over the base communicates the shield state.
        // Background nebulae are translucent meshes set up once in
        // setupBackgroundNebulae() and pushed to engineView.translucentObjects.

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
            // Asteroid_1.glb has a roughly unit bbox (±1 in all axes). Scale by
            // the per-asteroid `half` (mission baseline × type multiplier) so
            // FAST asteroids look small and HEAVY ones look chunky. Mesh tint
            // varies by type for readability — placeholder until M7 polish.
            val mesh = when (a.type) {
                AsteroidType.HEAVY     -> asteroidMeshHeavy
                AsteroidType.EXPLOSIVE -> asteroidMeshExplosive
                AsteroidType.ENERGY    -> asteroidMeshEnergy
                AsteroidType.NORMAL,
                AsteroidType.FAST      -> asteroidMesh3D
            }
            SceneObject(
                id         = 200 + i,
                meshHandle = mesh,
                x          = a.xPos, y = 0f, z = a.zPos,
                rotationZ  = a.rotation,
                scale      = a.half,
            )
        } + flashes.mapIndexed { i, f ->
            // Flash grows from 0.6× to 1.4× of its peak half-size during its life.
            val t = 1f - (f.life / f.maxLife)
            val s = f.halfMax * (0.6f + t * 0.8f)
            SceneObject(
                id         = 400 + i,
                meshHandle = quadFlashHandle,
                x = f.x, y = 0f, z = f.z,
                scaleX = s, scaleY = 1f, scaleZ = s,
            )
        } + bullets.mapIndexed { i, b ->
            // Rotate the quad so its long (Z) axis aligns with velocity (vx, vz).
            // Rotation around Y maps (0,0,1) → (sin θ, 0, cos θ), so θ = atan2(vx, vz).
            SceneObject(
                id         = 300 + i,
                meshHandle = quadMeshHandle,
                x          = b.x, y = 0f, z = b.z,
                rotationY  = kotlin.math.atan2(b.vx, b.vz),
                scaleX     = b.halfW,
                scaleY     = 1f,
                scaleZ     = b.halfH,
            )
        }

        engineView.plasmaBillboards = buildShieldDomeBillboards()
    }

    /**
     * Shield dome VFX (M7). Stack of additive plasma billboards forming a
     * tapered dome silhouette over the base:
     *  - three wide "ridge" billboards along the platform (left / centre /
     *    right) form a horizontal energy band at the dome's base
     *  - one narrower billboard above caps the dome's apex
     * Additive blending makes the overlap zones glow brighter, so the visible
     * shape reads as a wide energy bubble that tapers toward the top.
     *
     * Geometry is anchored ABOVE the central turret tip (z ≈ -0.34) — keeping
     * the bottom edge of every layer at z ≥ -0.30 so the platform and turrets
     * underneath stay their own colour instead of getting tinted blue.
     *
     * Animation:
     *  - Subtle pulse modulates scale by ±4% over time so the dome breathes.
     *  - Last 0.6 sec of duration: linear fade-out so the shield visibly
     *    "collapses" before the COOLING transition.
     *
     * Returns empty when the shield isn't active (or the dome mesh failed to
     * load and there's no fallback) — the engine simply skips the plasma pass.
     */
    private fun buildShieldDomeBillboards(): List<BillboardDraw> {
        if (shieldState != ShieldState.ACTIVE) return emptyList()
        if (quadDomeHandle == 0L) return emptyList()

        val elapsed = DraftCombat.SHIELD_DURATION_SEC - shieldTimer
        val pulse   = 1f + 0.04f * kotlin.math.sin(elapsed * 5.0f)
        val fade    = (shieldTimer / 0.6f).coerceIn(0f, 1f)
        val mul     = pulse * fade
        return listOf(
            // Base ridge — three wide billboards along the platform width.
            // Each is small enough that its bottom edge stays above the turrets.
            BillboardDraw(quadDomeHandle, -1.5f, 0f, 0.7f, scale = 1.0f * mul),
            BillboardDraw(quadDomeHandle,  0.0f, 0f, 0.9f, scale = 1.2f * mul),
            BillboardDraw(quadDomeHandle,  1.5f, 0f, 0.7f, scale = 1.0f * mul),
            // Apex — narrower billboard sitting on top of the ridge.
            BillboardDraw(quadDomeHandle,  0.0f, 0f, 1.6f, scale = 0.7f * mul),
        )
    }

    /**
     * M7.1 — replace the placeholder "one big yellow square" AoE flash with a
     * readable ring: a small bright flash at the impact centre plus a ring of
     * tiny flashes positioned on the AoE perimeter. The ring silhouette
     * communicates the affected radius without needing a soft-edge shader.
     */
    private fun spawnAoeRing(cx: Float, cz: Float, radius: Float) {
        // Bright centre — keeps the impact moment readable.
        flashes.add(Flash(
            x = cx, z = cz,
            life = DraftCombat.FLASH_LIFE_SEC,
            maxLife = DraftCombat.FLASH_LIFE_SEC,
            halfMax = DraftCombat.FLASH_HALF * 1.2f,
        ))
        // Perimeter ring — N small flashes spaced evenly around the radius.
        val n = DraftCombat.AOE_RING_PARTICLES
        for (i in 0 until n) {
            val ang = i * 2f * Math.PI.toFloat() / n
            flashes.add(Flash(
                x = cx + radius * kotlin.math.cos(ang),
                z = cz + radius * kotlin.math.sin(ang),
                life = DraftCombat.AOE_RING_LIFE_SEC,
                maxLife = DraftCombat.AOE_RING_LIFE_SEC,
                halfMax = DraftCombat.AOE_RING_PARTICLE_HALF,
            ))
        }
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
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        com.example.asteroidoutpost.game.UiHelpers.stylePanel(panel)

        // Left column: mission name (caption) + wave (heading).
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        hudMissionText = com.example.asteroidoutpost.game.UiHelpers
            .buildCaption(ctx, "")
        hudWaveText = com.example.asteroidoutpost.game.UiHelpers
            .buildHeading(ctx, "")
        left.addView(hudMissionText)
        left.addView(hudWaveText)

        // Right column: score + HP, right-aligned.
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        hudScoreText = com.example.asteroidoutpost.game.UiHelpers
            .buildHeading(ctx, "Score: 0").apply { gravity = Gravity.END }
        hudHpText = com.example.asteroidoutpost.game.UiHelpers
            .buildBody(ctx, "HP: 100", com.example.asteroidoutpost.game.UiTheme.COL_TEXT)
            .apply { gravity = Gravity.END }
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
            // and reset cooldown to fireIntervalSec; the first shot of a press
            // is still instant if the player hasn't fired recently (cooldown
            // already at 0). Active buff multiplies the weapon's per-shot damage.
            val weapon = currentWeapon
            val weaponDamage = (effectiveMainWeaponDamage * weapon.damageMultiplier * activeBuffDamageMul).toInt()
            if (centralFireCooldown > 0f) {
                centralFireCooldown -= dt
                if (centralFireCooldown < 0f) centralFireCooldown = 0f
            }
            if (isTouching && centralFireCooldown <= 0f) {
                centralFireCooldown = weapon.fireIntervalSec
                val ang = centralTurretAngle
                val sinA = kotlin.math.sin(ang)
                val cosA = kotlin.math.cos(ang)
                val muzzleR = DraftCombat.CENTRAL_TURRET_HALF_H * 2f
                val muzzleX = pivotX + sinA * muzzleR
                val muzzleZ = pivotZ + cosA * muzzleR
                bullets.add(Bullet(
                    x  = muzzleX,
                    z  = muzzleZ,
                    vx = sinA * weapon.projectileSpeed,
                    vz = cosA * weapon.projectileSpeed,
                    damage = weaponDamage,
                    halfW = weapon.projectileHalfW,
                    halfH = weapon.projectileHalfH,
                    aoeRadius = weapon.aoeRadius,
                    aoeDamage = (weaponDamage * weapon.aoeDamageMultiplier).toInt(),
                ))
                // Muzzle flash at the barrel tip — short bright pop.
                flashes.add(Flash(
                    x = muzzleX, z = muzzleZ,
                    life = DraftCombat.MUZZLE_FLASH_LIFE,
                    maxLife = DraftCombat.MUZZLE_FLASH_LIFE,
                    halfMax = DraftCombat.MUZZLE_FLASH_HALF,
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
                    ))
                    // Side-turret muzzle flash — same look as the central
                    // turret's, slightly smaller to keep them visually secondary.
                    flashes.add(Flash(
                        x = muzzleX, z = muzzleZ,
                        life = DraftCombat.MUZZLE_FLASH_LIFE,
                        maxLife = DraftCombat.MUZZLE_FLASH_LIFE,
                        halfMax = DraftCombat.MUZZLE_FLASH_HALF * 0.7f,
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
                    flashes.add(Flash(
                        x = b.x, z = b.z,
                        life = DraftCombat.TRAIL_LIFE_SEC,
                        maxLife = DraftCombat.TRAIL_LIFE_SEC,
                        halfMax = DraftCombat.TRAIL_HALF,
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
                        spawnAoeRing(hitX, hitZ, b.aoeRadius)
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
                            spawnAoeRing(a.xPos, a.zPos, DraftCombat.EXPLOSIVE_AOE_RADIUS)
                        }
                        AsteroidType.ENERGY -> {
                            triggeredBuff = true
                            flashes.add(Flash(
                                x = a.xPos, z = a.zPos,
                                life = DraftCombat.FLASH_LIFE_SEC,
                                maxLife = DraftCombat.FLASH_LIFE_SEC,
                                halfMax = DraftCombat.FLASH_HALF * 1.5f,
                            ))
                        }
                        AsteroidType.NORMAL,
                        AsteroidType.FAST,
                        AsteroidType.HEAVY -> {
                            flashes.add(Flash(
                                a.xPos, a.zPos,
                                DraftCombat.FLASH_LIFE_SEC,
                                DraftCombat.FLASH_LIFE_SEC,
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
                        flashes.add(Flash(
                            x = a.xPos,
                            z = DraftCombat.PLATFORM_TOP_Z + a.half,
                            life = DraftCombat.FLASH_LIFE_SEC,
                            maxLife = DraftCombat.FLASH_LIFE_SEC,
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
