cmake_minimum_required(VERSION 3.22.1)
project("worickb_ui")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SHAWARMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ShawarmaUI")

add_library(worickb_ui SHARED
    worickb_ui.cpp
    "${SHAWARMA_DIR}/third_party/nanovg/nanovg.c"
)

target_include_directories(worickb_ui PRIVATE
    "${SHAWARMA_DIR}/include"
    "${SHAWARMA_DIR}/third_party/nanovg"
)

target_compile_options(worickb_ui PRIVATE -O3 -fvisibility=hidden)

target_link_libraries(worickb_ui
    GLESv3
    EGL
    android
    log
)
