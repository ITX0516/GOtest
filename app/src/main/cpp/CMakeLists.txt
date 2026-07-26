# CMakeLists.txt for libweiqi_engine.so
#
# 两种模式：
#   1. PROCESS 模式（默认）：子进程启动引擎可执行文件（katago/leelaz），
#      通过 stdin/stdout 收发 GTP。引擎二进制由 CI 编译放入 assets/。
#   2. DYLIB 模式：通过 dlopen 动态加载 libkatago.so / libleelaz.so。

cmake_minimum_required(VERSION 3.22.1)

project(weiqi_engine LANGUAGES CXX)

set(WEIQI_ENGINE_MODE "PROCESS" CACHE STRING "Engine integration mode: PROCESS or DYLIB")
set_property(CACHE WEIQI_ENGINE_MODE PROPERTY STRINGS PROCESS DYLIB)

message(STATUS "Weiqi engine mode: ${WEIQI_ENGINE_MODE}")

set(WEIQI_CORE_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/jni_bridge.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/gtp_engine.cpp
)

set(WEIQI_CORE_HEADERS
    ${CMAKE_CURRENT_SOURCE_DIR}/gtp_engine.h
)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(weiqi_engine SHARED ${WEIQI_CORE_SOURCES} ${WEIQI_CORE_HEADERS})

target_include_directories(weiqi_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(weiqi_engine PRIVATE
    -fexceptions
    -frtti
    -Wall
    -Wno-unused-parameter
)

# === PROCESS 模式 ===
if(WEIQI_ENGINE_MODE STREQUAL "PROCESS")
    target_sources(weiqi_engine PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/process_engine.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/real_engines.cpp
    )
    target_compile_definitions(weiqi_engine PRIVATE WEIQI_PROCESS_MODE=1)
    message(STATUS "  -> PROCESS mode: fork+exec engine binary via GTP pipe")
endif()

# === DYLIB 模式 ===
if(WEIQI_ENGINE_MODE STREQUAL "DYLIB")
    target_sources(weiqi_engine PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/dylib_engine.cpp
    )
    target_compile_definitions(weiqi_engine PRIVATE WEIQI_DYLIB_MODE=1)

    find_library(dl-lib dl)
    target_link_libraries(weiqi_engine PRIVATE ${dl-lib})

    if(DEFINED KATAGO_LIB_DIR)
        target_compile_definitions(weiqi_engine PRIVATE WEIQI_HAVE_KATAGO=1)
    endif()
    if(DEFINED LEELAZERO_LIB_DIR)
        target_compile_definitions(weiqi_engine PRIVATE WEIQI_HAVE_LEELAZERO=1)
    endif()
endif()

find_library(log-lib log)
target_link_libraries(weiqi_engine PRIVATE ${log-lib})
