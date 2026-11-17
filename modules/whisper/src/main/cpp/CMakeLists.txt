cmake_minimum_required(VERSION 4.1.2)
project(whisper_jni LANGUAGES C CXX)
include(FetchContent)

if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif ()

if (${CMAKE_BUILD_TYPE} STREQUAL "Debug")
    set(IS_DEBUG TRUE)
    set(IS_RELEASE FALSE)
else ()
    set(IS_DEBUG FALSE)
    set(IS_RELEASE TRUE)
endif ()

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS ${IS_DEBUG} CACHE BOOL "" FORCE)

set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "Enable Vulkan" FORCE)
if (CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
    set(GGML_AVX OFF CACHE BOOL "" FORCE)
    set(GGML_AVX2 OFF CACHE BOOL "" FORCE)
    set(GGML_AVX_VNNI OFF CACHE BOOL "" FORCE)
    set(GGML_FMA OFF CACHE BOOL "" FORCE)
    set(GGML_F16C OFF CACHE BOOL "" FORCE)
    set(GGML_BMI2 OFF CACHE BOOL "" FORCE)
endif ()
set(GGML_STATIC ${IS_RELEASE} CACHE BOOL "" FORCE)
set(GGML_LTO ${IS_RELEASE} CACHE BOOL "" FORCE)

option(FERMATA_WHISPER_VULKAN "Build the optional Whisper Vulkan backend" ON)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if (CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH armv8-a CACHE STRING "" FORCE)
endif ()

if (FERMATA_WHISPER_VULKAN AND CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan" FORCE)
    FetchContent_Declare(Vulkan-Headers
            GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers
            GIT_TAG 6802bb4733b63ed5efd3adb308a6c885ef180ea1
            SOURCE_SUBDIR headers-only
    )
    # Pinned for reproducible builds (main @ 2026-11-04)
    FetchContent_Declare(SPIRV-Headers GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers
            GIT_TAG 6efe5ae6b7c6ad29da3f2cc3f164b9c3d72b78b1
            OVERRIDE_FIND_PACKAGE
    )
    FetchContent_MakeAvailable(Vulkan-Headers SPIRV-Headers)
    set(Vulkan_INCLUDE_DIR "${vulkan-headers_SOURCE_DIR}/include" CACHE PATH "" FORCE)
    include_directories("${Vulkan_INCLUDE_DIR}" "${SPIRV-Headers_SOURCE_DIR}/include")
    set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/linux-x86_64/glslc" CACHE FILEPATH "" FORCE)
    set(Vulkan_GLSLANG_VALIDATOR_EXECUTABLE "${Vulkan_GLSLC_EXECUTABLE}" CACHE FILEPATH "" FORCE)
    set(Vulkan_glslangValidator_FOUND TRUE CACHE BOOL "" FORCE)
    find_library(Vulkan_LIBRARY vulkan)
endif ()

find_library(LOG_LIB log)

FetchContent_Declare(
        whispercpp
        GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
        # Pinned for reproducible builds (master @ 2026-11-05, 1.9.5-dev)
        GIT_TAG 727d3e7666485e12450746b523bfb4e7f057cc5e
)
FetchContent_MakeAvailable(whispercpp)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni PRIVATE whisper ${LOG_LIB})
if (GGML_VULKAN)
    target_compile_definitions(whisper_jni PRIVATE FERMATA_WHISPER_HAS_VULKAN)
endif ()
target_include_directories(whisper_jni PRIVATE ${whispercpp_SOURCE_DIR})


if (IS_DEBUG)
    target_compile_options(whisper_jni PRIVATE -g -O0 -fno-omit-frame-pointer)
else ()
    target_compile_options(whisper_jni PRIVATE -O3 -ffast-math -flto)
    target_compile_options(whisper_jni PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
    target_compile_options(whisper_jni PRIVATE -ffunction-sections -fdata-sections)

    target_link_options(whisper_jni PRIVATE -flto)
    target_link_options(whisper_jni PRIVATE -Wl,--gc-sections)
    target_link_options(whisper_jni PRIVATE -Wl,--exclude-libs,ALL)
endif ()
