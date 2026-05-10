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

    // 1. Trapezoidal hull — two triangles span the four corners.
    mb.addTri(
        -sternHalfX, sternZ,    // stern-port
         sternHalfX, sternZ,    // stern-starboard
         bowHalfX,   bowZ,      // bow-starboard
        hullR, hullG, hullB,
    )
    mb.addTri(
        -sternHalfX, sternZ,    // stern-port
         bowHalfX,   bowZ,      // bow-starboard
        -bowHalfX,   bowZ,      // bow-port
        hullR, hullG, hullB,
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
    // Centre vertex: position (0,0,0), RGBA opaque, normal (0,1,0).
    vertices[0] = 0f; vertices[1] = 0f; vertices[2] = 0f
    vertices[3] = r;  vertices[4] = g;  vertices[5] = b; vertices[6] = 1f
    vertices[7] = 0f; vertices[8] = 1f; vertices[9] = 0f
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
        vertices[off + 7] = 0f
        vertices[off + 8] = 1f
        vertices[off + 9] = 0f
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
 * Wide flat shield arch. Vertices are placed in **world coordinates**
 * (pre-scaled to SHIELD_ARCH_HALF_W × SHIELD_ARCH_HALF_H), so the
 * SceneObject just needs scale=1 + a translation to platform top.
 * This avoids the directional thickness distortion that comes from
 * scaling a unit half-circle non-uniformly via SceneObject.scaleX/Z.
 *
 * Three concentric rings (inner / mid / outer) offset along the
 * outward ellipse normal by ±thickness/2, with per-vertex alpha
 * 0 / peak / 0 → smooth glow band of constant world-space thickness
 * around the arc. Same triangle-strip wiring as the legacy dome.
 */
internal fun buildShieldArchMesh(engine: EngineJni): Long {
    val sectors  = 64
    val halfW    = DraftCombat.SHIELD_ARCH_HALF_W
    val halfH    = DraftCombat.SHIELD_ARCH_HALF_H
    val tHalf    = DraftCombat.SHIELD_ARCH_THICKNESS * 0.5f
    val n        = DraftCombat.SHIELD_ARCH_SHARPNESS
    val pExp     = 2.0f / n            // parametric exponent: |c|^(2/n)
    val nExp     = 2.0f * (n - 1f) / n // gradient exponent for normal
    val r = 0.45f; val g = 0.75f; val b = 1.00f
    val alphas   = floatArrayOf(0f, DraftCombat.SHIELD_ARCH_PEAK_ALPHA, 0f)

    val nVertsPerArc = sectors + 1
    val nVerts = nVertsPerArc * 3
    val verts  = FloatArray(nVerts * 10)
    for (ring in 0..2) {
        val offMul = (ring - 1).toFloat()  // -1, 0, +1
        for (s in 0..sectors) {
            val ang = (s.toDouble() * Math.PI / sectors).toFloat()
            val c  = kotlin.math.cos(ang)
            val sV = kotlin.math.sin(ang)  // ≥ 0 on [0, π]
            val signC = if (c >= 0f) 1f else -1f
            val absC  = kotlin.math.abs(c)
            // Superellipse parametric form on the upper half:
            //   x/a = sign(cos θ) · |cos θ|^(2/n),  z/b = sin θ^(2/n).
            val ux = signC * absC.pow(pExp)
            val uz = sV.pow(pExp)
            // Outward normal = gradient of |x/a|^n + |z/b|^n − 1
            // ∝ (sign(x)·|x/a|^(n−1)/a, sign(z)·|z/b|^(n−1)/b)
            val gx = signC * absC.pow(nExp) / halfW
            val gz = sV.pow(nExp) / halfH
            val gl = kotlin.math.sqrt(gx * gx + gz * gz).coerceAtLeast(1e-6f)
            val nx = gx / gl
            val nz = gz / gl
            val px = ux * halfW + nx * tHalf * offMul
            val pz = uz * halfH + nz * tHalf * offMul
            val off = (ring * nVertsPerArc + s) * 10
            verts[off + 0] = px
            verts[off + 1] = 0f
            verts[off + 2] = pz
            verts[off + 3] = r; verts[off + 4] = g; verts[off + 5] = b
            verts[off + 6] = alphas[ring]
            verts[off + 7] = 0f; verts[off + 8] = 1f; verts[off + 9] = 0f
        }
    }
    val indices = ShortArray(2 * sectors * 6)
    var idx = 0
    for (strip in 0..1) {
        val r0 = strip; val r1 = strip + 1
        for (s in 0 until sectors) {
            val v0 = (r0 * nVertsPerArc + s    ).toShort()
            val v1 = (r0 * nVertsPerArc + s + 1).toShort()
            val v2 = (r1 * nVertsPerArc + s    ).toShort()
            val v3 = (r1 * nVertsPerArc + s + 1).toShort()
            indices[idx++] = v0; indices[idx++] = v1; indices[idx++] = v2
            indices[idx++] = v1; indices[idx++] = v3; indices[idx++] = v2
        }
    }
    return engine.loadMeshRaw(verts, indices)
}

/**
 * Static turret base — a chamfered slab sitting on the platform with a
 * brightly-coloured top accent stripe (red for central, blue for sides).
 * Origin at platform-top centre so a SceneObject just translates without
 * rotating. The slab's top edge is the rotation pivot of the matching
 * barrel mesh; both meshes share their X=0 line and the slab's top z = +height.
 */
internal fun buildTurretBaseMesh(
    engine: EngineJni,
    halfW: Float, height: Float,
    bodyR: Float, bodyG: Float, bodyB: Float,
    accentR: Float, accentG: Float, accentB: Float,
): Long {
    val mb = MeshBuilder()
    // Body — caller-supplied tint (steel-blue for central, dark-blue
    // for sides). Chamfered footprint breaks the rectangle silhouette
    // so the base reads as engineered hex plating rather than a brick.
    mb.addChamferedRect(-halfW, 0f, halfW, height, halfW * 0.30f, bodyR, bodyG, bodyB)
    // Vent slits on the bottom flanks (deep dark, layered above body).
    val ventDark = floatArrayOf(0.05f, 0.06f, 0.09f)
    val ventTop = height * 0.55f
    val ventBot = height * 0.18f
    val ventInset = halfW * 0.08f
    val ventThick = halfW * 0.05f
    mb.addRect(-halfW + ventInset, ventBot, -halfW + ventInset + ventThick, ventTop,
               ventDark[0], ventDark[1], ventDark[2], y = -0.005f)
    mb.addRect( halfW - ventInset - ventThick, ventBot,  halfW - ventInset, ventTop,
               ventDark[0], ventDark[1], ventDark[2], y = -0.005f)
    // Top accent stripe — colour-codes the turret type.
    mb.addRect(-halfW * 0.72f, height * 0.72f,
                halfW * 0.72f, height * 0.88f,
               accentR, accentG, accentB, y = -0.005f)
    return mb.upload(engine)
}

/**
 * Rotating housing + barrel + muzzle ring. Origin at the pivot (top of
 * the static base). The barrel extends along +Z so a SceneObject's
 * rotationY = 0 points the gun straight up the screen, matching the
 * legacy convention. Built from a few non-overlapping body chunks (Y=0)
 * plus thin overlay details (slits, fin, bore — at y=-0.005) so the
 * LESS-depth test renders the layered look without artefacts.
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
    val barrelStart = housingLength
    val muzzleStart = housingLength + barrelLength
    val tipZ        = muzzleStart + muzzleLength
    // Palette — body tint comes from caller (steel-blue for central,
    // dark-red for side barrels). Ring (muzzle collar) ≈ 60% of body
    // for a "machined" look; fin (cooling element) ≈ 130% (clamped) for
    // a brighter highlight; slits stay near-black for definition.
    val darkR = 0.08f; val darkG = 0.09f; val darkB = 0.12f
    val ringR = (bodyR * 0.6f).coerceIn(0f, 1f)
    val ringG = (bodyG * 0.6f).coerceIn(0f, 1f)
    val ringB = (bodyB * 0.6f).coerceIn(0f, 1f)
    val finR  = (bodyR * 1.30f).coerceIn(0f, 1f)
    val finG  = (bodyG * 1.30f).coerceIn(0f, 1f)
    val finB  = (bodyB * 1.30f).coerceIn(0f, 1f)
    // 1. Pivot collar — small dark band straddling the rotation axis.
    //    Sits half below the housing front so it's mostly hidden until
    //    the housing rotates off-axis, then reads as a turret ring.
    val collarHalfW = housingHalfW * 1.05f
    val collarHalfH = housingLength * 0.10f
    mb.addChamferedRect(
        -collarHalfW, -collarHalfH * 0.5f,
         collarHalfW,  collarHalfH * 0.5f,
        collarHalfW * 0.25f,
        ringR, ringG, ringB,
    )
    // 2. Housing — chamfered armoured box in accent colour.
    mb.addChamferedRect(
        -housingHalfW, collarHalfH * 0.5f,
         housingHalfW, housingLength,
        housingHalfW * 0.30f,
        accentR, accentG, accentB,
    )
    // 3. Two horizontal "vent slits" across the housing flanks.
    val slitTop = housingLength * 0.30f
    val slitBot = housingLength * 0.18f
    val slit2Top = housingLength * 0.66f
    val slit2Bot = housingLength * 0.54f
    mb.addRect(-housingHalfW * 0.78f, slitBot,  housingHalfW * 0.78f, slitTop,
               darkR, darkG, darkB, y = -0.005f)
    mb.addRect(-housingHalfW * 0.78f, slit2Bot, housingHalfW * 0.78f, slit2Top,
               darkR, darkG, darkB, y = -0.005f)
    // 4. Mantlet — short trapezoid-ish chunk where the barrel plugs into
    //    the housing front. Rendered as a chamfered rect for sci-fi feel.
    val mantletEnd = barrelStart + barrelLength * 0.05f
    mb.addChamferedRect(
        -housingHalfW * 0.55f, barrelStart,
         housingHalfW * 0.55f, mantletEnd,
        housingHalfW * 0.20f,
        bodyR, bodyG, bodyB,
    )
    // 5. Barrel — narrow rectangle in body grey.
    mb.addRect(-barrelHalfW, mantletEnd, barrelHalfW, muzzleStart,
               bodyR, bodyG, bodyB)
    // 6. Cooling fin — thin lighter band mid-barrel for visual interest.
    val finCenter = (mantletEnd + muzzleStart) * 0.5f
    val finHalfL  = barrelLength * 0.04f
    val finHalfW  = barrelHalfW * 1.6f
    mb.addRect(-finHalfW, finCenter - finHalfL,
                finHalfW, finCenter + finHalfL,
               finR, finG, finB, y = -0.005f)
    // 7. Muzzle ring — flange at the tip, slightly wider than barrel.
    mb.addChamferedRect(
        -muzzleHalfW, muzzleStart,
         muzzleHalfW, tipZ,
        muzzleHalfW * 0.20f,
        ringR, ringG, ringB,
    )
    // 8. Bore — dark inner core at the muzzle so the tip reads as a
    //    barrel opening rather than a solid plug.
    val boreHalfW = barrelHalfW * 0.75f
    mb.addRect(-boreHalfW, muzzleStart + muzzleLength * 0.22f,
                boreHalfW, tipZ - muzzleLength * 0.10f,
               darkR, darkG, darkB, y = -0.005f)
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
    // Sizes — 1.6× the original footprint for a more substantial dome.
    val baseHalfW  = 0.120f
    val baseHeight = 0.056f
    val domeRadius = 0.136f
    // Palette — cool grey-blue body with cyan seam accent.
    val baseR = 0.22f; val baseG = 0.26f; val baseB = 0.32f
    val domeR = 0.30f; val domeG = 0.36f; val domeB = 0.44f
    val accR  = 0.45f; val accG  = 0.85f; val accB  = 1.00f
    // 1. Static slab base — chamfered for sci-fi feel.
    mb.addChamferedRect(
        -baseHalfW, 0f, baseHalfW, baseHeight,
        baseHalfW * 0.30f,
        baseR, baseG, baseB,
    )
    // 2. Dome — sealed half-disk sitting on the base top.
    mb.addHalfDisk(0f, baseHeight, domeRadius, domeR, domeG, domeB, y = -0.002f)
    // 3. Cyan accent stripe at the seam between base and dome.
    mb.addRect(
        -baseHalfW * 0.85f, baseHeight - 0.008f,
         baseHalfW * 0.85f, baseHeight + 0.005f,
        accR, accG, accB, y = -0.004f,
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
    // Palette — body grey-blue (matches turret bases), warm orange for
    // warning stripes, very dark for the open launch tube.
    val baseR = 0.22f; val baseG = 0.24f; val baseB = 0.30f
    val midR  = 0.32f; val midG  = 0.34f; val midB  = 0.40f
    val rimR  = 0.40f; val rimG  = 0.42f; val rimB  = 0.48f
    val warnR = 0.95f; val warnG = 0.55f; val warnB = 0.20f
    val darkR = 0.04f; val darkG = 0.05f; val darkB = 0.08f
    // 1. Base/foundation — chamfered slab on the platform.
    mb.addChamferedRect(
        -baseHalfW, 0f, baseHalfW, baseTopZ,
        baseHalfW * 0.30f,
        baseR, baseG, baseB,
    )
    // 2. Mid tower body — chamfered rect, slightly narrower than base.
    mb.addChamferedRect(
        -midHalfW, baseTopZ, midHalfW, midTopZ,
        midHalfW * 0.22f,
        midR, midG, midB,
    )
    // 3. Rim/collar — wider band at the top giving the silo its mouth.
    mb.addChamferedRect(
        -rimHalfW, midTopZ, rimHalfW, rimTopZ,
        rimHalfW * 0.25f,
        rimR, rimG, rimB,
    )
    // 4. Two vertical warning stripes flanking the opening on the mid
    //    body. Layered above body to clear the LESS depth test.
    val stripeHalfW = 0.012f
    val stripeMidX = midHalfW * 0.55f
    mb.addRect(
        -stripeMidX - stripeHalfW, baseTopZ + 0.010f,
        -stripeMidX + stripeHalfW, midTopZ - 0.010f,
        warnR, warnG, warnB, y = -0.003f,
    )
    mb.addRect(
         stripeMidX - stripeHalfW, baseTopZ + 0.010f,
         stripeMidX + stripeHalfW, midTopZ - 0.010f,
        warnR, warnG, warnB, y = -0.003f,
    )
    // 5. Launch opening — dark rectangle cut into the top. Extends a bit
    //    below the rim so the dark tube reads as deep, not just a slit.
    mb.addRect(
        -openHalfW, midTopZ - 0.040f,
         openHalfW, rimTopZ - 0.005f,
        darkR, darkG, darkB, y = -0.006f,
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
