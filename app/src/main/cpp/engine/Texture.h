#pragma once

#include <vulkan/vulkan.h>

#include <cstdint>

namespace station {

    /**
     * GPU texture: VkImage + memory + view + sampler + descriptor set.
     *
     * E8.2 — engine wave that introduces sampled textures. A Texture is a
     * standalone GPU resource; ownership transfers to the engine's texture
     * pool (in `VulkanContext`) once `create*` succeeds. Each Texture
     * pre-allocates its own descriptor set 1 (combined image sampler) so
     * binding it during a draw is a single `vkCmdBindDescriptorSets` call.
     *
     * Two creation paths:
     *  - `createFromPixels` — caller passes raw RGBA8 pixels (used by
     *    the engine itself for the default 1×1 white fallback).
     *  - `createFromPng` — decodes a PNG byte stream via stb_image
     *    (asset PNGs from Kotlin go through this path).
     *
     * Currently single-purpose (sampled in fragment via a single
     * combined-image-sampler at set=1, binding=0). When E9 needs sprite
     * atlases or E10 needs render-to-texture targets, this class
     * generalises by adding usage flags / separate sampler-vs-storage
     * descriptors.
     */
    class Texture {
    public:
        Texture()  = default;
        ~Texture() = default;

        Texture(const Texture&)            = delete;
        Texture& operator=(const Texture&) = delete;

        // Build a texture from a tightly-packed RGBA8 buffer (4 bytes per
        // pixel, no row padding). `descriptorPool` and `setLayout` come
        // from the engine; the caller is responsible for ensuring the pool
        // has capacity for this set.
        bool createFromPixels(VkPhysicalDevice physicalDevice,
                              VkDevice         device,
                              VkQueue          queue,
                              uint32_t         queueFamily,
                              VkDescriptorPool descriptorPool,
                              VkDescriptorSetLayout setLayout,
                              const uint8_t*   pixels,
                              uint32_t         width,
                              uint32_t         height);

        // Decode `pngBytes` via stb_image then forward to createFromPixels.
        // Always demands 4-channel RGBA output regardless of the source
        // PNG's channel count.
        bool createFromPng(VkPhysicalDevice physicalDevice,
                           VkDevice         device,
                           VkQueue          queue,
                           uint32_t         queueFamily,
                           VkDescriptorPool descriptorPool,
                           VkDescriptorSetLayout setLayout,
                           const uint8_t*   pngBytes,
                           uint32_t         length);

        void destroy(VkDevice device, VkDescriptorPool descriptorPool);

        [[nodiscard]] bool             isReady()       const { return m_ready; }
        [[nodiscard]] VkDescriptorSet  descriptorSet() const { return m_descriptorSet; }
        [[nodiscard]] uint32_t         width()         const { return m_width; }
        [[nodiscard]] uint32_t         height()        const { return m_height; }

    private:
        VkImage         m_image         = VK_NULL_HANDLE;
        VkDeviceMemory  m_memory        = VK_NULL_HANDLE;
        VkImageView     m_view          = VK_NULL_HANDLE;
        VkSampler       m_sampler       = VK_NULL_HANDLE;
        VkDescriptorSet m_descriptorSet = VK_NULL_HANDLE;
        uint32_t        m_width         = 0;
        uint32_t        m_height        = 0;
        bool            m_ready         = false;
    };

} // namespace station
