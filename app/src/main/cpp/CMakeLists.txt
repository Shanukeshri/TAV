cmake_minimum_required(VERSION 3.14)
project("tav_llama")

set(CMAKE_CXX_STANDARD 17)

# Disable everything we don't need from llama.cpp
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(tav_llama SHARED tav_llama_jni.cpp)

target_include_directories(tav_llama PRIVATE
    llama.cpp/include
    llama.cpp/common
    llama.cpp/ggml/include
)

target_link_libraries(tav_llama
    llama
    llama-common
    log
)
