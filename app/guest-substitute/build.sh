#!/bin/sh
# Guest Magisk substitute stack: our daemon (substitute.c) in the official Magisk
# v30.7 app's clothes, embedded in the host APK as an asset. The host plants the
# daemon payload into the guest through TreeFixer and installs this app inside the
# guest while a VM's Magisk substitute switch is on (see SubstituteSupport).
#
#   assets/engines/kk/aemu-magisk-sub.apk  the ONLY output: the official app APK
#     with its payload slots replaced (repack.py has the slot table), zipaligned
#     and re-signed with the AEmulator long-term key. TreeFixer extracts the
#     daemon binaries, busybox and scripts from this same file, so the app and
#     the planted payload can never drift apart.
#
# The official APK is pinned by version and sha256; a mismatch stops the build.
# Signing is not optional and fails loudly without the key: an unsigned or
# wrong-key package would be a different app to the guest's package manager.
#
# Needs python3, curl, the Android SDK (ANDROID_HOME; zipalign + apksigner from
# the newest build-tools) and the NDK (ANDROID_NDK_HOME). Signing additionally
# needs AEMU_KEYSTORE_FILE (PKCS12, alias aemulator) and AEMU_KEYSTORE_PASSWORD.
# MAGISK_APK may point at a local official APK copy; otherwise it is downloaded.
# Usage: ANDROID_HOME=/path/to/sdk ANDROID_NDK_HOME=/path/to/ndk sh build.sh
set -e
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
app=$here/..
out=$app/src/main/assets/engines/kk
mkdir -p "$out"

SDK=${ANDROID_HOME:?set ANDROID_HOME}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)/
[ -x "${BT}zipalign" ] || { echo "no build-tools in $SDK" >&2; exit 1; }
NDKBIN=${ANDROID_NDK_HOME:-${NDK_HOME:-}}/toolchains/llvm/prebuilt/linux-x86_64/bin
CC64=$NDKBIN/aarch64-linux-android23-clang
CC32=$NDKBIN/armv7a-linux-androideabi23-clang
[ -x "$CC64" ] || { echo "no NDK clang at $CC64 (set ANDROID_NDK_HOME)" >&2; exit 1; }

APK_SHA256=e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5
CERT_SHA256=d019b4c24d035c8bdf91cac986cb2f5b170447c500051abf2c9ff131420a155b
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

src_apk=$work/Magisk-v30.7.apk
if [ -n "${MAGISK_APK:-}" ]; then
  cp "$MAGISK_APK" "$src_apk"
else
  curl -fL -o "$src_apk" https://github.com/topjohnwu/Magisk/releases/download/v30.7/Magisk-v30.7.apk
fi
echo "$APK_SHA256  $src_apk" | sha256sum -c -

# The substitute daemon, both guest ABIs, at the tree's zero-warning bar.
"$CC64" -O2 -Wall -Wextra -Wno-unused-parameter -Werror \
  -o "$work/subst64" "$here/substitute.c" -ldl
"$CC32" -O2 -Wall -Wextra -Wno-unused-parameter -Werror \
  -o "$work/subst32" "$here/substitute.c" -ldl

python3 "$here/repack.py" --src "$src_apk" \
  --subst64 "$work/subst64" --subst32 "$work/subst32" \
  --scripts-dir "$here" --out "$work/unsigned.apk"
"${BT}zipalign" -p -f 4096 "$work/unsigned.apk" "$work/aligned.apk"

KS=${AEMU_KEYSTORE_FILE:?set AEMU_KEYSTORE_FILE (AEmulator long-term PKCS12)}
PW=${AEMU_KEYSTORE_PASSWORD:?set AEMU_KEYSTORE_PASSWORD}
"${BT}apksigner" sign --ks "$KS" --ks-pass "pass:$PW" --ks-key-alias aemulator \
  --out "$out/aemu-magisk-sub.apk" "$work/aligned.apk"
"${BT}apksigner" verify --print-certs "$out/aemu-magisk-sub.apk" \
  | grep -qi "$CERT_SHA256" \
  || { echo "aemu-magisk-sub.apk is not signed with the AEmulator long-term key" >&2; exit 1; }
echo "built $out/aemu-magisk-sub.apk"
