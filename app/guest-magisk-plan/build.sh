#!/bin/sh
# Guest magisk-plan stack: the genuine Magisk v30.7 daemon, built from the
# unmodified upstream sources by this project (Magisk tree, build.py native),
# riding in the official app's clothes — embedded in the host APK as an asset.
# The host plants the daemon payload into the guest through TreeFixer and
# installs this app inside the guest while a VM's magisk-plan switch is on
# (see MagiskPlanSupport); GuestVm wakes the daemon at the plan boot's own
# milestones, the supervisor's mount table and the planted selinuxfs answer
# its requests.
#
#   assets/engines/kk/aemu-magisk-plan.apk  the ONLY output: the official app
#     APK with its five arm64 payload slots replaced by the own-build
#     binaries (repack.py has the slot table), zipaligned and re-signed with
#     the AEmulator long-term key. TreeFixer extracts the daemon binaries,
#     busybox and scripts from this same file, so the app and the planted
#     payload can never drift apart.
#
# The official APK is pinned by version and sha256; a mismatch stops the build.
# Signing is not optional and fails loudly without the key: an unsigned or
# wrong-key package would be a different app to the guest's package manager.
#
# Needs python3, curl, the Android SDK (ANDROID_HOME; zipalign + apksigner
# from the newest build-tools) and a Java runtime for apksigner. Signing
# additionally needs AEMU_KEYSTORE_FILE (PKCS12, alias aemulator) and
# AEMU_KEYSTORE_PASSWORD. MAGISK_APK may point at a local official APK copy;
# otherwise it is downloaded. MAGISK_NATIVE_OUT must point at the directory
# holding the own-build arm64-v8a binaries (Magisk tree native/out/arm64-v8a).
# Usage: ANDROID_HOME=/path/to/sdk MAGISK_NATIVE_OUT=/path/to/out sh build.sh
set -e
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
app=$here/..
out=$app/src/main/assets/engines/kk
mkdir -p "$out"

SDK=${ANDROID_HOME:?set ANDROID_HOME}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)/
[ -x "${BT}zipalign" ] || { echo "no build-tools in $SDK" >&2; exit 1; }
NATIVE_OUT=${MAGISK_NATIVE_OUT:?set MAGISK_NATIVE_OUT (own-build arm64-v8a binaries)}
for f in magisk magiskinit magiskboot magiskpolicy libinit-ld.so; do
  [ -f "$NATIVE_OUT/$f" ] || { echo "own-build binary missing: $NATIVE_OUT/$f" >&2; exit 1; }
done

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

python3 "$here/repack.py" --src "$src_apk" \
  --native-out "$NATIVE_OUT" --out "$work/unsigned.apk"
"${BT}zipalign" -p -f 4096 "$work/unsigned.apk" "$work/aligned.apk"

KS=${AEMU_KEYSTORE_FILE:?set AEMU_KEYSTORE_FILE (AEmulator long-term PKCS12)}
if [ -z "${AEMU_KEYSTORE_PASSWORD:-}" ]; then echo "set AEMU_KEYSTORE_PASSWORD (the keystore password)" >&2; exit 1; fi
PW=$AEMU_KEYSTORE_PASSWORD
"${BT}apksigner" sign --ks "$KS" --ks-pass "pass:$PW" --ks-key-alias aemulator \
  --out "$out/aemu-magisk-plan.apk" "$work/aligned.apk"
"${BT}apksigner" verify --print-certs "$out/aemu-magisk-plan.apk" \
  | grep -qi "$CERT_SHA256" \
  || { echo "aemu-magisk-plan.apk is not signed with the AEmulator long-term key" >&2; exit 1; }
echo "built $out/aemu-magisk-plan.apk"
