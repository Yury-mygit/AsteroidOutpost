# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

**Asteroid Outpost** is a 2D portrait-orientation arcade game for Android, built on top of a native Vulkan engine forked from a separate project called **g3**. The engine source set was copied from `D:\g3\app\src\main\` into `app/src/main/`, then heavily repurposed and rebranded.

> **`ROADMAP.md` is the source of truth** for what's done, what's planned, and the rationale behind each milestone. Treat any drift between this file and ROADMAP as a sign one wasn't updated. The active refactor source spec is `idea.txt` (M1–M7 done long ago; current wave is M10–M15 + E13–E14, complete as of 2026-05-09).

**Branch state.** `style3` is the active branch — flips the camera behind the ship for a third-person space-shooter look (orbit `+0.6` instead of `-0.6`), asteroids fly at the bow from far +Z, the deck tilts toward the player, an interceptor-drone ability ships, and the mission-flow UI is graph-based. `style2` keeps the diagonal-from-above 3D look (camera in front of bow). `style1` is the original 2D side-view.

### What the game is

Wide grey platform at the bottom holds player installations (central turret + 2 side turrets + laser dome + rocket silo). Central turret + dome + silo are still procedural meshes per `TurretMeshBuilder`; **side turrets ship as authored `.glb` pair** (`Turret_Side_Body.glb` = base+tower fused, `Turret_Side_Cannon.glb` = barrel) — standard gltf convention (+Y up, -Z forward), `SceneAssembler` applies `rotationX = +π/2` to align with world axes. Asteroids spawn at top and fall through 5 typed variants (NORMAL / FAST / HEAVY / EXPLOSIVE / ENERGY); a 6th type `ENEMY_SHIP` exists for combat missions (uses asteroid mesh as placeholder).

- **Player input is priority + abilities**, not aiming. Central turret + side turrets auto-target highest-current-HP threat (sticky lock until kill). Tap an asteroid → **priority lock** for the player (green corner-bracket frame); re-tap to release. Priority lock is the master target for **laser + rockets + drones only** (`preferredTarget()` in MissionRunner) — central / side turrets stay on pure auto-aim either way. Auto-aim threats (those on course to hit shield/hull within `WEAPON_ENGAGEMENT_RANGE`) are outlined with red corner-brackets so the player sees what's actually dangerous.
- **Per-weapon firing arcs** (% of 180°): central cannon 90 / MG 80; side cannon 80 / MG 70 (future); laser 95; rocket 95. Out-of-arc lock = barrel sweeps to edge but doesn't fire.
- **Weapon select** on «Корабль» screen — three options in `game/Weapon.kt::WeaponCatalog`: **Автомат** (fast / low damage / single-target, warm cone-trefoil muzzle), **Пушка** (`WeaponId.HEAVY_CANNON`, slow / ×3 / AoE splash via `HeavyShellBehavior`, warm cone-trefoil muzzle), **Рельсотрон** (`WeaponId.RAILGUN`, slowest fire 2 s / ×5 per-shot / no AoE / `projectileSpeed = 108` (≈ 6× automatic) / lightning muzzle / blue tracer beam from origin to projectile via the existing beam pipeline). `WeaponId.HEAVY_CANNON` enum-name stayed historic; the user-facing rename to «Пушка» lives only in `displayName`/`description`.
- **Energy + abilities** (`energy ∈ [0, 100]`, regen 10/sec, HUD `⚡ N/100`): **Ракетный залп** (30 / cd 8s) — `RocketSilo` queues top-N targets, spring-launches one rocket at a time (ASCENDING → ignition flash → FLYING with steer + jet + smoke). **Лазерный удар** (50 / cd 18s) — 5-sec / 50-DPS continuous beam from the dome, blocked by first asteroid in line.
- **Shield** is a permanent HP-based barrier (`shieldHp: Float`, max 500). Shield button = hold-to-recharge: drain 50 energy/sec, refill 200 HP/sec. Asteroids break on the arch superellipse (n=4) when within `±SHIELD_ARCH_HALF_W`; damage routes through shield first (full / partial+overflow / pass-through). While recharging, incoming shield damage is reduced 20% and cyan tangential sparks run along the arch. **Enemy ships also have a shield buffer** (`Asteroid.shieldHp` / `shieldHpMax`; ENEMY_SHIP spawns with shield = ½ HP). All damage applications route through `WeaponEffectContext.damageAsteroid` which drains shield first; the in-world HP-bars show cyan shield bar above the green structure bar for shielded targets.
- **Missions** — two flavours in `Missions.ALL`: route-mode (campaign 1-5 — five pre-placed asteroid corridors `MissionRoutes.CAMPAIGN_1..5` of growing length/density; each corridor onboards one new type: NORMAL → +FAST → +HEAVY/ENERGY → +EXPLOSIVE → all five) and **combat missions** (missions 7/8 — `enemyShipSpawns: List<EnemyShipSpawn>` describes N enemy ships that materialise after delays and hold station 20 units ahead of the player, firing `EnemyBolt`s; ENEMY_SHIP is a special AsteroidType reusing auto-aim / tap-pick / damage pipeline). The old wave-based mode (asteroids falling onto a stationary platform) is gone — the player now always cruises forward at `SHIP_CRUISE_SPEED`. Combat missions surfaced through `RandomMissionsOverlay`.
- **Meta** — metal currency persists in `SharedPreferences("outpost_progress_v2")`. Three upgrade tracks × 3 levels in `game/UpgradeCatalog.kt`.
- **Screen flow** (style3): menu → mission hub (Кампания / Случайные) → campaign graph (5 numbered colour-coded circles) → mission detail → game (HUD: Score / HP / Волна X/Y) → win/lose. Weapon is no longer asked per mission; it's persisted on the «Корабль» screen (formerly «База») via `GameProgress.selectedWeaponId` and used directly when the mission starts.
- **Drones ability** (`AbilityId.DRONES`, `combat/Drone.kt`): tap the third action button to launch 4 interceptors from under the ship. **Thrust-based physics** — spawn with zero velocity, apply constant `DRONE_THRUST` toward `preferredTarget()` each tick, capped at `DRONE_SPEED`. Inertia handles overshoot / reverse naturally (light-spacecraft feel; ~0.83s ramp-up, ~1.7s 180° flip). Fire a green continuous laser via a `Beam` whose source/aim closures resolve back to the drone each tick; re-pick target every frame from `preferredTarget()` so the swarm follows the player's priority lock. Lifetime 10s, beam ticks AFTER drones in `MissionRunner.tick`. Mesh: `art/ship.gltf`, tinted red-orange.

**Debug overlays** (axes-gizmo + per-asteroid labels) are gated by `DebugSettings` (persisted toggle + label-mode picker in `SettingsOverlay`). Default off — labels and axes only render after the player explicitly enables them. Reactive: flipping the toggle in Settings calls `applyDebugVisibility()` immediately.

**Server API integration** (work in progress as of 2026-05-12). The app talks to `https://api.g4.raftforge.art/api/v1` for: device-token auth, mission catalog (list + details), progress sync, telemetry stream. Full contract in `docs/api/API.md` + `docs/api/openapi.yaml`. Kotlin client in `app/src/main/java/com/example/asteroidoutpost/net/` (ApiClient + service classes). Server itself built by a separate agent. Currently only auth is wired (background bootstrap in `MainActivity.onCreate`); missions / progress / telemetry endpoints have stub services awaiting wire-up to screens / runner. Offline-fallback to bundled `Missions.ALL` and SharedPreferences when server is unreachable.

**Motion blur is disabled** (`post.frag` is a pure passthrough). The E10.4 5×5 velocity-dilation step leaked moving-asteroid velocity into adjacent static-turret pixels and produced visible shimmer that mesh / prev_model fixes couldn't address. Pipeline + offscreen colour/velocity attachments + `prev_model` Kotlin-side are still wired so it's easy to revive — but doing so cleanly requires per-object dilation boundaries (stencil mask on static surfaces). See `feedback project_motion_blur_disabled.md`.

Numbers, type tables, and gameplay rationale live in ROADMAP §Концепция.

### Code layout

```
app/src/main/
├── AndroidManifest.xml          (portrait, AppCompat theme)
├── assets/
│   ├── models/                  Asteroid_*.glb, Bullet*.glb, quad.gltf, ship.gltf (legacy)
│   ├── shaders/                 compiled .spv (do not hand-edit)
│   ├── ml/                      g3 voice command model (unused — leftover, no callers)
│   └── sound/                   g3 sounds (unused — leftover, no callers)
├── shaders/                     GLSL source (.vert, .frag) — recompile after editing
├── res/                         AppCompat layouts, themes, mipmaps
├── cpp/
│   ├── android/EngineJni.cpp    JNI bridge
│   └── engine/                  Vulkan engine (Camera.cpp, VulkanContext.cpp, ...)
└── java/com/example/asteroidoutpost/
    ├── (root)                   MainActivity (UI shell), EngineView, EngineJni, Scene
    ├── sim/, intelligence/,     g3 source files — STILL on disk but no live callers
    │   ai/, mission/            (the g3 dead-code cleanup deleted every reference from
    │                            MainActivity; folders persist with no entry points and
    │                            can be removed wholesale when convenient)
    └── game/                    Outpost code, organised by responsibility:
        ├── (root)               MissionConfig+Missions, UpgradeCatalog, GameProgress,
        │                        ProgressRepository (owns persistent state),
        │                        Ability+AbilityCatalog, AbilitySlot, Weapon+WeaponCatalog,
        │                        AsteroidType, OverlayFactory, UiTheme, UiHelpers,
        │                        SceneAssembler (game → engine adapter)
        ├── combat/              Asteroid, Effects (Projectile/Beam/ProjectileBehavior +
        │                        3 impls + WeaponEffectContext), Vfx (Flash/Fireball/
        │                        Particle), VfxSpawner, Particles (tick/pack helpers),
        │                        AutoAim, Combat (DraftCombat constants)
        ├── content/             MeshBuilder, Meshes (procedural turret/silo/laser/rocket/
        │                        shield-arch/fireball/cone meshes), Textures (smoke/debris)
        └── ui/                  HudView (all in-game HUD widgets + animations),
                                 IconDrawables (V-shield, rocket, laser-cuts-asteroid)
```

The g3 source files (`sim/`, `intelligence/`, `ai/`, `mission/`) and the `assets/ml/` + `assets/sound/` directories are leftover from the fork and have no live callers. Remove wholesale when convenient; nothing in the Outpost runtime depends on them.

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

### Architecture — game ↔ engine boundary

The engine knows zero game types. It consumes only `SceneObject` / `BillboardDraw` / `BeamDraw` / `ParticleBatchKt` per frame. The translator is **`game/SceneAssembler.kt`** — a class that reads game state (asteroids, projectiles, beams, flashes, fireballs, particles) by reference and produces a `SceneFrame` snapshot for the engine. Reusing the engine for another game (g3 ships) means writing a different `SceneAssembler`; the engine stays untouched.

The same boundary is enforced at the weapon-effect layer: `Projectile`/`Beam`/behaviour classes are top-level in `game/combat/Effects.kt` and receive a `WeaponEffectContext` (asteroids list + VfxSpawner) per tick. They never reference Activity or other game-orchestration state.

### Outpost runtime (split across MainActivity + game/)

State + game logic is dispersed across `MainActivity` (UI shell, lifecycle, asset loading, overlay flow), the `game/combat/` subsystems (effect ticking, VFX spawning, target selection, particle pools), and `game/SceneAssembler.kt` (per-frame draw-list assembly). The **per-tick orchestrator still lives in MainActivity** as `scheduleDraftTick` — extracting it into a dedicated `MissionRunner` is the next planned refactor (see ROADMAP §Refactor wave 2026-05-09).

Game state machine: `MENU / PLAYING / WON / LOST`. The tick handler runs only in `PLAYING`, on a `HandlerThread("DraftTickThread")` (separate from UI thread — UI-thread mutations to shared lists go through `missionHandler?.post { ... }` to avoid CME). Per tick:

1. Shield: if recharging and energy > 0 and HP < max, drain energy and refill shield-HP (4× ratio).
2. Energy regen + ability cooldowns; UI throttled on integer-second changes via `hud.refreshEnergy/refreshAllAbilities`.
3. Auto-aim: `centralTurretTarget()` returns priority-lock (player tap), else highest-current-HP live asteroid (tiebreak nearest), else null. Sticky. Helpers in `game/combat/AutoAim.kt`.
4. Auto-fire on locked target when cooldown ≤ 0 and `aimAligned`. Side turrets fire at their nearest in-arc asteroid independently. VFX via `vfx.spawnMuzzleBlast` etc.
5. Tick `effects: List<WeaponEffect>` polymorphically with `WeaponEffectContext`: `Projectile` (with `behaviour: ProjectileBehavior` strategy — plain / heavy shell / homing rocket) or `Beam` (closure source/aim selector + optional `canEngage` arc gate). Each `tick(dt, ctx) → consumed`. `Projectile.tick` uses **swept (segment-vs-AABB) collision** — required because Рельсотрон's snарad (`projectileSpeed = 108`) covers ~1.7 ед per tick at 60 fps, more than the asteroid radius — point-sample would tunnel. Slab test picks the earliest hit, projectile is snapped to the impact point before `onImpact`.
6. Move asteroids; collision with shield arch (superellipse Z(x)) routes damage through `shieldHp` first, overflow to platform. Damage triggers `hud.pulseBaseDamage()`.
7. Route progression (campaign): cursor walks `MissionRoute.asteroids` (sorted by `absY`) and materialises placements when the ship gets within `ROUTE_SPAWN_DEPTH` ahead of them. Win when `shipPosY >= route.endY` and no live asteroids remain. The legacy wave-spawn path is still in the runner but no campaign mission carries `waves` anymore (`asteroidSpeed = 0f` in all of them); could be revived by setting `route = null` and populating `waves` again.
8. `buildScene()` (thin wrapper) calls `sceneAssembler.assemble(reloadProgress, centralTurretAngle, shieldHp)` and copies the resulting `SceneFrame`'s six lists onto `engineView`.
9. Win/lose checks.

**Central turret rotation pivot.** Handled inside SceneAssembler — `buildScene()` no longer cares. The barrel mesh is authored with origin at the pivot and extends along +Z, so a `SceneObject` rotation around Y pivots in place without offset tricks.

**Threading.** Tick runs on `DraftTickThread`, button onClick / onTouch handlers run on UI thread. Direct list-mutations from UI thread would race the tick's iterator (CME). The pattern: ability activations and beam spawns from UI-thread touch handlers post their effect through `missionHandler?.post { ... }` so the spawn lands between two ticks, atomic with the rest of the simulation. HUD refreshes from the tick are routed via `runOnUiThread { hud.refresh*() }`.

### Vulkan pipelines (`cpp/engine/VulkanContext.cpp`)

Eleven pipelines. Most share `triangle.vert`/`triangle.frag` and differ only in blend / depth state. Particles, post-process, and beams have their own shader pairs.

- **`system`** — opaque meshes (platform, turrets, asteroids, projectiles, reload bar, HP-bars). Depth-test on, depth-write on, no blending. The workhorse.
- **`frame`** — additive depth-tested selection frames. Disabled by Outpost.
- **`star`** — point-list star field.
- **`plasma`** — additive camera-facing billboards (`ONE / ONE`), **depth test OFF** (always-overlay). All `Flash` VFX (muzzle blast cones, asteroid hit / death / shield absorb flashes). Supports per-draw `rotation` (around local Y) and non-uniform `scaleH/scaleV`. The `pc.tint.x ≥ 0.5` shader branch adds: radial soft-fade in model-space X-Z, radial heat-ramp (warm core → orange edge), FBM turbulence (animated by `pc.time`), and per-billboard tint via `pc.plasmaColor`. Sub-mode `pc.tint.y ≥ 0.5` (with seed in `pc.tint.z`) draws a procedural lightning bolt — used by the railgun muzzle effect.
- **`billboard`** — normal camera-facing billboards. Unused.
- **`translucent`** — alpha-blend mesh pipeline (`SRC_ALPHA / ONE_MINUS_SRC_ALPHA`), depth-test on, depth-write off. Background nebulae and the shield arch. `nebulaAlphaMod()` / `hexAlphaMod()` modulate alpha when `pc.tint.y/z` material flags are set (NEBULA = 1, HEX = 2). Material chosen per-draw via `EngineJni.drawTranslucentMesh(handle, mat4, material)`.
- **`additive`** — additive-blend pipeline for arbitrary 3D meshes (`ONE / ONE`, depth-test on read-only). 3D fireball explosions at AoE events. Two fragment branches under `pc.tint.w`: plain additive (`>= 0.5`) or fire material (`>= 1.5`, uses `abs(vNormal.y)` Fresnel + heat-ramp + FBM — project-specific shortcut, only valid under the fixed pitch=π/2 camera; would need a real view direction for g3's moving camera).
- **`particleAdditive` / `particleAlpha`** — instanced particle pipelines. Own `particle.vert`/`.frag` with per-instance binding 1 (8 floats stride: pos3 + size1 + rgba4). Additive (sparks, embers; ONE/ONE depth-off) vs alpha (smoke, debris; SRC_ALPHA depth read-only). Pools of 4096 particles each, persistent-mapped, `drawParticles` API ships a whole pool in one batched call.
- **`post`** — fullscreen-triangle post-process. Reads offscreen colour + velocity attachments, runs 5×5 max-velocity dilation + 8-tap weighted blur for motion blur, writes the swapchain image. Tunables in `post.frag` (`kIntensity`, `kMaxBlur`, `kStaticWeight`, `kMotionSamples`, `kDilationRadius`).
- **`beam`** — dedicated laser-beam pipeline. Empty vertex input, 6-vertex view-aligned quad expansion via `gl_VertexIndex`. Push constants for `start, end, color, width, time`. `beam.vert` computes the perpendicular from the view matrix so it works under arbitrary cameras (g3 portability). Additive ONE/ONE, depth-test on read-only. Consumers: laser ability beam, drone-swarm beams, and **the Рельсотрон tracer** (one BeamDraw per live rail projectile, from spawn-origin to current position; see `SceneAssembler.kt` `railTracers`).

**Vertex format**: `position(vec3) + RGBA color(vec4) + normal(vec3) + uv(vec2)`. Vertex shader exposes `vLocalXZ` for plasma soft-fade and `vUV` for texture sampling.

**Push constant layout** — 104 bytes total, kept under the 128-byte Vulkan minimum so old Adreno/Mali devices don't trip the cliff: `mat4 model` + `vec4 tint` (shader-mode flags, **not colour**) + `vec4 plasmaColor` (per-draw RGBA tint shared by plasma billboards / additive meshes / textured meshes) + `float time` + `float textureMode`. Exact bit layout in `cpp/engine/VulkanContext.cpp::PushConstantData`.

**Descriptor sets** (main pipeline layout, 9 scene pipelines): set 0 = scene UBO (`view + proj + prev_view + prev_proj`); set 1 = textured fragment branch combined image sampler (default 1×1 white bound at frame start, rebound per textured draw); set 2 = per-draw dynamic UBO carrying `prev_model` (slot 0 sentinel identity for billboards/particles, mesh-style draws allocate own slots). Post and beam pipelines have their own minimal layouts.

**Render flow**: scene pass writes two attachments — offscreen colour (B8G8R8A8_UNORM) and offscreen velocity (R16G16_SFLOAT). All overlay pipelines (plasma / translucent / additive / particles / frame) have `cbAtts[1].colorWriteMask=0` so they don't clobber opaque-mesh velocity vectors. Then a post pass reads both and writes the swapchain image. Order: opaque → textured → system billboards → translucent → additive mesh → particles → plasma billboards → beams → frames → post → present.

### Camera

Configured in `cpp/engine/Camera.cpp::reset()` for fixed side-view: target `(0, 0, 4)`, radius `22`, pitch `π/2` (rotation around X). Touch input on the engine surface is swallowed by `MainActivity`'s onTouchListener so the player's drag doesn't move the camera; it maps to world `(X, Z)` for asteroid hit-testing (priority lock) and ability touch handling.

### Coordinate convention

X = horizontal screen, Z = vertical screen, Y = depth (always 0 for game objects). Visible area at the target plane on a 1080×2400 device: X ∈ [−2.47, +2.47], Z ∈ [−1.49, +9.49]. Same world-units-to-pixels ratio horizontally and vertically, so equal `scaleX` and `scaleZ` produce a visually square shape.

## SceneObject extensions for Outpost

`Scene.kt`'s `SceneObject` has these Outpost-only fields (defaults are no-ops, so g3 code using only uniform `scale` + `rotationZ` is unaffected):

- `scaleX/Y/Z` (NaN means "fall back to uniform `scale`") — for stretching primitives.
- `rotationY` — rotation around world Y axis, used to orient projectiles along velocity.
- `material: Int` — translucent-pipeline shader-mode flag (PLAIN / NEBULA / HEX). Ignored on other routes.
- `tintR/G/B/A: Float` and `additiveMaterial: Int` — additive-pipeline per-draw RGBA tint forwarded into `pc.plasmaColor`, plus sub-material flag (ADDITIVE_PLAIN / ADDITIVE_FIRE). The tint fields are also consumed by the textured route.
- `textureHandle: Long` — only consumed by the textured route. 0 = no texture.
- `prevModelMatrix: FloatArray?` — for motion blur; tracked by `Asteroid.prevZ/prevRotation`, `Projectile.prevX/prevZ`, `Fireball.prevLife`, snapshotted before tick movement.

`modelMatrix()` composes `T * Rz * Ry * S`.

`EngineView` carries five scene lists each frame: `scene` (opaque, `drawMesh`), `translucentObjects`, `additiveObjects`, `texturedObjects`, and `beams: List<BeamDraw>`. All five are submitted from `MainActivity.buildScene()` via `submitScene(...)`. Same `SceneObject` type — only routing differs (except `beams`, which is its own data class).

**Asset overview**:
- Most geometry uses `quad.gltf` (X-Z unit quad, double-sided, white per-vertex colour) loaded with multiple tints.
- Asteroids use 5 distinct `.glb`s (`Asteroid_1`..`_4`, `_9`) with per-type tints; mesh handle picked at spawn.
- Bullets use `Bullet.glb` and `Bullet_Heavy.glb` (oriented along +X, so `BULLET_MODEL_YAW_OFFSET = -π/2`). `GltfLoader::loadFromMemory` merges multi-primitive meshes into one (the bullet glbs ship as 3-6 prims per material).
- Rockets use a **procedural mesh** (`buildRocketMesh`) — engine bell, body, fins, nose, warning stripe.
- Turrets / laser dome / rocket silo built procedurally via `TurretMeshBuilder` (`addRect`, `addChamferedRect`, `addTri`, `addHalfDisk`). Each turret is a static base + rotating barrel.
- Background nebulae and the fireball UV-sphere are also procedural via `loadMeshRaw`.

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

The "clean sci-fi arcade" look is centralised:

- **`game/UiTheme.kt`** — single source of truth for the 5-colour palette (overlay bg, panel bg, accent red/blue/green, warning amber, text variants), border colours, corner radii, paddings, gaps, button heights, and text sizes. Touch this file to retune the look — overlays must not hard-code values.
- **`game/UiHelpers.kt`** — programmatic helpers: `stylePanel`, `buildCard`, `buildPrimaryButton`, `buildSecondaryButton`, `buildDisabledButton`, `buildTitle/Heading/Body/Caption`, `buildPill`. All built with `GradientDrawable` (no XML drawables), styled from `UiTheme` constants.
- **`game/ui/IconDrawables.kt`** — Path-based runtime-tintable icons used on ability buttons (V-shaped shield, rocket, laser-cuts-asteroid). `ShieldFillDrawable` paints the shield button as a vertical green/gray HP fill bar.
- **`game/ui/HudView.kt`** — owns every in-game HUD widget: top mission/wave/score/HP/energy panel, the centred "Волна N" announce text, the action bar (shield + ability buttons + abort ✕), and the buff indicator. Builds the views, applies Drawables, mutates view state from `refresh*` calls, runs the small UI animations (`pulseBaseDamage`, `announceWave`). MainActivity mounts HudView's outputs into the FrameLayout root and never touches HUD widgets directly afterwards.

`OverlayFactory` consumes UiTheme + UiHelpers — it owns the layout structure of each *overlay* screen (menu / mission select / upgrades / win-lose / generic), but never sets a colour or padding directly. Adding a new overlay: build root via `makeOverlayRoot`, populate with `UiHelpers.build*` widgets, add gaps via `gapParams(ctx, dp)`.

## Memory & persistence

- `GameProgress` — persistent state: `metal`, three upgrade levels, `highestMissionUnlocked`. Owned by `ProgressRepository`, which holds the live in-memory copy as `current` and persists every mutation through `update { transform }` to `SharedPreferences("outpost_progress_v2", MODE_PRIVATE)`. The `_v2` suffix is the M4 rename break — pre-M4 builds wrote to `outpost_progress`; that file is now ignored. Both Activity (overlays display metal) and the tick (reads upgrade levels for effective damage, writes +metal on win) share a single `progressRepo` instance and read `progressRepo.current` directly.
- `MissionRun` — in-flight stats for the current run: asteroids destroyed, score, metal earned, win bonus, current wave, total waves, mission name. Reset on each `startMission`.

