#!/usr/bin/env bash
# CI helper: builds acestep.cpp for this Linux machine, downloads the Lite models
# plus the songwriter, and starts the server on 127.0.0.1:8085.
# An Android emulator on the same machine reaches it as 10.0.2.2:8085.
set -euo pipefail

REF="${ACESTEP_REF:?set ACESTEP_REF}"
WORK="${ACE_WORK:-${RUNNER_TEMP:-/tmp}/ace}"
PORT="${ACE_PORT:-8085}"
HF="https://huggingface.co/Serveurperso/ACE-Step-1.5-GGUF/resolve/main"
MODELS="vae-BF16.gguf Qwen3-Embedding-0.6B-Q8_0.gguf acestep-v15-turbo-Q4_K_M.gguf acestep-5Hz-lm-0.6B-Q8_0.gguf ${EXTRA_MODELS:-}"

mkdir -p "$WORK/models" "$WORK/adapters"
BIN="$WORK/ace-server"

if [ ! -x "$BIN" ]; then
    git clone -q --filter=blob:none https://github.com/ServeurpersoCom/acestep.cpp.git "$WORK/src"
    git -C "$WORK/src" checkout -q "$REF"
    git -C "$WORK/src" submodule update --init --recursive --depth 1
    # ACE_CPU_FLAGS lets a check build with the phone's exact CPU settings instead of this machine's.
    cmake -S "$WORK/src" -B "$WORK/src/build" -DCMAKE_BUILD_TYPE=Release \
        ${ACE_CPU_FLAGS:--DGGML_NATIVE=ON} -DGGML_BLAS=OFF -DGGML_CUDA=OFF -DGGML_VULKAN=OFF -DGGML_METAL=OFF \
        -DGGML_OPENMP=OFF -DGGML_BUILD_TESTS=OFF -DGGML_BUILD_EXAMPLES=OFF > "$WORK/cmake.log"
    cmake --build "$WORK/src/build" --target ace-server -j"$(nproc)" > "$WORK/build.log" 2>&1 \
        || { tail -n 60 "$WORK/build.log"; exit 1; }
    cp "$(find "$WORK/src/build" -type f -name ace-server | head -n 1)" "$BIN"
fi

for f in $MODELS; do
    if [ ! -s "$WORK/models/$f" ]; then
        curl -fsSL --retry 3 -o "$WORK/models/$f" "$HF/$f"
    fi
done
ls -la "$WORK/models"

nohup "$BIN" --models "$WORK/models" --adapters "$WORK/adapters" --host 127.0.0.1 --port "$PORT" \
    > "$WORK/server.log" 2>&1 &
echo $! > "$WORK/server.pid"

for _ in $(seq 1 150); do
    if curl -fs "http://127.0.0.1:$PORT/health" > /dev/null; then
        echo "Engine is up on port $PORT"
        curl -s "http://127.0.0.1:$PORT/props" | head -c 1500
        echo
        exit 0
    fi
    sleep 2
done
echo "Engine did not start" >&2
tail -n 80 "$WORK/server.log" >&2
exit 1
