#pragma once

#include <vulkan/vulkan.h>

#include <cstdint>
#include <vector>

namespace station {

    struct RenderResources {
        // E10 — scene render pass renders into the offscreen colour image
        // (and writes velocity into a 2nd attachment after E10.2).
        // Post-process render pass samples the offscreen colour and writes
        // the final result to the swapchain image. Splitting the chain
        // gives us a place to plug motion blur (E10.4) without touching
        // the scene rendering itself.
        VkRenderPass renderPass = VK_NULL_HANDLE;             // scene pass
        std::vector<VkFramebuffer> framebuffers;              // scene fbs (one entry, shared offscreen)
        VkRenderPass postRenderPass = VK_NULL_HANDLE;         // post-process pass
        std::vector<VkFramebuffer> postFramebuffers;          // post-process fbs (one per swapchain image)

        // Depth buffer (single image shared across frames — we fence-wait before reuse)
        VkImage        depthImage       = VK_NULL_HANDLE;
        VkDeviceMemory depthMemory      = VK_NULL_HANDLE;
        VkImageView    depthImageView   = VK_NULL_HANDLE;

        // E10.1 — offscreen colour target sampled by the post-process pass.
        // VK_FORMAT_B8G8R8A8_UNORM, COLOR_ATTACHMENT + SAMPLED. Layout
        // transitions are baked into the scene pass (UNDEFINED →
        // SHADER_READ_ONLY_OPTIMAL via finalLayout).
        VkImage        offscreenColorImage  = VK_NULL_HANDLE;
        VkDeviceMemory offscreenColorMemory = VK_NULL_HANDLE;
        VkImageView    offscreenColorView   = VK_NULL_HANDLE;
        VkSampler      offscreenColorSampler= VK_NULL_HANDLE;

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
        // E10.1 — `finalLayout` lets the caller distinguish the scene pass
        // (UNDEFINED → SHADER_READ_ONLY_OPTIMAL so the post-process pass
        // can sample the result) from the post pass (UNDEFINED →
        // PRESENT_SRC_KHR for swapchain output).
        static bool createRenderPass(
                VkDevice device,
                VkFormat colorFormat,
                VkFormat depthFormat,
                VkImageLayout finalLayout,
                VkRenderPass& outRenderPass
        );

        // E10.1 — render pass for the post-process step. Single colour
        // attachment (swapchain image), no depth. loadOp = LOAD because
        // we'll always write every pixel from the fullscreen draw, so
        // CLEAR would just be wasted bandwidth. (Swapchain images come
        // from acquireNextImage with undefined contents, but we
        // overwrite the whole frame.)
        static bool createPostRenderPass(
                VkDevice device,
                VkFormat colorFormat,
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

        // E10.1 — offscreen colour target used by the scene pass.
        // COLOR_ATTACHMENT + SAMPLED, B8G8R8A8_UNORM. Single image shared
        // across frames (the inFlightFence ensures no overlap).
        static bool createOffscreenColorResources(
                VkPhysicalDevice physicalDevice,
                VkDevice device,
                VkExtent2D extent,
                VkFormat format,
                VkImage& outImage,
                VkDeviceMemory& outMemory,
                VkImageView& outImageView,
                VkSampler& outSampler
        );

        static bool createFramebuffers(
                VkDevice device,
                VkRenderPass renderPass,
                VkExtent2D extent,
                const std::vector<VkImageView>& imageViews,
                VkImageView depthImageView,
                std::vector<VkFramebuffer>& outFramebuffers
        );

        // E10.1 — scene framebuffer (single, shared across frames) wrapping
        // the offscreen colour view + depth view.
        static bool createSceneFramebuffer(
                VkDevice device,
                VkRenderPass sceneRenderPass,
                VkExtent2D extent,
                VkImageView offscreenColorView,
                VkImageView depthImageView,
                VkFramebuffer& outFramebuffer
        );

        // E10.1 — post-process framebuffers (one per swapchain image).
        // Single colour attachment per fb (the swapchain image view).
        static bool createPostFramebuffers(
                VkDevice device,
                VkRenderPass postRenderPass,
                VkExtent2D extent,
                const std::vector<VkImageView>& swapchainViews,
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