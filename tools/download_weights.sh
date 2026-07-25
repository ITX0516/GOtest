#!/usr/bin/env bash
# ============================================================================
# 下载 KataGo 权重文件到 app/src/main/assets/weights/
#
# 权重来源：https://katagotraining.org/networks/
#
# 用法: ./tools/download_weights.sh [--size b10|b18|b28] [--all]
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

DEST_DIR="$PROJECT_ROOT/app/src/main/assets/weights"

# 权重下载地址（来自 katagotraining.org）
declare -A WEIGHT_URLS=(
    ["b10"]="https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b10c128-s1141046784-d204142634.bin.gz"
    ["b18"]="https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b18c384nbt-s9996604416-d4316597426.bin.gz"
    ["b28"]="https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b28c512nbt-s12283775232-d5679728027.bin.gz"
)

declare -A WEIGHT_NAMES=(
    ["b10"]="katago_b10c128.bin.gz"
    ["b18"]="katago_b18c384nbt.bin.gz"
    ["b28"]="katago_b28c512nbt.bin.gz"
)

declare -A WEIGHT_SIZES=(
    ["b10"]="~12MB  — 快速，业余 1-3 段水平，手机 1-2 秒一步"
    ["b18"]="~93MB  — 平衡，业余 5-6 段水平，手机 5-10 秒一步（推荐）"
    ["b28"]="~259MB — 最强，职业水平，手机 30+ 秒一步"
)

TARGET="b18"  # 默认下载 b18
ALL=0

usage() {
    cat <<EOF
用法: $0 [选项]

选项:
  --size <b10|b18|b28>  下载指定大小的权重（默认 b18）
  --all                  下载全部三种权重
  --dest <dir>           目标目录（默认 app/src/main/assets/weights/）
  -h, --help             显示帮助

权重说明：
EOF
    for key in b10 b18 b28; do
        echo "  $key  ${WEIGHT_SIZES[$key]}"
    done
    echo ""
    echo "来源: https://katagotraining.org/networks/"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --size) TARGET="$2"; shift 2 ;;
        --all)  ALL=1; shift ;;
        --dest) DEST_DIR="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "未知参数: $1"; usage; exit 1 ;;
    esac
done

mkdir -p "$DEST_DIR"

download_one() {
    local key="$1"
    local url="${WEIGHT_URLS[$key]}"
    local filename="${WEIGHT_NAMES[$key]}"
    local dest="$DEST_DIR/$filename"

    if [[ -f "$dest" ]]; then
        echo "  ✓ 已存在: $filename"
        return 0
    fi

    echo "  下载 $key 权重 (${WEIGHT_SIZES[$key]})..."
    echo "    URL: $url"

    if command -v curl &>/dev/null; then
        curl -L -o "$dest" "$url" --progress-bar
    elif command -v wget &>/dev/null; then
        wget -O "$dest" "$url" -q --show-progress
    else
        echo "错误: 未找到 curl 或 wget"
        return 1
    fi

    # 验证文件大小（>1MB 就算成功）
    local size
    size=$(stat -c%s "$dest" 2>/dev/null || stat -f%z "$dest" 2>/dev/null || echo 0)
    if [[ $size -lt 1000000 ]]; then
        echo "  ⚠ 文件可能下载失败（大小: $size 字节）"
        return 1
    fi

    echo "  ✓ 下载完成: $filename ($(( size / 1024 / 1024 )) MB)"
}

echo "========================================"
echo "下载 KataGo 权重文件"
echo "  目标目录: $DEST_DIR"
echo "========================================"

if [[ $ALL -eq 1 ]]; then
    for key in b10 b18 b28; do
        download_one "$key"
    done
else
    if [[ -z "${WEIGHT_URLS[$TARGET]:-}" ]]; then
        echo "错误: 未知权重大小: $TARGET"
        echo "可选: b10, b18, b28"
        exit 1
    fi
    download_one "$TARGET"
fi

echo ""
echo "✓ 权重下载完成！"
echo ""
echo "下一步："
echo "  BUILTIN 模式: 设置 WEIQI_ENGINE_MODE=BUILTIN，同步 Gradle 构建"
echo "  PROCESS 模式: 还需要编译引擎可执行文件: ./tools/build_katago_android.sh"
