#include "ShipMesh.h"

#include <cmath>

namespace station {

    // Compute flat face normal for a triangle
    static void faceNormal(const float* a, const float* b, const float* c, float* out) {
            float ab[3] = { b[0]-a[0], b[1]-a[1], b[2]-a[2] };
            float ac[3] = { c[0]-a[0], c[1]-a[1], c[2]-a[2] };
            out[0] = ab[1]*ac[2] - ab[2]*ac[1];
            out[1] = ab[2]*ac[0] - ab[0]*ac[2];
            out[2] = ab[0]*ac[1] - ab[1]*ac[0];
            float len = std::sqrt(out[0]*out[0] + out[1]*out[1] + out[2]*out[2]);
            if (len > 1e-6f) { out[0]/=len; out[1]/=len; out[2]/=len; }
    }

    MeshData ShipMesh::create() {
            MeshData mesh{};

            mesh.vertices = {
                    // 0 nose
                    {{ 0.00f,  0.80f,  0.00f}, {0.90f, 0.90f, 1.00f}, {0,0,0}},
                    // upper hull
                    {{ 0.00f,  0.15f,  0.22f}, {0.75f, 0.78f, 0.92f}, {0,0,0}}, // 1
                    {{-0.28f, -0.10f,  0.10f}, {0.55f, 0.60f, 0.82f}, {0,0,0}}, // 2
                    {{ 0.28f, -0.10f,  0.10f}, {0.55f, 0.60f, 0.82f}, {0,0,0}}, // 3
                    // lower hull
                    {{ 0.00f,  0.05f, -0.22f}, {0.38f, 0.42f, 0.62f}, {0,0,0}}, // 4
                    {{-0.24f, -0.14f, -0.10f}, {0.30f, 0.32f, 0.50f}, {0,0,0}}, // 5
                    {{ 0.24f, -0.14f, -0.10f}, {0.30f, 0.32f, 0.50f}, {0,0,0}}, // 6
                    // wing tips
                    {{-0.82f, -0.05f,  0.00f}, {0.48f, 0.55f, 0.85f}, {0,0,0}}, // 7
                    {{ 0.82f, -0.05f,  0.00f}, {0.48f, 0.55f, 0.85f}, {0,0,0}}, // 8
                    // tail
                    {{ 0.00f, -0.78f,  0.00f}, {0.24f, 0.28f, 0.44f}, {0,0,0}}, // 9
                    // dorsal fin
                    {{ 0.00f, -0.36f,  0.34f}, {0.88f, 0.52f, 0.28f}, {0,0,0}}, // 10
            };

            mesh.indices = {
                    0, 1, 2,   0, 3, 1,
                    0, 5, 4,   0, 4, 6,
                    1, 3, 2,   4, 5, 6,
                    2, 7, 5,   2, 1, 7,
                    3, 6, 8,   1, 3, 8,
                    2, 9, 3,   5, 6, 9,
                    1, 10, 2,  1, 3, 10,
            };

            // Accumulate smooth normals from face normals
            std::vector<float> nAccum(mesh.vertices.size() * 3, 0.0f);
            for (size_t i = 0; i < mesh.indices.size(); i += 3) {
                    uint16_t ia = mesh.indices[i], ib = mesh.indices[i+1], ic = mesh.indices[i+2];
                    float fn[3];
                    faceNormal(mesh.vertices[ia].position,
                               mesh.vertices[ib].position,
                               mesh.vertices[ic].position, fn);
                    for (int v : {(int)ia, (int)ib, (int)ic}) {
                            nAccum[v*3+0] += fn[0];
                            nAccum[v*3+1] += fn[1];
                            nAccum[v*3+2] += fn[2];
                    }
            }
            // Normalize accumulated normals
            for (size_t i = 0; i < mesh.vertices.size(); ++i) {
                    float* n = nAccum.data() + i*3;
                    float len = std::sqrt(n[0]*n[0] + n[1]*n[1] + n[2]*n[2]);
                    if (len > 1e-6f) {
                            mesh.vertices[i].normal[0] = n[0]/len;
                            mesh.vertices[i].normal[1] = n[1]/len;
                            mesh.vertices[i].normal[2] = n[2]/len;
                    } else {
                            mesh.vertices[i].normal[0] = 0;
                            mesh.vertices[i].normal[1] = 0;
                            mesh.vertices[i].normal[2] = 1;
                    }
            }

            return mesh;
    }

} // namespace station