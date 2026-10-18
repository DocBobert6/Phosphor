cmake_minimum_required(VERSION 3.22.1)
project(phosphor)

add_library(phosphor SHARED phosphor.cpp)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)

target_link_libraries(phosphor
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
)

target_compile_features(phosphor PRIVATE cxx_std_17)
