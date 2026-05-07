#include "RenderResources.h"

#include <android/log.h>

#include <string>

namespace station {
    namespace {
        constexpr const char* LOG_TAG = "stationcore";

        void log_error(const std::string& message) {
            __android_log_write(ANDROID_LOG_ERROR, LOG_TAG, message.c_str());
        }

        void log_info(const std::string& message) {
            __android_log_write(ANDROID_LOG_INFO, LOG_TAG, message.c_str());
        }

        std::string vkResultToString(VkResult result) {
            switch (result) {
                case VK_SUCCESS: return "VK_SUCCESS";
                case VK_NOT_READY: return "VK_NOT_READY";
                case VK_TIMEOUT: return "VK_TIMEOUT";
                case VK_EVENT_SET: return "VK_EVENT_SET";
                case VK_EVENT_RESET: return "VK_EVENT_RESET";
                case VK_INCOMPLETE: return "VK_INCOMPLETE";
                case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
                case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
                case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
                case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
                case VK_ERROR_MEMORY_MAP_FAILED: return "VK_ERROR_MEMORY_MAP_FAILED";
                case VK_ERROR_LAYER_NOT_PRESENT: return "VK_ERROR_LAYER_NOT_PRESENT";
                case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
                case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
                case VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
                case VK_ERROR_TOO_MANY_OBJECTS: return "VK_ERROR_TOO_MANY_OBJECTS";
                case VK_ERROR_FORMAT_NOT_SUPPORTED: return "VK_ERROR_FORMAT_NOT_SUPPORTED";
                case VK_SUBOPTIMAL_KHR: return "VK_SUBOPTIMAL_KHR";
                case VK_ERROR_OUT_OF_DATE_KHR: return "VK_ERROR_OUT_OF_DATE_KHR";
                default: return "VK_RESULT_UNKNOWN(" + std::to_string(static_cast<int>(result)) + ")";
            }
        }

        uint32_t findMemoryType(
                VkPhysicalDevice physicalDevice,
                uint32_t typeFilter,
                VkMemoryPropertyFlags properties
        ) {
            VkPhysicalDeviceMemoryProperties memProps{};
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memProps);

            for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
                if ((typeFilter & (1u << i)) &&
                    (memProps.memoryTypes[i].propertyFlags & properties) == properties) {
                    return i;
                }
            }
            return UINT32_MAX;
        }
    } // namespace

    // ---------------------------------------------------------------------------
    // pickDepthFormat
    // ---------------------------------------------------------------------------
    VkFormat RenderResourcesBuilder::pickDepthFormat(VkPhysicalDevice physicalDevice) {
        // Prefer D24 (saves bandwidth on mobile), fall back to D32.
        const VkFormat candidates[] = {
                VK_FORMAT_D24_UNORM_S8_UINT,
                VK_FORMAT_D32_SFLOAT,
                VK_FORMAT_D16_UNORM,
        };

        for (VkFormat fmt : candidates) {
            VkFormatProperties props{};
            vkGetPhysicalDeviceFormatProperties(physicalDevice, fmt, &props);
            if (props.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) {
                log_info(std::string("Picked depth format: ") + std::to_string(static_cast<int>(fmt)));
                return fmt;
            }
        }

        log_error("No suitable depth format found, using D32_SFLOAT as fallback");
        return VK_FORMAT_D32_SFLOAT;
    }

    // ---------------------------------------------------------------------------
    // createRenderPass  (now with depth attachment)
    // ---------------------------------------------------------------------------
    bool RenderResourcesBuilder::createRenderPass(
            VkDevice device,
            VkFormat colorFormat,
            VkFormat velocityFormat,
            VkFormat depthFormat,
            VkImageLayout finalLayout,
            VkRenderPass& outRenderPass
    ) {
        const bool hasVelocity = (velocityFormat != VK_FORMAT_UNDEFINED);

        // Attachment 0: colour
        VkAttachmentDescription attachments[3]{};
        attachments[0].format         = colorFormat;
        attachments[0].samples        = VK_SAMPLE_COUNT_1_BIT;
        attachments[0].loadOp         = VK_ATTACHMENT_LOAD_OP_CLEAR;
        attachments[0].storeOp        = VK_ATTACHMENT_STORE_OP_STORE;
        attachments[0].stencilLoadOp  = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachments[0].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachments[0].initialLayout  = VK_IMAGE_LAYOUT_UNDEFINED;
        attachments[0].finalLayout    = finalLayout;

        // Attachment 1: velocity (E10.2) when velocityFormat provided.
        // Same loadOp/storeOp/finalLayout as colour — the post pass needs to
        // sample velocity for motion blur (E10.4). Clear value (0,0) means
        // "no motion" which is the correct background for the blur shader.
        // When no velocity attachment, slot is unused and depth slides up.
        const uint32_t velocityIdx = hasVelocity ? 1u : 0u;
        const uint32_t depthIdx    = hasVelocity ? 2u : 1u;
        const uint32_t attachmentCount = hasVelocity ? 3u : 2u;

        if (hasVelocity) {
            attachments[1].format         = velocityFormat;
            attachments[1].samples        = VK_SAMPLE_COUNT_1_BIT;
            attachments[1].loadOp         = VK_ATTACHMENT_LOAD_OP_CLEAR;
            attachments[1].storeOp        = VK_ATTACHMENT_STORE_OP_STORE;
            attachments[1].stencilLoadOp  = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            attachments[1].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
            attachments[1].initialLayout  = VK_IMAGE_LAYOUT_UNDEFINED;
            attachments[1].finalLayout    = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        }

        // Depth attachment (index depends on whether velocity is present).
        attachments[depthIdx].format         = depthFormat;
        attachments[depthIdx].samples        = VK_SAMPLE_COUNT_1_BIT;
        attachments[depthIdx].loadOp         = VK_ATTACHMENT_LOAD_OP_CLEAR;
        attachments[depthIdx].storeOp        = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachments[depthIdx].stencilLoadOp  = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachments[depthIdx].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachments[depthIdx].initialLayout  = VK_IMAGE_LAYOUT_UNDEFINED;
        attachments[depthIdx].finalLayout    = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;

        VkAttachmentReference colorRefs[2]{};
        colorRefs[0].attachment = 0;
        colorRefs[0].layout     = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        if (hasVelocity) {
            colorRefs[1].attachment = velocityIdx;
            colorRefs[1].layout     = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        }

        VkAttachmentReference depthRef{};
        depthRef.attachment = depthIdx;
        depthRef.layout     = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;

        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint       = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount    = hasVelocity ? 2u : 1u;
        subpass.pColorAttachments       = colorRefs;
        subpass.pDepthStencilAttachment = &depthRef;

        VkSubpassDependency deps[2]{};
        deps[0].srcSubpass    = VK_SUBPASS_EXTERNAL;
        deps[0].dstSubpass    = 0;
        deps[0].srcStageMask  = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
        deps[0].dstStageMask  = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
        deps[0].srcAccessMask = 0;
        deps[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;

        deps[1].srcSubpass    = 0;
        deps[1].dstSubpass    = VK_SUBPASS_EXTERNAL;
        deps[1].srcStageMask  = VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
        deps[1].dstStageMask  = VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
        deps[1].srcAccessMask = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
        deps[1].dstAccessMask = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT;

        VkRenderPassCreateInfo renderPassInfo{};
        renderPassInfo.sType           = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        renderPassInfo.attachmentCount = attachmentCount;
        renderPassInfo.pAttachments    = attachments;
        renderPassInfo.subpassCount    = 1;
        renderPassInfo.pSubpasses      = &subpass;
        renderPassInfo.dependencyCount = 2;
        renderPassInfo.pDependencies   = deps;

        const VkResult result = vkCreateRenderPass(device, &renderPassInfo, nullptr, &outRenderPass);
        if (result != VK_SUCCESS) {
            log_error("vkCreateRenderPass failed: " + vkResultToString(result));
            return false;
        }

        log_info(std::string("Render pass created (color") +
                 (hasVelocity ? " + velocity" : "") + " + depth)");
        return true;
    }

    // ---------------------------------------------------------------------------
    // createPostRenderPass — E10.1 post-process pass (swapchain output)
    // ---------------------------------------------------------------------------
    bool RenderResourcesBuilder::createPostRenderPass(
            VkDevice device,
            VkFormat colorFormat,
            VkRenderPass& outRenderPass
    ) {
        VkAttachmentDescription colorAttachment{};
        colorAttachment.format         = colorFormat;
        colorAttachment.samples        = VK_SAMPLE_COUNT_1_BIT;
        // CLEAR to be safe — the fullscreen draw covers everything but a
        // black clear protects against driver corner cases on the first
        // frame after acquire when the swapchain image content is
        // undefined.
        colorAttachment.loadOp         = VK_ATTACHMENT_LOAD_OP_CLEAR;
        colorAttachment.storeOp        = VK_ATTACHMENT_STORE_OP_STORE;
        colorAttachment.stencilLoadOp  = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        colorAttachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        colorAttachment.initialLayout  = VK_IMAGE_LAYOUT_UNDEFINED;
        colorAttachment.finalLayout    = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

        VkAttachmentReference colorRef{};
        colorRef.attachment = 0;
        colorRef.layout     = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint    = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments    = &colorRef;

        // Wait for the scene pass to finish writing the offscreen colour
        // before sampling it from the post fragment shader. The scene
        // pass's finalLayout transition handles the image-layout side; the
        // dependency below handles the cache-flush / pipeline barrier.
        VkSubpassDependency deps[2]{};
        deps[0].srcSubpass    = VK_SUBPASS_EXTERNAL;
        deps[0].dstSubpass    = 0;
        deps[0].srcStageMask  = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        deps[0].dstStageMask  = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT |
                                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        deps[0].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        deps[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT |
                                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

        deps[1].srcSubpass    = 0;
        deps[1].dstSubpass    = VK_SUBPASS_EXTERNAL;
        deps[1].srcStageMask  = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        deps[1].dstStageMask  = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
        deps[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        deps[1].dstAccessMask = 0;

        VkRenderPassCreateInfo renderPassInfo{};
        renderPassInfo.sType           = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        renderPassInfo.attachmentCount = 1;
        renderPassInfo.pAttachments    = &colorAttachment;
        renderPassInfo.subpassCount    = 1;
        renderPassInfo.pSubpasses      = &subpass;
        renderPassInfo.dependencyCount = 2;
        renderPassInfo.pDependencies   = deps;

        const VkResult result = vkCreateRenderPass(device, &renderPassInfo, nullptr, &outRenderPass);
        if (result != VK_SUCCESS) {
            log_error("vkCreateRenderPass(post) failed: " + vkResultToString(result));
            return false;
        }
        log_info("Post-process render pass created");
        return true;
    }

    // ---------------------------------------------------------------------------
    // createOffscreenColorResources — E10.1 sampled colour target
    // ---------------------------------------------------------------------------
    bool RenderResourcesBuilder::createOffscreenColorResources(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkExtent2D extent,
            VkFormat format,
            VkImage& outImage,
            VkDeviceMemory& outMemory,
            VkImageView& outImageView,
            VkSampler& outSampler
    ) {
        VkImageCreateInfo imageInfo{};
        imageInfo.sType         = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType     = VK_IMAGE_TYPE_2D;
        imageInfo.format        = format;
        imageInfo.extent        = { extent.width, extent.height, 1 };
        imageInfo.mipLevels     = 1;
        imageInfo.arrayLayers   = 1;
        imageInfo.samples       = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling        = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage         = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                                  VK_IMAGE_USAGE_SAMPLED_BIT;
        imageInfo.sharingMode   = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

        VkResult result = vkCreateImage(device, &imageInfo, nullptr, &outImage);
        if (result != VK_SUCCESS) {
            log_error("vkCreateImage (offscreen colour) failed: " + vkResultToString(result));
            return false;
        }
        VkMemoryRequirements memReqs{};
        vkGetImageMemoryRequirements(device, outImage, &memReqs);
        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType           = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize  = memReqs.size;
        allocInfo.memoryTypeIndex = findMemoryType(physicalDevice, memReqs.memoryTypeBits,
                                                   VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (allocInfo.memoryTypeIndex == UINT32_MAX) {
            log_error("findMemoryType failed for offscreen colour");
            vkDestroyImage(device, outImage, nullptr); outImage = VK_NULL_HANDLE;
            return false;
        }
        result = vkAllocateMemory(device, &allocInfo, nullptr, &outMemory);
        if (result != VK_SUCCESS) {
            log_error("vkAllocateMemory (offscreen colour) failed: " + vkResultToString(result));
            vkDestroyImage(device, outImage, nullptr); outImage = VK_NULL_HANDLE;
            return false;
        }
        result = vkBindImageMemory(device, outImage, outMemory, 0);
        if (result != VK_SUCCESS) {
            log_error("vkBindImageMemory (offscreen colour) failed");
            vkFreeMemory(device, outMemory, nullptr); outMemory = VK_NULL_HANDLE;
            vkDestroyImage(device, outImage, nullptr); outImage = VK_NULL_HANDLE;
            return false;
        }

        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType                           = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image                           = outImage;
        viewInfo.viewType                        = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format                          = format;
        viewInfo.subresourceRange.aspectMask     = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel   = 0;
        viewInfo.subresourceRange.levelCount     = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount     = 1;
        result = vkCreateImageView(device, &viewInfo, nullptr, &outImageView);
        if (result != VK_SUCCESS) {
            log_error("vkCreateImageView (offscreen colour) failed");
            return false;
        }

        VkSamplerCreateInfo samplerInfo{};
        samplerInfo.sType        = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        samplerInfo.magFilter    = VK_FILTER_LINEAR;
        samplerInfo.minFilter    = VK_FILTER_LINEAR;
        samplerInfo.mipmapMode   = VK_SAMPLER_MIPMAP_MODE_LINEAR;
        samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.anisotropyEnable = VK_FALSE;
        samplerInfo.maxAnisotropy    = 1.0f;
        samplerInfo.borderColor   = VK_BORDER_COLOR_INT_OPAQUE_BLACK;
        samplerInfo.unnormalizedCoordinates = VK_FALSE;
        samplerInfo.compareEnable = VK_FALSE;
        samplerInfo.compareOp     = VK_COMPARE_OP_ALWAYS;
        samplerInfo.minLod        = 0.0f;
        samplerInfo.maxLod        = 0.0f;
        result = vkCreateSampler(device, &samplerInfo, nullptr, &outSampler);
        if (result != VK_SUCCESS) {
            log_error("vkCreateSampler (offscreen colour) failed");
            return false;
        }

        log_info("Offscreen colour resources created");
        return true;
    }

    // ---------------------------------------------------------------------------
    // createDepthResources
    // ---------------------------------------------------------------------------
    bool RenderResourcesBuilder::createDepthResources(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkExtent2D extent,
            VkFormat depthFormat,
            VkImage& outImage,
            VkDeviceMemory& outMemory,
            VkImageView& outImageView
    ) {
        // Image
        VkImageCreateInfo imageInfo{};
        imageInfo.sType         = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType     = VK_IMAGE_TYPE_2D;
        imageInfo.format        = depthFormat;
        imageInfo.extent        = { extent.width, extent.height, 1 };
        imageInfo.mipLevels     = 1;
        imageInfo.arrayLayers   = 1;
        imageInfo.samples       = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling        = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage         = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
        imageInfo.sharingMode   = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

        VkResult result = vkCreateImage(device, &imageInfo, nullptr, &outImage);
        if (result != VK_SUCCESS) {
            log_error("vkCreateImage (depth) failed: " + vkResultToString(result));
            return false;
        }

        // Memory
        VkMemoryRequirements memReqs{};
        vkGetImageMemoryRequirements(device, outImage, &memReqs);

        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType           = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize  = memReqs.size;
        allocInfo.memoryTypeIndex = findMemoryType(
                physicalDevice,
                memReqs.memoryTypeBits,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );

        if (allocInfo.memoryTypeIndex == UINT32_MAX) {
            log_error("findMemoryType failed for depth image");
            vkDestroyImage(device, outImage, nullptr);
            outImage = VK_NULL_HANDLE;
            return false;
        }

        result = vkAllocateMemory(device, &allocInfo, nullptr, &outMemory);
        if (result != VK_SUCCESS) {
            log_error("vkAllocateMemory (depth) failed: " + vkResultToString(result));
            vkDestroyImage(device, outImage, nullptr);
            outImage = VK_NULL_HANDLE;
            return false;
        }

        result = vkBindImageMemory(device, outImage, outMemory, 0);
        if (result != VK_SUCCESS) {
            log_error("vkBindImageMemory (depth) failed: " + vkResultToString(result));
            vkFreeMemory(device, outMemory, nullptr);
            outMemory = VK_NULL_HANDLE;
            vkDestroyImage(device, outImage, nullptr);
            outImage = VK_NULL_HANDLE;
            return false;
        }

        // Image view
        // Aspect: depth only for D32; depth+stencil for D24S8.
        VkImageAspectFlags aspectMask = VK_IMAGE_ASPECT_DEPTH_BIT;
        if (depthFormat == VK_FORMAT_D24_UNORM_S8_UINT ||
            depthFormat == VK_FORMAT_D32_SFLOAT_S8_UINT ||
            depthFormat == VK_FORMAT_D16_UNORM_S8_UINT) {
            aspectMask |= VK_IMAGE_ASPECT_STENCIL_BIT;
        }

        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType                           = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image                           = outImage;
        viewInfo.viewType                        = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format                          = depthFormat;
        viewInfo.subresourceRange.aspectMask     = aspectMask;
        viewInfo.subresourceRange.baseMipLevel   = 0;
        viewInfo.subresourceRange.levelCount     = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount     = 1;

        result = vkCreateImageView(device, &viewInfo, nullptr, &outImageView);
        if (result != VK_SUCCESS) {
            log_error("vkCreateImageView (depth) failed: " + vkResultToString(result));
            vkFreeMemory(device, outMemory, nullptr);
            outMemory = VK_NULL_HANDLE;
            vkDestroyImage(device, outImage, nullptr);
            outImage = VK_NULL_HANDLE;
            return false;
        }

        log_info("Depth resources created");
        return true;
    }

    // ---------------------------------------------------------------------------
    // createFramebuffers  (now takes depthImageView)
    // ---------------------------------------------------------------------------
    bool RenderResourcesBuilder::createFramebuffers(
            VkDevice device,
            VkRenderPass renderPass,
            VkExtent2D extent,
            const std::vector<VkImageView>& imageViews,
            VkImageView depthImageView,
            std::vector<VkFramebuffer>& outFramebuffers
    ) {
        outFramebuffers.clear();
        outFramebuffers.reserve(imageViews.size());

        for (size_t i = 0; i < imageViews.size(); ++i) {
            // Attachment order must match the render pass: [color, depth]
            VkImageView attachments[] = { imageViews[i], depthImageView };

            VkFramebufferCreateInfo framebufferInfo{};
            framebufferInfo.sType           = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            framebufferInfo.renderPass      = renderPass;
            framebufferInfo.attachmentCount = 2;
            framebufferInfo.pAttachments    = attachments;
            framebufferInfo.width           = extent.width;
            framebufferInfo.height          = extent.height;
            framebufferInfo.layers          = 1;

            VkFramebuffer framebuffer = VK_NULL_HANDLE;
            const VkResult result = vkCreateFramebuffer(
                    device,
                    &framebufferInfo,
                    nullptr,
                    &framebuffer
            );

            if (result != VK_SUCCESS) {
                log_error(
                        "vkCreateFramebuffer failed for framebuffer " +
                        std::to_string(i) + ": " + vkResultToString(result)
                );
                return false;
            }

            outFramebuffers.push_back(framebuffer);
        }

        log_info("Framebuffers created: " + std::to_string(outFramebuffers.size()));
        return true;
    }

    // ---------------------------------------------------------------------------
    // createSceneFramebuffer — E10.1 single shared framebuffer for the scene
    // pass (offscreen colour + depth).
    // ---------------------------------------------------------------------------
    bool RenderResourcesBuilder::createSceneFramebuffer(
            VkDevice device,
            VkRenderPass sceneRenderPass,
            VkExtent2D extent,
            VkImageView offscreenColorView,
            VkImageView offscreenVelocityView,
            VkImageView depthImageView,
            VkFramebuffer& outFramebuffer
    ) {
        // Attachment order must match the render-pass description in
        // createRenderPass: colour, velocity (optional), depth.
        const bool hasVelocity = (offscreenVelocityView != VK_NULL_HANDLE);
        VkImageView attachments[3]{};
        uint32_t attachmentCount = 0;
        attachments[attachmentCount++] = offscreenColorView;
        if (hasVelocity) attachments[attachmentCount++] = offscreenVelocityView;
        attachments[attachmentCount++] = depthImageView;

        VkFramebufferCreateInfo info{};
        info.sType           = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        info.renderPass      = sceneRenderPass;
        info.attachmentCount = attachmentCount;
        info.pAttachments    = attachments;
        info.width           = extent.width;
        info.height          = extent.height;
        info.layers          = 1;
        const VkResult r = vkCreateFramebuffer(device, &info, nullptr, &outFramebuffer);
        if (r != VK_SUCCESS) {
            log_error("vkCreateFramebuffer(scene) failed: " + vkResultToString(r));
            return false;
        }
        log_info(std::string("Scene framebuffer created (") +
                 std::to_string(attachmentCount) + " attachments)");
        return true;
    }

    // ---------------------------------------------------------------------------
    // createPostFramebuffers — E10.1 per-swapchain-image fbs for post pass.
    // ---------------------------------------------------------------------------
    bool RenderResourcesBuilder::createPostFramebuffers(
            VkDevice device,
            VkRenderPass postRenderPass,
            VkExtent2D extent,
            const std::vector<VkImageView>& swapchainViews,
            std::vector<VkFramebuffer>& outFramebuffers
    ) {
        outFramebuffers.clear();
        outFramebuffers.reserve(swapchainViews.size());
        for (size_t i = 0; i < swapchainViews.size(); ++i) {
            VkFramebufferCreateInfo info{};
            info.sType           = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            info.renderPass      = postRenderPass;
            info.attachmentCount = 1;
            info.pAttachments    = &swapchainViews[i];
            info.width           = extent.width;
            info.height          = extent.height;
            info.layers          = 1;
            VkFramebuffer fb = VK_NULL_HANDLE;
            const VkResult r = vkCreateFramebuffer(device, &info, nullptr, &fb);
            if (r != VK_SUCCESS) {
                log_error("vkCreateFramebuffer(post) failed: " + vkResultToString(r));
                return false;
            }
            outFramebuffers.push_back(fb);
        }
        log_info("Post framebuffers created: " + std::to_string(outFramebuffers.size()));
        return true;
    }

    // ---------------------------------------------------------------------------
    // createCommandPool / createCommandBuffers / createSyncObjects — unchanged
    // ---------------------------------------------------------------------------
    bool RenderResourcesBuilder::createCommandPool(
            VkDevice device,
            uint32_t queueFamilyIndex,
            VkCommandPool& outCommandPool
    ) {
        VkCommandPoolCreateInfo poolInfo{};
        poolInfo.sType            = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        poolInfo.flags            = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        poolInfo.queueFamilyIndex = queueFamilyIndex;

        const VkResult result = vkCreateCommandPool(device, &poolInfo, nullptr, &outCommandPool);
        if (result != VK_SUCCESS) {
            log_error("vkCreateCommandPool failed: " + vkResultToString(result));
            return false;
        }

        log_info("Command pool created");
        return true;
    }

    bool RenderResourcesBuilder::createCommandBuffers(
            VkDevice device,
            VkCommandPool commandPool,
            uint32_t framebufferCount,
            std::vector<VkCommandBuffer>& outCommandBuffers
    ) {
        outCommandBuffers.resize(framebufferCount);

        VkCommandBufferAllocateInfo allocInfo{};
        allocInfo.sType              = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocInfo.commandPool        = commandPool;
        allocInfo.level              = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocInfo.commandBufferCount = framebufferCount;

        const VkResult result = vkAllocateCommandBuffers(
                device,
                &allocInfo,
                outCommandBuffers.data()
        );

        if (result != VK_SUCCESS) {
            log_error("vkAllocateCommandBuffers failed: " + vkResultToString(result));
            return false;
        }

        log_info("Command buffers allocated: " + std::to_string(outCommandBuffers.size()));
        return true;
    }

    bool RenderResourcesBuilder::createSyncObjects(
            VkDevice device,
            VkSemaphore& outImageAvailableSemaphore,
            VkSemaphore& outRenderFinishedSemaphore,
            VkFence& outInFlightFence
    ) {
        VkSemaphoreCreateInfo semaphoreInfo{};
        semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;

        VkFenceCreateInfo fenceInfo{};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;

        VkResult result = vkCreateSemaphore(
                device,
                &semaphoreInfo,
                nullptr,
                &outImageAvailableSemaphore
        );
        if (result != VK_SUCCESS) {
            log_error("vkCreateSemaphore(imageAvailable) failed: " + vkResultToString(result));
            return false;
        }

        result = vkCreateSemaphore(
                device,
                &semaphoreInfo,
                nullptr,
                &outRenderFinishedSemaphore
        );
        if (result != VK_SUCCESS) {
            log_error("vkCreateSemaphore(renderFinished) failed: " + vkResultToString(result));
            return false;
        }

        result = vkCreateFence(
                device,
                &fenceInfo,
                nullptr,
                &outInFlightFence
        );
        if (result != VK_SUCCESS) {
            log_error("vkCreateFence failed: " + vkResultToString(result));
            return false;
        }

        log_info("Sync objects created");
        return true;
    }

    bool RenderResourcesBuilder::recordClearCommandBuffer(
            VkCommandBuffer commandBuffer,
            VkRenderPass renderPass,
            VkFramebuffer framebuffer,
            VkExtent2D extent,
            float r,
            float g,
            float b,
            float a
    ) {
        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;

        VkResult result = vkBeginCommandBuffer(commandBuffer, &beginInfo);
        if (result != VK_SUCCESS) {
            log_error("vkBeginCommandBuffer failed: " + vkResultToString(result));
            return false;
        }

        // Two clear values: color + depth
        VkClearValue clearValues[2]{};
        clearValues[0].color        = {{r, g, b, a}};
        clearValues[1].depthStencil = {1.0f, 0};

        VkRenderPassBeginInfo renderPassInfo{};
        renderPassInfo.sType           = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        renderPassInfo.renderPass      = renderPass;
        renderPassInfo.framebuffer     = framebuffer;
        renderPassInfo.renderArea.offset = {0, 0};
        renderPassInfo.renderArea.extent = extent;
        renderPassInfo.clearValueCount = 2;
        renderPassInfo.pClearValues    = clearValues;

        vkCmdBeginRenderPass(commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdEndRenderPass(commandBuffer);

        result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            log_error("vkEndCommandBuffer failed: " + vkResultToString(result));
            return false;
        }

        return true;
    }

    // ---------------------------------------------------------------------------
    // destroy
    // ---------------------------------------------------------------------------
    void RenderResourcesBuilder::destroy(
            VkDevice device,
            RenderResources& resources
    ) {
        for (VkFramebuffer framebuffer : resources.framebuffers) {
            if (framebuffer != VK_NULL_HANDLE) {
                vkDestroyFramebuffer(device, framebuffer, nullptr);
            }
        }
        resources.framebuffers.clear();

        for (VkFramebuffer framebuffer : resources.postFramebuffers) {
            if (framebuffer != VK_NULL_HANDLE) {
                vkDestroyFramebuffer(device, framebuffer, nullptr);
            }
        }
        resources.postFramebuffers.clear();

        // E10.1 — destroy offscreen colour resources before the scene pass
        // (sampler/view depend on the image which depends on the memory).
        if (resources.offscreenColorSampler != VK_NULL_HANDLE) {
            vkDestroySampler(device, resources.offscreenColorSampler, nullptr);
            resources.offscreenColorSampler = VK_NULL_HANDLE;
        }
        if (resources.offscreenColorView != VK_NULL_HANDLE) {
            vkDestroyImageView(device, resources.offscreenColorView, nullptr);
            resources.offscreenColorView = VK_NULL_HANDLE;
        }
        if (resources.offscreenColorImage != VK_NULL_HANDLE) {
            vkDestroyImage(device, resources.offscreenColorImage, nullptr);
            resources.offscreenColorImage = VK_NULL_HANDLE;
        }
        if (resources.offscreenColorMemory != VK_NULL_HANDLE) {
            vkFreeMemory(device, resources.offscreenColorMemory, nullptr);
            resources.offscreenColorMemory = VK_NULL_HANDLE;
        }

        // E10.2 — velocity attachment cleanup, same shape as colour.
        if (resources.offscreenVelocitySampler != VK_NULL_HANDLE) {
            vkDestroySampler(device, resources.offscreenVelocitySampler, nullptr);
            resources.offscreenVelocitySampler = VK_NULL_HANDLE;
        }
        if (resources.offscreenVelocityView != VK_NULL_HANDLE) {
            vkDestroyImageView(device, resources.offscreenVelocityView, nullptr);
            resources.offscreenVelocityView = VK_NULL_HANDLE;
        }
        if (resources.offscreenVelocityImage != VK_NULL_HANDLE) {
            vkDestroyImage(device, resources.offscreenVelocityImage, nullptr);
            resources.offscreenVelocityImage = VK_NULL_HANDLE;
        }
        if (resources.offscreenVelocityMemory != VK_NULL_HANDLE) {
            vkFreeMemory(device, resources.offscreenVelocityMemory, nullptr);
            resources.offscreenVelocityMemory = VK_NULL_HANDLE;
        }

        if (resources.depthImageView != VK_NULL_HANDLE) {
            vkDestroyImageView(device, resources.depthImageView, nullptr);
            resources.depthImageView = VK_NULL_HANDLE;
        }

        if (resources.depthImage != VK_NULL_HANDLE) {
            vkDestroyImage(device, resources.depthImage, nullptr);
            resources.depthImage = VK_NULL_HANDLE;
        }

        if (resources.depthMemory != VK_NULL_HANDLE) {
            vkFreeMemory(device, resources.depthMemory, nullptr);
            resources.depthMemory = VK_NULL_HANDLE;
        }

        if (resources.renderPass != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device, resources.renderPass, nullptr);
            resources.renderPass = VK_NULL_HANDLE;
        }
        if (resources.postRenderPass != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device, resources.postRenderPass, nullptr);
            resources.postRenderPass = VK_NULL_HANDLE;
        }

        if (!resources.commandBuffers.empty() && resources.commandPool != VK_NULL_HANDLE) {
            vkFreeCommandBuffers(
                    device,
                    resources.commandPool,
                    static_cast<uint32_t>(resources.commandBuffers.size()),
                    resources.commandBuffers.data()
            );
            resources.commandBuffers.clear();
        }

        if (resources.imageAvailableSemaphore != VK_NULL_HANDLE) {
            vkDestroySemaphore(device, resources.imageAvailableSemaphore, nullptr);
            resources.imageAvailableSemaphore = VK_NULL_HANDLE;
        }

        if (resources.renderFinishedSemaphore != VK_NULL_HANDLE) {
            vkDestroySemaphore(device, resources.renderFinishedSemaphore, nullptr);
            resources.renderFinishedSemaphore = VK_NULL_HANDLE;
        }

        if (resources.inFlightFence != VK_NULL_HANDLE) {
            vkDestroyFence(device, resources.inFlightFence, nullptr);
            resources.inFlightFence = VK_NULL_HANDLE;
        }

        if (resources.commandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(device, resources.commandPool, nullptr);
            resources.commandPool = VK_NULL_HANDLE;
        }

        resources.renderReady = false;
        log_info("Render resources destroyed");
    }

} // namespace station