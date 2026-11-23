#!/bin/sh
# Rebuilds src/main/assets/engines/kk/aemu-agent.jar (the guest agent service: hide-list identity sync and
# the APK import inbox; see AgentSupport on the host side).
# Needs: JDK 8+ (javac), an android.jar (API 19, like guest-bt: everything the agent uses is older than
# that) and d8 from Build Tools (or dx / dalvik-exchange).
#   ANDROID_JAR=/path/to/platforms/android-19/android.jar D8_JAR=/path/to/build-tools/<ver>/lib/d8.jar ./build.sh
set -e
here=$(cd "$(dirname "$0")" && pwd)
: "${ANDROID_JAR:?set ANDROID_JAR to an android.jar}"
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/dex"
javac -encoding UTF-8 --release 8 -nowarn -Xlint:-options -cp "$ANDROID_JAR" -d "$work/classes" \
    $(find "$here/java" -name '*.java')
if [ -n "${D8_JAR:-}" ]; then
    (cd "$work/classes" && java -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 19 --lib "$ANDROID_JAR" --output "$work/dex" $(find . -name "*.class"))
else
    DX=$(command -v dalvik-exchange || command -v dx)
    "$DX" --dex --min-sdk-version=19 --output="$work/dex/classes.dex" "$work/classes"
fi
out="$here/../src/main/assets/engines/kk/aemu-agent.jar"
(cd "$work/dex" && rm -f "$out" && zip -q -X "$out" classes.dex)
echo "wrote $out"
