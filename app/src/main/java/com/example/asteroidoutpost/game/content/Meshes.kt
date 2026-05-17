package com.example.asteroidoutpost.game.content

import com.example.asteroidoutpost.EngineJni
import com.example.asteroidoutpost.game.combat.DraftCombat
import kotlin.math.pow

/**
 * Procedural mesh content for Asteroid Outpost. Each builder takes the
 * `EngineJni` it should upload to and returns a `Long` handle.
 *
 * Builders fall into two camps:
 *  - **Generic primitives** — soft disk (background nebulae), UV-sphere
 *    (3D fireball), unit textured quad (particle billboards), cone fan
 *    (muzzle blast). Nothing Outpost-specific in the geometry; reusable.
 *  - **Outpost-specific shapes** — shield arch (superellipse n=4), turret
 *    base / barrel, laser dome, rocket silo, homing rocket. Hardcoded
 *    dimensions + tints under Outpost's design language.
 *
 * Composition (which colours, which sizes for each turret, which order
 * to load) lives in `MainActivity.buildTurretMeshes()` /
 * `setupBackgroundNebulae()` — those compose these primitives.
 */

/**
 * Top-down-back view of the player's ship hull. The camera looks
 * forward over the bridge, so the ship's long axis runs vertically up
 * the screen (= along world Z) — wide stern at the bottom (camera-near),
 * trapezoidal deck narrowing toward the bow at the top.
 *
 * Authored in world units to match the legacy gray-quad platform
 * footprint (X half = 2.40, Z half = 0.275); the SceneObject just
 * translates onto platform position with scale = 1. Centerline runs at
 * x = 0; turrets / aux mounts sit on the deck at various Z values along
 * it (see `DraftCombat.CENTRAL_TURRET_BASE_Z`, `TURRET_TOP_Z`, etc.).
 */
/** Vertical extent of the ship hull (camera-far from deck). The top face
 *  stays at y=0 so deck-mounted geometry doesn't have to move; the volume
 *  drops to y=+HULL_Y_DEPTH which renders as the underside of the ship
 *  receding into shadow under the tilted camera. */
internal const val HULL_Y_DEPTH: Float = 0.10f

internal fun buildShipHullMesh(engine: EngineJni): Long {
    val mb = MeshBuilder()

    val sternHalfX = 2.40f   // wide back end (camera-near)
    val bowHalfX   = 0.30f   // bow narrows to a thin flat at the prow
    val sternZ     = -0.275f // bottom of platform Z range (camera-near)
    val bowZ       =  0.275f // top of platform Z range (forward)

    // Trapezoid X half-width at a given local Z (linear lerp stern→bow).
    // Used to size deck-spanning details (panel seams) so they stop right
    // at the hull edge regardless of where they sit along the length.
    fun hullHalfXAt(z: Float): Float {
        val t = ((z - sternZ) / (bowZ - sternZ)).coerceIn(0f, 1f)
        return sternHalfX + (bowHalfX - sternHalfX) * t
    }

    // Graphite-on-charcoal palette — darker than slate, closer to the
    // concept-art armoured plating. Cool cyan accent for engines and
    // running lights.
    val hullR = 0.28f; val hullG = 0.30f; val hullB = 0.36f
    val deckR = 0.35f; val deckG = 0.37f; val deckB = 0.43f
    val seamR = 0.16f; val seamG = 0.17f; val seamB = 0.22f
    val sternBlockR = 0.14f; val sternBlockG = 0.16f; val sternBlockB = 0.20f
    val cyanR = 0.30f; val cyanG = 0.85f; val cyanB = 0.95f
    val bowAccentR = 0.30f; val bowAccentG = 0.55f; val bowAccentB = 0.70f
    val mastR = 0.22f; val mastG = 0.24f; val mastB = 0.30f

    // 1. Trapezoidal hull — extruded as a single 3D prism. Top face (deck)
    //    sits at y=0; the volume extends DOWNWARD into the water (y > 0
    //    = farther from the camera in our convention), so everything that
    //    used to sit on the deck (turret bases, laser dome, rocket silo,
    //    shield arch) keeps its existing world Y unchanged.
    //
    //    Outline is CCW in (x, z): stern-port → stern-starboard up the right
    //    flank → bow-starboard → across the bow → bow-port → down the left
    //    flank → back to stern-port. All four corners are convex, so
    //    ear-clipping resolves to two triangles immediately.
    val hullOutline = listOf(
        -sternHalfX to sternZ,    // stern-port
         sternHalfX to sternZ,    // stern-starboard
         bowHalfX   to bowZ,      // bow-starboard
        -bowHalfX   to bowZ,      // bow-port
    )
    mb.addExtrudedPolygon(
        hullOutline,
        hullR, hullG, hullB,
        yTop = 0f, yBottom = HULL_Y_DEPTH,
    )

    // 2. Centerline deck stripe — slightly lighter band along the keel
    //    from stern engines to just before the bow gun. Layered above the
    //    hull via a small Y nudge so LESS-depth accepts it.
    mb.addRect(
        -0.14f, sternZ + 0.10f,
         0.14f, bowZ   - 0.04f,
        deckR, deckG, deckB, y = -0.005f,
    )

    // 3. Panel seams — three thin dark lines running across the deck
    //    perpendicular to the centerline. Suggests armour-segment joins
    //    and gives the hull readable scale without flooding it with
    //    detail. Each seam clamps to the trapezoid edge at its Z.
    val seamThickness = 0.012f
    val seamZs = floatArrayOf(-0.13f, +0.05f, +0.18f)
    for (sz in seamZs) {
        val halfX = hullHalfXAt(sz) - 0.03f   // 3 cm inset from edge
        mb.addRect(
            -halfX, sz - seamThickness * 0.5f,
             halfX, sz + seamThickness * 0.5f,
            seamR, seamG, seamB, y = -0.005f,
        )
    }

    // 4. Stern engine block — dark band along the very back edge.
    val blockTopZ = sternZ + 0.10f
    mb.addRect(
        -0.85f, sternZ,
         0.85f, blockTopZ,
        sternBlockR, sternBlockG, sternBlockB, y = -0.005f,
    )

    // 5. Five cyan engine exhausts inside the stern block — main drive
    //    nacelles glowing. Distributed evenly across the block width.
    val exhaustCount = 5
    val exhaustHalfW = 0.10f
    val exhaustGap   = 0.06f
    val exhaustHalfH = 0.025f
    val exhaustZ    = sternZ + 0.05f
    val exhaustSpan = exhaustCount * exhaustHalfW * 2f +
                      (exhaustCount - 1) * exhaustGap
    var exhaustX0 = -exhaustSpan * 0.5f
    for (i in 0 until exhaustCount) {
        val cx = exhaustX0 + exhaustHalfW
        mb.addRect(
            cx - exhaustHalfW, exhaustZ - exhaustHalfH,
            cx + exhaustHalfW, exhaustZ + exhaustHalfH,
            cyanR, cyanG, cyanB, y = -0.010f,
        )
        exhaustX0 += exhaustHalfW * 2f + exhaustGap
    }

    // 6. Antenna mast at the bow tip — thin vertical strip along the
    //    centerline just before the prow point. Reads as a sensor /
    //    radio mast, no gameplay role.
    mb.addRect(
        -0.020f, bowZ - 0.055f,
         0.020f, bowZ - 0.005f,
        mastR, mastG, mastB, y = -0.010f,
    )

    // 7. Bow accent — small coloured triangle just before the bow tip,
    //    suggests a forward running light / unit insignia.
    mb.addTri(
        -bowHalfX * 0.55f, bowZ - 0.07f,
         bowHalfX * 0.55f, bowZ - 0.07f,
         0f,               bowZ - 0.005f,
        bowAccentR, bowAccentG, bowAccentB, y = -0.005f,
    )

    return mb.upload(engine)
}

/**
 * Build a soft-edge disk mesh via `loadMeshRaw` (E1.3): a triangle fan
 * with the centre vertex fully opaque and the rim vertices at alpha=0.
 * Drawn through the translucent pipeline (E1.2) it reads as a soft round
 * blob with no visible quad edges. Disk lies in the X-Z plane to match
 * the existing camera convention.
 */
internal fun buildSoftDiskMesh(
    engine: EngineJni,
    r: Float, g: Float, b: Float,
    sectors: Int = 24,
): Long {
    val nVerts = sectors + 1
    val vertices = FloatArray(nVerts * 10)
    // Vertex normal points along the engine's main light direction
    // (`triangle.frag` hardcodes `lightDir = normalize(0.4, 0.6, 0.8)` =
    // (0.371, 0.557, 0.743)). Translucent nebulae are conceptually
    // emissive cloud patches — they shouldn't fall off when their plane
    // tilts away from the light. By baking light-aligned normals at mesh
    // build time, the shader's Lambertian `diff = max(dot(N, L), 0)`
    // returns ~1 regardless of any SceneObject rotation later applied
    // (Phase 6 tilts each nebula by `CAMERA_TILT_RAD` around X to stand
    // them up as billboards; with a +Y normal the dot would collapse to
    // ~0.04 and the clouds would render mostly as rim+ambient = dim grey).
    val nx = 0.371f; val ny = 0.557f; val nz = 0.743f
    // Centre vertex: position (0,0,0), RGBA opaque.
    vertices[0] = 0f; vertices[1] = 0f; vertices[2] = 0f
    vertices[3] = r;  vertices[4] = g;  vertices[5] = b; vertices[6] = 1f
    vertices[7] = nx; vertices[8] = ny; vertices[9] = nz
    // Rim vertices on a unit circle, alpha=0 so the colour fades out.
    for (s in 0 until sectors) {
        val ang = (s.toDouble() * 2.0 * Math.PI / sectors).toFloat()
        val off = (s + 1) * 10
        vertices[off + 0] = kotlin.math.cos(ang)
        vertices[off + 1] = 0f
        vertices[off + 2] = kotlin.math.sin(ang)
        vertices[off + 3] = r
        vertices[off + 4] = g
        vertices[off + 5] = b
        vertices[off + 6] = 0f  // transparent rim
        vertices[off + 7] = nx
        vertices[off + 8] = ny
        vertices[off + 9] = nz
    }
    // Triangle fan: centre → rim[s] → rim[s+1]
    val indices = ShortArray(sectors * 3)
    for (s in 0 until sectors) {
        indices[s * 3 + 0] = 0
        indices[s * 3 + 1] = (s + 1).toShort()
        indices[s * 3 + 2] = ((s + 1) % sectors + 1).toShort()
    }
    return engine.loadMeshRaw(vertices, indices)
}

/**
 * Static turret base — a chamfered armoured box sitting on the platform with a
 * brightly-coloured top accent stripe (red for central, blue for sides).
 * Origin at platform-top centre so a SceneObject just translates without
 * rotating. Extruded vertically so the base reads as a real 3D plinth under
 * the tilted camera; vents and accent stripe stay as flat overlays on the
 * top face.
 */
internal const val TURRET_BASE_Y_HEIGHT: Float = 0.06f
internal const val TURRET_BARREL_Y_HEIGHT: Float = 0.05f
internal const val TURRET_TOWER_Y_HEIGHT:  Float = 0.11f
internal const val TURRET_TOWER_HALF_W:    Float = 0.20f
internal const val TURRET_TOWER_HALF_L:    Float = 0.20f
internal const val TURRET_CANNON_LENGTH:   Float = 0.55f
internal const val TURRET_CANNON_HALF_W:   Float = 0.07f
internal const val TURRET_CANNON_HALF_THICK: Float = 0.07f
internal const val TURRET_CANNON_MUZZLE_LENGTH: Float = 0.08f
internal const val TURRET_CANNON_MUZZLE_HALF_W: Float = 0.10f

/**
 * World-Z of the cannon SceneObject, relative to `PLATFORM_TOP_Z`. The cannon
 * mesh sits on top of the static base + rotating tower, with a small anti-Z-fight
 * nudge. Single source of truth so the visual placement (SceneAssembler) and
 * the gameplay muzzle-spawn point (MissionRunner) stay in sync — drifting them
 * apart was the «выстрел не от ствола» bug.
 */
internal const val CENTRAL_CANNON_Z_ABOVE_PLATFORM: Float =
    TURRET_BASE_Y_HEIGHT + TURRET_TOWER_Y_HEIGHT + TURRET_CANNON_HALF_THICK + 0.015f

internal fun buildTurretBaseMesh(
    engine: EngineJni,
    halfW: Float, height: Float,
    bodyR: Float, bodyG: Float, bodyB: Float,
    accentR: Float, accentG: Float, accentB: Float,
): Long {
    val mb = MeshBuilder()
    val yTop = -TURRET_BASE_Y_HEIGHT
    // Body — caller-supplied tint (steel-blue for central, dark-blue
    // for sides). Chamfered footprint + vertical extrusion gives the
    // base real volume; side walls auto-shaded darker by the helper.
    mb.addExtrudedChamferedRect(
        -halfW, 0f, halfW, height, halfW * 0.30f,
        bodyR, bodyG, bodyB,
        yTop = yTop, yBottom = 0f,
    )
    // Vent slits on the bottom flanks (deep dark, on top face above body).
    val ventDark = floatArrayOf(0.05f, 0.06f, 0.09f)
    val ventTop = height * 0.55f
    val ventBot = height * 0.18f
    val ventInset = halfW * 0.08f
    val ventThick = halfW * 0.05f
    mb.addRect(-halfW + ventInset, ventBot, -halfW + ventInset + ventThick, ventTop,
               ventDark[0], ventDark[1], ventDark[2], y = yTop - 0.005f)
    mb.addRect( halfW - ventInset - ventThick, ventBot,  halfW - ventInset, ventTop,
               ventDark[0], ventDark[1], ventDark[2], y = yTop - 0.005f)
    // Top accent stripe — colour-codes the turret type.
    mb.addRect(-halfW * 0.72f, height * 0.72f,
                halfW * 0.72f, height * 0.88f,
               accentR, accentG, accentB, y = yTop - 0.005f)
    return mb.upload(engine)
}

/**
 * Tower — the rotating "head" that sits on the platform and carries the
 * cannon. Just a chamfered box (no barrel built in). Origin at the centre
 * of the bottom face (so a SceneObject just sits it atop the platform).
 * Extends camera-near in -Y (mesh-local) by TURRET_TOWER_Y_HEIGHT.
 * Concept «Вид 3»: tower rotates relative to platform; cannon (separate
 * mesh) rotates with it AND pitches further around its own axis.
 */
internal fun buildTurretTowerMesh(
    engine: EngineJni,
    bodyR: Float, bodyG: Float, bodyB: Float,
): Long {
    val mb = MeshBuilder()
    val halfW = TURRET_TOWER_HALF_W
    val halfL = TURRET_TOWER_HALF_L
    val yTop  = -TURRET_TOWER_Y_HEIGHT     // camera-near
    val yBot  = 0f
    val chamfer = 0.030f
    mb.addExtrudedChamferedRect(
        -halfW, -halfL, halfW, halfL, chamfer,
        bodyR, bodyG, bodyB, yTop = yTop, yBottom = yBot,
    )
    // Brighter accent stripe down the front face — gives the tower a
    // sense of "facing direction" so its rotation reads as the head
    // pointing somewhere, not just spinning anonymously.
    val accentR = (bodyR * 1.4f).coerceAtMost(1f)
    val accentG = (bodyG * 1.4f).coerceAtMost(1f)
    val accentB = (bodyB * 1.4f).coerceAtMost(1f)
    mb.addRect(
        -halfW * 0.35f, halfL - 0.012f,
         halfW * 0.35f, halfL + 0.001f,
        accentR, accentG, accentB,
        y = yTop - 0.005f,
    )
    return mb.upload(engine)
}

/**
 * Cannon — the barrel itself, extends +Z forward from the pitch pivot at
 * the origin. Designed so that with rotationY = yawAngle the barrel
 * points the same way as the legacy `buildTurretBarrelMesh` direction,
 * and an additional rotationX = pitchAngle elevates the tip up/down.
 * Includes a thicker muzzle ring at the +Z tip.
 */
internal fun buildTurretCannonMesh(
    engine: EngineJni,
    bodyR: Float, bodyG: Float, bodyB: Float,
): Long {
    val mb = MeshBuilder()
    val halfW       = TURRET_CANNON_HALF_W
    val length      = TURRET_CANNON_LENGTH
    val thick       = TURRET_CANNON_HALF_THICK
    val muzzleHalfW = TURRET_CANNON_MUZZLE_HALF_W
    val muzzleLen   = TURRET_CANNON_MUZZLE_LENGTH
    // Barrel body. Origin at Z=0 (pitch pivot); extends to Z = length−muzzleLen.
    // Body and muzzle ring used to share a wall at z=length−muzzleLen which,
    // after the Rx(-π/2) lay-flat, collapsed to a coplanar pair → Z-fight
    // shimmer ("smoke") at the junction. 3 mm gap between them removes the
    // overlap; the muzzle ring's outer geometry occludes the gap visually.
    val ringR = (bodyR * 0.65f).coerceIn(0f, 1f)
    val ringG = (bodyG * 0.65f).coerceIn(0f, 1f)
    val ringB = (bodyB * 0.65f).coerceIn(0f, 1f)
    val bodyEndZ   = length - muzzleLen - 0.003f
    val muzzleStartZ = length - muzzleLen
    mb.addExtrudedRect(
        -halfW, 0f, halfW, bodyEndZ,
        bodyR, bodyG, bodyB,
        yTop = -thick, yBottom = thick,
    )
    // Muzzle ring at the tip — chunkier, darker.
    mb.addExtrudedRect(
        -muzzleHalfW, muzzleStartZ,  muzzleHalfW, length,
        ringR, ringG, ringB,
        yTop = -thick * 1.25f, yBottom = thick * 1.25f,
    )
    return mb.upload(engine)
}

/**
 * Rotating housing + barrel + muzzle ring. Origin at the pivot (top of
 * the static base). The barrel extends along +Z so a SceneObject's
 * rotationY = 0 points the gun straight up the screen, matching the
 * legacy convention. Built from a few non-overlapping body chunks (Y=0)
 * plus thin overlay details (slits, fin, bore — at y=-0.005) so the
 * LESS-depth test renders the layered look without artefacts.
 *
 * Legacy: kept for fallback / non-concept turrets but mainline central +
 * side turrets now use buildTurretTowerMesh + buildTurretCannonMesh.
 */
internal fun buildTurretBarrelMesh(
    engine: EngineJni,
    housingHalfW: Float, housingLength: Float,
    barrelHalfW:  Float, barrelLength:  Float,
    muzzleHalfW:  Float, muzzleLength:  Float,
    bodyR: Float, bodyG: Float, bodyB: Float,
    accentR: Float, accentG: Float, accentB: Float,
): Long {
    val mb = MeshBuilder()
    val barrelStart  = housingLength
    val muzzleStart  = housingLength + barrelLength
    val tipZ         = muzzleStart + muzzleLength
    val collarHalfW  = housingHalfW * 1.05f
    val collarHalfH  = housingLength * 0.10f          // total Z-extent of collar
    val mantletHalfW = housingHalfW * 0.55f
    val mantletEnd   = barrelStart + barrelLength * 0.05f
    // Chamfers — clamped so they never exceed the section's smaller half-extent.
    // Collar's Z-extent is 0.020, mantlet's is 0.017 — both too small for any
    // meaningful chamfer, so those two sections fall back to plain rects in the
    // outline (no chamfer corners). Housing & muzzle ring are tall enough for a
    // proper chamfer.
    val chH  = (housingHalfW * 0.30f).coerceAtMost((housingLength - collarHalfH * 0.5f) * 0.4f)
    val chMz = (muzzleHalfW * 0.20f).coerceAtMost(muzzleLength * 0.4f)

    val yTop = -TURRET_BARREL_Y_HEIGHT
    // Palette — body tint comes from caller; ring (collar / muzzle ring) ≈
    // 60% of body for a "machined" look; fin (cooling element) ≈ 130% for a
    // brighter highlight; slits stay near-black for definition.
    val darkR = 0.08f; val darkG = 0.09f; val darkB = 0.12f
    val ringR = (bodyR * 0.6f).coerceIn(0f, 1f)
    val ringG = (bodyG * 0.6f).coerceIn(0f, 1f)
    val ringB = (bodyB * 0.6f).coerceIn(0f, 1f)
    val finR  = (bodyR * 1.30f).coerceIn(0f, 1f)
    val finG  = (bodyG * 1.30f).coerceIn(0f, 1f)
    val finB  = (bodyB * 1.30f).coerceIn(0f, 1f)

    // Single CCW outline of the union (collar ∪ housing ∪ mantlet ∪ barrel
    // ∪ muzzle ring). One monolithic extruded polygon = no internal junction
    // walls = no rotational z-fight. Section colour differentiation comes
    // from flat 2D overlays on the top face below.
    //
    // Vertex order: starts at collar back-right, goes CCW (up the right
    // side, across the muzzle front, down the left side, across the collar
    // back). 28 verts total — concave at each inward step (collar→housing,
    // housing→mantlet, mantlet→barrel) and at each outward step (back from
    // muzzle, mantlet, housing, collar).
    val outline = listOf(
        // RIGHT side, going forward (back → bow)
         collarHalfW         to -collarHalfH * 0.5f,                 //  0
         collarHalfW         to  collarHalfH * 0.5f,                 //  1
        (housingHalfW - chH) to  collarHalfH * 0.5f,                 //  2  inward step → housing
         housingHalfW        to  collarHalfH * 0.5f + chH,           //  3
         housingHalfW        to  housingLength - chH,                //  4
        (housingHalfW - chH) to  housingLength,                      //  5
         mantletHalfW        to  housingLength,                      //  6  inward step → mantlet
         mantletHalfW        to  mantletEnd,                         //  7
         barrelHalfW         to  mantletEnd,                         //  8  inward step → barrel
         barrelHalfW         to  muzzleStart,                        //  9
        (muzzleHalfW - chMz) to  muzzleStart,                        // 10  outward step → muzzle ring
         muzzleHalfW         to  muzzleStart + chMz,                 // 11
         muzzleHalfW         to  tipZ - chMz,                        // 12
        (muzzleHalfW - chMz) to  tipZ,                               // 13
        // FRONT edge (across muzzle tip)
        (-muzzleHalfW + chMz) to  tipZ,                              // 14
         -muzzleHalfW         to  tipZ - chMz,                       // 15
        // LEFT side, going back (bow → stern)
         -muzzleHalfW         to  muzzleStart + chMz,                // 16
        (-muzzleHalfW + chMz) to  muzzleStart,                       // 17
         -barrelHalfW         to  muzzleStart,                       // 18  inward step
         -barrelHalfW         to  mantletEnd,                        // 19
         -mantletHalfW        to  mantletEnd,                        // 20  outward step
         -mantletHalfW        to  housingLength,                     // 21
        (-housingHalfW + chH) to  housingLength,                     // 22  outward step → housing
         -housingHalfW        to  housingLength - chH,               // 23
         -housingHalfW        to  collarHalfH * 0.5f + chH,          // 24
        (-housingHalfW + chH) to  collarHalfH * 0.5f,                // 25
         -collarHalfW         to  collarHalfH * 0.5f,                // 26  outward step → collar
         -collarHalfW         to -collarHalfH * 0.5f,                // 27
        // BACK edge closes implicitly between vert 27 and vert 0.
    )
    mb.addExtrudedPolygon(outline, bodyR, bodyG, bodyB, yTop = yTop, yBottom = 0f)

    // Top-face colour overlays — distinct sections paint over the body grey.
    // ovY sits 0.005 above (camera-near of) the body's top face so the LESS
    // depth test picks the overlay deterministically.
    val ovY = yTop - 0.005f
    // Collar tint — plain rect (its Z-extent is too short for a chamfer).
    mb.addRect(
        -collarHalfW, -collarHalfH * 0.5f,
         collarHalfW,  collarHalfH * 0.5f,
        ringR, ringG, ringB, y = ovY,
    )
    // Housing accent.
    mb.addChamferedRect(
        -housingHalfW, collarHalfH * 0.5f,
         housingHalfW, housingLength,
        chH,
        accentR, accentG, accentB, y = ovY,
    )
    // Muzzle ring tint.
    mb.addChamferedRect(
        -muzzleHalfW, muzzleStart,
         muzzleHalfW, tipZ,
        chMz,
        ringR, ringG, ringB, y = ovY,
    )

    // Inner-detail overlays (slits, fin, bore). Slightly above the colour
    // overlays so they win the LESS test.
    val ovY2 = ovY - 0.001f
    val slitTop  = housingLength * 0.30f
    val slitBot  = housingLength * 0.18f
    val slit2Top = housingLength * 0.66f
    val slit2Bot = housingLength * 0.54f
    mb.addRect(-housingHalfW * 0.78f, slitBot,  housingHalfW * 0.78f, slitTop,
               darkR, darkG, darkB, y = ovY2)
    mb.addRect(-housingHalfW * 0.78f, slit2Bot, housingHalfW * 0.78f, slit2Top,
               darkR, darkG, darkB, y = ovY2)
    val finCenter = (mantletEnd + muzzleStart) * 0.5f
    val finHalfL  = barrelLength * 0.04f
    val finHalfW  = barrelHalfW * 1.6f
    mb.addRect(-finHalfW, finCenter - finHalfL,
                finHalfW, finCenter + finHalfL,
               finR, finG, finB, y = ovY2)
    val boreHalfW = barrelHalfW * 0.75f
    mb.addRect(-boreHalfW, muzzleStart + muzzleLength * 0.22f,
                boreHalfW, tipZ - muzzleLength * 0.10f,
               darkR, darkG, darkB, y = ovY2)
    return mb.upload(engine)
}

/**
 * Procedural laser-installation mesh — a chamfered slab base topped with
 * a sealed hemispherical dome, sized 1.6× the original silhouette.
 * Cyan-blue accent stripe at the base/dome seam ties the visual to the
 * laser-strike ability VFX (E12 lightning bolts also cyan). Single
 * static mesh — nothing rotates.
 */
internal fun buildLaserInstallationMesh(engine: EngineJni): Long {
    val mb = MeshBuilder()
    val baseHalfW  = 0.120f
    val baseHeight = 0.056f          // Z-extent of the slab
    val domeRadius = 0.110f          // base-ring radius of the hemispherical dome
    val domeHeight = 0.110f          // Y-extent (apex height above slab top)
    val yTop = -TURRET_BASE_Y_HEIGHT
    // Palette — cool grey-blue body with cyan seam accent.
    val baseR = 0.22f; val baseG = 0.26f; val baseB = 0.32f
    val domeR = 0.30f; val domeG = 0.36f; val domeB = 0.44f
    val accR  = 0.45f; val accG  = 0.85f; val accB  = 1.00f
    // 1. Static slab base — extruded chamfered prism.
    mb.addExtrudedChamferedRect(
        -baseHalfW, 0f, baseHalfW, baseHeight,
        baseHalfW * 0.30f,
        baseR, baseG, baseB,
        yTop = yTop, yBottom = 0f,
    )
    // 2. Cyan accent stripe at the slab/dome seam — sits on the slab's
    //    top face just behind the dome's base ring footprint.
    mb.addRect(
        -baseHalfW * 0.85f, baseHeight - 0.008f,
         baseHalfW * 0.85f, baseHeight + 0.005f,
        accR, accG, accB, y = yTop - 0.004f,
    )
    // 3. 3D hemispherical dome — radar-style bulb sitting on the slab top.
    //    Centred at the slab's z-mid and apex pointing toward camera. The
    //    base ring sits a hair (0.001) above slab top so LESS-depth picks
    //    the dome over the slab's chamfered top face along the seam.
    mb.addHemisphere(
        cx = 0f, cz = baseHeight * 0.5f,
        radius = domeRadius,
        baseY = yTop - 0.001f,
        apexY = yTop - domeHeight,
        r = domeR, g = domeG, b = domeB,
    )
    return mb.upload(engine)
}

/**
 * Procedural rocket-silo top — chamfered foundation + mid-section + a
 * slightly wider rim, with a dark launch tube cut into the centre and
 * two warning stripes flanking the opening so the silhouette reads as
 * a rocket silo rather than a generic post. Sits on the platform on
 * the LEFT side of the central turret (mirror of the laser install).
 * Single static mesh, no rotation.
 */
internal fun buildRocketSiloMesh(engine: EngineJni): Long {
    val mb = MeshBuilder()
    // Sizes — total height ~0.15 from platform top, max half-width 0.13.
    val baseHalfW = 0.130f
    val baseTopZ  = 0.040f
    val midHalfW  = 0.100f
    val midTopZ   = 0.115f
    val rimHalfW  = 0.108f
    val rimTopZ   = 0.150f          // = ROCKET_SILO_MUZZLE_OFFSET + 0.02
    val openHalfW = 0.070f
    // Palette — warm industrial steel (rust/bronze) so the silo reads
    // distinctly against the cool grey-blue hull instead of blending in.
    // Warm orange warning stripes flank the launch opening; very dark
    // launch tube reads as a deep cavity.
    val baseR = 0.42f; val baseG = 0.32f; val baseB = 0.22f
    val midR  = 0.55f; val midG  = 0.42f; val midB  = 0.28f
    val rimR  = 0.68f; val rimG  = 0.52f; val rimB  = 0.32f
    val warnR = 0.95f; val warnG = 0.55f; val warnB = 0.20f
    val darkR = 0.04f; val darkG = 0.05f; val darkB = 0.08f
    // Three Y tiers so the silo reads as stepped: foundation slab is the
    // shortest, mid tower mid-height, rim collar tallest. Total height
    // bumped up so the silo stands above the turret bases and is harder
    // to lose against the deck under perspective.
    val baseYTop = -TURRET_BASE_Y_HEIGHT * 0.85f         // foundation
    val midYTop  = -TURRET_BASE_Y_HEIGHT - 0.040f        // mid tower
    val rimYTop  = -TURRET_BASE_Y_HEIGHT - 0.080f        // rim/collar (tallest)
    // 1. Base/foundation — extruded chamfered prism on the platform.
    mb.addExtrudedChamferedRect(
        -baseHalfW, 0f, baseHalfW, baseTopZ,
        baseHalfW * 0.30f,
        baseR, baseG, baseB,
        yTop = baseYTop, yBottom = 0f,
    )
    // 2. Mid tower body — narrower box stepped above the foundation.
    mb.addExtrudedChamferedRect(
        -midHalfW, baseTopZ, midHalfW, midTopZ,
        midHalfW * 0.22f,
        midR, midG, midB,
        yTop = midYTop, yBottom = baseYTop,
    )
    // 3. Rim/collar — widest at the top, tallest of the three tiers.
    mb.addExtrudedChamferedRect(
        -rimHalfW, midTopZ, rimHalfW, rimTopZ,
        rimHalfW * 0.25f,
        rimR, rimG, rimB,
        yTop = rimYTop, yBottom = midYTop,
    )
    // 4. Two vertical warning stripes on the mid body's top face.
    val stripeHalfW = 0.012f
    val stripeMidX = midHalfW * 0.55f
    mb.addRect(
        -stripeMidX - stripeHalfW, baseTopZ + 0.010f,
        -stripeMidX + stripeHalfW, midTopZ - 0.010f,
        warnR, warnG, warnB, y = midYTop - 0.003f,
    )
    mb.addRect(
         stripeMidX - stripeHalfW, baseTopZ + 0.010f,
         stripeMidX + stripeHalfW, midTopZ - 0.010f,
        warnR, warnG, warnB, y = midYTop - 0.003f,
    )
    // 5. Launch opening — dark rectangle cut into the rim's top face.
    mb.addRect(
        -openHalfW, midTopZ - 0.040f,
         openHalfW, rimTopZ - 0.005f,
        darkR, darkG, darkB, y = rimYTop - 0.003f,
    )
    return mb.upload(engine)
}

/**
 * Procedural homing-rocket mesh. Origin at the rocket's geometric centre
 * so the SceneObject-based AABB collision matches roughly. Body axis is
 * along +Z so a rotationY of `atan2(vx, vz)` (no yaw offset) aligns the
 * nose with the velocity vector. Total length = ROCKET_BODY_LENGTH;
 * vertices span Z ∈ [-LENGTH/2, +LENGTH/2].
 *
 * Components (in mesh space, +Z = forward = nose):
 *   • Engine bell at the back (chamfered, dark) — hint of exhaust nozzle
 *   • Body — main grey cylinder (rect in this 2D side-view)
 *   • Two side fins — flared triangles at the base for "missile" silhouette
 *   • Nose cone — triangle tapering to a point
 *   • Warning stripe — thin orange band on body, layered above for depth
 */
internal fun buildRocketMesh(engine: EngineJni): Long {
    val mb = MeshBuilder()
    val length    = DraftCombat.ROCKET_BODY_LENGTH
    val backZ     = -length * 0.5f                 // engine end
    val noseZ     =  length * 0.5f                 // tip end
    val bellZ     = backZ + length * 0.10f         // top of bell
    val finZ      = backZ + length * 0.30f         // top of fins
    val bodyZ     = backZ + length * 0.70f         // top of straight body, start of cone
    val bodyHalfW = 0.030f
    val bellHalfW = 0.045f
    val finHalfW  = 0.058f
    // Palette
    val bodyR = 0.70f; val bodyG = 0.72f; val bodyB = 0.76f
    val bellR = 0.16f; val bellG = 0.18f; val bellB = 0.22f
    val finR  = 0.55f; val finG  = 0.58f; val finB  = 0.65f
    val warnR = 0.95f; val warnG = 0.55f; val warnB = 0.20f
    // 1. Engine bell — slightly flared chamfered slab.
    mb.addChamferedRect(
        -bellHalfW, backZ, bellHalfW, bellZ,
        bellHalfW * 0.30f,
        bellR, bellG, bellB,
    )
    // 2. Body — main rect from bell to nose-cone start.
    mb.addRect(
        -bodyHalfW, bellZ, bodyHalfW, bodyZ,
        bodyR, bodyG, bodyB,
    )
    // 3. Side fins — triangles from body flank out to finHalfW.
    mb.addTri(
        -bodyHalfW, bellZ,        // attach lower
        -finHalfW,  bellZ,        // outer tip at base
        -bodyHalfW, finZ,         // attach upper
        finR, finG, finB,
    )
    mb.addTri(
         bodyHalfW, bellZ,
         bodyHalfW, finZ,
         finHalfW,  bellZ,
        finR, finG, finB,
    )
    // 4. Nose cone — triangle from body shoulder to tip.
    mb.addTri(
        -bodyHalfW, bodyZ,
         bodyHalfW, bodyZ,
         0f,        noseZ,
        bodyR, bodyG, bodyB,
    )
    // 5. Warning stripe — thin orange band on the body.
    val stripeMidZ = (bellZ + bodyZ) * 0.5f
    val stripeHalfL = length * 0.04f
    mb.addRect(
        -bodyHalfW * 0.85f, stripeMidZ - stripeHalfL,
         bodyHalfW * 0.85f, stripeMidZ + stripeHalfL,
        warnR, warnG, warnB, y = -0.003f,
    )
    return mb.upload(engine)
}

/**
 * E7.1 — procedural UV-sphere for the 3D fireball. Y-axis aligned (poles
 * at ±Y) so the fragment shader's `abs(vNormal.y)` Fresnel reads as
 * "facing camera" under this project's pitch=π/2 camera (camera looks
 * along ±Y, see Camera::reset). Per-vertex colour white and alpha 1 —
 * tint and overall brightness come from per-draw `pc.plasmaColor`.
 * Default 12×16 = 384 tris (under 65k uint16 index ceiling, cheap).
 */
internal fun buildFireballSphereMesh(
    engine: EngineJni,
    stacks: Int = 12, slices: Int = 16,
): Long {
    val nVerts = (stacks + 1) * (slices + 1)
    val vertices = FloatArray(nVerts * 10)
    var off = 0
    for (i in 0..stacks) {
        val theta = i.toDouble() * Math.PI / stacks
        val sinT = kotlin.math.sin(theta).toFloat()
        val cosT = kotlin.math.cos(theta).toFloat()
        for (j in 0..slices) {
            val phi = j.toDouble() * 2.0 * Math.PI / slices
            val sinP = kotlin.math.sin(phi).toFloat()
            val cosP = kotlin.math.cos(phi).toFloat()
            val x = sinT * cosP
            val y = cosT
            val z = sinT * sinP
            vertices[off + 0] = x
            vertices[off + 1] = y
            vertices[off + 2] = z
            vertices[off + 3] = 1f; vertices[off + 4] = 1f; vertices[off + 5] = 1f
            vertices[off + 6] = 1f
            vertices[off + 7] = x; vertices[off + 8] = y; vertices[off + 9] = z
            off += 10
        }
    }
    val nTris = stacks * slices * 2
    val indices = ShortArray(nTris * 3)
    var idx = 0
    for (i in 0 until stacks) {
        for (j in 0 until slices) {
            val a = (i * (slices + 1) + j).toShort()
            val b = (i * (slices + 1) + j + 1).toShort()
            val c = ((i + 1) * (slices + 1) + j).toShort()
            val d = ((i + 1) * (slices + 1) + j + 1).toShort()
            indices[idx++] = a; indices[idx++] = c; indices[idx++] = b
            indices[idx++] = b; indices[idx++] = c; indices[idx++] = d
        }
    }
    return engine.loadMeshRaw(vertices, indices)
}

/**
 * E20 — shield force-field hemisphere mesh: unit half-sphere covering
 * y ≥ 0 (front hemisphere when SceneObject is at ship origin with no
 * rotation). Per-vertex normal = position (unit sphere → normal points
 * outward from origin). Used by the dedicated forcefield pipeline which
 * runs a fresnel + impact-bloom fragment shader; the mesh itself just
 * supplies position + outward normal.
 *
 * Stacks = 8 vertex rings from pole (y=1) to equator (y=0). Slices = 24
 * around the polar axis. Total 8·24·2 = 384 tris, same order as the
 * fireball mesh.
 */
internal fun buildShieldHemisphereMesh(
    engine: EngineJni,
    stacks: Int = com.example.asteroidoutpost.game.combat.DraftCombat.SHIELD_HEMISPHERE_STACKS,
    slices: Int = com.example.asteroidoutpost.game.combat.DraftCombat.SHIELD_HEMISPHERE_SLICES,
): Long {
    val nVerts = (stacks + 1) * (slices + 1)
    val vertices = FloatArray(nVerts * 10)
    var off = 0
    for (i in 0..stacks) {
        // theta in [0, π/2] — pole at i=0, equator at i=stacks.
        val theta = i.toDouble() * (Math.PI * 0.5) / stacks
        val sinT = kotlin.math.sin(theta).toFloat()
        val cosT = kotlin.math.cos(theta).toFloat()
        for (j in 0..slices) {
            val phi = j.toDouble() * 2.0 * Math.PI / slices
            val sinP = kotlin.math.sin(phi).toFloat()
            val cosP = kotlin.math.cos(phi).toFloat()
            val x = sinT * cosP
            val y = cosT
            val z = sinT * sinP
            vertices[off + 0] = x
            vertices[off + 1] = y
            vertices[off + 2] = z
            vertices[off + 3] = 1f; vertices[off + 4] = 1f; vertices[off + 5] = 1f
            vertices[off + 6] = 1f
            // Normal = position (unit sphere centred at origin).
            vertices[off + 7] = x; vertices[off + 8] = y; vertices[off + 9] = z
            off += 10
        }
    }
    val nTris = stacks * slices * 2
    val indices = ShortArray(nTris * 3)
    var idx = 0
    for (i in 0 until stacks) {
        for (j in 0 until slices) {
            val a = (i * (slices + 1) + j).toShort()
            val b = (i * (slices + 1) + j + 1).toShort()
            val c = ((i + 1) * (slices + 1) + j).toShort()
            val d = ((i + 1) * (slices + 1) + j + 1).toShort()
            indices[idx++] = a; indices[idx++] = c; indices[idx++] = b
            indices[idx++] = b; indices[idx++] = c; indices[idx++] = d
        }
    }
    return engine.loadMeshRaw(vertices, indices)
}

/**
 * E11 — muzzle-blast cone mesh: triangle fan in the X-Z plane with
 * ±15° aperture (30° total wedge) around local +Z, radius 1, alpha 1
 * everywhere. The plasma fragment shader's radial soft-fade
 * (`plasmaSoftFade()`, smoothstep(0.4, 1.0, length(vLocalXZ))) does the
 * fade — alpha 1 at origin → 0 at perimeter — so each cone reads as a
 * wispy fire wedge fading outward. Forward = +Z so a `rotation=0`
 * cone aligns to the screen-up direction; spawnMuzzleBlast feeds in
 * three rotations 120° apart for the trefoil pattern.
 *
 * Default 12 segments → 12 triangles, ~13 vertices. Sub-pixel detail
 * comes from FBM turbulence in the fragment shader, not from mesh
 * tessellation, so a low segment count is fine.
 */
internal fun buildMuzzleConeMesh(engine: EngineJni, segments: Int = 12): Long {
    val aperture = (30.0 * Math.PI / 180.0).toFloat()  // total cone width in radians
    val halfAp   = aperture * 0.5f

    // 1 centre vertex + (segments+1) perimeter vertices, 10 floats each.
    val nVerts = 1 + (segments + 1)
    val vertices = FloatArray(nVerts * 10)
    // Centre vertex at origin — alpha 1, rest of fields don't matter for the
    // plasma fragment branch (it doesn't read normal, and uv stays default).
    vertices[0] = 0f; vertices[1] = 0f; vertices[2] = 0f
    vertices[3] = 1f; vertices[4] = 1f; vertices[5] = 1f; vertices[6] = 1f
    vertices[7] = 0f; vertices[8] = 1f; vertices[9] = 0f

    var off = 10
    for (i in 0..segments) {
        // phi sweeps from -halfAp to +halfAp; phi=0 puts the vertex at +Z
        // (forward), so the fan opens forward. The cos/sin assignment maps
        // phi=0 → (0, 0, 1), phi=+halfAp → (sin(halfAp), 0, cos(halfAp)).
        val phi  = -halfAp + (aperture / segments) * i
        val px   = kotlin.math.sin(phi)
        val pz   = kotlin.math.cos(phi)
        vertices[off + 0] = px
        vertices[off + 1] = 0f
        vertices[off + 2] = pz
        vertices[off + 3] = 1f; vertices[off + 4] = 1f; vertices[off + 5] = 1f
        vertices[off + 6] = 1f
        vertices[off + 7] = 0f; vertices[off + 8] = 1f; vertices[off + 9] = 0f
        off += 10
    }

    // Triangle fan: (centre=0, perim_i, perim_i+1) for i in 1..segments.
    val indices = ShortArray(segments * 3)
    var idx = 0
    for (i in 1..segments) {
        indices[idx++] = 0
        indices[idx++] = i.toShort()
        indices[idx++] = (i + 1).toShort()
    }

    return engine.loadMeshRaw(vertices, indices)
}

/**
 * E9 — unit UV-mapped X-Z plane quad for particles. Same primitive as
 * the E8.4 textured-quad smoke test, regenerated here because it lives
 * permanently and the smoke-test version was retired. Particle vertex
 * shader uses inPosition.xz for billboarding + soft-fade radius.
 */
internal fun buildParticleQuadMesh(engine: EngineJni): Long {
    val verts = floatArrayOf(
        //  x,   y,    z,    r, g, b, a,    nx, ny, nz,   u,  v
        -1f, 0f, -1f,  1f, 1f, 1f, 1f,  0f, 1f, 0f,  0f, 0f,
         1f, 0f, -1f,  1f, 1f, 1f, 1f,  0f, 1f, 0f,  1f, 0f,
         1f, 0f,  1f,  1f, 1f, 1f, 1f,  0f, 1f, 0f,  1f, 1f,
        -1f, 0f,  1f,  1f, 1f, 1f, 1f,  0f, 1f, 0f,  0f, 1f,
    )
    val indices = shortArrayOf(0, 1, 2, 0, 2, 3)
    return engine.loadMeshRawUV(verts, indices)
}
