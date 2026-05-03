#pragma once

#include <vulkan/vulkan.h>
#include <vector>

// Platform abstraction: knows how to create a VkSurfaceKHR from a native handle,
// and which instance extensions are required.
// Implemented per-platform in PlatformAndroid.cpp / PlatformWindows.cpp

namespace station {

    struct PlatformSurface {
        // Create a VkSurfaceKHR from a native window handle.
        // handle is ANativeWindow* on Android, HWND on Windows.
        static bool create(VkInstance instance, void* handle, VkSurfaceKHR& outSurface);

        // Instance extensions required to create a surface on this platform.
        static std::vector<const char*> requiredInstanceExtensions();
    };

} // namespace station