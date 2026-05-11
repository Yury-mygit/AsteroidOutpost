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
                            const std::vector<uint32_t>& fragSpv,
                            const std::vector<uint32_t>& particleVertSpv = {},
                            const std::vector<uint32_t>& particleFragSpv = {},
                            const std::vector<uint32_t>& postVertSpv = {},
                            const std::vector<uint32_t>& postFragSpv = {},
                            const std::vector<uint32_t>& beamVertSpv = {},
                            const std::vector<uint32_t>& beamFragSpv = {},
                            const std::vector<uint32_t>& backgroundVertSpv = {},
                            const std::vector<uint32_t>& backgroundFragSpv = {},
                            const std::vector<uint32_t>& forceFieldVertSpv = {},
                            const std::vector<uint32_t>& forceFieldFragSpv = {});

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

        // E9 — submit a particle batch. `instanceFloats` is an interleaved
        // array of `count * kParticleFloatStride` floats (per-instance pos3
        // + size1 + color4). `mode` picks pipeline: 0 = additive (sparks),
        // 1 = alpha-textured (smoke/debris). `textureToken` is only used
        // by the alpha-textured pipeline; pass 0 for additive (default
        // white texture covers the binding).
        void drawParticles(uint32_t meshToken, uint32_t textureToken,
                           const float* instanceFloats, uint32_t count,
                           int32_t mode);
        static constexpr uint32_t kParticleFloatStride = 8; // pos3 + size1 + rgba4

        // Scene API — called each frame from engine_api.
        // E10.3 — every mesh-style draw takes an optional `prevModelMatrix`
        // (nullptr = "no prev tracking", engine treats prev_model = current
        // model → zero screen-space velocity for that draw). The vertex
        // shader reads prev_model from descriptor set 2 (binding 0) with
        // a dynamic offset that the render loop pushes per draw. This is
        // the per-object input to the motion-blur shader (E10.4 — currently
        // post.frag is still passthrough).
        void beginScene();
        void drawMesh(uint32_t token, const float modelMatrix[16],
                      const float prevModelMatrix[16] = nullptr);
        void drawPickableMesh(uint32_t token, int32_t objectId,
                              const float modelMatrix[16], float pickRadius,
                              const float prevModelMatrix[16] = nullptr);
        void drawBillboardMesh(uint32_t token, float x, float y, float z, float scale);
        // E11 — `rotation` (radians) rotates the mesh in its local X-Z
        // plane (around Y) before the camera-aligned billboard transform.
        // 0 keeps legacy "mesh local +Z = screen-up" behaviour. Useful for
        // non-circular plasma meshes (muzzle cones, streak quads) that
        // need to orient along an arbitrary direction in screen space.
        // E12 — `lightningSeed` (>0) opts the draw into the lightning-bolt
        // sub-shader: the fragment paints a procedural electric arc instead
        // of the heat-ramp flame. Seed value reaches the shader via
        // `pc.tint.z`; setting `lightningSeed = 0` keeps legacy plasma
        // flash behaviour and is the default everywhere except the railgun
        // muzzle stack.
        void drawPlasmaBillboard(uint32_t token, float x, float y, float z,
                                 float scaleH, float scaleV,
                                 float r, float g, float b, float a,
                                 float rotation = 0.0f,
                                 float lightningSeed = 0.0f);
        // E3.1 — `material` selects a fragment-shader branch in the translucent
        // pipeline: 0 = plain (per-vertex alpha only), 1 = nebula (FBM noise
        // modulates alpha), 2 = hex (procedural hex grid modulates alpha).
        // Encoded into pc.tint.y/z so the shader can branch with no extra API.
        void drawTranslucentMesh(uint32_t token, const float modelMatrix[16],
                                 int32_t material = 0,
                                 const float prevModelMatrix[16] = nullptr);
        // E7 — additive-blend mesh draw. ONE/ONE blend (like plasma billboards)
        // but accepts arbitrary 3D meshes via model matrix. Per-vertex alpha
        // controls glow falloff; (r,g,b,a) tint multiplies in as colour and
        // brightness scalar through pc.plasmaColor. `material` selects a
        // fragment-shader branch: 0 = plain pass-through, 1 = fire (heat-ramp
        // + FBM turbulence + Fresnel-like edge soft-fade). Used for fireballs,
        // plasma laser beams, electric arcs, etc.
        void drawAdditiveMesh(uint32_t token, const float modelMatrix[16],
                              float r, float g, float b, float a,
                              int32_t material = 0,
                              const float prevModelMatrix[16] = nullptr);
        // E8.3 — textured opaque mesh. Same opaque pipeline as drawMesh, but
        // binds the given texture's descriptor set 1 and sets pc.textureMode
        // so the fragment shader samples vUV instead of using vColor.rgb.
        // (r,g,b,a) is a per-draw tint multiplied into the sampled colour
        // (default white = no tint). Mesh must have authored UVs (TEXCOORD_0)
        // for the lookup to be meaningful.
        void drawTexturedMesh(uint32_t meshToken, uint32_t textureToken,
                              const float modelMatrix[16],
                              float r, float g, float b, float a,
                              const float prevModelMatrix[16] = nullptr);
        // E14 — dedicated laser-beam draw call. Public-quality API: caller
        // gives world-space start/end + perpendicular width + RGBA. Engine
        // builds a view-aligned quad on the GPU (vertex shader expands it
        // from gl_VertexIndex; no Kotlin geometry generation), runs the
        // beam fragment shader (Gaussian core + halo, sharp endpoints,
        // gentle pulse) on the additive pipeline. Routed through scene
        // pass so depth-test occludes beams behind opaque geometry. Does
        // NOT need any descriptor set or mesh handle.
        void drawLaserBeam(float startX, float startY, float startZ,
                           float endX,   float endY,   float endZ,
                           float width,
                           float r, float g, float b, float a);

        // E20 — force-field shield. Renders the supplied hemisphere mesh
        // via the dedicated forcefield pipeline (own layout, push constants
        // = vec4 centerRadius + vec4 impacts[4]). The shader produces
        // fresnel-rim + Gaussian-impact-bumps additive output. Set 0 is
        // the only descriptor set; no per-draw UBO, no texture.
        void drawForceField(uint32_t meshToken,
                            float cx, float cy, float cz, float radius,
                            const float impacts[16]);
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
        // E21 — public camera target setter; used by route-mode missions
        // to track the moving ship each frame.
        void setCameraTarget(float x, float y, float z) { m_camera.setTarget(x, y, z); }
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
            uint32_t perDrawUboOffset; // E10.3 — byte offset of this draw's
                                       // prev_model slot in m_perDrawUboBuffer.
                                       // Plasma billboards / frame meshes /
                                       // particles share a single zero-velocity
                                       // slot; mesh-style draws each take their
                                       // own slot during the draw* call.
            bool     billboard;
            bool     objectFrame;
            float    center[3];
            float    scale;            // billboard horizontal half-size (legacy: uniform)
            float    scaleV;           // E5.2 — billboard vertical half-size (plasma only)
            float    rotation;         // E11 — local Y-axis rotation (radians, plasma only)
            float    lightningSeed;    // E12 — >0 enables lightning-bolt sub-shader (plasma only)
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

        // E9 — particle batches. One ParticleBatch per drawParticles call;
        // the engine uploads its float data into the right shared instance
        // VkBuffer at the start of renderFrame and draws each batch as a
        // single instanced draw call.
        struct ParticleBatch {
            uint32_t meshToken;
            uint32_t textureToken;
            uint32_t bufferOffsetFloats;  // offset into m_particle{Add,Alpha}InstanceMapped
            uint32_t count;
            int32_t  mode;                // 0 = additive, 1 = alpha-textured
        };
        std::vector<ParticleBatch> m_particleBatches;
        // Pending uploads accumulated by drawParticles between begin/endScene.
        // Engine concatenates all additive batches into the additive
        // instance buffer and all alpha batches into the alpha instance
        // buffer at renderFrame start so the GPU sees a single mapped
        // memory write per pipeline.
        std::vector<float> m_particleAdditiveStaging;
        std::vector<float> m_particleAlphaStaging;
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

        // E9 — instance buffers for the two particle pipelines. HOST_VISIBLE
        // and persistently mapped so renderFrame can memcpy the staging
        // arrays straight into them with no map/unmap churn. Sized for
        // kMaxParticles (4096) per pipeline, well beyond expected peak.
        static constexpr uint32_t kMaxParticles = 4096;
        VkBuffer       m_particleAdditiveInstanceBuffer = VK_NULL_HANDLE;
        VkDeviceMemory m_particleAdditiveInstanceMemory = VK_NULL_HANDLE;
        void*          m_particleAdditiveInstanceMapped = nullptr;
        VkBuffer       m_particleAlphaInstanceBuffer    = VK_NULL_HANDLE;
        VkDeviceMemory m_particleAlphaInstanceMemory    = VK_NULL_HANDLE;
        void*          m_particleAlphaInstanceMapped    = nullptr;
        VkPipelineLayout      m_pipelineLayout       = VK_NULL_HANDLE;
        VkPipeline            m_pipeline             = VK_NULL_HANDLE;  // mesh pipeline
        VkPipeline            m_starPipeline         = VK_NULL_HANDLE;  // star point pipeline
        // E18 — fullscreen FBM nebula backdrop. Empty vertex input,
        // draws 3 verts forming a fullscreen triangle; depth test/write
        // off so it sits at far plane unconditionally.
        VkPipeline            m_backgroundPipeline   = VK_NULL_HANDLE;
        VkShaderModule        m_backgroundVertModule = VK_NULL_HANDLE;
        VkShaderModule        m_backgroundFragModule = VK_NULL_HANDLE;
        VkPipeline            m_systemPipeline       = VK_NULL_HANDLE;  // system overlay pipeline
        VkPipeline            m_plasmaPipeline       = VK_NULL_HANDLE;  // additive-blend plasma pipeline
        VkPipeline            m_translucentPipeline  = VK_NULL_HANDLE;  // SRC_ALPHA / ONE_MINUS_SRC_ALPHA mesh pipeline (E1.2)
        VkPipeline            m_additivePipeline     = VK_NULL_HANDLE;  // ONE/ONE additive mesh pipeline (E7)
        VkPipeline            m_framePipeline        = VK_NULL_HANDLE;  // LINE_LIST frame pipeline
        // E9 — particle pipelines. Same per-instance binding 1 layout
        // (pos+size, color), differ only in blend state.
        VkPipeline            m_particleAdditivePipeline = VK_NULL_HANDLE;  // ONE/ONE
        VkPipeline            m_particleAlphaPipeline    = VK_NULL_HANDLE;  // SRC_ALPHA / ONE_MINUS_SRC_ALPHA
        VkShaderModule        m_vertModule           = VK_NULL_HANDLE;
        VkShaderModule        m_fragModule           = VK_NULL_HANDLE;
        VkShaderModule        m_particleVertModule   = VK_NULL_HANDLE;
        VkShaderModule        m_particleFragModule   = VK_NULL_HANDLE;
        // E10.1 — post-process pipeline (fullscreen-triangle, samples
        // offscreen colour and writes to swapchain). Own pipeline layout
        // because it doesn't need the scene UBO/push-constants — just
        // one descriptor set with the offscreen sampler.
        VkPipeline            m_postPipeline         = VK_NULL_HANDLE;
        VkPipelineLayout      m_postPipelineLayout   = VK_NULL_HANDLE;
        VkDescriptorSetLayout m_postSetLayout        = VK_NULL_HANDLE;
        VkDescriptorPool      m_postDescriptorPool   = VK_NULL_HANDLE;
        VkDescriptorSet       m_postDescriptorSet    = VK_NULL_HANDLE;
        VkShaderModule        m_postVertModule       = VK_NULL_HANDLE;
        VkShaderModule        m_postFragModule       = VK_NULL_HANDLE;
        // E14 — dedicated beam pipeline. Own minimal pipeline layout (set 0
        // = scene UBO for view/proj, push constants for per-beam params).
        // No vertex bindings — vertex shader uses gl_VertexIndex to expand
        // a 6-vertex quad spanning the segment. Additive ONE/ONE blend,
        // depth-test on read-only (occluded by opaque geometry, doesn't
        // occlude later VFX). Velocity attachment writeMask=0 to match
        // overlay convention.
        VkPipeline            m_beamPipeline         = VK_NULL_HANDLE;
        VkPipelineLayout      m_beamPipelineLayout   = VK_NULL_HANDLE;
        VkShaderModule        m_beamVertModule       = VK_NULL_HANDLE;
        VkShaderModule        m_beamFragModule       = VK_NULL_HANDLE;
        // Per-beam push constants — std140-aligned for Vulkan push_constant
        // layout. start/end are vec3 padded to 16-byte slots. Total 56
        // bytes used + 8 trailing padding = 64 bytes. Well under the
        // 128-byte minimum guarantee.
        struct BeamPushConstants {
            float start[3];   float _pad0;
            float end[3];     float _pad1;
            float color[4];
            float width;
            float time;
            float _pad2[2];
        };
        // Beam draws are stored as the push constant struct directly so
        // renderFrame can memcpy each one straight into vkCmdPushConstants.
        std::vector<BeamPushConstants> m_beamDrawList;
        // E20 — dedicated force-field pipeline. Own pipeline layout (set 0
        // = scene UBO; push constants = vec4 centerRadius + vec4 impacts[4]
        // = 80 bytes, well under the 128-byte Vulkan minimum guarantee).
        // Additive ONE/ONE blend, depth-test ON read-only, cull mode taken
        // from gpCI (NONE). Velocity attachment writeMask=0.
        VkPipeline            m_forceFieldPipeline       = VK_NULL_HANDLE;
        VkPipelineLayout      m_forceFieldPipelineLayout = VK_NULL_HANDLE;
        VkShaderModule        m_forceFieldVertModule     = VK_NULL_HANDLE;
        VkShaderModule        m_forceFieldFragModule     = VK_NULL_HANDLE;
        // Force-field push constants. vec4 + 4×vec4 = 16 + 64 = 80 bytes.
        struct ForceFieldPushConstants {
            float centerRadius[4];  // 16 bytes — xyz = world centre, w = radius
            float impacts[16];      // 64 bytes — 4×(x,y,z,age); age≥1 = empty slot
        };
        struct ForceFieldDraw {
            uint32_t meshToken;
            ForceFieldPushConstants pc;
        };
        std::vector<ForceFieldDraw> m_forceFieldDrawList;
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

        // E10.3 — previous-frame camera matrices, cached at end of
        // updateUniformBuffer and uploaded as prev_view / prev_proj to the
        // UBO on the NEXT frame. First frame leaves them at identity which
        // produces zero camera-velocity (correct for "no history yet").
        float m_prevView[16]{};
        float m_prevProj[16]{};
        bool  m_prevCameraInitialised = false;

        // E10.3 — per-draw dynamic UBO. Holds prev_model per draw call;
        // the vertex shader reads it from descriptor set 2 (binding 0)
        // with a dynamic offset that the engine updates per draw via
        // vkCmdBindDescriptorSets. Persistent-mapped HOST_VISIBLE so each
        // draw call's prev_model write is a single memcpy. Sized for
        // kMaxDrawsPerFrame slots; the cursor resets in beginScene and
        // grows as draw* calls land. Dynamic-offset rather than push-const
        // because growing push-const past the 128-byte Vulkan minimum
        // would trip device-cliff failures on older Adreno/Mali; UBO with
        // dynamic offset is portable across every Vulkan implementation.
        static constexpr uint32_t kMaxDrawsPerFrame = 4096;
        VkBuffer       m_perDrawUboBuffer = VK_NULL_HANDLE;
        VkDeviceMemory m_perDrawUboMemory = VK_NULL_HANDLE;
        void*          m_perDrawUboMapped = nullptr;
        uint32_t       m_perDrawUboStride = 0;       // padded slot stride in bytes (≥ 64)
        uint32_t       m_perDrawUboCursor = 0;       // next free slot index, reset in beginScene
        VkDescriptorSetLayout m_perDrawSetLayout    = VK_NULL_HANDLE;  // set 2
        VkDescriptorPool      m_perDrawDescriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet       m_perDrawDescriptorSet  = VK_NULL_HANDLE;

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
