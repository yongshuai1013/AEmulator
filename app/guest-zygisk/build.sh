#!/bin/sh
# Guest Zygisk spike stack: the route-B bridge (bridge.c) and the API v4
# test module (testmod.cpp, against the official zygisk.hpp vendored in
# this directory - topjohnwu/zygisk-module-sample at the "Zygisk API v4"
# commit 7bb941ac8edf, 0BSD, sha256
# fc6523882c0f8659b4fcd1e04ffb07d3a948090e5a8c08817af6e2b506a078d0).
#
#   assets/engines/kk/aemu-azygisk.so        the bridge; TreeFixer plants
#     it as /system/lib64/libazygisk.so and GuestVm points
#     ro.dalvik.vm.native.bridge at it, only while a VM's Zygisk spike
#     switch is on (ZygiskSpikeSupport).
#   assets/engines/kk/aemu-zygisk-testmod.so the test module; TreeFixer
#     plants it as /data/adb/modules/aemu-spike/zygisk/arm64-v8a.so and
#     lists the module directory in the bridge's manifest.
#
# 64-bit only, at the tree's zero-warning bar. The bridge is plain C on
# purpose: it loads inside zygote and must not drag a C++ runtime in.
# Needs the NDK (ANDROID_NDK_HOME). Usage:
#   ANDROID_NDK_HOME=/path/to/ndk sh build.sh
set -e
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
app=$here/..
out=$app/src/main/assets/engines/kk
mkdir -p "$out"

NDKBIN=${ANDROID_NDK_HOME:-${NDK_HOME:-}}/toolchains/llvm/prebuilt/linux-x86_64/bin
CC=$NDKBIN/aarch64-linux-android23-clang
CXX=$NDKBIN/aarch64-linux-android23-clang++
[ -x "$CC" ] || { echo "no NDK clang at $CC (set ANDROID_NDK_HOME)" >&2; exit 1; }

"$CC" -O2 -Wall -Wextra -Wno-unused-parameter -Werror -shared -fPIC \
  -o "$out/aemu-azygisk.so" "$here/bridge.c" -ldl -llog
"$CXX" -O2 -Wall -Wextra -Wno-unused-parameter -Werror -shared -fPIC \
  -static-libstdc++ \
  -o "$out/aemu-zygisk-testmod.so" "$here/testmod.cpp" -llog
ls -l "$out/aemu-azygisk.so" "$out/aemu-zygisk-testmod.so"
