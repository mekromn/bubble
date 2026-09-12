cmake_minimum_required(VERSION 3.22.1)
project(bubble_ahb LANGUAGES C CXX)

add_library(bubble-ahb SHARED bubble_ahb.cpp)
target_compile_features(bubble-ahb PRIVATE cxx_std_17)
target_compile_options(bubble-ahb PRIVATE -fvisibility=hidden)

find_library(log-lib log)
find_library(android-lib android)
find_library(media-lib mediandk)
find_library(nativewindow-lib nativewindow)

target_link_libraries(
    bubble-ahb
    PRIVATE
    ${android-lib}
    ${media-lib}
    ${nativewindow-lib}
    ${log-lib}
)

# Optimize only our bridge. No fast-math, resolution, pixel format or Gecko-pref changes.
option(BUBBLE_OPTIMIZED "Optimize the selected relay without debug code generation" OFF)
if(BUBBLE_OPTIMIZED)
    target_compile_options(bubble-ahb PRIVATE -O3 -flto=thin -fno-omit-frame-pointer)
    target_link_options(bubble-ahb PRIVATE -flto=thin -Wl,--gc-sections -Wl,-O2)
endif()
