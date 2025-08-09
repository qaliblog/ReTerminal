#!/usr/bin/env bash
set -euo pipefail

# Compile an MLC model for Android arm64 (AOT .so), similar to mlc-llm’s official flow.
#
# Usage:
#   scripts/mlc_compile_android.sh \
#     --model-id Qwen/Qwen2.5-Coder-7B-Instruct \
#     --quant q4f16_1 \
#     --device cpu \
#     --out dist/Qwen2.5-Coder-7B-Instruct-q4f16_1-MLC \
#     --conv-template qwen2 \
#     [--context-window-size 32768] \
#     [--vulkan]
#
# This will produce model-...-(cpu|vulkan).so under the output directory.
# Then copy the .so to your device under the selected model folder root, e.g.:
#   adb push dist/.../*.so /sdcard/reterminalAssets/<YourModel>/
# And ensure runtime is present under libs/arm64-v8a/ as libtvm4j_runtime_packed.so

MODEL_ID=""
QUANT="q4f16_1"
OUT_DIR=""
DO_CPU=1
DO_VULKAN=0
CONV_TEMPLATE=""
CTX_WIN=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --model-id)
      MODEL_ID="$2"; shift 2;;
    --quant)
      QUANT="$2"; shift 2;;
    --out)
      OUT_DIR="$2"; shift 2;;
    --device)
      if [[ "$2" == "cpu" ]]; then DO_CPU=1; DO_VULKAN=0; else DO_CPU=0; DO_VULKAN=1; fi; shift 2;;
    --vulkan)
      DO_VULKAN=1; shift 1;;
    --conv-template|--conv)
      CONV_TEMPLATE="$2"; shift 2;;
    --context-window-size|--ctx)
      CTX_WIN="$2"; shift 2;;
    *) echo "Unknown arg: $1"; exit 1;;
  esac
done

if [[ -z "$MODEL_ID" ]]; then
  echo "--model-id is required (e.g., Qwen/Qwen2.5-Coder-7B-Instruct)" >&2
  exit 1
fi

if [[ -z "$OUT_DIR" ]]; then
  # default OUT_DIR based on model id and quant
  SAFE_MODEL=$(echo "$MODEL_ID" | sed 's|/|-|g')
  OUT_DIR="dist/${SAFE_MODEL}-${QUANT}-MLC"
fi

# Infer conv template if not provided
if [[ -z "$CONV_TEMPLATE" ]]; then
  lower_id=$(echo "$MODEL_ID" | tr '[:upper:]' '[:lower:]')
  if [[ "$lower_id" == *"qwen2"* || "$lower_id" == *"qwen2.5"* || "$lower_id" == *"qwen-2"* ]]; then
    CONV_TEMPLATE="qwen2"
  elif [[ "$lower_id" == *"llama-3"* || "$lower_id" == *"llama3"* ]]; then
    CONV_TEMPLATE="llama-3"
  elif [[ "$lower_id" == *"llama-2"* || "$lower_id" == *"llama2"* ]]; then
    CONV_TEMPLATE="llama-2"
  elif [[ "$lower_id" == *"codellama"* ]]; then
    if [[ "$lower_id" == *"instruct"* ]]; then CONV_TEMPLATE="codellama_instruct"; else CONV_TEMPLATE="codellama_completion"; fi
  elif [[ "$lower_id" == *"mistral"* ]]; then
    CONV_TEMPLATE="mistral_default"
  elif [[ "$lower_id" == *"gemma"* ]]; then
    CONV_TEMPLATE="gemma_instruction"
  elif [[ "$lower_id" == *"phi-3"* ]]; then
    CONV_TEMPLATE="phi-3"
  elif [[ "$lower_id" == *"phi-2"* ]]; then
    CONV_TEMPLATE="phi-2"
  else
    # Fallbacks: common chat formats
    CONV_TEMPLATE="chatml"
  fi
fi

echo "[INFO] model_id=$MODEL_ID quant=$QUANT conv_template=$CONV_TEMPLATE ctx=$CTX_WIN out=$OUT_DIR"

PYTHON_BIN=${PYTHON_BIN:-python3}
VENV_DIR=${VENV_DIR:-.venv-mlc}

mkdir -p "$OUT_DIR"

if [[ ! -d "$VENV_DIR" ]]; then
  echo "[+] Creating venv at $VENV_DIR"
  "$PYTHON_BIN" -m venv "$VENV_DIR"
fi

source "$VENV_DIR/bin/activate"

echo "[+] Installing mlc nightly wheels"
pip install --upgrade pip wheel >/dev/null
# Prefer nightly wheels; fall back to stable if nightly not available
if ! pip install --pre -U -f https://mlc.ai/wheels mlc-ai-nightly mlc-llm-nightly; then
  echo "[!] Nightly wheels not found; installing stable mlc-llm instead"
  pip install -U mlc-llm mlc-ai-nightly || pip install -U mlc-llm
fi

echo "[+] Generating config (${QUANT})"
GEN_ARGS=(gen_config "$MODEL_ID" --quantization "$QUANT" --conv-template "$CONV_TEMPLATE" -o "$OUT_DIR")
if [[ -n "$CTX_WIN" ]]; then GEN_ARGS+=(--context-window-size "$CTX_WIN"); fi
mlc_llm "${GEN_ARGS[@]}"

echo "[+] Converting weights (${QUANT})"
mlc_llm convert_weight "$MODEL_ID" --quantization "$QUANT" -o "$OUT_DIR"

CFG_JSON="$OUT_DIR/mlc-chat-config.json"
if [[ ! -f "$CFG_JSON" ]]; then
  echo "Config not found: $CFG_JSON" >&2
  exit 1
fi

SAFE_NAME=$(basename "$OUT_DIR")

if [[ $DO_CPU -eq 1 ]]; then
  echo "[+] Compiling Android arm64 CPU module"
  mlc_llm compile "$CFG_JSON" --device cpu --target android-arm64 -o "$OUT_DIR/model-${SAFE_NAME}-cpu.so"
fi

if [[ $DO_VULKAN -eq 1 ]]; then
  echo "[+] Compiling Android arm64 Vulkan module"
  mlc_llm compile "$CFG_JSON" --device vulkan --target android-arm64 -o "$OUT_DIR/model-${SAFE_NAME}-vulkan.so"
fi

echo "[+] Done. Outputs in: $OUT_DIR"
ls -lh "$OUT_DIR" | sed 's/^/[OUT] /'