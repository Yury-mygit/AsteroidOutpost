package com.example.g3

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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.g3.ai.MissionController
import com.example.g3.ai.OrbitTarget
import com.example.g3.ai.Vec2
import com.example.g3.intelligence.CommandClassifier
import com.example.g3.intelligence.FleetRegistry
import com.example.g3.intelligence.FleetUnit
import com.example.g3.intelligence.PlayerCommand
import com.example.g3.intelligence.StationAI
import com.example.g3.sim.CombatEvent
import com.example.g3.sim.CombatStats
import com.example.g3.sim.SceneAdapter
import com.example.g3.sim.ShipState
import com.example.g3.sim.SimulationWorld
import com.example.g3.sim.Team
import com.example.g3.sim.WorldObject
import com.example.g3.sim.WorldObjectType

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
    private val selectedShipIds: MutableSet<Int> = linkedSetOf()
    private var selectedTargetId: Int = -1

    private val shipFormation = listOf(
        Triple(30.0f, 22.0f,  0.00f),
        Triple(32.5f, 23.5f,  0.15f),
        Triple(27.5f, 23.5f, -0.15f),
        Triple(31.2f, 20.0f,  0.05f),
        Triple(28.8f, 20.0f, -0.05f),
    )

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
        private val ALLIED_STATION_POS   = Vec2(0f,   -2f)
        private val ENEMY_STATION_POS    = Vec2(0f,  150f)
        private const val PATROL_ORBIT_RADIUS       = 18f
        private const val ENEMY_PATROL_ORBIT_RADIUS = 24f
        /** Centroid of the initial ship formation — the fleet rally point. */
        private val FLEET_RALLY_CENTER  = Vec2(30f, 22f)
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

        cameraJoystick.onCommand = { command ->
            when (command.mode) {
                CameraJoystickMode.PAN -> {
                    if (command.x != 0f || command.y != 0f)
                        engineView.engine.panCamera(command.x * JOYSTICK_PAN_STEP, command.y * JOYSTICK_PAN_STEP)
                    if (command.zoom != 0f)
                        engineView.engine.zoomCamera(1f - command.zoom * JOYSTICK_ZOOM_FACTOR_STEP)
                }
                CameraJoystickMode.ORBIT -> {
                    if (command.x != 0f || command.y != 0f) {
                        val yaw   = command.x * JOYSTICK_ORBIT_STEP
                        val pitch = command.y * JOYSTICK_ORBIT_STEP
                        engineView.engine.orbitCamera(yaw, pitch)
                        applyCameraOrbit(yaw, pitch)
                    }
                    if (command.roll != 0f) {
                        val roll = command.roll * JOYSTICK_ROLL_STEP
                        engineView.engine.rollCamera(roll)
                        applyCameraRoll(roll)
                    }
                }
                CameraJoystickMode.RESET -> {
                    engineView.engine.resetCamera()
                    resetCameraMatrix()
                }
            }
        }

        engineView.onCameraOrbited = { yaw, pitch -> applyCameraOrbit(yaw, pitch) }
        engineView.onCameraRolled  = { roll -> applyCameraRoll(roll) }
        engineView.onCameraReset   = { resetCameraMatrix() }
        engineView.onTap = { x, y -> selectAt(x, y) }
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
            if (stationMeshHandle == 0L) showStatus("Station load failed")
        } catch (e: Exception) {
            showStatus("Station not found: models/station.glb")
        }
    }

    private fun loadSelectionFrames() {
        val thinBytes = assets.open("models/selection_frame_thin.gltf").readBytes()
        val boldBytes = assets.open("models/selection_frame_bold.gltf").readBytes()
        selectionFrameThinHandle = engineView.engine.loadMesh(thinBytes)
        selectionFrameBoldHandle = engineView.engine.loadMesh(boldBytes)
        val enemyThinHandle = engineView.engine.loadMeshColored(thinBytes, ENEMY_FRAME_R, ENEMY_FRAME_G, ENEMY_FRAME_B)
        val enemyBoldHandle = engineView.engine.loadMeshColored(boldBytes, ENEMY_FRAME_R, ENEMY_FRAME_G, ENEMY_FRAME_B)
        engineView.highlightMeshes = HighlightMeshes(
            thin      = selectionFrameThinHandle,
            bold      = selectionFrameBoldHandle,
            enemyThin = enemyThinHandle,
            enemyBold = enemyBoldHandle
        )
        if (selectionFrameThinHandle == 0L || selectionFrameBoldHandle == 0L)
            showStatus("Selection frame load failed")
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
        engineView.scene = sceneAdapter().staticScene()
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
        val micEnabled  = prefs.getBoolean(SettingsActivity.KEY_MIC_ENABLED, true)
        val musicVolume = prefs.getFloat(SettingsActivity.KEY_MUSIC_VOLUME, 0.25f)
        bgMusic?.setVolume(musicVolume, musicVolume)
        btnMic.visibility = if (micEnabled) View.VISIBLE else View.GONE
        if (!micEnabled) stopListening()
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
            missionThread  = HandlerThread("MissionThread").also { it.start() }
            missionHandler = Handler(missionThread!!.looper)
            scheduleMissionTick()
        }
    }

    private fun stopTickingIfIdle() {
        if (!stationAI.hasActiveTasks() && !enemyAI.hasActiveTasks() && missionCtrl == null && !buildActive) {
            missionThread?.quitSafely()
            missionThread  = null
            missionHandler = null
        }
    }

    private fun scheduleMissionTick() {
        missionHandler?.postDelayed({
            val dt = TICK_MS / 1000f
            val intents = stationAI.tick(dt, simWorld) + enemyAI.tick(dt, simWorld)
            val events  = simWorld.update(dt, intents)
            stationAI.onEvents(events, simWorld)
            enemyAI.onEvents(events, simWorld)

            // MissionController overrides ship positions after simWorld update
            missionCtrl?.let { mc ->
                val done = mc.update(dt)
                simWorld.teleportShips(mc.toPositions().associate { it.id to (it.position to it.heading) })
                if (done) {
                    missionCtrl = null
                    runOnUiThread {
                        btnFlyAround.isActivated = false
                        stopEngineSound()
                        showStatus("Облёт завершён")
                    }
                }
            }

            if (buildActive) {
                buildProgress = (buildProgress + dt / BUILD_DURATION_SEC).coerceAtMost(1f)
                selectionOverlay.setBuildProgress(ALLIED_STATION_ID, buildProgress)
                if (buildProgress >= 1f) {
                    buildActive = false
                    runOnUiThread { onFighterBuilt() }
                }
            }

            playCombatEvents(events)
            handleDestroyedObjects(events)
            val worldObjects = simWorld.worldObjectSnapshot()
            latestHealthBars = computeHealthBars(worldObjects, simWorld.shipSnapshot())
            val adapter = sceneAdapter(worldObjects)
            engineView.scene            = adapter.sceneFromWorld(simWorld)
            engineView.plasmaBillboards = adapter.plasmaBillboards(simWorld)
            if (stationAI.hasActiveTasks() || enemyAI.hasActiveTasks() || missionCtrl != null || buildActive) scheduleMissionTick()
            else stopTickingIfIdle()
        }, TICK_MS)
    }

    // ---------------------------------------------------------------------------
    // Scene data builders
    // ---------------------------------------------------------------------------
    private val enemyFormation = listOf(
        Triple(10f, 120f, Math.PI.toFloat()),
        Triple(20f, 120f, Math.PI.toFloat()),
        Triple(30f, 120f, Math.PI.toFloat()),
        Triple(40f, 120f, Math.PI.toFloat()),
        Triple(50f, 120f, Math.PI.toFloat()),
    )

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

    private fun buildInitialWorldObjects(): List<WorldObject> = listOf(
        WorldObject(id = 5, team = Team.ALLY,  objectType = WorldObjectType.STATION, position = Vec2(0f,  -2f), z = -5f, combatStats = CombatStats.station()),
        WorldObject(id = 6, team = Team.ENEMY, objectType = WorldObjectType.STATION, position = Vec2(0f, 150f), z =  0f, combatStats = CombatStats.station()),
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
