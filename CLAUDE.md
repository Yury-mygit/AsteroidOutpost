# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

**Asteroid Outpost** is a 2D side-view shoot-'em-up for Android, built on top of an existing native Vulkan engine that was originally a separate project called **g3**. The engine source set was copied from `D:\g3\app\src\main\` into `app/src/main/`, then heavily repurposed and rebranded.

> **Active milestone plan & change log live in `ROADMAP.md`** (the project's living doc). Source spec for the active refactor wave is `idea.txt` — 12 tasks grouped into milestones M1–M7. **M1–M6 are complete as of 2026-05-04**; M7 (VFX polish pass) landed 2026-05-04 — turret/weapon VFX (muzzle flash, bullet trails, AoE explosion ring), tap-spam cooldown fix + reload bar above the central turret. **Engine wave E1** (per-vertex RGBA + alpha-blend pipeline + procedural-mesh API + soft-disk background nebulae) landed 2026-05-04 — first time we touched the engine since fork. **Engine wave E2** (radial soft-fade for plasma billboards + annular half-membrane shield dome via the translucent pipeline) landed 2026-05-05. Treat any drift between this CLAUDE.md and ROADMAP.md as a sign one wasn't updated — ROADMAP is the source of truth for what's done and what's planned.

### What the game is

Portrait-orientation arcade game.

- **Central turret** — tall red rectangle (~3× the side turrets) sitting at the centre of a wide grey platform at the bottom of the screen. The player drags on the screen to aim: the turret's barrel smoothly rotates toward the touch point. While the finger is held down, it fires continuously along the aim direction (hold-to-fire) at a fixed rate. The turret does not move along the platform; aim Z is clamped non-negative so you can't shoot down through the platform.
- **Two stationary blue side turrets** flank the central turret on the platform. Each auto-aims at the nearest asteroid and fires at an angle. Their damage is ~50% of the central turret — they're support, the player carries the fight with the central turret. Bullets are oriented along their velocity vector.
- **Grey asteroid squares** spawn at the top at random X positions, falling slowly downward. Bullets damage them; asteroids that reach the platform damage the platform and disappear.
- **Wave-based missions.** Each mission is a list of waves; a wave spawns N asteroids at a fixed interval, ends when all asteroids are gone, then a 2-sec break, then the next wave. Five missions teach one mechanic each (Учебная тревога → Быстрые цели → Тяжёлая угроза → Взрывная цепочка → Проверка базы); each mission's `WaveConfig.typeWeights` ramps up the new asteroid type so the lesson lands gradually. Numbers in `game/Missions.kt`.
- **Asteroid types** (M5, `game/AsteroidType.kt`): NORMAL (baseline), FAST (small, ×2 speed, low HP), HEAVY (big, ×3 HP, ×2 platform damage, slow), EXPLOSIVE (deals AoE damage on death), ENERGY (rare; on death triggers a 5-sec ×2 main-weapon damage buff via the single-slot buff system in `MainActivity`). Each `WaveConfig` carries a `typeWeights: Map<AsteroidType, Float>` — empty map = all NORMAL. Tinted `Asteroid_1.glb` mesh handles per type for visual distinction; size and platform damage scale with type multipliers.
- **Weapon select.** Before a mission starts, the player picks the central turret's weapon: **Автомат** (fast fire, low per-shot damage, single target) or **Тяжёлая пушка** (slow fire, ×3 damage, AoE splash on hit). Defined in `game/Weapon.kt::WeaponCatalog`. Selection is runtime-only (not yet persisted — M4).
- **Shield ability.** A button at the bottom-centre of the screen (diegetic — sits on top of the grey platform rectangle) activates a 3-second base shield with a 15-second cooldown. While active, asteroids that touch the platform are absorbed without damaging the base; a force-field dome renders over the base as a single **annular half-membrane mesh** (`buildShieldDome()` + `buildDomeMembraneMesh()` in `MainActivity`) drawn through the translucent pipeline — three concentric half-arcs with per-vertex alpha 0/peak/0 give a thin glowing silhouette and a transparent interior so the central turret remains visible inside. Subtle pulse + ~0.6s scale-collapse fade-out at end-of-duration. State machine `ShieldState { READY, ACTIVE, COOLING }` lives in `MainActivity`; constants are `DraftCombat.SHIELD_DURATION_SEC`/`SHIELD_COOLDOWN_SEC`.
- **Win** = all waves cleared. **Lose** = platform HP ≤ 0.
- **Meta-progression.** Each destroyed asteroid awards 1 metal; winning gives +20 bonus. Metal persists across launches. Spend metal in the **Upgrades** screen on three tracks: robot damage (10/15/22), base HP (+0/+50/+120 over mission baseline), turret damage (5/8/12), each with a 3-level cap.
- **Screens.** Main menu → Mission select → **Weapon select** → Game (with HUD: Score, HP, "Волна X/Y") → Win or Lose with stats and buttons (Next mission / Repeat / Upgrades / Mission select). Upgrades screen accessible from menu and from win/lose.

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

1. Shield: tick the `ShieldState` machine — count down ACTIVE → COOLING → READY, refresh the shield button on transitions and at integer-second boundaries (`shieldUiSecLast` throttle).
2. Aim: compute `targetAngle = atan2(aimTargetX − pivotX, max(0, aimTargetZ − pivotZ))`; smooth `centralTurretAngle` toward it (~16/sec exponential).
3. Hold-to-fire: `centralFireCooldown` counts DOWN every tick regardless of touch state. While `isTouching` and `centralFireCooldown <= 0`, fire one bullet from the muzzle (`pivot + dir * 2*HALF_H`) and reset cooldown to `currentWeapon.fireIntervalSec`. The independent-of-touch decrement is what stops tap-spam from bypassing the weapon's intended rate. Bullet damage = `effectiveMainWeaponDamage * weapon.damageMultiplier`; AoE-capable weapons stamp `aoeRadius`/`aoeDamage` onto the bullet.
4. Each side turret fires at the nearest asteroid (vector pointing to target).
5. Move bullets along their velocity vector; cull off-screen and on hit (apply per-bullet damage to asteroid HP, scaled by `activeBuffDamageMul`). On hit, if `bullet.aoeRadius > 0`, apply `aoeDamage` to other live asteroids within the radius and spawn a large flash. After the bullet pass, dead asteroids' on-death effects fire — EXPLOSIVE deals splash damage to neighbours within `EXPLOSIVE_AOE_RADIUS`; ENERGY arms the buff.
6. Move asteroids down at their per-asteroid speed (mission baseline × type multiplier captured at spawn).
7. Asteroid touches platform: if `shieldState == ACTIVE`, asteroid is absorbed (small flash, no HP loss); otherwise damage platform by the asteroid's `platformDmg` (HEAVY hits twice as hard), remove asteroid.
8. Wave control: spawn current wave's asteroids at intervals; when wave fully spawned and asteroids list is empty, start a 2-sec break or trigger win.
9. Build the scene (platform — blue tint while shield active, central turret, side turrets, asteroids, bullets, flashes) and submit.
10. Win/lose checks → `showWin()` / `showLose()` triggers + presentation overlays.

**Central turret rotation pivot.** The model rotates around `SceneObject` origin (its centre), but visually we want pivot at the *base* sitting on the platform. To get base-anchored rotation without a custom mesh, `buildScene()` offsets the SceneObject centre along the barrel direction: `centerX = pivotX + sin(angle) * HALF_H`, `centerZ = pivotZ + cos(angle) * HALF_H`. The base stays glued to `(pivotX, pivotZ)` for any angle.

### Vulkan pipelines (`cpp/engine/VulkanContext.cpp`)

Six pipelines, all driven by the same vertex/fragment shader pair (they differ only in blend / depth state):

- **`system`** — opaque meshes (platform, turrets, asteroids, bullets, reload bar). Depth-test on, depth-write on, no blending. The workhorse.
- **`frame`** — additive depth-tested selection frames. Disabled by Outpost (g3 reticle holdover).
- **`star`** — point-list star field. Visible behind the gameplay layer.
- **`plasma`** — additive camera-facing billboards (`ONE / ONE`). Used by Outpost for **all `Flash` VFX** (muzzle flash, bullet trails, AoE rings, asteroid hit). Fragment shader applies a radial soft-fade in model-space X-Z (E2.1) — quad corners go to alpha 0 so the visible glow inscribes the quad with no boxy silhouette. The fade is gated on `pc.tint.x ≥ 0.5`, which `VulkanContext::renderFrame` sets only when binding the plasma pipeline.
- **`billboard`** — normal camera-facing billboards. Unused by Outpost (no images yet).
- **`translucent`** — alpha-blend mesh pipeline (`SRC_ALPHA / ONE_MINUS_SRC_ALPHA`), depth-test on, **depth-write off**. Added in E1. Used by Outpost for the soft-disk background nebulae and the shield-dome half-membrane (E2.2). Per-vertex alpha controls transparency — the fragment shader passes `vColor.a` straight through. Render order in `renderFrame`: opaque → system billboards → **translucent** → plasma additive, so opaque gameplay correctly occludes geometry behind it (nebulae sit at y=+1, behind gameplay; the dome at y=−0.05, in front of platform/turrets so it draws over them).

Vertex format is now **position(vec3) + RGBA color(vec4) + normal(vec3)** (E1 widened color from vec3). Opaque code paths stamp A=1, so the change is invisible to existing meshes. The vertex shader additionally outputs `vLocalXZ = inPosition.xz` (E2.1) so the fragment shader has access to the model-space radius for the plasma soft-fade.

### Camera

Configured in `cpp/engine/Camera.cpp::reset()` for fixed side-view: target `(0, 0, 4)`, radius `22`, pitch `π/2` (rotation around X). Touch input on the engine surface is swallowed by `MainActivity`'s onTouchListener so the player's drag doesn't move the camera; it maps to `(aimTargetX, aimTargetZ)` in world coords for aiming the central turret.

### Coordinate convention

X = horizontal screen, Z = vertical screen, Y = depth (always 0 for game objects). Visible area at the target plane on a 1080×2400 device: X ∈ [−2.47, +2.47], Z ∈ [−1.49, +9.49]. Same world-units-to-pixels ratio horizontally and vertically, so equal `scaleX` and `scaleZ` produce a visually square shape.

## SceneObject extensions for Outpost

`Scene.kt`'s `SceneObject` was extended with:
- `scaleX`, `scaleY`, `scaleZ` (NaN means "fall back to uniform `scale`") — for stretching primitives.
- `rotationY` — rotation around world Y axis, used to orient bullets along their velocity vector.

`modelMatrix()` composes `T * Rz * Ry * S`. Default values are no-ops, so existing g3 code that only uses uniform `scale` and `rotationZ` is unaffected.

`EngineView` carries two scene lists each frame: `scene` (opaque, drawn through `drawMesh`) and `translucentObjects` (drawn through `drawTranslucentMesh` on the alpha-blend pipeline). Both are submitted from `MainActivity.buildScene()` via `submitScene(opaque, translucentObjects)`. Translucent objects use the same `SceneObject` type — only the routing differs.

Most Outpost geometry (platform, central turret, side turrets, bullets, reload bar, shield button backdrop) uses a single primitive: `app/src/main/assets/models/quad.gltf` — an X-Z plane unit quad with double-sided indices and white per-vertex colours, loaded with multiple tints (red / grey / blue / dome-blue). Asteroids use `Asteroid_1.glb` with per-type tints (NORMAL grey, HEAVY dark-red, EXPLOSIVE orange, ENERGY cyan).

**Background nebulae** are procedural soft-disk meshes built in `MainActivity.buildSoftDiskMesh()` via `engine.loadMeshRaw(verts, indices)`: a triangle fan with the centre vertex at A=1 and the rim vertices at A=0, so the alpha-blend pipeline draws a smooth circular fade with no visible edges. Five tinted disks (deep purple / cyan / dim crimson / twilight blue / warm dust) are placed across the playfield by `setupBackgroundNebulae()` and live in `engineView.translucentObjects`. They render between opaque scene and plasma billboards, so gameplay objects sit on top of them.

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

- `GameProgress` (data class) — persistent state: `metal`, three upgrade levels (`mainWeaponDamageLevel`, `baseHpLevel`, `sideTurretDamageLevel`), `highestMissionUnlocked`. Loaded once in `onCreate` from `SharedPreferences("outpost_progress_v2", MODE_PRIVATE)` via `ProgressRepository`. The `_v2` suffix is the M4 rename break — pre-M4 builds wrote to `outpost_progress`; that file is now ignored. Mutations go through `MainActivity.updateProgress { ... }` which copies + saves immediately.
- `MissionRun` (data class) — in-flight stats for the current run: asteroids destroyed, score, metal earned, win bonus, current wave display, total waves, mission name. Reset on each `startMission`.
