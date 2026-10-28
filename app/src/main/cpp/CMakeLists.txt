cmake_minimum_required(VERSION 3.22)
project("ataglanceplus")

set(CMAKE_CXX_STANDARD 17)
add_compile_options(-fdata-sections -ffunction-sections -fno-exceptions -fno-rtti -fvisibility=hidden)

# BoringSSL (prefab package io.github.vvb2060.ndk:boringssl) provides SPAKE2, HKDF and AES-GCM.
find_package(boringssl REQUIRED CONFIG)

add_library(adbpairing SHARED adb_pairing.cpp)
target_link_libraries(adbpairing log boringssl::crypto_static)
