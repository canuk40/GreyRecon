# CMake build for the vendored termux.c PTY-allocation JNI code, replacing the original Android.mk/
# ndk-build setup -- ndk-build (GNU Make-based) chokes on spaces in the project path on Windows
# ("OPEN IN ANDROID STUDIO - GreyRecon"), while AGP's CMake integration (already used by GreyRecon's
# own native code) handles it fine. Same source file, same flags, different build driver.
cmake_minimum_required(VERSION 3.22.1)
project("libtermux")

add_library(termux SHARED termux.c)

target_compile_options(termux PRIVATE -std=c11 -Wall -Wextra -Werror -Os -fno-stack-protector)
target_link_options(termux PRIVATE -Wl,--gc-sections)
