#include "Platform.h"

#include <vulkan/vulkan_android.h>
#include <android/native_window.h>
#include <android/log.h>

namespace station {

    bool PlatformSurface::create(VkInstance instance, void* handle, VkSurfaceKHR& outSurface) {
        VkAndroidSurfaceCreateInfoKHR ci{};
        ci.sType  = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        ci.window = static_cast<ANativeWindow*>(handle);

        VkResult r = vkCreateAndroidSurfaceKHR(instance, &ci, nullptr, &outSurface);
        if (r != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_ERROR, "stationcore",
                                "vkCreateAndroidSurfaceKHR failed: %d", r);
            return false;
        }
        return true;
    }

    std::vector<const char*> PlatformSurface::requiredInstanceExtensions() {
        return {
                VK_KHR_SURFACE_EXTENSION_NAME,
                VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
        };
    }

} // namespace station