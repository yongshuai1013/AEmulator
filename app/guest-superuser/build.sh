#!/bin/sh
# Guest Superuser stack: the su binary and its manager app, built from pinned upstream sources
# the same no-Gradle way app/guest-agent-app builds the inbox agent — straight from the NDK
# clang, the SDK build-tools and javac. Outputs land next to the other guest payloads:
#   assets/engines/kk/aemu-su             static aarch64 su; TreeFixer plants it at
#                                         /system/xbin/su mode 06755 while a VM's Superuser
#                                         switch is on (see SuperuserSupport).
#   assets/engines/kk/aemu-superuser.apk  the manager app; planted as the system app
#                                         /system/app/Superuser/Superuser.apk.
#
# Sources (Apache-2.0): koush/Superuser at the commit below (the app and the su C sources) and
# koush/Widgets at the commit below (the list UI library the app builds against). The Widgets
# pin needs a word of explanation: Superuser's local_manifest.xml only names Widgets' master
# branch, but master (frozen in May 2013) is not what the app was written against — its
# ListItem constructors take BetterListFragmentInternal while the app passes its
# BetterListFragment subclass, and the app's MainActivity imports android.support.v4.app.
# Fragment. The matching Widgets is the support branch at the commit below: the branch tip of
# 2014-11-21, the same day as the pinned Superuser commit ("fix notifications", one day after
# its "material update"). There ListItem takes BetterListFragment, BetterListFragment is a
# support-v4 Fragment and BetterListActivity an appcompat ActionBarActivity, exactly the
# shapes the app uses. That branch declares support-v4 21.+ and appcompat-v7 21.0.+ (its
# themes descend from Theme.AppCompat), so both 21.0.3 AARs are fetched from Google Maven
# below and pinned by sha256. Local changes to the pinned sources live in patches/ and are
# applied to the fresh clones; the libcutils one-liner su needs comes from the shim/ include
# directory instead of a patch.
#
# Upstream's app assets carry prebuilt su binaries for armeabi/mips/x86 (none for arm64) plus a
# recovery installer. They are NOT used — the su above is the only one — and the assets are left
# out of the packaged app entirely (no -A flag below), which also removes the app's own
# install-into-/system flow; the host plants both pieces instead.
#
# Needs a JDK (javac, keytool, jar), the Android SDK (aapt2, d8, zipalign, apksigner and the
# android-21 platform — the app's upstream compileSdk) and the NDK (ANDROID_NDK_HOME).
# Usage: ANDROID_HOME=/path/to/sdk ANDROID_NDK_HOME=/path/to/ndk sh build.sh
set -e
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
app=$here/..
out=$app/src/main/assets/engines/kk
mkdir -p "$out"

SUPERUSER_COMMIT=2e5cf1978193997806df583285f6660702a8af79
WIDGETS_COMMIT=4f5d879a63341f7ddc00df2ec4840cfea4087e1b
SUPPORT_V4_AAR_SHA256=703572d3015a088cc5604b7e38885af3d307c829d0c5ceaf8654ff41c71cd160
APPCOMPAT_AAR_SHA256=5dbeb5316d0a6027d646ae552804c3baa5e3bd53f7f33db50904d51505c8a0e5

SDK=${ANDROID_HOME:?set ANDROID_HOME}
JAR=$SDK/platforms/android-21/android.jar
[ -f "$JAR" ] || { echo "no android-21 platform in $SDK" >&2; exit 1; }
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)/
[ -x "${BT}aapt2" ] || { echo "no build-tools in $SDK" >&2; exit 1; }
NDKBIN=${ANDROID_NDK_HOME:-${NDK_HOME:-}}/toolchains/llvm/prebuilt/linux-x86_64/bin
CC=$NDKBIN/aarch64-linux-android23-clang
[ -x "$CC" ] || { echo "no NDK clang at $CC (set ANDROID_NDK_HOME)" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

git clone -q https://github.com/koush/Superuser.git "$work/Superuser"
git -C "$work/Superuser" checkout -q $SUPERUSER_COMMIT
git clone -q https://github.com/koush/Widgets.git "$work/Widgets"
git -C "$work/Widgets" checkout -q $WIDGETS_COMMIT
for p in "$here"/patches/superuser-*.patch; do git -C "$work/Superuser" apply "$p"; done
for p in "$here"/patches/widgets-*.patch; do if [ -f "$p" ]; then git -C "$work/Widgets" apply "$p"; fi; done

# ---- the two support libraries the pinned Widgets declares, as pinned AARs ----
mkdir -p "$work/support/v4" "$work/support/appcompat"
curl -sL -o "$work/support/v4.aar" "https://maven.google.com/com/android/support/support-v4/21.0.3/support-v4-21.0.3.aar"
curl -sL -o "$work/support/appcompat.aar" "https://maven.google.com/com/android/support/appcompat-v7/21.0.3/appcompat-v7-21.0.3.aar"
echo "$SUPPORT_V4_AAR_SHA256  $work/support/v4.aar" | sha256sum -c - >/dev/null
echo "$APPCOMPAT_AAR_SHA256  $work/support/appcompat.aar" | sha256sum -c - >/dev/null
(cd "$work/support/v4" && unzip -qo "$work/support/v4.aar" classes.jar libs/internal_impl-21.0.3.jar)
(cd "$work/support/appcompat" && unzip -qo "$work/support/appcompat.aar" classes.jar 'res/*')
V4="$work/support/v4/classes.jar"
V4IMPL="$work/support/v4/libs/internal_impl-21.0.3.jar"
COMPAT="$work/support/appcompat/classes.jar"
COMPATRES="$work/support/appcompat/res"

# ---- su: upstream's jni/Android.mk builds su/*.c + the vendored sqlite3 as one static binary ----
SU=$work/Superuser/Superuser/jni
"$CC" --target=aarch64-linux-android23 -O2 -static -Wall -Wno-unused-parameter \
    -I"$here/shim" -I"$SU" -I"$SU/sqlite3" -DSQLITE_OMIT_LOAD_EXTENSION \
    -o "$work/aemu-su" \
    "$SU/su/su.c" "$SU/su/activity.c" "$SU/su/db.c" "$SU/su/utils.c" "$SU/su/daemon.c" "$SU/su/pts.c" \
    "$SU/sqlite3/sqlite3.c"
cp "$work/aemu-su" "$out/aemu-su"
echo "guest-superuser: built $out/aemu-su from koush/Superuser $SUPERUSER_COMMIT"

# ---- manager app: aapt2 + javac + d8, like app/guest-agent-app ----
SUP=$work/Superuser/Superuser
WID=$work/Widgets/Widgets
mkdir -p "$work/gen" "$work/classes" "$work/compiled" "$work/dex"
"${BT}aapt2" compile --dir "$SUP/res" -o "$work/compiled/sup.zip"
"${BT}aapt2" compile --dir "$WID/res" -o "$work/compiled/wid.zip"
"${BT}aapt2" compile --dir "$COMPATRES" -o "$work/compiled/compat.zip"
"${BT}aapt2" link -o "$work/base.apk" --manifest "$SUP/AndroidManifest.xml" -I "$JAR" \
    --java "$work/gen" --extra-packages com.koushikdutta.widgets --extra-packages android.support.v7.appcompat \
    "$work/compiled/sup.zip" "$work/compiled/wid.zip" "$work/compiled/compat.zip"
find "$SUP/src" "$WID/src" "$work/gen" -name '*.java' > "$work/sources.txt"
javac --release 8 -cp "$JAR:$V4:$V4IMPL:$COMPAT" -d "$work/classes" @"$work/sources.txt"
(cd "$work/classes" && jar cf "$work/classes.jar" .)
"${BT}d8" --min-api 14 --lib "$JAR" --output "$work/dex" "$work/classes.jar" "$V4" "$V4IMPL" "$COMPAT"
cp "$work/base.apk" "$work/app.apk"
(cd "$work/dex" && zip -q "$work/app.apk" classes.dex)
"${BT}zipalign" -f 4 "$work/app.apk" "$work/aligned.apk"
KS="$here/superuser.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias superuser \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AEmulator Superuser,O=AEmulator" >/dev/null 2>&1
fi
"${BT}apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$out/aemu-superuser.apk" "$work/aligned.apk"
echo "guest-superuser: built $out/aemu-superuser.apk from koush/Superuser $SUPERUSER_COMMIT + koush/Widgets $WIDGETS_COMMIT"
