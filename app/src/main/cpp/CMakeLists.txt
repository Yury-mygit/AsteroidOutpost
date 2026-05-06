cmake_minimum_required(VERSION 3.22.1)

project(stationcore)

add_library(
    stationcore
    SHARED

    # JNI entry point
    android/EngineJni.cpp

    # Engine C API
    engine/engine_api.cpp

    # Platform surface (Android)
    engine/platform/PlatformAndroid.cpp

    # Engine internals
    engine/VulkanContext.cpp
    engine/RenderResources.cpp
    engine/Mesh.cpp
    engine/ShipMesh.cpp
    engine/Camera.cpp
    engine/GltfLoader.cpp
    engine/Texture.cpp
)

target_include_directories(
    stationcore
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/engine
    ${CMAKE_CURRENT_SOURCE_DIR}/engine/platform
    ${CMAKE_CURRENT_SOURCE_DIR}/engine/thirdparty
)

find_library(android-lib android)
find_library(log-lib     log)
find_library(vulkan-lib  vulkan)

target_link_libraries(
    stationcore
    PRIVATE
    ${android-lib}
    ${log-lib}
    ${vulkan-lib}
)