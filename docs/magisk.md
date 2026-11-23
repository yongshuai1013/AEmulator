# Magisk on the native (arm64) engines

Settings of a VM › **Root (Magisk)**: pick the full Magisk APK once (tested with v31.1), then switch **Root** on. It
applies at the next full boot. Only offered for an arm64 firmware with a 64-bit side (`/system/bin/linker64`) on the
native engine, Android 5.0+ (Magisk's own minimum may be higher: 31.x supports Android 6.0+, so on 5.x expect the
daemon not to start).

## How it works

The native engine has no kernel and no real init, so Magisk's boot stages are played by the host
(`MagiskCore.kt`, `GuestVm.kt`):

| Magisk expects                          | Here                                                                                  |
|-----------------------------------------|---------------------------------------------------------------------------------------|
| magiskinit patches the ramdisk          | not needed; `install` unpacks the APK's binaries/scripts into `/data/adb/magisk`      |
| tmpfs at `/debug_ramdisk` (or `/sbin`)  | a plain directory rebuilt before every boot (`prepareTmp`): `magisk`, `su`/`resetprop`/`magiskpolicy` applets, `.magisk/{busybox,device,modules,...}` |
| `su` in PATH (bind mount)               | symlinks `/system/{bin,xbin}/{su,magisk,resetprop,magiskpolicy}` → `/debug_ramdisk/…`; a ROM's own `su` is kept as `su.aemu-orig` |
| init triggers                           | `magisk --post-fs-data` before the first service, `--service` after the last, `--boot-complete` on `sys.boot_completed` |
| SO_PEERCRED / ids / SELinux files       | already faked by the shim (`run64.c`: per-process ids, `/proc/*/attr/*`, `/sys/fs/selinux/enforce`) |
| `setns` into the requester's namespace  | accepted and ignored, like `unshare` (`tools/patch_guest64_setns.py`; trapped for real since `patch_guest64_vmount.py`, the first patch's table entry had overflowed a byte) |
| `mount` / `umount` by modules and scripts | **real** (`native/guest64/vmount.h`): bind (file or directory, `--rbind`), tmpfs, `--move`, `umount`, listed in `/proc/mounts` and `mountinfo` - see below |
| `mknod x c 0 0` (whiteouts)             | **real**: a device node is a marker link the guest sees as a device; `Overlay` deletes the stock file for a 0:0 one |
| magic mount of modules                  | `MagiskCore.Overlay`: module `system/` files are copied into the tree after post-fs-data and **reverted exactly** at the next boot (journal in `/data/adb/aemu-overlay`), so disabling/removing a module works |
| the Magisk app                          | `pm install -r` of the picked APK once the system is up: an import queues it (`data/adb/aemu-magisk.install`), the next boot installs it even with Root off and deletes the flag as soon as the install has been called (one shot, whatever `pm` answered; re-import to queue it again) |

## Mounts and device nodes (vmount.h)

On when Root is on (`MagiskCore.prepareTmp` writes `<root>/dev/.aemu-vmount`; without that file the shim is exactly as before).
The shim has no kernel to ask, so it keeps a mount table (`<root>/dev/.aemu-mounts`, shared by every guest process, deleted
before each boot) that path translation consults on every absolute path.

* `mount -o bind SRC DST`, `--rbind`, `mount -t tmpfs tmpfs DST` (mode= honoured), `--move`, `umount`/`-l`; remounts and
  propagation flags succeed without doing anything. Stacking works (the certificate recipe: tmpfs over
  `/system/etc/security/cacerts`, copy, bind that over `/apex/com.android.conscrypt/cacerts`). Missing target = ENOENT, file
  on directory = ENOTDIR, `-t overlay`/ext4/loop = ENODEV, as on Linux.
* Only where a root user mounts: `/system /system_ext /product /vendor /odm /oem /*_dlkm /apex/<name>/... /debug_ramdisk /sbin
  /data/adb /data/local /data/misc` (not those directories themselves). Everything else the firmware mounts while booting
  (`/dev /proc /sys /data/data /storage /mnt /linkerconfig /apex/<name>`) is still accepted and ignored: the image is built
  on that. Magisk's own daemon is also left alone - `Overlay` already does its magic mount.
* `mknod c|b` makes a link with target `\1aemu-dev:c:MAJ:MIN:MODE`; stat/statx/getdents/readlink/access/open show a device.
  A 0:0 character device deletes the stock file or directory of that name when `Overlay` applies the module (and is undone
  at the next boot like every overlay change).

Limits: one table for the VM, no per-process namespaces (setns/unshare stay no-ops, so a mount is visible to every app,
including a DenyList one); relative paths and `*at()` calls relative to a directory descriptor do not see mounts; a mount
of a path reached only through a symlink other than the one it was made by is not seen under the third spelling.
**Not run on a device** - the table logic is covered by `native/guest64/tests/vmount_test.sh` (x86 host, real files).

Turning Root off, or **Remove Magisk**, puts the tree back (overlay, links, stand-in tmpfs); modules and `magisk.db` stay.

## Owner access (mode 000)

Magisk makes some directories with mode 000 (the magic-mount worker, `.magisk/worker/system`) and leaves `magisk.db` at 000. A
real root walks through; the guest's "root" is the plain host user, so it got `mkdir ... Permission denied`. With Root on, the
emulated root (uid 0) never loses its owner bits: `vm_mode_fix()` in `native/guest64/vmount.h` adds rwx for the owner to
`mkdirat` and to `fchmodat`/`fchmodat2` of a directory, rw for anything else (group/other bits stay as asked; other uids and a
VM with Root off are untouched). Wired into every `run64.c` by `tools/patch_guest64_ownerbits.py`; covered by
`native/guest64/tests/vmount_test.sh`. Not covered: `fchmod(fd)` (not trapped), a file *created* with mode 000 by `open`; the
host side (`MagiskCore.unlockTmp`, `fixDbMode`) still cleans those up. **Needs the rebuild below; not run on a device.**

## su answered "Accessed denied" (fixed 2026-11-21)

Magisk's daemon serves a non-root client (the Magisk app, any app asking for su) only if `stat("/proc/<client pid>/exe")` is
the same file as its own `stat("/proc/self/exe")` (`daemon.rs`, `is_client`, feature `check-client`). The shim redirected only
`/proc/self/exe` to the guest program, so for any other process the answer was the shim itself: every app's `su` failed
before a policy was looked at (the allow policy that `magiskManagerSetup` writes could never help). Now each process leaves its
guest program in `<root>/dev/.aemu-exe/<pid>` (`vm_exe_put`, only with Root on) and `/proc/<pid>/exe` is answered from there for
stat, open and readlink (`vm_proc_exe`, `tools/patch_guest64_procexe.py`). **Needs the rebuild; not run on a device.**
A process that only forks (no exec) publishes nothing and keeps the old answer.

## "Your device needs reflash Magisk" in the app (fixed 2026-11-21)

With `su` working, the app ran `env_check` (`app_functions.sh`), which wants a block device at `/debug_ramdisk/.magisk/device/preinit`
(exit code 2 = the app's "full fix" message; reinstalling cannot help, there is no boot image). `MagiskCore.prepareTmp` now leaves a
block-device marker there (`b:259:1:600`, see vmount.h), so `[ -b ... ]` is true. Needs no shim rebuild beyond the one above.

## Zygisk (added 2026-11-21, not run on a device)

Switch **Zygisk** on in the Magisk app (Settings), then do a full boot of the VM. Zygisk is a native bridge, so the host does what
the daemon's magic mount and init would:

| Magisk does                                            | Here                                                                                       |
|--------------------------------------------------------|--------------------------------------------------------------------------------------------|
| `post-fs-data` sets `ro.dalvik.vm.native.bridge` to `libzygisk.so` | unchanged: the daemon does it (resetprop writes the property area)                |
| magic mount puts the magisk binary at `/system/lib64/libzygisk.so` | `GuestVm.magiskPostFsData` reads the property, `MagiskCore.Overlay.apply(root, zygiskLib, log)` copies the binary (journaled, reverted at the next boot) |
| `/debug_ramdisk/magisk32`                              | `prepareTmp` copies it when the APK has it                                                 |
| daemon checks the zygote by `SO_PEERSEC == u:r:zygote:s0` | `vm_peersec()` in `vmount.h`, wired by `tools/patch_guest64_peersec.py` (**needs the rebuild**) |
| init: `onrestart exec magisk --zygote-restart`         | `restartZygote()` runs it before the new zygote starts (3 crashes: the daemon takes Zygisk out) |

Limits: 64-bit zygote only (the 32-bit one runs under qemu-arm and starts without Zygisk); the SO_PEERCRED stand-in only exists in the
21 and 31+ engines, so on 22-30 even the daemon's `is_root` check fails (not touched here). If `aemu.log` says `zygisk off` the
daemon did not set the property: look at `/cache/magisk.log`. The DenyList is still not emulated (no mount namespaces).

## Not done / not verified

* **Nothing here was run on a device.** `MagiskCore` is tested on a desktop JVM (`tests/MagiskCoreTest.kt`, against the real
  v31.1 APK); the shim patch compiles for all 16 engines. The boot stages are untested.
* If `/cache/magisk.log` or the app log (`magisk:` lines) shows the daemon not answering, that is where to start.
  Likely suspects: the live SELinux policy patch (`/sys/fs/selinux/load` does not exist), Magisk's `env_check` wanting a
  preinit block device (the app may offer "additional setup": ignore it), pty allocation for interactive `su`.
* DenyList/hide, modules that must be in place before early native
  services start, 32-bit-only firmware, x86 guests, and the qemu (KK/GB) engines.
* KernelSU cannot work this way (it is a kernel feature).

## Rebuilding

```
pip install ziglang
cd native/guest64 && for v in 21 22 23 24 25 26 27 28 29 30 31 33 34 35 36 37; do sh build-zig.sh $v; done
```
