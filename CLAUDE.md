# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

**Asteroid Outpost** is a 2D side-view shoot-'em-up for Android, built on top of an existing native Vulkan engine that was originally a separate project called **g3**. The engine source set was copied from `D:\g3\app\src\main\` into `app/src/main/`, then heavily repurposed and rebranded.

> **Active milestone plan & change log live in `ROADMAP.md`** (the project's living doc). Source spec for the active refactor wave is `idea.txt` — 12 tasks grouped into milestones M1–M7. **M1–M6 are complete as of 2026-05-04**; M7 (VFX polish pass) landed 2026-05-04 — turret/weapon VFX (muzzle flash, bullet trails, AoE explosion ring), tap-spam cooldown fix + reload bar above the central turret. **Engine wave E1** (per-vertex RGBA + alpha-blend pipeline + procedural-mesh API + soft-disk background nebulae) landed 2026-05-04 — first time we touched the engine since fork. **Engine wave E2** (radial soft-fade for plasma billboards + annular half-membrane shield dome via the translucent pipeline) landed 2026-05-05. **Engine wave E3** (material plumbing for translucent draws + procedural FBM nebulae with domain warping + hex-grid shader pattern on the shield dome) landed 2026-05-05. **M7.2 content swap** (2026-05-05): five distinct asteroid meshes (one per type, plus two grey variants for NORMAL/FAST), real `Bullet.glb` / `Bullet_Heavy.glb` projectile models replacing red-quad placeholders, central-turret aim-alignment fire gate, and an engine-side `GltfLoader` fix that merges multi-primitive meshes (the bullet .glbs ship as 3-6 prims per material). **Engine wave E4** (2026-05-05): plasma flash polish — premultiplied alpha (fixes the soft-fade no-op caused by ONE/ONE blend dropping source alpha), radial heat-ramp (warm white core → orange edge), and FBM turbulence sampled in world space. Flash VFX (muzzle, trails, AoE rings, hits) now read as wispy flames instead of yellow rectangles. **Engine wave E5.1** (2026-05-05): per-billboard plasma tint — extended `drawPlasmaBillboard` C API / JNI / Kotlin with `(r, g, b, a)` and added `vec4 plasmaColor` to the push-constant (96 bytes total). The fragment shader multiplies the heat-ramp result by `pc.plasmaColor.rgb` and uses `.a` as a brightness scalar; six per-event tints in `DraftCombat` (muzzle, trail, explosion, energy, death, shield) give each flash type its own colour identity. **Engine wave E6** (2026-05-05): time push-constant. Added `float time` to `PushConstantData` (offset 96, total 100 bytes) and a `std::chrono::steady_clock` baseline `m_renderStart` in `VulkanContext`. `renderFrame()` writes `pc.time = elapsedSec` into every draw's push-constant. Fragment shader uses `pc.time` to warp FBM in the plasma branch (fire flickers visibly within a 0.5s flash) and to drift nebulae at low speed (~0.04 sample units/sec — ambient cosmic flow). Infrastructure unblocks future animated procedural effects (shield impact pulses, electric arcs, twinkling stars). **Engine wave E5.2** (2026-05-05): billboard matrix bug fix + non-uniform scale. Diagnosed a long-standing bug in `Camera::billboardMatrix` — with the Outpost camera (pitch=π/2 around X), col 1 mapped model.y to camera-up and col 2 mapped model.z to camera-back/depth, but the project's quad meshes (quad.gltf and procedural soft-disks) live in the X-Z plane (model.y=0), so model.z was being mapped to depth instead of screen-vertical. The quads ended up lying in horizontal world planes (constant Z=cz) and rendered as horizontal stripes, not screen-aligned billboards — every previous wave's circular soft-fade and heat-ramp logic only "kind of worked" because flashes were small. Fix: swap col 1 ↔ col 2 so model.y → depth, model.z → screen-vertical. X-Z meshes are now truly screen-aligned, and `vLocalXZ` traces a real circle on screen as E2.1/E4 always intended. Also added `(scaleH, scaleV)` non-uniform scale to `drawPlasmaBillboard` for streak bullets and flat shockwave effects. **Engine wave E7** (2026-05-06): Additive Mesh Pipeline — 7-th Vulkan pipeline `m_additivePipeline` (ONE/ONE blend like plasma but for arbitrary 3D meshes via model matrix; depth-test on read-only, depth-write off so additive layers accumulate without occluding each other but still hide behind closer opaque geometry). New `station_engine_draw_additive_mesh(engine, mesh, mat4, r, g, b, a, material)` through C / JNI / Kotlin, parallel `EngineView.additiveObjects: List<SceneObject>`, render-loop step between translucent and plasma billboards. Fragment shader gets a new branch on `pc.tint.w >= 0.5` (plain additive: `outColor = vec4(vColor.rgb * pc.plasmaColor.rgb * vColor.a * pc.plasmaColor.a, vColor.a)` premultiplied for ONE/ONE). Unblocks 3D fireballs, plasma laser beams, electric arcs, plasma engines for g3. **Engine wave E7.1** (2026-05-06): 3D Fireball — first user of E7. Fire-material shader sub-branch on `pc.tint.w >= 1.5` uses `abs(vNormal.y)` as a Fresnel-like "facing camera" factor (project camera is fixed at pitch=π/2 → looks along ±Y, so this approximation works without computing real view direction); produces a hot white-yellow core fading through orange to a soft silhouette, multiplied by animated FBM turbulence (reuses E6 time push-constant) and per-draw `pc.plasmaColor` tint. NOTE: the `abs(vNormal.y)` shortcut is project-specific — reusing this on g3's moving camera would need view direction via UBO. Procedural Y-axis-aligned UV-sphere via `loadMeshRaw` (12×16, 384 tris). New `Fireball` data class replaces the flat plasma-billboard explosion at AoE-class events (heavy cannon hit, EXPLOSIVE asteroid death). `buildScene` drives three curves on `t = age/maxLife`: ease-out quadratic scale (fast initial blast, asymptotic settle), linear lerp `FIREBALL_TINT_START → FIREBALL_TINT_END` (forge-orange → dying-ember red), and `sqrt(1-t)` brightness fade (holds longer initially so the colour shift remains visible). Material flag (0=plain, 1=fire) is encoded into `cmd.tint[3]` (1.0f / 2.0f) inside `drawAdditiveMesh` so the render-loop just `memcpy`s `pc.tint = draw.tint` instead of hardcoding. **Engine wave E8** (2026-05-07): UV + textures — biggest infrastructure lift since the fork. Added `vec2 uv` to the `Vertex` struct (location 3, stride 40→48 bytes); `GltfLoader` reads `TEXCOORD_0` with `(0, 0)` fallback for meshes without UVs. New `Texture` C++ class (VkImage + memory + view + sampler + per-texture descriptor set) with PNG decode via stb_image and raw-RGBA8 paths. Descriptor set 1 layout (single `COMBINED_IMAGE_SAMPLER` at fragment stage) added to the shared pipeline layout, all 7 pipelines pick it up automatically. Default 1×1 white texture loaded at engine init and bound to set 1 at `renderFrame` start so untextured draws satisfy the layout requirement; textured draws rebind set 1 to their per-asset texture, then the loop restores default white for downstream draws. New APIs through C / JNI / Kotlin: `loadTexture(pngBytes)`, `loadTextureRaw(rgba8, w, h)` (skip PNG round-trip for procedural textures), `loadMeshRawUV(verts12, indices)` (12 floats per vertex with UV at end), `drawTexturedMesh(mesh, texture, mat4, r, g, b, a)`. New push-constant field `float textureMode` (offset 100, total 104 bytes — still under 128-byte Vulkan minimum). Fragment shader's lit branch picks `albedo = textureMode >= 0.5 ? texture(uTex, vUV).rgb * pc.plasmaColor.rgb : vColor.rgb` so textured opaque inherits the existing N·L diffuse + fill + rim + ambient lighting (the `pc.plasmaColor.rgb` tint multiplier here is reused from the additive/plasma routes — same field, same semantics). `SceneObject.textureHandle` + `EngineView.texturedObjects` + `submitScene` route through `drawTexturedMesh`. Verified end-to-end with two procedural smoke-test patches (128×128 rock noise + 64×64 cyan icon disc), then patches removed for production. Unblocks sprite atlases (E9 prerequisite), real asteroid/turret textures, HUD icons, decals on damaged platform (idea.txt task 4), and is shared infra E10 motion blur will reuse for sampler bindings. **Engine wave E9** (2026-05-07): native particle system. Two new Vulkan pipelines (`m_particleAdditivePipeline` ONE/ONE depth-off for sparks/embers, `m_particleAlphaPipeline` SRC_ALPHA depth read-only for smoke/debris), separate `particle.vert` / `particle.frag` shader pair (vertex shader billboards via camera right/up extracted from `ubo.view` + per-instance binding 1: 8 floats per instance = pos3 + size1 + rgba4), two persistent-mapped instance buffers (4096 particles each, HOST_VISIBLE+HOST_COHERENT), and `drawParticles(mesh, texture, instanceFloats, count, mode)` API through C / JNI / Kotlin that ships a whole pool in one batched JNI call. Render loop runs two passes (additive then alpha), each binding pipeline once and looping batches with per-batch mesh + texture binds + `vkCmdDrawIndexed(instanceCount = N)`. Kotlin layer: `Particle` data class (pos / vel / age / life / size / rgba / drag / gravity), three pools (sparks / smoke / debris) ticked by Euler integration with drag + gravity, packed once per frame as `count * 8` floats with `alpha = sqrt(1-t) * tintA`. Procedural textures generated in Kotlin (no PNG round-trip): 64×64 smoke puff (Gaussian + 2-octave noise, light gray with cool tint) and 64×64 debris chunk (irregular polygonal silhouette via two sine harmonics on radius, warm gray with top-left light gradient). Three consumers wired: 50-70 AoE sparks per `spawnExplosion` (oranges, drag 1.5), 4-8 debris chunks (gravity 1.2) + 3-5 smoke puffs per NORMAL/FAST/HEAVY asteroid death (HEAVY gets a darker reddish tint), and 3-5 muzzle micro-sparks per shot in a 40°-cone along the bullet velocity. Replaces what would otherwise be 1000+ JNI draw calls per frame on dense scenes with O(pools) batched calls. **Engine wave E10.1** (2026-05-07): motion-blur prep — render flow restructured from direct-to-swapchain to scene → offscreen → post → swapchain. `RenderResources` extended with `offscreenColorImage/Memory/View/Sampler` (B8G8R8A8_UNORM, COLOR_ATTACHMENT + SAMPLED, single shared image — `inFlightFence` ensures no overlap so per-image isn't needed), `postRenderPass`, and `postFramebuffers[]` (one per swapchain image). The existing scene `renderPass` now uses `finalLayout = SHADER_READ_ONLY_OPTIMAL` and `framebuffers` holds one shared scene framebuffer (offscreen colour + depth). New `m_postPipeline` with its own minimal pipeline layout (one descriptor set with the offscreen sampler, no push constants — post doesn't need scene UBO or push state). `post.vert` generates a fullscreen triangle via `gl_VertexIndex` without any vertex bindings; `post.frag` currently does a passthrough `texture(sceneColor, vUV)` sample (motion blur lands in E10.4). `renderFrame` is now two passes: scene pass draws everything as before into the offscreen image, then a post pass binds the post pipeline + descriptor set and issues a single `vkCmdDraw(3, 1, 0, 0)` to write the swapchain. Visually identical to pre-E10. Treat any drift between this CLAUDE.md and ROADMAP.md as a sign one wasn't updated — ROADMAP is the source of truth for what's done and what's planned.

### What the game is

Portrait-orientation arcade game.

- **Central turret** — tall red rectangle (~3× the side turrets) sitting at the centre of a wide grey platform at the bottom of the screen. The player drags on the screen to aim: the turret's barrel smoothly rotates toward the touch point. While the finger is held down, it fires continuously along the aim direction (hold-to-fire) at a fixed rate. The turret does not move along the platform; aim Z is clamped non-negative so you can't shoot down through the platform.
- **Two stationary blue side turrets** flank the central turret on the platform. Each auto-aims at the nearest asteroid and fires at an angle. Their damage is ~50% of the central turret — they're support, the player carries the fight with the central turret. Bullets are oriented along their velocity vector.
- **Grey asteroid squares** spawn at the top at random X positions, falling slowly downward. Bullets damage them; asteroids that reach the platform damage the platform and disappear.
- **Wave-based missions.** Each mission is a list of waves; a wave spawns N asteroids at a fixed interval, ends when all asteroids are gone, then a 2-sec break, then the next wave. Five missions teach one mechanic each (Учебная тревога → Быстрые цели → Тяжёлая угроза → Взрывная цепочка → Проверка базы); each mission's `WaveConfig.typeWeights` ramps up the new asteroid type so the lesson lands gradually. Numbers in `game/Missions.kt`.
- **Asteroid types** (M5, `game/AsteroidType.kt`): NORMAL (baseline), FAST (small, ×2 speed, low HP), HEAVY (big, ×3 HP, ×2 platform damage, slow), EXPLOSIVE (deals AoE damage on death), ENERGY (rare; on death triggers a 5-sec ×2 main-weapon damage buff via the single-slot buff system in `MainActivity`). Each `WaveConfig` carries a `typeWeights: Map<AsteroidType, Float>` — empty map = all NORMAL. Five distinct `.glb` meshes (M7.2) — `Asteroid_1`/`Asteroid_2` (grey, randomized for NORMAL/FAST), `Asteroid_3` (dark-red HEAVY), `Asteroid_4` (orange EXPLOSIVE), `Asteroid_9` (cyan ENERGY) — give per-type silhouettes; mesh handle is picked at spawn and stored on the `Asteroid` data class. Size and platform damage scale with type multipliers.
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
5. Move bullets along their velocity vector; cull off-screen and on hit (apply per-bullet damage to asteroid HP, scaled by `activeBuffDamageMul`). On hit, if `bullet.aoeRadius > 0`, apply `aoeDamage` to other live asteroids within the radius and call `spawnExplosion` (E7.1 — adds a 3D `Fireball` to the additive route, replacing the old flat plasma billboard). After the bullet pass, dead asteroids' on-death effects fire — EXPLOSIVE calls `spawnExplosion` and deals splash damage to neighbours within `EXPLOSIVE_AOE_RADIUS`; ENERGY arms the buff.
6. Move asteroids down at their per-asteroid speed (mission baseline × type multiplier captured at spawn).
7. Asteroid touches platform: if `shieldState == ACTIVE`, asteroid is absorbed (small flash, no HP loss); otherwise damage platform by the asteroid's `platformDmg` (HEAVY hits twice as hard), remove asteroid.
8. Wave control: spawn current wave's asteroids at intervals; when wave fully spawned and asteroids list is empty, start a 2-sec break or trigger win.
9. Build the scene (platform — blue tint while shield active, central turret, side turrets, asteroids, bullets, flashes) and submit.
10. Win/lose checks → `showWin()` / `showLose()` triggers + presentation overlays.

**Central turret rotation pivot.** The model rotates around `SceneObject` origin (its centre), but visually we want pivot at the *base* sitting on the platform. To get base-anchored rotation without a custom mesh, `buildScene()` offsets the SceneObject centre along the barrel direction: `centerX = pivotX + sin(angle) * HALF_H`, `centerZ = pivotZ + cos(angle) * HALF_H`. The base stays glued to `(pivotX, pivotZ)` for any angle.

### Vulkan pipelines (`cpp/engine/VulkanContext.cpp`)

Ten pipelines. The first seven share a single vertex/fragment shader pair (`triangle.vert`/`triangle.frag`) and differ only in blend / depth state. The two particle pipelines (E9) use a separate `particle.vert`/`particle.frag` pair because they need per-instance vertex bindings the main shader doesn't carry. The post pipeline (E10.1) uses its own `post.vert`/`post.frag` pair plus a minimal pipeline layout (1 descriptor set, no push constants) because it operates on a fullscreen triangle, not scene geometry.

- **`system`** — opaque meshes (platform, turrets, asteroids, bullets, reload bar). Depth-test on, depth-write on, no blending. The workhorse.
- **`frame`** — additive depth-tested selection frames. Disabled by Outpost (g3 reticle holdover).
- **`star`** — point-list star field. Visible behind the gameplay layer.
- **`plasma`** — additive camera-facing billboards (`ONE / ONE`), **depth test OFF** (always-overlay since E5.2-followup; before that flashes were occluded by closer parts of 3D asteroid meshes). Used by Outpost for **all `Flash` VFX** (muzzle flash, bullet trails, asteroid hit, ENERGY pickup, shield absorb). The fragment shader's `pc.tint.x ≥ 0.5` branch (set by `VulkanContext::renderFrame` when binding plasma) does four things on top of the standard pipeline: (a) **radial soft-fade** in model-space X-Z (E2.1) — quad corners go to alpha 0 so the visible glow inscribes the quad with no boxy silhouette; (b) **radial heat-ramp** (warm white-yellow core → orange edge, lerped on `length(vLocalXZ)`, E4); (c) **FBM turbulence** (4-octave value-noise reused from the nebula material, sampled in world space so adjacent flashes see distinct slices, E4); (d) **per-billboard tint** (E5.1) — `pc.plasmaColor.rgb` is multiplied into the heat-ramp result so each event type carries its own colour (muzzle warm-white, trail dim-warm, ENERGY cyan, death yellow, shield blue), and `.a` is a brightness scalar. Output is premultiplied (`RGB * alpha`) — the plasma pipeline blends `ONE/ONE` so the framebuffer drops source alpha on the floor, meaning the soft-fade only became visible after E4 added the premultiply. Output is unlit emissive (no diffuse/ambient/rim — additive blending already produces "self-luminous" feel).
- **`billboard`** — normal camera-facing billboards. Unused by Outpost (no images yet).
- **`translucent`** — alpha-blend mesh pipeline (`SRC_ALPHA / ONE_MINUS_SRC_ALPHA`), depth-test on, **depth-write off**. Added in E1. Used by Outpost for the soft-disk background nebulae and the shield-dome half-membrane (E2.2). Per-vertex alpha controls transparency — the fragment shader passes `vColor.a` straight through, optionally multiplied by procedural-pattern modulators (E3): `nebulaAlphaMod()` applies 4-octave domain-warped FBM noise on `vWorldPos.xz` (active when `pc.tint.y ≥ 0.5`, set by `MATERIAL_NEBULA = 1`); `hexAlphaMod()` applies a hex-grid pattern on `vLocalXZ` (active when `pc.tint.z ≥ 0.5`, set by `MATERIAL_HEX = 2`). Material is selected per-draw via `EngineJni.drawTranslucentMesh(handle, mat4, material)` and `SceneObject.material`.
- **`additive`** (E7) — additive-blend pipeline for **arbitrary 3D meshes** (`ONE / ONE`, depth-test on read-only, depth-write off). Conceptually plasma-billboard's blend mode applied to real geometry instead of camera-facing quads. Depth-test ON (read-only) is the key difference vs. `plasma`: an additive fireball behind an asteroid is correctly occluded, but multiple overlapping additive layers don't punch each other out — they accumulate into the framebuffer. Used by Outpost for **3D fireball explosions** at AoE-class events (heavy cannon hit, EXPLOSIVE asteroid death) — see `Fireball` data class in `MainActivity` and the procedural Y-axis-aligned UV-sphere built in `setupBackgroundNebulae()` via `loadMeshRaw`. Fragment shader has two branches under `pc.tint.w`: (a) **plain additive** (`>= 0.5 < 1.5`) — `outColor = vec4(vColor.rgb * pc.plasmaColor.rgb * vColor.a * pc.plasmaColor.a, vColor.a)` premultiplied for the ONE/ONE blend, mesh authors put A=1 at glow centres and A=0 at edges for soft falloff; (b) **fire material** (`>= 1.5`, E7.1) — `abs(vNormal.y)` Fresnel-like factor (the project's pitch=π/2 camera looks along ±Y, so model.y normals along ±Y mean "facing camera") drives a heat ramp from white-yellow core to orange edge plus animated FBM turbulence (reuses E6 `pc.time`); the `abs(vNormal.y)` shortcut is project-specific and would need a real view direction for g3's moving camera. Material flag is encoded into `cmd.tint[3]` (1.0f = plain, 2.0f = fire) by `drawAdditiveMesh(...,material)` so the render-loop just `memcpy`s `pc.tint = draw.tint`. Render order in `renderFrame`: opaque → system billboards → translucent → **additive mesh** → plasma billboards → frame; additive sits above translucent (emissive over alpha) but below plasma billboards (depth-tested 3D under depth-test-off overlay billboards).
- **`particleAdditive` / `particleAlpha`** (E9) — instanced particle pipelines. Both use `particle.vert` (binding 0 = unit-quad mesh, binding 1 = per-instance INSTANCE-rate, 8 floats stride: pos3 + size1 + rgba4). Vertex shader builds camera-facing billboards from `ubo.view` rows. `particleAdditive` uses ONE/ONE blend and depth-test off (sparks/embers — overlay VFX); `particleAlpha` uses SRC_ALPHA blend and depth-test on read-only (smoke/debris — 3D occlusion). `particle.frag` branches on `pc.textureMode`: 0 = additive heat-ramp + radial soft-fade × per-instance vColor, ≥0.5 = sample `uTex` × per-instance vColor with vColor.a × fade as alpha. Each pipeline owns a persistent-mapped HOST_VISIBLE instance buffer (4096 × 32 bytes); `drawParticles` API ships a whole pool in one batched JNI call. Render-loop runs two passes (additive then alpha); each binds pipeline + instance buffer once, loops batches with per-batch mesh + texture descriptor binds + a single `vkCmdDrawIndexed(instanceCount = N)`.
- **`post`** (E10.1) — fullscreen-triangle post-process pipeline. No vertex bindings (vertex shader generates 3 verts via `gl_VertexIndex`); single descriptor set with the offscreen colour sampler; no push constants. Reads the offscreen colour image (written by everything above) and writes to the swapchain image. Currently passthrough; in E10.4 it'll do velocity-buffer motion blur on top.

Vertex format is now **position(vec3) + RGBA color(vec4) + normal(vec3) + uv(vec2)** (E1 widened color from vec3, E8 added uv). Opaque code paths stamp A=1, untextured paths leave UV at (0, 0), so the changes are invisible to existing meshes. The vertex shader additionally outputs `vLocalXZ = inPosition.xz` (E2.1) and `vUV` (E8.1) so the fragment shader has access to the model-space radius for the plasma soft-fade and to UVs for texture sampling.

Push constant layout (104 bytes total, well under the 128-byte Vulkan minimum guarantee): `mat4 model` (offset 0) + `vec4 tint` (offset 64, shader-mode flags **not colour** — `.x` plasma soft-fade E2.1, `.y` nebula material E3.1, `.z` hex material E3.1, `.w` additive sub-material E7) + `vec4 plasmaColor` (offset 80, per-draw RGBA tint shared by plasma billboards E5.1, additive meshes E7, and textured meshes E8.3) + `float time` (offset 96, elapsed seconds since first frame, E6) + `float textureMode` (offset 100, E8.3 — `>= 0.5` makes the lit fragment branch sample `uTex` at vUV instead of using vColor.rgb).

Descriptor sets (main pipeline layout, used by the 9 scene pipelines): **set 0** = UBO (view + proj, vertex stage); **set 1** = `COMBINED_IMAGE_SAMPLER` for the textured fragment branch (fragment stage, E8.2). The shared pipeline layout includes both, so all 9 scene pipelines accept both sets without per-pipeline layout variants. A default 1×1 white texture is loaded at engine init and bound to set 1 at the start of every `renderFrame`; textured draws rebind set 1 to their own texture's pre-allocated descriptor set, then the loop restores default white before subsequent untextured pipelines run. The post pipeline (E10.1) has its own minimal layout — one descriptor set bound to the offscreen colour sampler.

Render flow per frame (E10.1): scene pass renders into the **offscreen colour image** (single shared, B8G8R8A8_UNORM, COLOR_ATTACHMENT + SAMPLED, finalLayout = SHADER_READ_ONLY_OPTIMAL). Then a separate post pass renders into the **swapchain image** for the current `imageIndex`, sampling the offscreen colour. Means scene order is: offscreen-clear → opaque mesh → textured mesh → system billboards → translucent → additive mesh → particles (additive + alpha) → plasma billboards → selection frames → end scene pass → post pass with fullscreen draw → present.

### Camera

Configured in `cpp/engine/Camera.cpp::reset()` for fixed side-view: target `(0, 0, 4)`, radius `22`, pitch `π/2` (rotation around X). Touch input on the engine surface is swallowed by `MainActivity`'s onTouchListener so the player's drag doesn't move the camera; it maps to `(aimTargetX, aimTargetZ)` in world coords for aiming the central turret.

### Coordinate convention

X = horizontal screen, Z = vertical screen, Y = depth (always 0 for game objects). Visible area at the target plane on a 1080×2400 device: X ∈ [−2.47, +2.47], Z ∈ [−1.49, +9.49]. Same world-units-to-pixels ratio horizontally and vertically, so equal `scaleX` and `scaleZ` produce a visually square shape.

## SceneObject extensions for Outpost

`Scene.kt`'s `SceneObject` was extended with:
- `scaleX`, `scaleY`, `scaleZ` (NaN means "fall back to uniform `scale`") — for stretching primitives.
- `rotationY` — rotation around world Y axis, used to orient bullets along their velocity vector.
- `material: Int` (E3.1) — translucent-pipeline fragment-shader material flag (PLAIN / NEBULA / HEX). Ignored on opaque/additive routes.
- `tintR/G/B/A: Float` and `additiveMaterial: Int` (E7) — additive-pipeline per-draw RGBA tint forwarded into `pc.plasmaColor`, plus sub-material flag (ADDITIVE_PLAIN / ADDITIVE_FIRE). Ignored on opaque/translucent routes. The `tintR/G/B/A` fields are **also** consumed by the textured route (E8) since that path forwards them through the same `pc.plasmaColor` slot.
- `textureHandle: Long` (E8) — handle from `loadTexture` / `loadTextureRaw`. Only consumed by the textured route; ignored elsewhere. 0 = no texture.

`modelMatrix()` composes `T * Rz * Ry * S`. Default values are no-ops, so existing g3 code that only uses uniform `scale` and `rotationZ` is unaffected.

`EngineView` carries four scene lists each frame: `scene` (opaque, drawn through `drawMesh`), `translucentObjects` (drawn through `drawTranslucentMesh` on the alpha-blend pipeline), `additiveObjects` (E7 — drawn through `drawAdditiveMesh` on the ONE/ONE additive pipeline), and `texturedObjects` (E8 — drawn through `drawTexturedMesh` on the same opaque pipeline as `scene` but with set 1 bound to the per-object texture and `pc.textureMode = 1.0`). All four are submitted from `MainActivity.buildScene()` via `submitScene(opaque, ..., translucentObjects, additiveObjects, texturedObjects)`. They use the same `SceneObject` type — only the routing differs.

Most Outpost geometry (platform, central turret, side turrets, reload bar, shield button backdrop) uses a single primitive: `app/src/main/assets/models/quad.gltf` — an X-Z plane unit quad with double-sided indices and white per-vertex colours, loaded with multiple tints (red / grey / blue / dome-blue). Asteroids use five distinct `.glb`s (`Asteroid_1`..`Asteroid_4`, `Asteroid_9`) with per-type tints (NORMAL/FAST grey across two meshes, HEAVY dark-red, EXPLOSIVE orange, ENERGY cyan); the Asteroid data class stores the picked mesh handle so each asteroid keeps its silhouette across its lifetime. Bullets use `Bullet.glb` (automatic + side turrets) and `Bullet_Heavy.glb` (heavy cannon); both are oriented with their long axis along +X, so `MainActivity.buildScene` adds `BULLET_MODEL_YAW_OFFSET = -π/2` to the `atan2(vx, vz)` velocity-yaw so the nose points along the velocity vector. Bullet scale = `b.halfH * BULLET_MODEL_SCALE_MUL` (2×) so the bullet reads next to its trail/muzzle flash instead of vanishing in the additive haze.

`GltfLoader::loadFromMemory` (M7.2 fix) merges every triangle primitive of every mesh into one `MeshData` — glTF authoring tools split a model into one primitive per material (`Bullet.glb` has 3, `Bullet_Heavy.glb` has 6), and the previous loader only kept the first primitive, which is why the bullet models initially rendered as a fragment of the casing. `load_mesh_colored` re-stamps every vertex's tint, so the per-material split is invisible after merge anyway.

**Background nebulae** are procedural soft-disk meshes built in `MainActivity.buildSoftDiskMesh()` via `engine.loadMeshRaw(verts, indices)`: a triangle fan with the centre vertex at A=1 and the rim vertices at A=0, so the alpha-blend pipeline draws a smooth circular fade with no visible edges. Five tinted disks (deep purple / cyan / dim crimson / twilight blue / warm dust) are placed across the playfield by `setupBackgroundNebulae()` and live in `engineView.translucentObjects`. They render between opaque scene and plasma billboards, so gameplay objects sit on top of them.

**Fireball mesh** (E7.1) is also a procedural mesh built once in `setupBackgroundNebulae()` (same place because both share `loadMeshRaw`). `MainActivity.buildFireballSphereMesh()` produces a Y-axis-aligned UV-sphere (12 stacks × 16 slices, ~384 tris) with white per-vertex colour, alpha 1, normals = unit position. Y-axis alignment is required: the fire-material fragment shader's Fresnel-like fade uses `abs(vNormal.y)` under the project's fixed pitch=π/2 camera. The mesh is consumed by AoE explosions (`Fireball` data class, spawned by `spawnExplosion` from heavy cannon hits and EXPLOSIVE asteroid deaths) — `buildScene` maps live fireballs to `engineView.additiveObjects` with material=ADDITIVE_FIRE and ease-out-quad scale + colour-lerp + sqrt-brightness curves driven by age/maxLife.

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
