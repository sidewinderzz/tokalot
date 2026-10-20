cmake_minimum_required(VERSION 3.22.1)
project(offlineflow_jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Keep whisper.cpp lean: static libs, no examples/tests/OpenMP.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.7.5
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper)

# 1. offlineflow: plain ARMv8, runs on every 64-bit phone. (Cross-compiling, ggml targets no newer
#    CPU features on its own.)
add_library(offlineflow SHARED whisper_jni.cpp)
target_link_libraries(offlineflow whisper android log)

# 2. offlineflow_fast: the same, built for ARMv8.2 with dot product and half-precision maths, which
#    nearly every phone from 2018 on has. Whisper's matrix maths runs several times faster with them.
#    It would crash an older phone, so WhisperBridge only loads it after checking the CPU has both.
#    ggml can only be configured once per CMake project, so this copy is built as a separate project.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    include(ExternalProject)
    set(FAST_LIBS ${CMAKE_CURRENT_BINARY_DIR}/whisper_fast/lib)
    ExternalProject_Add(whisper_fast
        SOURCE_DIR ${whisper_SOURCE_DIR}
        BINARY_DIR ${CMAKE_CURRENT_BINARY_DIR}/whisper_fast/build
        CMAKE_ARGS
            -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
            -DANDROID_ABI=${ANDROID_ABI}
            -DANDROID_PLATFORM=${ANDROID_PLATFORM}
            -DANDROID_STL=${ANDROID_STL}
            -DCMAKE_BUILD_TYPE=Release
            -DCMAKE_POSITION_INDEPENDENT_CODE=ON
            -DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=${FAST_LIBS}
            -DBUILD_SHARED_LIBS=OFF
            -DWHISPER_BUILD_TESTS=OFF
            -DWHISPER_BUILD_EXAMPLES=OFF
            -DWHISPER_BUILD_SERVER=OFF
            -DGGML_OPENMP=OFF
            -DGGML_NATIVE=OFF
            -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16
        INSTALL_COMMAND ""
        BUILD_BYPRODUCTS
            ${FAST_LIBS}/libwhisper.a ${FAST_LIBS}/libggml.a ${FAST_LIBS}/libggml-cpu.a ${FAST_LIBS}/libggml-base.a
    )
    add_library(offlineflow_fast SHARED whisper_jni.cpp)
    add_dependencies(offlineflow_fast whisper_fast)
    target_include_directories(offlineflow_fast PRIVATE ${whisper_SOURCE_DIR}/include ${whisper_SOURCE_DIR}/ggml/include)
    target_link_libraries(offlineflow_fast
        -Wl,--start-group
        ${FAST_LIBS}/libwhisper.a ${FAST_LIBS}/libggml.a ${FAST_LIBS}/libggml-cpu.a ${FAST_LIBS}/libggml-base.a
        -Wl,--end-group
        android log)
endif()
