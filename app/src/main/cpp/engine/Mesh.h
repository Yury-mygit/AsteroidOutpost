#pragma once

#include <vulkan/vulkan.h>

#include <cstdint>
#include <vector>

namespace station {

    struct Vertex {
        float position[3];
        float color[3];
        float normal[3];   // world-space normal for lighting

        static VkVertexInputBindingDescription getBindingDescription();
        static std::vector<VkVertexInputAttributeDescription> getAttributeDescriptions();
    };

    struct MeshData {
        std::vector<Vertex>   vertices;
        std::vector<uint16_t> indices;
    };

    class MeshGpu {
    public:
        MeshGpu()  = default;
        ~MeshGpu() = default;

        MeshGpu(const MeshGpu&) = delete;
        MeshGpu& operator=(const MeshGpu&) = delete;

        bool create(
                VkPhysicalDevice physicalDevice,
                VkDevice device,
                const MeshData& meshData
        );

        void destroy(VkDevice device);

        [[nodiscard]] bool     isReady()    const;
        [[nodiscard]] uint32_t indexCount() const;

        void bind(VkCommandBuffer commandBuffer) const;

    private:
        VkBuffer       m_vertexBuffer = VK_NULL_HANDLE;
        VkDeviceMemory m_vertexMemory = VK_NULL_HANDLE;

        VkBuffer       m_indexBuffer  = VK_NULL_HANDLE;
        VkDeviceMemory m_indexMemory  = VK_NULL_HANDLE;

        uint32_t m_indexCount = 0;
        bool     m_ready      = false;

        static uint32_t findMemoryType(
                VkPhysicalDevice physicalDevice,
                uint32_t typeFilter,
                VkMemoryPropertyFlags properties
        );

        static bool createBuffer(
                VkPhysicalDevice physicalDevice,
                VkDevice device,
                VkDeviceSize size,
                VkBufferUsageFlags usage,
                VkMemoryPropertyFlags properties,
                VkBuffer& outBuffer,
                VkDeviceMemory& outMemory
        );
    };

} // namespace station