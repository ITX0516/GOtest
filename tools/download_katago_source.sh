#!/usr/bin/env bash
# ============================================================================
# 下载 KataGo 源码到 app/src/main/cpp/third_party/katago/
# 用于 BUILTIN 模式：直接把 KataGo 编译进 libweiqi_engine.so
#
# 用法: ./tools/download_katago_source.sh [--branch v1.16.4]
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

KATAGO_REPO="https://github.com/lightvector/KataGo.git"
KATAGO_BRANCH="v1.16.4"
DEST_DIR="$PROJECT_ROOT/app/src/main/cpp/third_party/katago"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --branch) KATAGO_BRANCH="$2"; shift 2 ;;
        --repo)   KATAGO_REPO="$2"; shift 2 ;;
        --dest)   DEST_DIR="$2"; shift 2 ;;
        -h|--help)
            echo "用法: $0 [--branch <tag>] [--repo <url>] [--dest <dir>]"
            exit 0
            ;;
        *)
            echo "未知参数: $1"
            exit 1
            ;;
    esac
done

echo "========================================"
echo "下载 KataGo 源码"
echo "  仓库: $KATAGO_REPO"
echo "  版本: $KATAGO_BRANCH"
echo "  目标: $DEST_DIR"
echo "========================================"

if [[ -d "$DEST_DIR" ]]; then
    echo "目录已存在，检查是否需要更新..."
    if [[ -d "$DEST_DIR/.git" ]]; then
        cd "$DEST_DIR"
        CURRENT_BRANCH=$(git describe --tags 2>/dev/null || git rev-parse --short HEAD)
        echo "当前版本: $CURRENT_BRANCH"
        read -rp "是否重新下载？[y/N] " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            echo "跳过下载。"
            exit 0
        fi
        cd "$SCRIPT_DIR"
    fi
    echo "清理旧目录..."
    rm -rf "$DEST_DIR"
fi

mkdir -p "$(dirname "$DEST_DIR")"

echo "克隆 KataGo 源码（深度 1，仅 cpp 目录）..."
git clone --depth 1 --branch "$KATAGO_BRANCH" --filter=blob:none "$KATAGO_REPO" "$DEST_DIR"

echo ""
echo "✓ KataGo 源码已下载到: $DEST_DIR"
echo ""
echo "下一步："
echo "  1. 下载权重: ./tools/download_weights.sh"
echo "  2. 在 app/build.gradle.kts 中设置 WEIQI_ENGINE_MODE=BUILTIN"
echo "  3. 同步 Gradle 并构建项目"
