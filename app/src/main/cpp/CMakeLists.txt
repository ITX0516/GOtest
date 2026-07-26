# CMakeLists.txt for libweiqi_engine.so
#
# 构建 Kotlin NativeEngineBridge 的 native 实现。
#
# 三种模式：
#   1. BUILTIN 模式（默认）：直接把 KataGo 源码编译进 libweiqi_engine.so，
#      无需额外可执行文件或 .so。集成度最高，性能最好。
#   2. PROCESS 模式：通过子进程启动引擎可执行文件（katago/leelaz），
#      通过 stdin/stdout 收发 GTP。不需要修改引擎源码，兼容性最好。
#   3. DYLIB 模式：通过 dlopen 动态加载 libkatago.so / libleelaz.so，
#      调用其暴露的 C 接口。性能更好但需要引擎提供 C ABI。
#
# 控制开关（在 app/build.gradle.kts 的 externalNativeBuild.cmake.arguments 中设置）：
#   -DWEIQI_ENGINE_MODE=BUILTIN|PROCESS|DYLIB
#
# BUILTIN 模式额外配置：
#   -DKATAGO_SOURCE_DIR=<path>  # KataGo 源码 cpp/ 目录路径
#                                # 默认: third_party/katago/cpp
#   下载源码: ./tools/download_katago_source.sh
#
# 示例（app/build.gradle.kts 中）：
#   externalNativeBuild {
#       cmake {
#           arguments += listOf("-DWEIQI_ENGINE_MODE=BUILTIN")
#       }
#   }

cmake_minimum_required(VERSION 3.22.1)

project(weiqi_engine LANGUAGES CXX)

# ===== 模式选择 =====
set(WEIQI_ENGINE_MODE "BUILTIN" CACHE STRING "Engine integration mode: BUILTIN, PROCESS, or DYLIB")
set_property(CACHE WEIQI_ENGINE_MODE PROPERTY STRINGS BUILTIN PROCESS DYLIB)

message(STATUS "Weiqi engine mode: ${WEIQI_ENGINE_MODE}")

# 各模式的源文件
set(WEIQI_CORE_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/jni_bridge.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/gtp_engine.cpp
)

set(WEIQI_CORE_HEADERS
    ${CMAKE_CURRENT_SOURCE_DIR}/gtp_engine.h
)

set(WEIQI_ALL_SOURCES ${WEIQI_CORE_SOURCES})
set(WEIQI_ALL_HEADERS ${WEIQI_CORE_HEADERS})

# ===== C++ 标准与编译选项 =====
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ===== 构建共享库 =====
add_library(weiqi_engine SHARED ${WEIQI_ALL_SOURCES} ${WEIQI_ALL_HEADERS})

target_include_directories(weiqi_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(weiqi_engine PRIVATE
    -fexceptions
    -frtti
    -Wall
    -Wno-unused-parameter
)

# ===== 各模式编译定义 =====

# BUILTIN 模式（默认）
if(WEIQI_ENGINE_MODE STREQUAL "BUILTIN")
    target_sources(weiqi_engine PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/builtin_katago_engine.cpp
    )
    target_include_directories(weiqi_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_definitions(weiqi_engine PRIVATE WEIQI_BUILTIN_MODE=1)

    # KataGo 源码路径
    set(KATAGO_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/katago/cpp" CACHE PATH "Path to KataGo cpp source directory")

    if(NOT EXISTS "${KATAGO_SOURCE_DIR}/CMakeLists.txt")
        message(WARNING "KataGo source not found at ${KATAGO_SOURCE_DIR}")
        message(STATUS "  Run: ./tools/download_katago_source.sh")
        message(STATUS "  Or set -DKATAGO_SOURCE_DIR=<path>")
    else()
        message(STATUS "  -> BUILTIN mode: KataGo source at ${KATAGO_SOURCE_DIR}")

        # 收集 KataGo 源文件（排除 main.cpp 和 command/ 下的独立工具）
        file(GLOB_RECURSE KATAGO_SOURCES
            "${KATAGO_SOURCE_DIR}/core/*.cpp"
            "${KATAGO_SOURCE_DIR}/game/*.cpp"
            "${KATAGO_SOURCE_DIR}/search/*.cpp"
            "${KATAGO_SOURCE_DIR}/neuralnet/*.cpp"
            "${KATAGO_SOURCE_DIR}/dataio/*.cpp"
            "${KATAGO_SOURCE_DIR}/book/*.cpp"
        )
        # 排除 main.cpp（有自己的 main 函数会冲突）
        list(FILTER KATAGO_SOURCES EXCLUDE REGEX ".*/main.cpp$")
        # 排除测试文件
        list(FILTER KATAGO_SOURCES EXCLUDE REGEX ".*/tests/")

        target_sources(weiqi_engine PRIVATE ${KATAGO_SOURCES})
        target_include_directories(weiqi_engine PRIVATE
            ${KATAGO_SOURCE_DIR}
            ${KATAGO_SOURCE_DIR}/..
        )

        # KataGo 需要的编译定义
        target_compile_definitions(weiqi_engine PRIVATE
            USE_CPU_ONLY=1
            NO_GIT_REVISION=1
        )

        # 链接 zlib（用于解压权重文件）
        find_library(z-lib z)
        if(z-lib)
            target_link_libraries(weiqi_engine PRIVATE ${z-lib})
            message(STATUS "  -> linked zlib for weight decompression")
        else()
            message(WARNING "zlib not found, KataGo may fail to decompress .gz weights")
        endif()

        # Eigen 头文件（KataGo 依赖）
        if(EXISTS "${KATAGO_SOURCE_DIR}/external/eigen")
            target_include_directories(weiqi_engine PRIVATE
                "${KATAGO_SOURCE_DIR}/external/eigen"
            )
            message(STATUS "  -> using bundled Eigen")
        else()
            message(STATUS "  -> Eigen: assuming system or already in include path")
        endif()
    endif()
endif()

# PROCESS 模式（子进程方式）
if(WEIQI_ENGINE_MODE STREQUAL "PROCESS")
    target_sources(weiqi_engine PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/process_engine.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/real_engines.cpp
    )
    target_compile_definitions(weiqi_engine PRIVATE WEIQI_PROCESS_MODE=1)
    message(STATUS "  -> PROCESS mode: fork+exec engine binary via GTP pipe")
endif()

# DYLIB 模式（动态库 dlopen 方式）
if(WEIQI_ENGINE_MODE STREQUAL "DYLIB")
    target_sources(weiqi_engine PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/dylib_engine.cpp
    )
    target_compile_definitions(weiqi_engine PRIVATE WEIQI_DYLIB_MODE=1)

    # 链接 android log 与 dl 库
    find_library(dl-lib dl)
    target_link_libraries(weiqi_engine PRIVATE ${dl-lib})

    if(KATAGO_LIB_DIR AND EXISTS "${KATAGO_LIB_DIR}/${CMAKE_ANDROID_ARCH_ABI}/libkatago.so")
        target_link_directories(weiqi_engine PRIVATE "${KATAGO_LIB_DIR}/${CMAKE_ANDROID_ARCH_ABI}")
        target_compile_definitions(weiqi_engine PRIVATE WEIQI_HAVE_KATAGO=1)
        message(STATUS "  -> linked libkatago.so from ${KATAGO_LIB_DIR}/${CMAKE_ANDROID_ARCH_ABI}")
    else()
        message(STATUS "  -> KataGo: dlopen only (no link-time dependency)")
    endif()

    if(LEELAZERO_LIB_DIR AND EXISTS "${LEELAZERO_LIB_DIR}/${CMAKE_ANDROID_ARCH_ABI}/libleelaz.so")
        target_link_directories(weiqi_engine PRIVATE "${LEELAZERO_LIB_DIR}/${CMAKE_ANDROID_ARCH_ABI}")
        target_compile_definitions(weiqi_engine PRIVATE WEIQI_HAVE_LEELAZERO=1)
        message(STATUS "  -> linked libleelaz.so from ${LEELAZERO_LIB_DIR}/${CMAKE_ANDROID_ARCH_ABI}")
    else()
        message(STATUS "  -> LeelaZero: dlopen only (no link-time dependency)")
    endif()
endif()

# ===== 链接必要的 Android 库 =====
find_library(log-lib log)
target_link_libraries(weiqi_engine PRIVATE ${log-lib})
