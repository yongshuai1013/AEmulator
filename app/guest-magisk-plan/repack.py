#!/usr/bin/env python3
"""Repack the official Magisk v30.7 app APK into the AEmulator magisk-plan build.

Slot table (design: magisk-plan-inventory-desk.md H1 + magisk-plan-two-gaps-desk.md):
  lib/arm64-v8a/libmagisk.so        -> this project's own build of the v30.7
                                       daemon binary (native/out/arm64-v8a/magisk)
  lib/arm64-v8a/libmagiskpolicy.so  -> own build (…/magiskpolicy)
  lib/arm64-v8a/libmagiskinit.so    -> own build (…/magiskinit)
  lib/arm64-v8a/libmagiskboot.so    -> own build (…/magiskboot)
  lib/arm64-v8a/libinit-ld.so       -> own build (…/libinit-ld.so)
  everything else                   -> official bytes, untouched — the manager
                                       app's own code, the scripts, busybox,
                                       the 32-bit payload, assets/stub.apk.
  lib/x86/**, lib/x86_64/**         -> REMOVED (no x86 guest exists in this
                                       product; the app only ever extracts the
                                       running ABI — same cut as the substitute)
  META-INF/CERT.*, META-INF/MANIFEST.MF -> REMOVED (build.sh re-signs with the
                                       AEmulator long-term key)

Unlike the substitute repack, no daemon is impersonated here: the five slots
above are the genuine article, built from the unmodified v30.7 sources, and
the scripts stay upstream's — the plan guest answers the daemon's requests
(the supervisor's mount table, the planted selinuxfs) instead of editing the
daemon into asking for less.

Everything else is copied byte for byte with its compression type preserved.
The script fails loudly if a slot it expects is not in the source APK, so an
upstream layout change can never silently produce a stock-looking package.
"""

import argparse
import pathlib
import sys
import zipfile

DROP_EXACT = {
    "META-INF/CERT.RSA",
    "META-INF/CERT.SF",
    "META-INF/MANIFEST.MF",
}
DROP_PREFIX = ("lib/x86/", "lib/x86_64/")

SLOTS = {
    "lib/arm64-v8a/libmagisk.so": "magisk",
    "lib/arm64-v8a/libmagiskpolicy.so": "magiskpolicy",
    "lib/arm64-v8a/libmagiskinit.so": "magiskinit",
    "lib/arm64-v8a/libmagiskboot.so": "magiskboot",
    "lib/arm64-v8a/libinit-ld.so": "libinit-ld.so",
}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="official Magisk-v30.7.apk")
    ap.add_argument("--native-out", required=True,
                    help="directory holding the own-build arm64-v8a binaries")
    ap.add_argument("--out", required=True, help="unsigned, unaligned output APK")
    args = ap.parse_args()

    native_out = pathlib.Path(args.native_out)
    replacements = {}
    for entry, fname in SLOTS.items():
        f = native_out / fname
        if not f.is_file():
            print(f"repack-plan: own-build binary missing: {f}", file=sys.stderr)
            return 1
        replacements[entry] = f.read_bytes()

    with zipfile.ZipFile(args.src) as zin, \
            zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            name = info.filename
            if name in DROP_EXACT or name.startswith(DROP_PREFIX):
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
        print(f"repack-plan: slots missing from the source APK: {sorted(replacements)}",
              file=sys.stderr)
        return 1
    out_size = pathlib.Path(args.out).stat().st_size
    print(f"repack-plan: wrote {args.out} ({out_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
