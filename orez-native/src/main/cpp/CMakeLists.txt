cmake_minimum_required(VERSION 3.22.1)
project(mangalens_orez_native)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# Android loads packaged libraries through the app linker; desktop directory
# discovery does not find them. Link/register the CPU backend directly.
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

# Debug APKs are the installable delivery build for this project. Keep native
# libraries optimized and strip native debug info to avoid hundreds of MB of
# unneeded unoptimized C++ output in each ABI split.
set(CMAKE_C_FLAGS_DEBUG "-O2 -g0" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g0" CACHE STRING "" FORCE)

include(FetchContent)
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG 4da6337767f973e2b4d0797e5b323d77d8565e4a
)
FetchContent_MakeAvailable(llama_cpp)

add_library(mangalens_orez_native SHARED orez_native.cpp)
target_include_directories(mangalens_orez_native PRIVATE ${llama_cpp_SOURCE_DIR}/include)
target_link_libraries(mangalens_orez_native PRIVATE llama log android)
