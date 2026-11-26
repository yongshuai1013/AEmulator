#!/usr/bin/env python3
"""Repack the official Magisk v30.7 app APK into the AEmulator substitute build.

Slot table (design: daemon-substitute-desk.md, the repack section):
  lib/<arm64,arm32>/libmagisk.so        -> the substitute binary (this directory's
                                           substitute.c, compiled by build.sh)
  lib/<arm64,arm32>/libmagiskpolicy.so  -> the substitute binary (its argv[0] shape is
                                           the honest refusal shell: no policy is ever
                                           compiled or loaded in this environment)
  lib/<abi>/libbusybox.so               -> official bytes, untouched
  lib/<abi>/libmagiskinit.so,
  lib/<abi>/libmagiskboot.so,
  lib/<abi>/libinit-ld.so               -> official bytes, idle (never executed: there
                                           is no boot image to patch in the guest)
  assets/util_functions.sh              -> ours (upstream 30.7 verbatim except
                                           copy_preinit_files, see the file header)
  assets/app_functions.sh               -> ours (upstream 30.7 verbatim except the four
                                           hunks listed in the file header)
  assets/module_installer.sh and the
  remaining assets                      -> official bytes, untouched
  assets/stub.apk                       -> REMOVED (the stub-based patch-install flow
                                           cannot exist here; the app reports the
                                           extraction failure honestly instead of
                                           half-running it)
  lib/x86/**, lib/x86_64/**             -> REMOVED (no x86 guest exists in this
                                           product; the app only ever extracts the
                                           running ABI)
  META-INF/CERT.*, META-INF/MANIFEST.MF -> REMOVED (build.sh re-signs with the
                                           AEmulator long-term key)

Everything else is copied byte for byte with its compression type preserved.
The script fails loudly if a slot it expects is not in the source APK, so an
upstream layout change can never silently produce a stock-looking package.
"""

import argparse
import pathlib
import sys
import zipfile

DROP_EXACT = {
    "assets/stub.apk",
    "META-INF/CERT.RSA",
    "META-INF/CERT.SF",
    "META-INF/MANIFEST.MF",
}
DROP_PREFIX = ("lib/x86/", "lib/x86_64/")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="official Magisk-v30.7.apk")
    ap.add_argument("--subst64", required=True, help="substitute binary, aarch64")
    ap.add_argument("--subst32", required=True, help="substitute binary, armv7a")
    ap.add_argument("--scripts-dir", required=True,
                    help="directory holding our util_functions.sh / app_functions.sh")
    ap.add_argument("--out", required=True, help="unsigned, unaligned output APK")
    args = ap.parse_args()

    scripts = pathlib.Path(args.scripts_dir)
    subst64 = pathlib.Path(args.subst64).read_bytes()
    subst32 = pathlib.Path(args.subst32).read_bytes()
    replacements = {
        "lib/arm64-v8a/libmagisk.so": subst64,
        "lib/armeabi-v7a/libmagisk.so": subst32,
        "lib/arm64-v8a/libmagiskpolicy.so": subst64,
        "lib/armeabi-v7a/libmagiskpolicy.so": subst32,
        "assets/util_functions.sh": (scripts / "util_functions.sh").read_bytes(),
        "assets/app_functions.sh": (scripts / "app_functions.sh").read_bytes(),
    }
    util = replacements["assets/util_functions.sh"].decode("utf-8")
    if "MAGISK_VER='30.7'" not in util or "MAGISK_VER_CODE=30700" not in util:
        print("repack: our util_functions.sh lost the 30.7 / 30700 version lines",
              file=sys.stderr)
        return 1

    dropped_stub = False
    with zipfile.ZipFile(args.src) as zin, \
            zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            name = info.filename
            if name in DROP_EXACT or name.startswith(DROP_PREFIX):
                if name == "assets/stub.apk":
                    dropped_stub = True
                print(f"drop     {name}")
                continue
            if name in replacements:
                data = replacements.pop(name)
                print(f"replace  {name} ({info.file_size} -> {len(data)})")
            else:
                data = zin.read(name)
            entry = zipfile.ZipInfo(name, date_time=info.date_time)
            entry.compress_type = info.compress_type
            entry.external_attr = info.external_attr
            entry.internal_attr = info.internal_attr
            entry.create_system = info.create_system
            zout.writestr(entry, data)

    if replacements:
        print(f"repack: slots missing from the source APK: {sorted(replacements)}",
              file=sys.stderr)
        return 1
    if not dropped_stub:
        print("repack: assets/stub.apk was not in the source APK to drop",
              file=sys.stderr)
        return 1
    out_size = pathlib.Path(args.out).stat().st_size
    print(f"repack: wrote {args.out} ({out_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
