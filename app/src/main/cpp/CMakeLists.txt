cmake_minimum_required(VERSION 3.22.1)
project("greyrecon_native")

add_library(ipneigh SHARED ipneigh.cpp)

find_library(log-lib log)
target_link_libraries(ipneigh ${log-lib})

# LD_PRELOAD shim letting the terminal exec user-supplied binaries -- see greyrecon_exec.c for the
# full explanation. -fvisibility=hidden so only the intended execve/execv/execvp/execvpe symbols are
# exported for interposition, not the internal helper functions.
add_library(greyrecon_exec SHARED greyrecon_exec.c)
target_compile_options(greyrecon_exec PRIVATE -fvisibility=hidden)
