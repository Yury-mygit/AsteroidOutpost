# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

This project is **Asteroid Outpost** (an Android tower-defense / roguelite, design in `ROADMAP.md`, in Russian) built on top of an existing native Vulkan engine that was originally a separate project called **g3**. The engine source set was copied verbatim from `D:\g3\app\src\main\` into `app/src/main/`. As a consequence:

- All Kotlin code lives under **`com.example.g3.*`** (not `com.example.asteroidoutpost`). The `applicationId` and `namespace` in `app/build.gradle.kts` are also `com.example.g3` to match the JNI symbols (`Java_com_example_g3_*` in `cpp/android/EngineJni.cpp`). **Do not rename the package piecemeal** — the C++ JNI bridge is name-mangled against this exact path.
- The Gradle root project is still `"Asteroid(Outpost"` (with the stray `(`), but the strings.xml `app_name` is still `g3` and the theme is `Theme.G3`. These will need to be rebranded when the game is closer to release; for now the engine assumes them.
- The g3 engine ships a demo scene with allied/enemy fighters and stations — none of the Asteroid Outpost gameplay (turrets, waves, elemental synergies, swipe cannon) exists yet. M1 of the roadmap is the next layer to build on top.
- Engine is **landscape-only** (`android:screenOrientation="sensorLandscape"`). The roadmap originally pitched a portrait 1080×2400 layout — that's an open contradiction to resolve before touching the UI layer.

`ROADMAP.md` is the source of truth for *what* the game should become; respond in Russian when discussing it unless asked otherwise.

## Build & run

```bash
./gradlew assembleDebug              # build debug APK (also invokes CMake for native libs)
./gradlew installDebug               # build + install on connected device/emulator
./gradlew test                       # JVM unit tests (none currently — see "Tests" below)
./gradlew connectedAndroidTest       # instrumented tests on device
./gradlew lint
./gradlew clean
```

Use `gradlew.bat` on Windows shells. Native build runs through CMake (`app/src/main/cpp/CMakeLists.txt`) — first build downloads the NDK if missing and is slow.

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

- AGP **9.1.1**, Kotlin **2.2.10**
- `compileSdk = 36`, `minSdk = 28`, `targetSdk = 36`, **Java 17** source/target
- NDK ABIs: `arm64-v8a`, `x86_64` only (no 32-bit)
- C++20 (`-std=c++20`), Vulkan, no `game-activity` lib
- AppCompat 1.7.1 (no Compose anywhere — the original Compose scaffold was removed when g3 was copied in)
- Toolchain JDK 21 (`gradle/gradle-daemon-jvm.properties`)

## Architecture

```text
Kotlin (UI, lifecycle, AI, scene state, asset loading)
    -> JNI via EngineJni.kt / cpp/android/EngineJni.cpp
C API (cpp/engine/engine_api.h — the only crossing point)
    ->
C++ Vulkan engine -> libstationcore.so
```

- Kotlin owns gameplay, UI, selection, object identity, asset bytes. The engine is "dumb": each frame Kotlin submits a draw list (`beginScene → drawMesh* → endScene → renderFrame`).
- The engine knows nothing about missions, tactics, or game rules.
- All `.cpp` files must be listed in `cpp/CMakeLists.txt` — adding a source file without updating CMake silently fails to link.

### Kotlin layers (`java/com/example/g3/`)

| Package | Role |
|---|---|
| (root) | Activities, `EngineView` (SurfaceView + render thread + touch), `Scene.kt`, `EngineJni.kt`, overlay views |
| `sim/` | **Simulation runtime** — `SimulationWorld` owns ships, projectiles, explosions, world objects. `SceneAdapter` turns snapshots into render lists. `WeaponController`, `ShipMotor`, `ShipController` translate intents into state. |
| `intelligence/` | **Tactical command layer** — `StationAI` is the rule-based commander emitting `ShipIntent`s. `CommandClassifier` loads `assets/ml/model.json` for Russian voice/text commands. `FleetRegistry` maps wing names (Alpha=0,1,2; Beta=3,4) to ship ids. |
| `ai/` | Mixed: `MissionController` still drives the fly-around path; `ShipAgent`, `FormationDef`, `SlotAssigner` support it. Not pure legacy — don't delete blindly. |
| `mission/` | Mission abstractions (`AttackMission`, `FlyAroundMission`). |

### Per-frame tick flow

```text
MainActivity.ensureTicking()
  → StationAI.tick(dt, simWorld)
  → simWorld.update(dt, intents)
  → StationAI.onEvents(events, simWorld)
  → optional MissionController.update(dt)
  → SceneAdapter.sceneFromWorld(...)
  → SceneAdapter.plasmaBillboards(...)
```

### Vulkan pipelines (`cpp/engine/VulkanContext.cpp`)

`system` (opaque meshes), `frame` (additive depth-tested selection frames), `star` (point field), `plasma` (additive camera-facing billboards), `billboard` (normal billboards).

Selection frames are *rectangular UI markers*, not mesh contours — Kotlin sends gameplay shape points, native code projects them and fits the frame mesh around the screen-space bounds.

### Demo scene (will be replaced when M1 starts)

ids `0..4` allied fighters near `(15, 10, 0)`; id `5` allied station `(0,-2,-5)`; id `6` enemy station `(0,150,0)`; ids `7..11` enemy fighters at `y=120`.

## Adding an engine API function

1. Declare + implement in `cpp/engine/engine_api.h` and `engine_api.cpp`.
2. Add a JNI wrapper in `cpp/android/EngineJni.cpp` (symbol must be `Java_com_example_g3_EngineJni_<funcName>`).
3. Add a matching `external fun` in `EngineJni.kt`.
4. If you added a new `.cpp` file, also list it in `cpp/CMakeLists.txt`.

## Tests

**The g3 source copy intentionally excluded `app/src/test/` and `app/src/androidTest/`** — the original g3 project has ~9 unit tests under `D:\g3\app\src\test\java\com\example\g3\` (covering `sim/`, `mission/`, `intelligence/`) plus an instrumented example. They are not in this repo. If you need them, copy them over from `D:\g3\app\src\test\` and `D:\g3\app\src\androidTest\` — they should compile against the engine code as-is since the package paths match.

## Project quirks

- Root project name in `settings.gradle.kts` is literally `"Asteroid(Outpost"` (stray `(`). Leave it.
- `_ABOUT.md` files exist in `cpp/`, `cpp/engine/`, `cpp/android/`, and `java/com/example/g3/` and document each layer. Update them when you change the layer's responsibilities (rule 5 from the original g3 conventions).
- Don't make architectural changes to the Kotlin↔C boundary or Vulkan pipelines without discussing first — they are the most expensive parts to get wrong.
- `RECORD_AUDIO` permission is declared because of the speech-input feature (`btnMic` → `CommandClassifier`).
