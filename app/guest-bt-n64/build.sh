#!/bin/sh
# Rebuilds the N64 guest Bluetooth jars:
#   src/main/assets/engines/kk/aemu-bt23.jar  (API 23, AIDL from AOSP android-6.0.1_r81)
#   src/main/assets/engines/kk/aemu-bt24.jar  (API 24-25, AIDL from AOSP android-7.1.2_r39)
#   src/main/assets/engines/kk/aemu-bt26.jar  (API 26-27, AIDL from AOSP android-8.1.0_r52)
# The guest-side service (app.aemu.stub.BtService) is one shared source tree (java/) plus one small
# per-group tree (java<group>/) holding that generation's BtAdapter/BtManager/BtGatt, because the
# IBluetooth* surfaces differ per generation. The android.* classes are pruned from the output: at run
# time the jar links against the guest framework's own stubs.
# Needs: JDK 8+ (javac), the `aidl` tool, D8 (D8_JAR) or dx, and the three android.jar files:
#   JAR23=/path/to/platforms/android-23/android.jar JAR24=.../android-25/... JAR26=.../android-27/... ./build.sh
set -e
here=$(cd "$(dirname "$0")" && pwd)
AIDL=${AIDL:-$(command -v aidl || true)}
: "${AIDL:?set AIDL to the aidl tool (build-tools)}"
JAR23=${JAR23:-${ANDROID_HOME:-}/platforms/android-23/android.jar}
JAR24=${JAR24:-${ANDROID_HOME:-}/platforms/android-25/android.jar}
JAR26=${JAR26:-${ANDROID_HOME:-}/platforms/android-27/android.jar}
DX=${DX:-$(command -v dalvik-exchange || command -v dx || true)}

build_group() {
    group=$1; jar=$2; minapi=$3; out=$4
    [ -f "$jar" ] || { echo "missing android.jar for group $group: $jar" >&2; exit 1; }
    work=$(mktemp -d)
    mkdir -p "$work/gen" "$work/classes" "$work/dex"
    for f in "$here/aidl$group"/android/bluetooth/I*.aidl "$here/aidl$group"/android/bluetooth/le/I*.aidl; do
        [ -f "$f" ] && "$AIDL" -I"$here/aidl$group" -o"$work/gen" "$f"
    done
    javac -encoding UTF-8 --release 8 -nowarn -Xlint:-options -cp "$jar" -sourcepath "$work/gen" -d "$work/classes" \
        $(find "$here/java" "$here/java$group" "$here/javastub" -name '*.java')
    # only our own classes go into the jar; android.bluetooth.* comes from the guest framework
    find "$work/classes" -path '*/android' -prune -exec rm -rf {} + 2>/dev/null || true
    if [ -n "${D8_JAR:-}" ]; then
        (cd "$work/classes" && java -cp "$D8_JAR" com.android.tools.r8.D8 --min-api "$minapi" --lib "$jar" \
            --output "$work/dex" $(find . -name '*.class'))
    else
        "$DX" --dex --min-sdk-version="$minapi" --output="$work/dex/classes.dex" "$work/classes"
    fi
    outpath="$here/../src/main/assets/engines/kk/$out"
    (cd "$work/dex" && rm -f "$outpath" && zip -q -X "$outpath" classes.dex)
    rm -rf "$work"
    echo "wrote $outpath"
}

build_group 23 "$JAR23" 23 aemu-bt23.jar
build_group 24 "$JAR24" 24 aemu-bt24.jar
build_group 26 "$JAR26" 26 aemu-bt26.jar
