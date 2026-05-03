#pragma once

#include <vulkan/vulkan.h>

#include <cstdint>
#include <vector>

namespace station {

    struct RenderResources {
        VkRenderPass renderPass = VK_NULL_HANDLE;
        std::vector<VkFramebuffer> framebuffers;

        // Depth buffer (single image shared across frames — we fence-wait before reuse)
        VkImage        depthImage       = VK_NULL_HANDLE;
        VkDeviceMemory depthMemory      = VK_NULL_HANDLE;
        VkImageView    depthImageView   = VK_NULL_HANDLE;

        VkCommandPool commandPool = VK_NULL_HANDLE;
        std::vector<VkCommandBuffer> commandBuffers;

        VkSemaphore imageAvailableSemaphore = VK_NULL_HANDLE;
        VkSemaphore renderFinishedSemaphore = VK_NULL_HANDLE;
        VkFence inFlightFence = VK_NULL_HANDLE;

        bool renderReady = false;
    };

    class RenderResourcesBuilder {
    public:
        // Now requires physicalDevice to pick depth format and allocate memory.
        static bool createRenderPass(
                VkDevice device,
                VkFormat colorFormat,
                VkFormat depthFormat,
                VkRenderPass& outRenderPass
        );

        static bool createDepthResources(
                VkPhysicalDevice physicalDevice,
                VkDevice device,
                VkExtent2D extent,
                VkFormat depthFormat,
                VkImage& outImage,
                VkDeviceMemory& outMemory,
                VkImageView& outImageView
        );

        static bool createFramebuffers(
                VkDevice device,
                VkRenderPass renderPass,
                VkExtent2D extent,
                const std::vector<VkImageView>& imageViews,
                VkImageView depthImageView,
                std::vector<VkFramebuffer>& outFramebuffers
        );

        static bool createCommandPool(
                VkDevice device,
                uint32_t queueFamilyIndex,
                VkCommandPool& outCommandPool
        );

        static bool createCommandBuffers(
                VkDevice device,
                VkCommandPool commandPool,
                uint32_t framebufferCount,
                std::vector<VkCommandBuffer>& outCommandBuffers
        );

        static bool createSyncObjects(
                VkDevice device,
                VkSemaphore& outImageAvailableSemaphore,
                VkSemaphore& outRenderFinishedSemaphore,
                VkFence& outInFlightFence
        );

        static bool recordClearCommandBuffer(
                VkCommandBuffer commandBuffer,
                VkRenderPass renderPass,
                VkFramebuffer framebuffer,
                VkExtent2D extent,
                float r,
                float g,
                float b,
                float a
        );

        // Helper: pick best depth format supported by the device.
        static VkFormat pickDepthFormat(VkPhysicalDevice physicalDevice);

        static void destroy(
                VkDevice device,
                RenderResources& resources
        );
    };

} // namespace station