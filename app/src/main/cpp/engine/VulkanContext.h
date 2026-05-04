#pragma once

#include <vulkan/vulkan.h>

#include <cstdint>
#include <vector>

#include "Camera.h"
#include "Mesh.h"
#include "RenderResources.h"

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

        // Scene API — called each frame from engine_api
        void beginScene();
        void drawMesh(uint32_t token, const float modelMatrix[16]);
        void drawPickableMesh(uint32_t token, int32_t objectId,
                              const float modelMatrix[16], float pickRadius);
        void drawBillboardMesh(uint32_t token, float x, float y, float z, float scale);
        void drawPlasmaBillboard(uint32_t token, float x, float y, float z, float scale);
        void drawTranslucentMesh(uint32_t token, const float modelMatrix[16]);
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
            bool     billboard;
            bool     objectFrame;
            float    center[3];
            float    scale;
            float    halfExtents[3];
            float    padding;
            float    tint[4];
            float    modelMatrix[16];
            std::vector<math::Vec3> framePoints;
        };
        std::vector<DrawCommand> m_drawList;
        std::vector<DrawCommand> m_systemDrawList;
        std::vector<DrawCommand> m_plasmaDrawList;
        std::vector<DrawCommand> m_translucentDrawList;  // E1.2 — alpha-blend mesh draws
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
        VkDescriptorSetLayout m_descriptorSetLayout = VK_NULL_HANDLE;
        VkDescriptorPool      m_descriptorPool      = VK_NULL_HANDLE;
        VkDescriptorSet       m_descriptorSet       = VK_NULL_HANDLE;
        VkBuffer              m_uniformBuffer        = VK_NULL_HANDLE;
        VkDeviceMemory        m_uniformMemory        = VK_NULL_HANDLE;
        VkPipelineLayout      m_pipelineLayout       = VK_NULL_HANDLE;
        VkPipeline            m_pipeline             = VK_NULL_HANDLE;  // mesh pipeline
        VkPipeline            m_starPipeline         = VK_NULL_HANDLE;  // star point pipeline
        VkPipeline            m_systemPipeline       = VK_NULL_HANDLE;  // system overlay pipeline
        VkPipeline            m_plasmaPipeline       = VK_NULL_HANDLE;  // additive-blend plasma pipeline
        VkPipeline            m_translucentPipeline  = VK_NULL_HANDLE;  // SRC_ALPHA / ONE_MINUS_SRC_ALPHA mesh pipeline (E1.2)
        VkPipeline            m_framePipeline        = VK_NULL_HANDLE;  // LINE_LIST frame pipeline
        VkShaderModule        m_vertModule           = VK_NULL_HANDLE;
        VkShaderModule        m_fragModule           = VK_NULL_HANDLE;
        MeshGpu               m_frameLineMesh{};
        MeshGpu               m_frameLineMeshEnemy{};
        bool                  m_wideLines            = false;

        bool m_deviceReady  = false;
        bool m_surfaceReady = false;
        bool m_focused      = false;

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
