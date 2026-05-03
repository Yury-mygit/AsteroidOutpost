# java/com/example/g3/

Kotlin Android layer. Owns UI, lifecycle, asset loading, touch input, selection state, tactical command dispatch, and Android audio. The native engine does not own gameplay state.

## Main Responsibilities

- Build and submit the render scene through `EngineView`.
- Maintain selected allied ships and selected target ids.
- Load meshes, shaders, and audio assets from `assets/`.
- Drive the gameplay tick loop.
- Route button, text, and microphone commands into `StationAI`.
- Keep the special fly-around path working through `MissionController`.

## Key Files

| File | Purpose |
|---|---|
| `MainActivity.kt` | Main app controller: UI wiring, asset loading, command dispatch, speech input, sound, mission tick loop, and scene rebuilds. |
| `EngineView.kt` | `SurfaceView`, render thread, touch gestures, camera callbacks, and tap picking. |
| `CameraJoystickView.kt` | Bottom-right camera control pad for pan/dolly/orbit/roll/reset. |
| `SelectionOverlayView.kt` | Draws selection frames and shield/hull bars. |
| `AxisIndicatorView.kt` | Small camera-orientation indicator shown near the settings pull tab. |
| `SettingsActivity.kt` | Settings screen and persisted app options. |
| `Scene.kt` | Scene draw contracts: `SceneObject`, `HighlightMeshes`, `BillboardDraw`, `submitScene()`. |

## Intelligence Layer

`intelligence/` contains the current tactical command layer.

| File | Purpose |
|---|---|
| `intelligence/StationAI.kt` | Rule-based station commander. Maintains active tasks and emits `ShipIntent`s for ships. |
| `intelligence/PlayerCommand.kt` | Structured commands: attack target, attack nearest, defend station, return home, patrol, set mode. |
| `intelligence/FleetUnit.kt` | Unit selector abstraction (`All`, explicit ids, named wing). |
| `intelligence/FleetRegistry.kt` | Maps named wings to allied ship ids. Default setup is Alpha = `0,1,2`; Beta = `3,4`. |
| `intelligence/CommandClassifier.kt` | Loads `assets/ml/model.json` and classifies Russian command text. |

## Simulation Layer

`sim/` remains the gameplay runtime used by the Android layer.

| File | Purpose |
|---|---|
| `sim/SimulationWorld.kt` | Runtime owner of ships, world objects, projectiles, explosions, and combat events. |
| `sim/ShipIntent.kt` | High-level ship behaviors, including `MoveTo`, `FollowPath`, `AttackTarget`, `ReturnHome`, `NoseThrustToward`, and `DriftPass`. |
| `sim/ShipController.kt` | Converts `ShipIntent` into `ShipCommand`. |
| `sim/ShipMotor.kt` | Applies movement constraints and heading updates. |
| `sim/WeaponController.kt` | Spawns and updates projectiles, hits, explosions, and combat events. |
| `sim/SceneAdapter.kt` | Converts snapshots into `SceneObject` and plasma billboard lists. |
| `sim/CombatStats.kt` / `sim/WorldObject.kt` | Shield/hull stats and damageable non-ship world objects. |

## AI Helper Layer

`ai/` is now mixed-purpose:

- `MissionController.kt` is still active for the fly-around mission.
- `ShipAgent.kt`, `FormationDef.kt`, `SlotAssigner.kt`, and path helpers support that path.
- `OrbitTarget.kt` remains a scene-derived helper.

Do not document `ai/` as fully legacy anymore.

## Current Scene

- Allied fighters: ids `0..4`, around `(15, 10, 0)`.
- Allied station: id `5`, at `(0, -2, -5)`.
- Enemy station: id `6`, at `(0, 150, 0)`.
- Enemy fighters: ids `7..11`, along `y = 120`.

## Main UI Controls

Top-left buttons:

- `btnAttack` -> attack selected target with selected ships.
- `btnDefend` -> routed through the same command path with `DEFEND_STATION`.
- `btnFlyAround` -> starts or cancels `MissionController`.
- `btnPatrol` -> starts or cancels patrol around allied station.
- `btnHome` -> sends allied ships back to the rally position.

Other controls:

- `btnMic` -> one-shot speech recognition session.
- `shipCard` -> selected allied unit summary and target hint.
- `CameraJoystickView` -> pan/dolly or orbit/roll depending on gesture mode.

## Tick Flow

```text
MainActivity.ensureTicking()
  -> StationAI.tick(dt, simWorld)
  -> simWorld.update(dt, intents)
  -> StationAI.onEvents(events, simWorld)
  -> optional MissionController.update(dt)
  -> optional simWorld.teleportShips(...)
  -> SceneAdapter.sceneFromWorld(...)
  -> SceneAdapter.plasmaBillboards(...)
```

## Notes

- Kotlin reads all asset files and passes raw bytes into native code.
- Scene data, selection, and command state live in Kotlin.
- If screenshots are referenced without an explicit path, use the newest file in `Screen/`.
