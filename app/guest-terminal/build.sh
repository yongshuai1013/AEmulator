#!/bin/sh
# Guest terminal app: jackpal/Android-Terminal-Emulator ("Terminal Emulator for Android"),
# built from pinned upstream source the same no-Gradle way app/guest-superuser builds the
# Superuser stack — NDK clang for the JNI libraries, aapt2 + javac + d8 for the Java.
# Output lands next to the other guest payloads:
#   assets/engines/kk/aemu-terminal.apk   TreeFixer plants it as a system app in every
#                                         api >= 14 guest on the KK and N64 engines
#                                         (see TerminalSupport). No switch: always planted.
#
# Source (Apache-2.0): jackpal/Android-Terminal-Emulator at the commit below — the tip of
# master, i.e. the v1.0.70 release (versionCode 71, versionName 1.0.70) plus doc-only
# commits and the revert of the TV-mode merge; no code changes after that. Upstream's own
# build is Gradle 2.2.1 / AGP 1.0.0 against the long-dead jcenter, so it cannot run on the
# CI toolchain; the manual pipeline below mirrors upstream's configuration instead. The
# sources are compiled against the android-21 platform (CI already installs it for the
# superuser build): upstream's compileSdk was 22, but the code predates API 22, uses
# nothing from it, and still uses android.util.FloatMath, which the android-23 jar removed.
# targetSdkVersion 22 and the app version stay upstream's; minSdkVersion is raised from
# upstream's 4 to 14 — the oldest guest this app is planted into, and the d8 floor the
# superuser build already uses. Local changes to the pinned sources live in patches/ and
# are applied to the fresh clone.
#
# The app is three upstream modules: term (the app), emulatorview (the terminal view
# library) and libtermexec (the remote-exec library TermService binds to; one AIDL
# interface). Two JNI libraries: libjackpal-androidterm5 (the app's own pty/exec glue) and
# libjackpal-termexec2 (libtermexec's process helper), each built for arm64-v8a (N64
# guests) and armeabi-v7a (KK guests, and 32-bit processes on N64 guests). The v7a build
# uses the NDK's android-21 wrapper — r28 ships no lower one — and stays safe on the api
# 19 guest because the pty code only calls pre-ICS libc staples (audited from the built
# library's undefined symbols when this build was introduced; see the batch VERIFY). libtermexec's
# one support-annotations use is satisfied by the pinned jar fetched below (compile-only;
# the annotations are CLASS-retention and are not packaged). Upstream's Apache-2.0 LICENSE
# and NOTICE are copied into the APK.
#
# Needs a JDK (javac, keytool, jar), the Android SDK (aapt2, aidl, d8, zipalign, apksigner
# and the android-21 platform) and the NDK (ANDROID_NDK_HOME).
# Usage: ANDROID_HOME=/path/to/sdk ANDROID_NDK_HOME=/path/to/ndk sh build.sh
set -e
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
app=$here/..
out=$app/src/main/assets/engines/kk
mkdir -p "$out"

TERMINAL_COMMIT=35188f8a8b57989a4a4ec9485e11187b46be26d9
ANNOTATIONS_SHA256=e9e817959d1b846349e93b796537bdaef84aef2c67d8b8b2acc31c6ef540d559

SDK=${ANDROID_HOME:?set ANDROID_HOME}
JAR=$SDK/platforms/android-21/android.jar
[ -f "$JAR" ] || { echo "no android-21 platform in $SDK" >&2; exit 1; }
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)/
[ -x "${BT}aapt2" ] || { echo "no build-tools in $SDK" >&2; exit 1; }
NDKBIN=${ANDROID_NDK_HOME:-${NDK_HOME:-}}/toolchains/llvm/prebuilt/linux-x86_64/bin
CXX64=$NDKBIN/aarch64-linux-android21-clang++
CXX32=$NDKBIN/armv7a-linux-androideabi21-clang++
[ -x "$CXX64" ] || { echo "no NDK clang++ at $CXX64 (set ANDROID_NDK_HOME)" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

git clone -q https://github.com/jackpal/Android-Terminal-Emulator.git "$work/ate"
git -C "$work/ate" checkout -q $TERMINAL_COMMIT
for p in "$here"/patches/*.patch; do if [ -f "$p" ]; then git -C "$work/ate" apply "$p"; fi; done

TERM=$work/ate/term
EV=$work/ate/emulatorview
TE=$work/ate/libtermexec

# ---- the one compile-only dependency: support-annotations (libtermexec's @NonNull) ----
curl -sL -o "$work/annotations.jar" "https://maven.google.com/com/android/support/support-annotations/21.0.0/support-annotations-21.0.0.jar"
echo "$ANNOTATIONS_SHA256  $work/annotations.jar" | sha256sum -c - >/dev/null

# ---- JNI libraries, both ABIs ----
# -static-libstdc++: link the C++ runtime into each library. The default dynamic
# link leaves a DT_NEEDED on libc++_shared.so, which the APK does not carry —
# the second field run opened the window, then died in TermExec.<clinit> with
# "dlopen failed: library libc++_shared.so not found" when the first session
# loaded the library. With the runtime linked in, the only DT_NEEDED entries
# left are system libraries (guarded at the end of this script).
mkdir -p "$work/stage/lib/arm64-v8a" "$work/stage/lib/armeabi-v7a"
for abi in arm64-v8a armeabi-v7a; do
    if [ "$abi" = arm64-v8a ]; then CXX=$CXX64; else CXX=$CXX32; fi
    "$CXX" -O2 -fPIC -shared -static-libstdc++ -o "$work/stage/lib/$abi/libjackpal-androidterm5.so" \
        "$TERM/src/main/jni/common.cpp" "$TERM/src/main/jni/fileCompat.cpp" "$TERM/src/main/jni/termExec.cpp" -llog
    "$CXX" -O2 -fPIC -shared -static-libstdc++ -o "$work/stage/lib/$abi/libjackpal-termexec2.so" \
        "$TE/src/main/jni/process.cpp" -llog
done

# ---- AIDL (libtermexec's single interface) ----
mkdir -p "$work/gen"
"${BT}aidl" -p"$SDK/platforms/android-21/framework.aidl" -I"$TE/src/main/aidl" -o "$work/gen" \
    "$TE/src/main/aidl/jackpal/androidterm/libtermexec/v1/ITerminal.aidl"

# ---- resources + manifest ----
mkdir -p "$work/compiled" "$work/classes" "$work/dex"
"${BT}aapt2" compile --dir "$TERM/src/main/res" -o "$work/compiled/term.zip"
"${BT}aapt2" compile --dir "$EV/src/main/res" -o "$work/compiled/ev.zip"
"${BT}aapt2" link -o "$work/base.apk" --manifest "$TERM/src/main/AndroidManifest.xml" -I "$JAR" \
    --java "$work/gen" --extra-packages jackpal.androidterm.emulatorview \
    --min-sdk-version 14 --target-sdk-version 22 \
    "$work/compiled/term.zip" "$work/compiled/ev.zip"

# ---- Java ----
find "$TERM/src/main/java" "$EV/src/main/java" "$TE/src/main/java" "$work/gen" -name '*.java' > "$work/sources.txt"
javac --release 8 -encoding UTF-8 -cp "$JAR:$work/annotations.jar" -d "$work/classes" @"$work/sources.txt"
(cd "$work/classes" && jar cf "$work/classes.jar" .)
"${BT}d8" --min-api 14 --lib "$JAR" --output "$work/dex" "$work/classes.jar"

# ---- assemble, align, sign ----
cp "$work/ate/LICENSE" "$work/ate/NOTICE" "$work/stage/"
cp "$work/base.apk" "$work/app.apk"
(cd "$work/dex" && zip -q "$work/app.apk" classes.dex)
# The JNI libraries go in STORED (uncompressed) and page-aligned: the guest loads them
# straight out of the APK (the classloader's search path includes Terminal.apk!/lib/<abi>,
# and the guest's package manager does not extract a lib/ directory next to the planted
# system app), and a deflated .so cannot be mapped in place — the first field run died
# with UnsatisfiedLinkError on libjackpal-termexec2.so. LICENSE/NOTICE keep the default
# compression; they are read as streams, not mapped.
(cd "$work/stage" && zip -q -0 -r "$work/app.apk" lib)
(cd "$work/stage" && zip -q "$work/app.apk" LICENSE NOTICE)
"${BT}zipalign" -f -p 4 "$work/app.apk" "$work/aligned.apk"
KS="$here/terminal.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias terminal \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AEmulator Terminal,O=AEmulator" >/dev/null 2>&1
fi
"${BT}apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$out/aemu-terminal.apk" "$work/aligned.apk"
# Guard the stored-.so rule above: fail the build if any packaged native library ended
# up compressed, so this defect class cannot ship silently again.
python3 - "$out/aemu-terminal.apk" <<'PYEOF'
import sys, zipfile
bad = [i.filename for i in zipfile.ZipFile(sys.argv[1]).infolist()
       if i.filename.startswith("lib/") and i.filename.endswith(".so")
       and i.compress_type != zipfile.ZIP_STORED]
if bad:
    print("guest-terminal: compressed native libraries in APK: " + ", ".join(bad),
          file=sys.stderr)
    sys.exit(1)
PYEOF
# Guard the static-C++-runtime rule above: fail the build if any packaged native
# library DT_NEEDEDs a library the guest does not provide as a system library —
# the libc++_shared.so miss shipped silently once and only died on the device.
READELF=readelf
[ -x "${NDKBIN}llvm-readelf" ] && READELF=${NDKBIN}llvm-readelf
mkdir -p "$work/needed"
python3 - "$out/aemu-terminal.apk" "$work/needed" <<'PYEOF'
import os, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
for i in z.infolist():
    if i.filename.startswith("lib/") and i.filename.endswith(".so"):
        z.extract(i, sys.argv[2])
PYEOF
needed_bad=0
for so in $(find "$work/needed" -name '*.so' | sort); do
    for lib in $("$READELF" -d "$so" | sed -n 's/.*Shared library: \[\(.*\)\].*/\1/p'); do
        case "$lib" in
            libc.so|libm.so|libdl.so|liblog.so|libandroid.so|libstdc++.so) ;;
            *) echo "guest-terminal: $so needs non-system library $lib" >&2; needed_bad=1 ;;
        esac
    done
done
[ "$needed_bad" -eq 0 ] || exit 1
echo "guest-terminal: built $out/aemu-terminal.apk from jackpal/Android-Terminal-Emulator $TERMINAL_COMMIT"
