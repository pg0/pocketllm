cmake_minimum_required(VERSION 3.22.1)

project(pocketllm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# app/src/main/cpp -> repo root
set(LLAMA_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")

if(NOT EXISTS "${LLAMA_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR
        "third_party/llama.cpp is empty. Run:\n"
        "  git submodule update --init --recursive")
endif()

# --- llama.cpp build configuration -------------------------------------------
# We are consumed as a subproject, so LLAMA_STANDALONE is OFF and the tools /
# examples / tests trees are already skipped. LLAMA_BUILD_MTMD is the documented
# hook for building tools/mtmd as a standalone library without dragging in the
# rest of tools/ (and without llama-common, which mtmd refuses to link against).
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD     ON  CACHE BOOL "" FORCE)

# Video input shells out to ffmpeg, which does not exist on Android. The
# upstream CMake already forces this off when LLAMA_SUBPROCESS is off, but being
# explicit keeps the intent readable.
set(MTMD_VIDEO           OFF CACHE BOOL "" FORCE)

# Static link everything into libpocketllm.so: one .so to package, no
# System.loadLibrary ordering to get right.
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)

# -march=native is meaningless when cross-compiling for the phone.
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)

# ...but turning it off is not free: with GGML_NATIVE off and neither
# GGML_CPU_ARM_ARCH nor GGML_CPU_ALL_VARIANTS set, ggml-cpu appends *no* -march
# at all and inherits the NDK's baseline armv8-a. That baseline has no SDOT, so
# every quantised matmul falls back to scalar-ish NEON. Measured on a Galaxy S23
# Ultra before this line existed: 1.3 tok/s decode and a 44 s prefill for a
# 40-token prompt.
#
# armv8.2-a+dotprod is the safe floor: Cortex-A75/A55 (2018) and everything
# since has it, which covers every phone that can hold a 3 GB model anyway.
# +fp16 enables half-precision vector arithmetic from the same architecture
# level. i8mm is deliberately *not* here -- it is armv8.6 and would SIGILL on
# pre-2021 silicon; getting it safely needs runtime dispatch (see roadmap).
set(GGML_CPU_ARM_ARCH    "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

# The NDK ships no libomp for the app ABI; leaving OpenMP on is the classic
# "cannot find -lomp" Android build failure.
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

# --- our JNI library ----------------------------------------------------------
add_library(pocketllm SHARED pocketllm_jni.cpp)

target_link_libraries(pocketllm
    PRIVATE
        llama
        mtmd
        android
        log)

target_compile_options(pocketllm PRIVATE -O3 -fvisibility=hidden)

# Android 15+ devices can use 16 KB memory pages; Play requires shared libraries
# to be aligned for them. Harmless on 4 KB devices.
target_link_options(pocketllm PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
