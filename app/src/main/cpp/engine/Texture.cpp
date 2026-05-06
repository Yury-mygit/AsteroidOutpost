#include "Texture.h"

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

#include <android/log.h>
#include <cstring>

#define LOG_TAG "stationcore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace station {
    namespace {

        uint32_t findMemoryType(VkPhysicalDevice pd, uint32_t filter, VkMemoryPropertyFlags props) {
            VkPhysicalDeviceMemoryProperties mp{};
            vkGetPhysicalDeviceMemoryProperties(pd, &mp);
            for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
                if ((filter & (1u << i)) && (mp.memoryTypes[i].propertyFlags & props) == props)
                    return i;
            return UINT32_MAX;
        }

        bool createBuffer(VkPhysicalDevice pd, VkDevice dev,
                          VkDeviceSize size, VkBufferUsageFlags usage,
                          VkMemoryPropertyFlags props,
                          VkBuffer& outBuf, VkDeviceMemory& outMem) {
            VkBufferCreateInfo bi{};
            bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
            bi.size = size;
            bi.usage = usage;
            bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            if (vkCreateBuffer(dev, &bi, nullptr, &outBuf) != VK_SUCCESS) return false;

            VkMemoryRequirements mr{};
            vkGetBufferMemoryRequirements(dev, outBuf, &mr);

            VkMemoryAllocateInfo ai{};
            ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
            ai.allocationSize = mr.size;
            ai.memoryTypeIndex = findMemoryType(pd, mr.memoryTypeBits, props);
            if (ai.memoryTypeIndex == UINT32_MAX) return false;
            if (vkAllocateMemory(dev, &ai, nullptr, &outMem) != VK_SUCCESS) return false;
            return vkBindBufferMemory(dev, outBuf, outMem, 0) == VK_SUCCESS;
        }

        // One-shot command buffer helper: create temp pool + buffer, return
        // the buffer to caller in BEGIN state. Caller records and ends, then
        // submits via `submitOneShot`. Pool/buffer destroyed at the end.
        struct OneShot {
            VkCommandPool   pool   = VK_NULL_HANDLE;
            VkCommandBuffer buffer = VK_NULL_HANDLE;
        };

        bool beginOneShot(VkDevice dev, uint32_t queueFamily, OneShot& out) {
            VkCommandPoolCreateInfo pci{};
            pci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
            pci.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
            pci.queueFamilyIndex = queueFamily;
            if (vkCreateCommandPool(dev, &pci, nullptr, &out.pool) != VK_SUCCESS) return false;

            VkCommandBufferAllocateInfo ai{};
            ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
            ai.commandPool = out.pool;
            ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
            ai.commandBufferCount = 1;
            if (vkAllocateCommandBuffers(dev, &ai, &out.buffer) != VK_SUCCESS) return false;

            VkCommandBufferBeginInfo bi{};
            bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            return vkBeginCommandBuffer(out.buffer, &bi) == VK_SUCCESS;
        }

        bool submitAndDestroyOneShot(VkDevice dev, VkQueue queue, OneShot& s) {
            if (vkEndCommandBuffer(s.buffer) != VK_SUCCESS) return false;
            VkSubmitInfo si{};
            si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            si.commandBufferCount = 1;
            si.pCommandBuffers = &s.buffer;
            if (vkQueueSubmit(queue, 1, &si, VK_NULL_HANDLE) != VK_SUCCESS) return false;
            // Wait for queue — fine for engine-init textures (default white,
            // future asset preload). For per-frame uploads we'd want fences
            // and async transfer, but that's out of scope for E8.2.
            vkQueueWaitIdle(queue);
            vkFreeCommandBuffers(dev, s.pool, 1, &s.buffer);
            vkDestroyCommandPool(dev, s.pool, nullptr);
            s.buffer = VK_NULL_HANDLE;
            s.pool   = VK_NULL_HANDLE;
            return true;
        }

        void transitionLayout(VkCommandBuffer cmd, VkImage img,
                              VkImageLayout oldL, VkImageLayout newL) {
            VkImageMemoryBarrier b{};
            b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout = oldL;
            b.newLayout = newL;
            b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.image = img;
            b.subresourceRange.aspectMask     = VK_IMAGE_ASPECT_COLOR_BIT;
            b.subresourceRange.baseMipLevel   = 0;
            b.subresourceRange.levelCount     = 1;
            b.subresourceRange.baseArrayLayer = 0;
            b.subresourceRange.layerCount     = 1;

            VkPipelineStageFlags srcStage, dstStage;
            if (oldL == VK_IMAGE_LAYOUT_UNDEFINED &&
                newL == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
                b.srcAccessMask = 0;
                b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            } else if (oldL == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL &&
                       newL == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
                srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            } else {
                LOGE("transitionLayout: unsupported transition %d -> %d", oldL, newL);
                return;
            }
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &b);
        }

    } // namespace

    bool Texture::createFromPng(VkPhysicalDevice pd, VkDevice dev,
                                VkQueue queue, uint32_t queueFamily,
                                VkDescriptorPool pool, VkDescriptorSetLayout setLayout,
                                const uint8_t* pngBytes, uint32_t length) {
        int w = 0, h = 0, ch = 0;
        // Force 4 channels — driver path (R8G8B8A8_UNORM) is universal on
        // mobile GPUs; the wasted alpha byte for opaque PNGs is cheap.
        stbi_uc* pixels = stbi_load_from_memory(pngBytes, (int)length, &w, &h, &ch, STBI_rgb_alpha);
        if (!pixels || w <= 0 || h <= 0) {
            LOGE("Texture::createFromPng: stbi_load failed (%s)", stbi_failure_reason());
            if (pixels) stbi_image_free(pixels);
            return false;
        }
        bool ok = createFromPixels(pd, dev, queue, queueFamily, pool, setLayout,
                                   pixels, (uint32_t)w, (uint32_t)h);
        stbi_image_free(pixels);
        if (ok) LOGI("Texture: PNG decoded → %dx%d (src ch=%d)", w, h, ch);
        return ok;
    }

    bool Texture::createFromPixels(VkPhysicalDevice pd, VkDevice dev,
                                   VkQueue queue, uint32_t queueFamily,
                                   VkDescriptorPool pool, VkDescriptorSetLayout setLayout,
                                   const uint8_t* pixels, uint32_t w, uint32_t h) {
        destroy(dev, pool);
        if (!pixels || !w || !h) { LOGE("Texture::createFromPixels: bad input"); return false; }

        m_width  = w;
        m_height = h;
        const VkDeviceSize bytes = (VkDeviceSize)w * h * 4;

        // Staging buffer (HOST_VISIBLE) — copy pixels into it, then
        // vkCmdCopyBufferToImage transfers to a DEVICE_LOCAL VkImage.
        VkBuffer       stagingBuf = VK_NULL_HANDLE;
        VkDeviceMemory stagingMem = VK_NULL_HANDLE;
        if (!createBuffer(pd, dev, bytes,
                          VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                          VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                          stagingBuf, stagingMem)) {
            LOGE("Texture: staging buffer alloc failed");
            return false;
        }
        void* mapped = nullptr;
        vkMapMemory(dev, stagingMem, 0, bytes, 0, &mapped);
        std::memcpy(mapped, pixels, (size_t)bytes);
        vkUnmapMemory(dev, stagingMem);

        // VkImage: 2D, RGBA8 unorm, single mip, single layer, OPTIMAL tiling.
        VkImageCreateInfo ici{};
        ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        ici.imageType = VK_IMAGE_TYPE_2D;
        ici.format    = VK_FORMAT_R8G8B8A8_UNORM;
        ici.extent    = { w, h, 1 };
        ici.mipLevels = 1;
        ici.arrayLayers = 1;
        ici.samples     = VK_SAMPLE_COUNT_1_BIT;
        ici.tiling      = VK_IMAGE_TILING_OPTIMAL;
        ici.usage       = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (vkCreateImage(dev, &ici, nullptr, &m_image) != VK_SUCCESS) {
            LOGE("Texture: vkCreateImage failed");
            vkDestroyBuffer(dev, stagingBuf, nullptr);
            vkFreeMemory(dev, stagingMem, nullptr);
            destroy(dev, pool);
            return false;
        }
        VkMemoryRequirements mr{};
        vkGetImageMemoryRequirements(dev, m_image, &mr);
        VkMemoryAllocateInfo mai{};
        mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mai.allocationSize  = mr.size;
        mai.memoryTypeIndex = findMemoryType(pd, mr.memoryTypeBits,
                                             VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (mai.memoryTypeIndex == UINT32_MAX ||
            vkAllocateMemory(dev, &mai, nullptr, &m_memory) != VK_SUCCESS ||
            vkBindImageMemory(dev, m_image, m_memory, 0) != VK_SUCCESS) {
            LOGE("Texture: image memory bind failed");
            vkDestroyBuffer(dev, stagingBuf, nullptr);
            vkFreeMemory(dev, stagingMem, nullptr);
            destroy(dev, pool);
            return false;
        }

        // Upload: layout transitions + buffer→image copy on a one-shot CB.
        OneShot one;
        if (!beginOneShot(dev, queueFamily, one)) {
            LOGE("Texture: beginOneShot failed");
            vkDestroyBuffer(dev, stagingBuf, nullptr);
            vkFreeMemory(dev, stagingMem, nullptr);
            destroy(dev, pool);
            return false;
        }
        transitionLayout(one.buffer, m_image,
                         VK_IMAGE_LAYOUT_UNDEFINED,
                         VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        VkBufferImageCopy copy{};
        copy.bufferOffset = 0;
        copy.bufferRowLength = 0;       // tightly packed
        copy.bufferImageHeight = 0;
        copy.imageSubresource.aspectMask     = VK_IMAGE_ASPECT_COLOR_BIT;
        copy.imageSubresource.mipLevel       = 0;
        copy.imageSubresource.baseArrayLayer = 0;
        copy.imageSubresource.layerCount     = 1;
        copy.imageOffset = { 0, 0, 0 };
        copy.imageExtent = { w, h, 1 };
        vkCmdCopyBufferToImage(one.buffer, stagingBuf, m_image,
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
        transitionLayout(one.buffer, m_image,
                         VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                         VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        if (!submitAndDestroyOneShot(dev, queue, one)) {
            LOGE("Texture: submitOneShot failed");
            vkDestroyBuffer(dev, stagingBuf, nullptr);
            vkFreeMemory(dev, stagingMem, nullptr);
            destroy(dev, pool);
            return false;
        }
        vkDestroyBuffer(dev, stagingBuf, nullptr);
        vkFreeMemory(dev, stagingMem, nullptr);

        // ImageView (2D, RGBA8) — what the descriptor binds.
        VkImageViewCreateInfo vci{};
        vci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vci.image = m_image;
        vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vci.format   = VK_FORMAT_R8G8B8A8_UNORM;
        vci.subresourceRange.aspectMask     = VK_IMAGE_ASPECT_COLOR_BIT;
        vci.subresourceRange.baseMipLevel   = 0;
        vci.subresourceRange.levelCount     = 1;
        vci.subresourceRange.baseArrayLayer = 0;
        vci.subresourceRange.layerCount     = 1;
        if (vkCreateImageView(dev, &vci, nullptr, &m_view) != VK_SUCCESS) {
            LOGE("Texture: vkCreateImageView failed"); destroy(dev, pool); return false;
        }

        // Sampler — bilinear filter, repeat addressing, no anisotropy (mobile).
        VkSamplerCreateInfo sci{};
        sci.sType        = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        sci.magFilter    = VK_FILTER_LINEAR;
        sci.minFilter    = VK_FILTER_LINEAR;
        sci.mipmapMode   = VK_SAMPLER_MIPMAP_MODE_LINEAR;
        sci.addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT;
        sci.addressModeV = VK_SAMPLER_ADDRESS_MODE_REPEAT;
        sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_REPEAT;
        sci.anisotropyEnable = VK_FALSE;
        sci.maxAnisotropy    = 1.0f;
        sci.borderColor   = VK_BORDER_COLOR_INT_OPAQUE_BLACK;
        sci.unnormalizedCoordinates = VK_FALSE;
        sci.compareEnable = VK_FALSE;
        sci.compareOp     = VK_COMPARE_OP_ALWAYS;
        sci.minLod        = 0.0f;
        sci.maxLod        = 0.0f;
        if (vkCreateSampler(dev, &sci, nullptr, &m_sampler) != VK_SUCCESS) {
            LOGE("Texture: vkCreateSampler failed"); destroy(dev, pool); return false;
        }

        // Descriptor set: one combined image sampler at binding 0.
        VkDescriptorSetAllocateInfo dsai{};
        dsai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        dsai.descriptorPool = pool;
        dsai.descriptorSetCount = 1;
        dsai.pSetLayouts = &setLayout;
        if (vkAllocateDescriptorSets(dev, &dsai, &m_descriptorSet) != VK_SUCCESS) {
            LOGE("Texture: vkAllocateDescriptorSets failed"); destroy(dev, pool); return false;
        }
        VkDescriptorImageInfo dii{};
        dii.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        dii.imageView   = m_view;
        dii.sampler     = m_sampler;
        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = m_descriptorSet;
        write.dstBinding = 0;
        write.descriptorType  = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.descriptorCount = 1;
        write.pImageInfo      = &dii;
        vkUpdateDescriptorSets(dev, 1, &write, 0, nullptr);

        m_ready = true;
        return true;
    }

    void Texture::destroy(VkDevice dev, VkDescriptorPool pool) {
        if (m_descriptorSet != VK_NULL_HANDLE && pool != VK_NULL_HANDLE) {
            // FREE_DESCRIPTOR_SET_BIT must be set on the pool for this to work;
            // engine pool will be created with that flag (see VulkanContext).
            vkFreeDescriptorSets(dev, pool, 1, &m_descriptorSet);
            m_descriptorSet = VK_NULL_HANDLE;
        }
        if (m_sampler != VK_NULL_HANDLE) { vkDestroySampler(dev, m_sampler, nullptr); m_sampler = VK_NULL_HANDLE; }
        if (m_view    != VK_NULL_HANDLE) { vkDestroyImageView(dev, m_view, nullptr);  m_view    = VK_NULL_HANDLE; }
        if (m_image   != VK_NULL_HANDLE) { vkDestroyImage(dev, m_image, nullptr);     m_image   = VK_NULL_HANDLE; }
        if (m_memory  != VK_NULL_HANDLE) { vkFreeMemory(dev, m_memory, nullptr);      m_memory  = VK_NULL_HANDLE; }
        m_width = 0; m_height = 0; m_ready = false;
    }

} // namespace station
