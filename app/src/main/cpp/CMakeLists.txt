cmake_minimum_required(VERSION 3.22.1)
project(bubble_ahb LANGUAGES C CXX)

add_library(bubble-ahb SHARED bubble_ahb.cpp)
target_compile_features(bubble-ahb PRIVATE cxx_std_17)
target_compile_options(bubble-ahb PRIVATE -fvisibility=hidden)

find_library(log-lib log)
find_library(android-lib android)
find_library(media-lib mediandk)
find_library(nativewindow-lib nativewindow)
find_library(dl-lib dl)

target_link_libraries(
    bubble-ahb
    PRIVATE
    ${android-lib}
    ${media-lib}
    ${nativewindow-lib}
    ${dl-lib}
    ${log-lib}
)
