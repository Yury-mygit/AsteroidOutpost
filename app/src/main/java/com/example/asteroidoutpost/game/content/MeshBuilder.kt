package com.example.asteroidoutpost.game.content

import com.example.asteroidoutpost.EngineJni

/**
 * Procedural mesh assembly helper. Each `addRect` / `addChamferedRect` /
 * `addTri` / `addHalfDisk` emits a flat polygon in the X-Z plane (Y=0 by
 * default, with a small `y` offset for layered details so the LESS-depth
 * test doesn't reject overlay fragments at equal depth — same trick as
 * the HP-bar fill at y=-0.01). All vertices stamp normal=(0, 1, 0) so the
 * lit pipeline gives the silhouette a uniform soft fill regardless of
 * winding. Per-vertex RGB is passed through so different parts of the
 * mesh (housing accent, dark slits, muzzle bore, warning stripes) read
 * distinctly without needing a texture.
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
    ) {
        verts.add(x); verts.add(y); verts.add(z)
        verts.add(r); verts.add(g); verts.add(b); verts.add(a)
        verts.add(0f); verts.add(1f); verts.add(0f)
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
    fun upload(engine: EngineJni): Long {
        val v = FloatArray(verts.size) { verts[it] }
        val i = ShortArray(indices.size) { indices[it] }
        return engine.loadMeshRaw(v, i)
    }
}
