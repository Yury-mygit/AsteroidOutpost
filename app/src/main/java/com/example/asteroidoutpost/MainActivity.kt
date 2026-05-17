package com.example.asteroidoutpost

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.example.asteroidoutpost.game.GameState
import com.example.asteroidoutpost.game.MissionConfig
import com.example.asteroidoutpost.game.MissionRunner
import com.example.asteroidoutpost.game.Missions
import com.example.asteroidoutpost.game.overlay.addMenuButton
import com.example.asteroidoutpost.game.overlay.buildCampaign
import com.example.asteroidoutpost.game.overlay.buildEndOfMission
import com.example.asteroidoutpost.game.overlay.buildMenu
import com.example.asteroidoutpost.game.overlay.buildMissionDetail
import com.example.asteroidoutpost.game.overlay.buildMissionHub
import com.example.asteroidoutpost.game.overlay.buildMissionList
import com.example.asteroidoutpost.game.overlay.buildRandomMissions
import com.example.asteroidoutpost.game.overlay.buildSettings
import com.example.asteroidoutpost.game.overlay.buildUpgrades
import com.example.asteroidoutpost.game.overlay.buildWeaponSelect
import com.example.asteroidoutpost.game.overlay.setMenuBody
import com.example.asteroidoutpost.game.ProgressRepository
import com.example.asteroidoutpost.game.ASTEROID_PICK_ID_BASE
import com.example.asteroidoutpost.game.SceneAssembler
import com.example.asteroidoutpost.game.UpgradeCatalog
import com.example.asteroidoutpost.game.Weapon
import com.example.asteroidoutpost.game.WeaponCatalog
import com.example.asteroidoutpost.game.combat.DraftCombat
import com.example.asteroidoutpost.game.combat.VfxSpawner
import com.example.asteroidoutpost.game.content.buildFireballSphereMesh
import com.example.asteroidoutpost.game.content.buildLaserInstallationMesh
import com.example.asteroidoutpost.game.content.buildMuzzleConeMesh
import com.example.asteroidoutpost.game.content.buildParticleQuadMesh
import com.example.asteroidoutpost.game.content.buildRocketMesh
import com.example.asteroidoutpost.game.content.buildRocketSiloMesh
import com.example.asteroidoutpost.game.content.buildShieldHemisphereMesh
import com.example.asteroidoutpost.game.content.buildShipHullMesh
import com.example.asteroidoutpost.game.content.buildSoftDiskMesh
import com.example.asteroidoutpost.game.content.buildTurretBarrelMesh
import com.example.asteroidoutpost.game.content.buildTurretCannonMesh
import com.example.asteroidoutpost.game.content.buildTurretTowerMesh
import com.example.asteroidoutpost.game.content.buildTurretBaseMesh
import com.example.asteroidoutpost.game.content.generateDebrisTexture
import com.example.asteroidoutpost.game.content.generateSmokeTexture
import com.example.asteroidoutpost.game.ui.HudView

/** Radical 3D — camera now ORBITS BEHIND THE SHIP (positive sign) instead
 *  of in front of the bow. New view: third-person space-shooter — ship in
 *  foreground, asteroids approach from far +Z direction (in front of bow),
 *  player shoots forward. Look direction now has +Z component, so any -Z
 *  trajectory (asteroids descending in z) approaches the camera and grows
 *  on screen — the constraint that previously needed Y/Z > 0.685 is GONE
 *  (any Y/Z works). */
private const val CAMERA_TILT_RAD: Float = 0.0f

// Asteroid bracket-frame colours. Priority lock (player tap) — green;
// auto-aim threats (course intersects shield/hull within engagement
// range) — red. Saturated values so they read against busy nebula
// backgrounds; alpha=full on the main stroke, the glow halo uses ~33%
// of the same RGB internally.
private const val COLOR_FRAME_PRIORITY: Int = 0xFF44EE44.toInt()
private const val COLOR_FRAME_THREAT:   Int = 0xFFEE4444.toInt()

class MainActivity : AppCompatActivity() {

    private lateinit var engineView: EngineView

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
    // Ship hull replaces the legacy gray-quad platform — long horizontal
    // silhouette with a tapered prow (right) and engine block (left).
    private var shipHullMeshHandle:       Long = 0L
    private var centralBaseMeshHandle:    Long = 0L
    private var centralBarrelMeshHandle:  Long = 0L   // legacy — unused now
    private var sideBaseMeshHandle:       Long = 0L
    private var sideBarrelMeshHandle:     Long = 0L   // legacy — unused now
    // Concept «Вид 3» — split rotating part into tower + cannon. Tower
    // rotates yaw with the central angle; cannon rotates the same yaw +
    // optional pitch around its own X axis.
    private var centralTowerMeshHandle:   Long = 0L
    private var centralCannonMeshHandle:  Long = 0L
    private var sideTowerMeshHandle:      Long = 0L
    private var sideCannonMeshHandle:     Long = 0L
    // .glb-loaded side turret (Body = base+tower fused, Cannon = barrel).
    // Authored in standard gltf convention (+Y up, -Z forward); applied with
    // rotationX = +π/2 in SceneAssembler. No internal coplanar seams →
    // no Z-fight, no runtime nudges.
    private var sideBodyGltfMeshHandle:   Long = 0L
    private var sideCannonGltfMeshHandle: Long = 0L
    private var laserInstallMeshHandle:   Long = 0L
    private var rocketSiloMeshHandle:     Long = 0L
    private var rocketMeshHandle:         Long = 0L
    // Per-type asteroid meshes. NORMAL/FAST randomize across two grey variants
    // at spawn so common waves don't look like clones; HEAVY/EXPLOSIVE/ENERGY
    // each get their own silhouette + tint so the type is readable at a glance.
    private var asteroidMeshGrey1:     Long = 0L  // Asteroid_1.glb grey  (NORMAL/FAST variant A)
    private var asteroidMeshGrey2:     Long = 0L  // Asteroid_2.glb grey  (NORMAL/FAST variant B)
    private var asteroidMeshHeavy:     Long = 0L  // Asteroid_3.glb dark red (HEAVY — chunky/round)
    private var asteroidMeshExplosive: Long = 0L  // Asteroid_4.glb orange   (EXPLOSIVE)
    private var asteroidMeshEnergy:    Long = 0L  // Asteroid_9.glb cyan     (ENERGY)
    // Bullet meshes (replace red-quad placeholders).
    private var bulletMeshHandle:        Long = 0L  // Bullet.glb        (automatic + side turrets)
    private var bulletHeavyMeshHandle:   Long = 0L  // Bullet_Heavy.glb  (heavy cannon)
    private var bulletRailgunMeshHandle: Long = 0L  // Bullet.glb        (railgun — blue tint)
    private var droneMeshHandle:       Long = 0L  // ship.gltf — DRONES ability
    private var enemyShipMeshHandle:   Long = 0L  // Enemy_Ship.glb — combat-mission antagonist
    private var quadFlashHandle:    Long = 0L  // unit X-Z quad, bright yellow (destruction flash)
    private var quadHpBgHandle:     Long = 0L  // unit X-Z quad, dark grey (HP-bar background)
    private var quadHpFgHandle:     Long = 0L  // unit X-Z quad, green (HP-bar structure fill)
    private var quadHpShieldHandle: Long = 0L  // unit X-Z quad, cyan (HP-bar shield fill)
    // Background nebulae — soft-edge disks (E1.4) loaded via `loadMeshRaw`.
    // Each disk is a triangle fan: centre vertex alpha=1, rim vertices alpha=0,
    // so when rendered through the translucent pipeline it fades smoothly to
    // the background instead of showing hard quad edges. One handle per tint.
    private val nebulaHandles: LongArray = LongArray(5)
    // E20 — force-field hemisphere mesh (unit half-sphere, y ≥ 0).
    private var shieldHemisphereHandle: Long = 0L
    // Debug — labels above asteroids. Mounted in Activity setup; mutated
    // (snapshot updated) from the tick thread after each buildScene.
    private var debugAsteroidLabelsView: com.example.asteroidoutpost.game.ui.DebugAsteroidLabelsView? = null
    // Green frame around the player-priority-locked asteroid (tap-to-lock).
    // Updated alongside debug labels; null when nothing is locked.
    private var selectionFrameView: com.example.asteroidoutpost.game.ui.SelectionFrameView? = null
    // Debug — axes-gizmo container (МИР + ЭКР tiles, top-right). Toggled
    // by the master debug switch in SettingsOverlay.
    private var debugAxesContainer: View? = null
    // User debug toggles (master on/off + asteroid label mode). Backed by
    // SharedPreferences; read each frame by `updateDebugAsteroidLabels` and
    // applied to overlay visibility when the user flips them in Settings.
    private lateinit var debugSettings: com.example.asteroidoutpost.game.DebugSettings
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
    // MutableList so its contents can be rebuilt at runtime when the
    // nebula-quality flag toggles (tap on FPS label) — SceneAssembler holds
    // a reference to this same list, so mutating in place propagates.
    private val nebulaeTranslucent: MutableList<SceneObject> = mutableListOf()
    private data class NebulaPlacement(
        val tint: Int, val x: Float, val y: Float, val z: Float, val scale: Float,
    )
    private val nebulaPlacements: MutableList<NebulaPlacement> = mutableListOf()
    /** Toggle between full-quality nebula FBM (warp + 4-octave) and fast mode
     *  (single 3-octave, no warp). Tap on the FPS label cycles. */
    private var nebulaFastMode: Boolean = false

    // Game → engine adapter. Reads the runner's gameplay state (asteroids,
    // effects, flashes, fireballs, particles) by ref + per-frame scalars
    // (centralTurretAngle, shieldHp, reloadProgress) and produces a
    // `SceneFrame` the engine consumes. Constructed at the end of
    // setupBackgroundNebulae once mesh handles + the runner are ready.
    private lateinit var sceneAssembler: SceneAssembler

    // Mission runner — owns gameplay state, tick loop, ability framework,
    // RocketSilo, target selection, win/lose detection. Constructed in
    // onCreate; assets/HUD/VFX attached in setupBackgroundNebulae once
    // they're loaded. Activity drives lifecycle (startMission/stopMission/
    // destroy) and forwards UI events (taps, ability presses, shield holds).
    private lateinit var missionRunner: MissionRunner

    // Persistent player state. Loaded from SharedPreferences in onCreate.
    private lateinit var progressRepo: ProgressRepository
    // Network — server API. ApiClient owns OkHttp + token persistence;
    // service classes are thin wrappers over typed endpoints. All four
    // share a single ApiClient so they reuse one connection pool and
    // one bearer token. Wired-up calls land here as feature work lands
    // (auth is wired in onCreate; missions/progress/telemetry wired in
    // their respective screens / runner hooks in subsequent iterations).
    private lateinit var apiClient: com.example.asteroidoutpost.net.ApiClient
    private lateinit var authService: com.example.asteroidoutpost.net.AuthService
    private lateinit var missionService: com.example.asteroidoutpost.net.MissionService
    private lateinit var progressService: com.example.asteroidoutpost.net.ProgressService
    private lateinit var telemetryService: com.example.asteroidoutpost.net.TelemetryService

    // HUD action-bar widgets (shield button, ability buttons, buff indicator,
    // abort ✕). Owns its own Drawables and refresh logic; we expose the views
    // as `hud.shieldButton` / `hud.abortButton` etc. for layout mounting.
    private lateinit var hud: HudView
    private lateinit var abilityBar: LinearLayout

    private val hudCallbacks: HudView.Callbacks = object : HudView.Callbacks {
        override fun onShieldDown()    { missionRunner.handleShieldDown() }
        override fun onShieldUp()      { missionRunner.handleShieldUp() }
        override fun onAbilityTap(slotIndex: Int) { missionRunner.handleAbilityTap(slotIndex) }
        override fun onAbortMission()  {
            if (missionRunner.gameState != GameState.PLAYING) return
            // Abort returns the player to mission select, with menu underneath
            // for the "Назад" path.
            resetStack(Screen.Menu, Screen.MissionHub)
        }
    }

    private val missionRunnerHost: MissionRunner.Host = object : MissionRunner.Host {
        override fun onMissionWon()  { replaceTop(Screen.Win) }
        override fun onMissionLost() { replaceTop(Screen.Lose) }
    }

    private lateinit var fpsLabel:          TextView
    private val fpsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val fpsUpdater = object : Runnable {
        override fun run() {
            fpsLabel.text = "FPS ${engineView.fps.toInt()}"
            fpsHandler.postDelayed(this, 500L)
        }
    }

    // ---------------------------------------------------------------------
    // Navigation stack — single source of truth for which overlay is on
    // screen. `enterScreen` pushes (Back will pop), `popScreen` pops one
    // (empty stack closes the app), `replaceTop` swaps the visible screen
    // without changing depth (used when the runner reports win/lose →
    // Playing replaced by Win/Lose), `resetStack` rebuilds the entire
    // history (used by "К выбору миссий" and abort).
    //
    // Each entry is a [Screen] sealed object — parametrized variants
    // (e.g. WeaponSelect carries the picked mission) keep all per-screen
    // context inside the stack instead of scattered fields.
    // ---------------------------------------------------------------------
    private sealed class Screen {
        object Menu : Screen()
        object MissionHub : Screen()                                       // Campaign / Random tabs
        object Campaign : Screen()                                         // Graph of mission circles
        object RandomMissions : Screen()                                   // Procedural missions placeholder
        data class MissionDetail(val mission: MissionConfig) : Screen()    // Description + Start
        object MissionSelect : Screen()                                    // Legacy flat list (kept for fallback)
        data class WeaponSelect(val mission: MissionConfig) : Screen()
        object Base : Screen()
        object Settings : Screen()
        object Win : Screen()
        object Lose : Screen()
        /** No overlay — gameplay running. HUD visible. */
        object Playing : Screen()
    }

    private val backStack = ArrayDeque<Screen>()
    private var currentOverlay: View? = null

    // Background music. SFX (engine drone / shoot) belonged to the g3 fleet
    // sim and have been retired with that subsystem.
    private var bgMusic: MediaPlayer? = null

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
        engineView = findViewById(R.id.engineView)

        // Load persistent player progress (metal, upgrades, etc.).
        progressRepo = ProgressRepository(this)

        // Network layer — single ApiClient + service instances per activity.
        // First-time launch silently registers device with server in the
        // background; subsequent launches reuse the cached token. All
        // failures are non-fatal — app continues fully offline if the
        // server is unreachable.
        apiClient = com.example.asteroidoutpost.net.ApiClient(this)
        authService = com.example.asteroidoutpost.net.AuthService(apiClient)
        missionService = com.example.asteroidoutpost.net.MissionService(apiClient)
        progressService = com.example.asteroidoutpost.net.ProgressService(apiClient)
        telemetryService = com.example.asteroidoutpost.net.TelemetryService(apiClient)
        Thread({ authService.ensureToken() }, "ApiBootstrap").start()

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

        // Mission runner — built early so HudView can reference its
        // `abilitySlots` at construction time. HUD/VFX/mesh handles are
        // attached later (in setupBackgroundNebulae) once asset load
        // completes; the runner doesn't tick until startMission anyway.
        missionRunner = MissionRunner(
            uiHandler    = Handler(mainLooper),
            progressRepo = progressRepo,
            onRender     = ::buildScene,
            host         = missionRunnerHost,
        )

        // DRAFT — camera locked. The central turret runs auto-aim: it picks the
        // most dangerous asteroid (highest current HP, ties broken by nearest
        // to the turret) and fires at it whenever the cooldown allows. The
        // player taps an asteroid to override the auto-pick with a priority
        // lock — the runner then prefers that asteroid until it dies or
        // leaves the screen. Tap on empty space is a no-op (so accidental
        // misses don't release a deliberate lock).
        engineView.onCameraOrbited = { _, _ -> }
        engineView.onCameraRolled  = { _ -> }
        engineView.onCameraReset   = { }
        engineView.onTap = { _, _ -> }
        engineView.setOnTouchListener { _, event ->
            // Debug toggle — tap inside the bottom-left ~120×120 px square
            // where the FPS label sits cycles nebula quality FULL ↔ FAST.
            // Routed here (not via FPS label's OnClickListener) because the
            // FrameLayout dispatch order doesn't reliably let the small
            // text-view consume the touch first; this hook checks raw
            // coordinates and intercepts before any other tap handling.
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val h = engineView.height.toFloat()
                if (event.x < 160f && event.y > h - 160f) {
                    nebulaFastMode = !nebulaFastMode
                    rebuildNebulaeTranslucent()
                    engineView.translucentObjects = nebulaeTranslucent
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        if (nebulaFastMode) "Nebula: FAST (fbm3 no warp)"
                        else                "Nebula: FULL (warp + fbm4)",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnTouchListener true
                }
            }
            if (missionRunner.gameState == GameState.PLAYING &&
                event.actionMasked == MotionEvent.ACTION_DOWN) {
                // Screen-space pick — project every live asteroid to pixel
                // coords and pick the one whose projected centre is closest
                // to the touch point (within its own screen-radius hitbox).
                // Replaces engine.pickObject (per-pixel depth buffer): that
                // approach always returned the nearest occluding asteroid
                // even when the player tapped on the visible edge of a
                // farther one. With screen-space picking, two overlapping
                // asteroids can be distinguished by tapping the further
                // one's exposed edge.
                val pickedId = pickAsteroidByScreen(event.x, event.y)
                if (pickedId != null) {
                    missionRunner.handleAsteroidPickedById(pickedId)
                }
            }
            true
        }

        // Single sci-fi HUD anchored at top: left = mission + wave, right = score + HP,
        // ✕ embedded as the rightmost child (no separate floating button). Background
        // intentionally absent — HUD shouldn't compete visually with gameplay.
        val root = engineView.parent as FrameLayout
        hud = HudView(this, missionRunner.abilitySlots, hudCallbacks)
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
        // Horizontal row at bottom-centre — thumb-reachable on portrait
        // phones. Buttons are square icon-tiles (~46dp side) with small
        // horizontal gaps; cooldown text swaps in for the icon via refresh
        // when shown.
        val btnSide     = theme.dp(this, 69f)   // 1.5× the previous 46dp
        val btnGapDp    = theme.dp(this, 16f)   // 2× the previous 8dp gap
        val btnLp = LinearLayout.LayoutParams(btnSide, btnSide)
        val btnLpGap = LinearLayout.LayoutParams(btnSide, btnSide).apply {
            setMargins(btnGapDp, 0, 0, 0)
        }
        abilityBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        abilityBar.addView(hud.buildShieldButton(), btnLp)
        // Build one button per slot, sharing the same dimensions as the
        // shield button so the row reads as a uniform action bar.
        for (btn in hud.buildAbilityButtons()) {
            abilityBar.addView(btn, btnLpGap)
        }
        val barParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL,
        ).apply {
            bottomMargin = theme.dp(this@MainActivity, 16f)
        }
        root.addView(abilityBar, barParams)
        hud.refreshShield(missionRunner.shieldHp, DraftCombat.SHIELD_MAX_HP)
        hud.refreshAllAbilities(DraftCombat.ENERGY_MAX)

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

        // Debug — two stacked axes gizmos in the right band: world (top)
        // and screen (bottom). Same 2D arrow style; only the per-arrow
        // (dx, dy) data differs. World-axis directions are pre-computed
        // by applying R^-1 = Rx(-(π/2 + tilt)) (style3 camera rotation
        // inverse, where tilt = CAMERA_TILT_RAD) to each world basis
        // vector and taking the resulting (camera_x, camera_y) components.
        // Canvas y is flipped (positive = down on screen) so camera_y → -dy.
        //
        //   world +X → camera (1, 0, 0)              → dx=+1.000, dy= 0.000
        //   world +Y → camera (0, -sin(tilt), …)     → dx= 0.000, dy=+sin(tilt)
        //   world +Z → camera (0, +cos(tilt), …)     → dx= 0.000, dy=-cos(tilt)
        //
        // At tilt 0: sin=0, cos=1. World +Y goes straight into the screen
        // (zero 2D component), world +Z aligns with screen-up. So world Y
        // is essentially a "dot" — not drawable as a 2D arrow.
        val redAxis   = 0xFFFF5050.toInt()
        val greenAxis = 0xFF5DE08C.toInt()
        val blueAxis  = 0xFF6090FF.toInt()
        val worldAxes = com.example.asteroidoutpost.game.ui.DebugAxesView(this, "МИР", listOf(
            com.example.asteroidoutpost.game.ui.DebugAxesView.Axis("X",  1.000f,  0.000f, redAxis),
            com.example.asteroidoutpost.game.ui.DebugAxesView.Axis("Y",  0.000f,  0.000f, greenAxis),
            com.example.asteroidoutpost.game.ui.DebugAxesView.Axis("Z",  0.000f, -1.000f, blueAxis),
        ))
        val screenAxes = com.example.asteroidoutpost.game.ui.DebugAxesView(this, "ЭКР", listOf(
            com.example.asteroidoutpost.game.ui.DebugAxesView.Axis("X",  1.000f,  0.000f, redAxis),
            com.example.asteroidoutpost.game.ui.DebugAxesView.Axis("Y",  0.000f, -1.000f, greenAxis),
        ))
        val axesTileSize = com.example.asteroidoutpost.game.UiTheme.dp(this, 72f)
        val axesContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(worldAxes,  android.widget.LinearLayout.LayoutParams(axesTileSize, axesTileSize))
            addView(screenAxes, android.widget.LinearLayout.LayoutParams(axesTileSize, axesTileSize))
        }
        val axesContainerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL,
        ).apply {
            rightMargin = com.example.asteroidoutpost.game.UiTheme.dp(this@MainActivity, 6f)
        }
        root.addView(axesContainer, axesContainerParams)
        debugAxesContainer = axesContainer

        // Selection-frame overlay (game feature, not debug) — green rect
        // around the player-priority-locked asteroid. Mounted BEFORE the
        // labels view so labels render on top (won't be hidden behind the
        // frame stroke). Touches fall through.
        val selFrame = com.example.asteroidoutpost.game.ui.SelectionFrameView(this)
        selectionFrameView = selFrame
        val selFrameParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        root.addView(selFrame, selFrameParams)

        // Debug — full-screen transparent overlay drawing world-coord
        // labels above each live asteroid. Mounted last so it sits on
        // top of everything; touches fall through (View.isClickable=false).
        val labelsView = com.example.asteroidoutpost.game.ui.DebugAsteroidLabelsView(this)
        debugAsteroidLabelsView = labelsView
        val labelsParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        root.addView(labelsView, labelsParams)

        // Apply initial debug-overlay visibility from persisted settings.
        // Re-applied on Settings change via the onSettingsChanged callback.
        debugSettings = com.example.asteroidoutpost.game.DebugSettings(this)
        applyDebugVisibility()

        // Diagnostic FPS readout — bottom-left corner, dim caption-size so it
        // doesn't compete with gameplay. Reads engineView.fps (sliding 1-sec
        // window updated by RenderThread). Polled every 500ms by fpsUpdater.
        fpsLabel = TextView(this).apply {
            text = "FPS —"
            setTextColor(com.example.asteroidoutpost.game.UiTheme.COL_TEXT_DIM)
            textSize = com.example.asteroidoutpost.game.UiTheme.SP_CAPTION * 0.7f
            // Tap to toggle nebula quality — full FBM (warp + 4 octaves) vs
            // fast (single fbm3, no warp). Lets us A/B compare visually
            // without rebuilding the app. Padding makes the touch area
            // larger than the small text.
            setPadding(
                com.example.asteroidoutpost.game.UiTheme.dp(this@MainActivity, 12f),
                com.example.asteroidoutpost.game.UiTheme.dp(this@MainActivity, 12f),
                com.example.asteroidoutpost.game.UiTheme.dp(this@MainActivity, 12f),
                com.example.asteroidoutpost.game.UiTheme.dp(this@MainActivity, 12f),
            )
            setOnClickListener {
                nebulaFastMode = !nebulaFastMode
                rebuildNebulaeTranslucent()
                engineView.translucentObjects = nebulaeTranslucent
                android.widget.Toast.makeText(
                    this@MainActivity,
                    if (nebulaFastMode) "Nebula: FAST (fbm3 no warp)" else "Nebula: FULL (warp + fbm4)",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
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

        // Game starts on the main menu — every overlay is built on demand
        // by [renderTop] when its Screen lands on the stack.
        engineView.onSurfaceReady = {
            loadAssets()
            // 3D pivot — tilt the camera off the strict side-view.
            // Engine starts at pitch = π/2 (camera looking horizontally,
            // gameplay plane viewed perpendicular). We orbit -0.6 rad
            // (~34°) so it ends at pitch ≈ 56° — a "from above and
            // behind" angle that reads as a real 3D POV from the bridge.
            engineView.engine.orbitCamera(0f, CAMERA_TILT_RAD)
        }
        resetStack(Screen.Menu)
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
            // E20 — force-field shield pipeline shaders. Optional; without
            // them drawForceField is a no-op (engine skips the pipeline
            // create branch).
            engineView.engine.setShader("forcefield.vert", assets.open("shaders/forcefield.vert.spv").readBytes())
            engineView.engine.setShader("forcefield.frag", assets.open("shaders/forcefield.frag.spv").readBytes())
            // E18 — fullscreen FBM nebula background. DISABLED for now —
            // GPU cost was visible (FPS dropped) and screen-space FBM
            // didn't match the structured-cloud look of the foreground 3D
            // nebulae anyway. Skipping the setShader calls leaves the
            // engine's `backgroundVertSpv`/`backgroundFragSpv` empty,
            // which the createPipeline branch checks before building
            // m_backgroundPipeline — so no pipeline, no draw call. The
            // shader files (.vert/.frag/.spv) and the C++ pipeline plumbing
            // remain in place; uncommenting these two lines re-enables it.
            // engineView.engine.setShader("background.vert", assets.open("shaders/background.vert.spv").readBytes())
            // engineView.engine.setShader("background.frag", assets.open("shaders/background.frag.spv").readBytes())
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
            quadHpBgHandle     = engineView.engine.loadMeshColored(quadBytes, 0.18f, 0.20f, 0.22f)
            quadHpFgHandle     = engineView.engine.loadMeshColored(quadBytes, 0.30f, 0.85f, 0.35f)
            quadHpShieldHandle = engineView.engine.loadMeshColored(quadBytes, 0.30f, 0.80f, 1.00f)
            if (quadMeshHandle      == 0L) quadMeshHandle      = engineView.engine.loadMesh(quadBytes)
            if (quadGreyHandle      == 0L) quadGreyHandle      = engineView.engine.loadMesh(quadBytes)
            if (quadBlueHandle      == 0L) quadBlueHandle      = engineView.engine.loadMesh(quadBytes)
            if (quadFlashHandle     == 0L) quadFlashHandle     = engineView.engine.loadMesh(quadBytes)
            if (quadHpBgHandle      == 0L) quadHpBgHandle      = engineView.engine.loadMesh(quadBytes)
            if (quadHpFgHandle      == 0L) quadHpFgHandle      = engineView.engine.loadMesh(quadBytes)
            if (quadHpShieldHandle  == 0L) quadHpShieldHandle  = engineView.engine.loadMesh(quadBytes)
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
            // Bright brown for the common asteroid types so they stand
            // distinctly out from the (cool blue) nebula backdrop and the
            // (red-orange) drones. HEAVY/EXPLOSIVE/ENERGY keep their type
            // tints so the player can read them at a glance.
            asteroidMeshGrey1     = engineView.engine.loadMeshColored(a1, 0.78f, 0.50f, 0.22f)
            asteroidMeshGrey2     = engineView.engine.loadMeshColored(a2, 0.85f, 0.55f, 0.25f)
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
            bulletMeshHandle        = engineView.engine.loadMeshColored(bulletBytes, 1.00f, 0.85f, 0.55f)
            bulletHeavyMeshHandle   = engineView.engine.loadMeshColored(heavyBytes,  0.90f, 0.80f, 0.60f)
            // Railgun shares Bullet.glb geometry (slim slug) but with a cool
            // electric-blue per-vertex tint so the slug reads as the Рельсотрон
            // round even before the blue tracer beam catches up.
            bulletRailgunMeshHandle = engineView.engine.loadMeshColored(bulletBytes, 0.30f, 0.70f, 1.00f)
            if (bulletMeshHandle        == 0L) bulletMeshHandle        = engineView.engine.loadMesh(bulletBytes)
            if (bulletHeavyMeshHandle   == 0L) bulletHeavyMeshHandle   = engineView.engine.loadMesh(heavyBytes)
            if (bulletRailgunMeshHandle == 0L) bulletRailgunMeshHandle = engineView.engine.loadMesh(bulletBytes)
            if (bulletHeavyMeshHandle   == 0L) bulletHeavyMeshHandle   = bulletMeshHandle
            if (bulletRailgunMeshHandle == 0L) bulletRailgunMeshHandle = bulletMeshHandle
            if (bulletMeshHandle == 0L) showStatus("Bullet meshes load failed")
        } catch (e: Exception) {
            showStatus("Bullet mesh load failed: ${e.message}")
        }
        // E19 — drones ability. Bright red-orange tint so the swarm reads
        // distinctly against the brown asteroids and blue nebula backdrop.
        try {
            val droneBytes = assets.open("models/ship.gltf").readBytes()
            droneMeshHandle = engineView.engine.loadMeshColored(droneBytes, 0.95f, 0.40f, 0.18f)
            if (droneMeshHandle == 0L) droneMeshHandle = engineView.engine.loadMesh(droneBytes)
            if (droneMeshHandle == 0L) showStatus("Drone mesh load failed")
        } catch (e: Exception) {
            showStatus("Drone mesh load failed: ${e.message}")
        }
        // Side turret .glb pair — Body (base+tower fused) and Cannon.
        // Authored in standard gltf convention (+Y up, -Z forward).
        // SceneAssembler applies rotationX = +π/2 to map Y-up → world Z-up
        // and rotationZ for yaw on the cannon. Per-vertex baseColor already
        // baked into the file by the artist; no runtime tint.
        try {
            val bodyBytes   = assets.open("models/Turret_Side_Body.glb").readBytes()
            val cannonBytes = assets.open("models/Turret_Side_Cannon.glb").readBytes()
            sideBodyGltfMeshHandle   = engineView.engine.loadMesh(bodyBytes)
            sideCannonGltfMeshHandle = engineView.engine.loadMesh(cannonBytes)
            if (sideBodyGltfMeshHandle == 0L || sideCannonGltfMeshHandle == 0L)
                showStatus("Side turret .glb load failed (handle=0)")
        } catch (e: Exception) {
            showStatus("Side turret .glb load failed: ${e.message}")
        }
        // Enemy ship — procedural TIE-fighter-ish .glb generated by
        // tools/build_enemy_ship_glb.py. Multi-material (hull / wing /
        // engine) with baseColorFactor baked per primitive — GltfLoader
        // merges into one mesh with per-vertex colour. Authored in
        // game-world convention (+Z up, +Y forward), so no rotation
        // override needed in SceneAssembler.
        try {
            val enemyShipBytes = assets.open("models/Enemy_Ship.glb").readBytes()
            enemyShipMeshHandle = engineView.engine.loadMesh(enemyShipBytes)
            if (enemyShipMeshHandle == 0L)
                showStatus("Enemy ship .glb load failed (handle=0)")
        } catch (e: Exception) {
            showStatus("Enemy ship .glb load failed: ${e.message}")
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
        // Central — steel-blue body + saturated cyan accent (primary
        // armament reads cool/bright). Side bases — dark navy body,
        // side barrels — dark red body (per the "blue base, red gun"
        // contrast brief).
        centralBaseMeshHandle = buildTurretBaseMesh(
            engine,
            halfW   = DraftCombat.CENTRAL_BASE_HALF_W,
            height  = DraftCombat.CENTRAL_BASE_HEIGHT,
            bodyR   = 0.42f, bodyG = 0.50f, bodyB = 0.62f,
            accentR = 0.40f, accentG = 0.78f, accentB = 1.00f,
        )
        centralBarrelMeshHandle = buildTurretBarrelMesh(
            engine,
            housingHalfW = DraftCombat.CENTRAL_HOUSING_HALF_W,
            housingLength = DraftCombat.CENTRAL_HOUSING_LENGTH,
            barrelHalfW  = DraftCombat.CENTRAL_BARREL_HALF_W,
            barrelLength = DraftCombat.CENTRAL_BARREL_LENGTH,
            muzzleHalfW  = DraftCombat.CENTRAL_MUZZLE_HALF_W,
            muzzleLength = DraftCombat.CENTRAL_MUZZLE_LENGTH,
            bodyR   = 0.50f, bodyG = 0.58f, bodyB = 0.72f,
            accentR = 0.45f, accentG = 0.82f, accentB = 1.00f,
        )
        // Concept «Вид 3» — split rotating part into tower + cannon.
        // Brighter tints (was 0.50/0.58/0.72) so the pieces stand out
        // against the dark hull plating.
        centralTowerMeshHandle  = buildTurretTowerMesh(
            engine, bodyR = 0.78f, bodyG = 0.85f, bodyB = 0.95f,
        )
        centralCannonMeshHandle = buildTurretCannonMesh(
            engine, bodyR = 0.78f, bodyG = 0.85f, bodyB = 0.95f,
        )
        sideBaseMeshHandle = buildTurretBaseMesh(
            engine,
            halfW   = DraftCombat.SIDE_BASE_HALF_W,
            height  = DraftCombat.SIDE_BASE_HEIGHT,
            bodyR   = 0.10f, bodyG = 0.16f, bodyB = 0.42f,
            accentR = 0.35f, accentG = 0.55f, accentB = 0.90f,
        )
        sideBarrelMeshHandle = buildTurretBarrelMesh(
            engine,
            housingHalfW = DraftCombat.SIDE_HOUSING_HALF_W,
            housingLength = DraftCombat.SIDE_HOUSING_LENGTH,
            barrelHalfW  = DraftCombat.SIDE_BARREL_HALF_W,
            barrelLength = DraftCombat.SIDE_BARREL_LENGTH,
            muzzleHalfW  = DraftCombat.SIDE_MUZZLE_HALF_W,
            muzzleLength = DraftCombat.SIDE_MUZZLE_LENGTH,
            bodyR   = 0.50f, bodyG = 0.10f, bodyB = 0.12f,
            accentR = 0.85f, accentG = 0.30f, accentB = 0.30f,
        )
        sideTowerMeshHandle   = buildTurretTowerMesh(
            engine, bodyR = 0.95f, bodyG = 0.30f, bodyB = 0.30f,
        )
        sideCannonMeshHandle  = buildTurretCannonMesh(
            engine, bodyR = 0.95f, bodyG = 0.30f, bodyB = 0.30f,
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
     *
     * Tail of this function attaches the runner's late-bound deps (HUD,
     * VfxSpawner, mesh handles) and constructs the SceneAssembler that
     * reads from the runner's collections — runner must be live and the
     * mesh handles must already be loaded by the time this runs.
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
        // Phase 6 — under the tilted 3D camera, flat xz disks at y=1 used
        // to sit on the deck plane like paint splatters. Now each nebula
        // SceneObject carries `rotationX = CAMERA_TILT_RAD`, which orients
        // the disk perpendicular to the tilted view direction — they read
        // as proper background billboards floating in the void instead of
        // textures painted on the floor. Positions pushed deep behind the
        // asteroid spawn plane (z > 8, y > 6) so they recede into the
        // distance and don't compete with foreground gameplay.
        // 5 nebulae in the upper half of the screen — discrete coloured
        // patches with parallax depth. Mid/lower areas are intentionally
        // black for now (full-screen FBM background was abandoned for
        // performance; revisit later with a different approach).
        // Z reduced ~2 units across the board to lower the nebula band on
        // screen ("спустить верх пониже"); scales × 1.2 ("растянуть на 20%").
        // Static — drift was disabled in triangle.frag::nebulaAlphaMod.
        nebulaPlacements.clear()
        // Pushed deep past the asteroid spawn plane (which sits at depth
        // ~24.65) so the nebulae read as a true "far backdrop" — no longer
        // intersect drone / asteroid trajectories. Scales bumped × 1.7 to
        // compensate for the further distance and keep similar screen
        // coverage. Two close nebulae (N4 dust, N2 crimson) stay disabled.
        nebulaPlacements.addAll(listOf(
            NebulaPlacement(0, -2.0f, 10f, 18f, 9.0f),  // purple, upper-left, depth ≈28
            NebulaPlacement(1,  2.2f, 10f, 17f, 8.0f),  // cyan, upper-right, depth ≈27
            NebulaPlacement(3,  0.5f, 12f, 22f, 9.0f),  // twilight blue, upper-centre, depth ≈32
        ))
        rebuildNebulaeTranslucent()
        // E3.3 — filled half-disk so the hex shader has a continuous surface
        // to draw onto. centerAlpha is low (subtle interior fill, turret stays
        // visible) and the mid-arc carries the rim glow. midR pulled inward to
        // 0.80 so the falloff from peak to outer rim is wider — softer dome
        // silhouette instead of a hard edge. Hex modulation is intentionally
        // subtle (see hexAlphaMod in triangle.frag).
        // E20 force-field hemisphere — rendered via the dedicated
        // forcefield pipeline (own shader, fresnel + impact bloom).
        shieldHemisphereHandle = buildShieldHemisphereMesh(engine)
        // Ship hull mesh — replaces the legacy gray-quad platform.
        shipHullMeshHandle = buildShipHullMesh(engine)
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
        // buildScene from the tick) still show the nebulae backdrop.
        engineView.translucentObjects = nebulaeTranslucent

        // VFX spawner — appends to the runner's collections (passed by ref).
        // Used by tick / projectile behaviours / RocketSilo for muzzle blasts,
        // fireballs, hit flashes, asteroid death, shield recharge sparks, etc.
        val vfx = VfxSpawner(
            flashes          = missionRunner.flashes,
            fireballs        = missionRunner.fireballs,
            sparkParticles   = missionRunner.sparkParticles,
            smokeParticles   = missionRunner.smokeParticles,
            debrisParticles  = missionRunner.debrisParticles,
            muzzleConeMeshHandle = muzzleConeMeshHandle,
            quadFlashHandle      = quadFlashHandle,
        )
        // Hand HUD/VFX/projectile mesh handles to the runner so it can spawn
        // bullets/rockets/beams. From this call onward startMission is safe.
        missionRunner.attachAssets(
            hud                   = hud,
            vfx                   = vfx,
            rocketMeshHandle        = rocketMeshHandle,
            bulletMeshHandle        = bulletMeshHandle,
            bulletHeavyMeshHandle   = bulletHeavyMeshHandle,
            bulletRailgunMeshHandle = bulletRailgunMeshHandle,
            asteroidMeshGrey1     = asteroidMeshGrey1,
            asteroidMeshGrey2     = asteroidMeshGrey2,
            asteroidMeshHeavy     = asteroidMeshHeavy,
            asteroidMeshExplosive = asteroidMeshExplosive,
            asteroidMeshEnergy    = asteroidMeshEnergy,
            enemyShipMeshHandle   = enemyShipMeshHandle,
        )
        // game → engine adapter. Reads the runner's collections (asteroids,
        // effects, flashes, fireballs, particles) and per-frame scalars
        // (centralTurretAngle, shieldHp, reloadProgress) to compose one
        // `SceneFrame` per call to `assemble(...)`.
        sceneAssembler = SceneAssembler(
            asteroids          = missionRunner.asteroids,
            effects            = missionRunner.effects,
            drones             = missionRunner.drones,
            flashes            = missionRunner.flashes,
            fireballs          = missionRunner.fireballs,
            shieldImpacts      = missionRunner.shieldImpacts,
            sparkParticles     = missionRunner.sparkParticles,
            smokeParticles     = missionRunner.smokeParticles,
            debrisParticles    = missionRunner.debrisParticles,
            turretXs           = missionRunner.turretXs,
            sideTurretAngles   = missionRunner.sideTurretAngles,
            nebulaeTranslucent = nebulaeTranslucent,
            quadGreyHandle           = quadGreyHandle,
            shipHullMeshHandle       = shipHullMeshHandle,
            quadFlashHandle          = quadFlashHandle,
            quadMeshHandle           = quadMeshHandle,
            quadHpBgHandle           = quadHpBgHandle,
            quadHpFgHandle           = quadHpFgHandle,
            quadHpShieldHandle       = quadHpShieldHandle,
            centralBaseMeshHandle    = centralBaseMeshHandle,
            centralTowerMeshHandle   = centralTowerMeshHandle,
            centralCannonMeshHandle  = centralCannonMeshHandle,
            sideBaseMeshHandle       = sideBaseMeshHandle,
            sideTowerMeshHandle      = sideTowerMeshHandle,
            sideCannonMeshHandle     = sideCannonMeshHandle,
            sideBodyGltfMeshHandle   = sideBodyGltfMeshHandle,
            sideCannonGltfMeshHandle = sideCannonGltfMeshHandle,
            laserInstallMeshHandle   = laserInstallMeshHandle,
            rocketSiloMeshHandle     = rocketSiloMeshHandle,
            asteroidMeshGrey1        = asteroidMeshGrey1,
            droneMeshHandle          = droneMeshHandle,
            shieldHemisphereHandle   = shieldHemisphereHandle,
            fireballMeshHandle       = fireballMeshHandle,
            particleQuadHandle       = particleQuadHandle,
            smokeTextureHandle       = smokeTextureHandle,
            debrisTextureHandle      = debrisTextureHandle,
        )
    }

    /**
     * Rebuild the translucent nebula list from `nebulaPlacements` using the
     * current `nebulaFastMode`. The list itself (and its reference held by
     * SceneAssembler) is mutated in place, so the assembler picks up the
     * new material flag on its next `assemble()` call without needing a
     * fresh assembler.
     */
    private fun rebuildNebulaeTranslucent() {
        val material =
            if (nebulaFastMode) EngineJni.MATERIAL_NEBULA_FAST
            else EngineJni.MATERIAL_NEBULA
        val rebuilt = nebulaPlacements.mapIndexed { i, p ->
            SceneObject(
                id         = 2000 + i,
                meshHandle = nebulaHandles[p.tint],
                x          = p.x, y = p.y, z = p.z,
                scale      = p.scale,
                rotationX  = CAMERA_TILT_RAD,
                material   = material,
            )
        }
        nebulaeTranslucent.clear()
        nebulaeTranslucent.addAll(rebuilt)
    }

    // ---------------------------------------------------------------------------
    // Scene building
    /**
     * Per-frame scene push. Reads the runner's per-frame scalars (reload
     * progress, central turret angle, shield HP) and delegates the heavy
     * lifting to `sceneAssembler`. The assembler reads game state via the
     * collection refs baked into its constructor and returns a `SceneFrame`;
     * we copy the lists onto `engineView`. Wired as the runner's `onRender`
     * callback so it fires once per tick during PLAYING; also called from
     * `loadAssets` and `goToMenu` so the menu / win-lose scenes are
     * composed even while the runner is idle.
     */
    private fun buildScene() {
        // Guard against the early-mount path. `resetStack(Screen.Menu)` at
        // the end of onCreate fires before `engineView.onSurfaceReady`, so
        // the assembler isn't built yet — `loadAssets` will populate the
        // first frame itself once the Vulkan surface is up.
        if (!::sceneAssembler.isInitialized) return
        // Snapshot shipPosY ONCE so scene-ship-Y and camera-target-Y use
        // the same value within this buildScene cycle. The write of the
        // engineView lists + cameraTargetY is wrapped in sceneSyncLock
        // so the render thread can't grab a partial state mid-write
        // (would cause visible per-frame trembling).
        val shipY = missionRunner.shipPosY
        val frame = sceneAssembler.assemble(
            centralTurretAngle = missionRunner.centralTurretAngle,
            shieldHp           = missionRunner.shieldHp,
            shipPosY           = shipY,
        )
        synchronized(engineView.sceneSyncLock) {
            engineView.scene              = frame.scene
            engineView.plasmaBillboards   = frame.plasmaBillboards
            engineView.translucentObjects = frame.translucentObjects
            engineView.additiveObjects    = frame.additiveObjects
            engineView.beams              = frame.beams
            engineView.forceFields        = frame.forceFields
            engineView.particleBatches    = frame.particleBatches
            engineView.cameraTargetY      = shipY
        }
        // Debug — project each live asteroid's world position to the
        // engine surface and push label snapshots to the overlay view.
        // Runs on the tick thread (caller of buildScene), where the
        // asteroid list isn't being concurrently mutated.
        updateDebugAsteroidLabels()
        // Selection frame around player priority lock — same projection
        // math as labels (factored into the helper below).
        updateSelectionFrame()
    }

    /**
     * Pick the asteroid whose projected on-screen centre is closest to the
     * given touch point, provided the touch falls within the asteroid's
     * screen-space hitbox (silhouette radius × 1.5 for a forgiving tap
     * radius). Returns null when no asteroid is in range. Uses the same
     * perspective constants as `updateDebugAsteroidLabels` /
     * `updateSelectionFrame` so the on-screen hitbox aligns with the
     * green selection frame the user already sees.
     */
    private fun pickAsteroidByScreen(touchX: Float, touchY: Float): Long? {
        val w = engineView.width.toFloat()
        val h = engineView.height.toFloat()
        if (w <= 1f || h <= 1f) return null
        val tilt   = CAMERA_TILT_RAD
        val pitch  = (kotlin.math.PI / 2.0 + tilt).toFloat()
        val sinP   = kotlin.math.sin(pitch)
        val cosP   = kotlin.math.cos(pitch)
        val targetY = missionRunner.shipPosY
        val targetZ = 2.5f
        val radius  = 11f
        val eyeY   = targetY - radius * sinP
        val eyeZ   = targetZ + radius * cosP
        val fwdY = sinP;  val fwdZ = -cosP
        val upY  = cosP;  val upZ  = sinP
        val fovYRad  = (55.0 * kotlin.math.PI / 180.0).toFloat()
        val tanHalf  = kotlin.math.tan(fovYRad / 2f)
        val aspect   = w / h

        var bestId: Long? = null
        var bestD2 = Float.POSITIVE_INFINITY
        for (a in missionRunner.asteroids) {
            if (a.hp <= 0) continue
            val dy = a.yPos - eyeY
            val dz = a.zPos - eyeZ
            val zCam = dy * fwdY + dz * fwdZ
            if (zCam <= 0.3f) continue
            val xCam = a.xPos
            val yCam = dy * upY + dz * upZ
            val ndcX = (xCam / zCam) / (tanHalf * aspect)
            val ndcY = -(yCam / zCam) / tanHalf
            val sx = (ndcX + 1f) * 0.5f * w
            val sy = (ndcY + 1f) * 0.5f * h
            val radiusPx = (a.half / zCam) / tanHalf * h * 0.5f
            // Hitbox grows with the silhouette but never drops below a
            // finger-size floor (~44 dp ≈ Android standard touch target).
            // Without the floor, asteroids beyond ~30 units shrink to
            // sub-10-pixel silhouettes and become impossible to tap.
            val minTouchPx = com.example.asteroidoutpost.game.UiTheme.dp(this, 22f).toFloat()
            val hitboxPx = (radiusPx * 1.5f).coerceAtLeast(minTouchPx)
            val dx = sx - touchX
            val dyT = sy - touchY
            val d2 = dx * dx + dyT * dyT
            if (d2 <= hitboxPx * hitboxPx && d2 < bestD2) {
                bestD2 = d2
                bestId = a.id
            }
        }
        return bestId
    }

    /**
     * Build the per-frame snapshot of asteroid bracket-frames: green
     * around the player-priority-locked asteroid, red around every other
     * current threat (those on a course to hit shield/hull within
     * WEAPON_ENGAGEMENT_RANGE — what auto-aim weapons will engage). The
     * priority asteroid is excluded from the threat list even if it
     * qualifies as a threat, so it never gets both colours.
     */
    private fun updateSelectionFrame() {
        val view = selectionFrameView ?: return
        val w = engineView.width.toFloat()
        val h = engineView.height.toFloat()
        if (w <= 1f || h <= 1f) { view.clear(); return }
        val tilt   = CAMERA_TILT_RAD
        val pitch  = (kotlin.math.PI / 2.0 + tilt).toFloat()
        val sinP   = kotlin.math.sin(pitch)
        val cosP   = kotlin.math.cos(pitch)
        val targetY = missionRunner.shipPosY
        val targetZ = 2.5f
        val radius  = 11f
        val eyeY   = targetY - radius * sinP
        val eyeZ   = targetZ + radius * cosP
        val fwdY = sinP;  val fwdZ = -cosP
        val upY  = cosP;  val upZ  = sinP
        val fovYRad  = (55.0 * kotlin.math.PI / 180.0).toFloat()
        val tanHalf  = kotlin.math.tan(fovYRad / 2f)
        val aspect   = w / h

        val frames = ArrayList<com.example.asteroidoutpost.game.ui.SelectionFrameView.Frame>()
        val priority = missionRunner.currentPriorityTarget()
        val priorityId = priority?.id
        val threats = missionRunner.currentThreatAsteroids()

        fun project(a: com.example.asteroidoutpost.game.combat.Asteroid, color: Int) {
            val dy = a.yPos - eyeY
            val dz = a.zPos - eyeZ
            val zCam = dy * fwdY + dz * fwdZ
            if (zCam <= 0.3f) return
            val xCam = a.xPos
            val yCam = dy * upY + dz * upZ
            val ndcX = (xCam / zCam) / (tanHalf * aspect)
            val ndcY = -(yCam / zCam) / tanHalf
            val sx = (ndcX + 1f) * 0.5f * w
            val sy = (ndcY + 1f) * 0.5f * h
            val radiusPx = (a.half / zCam) / tanHalf * h * 0.5f * 1.6f
            frames.add(com.example.asteroidoutpost.game.ui.SelectionFrameView.Frame(
                sx, sy, radiusPx, color))
        }

        if (priority != null) project(priority, COLOR_FRAME_PRIORITY)
        for (t in threats) {
            if (t.id == priorityId) continue   // already drawn green
            project(t, COLOR_FRAME_THREAT)
        }
        view.update(frames)
    }

    /**
     * Compute pixel positions for each live asteroid and hand them to the
     * debug overlay. World→screen math matches the engine's lookAt camera:
     * pitch = π/2 + CAMERA_TILT_RAD, target = (0, 0, 2.5), radius = 11,
     * fovY = 55°. Run on the tick thread; the View itself reads the
     * AtomicReference snapshot from the UI thread in onDraw.
     */
    private fun updateDebugAsteroidLabels() {
        val view = debugAsteroidLabelsView ?: return
        // Honour the master debug switch + label-mode picker from Settings.
        // NONE / off → wipe stale labels and bail before doing projection work.
        if (!debugSettings.enabled ||
            debugSettings.labelMode == com.example.asteroidoutpost.game.DebugLabelMode.NONE) {
            view.clear(); return
        }
        val mode = debugSettings.labelMode
        val w = engineView.width.toFloat()
        val h = engineView.height.toFloat()
        if (w <= 1f || h <= 1f) { view.clear(); return }
        val tilt   = CAMERA_TILT_RAD
        val pitch  = (kotlin.math.PI / 2.0 + tilt).toFloat()
        val sinP   = kotlin.math.sin(pitch)
        val cosP   = kotlin.math.cos(pitch)
        val targetY = missionRunner.shipPosY    // camera target tracks ship
        val targetZ = 2.5f
        val radius  = 11f
        val eyeY   = targetY - radius * sinP
        val eyeZ   = targetZ + radius * cosP
        // Camera basis in world: forward = R·(0,0,-1), up = R·(0,1,0)
        val fwdY = sinP;  val fwdZ = -cosP
        val upY  = cosP;  val upZ  = sinP
        val fovYRad  = (55.0 * kotlin.math.PI / 180.0).toFloat()
        val tanHalf  = kotlin.math.tan(fovYRad / 2f)
        val aspect   = w / h
        val asteroids = missionRunner.asteroids
        val out = ArrayList<com.example.asteroidoutpost.game.ui.DebugAsteroidLabelsView.Label>(asteroids.size)
        for (a in asteroids) {
            val dy = a.yPos - eyeY
            val dz = a.zPos - eyeZ
            val zCam = dy * fwdY + dz * fwdZ
            if (zCam <= 0.3f) continue   // behind camera or too close
            val xCam = a.xPos
            val yCam = dy * upY + dz * upZ
            val ndcX = (xCam / zCam) / (tanHalf * aspect)
            val ndcY = -(yCam / zCam) / tanHalf   // Vulkan-style Y flip
            val sx = (ndcX + 1f) * 0.5f * w
            val sy = (ndcY + 1f) * 0.5f * h
            val txt = when (mode) {
                com.example.asteroidoutpost.game.DebugLabelMode.COORDS ->
                    String.format(java.util.Locale.ROOT,
                        "(%.1f, %.1f, %.1f)", a.xPos, a.yPos, a.zPos)
                com.example.asteroidoutpost.game.DebugLabelMode.DISTANCE -> {
                    val dxs = a.xPos
                    val dys = a.yPos - missionRunner.shipPosY
                    val dzs = a.zPos
                    val d = kotlin.math.sqrt(dxs * dxs + dys * dys + dzs * dzs)
                    String.format(java.util.Locale.ROOT, "%.1f m", d)
                }
                com.example.asteroidoutpost.game.DebugLabelMode.NONE -> continue   // guarded above
            }
            out.add(com.example.asteroidoutpost.game.ui.DebugAsteroidLabelsView.Label(sx, sy - 14f, txt))
        }
        view.update(out)
    }

    // ---------------------------------------------------------------------------
    // Navigation — stack of [Screen]s. `enterScreen` pushes, `popScreen` pops
    // (closes the app on empty stack), `replaceTop` swaps the visible
    // screen without changing depth, `resetStack` rebuilds history. Each
    // change ends in [renderTop], which removes whatever overlay is mounted
    // and builds a fresh one for the new top of the stack.
    // ---------------------------------------------------------------------------

    private fun enterScreen(screen: Screen) {
        backStack.addLast(screen)
        renderTop()
    }

    private fun popScreen() {
        if (backStack.isNotEmpty()) backStack.removeLast()
        if (backStack.isEmpty()) finish() else renderTop()
    }

    private fun replaceTop(screen: Screen) {
        if (backStack.isNotEmpty()) backStack.removeLast()
        backStack.addLast(screen)
        renderTop()
    }

    private fun resetStack(vararg screens: Screen) {
        backStack.clear()
        backStack.addAll(screens)
        renderTop()
    }

    private fun renderTop() {
        val root = engineView.parent as FrameLayout
        currentOverlay?.let { root.removeView(it) }
        currentOverlay = null
        // HUD is shown only during gameplay; reset to off and let the
        // gameplay branch turn it back on if needed.
        hud.setHudVisible(false)
        abilityBar.visibility      = View.GONE
        hud.abortButton.visibility = View.GONE

        when (val top = backStack.lastOrNull() ?: return) {
            Screen.Menu            -> mountMenu(root)
            Screen.MissionHub      -> mountMissionHub(root)
            Screen.Campaign        -> mountCampaign(root)
            Screen.RandomMissions  -> mountRandomMissions(root)
            is Screen.MissionDetail -> mountMissionDetail(root, top.mission)
            Screen.MissionSelect   -> mountMissionSelect(root)
            is Screen.WeaponSelect -> mountWeaponSelect(root, top.mission)
            Screen.Base            -> mountBase(root)
            Screen.Settings        -> mountSettings(root)
            Screen.Win             -> mountWin(root)
            Screen.Lose            -> mountLose(root)
            Screen.Playing         -> mountPlaying()
        }
    }

    private fun mountAt(root: FrameLayout, view: View) {
        currentOverlay = view
        root.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun mountMenu(root: FrameLayout) {
        // Returning to menu wipes the in-flight scene so the player sees
        // the empty platform again, not frozen mid-mission projectiles.
        missionRunner.stopMission(clearScene = true)
        val view = buildMenu(
            this, "Asteroid Outpost", "Миссии",
            onClose    = { finish() },
            onSettings = { enterScreen(Screen.Settings) },
            onClick    = { enterScreen(Screen.MissionHub) },
        )
        setMenuBody(view, "Всего металла: ${progressRepo.current.metal}")
        addMenuButton(view, "Корабль") { enterScreen(Screen.Base) }
        mountAt(root, view)
        buildScene()
    }

    private fun mountMissionHub(root: FrameLayout) {
        missionRunner.stopMission(clearScene = false)
        val view = buildMissionHub(
            context    = this,
            onCampaign = { enterScreen(Screen.Campaign) },
            onRandom   = { enterScreen(Screen.RandomMissions) },
            onBack     = { popScreen() },
        )
        mountAt(root, view)
    }

    private fun mountCampaign(root: FrameLayout) {
        missionRunner.stopMission(clearScene = false)
        val view = buildCampaign(
            context  = this,
            // Mission 6 («Маршрут») is a one-shot event surfaced in
            // RandomMissions, not part of the campaign graph.
            missions = Missions.ALL.filter { it.id <= 5 },
            onPick   = { enterScreen(Screen.MissionDetail(it)) },
            onBack   = { popScreen() },
        )
        mountAt(root, view)
    }

    private fun mountSettings(root: FrameLayout) {
        missionRunner.stopMission(clearScene = false)
        val view = buildSettings(
            context           = this,
            debugSettings     = debugSettings,
            onSettingsChanged = { applyDebugVisibility() },
            onBack            = { popScreen() },
        )
        mountAt(root, view)
    }

    /**
     * Apply current `debugSettings` state to the live debug overlays.
     * Called once at startup, and again whenever the user flips a toggle
     * in Settings. Asteroid labels react on the next `updateDebugAsteroidLabels`
     * call (no explicit refresh needed); axes gizmo visibility is a direct
     * View.visibility flip here.
     */
    private fun applyDebugVisibility() {
        val v = if (debugSettings.enabled) View.VISIBLE else View.GONE
        debugAxesContainer?.visibility = v
        // Labels view stays mounted; updateDebugAsteroidLabels honours the
        // enabled+mode flags via `view.clear()` when nothing should render.
        if (!debugSettings.enabled) debugAsteroidLabelsView?.clear()
    }

    private fun mountRandomMissions(root: FrameLayout) {
        missionRunner.stopMission(clearScene = false)
        val view = buildRandomMissions(
            context = this,
            onMissionTap = { mission -> enterScreen(Screen.MissionDetail(mission)) },
            onBack       = { popScreen() },
        )
        mountAt(root, view)
    }

    private fun mountMissionDetail(root: FrameLayout, mission: MissionConfig) {
        missionRunner.stopMission(clearScene = false)
        val view = buildMissionDetail(
            context = this,
            mission = mission,
            onStart = { picked ->
                // Skip WeaponSelect — weapon is set persistently from
                // «Корабль». Use the saved selection straight into Playing.
                val weapon = WeaponCatalog.byId(progressRepo.current.selectedWeaponId)
                resetStack(Screen.Playing)
                missionRunner.startMission(picked, weapon)
            },
            onBack  = { popScreen() },
        )
        mountAt(root, view)
    }

    private fun mountMissionSelect(root: FrameLayout) {
        // Don't clear the in-flight scene — if we're aborting from PLAYING,
        // gameplay state freezes once the runner flips to MENU; it's wiped
        // wholesale on the next startMission anyway.
        missionRunner.stopMission(clearScene = false)
        val view = buildMissionList(
            this,
            Missions.ALL,
            onStart = { enterScreen(Screen.WeaponSelect(it)) },
            onBack  = { popScreen() },
        )
        mountAt(root, view)
    }

    private fun mountWeaponSelect(root: FrameLayout, mission: MissionConfig) {
        missionRunner.stopMission(clearScene = false)
        val view = buildWeaponSelect(
            context         = this,
            weapons         = WeaponCatalog.ALL,
            currentWeaponId = missionRunner.currentWeapon.id,
            onChoose = { picked ->
                resetStack(Screen.Playing)
                missionRunner.startMission(mission, picked)
            },
            onBack = { popScreen() },
        )
        mountAt(root, view)
    }

    private fun mountBase(root: FrameLayout) {
        // База shows the live base scene as a backdrop — same draw list as
        // gameplay, but with combat off and no enemies. Wipe asteroids /
        // projectiles / VFX (without touching mission state, so Win/Lose
        // ↔ База round-trips don't lose context) and refresh the scene.
        missionRunner.clearCombatScene()
        buildScene()
        val view = buildUpgrades(
            context = this,
            progress = progressRepo.current,
            onPurchase = { type, cost ->
                progressRepo.update { UpgradeCatalog.applyPurchase(it, type, cost) }
                renderTop()   // refresh in place
            },
            onWeaponPick = { weaponId ->
                progressRepo.update { it.copy(selectedWeaponId = weaponId) }
                missionRunner.currentWeapon = WeaponCatalog.byId(weaponId)
                renderTop()   // refresh — pill updates, "Выбрано" jumps card
            },
            onBack = { popScreen() },
        )
        mountAt(root, view)
    }

    private fun mountWin(root: FrameLayout) {
        val mission = missionRunner.currentMission ?: return
        val run = missionRunner.missionRun
        val stats = listOf(
            "Уничтожено астероидов" to run.asteroidsDestroyed.toString(),
            "Очки"                  to run.score.toString(),
            "Получено металла"      to "${run.metalEarned} (+${run.winBonus} бонус)",
            "Всего металла"         to progressRepo.current.metal.toString(),
        )
        val buttons = mutableListOf<Pair<String, () -> Unit>>()
        // "Следующая миссия" only inside the campaign chain (M1→M2→…→M5).
        // After M5 or after combat events (M7/M8) the campaign has no
        // successor — the player goes back through "К выбору миссий".
        val nextCampaign = Missions.ALL.firstOrNull { it.id == mission.id + 1 && it.id <= 5 }
        if (mission.id <= 4 && nextCampaign != null) {
            buttons += "Следующая миссия" to {
                resetStack(Screen.Playing)
                missionRunner.startMission(nextCampaign, missionRunner.currentWeapon)
            }
        }
        buttons += "Повторить"       to {
            resetStack(Screen.Playing)
            missionRunner.startMission(mission, missionRunner.currentWeapon)
        }
        buttons += "Корабль"         to { enterScreen(Screen.Base) }
        buttons += "К выбору миссий" to { resetStack(Screen.Menu, Screen.MissionHub) }
        val view = buildEndOfMission(
            context  = this,
            title    = "МИССИЯ ВЫПОЛНЕНА",
            subtitle = mission.name,
            stats    = stats,
            accent   = com.example.asteroidoutpost.game.UiTheme.COL_ACCENT_GREEN,
            buttons  = buttons,
        )
        mountAt(root, view)
    }

    private fun mountLose(root: FrameLayout) {
        val mission = missionRunner.currentMission ?: return
        val run = missionRunner.missionRun
        val stats = listOf(
            "Пройдено волн"         to "${run.currentWaveDisplay - 1}/${run.totalWaves}",
            "Уничтожено астероидов" to run.asteroidsDestroyed.toString(),
            "Очки"                  to run.score.toString(),
            "Получено металла"      to run.metalEarned.toString(),
            "Всего металла"         to progressRepo.current.metal.toString(),
        )
        val buttons = listOf<Pair<String, () -> Unit>>(
            "Повторить миссию" to {
                resetStack(Screen.Playing)
                missionRunner.startMission(mission, missionRunner.currentWeapon)
            },
            "Корабль"          to { enterScreen(Screen.Base) },
            "К выбору миссий"  to { resetStack(Screen.Menu, Screen.MissionHub) },
        )
        val view = buildEndOfMission(
            context    = this,
            title      = "ВАШ КОРАБЛЬ КАТАСТРОФИЧЕСКИ ПОВРЕЖДЁН",
            subtitle   = mission.name,
            stats      = stats,
            motivation = "Усильте робота или базу и попробуйте снова.",
            accent     = com.example.asteroidoutpost.game.UiTheme.COL_ACCENT_RED,
            buttons    = buttons,
        )
        mountAt(root, view)
    }

    private fun mountPlaying() {
        // No overlay during gameplay — just turn the HUD on. The runner
        // was already told to start the mission by the caller (weapon
        // select / win-screen "Repeat" / lose-screen "Repeat") before
        // pushing Screen.Playing onto the stack.
        hud.setHudVisible(true)
        abilityBar.visibility      = View.VISIBLE
        hud.abortButton.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------------------
    override fun onResume()  { super.onResume();  engineView.onResume(); bgMusic?.start() }
    override fun onPause()   { super.onPause();   engineView.onPause();  bgMusic?.pause() }
    override fun onDestroy() {
        super.onDestroy()
        missionRunner.destroy()
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
