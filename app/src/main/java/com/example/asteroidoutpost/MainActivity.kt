package com.example.asteroidoutpost

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
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
import kotlin.math.pow
import com.example.asteroidoutpost.game.Ability
import com.example.asteroidoutpost.game.AbilityCatalog
import com.example.asteroidoutpost.game.AbilitySlot
import com.example.asteroidoutpost.game.SceneAssembler
import com.example.asteroidoutpost.game.buildHpBars
import com.example.asteroidoutpost.game.buildShieldDome
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
import com.example.asteroidoutpost.game.combat.Asteroid
import com.example.asteroidoutpost.game.combat.DraftCombat
import com.example.asteroidoutpost.game.combat.Fireball
import com.example.asteroidoutpost.game.combat.Flash
import com.example.asteroidoutpost.game.combat.Particle
import com.example.asteroidoutpost.game.combat.RocketPhase
import com.example.asteroidoutpost.game.combat.Vec3
import com.example.asteroidoutpost.game.combat.Beam
import com.example.asteroidoutpost.game.combat.HeavyShellBehavior
import com.example.asteroidoutpost.game.combat.HomingRocketBehavior
import com.example.asteroidoutpost.game.combat.PlainBulletBehavior
import com.example.asteroidoutpost.game.combat.Projectile
import com.example.asteroidoutpost.game.combat.ProjectileBehavior
import com.example.asteroidoutpost.game.combat.VfxSpawner
import com.example.asteroidoutpost.game.combat.WeaponEffect
import com.example.asteroidoutpost.game.combat.WeaponEffectContext
import com.example.asteroidoutpost.game.combat.tickParticles
import com.example.asteroidoutpost.game.combat.bestHpTargetInArc
import com.example.asteroidoutpost.game.combat.centralWeaponHalfArc
import com.example.asteroidoutpost.game.combat.isWithinArc
import com.example.asteroidoutpost.game.combat.nearestAsteroidInArc
import com.example.asteroidoutpost.game.combat.pickAsteroidAt
import com.example.asteroidoutpost.game.combat.pickAsteroidType
import com.example.asteroidoutpost.game.content.buildFireballSphereMesh
import com.example.asteroidoutpost.game.content.buildLaserInstallationMesh
import com.example.asteroidoutpost.game.content.buildMuzzleConeMesh
import com.example.asteroidoutpost.game.content.buildParticleQuadMesh
import com.example.asteroidoutpost.game.content.buildRocketMesh
import com.example.asteroidoutpost.game.content.buildRocketSiloMesh
import com.example.asteroidoutpost.game.content.buildShieldArchMesh
import com.example.asteroidoutpost.game.content.buildSoftDiskMesh
import com.example.asteroidoutpost.game.content.buildTurretBarrelMesh
import com.example.asteroidoutpost.game.content.buildTurretBaseMesh
import com.example.asteroidoutpost.game.content.generateDebrisTexture
import com.example.asteroidoutpost.game.content.generateSmokeTexture
import com.example.asteroidoutpost.game.ui.HudView

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

    // ---------------------------------------------------------------------------
    // Scene meshes — owned by Kotlin, submitted to engine each frame
    // ---------------------------------------------------------------------------
    private var quadMeshHandle:     Long = 0L  // unit X-Z quad, red tint (central turret, bullets)
    private var quadGreyHandle:     Long = 0L  // unit X-Z quad, grey tint (platform)
    private var quadBlueHandle:     Long = 0L  // unit X-Z quad, blue tint (side turrets)
    // Procedural turret meshes (M10 — static base + rotating housing/barrel).
    // Built once in `buildTurretMeshes` from per-turret-type parameters; the
    // base meshes are oriented sitting on the platform, the barrel meshes have
    // their origin at the pivot and extend along +Z so a SceneObject's
    // rotationY pivots the housing in-place.
    private var centralBaseMeshHandle:    Long = 0L
    private var centralBarrelMeshHandle:  Long = 0L
    private var sideBaseMeshHandle:       Long = 0L
    private var sideBarrelMeshHandle:     Long = 0L
    private var laserInstallMeshHandle:   Long = 0L
    private var rocketSiloMeshHandle:     Long = 0L
    private var rocketMeshHandle:         Long = 0L
    // Side turret aim angles — independent of the firing routine, smoothed
    // every tick toward the nearest asteroid so the rotating barrel mesh
    // tracks a target visually rather than snapping at fire-time only.
    private val sideTurretAngles = floatArrayOf(0f, 0f)
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

    private val flashes: MutableList<Flash> = mutableListOf()

    private val fireballs: MutableList<Fireball> = mutableListOf()

    // Two pools, one per pipeline. Capped at engine kMaxParticles (4096)
    // each; runaway emitters are limited at the engine boundary too.
    private val sparkParticles:  MutableList<Particle> = mutableListOf()
    private val smokeParticles:  MutableList<Particle> = mutableListOf()  // alpha-textured smoke
    private val debrisParticles: MutableList<Particle> = mutableListOf()  // alpha-textured chunks
    // Active combat effects — projectiles (Projectile) and beams (Beam).
    // Owned/mutated only by the tick thread; `tick()` per effect each frame,
    // consumed effects are removed; buildScene queries by type to compose
    // SceneObjects (projectiles) and BeamDraws (beams).
    private val effects: MutableList<WeaponEffect> = mutableListOf()
    private val asteroids:  MutableList<Asteroid> = mutableListOf(
        Asteroid(id = 1L, xPos = -1.5f, zPos = 8.5f, hp = 100),
        Asteroid(id = 2L, xPos =  1.0f, zPos = 8.0f, hp = 100),
    )
    private var nextAsteroidId: Long = 3L
    private fun newAsteroidId(): Long = nextAsteroidId++
    // VFX side of combat — appends to the lists above. Initialised at the
    // end of `setupBackgroundNebulae` once mesh handles (muzzleCone, quadFlash)
    // are loaded; safe to use from the tick from the moment PLAYING starts.
    private lateinit var vfx: VfxSpawner
    // Per-tick context handed to every WeaponEffect.tick() call. Exposes the
    // bits of world state effects need (asteroids for collision/aiming, vfx
    // for impact/jet/trail flashes) without coupling the effect classes to
    // MainActivity. Initialised alongside `vfx`.
    private val weaponCtx: WeaponEffectContext = object : WeaponEffectContext {
        override val asteroids: List<Asteroid> get() = this@MainActivity.asteroids
        override val vfx: VfxSpawner            get() = this@MainActivity.vfx
    }
    // Game → engine adapter. Reads game state (asteroids, projectiles, beams,
    // flashes, fireballs, particles) and produces a per-frame `SceneFrame` the
    // engine consumes. Constructed at the end of `setupBackgroundNebulae`
    // (after all mesh handles are loaded); used from `buildScene` each frame.
    private lateinit var sceneAssembler: SceneAssembler
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

    private val abilitySlots: List<AbilitySlot> = listOf(
        AbilitySlot(AbilityCatalog.ROCKET_STRIKE),
        AbilitySlot(AbilityCatalog.LASER_STRIKE),
    )
    // DRAFT — turret state. Two static blue squares on the platform; each fires
    // at the nearest asteroid. Kept simple (per-turret fire timer only).
    private val turretXs       = floatArrayOf(-1.8f, 1.8f)
    // Weapon-installation owners — tied to specific projectile types via
    // their fire() methods. RocketSilo can ONLY produce HomingRocketBehavior
    // projectiles (see class definition); the structural binding prevents
    // accidental "rocket silo fires bullets" regressions.
    private val rocketSilo: RocketSilo = RocketSilo(
        xPos = DraftCombat.ROCKET_SILO_X,
        zPos = DraftCombat.PLATFORM_TOP_Z + DraftCombat.ROCKET_SILO_MUZZLE_OFFSET,
    )
    private val turretFireT    = floatArrayOf(0f, 0f)

    @Volatile private var platformHP: Int = 100
    // Persistent player state. Loaded from SharedPreferences in onCreate.
    private lateinit var progressRepo: ProgressRepository
    // In-flight stats for the current mission attempt. Reset on each game start.
    private val missionRun: MissionRun = MissionRun()
    private lateinit var abilityBar:        LinearLayout
    // HUD action-bar widgets (shield button, ability buttons, buff indicator,
    // abort ✕). Owns its own Drawables and refresh logic; we expose the views
    // as `hud.shieldButton` / `hud.abortButton` etc. for layout mounting.
    private lateinit var hud: HudView
    private val hudCallbacks: HudView.Callbacks = object : HudView.Callbacks {
        override fun onShieldDown() {
            if (gameState != GameState.PLAYING) return
            shieldRecharging = true
            hud.refreshShield(shieldHp, DraftCombat.SHIELD_MAX_HP)
        }
        override fun onShieldUp() {
            shieldRecharging = false
            hud.refreshShield(shieldHp, DraftCombat.SHIELD_MAX_HP)
        }
        override fun onAbilityTap(slotIndex: Int) {
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
        override fun onAbortMission() {
            if (gameState != GameState.PLAYING) return
            showMissionSelect()
        }
    }
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
    // ---------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        applySettings()
    }

    // ---------------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------------
    private var missionThread:  HandlerThread? = null
    private var missionHandler: Handler?       = null

    // Background music. SFX (engine drone / shoot) belonged to the g3 fleet
    // sim and have been retired with that subsystem.
    private var bgMusic: MediaPlayer? = null

    companion object {
        private const val TICK_MS = 20L
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
        // All other g3-inherited buttons (commands drawer, build menu, mic, fleet
        // missions) have no Outpost-side action — they're hidden by the visibility
        // sweep below and unwired so the inflated layout doesn't ship dead handlers.

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
            if (gameState == GameState.PLAYING &&
                event.actionMasked == MotionEvent.ACTION_DOWN) {
                val w = engineView.width.toFloat()
                val h = engineView.height.toFloat()
                if (w > 0f && h > 0f) {
                    val worldX = (event.x / w - 0.5f) *
                                 (DraftCombat.SCREEN_HALF_W * 2f)
                    val zSpan  = DraftCombat.SCREEN_TOP_Z -
                                 DraftCombat.SCREEN_BOTTOM_Z
                    val worldZ = DraftCombat.SCREEN_TOP_Z -
                                 (event.y / h) * zSpan
                    val picked = pickAsteroidAt(asteroids, worldX, worldZ)
                    if (picked != null) {
                        // Re-tapping the same locked asteroid releases the
                        // priority lock — auto-pick takes over within the
                        // central's arc. Tap on a different asteroid sets
                        // a new lock.
                        centralTargetId = if (centralTargetId == picked.id) null
                                          else picked.id
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
        hud = HudView(this, abilitySlots, hudCallbacks)
        val sideMargin = com.example.asteroidoutpost.game.UiTheme.dp(this, 12f)
        val topMargin  = com.example.asteroidoutpost.game.UiTheme.dp(this, 16f)
        val hudParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP,
        ).apply { setMargins(sideMargin, topMargin, sideMargin, 0) }
        root.addView(hud.buildHudPanel(), hudParams)

        // Ability bar — diegetic, sits on the platform area at the bottom
        // centre. Holds the shield button + 2 active-ability buttons (M8.4
        // rocket strike, laser strike) in one horizontal row so they share
        // a single visibility lifecycle and stay grouped on the platform.
        // Each button has its own state machine (shield: READY/ACTIVE/
        // COOLING; ability: READY/COOLING/INSUFFICIENT-ENERGY/ARMED).
        val theme = com.example.asteroidoutpost.game.UiTheme
        // Icon-sized bar — buttons are now icon-only most of the time
        // (cooldown text on ability buttons swaps in via refresh, replacing
        // the icon rather than stacking under it). Height = icon (22dp) +
        // ~5dp slack to breathe.
        val barH        = theme.dp(this, 32f)
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
        abilityBar.addView(hud.buildShieldButton(), btnLp)
        // Build one button per slot, sharing the same dimensions as the
        // shield button so the row reads as a uniform diegetic control bar.
        for (btn in hud.buildAbilityButtons()) {
            abilityBar.addView(btn, btnLpGap)
        }
        val barParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = theme.dp(this@MainActivity, 12f) }
        root.addView(abilityBar, barParams)
        hud.refreshShield(shieldHp, DraftCombat.SHIELD_MAX_HP)
        hud.refreshAllAbilities(energy)

        // Abort ✕ button is built and added inside buildHudPanel() so it
        // shares the HUD's row and visibility — no separate FrameLayout
        // child here.

        // Buff indicator — small caption that appears under the HUD while a
        // buff (currently only ENERGY-asteroid main-weapon ×2) is active.
        // Hidden otherwise.
        val buffTopMargin = com.example.asteroidoutpost.game.UiTheme.dp(this, 92f)
        val buffParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(0, buffTopMargin, 0, 0) }
        root.addView(hud.buildBuffIndicator(), buffParams)

        // Big centered "Волна N" / "Финальная волна" announce text — built
        // and animated by HudView; we just mount it.
        val waveAnnounceParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER,
        )
        root.addView(hud.buildWaveAnnounce(), waveAnnounceParams)

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
        OverlayFactory.setBody(menuOverlay, "Всего металла: ${progressRepo.current.metal}")
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
        hud.setHudVisible(false)
        abilityBar.visibility = View.GONE     // hidden outside PLAYING
        hud.abortButton.visibility = View.GONE
        selectionOverlay.visibility = View.GONE   // selection feature disabled
        engineView.onSurfaceReady = { loadAssets() }
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
            // E14 — beam pipeline shaders (own pipeline layout). Optional
            // from the engine's POV — without them drawLaserBeam is a no-op.
            engineView.engine.setShader("beam.vert", assets.open("shaders/beam.vert.spv").readBytes())
            engineView.engine.setShader("beam.frag", assets.open("shaders/beam.frag.spv").readBytes())
        } catch (e: Exception) {
            showStatus("Shader load failed: ${e.message}")
        }
    }

    /**
     * Outpost asset-loading entry point. Wired to `engineView.onSurfaceReady`
     * so it runs once after the Vulkan surface is ready. Loads quad / asteroid /
     * bullet meshes via `loadStation()`, then renders the first scene.
     */
    private fun loadAssets() {
        loadStation()
        buildScene()
    }

    private fun loadStation() {
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
        // ORDER MATTERS — `setupBackgroundNebulae()` constructs the
        // `SceneAssembler` at its tail, capturing all mesh handles by value.
        // The turret/silo/laser handles must be set before that, otherwise
        // SceneAssembler captures 0L and the platform-mounted geometry never
        // renders. (Each call is independent of the other; we just need
        // turret handles ready when SceneAssembler is built.)
        buildTurretMeshes()
        setupBackgroundNebulae()
    }

    /**
     * Build all four turret meshes (central base/barrel + side base/barrel)
     * once during asset load. Tints come from the turret-type accent colours
     * already used elsewhere — central red, side blue.
     */
    private fun buildTurretMeshes() {
        val engine = engineView.engine
        centralBaseMeshHandle = buildTurretBaseMesh(
            engine,
            halfW   = DraftCombat.CENTRAL_BASE_HALF_W,
            height  = DraftCombat.CENTRAL_BASE_HEIGHT,
            accentR = 0.85f, accentG = 0.30f, accentB = 0.30f,
        )
        centralBarrelMeshHandle = buildTurretBarrelMesh(
            engine,
            housingHalfW = DraftCombat.CENTRAL_HOUSING_HALF_W,
            housingLength = DraftCombat.CENTRAL_HOUSING_LENGTH,
            barrelHalfW  = DraftCombat.CENTRAL_BARREL_HALF_W,
            barrelLength = DraftCombat.CENTRAL_BARREL_LENGTH,
            muzzleHalfW  = DraftCombat.CENTRAL_MUZZLE_HALF_W,
            muzzleLength = DraftCombat.CENTRAL_MUZZLE_LENGTH,
            accentR = 0.90f, accentG = 0.32f, accentB = 0.32f,
        )
        sideBaseMeshHandle = buildTurretBaseMesh(
            engine,
            halfW   = DraftCombat.SIDE_BASE_HALF_W,
            height  = DraftCombat.SIDE_BASE_HEIGHT,
            accentR = 0.30f, accentG = 0.55f, accentB = 1.00f,
        )
        sideBarrelMeshHandle = buildTurretBarrelMesh(
            engine,
            housingHalfW = DraftCombat.SIDE_HOUSING_HALF_W,
            housingLength = DraftCombat.SIDE_HOUSING_LENGTH,
            barrelHalfW  = DraftCombat.SIDE_BARREL_HALF_W,
            barrelLength = DraftCombat.SIDE_BARREL_LENGTH,
            muzzleHalfW  = DraftCombat.SIDE_MUZZLE_HALF_W,
            muzzleLength = DraftCombat.SIDE_MUZZLE_LENGTH,
            accentR = 0.35f, accentG = 0.60f, accentB = 1.00f,
        )
        laserInstallMeshHandle = buildLaserInstallationMesh(engine)
        rocketSiloMeshHandle   = buildRocketSiloMesh(engine)
        rocketMeshHandle       = buildRocketMesh(engine)
    }

    /**
     * Generate the background nebula meshes once and submit them as
     * translucent scene objects. Set once at engine setup; never touched after,
     * so menu / mission select / game / win-lose all share the same backdrop.
     * Also builds the shield-dome half-disk meshes (E2.2) — they're loaded
     * here because they share `loadMeshRaw` and the translucent pipeline.
     */
    private fun setupBackgroundNebulae() {
        val engine = engineView.engine
        val tints = arrayOf(
            floatArrayOf(0.42f, 0.18f, 0.55f),  // 0 — deep purple
            floatArrayOf(0.18f, 0.50f, 0.55f),  // 1 — cyan
            floatArrayOf(0.55f, 0.22f, 0.30f),  // 2 — dim crimson
            floatArrayOf(0.25f, 0.32f, 0.60f),  // 3 — twilight blue
            floatArrayOf(0.50f, 0.42f, 0.22f),  // 4 — warm dust
        )
        for (i in tints.indices) {
            nebulaHandles[i] = buildSoftDiskMesh(engine, tints[i][0], tints[i][1], tints[i][2])
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
        domeMembraneHandle = buildShieldArchMesh(engine)
        // E7.1 — load the fireball UV-sphere once. Drawn through the additive
        // pipeline with ADDITIVE_FIRE material in spawnAoeRing.
        fireballMeshHandle = buildFireballSphereMesh(engine)
        // E11 — load the muzzle cone fan once; spawnMuzzleBlast spawns 3
        // plasma billboards using this mesh with per-flash rotation.
        muzzleConeMeshHandle = buildMuzzleConeMesh(engine)
        // E9 — particle infrastructure. Single shared unit-quad mesh, plus
        // two procedural textures (smoke puff for AoE/death, asteroid-chunk
        // debris for asteroid death). Sparks (additive) ignore textures.
        particleQuadHandle  = buildParticleQuadMesh(engine)
        smokeTextureHandle  = generateSmokeTexture(engine)
        debrisTextureHandle = generateDebrisTexture(engine)
        // Initial assignment so menu / mission-select scenes (which don't run
        // buildScene) still show the nebulae backdrop.
        engineView.translucentObjects = nebulaeTranslucent
        // All mesh handles ready — wire up the VFX spawner. Used by tick /
        // projectile behaviours / RocketSilo for muzzle blasts, fireballs,
        // hit flashes, asteroid death, shield recharge sparks, etc.
        vfx = VfxSpawner(
            flashes          = flashes,
            fireballs        = fireballs,
            sparkParticles   = sparkParticles,
            smokeParticles   = smokeParticles,
            debrisParticles  = debrisParticles,
            muzzleConeMeshHandle = muzzleConeMeshHandle,
            quadFlashHandle      = quadFlashHandle,
        )
        // game → engine adapter. Reads the same lists VfxSpawner appends to,
        // composes one `SceneFrame` per call to `assemble(...)` from the tick.
        sceneAssembler = SceneAssembler(
            asteroids          = asteroids,
            effects            = effects,
            flashes            = flashes,
            fireballs          = fireballs,
            sparkParticles     = sparkParticles,
            smokeParticles     = smokeParticles,
            debrisParticles    = debrisParticles,
            turretXs           = turretXs,
            sideTurretAngles   = sideTurretAngles,
            nebulaeTranslucent = nebulaeTranslucent,
            quadGreyHandle           = quadGreyHandle,
            quadFlashHandle          = quadFlashHandle,
            quadMeshHandle           = quadMeshHandle,
            quadHpBgHandle           = quadHpBgHandle,
            quadHpFgHandle           = quadHpFgHandle,
            centralBaseMeshHandle    = centralBaseMeshHandle,
            centralBarrelMeshHandle  = centralBarrelMeshHandle,
            sideBaseMeshHandle       = sideBaseMeshHandle,
            sideBarrelMeshHandle     = sideBarrelMeshHandle,
            laserInstallMeshHandle   = laserInstallMeshHandle,
            rocketSiloMeshHandle     = rocketSiloMeshHandle,
            asteroidMeshGrey1        = asteroidMeshGrey1,
            domeMembraneHandle       = domeMembraneHandle,
            fireballMeshHandle       = fireballMeshHandle,
            particleQuadHandle       = particleQuadHandle,
            smokeTextureHandle       = smokeTextureHandle,
            debrisTextureHandle      = debrisTextureHandle,
        )
    }

    // ---------------------------------------------------------------------------
    // Scene building
    /**
     * Per-frame scene push. Computes the small set of per-frame scalars
     * (reload progress) and delegates the heavy lifting to `sceneAssembler`.
     * The assembler reads game state (asteroids, effects, flashes, fireballs,
     * particles) via the references baked into its constructor and returns a
     * `SceneFrame`; we copy the lists onto `engineView`.
     */
    private fun buildScene() {
        val reloadInterval = currentWeapon.fireIntervalSec
        val reloadProgress = if (reloadInterval > 0f)
            (1f - centralFireCooldown / reloadInterval).coerceIn(0f, 1f)
        else 1f
        val frame = sceneAssembler.assemble(
            reloadProgress     = reloadProgress,
            centralTurretAngle = centralTurretAngle,
            shieldHp           = shieldHp,
        )
        engineView.scene              = frame.scene
        engineView.plasmaBillboards   = frame.plasmaBillboards
        engineView.translucentObjects = frame.translucentObjects
        engineView.additiveObjects    = frame.additiveObjects
        engineView.beams              = frame.beams
        engineView.particleBatches    = frame.particleBatches
    }

    // M8.5 — rocket strike helpers.

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
        val sz = DraftCombat.PLATFORM_TOP_Z + DraftCombat.ROCKET_SILO_MUZZLE_OFFSET
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
     * Rocket silo weapon — type-bound to the homing-rocket Projectile.
     * fire() always constructs Projectiles with HomingRocketBehavior; there
     * is no path through this class to fire a non-rocket. Future weapon
     * classes (LaserDome, EmpEmitter, ...) will follow the same pattern —
     * each one's fire() locks in its own projectile/effect type.
     *
     * Lives as an inner class so it captures MainActivity for `effects`,
     * helpers (spawnMuzzleBlast), and current upgrade state
     * (effectiveMainWeaponDamage / activeBuffDamageMul read at fire time,
     * not at construction).
     */
    /**
     * Spring-launched rocket silo. fire() doesn't immediately spawn rockets;
     * it queues them. Each tick, if no rocket is currently in the tube
     * (i.e. nothing in ASCENDING phase), the next queued target is
     * launched: the rocket emerges from the silo opening rising straight
     * up. Once the rocket has climbed by ROCKET_ASCENT_HEIGHT it transitions
     * to FLYING (engine ignites, homing kicks in) — and only then does the
     * next rocket pop from the queue. Sequential, never two rockets
     * sharing the tube.
     */
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
            // Spring-launch puff at the silo opening — points straight up
            // since the rocket leaves the tube vertically.
            vfx.spawnMuzzleBlast(xPos, zPos, 0f, 1f,
                             DraftCombat.ROCKET_HALF_W * 1.4f,
                             DraftCombat.FLASH_TINT_MUZZLE)
        }
    }

    private fun applySettings() {
        val prefs = getSharedPreferences(SettingsActivity.PREF_FILE, MODE_PRIVATE)
        val musicVolume = prefs.getFloat(SettingsActivity.KEY_MUSIC_VOLUME, 0.25f)
        bgMusic?.setVolume(musicVolume, musicVolume)
        // mic button forced off — the g3 voice-command path is not used by Outpost.
        btnMic.visibility = View.GONE
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
        // (handled by the touch listener).
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

    private fun startGame() {
        startMission(Missions.ALL[0])
    }

    private fun showMissionSelect() {
        gameState = GameState.MENU
        hud.setHudVisible(false)
        abilityBar.visibility   = View.GONE
        hud.abortButton.visibility = View.GONE
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
            this, progressRepo.current,
            onPurchase = { type, cost ->
                progressRepo.update { UpgradeCatalog.applyPurchase(it, type, cost) }
                rebuildUpgrades()   // refresh in place
            },
            onBack = {
                upgradesOverlay?.let { root.removeView(it) }
                upgradesOverlay = null
                upgradesReturnTo?.invoke()
                upgradesReturnTo = null
                // If returning to menu, refresh its metal counter.
                if (menuOverlay.visibility == View.VISIBLE) {
                    OverlayFactory.setBody(menuOverlay, "Всего металла: ${progressRepo.current.metal}")
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
        hud.refreshAllAbilities(energy)
        hud.refreshScore(0)
        hud.refreshHp(effectiveBaseHp)
        hud.refreshEnergy(energy, DraftCombat.ENERGY_MAX)
        hud.refreshMissionLabel(mission.name)
        hud.refreshWaveLabel("Волна 1/${mission.waves.size}")
        // Reset shield to READY so the new run starts with the ability available.
        shieldHp         = DraftCombat.SHIELD_MAX_HP
        shieldRecharging = false
        shieldUiPctLast  = -1
        hud.refreshShield(shieldHp, DraftCombat.SHIELD_MAX_HP)
        // Reset any active buff from the previous run.
        activeBuffTimer     = 0f
        activeBuffDamageMul = 1f
        buffUiSecLast       = -1
        hud.refreshBuff(activeBuffTimer, activeBuffDamageMul)
        hud.setHudVisible(true)
        abilityBar.visibility   = View.VISIBLE
        hud.abortButton.visibility = View.VISIBLE
        menuOverlay.visibility          = View.GONE
        missionSelectOverlay.visibility = View.GONE
        removeWinLoseOverlays()
        removeWeaponSelectOverlay()
        gameState = GameState.PLAYING
        ensureTicking()
        scheduleDraftTick()
        hud.announceWave(1, mission.waves.size)
    }

    private fun goToMenu() {
        gameState = GameState.MENU
        currentMission = null
        effects.clear()
        asteroids.clear()
        hud.setHudVisible(false)
        abilityBar.visibility   = View.GONE
        hud.abortButton.visibility = View.GONE
        missionSelectOverlay.visibility = View.GONE
        removeWinLoseOverlays()
        removeWeaponSelectOverlay()
        OverlayFactory.setBody(menuOverlay, "Всего металла: ${progressRepo.current.metal}")
        menuOverlay.visibility = View.VISIBLE
        buildScene()
    }

    private fun showWin() {
        gameState = GameState.WON
        // Win bonus: +20 metal, awarded once per victory.
        missionRun.winBonus = 20
        missionRun.metalEarned += missionRun.winBonus
        progressRepo.update { it.copy(metal = it.metal + missionRun.winBonus) }
        runOnUiThread { presentWinOverlay() }
    }

    private fun showLose() {
        gameState = GameState.LOST
        runOnUiThread { presentLoseOverlay() }
    }

    // ---- Ability activation -----------------------------------------------

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
                    val sz = DraftCombat.PLATFORM_TOP_Z +
                             DraftCombat.LASER_DOME_TOP_OFFSET
                    effects.add(Beam(
                        source = { Vec3(sx, 0f, sz) },
                        aimSelector  = { centralTurretTarget() },
                        durationSec  = DraftCombat.LASER_BEAM_DURATION_SEC,
                        dps          = DraftCombat.LASER_BEAM_DPS,
                        width        = DraftCombat.LASER_BEAM_WIDTH,
                        color        = DraftCombat.LASER_TINT,
                        canEngage    = { a ->
                            isWithinArc(a, sx, sz, DraftCombat.ARC_LASER_HALF_RAD)
                        },
                    ))
                    true
                }
            }
        }
        if (!fired) return false
        energy         = (energy - a.cost).coerceAtLeast(0f)
        energyUiLast   = -1                    // force HUD energy refresh next tick
        slot.currentCd = a.cooldownSec
        slot.cdUiLast  = -1
        runOnUiThread {
            hud.refreshEnergy(energy, DraftCombat.ENERGY_MAX)
            hud.refreshAllAbilities(energy)
        }
        return true
    }

    private fun presentWinOverlay() {
        val mission = currentMission ?: return
        val root = engineView.parent as FrameLayout
        winOverlay?.let { root.removeView(it) }
        val stats = listOf(
            "Уничтожено астероидов" to missionRun.asteroidsDestroyed.toString(),
            "Очки"                  to missionRun.score.toString(),
            "Получено металла"      to "${missionRun.metalEarned} (+${missionRun.winBonus} бонус)",
            "Всего металла"         to progressRepo.current.metal.toString(),
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
            "Всего металла"         to progressRepo.current.metal.toString(),
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
                    runOnUiThread { hud.refreshShield(shieldHp, DraftCombat.SHIELD_MAX_HP) }
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
                    runOnUiThread {
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
                        runOnUiThread { hud.refreshAbility(i, energy) }
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
                    runOnUiThread { hud.refreshBuff(activeBuffTimer, activeBuffDamageMul) }
                } else {
                    val sec = kotlin.math.ceil(activeBuffTimer.toDouble()).toInt()
                    if (sec != buffUiSecLast) {
                        buffUiSecLast = sec
                        runOnUiThread { hud.refreshBuff(activeBuffTimer, activeBuffDamageMul) }
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
                effects.add(Projectile(
                    x  = muzzleX,
                    z  = muzzleZ,
                    vx = sinA * weapon.projectileSpeed,
                    vz = cosA * weapon.projectileSpeed,
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
                    effects.add(Projectile(
                        x  = muzzleX, z = muzzleZ,
                        vx = nx * DraftCombat.SIDE_BULLET_SPEED,
                        vz = nz * DraftCombat.SIDE_BULLET_SPEED,
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
            // Tick all active weapon effects (projectiles + beams + future
            // shockwaves / EMP / etc.). Each effect's tick() owns its own
            // movement, collision, damage and lifetime; returns true to be
            // removed. The umbrella means one loop covers every type — no
            // per-effect-kind branching here.
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
                            vfx.spawnExplosion(a.xPos, a.zPos, DraftCombat.EXPLOSIVE_AOE_RADIUS)
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
                            vfx.spawnAsteroidDeathFX(a.xPos, a.zPos, tint)
                        }
                    }
                }
                if (triggeredBuff) {
                    activeBuffTimer     = DraftCombat.ENERGY_BUFF_DURATION
                    activeBuffDamageMul = DraftCombat.ENERGY_BUFF_DAMAGE_MUL
                    buffUiSecLast = -1
                    runOnUiThread { hud.refreshBuff(activeBuffTimer, activeBuffDamageMul) }
                }
                missionRun.score += killed * 10
                missionRun.asteroidsDestroyed += killed
                missionRun.metalEarned += killed   // +1 metal per asteroid
                progressRepo.update { it.copy(metal = it.metal + killed) }
                runOnUiThread { hud.refreshScore(missionRun.score) }
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
            // Asteroid hit detection — shield first (asteroid breaks ON the
            // arch, never reaches platform if shield is up and the asteroid
            // is under the dome's X coverage), then platform (asteroid
            // landed past where the shield could intercept).
            var platformDamage = 0
            val asteroidIter = asteroids.iterator()
            while (asteroidIter.hasNext()) {
                val a = asteroidIter.next()
                // 1. Shield-arch contact (only if shield up + within X coverage).
                //    Compute the arch Z at this asteroid's X via the same
                //    superellipse used to author the mesh: |x/a|^n + |z/b|^n = 1.
                if (shieldHp > 0f) {
                    val xRatio = kotlin.math.abs(a.xPos) / DraftCombat.SHIELD_ARCH_HALF_W
                    if (xRatio < 1f) {
                        val n = DraftCombat.SHIELD_ARCH_SHARPNESS
                        val archBaseZ = DraftCombat.PLATFORM_TOP_Z +
                                        DraftCombat.SHIELD_ARCH_LIFT_FRAC *
                                            DraftCombat.SHIELD_ARCH_HALF_H
                        val archZ = archBaseZ + DraftCombat.SHIELD_ARCH_HALF_H *
                            (1f - xRatio.pow(n)).pow(1f / n)
                        if (a.zPos - a.half <= archZ) {
                            // Shield contact — asteroid breaks on the arch.
                            // Full absorb when shield can soak it; partial
                            // absorb spills overflow into the platform.
                            // While the player is recharging, incoming damage
                            // is reduced by the SHIELD_RECHARGE_DAMAGE_MUL
                            // factor — explicit benefit for actively topping
                            // up the shield mid-impact.
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
                                x = a.xPos, z = archZ,
                                life = DraftCombat.FLASH_LIFE_SEC,
                                maxLife = DraftCombat.FLASH_LIFE_SEC,
                                tintR = sh[0], tintG = sh[1], tintB = sh[2], tintA = sh[3],
                            ))
                            asteroidIter.remove()
                            continue
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
                runOnUiThread {
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
                        runOnUiThread {
                            hud.refreshWaveLabel(
                                "Волна ${missionRun.currentWaveDisplay}/${mission.waves.size}",
                            )
                        }
                        hud.announceWave(missionRun.currentWaveDisplay, mission.waves.size)
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

    // ---------------------------------------------------------------------------
    override fun onResume()  { super.onResume();  engineView.onResume(); bgMusic?.start() }
    override fun onPause()   { super.onPause();   engineView.onPause();  bgMusic?.pause() }
    override fun onDestroy() {
        super.onDestroy()
        missionThread?.quitSafely(); missionThread = null; missionHandler = null
        bgMusic?.release(); bgMusic = null
        engineView.onDestroyView()
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
