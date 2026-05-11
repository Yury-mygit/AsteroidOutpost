"""
Procedural-build a primitive enemy-ship .glb for the combat-mission prototype.

Shape: TIE-fighter-ish — central cockpit cube + two side wing panels joined
by thin struts + small engine block at the rear. Symmetric front-back is
acceptable here (the ship faces away from the player anyway, since it's
flying ahead of us along +Y; engine glow is on the -Y face).

Coordinate convention: AUTHORED IN GAME-WORLD CONVENTION (not gltf
standard) — +Z = up, +Y = forward, +X = right. SceneAssembler renders
ENEMY_SHIP asteroids with no rotation transform, so this orientation
maps directly. glTF viewers will show the model lying on its side; this
is fine for an internal asset.

Materials → per-primitive baseColorFactor (GltfLoader merges multi-prim
meshes into one buffer, baking the material colour into per-vertex
colour). No textures, no UV, no skeleton.

Output: art/Enemy_Ship.glb. Re-run this script to regenerate after
tweaking dimensions/colors.
"""

import struct
import json
import os
from collections import OrderedDict


# ---------- Tunables ----------

# Cockpit (central body, slightly elongated along +Y forward)
COCKPIT_HALF = (0.20, 0.30, 0.20)
COCKPIT_CTR  = (0.0, 0.0, 0.0)

# Wings (thin flat panels on ±X). Y = long, Z = tall.
WING_DIST   = 0.70                  # X centre offset
WING_HALF   = (0.04, 0.40, 0.50)    # half-extents (thin in X)

# Struts (connectors between cockpit and wing)
STRUT_CX    = 0.45
STRUT_HALF  = (0.25, 0.04, 0.04)

# Engine block on the rear (-Y face)
ENGINE_HALF = (0.10, 0.10, 0.10)
ENGINE_CTR  = (0.0, -0.40, 0.0)


# ---------- Materials ----------

MATERIALS = OrderedDict([
    # Slightly cool grey for the hull / struts. Reads as steel.
    ('hull',    [0.45, 0.50, 0.55, 1.0]),
    # Darker for wings so the silhouette has a sharp inboard/outboard split.
    ('wing',    [0.28, 0.32, 0.38, 1.0]),
    # Warm red-orange for the engine — only piece that should "glow"
    # against a dark background. Same hue family as the drone tint.
    ('engine',  [0.85, 0.32, 0.20, 1.0]),
])


# ---------- Geometry builder ----------

# triangles per material: list of (v0, v1, v2) where each vertex is
# ((x, y, z), (nx, ny, nz)).
groups = {name: [] for name in MATERIALS}


def add_face_quad(mat, v0, v1, v2, v3, normal):
    """Quad as two triangles. v0..v3 should be CCW from outside the box."""
    n = tuple(normal)
    groups[mat].append(((v0, n), (v1, n), (v2, n)))
    groups[mat].append(((v0, n), (v2, n), (v3, n)))


def add_box(mat, centre, half):
    """6-face box, all faces outward-normal."""
    cx, cy, cz = centre
    hx, hy, hz = half

    def v(sx, sy, sz):
        return (cx + sx * hx, cy + sy * hy, cz + sz * hz)

    # +X face — CCW from +X looking direction
    add_face_quad(mat, v(+1,-1,-1), v(+1,+1,-1), v(+1,+1,+1), v(+1,-1,+1), (+1, 0, 0))
    # -X face
    add_face_quad(mat, v(-1,-1,+1), v(-1,+1,+1), v(-1,+1,-1), v(-1,-1,-1), (-1, 0, 0))
    # +Y face (forward, nose)
    add_face_quad(mat, v(-1,+1,-1), v(-1,+1,+1), v(+1,+1,+1), v(+1,+1,-1), (0, +1, 0))
    # -Y face (rear, where engine attaches)
    add_face_quad(mat, v(+1,-1,-1), v(+1,-1,+1), v(-1,-1,+1), v(-1,-1,-1), (0, -1, 0))
    # +Z face (top)
    add_face_quad(mat, v(-1,-1,+1), v(-1,+1,+1), v(+1,+1,+1), v(+1,-1,+1), (0, 0, +1))
    # -Z face (bottom)
    add_face_quad(mat, v(+1,-1,-1), v(+1,+1,-1), v(-1,+1,-1), v(-1,-1,-1), (0, 0, -1))


# Build the ship
add_box('hull',   COCKPIT_CTR,                       COCKPIT_HALF)
add_box('wing',   (-WING_DIST, 0.0, 0.0),            WING_HALF)
add_box('wing',   (+WING_DIST, 0.0, 0.0),            WING_HALF)
add_box('hull',   (-STRUT_CX, 0.0, 0.0),             STRUT_HALF)
add_box('hull',   (+STRUT_CX, 0.0, 0.0),             STRUT_HALF)
add_box('engine', ENGINE_CTR,                        ENGINE_HALF)


# ---------- Serialise to .glb ----------

# Per-material primitive: positions + normals + indices, contiguous in the
# single shared BIN buffer.
prim_specs = []
bin_blob = bytearray()


def align4():
    while len(bin_blob) % 4 != 0:
        bin_blob.append(0)


for mat_name, triangles in groups.items():
    positions = []
    normals = []
    indices = []
    for tri_idx, (a, b, c) in enumerate(triangles):
        for vert in (a, b, c):
            positions.append(vert[0])
            normals.append(vert[1])
        base = tri_idx * 3
        indices.extend([base, base + 1, base + 2])

    align4()
    pos_off = len(bin_blob)
    for p in positions:
        bin_blob.extend(struct.pack('<3f', *p))
    pos_len = len(bin_blob) - pos_off

    align4()
    nor_off = len(bin_blob)
    for n in normals:
        bin_blob.extend(struct.pack('<3f', *n))
    nor_len = len(bin_blob) - nor_off

    align4()
    idx_off = len(bin_blob)
    for i in indices:
        bin_blob.extend(struct.pack('<H', i))
    idx_len = len(bin_blob) - idx_off
    align4()

    pmin = [min(p[i] for p in positions) for i in range(3)]
    pmax = [max(p[i] for p in positions) for i in range(3)]

    prim_specs.append({
        'mat':     mat_name,
        'pos_off': pos_off, 'pos_len': pos_len, 'pos_n': len(positions),
        'pos_min': pmin,    'pos_max': pmax,
        'nor_off': nor_off, 'nor_len': nor_len, 'nor_n': len(normals),
        'idx_off': idx_off, 'idx_len': idx_len, 'idx_n': len(indices),
    })


# Build gltf JSON
buffer_views = []
accessors = []
material_names = list(MATERIALS.keys())
material_index = {name: i for i, name in enumerate(material_names)}

primitives = []
for p in prim_specs:
    p['pos_acc'] = len(accessors)
    buffer_views.append({'buffer': 0, 'byteOffset': p['pos_off'], 'byteLength': p['pos_len']})
    accessors.append({
        'bufferView': len(buffer_views) - 1,
        'componentType': 5126,  # FLOAT
        'count': p['pos_n'],
        'type': 'VEC3',
        'min': p['pos_min'],
        'max': p['pos_max'],
    })

    p['nor_acc'] = len(accessors)
    buffer_views.append({'buffer': 0, 'byteOffset': p['nor_off'], 'byteLength': p['nor_len']})
    accessors.append({
        'bufferView': len(buffer_views) - 1,
        'componentType': 5126,
        'count': p['nor_n'],
        'type': 'VEC3',
    })

    p['idx_acc'] = len(accessors)
    buffer_views.append({'buffer': 0, 'byteOffset': p['idx_off'], 'byteLength': p['idx_len']})
    accessors.append({
        'bufferView': len(buffer_views) - 1,
        'componentType': 5123,  # USHORT
        'count': p['idx_n'],
        'type': 'SCALAR',
    })

    primitives.append({
        'attributes': {'POSITION': p['pos_acc'], 'NORMAL': p['nor_acc']},
        'indices':    p['idx_acc'],
        'material':   material_index[p['mat']],
    })


materials_json = [
    {'name': name, 'pbrMetallicRoughness': {
        'baseColorFactor': MATERIALS[name],
        'metallicFactor':  0.3,
        'roughnessFactor': 0.7,
    }}
    for name in material_names
]

gltf = {
    'asset': {'version': '2.0', 'generator': 'tools/build_enemy_ship_glb.py'},
    'scene': 0,
    'scenes': [{'nodes': [0]}],
    'nodes': [{'mesh': 0, 'name': 'Enemy_Ship'}],
    'meshes': [{'name': 'Enemy_Ship', 'primitives': primitives}],
    'materials': materials_json,
    'accessors': accessors,
    'bufferViews': buffer_views,
    'buffers': [{'byteLength': len(bin_blob)}],
}


# ---------- Write .glb ----------

json_bytes = json.dumps(gltf, separators=(',', ':')).encode('utf-8')
while len(json_bytes) % 4 != 0:
    json_bytes += b' '
while len(bin_blob) % 4 != 0:
    bin_blob.append(0)

total = 12 + 8 + len(json_bytes) + 8 + len(bin_blob)

out_dir = 'art'
os.makedirs(out_dir, exist_ok=True)
out_path = os.path.join(out_dir, 'Enemy_Ship.glb')

with open(out_path, 'wb') as f:
    f.write(b'glTF')
    f.write(struct.pack('<I', 2))
    f.write(struct.pack('<I', total))
    f.write(struct.pack('<I', len(json_bytes)))
    f.write(b'JSON')
    f.write(json_bytes)
    f.write(struct.pack('<I', len(bin_blob)))
    f.write(b'BIN\0')
    f.write(bytes(bin_blob))

print(f'Wrote {out_path}: {total} bytes, {sum(len(g) for g in groups.values())} tris '
      f'across {len(prim_specs)} primitives.')
