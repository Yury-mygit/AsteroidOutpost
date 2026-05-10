# `game/` symbol index

Quick lookup — what lives where, by responsibility. Update when files move
or when public symbols rename.

## Runtime — orchestration

| What | Where |
|---|---|
| Mission state machine + tick loop | `MissionRunner.kt :: MissionRunner` |
| Game state machine enum | `MissionRunner.kt :: GameState` (MENU / PLAYING / WON / LOST) |
| Win/lose host hooks | `MissionRunner.kt :: MissionRunner.Host` |
| Scene → engine adapter (per-frame draw list) | `SceneAssembler.kt :: SceneAssembler::assemble` |

## Overlays — `game/overlay/`

Each screen file owns one overlay. Common builders (`makeOverlay`,
`gapParams`) live in `OverlayCommon.kt`. All screens compose from
[`OverlayOpts`](overlay/OverlayCommon.kt) flags (`scrim`, `scrollable`,
`footer`, `centred`).

| Screen | Public entry | File |
|---|---|---|
| Main menu | `buildMenu`, `setMenuBody`, `addMenuButton` | `overlay/MenuOverlay.kt` |
| Mission select | `buildMissionList` | `overlay/MissionSelectOverlay.kt` |
| Weapon select | `buildWeaponSelect` | `overlay/WeaponSelectOverlay.kt` |
| Base / upgrades | `buildUpgrades` | `overlay/UpgradesOverlay.kt` |
| Win / lose | `buildEndOfMission` | `overlay/EndOfMissionOverlay.kt` |
| Common plumbing | `makeOverlay`, `gapParams`, `OverlayOpts`, `Overlay` | `overlay/OverlayCommon.kt` |

`MainActivity` drives navigation via a `sealed class Screen` + `backStack`
(`enterScreen` / `popScreen` / `replaceTop` / `resetStack`).

## HUD & icons — `game/ui/`

| What | Where |
|---|---|
| In-game top panel + ability bar + abort ✕ | `ui/HudView.kt :: HudView` |
| Wave-announce / pulse / refresh* methods | `ui/HudView.kt` (same class) |
| Icon base class (Path-based vector tint) | `ui/icons/IconDrawable.kt :: IconDrawable` |
| Shield V-icon | `ui/icons/ShieldIcon.kt :: makeShieldIcon` |
| Rocket icon | `ui/icons/RocketIcon.kt :: makeRocketIcon` |
| Laser-cuts-asteroid icon | `ui/icons/LaserIcon.kt :: makeLaserIcon` |
| Back chevron icon | `ui/icons/BackIcon.kt :: makeBackIcon` |
| Shield HP fill bar drawable | `ui/icons/ShieldFillDrawable.kt :: ShieldFillDrawable` |

## UI theming primitives

| What | Where |
|---|---|
| Palette / dp / paddings / radii / typography | `UiTheme.kt :: UiTheme` |
| Button builders (primary / secondary / disabled) | `UiHelpers.kt :: buildPrimaryButton / buildSecondaryButton / buildDisabledButton` |
| Outlined-tile icon buttons (✕ / ←) | `UiHelpers.kt :: buildGlyphTile / buildIconTile` |
| Title / heading / body / caption / pill | `UiHelpers.kt :: buildTitle / buildHeading / buildBody / buildCaption / buildPill` |
| Card panel | `UiHelpers.kt :: buildCard / stylePanel` |

## Combat — `game/combat/`

| What | Where |
|---|---|
| Asteroid data class | `combat/Asteroid.kt :: Asteroid` |
| Asteroid types + per-type tables | `AsteroidType.kt` |
| Falling / collision / spin constants | `combat/Combat.kt :: DraftCombat` |
| Weapon-effect umbrella (Projectile / Beam / Behavior) | `combat/Effects.kt` |
| Per-projectile behaviour strategies | `combat/Effects.kt :: PlainBulletBehavior / HeavyShellBehavior / HomingRocketBehavior` |
| VFX state (Flash / Fireball / Particle) | `combat/Vfx.kt` |
| VFX emitter (muzzle / flash / sparks / smoke / debris) | `combat/VfxSpawner.kt :: VfxSpawner` |
| Particle pools / tick / packing | `combat/Particles.kt` |
| Target selection (best HP / nearest / arc gating) | `combat/AutoAim.kt :: bestHpTargetInArc / nearestAsteroidInArc / pickAsteroidAt / pickAsteroidType / centralWeaponHalfArc / isWithinArc` |

## Content / procedural meshes — `game/content/`

| What | Where |
|---|---|
| Mesh builder DSL (rect / chamfered / tri / half-disk) | `content/MeshBuilder.kt :: TurretMeshBuilder` |
| Procedural mesh factories (turret base/barrel, silo, dome, arch, fireball, cone, rocket, soft disk, particle quad) | `content/Meshes.kt :: build*Mesh` |
| Procedural textures (smoke, debris) | `content/Textures.kt :: generate*Texture` |

## Game data / catalogs

| What | Where |
|---|---|
| Mission configs + balance numbers | `Missions.kt :: Missions.ALL`, `MissionConfig.kt` |
| In-flight mission stats | `MissionRun.kt :: MissionRun` |
| Weapon catalog | `Weapon.kt :: WeaponCatalog`, `WeaponId` |
| Ability catalog + slot runtime | `Ability.kt :: AbilityCatalog`, `AbilitySlot.kt :: AbilitySlot`, `Ability.kt :: AbilityId` |
| Upgrade catalog + apply-purchase | `UpgradeCatalog.kt :: UpgradeCatalog` |

## Persistence

| What | Where |
|---|---|
| Persistent player state (metal / upgrade levels) | `GameProgress.kt :: GameProgress` |
| SharedPreferences-backed live state | `ProgressRepository.kt :: ProgressRepository` |
