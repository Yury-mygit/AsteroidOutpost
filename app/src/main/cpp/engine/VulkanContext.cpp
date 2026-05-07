#include "VulkanContext.h"

#include <android/log.h>
#include <android/native_window.h>
#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <random>
#include <string>

#include "RenderResources.h"
#include "ShipMesh.h"
#include "math/Mat4.h"

#define LOG_TAG "stationcore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace station {
    namespace {
        // UBO now holds view + proj separately so the shader can do world-space lighting
        struct UniformBufferObject {
            float view[16];
            float proj[16];
        };

        // Push constant: model matrix + tint flags + plasma color + time
        // (100 bytes — well under the 128-byte Vulkan minimum guarantee).
        // `tint` carries shader-mode flags (E2.1 plasma soft-fade in .x,
        // E3.1 nebula material in .y, E3.1 hex material in .z) — NOT colour.
        // `plasmaColor` (E5.1) is the per-billboard tint applied inside the
        // plasma fragment branch; default (1,1,1,1) preserves the E4 look.
        // `time` (E6) is elapsed seconds since first frame; the fragment
        // shader uses it to animate FBM turbulence (plasma fire) and to
        // drift nebulae across the field.
        // E8.3 — `textureMode` flips the lit branch in the fragment shader
        // between vColor.rgb (untextured, the original behaviour) and
        // texture(uTex, vUV) sampled from set 1. Set to 1.0 only by the
        // textured-mesh render-loop; everywhere else it stays 0.
        struct PushConstantData {
            float model[16];      // offset   0, size 64
            float tint[4];        // offset  64, size 16
            float plasmaColor[4]; // offset  80, size 16
            float time;           // offset  96, size 4
            float textureMode;    // offset 100, size 4
        };

        struct ProjectedPoint {
            bool visible = false;
            float x = 0.0f;
            float y = 0.0f;
            float depth = 0.0f;
        };

        std::string vkRes(VkResult r) {
            switch (r) {
                case VK_SUCCESS:                     return "VK_SUCCESS";
                case VK_ERROR_OUT_OF_HOST_MEMORY:    return "VK_ERROR_OUT_OF_HOST_MEMORY";
                case VK_ERROR_OUT_OF_DEVICE_MEMORY:  return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
                case VK_SUBOPTIMAL_KHR:              return "VK_SUBOPTIMAL_KHR";
                case VK_ERROR_OUT_OF_DATE_KHR:       return "VK_ERROR_OUT_OF_DATE_KHR";
                default: return "VK_RESULT(" + std::to_string((int)r) + ")";
            }
        }

        // Generate a simple star-field mesh: N points scattered on a large sphere
        MeshData generateStars(uint32_t count, float radius) {
            MeshData stars;
            std::mt19937 rng(42);
            std::uniform_real_distribution<float> theta(0.0f, 2.0f * 3.14159265f);
            std::uniform_real_distribution<float> cosPhiDist(-1.0f, 1.0f);
            std::uniform_real_distribution<float> brightness(0.5f, 1.0f);

            stars.vertices.reserve(count);
            stars.indices.reserve(count);

            for (uint32_t i = 0; i < count; ++i) {
                float t    = theta(rng);
                float cosp = cosPhiDist(rng);
                float sinp = std::sqrt(1.0f - cosp * cosp);
                float b    = brightness(rng);

                Vertex v{};
                v.position[0] = radius * sinp * std::cos(t);
                v.position[1] = radius * sinp * std::sin(t);
                v.position[2] = radius * cosp;
                // Stars: white-ish with slight blue tint, fully opaque.
                v.color[0] = b * 0.9f;
                v.color[1] = b * 0.95f;
                v.color[2] = b * 1.0f;
                v.color[3] = 1.0f;
                // Normal points inward (toward camera) — not used for stars
                v.normal[0] = -v.position[0] / radius;
                v.normal[1] = -v.position[1] / radius;
                v.normal[2] = -v.position[2] / radius;

                stars.vertices.push_back(v);
                stars.indices.push_back((uint16_t)i);
            }
            return stars;
        }

        ProjectedPoint projectPoint(const math::Mat4& vp, const math::Vec3& p,
                                    float width, float height) {
            const float clipX = vp.m[0] * p.x + vp.m[4] * p.y + vp.m[8]  * p.z + vp.m[12];
            const float clipY = vp.m[1] * p.x + vp.m[5] * p.y + vp.m[9]  * p.z + vp.m[13];
            const float clipZ = vp.m[2] * p.x + vp.m[6] * p.y + vp.m[10] * p.z + vp.m[14];
            const float clipW = vp.m[3] * p.x + vp.m[7] * p.y + vp.m[11] * p.z + vp.m[15];
            if (clipW <= 0.0001f) return {};

            const float ndcX = clipX / clipW;
            const float ndcY = clipY / clipW;
            const float ndcZ = clipZ / clipW;
            if (ndcX < -1.25f || ndcX > 1.25f || ndcY < -1.25f || ndcY > 1.25f ||
                ndcZ < -0.25f || ndcZ > 1.25f) {
                return {};
            }

            ProjectedPoint out{};
            out.visible = true;
            out.x = (ndcX * 0.5f + 0.5f) * width;
            out.y = (ndcY * 0.5f + 0.5f) * height;
            out.depth = ndcZ;
            return out;
        }

        math::Vec3 transformPoint(const float modelMatrix[16], const float point[3]) {
            return {
                    modelMatrix[0] * point[0] + modelMatrix[4] * point[1] +
                    modelMatrix[8] * point[2] + modelMatrix[12],
                    modelMatrix[1] * point[0] + modelMatrix[5] * point[1] +
                    modelMatrix[9] * point[2] + modelMatrix[13],
                    modelMatrix[2] * point[0] + modelMatrix[6] * point[1] +
                    modelMatrix[10] * point[2] + modelMatrix[14]
            };
        }

        float maxColumnScale(const float modelMatrix[16]) {
            const float sx = std::sqrt(modelMatrix[0] * modelMatrix[0] +
                                       modelMatrix[1] * modelMatrix[1] +
                                       modelMatrix[2] * modelMatrix[2]);
            const float sy = std::sqrt(modelMatrix[4] * modelMatrix[4] +
                                       modelMatrix[5] * modelMatrix[5] +
                                       modelMatrix[6] * modelMatrix[6]);
            const float sz = std::sqrt(modelMatrix[8] * modelMatrix[8] +
                                       modelMatrix[9] * modelMatrix[9] +
                                       modelMatrix[10] * modelMatrix[10]);
            return std::max({sx, sy, sz});
        }

        // Projects local Vec3 points through modelMatrix + VP, returns padded screen bounds.
        // outBounds: [left, top, right, bottom, avgClipW]
        bool projectLocalPointsToBounds(
                const math::Mat4& vp,
                const std::vector<math::Vec3>& localPts,
                const float modelMatrix[16],
                float padding, float viewW, float viewH,
                float outBounds[5]) {
            float minX =  std::numeric_limits<float>::max();
            float maxX = -std::numeric_limits<float>::max();
            float minY =  std::numeric_limits<float>::max();
            float maxY = -std::numeric_limits<float>::max();
            float wSum = 0.0f;
            int   count = 0;

            for (const math::Vec3& local : localPts) {
                const float p[3] = {local.x, local.y, local.z};
                const math::Vec3 world = transformPoint(modelMatrix, p);
                const float clipX = vp.m[0]*world.x + vp.m[4]*world.y + vp.m[8]*world.z  + vp.m[12];
                const float clipY = vp.m[1]*world.x + vp.m[5]*world.y + vp.m[9]*world.z  + vp.m[13];
                const float clipW = vp.m[3]*world.x + vp.m[7]*world.y + vp.m[11]*world.z + vp.m[15];
                if (clipW <= 0.0001f) continue;
                const float ndcX = clipX / clipW;
                const float ndcY = clipY / clipW;
                if (ndcX < -1.25f || ndcX > 1.25f || ndcY < -1.25f || ndcY > 1.25f) continue;
                const float sx = (ndcX * 0.5f + 0.5f) * viewW;
                const float sy = (ndcY * 0.5f + 0.5f) * viewH;
                minX = std::min(minX, sx); maxX = std::max(maxX, sx);
                minY = std::min(minY, sy); maxY = std::max(maxY, sy);
                wSum += clipW;
                ++count;
            }
            if (count == 0) return false;

            const float padX = (maxX - minX) * padding * 0.5f;
            const float padY = (maxY - minY) * padding * 0.5f;
            outBounds[0] = minX - padX;
            outBounds[1] = minY - padY;
            outBounds[2] = maxX + padX;
            outBounds[3] = maxY + padY;
            outBounds[4] = wSum / static_cast<float>(count); // avg clipW ≈ linear depth
            return true;
        }
    } // namespace

    // -----------------------------------------------------------------------
    // Static helpers
    // -----------------------------------------------------------------------
    uint32_t VulkanContext::findMemoryType(VkPhysicalDevice pd, uint32_t filter,
                                           VkMemoryPropertyFlags props) {
        VkPhysicalDeviceMemoryProperties mp{};
        vkGetPhysicalDeviceMemoryProperties(pd, &mp);
        for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
            if ((filter & (1u << i)) && (mp.memoryTypes[i].propertyFlags & props) == props)
                return i;
        return UINT32_MAX;
    }

    bool VulkanContext::createBuffer(VkPhysicalDevice pd, VkDevice dev,
                                     VkDeviceSize size, VkBufferUsageFlags usage,
                                     VkMemoryPropertyFlags props,
                                     VkBuffer& outBuf, VkDeviceMemory& outMem) {
        VkBufferCreateInfo bi{};
        bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bi.size = size; bi.usage = usage; bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

        VkResult r = vkCreateBuffer(dev, &bi, nullptr, &outBuf);
        if (r != VK_SUCCESS) { LOGE("vkCreateBuffer: %s", vkRes(r).c_str()); return false; }

        VkMemoryRequirements mr{};
        vkGetBufferMemoryRequirements(dev, outBuf, &mr);

        VkMemoryAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize  = mr.size;
        ai.memoryTypeIndex = findMemoryType(pd, mr.memoryTypeBits, props);
        if (ai.memoryTypeIndex == UINT32_MAX) { LOGE("findMemoryType failed"); return false; }

        r = vkAllocateMemory(dev, &ai, nullptr, &outMem);
        if (r != VK_SUCCESS) { LOGE("vkAllocateMemory: %s", vkRes(r).c_str()); return false; }

        r = vkBindBufferMemory(dev, outBuf, outMem, 0);
        if (r != VK_SUCCESS) { LOGE("vkBindBufferMemory: %s", vkRes(r).c_str()); return false; }
        return true;
    }

    // -----------------------------------------------------------------------
    // initDevice
    // -----------------------------------------------------------------------
    bool VulkanContext::initDevice() {
        if (m_deviceReady) return true;

        VkApplicationInfo appInfo{};
        appInfo.sType            = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        appInfo.pApplicationName = "g3";
        appInfo.pEngineName      = "stationcore";
        appInfo.apiVersion       = VK_API_VERSION_1_1;

        std::vector<const char*> exts = {
                VK_KHR_SURFACE_EXTENSION_NAME,
                VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
        };

        VkInstanceCreateInfo ici{};
        ici.sType                   = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        ici.pApplicationInfo        = &appInfo;
        ici.enabledExtensionCount   = (uint32_t)exts.size();
        ici.ppEnabledExtensionNames = exts.data();

        VkResult r = vkCreateInstance(&ici, nullptr, &m_instance);
        if (r != VK_SUCCESS) { LOGE("vkCreateInstance: %s", vkRes(r).c_str()); return false; }

        uint32_t n = 0;
        vkEnumeratePhysicalDevices(m_instance, &n, nullptr);
        if (!n) { LOGE("No Vulkan devices"); return false; }
        std::vector<VkPhysicalDevice> devs(n);
        vkEnumeratePhysicalDevices(m_instance, &n, devs.data());
        m_physicalDevice = devs[0];

        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(m_physicalDevice, &props);
        LOGI("GPU: %s", props.deviceName);

        m_deviceReady = true;
        return true;
    }

    // -----------------------------------------------------------------------
    // createSurface
    // -----------------------------------------------------------------------
    bool VulkanContext::createSurface(void* nativeHandle, int width, int height) {
        if (nativeHandle != nullptr) {
            if (m_device != VK_NULL_HANDLE) vkDeviceWaitIdle(m_device);
            destroySwapchain();
            if (m_surface != VK_NULL_HANDLE) {
                vkDestroySurfaceKHR(m_instance, m_surface, nullptr);
                m_surface = VK_NULL_HANDLE;
            }

            VkAndroidSurfaceCreateInfoKHR sci{};
            sci.sType  = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
            sci.window = static_cast<ANativeWindow*>(nativeHandle);
            VkResult sr = vkCreateAndroidSurfaceKHR(m_instance, &sci, nullptr, &m_surface);
            if (sr != VK_SUCCESS) { LOGE("vkCreateAndroidSurfaceKHR: %d", sr); return false; }
        }

        if (m_device == VK_NULL_HANDLE) {
            if (!pickQueueFamily()) return false;
            if (!createDevice())    return false;
            if (!createPipelineInfra()) return false;

            // Create star-field mesh once
            MeshData stars = generateStars(600, 45.0f);
            m_starMesh.create(m_physicalDevice, m_device, stars);

            // Corner-bracket frame mesh (LINE_LIST): 4 L-shaped corners, no full sides.
            {
                MeshData lineMesh;
                Vertex lv{};
                lv.color[0] = 0.0f; lv.color[1] = 1.0f; lv.color[2] = 0.0f; lv.color[3] = 1.0f; // pure green, opaque
                lv.normal[0] = 0.0f; lv.normal[1] = 0.0f; lv.normal[2] = 1.0f;
                // 12 vertices: 4 corners + 2 leg-ends each. leg = 30% of half-extent.
                constexpr float H = 0.5f;   // half-extent
                constexpr float L = 0.15f;  // leg length
                const float pts[12][2] = {
                    {-H,-H}, {-H+L,-H}, {-H,-H+L},  // top-left corner + 2 ends
                    { H,-H}, { H-L,-H}, { H,-H+L},  // top-right
                    { H, H}, { H-L, H}, { H, H-L},  // bottom-right
                    {-H, H}, {-H+L, H}, {-H, H-L},  // bottom-left
                };
                for (auto& p : pts) {
                    lv.position[0] = p[0]; lv.position[1] = p[1]; lv.position[2] = 0.0f;
                    lineMesh.vertices.push_back(lv);
                }
                // 8 lines, each corner contributes 2
                lineMesh.indices = {
                    0,1,  0,2,   // top-left
                    3,4,  3,5,   // top-right
                    6,7,  6,8,   // bottom-right
                    9,10, 9,11   // bottom-left
                };
                m_frameLineMesh.create(m_physicalDevice, m_device, lineMesh);

                // Same geometry, enemy-red vertices.
                for (auto& v : lineMesh.vertices) {
                    v.color[0] = 1.0f; v.color[1] = 0.38f; v.color[2] = 0.34f; v.color[3] = 1.0f;
                }
                m_frameLineMeshEnemy.create(m_physicalDevice, m_device, lineMesh);
            }
        }

        if (!selectSurfaceProps(width, height)) return false;
        if (!createSwapchain())                  return false;
        if (!createSwapViews())                  return false;
        if (!createDepthAndFramebuffers())        return false;
        if (!createCommandInfra())               return false;
        if (!createSyncObjects())                return false;

        m_surfaceReady = true;
        LOGI("Surface ready %dx%d", width, height);
        return true;
    }

    // -----------------------------------------------------------------------
    // destroySurface / destroy
    // -----------------------------------------------------------------------
    void VulkanContext::destroySurface() {
        if (m_device != VK_NULL_HANDLE) vkDeviceWaitIdle(m_device);
        destroySwapchain();
        if (m_surface != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(m_instance, m_surface, nullptr);
            m_surface = VK_NULL_HANDLE;
        }
        m_surfaceReady = false;
    }

    void VulkanContext::destroy() {
        if (m_device != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(m_device);
            destroySwapchain();
            m_starMesh.destroy(m_device);
            m_frameLineMesh.destroy(m_device);
            m_frameLineMeshEnemy.destroy(m_device);
            for (uint32_t i = 0; i < kMaxMeshes; ++i)
                if (m_meshUsed[i]) {
                    m_meshPool[i].destroy(m_device);
                    m_meshFramePoints[i].clear();
                }
            destroyPipelineInfra();
            vkDestroyDevice(m_device, nullptr);
            m_device = VK_NULL_HANDLE;
            m_graphicsQueue = VK_NULL_HANDLE;
        }
        if (m_surface != VK_NULL_HANDLE) { vkDestroySurfaceKHR(m_instance, m_surface, nullptr); m_surface = VK_NULL_HANDLE; }
        if (m_instance != VK_NULL_HANDLE) { vkDestroyInstance(m_instance, nullptr); m_instance = VK_NULL_HANDLE; }
        m_physicalDevice = VK_NULL_HANDLE;
        m_queueFamily    = UINT32_MAX;
        m_deviceReady    = false;
        m_surfaceReady   = false;
        LOGI("VulkanContext destroyed");
    }

    // -----------------------------------------------------------------------
    // Device / queue helpers
    // -----------------------------------------------------------------------
    bool VulkanContext::pickQueueFamily() {
        uint32_t qn = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(m_physicalDevice, &qn, nullptr);
        std::vector<VkQueueFamilyProperties> qfp(qn);
        vkGetPhysicalDeviceQueueFamilyProperties(m_physicalDevice, &qn, qfp.data());
        for (uint32_t qi = 0; qi < qn; ++qi) {
            VkBool32 present = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(m_physicalDevice, qi, m_surface, &present);
            if ((qfp[qi].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                m_queueFamily = qi;
                LOGI("Queue family: %u", qi);
                return true;
            }
        }
        LOGE("No suitable queue family"); return false;
    }

    bool VulkanContext::createDevice() {
        float prio = 1.0f;
        VkDeviceQueueCreateInfo qci{};
        qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        qci.queueFamilyIndex = m_queueFamily; qci.queueCount = 1; qci.pQueuePriorities = &prio;
        const char* ext[] = { VK_KHR_SWAPCHAIN_EXTENSION_NAME };
        VkPhysicalDeviceFeatures supportedFeats{};
        vkGetPhysicalDeviceFeatures(m_physicalDevice, &supportedFeats);
        VkPhysicalDeviceFeatures feat{};
        if (supportedFeats.wideLines) {
            feat.wideLines = VK_TRUE;
            m_wideLines = true;
            LOGI("wideLines enabled");
        }
        VkDeviceCreateInfo dci{};
        dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci;
        dci.enabledExtensionCount = 1; dci.ppEnabledExtensionNames = ext;
        dci.pEnabledFeatures = &feat;
        VkResult r = vkCreateDevice(m_physicalDevice, &dci, nullptr, &m_device);
        if (r != VK_SUCCESS) { LOGE("vkCreateDevice: %s", vkRes(r).c_str()); return false; }
        vkGetDeviceQueue(m_device, m_queueFamily, 0, &m_graphicsQueue);
        LOGI("Logical device created");
        return true;
    }

    // -----------------------------------------------------------------------
    // Surface / swapchain helpers
    // -----------------------------------------------------------------------
    bool VulkanContext::selectSurfaceProps(int width, int height) {
        VkSurfaceCapabilitiesKHR caps{};
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(m_physicalDevice, m_surface, &caps);

        uint32_t fn = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(m_physicalDevice, m_surface, &fn, nullptr);
        std::vector<VkSurfaceFormatKHR> fmts(fn);
        vkGetPhysicalDeviceSurfaceFormatsKHR(m_physicalDevice, m_surface, &fn, fmts.data());
        m_sel.format = fmts[0];
        for (auto& f : fmts)
            if ((f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM)
                && f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) { m_sel.format = f; break; }

        uint32_t pn = 0;
        vkGetPhysicalDeviceSurfacePresentModesKHR(m_physicalDevice, m_surface, &pn, nullptr);
        std::vector<VkPresentModeKHR> modes(pn);
        vkGetPhysicalDeviceSurfacePresentModesKHR(m_physicalDevice, m_surface, &pn, modes.data());
        m_sel.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        for (auto m : modes) if (m == VK_PRESENT_MODE_MAILBOX_KHR) { m_sel.presentMode = m; break; }

        m_sel.extent = caps.currentExtent;
        if (m_sel.extent.width == UINT32_MAX) {
            m_sel.extent.width  = std::clamp((uint32_t)width,  caps.minImageExtent.width,  caps.maxImageExtent.width);
            m_sel.extent.height = std::clamp((uint32_t)height, caps.minImageExtent.height, caps.maxImageExtent.height);
        }
        m_sel.imageCount = caps.minImageCount + 1;
        if (caps.maxImageCount > 0 && m_sel.imageCount > caps.maxImageCount)
            m_sel.imageCount = caps.maxImageCount;

        m_depthFormat = RenderResourcesBuilder::pickDepthFormat(m_physicalDevice);
        m_camera.setAspect((float)m_sel.extent.width / (float)m_sel.extent.height);
        return true;
    }

    bool VulkanContext::createSwapchain() {
        VkSwapchainCreateInfoKHR ci{};
        ci.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        ci.surface = m_surface; ci.minImageCount = m_sel.imageCount;
        ci.imageFormat = m_sel.format.format; ci.imageColorSpace = m_sel.format.colorSpace;
        ci.imageExtent = m_sel.extent; ci.imageArrayLayers = 1;
        ci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        ci.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
        ci.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        ci.presentMode = m_sel.presentMode; ci.clipped = VK_TRUE;
        VkResult r = vkCreateSwapchainKHR(m_device, &ci, nullptr, &m_swapchain);
        if (r != VK_SUCCESS) { LOGE("vkCreateSwapchainKHR: %s", vkRes(r).c_str()); return false; }
        uint32_t n = 0;
        vkGetSwapchainImagesKHR(m_device, m_swapchain, &n, nullptr);
        m_swapImages.resize(n);
        vkGetSwapchainImagesKHR(m_device, m_swapchain, &n, m_swapImages.data());
        return true;
    }

    bool VulkanContext::createSwapViews() {
        m_swapViews.resize(m_swapImages.size());
        for (size_t i = 0; i < m_swapImages.size(); ++i) {
            VkImageViewCreateInfo ci{};
            ci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            ci.image = m_swapImages[i]; ci.viewType = VK_IMAGE_VIEW_TYPE_2D;
            ci.format = m_sel.format.format;
            ci.components = {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
                             VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY};
            ci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            VkResult r = vkCreateImageView(m_device, &ci, nullptr, &m_swapViews[i]);
            if (r != VK_SUCCESS) { LOGE("vkCreateImageView[%zu]: %s", i, vkRes(r).c_str()); return false; }
        }
        return true;
    }

    bool VulkanContext::createDepthAndFramebuffers() {
        // E10.1 — scene pass renders into the offscreen colour image with
        // finalLayout=SHADER_READ_ONLY_OPTIMAL so the post pass can sample
        // the result. Format = swapchain format for trivial passthrough
        // (the post pipeline currently just samples + writes; in E10.4
        // it'll do motion blur on top).
        // E10.2 — second colour attachment for screen-space velocity.
        // R16G16_SFLOAT is enough range for [-1,+1] NDC deltas with sign,
        // and only 4 bytes/pixel so the bandwidth hit is modest. Same usage
        // (COLOR_ATTACHMENT + SAMPLED) as the colour image so the post pass
        // can sample velocity for motion blur (E10.4).
        const VkFormat offscreenFormat = m_sel.format.format;
        const VkFormat velocityFormat  = VK_FORMAT_R16G16_SFLOAT;
        if (!RenderResourcesBuilder::createRenderPass(
                m_device, offscreenFormat, velocityFormat, m_depthFormat,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                m_renderResources.renderPass)) return false;
        if (!RenderResourcesBuilder::createDepthResources(
                m_physicalDevice, m_device, m_sel.extent, m_depthFormat,
                m_renderResources.depthImage, m_renderResources.depthMemory,
                m_renderResources.depthImageView)) return false;
        if (!RenderResourcesBuilder::createOffscreenColorResources(
                m_physicalDevice, m_device, m_sel.extent, offscreenFormat,
                m_renderResources.offscreenColorImage,
                m_renderResources.offscreenColorMemory,
                m_renderResources.offscreenColorView,
                m_renderResources.offscreenColorSampler)) return false;
        // Reuse the offscreen-colour factory for the velocity attachment —
        // identical resource shape (image + memory + view + sampler), only
        // the format differs.
        if (!RenderResourcesBuilder::createOffscreenColorResources(
                m_physicalDevice, m_device, m_sel.extent, velocityFormat,
                m_renderResources.offscreenVelocityImage,
                m_renderResources.offscreenVelocityMemory,
                m_renderResources.offscreenVelocityView,
                m_renderResources.offscreenVelocitySampler)) return false;
        // Single scene framebuffer wrapped in the existing vector for
        // indexing parity with the rest of the codebase.
        VkFramebuffer sceneFb = VK_NULL_HANDLE;
        if (!RenderResourcesBuilder::createSceneFramebuffer(
                m_device, m_renderResources.renderPass, m_sel.extent,
                m_renderResources.offscreenColorView,
                m_renderResources.offscreenVelocityView,
                m_renderResources.depthImageView,
                sceneFb)) return false;
        m_renderResources.framebuffers = { sceneFb };

        // Post-process pass: one fb per swapchain image, single colour
        // attachment, layout transitions UNDEFINED → PRESENT_SRC_KHR.
        if (!RenderResourcesBuilder::createPostRenderPass(
                m_device, m_sel.format.format, m_renderResources.postRenderPass)) return false;
        if (!RenderResourcesBuilder::createPostFramebuffers(
                m_device, m_renderResources.postRenderPass, m_sel.extent,
                m_swapViews, m_renderResources.postFramebuffers)) return false;
        return true;
    }

    bool VulkanContext::createCommandInfra() {
        if (!RenderResourcesBuilder::createCommandPool(
                m_device, m_queueFamily, m_renderResources.commandPool)) return false;
        // E10.1 — command buffers are per-swapchain-image (matches the
        // post framebuffers, since each renders to a different swapchain
        // colour view).
        if (!RenderResourcesBuilder::createCommandBuffers(
                m_device, m_renderResources.commandPool,
                (uint32_t)m_renderResources.postFramebuffers.size(),
                m_renderResources.commandBuffers)) return false;
        return true;
    }

    bool VulkanContext::createSyncObjects() {
        return RenderResourcesBuilder::createSyncObjects(
                m_device,
                m_renderResources.imageAvailableSemaphore,
                m_renderResources.renderFinishedSemaphore,
                m_renderResources.inFlightFence);
    }

    // -----------------------------------------------------------------------
    // createPipelineInfra — UBO + descriptor + pipeline layout with push constant
    // -----------------------------------------------------------------------
    bool VulkanContext::createPipelineInfra() {
        // UBO: view + proj (2 * 16 floats)
        if (!createBuffer(m_physicalDevice, m_device,
                          sizeof(UniformBufferObject),
                          VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                          VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                          m_uniformBuffer, m_uniformMemory)) return false;

        // Zero-init
        void* mapped = nullptr;
        vkMapMemory(m_device, m_uniformMemory, 0, sizeof(UniformBufferObject), 0, &mapped);
        memset(mapped, 0, sizeof(UniformBufferObject));
        vkUnmapMemory(m_device, m_uniformMemory);

        // Descriptor set layout (binding 0 = UBO)
        VkDescriptorSetLayoutBinding binding{};
        binding.binding = 0; binding.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        binding.descriptorCount = 1; binding.stageFlags = VK_SHADER_STAGE_VERTEX_BIT;

        VkDescriptorSetLayoutCreateInfo dslCI{};
        dslCI.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        dslCI.bindingCount = 1; dslCI.pBindings = &binding;
        VkResult r = vkCreateDescriptorSetLayout(m_device, &dslCI, nullptr, &m_descriptorSetLayout);
        if (r != VK_SUCCESS) { LOGE("vkCreateDescriptorSetLayout: %s", vkRes(r).c_str()); return false; }

        // E8.2 — set 1 layout: single combined image sampler at binding 0,
        // visible to fragment stage only. All pipelines share this layout
        // via the unified pipeline layout below; the descriptor *bound* per
        // draw is what changes (default white at frame start, per-asset
        // texture for textured draws once E8.3 lands).
        VkDescriptorSetLayoutBinding texBinding{};
        texBinding.binding = 0;
        texBinding.descriptorType  = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        texBinding.descriptorCount = 1;
        texBinding.stageFlags      = VK_SHADER_STAGE_FRAGMENT_BIT;
        VkDescriptorSetLayoutCreateInfo texDslCI{};
        texDslCI.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        texDslCI.bindingCount = 1; texDslCI.pBindings = &texBinding;
        r = vkCreateDescriptorSetLayout(m_device, &texDslCI, nullptr, &m_textureSetLayout);
        if (r != VK_SUCCESS) { LOGE("vkCreateDescriptorSetLayout(texture): %s", vkRes(r).c_str()); return false; }

        // Descriptor pool + set
        VkDescriptorPoolSize poolSize{ VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1 };
        VkDescriptorPoolCreateInfo poolCI{};
        poolCI.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        poolCI.poolSizeCount = 1; poolCI.pPoolSizes = &poolSize; poolCI.maxSets = 1;
        r = vkCreateDescriptorPool(m_device, &poolCI, nullptr, &m_descriptorPool);
        if (r != VK_SUCCESS) { LOGE("vkCreateDescriptorPool: %s", vkRes(r).c_str()); return false; }

        // E8.2 — texture pool. Sized for `kMaxTextures` combined image
        // samplers. FREE_DESCRIPTOR_SET_BIT lets `Texture::destroy` return
        // a slot back; without it `vkFreeDescriptorSets` would be illegal.
        constexpr uint32_t kMaxTextures = 64;
        VkDescriptorPoolSize texPoolSize{ VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, kMaxTextures };
        VkDescriptorPoolCreateInfo texPoolCI{};
        texPoolCI.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        texPoolCI.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
        texPoolCI.poolSizeCount = 1; texPoolCI.pPoolSizes = &texPoolSize;
        texPoolCI.maxSets = kMaxTextures;
        r = vkCreateDescriptorPool(m_device, &texPoolCI, nullptr, &m_texturePool);
        if (r != VK_SUCCESS) { LOGE("vkCreateDescriptorPool(texture): %s", vkRes(r).c_str()); return false; }

        VkDescriptorSetAllocateInfo dsAI{};
        dsAI.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        dsAI.descriptorPool = m_descriptorPool; dsAI.descriptorSetCount = 1;
        dsAI.pSetLayouts = &m_descriptorSetLayout;
        r = vkAllocateDescriptorSets(m_device, &dsAI, &m_descriptorSet);
        if (r != VK_SUCCESS) { LOGE("vkAllocateDescriptorSets: %s", vkRes(r).c_str()); return false; }

        VkDescriptorBufferInfo bufInfo{ m_uniformBuffer, 0, sizeof(UniformBufferObject) };
        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = m_descriptorSet; write.dstBinding = 0;
        write.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        write.descriptorCount = 1; write.pBufferInfo = &bufInfo;
        vkUpdateDescriptorSets(m_device, 1, &write, 0, nullptr);

        // E8.2 — default 1×1 white texture. Bound to set 1 at the start of
        // every renderFrame so untextured draws (i.e. everything for now)
        // satisfy the layout requirement without sampling anything visible.
        // Once textured draws land (E8.3), they rebind set 1 to their own
        // texture; sets persist within a command buffer until rebound.
        const uint8_t whitePixel[4] = { 255, 255, 255, 255 };
        if (!m_defaultWhiteTexture.createFromPixels(
                m_physicalDevice, m_device, m_graphicsQueue, m_queueFamily,
                m_texturePool, m_textureSetLayout,
                whitePixel, 1, 1)) {
            LOGE("Default white texture creation failed");
            return false;
        }

        // E9 — particle instance buffers. Persistent-mapped HOST_VISIBLE
        // so renderFrame can memcpy staging arrays straight in. Sized for
        // kMaxParticles (4096) per pipeline; per-instance stride is 8
        // floats (pos3 + size1 + rgba4). Two buffers (additive vs alpha)
        // because the two pipelines may render different particle counts
        // and the simplest layout is one batch buffer per pipeline.
        const VkDeviceSize particleBufBytes =
                (VkDeviceSize)kMaxParticles * kParticleFloatStride * sizeof(float);
        auto allocInstance = [&](VkBuffer& buf, VkDeviceMemory& mem, void*& mapped) -> bool {
            if (!createBuffer(m_physicalDevice, m_device, particleBufBytes,
                              VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                              VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                              VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                              buf, mem)) return false;
            return vkMapMemory(m_device, mem, 0, particleBufBytes, 0, &mapped) == VK_SUCCESS;
        };
        if (!allocInstance(m_particleAdditiveInstanceBuffer,
                           m_particleAdditiveInstanceMemory,
                           m_particleAdditiveInstanceMapped)) {
            LOGE("Particle additive instance buffer alloc failed"); return false;
        }
        if (!allocInstance(m_particleAlphaInstanceBuffer,
                           m_particleAlphaInstanceMemory,
                           m_particleAlphaInstanceMapped)) {
            LOGE("Particle alpha instance buffer alloc failed"); return false;
        }

        LOGI("Pipeline infra created");
        return true;
    }

    void VulkanContext::destroyPipelineInfra() {
        if (m_pipeline            != VK_NULL_HANDLE) { vkDestroyPipeline(m_device, m_pipeline, nullptr);                       m_pipeline = VK_NULL_HANDLE; }
        if (m_starPipeline        != VK_NULL_HANDLE) { vkDestroyPipeline(m_device, m_starPipeline, nullptr);                   m_starPipeline = VK_NULL_HANDLE; }
        if (m_systemPipeline      != VK_NULL_HANDLE) { vkDestroyPipeline(m_device, m_systemPipeline, nullptr);                 m_systemPipeline = VK_NULL_HANDLE; }
        if (m_plasmaPipeline      != VK_NULL_HANDLE) { vkDestroyPipeline(m_device, m_plasmaPipeline, nullptr);                 m_plasmaPipeline = VK_NULL_HANDLE; }
        if (m_translucentPipeline != VK_NULL_HANDLE) { vkDestroyPipeline(m_device, m_translucentPipeline, nullptr);            m_translucentPipeline = VK_NULL_HANDLE; }
        if (m_additivePipeline    != VK_NULL_HANDLE) { vkDestroyPipeline(m_device, m_additivePipeline, nullptr);               m_additivePipeline = VK_NULL_HANDLE; }
        if (m_framePipeline       != VK_NULL_HANDLE) { vkDestroyPipeline(m_device, m_framePipeline, nullptr);                  m_framePipeline = VK_NULL_HANDLE; }
        if (m_particleAdditivePipeline != VK_NULL_HANDLE) { vkDestroyPipeline(m_device, m_particleAdditivePipeline, nullptr); m_particleAdditivePipeline = VK_NULL_HANDLE; }
        if (m_particleAlphaPipeline    != VK_NULL_HANDLE) { vkDestroyPipeline(m_device, m_particleAlphaPipeline, nullptr);    m_particleAlphaPipeline = VK_NULL_HANDLE; }
        if (m_pipelineLayout      != VK_NULL_HANDLE) { vkDestroyPipelineLayout(m_device, m_pipelineLayout, nullptr);           m_pipelineLayout = VK_NULL_HANDLE; }
        if (m_vertModule          != VK_NULL_HANDLE) { vkDestroyShaderModule(m_device, m_vertModule, nullptr);                  m_vertModule = VK_NULL_HANDLE; }
        if (m_fragModule          != VK_NULL_HANDLE) { vkDestroyShaderModule(m_device, m_fragModule, nullptr);                  m_fragModule = VK_NULL_HANDLE; }
        if (m_particleVertModule  != VK_NULL_HANDLE) { vkDestroyShaderModule(m_device, m_particleVertModule, nullptr);          m_particleVertModule = VK_NULL_HANDLE; }
        if (m_particleFragModule  != VK_NULL_HANDLE) { vkDestroyShaderModule(m_device, m_particleFragModule, nullptr);          m_particleFragModule = VK_NULL_HANDLE; }
        // E10.1 — post pipeline / layout / descriptor / shader cleanup.
        if (m_postPipeline        != VK_NULL_HANDLE) { vkDestroyPipeline(m_device, m_postPipeline, nullptr);                    m_postPipeline = VK_NULL_HANDLE; }
        if (m_postPipelineLayout  != VK_NULL_HANDLE) { vkDestroyPipelineLayout(m_device, m_postPipelineLayout, nullptr);        m_postPipelineLayout = VK_NULL_HANDLE; }
        if (m_postDescriptorPool  != VK_NULL_HANDLE) { vkDestroyDescriptorPool(m_device, m_postDescriptorPool, nullptr);        m_postDescriptorPool = VK_NULL_HANDLE; }
        if (m_postSetLayout       != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(m_device, m_postSetLayout, nullptr);        m_postSetLayout = VK_NULL_HANDLE; }
        if (m_postVertModule      != VK_NULL_HANDLE) { vkDestroyShaderModule(m_device, m_postVertModule, nullptr);              m_postVertModule = VK_NULL_HANDLE; }
        if (m_postFragModule      != VK_NULL_HANDLE) { vkDestroyShaderModule(m_device, m_postFragModule, nullptr);              m_postFragModule = VK_NULL_HANDLE; }
        m_postDescriptorSet = VK_NULL_HANDLE;
        // E8.2/E8.3 — destroy textures before their descriptor pool. Free
        // every used pool slot first, then the engine-owned default white,
        // then drop the descriptor pool itself.
        for (uint32_t i = 0; i < kMaxTextures; ++i) {
            if (m_textureUsed[i]) {
                m_textureSlots[i].destroy(m_device, m_texturePool);
                m_textureUsed[i] = false;
            }
        }
        m_defaultWhiteTexture.destroy(m_device, m_texturePool);
        if (m_texturePool         != VK_NULL_HANDLE) { vkDestroyDescriptorPool(m_device, m_texturePool, nullptr);              m_texturePool = VK_NULL_HANDLE; }
        if (m_textureSetLayout    != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(m_device, m_textureSetLayout, nullptr);    m_textureSetLayout = VK_NULL_HANDLE; }
        if (m_descriptorPool      != VK_NULL_HANDLE) { vkDestroyDescriptorPool(m_device, m_descriptorPool, nullptr);           m_descriptorPool = VK_NULL_HANDLE; }
        if (m_descriptorSetLayout != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(m_device, m_descriptorSetLayout, nullptr); m_descriptorSetLayout = VK_NULL_HANDLE; }
        if (m_uniformBuffer       != VK_NULL_HANDLE) { vkDestroyBuffer(m_device, m_uniformBuffer, nullptr);                    m_uniformBuffer = VK_NULL_HANDLE; }
        if (m_uniformMemory       != VK_NULL_HANDLE) { vkFreeMemory(m_device, m_uniformMemory, nullptr);                       m_uniformMemory = VK_NULL_HANDLE; }
        // E9 — unmap + free particle instance buffers.
        if (m_particleAdditiveInstanceMemory != VK_NULL_HANDLE) {
            if (m_particleAdditiveInstanceMapped) {
                vkUnmapMemory(m_device, m_particleAdditiveInstanceMemory);
                m_particleAdditiveInstanceMapped = nullptr;
            }
        }
        if (m_particleAdditiveInstanceBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(m_device, m_particleAdditiveInstanceBuffer, nullptr);
            m_particleAdditiveInstanceBuffer = VK_NULL_HANDLE;
        }
        if (m_particleAdditiveInstanceMemory != VK_NULL_HANDLE) {
            vkFreeMemory(m_device, m_particleAdditiveInstanceMemory, nullptr);
            m_particleAdditiveInstanceMemory = VK_NULL_HANDLE;
        }
        if (m_particleAlphaInstanceMemory != VK_NULL_HANDLE) {
            if (m_particleAlphaInstanceMapped) {
                vkUnmapMemory(m_device, m_particleAlphaInstanceMemory);
                m_particleAlphaInstanceMapped = nullptr;
            }
        }
        if (m_particleAlphaInstanceBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(m_device, m_particleAlphaInstanceBuffer, nullptr);
            m_particleAlphaInstanceBuffer = VK_NULL_HANDLE;
        }
        if (m_particleAlphaInstanceMemory != VK_NULL_HANDLE) {
            vkFreeMemory(m_device, m_particleAlphaInstanceMemory, nullptr);
            m_particleAlphaInstanceMemory = VK_NULL_HANDLE;
        }
        m_descriptorSet = VK_NULL_HANDLE;
    }

    // -----------------------------------------------------------------------
    // createPipeline
    // -----------------------------------------------------------------------
    bool VulkanContext::createPipeline(const std::vector<uint32_t>& vertSpv,
                                       const std::vector<uint32_t>& fragSpv,
                                       const std::vector<uint32_t>& particleVertSpv,
                                       const std::vector<uint32_t>& particleFragSpv,
                                       const std::vector<uint32_t>& postVertSpv,
                                       const std::vector<uint32_t>& postFragSpv) {
        auto makeModule = [&](const std::vector<uint32_t>& code, VkShaderModule& mod) -> bool {
            VkShaderModuleCreateInfo ci{};
            ci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
            ci.codeSize = code.size() * 4; ci.pCode = code.data();
            VkResult r = vkCreateShaderModule(m_device, &ci, nullptr, &mod);
            if (r != VK_SUCCESS) { LOGE("vkCreateShaderModule: %s", vkRes(r).c_str()); return false; }
            return true;
        };

        if (vertSpv.empty() || fragSpv.empty()) { LOGE("createPipeline: shaders not set"); return false; }
        if (!makeModule(vertSpv, m_vertModule)) return false;
        if (!makeModule(fragSpv, m_fragModule)) return false;

        VkPipelineShaderStageCreateInfo stages[2]{};
        stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT; stages[0].module = m_vertModule; stages[0].pName = "main";
        stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module = m_fragModule; stages[1].pName = "main";

        auto bind  = Vertex::getBindingDescription();
        auto attrs = Vertex::getAttributeDescriptions();

        VkPipelineVertexInputStateCreateInfo viCI{};
        viCI.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        viCI.vertexBindingDescriptionCount = 1; viCI.pVertexBindingDescriptions = &bind;
        viCI.vertexAttributeDescriptionCount = (uint32_t)attrs.size(); viCI.pVertexAttributeDescriptions = attrs.data();

        VkPipelineInputAssemblyStateCreateInfo iaCI{};
        iaCI.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        iaCI.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

        VkViewport vp{ 0, 0, (float)m_sel.extent.width, (float)m_sel.extent.height, 0, 1 };
        VkRect2D sc{ {0,0}, m_sel.extent };
        VkPipelineViewportStateCreateInfo vpCI{};
        vpCI.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        vpCI.viewportCount = 1; vpCI.pViewports = &vp; vpCI.scissorCount = 1; vpCI.pScissors = &sc;

        VkPipelineRasterizationStateCreateInfo rastCI{};
        rastCI.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rastCI.polygonMode = VK_POLYGON_MODE_FILL; rastCI.lineWidth = 1.0f;
        rastCI.cullMode = VK_CULL_MODE_NONE; rastCI.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;

        VkPipelineMultisampleStateCreateInfo msCI{};
        msCI.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        msCI.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineDepthStencilStateCreateInfo dsCI{};
        dsCI.sType = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
        dsCI.depthTestEnable = VK_TRUE; dsCI.depthWriteEnable = VK_TRUE;
        dsCI.depthCompareOp = VK_COMPARE_OP_LESS;

        // E10.2 — two colour attachments: [0] = scene colour (per-pipeline
        // blend state, mutated below), [1] = velocity (always no-blend,
        // R+G write mask only). Existing code keeps mutating `cbAtt` which
        // is now an alias for cbAtts[0]; the velocity slot stays untouched.
        // Post pipeline below uses its own postCb/postCbCI and is unaffected.
        VkPipelineColorBlendAttachmentState cbAtts[2]{};
        auto& cbAtt = cbAtts[0];
        cbAtt.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                               VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        cbAtts[1].blendEnable    = VK_FALSE;
        cbAtts[1].colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT;
        VkPipelineColorBlendStateCreateInfo cbCI{};
        cbCI.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        cbCI.attachmentCount = 2; cbCI.pAttachments = cbAtts;

        // Push constant range: mat4 model + vec4 tint, visible to both stages
        VkPushConstantRange pcRange{};
        pcRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
        pcRange.offset     = 0;
        pcRange.size       = sizeof(PushConstantData);

        // E8.2 — pipeline layout takes both descriptor set layouts: set 0
        // (UBO, vertex stage) and set 1 (combined image sampler, fragment
        // stage). All seven pipelines share this layout, so existing draws
        // pick up the texture binding for free and textured draws (E8.3+)
        // can rebind set 1 without changing pipeline.
        VkDescriptorSetLayout setLayouts[2] = { m_descriptorSetLayout, m_textureSetLayout };
        VkPipelineLayoutCreateInfo plCI{};
        plCI.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        plCI.setLayoutCount = 2; plCI.pSetLayouts = setLayouts;
        plCI.pushConstantRangeCount = 1; plCI.pPushConstantRanges = &pcRange;

        VkResult r = vkCreatePipelineLayout(m_device, &plCI, nullptr, &m_pipelineLayout);
        if (r != VK_SUCCESS) { LOGE("vkCreatePipelineLayout: %s", vkRes(r).c_str()); return false; }

        VkGraphicsPipelineCreateInfo gpCI{};
        gpCI.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        gpCI.stageCount = 2; gpCI.pStages = stages;
        gpCI.pVertexInputState = &viCI; gpCI.pInputAssemblyState = &iaCI;
        gpCI.pViewportState = &vpCI; gpCI.pRasterizationState = &rastCI;
        gpCI.pMultisampleState = &msCI; gpCI.pDepthStencilState = &dsCI;
        gpCI.pColorBlendState = &cbCI;
        gpCI.layout = m_pipelineLayout;
        gpCI.renderPass = m_renderResources.renderPass; gpCI.subpass = 0;

        r = vkCreateGraphicsPipelines(m_device, VK_NULL_HANDLE, 1, &gpCI, nullptr, &m_pipeline);
        if (r != VK_SUCCESS) { LOGE("vkCreateGraphicsPipelines: %s", vkRes(r).c_str()); return false; }

        // System overlay pipeline: triangle mesh, drawn after scene, no depth test/write.
        dsCI.depthTestEnable  = VK_FALSE;
        dsCI.depthWriteEnable = VK_FALSE;
        dsCI.depthCompareOp   = VK_COMPARE_OP_ALWAYS;

        r = vkCreateGraphicsPipelines(m_device, VK_NULL_HANDLE, 1, &gpCI, nullptr, &m_systemPipeline);
        if (r != VK_SUCCESS) { LOGE("vkCreateGraphicsPipelines(system): %s", vkRes(r).c_str()); return false; }

        // Plasma pipeline: additive blend (src=ONE, dst=ONE). Depth test is
        // OFF so flashes (muzzle, trail, hit, explosion, ENERGY pickup,
        // shield absorb) always render on top of gameplay geometry. With
        // depth test on (LESS_OR_EQUAL), an explosion centred at y=0 was
        // occluded by 3D asteroid meshes whose front-half vertices sit at
        // y<0 — closer to camera than the flash. Plasma overlays VFX, so
        // depth-suppression matches the semantic intent.
        dsCI.depthTestEnable  = VK_FALSE;
        dsCI.depthWriteEnable = VK_FALSE;
        dsCI.depthCompareOp   = VK_COMPARE_OP_ALWAYS;
        cbAtt.blendEnable         = VK_TRUE;
        cbAtt.srcColorBlendFactor = VK_BLEND_FACTOR_ONE;
        cbAtt.dstColorBlendFactor = VK_BLEND_FACTOR_ONE;
        cbAtt.colorBlendOp        = VK_BLEND_OP_ADD;
        cbAtt.srcAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
        cbAtt.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        cbAtt.alphaBlendOp        = VK_BLEND_OP_ADD;
        r = vkCreateGraphicsPipelines(m_device, VK_NULL_HANDLE, 1, &gpCI, nullptr, &m_plasmaPipeline);
        if (r != VK_SUCCESS) { LOGE("vkCreateGraphicsPipelines(plasma): %s", vkRes(r).c_str()); return false; }

        // Translucent pipeline (E1.2): same depth state as plasma (test on,
        // write off — so multiple translucent layers don't occlude each other
        // and back-to-front sorting becomes irrelevant for non-overlapping
        // layers), but standard alpha blending (SRC_ALPHA / ONE_MINUS_SRC_ALPHA)
        // instead of additive. Used by drawTranslucentMesh from Kotlin.
        cbAtt.blendEnable         = VK_TRUE;
        cbAtt.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
        cbAtt.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        cbAtt.colorBlendOp        = VK_BLEND_OP_ADD;
        cbAtt.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        cbAtt.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        cbAtt.alphaBlendOp        = VK_BLEND_OP_ADD;
        r = vkCreateGraphicsPipelines(m_device, VK_NULL_HANDLE, 1, &gpCI, nullptr, &m_translucentPipeline);
        if (r != VK_SUCCESS) { LOGE("vkCreateGraphicsPipelines(translucent): %s", vkRes(r).c_str()); return false; }

        // Additive mesh pipeline (E7): ONE/ONE blend like plasma, but accepts
        // arbitrary 3D meshes through a model matrix. Depth-test ON read-only
        // (depthCompareOp = LESS, depthWrite = OFF) so opaque geometry occludes
        // the additive mesh, but multiple additive layers don't punch each
        // other out — they accumulate into the framebuffer. Plasma billboards
        // disable depth-test entirely (they're pure overlay VFX); additive
        // meshes DO want occlusion so a fireball behind an asteroid is
        // correctly hidden. Used by drawAdditiveMesh from Kotlin for fireball
        // explosions, plasma beams, lightning bolts.
        dsCI.depthTestEnable  = VK_TRUE;
        dsCI.depthWriteEnable = VK_FALSE;
        dsCI.depthCompareOp   = VK_COMPARE_OP_LESS;
        cbAtt.blendEnable         = VK_TRUE;
        cbAtt.srcColorBlendFactor = VK_BLEND_FACTOR_ONE;
        cbAtt.dstColorBlendFactor = VK_BLEND_FACTOR_ONE;
        cbAtt.colorBlendOp        = VK_BLEND_OP_ADD;
        cbAtt.srcAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
        cbAtt.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        cbAtt.alphaBlendOp        = VK_BLEND_OP_ADD;
        r = vkCreateGraphicsPipelines(m_device, VK_NULL_HANDLE, 1, &gpCI, nullptr, &m_additivePipeline);
        if (r != VK_SUCCESS) { LOGE("vkCreateGraphicsPipelines(additive): %s", vkRes(r).c_str()); return false; }
        cbAtt.blendEnable = VK_FALSE;  // reset for subsequent pipelines

        // Frame pipeline: LINE_LIST, dynamic line width, no depth test
        iaCI.topology = VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
        rastCI.lineWidth = 1.0f;
        VkDynamicState dynState = VK_DYNAMIC_STATE_LINE_WIDTH;
        VkPipelineDynamicStateCreateInfo dynCI{};
        dynCI.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dynCI.dynamicStateCount = 1;
        dynCI.pDynamicStates = &dynState;
        gpCI.pDynamicState = &dynCI;
        r = vkCreateGraphicsPipelines(m_device, VK_NULL_HANDLE, 1, &gpCI, nullptr, &m_framePipeline);
        if (r != VK_SUCCESS) { LOGE("vkCreateGraphicsPipelines(frame): %s", vkRes(r).c_str()); return false; }
        gpCI.pDynamicState = nullptr;

        // Star pipeline: same but POINT_LIST topology and depth write off
        // (so stars don't occlude ships but ships occlude each other)
        iaCI.topology = VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
        dsCI.depthWriteEnable = VK_FALSE;
        dsCI.depthCompareOp   = VK_COMPARE_OP_ALWAYS; // always draw stars behind everything

        r = vkCreateGraphicsPipelines(m_device, VK_NULL_HANDLE, 1, &gpCI, nullptr, &m_starPipeline);
        if (r != VK_SUCCESS) { LOGE("vkCreateGraphicsPipelines(stars): %s", vkRes(r).c_str()); return false; }

        // E9 — Particle pipelines. Two pipelines share particle.vert /
        // particle.frag and the same per-instance binding 1; they differ
        // only in blend state (additive ONE/ONE for sparks vs. SRC_ALPHA
        // alpha-blend for textured smoke/debris) and depth-test behaviour.
        // Both reuse the unit-quad mesh from binding 0 and read 8 floats
        // per instance (pos3 + size1 + rgba4) from binding 1. Skip the
        // whole block if particle shaders weren't uploaded — the rest of
        // the engine still works.
        if (!particleVertSpv.empty() && !particleFragSpv.empty()) {
            if (!makeModule(particleVertSpv, m_particleVertModule)) return false;
            if (!makeModule(particleFragSpv, m_particleFragModule)) return false;

            VkPipelineShaderStageCreateInfo pStages[2]{};
            pStages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            pStages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
            pStages[0].module = m_particleVertModule; pStages[0].pName = "main";
            pStages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            pStages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
            pStages[1].module = m_particleFragModule; pStages[1].pName = "main";

            // Two vertex bindings: 0 = standard Vertex (unit quad mesh),
            // 1 = per-instance (rate INSTANCE) with 8 floats — pos3 + size1
            // + rgba4.
            VkVertexInputBindingDescription pBindings[2]{};
            pBindings[0] = Vertex::getBindingDescription();   // binding 0
            pBindings[1].binding   = 1;
            pBindings[1].stride    = sizeof(float) * kParticleFloatStride;
            pBindings[1].inputRate = VK_VERTEX_INPUT_RATE_INSTANCE;

            auto baseAttrs = Vertex::getAttributeDescriptions();
            std::vector<VkVertexInputAttributeDescription> pAttrs(baseAttrs.begin(), baseAttrs.end());
            // Per-instance attribute 4: vec4 (pos.xyz + size.w packed
            // together — single 16-byte fetch, friendlier to GPU than two
            // separate attributes).
            VkVertexInputAttributeDescription a4{};
            a4.binding  = 1;
            a4.location = 4;
            a4.format   = VK_FORMAT_R32G32B32A32_SFLOAT;
            a4.offset   = 0;
            // Per-instance attribute 5: vec4 RGBA colour.
            VkVertexInputAttributeDescription a5{};
            a5.binding  = 1;
            a5.location = 5;
            a5.format   = VK_FORMAT_R32G32B32A32_SFLOAT;
            a5.offset   = sizeof(float) * 4;
            pAttrs.push_back(a4);
            pAttrs.push_back(a5);

            VkPipelineVertexInputStateCreateInfo pViCI{};
            pViCI.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
            pViCI.vertexBindingDescriptionCount = 2;
            pViCI.pVertexBindingDescriptions = pBindings;
            pViCI.vertexAttributeDescriptionCount = (uint32_t)pAttrs.size();
            pViCI.pVertexAttributeDescriptions = pAttrs.data();

            // Reset the bits of state we mutated for frame/star pipelines.
            iaCI.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            rastCI.lineWidth = 1.0f;
            gpCI.pStages             = pStages;
            gpCI.pVertexInputState   = &pViCI;
            gpCI.pInputAssemblyState = &iaCI;
            gpCI.pDynamicState       = nullptr;

            // Particle additive: ONE/ONE, depth-test off (overlay VFX —
            // matches plasma billboards' semantic).
            dsCI.depthTestEnable  = VK_FALSE;
            dsCI.depthWriteEnable = VK_FALSE;
            dsCI.depthCompareOp   = VK_COMPARE_OP_ALWAYS;
            cbAtt.blendEnable         = VK_TRUE;
            cbAtt.srcColorBlendFactor = VK_BLEND_FACTOR_ONE;
            cbAtt.dstColorBlendFactor = VK_BLEND_FACTOR_ONE;
            cbAtt.colorBlendOp        = VK_BLEND_OP_ADD;
            cbAtt.srcAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
            cbAtt.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
            cbAtt.alphaBlendOp        = VK_BLEND_OP_ADD;
            r = vkCreateGraphicsPipelines(m_device, VK_NULL_HANDLE, 1, &gpCI, nullptr, &m_particleAdditivePipeline);
            if (r != VK_SUCCESS) { LOGE("vkCreateGraphicsPipelines(particle additive): %s", vkRes(r).c_str()); return false; }

            // Particle alpha-textured: SRC_ALPHA / ONE_MINUS_SRC_ALPHA,
            // depth-test on read-only (smoke/debris IS 3D — should hide
            // behind closer opaque geometry, but multiple smoke layers
            // don't punch each other out).
            dsCI.depthTestEnable  = VK_TRUE;
            dsCI.depthWriteEnable = VK_FALSE;
            dsCI.depthCompareOp   = VK_COMPARE_OP_LESS;
            cbAtt.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
            cbAtt.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            cbAtt.colorBlendOp        = VK_BLEND_OP_ADD;
            cbAtt.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
            cbAtt.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            cbAtt.alphaBlendOp        = VK_BLEND_OP_ADD;
            r = vkCreateGraphicsPipelines(m_device, VK_NULL_HANDLE, 1, &gpCI, nullptr, &m_particleAlphaPipeline);
            if (r != VK_SUCCESS) { LOGE("vkCreateGraphicsPipelines(particle alpha): %s", vkRes(r).c_str()); return false; }
            cbAtt.blendEnable = VK_FALSE;

            LOGI("Particle pipelines created (additive + alpha-textured)");
        }

        // E10.1 — post-process pipeline. Fullscreen triangle (no vertex
        // input bindings — gl_VertexIndex generates positions in vert),
        // single descriptor set with the offscreen colour sampler, no
        // depth, no blend. Renders to the swapchain via m_postRenderPass.
        if (!postVertSpv.empty() && !postFragSpv.empty() &&
            m_renderResources.postRenderPass != VK_NULL_HANDLE) {
            if (!makeModule(postVertSpv, m_postVertModule)) return false;
            if (!makeModule(postFragSpv, m_postFragModule)) return false;

            // Two bindings — binding 0 = scene colour, binding 1 = scene
            // velocity (E10.2). The post.frag in E10.2 only samples colour
            // (passthrough); the velocity binding is wired up now so the
            // motion-blur shader in E10.4 can use it without further
            // descriptor changes. Vulkan permits descriptor bindings the
            // shader doesn't read — only the reverse is an error.
            VkDescriptorSetLayoutBinding postBindings[2]{};
            postBindings[0].binding         = 0;
            postBindings[0].descriptorType  = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            postBindings[0].descriptorCount = 1;
            postBindings[0].stageFlags      = VK_SHADER_STAGE_FRAGMENT_BIT;
            postBindings[1].binding         = 1;
            postBindings[1].descriptorType  = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            postBindings[1].descriptorCount = 1;
            postBindings[1].stageFlags      = VK_SHADER_STAGE_FRAGMENT_BIT;
            VkDescriptorSetLayoutCreateInfo dslCI{};
            dslCI.sType        = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            dslCI.bindingCount = 2;
            dslCI.pBindings    = postBindings;
            r = vkCreateDescriptorSetLayout(m_device, &dslCI, nullptr, &m_postSetLayout);
            if (r != VK_SUCCESS) { LOGE("vkCreateDescriptorSetLayout(post): %s", vkRes(r).c_str()); return false; }

            VkDescriptorPoolSize poolSize{ VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2 };
            VkDescriptorPoolCreateInfo poolCI{};
            poolCI.sType         = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            poolCI.poolSizeCount = 1;
            poolCI.pPoolSizes    = &poolSize;
            poolCI.maxSets       = 1;
            r = vkCreateDescriptorPool(m_device, &poolCI, nullptr, &m_postDescriptorPool);
            if (r != VK_SUCCESS) { LOGE("vkCreateDescriptorPool(post): %s", vkRes(r).c_str()); return false; }

            VkDescriptorSetAllocateInfo dsAI{};
            dsAI.sType              = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            dsAI.descriptorPool     = m_postDescriptorPool;
            dsAI.descriptorSetCount = 1;
            dsAI.pSetLayouts        = &m_postSetLayout;
            r = vkAllocateDescriptorSets(m_device, &dsAI, &m_postDescriptorSet);
            if (r != VK_SUCCESS) { LOGE("vkAllocateDescriptorSets(post): %s", vkRes(r).c_str()); return false; }

            VkDescriptorImageInfo diis[2]{};
            diis[0].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            diis[0].imageView   = m_renderResources.offscreenColorView;
            diis[0].sampler     = m_renderResources.offscreenColorSampler;
            diis[1].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            diis[1].imageView   = m_renderResources.offscreenVelocityView;
            diis[1].sampler     = m_renderResources.offscreenVelocitySampler;
            VkWriteDescriptorSet writes[2]{};
            writes[0].sType            = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[0].dstSet           = m_postDescriptorSet;
            writes[0].dstBinding       = 0;
            writes[0].descriptorType   = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            writes[0].descriptorCount  = 1;
            writes[0].pImageInfo       = &diis[0];
            writes[1].sType            = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[1].dstSet           = m_postDescriptorSet;
            writes[1].dstBinding       = 1;
            writes[1].descriptorType   = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            writes[1].descriptorCount  = 1;
            writes[1].pImageInfo       = &diis[1];
            vkUpdateDescriptorSets(m_device, 2, writes, 0, nullptr);

            // Pipeline layout — own minimal layout (1 set, no PCs).
            VkPipelineLayoutCreateInfo plCI{};
            plCI.sType          = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
            plCI.setLayoutCount = 1;
            plCI.pSetLayouts    = &m_postSetLayout;
            r = vkCreatePipelineLayout(m_device, &plCI, nullptr, &m_postPipelineLayout);
            if (r != VK_SUCCESS) { LOGE("vkCreatePipelineLayout(post): %s", vkRes(r).c_str()); return false; }

            VkPipelineShaderStageCreateInfo postStages[2]{};
            postStages[0].sType  = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            postStages[0].stage  = VK_SHADER_STAGE_VERTEX_BIT;
            postStages[0].module = m_postVertModule; postStages[0].pName = "main";
            postStages[1].sType  = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            postStages[1].stage  = VK_SHADER_STAGE_FRAGMENT_BIT;
            postStages[1].module = m_postFragModule; postStages[1].pName = "main";

            VkPipelineVertexInputStateCreateInfo postViCI{};
            postViCI.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
            // No vertex bindings — vertex shader uses gl_VertexIndex.

            VkPipelineInputAssemblyStateCreateInfo postIaCI{};
            postIaCI.sType    = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
            postIaCI.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

            VkPipelineDepthStencilStateCreateInfo postDsCI{};
            postDsCI.sType            = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
            postDsCI.depthTestEnable  = VK_FALSE;
            postDsCI.depthWriteEnable = VK_FALSE;
            postDsCI.depthCompareOp   = VK_COMPARE_OP_ALWAYS;

            VkPipelineColorBlendAttachmentState postCb{};
            postCb.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                    VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
            postCb.blendEnable = VK_FALSE;
            VkPipelineColorBlendStateCreateInfo postCbCI{};
            postCbCI.sType           = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
            postCbCI.attachmentCount = 1;
            postCbCI.pAttachments    = &postCb;

            VkGraphicsPipelineCreateInfo postGpCI = gpCI;  // copy basic state
            postGpCI.stageCount          = 2;
            postGpCI.pStages             = postStages;
            postGpCI.pVertexInputState   = &postViCI;
            postGpCI.pInputAssemblyState = &postIaCI;
            postGpCI.pDepthStencilState  = &postDsCI;
            postGpCI.pColorBlendState    = &postCbCI;
            postGpCI.pDynamicState       = nullptr;
            postGpCI.layout              = m_postPipelineLayout;
            postGpCI.renderPass          = m_renderResources.postRenderPass;
            postGpCI.subpass             = 0;

            r = vkCreateGraphicsPipelines(m_device, VK_NULL_HANDLE, 1, &postGpCI, nullptr, &m_postPipeline);
            if (r != VK_SUCCESS) { LOGE("vkCreateGraphicsPipelines(post): %s", vkRes(r).c_str()); return false; }
            LOGI("Post-process pipeline created");
        }

        LOGI("Pipelines created (mesh + system + stars + particles + post)");
        return true;
    }

    // -----------------------------------------------------------------------
    // destroySwapchain
    // -----------------------------------------------------------------------
    void VulkanContext::destroySwapchain() {
        RenderResourcesBuilder::destroy(m_device, m_renderResources);
        for (auto iv : m_swapViews) if (iv != VK_NULL_HANDLE) vkDestroyImageView(m_device, iv, nullptr);
        m_swapViews.clear(); m_swapImages.clear();
        if (m_swapchain != VK_NULL_HANDLE) { vkDestroySwapchainKHR(m_device, m_swapchain, nullptr); m_swapchain = VK_NULL_HANDLE; }
    }

    // -----------------------------------------------------------------------
    // Mesh pool
    // -----------------------------------------------------------------------
    uint32_t VulkanContext::uploadMesh(const MeshData& data) {
        for (uint32_t i = 0; i < kMaxMeshes; ++i) {
            if (!m_meshUsed[i]) {
                if (!m_meshPool[i].create(m_physicalDevice, m_device, data)) return 0;
                float minV[3] = {
                        std::numeric_limits<float>::max(),
                        std::numeric_limits<float>::max(),
                        std::numeric_limits<float>::max()
                };
                float maxV[3] = {
                        -std::numeric_limits<float>::max(),
                        -std::numeric_limits<float>::max(),
                        -std::numeric_limits<float>::max()
                };
                for (const auto& v : data.vertices) {
                    for (int axis = 0; axis < 3; ++axis) {
                        minV[axis] = std::min(minV[axis], v.position[axis]);
                        maxV[axis] = std::max(maxV[axis], v.position[axis]);
                    }
                }
                m_meshFramePoints[i].clear();
                m_meshFramePoints[i].reserve(data.vertices.size());
                for (const auto& v : data.vertices) {
                    m_meshFramePoints[i].push_back({v.position[0], v.position[1], v.position[2]});
                }
                float radiusSq = 0.0f;
                for (int axis = 0; axis < 3; ++axis) {
                    m_meshBounds[i].center[axis] = (minV[axis] + maxV[axis]) * 0.5f;
                    m_meshBounds[i].halfExtents[axis] = (maxV[axis] - minV[axis]) * 0.5f;
                    radiusSq += m_meshBounds[i].halfExtents[axis] * m_meshBounds[i].halfExtents[axis];
                }
                m_meshBounds[i].radius = std::sqrt(radiusSq);
                m_meshUsed[i] = true;
                return i + 1;
            }
        }
        LOGE("Mesh pool full"); return 0;
    }

    void VulkanContext::freeMesh(uint32_t token) {
        if (!token || token > kMaxMeshes) return;
        uint32_t i = token - 1;
        if (m_meshUsed[i]) {
            m_meshPool[i].destroy(m_device);
            m_meshUsed[i] = false;
            m_meshFramePoints[i].clear();
        }
    }

    uint32_t VulkanContext::uploadTexture(const uint8_t* pngBytes, uint32_t length) {
        if (!pngBytes || !length) return 0;
        for (uint32_t i = 0; i < kMaxTextures; ++i) {
            if (!m_textureUsed[i]) {
                if (!m_textureSlots[i].createFromPng(
                        m_physicalDevice, m_device, m_graphicsQueue, m_queueFamily,
                        m_texturePool, m_textureSetLayout,
                        pngBytes, length)) return 0;
                m_textureUsed[i] = true;
                return i + 1;
            }
        }
        LOGE("Texture pool full"); return 0;
    }

    uint32_t VulkanContext::uploadTextureRaw(const uint8_t* rgba8, uint32_t w, uint32_t h) {
        if (!rgba8 || !w || !h) return 0;
        for (uint32_t i = 0; i < kMaxTextures; ++i) {
            if (!m_textureUsed[i]) {
                if (!m_textureSlots[i].createFromPixels(
                        m_physicalDevice, m_device, m_graphicsQueue, m_queueFamily,
                        m_texturePool, m_textureSetLayout,
                        rgba8, w, h)) return 0;
                m_textureUsed[i] = true;
                return i + 1;
            }
        }
        LOGE("Texture pool full"); return 0;
    }

    void VulkanContext::freeTexture(uint32_t token) {
        if (!token || token > kMaxTextures) return;
        uint32_t i = token - 1;
        if (m_textureUsed[i]) {
            m_textureSlots[i].destroy(m_device, m_texturePool);
            m_textureUsed[i] = false;
        }
    }

    // E9 — accept a particle batch from Kotlin. Per-instance data is
    // appended to the appropriate staging vector (additive vs alpha)
    // and a ParticleBatch entry records meshToken/textureToken/offset/count
    // so renderFrame can issue one instanced draw per batch sharing
    // pipeline state. Bounds-check against kMaxParticles per pipeline so
    // a runaway emitter can't blow up the GPU buffer.
    void VulkanContext::drawParticles(uint32_t meshToken, uint32_t textureToken,
                                      const float* instanceFloats, uint32_t count,
                                      int32_t mode) {
        if (!m_sceneOpen || meshToken == 0 || meshToken > kMaxMeshes) return;
        if (!instanceFloats || count == 0) return;
        std::vector<float>& staging = (mode == 1) ? m_particleAlphaStaging
                                                  : m_particleAdditiveStaging;
        // Cap at kMaxParticles total per pipeline. Prefer the older particles
        // (already in the buffer) over the new burst — drop the tail.
        const uint32_t haveFloats   = (uint32_t)staging.size();
        const uint32_t haveCount    = haveFloats / kParticleFloatStride;
        if (haveCount >= kMaxParticles) return;
        const uint32_t spaceLeft    = kMaxParticles - haveCount;
        const uint32_t addCount     = std::min(count, spaceLeft);
        const uint32_t addFloats    = addCount * kParticleFloatStride;

        ParticleBatch batch{};
        batch.meshToken          = meshToken;
        batch.textureToken       = textureToken;
        batch.bufferOffsetFloats = haveFloats;
        batch.count              = addCount;
        batch.mode               = mode;

        staging.insert(staging.end(), instanceFloats, instanceFloats + addFloats);
        m_particleBatches.push_back(batch);
    }

    void VulkanContext::beginScene() {
        m_drawList.clear();
        m_systemDrawList.clear();
        m_plasmaDrawList.clear();
        m_translucentDrawList.clear();
        m_additiveDrawList.clear();
        m_texturedDrawList.clear();
        m_particleBatches.clear();
        m_particleAdditiveStaging.clear();
        m_particleAlphaStaging.clear();
        m_pickRecords.clear();
        m_sceneOpen = true;
    }

    void VulkanContext::drawMesh(uint32_t token, const float modelMatrix[16]) {
        if (!m_sceneOpen || token == 0 || token > kMaxMeshes) return;
        DrawCommand cmd{};
        cmd.token = token;
        cmd.billboard = false;
        cmd.objectFrame = false;
        std::memcpy(cmd.modelMatrix, modelMatrix, sizeof(float) * 16);
        m_drawList.push_back(cmd);
    }

    void VulkanContext::drawPickableMesh(uint32_t token, int32_t objectId,
                                         const float modelMatrix[16], float pickRadius) {
        drawMesh(token, modelMatrix);
        if (!m_sceneOpen || objectId < 0 || pickRadius <= 0.0f) return;
        if (token == 0 || token > kMaxMeshes) return;

        const MeshBounds& bounds = m_meshBounds[token - 1];
        const float lx = bounds.center[0];
        const float ly = bounds.center[1];
        const float lz = bounds.center[2];

        PickRecord record{};
        record.objectId = objectId;
        record.token = token;
        record.center[0] = modelMatrix[0] * lx + modelMatrix[4] * ly + modelMatrix[8]  * lz + modelMatrix[12];
        record.center[1] = modelMatrix[1] * lx + modelMatrix[5] * ly + modelMatrix[9]  * lz + modelMatrix[13];
        record.center[2] = modelMatrix[2] * lx + modelMatrix[6] * ly + modelMatrix[10] * lz + modelMatrix[14];
        record.radius = std::max(pickRadius, bounds.radius);
        m_pickRecords.push_back(record);
    }

    // E5.1 — `r,g,b,a` is the per-billboard tint; default (1,1,1,1) at the
    // Kotlin layer keeps the E4 heat-ramp look. The shader multiplies the
    // heat-ramp result by `pc.plasmaColor.rgb` and uses `.a` as a brightness
    // scalar, so callers can recolour individual flashes (cyan ENERGY pickup,
    // orange-red AoE explosions, blue impact sparks, etc.) without touching
    // the underlying mesh tint.
    // E5.2 — `scaleH` and `scaleV` allow non-uniform billboards (streak
    // bullets, flat shockwaves). Pass equal values for a square billboard.
    void VulkanContext::drawPlasmaBillboard(uint32_t token, float x, float y, float z,
                                            float scaleH, float scaleV,
                                            float r, float g, float b, float a) {
        if (!m_sceneOpen || token == 0 || token > kMaxMeshes) return;
        DrawCommand cmd{};
        cmd.token     = token;
        cmd.billboard = true;
        cmd.center[0] = x; cmd.center[1] = y; cmd.center[2] = z;
        cmd.scale     = scaleH;
        cmd.scaleV    = scaleV;
        cmd.plasmaColor[0] = r;
        cmd.plasmaColor[1] = g;
        cmd.plasmaColor[2] = b;
        cmd.plasmaColor[3] = a;
        m_plasmaDrawList.push_back(cmd);
    }

    // E1.2 — alpha-blended mesh draw. Uses the regular triangle.frag (RGBA
    // output) but the translucent pipeline applies SRC_ALPHA blending so the
    // mesh's per-vertex alpha controls how much it occludes what's behind.
    // E3.1 — material flags packed into tint slots so the fragment shader can
    // branch on them: pc.tint.y = NEBULA, pc.tint.z = HEX. plain = no flags.
    void VulkanContext::drawTranslucentMesh(uint32_t token, const float modelMatrix[16], int32_t material) {
        if (!m_sceneOpen || token == 0 || token > kMaxMeshes) return;
        DrawCommand cmd{};
        cmd.token       = token;
        cmd.billboard   = false;
        cmd.objectFrame = false;
        std::memcpy(cmd.modelMatrix, modelMatrix, sizeof(float) * 16);
        if (material == 1) cmd.tint[1] = 1.0f;  // NEBULA → fragment FBM mode
        if (material == 2) cmd.tint[2] = 1.0f;  // HEX    → fragment hex-grid mode
        m_translucentDrawList.push_back(cmd);
    }

    // E8.3 — textured opaque mesh. Goes through the same opaque pipeline as
    // drawMesh (depth-test+write on, no blend), but the render-loop step
    // binds set 1 to the texture's descriptor set and pushes textureMode=1
    // so the fragment shader samples vUV. (r,g,b,a) is multiplied into the
    // sampled colour as a tint (default white = no tint).
    void VulkanContext::drawTexturedMesh(uint32_t meshToken, uint32_t textureToken,
                                         const float modelMatrix[16],
                                         float r, float g, float b, float a) {
        if (!m_sceneOpen || meshToken == 0 || meshToken > kMaxMeshes) return;
        if (textureToken == 0 || textureToken > kMaxTextures) return;
        DrawCommand cmd{};
        cmd.token        = meshToken;
        cmd.textureToken = textureToken;
        cmd.billboard    = false;
        cmd.objectFrame  = false;
        std::memcpy(cmd.modelMatrix, modelMatrix, sizeof(float) * 16);
        cmd.plasmaColor[0] = r;
        cmd.plasmaColor[1] = g;
        cmd.plasmaColor[2] = b;
        cmd.plasmaColor[3] = a;
        m_texturedDrawList.push_back(cmd);
    }

    // E7 — additive mesh draw. Same blend mode as plasma billboards (ONE/ONE)
    // but accepts arbitrary 3D mesh + model matrix instead of camera-facing
    // billboards. The (r,g,b,a) tint piggybacks on plasmaColor — fragment
    // shader multiplies it into per-vertex colour as overall colour and
    // brightness scalar (matches the E5.1 plasmaColor semantics). Per-vertex
    // alpha (set when the mesh is authored / uploaded) controls glow falloff:
    // mesh authors put A=1 at glow centres, A=0 at edges for soft fade.
    // E7.1 — `material` encoded into cmd.tint[3] (1.0f = plain pass-through,
    // 2.0f = fire material with heat-ramp + FBM in the fragment shader). The
    // render loop copies cmd.tint → pc.tint so the shader can branch on .w.
    void VulkanContext::drawAdditiveMesh(uint32_t token, const float modelMatrix[16],
                                         float r, float g, float b, float a,
                                         int32_t material) {
        if (!m_sceneOpen || token == 0 || token > kMaxMeshes) return;
        DrawCommand cmd{};
        cmd.token       = token;
        cmd.billboard   = false;
        cmd.objectFrame = false;
        std::memcpy(cmd.modelMatrix, modelMatrix, sizeof(float) * 16);
        cmd.plasmaColor[0] = r;
        cmd.plasmaColor[1] = g;
        cmd.plasmaColor[2] = b;
        cmd.plasmaColor[3] = a;
        // .w = 1.0f for plain additive; 2.0f for fire material. Fragment
        // shader branches on `pc.tint.w` ranges.
        cmd.tint[3] = (material == 1) ? 2.0f : 1.0f;
        m_additiveDrawList.push_back(cmd);
    }

    void VulkanContext::drawBillboardMesh(uint32_t token, float x, float y, float z, float scale) {
        if (!m_sceneOpen || token == 0 || token > kMaxMeshes) return;
        DrawCommand cmd{};
        cmd.token = token;
        cmd.billboard = true;
        cmd.objectFrame = false;
        cmd.center[0] = x;
        cmd.center[1] = y;
        cmd.center[2] = z;
        cmd.scale = scale;
        m_systemDrawList.push_back(cmd);
    }

    void VulkanContext::drawObjectFrameMesh(uint32_t frameToken, uint32_t targetToken, const float modelMatrix[16], float padding, const float tint[4]) {
        if (!m_sceneOpen || frameToken == 0 || frameToken > kMaxMeshes) return;
        if (targetToken == 0 || targetToken > kMaxMeshes) return;
        DrawCommand cmd{};
        cmd.token = frameToken;
        cmd.targetToken = targetToken;
        cmd.billboard = false;
        cmd.objectFrame = true;
        const MeshBounds& bounds = m_meshBounds[targetToken - 1];
        cmd.center[0] = bounds.center[0];
        cmd.center[1] = bounds.center[1];
        cmd.center[2] = bounds.center[2];
        cmd.halfExtents[0] = bounds.halfExtents[0];
        cmd.halfExtents[1] = bounds.halfExtents[1];
        cmd.halfExtents[2] = bounds.halfExtents[2];
        cmd.padding = padding;
        std::memcpy(cmd.tint, tint, sizeof(float) * 4);
        std::memcpy(cmd.modelMatrix, modelMatrix, sizeof(float) * 16);
        m_systemDrawList.push_back(cmd);
    }

    void VulkanContext::drawGameplayFrameMesh(uint32_t frameToken, const float modelMatrix[16],
                                              const float* localPoints, int32_t pointCount,
                                              float padding, float lineWidth, const float tint[4]) {
        if (!m_sceneOpen || frameToken == 0 || frameToken > kMaxMeshes) return;
        if (!localPoints || pointCount <= 0) return;

        DrawCommand cmd{};
        cmd.token = frameToken;
        cmd.targetToken = 0;
        cmd.billboard = false;
        cmd.objectFrame = true;
        cmd.padding = padding;
        cmd.scale = lineWidth; // repurposed: line width for frame draws
        std::memcpy(cmd.tint, tint, sizeof(float) * 4);
        std::memcpy(cmd.modelMatrix, modelMatrix, sizeof(float) * 16);

        cmd.framePoints.reserve(static_cast<size_t>(pointCount));
        float minV[3] = {
                std::numeric_limits<float>::max(),
                std::numeric_limits<float>::max(),
                std::numeric_limits<float>::max()
        };
        float maxV[3] = {
                -std::numeric_limits<float>::max(),
                -std::numeric_limits<float>::max(),
                -std::numeric_limits<float>::max()
        };
        for (int32_t i = 0; i < pointCount; ++i) {
            const math::Vec3 point{
                    localPoints[i * 3],
                    localPoints[i * 3 + 1],
                    localPoints[i * 3 + 2]
            };
            cmd.framePoints.push_back(point);
            minV[0] = std::min(minV[0], point.x);
            minV[1] = std::min(minV[1], point.y);
            minV[2] = std::min(minV[2], point.z);
            maxV[0] = std::max(maxV[0], point.x);
            maxV[1] = std::max(maxV[1], point.y);
            maxV[2] = std::max(maxV[2], point.z);
        }

        for (int axis = 0; axis < 3; ++axis) {
            cmd.center[axis] = (minV[axis] + maxV[axis]) * 0.5f;
            cmd.halfExtents[axis] = (maxV[axis] - minV[axis]) * 0.5f;
        }
        m_systemDrawList.push_back(cmd);
    }

    int32_t VulkanContext::pickObject(float screenX, float screenY, int32_t currentObjectId) const {
        if (m_pickRecords.empty() || m_sel.extent.width == 0 || m_sel.extent.height == 0) {
            return -1;
        }

        struct Hit {
            int32_t objectId;
            float depth;
        };
        std::vector<Hit> hits;

        const float width = static_cast<float>(m_sel.extent.width);
        const float height = static_cast<float>(m_sel.extent.height);
        const math::Mat4 vp = m_camera.viewProjection(computeSceneFarClip());

        for (const auto& record : m_pickRecords) {
            const math::Vec3 center{record.center[0], record.center[1], record.center[2]};
            const ProjectedPoint pc = projectPoint(vp, center, width, height);
            if (!pc.visible) continue;

            const ProjectedPoint pr = projectPoint(
                    vp,
                    {center.x + record.radius, center.y, center.z},
                    width, height);
            float screenRadius = 40.0f;
            if (pr.visible) {
                const float dx = pr.x - pc.x;
                const float dy = pr.y - pc.y;
                screenRadius = std::sqrt(dx * dx + dy * dy);
            }
            screenRadius = std::clamp(screenRadius, 28.0f, 140.0f);

            const float dx = screenX - pc.x;
            const float dy = screenY - pc.y;
            if ((dx * dx + dy * dy) <= (screenRadius * screenRadius)) {
                hits.push_back({record.objectId, pc.depth});
            }
        }

        if (hits.empty()) return -1;

        std::sort(hits.begin(), hits.end(), [](const Hit& a, const Hit& b) {
            return a.depth < b.depth;
        });

        for (size_t i = 0; i < hits.size(); ++i) {
            if (hits[i].objectId == currentObjectId) {
                return (i + 1 < hits.size()) ? hits[i + 1].objectId : -1;
            }
        }
        return hits.front().objectId;
    }

    bool VulkanContext::projectGameplayBounds(const float modelMatrix[16],
                                              const float* localPoints,
                                              int32_t pointCount,
                                              float padding,
                                              float outBounds[7]) const {
        if (!modelMatrix || !localPoints || !outBounds || pointCount <= 0 ||
            m_sel.extent.width == 0 || m_sel.extent.height == 0) {
            return false;
        }

        const float width = static_cast<float>(m_sel.extent.width);
        const float height = static_cast<float>(m_sel.extent.height);
        const math::Mat4 vp = m_camera.viewProjection(computeSceneFarClip());

        float minX =  std::numeric_limits<float>::max();
        float maxX = -std::numeric_limits<float>::max();
        float minY =  std::numeric_limits<float>::max();
        float maxY = -std::numeric_limits<float>::max();
        float depthSum = 0.0f;
        int projectedCount = 0;

        for (int32_t i = 0; i < pointCount; ++i) {
            const float* local = localPoints + i * 3;
            const math::Vec3 world = transformPoint(modelMatrix, local);
            const float clipX = vp.m[0] * world.x + vp.m[4] * world.y + vp.m[8]  * world.z + vp.m[12];
            const float clipY = vp.m[1] * world.x + vp.m[5] * world.y + vp.m[9]  * world.z + vp.m[13];
            const float clipZ = vp.m[2] * world.x + vp.m[6] * world.y + vp.m[10] * world.z + vp.m[14];
            const float clipW = vp.m[3] * world.x + vp.m[7] * world.y + vp.m[11] * world.z + vp.m[15];
            if (clipW <= 0.0001f) continue;

            const float ndcX = clipX / clipW;
            const float ndcY = clipY / clipW;
            const float ndcZ = clipZ / clipW;
            if (ndcX < -1.0f || ndcX > 1.0f || ndcY < -1.0f || ndcY > 1.0f ||
                ndcZ < -0.05f || ndcZ > 1.05f) {
                continue;
            }

            const float screenX = (ndcX * 0.5f + 0.5f) * width;
            const float screenY = (ndcY * 0.5f + 0.5f) * height;

            minX = std::min(minX, screenX);
            maxX = std::max(maxX, screenX);
            minY = std::min(minY, screenY);
            maxY = std::max(maxY, screenY);
            depthSum += ndcZ;
            ++projectedCount;
        }

        if (projectedCount == 0) return false;

        const float padX = (maxX - minX) * std::max(padding, 0.0f) * 0.5f;
        const float padY = (maxY - minY) * std::max(padding, 0.0f) * 0.5f;
        minX -= padX;
        maxX += padX;
        minY -= padY;
        maxY += padY;

        const bool intersectsScreen = maxX >= 0.0f && minX <= width && maxY >= 0.0f && minY <= height;
        if (!intersectsScreen) return false;

        const bool fullyInside = minX >= 0.0f && maxX <= width && minY >= 0.0f && maxY <= height;
        outBounds[0] = 1.0f;
        outBounds[1] = fullyInside ? 0.0f : 1.0f;
        outBounds[2] = minX;
        outBounds[3] = minY;
        outBounds[4] = maxX;
        outBounds[5] = maxY;
        outBounds[6] = depthSum / static_cast<float>(projectedCount);
        return true;
    }

    bool VulkanContext::projectMeshBounds(uint32_t token,
                                          const float modelMatrix[16],
                                          float padding,
                                          float outBounds[7]) const {
        if (!modelMatrix || !outBounds || token == 0 || token > kMaxMeshes ||
            !m_meshUsed[token - 1] || m_sel.extent.width == 0 || m_sel.extent.height == 0) {
            return false;
        }

        const std::vector<math::Vec3>& points = m_meshFramePoints[token - 1];
        if (points.empty()) return false;

        const float width = static_cast<float>(m_sel.extent.width);
        const float height = static_cast<float>(m_sel.extent.height);
        const math::Mat4 vp = m_camera.viewProjection(computeSceneFarClip());

        float minX =  std::numeric_limits<float>::max();
        float maxX = -std::numeric_limits<float>::max();
        float minY =  std::numeric_limits<float>::max();
        float maxY = -std::numeric_limits<float>::max();
        float depthSum = 0.0f;
        int projectedCount = 0;

        for (const math::Vec3& local : points) {
            const float p[3] = {local.x, local.y, local.z};
            const math::Vec3 world = transformPoint(modelMatrix, p);
            const float clipX = vp.m[0] * world.x + vp.m[4] * world.y + vp.m[8]  * world.z + vp.m[12];
            const float clipY = vp.m[1] * world.x + vp.m[5] * world.y + vp.m[9]  * world.z + vp.m[13];
            const float clipZ = vp.m[2] * world.x + vp.m[6] * world.y + vp.m[10] * world.z + vp.m[14];
            const float clipW = vp.m[3] * world.x + vp.m[7] * world.y + vp.m[11] * world.z + vp.m[15];
            if (clipW <= 0.0001f) continue;

            const float ndcX = clipX / clipW;
            const float ndcY = clipY / clipW;
            const float ndcZ = clipZ / clipW;
            if (ndcX < -1.0f || ndcX > 1.0f || ndcY < -1.0f || ndcY > 1.0f ||
                ndcZ < -0.05f || ndcZ > 1.05f) {
                continue;
            }

            const float screenX = (ndcX * 0.5f + 0.5f) * width;
            const float screenY = (ndcY * 0.5f + 0.5f) * height;

            minX = std::min(minX, screenX);
            maxX = std::max(maxX, screenX);
            minY = std::min(minY, screenY);
            maxY = std::max(maxY, screenY);
            depthSum += ndcZ;
            ++projectedCount;
        }

        if (projectedCount == 0) return false;

        const float padX = (maxX - minX) * std::max(padding, 0.0f) * 0.5f;
        const float padY = (maxY - minY) * std::max(padding, 0.0f) * 0.5f;
        minX -= padX;
        maxX += padX;
        minY -= padY;
        maxY += padY;

        const bool intersectsScreen = maxX >= 0.0f && minX <= width && maxY >= 0.0f && minY <= height;
        if (!intersectsScreen) return false;

        const bool fullyInside = minX >= 0.0f && maxX <= width && minY >= 0.0f && maxY <= height;
        outBounds[0] = 1.0f;
        outBounds[1] = fullyInside ? 0.0f : 1.0f;
        outBounds[2] = minX;
        outBounds[3] = minY;
        outBounds[4] = maxX;
        outBounds[5] = maxY;
        outBounds[6] = depthSum / static_cast<float>(projectedCount);
        return true;
    }

    void VulkanContext::endScene() {
        m_sceneOpen = false;
        // Draw list is consumed in renderFrame()
    }
    void VulkanContext::setFocused(bool f)             { m_focused = f; }
    void VulkanContext::orbitCamera(float dy, float dp){ m_camera.orbit(dy, dp); }
    void VulkanContext::rollCamera(float a)                { m_camera.roll(a); }
    void VulkanContext::panCamera(float dx, float dy)        { m_camera.pan(dx, dy); }
    void VulkanContext::zoomCamera(float factor)       { m_camera.zoom(factor); }
    void VulkanContext::zoomCameraAt(float factor, float screenX, float screenY) {
        m_camera.zoomAt(
                factor,
                screenX,
                screenY,
                static_cast<float>(m_sel.extent.width),
                static_cast<float>(m_sel.extent.height),
                0.0f);
    }

    // -----------------------------------------------------------------------
    // updateUniformBuffer — now uploads view + proj separately
    // -----------------------------------------------------------------------
    float VulkanContext::computeSceneFarClip() const {
        using namespace math;
        const Vec3 eye = m_camera.eyePosition();
        const Vec3 forward = m_camera.forwardDirection();

        float farClip = m_camera.farClip();
        auto includeSphere = [&](const Vec3& center, float radius) {
            const float depth = dot(center - eye, forward);
            if (depth > 0.0f) {
                farClip = std::max(farClip, depth + std::max(radius, 0.0f) + 25.0f);
            }
        };

        for (const auto& draw : m_drawList) {
            if (draw.token == 0 || draw.token > kMaxMeshes) continue;
            const MeshBounds& bounds = m_meshBounds[draw.token - 1];
            if (draw.billboard) {
                includeSphere({draw.center[0], draw.center[1], draw.center[2]}, draw.scale);
                continue;
            }

            const Vec3 center = transformPoint(draw.modelMatrix, bounds.center);
            includeSphere(center, bounds.radius * maxColumnScale(draw.modelMatrix));
        }

        for (const auto& draw : m_systemDrawList) {
            if (draw.billboard) {
                includeSphere({draw.center[0], draw.center[1], draw.center[2]}, draw.scale);
            }
        }

        return std::clamp(farClip, 100.0f, 1000.0f);
    }

    void VulkanContext::updateUniformBuffer() {
        using namespace math;
        const Mat4 view = m_camera.viewMatrix();
        const Mat4 proj = m_camera.projMatrix(computeSceneFarClip());

        UniformBufferObject ubo{};
        for (int i = 0; i < 16; ++i) { ubo.view[i] = view.m[i]; ubo.proj[i] = proj.m[i]; }

        void* mapped = nullptr;
        if (vkMapMemory(m_device, m_uniformMemory, 0, sizeof(ubo), 0, &mapped) == VK_SUCCESS) {
            memcpy(mapped, &ubo, sizeof(ubo));
            vkUnmapMemory(m_device, m_uniformMemory);
        }
    }

    // -----------------------------------------------------------------------
    // renderFrame
    // -----------------------------------------------------------------------
    void VulkanContext::renderFrame() {
        if (!m_surfaceReady || !m_focused) return;
        if (!m_pipeline) return;

        vkWaitForFences(m_device, 1, &m_renderResources.inFlightFence, VK_TRUE, UINT64_MAX);
        vkResetFences(m_device, 1, &m_renderResources.inFlightFence);

        // E6 — elapsed seconds since first frame, written into pc.time for
        // every draw so the fragment shader can animate procedural effects.
        const auto now = std::chrono::steady_clock::now();
        if (!m_renderStartInitialised) {
            m_renderStart = now;
            m_renderStartInitialised = true;
        }
        const float elapsedSec =
                std::chrono::duration<float>(now - m_renderStart).count();

        uint32_t imageIndex = 0;
        VkResult r = vkAcquireNextImageKHR(m_device, m_swapchain, UINT64_MAX,
                                           m_renderResources.imageAvailableSemaphore, VK_NULL_HANDLE, &imageIndex);
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) { LOGE("vkAcquireNextImageKHR: %s", vkRes(r).c_str()); return; }

        updateUniformBuffer();

        VkCommandBuffer cmd = m_renderResources.commandBuffers[imageIndex];
        vkResetCommandBuffer(cmd, 0);

        VkCommandBufferBeginInfo bi{};
        bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        vkBeginCommandBuffer(cmd, &bi);

        // Scene pass clears, in attachment order: colour, velocity, depth.
        // Velocity clears to (0, 0) — "no motion" so untouched pixels read
        // as static under the motion-blur shader (E10.4).
        VkClearValue cv[3]{};
        cv[0].color        = {{0.01f, 0.01f, 0.04f, 1.0f}}; // very dark blue-black
        cv[1].color        = {{0.0f, 0.0f, 0.0f, 0.0f}};    // velocity (RG only)
        cv[2].depthStencil = {1.0f, 0};

        // E10.1 — scene pass renders into the offscreen colour image.
        // framebuffers[0] is shared (single offscreen colour + depth);
        // imageIndex is only used for the post pass below.
        VkRenderPassBeginInfo rpi{};
        rpi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        rpi.renderPass = m_renderResources.renderPass;
        rpi.framebuffer = m_renderResources.framebuffers[0];
        rpi.renderArea.extent = m_sel.extent;
        rpi.clearValueCount = 3; rpi.pClearValues = cv;
        vkCmdBeginRenderPass(cmd, &rpi, VK_SUBPASS_CONTENTS_INLINE);

        // E8.2 — bind set 1 (texture) once with the default white 1×1 texture.
        // Descriptor set bindings persist within a command buffer until
        // rebound, so untextured draws downstream inherit this without any
        // per-pipeline rebind. Textured draws (E8.3+) will rebind set 1
        // to their own descriptor before drawing, then the next draw that
        // needs the default can rebind back. All pipelines share
        // m_pipelineLayout so layout-change invalidation doesn't apply.
        if (m_defaultWhiteTexture.isReady()) {
            VkDescriptorSet defaultTexSet = m_defaultWhiteTexture.descriptorSet();
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                    m_pipelineLayout, 1, 1, &defaultTexSet, 0, nullptr);
        }

        // --- Draw star-field first (depth write OFF, drawn behind everything) ---
        if (m_starMesh.isReady() && m_starPipeline != VK_NULL_HANDLE) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, m_starPipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                    m_pipelineLayout, 0, 1, &m_descriptorSet, 0, nullptr);

            // Identity model for stars (they're in world space already)
            PushConstantData starPc{};
            starPc.time = elapsedSec;
            math::Mat4 identity = math::Mat4::identity();
            for (int i = 0; i < 16; ++i) starPc.model[i] = identity.m[i];
            vkCmdPushConstants(cmd, m_pipelineLayout,
                               VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                               0, sizeof(PushConstantData), &starPc);

            m_starMesh.bind(cmd);
            // Draw as points — index count = vertex count for point list
            vkCmdDrawIndexed(cmd, m_starMesh.indexCount(), 1, 0, 0, 0);
        }

        // --- Draw scene objects (submitted by Kotlin via begin/draw/end_scene) ---
        if (!m_drawList.empty() && m_pipeline != VK_NULL_HANDLE) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, m_pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                    m_pipelineLayout, 0, 1, &m_descriptorSet, 0, nullptr);

            uint32_t lastBoundToken = 0;

            for (const auto& draw : m_drawList) {
                uint32_t idx = draw.token - 1;
                if (!m_meshUsed[idx] || !m_meshPool[idx].isReady()) continue;

                // Rebind vertex/index buffers only when mesh changes
                if (draw.token != lastBoundToken) {
                    m_meshPool[idx].bind(cmd);
                    lastBoundToken = draw.token;
                }

                PushConstantData pc{};
                pc.time = elapsedSec;
                if (draw.billboard) {
                    const math::Mat4 billboard = m_camera.billboardMatrix(
                            {draw.center[0], draw.center[1], draw.center[2]},
                            draw.scale);
                    std::memcpy(pc.model, billboard.m, sizeof(float) * 16);
                } else {
                    std::memcpy(pc.model, draw.modelMatrix, sizeof(float) * 16);
                }
                vkCmdPushConstants(cmd, m_pipelineLayout,
                                   VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                                   0, sizeof(PushConstantData), &pc);
                vkCmdDrawIndexed(cmd, m_meshPool[idx].indexCount(), 1, 0, 0, 0);
            }
        }

        // --- Draw textured opaque meshes (E8.3) ---
        // Same opaque pipeline as m_drawList; differs only by binding set 1
        // to the per-draw texture (instead of the frame-default white) and
        // setting pc.textureMode=1 so the fragment samples vUV. After this
        // loop we rebind set 1 back to the default white so subsequent
        // untextured pipelines have a known descriptor (not strictly needed
        // — they don't sample — but cheap and keeps state predictable for
        // future shaders).
        if (!m_texturedDrawList.empty() && m_pipeline != VK_NULL_HANDLE) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, m_pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                    m_pipelineLayout, 0, 1, &m_descriptorSet, 0, nullptr);

            uint32_t lastBoundMesh = 0;
            uint32_t lastBoundTex  = 0;
            for (const auto& draw : m_texturedDrawList) {
                uint32_t mIdx = draw.token - 1;
                uint32_t tIdx = draw.textureToken - 1;
                if (!m_meshUsed[mIdx] || !m_meshPool[mIdx].isReady())   continue;
                if (!m_textureUsed[tIdx] || !m_textureSlots[tIdx].isReady()) continue;

                if (draw.token != lastBoundMesh) {
                    m_meshPool[mIdx].bind(cmd);
                    lastBoundMesh = draw.token;
                }
                if (draw.textureToken != lastBoundTex) {
                    VkDescriptorSet texSet = m_textureSlots[tIdx].descriptorSet();
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                            m_pipelineLayout, 1, 1, &texSet, 0, nullptr);
                    lastBoundTex = draw.textureToken;
                }

                PushConstantData pc{};
                pc.time = elapsedSec;
                pc.textureMode = 1.0f;
                std::memcpy(pc.model, draw.modelMatrix, sizeof(float) * 16);
                std::memcpy(pc.plasmaColor, draw.plasmaColor, sizeof(float) * 4);
                vkCmdPushConstants(cmd, m_pipelineLayout,
                                   VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                                   0, sizeof(PushConstantData), &pc);
                vkCmdDrawIndexed(cmd, m_meshPool[mIdx].indexCount(), 1, 0, 0, 0);
            }

            // Restore default white set 1 for downstream draws.
            if (m_defaultWhiteTexture.isReady()) {
                VkDescriptorSet defaultTexSet = m_defaultWhiteTexture.descriptorSet();
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                        m_pipelineLayout, 1, 1, &defaultTexSet, 0, nullptr);
            }
        }

        // --- Draw system billboards (non-frame) after scene objects ---
        if (!m_systemDrawList.empty() && m_systemPipeline != VK_NULL_HANDLE) {
            bool pipelineBound = false;
            uint32_t lastBoundToken = 0;

            for (const auto& draw : m_systemDrawList) {
                if (draw.objectFrame) continue;

                uint32_t idx = draw.token - 1;
                if (!m_meshUsed[idx] || !m_meshPool[idx].isReady()) continue;

                if (!pipelineBound) {
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, m_systemPipeline);
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                            m_pipelineLayout, 0, 1, &m_descriptorSet, 0, nullptr);
                    pipelineBound = true;
                }

                if (draw.token != lastBoundToken) {
                    m_meshPool[idx].bind(cmd);
                    lastBoundToken = draw.token;
                }

                PushConstantData pc{};
                pc.time = elapsedSec;
                const math::Mat4 billboard = m_camera.billboardMatrix(
                        {draw.center[0], draw.center[1], draw.center[2]}, draw.scale);
                std::memcpy(pc.model, billboard.m, sizeof(float) * 16);
                std::memcpy(pc.tint, draw.tint, sizeof(float) * 4);
                vkCmdPushConstants(cmd, m_pipelineLayout,
                                   VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                                   0, sizeof(PushConstantData), &pc);
                vkCmdDrawIndexed(cmd, m_meshPool[idx].indexCount(), 1, 0, 0, 0);
            }
        }

        // --- Draw translucent meshes with alpha-blend pipeline (E1.2) ---
        // Slotted between system billboards and plasma so additive plasma VFX
        // still draws "on top" of soft-edged translucent geometry (nebulae,
        // shield dome, etc.).
        if (!m_translucentDrawList.empty() && m_translucentPipeline != VK_NULL_HANDLE) {
            bool pipelineBound = false;
            uint32_t lastBoundToken = 0;
            for (const auto& draw : m_translucentDrawList) {
                uint32_t idx = draw.token - 1;
                if (!m_meshUsed[idx] || !m_meshPool[idx].isReady()) continue;
                if (!pipelineBound) {
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, m_translucentPipeline);
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                            m_pipelineLayout, 0, 1, &m_descriptorSet, 0, nullptr);
                    pipelineBound = true;
                }
                if (draw.token != lastBoundToken) {
                    m_meshPool[idx].bind(cmd);
                    lastBoundToken = draw.token;
                }
                PushConstantData pc{};
                pc.time = elapsedSec;
                std::memcpy(pc.model, draw.modelMatrix, sizeof(float) * 16);
                // E3.1 — propagate material flags (cmd.tint set by
                // drawTranslucentMesh). Fragment shader reads pc.tint.y/z.
                std::memcpy(pc.tint, draw.tint, sizeof(float) * 4);
                vkCmdPushConstants(cmd, m_pipelineLayout,
                                   VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                                   0, sizeof(PushConstantData), &pc);
                vkCmdDrawIndexed(cmd, m_meshPool[idx].indexCount(), 1, 0, 0, 0);
            }
        }

        // --- Draw additive 3D meshes (E7) ---
        // Fireballs, plasma beams, lightning. Same ONE/ONE blend as plasma
        // billboards but accepts arbitrary mesh + model matrix. Slotted
        // between translucent and plasma billboards: above translucent so
        // the additive overlay reads on top of soft alpha-blended layers,
        // below plasma billboards so pure-overlay billboards (depth-test
        // off) sit on top of depth-tested additive meshes.
        if (!m_additiveDrawList.empty() && m_additivePipeline != VK_NULL_HANDLE) {
            bool pipelineBound = false;
            uint32_t lastBoundToken = 0;
            for (const auto& draw : m_additiveDrawList) {
                uint32_t idx = draw.token - 1;
                if (!m_meshUsed[idx] || !m_meshPool[idx].isReady()) continue;
                if (!pipelineBound) {
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, m_additivePipeline);
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                            m_pipelineLayout, 0, 1, &m_descriptorSet, 0, nullptr);
                    pipelineBound = true;
                }
                if (draw.token != lastBoundToken) {
                    m_meshPool[idx].bind(cmd);
                    lastBoundToken = draw.token;
                }
                PushConstantData pc{};
                pc.time = elapsedSec;
                std::memcpy(pc.model, draw.modelMatrix, sizeof(float) * 16);
                // E7 — tint.x/y/z reserved for plasma soft-fade / nebula / hex;
                // .w distinguishes additive sub-materials (1.0=plain, 2.0=fire,
                // set by drawAdditiveMesh from the `material` param).
                std::memcpy(pc.tint, draw.tint, sizeof(float) * 4);
                std::memcpy(pc.plasmaColor, draw.plasmaColor, sizeof(float) * 4);
                vkCmdPushConstants(cmd, m_pipelineLayout,
                                   VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                                   0, sizeof(PushConstantData), &pc);
                vkCmdDrawIndexed(cmd, m_meshPool[idx].indexCount(), 1, 0, 0, 0);
            }
        }

        // --- Draw plasma bolts with additive-blend pipeline ---
        if (!m_plasmaDrawList.empty() && m_plasmaPipeline != VK_NULL_HANDLE) {
            bool pipelineBound = false;
            uint32_t lastBoundToken = 0;
            for (const auto& draw : m_plasmaDrawList) {
                uint32_t idx = draw.token - 1;
                if (!m_meshUsed[idx] || !m_meshPool[idx].isReady()) continue;
                if (!pipelineBound) {
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, m_plasmaPipeline);
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                            m_pipelineLayout, 0, 1, &m_descriptorSet, 0, nullptr);
                    pipelineBound = true;
                }
                if (draw.token != lastBoundToken) {
                    m_meshPool[idx].bind(cmd);
                    lastBoundToken = draw.token;
                }
                PushConstantData pc{};
                pc.time = elapsedSec;
                // E5.2 — plasma uses non-uniform (scaleH=draw.scale, scaleV).
                const math::Mat4 billboard = m_camera.billboardMatrix(
                        {draw.center[0], draw.center[1], draw.center[2]},
                        draw.scale, draw.scaleV);
                std::memcpy(pc.model, billboard.m, sizeof(float) * 16);
                // E2.1 — flag plasma fragment shader to apply radial soft-fade.
                // Project's plasma billboards use quad.gltf (corners at ±1 in X-Z).
                // Fragment shader maps length(vLocalXZ) → alpha so the visible
                // glow inscribes the quad and corners go transparent.
                pc.tint[0] = 1.0f;
                // E5.1 — per-billboard colour tint, multiplied into the heat-ramp
                // result inside the plasma fragment branch. Default white at the
                // Kotlin layer preserves the E4 look; non-white tints recolour
                // individual flash events (cyan ENERGY pickup, orange-red AoE).
                std::memcpy(pc.plasmaColor, draw.plasmaColor, sizeof(float) * 4);
                vkCmdPushConstants(cmd, m_pipelineLayout,
                                   VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                                   0, sizeof(PushConstantData), &pc);
                vkCmdDrawIndexed(cmd, m_meshPool[idx].indexCount(), 1, 0, 0, 0);
            }
        }

        // --- Draw particle batches (E9) ---
        // Two passes: additive batches first, then alpha-textured. Each
        // pass uploads its concatenated staging array to its instance
        // buffer in one memcpy, binds the matching pipeline once, then
        // walks the batches issuing per-batch instanced draws (one mesh
        // bind per token change, one descriptor-set bind per texture
        // change). Particles render between plasma billboards and
        // selection frames so they overlay translucent + additive but sit
        // beneath UI frames.
        if ((!m_particleAdditiveStaging.empty() || !m_particleAlphaStaging.empty())
            && (m_particleAdditivePipeline != VK_NULL_HANDLE || m_particleAlphaPipeline != VK_NULL_HANDLE)) {
            // Upload staging → mapped instance buffers.
            if (!m_particleAdditiveStaging.empty() && m_particleAdditiveInstanceMapped) {
                std::memcpy(m_particleAdditiveInstanceMapped,
                            m_particleAdditiveStaging.data(),
                            m_particleAdditiveStaging.size() * sizeof(float));
            }
            if (!m_particleAlphaStaging.empty() && m_particleAlphaInstanceMapped) {
                std::memcpy(m_particleAlphaInstanceMapped,
                            m_particleAlphaStaging.data(),
                            m_particleAlphaStaging.size() * sizeof(float));
            }

            auto runPass = [&](int32_t mode, VkPipeline pipeline,
                               VkBuffer instanceBuffer) {
                if (pipeline == VK_NULL_HANDLE || instanceBuffer == VK_NULL_HANDLE) return;
                bool pipelineBound = false;
                uint32_t lastBoundMesh = 0;
                uint32_t lastBoundTex  = 0;
                for (const auto& batch : m_particleBatches) {
                    if (batch.mode != mode) continue;
                    if (batch.count == 0) continue;
                    uint32_t mIdx = batch.meshToken - 1;
                    if (!m_meshUsed[mIdx] || !m_meshPool[mIdx].isReady()) continue;

                    if (!pipelineBound) {
                        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
                        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                                m_pipelineLayout, 0, 1, &m_descriptorSet, 0, nullptr);
                        pipelineBound = true;
                    }

                    if (batch.meshToken != lastBoundMesh) {
                        m_meshPool[mIdx].bind(cmd);
                        lastBoundMesh = batch.meshToken;
                    }

                    // Texture: alpha pipeline samples uTex; additive uses
                    // the engine's default white at set 1 (already bound
                    // at frame start) when textureToken == 0.
                    VkDescriptorSet wantTexSet = VK_NULL_HANDLE;
                    if (batch.textureToken != 0 && batch.textureToken <= kMaxTextures) {
                        uint32_t tIdx = batch.textureToken - 1;
                        if (m_textureUsed[tIdx] && m_textureSlots[tIdx].isReady()) {
                            wantTexSet = m_textureSlots[tIdx].descriptorSet();
                        }
                    }
                    if (wantTexSet == VK_NULL_HANDLE && m_defaultWhiteTexture.isReady()) {
                        wantTexSet = m_defaultWhiteTexture.descriptorSet();
                    }
                    if (wantTexSet != VK_NULL_HANDLE && batch.textureToken != lastBoundTex) {
                        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                                m_pipelineLayout, 1, 1, &wantTexSet, 0, nullptr);
                        lastBoundTex = batch.textureToken;
                    }

                    // Bind the instance buffer at binding 1 with the
                    // batch's float offset converted to bytes.
                    VkDeviceSize byteOffset = batch.bufferOffsetFloats * sizeof(float);
                    VkBuffer     instBuf    = instanceBuffer;
                    vkCmdBindVertexBuffers(cmd, 1, 1, &instBuf, &byteOffset);

                    PushConstantData pc{};
                    pc.time        = elapsedSec;
                    // Identity model — vertex shader builds world position
                    // from instance pos + camera right/up directly.
                    pc.model[0]  = 1.0f; pc.model[5]  = 1.0f;
                    pc.model[10] = 1.0f; pc.model[15] = 1.0f;
                    // textureMode = 1 for alpha-textured branch (sample
                    // uTex), 0 for additive (heat-ramp).
                    pc.textureMode = (mode == 1) ? 1.0f : 0.0f;
                    pc.plasmaColor[0] = 1.0f; pc.plasmaColor[1] = 1.0f;
                    pc.plasmaColor[2] = 1.0f; pc.plasmaColor[3] = 1.0f;
                    vkCmdPushConstants(cmd, m_pipelineLayout,
                                       VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                                       0, sizeof(PushConstantData), &pc);

                    vkCmdDrawIndexed(cmd, m_meshPool[mIdx].indexCount(),
                                     batch.count, 0, 0, 0);
                }

                // Restore the engine's default white texture set 1 and
                // re-bind binding 1 to the additive instance buffer so
                // downstream pipelines (frame draws) start from a known
                // state. Both are cheap and keep the command-buffer
                // post-conditions predictable.
                if (pipelineBound && m_defaultWhiteTexture.isReady() && lastBoundTex != 0) {
                    VkDescriptorSet defaultTexSet = m_defaultWhiteTexture.descriptorSet();
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                            m_pipelineLayout, 1, 1, &defaultTexSet, 0, nullptr);
                }
            };
            runPass(0, m_particleAdditivePipeline, m_particleAdditiveInstanceBuffer);
            runPass(1, m_particleAlphaPipeline,    m_particleAlphaInstanceBuffer);
        }

        // --- Draw selection frames with constant-width line pipeline ---
        if (!m_systemDrawList.empty() &&
            m_framePipeline != VK_NULL_HANDLE &&
            m_frameLineMesh.isReady() && m_frameLineMeshEnemy.isReady()) {

            const float W  = static_cast<float>(m_sel.extent.width);
            const float H  = static_cast<float>(m_sel.extent.height);
            const math::Mat4 vp = m_camera.viewProjection(computeSceneFarClip());
            bool pipelineBound = false;

            for (const auto& draw : m_systemDrawList) {
                if (!draw.objectFrame) continue;

                math::Mat4 frame{};

                if (draw.targetToken > 0) {
                    // Object frame: project mesh vertices to get screen bounds
                    float sb[7]{};
                    if (!projectMeshBounds(draw.targetToken, draw.modelMatrix, draw.padding, sb))
                        continue;
                    frame = m_camera.frameMatrixForScreenBounds(sb[2], sb[3], sb[4], sb[5], W, H);
                } else {
                    // Gameplay frame: project the stored local points to screen bounds
                    const std::vector<math::Vec3>& pts = draw.framePoints;
                    if (pts.empty()) continue;
                    float pb[5]{};
                    if (!projectLocalPointsToBounds(vp, pts, draw.modelMatrix, draw.padding, W, H, pb))
                        continue;
                    frame = m_camera.frameMatrixForScreenBounds(pb[0], pb[1], pb[2], pb[3], W, H, pb[4]);
                }

                if (!pipelineBound) {
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, m_framePipeline);
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                            m_pipelineLayout, 0, 1, &m_descriptorSet, 0, nullptr);
                    pipelineBound = true;
                }

                // Bind per-draw: enemy (tint.a > 0.5) gets red mesh, allied gets green.
                const bool isEnemy = (draw.tint[3] > 0.5f);
                (isEnemy ? m_frameLineMeshEnemy : m_frameLineMesh).bind(cmd);

                const float lw = (m_wideLines && draw.scale > 1.0f) ? draw.scale : 1.0f;
                vkCmdSetLineWidth(cmd, lw);

                PushConstantData pc{};
                pc.time = elapsedSec;
                std::memcpy(pc.model, frame.m, sizeof(float) * 16);
                std::memcpy(pc.tint, draw.tint, sizeof(float) * 4);
                vkCmdPushConstants(cmd, m_pipelineLayout,
                                   VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                                   0, sizeof(PushConstantData), &pc);
                vkCmdDrawIndexed(cmd, 16, 1, 0, 0, 0); // 8 line segments (4 corner brackets)
            }
        }

        vkCmdEndRenderPass(cmd);

        // E10.1 — post-process pass. Begins after scene pass ended; the
        // scene pass's finalLayout transition (UNDEFINED →
        // SHADER_READ_ONLY_OPTIMAL) is implicit, so the offscreen colour
        // is ready to be sampled. Fullscreen triangle (3 vertices, no
        // vertex bindings) reads scene colour and writes to swapchain.
        // After E10.4 this becomes the motion-blur step.
        if (m_postPipeline != VK_NULL_HANDLE &&
            m_renderResources.postRenderPass != VK_NULL_HANDLE &&
            imageIndex < m_renderResources.postFramebuffers.size() &&
            m_renderResources.postFramebuffers[imageIndex] != VK_NULL_HANDLE) {
            VkClearValue postClear{};
            postClear.color = {{0.0f, 0.0f, 0.0f, 1.0f}};
            VkRenderPassBeginInfo postRpi{};
            postRpi.sType            = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
            postRpi.renderPass       = m_renderResources.postRenderPass;
            postRpi.framebuffer      = m_renderResources.postFramebuffers[imageIndex];
            postRpi.renderArea.extent= m_sel.extent;
            postRpi.clearValueCount  = 1;
            postRpi.pClearValues     = &postClear;
            vkCmdBeginRenderPass(cmd, &postRpi, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, m_postPipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                    m_postPipelineLayout, 0, 1, &m_postDescriptorSet, 0, nullptr);
            vkCmdDraw(cmd, 3, 1, 0, 0);
            vkCmdEndRenderPass(cmd);
        }

        vkEndCommandBuffer(cmd);

        VkPipelineStageFlags ws = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo si{};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.waitSemaphoreCount = 1; si.pWaitSemaphores = &m_renderResources.imageAvailableSemaphore;
        si.pWaitDstStageMask = &ws; si.commandBufferCount = 1; si.pCommandBuffers = &cmd;
        si.signalSemaphoreCount = 1; si.pSignalSemaphores = &m_renderResources.renderFinishedSemaphore;
        vkQueueSubmit(m_graphicsQueue, 1, &si, m_renderResources.inFlightFence);

        VkPresentInfoKHR pi{};
        pi.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        pi.waitSemaphoreCount = 1; pi.pWaitSemaphores = &m_renderResources.renderFinishedSemaphore;
        pi.swapchainCount = 1; pi.pSwapchains = &m_swapchain; pi.pImageIndices = &imageIndex;
        vkQueuePresentKHR(m_graphicsQueue, &pi);
    }

} // namespace station
