#!/bin/sh
set -e
ZIG=${ZIG:-zig}
command -v "$ZIG" >/dev/null 2>&1 || ZIG="python3 -m ziglang"
cd "$(dirname "$0")"
OUT=${1:-../../app/src/main/assets/engines/radio-hal/vendor#bin#hw#android.hardware.radio@1.0-service.aemu}
mkdir -p "$(dirname "$OUT")"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
TARGET="-target aarch64-linux-android"

LIBC_FUNCS="memcpy memset strlen strncmp open close read ioctl mmap usleep poll socket connect _exit __libc_init"
for f in $LIBC_FUNCS; do echo "void $f(void) { for (;;) {} }"; done > "$T/libc_stub.c"
echo "void __android_log_print(void) {}" > "$T/liblog_stub.c"
for n in c log; do
  $ZIG cc $TARGET -w -fno-builtin -fno-sanitize=undefined -nostdlib -shared -fPIC -Wl,-soname,lib$n.so -Wl,--build-id=none \
      -o "$T/lib$n.so" "$T/lib${n}_stub.c"
done

$ZIG cc $TARGET -O2 -fPIE -fno-sanitize=undefined -ffreestanding -nostdlib -fno-stack-protector -fno-unwind-tables -fno-asynchronous-unwind-tables \
  -Wall -Wextra -Wno-unused-parameter -pie -Wl,--dynamic-linker=/system/bin/linker64 -Wl,--build-id=none \
  -Wl,--hash-style=both -Wl,-z,now -Wl,-z,relro -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,-e,_start -Wl,-s \
  -o "$OUT" radio_service.c "$T/liblog.so" "$T/libc.so"
echo "built $OUT ($(wc -c < "$OUT") bytes)"
