# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

**Asteroid Outpost** is a 2D side-view shoot-'em-up for Android, built on top of an existing native Vulkan engine that was originally a separate project called **g3**. The engine source set was copied from `D:\g3\app\src\main\` into `app/src/main/`, then heavily repurposed and rebranded.

### What the game is

Portrait-orientation arcade game.

- **Player robot** — small red rectangle on a wide grey platform at the bottom of the screen. Drag your finger across the screen to move it horizontally; release to stop. The robot fires bullets straight up at a fixed rate.
- **Two stationary blue turrets** sit on the platform near its left and right edges. Each auto-aims at the nearest asteroid and fires bullets at an angle, with lower damage than the robot. Bullets are oriented along their velocity vector.
- **Grey asteroid squares** spawn at the top at random X positions, falling slowly downward. Bullets damage them; asteroids that reach the platform damage the platform and disappear.
- **Wave-based missions.** Each mission is a list of waves; a wave spawns N asteroids at a fixed interval, ends when all asteroids are gone, then a 2-sec break, then the next wave. Three missions exist (Учебная тревога / Метеоритный поток / Тяжёлые астероиды) with progressively harder numbers.
- **Win** = all waves cleared. **Lose** = platform HP ≤ 0.
- **Meta-progression.** Each destroyed asteroid awards 1 metal; winning gives +20 bonus. Metal persists across launches. Spend metal in the **Upgrades** screen on three tracks: robot damage (10/15/22), base HP (+0/+50/+120 over mission baseline), turret damage (5/8/12), each with a 3-level cap.
- **Screens.** Main menu → Mission select → Game (with HUD: Score, HP, "Волна X/Y") → Win or Lose with stats and buttons (Next mission / Repeat / Upgrades / Mission select). Upgrades screen accessible from menu and from win/lose.

### Code layout

```
app/src/main/
├── AndroidManifest.xml          (portrait, AppCompat theme)
├── assets/
│   ├── models/                  ship.gltf, station.glb, quad.gltf, selection_frame_*.gltf
│   ├── shaders/                 compiled .spv (do not hand-edit)
│   ├── ml/                      g3 voice command model (unused by Outpost)
│   └── sound/                   g3 sounds (unused by Outpost)
├── shaders/                     GLSL source (.vert, .frag) — recompile after editing
├── res/                         AppCompat layouts, themes, mipmaps
├── cpp/
│   ├── android/EngineJni.cpp    JNI bridge
│   └── engine/                  Vulkan engine (Camera.cpp, VulkanContext.cpp, ...)
└── java/com/example/asteroidoutpost/
    ├── (root)                   MainActivity, EngineView, EngineJni, Scene, overlay views
    ├── game/                    Outpost gameplay & UI: GameProgress, MissionRun,
    │                            MissionConfig+Missions, UpgradeCatalog, OverlayFactory,
    │                            ProgressRepository, UiTheme + UiHelpers (sci-fi style)
    ├── sim/, intelligence/,     g3 simulation/AI/missions — present in source but
    │   ai/, mission/            BYPASSED at runtime (Outpost tick doesn't use them)
```

The g3 simulation, fleet AI, voice commands, missions, and orbit-camera controls are all left in-tree but unused. Don't expand them; new gameplay should land in `game/` and in `MainActivity`'s draft tick.

## Build & run

```bash
./gradlew assembleDebug              # build debug APK (CMake builds native libs)
./gradlew installDebug               # build + install on connected device/emulator
./gradlew clean                      # nuke build outputs
./gradlew lint
```

Use `gradlew.bat` on Windows shells. First build downloads the NDK if missing — slow.

There are **no tests** in this repo. The original g3 project has ~9 unit tests under `D:\g3\app\src\test\java\com\example\g3\` (covering its `sim/`, `mission/`, `intelligence/` packages); they would need their package declarations updated to `com.example.asteroidoutpost.*` to compile here.

## Shader compilation

`.glsl` sources live in `app/src/main/shaders/`. Compiled SPIR-V (`.spv`) lives in `app/src/main/assets/shaders/`. After editing any shader source, recompile:

```bash
cd app/src/main
glslc shaders/triangle.vert -o assets/shaders/triangle.vert.spv
glslc shaders/triangle.frag -o assets/shaders/triangle.frag.spv
```

Never edit files in `assets/shaders/` directly — they are build outputs.

## Toolchain & versions

Pinned in `gradle/libs.versions.toml`:

- AGP **9.1.1**, Kotlin **2.1.0**
- `compileSdk = 36`, `minSdk = 28`, `targetSdk = 36`, **Java 17** source/target
- Package: `com.example.asteroidoutpost`
- NDK ABIs: `arm64-v8a`, `x86_64` only (no 32-bit)
- C++20 (`-std=c++20`), Vulkan, no `game-activity` lib
- AppCompat 1.7.1 (no Compose anywhere)
- Toolchain JDK 21 (`gradle/gradle-daemon-jvm.properties`)

Note: although AGP 9.1.1 supports Kotlin 2.2.x, applying `kotlin.android` plugin alongside AGP 9 raises "Cannot add extension with name 'kotlin'" when both register the extension. Workaround used here: declare the plugin in `libs.versions.toml` but do **not** apply it in `app/build.gradle.kts` — AGP supplies the Kotlin extension itself.

## Architecture

```
Kotlin (UI, lifecycle, game state, scene assembly, asset loading)
    ↓ JNI via EngineJni.kt / cpp/android/EngineJni.cpp
C API (cpp/engine/engine_api.h — the only crossing point)
    ↓
C++ Vulkan engine → libstationcore.so
```

- Kotlin owns gameplay, UI, asset bytes. The engine is "dumb" — every frame Kotlin submits a draw list (`beginScene → drawMesh* → endScene → renderFrame`).
- The engine knows nothing about missions, scoring, or game rules.
- All `.cpp` files must be listed in `cpp/CMakeLists.txt` — adding a source file without updating CMake silently fails to link.
- JNI symbols are mangled into the package path: `Java_com_example_asteroidoutpost_EngineJni_<funcName>`. Renaming the package requires updating all of these symbols simultaneously (and the C++ side).

### Outpost runtime (in MainActivity)

Game state machine: `MENU / PLAYING / WON / LOST`. The tick handler runs only in `PLAYING`. Per tick:

1. Move robot toward touch X (only while finger is down).
2. Robot fires bullets straight up at fixed interval.
3. Each turret fires at the nearest asteroid (vector pointing to target).
4. Move bullets along their velocity vector; cull off-screen and on hit (apply per-bullet damage to asteroid HP).
5. Move asteroids down at mission's speed.
6. Asteroid touches platform → damage platform, remove asteroid.
7. Wave control: spawn current wave's asteroids at intervals; when wave fully spawned and asteroids list is empty, start a 2-sec break or trigger win.
8. Build the scene (platform, robot, turrets, asteroids, bullets) and submit.
9. Win/lose checks → `showWin()` / `showLose()` triggers + presentation overlays.

### Vulkan pipelines (`cpp/engine/VulkanContext.cpp`)

`system` (opaque meshes), `frame` (additive depth-tested selection frames — disabled by Outpost), `star` (point field — visible in background), `plasma` (additive camera-facing billboards — unused by Outpost), `billboard` (normal billboards — unused).

### Camera

Configured in `cpp/engine/Camera.cpp::reset()` for fixed side-view: target `(0, 0, 4)`, radius `22`, pitch `π/2` (rotation around X). Touch input on the engine surface is swallowed by `MainActivity`'s onTouchListener so the player's drag doesn't move the camera; it sets the robot's target X instead.

### Coordinate convention

X = horizontal screen, Z = vertical screen, Y = depth (always 0 for game objects). Visible area at the target plane on a 1080×2400 device: X ∈ [−2.47, +2.47], Z ∈ [−1.49, +9.49]. Same world-units-to-pixels ratio horizontally and vertically, so equal `scaleX` and `scaleZ` produce a visually square shape.

## SceneObject extensions for Outpost

`Scene.kt`'s `SceneObject` was extended with:
- `scaleX`, `scaleY`, `scaleZ` (NaN means "fall back to uniform `scale`") — for stretching primitives.
- `rotationY` — rotation around world Y axis, used to orient bullets along their velocity vector.

`modelMatrix()` composes `T * Rz * Ry * S`. Default values are no-ops, so existing g3 code that only uses uniform `scale` and `rotationZ` is unaffected.

All Outpost geometry (platform, robot, turrets, asteroids, bullets) uses a single primitive: `app/src/main/assets/models/quad.gltf` — an X-Z plane unit quad with double-sided indices and white per-vertex colours. It's loaded with three tints (red / grey / blue). Real models are a backlog item.

## Adding an engine API function

1. Declare + implement in `cpp/engine/engine_api.h` and `engine_api.cpp`.
2. Add a JNI wrapper in `cpp/android/EngineJni.cpp` (symbol must be `Java_com_example_asteroidoutpost_EngineJni_<funcName>`).
3. Add a matching `external fun` in `EngineJni.kt`.
4. If you added a new `.cpp` file, list it in `cpp/CMakeLists.txt`.

## Project quirks

- Root project name in `settings.gradle.kts` is literally `"Asteroid(Outpost"` (stray `(`). Cosmetic — leave it.
- `RECORD_AUDIO` permission is declared because of the inherited g3 voice-input feature; the corresponding mic UI is hidden by Outpost (`applySettings` forces `btnMic` to GONE). Permission can be dropped if voice never comes back.
- The full g3 `activity_main.xml` is still inflated, but every g3 control (commands drawer, build menu, ship card, mic, settings tab, axis indicator) is hidden in `MainActivity.onCreate`. Outpost UI (HUD text views + overlays) is added programmatically. When introducing real Outpost UI, consider replacing the layout entirely.
- `_ABOUT.md` files exist in `cpp/`, `cpp/engine/`, `cpp/android/`, and `java/com/example/asteroidoutpost/` and document each layer. Keep them in sync when changing layer responsibilities.
- Don't make architectural changes to the Kotlin↔C boundary or Vulkan pipelines without discussing first — they are the most expensive parts to get wrong.

## UI / styling

The game's "clean sci-fi arcade" look is centralised in two files:

- **`game/UiTheme.kt`** — single source of truth for the 5-colour palette (overlay bg, panel bg, accent red/blue/green, warning amber, text variants), border colours, corner radii (panel 18dp, card 14dp, button 12dp), paddings, gaps, button heights, and text sizes (title 28sp, heading 20sp, body 16sp, caption 13sp). Touch this file to retune the look — overlays must not hard-code values.
- **`game/UiHelpers.kt`** — programmatic helpers: `stylePanel(view, raised?)`, `buildCard(ctx, raised?)`, `buildPrimaryButton`, `buildSecondaryButton`, `buildDisabledButton`, `buildTitle/Heading/Body/Caption`, `buildPill(fill)`. All built with `GradientDrawable` (no XML drawables), styled from `UiTheme` constants.

`OverlayFactory` consumes both — it owns the layout structure of each overlay (menu / mission select / upgrades / win-lose / generic), but never sets a colour or padding directly. Adding a new overlay screen: build root via `makeOverlayRoot`, populate with `UiHelpers.build*` widgets, add gaps via `gapParams(ctx, dp)`.

## Memory & persistence

- `GameProgress` (data class) — persistent state: `metal`, three upgrade levels, `highestMissionUnlocked`. Loaded once in `onCreate` from `SharedPreferences("outpost_progress", MODE_PRIVATE)` via `ProgressRepository`. Mutations go through `MainActivity.updateProgress { ... }` which copies + saves immediately.
- `MissionRun` (data class) — in-flight stats for the current run: asteroids destroyed, score, metal earned, win bonus, current wave display, total waves, mission name. Reset on each `startMission`.
