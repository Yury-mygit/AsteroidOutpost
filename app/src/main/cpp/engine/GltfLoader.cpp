#define TINYGLTF_IMPLEMENTATION
#define TINYGLTF_NO_STB_IMAGE
#define TINYGLTF_NO_STB_IMAGE_WRITE
#define TINYGLTF_NO_EXTERNAL_IMAGE
#include "tiny_gltf.h"

#include "GltfLoader.h"

#include <android/log.h>
#include <cmath>
#include <cstring>
#include <string>

#define LOG_TAG "stationcore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace station {
    namespace {

        template<typename T>
        const T* accessorPtr(const tinygltf::Model& model, int idx) {
            if (idx < 0) return nullptr;
            const auto& acc = model.accessors[idx];
            const auto& bv  = model.bufferViews[acc.bufferView];
            const auto& buf = model.buffers[bv.buffer];
            return reinterpret_cast<const T*>(buf.data.data() + bv.byteOffset + acc.byteOffset);
        }

        size_t accessorCount(const tinygltf::Model& model, int idx) {
            return idx >= 0 ? model.accessors[idx].count : 0;
        }

        bool convertPrimitive(const tinygltf::Model& model,
                              const tinygltf::Primitive& prim,
                              MeshData& out) {
            // POSITION (required)
            auto posIt = prim.attributes.find("POSITION");
            if (posIt == prim.attributes.end()) { LOGE("GltfLoader: no POSITION"); return false; }
            const float* positions = accessorPtr<float>(model, posIt->second);
            size_t vertCount       = accessorCount(model, posIt->second);
            if (!positions || !vertCount) { LOGE("GltfLoader: empty POSITION"); return false; }

            // NORMAL (optional)
            auto norIt = prim.attributes.find("NORMAL");
            const float* normals = nullptr;
            if (norIt != prim.attributes.end())
                normals = accessorPtr<float>(model, norIt->second);

            // COLOR_0 (optional)
            auto colIt = prim.attributes.find("COLOR_0");
            const float* colors = nullptr;
            int colComp = 3;
            if (colIt != prim.attributes.end()) {
                const auto& acc = model.accessors[colIt->second];
                colComp = (acc.type == TINYGLTF_TYPE_VEC4) ? 4 : 3;
                colors  = accessorPtr<float>(model, colIt->second);
            }

            // Fallback colour from material (alpha defaults to 1 — opaque).
            float fallback[4] = {0.6f, 0.6f, 0.7f, 1.0f};
            if (!colors && prim.material >= 0) {
                const auto& bf = model.materials[prim.material].pbrMetallicRoughness.baseColorFactor;
                if (bf.size() >= 3) { fallback[0]=(float)bf[0]; fallback[1]=(float)bf[1]; fallback[2]=(float)bf[2]; }
                if (bf.size() >= 4) { fallback[3]=(float)bf[3]; }
            }

            // Build vertices
            out.vertices.resize(vertCount);
            for (size_t i = 0; i < vertCount; ++i) {
                out.vertices[i].position[0] = positions[i*3+0];
                out.vertices[i].position[1] = positions[i*3+1];
                out.vertices[i].position[2] = positions[i*3+2];

                if (colors) {
                    out.vertices[i].color[0] = colors[i*colComp+0];
                    out.vertices[i].color[1] = colors[i*colComp+1];
                    out.vertices[i].color[2] = colors[i*colComp+2];
                    // VEC4 colours carry per-vertex alpha; VEC3 defaults to opaque.
                    out.vertices[i].color[3] = (colComp == 4) ? colors[i*colComp+3] : 1.0f;
                } else {
                    out.vertices[i].color[0] = fallback[0];
                    out.vertices[i].color[1] = fallback[1];
                    out.vertices[i].color[2] = fallback[2];
                    out.vertices[i].color[3] = fallback[3];
                }

                if (normals) {
                    out.vertices[i].normal[0] = normals[i*3+0];
                    out.vertices[i].normal[1] = normals[i*3+1];
                    out.vertices[i].normal[2] = normals[i*3+2];
                } else {
                    // No normals in file — compute flat normals later per face
                    out.vertices[i].normal[0] = 0.0f;
                    out.vertices[i].normal[1] = 0.0f;
                    out.vertices[i].normal[2] = 1.0f;
                }
            }

            // Indices
            if (prim.indices >= 0) {
                const auto& idxAcc = model.accessors[prim.indices];
                size_t idxCount    = idxAcc.count;
                out.indices.resize(idxCount);

                const auto& bv  = model.bufferViews[idxAcc.bufferView];
                const uint8_t* base = model.buffers[bv.buffer].data.data()
                                      + bv.byteOffset + idxAcc.byteOffset;

                if (idxAcc.componentType == TINYGLTF_COMPONENT_TYPE_UNSIGNED_SHORT) {
                    auto* src = reinterpret_cast<const uint16_t*>(base);
                    for (size_t i = 0; i < idxCount; ++i) out.indices[i] = src[i];
                } else if (idxAcc.componentType == TINYGLTF_COMPONENT_TYPE_UNSIGNED_INT) {
                    auto* src = reinterpret_cast<const uint32_t*>(base);
                    for (size_t i = 0; i < idxCount; ++i) {
                        if (src[i] > 65535u) { LOGE("GltfLoader: index >65535"); return false; }
                        out.indices[i] = (uint16_t)src[i];
                    }
                } else if (idxAcc.componentType == TINYGLTF_COMPONENT_TYPE_UNSIGNED_BYTE) {
                    for (size_t i = 0; i < idxCount; ++i) out.indices[i] = base[i];
                } else {
                    LOGE("GltfLoader: unsupported index type %d", idxAcc.componentType);
                    return false;
                }
            } else {
                out.indices.resize(vertCount);
                for (size_t i = 0; i < vertCount; ++i) out.indices[i] = (uint16_t)i;
            }

            // If no normals in file — compute flat normals from triangles
            if (!normals) {
                LOGI("GltfLoader: no NORMAL attr — computing flat normals");
                std::vector<float> nAccum(vertCount * 3, 0.0f);
                for (size_t i = 0; i < out.indices.size(); i += 3) {
                    uint16_t ia = out.indices[i], ib = out.indices[i+1], ic = out.indices[i+2];
                    const float* a = out.vertices[ia].position;
                    const float* b = out.vertices[ib].position;
                    const float* c = out.vertices[ic].position;
                    float ab[3] = {b[0]-a[0], b[1]-a[1], b[2]-a[2]};
                    float ac[3] = {c[0]-a[0], c[1]-a[1], c[2]-a[2]};
                    float fn[3] = {
                            ab[1]*ac[2] - ab[2]*ac[1],
                            ab[2]*ac[0] - ab[0]*ac[2],
                            ab[0]*ac[1] - ab[1]*ac[0]
                    };
                    float len = std::sqrt(fn[0]*fn[0]+fn[1]*fn[1]+fn[2]*fn[2]);
                    if (len > 1e-8f) { fn[0]/=len; fn[1]/=len; fn[2]/=len; }
                    for (int v : {(int)ia,(int)ib,(int)ic}) {
                        nAccum[v*3+0]+=fn[0]; nAccum[v*3+1]+=fn[1]; nAccum[v*3+2]+=fn[2];
                    }
                }
                for (size_t i = 0; i < vertCount; ++i) {
                    float* n = nAccum.data()+i*3;
                    float len = std::sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);
                    if (len > 1e-6f) {
                        out.vertices[i].normal[0]=n[0]/len;
                        out.vertices[i].normal[1]=n[1]/len;
                        out.vertices[i].normal[2]=n[2]/len;
                    }
                }
            }

            LOGI("GltfLoader: %zu verts, %zu indices, normals=%s",
                 vertCount, out.indices.size(), normals ? "file" : "computed");
            return true;
        }

    } // namespace

    bool GltfLoader::loadFromMemory(const uint8_t* data, size_t length, MeshData& outMesh) {
        if (!data || !length) { LOGE("GltfLoader: null data"); return false; }

        tinygltf::Model    model;
        tinygltf::TinyGLTF loader;
        std::string        err, warn;

        bool ok = false;
        const bool isBinary = (length >= 4 &&
                               data[0]==0x67 && data[1]==0x6C && data[2]==0x54 && data[3]==0x46);

        if (isBinary) {
            ok = loader.LoadBinaryFromMemory(&model, &err, &warn, data, (uint32_t)length);
        } else {
            std::string text(reinterpret_cast<const char*>(data), length);
            ok = loader.LoadASCIIFromString(&model, &err, &warn,
                                            text.c_str(), (uint32_t)text.size(), "");
        }

        if (!warn.empty()) LOGI("GltfLoader warn: %s", warn.c_str());
        if (!err.empty())  LOGE("GltfLoader err:  %s", err.c_str());
        if (!ok)           { LOGE("GltfLoader: parse failed"); return false; }

        // Merge every triangle primitive of every mesh into a single MeshData.
        // glTF authoring tools split a model into one primitive per material
        // (e.g. Bullet.glb has 3 prims for the brass casing, copper tip, and
        // base ring). Without merging we would render only the first primitive
        // — the rest of the model would be missing. `load_mesh_colored` later
        // re-stamps every vertex's tint to a single colour, so we don't lose
        // anything visible by collapsing the per-material split here.
        bool any = false;
        for (const auto& mesh : model.meshes) {
            for (const auto& prim : mesh.primitives) {
                if (prim.mode != TINYGLTF_MODE_TRIANGLES) continue;
                MeshData primData;
                if (!convertPrimitive(model, prim, primData)) continue;
                uint16_t base = (uint16_t)outMesh.vertices.size();
                outMesh.vertices.insert(outMesh.vertices.end(),
                                        primData.vertices.begin(),
                                        primData.vertices.end());
                for (uint16_t idx : primData.indices) {
                    outMesh.indices.push_back(base + idx);
                }
                any = true;
            }
            if (any) {
                LOGI("GltfLoader: mesh='%s' merged %zu prims → %zu verts, %zu indices",
                     mesh.name.c_str(),
                     mesh.primitives.size(),
                     outMesh.vertices.size(),
                     outMesh.indices.size());
                return true;
            }
        }
        LOGE("GltfLoader: no triangle mesh found");
        return false;
    }

} // namespace station