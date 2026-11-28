#!/bin/sh
# Builds TcpTimingProbe.apk: a one-shot guest-side TCP timing probe (single
# Activity, no dependencies) that timestamps each step of a detoured connect on
# the guest's own clock, in three shapes - blocking, non-blocking with a
# Selector (Chrome's shape), and non-blocking plus a 1-byte probe write made
# before any wait for writability - so the numbers can be lined up against
# the host hub's log. Built without Gradle, like app/guest-agent-app.
#
# Output: TcpTimingProbe.apk next to this script, and a copy inside the host
# app's engine assets (aemu-tcp-probe.apk). CI uploads the standalone file as
# its own artifact; the bundled copy is planted into the guest tree by
# TreeFixer and installed by GuestVm at boot complete, so a guest carries the
# probe by default, as a plain user app - which is exactly what the
# measurement needs (a non-exempt guest process whose connects take the
# detour), and a guest has no file manager to install it by hand with.
# Signed with a self-generated keystore next to this script (probe.keystore,
# gitignored like every keystore here); a hand install accepts any signature,
# and a re-install of a differently signed rebuild needs an uninstall first -
# the boot-complete installer does that uninstall itself when a replace is
# refused.
#
# Needs a JDK (javac, keytool) and the Android SDK (aapt2, d8, zipalign,
# apksigner, android.jar; the android-23 platform is the one CI installs for
# this app's API level):
#   ANDROID_HOME=/path/to/sdk ./build.sh
set -e
here=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}
JAR="$SDK/platforms/android-23/android.jar"
[ -f "$JAR" ] || JAR=$(ls "$SDK"/platforms/*/android.jar | sort -V | tail -1)
BT=$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)
KS="$here/probe.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias probe \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AEmu TCP Probe" >/dev/null 2>&1
fi
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes"
"${BT}aapt2" link -o "$work/base.apk" --manifest "$here/AndroidManifest.xml" -I "$JAR"
javac -encoding UTF-8 --release 8 -nowarn -Xlint:-options -cp "$JAR" -d "$work/classes" \
    $(find "$here/java" -name '*.java')
(cd "$work/classes" && zip -q -X "$work/classes.jar" $(find . -name '*.class'))
"${BT}d8" --min-api 19 --lib "$JAR" --output "$work" "$work/classes.jar"
python3 -c "import zipfile;z=zipfile.ZipFile('$work/base.apk','a',zipfile.ZIP_DEFLATED);z.write('$work/classes.dex','classes.dex');z.close()"
"${BT}zipalign" -f 4 "$work/base.apk" "$work/aligned.apk"
out="$here/TcpTimingProbe.apk"
"${BT}apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$out" "$work/aligned.apk"
echo "wrote $out"
# the bundled copy the host app delivers into the guest by default: the same signed bytes
# as the standalone artifact, under the name TreeFixer and GuestVm look for
bundled="$here/../src/main/assets/engines/kk/aemu-tcp-probe.apk"
mkdir -p "$(dirname "$bundled")"
cp "$out" "$bundled"
echo "wrote $bundled"
