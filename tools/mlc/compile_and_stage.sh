#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   tools/mlc/compile_and_stage.sh HF://mlc-ai/gemma-2b-it-q4f16_1-MLC q4f16_1 out/mlc/gemma2b
# Env (optional):
#   MLC_PYTHON=python3        # python executable with mlc_llm installed
#   DEVICE=vulkan             # compile device: vulkan|cuda|metal|cpu (default: vulkan)
#   MODEL_ID=gemma-2b-q4f16_1 # model id used for output naming (default: derived from repo)
#   TVM_RUNTIME_SO=/abs/path/libtvm4j_runtime_packed.so # if provided, will be staged under libs/<abi>/
#   ABI=arm64-v8a             # ABI for libs/ subfolder (default: arm64-v8a)

PY=${MLC_PYTHON:-python3}
DEVICE=${DEVICE:-vulkan}
REPO=${1:?"HF repo required, e.g., HF://mlc-ai/gemma-2b-it-q4f16_1-MLC"}
QUANT=${2:?"Quantization required, e.g., q4f16_1"}
OUT=${3:?"Output dir required"}
ABI=${ABI:-arm64-v8a}
MODEL_ID=${MODEL_ID:-$(basename "$REPO")}

WORK="$OUT/work"
STAGE="$OUT/stage/$MODEL_ID"
mkdir -p "$WORK" "$STAGE"

echo "[MLC] gen_config..."
$PY -m mlc_llm gen_config "$REPO" --quantization "$QUANT" --output "$WORK"

echo "[MLC] convert_weight..."
$PY -m mlc_llm convert_weight "$REPO" --quantization "$QUANT" --output "$WORK"

echo "[MLC] compile ($DEVICE) ..."
SO_PATH="$WORK/${MODEL_ID}-${QUANT}-${DEVICE}.so"
$PY -m mlc_llm compile "$WORK/mlc-chat-config.json" --device "$DEVICE" --quantization "$QUANT" --output "$SO_PATH"

# Stage artifacts in app-expected layout
cp -v "$WORK/mlc-chat-config.json" "$STAGE/"
cp -v "$WORK/ndarray-cache.json" "$STAGE/"
cp -v "$SO_PATH" "$STAGE/" || true

# params shards
find "$WORK" -maxdepth 1 -name 'params_shard_*.bin' -print0 | xargs -0 -I{} cp -v {} "$STAGE/"

# Optional: runtime lib
if [[ -n "${TVM_RUNTIME_SO:-}" && -f "$TVM_RUNTIME_SO" ]]; then
  mkdir -p "$STAGE/libs/$ABI"
  cp -v "$TVM_RUNTIME_SO" "$STAGE/libs/$ABI/"
  echo "[MLC] Staged runtime: $STAGE/libs/$ABI/$(basename "$TVM_RUNTIME_SO")"
else
  echo "[MLC] NOTE: TVM runtime lib not provided. Place libtvm4j_runtime_packed.so or libtvm_runtime.so under $STAGE/libs/$ABI/ on device."
fi

echo "[DONE] Staged model at: $STAGE"
echo "Copy this folder to your device (e.g., /sdcard/reterminalAssets/$MODEL_ID) and select it in Settings."