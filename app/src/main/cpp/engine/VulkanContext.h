#pragma once

#include <vulkan/vulkan.h>

#include <chrono>
#include <cstdint>
#include <vector>

#include "Camera.h"
#include "Mesh.h"
#include "RenderResources.h"
#include "Texture.h"

namespace station {

    class VulkanContext {
    public:
        VulkanContext()  = default;
        ~VulkanContext() = default;

        VulkanContext(const VulkanContext&) = delete;
        VulkanContext& operator=(const VulkanContext&) = delete;

        bool initDevice();
        bool createSurface(void* nativeHandle, int width, int height);
        void destroySurface();
        void destroy();
        bool createPipeline(const std::vector<uint32_t>& vertSpv,
                            const std::vector<uint32_t>& fragSpv);

        void setFocused(bool focused);
        [[nodiscard]] bool isFocused()      const { return m_focused; }
        [[nodiscard]] bool isSurfaceReady() const { return m_surfaceReady; }

        uint32_t uploadMesh(const MeshData& meshData);
        void     freeMesh(uint32_t token);

        // E8.3 — texture pool. Token is 1-based index into m_texturePool;
        // 0 = failure / "no texture" sentinel. PNG bytes decoded internally
        // via stb_image. Per-texture descriptor set (set 1) is allocated
        // up-front so a textured draw is just a vkCmdBindDescriptorSets +
        // vkCmdDraw without per-frame churn.
        uint32_t uploadTexture(const uint8_t* pngBytes, uint32_t length);
        // E8.4 — same as uploadTexture but takes raw RGBA8 pixels directly,
        // skipping the PNG decode. `length` must be width*height*4.
        uint32_t uploadTextureRaw(const uint8_t* rgba8, uint32_t width, uint32_t height);
        void     freeTexture(uint32_t token);

        // Scene API — called each frame from engine_api
        void beginScene();
        void drawMesh(uint32_t token, const float modelMatrix[16]);
        void drawPickableMesh(uint32_t token, int32_t objectId,
                              const float modelMatrix[16], float pickRadius);
        void drawBillboardMesh(uint32_t token, float x, float y, float z, float scale);
        void drawPlasmaBillboard(uint32_t token, float x, float y, float z,
                                 float scaleH, float scaleV,
                                 float r, float g, float b, float a);
        // E3.1 — `material` selects a fragment-shader branch in the translucent
        // pipeline: 0 = plain (per-vertex alpha only), 1 = nebula (FBM noise
        // modulates alpha), 2 = hex (procedural hex grid modulates alpha).
        // Encoded into pc.tint.y/z so the shader can branch with no extra API.
        void drawTranslucentMesh(uint32_t token, const float modelMatrix[16], int32_t material = 0);
        // E7 — additive-blend mesh draw. ONE/ONE blend (like plasma billboards)
        // but accepts arbitrary 3D meshes via model matrix. Per-vertex alpha
        // controls glow falloff; (r,g,b,a) tint multiplies in as colour and
        // brightness scalar through pc.plasmaColor. `material` selects a
        // fragment-shader branch: 0 = plain pass-through, 1 = fire (heat-ramp
        // + FBM turbulence + Fresnel-like edge soft-fade). Used for fireballs,
        // plasma laser beams, electric arcs, etc.
        void drawAdditiveMesh(uint32_t token, const float modelMatrix[16],
                              float r, float g, float b, float a,
                              int32_t material = 0);
        // E8.3 — textured opaque mesh. Same opaque pipeline as drawMesh, but
        // binds the given texture's descriptor set 1 and sets pc.textureMode
        // so the fragment shader samples vUV instead of using vColor.rgb.
        // (r,g,b,a) is a per-draw tint multiplied into the sampled colour
        // (default white = no tint). Mesh must have authored UVs (TEXCOORD_0)
        // for the lookup to be meaningful.
        void drawTexturedMesh(uint32_t meshToken, uint32_t textureToken,
                              const float modelMatrix[16],
                              float r, float g, float b, float a);
        void drawObjectFrameMesh(uint32_t frameToken, uint32_t targetToken, const float modelMatrix[16], float padding, const float tint[4]);
        void drawGameplayFrameMesh(uint32_t frameToken, const float modelMatrix[16],
                                   const float* localPoints, int32_t pointCount,
                                   float padding, float lineWidth, const float tint[4]);
        void endScene();
        int32_t pickObject(float screenX, float screenY, int32_t currentObjectId) const;
        bool projectGameplayBounds(const float modelMatrix[16],
                                   const float* localPoints,
                                   int32_t pointCount,
                                   float padding,
                                   float outBounds[7]) const;
        bool projectMeshBounds(uint32_t token,
                               const float modelMatrix[16],
                               float padding,
                               float outBounds[7]) const;

        void orbitCamera(float deltaYaw, float deltaPitch);
        void rollCamera(float deltaAngle);
        void panCamera(float dx, float dy);
        void zoomCamera(float factor);
        void zoomCameraAt(float factor, float screenX, float screenY);
        void resetCamera() { m_camera.reset(); }
        void renderFrame();

    private:
        VkInstance       m_instance       = VK_NULL_HANDLE;
        VkSurfaceKHR     m_surface        = VK_NULL_HANDLE;
        VkPhysicalDevice m_physicalDevice = VK_NULL_HANDLE;
        uint32_t         m_queueFamily    = UINT32_MAX;
        VkDevice         m_device         = VK_NULL_HANDLE;
        VkQueue          m_graphicsQueue  = VK_NULL_HANDLE;

        struct SurfaceSelection {
            VkSurfaceFormatKHR format{};
            VkPresentModeKHR   presentMode = VK_PRESENT_MODE_FIFO_KHR;
            VkExtent2D         extent{};
            uint32_t           imageCount = 0;
        } m_sel{};

        VkSwapchainKHR           m_swapchain = VK_NULL_HANDLE;
        std::vector<VkImage>     m_swapImages;
        std::vector<VkImageView> m_swapViews;

        RenderResources m_renderResources{};
        Camera          m_camera{};
        VkFormat        m_depthFormat = VK_FORMAT_UNDEFINED;

        // Mesh pool for user-loaded meshes
        static constexpr uint32_t kMaxMeshes = 64;
        MeshGpu  m_meshPool[kMaxMeshes]{};
        bool     m_meshUsed[kMaxMeshes]{};
        // E8.3 — texture slots. Capacity matches the descriptor pool sized
        // in createPipelineInfra (kMaxTextures there); keep in sync if
        // either changes. Distinct from `m_texturePool` (the VkDescriptorPool
        // for sampler sets) — naming inherited from the parallel mesh layout.
        static constexpr uint32_t kMaxTextures = 64;
        Texture  m_textureSlots[kMaxTextures]{};
        bool     m_textureUsed[kMaxTextures]{};
        struct MeshBounds {
            float center[3];
            float halfExtents[3];
            float radius;
        };
        MeshBounds m_meshBounds[kMaxMeshes]{};
        std::vector<math::Vec3> m_meshFramePoints[kMaxMeshes];
        // Draw list — rebuilt each frame by Kotlin via scene API
        struct DrawCommand {
            uint32_t token;
            uint32_t targetToken;
            uint32_t textureToken;     // E8.3 — 0 = no texture (default white set 1)
            bool     billboard;
            bool     objectFrame;
            float    center[3];
            float    scale;            // billboard horizontal half-size (legacy: uniform)
            float    scaleV;           // E5.2 — billboard vertical half-size (plasma only)
            float    halfExtents[3];
            float    padding;
            float    tint[4];
            float    plasmaColor[4];   // E5.1 — per-billboard tint for plasma flashes
            float    modelMatrix[16];
            std::vector<math::Vec3> framePoints;
        };
        std::vector<DrawCommand> m_drawList;
        std::vector<DrawCommand> m_systemDrawList;
        std::vector<DrawCommand> m_plasmaDrawList;
        std::vector<DrawCommand> m_translucentDrawList;  // E1.2 — alpha-blend mesh draws
        std::vector<DrawCommand> m_additiveDrawList;     // E7   — ONE/ONE additive mesh draws
        std::vector<DrawCommand> m_texturedDrawList;     // E8.3 — textured opaque mesh draws
        bool m_sceneOpen = false;

        struct PickRecord {
            int32_t objectId;
            uint32_t token;
            float center[3];
            float radius;
        };
        std::vector<PickRecord> m_pickRecords;

        // Star-field (generated once)
        MeshGpu  m_starMesh{};

        // Pipelines
        VkDescriptorSetLayout m_descriptorSetLayout = VK_NULL_HANDLE;  // set 0 — UBO
        // E8.2 — set 1 layout for combined image samplers (textures). One
        // binding (binding 0, FRAGMENT_BIT). All pipelines share this layout
        // via `m_pipelineLayout`. Default white texture's set is bound at
        // frame start; textured draws (E8.3+) rebind set 1 per draw.
        VkDescriptorSetLayout m_textureSetLayout    = VK_NULL_HANDLE;
        VkDescriptorPool      m_descriptorPool      = VK_NULL_HANDLE;  // set-0 pool (1 set, the UBO)
        VkDescriptorPool      m_texturePool         = VK_NULL_HANDLE;  // set-1 pool, sized for many textures
        VkDescriptorSet       m_descriptorSet       = VK_NULL_HANDLE;
        VkBuffer              m_uniformBuffer        = VK_NULL_HANDLE;
        VkDeviceMemory        m_uniformMemory        = VK_NULL_HANDLE;
        // E8.2 — default 1×1 white texture used as a no-op when a draw
        // doesn't bind its own texture. Always present once
        // createPipelineInfra succeeds; lifetime tied to the engine.
        Texture               m_defaultWhiteTexture;
        VkPipelineLayout      m_pipelineLayout       = VK_NULL_HANDLE;
        VkPipeline            m_pipeline             = VK_NULL_HANDLE;  // mesh pipeline
        VkPipeline            m_starPipeline         = VK_NULL_HANDLE;  // star point pipeline
        VkPipeline            m_systemPipeline       = VK_NULL_HANDLE;  // system overlay pipeline
        VkPipeline            m_plasmaPipeline       = VK_NULL_HANDLE;  // additive-blend plasma pipeline
        VkPipeline            m_translucentPipeline  = VK_NULL_HANDLE;  // SRC_ALPHA / ONE_MINUS_SRC_ALPHA mesh pipeline (E1.2)
        VkPipeline            m_additivePipeline     = VK_NULL_HANDLE;  // ONE/ONE additive mesh pipeline (E7)
        VkPipeline            m_framePipeline        = VK_NULL_HANDLE;  // LINE_LIST frame pipeline
        VkShaderModule        m_vertModule           = VK_NULL_HANDLE;
        VkShaderModule        m_fragModule           = VK_NULL_HANDLE;
        MeshGpu               m_frameLineMesh{};
        MeshGpu               m_frameLineMeshEnemy{};
        bool                  m_wideLines            = false;

        bool m_deviceReady  = false;
        bool m_surfaceReady = false;
        bool m_focused      = false;

        // E6 — wall-clock baseline for time push-constant. Set on first
        // renderFrame; elapsed seconds (now - m_renderStart) feed pc.time
        // so the fragment shader can animate FBM turbulence and other
        // procedural effects.
        std::chrono::steady_clock::time_point m_renderStart{};
        bool m_renderStartInitialised = false;

        bool pickQueueFamily();
        bool createDevice();
        bool selectSurfaceProps(int width, int height);
        bool createSwapchain();
        bool createSwapViews();
        bool createDepthAndFramebuffers();
        bool createCommandInfra();
        bool createSyncObjects();
        bool createPipelineInfra();
        void destroyPipelineInfra();
        void destroySwapchain();
        float computeSceneFarClip() const;
        void updateUniformBuffer();

        static uint32_t findMemoryType(VkPhysicalDevice pd, uint32_t filter,
                                       VkMemoryPropertyFlags props);
        static bool createBuffer(VkPhysicalDevice pd, VkDevice dev,
                                 VkDeviceSize size, VkBufferUsageFlags usage,
                                 VkMemoryPropertyFlags props,
                                 VkBuffer& outBuf, VkDeviceMemory& outMem);
    };

} // namespace station
