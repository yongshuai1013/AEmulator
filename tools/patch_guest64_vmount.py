#!/usr/bin/env python3
"""Batch 9 (Magisk #24): merge the vmount layer (native/guest64/vmount.h) into every native/guest64/<api>/run64.c.

The code is AEmulator Plus's (2026-11-21, GPL-3.0); vmount.h itself is included unchanged. Upstream ships five patch
scripts (vmount, setns, ownerbits, procexe, peersec), but they were written against an older run64.c shape and no
longer apply to this tree, so this script performs the same ten edits directly, each anchored on a line that must
occur exactly once. The edits are the ones upstream's patched tree carries today:

  1. xl() is split into xl()/xl_x(p, buf, err, rw) and the mount table is consulted after the path is normalised
     (rw = 0 translates without it; vmount.h's own lookups use that).
  2. run64.c's trap2() becomes trap2_base() and vmount.h (included just above it) provides trap2(): with the
     feature off it forwards everything, with it on mount/umount2/mknodat/getdents64 and the device-node answers
     are vmount.h's.
  3. The mkdirat group's mode goes through vm_mode_fix() (the emulated root keeps its owner bits).
  4. readlinkat of /proc/<pid>/exe is answered from what that process published (vm_proc_exe), before the
     /proc/self/exe answer.
  5. getsockopt(SO_PEERSEC) is answered by vm_peersec() (Zygisk's peer check).
  6. The trap2_base dispatch answers setns like unshare: 0.
  7. The seccomp filter traps getdents64 and setns, both only when the feature is on.
  8. aemu_main calls vm_init() once the root is known, and vm_exe_put() once this process's guest program is.

Two deliberate differences from upstream's tree, both to keep a VM without the feature bit-for-bit as it was:
upstream traps setns for every VM (a literal 268 in its u8 trapped[] table, which truncates to 12, plus an
unguarded JEQ); here setns is trapped only under g_vm (edit 7) and the table is not touched. Nothing else is
changed: without <root>/dev/.aemu-vmount, g_vm stays 0, xl_x with rw=1 never rewrites, trap2() forwards, and
the filter is built exactly as before.

Idempotent: a file that already includes vmount.h is left alone.
"""
import pathlib, sys

def sub1(s, old, new, what):
    n = s.count(old)
    assert n == 1, f"{what}: anchor found {n} times"
    return s.replace(old, new)

def patch(s):
    # 1. xl split + the g_vm flag
    s = sub1(s,
        "// Guest path \u2192 host path. Relative paths stay as they are (the working directory is already inside the root).\n"
        "// Returns the path to use, or 0 with *err set.\n"
        "static const char *xl(const char *p, char *buf, int *err) {\n",
        "// Guest path \u2192 host path. Relative paths stay as they are (the working directory is already inside the root).\n"
        "// Returns the path to use, or 0 with *err set.\n"
        "// Added for AEmulator Plus (2026-11-21): mounts and device nodes, see vmount.h. rw = 0 translates without the mount table.\n"
        "static int g_vm;                                            // <root>/dev/.aemu-vmount exists: this VM has real mounts\n"
        "static void vm_rewrite(char *n, u64 cap);\n"
        "static const char *xl_x(const char *p, char *buf, int *err, int rw);\n"
        "static const char *xl(const char *p, char *buf, int *err) { return xl_x(p, buf, err, 1); }\n"
        "static const char *xl_x(const char *p, char *buf, int *err, int rw) {\n",
        "xl split")
    s = sub1(s,
        "    if ((*err = norm(p, n, PATH_MAX - g_rootlen))) return 0;\n",
        "    if ((*err = norm(p, n, PATH_MAX - g_rootlen))) return 0;\n"
        "    if (rw && g_vm) vm_rewrite(n, PATH_MAX - g_rootlen);       // what is mounted at this path (vmount.h)\n",
        "xl hook")
    # 2. include + trap2 becomes trap2_base
    s = sub1(s,
        "static long trap2(long nr, long *a, void *uc, char **pbp) {\n",
        "#include \"../vmount.h\"\n\n"
        "static long trap2_base(long nr, long *a, void *uc, char **pbp) {\n",
        "trap2 rename")
    return s

def patch2(s):
    # 3. the emulated root keeps the owner bits (vm_mode_fix)
    s = sub1(s,
        "        return S5(nr, a[0], h1, a[2], a[3], a[4]);\n",
        "        // Added for AEmulator Plus (2026-11-21): the emulated root keeps the owner bits (vmount.h, vm_mode_fix)\n"
        "        return S5(nr, a[0], h1, vm_mode_fix(nr, a[0], h1, a[2]), a[3], a[4]);\n",
        "mode fix")
    # 4. /proc/<pid>/exe of another guest process
    s = sub1(s,
        '            if (seq(P(1), "/proc/self/exe") && g_exe[0]) {\n',
        "            if (g_vm) {      // Added for AEmulator Plus (2026-11-21): /proc/<pid>/exe of another guest process, see vmount.h\n"
        "                char *ex = PB(1);\n"
        "                if (vm_proc_exe(P(1), ex)) { u64 n = slen(ex); if (n > (u64)a[3]) n = (u64)a[3]; memcpy((void *)a[2], ex, n); return (long)n; }\n"
        "            }\n"
        '            if (seq(P(1), "/proc/self/exe") && g_exe[0]) {\n',
        "proc exe")
    # 5. SO_PEERSEC for Zygisk
    s = sub1(s,
        "    case NR_getsockopt: {\n"
        "        if (is_nl_fd((int)a[0])) return 0;\n",
        "    case NR_getsockopt: {\n"
        "        if (is_nl_fd((int)a[0])) return 0;\n"
        "        if (g_vm && a[1] == 1 && a[2] == 31 /* SO_PEERSEC */) return vm_peersec(a[0], (char *)a[3], (u32 *)a[4]);   // Added for AEmulator Plus (2026-11-21): Zygisk, see vmount.h\n",
        "peersec")
    # 6. setns answers 0, like unshare
    s = sub1(s,
        "    case NR_mount: case NR_umount2: case NR_chroot: case NR_capset: case NR_setgroups: case NR_unshare: return 0;\n",
        "    case NR_mount: case NR_umount2: case NR_chroot: case NR_capset: case NR_setgroups: case NR_unshare: case 268 /* setns */: return 0;\n",
        "setns dispatch")
    return s

def patch3(s):
    # 7. the filter: getdents64 and setns, only for a VM with the feature on
    s = sub1(s,
        "    f[n++] = JEQ(NR_uname, L_TRAP, 0);\n",
        "    f[n++] = JEQ(NR_uname, L_TRAP, 0);\n"
        "    if (g_vm) f[n++] = JEQ(268 /* setns */, L_TRAP, 0);\n"
        "    // Added for AEmulator Plus (2026-11-21): directory reads, to show a device node (a whiteout) as one (vmount.h)\n"
        "    if (g_vm) f[n++] = JEQ(61 /* getdents64 */, L_TRAP, 0);\n",
        "filter")
    # 8. aemu_main: find out, and publish this process's guest program
    s = sub1(s,
        "    memcpy(g_root, r, slen(r) + 1); g_rootlen = slen(g_root);\n",
        "    memcpy(g_root, r, slen(r) + 1); g_rootlen = slen(g_root);\n"
        "    vm_init();                                                   // (vmount.h)\n",
        "vm_init call")
    s = sub1(s,
        "    // sockets init would have made for the service: AEMU64_SOCK_<name>=<host path>,<octal mode>",
        "    vm_exe_put();    // Added for AEmulator Plus (2026-11-21): /proc/<pid>/exe of this process for the others (vmount.h)\n"
        "    // sockets init would have made for the service: AEMU64_SOCK_<name>=<host path>,<octal mode>",
        "vm_exe_put call")
    return s

def main(root):
    for p in sorted(pathlib.Path(root).glob("[0-9]*/run64.c")):
        s = p.read_text()
        if "vmount.h" in s:
            print(f"{p}: already patched"); continue
        p.write_text(patch3(patch2(patch(s))))
        print(f"{p}: patched")

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "native/guest64")
