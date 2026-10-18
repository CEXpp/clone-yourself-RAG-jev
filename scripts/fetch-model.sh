#!/usr/bin/env bash
set -euo pipefail

MODEL_DIR="${1:-models}"
mkdir -p "$MODEL_DIR"

REVISION="75c43b069aac4d136ba6bc1122f995fedcfd2781"
EXPECTED_ONNX_SHA="69a0b846f4f116b5e6aabf9546ea6754d02264f3211a13a1bd69b31b8040749a"
EXPECTED_VOCAB_SHA="45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c"

ONNX_URL="https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/${REVISION}/onnx/model.onnx"
VOCAB_URL="https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/${REVISION}/vocab.txt"

LOCAL_CACHE_DIR="/home/hatch/workspace/projects/spike/model"

fetch_file() {
    local target="$1"
    local url="$2"
    local fallback="$3"

    if [ -f "$target" ]; then
        echo "$target already exists."
        return 0
    fi

    echo "Fetching $target from $url..."
    if curl -sSL -f --connect-timeout 10 --max-time 120 "$url" -o "$target"; then
        echo "Successfully downloaded $target."
    elif [ -f "$fallback" ]; then
        echo "Network download failed or unavailable, copying from local cached copy $fallback..."
        cp "$fallback" "$target"
    else
        echo "Error: failed to fetch $target and no fallback available." >&2
        exit 1
    fi
}

fetch_file "$MODEL_DIR/model.onnx" "$ONNX_URL" "$LOCAL_CACHE_DIR/model.onnx"
fetch_file "$MODEL_DIR/vocab.txt" "$VOCAB_URL" "$LOCAL_CACHE_DIR/vocab.txt"

echo "Verifying SHA256 checksums..."
echo "${EXPECTED_ONNX_SHA}  ${MODEL_DIR}/model.onnx" | sha256sum -c -
echo "${EXPECTED_VOCAB_SHA}  ${MODEL_DIR}/vocab.txt" | sha256sum -c -

echo "BGE small zh v1.5 model and vocab verified successfully in ${MODEL_DIR}/"
