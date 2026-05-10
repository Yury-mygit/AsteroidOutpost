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
import com.example.asteroidoutpost.game.overlay.buildEndOfMission
import com.example.asteroidoutpost.game.overlay.buildMenu
import com.example.asteroidoutpost.game.overlay.buildMissionList
import com.example.asteroidoutpost.game.overlay.buildUpgrades
import com.example.asteroidoutpost.game.overlay.buildWeaponSelect
import com.example.asteroidoutpost.game.overlay.setMenuBody
import com.example.asteroidoutpost.game.ProgressRepository
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
import com.example.asteroidoutpost.game.content.buildShieldArchMesh
import com.example.asteroidoutpost.game.content.buildShipHullMesh
import com.example.asteroidoutpost.game.content.buildSoftDiskMesh
import com.example.asteroidoutpost.game.content.buildTurretBarrelMesh
import com.example.asteroidoutpost.game.content.buildTurretBaseMesh
import com.example.asteroidoutpost.game.content.generateDebrisTexture
import com.example.asteroidoutpost.game.content.generateSmokeTexture
import com.example.asteroidoutpost.game.ui.HudView

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
    private var centralBarrelMeshHandle:  Long = 0L
    private var sideBaseMeshHandle:       Long = 0L
    private var sideBarrelMeshHandle:     Long = 0L
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
            resetStack(Screen.Menu, Screen.MissionSelect)
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
        object MissionSelect : Screen()
        data class WeaponSelect(val mission: MissionConfig) : Screen()
        object Base : Screen()
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
            if (missionRunner.gameState == GameState.PLAYING &&
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
                    missionRunner.handleWorldTap(worldX, worldZ)
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
        // Vertical column at bottom-right — thumb-reachable on portrait
        // phones. Buttons are square icon-tiles (~46dp side) stacked with a
        // small gap; cooldown text swaps in for the icon via refresh when
        // it has to show.
        val btnSide     = theme.dp(this, 46f)
        val btnGapDp    = theme.dp(this, 8f)
        val btnLp = LinearLayout.LayoutParams(btnSide, btnSide)
        val btnLpGap = LinearLayout.LayoutParams(btnSide, btnSide).apply {
            setMargins(0, btnGapDp, 0, 0)
        }
        abilityBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        abilityBar.addView(hud.buildShieldButton(), btnLp)
        // Build one button per slot, sharing the same dimensions as the
        // shield button so the column reads as a uniform diegetic control stack.
        for (btn in hud.buildAbilityButtons()) {
            abilityBar.addView(btn, btnLpGap)
        }
        val barParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.END,
        ).apply {
            bottomMargin = theme.dp(this@MainActivity, 16f)
            marginEnd    = theme.dp(this@MainActivity, 16f)
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

        // Game starts on the main menu — every overlay is built on demand
        // by [renderTop] when its Screen lands on the stack.
        engineView.onSurfaceReady = { loadAssets() }
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
            rocketMeshHandle      = rocketMeshHandle,
            bulletMeshHandle      = bulletMeshHandle,
            bulletHeavyMeshHandle = bulletHeavyMeshHandle,
            asteroidMeshGrey1     = asteroidMeshGrey1,
            asteroidMeshGrey2     = asteroidMeshGrey2,
            asteroidMeshHeavy     = asteroidMeshHeavy,
            asteroidMeshExplosive = asteroidMeshExplosive,
            asteroidMeshEnergy    = asteroidMeshEnergy,
        )
        // game → engine adapter. Reads the runner's collections (asteroids,
        // effects, flashes, fireballs, particles) and per-frame scalars
        // (centralTurretAngle, shieldHp, reloadProgress) to compose one
        // `SceneFrame` per call to `assemble(...)`.
        sceneAssembler = SceneAssembler(
            asteroids          = missionRunner.asteroids,
            effects            = missionRunner.effects,
            flashes            = missionRunner.flashes,
            fireballs          = missionRunner.fireballs,
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
        val frame = sceneAssembler.assemble(
            centralTurretAngle = missionRunner.centralTurretAngle,
            shieldHp           = missionRunner.shieldHp,
        )
        engineView.scene              = frame.scene
        engineView.plasmaBillboards   = frame.plasmaBillboards
        engineView.translucentObjects = frame.translucentObjects
        engineView.additiveObjects    = frame.additiveObjects
        engineView.beams              = frame.beams
        engineView.particleBatches    = frame.particleBatches
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
            Screen.Menu          -> mountMenu(root)
            Screen.MissionSelect -> mountMissionSelect(root)
            is Screen.WeaponSelect -> mountWeaponSelect(root, top.mission)
            Screen.Base          -> mountBase(root)
            Screen.Win           -> mountWin(root)
            Screen.Lose          -> mountLose(root)
            Screen.Playing       -> mountPlaying()
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
            onClose = { finish() },
            onClick = { enterScreen(Screen.MissionSelect) },
        )
        setMenuBody(view, "Всего металла: ${progressRepo.current.metal}")
        addMenuButton(view, "База") { enterScreen(Screen.Base) }
        mountAt(root, view)
        buildScene()
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
            this, progressRepo.current,
            onPurchase = { type, cost ->
                progressRepo.update { UpgradeCatalog.applyPurchase(it, type, cost) }
                renderTop()   // refresh in place
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
        val nextIdx = Missions.ALL.indexOf(mission) + 1
        if (nextIdx in Missions.ALL.indices) {
            buttons += "Следующая миссия" to {
                resetStack(Screen.Playing)
                missionRunner.startMission(Missions.ALL[nextIdx], missionRunner.currentWeapon)
            }
        }
        buttons += "Повторить"       to {
            resetStack(Screen.Playing)
            missionRunner.startMission(mission, missionRunner.currentWeapon)
        }
        buttons += "База"            to { enterScreen(Screen.Base) }
        buttons += "К выбору миссий" to { resetStack(Screen.Menu, Screen.MissionSelect) }
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
            "База"             to { enterScreen(Screen.Base) },
            "К выбору миссий"  to { resetStack(Screen.Menu, Screen.MissionSelect) },
        )
        val view = buildEndOfMission(
            context    = this,
            title      = "БАЗА РАЗРУШЕНА",
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
