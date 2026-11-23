// Host test of vmount.h (gcc, x86-64): sc() is a shim that maps the arm64 syscall numbers run64.c uses onto libc, the
// helpers come from run64.c's own text. Build/run:  sh tests/vmount_test.sh
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/syscall.h>
typedef unsigned long u64; typedef long i64; typedef unsigned int u32; typedef unsigned short u16; typedef unsigned char u8;
#define S0(n) sc(n, 0, 0, 0, 0, 0, 0)
#define S1(n, a) sc(n, (long)(a), 0, 0, 0, 0, 0)
#define S2(n, a, b) sc(n, (long)(a), (long)(b), 0, 0, 0, 0)
#define S3(n, a, b, c) sc(n, (long)(a), (long)(b), (long)(c), 0, 0, 0)
#define S4(n, a, b, c, d) sc(n, (long)(a), (long)(b), (long)(c), (long)(d), 0, 0)
#define S5(n, a, b, c, d, e) sc(n, (long)(a), (long)(b), (long)(c), (long)(d), (long)(e), 0)
#define S6(n, a, b, c, d, e, f) sc(n, (long)(a), (long)(b), (long)(c), (long)(d), (long)(e), (long)(f))
static long sc(long, long, long, long, long, long, long);
#include "enums.inc"

#define ENAMETOOLONG 36
#define PATH_MAX 4096
#include "helpers.inc"
struct kstat { u64 st_dev, st_ino; u32 st_mode, st_nlink, st_uid, st_gid; u64 st_rdev, __pad1; i64 st_size; int st_blksize, __pad2; i64 st_blocks; i64 at, atn, mt, mtn, ct, ctn; u32 __u4, __u5; };
static char g_root[512]; static u64 g_rootlen;
static int g_vm;
static int g_uid;                       // the emulated user: 0 = root
static char g_exe[PATH_MAX];
static void fake_owner(struct kstat *st) { (void)st; }

static long sc(long nr, long a, long b, long c, long d, long e, long f) {
    long r;
    switch (nr) {
    case NR_openat: r = openat((int)a, (char *)b, (int)c, (int)d); break;
    case NR_mknodat: r = mknodat((int)a, (char *)b, (mode_t)c, (dev_t)d); break;
    case NR_readlinkat: r = readlinkat((int)a, (char *)b, (char *)c, (size_t)d); break;
    case NR_symlinkat: r = symlinkat((char *)a, (int)b, (char *)c); break;
    case NR_unlinkat: r = unlinkat((int)a, (char *)b, (int)c); break;
    case NR_mkdirat: r = mkdirat((int)a, (char *)b, (mode_t)c); break;
    case NR_fchmodat: r = fchmodat((int)a, (char *)b, (mode_t)c, 0); break;
    case NR_faccessat: r = faccessat((int)a, (char *)b, (int)c, 0); break;
    case NR_newfstatat: { struct stat s; r = fstatat((int)a, (char *)b, &s, (int)d); if (r == 0) { struct kstat *k = (struct kstat *)c; memset(k, 0, sizeof *k);
        k->st_dev = s.st_dev; k->st_ino = s.st_ino; k->st_mode = s.st_mode; k->st_nlink = s.st_nlink; k->st_size = s.st_size; } break; }
    case NR_fstat: { struct stat s; r = fstat((int)a, &s); if (r == 0) { struct kstat *k = (struct kstat *)b; memset(k, 0, sizeof *k); k->st_size = s.st_size; } break; }
    case NR_mmap: { void *p = mmap((void *)a, (size_t)b, (int)c, (int)d, (int)e, (off_t)f); return p == MAP_FAILED ? -errno : (long)p; }
    case NR_munmap: r = munmap((void *)a, (size_t)b); break;
    case NR_ftruncate: r = ftruncate((int)a, b); break;
    case NR_close: r = close((int)a); break;
    case NR_read: r = read((int)a, (void *)b, (size_t)c); break;
    case NR_write: r = write((int)a, (void *)b, (size_t)c); break;
    case NR_lseek: r = lseek((int)a, b, (int)c); break;
    case NR_memfd_create: r = memfd_create((char *)a, (unsigned)b); break;
    case NR_getcwd: r = getcwd((char *)a, (size_t)b) ? (long)strlen((char *)a) + 1 : -1; break;
    case 61: r = syscall(SYS_getdents64, (int)a, (void *)b, (size_t)c); break;
    case NR_sched_yield: return 0;
    case 166: return umask((mode_t)a);
    default: fprintf(stderr, "sc: unmocked syscall %ld\n", nr); abort();
    }
    return r < 0 ? -errno : r;
}
static void unxl(char *p) { if (g_rootlen && !strncmp(p, g_root, g_rootlen)) memmove(p, p + g_rootlen, strlen(p + g_rootlen) + 1); if (!*p) strcpy(p, "/"); }
static void vm_rewrite(char *n, u64 cap);
static const char *xl_x(const char *p, char *buf, int *err, int rw) {
    if (!p || p[0] != '/') return p;
    char *n = buf + g_rootlen;
    if ((*err = norm(p, n, PATH_MAX - g_rootlen))) return 0;
    if (rw && g_vm) vm_rewrite(n, PATH_MAX - g_rootlen);
    if (!strncmp(n, "/dev/null", 10)) return "/dev/null";
    memcpy(buf, g_root, g_rootlen);
    return buf;
}
static const char *xl(const char *p, char *buf, int *err) { return xl_x(p, buf, err, 1); }
static char g_pb[4][3 * PATH_MAX]; static int g_pbn;
static char *pb_get(void) { return g_pb[g_pbn++ & 3]; }
#define P(i) ((const char *)a[i])
#define PB(i) ((*pbp ? *pbp : (*pbp = pb_get())) + (i) * PATH_MAX)
static long base_calls;
static long vm_mode_fix(long nr, long dirfd, const char *host, long mode);   // vmount.h (in run64.c the include comes first)
static long trap2_base(long nr, long *a, void *uc, char **pbp) {   // the real handler's job for these calls: translate and pass on
    (void)uc; base_calls++;
    int err = 0; char *b = PB(0), *b1 = PB(1);
    const char *h = P(1) ? xl((const char *)a[1], b, &err) : 0;
    switch (nr) {
    case NR_openat: return S4(nr, a[0], h, a[2], a[3]);
    case NR_newfstatat: { long r = S4(nr, a[0], h, a[2], a[3]); return r; }
    case NR_faccessat: return S4(nr, a[0], h, a[2], a[3]);
    case NR_readlinkat: { long r = S4(nr, a[0], h, b1, PATH_MAX - 1); if (r > 0) memcpy((void *)a[2], b1, (size_t)r); return r; }
    case NR_fchmodat: return S4(nr, a[0], h, vm_mode_fix(nr, a[0], h, a[2]), 0);
    case NR_mkdirat: return S3(nr, a[0], h, vm_mode_fix(nr, a[0], h, a[2]));
    case NR_mknodat: return S4(nr, a[0], h, a[2], a[3]);
    case NR_symlinkat: { const char *h2 = xl((const char *)a[2], b1, &err); return S3(nr, P(0), a[1], h2); }
    }
    return -38;
}
#include "../vmount.h"

static int fails;
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); fails++; } } while (0)
static char root[300];
static long T(long nr, long a0, long a1, long a2, long a3, long a4) { long a[6] = { a0, a1, a2, a3, a4, 0 }; char *pb = 0; return trap2(nr, a, 0, &pb); }
static void wr(const char *rel, const char *txt) { char p[600]; snprintf(p, sizeof p, "%s%s", root, rel); FILE *f = fopen(p, "w"); fputs(txt, f); fclose(f); }
static void mk(const char *rel) { char p[600]; snprintf(p, sizeof p, "%s%s", root, rel); mkdir(p, 0755); }
static int rd(const char *guest, char *out, int cap) {     // open + read through the handler
    long fd = T(NR_openat, AT_FDCWD, (long)guest, 0, 0, 0); if (fd < 0) return (int)fd;
    long n = read((int)fd, out, cap - 1); close((int)fd); out[n < 0 ? 0 : n] = 0; return (int)n;
}
int main(void) {
    strcpy(root, "/tmp/vmt.XXXXXX"); if (!mkdtemp(root)) return 2;
    strcpy(g_root, root); g_rootlen = strlen(root);
    mk("/dev"); mk("/system"); mk("/system/etc"); mk("/system/etc/security"); mk("/system/etc/security/cacerts"); mk("/apex"); mk("/apex/com.android.conscrypt");
    mk("/apex/com.android.conscrypt/cacerts"); mk("/data"); mk("/data/local"); mk("/data/local/tmp"); mk("/data/local/tmp/ca"); mk("/system/app"); mk("/system/app/Bloat");
    mk("/proc"); mk("/data/data");
    wr("/system/etc/security/cacerts/old.0", "stock\n"); wr("/apex/com.android.conscrypt/cacerts/apex.0", "apex\n"); wr("/data/local/tmp/ca/mine.0", "mine\n");
    wr("/system/etc/hosts", "127.0.0.1 localhost\n"); wr("/data/local/tmp/hosts", "0.0.0.0 ads\n"); wr("/system/app/Bloat/Bloat.apk", "apk\n");
    { char p[600]; snprintf(p, sizeof p, "%s/etc", root); symlink("/system/etc", p); }       // an absolute link: taken from the image root
    { char p[600]; snprintf(p, sizeof p, "%s/dev/.aemu-vmount", root); close(open(p, O_CREAT | O_WRONLY, 0600)); }
    vm_init(); CHECK(g_vm == 1 && g_vmt);
    char b[256];
    (void)0;

    // bind a directory over a directory
    CHECK(T(NR_mount, (long)"/data/local/tmp/ca", (long)"/apex/com.android.conscrypt/cacerts", 0, VMF_MS_BIND, 0) == 0);
    CHECK(rd("/apex/com.android.conscrypt/cacerts/mine.0", b, sizeof b) > 0 && !strcmp(b, "mine\n"));
    CHECK(rd("/apex/com.android.conscrypt/cacerts/apex.0", b, sizeof b) == -2);               // hidden by the mount
    CHECK(rd("/data/local/tmp/ca/mine.0", b, sizeof b) > 0);
    // a bind of a file over a file (hosts)
    CHECK(T(NR_mount, (long)"/data/local/tmp/hosts", (long)"/etc/hosts", 0, VMF_MS_BIND, 0) == 0);    // via the /etc link
    CHECK(rd("/system/etc/hosts", b, sizeof b) > 0 && !strcmp(b, "0.0.0.0 ads\n"));
    CHECK(rd("/etc/hosts", b, sizeof b) > 0 && !strcmp(b, "0.0.0.0 ads\n"));                  // either spelling
    // refused as on Linux: missing target, file on directory
    CHECK(T(NR_mount, (long)"/data/local/tmp/hosts", (long)"/system/etc/nosuch", 0, VMF_MS_BIND, 0) == -2);
    CHECK(T(NR_mount, (long)"/data/local/tmp/hosts", (long)"/system/etc", 0, VMF_MS_BIND, 0) == -VMF_ENOTDIR);
    // a tmpfs hides the directory; copy into it; bind the tmpfs somewhere else (the certificate recipe)
    CHECK(T(NR_mount, (long)"tmpfs", (long)"/system/etc/security/cacerts", (long)"tmpfs", 0, (long)"mode=755") == 0);
    CHECK(rd("/system/etc/security/cacerts/old.0", b, sizeof b) == -2);
    { char p[600]; snprintf(p, sizeof p, "%s/dev/.aemu-tmpfs/3", root); CHECK(access(p, F_OK) == 0); }   // the backing directory exists
    { long fd = T(NR_openat, AT_FDCWD, (long)"/system/etc/security/cacerts/cp.0", 0101, 0644, 0); CHECK(fd >= 0); if (fd >= 0) { write((int)fd, "cp\n", 3); close((int)fd); } }
    CHECK(rd("/system/etc/security/cacerts/cp.0", b, sizeof b) > 0 && !strcmp(b, "cp\n"));
    { char p[600]; snprintf(p, sizeof p, "%s/system/etc/security/cacerts/cp.0", root); CHECK(access(p, F_OK) != 0); }   // the stock directory is untouched
    CHECK(T(NR_mount, (long)"/system/etc/security/cacerts", (long)"/apex/com.android.conscrypt/cacerts", 0, VMF_MS_BIND, 0) == 0);
    CHECK(rd("/apex/com.android.conscrypt/cacerts/cp.0", b, sizeof b) > 0);                  // stacked on the first bind: the tmpfs shows
    CHECK(rd("/apex/com.android.conscrypt/cacerts/mine.0", b, sizeof b) == -2);
    // st_dev of a tmpfs differs from its parent's
    { struct kstat s1, s2; CHECK(T(NR_newfstatat, AT_FDCWD, (long)"/system/etc/security/cacerts", (long)&s1, 0, 0) == 0);
      CHECK(T(NR_newfstatat, AT_FDCWD, (long)"/system/etc/security", (long)&s2, 0, 0) == 0); CHECK(s1.st_dev != s2.st_dev); }
    // /proc/mounts and mountinfo list them
    { char big[8192]; int n = rd("/proc/self/mounts", big, sizeof big); CHECK(n > 0 && strstr(big, "tmpfs /system/etc/security/cacerts tmpfs") && strstr(big, "/apex/com.android.conscrypt/cacerts"));
      n = rd("/proc/self/mountinfo", big, sizeof big); CHECK(n > 0 && strstr(big, " - tmpfs tmpfs ")); }
    // umount: top of the stack goes first, the one below shows again; a plain directory is EINVAL; not allowed targets are ignored
    CHECK(T(NR_umount2, (long)"/apex/com.android.conscrypt/cacerts", 0, 0, 0, 0) == 0);
    CHECK(rd("/apex/com.android.conscrypt/cacerts/mine.0", b, sizeof b) > 0);
    CHECK(T(NR_umount2, (long)"/apex/com.android.conscrypt/cacerts", 0, 0, 0, 0) == 0);
    CHECK(rd("/apex/com.android.conscrypt/cacerts/apex.0", b, sizeof b) > 0);                 // stock again
    CHECK(T(NR_umount2, (long)"/apex/com.android.conscrypt/cacerts", 0, 0, 0, 0) == -VMF_EINVAL);
    CHECK(T(NR_mount, (long)"tmpfs", (long)"/data/data", (long)"tmpfs", 0, 0) == 0 && rd("/data/data/x", b, sizeof b) == -2);   // ignored: firmware boot
    CHECK(T(NR_mount, (long)"tmpfs", (long)"/dev", (long)"tmpfs", 0, 0) == 0);
    CHECK(T(NR_mount, (long)"tmpfs", (long)"/apex/com.android.conscrypt", (long)"tmpfs", 0, 0) == 0);   // /apex/<name> stays apexd's
    CHECK(T(NR_mount, (long)"x", (long)"/system/etc/security", (long)"overlay", 0, 0) == -VMF_ENODEV);
    CHECK(T(NR_mount, 0, (long)"/system", 0, VMF_MS_REMOUNT | 1, 0) == 0);
    // move
    mk("/data/local/tmp/mv");
    CHECK(T(NR_mount, (long)"tmpfs", (long)"/data/local/tmp/mv", (long)"tmpfs", 0, 0) == 0);
    CHECK(T(NR_mount, (long)"/data/local/tmp/mv", (long)"/system/app/Bloat", 0, VMF_MS_MOVE, 0) == 0);
    CHECK(rd("/system/app/Bloat/Bloat.apk", b, sizeof b) == -2 && rd("/data/local/tmp/mv/x", b, sizeof b) == -2);
    CHECK(T(NR_umount2, (long)"/system/app/Bloat", 0, 0, 0, 0) == 0 && rd("/system/app/Bloat/Bloat.apk", b, sizeof b) > 0);

    // the Magisk daemon's own mounts stay ignored (Overlay does its work)
    strcpy(g_exe, "/debug_ramdisk/magisk");
    CHECK(T(NR_mount, (long)"tmpfs", (long)"/system/app", (long)"tmpfs", 0, 0) == 0 && rd("/system/app/Bloat/Bloat.apk", b, sizeof b) > 0);
    strcpy(g_exe, "/system/bin/toybox");

    // device nodes: a whiteout
    CHECK(T(NR_mknodat, AT_FDCWD, (long)"/system/app/Gone", 020644, 0, 0) == 0);
    { struct kstat s; CHECK(T(NR_newfstatat, AT_FDCWD, (long)"/system/app/Gone", (long)&s, 0x100, 0) == 0); { mode_t um = umask(0); umask(um); CHECK((s.st_mode & 0170000) == 0020000 && s.st_rdev == 0 && (s.st_mode & 0777) == (0644 & ~um)); }
      memset(&s, 0xff, sizeof s); CHECK(T(NR_newfstatat, AT_FDCWD, (long)"/system/app/Gone", (long)&s, 0, 0) == 0 && (s.st_mode & 0170000) == 0020000); }
    CHECK(T(NR_faccessat, AT_FDCWD, (long)"/system/app/Gone", 0, 0, 0) == 0);
    CHECK(T(NR_readlinkat, AT_FDCWD, (long)"/system/app/Gone", (long)b, 100, 0) == -VMF_EINVAL);
    CHECK(T(NR_openat, AT_FDCWD, (long)"/system/app/Gone", 0, 0, 0) == -VMF_ENXIO);
    CHECK(T(NR_mknodat, AT_FDCWD, (long)"/system/app/Gone", 020644, 0, 0) == -VMF_EEXIST);
    CHECK(T(NR_mknodat, AT_FDCWD, (long)"/system/app/Null", 020666, 1ul << 8 | 3, 0) == 0);
    { long fd = T(NR_openat, AT_FDCWD, (long)"/system/app/Null", 1, 0, 0); CHECK(fd >= 0); if (fd >= 0) close((int)fd); }
    CHECK(T(NR_mknodat, AT_FDCWD, (long)"/system/app/Fifo", 010644, 0, 0) == 0);            // not ours: the base does it
    { char buf[1024]; snprintf(b, sizeof b, "%.200s/system/app", root); long fdd = open(b, O_RDONLY | O_DIRECTORY); long n = T(61, fdd, (long)buf, sizeof buf, 0, 0);
      int chr = 0, lnk = 0; for (long o = 0; o < n; o += *(u16 *)(buf + o + 16)) { u8 t = buf[o + 18]; const char *nm = buf + o + 19; if (!strcmp(nm, "Gone") && t == 2) chr++; if (!strcmp(nm, "Null") && t == 2) chr++; if (t == 10) lnk++; }
      CHECK(chr == 2 && lnk == 0); close((int)fdd); }
    CHECK(T(NR_fchmodat, AT_FDCWD, (long)"/system/app/Gone", 0, 0, 0) == 0);
    { struct kstat s; CHECK(T(NR_newfstatat, AT_FDCWD, (long)"/system/app/Gone", (long)&s, 0x100, 0) == 0 && (s.st_mode & 07777) == 0); }

    // OWNER ACCESS: the emulated root never loses the owner bits (Magisk's mode-000 directories)
    mk("/system/lockdir");
    CHECK(T(NR_mkdirat, AT_FDCWD, (long)"/data/local/tmp/d000", 0, 0, 0) == 0);                  // root: mkdir mode 000 ...
    { struct kstat s; CHECK(T(NR_newfstatat, AT_FDCWD, (long)"/data/local/tmp/d000", (long)&s, 0, 0) == 0 && (s.st_mode & 0700) == 0700); }   // ... still enterable
    CHECK(T(NR_mkdirat, AT_FDCWD, (long)"/data/local/tmp/d000/sub", 0755, 0, 0) == 0);             // what Magisk's mkdir of worker/system/bin needed
    CHECK(T(NR_fchmodat, AT_FDCWD, (long)"/system/lockdir", 0, 0, 0) == 0);                       // chmod 000 of a directory
    { struct kstat s; CHECK(T(NR_newfstatat, AT_FDCWD, (long)"/system/lockdir", (long)&s, 0, 0) == 0 && (s.st_mode & 07777) == 0700); }
    wr("/data/local/tmp/f000", "x");
    CHECK(T(NR_fchmodat, AT_FDCWD, (long)"/data/local/tmp/f000", 0, 0, 0) == 0);                  // chmod 000 of a file (magisk.db)
    { struct kstat s; CHECK(T(NR_newfstatat, AT_FDCWD, (long)"/data/local/tmp/f000", (long)&s, 0, 0) == 0 && (s.st_mode & 07777) == 0600); }
    CHECK(T(NR_fchmodat, AT_FDCWD, (long)"/data/local/tmp/f000", 0644, 0, 0) == 0);               // a normal mode stays as asked
    { struct kstat s; CHECK(T(NR_newfstatat, AT_FDCWD, (long)"/data/local/tmp/f000", (long)&s, 0, 0) == 0 && (s.st_mode & 07777) == 0644); }
    CHECK(T(NR_fchmodat, AT_FDCWD, (long)"/data/local/tmp/f000", 0111, 0, 0) == 0);               // group/other bits as asked, owner gains rw
    { struct kstat s; CHECK(T(NR_newfstatat, AT_FDCWD, (long)"/data/local/tmp/f000", (long)&s, 0, 0) == 0 && (s.st_mode & 07777) == 0711); }
    CHECK(T(NR_fchmodat, AT_FDCWD, (long)"/data/local/tmp/nosuch", 0, 0, 0) < 0);                 // a missing target still fails
    g_uid = 10132;                                                                                 // an app user: a real 000 stays 000
    CHECK(T(NR_mkdirat, AT_FDCWD, (long)"/data/local/tmp/app000", 0, 0, 0) == 0);
    { struct kstat s; CHECK(T(NR_newfstatat, AT_FDCWD, (long)"/data/local/tmp/app000", (long)&s, 0, 0) == 0 && (s.st_mode & 07777) == 0); }
    g_uid = 0; g_vm = 0;                                                                           // feature off: untouched, as before
    CHECK(T(NR_mkdirat, AT_FDCWD, (long)"/data/local/tmp/off000", 0, 0, 0) == 0);
    { struct kstat s; CHECK(T(NR_newfstatat, AT_FDCWD, (long)"/data/local/tmp/off000", (long)&s, 0, 0) == 0 && (s.st_mode & 07777) == 0); }
    g_vm = 1;

    printf(fails ? "%d FAILED\n" : "all vmount tests passed\n", fails);
    char cmd[400]; snprintf(cmd, sizeof cmd, "rm -rf %s", root); if (system(cmd)) {}
    return fails != 0;
}
