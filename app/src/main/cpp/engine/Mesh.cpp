#include "Mesh.h"

#include <android/log.h>
#include <cstring>
#include <string>

namespace station {
    namespace {
        constexpr const char* LOG_TAG = "stationcore";

        void log_error(const std::string& msg) {
            __android_log_write(ANDROID_LOG_ERROR, LOG_TAG, msg.c_str());
        }

        std::string vkRes(VkResult r) {
            switch (r) {
                case VK_SUCCESS:                    return "VK_SUCCESS";
                case VK_ERROR_OUT_OF_HOST_MEMORY:   return "VK_ERROR_OUT_OF_HOST_MEMORY";
                case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
                case VK_ERROR_MEMORY_MAP_FAILED:    return "VK_ERROR_MEMORY_MAP_FAILED";
                default: return "VK_RESULT(" + std::to_string((int)r) + ")";
            }
        }
    }

    VkVertexInputBindingDescription Vertex::getBindingDescription() {
        VkVertexInputBindingDescription bd{};
        bd.binding   = 0;
        bd.stride    = sizeof(Vertex);
        bd.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;
        return bd;
    }

    std::vector<VkVertexInputAttributeDescription> Vertex::getAttributeDescriptions() {
        std::vector<VkVertexInputAttributeDescription> attrs(4);

        // location 0 — position
        attrs[0].binding  = 0;
        attrs[0].location = 0;
        attrs[0].format   = VK_FORMAT_R32G32B32_SFLOAT;
        attrs[0].offset   = offsetof(Vertex, position);

        // location 1 — colour (RGBA)
        attrs[1].binding  = 0;
        attrs[1].location = 1;
        attrs[1].format   = VK_FORMAT_R32G32B32A32_SFLOAT;
        attrs[1].offset   = offsetof(Vertex, color);

        // location 2 — normal
        attrs[2].binding  = 0;
        attrs[2].location = 2;
        attrs[2].format   = VK_FORMAT_R32G32B32_SFLOAT;
        attrs[2].offset   = offsetof(Vertex, normal);

        // location 3 — UV (E8.1). Defaults to (0, 0) when the mesh source
        // doesn't provide TEXCOORD_0; sampled only by the textured fragment
        // branch (E8.3+), so meshes without UVs render unchanged.
        attrs[3].binding  = 0;
        attrs[3].location = 3;
        attrs[3].format   = VK_FORMAT_R32G32_SFLOAT;
        attrs[3].offset   = offsetof(Vertex, uv);

        return attrs;
    }

    uint32_t MeshGpu::findMemoryType(VkPhysicalDevice pd, uint32_t filter,
                                     VkMemoryPropertyFlags props) {
        VkPhysicalDeviceMemoryProperties mp{};
        vkGetPhysicalDeviceMemoryProperties(pd, &mp);
        for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
            if ((filter & (1u << i)) && (mp.memoryTypes[i].propertyFlags & props) == props)
                return i;
        return UINT32_MAX;
    }

    bool MeshGpu::createBuffer(VkPhysicalDevice pd, VkDevice dev,
                               VkDeviceSize size, VkBufferUsageFlags usage,
                               VkMemoryPropertyFlags props,
                               VkBuffer& outBuf, VkDeviceMemory& outMem) {
        VkBufferCreateInfo bi{};
        bi.sType       = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bi.size        = size;
        bi.usage       = usage;
        bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

        VkResult r = vkCreateBuffer(dev, &bi, nullptr, &outBuf);
        if (r != VK_SUCCESS) { log_error("vkCreateBuffer: " + vkRes(r)); return false; }

        VkMemoryRequirements mr{};
        vkGetBufferMemoryRequirements(dev, outBuf, &mr);

        VkMemoryAllocateInfo ai{};
        ai.sType           = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize  = mr.size;
        ai.memoryTypeIndex = findMemoryType(pd, mr.memoryTypeBits, props);
        if (ai.memoryTypeIndex == UINT32_MAX) { log_error("findMemoryType failed"); return false; }

        r = vkAllocateMemory(dev, &ai, nullptr, &outMem);
        if (r != VK_SUCCESS) { log_error("vkAllocateMemory: " + vkRes(r)); return false; }

        r = vkBindBufferMemory(dev, outBuf, outMem, 0);
        if (r != VK_SUCCESS) { log_error("vkBindBufferMemory: " + vkRes(r)); return false; }

        return true;
    }

    bool MeshGpu::create(VkPhysicalDevice pd, VkDevice dev, const MeshData& data) {
        destroy(dev);
        if (data.vertices.empty() || data.indices.empty()) {
            log_error("MeshGpu::create: empty data"); return false;
        }

        VkDeviceSize vbSize = sizeof(Vertex)   * data.vertices.size();
        VkDeviceSize ibSize = sizeof(uint16_t) * data.indices.size();

        if (!createBuffer(pd, dev, vbSize,
                          VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                          VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                          m_vertexBuffer, m_vertexMemory)) { destroy(dev); return false; }

        if (!createBuffer(pd, dev, ibSize,
                          VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                          VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                          m_indexBuffer, m_indexMemory)) { destroy(dev); return false; }

        void* ptr = nullptr;
        vkMapMemory(dev, m_vertexMemory, 0, vbSize, 0, &ptr);
        std::memcpy(ptr, data.vertices.data(), (size_t)vbSize);
        vkUnmapMemory(dev, m_vertexMemory);

        vkMapMemory(dev, m_indexMemory, 0, ibSize, 0, &ptr);
        std::memcpy(ptr, data.indices.data(), (size_t)ibSize);
        vkUnmapMemory(dev, m_indexMemory);

        m_indexCount = (uint32_t)data.indices.size();
        m_ready      = true;
        return true;
    }

    void MeshGpu::destroy(VkDevice dev) {
        if (m_indexBuffer  != VK_NULL_HANDLE) { vkDestroyBuffer(dev, m_indexBuffer,  nullptr); m_indexBuffer  = VK_NULL_HANDLE; }
        if (m_indexMemory  != VK_NULL_HANDLE) { vkFreeMemory(dev, m_indexMemory,  nullptr);    m_indexMemory  = VK_NULL_HANDLE; }
        if (m_vertexBuffer != VK_NULL_HANDLE) { vkDestroyBuffer(dev, m_vertexBuffer, nullptr); m_vertexBuffer = VK_NULL_HANDLE; }
        if (m_vertexMemory != VK_NULL_HANDLE) { vkFreeMemory(dev, m_vertexMemory, nullptr);    m_vertexMemory = VK_NULL_HANDLE; }
        m_indexCount = 0;
        m_ready      = false;
    }

    bool     MeshGpu::isReady()    const { return m_ready; }
    uint32_t MeshGpu::indexCount() const { return m_indexCount; }

    void MeshGpu::bind(VkCommandBuffer cmd) const {
        VkBuffer     bufs[]    = { m_vertexBuffer };
        VkDeviceSize offsets[] = { 0 };
        vkCmdBindVertexBuffers(cmd, 0, 1, bufs, offsets);
        vkCmdBindIndexBuffer(cmd, m_indexBuffer, 0, VK_INDEX_TYPE_UINT16);
    }

} // namespace station