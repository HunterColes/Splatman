# splat-sfm native front end (#225): C++17, OpenCV from Maven Central through prefab (OpenCV::opencv_java4).
cmake_minimum_required(VERSION 3.22.1)
project(splat_sfm LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(OpenCV REQUIRED CONFIG)

add_library(splat_sfm SHARED jni_bridge.cpp front_end.cpp)
target_compile_options(splat_sfm PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_libraries(splat_sfm PRIVATE OpenCV::opencv_java4)
# 16 KB page devices (Android 15+).
target_link_options(splat_sfm PRIVATE "-Wl,-z,max-page-size=16384")
