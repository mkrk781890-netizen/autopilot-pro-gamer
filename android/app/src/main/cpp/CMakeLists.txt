cmake_minimum_required(VERSION 3.22.1)

project("autopilot_engine" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(
    autopilot_engine
    SHARED
    native-lib.cpp
)

target_compile_options(
    autopilot_engine
    PRIVATE
    -O3
    -ffast-math
    -Wall
    -Wextra
    -Wno-unused-parameter
)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    autopilot_engine
    PRIVATE
    ${jnigraphics-lib}
    ${log-lib}
    ${android-lib}
    dl
)
