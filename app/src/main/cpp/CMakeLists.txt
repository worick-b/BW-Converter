cmake_minimum_required(VERSION 3.22.1)
project("abw_ui")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(abw_ui SHARED
    abw_ui.cpp
    nanovg/nanovg.c
)

target_include_directories(abw_ui PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/nanovg
)

target_compile_options(abw_ui PRIVATE -O3 -fvisibility=hidden)

target_link_libraries(abw_ui
    GLESv3
    EGL
    android
    log
)
