#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

#include "Mesh.h"

namespace station {

    class GltfLoader {
    public:
        // Load from raw bytes (GLB or GLTF). Kotlin reads the file, passes bytes here.
        static bool loadFromMemory(
                const uint8_t* data,
                size_t length,
                MeshData& outMesh
        );
    };

} // namespace station