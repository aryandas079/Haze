cmake_minimum_required(VERSION 3.22.1)

project("hazeshield")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    hazeshield
    SHARED
    domain_matcher.cc
    rule_matcher.cc
    haze_shield_core.cc
    jni_bridge.cc
)

find_library(
    log-lib
    log
)

target_link_libraries(
    hazeshield
    ${log-lib}
)
