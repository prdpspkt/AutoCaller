cmake_minimum_required(VERSION 3.22.1)
project(whisperjni)

set(CMAKE_CXX_STANDARD 17)

# whisper.cpp is added as a git submodule at app/src/main/cpp/whisper.cpp
#   git submodule add https://github.com/ggml-org/whisper.cpp app/src/main/cpp/whisper.cpp
# Build it as static libs so everything ends up inside libwhisperjni.so.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp
                 ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp)

add_library(whisperjni SHARED whisper_jni.cpp)

target_link_libraries(whisperjni
        whisper
        android
        log)
