// vmount.h — mounts, tmpfs and device nodes for aemu64 (AEmulator Plus addition, 2026-11-21).
//
// Included by every native/guest64/<api>/run64.c (see tools/patch_guest64_vmount.py). Until now mount(2) and mknod(2) of a
// device node were "accepted and ignored" / refused: a script that bind-mounted a certificate directory, mounted a tmpfs
// or made a whiteout (`mknod x c 0 0`) was told it had worked (or failed) and nothing changed. This file gives the guest
// the real thing as far as an app process can: there is no kernel to ask, so the mount table lives here and path
// translation (xl) consults it.
//
// Switched on per VM by <root>/dev/.aemu-vmount (the host writes it when Root/Magisk is on, see MagiskCore.prepareTmp).
// Without that file nothing in here is active and the engine behaves exactly as before.
//
// MOUNTS
//   One table for the whole VM (there are no mount namespaces: setns/unshare stay no-ops), in <root>/dev/.aemu-mounts,
//   mapped MAP_SHARED by every guest process, read lock-free (seqlock) on every path the guest names, written under a
//   spin lock by mount/umount. The host deletes the file before every boot, so mounts never outlive a boot.
//   - bind (MS_BIND, MS_REC): directory over directory or file over file; the target then reads the source.
//   - tmpfs / ramfs: a fresh empty directory (<root>/dev/.aemu-tmpfs/<n>) shown at the target, hiding what was there.
//   - MS_MOVE, umount2, remount (no-op: the tree is writable), propagation changes (no-op: one table).
//   - /proc/mounts and /proc/self/mountinfo list them (toybox's umount only unmounts what it finds there).
//   - stat() of something on a tmpfs reports that tmpfs's own st_dev, so `mountpoint` works.
//   Only targets where a module or a root user would mount are honoured (vm_allowed). Everything the firmware's own
//   programs mount at boot (/dev, /proc, /sys, /data/data, /storage, /mnt, /linkerconfig, /apex/<name> ...) is still
//   accepted and ignored, because the image is built on that and the boot depends on it.
//   Not done: per-process mount namespaces; relative paths and *at() lookups relative to a directory descriptor do not
//   see mounts (only absolute paths do); overlayfs and block-device file systems (ENODEV).
//
// DEVICE NODES
//   An app cannot mknod(2) a character device, and there is no device for a file system to hold. mknod of S_IFCHR/S_IFBLK
//   makes a symbolic link whose target is "\1aemu-dev:<c|b>:<major>:<minor>:<octal mode>"; the guest is shown a device:
//   lstat/stat/statx report S_IFCHR + rdev, getdents64 reports DT_CHR, readlink says EINVAL, access() succeeds, open()
//   gives /dev/null etc. for the usual numbers and ENXIO for the rest. A 0:0 character device is an overlay whiteout,
//   which is how Magisk modules delete stock files (MagiskCore.Overlay understands the same marker on the host side).
//
// PER-APP VIEWS (view layer)
//   With <root>/dev/.aemu-hideuids present (the host's hide list, resolved to guest uids), a process whose
//   uid is listed reads the guest as if Magisk were not in it: Magisk's own places and the module
//   overlay's files answer ENOENT, files the overlay replaced read as their stock backups, and
//   /proc/mounts lists only the image's own table. See the "per-app views" section below. No file, no
//   listed uid: nothing changes for anyone.
//
// Needs from run64.c (all defined above the include point): types, S*() and NR_*, slen/seq/pre/mem*, norm, xl_x, unxl,
// struct kstat, fake_owner, fake_memfile, utoa, g_root/g_rootlen, PB(), P(), trap2_base.

#ifndef AEMU_VMOUNT_H
#define AEMU_VMOUNT_H

#define VM_PATH 400

// OWNER ACCESS (2026-11-21). Magisk makes some of its own directories with mode 000 (".magisk/worker/system" of the magic mount,
// then "mkdir .../system/bin" inside it) and leaves /data/adb/magisk.db at 000. A real root walks through such a mode; here the
// guest's "root" is the plain host user that runs the app, to whom mode 000 is a locked door ("mkdir ... Permission denied").
// So, for the emulated root (g_uid == 0) of a VM with the feature on (g_vm), the owner bits are never taken away: a directory
// made or chmod-ed gets at least rwx for its owner, anything else at least rw. Nothing else is changed: group/other bits stay
// as asked, other guest users get what they asked for, and without <root>/dev/.aemu-vmount this returns the mode untouched.
// mkdirat: mode a[2]. fchmodat (and 16+'s fchmodat2): mode a[2], the target is looked up to tell a directory from a file.
static long vm_mode_fix(long nr, long dirfd, const char *host, long mode) {
    if (!g_vm || g_uid != 0 || !host) return mode;
    if (nr == NR_mkdirat) return mode | 0700;
    if (nr == NR_fchmodat) {
        struct kstat st;
        if (S4(NR_newfstatat, dirfd, host, &st, 0) < 0) return mode;      // nothing there: the call fails by itself
        if ((st.st_mode & 0170000) == 0120000) return mode;                // a link: its own mode means nothing
        return mode | (((st.st_mode & 0170000) == 0040000) ? 0700 : 0600);
    }
    return mode;
}
#define VM_MAXREC 100
#define VM_FILE 131072
#define VM_MAGIC 0x564d5431u
#define VM_BIND 1
#define VM_TMPFS 2

#define VMF_EPERM 1
#define VMF_ENOENT 2
#define VMF_ENXIO 6
#define VMF_EFAULT 14
#define VMF_EBUSY 16
#define VMF_EEXIST 17
#define VMF_ENODEV 19
#define VMF_ENOTDIR 20
#define VMF_EINVAL 22
#define VMF_ENAMETOOLONG 36
#define VMF_ELOOP 40
#define VMF_ENOSPC 28

#define VMF_MS_REMOUNT 32UL
#define VMF_MS_BIND 4096UL
#define VMF_MS_MOVE 8192UL
#define VMF_MS_REC 16384UL
#define VMF_MS_UNBINDABLE (1UL << 17)
#define VMF_MS_PRIVATE (1UL << 18)
#define VMF_MS_SLAVE (1UL << 19)
#define VMF_MS_SHARED (1UL << 20)

struct vm_rec {
    u32 used, ord, id, dev;
    u8 kind, alias; u16 keylen; u32 pad;
    char key[VM_PATH];      // where it is mounted: a path in the image, symlinks resolved, mounts below it already crossed
    char src[VM_PATH];      // what is shown there: likewise
    char disp[VM_PATH];     // the target as the caller spelled it (what /proc/mounts shows)
};
struct vm_tab { u32 magic, lock, seq, live, nextord, nextid, pad[10]; struct vm_rec r[VM_MAXREC]; };
_Static_assert(sizeof(struct vm_tab) <= VM_FILE, "mount table does not fit its file");

static struct vm_tab *g_vmt;

struct vm_wr { int crossed; int fin; u32 kind; u32 dev; };

static long trap2_base(long nr, long *a, void *uc, char **pbp);

// ------------------------------------------------------------------------------------------------ the table

static int vm_memeq(const char *a, const char *b, u64 n) { while (n--) if (*a++ != *b++) return 0; return 1; }

static void vm_wlock(void) {
    for (u64 i = 0;; i++) {
        u32 z = 0;
        if (__atomic_compare_exchange_n(&g_vmt->lock, &z, 1, 0, __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) break;
        if (i > 200000) break;                               // the owner died holding it: take over
        S0(NR_sched_yield);
    }
    u32 s = __atomic_load_n(&g_vmt->seq, __ATOMIC_RELAXED);
    if (!(s & 1)) __atomic_store_n(&g_vmt->seq, s + 1, __ATOMIC_RELEASE);      // odd: readers wait or retry
    if (g_vmt->magic != VM_MAGIC) { g_vmt->magic = VM_MAGIC; g_vmt->nextord = 1; g_vmt->nextid = 1; }
}
static void vm_wunlock(void) {
    __atomic_store_n(&g_vmt->seq, __atomic_load_n(&g_vmt->seq, __ATOMIC_RELAXED) + 1, __ATOMIC_RELEASE);   // even again
    __atomic_store_n(&g_vmt->lock, 0, __ATOMIC_RELEASE);
}

static u32 vm_newid(void) { vm_wlock(); u32 id = g_vmt->nextid++; vm_wunlock(); return id; }

static u64 vm_makedev(u32 maj, u32 min) {
    return ((u64)(maj & 0xfffu) << 8) | (u64)(min & 0xffu) | ((u64)(min & ~0xffu) << 12) | ((u64)(maj & ~0xfffu) << 32);
}

static int vm_add(u32 kind, const char *key, const char *src, const char *disp, u32 id, int alias, u32 dev) {
    if (slen(key) >= VM_PATH || slen(src) >= VM_PATH || slen(disp) >= VM_PATH) return -VMF_ENAMETOOLONG;
    int slot = -1;
    vm_wlock();
    for (int i = 0; i < VM_MAXREC; i++) if (!g_vmt->r[i].used) { slot = i; break; }
    if (slot >= 0) {
        struct vm_rec *r = &g_vmt->r[slot];
        r->ord = g_vmt->nextord++; r->id = id; r->dev = dev; r->kind = (u8)kind; r->alias = (u8)alias;
        r->keylen = (u16)slen(key);
        memcpy(r->key, key, slen(key) + 1); memcpy(r->src, src, slen(src) + 1); memcpy(r->disp, disp, slen(disp) + 1);
        __atomic_store_n(&r->used, 1, __ATOMIC_RELEASE);
        __atomic_add_fetch(&g_vmt->live, 1, __ATOMIC_RELAXED);
    }
    vm_wunlock();
    return slot >= 0 ? 0 : -VMF_ENOSPC;
}

static void vm_remove_id(u32 id) {
    vm_wlock();
    for (int i = 0; i < VM_MAXREC; i++)
        if (g_vmt->r[i].used && g_vmt->r[i].id == id) { g_vmt->r[i].used = 0; __atomic_sub_fetch(&g_vmt->live, 1, __ATOMIC_RELAXED); }
    vm_wunlock();
}

// Walks an absolute, normalised path component by component the way a lookup crosses mount points: whenever the path so
// far is the target of a mount it continues on what is mounted there (and, if that is itself a mount target, on that).
// out gets the result; w says whether a mount was crossed, which record sat at the very last component and what kind the
// last one crossed was. The path never climbs: it is already normalised.
static int vm_walk1(const char *path, char *out, u64 cap, struct vm_wr *w) {
    u64 o = 0;
    out[0] = 0; w->crossed = 0; w->fin = -1; w->kind = 0; w->dev = 0;
    const char *p = path;
    while (*p) {
        while (*p == '/') p++;
        if (!*p) break;
        const char *e = p; while (*e && *e != '/') e++;
        u64 cl = (u64)(e - p);
        if (o + cl + 2 > cap) return -VMF_ENAMETOOLONG;
        out[o++] = '/'; memcpy(out + o, p, cl); o += cl; out[o] = 0;
        p = e;
        u32 last = 0; w->fin = -1;
        for (int guard = 0; guard < 24; guard++) {
            int best = -1; u32 bord = 0xffffffffu;
            for (int i = 0; i < VM_MAXREC; i++) {
                struct vm_rec *r = &g_vmt->r[i];
                if (!r->used || r->ord <= last || r->keylen != o || r->ord >= bord) continue;
                if (vm_memeq(r->key, out, o)) { best = i; bord = r->ord; }
            }
            if (best < 0) break;
            struct vm_rec *r = &g_vmt->r[best];
            u64 sl = slen(r->src);
            if (sl >= VM_PATH || sl + 1 > cap) return -VMF_ENAMETOOLONG;
            memcpy(out, r->src, sl + 1); o = sl;
            last = bord; w->fin = best; w->crossed = 1; w->kind = r->kind; w->dev = r->dev;
        }
    }
    if (!o) { out[0] = '/'; out[1] = 0; }
    return 0;
}

static int vm_walk(const char *path, char *out, u64 cap, struct vm_wr *w) {
    if (!g_vmt || !__atomic_load_n(&g_vmt->live, __ATOMIC_RELAXED)) {
        u64 l = slen(path);
        if (l + 1 > cap) return -VMF_ENAMETOOLONG;
        memcpy(out, path, l + 1); w->crossed = 0; w->fin = -1; w->kind = 0; w->dev = 0;
        return 0;
    }
    for (int tries = 0; tries < 100; tries++) {
        u32 s1 = __atomic_load_n(&g_vmt->seq, __ATOMIC_ACQUIRE);
        if (s1 & 1) { S0(NR_sched_yield); continue; }
        int rc = vm_walk1(path, out, cap, w);
        if (__atomic_load_n(&g_vmt->seq, __ATOMIC_ACQUIRE) == s1) return rc;
    }
    return vm_walk1(path, out, cap, w);                     // a writer that never finished: best effort
}

// xl() calls this for every absolute path the guest names (see the hook in xl_x).
static void vm_rewrite(char *n, u64 cap) {
    if (!g_vmt || !__atomic_load_n(&g_vmt->live, __ATOMIC_RELAXED)) return;
    if (g_rootlen && pre(n, g_root) && (n[g_rootlen] == '/' || !n[g_rootlen])) return;     // already a host path (our own argv)
    u64 l = slen(n);
    if (l > 1000) return;
    char out[1104]; struct vm_wr w;
    if (vm_walk(n, out, sizeof out, &w) < 0 || !w.crossed) return;
    u64 ol = slen(out);
    if (ol + 1 > cap) return;
    memcpy(n, out, ol + 1);
}

// ------------------------------------------------------------------------------------------------ paths

// The path as an absolute, normalised guest path (a relative one is taken from the working directory).
static int vm_abs(const char *p, char *out, u64 cap) {
    if (!p) return -VMF_EFAULT;
    if (p[0] == '/') return norm(p, out, cap);
    char cw[1104];
    long n = S2(NR_getcwd, cw, sizeof cw);
    if (n <= 0) return -VMF_ENOENT;
    unxl(cw);
    u64 l = slen(cw), pl = slen(p);
    if (l + pl + 2 > sizeof cw) return -VMF_ENAMETOOLONG;
    if (l == 1) l = 0;
    cw[l] = '/'; memcpy(cw + l + 1, p, pl + 1);
    return norm(cw, out, cap);
}

static int vm_outside(const char *c) {                      // not part of the image: the phone's own /dev, /proc, /sys
    return (pre(c, "/dev") && (c[4] == '/' || !c[4])) || (pre(c, "/proc") && (c[5] == '/' || !c[5])) || (pre(c, "/sys") && (c[4] == '/' || !c[4]));
}

// Every symbolic link of an absolute guest path resolved, an absolute link taken from the root of the image (the kernel
// of the phone would take it from its own). Used when a mount is made, so a path is kept under one spelling.
static int vm_canon(const char *in, char *out, u64 cap) {
    char work[1104], cur[1104], lk[600], host[1700], nw[1104];
    u64 il = slen(in);
    if (il >= sizeof work) return -VMF_ENAMETOOLONG;
    memcpy(work, in, il + 1);
    for (int hops = 0; hops <= 40;) {
        u64 co = 0; cur[0] = 0;
        const char *p = work; int restart = 0;
        while (*p && !restart) {
            while (*p == '/') p++;
            if (!*p) break;
            const char *e = p; while (*e && *e != '/') e++;
            u64 cl = (u64)(e - p), save = co;
            if (co + cl + 2 > sizeof cur) return -VMF_ENAMETOOLONG;
            cur[co++] = '/'; memcpy(cur + co, p, cl); co += cl; cur[co] = 0;
            long n = -1;
            if (!vm_outside(cur) && g_rootlen + co + 1 <= sizeof host) {
                memcpy(host, g_root, g_rootlen); memcpy(host + g_rootlen, cur, co + 1);
                n = S4(NR_readlinkat, AT_FDCWD, host, lk, sizeof lk - 1);
            }
            if (n > 0) {
                lk[n] = 0;
                if (++hops > 40) return -VMF_ELOOP;
                u64 no = 0, rest = slen(e);
                if (lk[0] != '/') { memcpy(nw, cur, save); no = save; nw[no++] = '/'; }
                if (no + (u64)n + rest + 2 > sizeof nw) return -VMF_ENAMETOOLONG;
                memcpy(nw + no, lk, (u64)n); no += (u64)n; memcpy(nw + no, e, rest + 1);
                int err = norm(nw, work, sizeof work);
                if (err) return err;
                restart = 1;
            }
            p = e;
        }
        if (!restart) {
            if (!co) { cur[0] = '/'; cur[1] = 0; co = 1; }
            if (co + 1 > cap) return -VMF_ENAMETOOLONG;
            memcpy(out, cur, co + 1);
            return 0;
        }
    }
    return -VMF_ELOOP;
}

// Where a mount may be made. Everything else is accepted and ignored, as it always was (see the header).
static int vm_under(const char *c, const char *pfx) { u64 l = slen(pfx); return pre(c, pfx) && (c[l] == '/' || !c[l]); }
static int vm_allowed(const char *c) {
    static const char *const ok[] = { "/system", "/system_ext", "/product", "/vendor", "/odm", "/oem", "/vendor_dlkm", "/odm_dlkm",
                                      "/system_dlkm", "/debug_ramdisk", "/sbin", "/data/adb", "/data/local", "/data/misc", 0 };
    for (int i = 0; ok[i]; i++) if (vm_under(c, ok[i]) && !seq(c, ok[i])) return 1;      // never the root of one of them
    if (vm_under(c, "/apex")) {                              // /apex/<name>/<something>, not /apex/<name>: apexd's own bind mounts stay as they were
        const char *q = c + 5; int depth = 0;
        while (*q) { if (*q == '/') { depth++; q++; while (*q && *q != '/') q++; } else q++; }
        return depth >= 2;
    }
    return 0;
}

static long vm_stat_id(const char *id, u32 *mode, char *pb) {
    int err = 0;
    const char *h = xl_x(id, pb, &err, 0);
    if (!h) return err ? err : -VMF_ENOENT;
    struct kstat st;
    long r = S4(NR_newfstatat, AT_FDCWD, h, &st, 0);
    if (r < 0) return r;
    *mode = st.st_mode;
    return 0;
}

// ------------------------------------------------------------------------------------------------ mount, umount

static long vm_alias(const char *lex, const char *tc, const char *tid, u32 kind, const char *src, u32 id, u32 dev) {
    // the target spelled another way than its resolved form is the same place: both name it (only when nothing was
    // crossed on the way, otherwise the spelled path crosses the same mounts to get there)
    if (!seq(lex, tc) && seq(tid, tc)) return vm_add(kind, lex, src, lex, id, 1, dev);
    return 0;
}

static long vm_do_bind(const char *src, const char *t, const char *tc, int rec, char **pbp) {
    char s[1104], scn[1104], sid[1104], tid[1104];
    struct vm_wr w, w2;
    long e;
    if (!src) return -VMF_EFAULT;
    if ((e = vm_abs(src, s, sizeof s)) < 0) return e;
    if ((e = vm_canon(s, scn, sizeof scn)) < 0) return e;
    if ((e = vm_walk(scn, sid, sizeof sid, &w)) < 0) return e;
    if ((e = vm_walk(tc, tid, sizeof tid, &w2)) < 0) return e;
    u32 sm = 0, tm = 0;
    if ((e = vm_stat_id(sid, &sm, PB(0))) < 0) return e;
    if ((e = vm_stat_id(tid, &tm, PB(1))) < 0) return e;
    if (((sm & 0170000) == 0040000) != ((tm & 0170000) == 0040000)) return -VMF_ENOTDIR;
    u32 id = vm_newid();
    if ((e = vm_add(VM_BIND, tid, sid, t, id, 0, 0)) < 0) return e;
    if ((e = vm_alias(t, tc, tid, VM_BIND, sid, id, 0)) < 0) { vm_remove_id(id); return e; }
    if (rec && g_vmt) {                                      // --rbind: what is mounted below the source shows below the target
        u64 sl = slen(sid);
        struct vm_rec cp[VM_MAXREC / 4]; int nc = 0;
        for (int i = 0; i < VM_MAXREC && nc < VM_MAXREC / 4; i++) {
            struct vm_rec *r = &g_vmt->r[i];
            if (r->used && !r->alias && r->id != id && pre(r->key, sid) && r->key[sl] == '/') cp[nc++] = *r;
        }
        for (int i = 0; i < nc; i++) {
            char k[VM_PATH];
            u64 tl = slen(tid), rl = slen(cp[i].key + sl);
            if (tl + rl + 1 > sizeof k) continue;
            memcpy(k, tid, tl); memcpy(k + tl, cp[i].key + sl, rl + 1);
            vm_add(cp[i].kind, k, cp[i].src, k, vm_newid(), 0, cp[i].dev);
        }
    }
    return 0;
}

static long vm_do_tmpfs(const char *t, const char *tc, const char *data, char **pbp) {
    char tid[1104], h[600], bs[64], num[24];
    struct vm_wr w;
    long e;
    u32 tm = 0;
    if ((e = vm_walk(tc, tid, sizeof tid, &w)) < 0) return e;
    if ((e = vm_stat_id(tid, &tm, PB(1))) < 0) return e;
    if ((tm & 0170000) != 0040000) return -VMF_ENOTDIR;
    u32 mode = 01777;
    if (data) {                                              // "mode=755,size=..."
        for (const char *q = data; *q; q++)
            if ((q == data || q[-1] == ',') && pre(q, "mode=")) { u32 v = 0; for (q += 5; *q >= '0' && *q <= '7'; q++) v = v * 8 + (u32)(*q - '0'); mode = v & 07777; break; }
    }
    u32 id = vm_newid();
    if (g_rootlen + 40 > sizeof h) return -VMF_ENAMETOOLONG;
    memcpy(h, g_root, g_rootlen); memcpy(h + g_rootlen, "/dev/.aemu-tmpfs", 17);
    S3(NR_mkdirat, AT_FDCWD, h, 0700);                       // (exists after the first one)
    char *d = utoa((u64)id, num + sizeof num);
    memcpy(bs, "/dev/.aemu-tmpfs/", 17); memcpy(bs + 17, d, slen(d) + 1);
    memcpy(h + g_rootlen, bs, slen(bs) + 1);
    if ((e = S3(NR_mkdirat, AT_FDCWD, h, 0700)) < 0 && e != -VMF_EEXIST) return e;
    // the host's own permission check applies to the guest's accesses (every file of the image is the app's): the owner
    // keeps full access whatever mode the mount asked for; what the guest sees is faked from the mode by stat
    S4(NR_fchmodat, AT_FDCWD, h, (mode & 0777) | 0700, 0);
    u32 dev = (u32)(100 + id);
    if ((e = vm_add(VM_TMPFS, tid, bs, t, id, 0, dev)) < 0) return e;
    if ((e = vm_alias(t, tc, tid, VM_TMPFS, bs, id, dev)) < 0) { vm_remove_id(id); return e; }
    return 0;
}

static long vm_do_move(const char *src, const char *t, const char *tc, char **pbp) {
    char s[1104], scn[1104], sid[1104], tid[1104];
    struct vm_wr w, w2;
    long e;
    u32 tm = 0;
    if (!src) return -VMF_EFAULT;
    if ((e = vm_abs(src, s, sizeof s)) < 0) return e;
    if ((e = vm_canon(s, scn, sizeof scn)) < 0) return e;
    if ((e = vm_walk(scn, sid, sizeof sid, &w)) < 0) return e;
    if (w.fin < 0) return -VMF_EINVAL;                       // not a mount point
    if ((e = vm_walk(tc, tid, sizeof tid, &w2)) < 0) return e;
    if ((e = vm_stat_id(tid, &tm, PB(1))) < 0) return e;
    struct vm_rec old = g_vmt->r[w.fin];
    vm_remove_id(old.id);
    u32 id = vm_newid();
    if ((e = vm_add(old.kind, tid, old.src, t, id, 0, old.dev)) < 0) return e;
    return vm_alias(t, tc, tid, old.kind, old.src, id, old.dev);
}

// Magisk's own daemon "magic mounts" the modules' files with these calls; MagiskCore.Overlay already puts them in place (on the
// host, reverted at the next boot), so for the daemon itself mount stays accepted-and-ignored: doing both would cost every path
// lookup a table of hundreds of entries for nothing. Scripts and tools it runs (sh, mount) are not affected.
static int vm_is_magisk(void) {
    const char *b = g_exe; for (const char *q = g_exe; *q; q++) if (*q == '/') b = q + 1;
    return pre(b, "magisk");
}

static long vm_sys_mount(long *a, char **pbp) {
    const char *src = (const char *)a[0], *tgt = (const char *)a[1], *fst = (const char *)a[2], *data = (const char *)a[4];
    u64 fl = (u64)a[3];
    if (!tgt || !g_vmt || vm_is_magisk()) return 0;
    // remounts and propagation changes: nothing to change (one table, a writable tree)
    if (fl & (VMF_MS_REMOUNT | VMF_MS_SHARED | VMF_MS_PRIVATE | VMF_MS_SLAVE | VMF_MS_UNBINDABLE)) return 0;
    char t[1104], tc[1104];
    if (vm_abs(tgt, t, sizeof t) < 0 || vm_canon(t, tc, sizeof tc) < 0) return 0;
    if (!vm_allowed(tc)) return 0;                           // (see vm_allowed: what the firmware mounts itself)
    if (fl & VMF_MS_MOVE) return vm_do_move(src, t, tc, pbp);
    if (fl & VMF_MS_BIND) return vm_do_bind(src, t, tc, (fl & VMF_MS_REC) != 0, pbp);
    if (fst && (seq(fst, "tmpfs") || seq(fst, "ramfs"))) return vm_do_tmpfs(t, tc, data, pbp);
    return -VMF_ENODEV;                                      // overlay, ext4, loop images ...: not here
}

static long vm_sys_umount(long *a) {
    const char *tgt = (const char *)a[0];
    if (!tgt || !g_vmt || vm_is_magisk()) return 0;
    char t[1104], tc[1104], tid[1104];
    struct vm_wr w;
    if (vm_abs(tgt, t, sizeof t) < 0 || vm_canon(t, tc, sizeof tc) < 0) return 0;
    if (!vm_allowed(tc)) return 0;
    if (vm_walk(tc, tid, sizeof tid, &w) < 0) return 0;
    if (w.fin < 0) return -VMF_EINVAL;                       // not a mount point
    vm_remove_id(g_vmt->r[w.fin].id);
    return 0;
}

// ------------------------------------------------------------------------------------------------ device nodes

#define VM_DEV "\1aemu-dev:"
#define VM_DEVLEN 10

static int vm_dev_parse(const char *s, u32 *t, u32 *maj, u32 *min, u32 *mode) {
    if (!pre(s, VM_DEV)) return 0;
    s += VM_DEVLEN;
    if (*s != 'c' && *s != 'b') return 0;
    *t = (u32)*s++;
    if (*s++ != ':') return 0;
    u32 v = 0; while (*s >= '0' && *s <= '9') v = v * 10 + (u32)(*s++ - '0'); *maj = v;
    if (*s++ != ':') return 0;
    v = 0; while (*s >= '0' && *s <= '9') v = v * 10 + (u32)(*s++ - '0'); *min = v;
    if (*s++ != ':') return 0;
    v = 0; while (*s >= '0' && *s <= '7') v = v * 8 + (u32)(*s++ - '0'); *mode = v;
    return 1;
}

static int vm_probe(long dirfd, const char *host, u32 *t, u32 *maj, u32 *min, u32 *mode) {
    char b[64];
    long n = S4(NR_readlinkat, dirfd, host, b, sizeof b - 1);
    if (n < VM_DEVLEN + 6) return 0;
    b[n] = 0;
    return vm_dev_parse(b, t, maj, min, mode);
}

static void vm_dev_target(char *out, u32 t, u32 maj, u32 min, u32 mode) {
    char n[24], *d, oc[8];
    memcpy(out, VM_DEV, VM_DEVLEN); u64 o = VM_DEVLEN;
    out[o++] = (char)t; out[o++] = ':';
    d = utoa(maj, n + sizeof n); memcpy(out + o, d, slen(d)); o += slen(d); out[o++] = ':';
    d = utoa(min, n + sizeof n); memcpy(out + o, d, slen(d)); o += slen(d); out[o++] = ':';
    { int k = 0; u32 m = mode & 07777; do oc[k++] = (char)('0' + (m & 7)); while (m >>= 3); while (k) out[o++] = oc[--k]; }
    out[o] = 0;
}

static void vm_fix_kstat(struct kstat *st, u32 t, u32 maj, u32 min, u32 mode) {
    st->st_mode = (t == 'b' ? 0060000u : 0020000u) | (mode & 07777);
    st->st_rdev = vm_makedev(maj, min);
    st->st_size = 0; st->st_blocks = 0; st->st_nlink = 1;
}

// mknodat: a device node becomes a marker link; files, pipes and sockets are the phone's kernel's to make.
static long vm_sys_mknod(long *a, void *uc, char **pbp) {
    u32 mode = (u32)a[2], fmt = mode & 0170000;
    if (fmt != 0020000 && fmt != 0060000) return trap2_base(NR_mknodat, a, uc, pbp);
    char *b1 = PB(0);
    int err = 0;
    const char *h = xl(P(1), b1, &err);
    if (!h) return P(1) ? err : -VMF_EFAULT;
    u64 d = (u64)a[3];
    u32 maj = (u32)(((d >> 8) & 0xfffu) | ((d >> 32) & ~0xfffULL)), min = (u32)((d & 0xffu) | ((d >> 12) & ~0xffULL));
    u32 um = (u32)S1(166 /* umask */, 0); S1(166, um);
    char tg[80];
    vm_dev_target(tg, fmt == 0060000 ? 'b' : 'c', maj, min, mode & ~um & 07777);
    return S3(NR_symlinkat, tg, a[0], h);
}

// stat of a marker: 1 when the path is one (st filled; lstat'ed first unless already is)
static int vm_stat_marker(long dirfd, const char *h, struct kstat *st, int have) {
    u32 t, maj, min, mode;
    if (!vm_probe(dirfd, h, &t, &maj, &min, &mode)) return 0;
    if (!have && S4(NR_newfstatat, dirfd, h, st, 0x100) < 0) return 0;
    fake_owner(st);
    vm_fix_kstat(st, t, maj, min, mode);
    return 1;
}

static const char *vm_devpath(u32 maj, u32 min) {
    if (maj == 1) switch (min) { case 3: return "/dev/null"; case 5: return "/dev/zero"; case 7: return "/dev/full"; case 8: return "/dev/random"; case 9: return "/dev/urandom"; }
    if (maj == 5 && min == 0) return "/dev/tty";
    if (maj == 5 && min == 2) return "/dev/ptmx";
    return 0;
}

static long vm_sys_getdents(long *a) {
    long n = S3(61 /* getdents64 */, a[0], a[1], a[2]);
    if (n <= 0) return n;
    char *b = (char *)a[1];
    for (long o = 0; o + 19 <= n;) {
        u16 rl = *(u16 *)(b + o + 16);
        u8 *ty = (u8 *)(b + o + 18);
        if (!rl) break;
        if (*ty == 10 /* DT_LNK */) {
            char lk[64]; u32 t, maj, min, mode;
            long k = S4(NR_readlinkat, a[0], b + o + 19, lk, sizeof lk - 1);
            if (k >= VM_DEVLEN + 6) { lk[k] = 0; if (vm_dev_parse(lk, &t, &maj, &min, &mode)) *ty = t == 'b' ? 6 : 2; }
        }
        o += rl;
    }
    // our own bookkeeping files (.aemu-*: the mount table, the tmpfs store, the per-pid maps, the hide
    // list itself) are not the guest's business in a listing; they stay reachable by their exact path,
    // which is all our own guest services ever use
    {
        long w = 0, lastOff = 0, lastKept = -1;
        for (long o = 0; o + 19 <= n;) {
            u16 rl = *(u16 *)(b + o + 16);
            if (!rl) break;
            lastOff = *(i64 *)(b + o + 8);
            const char *nm = b + o + 19;
            int ours = nm[0] == '.' && nm[1] == 'a' && nm[2] == 'e' && nm[3] == 'm' && nm[4] == 'u' && nm[5] == '-';
            if (!ours) { if (w != o) memmove(b + w, b + o, rl); lastKept = w; w += rl; }
            o += rl;
        }
        if (w != n) {
            if (lastKept >= 0) *(i64 *)(b + lastKept + 8) = lastOff;   // the end-of-directory cookie of the unfiltered read
            return w;
        }
    }
    return n;
}

// ---- per-app views: hiding the root traces from listed apps ------------------------------------------------------
// The host's hide list (VmSettings.hiddenApps) reaches this layer as a set of guest uids in
// <root>/dev/.aemu-hideuids — resolved from package names by the guest agent (its PackageManager is the
// authority), or by the host out of the image's packages.xml before the agent is up. A process whose
// g_uid is in the set reads a guest without Magisk in it:
//   - the fixed places MagiskCore puts things (its links in /system/bin and /system/xbin, /data/adb,
//     /debug_ramdisk, the manager's own data, the Zygisk bridge in /system/lib64) answer ENOENT;
//   - what the module overlay copied in (MagiskCore.Overlay's journal) answers ENOENT where the overlay
//     created a file, and the journal's backup where the overlay replaced one — the un-mounted view,
//     as a denylist means it, not a hole where a stock file should be;
//   - /proc/mounts and mountinfo list only the image's own table, without this layer's mounts.
// No file (an empty list, the agent off, Magisk off) means no reader is hidden and nothing here ever
// engages: the checks below all sit behind vm_hidden(), which is then always false. Directory listings
// are the one gap: getdents64 has no path to match the journal against, so module file NAMES still
// show in a listing (their open/stat answer ENOENT); names of our own bookkeeping (.aemu-*) are
// dropped from listings for every reader while this layer is on.
#define VM_HIDE_MAXUID 128
#define VM_HIDE_MAXENT 1024
#define VM_HIDE_PATH 560

static int g_hide_uids[VM_HIDE_MAXUID];
static int g_hide_nuid;
static char g_hide_host[VM_HIDE_MAXENT][VM_HIDE_PATH];   // journal entry, as a host path
static char g_hide_orig[VM_HIDE_MAXENT][VM_HIDE_PATH];   // its backup host path; "" = created by the overlay (ENOENT)
static int g_hide_pfx[VM_HIDE_MAXENT];                   // a whole directory was replaced: prefix match
static int g_hide_nent;
static int g_hide_calls = 1 << 28;                       // the first check always loads
struct vm_fver { i64 mt, mtn, size; int ok; };
static struct vm_fver g_hide_uv, g_hide_jv;

static void vm_fver_get(const char *p, struct vm_fver *v) {
    struct kstat st;
    v->ok = S4(NR_newfstatat, AT_FDCWD, p, &st, 0) == 0;
    if (v->ok) { v->mt = st.mt; v->mtn = st.mtn; v->size = st.st_size; }
}
static int vm_fver_same(const struct vm_fver *a, const struct vm_fver *b) {
    return a->ok == b->ok && (!a->ok || (a->mt == b->mt && a->mtn == b->mtn && a->size == b->size));
}

// reads a whole small file; returns its length (content NUL-terminated), or -1
static long vm_read_file(const char *p, char *buf, u64 cap) {
    long fd = S4(NR_openat, AT_FDCWD, p, 02000000, 0);
    if (fd < 0) return -1;
    u64 o = 0;
    while (o + 1 < cap) { long n = S3(NR_read, fd, buf + o, cap - 1 - o); if (n <= 0) break; o += (u64)n; }
    S1(NR_close, fd);
    buf[o] = 0;
    return (long)o;
}

static void vm_hide_reload(const char *up, const char *jp) {
    static char buf[600000];
    g_hide_nuid = 0;
    long n = vm_read_file(up, buf, sizeof buf);
    if (n > 0) {
        char *p = buf;
        while (*p && g_hide_nuid < VM_HIDE_MAXUID) {
            while (*p == '\n' || *p == '\r' || *p == ' ' || *p == '\t') p++;
            if (*p < '0' || *p > '9') break;
            int v = 0; while (*p >= '0' && *p <= '9') v = v * 10 + (*p++ - '0');
            g_hide_uids[g_hide_nuid++] = v;
        }
    }
    g_hide_nent = 0;
    n = vm_read_file(jp, buf, sizeof buf);
    if (n <= 0) return;
    char *p = buf;
    while (*p && g_hide_nent < VM_HIDE_MAXENT) {
        char *e = p; while (*e && *e != '\n') e++;
        char save = *e; *e = 0;
        // kind \t tree-relative path [\t backup number]
        char *t1 = 0, *t2 = 0;
        for (char *q = p; *q; q++) if (*q == '\t') { if (!t1) t1 = q; else { t2 = q; break; } }
        if (t1 && (p[0] == 'F' || p[0] == 'D' || p[0] == 'R' || p[0] == 'P')) {
            *t1 = 0; if (t2) *t2 = 0;
            const char *rel = t1 + 1;
            u64 rl = slen(g_root) + 1 + slen(rel);
            if (rl < VM_HIDE_PATH) {
                char *h = g_hide_host[g_hide_nent];
                memcpy(h, g_root, g_rootlen); h[g_rootlen] = '/'; memcpy(h + g_rootlen + 1, rel, slen(rel) + 1);
                g_hide_orig[g_hide_nent][0] = 0;
                g_hide_pfx[g_hide_nent] = p[0] == 'P';
                if ((p[0] == 'R' || p[0] == 'P') && t2) {
                    const char *bk = t2 + 1;
                    static const char mid[] = "/data/adb/aemu-overlay/orig/";
                    u64 ol = g_rootlen + sizeof(mid) - 1 + slen(bk);
                    if (ol < VM_HIDE_PATH) {
                        char *o = g_hide_orig[g_hide_nent];
                        memcpy(o, g_root, g_rootlen); memcpy(o + g_rootlen, mid, sizeof(mid) - 1);
                        memcpy(o + g_rootlen + sizeof(mid) - 1, bk, slen(bk) + 1);
                    }
                }
                g_hide_nent++;
            }
        }
        *e = save;
        p = save ? e + 1 : e;
    }
}

static void vm_hide_refresh(void) {
    if (++g_hide_calls < 128) return;
    g_hide_calls = 0;
    if (g_rootlen > 400) return;
    char up[512], jp[512];
    memcpy(up, g_root, g_rootlen); memcpy(up + g_rootlen, "/dev/.aemu-hideuids", 20);
    memcpy(jp, g_root, g_rootlen); memcpy(jp + g_rootlen, "/data/adb/aemu-overlay/journal", 31);
    struct vm_fver uv, jv;
    vm_fver_get(up, &uv); vm_fver_get(jp, &jv);
    if (vm_fver_same(&uv, &g_hide_uv) && vm_fver_same(&jv, &g_hide_jv)) return;
    g_hide_uv = uv; g_hide_jv = jv;
    vm_hide_reload(up, jp);
}

// this process reads the un-rooted view (see the section header)
static int vm_hidden(void) {
    if (!g_vm) return 0;
    vm_hide_refresh();
    for (int i = 0; i < g_hide_nuid; i++) if (g_hide_uids[i] == g_uid) return 1;
    return 0;
}

// the places of Magisk itself, by guest path (see the section header)
static int vm_hide_fixed(const char *p) {
    if (!p || p[0] != '/') return 0;
    if (vm_under(p, "/data/adb") || vm_under(p, "/debug_ramdisk")) return 1;
    if (vm_under(p, "/data/data/com.topjohnwu.magisk") || vm_under(p, "/data/user/0/com.topjohnwu.magisk")) return 1;
    if (pre(p, "/system/lib64/libzygisk.so")) return 1;
    static const char *const dirs[2] = { "/system/bin/", "/system/xbin/" };
    static const char *const names[4] = { "su", "magisk", "resetprop", "magiskpolicy" };
    for (int d = 0; d < 2; d++) {
        if (!pre(p, dirs[d])) continue;
        const char *q = p + slen(dirs[d]);
        for (int i = 0; i < 4; i++) {
            if (seq(q, names[i])) return 1;
            u64 l = slen(names[i]);
            if (pre(q, names[i]) && seq(q + l, ".aemu-orig")) return 1;
        }
    }
    return 0;
}

// 0 = not an overlay path, 1 = created by the overlay (ENOENT), 2 = replaced (read the backup in out)
static int vm_hide_journal(const char *host, char *out, u64 cap) {
    if (!host) return 0;
    for (int i = 0; i < g_hide_nent; i++) {
        const char *h = g_hide_host[i];
        if (g_hide_pfx[i]) {
            u64 l = slen(h);
            if (pre(host, h) && (host[l] == '/' || !host[l])) {
                if (!g_hide_orig[i][0]) return 1;
                u64 ol = slen(g_hide_orig[i]), rl = slen(host + l);
                if (ol + rl + 1 > cap) return 0;
                memcpy(out, g_hide_orig[i], ol); memcpy(out + ol, host + l, rl + 1);
                return 2;
            }
        } else if (seq(host, h)) {
            if (!g_hide_orig[i][0]) return 1;
            u64 ol = slen(g_hide_orig[i]);
            if (ol + 1 > cap) return 0;
            memcpy(out, g_hide_orig[i], ol + 1);
            return 2;
        }
    }
    return 0;
}

static int vm_contains(const char *hay, const char *needle) {
    u64 nl = slen(needle);
    for (const char *p = hay; *p; p++) { u64 i = 0; while (i < nl && p[i] == needle[i]) i++; if (i == nl) return 1; }
    return 0;
}

// ------------------------------------------------------------------------------------------------ /proc/mounts

static int vm_is_mounts_path(const char *p, int *info) {
    if (!pre(p, "/proc/")) return 0;
    const char *q = p + 6;
    if (seq(q, "mounts")) { *info = 0; return 1; }
    if (pre(q, "self/")) q += 5;
    else if (pre(q, "thread-self/")) q += 12;
    else if (*q >= '1' && *q <= '9') { while (*q >= '0' && *q <= '9') q++; if (*q != '/') return 0; q++; }
    else return 0;
    if (seq(q, "mounts")) { *info = 0; return 1; }
    if (seq(q, "mountinfo")) { *info = 1; return 1; }
    return 0;
}

static u64 vm_put(char *b, u64 o, u64 cap, const char *s) { u64 l = slen(s); if (o + l >= cap) return o; memcpy(b + o, s, l); return o + l; }
static u64 vm_putn(char *b, u64 o, u64 cap, u64 v) { char t[24]; return vm_put(b, o, cap, utoa(v, t + sizeof t)); }

static long vm_mounts_open(int info) {
    const u64 cap = 65536;
    long m = S6(NR_mmap, 0, cap, 3, 0x22 /* MAP_PRIVATE|MAP_ANONYMOUS */, -1, 0);
    if ((u64)m > (u64)-4096) return -1;
    char *b = (char *)m, *base = b + 32768;
    u64 o = 0, bl = 0;
    int hide = vm_hidden();   // a listed app reads the un-mounted view: the image's own table only
    // the image's own table if it has one, else the plain minimum
    if (g_rootlen + 16 < 560) {
        char p[560];
        memcpy(p, g_root, g_rootlen); memcpy(p + g_rootlen, "/proc/mounts", 13);
        long fd = S4(NR_openat, AT_FDCWD, p, 02000000, 0);
        if (fd >= 0) { long n = S3(NR_read, fd, base, 16000); S1(NR_close, fd); if (n > 0) bl = (u64)n; }
    }
    if (!bl) bl = vm_put(base, 0, 16000,
        "rootfs / rootfs ro,relatime 0 0\ntmpfs /dev tmpfs rw,nosuid,relatime,mode=755 0 0\nproc /proc proc rw,relatime 0 0\n"
        "sysfs /sys sysfs rw,relatime 0 0\n/dev/block/dm-0 /system ext4 ro,seclabel,relatime 0 0\n/dev/block/dm-1 /data ext4 rw,seclabel,nosuid,nodev,noatime 0 0\n");
    base[bl] = 0;
    if (!info) {
        if (!hide) {
            o = vm_put(b, o, 32000, base);
        } else {
            for (char *l = base; *l;) {
                char *e = l; while (*e && *e != '\n') e++;
                char c = *e; *e = 0;
                if (!vm_contains(l, "debug_ramdisk") && !vm_contains(l, ".magisk")) { o = vm_put(b, o, 32000, l); o = vm_put(b, o, 32000, "\n"); }
                *e = c; l = c ? e + 1 : e;
            }
        }
    } else {                                                 // "dev point type opts 0 0"  ->  "id parent maj:min / point opts - type dev opts"
        u64 id = 20;
        for (char *l = base; *l;) {
            char *e = l; while (*e && *e != '\n') e++;
            char c = *e; *e = 0;
            if (hide && (vm_contains(l, "debug_ramdisk") || vm_contains(l, ".magisk"))) { *e = c; l = c ? e + 1 : e; continue; }
            char *f[5] = { 0, 0, 0, 0, 0 }; int nf = 0;
            for (char *q = l; *q && nf < 4;) { f[nf++] = q; while (*q && *q != ' ') q++; if (*q) *q++ = 0; }
            if (nf == 4) {
                o = vm_putn(b, o, 32000, id++); o = vm_put(b, o, 32000, " 1 0:"); o = vm_putn(b, o, 32000, id); o = vm_put(b, o, 32000, " / ");
                o = vm_put(b, o, 32000, f[1]); o = vm_put(b, o, 32000, " "); o = vm_put(b, o, 32000, f[3]); o = vm_put(b, o, 32000, " - ");
                o = vm_put(b, o, 32000, f[2]); o = vm_put(b, o, 32000, " "); o = vm_put(b, o, 32000, f[0]); o = vm_put(b, o, 32000, " rw\n");
            }
            *e = c; l = c ? e + 1 : e;
        }
    }
    if (g_vmt && !hide) {
        for (int i = 0; i < VM_MAXREC; i++) {
            struct vm_rec *r = &g_vmt->r[i];
            if (!r->used || r->alias) continue;
            char key[VM_PATH], src[VM_PATH], disp[VM_PATH];
            memcpy(key, r->key, VM_PATH); memcpy(src, r->src, VM_PATH); memcpy(disp, r->disp, VM_PATH);
            key[VM_PATH - 1] = src[VM_PATH - 1] = disp[VM_PATH - 1] = 0;
            int tmp = r->kind == VM_TMPFS;
            if (!info) {
                o = vm_put(b, o, 64000, tmp ? "tmpfs " : "/dev/block/dm-0 "); o = vm_put(b, o, 64000, disp);
                o = vm_put(b, o, 64000, tmp ? " tmpfs rw,seclabel,relatime,mode=755 0 0\n" : " ext4 rw,seclabel,relatime 0 0\n");
            } else {
                o = vm_putn(b, o, 64000, 1000 + r->id); o = vm_put(b, o, 64000, " 1 0:"); o = vm_putn(b, o, 64000, tmp ? r->dev : 1);
                o = vm_put(b, o, 64000, tmp ? " / " : " "); if (!tmp) { o = vm_put(b, o, 64000, src); o = vm_put(b, o, 64000, " "); }
                o = vm_put(b, o, 64000, disp);
                o = vm_put(b, o, 64000, tmp ? " rw,seclabel,relatime - tmpfs tmpfs rw,seclabel,mode=755\n" : " rw,relatime - ext4 /dev/block/dm-0 rw\n");
            }
        }
    }
    long fd = fake_memfile(info ? "aemu-mountinfo" : "aemu-mounts", b, o);
    S2(NR_munmap, m, cap);
    return fd;
}

// ------------------------------------------------------------------------------------------------ the front

// A path as the host sees it, for the call's first or second path argument; 0 with the error code in *err
static const char *vm_host(long *a, int i, int slot, char **pbp, int *err) {
    char *b = (*pbp ? *pbp : (*pbp = pb_get())) + slot * PATH_MAX;
    if (!a[i]) return 0;
    return xl((const char *)a[i], b, err);
}

// the per-app view applied to one path argument of a call: 0 = let the call through,
// 1 = answer ENOENT, 2 = the call belongs on the journal backup path in [out] instead
static int vm_hide_path(long *a, int idx, char **pbp, char *out, u64 cap) {
    if (!vm_hidden()) return 0;
    const char *raw = (const char *)a[idx];
    if (!raw || raw[0] != '/') return 0;
    char np[1104];
    if (slen(raw) >= sizeof np || norm(raw, np, sizeof np)) return 0;
    if (vm_hide_fixed(np)) return 1;
    int err = 0;
    const char *h = vm_host(a, idx, 0, pbp, &err);
    if (!h) return 0;
    return vm_hide_journal(h, out, cap);
}

static long vm_trap2(long nr, long *a, void *uc, char **pbp) {
    switch (nr) {
    case NR_mount: return vm_sys_mount(a, pbp);
    case NR_umount2: return vm_sys_umount(a);
    case NR_mknodat: return vm_sys_mknod(a, uc, pbp);
    case 61 /* getdents64 */: return vm_sys_getdents(a);
    case NR_symlinkat:
        // the target of a link is stored as the guest wrote it (re-rooted), not as it reads through a mount at this moment
        if (g_vmt && __atomic_load_n(&g_vmt->live, __ATOMIC_RELAXED) && P(0) && P(0)[0] == '/') {
            int err = 0;
            char *b = (*pbp ? *pbp : (*pbp = pb_get())) + 2 * PATH_MAX;
            const char *h = xl_x(P(0), b, &err, 0);
            if (h && pre(h, g_root)) a[0] = (long)h;           // (a host path is left alone by xl: nothing is translated twice)
        }
        return trap2_base(nr, a, uc, pbp);
    case NR_openat: {
        char hb[VM_HIDE_PATH];
        int ha = vm_hide_path(a, 1, pbp, hb, sizeof hb);
        if (ha == 1) return -VMF_ENOENT;
        if (ha == 2 && !(a[2] & 3)) return S4(NR_openat, AT_FDCWD, hb, a[2], a[3]);   // a replaced file reads as its stock backup; a write open keeps the overlay's file (the stock tree is read-only to the guest anyway)
        int info = 0;
        if (P(1) && g_vmt && __atomic_load_n(&g_vmt->live, __ATOMIC_RELAXED) && vm_is_mounts_path(P(1), &info)) {
            long r = vm_mounts_open(info);
            if (r >= 0) return r;
        }
        long r = trap2_base(nr, a, uc, pbp);
        if (r == -VMF_ENOENT && P(1) && P(1)[0]) {           // a device node here? (it is a link to nowhere for the kernel)
            int err = 0; u32 t, maj, min, mode;
            const char *h = vm_host(a, 1, 0, pbp, &err);
            if (h && vm_probe(a[0], h, &t, &maj, &min, &mode)) {
                const char *dp = vm_devpath(maj, min);
                if (!dp || t != 'c') return -VMF_ENXIO;
                return S4(NR_openat, AT_FDCWD, dp, a[2], a[3]);
            }
        }
        return r;
    }
    case NR_newfstatat: {
        char hb[VM_HIDE_PATH];
        int ha = vm_hide_path(a, 1, pbp, hb, sizeof hb);
        if (ha == 1) return -VMF_ENOENT;
        if (ha == 2) { struct kstat *st = (struct kstat *)a[2]; long r = S4(NR_newfstatat, AT_FDCWD, hb, st, a[3]); if (r == 0) fake_owner(st); return r; }
        long r = trap2_base(nr, a, uc, pbp);
        if (!P(1) || !P(1)[0] || (a[3] & 0x1000)) return r;
        struct kstat *st = (struct kstat *)a[2];
        int err = 0, nofollow = (a[3] & 0x100) != 0;
        if (r == 0 && nofollow && (st->st_mode & 0170000) == 0120000) {
            const char *h = vm_host(a, 1, 0, pbp, &err);
            if (h && vm_stat_marker(a[0], h, st, 1)) return 0;
        } else if (r == -VMF_ENOENT && !nofollow) {
            const char *h = vm_host(a, 1, 0, pbp, &err);
            if (h && vm_stat_marker(a[0], h, st, 0)) return 0;
        } else if (r == 0 && g_vmt && __atomic_load_n(&g_vmt->live, __ATOMIC_RELAXED) && P(1)[0] == '/') {
            // what is on a tmpfs is on its device (mountpoint(1) compares the device of a directory and of its parent)
            char t[1104], o[1104]; struct vm_wr w;
            if (slen(P(1)) < 1000 && norm(P(1), t, sizeof t) == 0 && vm_walk(t, o, sizeof o, &w) == 0 && w.crossed && w.kind == VM_TMPFS)
                st->st_dev = vm_makedev(0, w.dev);
        }
        return r;
    }
    case NR_statx: {
        char hb[VM_HIDE_PATH];
        int ha = vm_hide_path(a, 1, pbp, hb, sizeof hb);
        if (ha == 1) return -VMF_ENOENT;
        if (ha == 2) return S5(NR_statx, AT_FDCWD, hb, a[2], a[3], a[4]);
        long r = trap2_base(nr, a, uc, pbp);
        if (!P(1) || !P(1)[0] || !a[4] || (a[2] & 0x1000)) return r;
        u8 *sx = (u8 *)a[4];
        int err = 0, nofollow = (a[2] & 0x100) != 0, fix = 0;
        const char *h = 0;
        if (r == 0 && nofollow && (*(u16 *)(sx + 28) & 0170000) == 0120000) { h = vm_host(a, 1, 0, pbp, &err); fix = h != 0; }
        else if (r == -VMF_ENOENT && !nofollow) {
            h = vm_host(a, 1, 0, pbp, &err);
            if (h) { r = S5(NR_statx, a[0], h, a[2] | 0x100, a[3], a[4]); fix = r == 0; }
        }
        if (fix) {
            u32 t, maj, min, mode;
            if (vm_probe(a[0], h, &t, &maj, &min, &mode)) {
                *(u16 *)(sx + 28) = (u16)((t == 'b' ? 0060000u : 0020000u) | (mode & 07777));
                *(u64 *)(sx + 40) = 0; *(u64 *)(sx + 48) = 0; *(u32 *)(sx + 16) = 1;
                *(u32 *)(sx + 128) = maj; *(u32 *)(sx + 132) = min;
                *(u32 *)(sx + 20) = 0; *(u32 *)(sx + 24) = 0;
                return 0;
            }
            if (r == 0 && !nofollow) return -VMF_ENOENT;
        }
        return r;
    }
    case NR_readlinkat: {
        char hb[VM_HIDE_PATH];
        if (vm_hide_path(a, 1, pbp, hb, sizeof hb) == 1) return -VMF_ENOENT;   // a Magisk link must not give its target away
        long r = trap2_base(nr, a, uc, pbp);
        if (r >= VM_DEVLEN + 6 && a[2] && pre((const char *)a[2], VM_DEV)) return -VMF_EINVAL;    // a device is not a link
        return r;
    }
    case NR_faccessat: {
        char hb[VM_HIDE_PATH];
        int ha = vm_hide_path(a, 1, pbp, hb, sizeof hb);
        if (ha == 1) return -VMF_ENOENT;
        if (ha == 2) return S4(NR_faccessat, AT_FDCWD, hb, a[2], a[3]);
        long r = trap2_base(nr, a, uc, pbp);
        if (r == -VMF_ENOENT && P(1) && P(1)[0]) {
            int err = 0; u32 t, maj, min, mode;
            const char *h = vm_host(a, 1, 0, pbp, &err);
            if (h && vm_probe(a[0], h, &t, &maj, &min, &mode)) return 0;
        }
        return r;
    }
    case NR_fchmodat: case 452 /* fchmodat2 */: {
        long r = trap2_base(nr, a, uc, pbp);
        if (r == -VMF_ENOENT && P(1) && P(1)[0]) {
            int err = 0; u32 t, maj, min, mode;
            const char *h = vm_host(a, 1, 0, pbp, &err);
            if (h && vm_probe(a[0], h, &t, &maj, &min, &mode)) {
                char tg[80];
                vm_dev_target(tg, t, maj, min, (u32)a[2] & 07777);
                if (S3(NR_unlinkat, a[0], h, 0) < 0) return -VMF_EPERM;
                return S3(NR_symlinkat, tg, a[0], h);
            }
        }
        return r;
    }
    }
    return trap2_base(nr, a, uc, pbp);
}

static long trap2(long nr, long *a, void *uc, char **pbp) {
    if (!g_vm) return trap2_base(nr, a, uc, pbp);
    return vm_trap2(nr, a, uc, pbp);
}

// ---- /proc/<pid>/exe of another guest process (AEmulator Plus, 2026-11-21) ----------------------------------------------
// run64.c answers stat/open/readlink of "/proc/self/exe" with the GUEST program (g_exe), but the same call on "/proc/<pid>/exe"
// reached the phone's kernel and said "the shim itself". Magisk's daemon refuses every non-root client whose
// stat("/proc/<pid>/exe") is not the same file as its own stat("/proc/self/exe") (daemon.rs is_client, feature check-client):
// with the daemon's exe the guest's magisk and the client's exe the shim they never matched, and every su request of an app
// ended in "Accessed denied" before any policy was looked at. Each process leaves its guest program in
// <root>/dev/.aemu-exe/<pid> (only with the feature on, see vm_exe_put); /proc/<pid>/exe is then answered from there.
static int vm_exe_path(char *p, long pid) {
    if (g_rootlen > 400) return 0;
    char d[24]; char *n = utoa((u64)pid, d + sizeof d);
    memcpy(p, g_root, g_rootlen); memcpy(p + g_rootlen, "/dev/.aemu-exe/", 15); memcpy(p + g_rootlen + 15, n, slen(n) + 1);
    return 1;
}

// the guest program of this process, published for /proc/<pid>/exe; call once g_exe is known
static void vm_exe_put(void) {
    char p[512];
    if (!g_vm || !g_exe[0] || !vm_exe_path(p, 0)) return;
    p[g_rootlen + 14] = 0; S3(NR_mkdirat, AT_FDCWD, p, 0700);
    vm_exe_path(p, S0(NR_getpid));
    long fd = S4(NR_openat, AT_FDCWD, p, 01101 /*O_WRONLY|O_CREAT|O_TRUNC*/ | 02000000, 0600);
    if (fd < 0) return;
    S3(NR_write, fd, g_exe, slen(g_exe)); S1(NR_close, fd);
}

// [path] is "/proc/<pid>/exe" (a number, not "self") and that process published its program: the guest path in [out] (PATH_MAX), 1.
static int vm_proc_exe(const char *path, char *out) {
    if (!g_vm || !pre(path, "/proc/") || path[6] < '0' || path[6] > '9') return 0;
    const char *q = path + 6;
    while (*q >= '0' && *q <= '9') q++;
    if (!seq(q, "/exe")) return 0;
    char p[512];
    if (!vm_exe_path(p, (long)atou(path + 6))) return 0;
    long fd = S4(NR_openat, AT_FDCWD, p, 02000000, 0);
    if (fd < 0) return 0;
    long n = S3(NR_read, fd, out, PATH_MAX - 1); S1(NR_close, fd);
    if (n <= 0) return 0;
    out[n] = 0;
    return 1;
}

// ---- SO_PEERSEC (Zygisk, AEmulator Plus) --------------------------------------------------------------------------------
// Magisk's daemon answers a ZYGISK request only from a peer whose SO_PEERSEC context is "u:r:zygote:s0" (daemon.rs is_zygote;
// a non-root peer that is not zygote must also be a su client). The phone's kernel answers with the host app's own context (or
// ENOPROTOOPT), so the zygote's request was ACCESS_DENIED and no process was ever injected. The peer's guest uid is what
// <root>/dev/.aemu-ids/<pid> says (none: root, as for SO_PEERCRED): root (zygote and its children until they setuid) is the
// zygote, 1000 system_server, 2000 shell, the rest an app. Root may do anything the daemon offers anyway.
static long vm_peersec(long fd, char *buf, u32 *len) {
    u32 uc[3] = { 0, 0, 0 }; u32 ul = 12; u64 uid = 0;
    if (S5(NR_getsockopt, fd, 1, 17 /* SO_PEERCRED */, uc, &ul) == 0 && g_rootlen <= 400) {
        char p[512], t[48];
        char d[24]; char *n = utoa((u64)uc[0], d + sizeof d);
        memcpy(p, g_root, g_rootlen); memcpy(p + g_rootlen, "/dev/.aemu-ids/", 15); memcpy(p + g_rootlen + 15, n, slen(n) + 1);
        long f = S4(NR_openat, AT_FDCWD, p, 02000000, 0);
        if (f >= 0) { long r = S3(NR_read, f, t, sizeof t - 1); S1(NR_close, f); if (r > 0) { t[r] = 0; uid = atou(t); } }
    }
    const char *c = uid == 0 ? "u:r:zygote:s0" : uid == 1000 ? "u:r:system_server:s0" : uid == 2000 ? "u:r:shell:s0" : "u:r:untrusted_app:s0";
    if (!buf || !len) return -14;
    u32 n = (u32)slen(c) + 1;
    if (*len < n) { *len = n; return -34; }        // ERANGE, as the kernel
    memcpy(buf, c, n); *len = n;
    return 0;
}

static void vm_init(void) {
    char p[512];
    if (g_rootlen > 440) return;
    memcpy(p, g_root, g_rootlen); memcpy(p + g_rootlen, "/dev/.aemu-vmount", 18);
    if (S4(NR_faccessat, AT_FDCWD, p, 0, 0) != 0) return;
    g_vm = 1;
    memcpy(p + g_rootlen, "/dev/.aemu-mounts", 18);
    long fd = S4(NR_openat, AT_FDCWD, p, 02 | 0100 | 02000000 /* O_RDWR|O_CREAT|O_CLOEXEC */, 0600);
    if (fd < 0) return;
    struct kstat st;
    if (S2(NR_fstat, fd, &st) == 0 && (u64)st.st_size < VM_FILE) S2(NR_ftruncate, fd, VM_FILE);
    long m = S6(NR_mmap, 0, VM_FILE, 3 /* PROT_READ|PROT_WRITE */, 1 /* MAP_SHARED */, fd, 0);
    S1(NR_close, fd);
    if ((u64)m < (u64)-4096) g_vmt = (struct vm_tab *)m;
}

#endif
