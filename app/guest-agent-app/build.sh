#!/bin/sh
# Builds src/main/assets/engines/kk/aemu-agent.apk: the guest agent's app (AndroidManifest.xml +
# ManagerActivity — the Root Manager — InboxActivity and AgentClient), without Gradle, like
# tools/guesttest. Signed with a self-generated keystore next to
# this script (agent.keystore, gitignored like every keystore here): the app is ours alone, installed by
# the host through pm, and its updates are recognised by package + versionCode, not by signature continuity
# with anything else.
# The APK also carries the isolation probe binary (isoprobe.c, compiled here with the NDK and stored
# in the zip as "aemu-isoprobe"): the agent service extracts and runs it for the probe page. A build
# without the NDK fails loudly rather than shipping an app whose probe page cannot run.
# Needs a JDK (javac, keytool), the Android SDK (aapt2, d8, zipalign, apksigner, android.jar) and
# the NDK (ANDROID_NDK_HOME, or an ndk/ directory inside the SDK):
#   ANDROID_HOME=/path/to/sdk ./build.sh     (android-19 jar is enough; BT picks the newest build-tools)
set -e
here=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}
JAR="$SDK/platforms/android-19/android.jar"
[ -f "$JAR" ] || JAR=$(ls "$SDK"/platforms/*/android.jar | sort -V | tail -1)
BT=$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)
KS="$here/agent.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias agent \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AEmu Guest Agent" >/dev/null 2>&1
fi
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes"
"${BT}aapt2" link -o "$work/base.apk" --manifest "$here/AndroidManifest.xml" -I "$JAR"
javac -encoding UTF-8 --release 8 -nowarn -Xlint:-options -cp "$JAR" -d "$work/classes" \
    $(find "$here/java" -name '*.java')
(cd "$work/classes" && zip -q -X "$work/classes.jar" $(find . -name '*.class'))
"${BT}d8" --min-api 14 --lib "$JAR" --output "$work" "$work/classes.jar"
NDK=${ANDROID_NDK_HOME:-${NDK:-}}
if [ -z "$NDK" ] && [ -d "$SDK/ndk" ]; then NDK=$(ls -d "$SDK"/ndk/*/ 2>/dev/null | sort -V | tail -1); fi
CBIN=""
if [ -n "$NDK" ]; then
    CBIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
    [ -x "$CBIN/clang" ] || CBIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
fi
[ -n "$CBIN" ] && [ -x "$CBIN/clang" ] || { echo "isoprobe: no NDK clang found (set ANDROID_NDK_HOME)"; exit 1; }
"$CBIN/clang" --target=aarch64-linux-android23 -O2 -static -Wall -o "$work/aemu-isoprobe" "$here/isoprobe.c"
python3 -c "import zipfile;z=zipfile.ZipFile('$work/base.apk','a',zipfile.ZIP_DEFLATED);z.write('$work/classes.dex','classes.dex');z.write('$work/aemu-isoprobe','aemu-isoprobe',zipfile.ZIP_STORED);z.close()"
"${BT}zipalign" -f 4 "$work/base.apk" "$work/aligned.apk"
out="$here/../src/main/assets/engines/kk/aemu-agent.apk"
"${BT}apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$out" "$work/aligned.apk"
echo "wrote $out"
