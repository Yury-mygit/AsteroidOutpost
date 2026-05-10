package com.example.asteroidoutpost.game.content

import com.example.asteroidoutpost.EngineJni

/**
 * Procedural mesh assembly helper. Each `addRect` / `addChamferedRect` /
 * `addTri` / `addHalfDisk` emits a flat polygon in the X-Z plane (Y=0 by
 * default, with a small `y` offset for layered details so the LESS-depth
 * test doesn't reject overlay fragments at equal depth — same trick as
 * the HP-bar fill at y=-0.01). Top-facing primitives stamp normal=(0,1,0)
 * to receive the engine's main directional light (see triangle.frag —
 * `lightDir = normalize(0.4, 0.6, 0.8)`).
 *
 * **Phase 5 (E15) — real per-fragment lighting.** Extruded volumes
 * (`addExtrudedRect/Polygon/ChamferedRect`, `addHemisphere`) compute
 * **outward** normals per face — top faces (0,1,0), side walls radial
 * in xz, hemisphere normals radial-plus-flipped-y so the apex faces +Y
 * (matching the shader's main light direction). The shader's existing
 * Lambertian + fill + rim model then differentiates faces naturally,
 * making the old per-vertex `sideShade` darkening obsolete.
 *
 * `upload(engine)` materialises to a `loadMeshRaw` handle.
 *
 * Originally written for sci-fi turret meshes (M10/M11) — kept generic so
 * the silo, rocket, and laser-dome procedural meshes share the same primitive
 * vocabulary.
 */
internal class MeshBuilder {
    private val verts = ArrayList<Float>()
    private val indices = ArrayList<Short>()
    private fun addVert(
        x: Float, y: Float, z: Float,
        r: Float, g: Float, b: Float, a: Float,
        nx: Float = 0f, ny: Float = 1f, nz: Float = 0f,
    ) {
        verts.add(x); verts.add(y); verts.add(z)
        verts.add(r); verts.add(g); verts.add(b); verts.add(a)
        verts.add(nx); verts.add(ny); verts.add(nz)
    }
    /** Plain axis-aligned rectangle in the X-Z plane. */
    fun addRect(
        x0: Float, z0: Float, x1: Float, z1: Float,
        r: Float, g: Float, b: Float,
        a: Float = 1f, y: Float = 0f,
    ) {
        val base = (verts.size / 10).toShort()
        addVert(x0, y, z0, r, g, b, a)
        addVert(x1, y, z0, r, g, b, a)
        addVert(x1, y, z1, r, g, b, a)
        addVert(x0, y, z1, r, g, b, a)
        indices.add(base)
        indices.add((base + 1).toShort())
        indices.add((base + 2).toShort())
        indices.add(base)
        indices.add((base + 2).toShort())
        indices.add((base + 3).toShort())
    }
    /** Chamfered rectangle (octagonal silhouette) — sci-fi armor plating. */
    fun addChamferedRect(
        x0: Float, z0: Float, x1: Float, z1: Float, chamfer: Float,
        r: Float, g: Float, b: Float,
        a: Float = 1f, y: Float = 0f,
    ) {
        val base = (verts.size / 10).toShort()
        addVert(x0 + chamfer, y, z0,           r, g, b, a)  // 0: bot-edge L
        addVert(x1 - chamfer, y, z0,           r, g, b, a)  // 1: bot-edge R
        addVert(x1,           y, z0 + chamfer, r, g, b, a)  // 2: right-edge bot
        addVert(x1,           y, z1 - chamfer, r, g, b, a)  // 3: right-edge top
        addVert(x1 - chamfer, y, z1,           r, g, b, a)  // 4: top-edge R
        addVert(x0 + chamfer, y, z1,           r, g, b, a)  // 5: top-edge L
        addVert(x0,           y, z1 - chamfer, r, g, b, a)  // 6: left-edge top
        addVert(x0,           y, z0 + chamfer, r, g, b, a)  // 7: left-edge bot
        val cx = (x0 + x1) * 0.5f; val cz = (z0 + z1) * 0.5f
        addVert(cx, y, cz, r, g, b, a)                       // 8: center
        for (i in 0..7) {
            val a2 = i; val b2 = (i + 1) % 8
            indices.add((base + 8).toShort())
            indices.add((base + a2).toShort())
            indices.add((base + b2).toShort())
        }
    }
    /** Plain triangle in the X-Z plane. Useful for fins, nose cones, etc. */
    fun addTri(
        x0: Float, z0: Float,
        x1: Float, z1: Float,
        x2: Float, z2: Float,
        r: Float, g: Float, b: Float,
        a: Float = 1f, y: Float = 0f,
    ) {
        val base = (verts.size / 10).toShort()
        addVert(x0, y, z0, r, g, b, a)
        addVert(x1, y, z1, r, g, b, a)
        addVert(x2, y, z2, r, g, b, a)
        indices.add(base)
        indices.add((base + 1).toShort())
        indices.add((base + 2).toShort())
    }
    /**
     * Extruded rectangle — top face at `yTop`, four side walls dropping to
     * `yBottom`. Bottom face is omitted because the camera looks down at
     * the deck plane (y > 0 = farther from camera) and never sees it.
     * Each side wall emits its own pair of top/bottom vertices with the
     * wall's outward normal so the engine's Lambertian shader lights each
     * face individually (top vs front vs right vs back vs left). Top face
     * vertices keep the default (0,1,0) normal.
     */
    fun addExtrudedRect(
        x0: Float, z0: Float, x1: Float, z1: Float,
        r: Float, g: Float, b: Float,
        a: Float = 1f,
        yTop: Float, yBottom: Float,
    ) {
        // Top face — 4 verts with default upward normal.
        val baseTop = (verts.size / 10).toShort()
        addVert(x0, yTop, z0, r, g, b, a)
        addVert(x1, yTop, z0, r, g, b, a)
        addVert(x1, yTop, z1, r, g, b, a)
        addVert(x0, yTop, z1, r, g, b, a)
        indices.add(baseTop)
        indices.add((baseTop + 1).toShort())
        indices.add((baseTop + 2).toShort())
        indices.add(baseTop)
        indices.add((baseTop + 2).toShort())
        indices.add((baseTop + 3).toShort())

        // 4 side walls — each emits its own quad with outward normal so
        // adjacent walls have a hard normal break. Walking CCW around the
        // top outline (x0,z0) → (x1,z0) → (x1,z1) → (x0,z1), outward is
        // walk-direction rotated -90° in xz: (Δz, -Δx).
        val corners = arrayOf(
            floatArrayOf(x0, z0), floatArrayOf(x1, z0),
            floatArrayOf(x1, z1), floatArrayOf(x0, z1),
        )
        for (i in 0..3) {
            val (xa, za) = corners[i]
            val (xb, zb) = corners[(i + 1) % 4]
            val dx = xb - xa; val dz = zb - za
            val len = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6f)
            val nx = dz / len; val nz = -dx / len   // outward in xz
            val s = (verts.size / 10).toShort()
            addVert(xa, yTop,    za, r, g, b, a, nx, 0f, nz)
            addVert(xb, yTop,    zb, r, g, b, a, nx, 0f, nz)
            addVert(xb, yBottom, zb, r, g, b, a, nx, 0f, nz)
            addVert(xa, yBottom, za, r, g, b, a, nx, 0f, nz)
            indices.add(s); indices.add((s + 3).toShort()); indices.add((s + 2).toShort())
            indices.add(s); indices.add((s + 2).toShort()); indices.add((s + 1).toShort())
        }
    }

    /**
     * Extruded chamfered rectangle — top face is the same octagonal fan as
     * `addChamferedRect` (at `yTop`), with 8 side walls dropping to `yBottom`.
     * Used for turret bases / housings / silo segments — a chunky chamfered
     * box reads as engineered armor under perspective.
     */
    fun addExtrudedChamferedRect(
        x0: Float, z0: Float, x1: Float, z1: Float, chamfer: Float,
        r: Float, g: Float, b: Float,
        a: Float = 1f,
        yTop: Float, yBottom: Float,
    ) {
        // Top face — 8 outline verts + 1 centre for fan triangulation.
        // Default (0,1,0) normal so the shader treats the face as up-facing.
        val baseTop = (verts.size / 10).toShort()
        addVert(x0 + chamfer, yTop, z0,           r, g, b, a)  // 0
        addVert(x1 - chamfer, yTop, z0,           r, g, b, a)  // 1
        addVert(x1,           yTop, z0 + chamfer, r, g, b, a)  // 2
        addVert(x1,           yTop, z1 - chamfer, r, g, b, a)  // 3
        addVert(x1 - chamfer, yTop, z1,           r, g, b, a)  // 4
        addVert(x0 + chamfer, yTop, z1,           r, g, b, a)  // 5
        addVert(x0,           yTop, z1 - chamfer, r, g, b, a)  // 6
        addVert(x0,           yTop, z0 + chamfer, r, g, b, a)  // 7
        val cx = (x0 + x1) * 0.5f; val cz = (z0 + z1) * 0.5f
        addVert(cx, yTop, cz, r, g, b, a)                       // 8 centre
        for (i in 0..7) {
            indices.add((baseTop + 8).toShort())
            indices.add((baseTop + i).toShort())
            indices.add((baseTop + (i + 1) % 8).toShort())
        }

        // 8 side walls — each emits its own quad with the wall's outward
        // normal so the chamfer corners get a hard normal break (lit
        // distinctly from the flat sides).
        val outline = arrayOf(
            floatArrayOf(x0 + chamfer, z0),
            floatArrayOf(x1 - chamfer, z0),
            floatArrayOf(x1,           z0 + chamfer),
            floatArrayOf(x1,           z1 - chamfer),
            floatArrayOf(x1 - chamfer, z1),
            floatArrayOf(x0 + chamfer, z1),
            floatArrayOf(x0,           z1 - chamfer),
            floatArrayOf(x0,           z0 + chamfer),
        )
        for (i in 0..7) {
            val (xa, za) = outline[i]
            val (xb, zb) = outline[(i + 1) % 8]
            val dx = xb - xa; val dz = zb - za
            val len = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6f)
            val nx = dz / len; val nz = -dx / len
            val s = (verts.size / 10).toShort()
            addVert(xa, yTop,    za, r, g, b, a, nx, 0f, nz)
            addVert(xb, yTop,    zb, r, g, b, a, nx, 0f, nz)
            addVert(xb, yBottom, zb, r, g, b, a, nx, 0f, nz)
            addVert(xa, yBottom, za, r, g, b, a, nx, 0f, nz)
            indices.add(s); indices.add((s + 3).toShort()); indices.add((s + 2).toShort())
            indices.add(s); indices.add((s + 2).toShort()); indices.add((s + 1).toShort())
        }
    }

    /** Half-disk in the X-Z plane (θ ∈ [0, π]) — flat bottom on z=cz, dome bulges upward. */
    fun addHalfDisk(
        cx: Float, cz: Float, radius: Float,
        r: Float, g: Float, b: Float,
        a: Float = 1f, y: Float = 0f, sectors: Int = 24,
    ) {
        val base = (verts.size / 10).toShort()
        addVert(cx, y, cz, r, g, b, a)
        for (s in 0..sectors) {
            val ang = (s.toDouble() * Math.PI / sectors).toFloat()
            addVert(
                cx + kotlin.math.cos(ang) * radius, y,
                cz + kotlin.math.sin(ang) * radius,
                r, g, b, a,
            )
        }
        for (s in 0 until sectors) {
            indices.add(base)
            indices.add((base + 1 + s).toShort())
            indices.add((base + 2 + s).toShort())
        }
    }
    /**
     * Extruded arbitrary 2D polygon — top face (triangulated via ear-clipping)
     * at `yTop`, side walls connecting each outline edge to its `yBottom`
     * counterpart. Bottom face omitted (camera looks down at the deck plane,
     * so the bottom is never seen). `outline` must be CCW in (x, z).
     *
     * Used for compound silhouettes — e.g. a turret barrel built as one
     * monolithic polygon traced around (collar ∪ housing ∪ mantlet ∪ barrel
     * ∪ muzzle ring). A single mesh has no internal junction walls, which
     * is what kills the rotational z-fight ("steam shimmer") that comes
     * from extruding each piece separately and letting their abutment
     * walls coincide.
     *
     * Concave outlines (inward steps where one section is narrower than
     * the next) are handled — ear-clipping finds convex ears and skips
     * concave vertices until they become extractable.
     */
    fun addExtrudedPolygon(
        outline: List<Pair<Float, Float>>,
        r: Float, g: Float, b: Float,
        a: Float = 1f,
        yTop: Float, yBottom: Float,
    ) {
        val n = outline.size
        if (n < 3) return

        // Top face — outline verts with default (0,1,0) normal. Triangulation
        // via ear-clipping handles concave outlines (turret barrel etc.).
        val baseTop = (verts.size / 10).toShort()
        for ((x, z) in outline) {
            addVert(x, yTop, z, r, g, b, a)
        }
        for ((i, j, k) in earClip(outline)) {
            indices.add((baseTop + i).toShort())
            indices.add((baseTop + j).toShort())
            indices.add((baseTop + k).toShort())
        }

        // Side walls — each outline edge emits its own quad with the edge's
        // outward normal (rotated -90° from CCW walk direction) so adjacent
        // walls get a hard normal break and light distinctly.
        for (i in 0 until n) {
            val (xa, za) = outline[i]
            val (xb, zb) = outline[(i + 1) % n]
            val dx = xb - xa; val dz = zb - za
            val len = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6f)
            val nx = dz / len; val nz = -dx / len
            val s = (verts.size / 10).toShort()
            addVert(xa, yTop,    za, r, g, b, a, nx, 0f, nz)
            addVert(xb, yTop,    zb, r, g, b, a, nx, 0f, nz)
            addVert(xb, yBottom, zb, r, g, b, a, nx, 0f, nz)
            addVert(xa, yBottom, za, r, g, b, a, nx, 0f, nz)
            indices.add(s); indices.add((s + 3).toShort()); indices.add((s + 2).toShort())
            indices.add(s); indices.add((s + 2).toShort()); indices.add((s + 1).toShort())
        }
    }

    /**
     * Ear-clipping triangulation of a 2D polygon (CCW outline of `(x, z)`
     * points). Returns a list of triangle index triples into `outline`.
     * O(n²)–O(n³) in the worst case but trivial for polygons of a few
     * dozen verts (turret barrel = ~28 verts, runs in microseconds at
     * mesh-build time, never per frame).
     */
    private fun earClip(outline: List<Pair<Float, Float>>): List<Triple<Int, Int, Int>> {
        val n = outline.size
        val active = (0 until n).toMutableList()
        val tris = ArrayList<Triple<Int, Int, Int>>(n - 2)

        // Detect winding by signed area; algorithm assumes CCW (cross > 0
        // = convex ear), but we flip the convexity test if input is CW.
        var signedArea = 0f
        for (i in 0 until n) {
            val (x1, z1) = outline[i]
            val (x2, z2) = outline[(i + 1) % n]
            signedArea += (x1 * z2 - x2 * z1)
        }
        val ccw = signedArea > 0f

        var safeguard = n * n + 8
        while (active.size > 3 && safeguard-- > 0) {
            var found = false
            for (k in active.indices) {
                val sz = active.size
                val i0 = active[(k - 1 + sz) % sz]
                val i1 = active[k]
                val i2 = active[(k + 1) % sz]
                val pa = outline[i0]; val pb = outline[i1]; val pc = outline[i2]

                val cross = (pb.first - pa.first) * (pc.second - pa.second) -
                            (pb.second - pa.second) * (pc.first - pa.first)
                if (ccw && cross <= 0f) continue   // concave or colinear — not an ear
                if (!ccw && cross >= 0f) continue

                var hasInside = false
                for (j in active) {
                    if (j == i0 || j == i1 || j == i2) continue
                    if (pointInTri(outline[j], pa, pb, pc)) { hasInside = true; break }
                }
                if (hasInside) continue

                tris.add(Triple(i0, i1, i2))
                active.removeAt(k)
                found = true
                break
            }
            if (!found) break  // degenerate input — bail rather than infinite-loop
        }
        if (active.size == 3) {
            tris.add(Triple(active[0], active[1], active[2]))
        }
        return tris
    }

    private fun pointInTri(
        p: Pair<Float, Float>,
        a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>,
    ): Boolean {
        val s1 = signOf(p, a, b)
        val s2 = signOf(p, b, c)
        val s3 = signOf(p, c, a)
        val hasNeg = s1 < 0f || s2 < 0f || s3 < 0f
        val hasPos = s1 > 0f || s2 > 0f || s3 > 0f
        return !(hasNeg && hasPos)
    }

    private fun signOf(
        p: Pair<Float, Float>,
        a: Pair<Float, Float>, b: Pair<Float, Float>,
    ): Float = (p.first - b.first) * (a.second - b.second) -
              (a.first - b.first) * (p.second - b.second)

    /**
     * 3D hemispherical dome — `slices` × `stacks` mesh of a half-sphere
     * centred at `(cx, cz)` in the X-Z plane. Base ring sits at `baseY`
     * (full radius), apex collapses to a single point at `apexY`. Vertical
     * profile is hemispherical (`scale = sqrt(1 − tv²)`), so each
     * horizontal slice is a smaller circle.
     *
     * Side shading: per-vertex colour fades from `baseShade × rgb` at the
     * base ring to full `rgb` at the apex. With `apexY < baseY` (apex
     * camera-near in our convention), this fakes a top-lit dome — apex
     * facing camera reads brightest, equator-side facets read darker —
     * without needing a real lighting pass.
     */
    fun addHemisphere(
        cx: Float, cz: Float, radius: Float,
        baseY: Float, apexY: Float,
        r: Float, g: Float, b: Float,
        a: Float = 1f,
        slices: Int = 24, stacks: Int = 8,
    ) {
        val baseIdx = (verts.size / 10).toShort()
        val height = apexY - baseY        // negative when apex is camera-near
        val verPerLevel = slices + 1
        for (j in 0..stacks) {
            val tv = j.toFloat() / stacks
            val scale = kotlin.math.sqrt((1f - tv * tv).coerceAtLeast(0f))
            val y = baseY + height * tv
            for (i in 0..slices) {
                val phi = (i.toDouble() / slices * 2.0 * Math.PI).toFloat()
                val cosPhi = kotlin.math.cos(phi)
                val sinPhi = kotlin.math.sin(phi)
                val x = cx + radius * scale * cosPhi
                val z = cz + radius * scale * sinPhi
                // Outward ellipsoid normal — radial in xz, +Y at apex (sign
                // flipped relative to the geometric outward direction so the
                // shader's "+Y is up toward the light" convention treats
                // the apex as facing the main directional light).
                val nx = scale * cosPhi
                val ny = if (kotlin.math.abs(height) > 1e-6f) tv * radius / kotlin.math.abs(height) else 0f
                val nz = scale * sinPhi
                val nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                addVert(x, y, z, r, g, b, a, nx / nl, ny / nl, nz / nl)
            }
        }
        for (j in 0 until stacks) {
            for (i in 0 until slices) {
                val v0 = (baseIdx + j * verPerLevel + i).toShort()
                val v1 = (baseIdx + j * verPerLevel + i + 1).toShort()
                val v2 = (baseIdx + (j + 1) * verPerLevel + i).toShort()
                val v3 = (baseIdx + (j + 1) * verPerLevel + i + 1).toShort()
                indices.add(v0); indices.add(v1); indices.add(v2)
                indices.add(v1); indices.add(v3); indices.add(v2)
            }
        }
    }

    fun upload(engine: EngineJni): Long {
        val v = FloatArray(verts.size) { verts[it] }
        val i = ShortArray(indices.size) { indices[it] }
        return engine.loadMeshRaw(v, i)
    }
}
