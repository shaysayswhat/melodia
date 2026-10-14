#!/usr/bin/env bash
# Builds the acestep.cpp HTTP server for Android (arm64-v8a) and drops it into
# app/src/main/jniLibs/arm64-v8a/libaceserver.so, where Gradle packages it.
# Android installs it next to the app's native libraries, and the app runs it
# as a separate process that only listens on 127.0.0.1.
#
# Needs: git, cmake, make and the Android NDK (ANDROID_NDK_HOME).
set -euo pipefail

ACESTEP_REPO="${ACESTEP_REPO:-https://github.com/ServeurpersoCom/acestep.cpp.git}"
ACESTEP_REF="${ACESTEP_REF:-4c75d8b74cd0841353478284c1ca711705f2918c}"
API_LEVEL="${API_LEVEL:-29}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-$ROOT/native/work}"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_ROOT:-}}}"

if [ -z "$NDK" ] || [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
    echo "Android NDK not found. Set ANDROID_NDK_HOME." >&2
    exit 1
fi
echo "Using NDK at $NDK"

mkdir -p "$WORK" "$OUT"
SRC="$WORK/acestep.cpp"
if [ ! -d "$SRC/.git" ]; then
    git clone --filter=blob:none "$ACESTEP_REPO" "$SRC"
fi
cd "$SRC"
git fetch --depth 1 origin "$ACESTEP_REF" 2>/dev/null || git fetch origin
git checkout -q --force "$ACESTEP_REF"
git submodule update --init --recursive --depth 1

# armv8.2-a with dotprod and fp16 covers every phone with 8 GB+ RAM
# (Snapdragon 845 and newer, Dimensity, Exynos 9820+, Tensor).
cmake -S . -B build-android \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM="android-$API_LEVEL" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_NATIVE=OFF \
    -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 \
    -DGGML_OPENMP=OFF \
    -DGGML_BLAS=OFF \
    -DGGML_VULKAN=OFF \
    -DGGML_CUDA=OFF \
    -DGGML_METAL=OFF \
    -DGGML_BUILD_TESTS=OFF \
    -DGGML_BUILD_EXAMPLES=OFF

cmake --build build-android --target ace-server -j"$(nproc)"

BIN="build-android/ace-server"
if [ ! -f "$BIN" ]; then
    BIN="$(find build-android -type f -name ace-server | head -n 1)"
fi
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
cp "$BIN" "$OUT/libaceserver.so"
"$STRIP" "$OUT/libaceserver.so" || true
chmod 755 "$OUT/libaceserver.so"

echo "Built engine:"
ls -la "$OUT"
file "$OUT/libaceserver.so" || true
