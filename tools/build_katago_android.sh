#!/usr/bin/env bash
# ============================================================================
# KataGo Android 交叉编译脚本
#
# 用法：
#   ./build_katago_android.sh [--abi arm64-v8a] [--cpu-only] [--ndk /path/to/ndk]
#
# 依赖：
#   - Android NDK r23+（建议 r25c）
#   - CMake 3.22+
#   - Git
#   - Ninja（可选，更快）
#
# 产物：
#   build/katago-android-<abi>/katago          — 可执行文件（PROCESS 模式用）
#   build/katago-android-<abi>/libkatago.so     — 共享库（DYLIB 模式用，需要额外包装）
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ===== 默认参数 =====
ABI="arm64-v8a"
CPU_ONLY=1
KATAGO_REPO="https://github.com/lightvector/KataGo.git"
KATAGO_BRANCH="master"
BUILD_DIR="$SCRIPT_DIR/build/katago-android-$ABI"
OUTPUT_DIR="$SCRIPT_DIR/output/katago/$ABI"
NDK_PATH=""
ANDROID_PLATFORM="android-24"
BUILD_TYPE="Release"
CLEAN=0
SHARED_LIB=0

# ===== 参数解析 =====
while [[ $# -gt 0 ]]; do
    case "$1" in
        --abi)           ABI="$2"; shift 2 ;;
        --cpu-only)      CPU_ONLY=1; shift ;;
        --gpu)           CPU_ONLY=0; shift ;;
        --repo)          KATAGO_REPO="$2"; shift 2 ;;
        --branch)        KATAGO_BRANCH="$2"; shift 2 ;;
        --ndk)           NDK_PATH="$2"; shift 2 ;;
        --platform)      ANDROID_PLATFORM="$2"; shift 2 ;;
        --build-dir)     BUILD_DIR="$2"; shift 2 ;;
        --output-dir)    OUTPUT_DIR="$2"; shift 2 ;;
        --clean)         CLEAN=1; shift ;;
        --shared)        SHARED_LIB=1; shift ;;
        -h|--help)
            cat <<EOF
用法: $0 [选项]

选项:
  --abi <abi>         目标 ABI: arm64-v8a (默认), armeabi-v7a, x86_64
  --cpu-only          仅 CPU 模式（推荐手机端，兼容性最好）
  --gpu               启用 GPU/OpenCL 模式
  --repo <url>        KataGo 仓库地址（默认官方仓库）
  --branch <name>     KataGo 分支或标签（默认 master）
  --ndk <path>        Android NDK 路径
  --platform <api>    Android 最低 API 级别（默认 android-24）
  --build-dir <dir>   编译目录
  --output-dir <dir>  输出目录
  --clean             清理编译目录后重新编译
  --shared            同时编译共享库 libkatago.so（DYLIB 模式用）
  -h, --help          显示帮助

示例:
  # 编译 arm64-v8a CPU 版
  $0 --abi arm64-v8a --cpu-only

  # 用指定 NDK 编译
  $0 --ndk ~/Android/Sdk/ndk/25.2.9519653

  # 编译共享库（DYLIB 模式）
  $0 --abi arm64-v8a --shared
EOF
            exit 0
            ;;
        *)
            echo "未知参数: $1"
            exit 1
            ;;
    esac
done

# ===== 查找 NDK =====
if [[ -z "$NDK_PATH" ]]; then
    if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
        NDK_PATH="$ANDROID_NDK_HOME"
    elif [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
        # 找最新版本的 NDK
        NDK_PATH="$(ls -1d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"
    fi
fi

if [[ -z "$NDK_PATH" || ! -d "$NDK_PATH" ]]; then
    echo "错误: 未找到 Android NDK"
    echo "请通过 --ndk 指定路径，或设置 ANDROID_NDK_HOME 环境变量"
    exit 1
fi

echo "使用 NDK: $NDK_PATH"

CMAKE_TOOLCHAIN="$NDK_PATH/build/cmake/android.toolchain.cmake"
if [[ ! -f "$CMAKE_TOOLCHAIN" ]]; then
    echo "错误: 找不到 toolchain 文件: $CMAKE_TOOLCHAIN"
    exit 1
fi

# ===== ABI → 编译架构映射 =====
case "$ABI" in
    arm64-v8a)   ANDROID_ABI="arm64-v8a" ;;
    armeabi-v7a) ANDROID_ABI="armeabi-v7a" ;;
    x86_64)      ANDROID_ABI="x86_64" ;;
    *)
        echo "错误: 不支持的 ABI: $ABI"
        exit 1
        ;;
esac

# ===== 源码目录 =====
SRC_DIR="$SCRIPT_DIR/src/KataGo"

echo "========================================"
echo "KataGo Android 编译配置"
echo "  ABI:        $ANDROID_ABI"
echo "  CPU only:   $CPU_ONLY"
echo "  平台:       $ANDROID_PLATFORM"
echo "  构建类型:   $BUILD_TYPE"
echo "  源码目录:   $SRC_DIR"
echo "  编译目录:   $BUILD_DIR"
echo "  输出目录:   $OUTPUT_DIR"
echo "  共享库:     $SHARED_LIB"
echo "========================================"

# ===== 获取/更新源码 =====
if [[ ! -d "$SRC_DIR" ]]; then
    echo "克隆 KataGo 源码..."
    git clone --depth 1 --branch "$KATAGO_BRANCH" "$KATAGO_REPO" "$SRC_DIR"
else
    echo "更新 KataGo 源码..."
    cd "$SRC_DIR"
    git fetch --depth 1 origin "$KATAGO_BRANCH"
    git checkout "origin/$KATAGO_BRANCH"
    cd "$SCRIPT_DIR"
fi

# ===== 清理 =====
if [[ $CLEAN -eq 1 && -d "$BUILD_DIR" ]]; then
    echo "清理编译目录..."
    rm -rf "$BUILD_DIR"
fi

mkdir -p "$BUILD_DIR"
mkdir -p "$OUTPUT_DIR"

# ===== CMake 配置 =====
cd "$BUILD_DIR"

CMAKE_ARGS=(
    -DCMAKE_TOOLCHAIN_FILE="$CMAKE_TOOLCHAIN"
    -DANDROID_ABI="$ANDROID_ABI"
    -DANDROID_PLATFORM="$ANDROID_PLATFORM"
    -DCMAKE_BUILD_TYPE="$BUILD_TYPE"
    -DNO_GIT_REVISION=1
)

if [[ $CPU_ONLY -eq 1 ]]; then
    CMAKE_ARGS+=(-DUSE_CPU_ONLY=1)
    echo "CPU-only 模式（无 OpenCL/GPU 依赖）"
fi

if [[ $SHARED_LIB -eq 1 ]]; then
    # 注意：KataGo 官方 CMake 默认编译可执行文件
    # 如果需要共享库，需要自己加包装层
    # 这里先标记，后续步骤会生成包装
    echo "共享库模式：将在编译后生成 libkatago.so 包装"
fi

echo "CMake 配置..."
cmake "$SRC_DIR/cpp" "${CMAKE_ARGS[@]}"

# ===== 编译 =====
echo "开始编译..."
if command -v ninja &>/dev/null && [[ -f build.ninja ]]; then
    ninja -j"$(nproc 2>/dev/null || echo 4)"
else
    make -j"$(nproc 2>/dev/null || echo 4)"
fi

# ===== 复制产物 =====
echo "复制产物到输出目录..."

# 可执行文件
if [[ -f "$BUILD_DIR/katago" ]]; then
    cp "$BUILD_DIR/katago" "$OUTPUT_DIR/katago"
    chmod +x "$OUTPUT_DIR/katago"
    echo "  ✓ katago 可执行文件"
else
    echo "  ⚠ 未找到 katago 可执行文件，检查编译输出"
    find "$BUILD_DIR" -name "katago" -type f 2>/dev/null || true
fi

# 如果需要共享库，生成一个包装
if [[ $SHARED_LIB -eq 1 ]]; then
    echo "生成 libkatago.so 包装..."
    # 注意：KataGo 官方不直接输出 .so
    # 需要自己写一个 C 包装层（参考 kata_bridge.h）
    # 这里输出提示
    cat <<EOF > "$OUTPUT_DIR/README.txt"
libkatago.so 编译说明：

KataGo 官方 CMake 默认只编译可执行文件 katago。
要生成 DYLIB 模式需要的 libkatago.so，你需要：

1. 在 KataGo 源码 cpp/ 目录下添加一个 C 包装文件，
   导出 kata_bridge.h 中定义的函数。
2. 修改 CMakeLists.txt 添加共享库目标。

简化方案：直接使用 PROCESS 模式（子进程方式），
不需要 .so，兼容性更好，开箱即用。
EOF
    echo "  ⚠ 请参考 $OUTPUT_DIR/README.txt"
fi

# ===== 安装到项目 assets =====
ASSETS_BIN_DIR="$PROJECT_ROOT/app/src/main/assets/bin/$ABI"
read -rp "是否复制到项目 assets 目录？($ASSETS_BIN_DIR) [y/N] " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    mkdir -p "$ASSETS_BIN_DIR"
    if [[ -f "$OUTPUT_DIR/katago" ]]; then
        cp "$OUTPUT_DIR/katago" "$ASSETS_BIN_DIR/katago"
        chmod +x "$ASSETS_BIN_DIR/katago"
        echo "已复制到: $ASSETS_BIN_DIR/katago"
    fi
fi

echo ""
echo "✓ KataGo Android 编译完成！"
echo "  产物目录: $OUTPUT_DIR"
echo ""
echo "下一步："
echo "  1. 下载权重文件: ./download_weights.sh"
echo "  2. 将 katago 放到 app/src/main/assets/bin/$ABI/"
echo "  3. 将权重放到 app/src/main/assets/weights/"
echo "  4. 确认 build.gradle.kts 中 WEIQI_ENGINE_MODE=PROCESS"
