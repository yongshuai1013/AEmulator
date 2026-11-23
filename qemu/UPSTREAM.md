# Upstream provenance

The build materials in this directory (`build-android.sh`, `patches/`,
`src/`, `COPYING`, `NOTICE`, `licenses/`, `BUILD-MANIFEST.txt`) are vendored
verbatim from the SENSES emulator project:

- Repository: https://github.com/Istratii-tech/SENSES
- Commit: `0ec68b7a6733e01174f9a768a0c2410a0871fd67`
- License: GPL-2.0 (see `COPYING`; QEMU itself is GPL-2.0, see
  `licenses/QEMU-LICENSE.txt`)

`BUILD-MANIFEST.txt` is the upstream project's own build record, kept as
the format reference for the manifests our builds produce (our build
outputs land in `qemu/build/`).

`build-qemu.sh` is this project's derived build script: a copy of
`build-android.sh` with a single functional change — a `TARGET` knob
(`arm` → `arm-linux-user` / `qemu-arm`, `i386` → `i386-linux-user` /
`qemu-i386`). The original script is kept verbatim above for provenance;
the derived script records its own sha256 in the BUILD-MANIFEST it
produces.
