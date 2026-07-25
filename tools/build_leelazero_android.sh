#!/usr/bin/env bash
# ============================================================================
# LeelaZero Android 交叉编译脚本
#
# 注意：LeelaZero 官方对 Android 支持不如 KataGo 完善，编译较复杂。
# 如果只是想体验围棋 AI，强烈建议优先使用 KataGo。
#
# 依赖：
#   - Android NDK r23+
#   - CMake 3.22+
#   - Git
#   - Boost 库（需要交叉编译）
#
# 用法：
#   ./build_leelazero_android.sh --abi arm64-v8a --ndk /path/to/ndk
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ABI="arm64-v8a"
LEELAZ_REPO="https://github.com/leela-zero/leela-zero.git"
LEELAZ_BRANCH="next"
BUILD_DIR="$SCRIPT_DIR/build/leelaz-android-$ABI"
OUTPUT_DIR="$SCRIPT_DIR/output/leelazero/$ABI"
NDK_PATH=""
ANDROID_PLATFORM="android-24"
CLEAN=0

echo "========================================"
echo "⚠ LeelaZero Android 编译"
echo "========================================"
echo ""
echo "LeelaZero 官方对 Android 支持有限，编译依赖较多（Boost 等）。"
echo "建议优先使用 KataGo（编译简单、棋力更强、支持更好）。"
echo ""
echo "如果你仍要编译 LeelaZero，请手动执行以下步骤："
echo ""
echo "  1. 交叉编译 Boost for Android"
echo "     参考: https://github.com/moritz-wundke/Boost-for-Android"
echo ""
echo "  2. 克隆 leela-zero 源码"
echo "     git clone --recursive $LEELAZ_REPO"
echo ""
echo "  3. 使用 NDK toolchain 交叉编译"
echo "     cmake -DCMAKE_TOOLCHAIN_FILE=\$NDK/build/cmake/android.toolchain.cmake \\"
echo "           -DANDROID_ABI=$ABI \\"
echo "           -DBOOST_ROOT=/path/to/boost-android \\"
echo "           -DUSE_CPU_ONLY=1 \\"
echo "           .."
echo "     make -j4"
echo ""
echo "  4. 产物 leelaz 复制到 app/src/main/assets/bin/$ABI/"
echo ""
echo "或者：直接使用 KataGo（本项目已提供一键编译脚本）"
echo "  ./build_katago_android.sh --abi $ABI"
echo ""

cat <<EOF
────────────────────────────────────────────────────────
  推荐方案：使用 KataGo 代替 LeelaZero

  KataGo 优势：
  ✓ 棋力更强（同权重下比 LeelaZero 强 2-3 子）
  ✓ 官方支持 Android 编译
  ✓ 支持更丰富的分析功能（kata-analyze JSON）
  ✓ CPU-only 模式编译简单，无额外依赖
  ✓ 本项目已完整适配 PROCESS / DYLIB 两种模式

  一键编译 KataGo:
    ./build_katago_android.sh --abi arm64-v8a
────────────────────────────────────────────────────────
EOF

exit 0
