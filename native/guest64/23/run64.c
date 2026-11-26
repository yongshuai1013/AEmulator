// aemu64 — runs the firmware's own arm64 programs natively on an arm64 phone.
//
//   aemu64 <argv0> <guest program> [args…]        AEMU64_ROOT=<image root>
//
// The guest keeps its own linker and bionic. A seccomp filter traps the calls that must see the guest's world
// (paths, ids, ashmem, exec) into the SIGSYS handler below, which redirects them under the image root. Syscalls
// made from this image (linked at IMAGE_BASE) pass the filter, so the handler talks to the kernel directly.
// No libc: the guest owns the thread pointer.

typedef unsigned long u64;
typedef long i64;
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;

#define IMAGE_HI 0x20u            /* IMAGE_BASE >> 32, see build.sh */
#define IMAGE_SPAN 0x00100000u

long sc(long nr, long a, long b, long c, long d, long e, long f);
void enter_guest(void *entry, void *sp) __attribute__((noreturn));
#define S0(n) sc(n, 0, 0, 0, 0, 0, 0)
#define S1(n, a) sc(n, (long)(a), 0, 0, 0, 0, 0)
#define S2(n, a, b) sc(n, (long)(a), (long)(b), 0, 0, 0, 0)
#define S3(n, a, b, c) sc(n, (long)(a), (long)(b), (long)(c), 0, 0, 0)
#define S4(n, a, b, c, d) sc(n, (long)(a), (long)(b), (long)(c), (long)(d), 0, 0)
#define S5(n, a, b, c, d, e) sc(n, (long)(a), (long)(b), (long)(c), (long)(d), (long)(e), 0)
#define S6(n, a, b, c, d, e, f) sc(n, (long)(a), (long)(b), (long)(c), (long)(d), (long)(e), (long)(f))

enum {
    NR_setxattr = 5, NR_lsetxattr = 6, NR_getxattr = 8, NR_lgetxattr = 9, NR_listxattr = 11, NR_llistxattr = 12,
    NR_removexattr = 14, NR_lremovexattr = 15, NR_getcwd = 17, NR_dup = 23, NR_dup3 = 24, NR_fcntl = 25,
    NR_inotify_add_watch = 27, NR_ioctl = 29,
    NR_mknodat = 33, NR_mkdirat = 34, NR_unlinkat = 35, NR_symlinkat = 36, NR_linkat = 37, NR_renameat = 38,
    NR_umount2 = 39, NR_mount = 40, NR_statfs = 43, NR_truncate = 45, NR_ftruncate = 46, NR_faccessat = 48,
    NR_chdir = 49, NR_chroot = 51, NR_fchmodat = 53, NR_fchownat = 54, NR_fchown = 55, NR_openat = 56, NR_close = 57,
    NR_lseek = 62, NR_read = 63, NR_write = 64, NR_writev = 66, NR_pread64 = 67, NR_pwrite64 = 68, NR_readlinkat = 78, NR_newfstatat = 79, NR_fstat = 80,
    NR_timerfd_create = 85, NR_utimensat = 88, NR_capget = 90, NR_capset = 91, NR_exit_group = 94, NR_unshare = 97,
    NR_ptrace = 117, NR_sched_yield = 124, NR_rt_sigaction = 134, NR_rt_sigprocmask = 135, NR_reboot = 142,
    NR_setregid = 143, NR_setgid = 144, NR_setreuid = 145, NR_setuid = 146, NR_setresuid = 147, NR_getresuid = 148,
    NR_setresgid = 149, NR_getresgid = 150, NR_setfsuid = 151, NR_setfsgid = 152, NR_getgroups = 158, NR_setgroups = 159,
    NR_uname = 160, NR_prctl = 167, NR_getpid = 172, NR_getuid = 174, NR_geteuid = 175, NR_getgid = 176, NR_getegid = 177, NR_gettid = 178,
    NR_socket = 198, NR_socketpair = 199, NR_bind = 200, NR_connect = 203, NR_getsockname = 204, NR_sendto = 206, NR_recvfrom = 207, NR_setsockopt = 208, NR_getsockopt = 209,
    NR_sendmsg = 211, NR_recvmsg = 212, NR_munmap = 215, NR_execve = 221, NR_mmap = 222, NR_mprotect = 226,
    NR_renameat2 = 276, NR_memfd_create = 279, NR_statx = 291,
};

#define AT_FDCWD (-100)
#define ENOENT 2
#define ENOEXEC 8
#define ENOMEM 12
#define EFAULT 14
#define EINVAL 22
#define ENAMETOOLONG 36
#define ENOSYS 38
#define PATH_MAX 4096
#define SIGSYS 31
#define SYSBIT (1UL << (SIGSYS - 1))

static u64 slen(const char *s) { u64 n = 0; while (s[n]) n++; return n; }
static int seq(const char *a, const char *b) { while (*a && *a == *b) a++, b++; return *a == *b; }
static int pre(const char *s, const char *p) { while (*p) if (*s++ != *p++) return 0; return 1; }
void *memcpy(void *d, const void *s, u64 n) { u8 *a = d; const u8 *b = s; while (n--) *a++ = *b++; return d; }
void *memset(void *d, int c, u64 n) { u8 *a = d; while (n--) *a++ = (u8)c; return d; }
void *memmove(void *d, const void *s, u64 n) {
    u8 *a = d; const u8 *b = s;
    if (a < b) while (n--) *a++ = *b++; else while (n--) a[n] = b[n];
    return d;
}
static int g_sayfd = -1;          // crash reports also go to <root>/dev/aemu_crash.log
static void say(const char *s) { S3(NR_write, 2, s, slen(s)); if (g_sayfd >= 0) S3(NR_write, g_sayfd, s, slen(s)); }
static void hex(u64 v) { char b[19] = "0x"; for (int i = 0; i < 16; i++) b[2 + i] = "0123456789abcdef"[(v >> (60 - 4 * i)) & 15]; b[18] = 0; say(b); }
struct sigctx { u64 fault; u64 regs[31]; u64 sp, pc, pstate; };
static void where(u64 addr);
static void die(const char *a, const char *b) { say("aemu64: "); say(a); say(b); say("\n"); S1(NR_exit_group, 127); for (;;); }
static u64 atou(const char *s) { u64 v = 0; while (*s >= '0' && *s <= '9') v = v * 10 + (u64)(*s++ - '0'); return v; }
static char *utoa(u64 v, char *end) { *--end = 0; do *--end = (char)('0' + v % 10); while (v /= 10); return end; }

// ---------------------------------------------------------------------------------------------------- state

static char g_root[512];        // image root on the host, no trailing slash
static u64 g_rootlen;
static char g_self[512];        // this program
static char g_props[512];       // host file behind /dev/__properties__
static char g_uts_rel[80];      // the guest kernel's release (AEMU64_UTS_RELEASE from the image's boot.img)
static char g_uts_ver[288];     // the guest kernel's version (AEMU64_UTS_VERSION)
static char g_exe[PATH_MAX];    // guest view of the running program
static int g_uid, g_gid;        // the ids the guest believes it has
static u32 g_real_uid;
static char g_qemu[512];        // qemu-arm for the firmware's 32-bit programs (AEMU64_QEMU)
static int g_crash;
static u64 g_logdev, g_logino;      // the service log on descriptor 2 (a file or a pipe to the app)
static int g_logpipe;
static u64 g_inino;                 // ...and the pipe on descriptor 0
static char g_sd[512]; static u64 g_sdlen;   // the shared card folder on the phone (AEMU64_SDCARD)
static int g_is32;                // this process is qemu-arm running a 32-bit program
static int g_is_netd;             // this process is the guest's netd (its route requests take the nlreq path below)
static void (*g_fatal[32])(int, void *, void *);
static void fatal_relay(int sig, void *info, void *uc);
// qemu-arm's signal handler blocks every signal in the context it returns to (until the guest's handler is
// entered); with SIGSYS blocked the next trapped call would kill the process, so that bit is taken back out.
static void (*g_h32[65])(int, void *, void *);
static void relay32(int sig, void *info, void *uc) {
    void (*h)(int, void *, void *) = g_h32[sig];
    if ((unsigned long)h > 1) h(sig, info, uc);
    *(unsigned long *)((char *)uc + 40) &= ~(1UL << 30);    // uc_sigmask, SIGSYS
}
// The image has the engine's 32-bit SwiftShader (system/lib/egl): 32-bit programs then have GLES 2 like the 64-bit
// ones (WebView, a vendor browser on its own Chromium), and nothing is hidden from them.
static int gles2_for_32(void) {
    static int known = -1;
    if (known < 0) {
        static char p[600];
        memcpy(p, g_root, g_rootlen); memcpy(p + g_rootlen, "/system/lib/egl/libGLESv2_swiftshader.so", 41);
        known = S4(NR_faccessat, AT_FDCWD, p, 0, 0) == 0;
    }
    return known;
}
static int is_fatal(long sig) { return sig == 11 || sig == 7 || sig == 4 || sig == 6 || sig == 8; }            // AEMU64_CRASH: report fatal signals ourselves

// ---------------------------------------------------------------------------------------------------- mask mediation
//
// 137 mask mediation: a synchronous fatal fault whose signal is blocked in the faulting thread's mask is
// never delivered — the kernel force-kills the whole process group, so the in-process capture below
// (fatal_relay / aemu-crash) never runs; that is the side the field evidence landed on (three deaths, no
// block printed). The delivery mask therefore never carries the capture-critical set, however the bits got
// there. This follows the SYSBIT pattern exactly: one store (the interrupted context's uc_sigmask — what
// the kernel restores on handler return, and what a guest mask query reads back), stripped in place at the
// same chokepoints; there is deliberately no separate guest-visible shadow mask, just as SYSBIT has none.
// The wider AEMU64_CRASH set (the four below plus SIGFPE) stays gated on g_crash exactly as before; the
// four capture-critical signals are stripped unconditionally. The first strip in a process leaves one
// aemu-mask line, so a field log shows the mediation actually fired (and for whom).
#define FATALMASK ((1UL << 10) | (1UL << 5) | (1UL << 6) | (1UL << 3))    // SIGSEGV, SIGABRT, SIGBUS, SIGILL
static volatile int g_mask_noted;
static void mask_mediate(u64 *mask) {
    if (!(*mask & FATALMASK)) return;
    *mask &= ~FATALMASK;
    if (__atomic_exchange_n(&g_mask_noted, 1, __ATOMIC_ACQUIRE)) return;
    char t[24]; char nm[20]; memset(nm, 0, sizeof nm); S5(NR_prctl, 16 /*PR_GET_NAME*/, nm, 0, 0, 0);
    say("aemu-mask: pid="); say(utoa((u64)S0(NR_getpid), t + sizeof t));
    say(" comm="); say(nm[0] ? nm : g_exe);
    say(" stripped SIGSEGV/SIGABRT/SIGBUS/SIGILL\n");
}

// ---------------------------------------------------------------------------------------------------- paths

// Lexically normalises an absolute guest path into out (no "//", ".", ".."), so it cannot climb out of the root.
static int norm(const char *p, char *out, u64 cap) {
    u64 o = 0;
    while (*p) {
        while (*p == '/') p++;
        if (!*p) break;
        const char *e = p; while (*e && *e != '/') e++;
        u64 n = (u64)(e - p);
        if (n == 1 && p[0] == '.') { p = e; continue; }
        if (n == 2 && p[0] == '.' && p[1] == '.') { while (o && out[--o] != '/'); p = e; continue; }
        if (o + n + 2 > cap) return -ENAMETOOLONG;
        out[o++] = '/'; memcpy(out + o, p, n); o += n; p = e;
    }
    if (!o) out[o++] = '/';
    out[o] = 0;
    return 0;
}

static int host_dev(const char *n) {
    static const char *const pass[] = { "null", "zero", "full", "random", "urandom", "tty", "ptmx", 0 };
    for (int i = 0; pass[i]; i++) if (seq(n, pass[i])) return 1;
    return pre(n, "pts/") || seq(n, "pts");
}

// Guest path → host path. Relative paths stay as they are (the working directory is already inside the root).
// Returns the path to use, or 0 with *err set.
// Added for AEmulator Plus (2026-11-21): mounts and device nodes, see vmount.h. rw = 0 translates without the mount table.
static int g_vm;                                            // <root>/dev/.aemu-vmount exists: this VM has real mounts
static void vm_rewrite(char *n, u64 cap);
static const char *xl_x(const char *p, char *buf, int *err, int rw);
static const char *xl(const char *p, char *buf, int *err) { return xl_x(p, buf, err, 1); }
static const char *xl_x(const char *p, char *buf, int *err, int rw) {
    if (!p) return p;
    if (p[0] != '/') return p;
    char *n = buf + g_rootlen;              // normalised straight into its place after the root
    if ((*err = norm(p, n, PATH_MAX - g_rootlen))) return 0;
    if (rw && g_vm) vm_rewrite(n, PATH_MAX - g_rootlen);       // what is mounted at this path (vmount.h)
    if ((pre(n, "/proc") && (n[5] == '/' || !n[5])) || (pre(n, "/sys") && (n[4] == '/' || !n[4]))) {
        // the image's own files win (fake sysfs, /proc/mounts); the rest is the phone's. No SELinux for the guest,
        // and none of the phone's suspend controls (reading its wakeup_count blocks the guest's power manager).
        // under qemu-arm the process has the translator's own helper thread; zygote waits before every fork until
        // /proc/self/task lists one thread, so 32-bit programs see a list of one
        if (g_is32 && n[5] == '/') {
            const char *q = n + 6; u64 pid = 0;
            if (pre(q, "self")) q += 4; else { while (*q >= '0' && *q <= '9') pid = pid * 10 + (u64)(*q++ - '0'); if (pid != (u64)S0(172 /* getpid */)) q = ""; }
            if (seq(q, "/task")) memcpy(n, "/proc/.one", 11);
        }
        memcpy(buf, g_root, g_rootlen);
        if (pre(n, "/sys/fs/selinux") || pre(n, "/sys/power") || S4(NR_faccessat, AT_FDCWD, buf, 0, 0) == 0) return buf;
        return p;
    }
    // translated code is slow: the 32-bit zygote starts without its minutes of class preloading
    if (g_is32 && seq(n, "/system/etc/preloaded-classes")) memcpy(n, "/proc/.task1/none", 18);
    if (pre(n, "/dev/") && host_dev(n + 5)) return p;
    if (g_props[0] && seq(n, "/dev/__properties__")) return g_props;
    if (pre(n, g_root) && (n[g_rootlen] == '/' || !n[g_rootlen])) return p;   // already a host path (our own argv)
    if (g_sdlen) {
        // the guest's card: plain directories for it, the shared folder of the phone underneath
        static const char *const sd[] = { "/storage/emulated/0", "/storage/emulated/legacy", "/storage/self/primary", "/sdcard",
                                          "/mnt/sdcard", "/mnt/user/0/primary", "/mnt/shell/emulated/0", "/storage/sdcard0", 0 };
        for (int i = 0; sd[i]; i++) {
            u64 l = slen(sd[i]);
            if (!pre(n, sd[i]) || (n[l] != '/' && n[l])) continue;
            u64 rest = slen(n + l);
            if (g_sdlen + rest + 1 > 3 * PATH_MAX - PATH_MAX) break;
            memmove(buf + g_sdlen, n + l, rest + 1);
            memcpy(buf, g_sd, g_sdlen);
            return buf;
        }
    }
    memcpy(buf, g_root, g_rootlen);
    if (!n[1]) n[0] = 0;
    return buf;
}

// host path → guest path, in place
static void unxl(char *p) {
    if (g_sdlen && pre(p, g_sd) && (p[g_sdlen] == '/' || !p[g_sdlen])) {
        static const char g[] = "/storage/emulated/0";
        u64 rest = slen(p + g_sdlen);
        memmove(p + sizeof g - 1, p + g_sdlen, rest + 1);
        memcpy(p, g, sizeof g - 1);
        return;
    }
    if (!pre(p, g_root)) return;
    if (!p[g_rootlen]) { p[0] = '/'; p[1] = 0; return; }
    if (p[g_rootlen] == '/') memmove(p, p + g_rootlen, slen(p + g_rootlen) + 1);
}

// ---------------------------------------------------------------------------------------------------- stat

struct kstat {
    u64 st_dev, st_ino; u32 st_mode, st_nlink, st_uid, st_gid; u64 st_rdev, __pad1; i64 st_size;
    int st_blksize, __pad2; i64 st_blocks; i64 at, atn, mt, mtn, ct, ctn; u32 __u4, __u5;
};

// files of the image belong to the app's uid; the guest expects its own system to belong to root
static void fake_owner(struct kstat *st) {
    if (st->st_uid == g_real_uid) st->st_uid = 0;
    if (st->st_gid == g_real_uid) st->st_gid = 0;
    if ((st->st_mode & 0170000) != 0120000 && !(st->st_mode & 0077) && (st->st_mode & 0170000) != 0020000) {
        // the importer keeps everything 0700/0600: show the usual world-readable modes
        st->st_mode |= ((st->st_mode & 0500) >> 3) | ((st->st_mode & 0500) >> 6);
    }
}

// ---------------------------------------------------------------------------------------------------- ashmem

#define ASHMEM_MAGIC 0x77
// an ashmem region is a memfd of ours; it may have come from another process, so it is recognised by its name
static int is_ashmem_fd(int fd) {
    char p[40], t[32], d[24];
    memcpy(p, "/proc/self/fd/", 14);
    char *n = utoa((u64)fd, d + sizeof d); memcpy(p + 14, n, slen(n) + 1);
    long r = S4(NR_readlinkat, AT_FDCWD, p, t, sizeof t - 1);
    if (r < 18) return 0;
    t[r] = 0;
    return pre(t, "/memfd:aemu-ashmem");
}

static long ashmem_ioctl(int fd, u32 cmd, u64 arg) {
    switch (cmd & 0xff) {
    case 1: return 0;                                               // SET_NAME
    case 2: if (arg) *(char *)arg = 0; return 0;                    // GET_NAME
    case 3: return S2(NR_ftruncate, fd, arg);                       // SET_SIZE
    case 4: { struct kstat st; long r = S2(NR_fstat, fd, &st); return r < 0 ? r : st.st_size; } // GET_SIZE
    // The protection mask is kept on the region itself, as a seal, so that it travels with the descriptor: Chromium
    // makes a region read-only and checks that it reads back so (Chrome's GPU process aborts otherwise)
    case 5: if (!(arg & 2)) S3(25 /*fcntl*/, fd, 1033 /*F_ADD_SEALS*/, 0x10 /*F_SEAL_FUTURE_WRITE*/); return 0; // SET_PROT_MASK
    case 6: { long s = S3(25 /*fcntl*/, fd, 1034 /*F_GET_SEALS*/, 0); return s > 0 && (s & 0x10) ? 5 : 7; }       // GET_PROT_MASK
    case 7: return 0;                                               // PIN
    case 8: return 0;                                               // UNPIN
    case 9: return 0;                                               // GET_PIN_STATUS
    case 10: return 0;                                              // PURGE
    default: return 0;
    }
}

static long fake_memfile(const char *name, const char *content, u64 len) {
    long fd = S2(NR_memfd_create, name, 1 /*MFD_CLOEXEC*/);
    if (fd < 0) return fd;
    S3(NR_write, fd, content, len);
    S3(NR_lseek, fd, 0, 0 /*SEEK_SET*/);
    return fd;
}

// ---------------------------------------------------------------------------------------------------- netlink emulation
struct aemu_nlmsghdr {
    u32 len;
    u16 type;
    u16 flags;
    u32 seq;
    u32 pid;
};
struct aemu_nlmsgerr {
    int error;
    struct aemu_nlmsghdr msg;
};

#define MAX_NL 32
static struct { int fd, peer, proto; u32 groups; } g_nl[MAX_NL];

static void nl_register(int fd, int peer, int proto) {
    for (int i = 0; i < MAX_NL; i++) {
        if (!g_nl[i].fd) {
            g_nl[i].fd = fd;
            g_nl[i].peer = peer;
            g_nl[i].proto = proto;
            g_nl[i].groups = 0;
            return;
        }
    }
}

static int nl_find_peer(int fd) {
    for (int i = 0; i < MAX_NL; i++) {
        if (g_nl[i].fd == fd) return g_nl[i].peer;
    }
    return -1;
}

static int nl_proto_of(int fd) {
    for (int i = 0; i < MAX_NL; i++) if (g_nl[i].fd == fd) return g_nl[i].proto;
    return -1;
}

static u32 nl_groups_of(int fd) {
    for (int i = 0; i < MAX_NL; i++) if (g_nl[i].fd == fd) return g_nl[i].groups;
    return 0;
}

static void nl_set_groups(int fd, u32 groups) {
    for (int i = 0; i < MAX_NL; i++) if (g_nl[i].fd == fd) { g_nl[i].groups = groups; return; }
}

static int is_nl_fd(int fd) {
    for (int i = 0; i < MAX_NL; i++) {
        if (g_nl[i].fd == fd) return 1;
    }
    return 0;
}

static void nl_close(int fd) {
    for (int i = 0; i < MAX_NL; i++) {
        if (g_nl[i].fd == fd) {
            if (g_nl[i].peer >= 0) S1(NR_close, g_nl[i].peer);
            g_nl[i].fd = 0;
            g_nl[i].peer = -1;
            g_nl[i].proto = 0;
            g_nl[i].groups = 0;
        }
    }
}

// Every request gets the answer that lets its sender go on: a dump ends at once (nothing to list), a request that
// asked for an acknowledgement gets it, and one that only waits for data (family lookups) is told "not supported".
static void nl_handle_send(int fd, const void *buf, u64 len) {
    int peer = nl_find_peer(fd);
    if (!buf || peer < 0) return;
    const char *p = buf, *end = p + len;
    while (p + sizeof(struct aemu_nlmsghdr) <= end) {
        const struct aemu_nlmsghdr *req = (const struct aemu_nlmsghdr *)p;
        if (req->len < sizeof *req || p + req->len > end) break;
        struct { struct aemu_nlmsghdr hdr; struct aemu_nlmsgerr err; } m;
        m.hdr.seq = req->seq; m.hdr.pid = req->pid;
        if ((req->flags & 0x300) == 0x300) {        // NLM_F_DUMP
            m.hdr.len = sizeof m.hdr + 4; m.hdr.type = 3 /*NLMSG_DONE*/; m.hdr.flags = 2 /*NLM_F_MULTI*/;
            m.err.error = 0;
        } else {
            m.hdr.len = sizeof m; m.hdr.type = 2 /*NLMSG_ERROR*/; m.hdr.flags = 0;
            m.err.error = (req->flags & 4 /*NLM_F_ACK*/) ? 0 : -95 /*EOPNOTSUPP*/;
            m.err.msg = *req;
        }
        S3(NR_write, peer, &m, m.hdr.len);
        p += (req->len + 3) & ~3U;
    }
}

struct sockaddr_un { u16 family; char path[108]; };
#include "bclient.c"

// ---------------------------------------------------------------------------------------------------- framebuffer

// /dev/graphics/fb0 is a plain file of two RGBX pages (the app's screen view maps it); the guest's gralloc asks
// the usual fbdev questions, answered here from <root>/dhd.fbgeom ("width height").
static u32 g_fbw, g_fbh;

static void fb_geom(void) {
    char p[600], t[64];
    memcpy(p, g_root, g_rootlen); memcpy(p + g_rootlen, "/dhd.fbgeom", 12);
    long fd = S4(NR_openat, AT_FDCWD, p, 0, 0);
    g_fbw = 720; g_fbh = 1280;
    if (fd < 0) return;
    long n = S3(NR_read, fd, t, sizeof t - 1);
    S1(NR_close, fd);
    if (n <= 0) return;
    t[n] = 0;
    const char *q = t; u32 w = (u32)atou(q);
    while (*q >= '0' && *q <= '9') q++;
    while (*q == ' ') q++;
    u32 h = (u32)atou(q);
    if (w && h) { g_fbw = w; g_fbh = h; }
}

static long fb_ioctl(u32 cmd, u64 arg) {
    if (!g_fbw) fb_geom();
    switch (cmd) {
    case 0x4600: {                              // FBIOGET_VSCREENINFO
        u32 *v = (u32 *)arg;
        memset(v, 0, 160);
        v[0] = g_fbw; v[1] = g_fbh; v[2] = g_fbw; v[3] = g_fbh; v[6] = 32;   // one page: gralloc copies each frame in on post
        v[8] = 0; v[9] = 8; v[11] = 8; v[12] = 8; v[14] = 16; v[15] = 8; v[17] = 24; v[18] = 8;   // R, G, B, A
        return 0;
    }
    case 0x4602: {                              // FBIOGET_FSCREENINFO
        u8 *f = (u8 *)arg;
        memset(f, 0, 80);
        memcpy(f, "aemu", 5);
        *(u32 *)(f + 24) = g_fbw * g_fbh * 4;       // smem_len
        *(u32 *)(f + 36) = 2;                       // FB_VISUAL_TRUECOLOR
        *(u16 *)(f + 42) = 1;                       // ypanstep
        *(u32 *)(f + 48) = g_fbw * 4;               // line_length
        return 0;
    }
    case 0x4601: case 0x4606: case 0x4611: return 0;    // PUT_VSCREENINFO, PAN_DISPLAY, BLANK
    }
    return -25; /* ENOTTY */
}

// ---------------------------------------------------------------------------------------------------- input

// /dev/input/event0: a stream of struct input_event from the app (AEMU64_INPUT socket); the evdev questions of the
// guest's EventHub are answered here: a direct multi-touch screen with the navigation and volume keys.
static char g_input[108];
static int g_infds[4];                          // descriptors + 1

static int is_input_fd(int fd) { for (int i = 0; i < 4; i++) if (g_infds[i] == fd + 1) return 1; return 0; }

static long input_open(long flags) {
    if (!g_input[0]) return -ENOENT;
    long s = S3(NR_socket, 1, 1 | (flags & 02004000), 0);
    if (s < 0) return s;
    struct sockaddr_un sa; sa.family = 1;
    u64 l = slen(g_input); memcpy(sa.path, g_input, l + 1);
    long r = S3(NR_connect, s, &sa, 2 + l + 1);
    if (r < 0) { S1(NR_close, s); return r; }
    for (int i = 0; i < 4; i++) if (!g_infds[i] || g_infds[i] == (int)s + 1) { g_infds[i] = (int)s + 1; break; }
    return s;
}

static void setbit(u8 *m, u64 len, u32 bit) { if (bit / 8 < len) m[bit / 8] |= (u8)(1 << (bit % 8)); }

static long input_ioctl(u32 cmd, u64 arg) {
    u32 nr = cmd & 0xff, len = (cmd >> 16) & 0x3fff;
    u8 *out = (u8 *)arg;
    if (!g_fbw) fb_geom();
    if (nr == 0x01) { *(int *)arg = 0x010001; return 0; }                        // EVIOCGVERSION
    if (nr == 0x02) { u16 *id = (u16 *)arg; id[0] = 0x18; id[1] = 1; id[2] = 1; id[3] = 1; return 0; }   // EVIOCGID
    if (nr == 0x06) { static const char name[] = "dhd-touch"; u64 n = sizeof name < len ? sizeof name : len; memcpy(out, name, n); return (long)n; }
    if (nr == 0x07 || nr == 0x08) return -ENOENT;                                // phys, uniq
    if (nr == 0x09) { memset(out, 0, len); setbit(out, len, 1); return len; }    // INPUT_PROP_DIRECT
    if (nr == 0x18 || nr == 0x19 || nr == 0x1b) { memset(out, 0, len); return len; }   // key, led, switch state
    if (nr >= 0x20 && nr < 0x40) {                                               // EVIOCGBIT(type)
        memset(out, 0, len);
        switch (nr - 0x20) {
        case 0: setbit(out, len, 0); setbit(out, len, 1); setbit(out, len, 3); break;
        case 1: for (u32 k = 1; k < 0xf0; k++) setbit(out, len, k); setbit(out, len, 580); break;
        case 3: setbit(out, len, 48); setbit(out, len, 50); setbit(out, len, 53); setbit(out, len, 54); setbit(out, len, 57); break;
        }
        return len;
    }
    if (nr >= 0x40 && nr < 0x80) {                                               // EVIOCGABS(axis)
        int *a = (int *)arg; u32 ax = nr - 0x40;
        memset(a, 0, 24);
        if (ax == 53) a[2] = (int)g_fbw - 1; else if (ax == 54) a[2] = (int)g_fbh - 1;
        else if (ax == 48 || ax == 50) a[2] = 255; else if (ax == 57) a[2] = 31; else return -EINVAL;
        return 0;
    }
    if (nr == 0x90 || nr == 0xa0 || nr == 0x03) return 0;                        // grab, clock id, repeat
    return -EINVAL;
}

// ---------------------------------------------------------------------------------------------------- tun

// /dev/tun, synthesised for VpnService. This is M1 of the VPN completion: the CONTROL PLANE only.
// establish() works end to end (the framework's Vpn JNI gets a real interface, the settings page
// lists the app, the key icon lights), but no guest traffic enters the tunnel yet — the guest's
// routes are still answered by the netlink emulation, which sends nothing here. The guest agent's
// test page says exactly that; the data plane is M2.
//
// The channel: opening /dev/tun makes a datagram socket pair. The guest's end is the descriptor
// the framework holds (and dups into the VPN app); reads and writes on it are plain host calls
// with the host's blocking and readiness semantics, exactly like a real tun. The other end is the
// channel endpoint, held here, in the supervisor of the process that opened it (system_server):
//
//     tun_chan_read(s, buf, cap)    one packet the VPN app wrote, or a negative error (-11: none)
//     tun_chan_write(s, buf, len)   hand one packet to the VPN app's reads
//
// M1 drains the channel and counts what arrives; the count is logged when the session ends, so a
// field run can prove whether anything was ever written into the tunnel. M2's packet stack
// attaches at exactly these two calls: it consumes tun_chan_read and feeds tun_chan_write.
#define MAX_TUN 4
static struct {
    int live;
    int fd;                 // the guest's descriptor, in this process
    int peer;               // the channel endpoint (-1 once the hub holds it)
    int hubbed;             // M2: the channel endpoint was handed to the VpnHub (see the hub section)
    int granted;            // interface flags granted at TUNSETIFF (IFF_TUN | IFF_NO_PI)
    int up;                 // SIOCSIFFLAGS last said IFF_UP
    int mtu;
    int index;
    u32 addr, mask;         // what SIOCSIFADDR / SIOCSIFNETMASK recorded, network order
    u64 pkts, bytes;        // drained from the channel (written by the VPN app)
    char name[16];
} g_tun[MAX_TUN];
static int g_tun_live;

static void tun_cat(char *t, u64 *o, const char *s) { u64 n = slen(s); memcpy(t + *o, s, n); *o += n; t[*o] = 0; }
static void tun_catn(char *t, u64 *o, u64 v) { char num[24]; char *p = utoa(v, num + sizeof num); tun_cat(t, o, p); }

static long tun_chan_read(int s, char *buf, u64 cap) { return S3(NR_read, g_tun[s].peer, buf, cap); }
static long tun_chan_write(int s, const char *buf, u64 len) { return S3(NR_write, g_tun[s].peer, buf, len); }

// Retire sessions whose guest side is entirely gone: once every descriptor the guest held (the
// framework's and the app's dup alike) is closed, reads on the channel return 0.
static void tun_drain(void) {
    char buf[2048];
    for (int s = 0; s < MAX_TUN; s++) {
        if (!g_tun[s].live || g_tun[s].hubbed) continue;   // a hubbed channel has exactly one reader: the hub
        for (;;) {
            long n = tun_chan_read(s, buf, sizeof buf);
            if (n > 0) { g_tun[s].pkts++; g_tun[s].bytes += (u64)n; continue; }
            if (n == 0) {
                char line[224]; u64 o = 0;
                tun_cat(line, &o, "aemu-tun: session ");
                tun_cat(line, &o, g_tun[s].name);
                tun_cat(line, &o, " ended; the VPN app wrote ");
                tun_catn(line, &o, g_tun[s].pkts);
                tun_cat(line, &o, " packets, ");
                tun_catn(line, &o, g_tun[s].bytes);
                tun_cat(line, &o, " bytes (M1: drained and discarded at the channel endpoint)");
                say(line);
                S1(NR_close, g_tun[s].peer);
                g_tun[s].live = 0;
                g_tun_live--;
            }
            break;
        }
    }
}

// The hub section below takes the channel over at open and hears about the session's shape.
static void tun_hub_handoff(int s);
static void hub_session_cfg(int s);
static void hub_session_up(int s);
static int hub_ensure(void);
static void hub_pump(void);

// The hub's cached session state lives here, ahead of the tun ioctls: a process that does not
// own the session's tun (netd above all) answers interface questions from this cache.
static struct { int up; char name[16]; u32 addr; u32 mtu; int exempt; u32 index; } g_hub_sess;

// ---- netd's route requests: the VPN add/remove transactions
//
// netd programs rules and routes through RouteController::sendNetlinkRequest (6.0.1_r81): a
// fresh NETLINK_ROUTE socket per transaction, connect, writev, and a recv that waits for one
// 36-byte NLMSG_ERROR ack. Protocol 0 is a real phone socket in this supervisor (see the
// NR_socket case), and the phone's SELinux refuses an app's nlmsg_write, so netd's writev
// itself dies with EACCES: the interface is never tabled ("failed to add interface tun0 to
// VPN netId 101"), the framework's addRoute then finds it assigned to no netId, and the
// remove side — the same rules with the delete action — can never succeed either. In netd,
// and only there (its loader installs one extra filter, see install_filter_netd), the writes
// and receives of its route sockets are trapped. A request that belongs to the hub session's
// VPN flow is answered here with the ack a kernel would have sent; anything else is passed
// to the real socket untouched and keeps the refusal it gets today. Belonging is parsed,
// not assumed: the message must be a rule or a route (RTM_NEWRULE/DELRULE, RTM_NEWROUTE/
// DELROUTE) and must name the live session — by interface name (FRA_IIFNAME/FRA_OIFNAME),
// by its route table (FRA_TABLE/RTA_TABLE; the header's own table byte is always 0 in
// netd's requests, the id travels in the attribute, and the id is the interface index +
// 1000, RouteController.h), or by output interface index (RTA_OIF).
#define MAX_NLREQ 8
static struct { int fd; int pending; u8 req[1024]; } g_nlreq[MAX_NLREQ];

static int nlreq_find(int fd) {
    for (int i = 0; i < MAX_NLREQ; i++) if (g_nlreq[i].fd == fd) return i;
    return -1;
}

static int g_nlreq_full;

static void nlreq_track(int fd) {
    if (nlreq_find(fd) >= 0) return;
    for (int i = 0; i < MAX_NLREQ; i++) if (!g_nlreq[i].fd) {
        g_nlreq[i].fd = fd; g_nlreq[i].pending = 0;
        char line[96]; u64 o = 0;
        tun_cat(line, &o, "aemu-nlreq: tracking route socket fd ");
        tun_catn(line, &o, (u64)fd);
        tun_cat(line, &o, "\n");
        say(line);
        return;
    }
    // the table is full: say so (capped) instead of dropping the fd in silence — an
    // untracked route socket is otherwise indistinguishable from a swallowed log line
    if (g_nlreq_full < 8) {
        g_nlreq_full++;
        char line[96]; u64 o = 0;
        tun_cat(line, &o, "aemu-nlreq: table full, fd ");
        tun_catn(line, &o, (u64)fd);
        tun_cat(line, &o, " NOT tracked\n");
        say(line);
    }
}

static void nlreq_drop(int fd) {
    int i = nlreq_find(fd);
    if (i >= 0) { g_nlreq[i].fd = 0; g_nlreq[i].pending = 0; }
}

static int nlreq_name_eq(const u8 *p, u64 n) {          // an interface-name attribute payload vs the session's name
    char name[16];
    if (n > 15) n = 15;
    memcpy(name, p, n); name[n] = 0;
    return seq(name, g_hub_sess.name);
}

// Verdicts: >0 matched (1 by interface name, 2 by route table, 3 by output interface),
// 0 the request parsed but names the session in no attribute, -1 not a rule/route
// request at all, -2 no live session to match against.
static int nlreq_session_match(const u8 *p, u64 len) {
    if (len < 28) return -1;
    u32 mlen = *(const u32 *)p;
    u16 type = *(const u16 *)(p + 4);
    if (mlen < 28 || mlen > len) return -1;
    int rule = type == 32 || type == 33;                // RTM_NEWRULE / RTM_DELRULE
    int route = type == 24 || type == 25;               // RTM_NEWROUTE / RTM_DELROUTE
    if (!rule && !route) return -1;
    if (hub_ensure() < 0) return -2;
    hub_pump();                                         // the session state, fresh (tun_sioc's pattern)
    if (!g_hub_sess.up || !g_hub_sess.name[0] || !g_hub_sess.index) return -2;
    u32 table = g_hub_sess.index + 1000;
    const u8 *a = p + 28, *end = p + mlen;              // header (16) + rule/route body (12), then attributes
    while (a + 4 <= end) {
        u16 alen = *(const u16 *)a, atype = *(const u16 *)(a + 2);
        if (alen < 4 || a + alen > end) break;
        const u8 *v = a + 4; u64 vn = alen - 4;
        // attribute types are the kernel's own (uapi/linux/fib_rules.h, uapi/linux/rtnetlink.h):
        // FRA_IIFNAME 3, FRA_TABLE 15, FRA_OIFNAME 17; RTA_TABLE 15, RTA_OIF 4. (An earlier
        // version of this matcher read FRA_TABLE as 14 and FRA_OIFNAME as 11 — those are
        // FRA_SUPPRESS_PREFIXLEN and FRA_FLOW, so a rule naming the session only by its table
        // could never match; see vpn-diag-desk.md.)
        if (rule && (atype == 3 || atype == 17) && nlreq_name_eq(v, vn)) return 1;          // FRA_IIFNAME / FRA_OIFNAME
        if (rule && atype == 15 && vn >= 4 && *(const u32 *)v == table) return 2;           // FRA_TABLE
        if (route && atype == 15 && vn >= 4 && *(const u32 *)v == table) return 2;          // RTA_TABLE
        if (route && atype == 4 && vn >= 4 && *(const u32 *)v == g_hub_sess.index) return 3; // RTA_OIF
        a += (alen + 3) & ~3U;
    }
    return 0;
}

static const char *nlreq_type_name(u16 type) {
    switch (type) {
    case 24: return "NEWROUTE";
    case 25: return "DELROUTE";
    case 32: return "NEWRULE";
    case 33: return "DELRULE";
    }
    return "other";
}

static int g_nlreq_logged;
// One host-log line per transaction on a tracked route socket: what it was and what was
// decided, so a field run shows whether the trap fires at all and, if it does, exactly
// which request is refused and why. Capped; the boot-time flush alone spends dozens.
static void nlreq_log(int fd, const u8 *buf, u64 len, int verdict) {
    if (g_nlreq_logged >= 128) return;
    g_nlreq_logged++;
    u16 type = buf && len >= 6 ? *(const u16 *)(buf + 4) : 0;
    char line[224]; u64 o = 0;
    tun_cat(line, &o, "aemu-nlreq: fd ");
    tun_catn(line, &o, (u64)fd);
    tun_cat(line, &o, " type ");
    tun_cat(line, &o, nlreq_type_name(type));
    if (verdict > 0) {
        tun_cat(line, &o, " -> answered (matched by ");
        tun_cat(line, &o, verdict == 1 ? "name" : verdict == 2 ? "table" : "oif");
        tun_cat(line, &o, ")");
    } else {
        tun_cat(line, &o, " -> passed (");
        tun_cat(line, &o, verdict == -1 ? "not a rule/route request" : verdict == -2 ? "session down" : "no session attribute");
        tun_cat(line, &o, ")");
    }
    tun_cat(line, &o, "\n");
    say(line);
}

static int g_nlreq_untrk;
// A writev in netd on a socket this instance never tracked, shaped like a route
// transaction: the one shape that would prove the tracking itself missed a socket.
static void nlreq_log_untracked(int fd, const void *iovp, u64 iovcnt) {
    if (g_nlreq_untrk >= 8 || !iovp || !iovcnt) return;
    struct kiov { const void *base; u64 len; };
    const struct kiov *iov = (const struct kiov *)iovp;
    if (!iov[0].base || iov[0].len < 6) return;
    const u8 *p = (const u8 *)iov[0].base;
    u32 mlen = *(const u32 *)p;
    u16 type = *(const u16 *)(p + 4);
    if (mlen < 28 || (type != 24 && type != 25 && type != 32 && type != 33)) return;
    g_nlreq_untrk++;
    char line[192]; u64 o = 0;
    tun_cat(line, &o, "aemu-nlreq: fd ");
    tun_catn(line, &o, (u64)fd);
    tun_cat(line, &o, " writev on untracked socket, type ");
    tun_cat(line, &o, nlreq_type_name(type));
    tun_cat(line, &o, " (passed)\n");
    say(line);
}

static int g_nlreq_acked;
static void nlreq_log_ack(int fd) {
    if (g_nlreq_acked >= 128) return;
    g_nlreq_acked++;
    char line[96]; u64 o = 0;
    tun_cat(line, &o, "aemu-nlreq: fd ");
    tun_catn(line, &o, (u64)fd);
    tun_cat(line, &o, " ack delivered\n");
    say(line);
}

// The request was ours to answer: keep it, and the receive that follows gets the ack.
static int nlreq_swallow(int fd, const u8 *buf, u64 len) {
    int i = nlreq_find(fd);
    if (i < 0 || !buf || !len || len > sizeof g_nlreq[i].req) return 0;
    int verdict = nlreq_session_match(buf, len);
    nlreq_log(fd, buf, len, verdict);
    if (verdict <= 0) return 0;
    memcpy(g_nlreq[i].req, buf, len);
    g_nlreq[i].pending = 1;
    return 1;
}

// Hand the waiting ack to a receive on a swallowed request: an NLMSG_ERROR with error 0 and
// the request's own header echoed, the exact 36 bytes sendNetlinkRequest reads.
static int nlreq_ack(int fd, u8 *buf, u64 cap, u16 *sa, u32 *salen, long *out) {
    int i = nlreq_find(fd);
    if (i < 0 || !g_nlreq[i].pending) return 0;
    g_nlreq[i].pending = 0;
    struct { struct aemu_nlmsghdr hdr; struct aemu_nlmsgerr err; } m;
    memset(&m, 0, sizeof m);
    const struct aemu_nlmsghdr *req = (const struct aemu_nlmsghdr *)g_nlreq[i].req;
    m.hdr.len = sizeof m; m.hdr.type = 2 /*NLMSG_ERROR*/; m.hdr.flags = 0;
    m.hdr.seq = req->seq; m.hdr.pid = req->pid;
    m.err.error = 0;
    m.err.msg = *req;
    u64 n = cap < sizeof m ? cap : sizeof m;
    memcpy(buf, &m, n);
    if (sa && salen && *salen >= 12) {                  // the kernel's address, if one was asked for
        u32 *w = (u32 *)sa;
        sa[0] = 16; sa[1] = 0;
        w[1] = 0; w[2] = 0;
        *salen = 12;
    }
    *out = (long)n;
    return 1;
}

static long tun_open(long flags) {
    if (g_tun_live) tun_drain();                    // reap before allocating
    int s = -1;
    for (int i = 0; i < MAX_TUN; i++) if (!g_tun[i].live) { s = i; break; }
    if (s < 0) return -24;                          /* EMFILE */
    int sv[2];
    long r = S4(NR_socketpair, 1 /*AF_UNIX*/, 2 /*SOCK_DGRAM*/ | (int)(flags & 02004000), 0, (long)sv);
    if (r < 0) return r;
    S3(NR_fcntl, sv[1], 4 /*F_SETFL*/, 2048 /*O_NONBLOCK*/);   // the endpoint never blocks the supervisor
    memset(&g_tun[s], 0, sizeof g_tun[s]);
    g_tun[s].live = 1;
    g_tun[s].fd = sv[0];
    g_tun[s].peer = sv[1];
    g_tun[s].mtu = 1500;
    g_tun[s].index = 61 + s;
    g_tun_live++;
    tun_hub_handoff(s);
    say(g_tun[s].hubbed ? "aemu-tun: /dev/tun opened (M2: the hub holds the channel; detoured traffic will enter the tunnel)"
                        : "aemu-tun: /dev/tun opened (M1 control plane: establish works, traffic does not enter the tunnel yet)");
    return sv[0];
}

static int is_tun_fd(int fd) { for (int i = 0; i < MAX_TUN; i++) if (g_tun[i].live && g_tun[i].fd == fd) return 1; return 0; }

static long tun_ioctl(int fd, u32 cmd, u64 arg) {
    int s = -1;
    for (int i = 0; i < MAX_TUN; i++) if (g_tun[i].live && g_tun[i].fd == fd) s = i;
    if (s < 0) return -25;                          /* ENOTTY */
    char *ifr = (char *)arg;
    switch (cmd) {
    case 0x400454ca: {                              // TUNSETIFF
        char want[16];
        memcpy(want, ifr, 16);
        want[15] = 0;
        if (!want[0]) {                             // no name asked for: the lowest free tunN, as the kernel assigns
            for (int n = 0; n < MAX_TUN + 4 && !want[0]; n++) {
                char cand[16];
                u64 o = 0;
                tun_cat(cand, &o, "tun");
                tun_catn(cand, &o, (u64)n);
                int taken = 0;
                for (int i = 0; i < MAX_TUN; i++) if (g_tun[i].live && seq(g_tun[i].name, cand)) taken = 1;
                if (!taken) memcpy(want, cand, o + 1);
            }
        }
        memcpy(g_tun[s].name, want, 16);
        g_tun[s].granted = *(u16 *)(ifr + 16);
        memcpy(ifr, g_tun[s].name, 16);
        *(u16 *)(ifr + 16) = (u16)g_tun[s].granted;
        char line[96]; u64 o = 0;
        tun_cat(line, &o, "aemu-tun: interface ");
        tun_cat(line, &o, g_tun[s].name);
        tun_cat(line, &o, " allocated (TUNSETIFF)");
        say(line);
        hub_session_cfg(s);
        return 0;
    }
    case 0x800454d2:                                // TUNGETIFF
        memcpy(ifr, g_tun[s].name, 16);
        *(u16 *)(ifr + 16) = (u16)g_tun[s].granted;
        return 0;
    case 0x800454d3: *(int *)arg = 212992; return 0;    // TUNGETSNDBUF
    default: return 0;                              // the rest of the 'T' family: accepted, nothing to do at M1
    }
}

// The framework configures and checks the interface by NAME, through ioctls on a plain internet
// socket (the Vpn JNI's SIOCSIFFLAGS / SIOCSIFMTU / SIOGIFINDEX / SIOCSIFADDR / SIOCGIFFLAGS).
// Those used to fall through to the phone's kernel, which has no such interface and refused them
// all — a second, hidden break behind the missing /dev/tun. They are answered here, from this
// registry: an interface exists because a session is registered, and its flags, MTU and addresses
// are what the framework itself set. Returns 1 (and *out) when the name is one of ours.
static int tun_sioc(u32 cmd, u64 arg, long *out) {
    if ((cmd & 0xffffff00u) != 0x8900u || !arg) return 0;
    char *ifr = (char *)arg;
    char name[16];
    memcpy(name, ifr, 16);
    name[15] = 0;
    for (int i = 0; i < 15 && name[i]; i++) if (name[i] == ':') { name[i] = 0; break; }   // tun0:1 aliases
    int s = -1;
    for (int i = 0; i < MAX_TUN; i++) if (g_tun[i].live && g_tun[i].name[0] && seq(g_tun[i].name, name)) s = i;
    if (s < 0) {
        // Not a tun this process owns — but the session's tun belongs to the framework's
        // process, and every other process that resolves the interface by name lands here
        // with an empty local registry. netd is the one that matters: bionic's
        // if_nametoindex is exactly this ioctl (SIOCGIFINDEX), and a miss is why netd
        // logged "cannot find interface tun0", refused to table the interface, and then
        // had nothing to remove when the session ended. Answer the read-only questions
        // from the hub's cached session state; the write ioctls stay the owner's alone.
        if (cmd != 0x8933 && cmd != 0x8913 && cmd != 0x8921) return 0;
        if (hub_ensure() < 0) return 0;
        hub_pump();
        if (!g_hub_sess.up || !g_hub_sess.name[0] || !seq(g_hub_sess.name, name)) return 0;
        switch (cmd) {
        case 0x8933: if (!g_hub_sess.index) return 0; *(int *)(ifr + 16) = (int)g_hub_sess.index; *out = 0; return 1;  // SIOGIFINDEX
        case 0x8913: *(u16 *)(ifr + 16) = 0x80d1; *out = 0; return 1;   // SIOCGIFFLAGS: the owner's up answer
        case 0x8921: *(int *)(ifr + 16) = (int)g_hub_sess.mtu; *out = 0; return 1;  // SIOCGIFMTU
        }
        return 0;
    }
    switch (cmd) {
    case 0x8914: g_tun[s].up = *(u16 *)(ifr + 16) & 1; *out = 0; return 1;                  // SIOCSIFFLAGS
    case 0x8913: *(u16 *)(ifr + 16) = (u16)((g_tun[s].up ? 0x41 : 0) | 0x10 | 0x80 | 0x8000); *out = 0; return 1;  // SIOCGIFFLAGS: UP|RUNNING, POINTOPOINT|NOARP|MULTICAST
    case 0x8922: g_tun[s].mtu = *(int *)(ifr + 16); *out = 0; return 1;                     // SIOCSIFMTU
    case 0x8921: *(int *)(ifr + 16) = g_tun[s].mtu; *out = 0; return 1;                     // SIOCGIFMTU
    case 0x8933: *(int *)(ifr + 16) = g_tun[s].index; *out = 0; return 1;                   // SIOGIFINDEX
    case 0x8916: g_tun[s].addr = *(u32 *)(ifr + 20); hub_session_up(s); *out = 0; return 1;   // SIOCSIFADDR: the address is what makes the session real
    case 0x891c: g_tun[s].mask = *(u32 *)(ifr + 20); *out = 0; return 1;                    // SIOCSIFNETMASK
    case 0x8915:                                                                            // SIOCGIFADDR
        if (!g_tun[s].addr) { *out = -99; return 1; }                                       /* EADDRNOTAVAIL */
        memset(ifr + 16, 0, 16); *(u16 *)(ifr + 16) = 2; *(u32 *)(ifr + 20) = g_tun[s].addr; *out = 0; return 1;
    case 0x891b:                                                                            // SIOCGIFNETMASK
        if (!g_tun[s].mask) { *out = -99; return 1; }                                       /* EADDRNOTAVAIL */
        memset(ifr + 16, 0, 16); *(u16 *)(ifr + 16) = 2; *(u32 *)(ifr + 20) = g_tun[s].mask; *out = 0; return 1;
    }
    return 0;
}


// ---------------------------------------------------------------------------------------------------- vpn hub (M2)

// The data plane of the VPN completion. A resident piece of the host app, the VpnHub, holds what a
// per-process supervisor cannot: the tun channel endpoint of the session (handed over at open, see
// tun_open), the host ends of the guest connections that are detoured into the tunnel, and the
// netlink listeners to whom the interface's birth and death are announced (a synthesised interface
// has no kernel to announce it; without the announcement the framework never learns that a session
// ended — the M1 test page's Stop button proved that). Every supervisor process keeps one lazily
// opened connection to the hub and a cached copy of the session state; the connect trap is the
// only place the cache is consulted, so with no session up — or no hub — a guest program's calls
// take exactly the path they took before M2 (fail-open, down to the M1 local drain at worst).
//
// Wire protocol (filesystem unix stream at <root>/dev/socket/aemu_vpnhub): a frame is a u32 LE
// length (counting the type byte and the payload, not itself), a u8 type, then the payload. The
// frames that carry a descriptor (TUN_PEER, FLOW_FD, LINKREG) attach it with SCM_RIGHTS to the
// same sendmsg. IP addresses in payloads are u32 with the first octet in the most significant
// byte; ports are host-order u16.
enum {
    HUB_HELLO = 1,          // -> (empty)
    HUB_SESSION_STATE = 2,  // <- u8 up, char name[16], u32 addr, u32 mtu, i32 exempt uid, u32 index
    HUB_TUN_PEER = 3,       // -> (empty) + fd: the channel endpoint of a fresh /dev/tun
    HUB_PEER_OK = 4,        // <-
    HUB_SESSION_CFG = 5,    // -> char name[16], u32 index, u32 mtu
    HUB_SESSION_UP = 6,     // -> char name[16], u32 index, u32 addr, u32 mtu
    HUB_SESSION_DOWN = 7,   // <- char name[16]  (broadcast: the channel ran dry, the session is over)
    HUB_FLOW_NEW = 8,       // -> u32 uid, u32 dst ip, u16 dst port, u16 src port (0 = unbound)
    HUB_FLOW_OK = 9,        // <- u32 flow id
    HUB_FLOW_NO = 10,       // <-
    HUB_FLOW_FD = 11,       // -> u32 flow id + fd: the host end of the detoured connection
    HUB_LINKREG = 12,       // -> u32 netlink protocol + fd: a netlink listener's peer, to be told about the interface
    HUB_LINKREG_OK = 13,    // <- u32 listener id
    HUB_LINKUNREG = 14,     // -> u32 listener id
};

static int g_hub_fd = -1;
static int g_hub_pid;                       // the connection belongs to one process; a fork reconnects
static volatile int g_hub_lock;
static int g_hub_logged;                    // per process: the detour was announced, a refusal was announced

static void hub_lock(void) { while (__atomic_exchange_n(&g_hub_lock, 1, __ATOMIC_ACQUIRE)) S0(NR_sched_yield); }
static int hub_trylock(void) { return __atomic_exchange_n(&g_hub_lock, 1, __ATOMIC_ACQUIRE) == 0; }
static void hub_unlock(void) { __atomic_store_n(&g_hub_lock, 0, __ATOMIC_RELEASE); }

static void hub_log1(const char *a, const char *b) { say("aemu-vpn: "); say(a); say(b); say("\n"); }


// ---- hub wire io (the lock is held by the caller, except in hub_pump, which takes it itself)

#define NR_ppoll 73
struct hub_pollfd { int fd; short ev, rev; };
struct hub_ts { i64 sec, nsec; };

static int hub_readable(int fd, int ms) {
    struct hub_pollfd p = { fd, 1 /*POLLIN*/, 0 };
    struct hub_ts ts = { ms / 1000, (i64)(ms % 1000) * 1000000 };
    long r = S5(NR_ppoll, &p, 1, &ts, 0, 0);
    return r > 0 && (p.rev & (1 | 8 | 16));         // POLLIN | POLLERR | POLLHUP
}

static int hub_readn(int fd, u8 *buf, u64 n, int ms) {
    u64 got = 0;
    while (got < n) {
        if (!hub_readable(fd, ms)) return -1;
        long r = S3(NR_read, fd, buf + got, n - got);
        if (r == -4) continue;                      // EINTR
        if (r <= 0) return -1;
        got += (u64)r;
    }
    return 0;
}

static int hub_writen(int fd, const u8 *buf, u64 n) {
    u64 done = 0;
    while (done < n) {
        long r = S3(NR_write, fd, buf + done, n - done);
        if (r == -4) continue;
        if (r <= 0) return -1;
        done += (u64)r;
    }
    return 0;
}

static int hub_send(u8 type, const void *pl, u64 plen, int fd) {
    if (g_hub_fd < 0) return -1;
    u8 hdr[5];
    u32 len = (u32)(1 + plen);
    hdr[0] = (u8)len; hdr[1] = (u8)(len >> 8); hdr[2] = (u8)(len >> 16); hdr[3] = (u8)(len >> 24);
    hdr[4] = type;
    struct iov v[2];
    v[0].base = hdr; v[0].len = 5;
    v[1].base = (void *)pl; v[1].len = plen;
    struct { struct cmsg h; int f; } cm;
    struct msgh mh;
    memset(&mh, 0, sizeof mh);
    mh.iov = v; mh.iovlen = plen ? 2 : 1;
    if (fd >= 0) {
        cm.h.len = sizeof cm.h + 4; cm.h.level = 1 /*SOL_SOCKET*/; cm.h.type = 1 /*SCM_RIGHTS*/; cm.f = fd;
        mh.control = &cm; mh.controllen = (sizeof cm.h + 4 + 7) & ~7UL;
    }
    long r;
    do r = S3(NR_sendmsg, g_hub_fd, &mh, 0x4000 /*MSG_NOSIGNAL*/); while (r == -4);
    return r < 0 ? -1 : 0;
}

// One frame: 0 and the type/payload, or -1 (timeout, EOF, garbage). Payloads here are tiny.
static int hub_recv(u8 *type, u8 *pl, u64 cap, u64 *plen, int ms) {
    u8 hdr[5];
    if (hub_readn(g_hub_fd, hdr, 5, ms) < 0) return -1;
    u32 len = (u32)hdr[0] | ((u32)hdr[1] << 8) | ((u32)hdr[2] << 16) | ((u32)hdr[3] << 24);
    if (len < 1 || len - 1 > cap) return -1;
    *type = hdr[4];
    *plen = len - 1;
    if (len > 1 && hub_readn(g_hub_fd, pl, len - 1, ms) < 0) return -1;
    return 0;
}

static void hub_put32(u8 *p, u32 v) { p[0] = (u8)v; p[1] = (u8)(v >> 8); p[2] = (u8)(v >> 16); p[3] = (u8)(v >> 24); }
static u32 hub_get32(const u8 *p) { return (u32)p[0] | ((u32)p[1] << 8) | ((u32)p[2] << 16) | ((u32)p[3] << 24); }
static void hub_put16(u8 *p, u32 v) { p[0] = (u8)v; p[1] = (u8)(v >> 8); }


// ---- hub state keeping

// A session the hub reports over is over here too: drop the registry entry, so the by-name ioctls
// stop answering for it. That is what lets the framework's own teardown check (its SIOCGIFFLAGS
// probe of the interface) read "gone" and finish the disconnect the guest asked for.
static void hub_session_gone(const char *name) {
    for (int s = 0; s < MAX_TUN; s++) {
        if (g_tun[s].live && g_tun[s].name[0] && seq(g_tun[s].name, name)) {
            if (g_tun[s].peer >= 0) S1(NR_close, g_tun[s].peer);
            g_tun[s].live = 0;
            g_tun[s].hubbed = 0;
            g_tun_live--;
            char line[96]; u64 o = 0;
            tun_cat(line, &o, "aemu-vpn: session ");
            tun_cat(line, &o, name);
            tun_cat(line, &o, " registry cleared (the hub reported the channel closed)");
            say(line);
        }
    }
    if (g_hub_sess.up && seq(g_hub_sess.name, name)) g_hub_sess.up = 0;
}

static void hub_handle(u8 type, const u8 *pl, u64 len) {
    if (type == HUB_SESSION_STATE && len >= 29) {
        g_hub_sess.up = pl[0] != 0;
        memcpy(g_hub_sess.name, pl + 1, 16); g_hub_sess.name[15] = 0;
        g_hub_sess.addr = hub_get32(pl + 17);
        g_hub_sess.mtu = hub_get32(pl + 21);
        g_hub_sess.exempt = (int)hub_get32(pl + 25);
        g_hub_sess.index = len >= 33 ? hub_get32(pl + 29) : 0;
    } else if (type == HUB_SESSION_DOWN && len >= 16) {
        char name[16];
        memcpy(name, pl, 16); name[15] = 0;
        hub_session_gone(name);
    }
}

// The hub went away (its socket closed under us): nothing it held can still be true, least of all
// a session the framework would go on believing in. Clear the hubbed registry entries with it.
static void hub_dead(void) {
    if (g_hub_fd >= 0) S1(NR_close, g_hub_fd);
    g_hub_fd = -1;
    g_hub_sess.up = 0;
    for (int s = 0; s < MAX_TUN; s++) {
        if (g_tun[s].live && g_tun[s].hubbed) {
            g_tun[s].live = 0;
            g_tun[s].hubbed = 0;
            g_tun_live--;
            hub_log1("hub connection lost; session ", g_tun[s].name);
        }
    }
}

// Drain whatever the hub sent, without ever blocking: called at every trap entry, it is how a
// SESSION_DOWN reaches the supervisor that owns the registry (system_server) between two calls.
static void hub_pump(void) {
    if (g_hub_fd < 0 || g_hub_pid != (int)S0(NR_getpid)) return;
    if (!hub_trylock()) return;
    for (int i = 0; i < 16; i++) {
        if (!hub_readable(g_hub_fd, 0)) break;
        u8 type, pl[64]; u64 len = 0;
        if (hub_recv(&type, pl, sizeof pl, &len, 100) < 0) { hub_dead(); break; }
        hub_handle(type, pl, len);
    }
    hub_unlock();
}

// Wait for one frame type, handling the others on the way; the wanted frame's payload is copied
// to the caller's buffer (and, for a session state, handled as well). The lock is held by the caller.
static int hub_wait(u8 want, int ms, u8 *out, u64 cap, u64 *outlen) {
    for (int i = 0; i < 32; i++) {
        u8 type, pl[64]; u64 len = 0;
        if (hub_recv(&type, pl, sizeof pl, &len, ms) < 0) return -1;
        if (type == want) {
            if (want == HUB_SESSION_STATE) hub_handle(type, pl, len);
            if (out && len <= cap) { memcpy(out, pl, len); *outlen = len; }
            return 0;
        }
        hub_handle(type, pl, len);
    }
    return -1;
}

// Connect (once per process) and learn the current session state. -1: no hub — the caller falls
// back to the pre-M2 path, unchanged.
static int hub_ensure(void) {
    int me = (int)S0(NR_getpid);
    if (g_hub_fd >= 0 && g_hub_pid == me) return 0;
    hub_lock();
    if (g_hub_fd >= 0 && g_hub_pid == me) { hub_unlock(); return 0; }
    if (g_hub_fd >= 0) { S1(NR_close, g_hub_fd); g_hub_fd = -1; }   // inherited from the parent: not ours
    long s = S3(NR_socket, 1 /*AF_UNIX*/, 1 /*SOCK_STREAM*/, 0);
    if (s < 0) { hub_unlock(); return -1; }
    char path[600];
    memcpy(path, g_root, g_rootlen);
    memcpy(path + g_rootlen, "/dev/socket/aemu_vpnhub", 24);
    struct sockaddr_un sa;
    sa.family = 1;
    memcpy(sa.path, path, g_rootlen + 24);
    if (S3(NR_connect, s, &sa, 2 + g_rootlen + 23) < 0) { S1(NR_close, s); hub_unlock(); return -1; }
    g_hub_fd = (int)s;
    g_hub_pid = me;
    if (hub_send(HUB_HELLO, 0, 0, -1) == 0) { u8 pl[64]; u64 ln = 0; hub_wait(HUB_SESSION_STATE, 2000, pl, sizeof pl, &ln); }   // failure just leaves the cache empty
    hub_unlock();
    return 0;
}


// ---- what the tun section reports to the hub

// At /dev/tun open: hand the channel endpoint over. If anything about it fails, the session
// simply stays local — the M1 drain is the floor, never an error for the guest.
static void tun_hub_handoff(int s) {
    if (hub_ensure() < 0) { hub_log1("no hub at /dev/tun open; the channel stays with the supervisor (M1 behaviour)", ""); return; }
    hub_lock();
    int ok = hub_send(HUB_TUN_PEER, 0, 0, g_tun[s].peer) == 0 && hub_wait(HUB_PEER_OK, 2000, 0, 0, 0) == 0;
    if (ok) {
        S1(NR_close, g_tun[s].peer);
        g_tun[s].peer = -1;
        g_tun[s].hubbed = 1;
        hub_log1("tun channel handed to the hub", "");
    } else {
        hub_log1("the hub did not take the tun channel; keeping the M1 local drain", "");
    }
    hub_unlock();
}

static void hub_session_cfg(int s) {
    if (!g_tun[s].hubbed || hub_ensure() < 0) return;
    u8 pl[24];
    memset(pl, 0, sizeof pl);
    memcpy(pl, g_tun[s].name, 16);
    hub_put32(pl + 16, (u32)g_tun[s].index);
    hub_put32(pl + 20, (u32)g_tun[s].mtu);
    hub_lock();
    hub_send(HUB_SESSION_CFG, pl, sizeof pl, -1);
    hub_unlock();
}

// The address is what makes a session real (the framework sets it before it connects the agent):
// only now does the hub call the session up, announce the interface and start detouring.
static void hub_session_up(int s) {
    if (!g_tun[s].hubbed || hub_ensure() < 0) return;
    u8 pl[28];
    memset(pl, 0, sizeof pl);
    memcpy(pl, g_tun[s].name, 16);
    hub_put32(pl + 16, (u32)g_tun[s].index);
    u32 a = g_tun[s].addr;   // recorded from the ifreq in memory order: swap to first-octet-first
    hub_put32(pl + 20, ((a & 0xff) << 24) | ((a & 0xff00) << 8) | ((a >> 8) & 0xff00) | ((a >> 24) & 0xff));
    hub_put32(pl + 24, (u32)g_tun[s].mtu);
    hub_lock();
    hub_send(HUB_SESSION_UP, pl, sizeof pl, -1);
    hub_unlock();
    char line[96]; u64 o = 0;
    tun_cat(line, &o, "aemu-vpn: session ");
    tun_cat(line, &o, g_tun[s].name);
    tun_cat(line, &o, " is up; guest connections will be detoured into the tunnel");
    say(line);
}

// ---- the connect detour

// Which of this process's descriptors are plain IPv4 stream sockets, and where they bound to.
// Only those are detour candidates; everything else keeps the path it has always taken.
#define MAX_SOCK 64
static struct { int fd; int type; u16 port; } g_sock[MAX_SOCK];

static void sock_track(int fd, int type) {
    int free_i = -1;
    for (int i = 0; i < MAX_SOCK; i++) {
        if (g_sock[i].fd == fd) { g_sock[i].type = type; g_sock[i].port = 0; return; }
        if (!g_sock[i].fd && free_i < 0) free_i = i;
    }
    if (free_i >= 0) { g_sock[free_i].fd = fd; g_sock[free_i].type = type; g_sock[free_i].port = 0; }
}

static int sock_find(int fd) { for (int i = 0; i < MAX_SOCK; i++) if (g_sock[i].fd == fd) return i; return -1; }

static void sock_forget(int fd) { int i = sock_find(fd); if (i >= 0) g_sock[i].fd = 0; }

static void sock_note_bind(int fd) {
    int i = sock_find(fd);
    if (i < 0) return;
    u8 sa[32]; u32 sl = sizeof sa;
    memset(sa, 0, sizeof sa);
    if (S3(NR_getsockname, fd, sa, &sl) == 0 && sa[0] == 2) g_sock[i].port = (u16)(((u32)sa[2] << 8) | sa[3]);
}


// The detour itself. Returns 1 when the call is settled here (with *out as its result), 0 when
// the caller should take the ordinary path — no session, an exempt uid, a hub that will not
// answer, a refusal: every doubt resolves to the connection the guest would have had before M2.
static int hub_maybe_detour(int fd, const u8 *sa, u64 salen, long *out) {
    int si = sock_find(fd);
    if (si < 0 || (g_sock[si].type & 0xff) != 1 /*SOCK_STREAM*/) return 0;
    if (!sa || salen < 16 || sa[0] != 2 /*AF_INET*/) return 0;
    if (hub_ensure() < 0) return 0;
    hub_pump();
    if (!g_hub_sess.up) return 0;
    if (g_hub_sess.exempt >= 0 && g_uid == g_hub_sess.exempt) return 0;   // VpnService.protect(), on the supervisor's side

    u8 req[12];
    hub_put32(req, (u32)g_uid);
    hub_put32(req + 4, ((u32)sa[4] << 24) | ((u32)sa[5] << 16) | ((u32)sa[6] << 8) | (u32)sa[7]);
    hub_put16(req + 8, ((u32)sa[2] << 8) | (u32)sa[3]);
    hub_put16(req + 10, g_sock[si].port);

    hub_lock();
    u32 flow = 0;
    int ok = 0;
    if (hub_send(HUB_FLOW_NEW, req, sizeof req, -1) == 0) {
        u8 pl[64]; u64 ln = 0;
        if (hub_wait(HUB_FLOW_OK, 2000, pl, sizeof pl, &ln) == 0 && ln >= 4) { flow = hub_get32(pl); ok = 1; }
    }
    if (!ok) {
        hub_unlock();
        if (!g_hub_logged) { g_hub_logged = 1; hub_log1("the hub refused a detour; connections stay direct", ""); }
        return 0;
    }
    int sv[2];
    if (S4(NR_socketpair, 1 /*AF_UNIX*/, 1 /*SOCK_STREAM*/, 0, (long)sv) < 0) { hub_unlock(); return 0; }
    u8 idpl[4];
    hub_put32(idpl, flow);
    if (hub_send(HUB_FLOW_FD, idpl, sizeof idpl, sv[1]) < 0) {
        hub_unlock();
        S1(NR_close, sv[0]);
        S1(NR_close, sv[1]);
        hub_log1("a detoured connection could not be handed to the hub; closing it rather than faking success", "");
        *out = -111;   // ECONNREFUSED: the one failure that is reported as a failure
        return 1;
    }
    hub_unlock();
    // The guest's descriptor now names the socket pair: its reads, writes and polls are plain
    // host calls on it from here on, and the hub's synthesizer reads the other end.
    long r = S3(NR_dup3, sv[0], fd, (g_sock[si].type & 02000000) ? 02000000 /*O_CLOEXEC*/ : 0);
    S1(NR_close, sv[0]);
    S1(NR_close, sv[1]);
    if (r < 0) { *out = r; return 1; }
    if (!g_hub_logged) { g_hub_logged = 1; hub_log1("detouring guest connections into the tunnel (this process)", ""); }
    *out = 0;
    return 1;
}

// ---- netlink listeners (the stop fix's missing link)

// A synthesised interface has no kernel to announce its death. The listeners that bind netlink
// groups (netd above all) are registered with the hub, which writes the RTM_NEWLINK /
// RTM_DELLINK events a real kernel would have produced; the rest of the teardown chain is the
// guest's own stock code.
#define MAX_LINKREG 16
static struct { int fd; u32 id; } g_linkreg[MAX_LINKREG];

// One host-log line per listener registration attempt and its outcome, with the program
// that owns the listener: whether the hub ever hears of a listener decides whether its
// receive path below is even in play, and a field run has to be able to see it.
static void nllink_log_reg(int fd, u32 proto, u32 groups, u32 id, const char *fail) {
    const char *b = g_exe; for (const char *p = g_exe; *p; p++) if (*p == '/') b = p + 1;
    char line[256]; u64 o = 0;
    tun_cat(line, &o, "aemu-nllink: listener ");
    if (fail) {
        tun_cat(line, &o, "registration FAILED (");
        tun_cat(line, &o, fail);
        tun_cat(line, &o, "): fd ");
    } else {
        tun_cat(line, &o, "registered: fd ");
    }
    tun_catn(line, &o, (u64)fd);
    tun_cat(line, &o, " proto ");
    tun_catn(line, &o, proto);
    tun_cat(line, &o, " groups ");
    tun_catn(line, &o, groups);
    if (!fail) {
        tun_cat(line, &o, " hub id ");
        tun_catn(line, &o, id);
    }
    tun_cat(line, &o, " (");
    tun_cat(line, &o, b);
    tun_cat(line, &o, ")\n");
    say(line);
}

static void hub_linkreg(int fd, const u8 *sa, u64 salen) {
    if (!sa || salen < 12 || sa[0] != 16 /*AF_NETLINK*/) return;
    u32 groups = hub_get32(sa + 8);
    if (!groups) return;                       // a request socket, not a listener: no events for it
    nl_set_groups(fd, groups);
    for (int i = 0; i < MAX_LINKREG; i++) if (g_linkreg[i].fd == fd) return;
    int peer = nl_find_peer(fd);
    if (peer < 0 || hub_ensure() < 0) { nllink_log_reg(fd, (u32)nl_proto_of(fd), groups, 0, "no hub connection"); return; }
    int dup = (int)S2(NR_dup, peer, 0);
    if (dup < 0) { nllink_log_reg(fd, (u32)nl_proto_of(fd), groups, 0, "peer dup failed"); return; }
    // the registration carries the socket's protocol: the hub speaks to each listener in the
    // dialect its consumer parses (kernel uevents for the uevent protocol, rtnetlink for the rest)
    u8 lp[4];
    hub_put32(lp, (u32)nl_proto_of(fd));
    hub_lock();
    u32 id = 0;
    if (hub_send(HUB_LINKREG, lp, sizeof lp, dup) == 0) {
        u8 pl[64]; u64 ln = 0;
        if (hub_wait(HUB_LINKREG_OK, 2000, pl, sizeof pl, &ln) == 0 && ln >= 4) id = hub_get32(pl);
    }
    hub_unlock();
    S1(NR_close, dup);
    if (!id) { nllink_log_reg(fd, (u32)nl_proto_of(fd), groups, 0, "hub did not confirm"); return; }
    for (int i = 0; i < MAX_LINKREG; i++) if (!g_linkreg[i].fd) { g_linkreg[i].fd = fd; g_linkreg[i].id = id; nllink_log_reg(fd, (u32)nl_proto_of(fd), groups, id, 0); return; }
    nllink_log_reg(fd, (u32)nl_proto_of(fd), groups, 0, "listener table full");
}

static void hub_linkunreg(int fd) {
    for (int i = 0; i < MAX_LINKREG; i++) {
        if (g_linkreg[i].fd != fd) continue;
        u32 id = g_linkreg[i].id;
        g_linkreg[i].fd = 0;
        if (hub_ensure() == 0) {
            u8 pl[4];
            hub_put32(pl, id);
            hub_lock();
            hub_send(HUB_LINKUNREG, pl, sizeof pl, -1);
            hub_unlock();
        }
        return;
    }
}

// A registered listener's events have no kernel behind them: the hub writes them into the
// socket pair, but the guest's NetlinkListener reads with uevent_kernel_recv, which drops
// anything that does not arrive shaped as a kernel message — SCM_CREDENTIALS of uid 0, a
// source pid of 0, a subscribed group in the source address. A plain socket pair provides
// none of that, so an injected event used to die inside the receive call itself and the
// framework never heard it (the binary RTM_DELLINK of the M2 teardown among them). The
// supervisor stands in for the kernel at the receive, as it already does at the tun ioctls:
// the datagram is the hub's, the envelope is written the way the kernel would have written
// it. Only fds registered with the hub take this path; every other recvmsg is untouched.
static int g_nllink_delivered;
static int g_nllink_unserved;
// A netlink fd whose recvmsg the synthesised path did not serve: with the recvmsg trap
// in place this can only mean the fd was never registered with the hub, so the datagram
// (if any) is read plain — the exact shape that used to die as EIO in the listener.
static void nllink_log_notserved(int fd) {
    if (g_nllink_unserved >= 8) return;
    g_nllink_unserved++;
    char line[160]; u64 o = 0;
    tun_cat(line, &o, "aemu-nllink: fd ");
    tun_catn(line, &o, (u64)fd);
    tun_cat(line, &o, " recvmsg NOT served (not registered with the hub)\n");
    say(line);
}

static int nl_listener_recv(int fd, struct msgh *m, long *out) {
    int lid = 0;
    for (int i = 0; i < MAX_LINKREG; i++) if (g_linkreg[i].fd == fd) { lid = 1; break; }
    if (!lid || !m) return 0;
    int offered = (int)m->namelen;
    int env = 0;
    u8 buf[4096];
    long n = S3(NR_read, fd, buf, sizeof buf);
    if (n < 0) { *out = n; return 1; }
    if (m->name && m->namelen >= 12) {
        // struct sockaddr_nl is 12 bytes, and 12 is exactly what uevent_kernel_recv offers
        // (its msg_namelen is sizeof(addr)); asking for 16 here meant the address was never
        // written at all, and the receiver's pid/group gates failed on uninitialised stack
        // — every injected event died as "recvmsg failed (I/O error)" in the listener.
        u16 *sa = (u16 *)m->name;
        u32 *w = (u32 *)m->name;
        sa[0] = 16; sa[1] = 0;              // AF_NETLINK, no padding
        w[1] = 0;                           // the kernel's pid
        w[2] = nl_groups_of(fd);            // the group this socket subscribed to
        m->namelen = 12;
        env = 1;
    }
    if (m->control && m->controllen >= 32) {
        struct cmsg *c = (struct cmsg *)m->control;
        c->len = 28;                        // CMSG_LEN(sizeof(struct ucred))
        c->level = 1;                       // SOL_SOCKET
        c->type = 2;                        // SCM_CREDENTIALS
        u32 *cred = (u32 *)((u8 *)m->control + 16);
        cred[0] = 0; cred[1] = 0; cred[2] = 0;   // the kernel's own pid/uid/gid
        m->controllen = 32;                // CMSG_SPACE(sizeof(struct ucred))
    } else if (m->control) m->controllen = 0;
    m->flags = 0;
    long done = 0;
    for (u64 i = 0; i < m->iovlen && done < n; i++) {
        u64 take = m->iov[i].len < (u64)(n - done) ? m->iov[i].len : (u64)(n - done);
        memcpy(m->iov[i].base, buf + done, take);
        done += (long)take;
    }
    if (g_nllink_delivered < 24) {
        g_nllink_delivered++;
        char line[224]; u64 o = 0;
        tun_cat(line, &o, "aemu-nllink: fd ");
        tun_catn(line, &o, (u64)fd);
        tun_cat(line, &o, " proto ");
        tun_catn(line, &o, (u64)nl_proto_of(fd));
        tun_cat(line, &o, " delivered ");
        tun_catn(line, &o, (u64)done);
        if (env) {
            tun_cat(line, &o, " bytes, envelope written (namelen 12, groups ");
            tun_catn(line, &o, nl_groups_of(fd));
            tun_cat(line, &o, ")");
        } else {
            tun_cat(line, &o, " bytes, envelope NOT written (namelen offered ");
            tun_catn(line, &o, (u64)offered);
            tun_cat(line, &o, ")");
        }
        tun_cat(line, &o, "\n");
        say(line);
    }
    *out = done;
    return 1;
}

// ---------------------------------------------------------------------------------------------------- exec

static int env_is_ours(const char *e) { return pre(e, "AEMU64_"); }

static void exec_ids(const char *guest, const char *host, int *uid, int *gid);

static long do_exec(const char *path, char *const argv[], char *const envp[]) {
    static char buf[PATH_MAX]; int err = 0;      /* exec replaces the process: nobody else is left to share it */
    const char *host = xl(path, buf, &err);
    if (!host) return err;
    long r = S4(NR_faccessat, AT_FDCWD, host, 1 /*X_OK*/, 0);
    if (r < 0) return r;
    int euid = g_uid, egid = g_gid;
    exec_ids(path, host, &euid, &egid);
    u64 na = 0, ne = 0;
    if (argv) while (argv[na]) na++;
    if (envp) while (envp[ne]) ne++;
    u64 need = (na + ne + 24) * sizeof(char *);
    char **v = (char **)S6(NR_mmap, 0, need, 3, 0x22, -1, 0);
    if ((u64)v >= (u64)-4095) return -ENOMEM;
    char **a = v, **e;
    *a++ = g_self;
    *a++ = (char *)(na ? argv[0] : path);
    *a++ = (char *)host;
    for (u64 i = 1; i < na; i++) *a++ = argv[i];
    *a++ = 0;
    e = a;
    for (u64 i = 0; i < ne; i++) if (!env_is_ours(envp[i])) *a++ = envp[i];
    static char e_root[540], e_props[540], e_binder[140], e_hwbinder[140], e_vndbinder[140], e_id[64], e_f[] = "AEMU64_F=1";
    memcpy(e_root, "AEMU64_ROOT=", 12); memcpy(e_root + 12, g_root, g_rootlen + 1);
    *a++ = e_root;
    if (g_props[0]) { memcpy(e_props, "AEMU64_PROPS=", 13); memcpy(e_props + 13, g_props, slen(g_props) + 1); *a++ = e_props; }
    static char e_utsrel[128], e_utsver[320];
    if (g_uts_rel[0]) { memcpy(e_utsrel, "AEMU64_UTS_RELEASE=", 19); memcpy(e_utsrel + 19, g_uts_rel, slen(g_uts_rel) + 1); *a++ = e_utsrel; }
    if (g_uts_ver[0]) { memcpy(e_utsver, "AEMU64_UTS_VERSION=", 19); memcpy(e_utsver + 19, g_uts_ver, slen(g_uts_ver) + 1); *a++ = e_utsver; }
    if (g_bsock[0]) { memcpy(e_binder, "AEMU64_BINDER=", 14); memcpy(e_binder + 14, g_bsock, slen(g_bsock) + 1); *a++ = e_binder; }
    if (g_hwbsock[0]) { memcpy(e_hwbinder, "AEMU64_HWBINDER=", 16); memcpy(e_hwbinder + 16, g_hwbsock, slen(g_hwbsock) + 1); *a++ = e_hwbinder; }
    if (g_vndbsock[0]) { memcpy(e_vndbinder, "AEMU64_VNDBINDER=", 17); memcpy(e_vndbinder + 17, g_vndbsock, slen(g_vndbsock) + 1); *a++ = e_vndbinder; }
    char t[24], *s = utoa((u64)euid, t + sizeof t); u64 o = 10;
    memcpy(e_id, "AEMU64_ID=", 10); memcpy(e_id + o, s, slen(s)); o += slen(s); e_id[o++] = ',';
    s = utoa((u64)egid, t + sizeof t); memcpy(e_id + o, s, slen(s) + 1);
    *a++ = e_id; *a++ = e_f;
    if (g_crash) *a++ = "AEMU64_CRASH=1";
    static char e_input[130];
    if (g_input[0]) { memcpy(e_input, "AEMU64_INPUT=", 13); memcpy(e_input + 13, g_input, slen(g_input) + 1); *a++ = e_input; }
    static char e_qemu[540];
    if (g_qemu[0]) { memcpy(e_qemu, "AEMU64_QEMU=", 12); memcpy(e_qemu + 12, g_qemu, slen(g_qemu) + 1); *a++ = e_qemu; }
    static char e_sd[540];
    if (g_sdlen) { memcpy(e_sd, "AEMU64_SDCARD=", 14); memcpy(e_sd + 14, g_sd, g_sdlen + 1); *a++ = e_sd; }
    *a++ = 0;
    r = S3(NR_execve, g_self, v, e);
    S2(NR_munmap, v, need);
    return r;
}

// ---------------------------------------------------------------------------------------------------- traps

// The trap handler runs on whatever stack the guest thread has, including a 16 KB alternate signal stack with
// the guest's own handler already on it. Path buffers therefore come from this pool, not from the stack.
#define NPB 48
static char g_pb[NPB][3 * PATH_MAX];
static volatile int g_pbbusy[NPB];

static char *pb_get(void) {
    for (;;) {
        for (int i = 0; i < NPB; i++) {
            int z = 0;
            if (!g_pbbusy[i] && __atomic_compare_exchange_n(&g_pbbusy[i], &z, 1, 0, __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) return g_pb[i];
        }
        S0(NR_sched_yield);
    }
}
static void pb_put(char *b) { __atomic_store_n(&g_pbbusy[(b - g_pb[0]) / (3 * PATH_MAX)], 0, __ATOMIC_RELEASE); }

static long unix_addr(long nr, long fd, const struct sockaddr_un *sa, u64 len, char *pb) {
    if (sa && len > 2 && sa->family == 1 && sa->path[0] == '/') {
        char *p = pb, *buf = pb + PATH_MAX; int err = 0;
        u64 n = len - 2; if (n > 108) n = 108;
        memcpy(p, sa->path, n); p[n] = 0;
        const char *h = xl(p, buf, &err);
        if (!h) return err;
        u64 hl = slen(h);
        if (hl >= 108) return -ENAMETOOLONG;
        struct sockaddr_un t; t.family = 1; memcpy(t.path, h, hl + 1);
        return S3(nr, fd, &t, 2 + hl + 1);
    }
    return S3(nr, fd, sa, len);
}

// On the phone every file of the image is the app's. Whom the guest gave a file under /data to (installd hands each
// app its data directory and on the next boot refuses one that reads as someone else's) is kept beside the file,
// in an attribute, and read back by stat.
#define OWN_ATTR "user.aemu.own"
static void own_put(long nr, long where, long uid, long gid) {        // nr: NR_lsetxattr / NR_setxattr / fsetxattr
    u32 v[2] = { 0, 0 };
    if ((u32)uid == 0xffffffffu || (u32)gid == 0xffffffffu) S4(nr + 3, where, OWN_ATTR, v, sizeof v);   // the matching get
    if ((u32)uid != 0xffffffffu) v[0] = (u32)uid;
    if ((u32)gid != 0xffffffffu) v[1] = (u32)gid;
    S5(nr, where, OWN_ATTR, v, sizeof v, 0);
}

// The ids a fresh image starts with: the caller's own, unless the file it runs carries the
// setuid/setgid bits — the classic su mechanism, which the host kernel cannot apply for us (on the
// phone the file belongs to the app, so its bits would elevate to the app itself). Only files
// inside the guest tree count, and the owner is read exactly as stat reads it (fake_owner, then
// the /data owners attribute), so the identity an exec gains is the identity stat shows for the
// file. A change is logged once; an exec of a file without the bits is untouched.
static void exec_ids(const char *guest, const char *host, int *uid, int *gid) {
    if (!pre(host, g_root) || host[g_rootlen] != '/') return;
    struct kstat st;
    if (S4(NR_newfstatat, AT_FDCWD, host, &st, 0) != 0) return;
    u32 mode = st.st_mode;
    if ((mode & 0170000) != 0100000 || !(mode & 06000)) return;
    fake_owner(&st);
    u32 ouid = st.st_uid, ogid = st.st_gid;
    u32 own[2];
    if (guest && pre(guest, "/data/") && S4(NR_getxattr, host, OWN_ATTR, own, sizeof own) == (long)sizeof own) {
        ouid = own[0]; ogid = own[1];
    } else if (g_uid >= 10000 && st.st_uid == 0 && guest && (pre(guest, "/data/data/") || pre(guest, "/data/user/"))) {
        ouid = (u32)g_uid; ogid = (u32)g_gid;
    }
    int nu = (mode & 04000) ? (int)ouid : *uid;
    int ng = (mode & 02000) ? (int)ogid : *gid;
    if (nu == *uid && ng == *gid) return;
    static char line[PATH_MAX + 128]; u64 o = 0;
    const char *p = "aemu-exec: ";
    memcpy(line + o, p, slen(p)); o += slen(p);
    if (guest) { u64 gl = slen(guest); if (gl > PATH_MAX - 64) gl = PATH_MAX - 64; memcpy(line + o, guest, gl); o += gl; }
    p = (mode & 03000) == 03000 ? " is setuid+setgid: id " : (mode & 04000) ? " is setuid: id " : " is setgid: id ";
    memcpy(line + o, p, slen(p)); o += slen(p);
    char t[24]; char *s = utoa((u64)*uid, t + sizeof t); memcpy(line + o, s, slen(s)); o += slen(s);
    line[o++] = ','; s = utoa((u64)*gid, t + sizeof t); memcpy(line + o, s, slen(s)); o += slen(s);
    p = " -> "; memcpy(line + o, p, slen(p)); o += slen(p);
    s = utoa((u64)nu, t + sizeof t); memcpy(line + o, s, slen(s)); o += slen(s);
    line[o++] = ','; s = utoa((u64)ng, t + sizeof t); memcpy(line + o, s, slen(s)); o += slen(s);
    line[o++] = '\n'; line[o] = 0;
    say(line);
    *uid = nu; *gid = ng;
}

#define P(i) ((const char *)a[i])
#define X(i) xl(P(i), b##i, &err)
#define NEEDX(i) const char *h##i = X(i); if (!h##i && P(i)) return err

// /system is a read-only partition on a phone. Here it is a directory of the app, and the guest runs as the app: its
// installd unlinked the precompiled code of the framework there (the only copy: the jars are stripped) when it could
// not replace it, and the firmware never started again. A change under /system is refused as a read-only file
// system refuses it. `h` is the host path of the call, or a name relative to `dirfd`; `t` is PATH_MAX of scratch.
#define EROFS 30
static int in_system(long dirfd, const char *h, char *t) {
    if (!h) return 0;
    if (h[0] != '/') {
        char l[40] = "/proc/self/fd/", num[24];
        if ((int)dirfd == AT_FDCWD) memcpy(l, "/proc/self/cwd", 15);
        else { char *d = utoa((u64)(u32)dirfd, num + sizeof num); memcpy(l + 14, d, slen(d) + 1); }
        long r = S4(NR_readlinkat, AT_FDCWD, l, t, PATH_MAX - 1);
        if (r <= 0) return 0;
        t[r] = 0;
        h = t;
        return pre(h, g_root) && (seq(h + g_rootlen, "/system") || pre(h + g_rootlen, "/system/"));
    }
    return pre(h, g_root) && pre(h + g_rootlen, "/system/");
}

static long trap2(long nr, long *a, void *uc, char **pbp);

static long trap(long nr, long *a, void *uc) {
    char *pb = 0;
    long r = trap2(nr, a, uc, &pb);
    if (pb) pb_put(pb);
    return r;
}

#define PB(i) ((*pbp ? *pbp : (*pbp = pb_get())) + (i) * PATH_MAX)

#include "../vmount.h"

// ---------------------------------------------------------------------------------------------------- isolation observation

// Observe-only (the isolation measurement batch): when an open lands outside the guest root, one
// line goes to the host log — who opened what, and where it actually landed. Nothing is refused,
// nothing is altered: the call has already returned whatever it returned. The landing is read back
// from the descriptor itself (/proc/self/fd), so symlink escapes and dirfd-relative climbs are
// seen where they end, not where the path string pointed. Landings on the layer's designed
// surfaces are not reported: the phone's device nodes, the shared card folder (g_sd), and the
// /proc + /sys fallthrough — all deliberate, all visible in xl. Set ISO_OBSERVE to 0 to silence.
#define ISO_OBSERVE 1

static void iso_cat(char *t, u64 *o, const char *s) {
    u64 n = slen(s);
    if (*o + n > PATH_MAX - 2) n = PATH_MAX - 2 - *o;
    memcpy(t + *o, s, n); *o += n; t[*o] = 0;
}

static void iso_observe(int fd, const char *guest, char *t, char *line) {
    if (!ISO_OBSERVE) return;
    char lp[40] = "/proc/self/fd/", num[24];
    char *d = utoa((u64)(u32)fd, num + sizeof num);
    memcpy(lp + 14, d, slen(d) + 1);
    long r = S4(NR_readlinkat, AT_FDCWD, lp, t, PATH_MAX - 1);
    if (r <= 0) return;
    t[r] = 0;
    if (t[0] != '/') return;                                                // socket:[..], pipe:[..], anon_inode:..
    if (pre(t, g_root) && (t[g_rootlen] == '/' || !t[g_rootlen])) return;   // landed inside the root
    if (g_sdlen && pre(t, g_sd) && (t[g_sdlen] == '/' || !t[g_sdlen])) return;  // the shared card folder, by design
    if (pre(t, "/dev/") || pre(t, "/proc/") || pre(t, "/sys/") || pre(t, "/memfd:")) return;   // designed surfaces
    char comm[32];
    u64 cl = 0;
    long cf = S4(NR_openat, AT_FDCWD, "/proc/self/comm", 0, 0);
    if (cf >= 0) { long n = S3(NR_read, cf, comm, sizeof comm - 1); S1(NR_close, cf); if (n > 0) cl = (u64)n; }
    while (cl && (comm[cl - 1] == '\n' || comm[cl - 1] == ' ')) cl--;
    comm[cl] = 0;
    if (!cl) { memcpy(comm, "?", 2); cl = 1; }
    u64 o = 0;
    iso_cat(line, &o, "iso-obs: ");
    iso_cat(line, &o, comm);
    iso_cat(line, &o, " opened ");
    iso_cat(line, &o, guest ? guest : "?");
    iso_cat(line, &o, " and landed outside the root at ");
    iso_cat(line, &o, t);
    iso_cat(line, &o, "\n");
    say(line);
}

// ---------------------------------------------------------------------------------------------------- isolation shadow resolver

// Shadow mode (the isolation repair batch, observation half): an in-root resolver walks the path a
// call asked for in guest space — segment by segment, anchored at the guest cwd for a relative path,
// at the descriptor's guest landing for openat's dirfd, expanding the symlinks it finds in the tree
// and the /proc magic doorways (self/root, <pid>/root, self/cwd, <pid>/cwd, self/exe, <pid>/exe) —
// and computes where the call SHOULD land if the root were a wall. The answer is never used: it is
// compared with what really happened and written to the host log as an iso-shadow line. No return
// value, path or behaviour changes anywhere in this section. A real landing outside the root is
// checked against the shared-folder authorisation table — a sibling of the root directory,
// ../aemu-shared.txt, one host path per line with an optional ro/rw word after it, '#' comments, an
// absent file means an empty table — and the line ends would-deny or would-allow-by-table. The card
// folder (g_sd) is the one built-in share and, like the other designed surfaces iso_observe stays
// silent about, never produces a line. ISO_SHADOW=0 silences.
#define ISO_SHADOW 1

struct isob { char *land, *cur, *probe, *tgt, *rest, *line; };

static void iso_blk(struct isob *b, char **b1, char **b2) {
    *b1 = pb_get(); *b2 = pb_get();
    b->land = *b1; b->cur = *b1 + PATH_MAX; b->probe = *b1 + 2 * PATH_MAX;
    b->tgt = *b2; b->rest = *b2 + PATH_MAX; b->line = *b2 + 2 * PATH_MAX;
}

static void iso_comm(char *comm) {
    u64 cl = 0;
    long cf = S4(NR_openat, AT_FDCWD, "/proc/self/comm", 0, 0);
    if (cf >= 0) { long n = S3(NR_read, cf, comm, 31); S1(NR_close, cf); if (n > 0) cl = (u64)n; }
    while (cl && (comm[cl - 1] == '\n' || comm[cl - 1] == ' ')) cl--;
    comm[cl] = 0;
    if (!cl) memcpy(comm, "?", 2);
}

// a landing this layer means to allow (or no file landing at all): no line
static int iso_quiet(const char *t) {
    if (t[0] != '/') return 1;
    if (pre(t, g_root) && (t[g_rootlen] == '/' || !t[g_rootlen])) return 1;
    if (g_sdlen && pre(t, g_sd) && (t[g_sdlen] == '/' || !t[g_sdlen])) return 1;
    if (pre(t, "/dev/") || pre(t, "/proc/") || pre(t, "/sys/") || pre(t, "/memfd:")) return 1;
    return 0;
}

// the host directory a descriptor (or the working directory) stands on
static int iso_host_dir(long dirfd, char *out) {
    char lp[40] = "/proc/self/fd/", num[24];
    if ((int)dirfd == AT_FDCWD) memcpy(lp, "/proc/self/cwd", 15);
    else { char *d = utoa((u64)(u32)dirfd, num + sizeof num); memcpy(lp + 14, d, slen(d) + 1); }
    long r = S4(NR_readlinkat, AT_FDCWD, lp, out, PATH_MAX - 1);
    if (r <= 0) return 0;
    out[r] = 0;
    return out[0] == '/';
}

static void iso_pop(char *cur) {
    u64 n = slen(cur);
    while (n > 1 && cur[n - 1] != '/') n--;
    if (n > 1) n--;
    cur[n] = 0;
    if (!n) { cur[0] = '/'; cur[1] = 0; }
}

static int iso_push(char *cur, const char *seg, u64 n) {
    u64 cl = slen(cur);
    if (cl + n + 2 > PATH_MAX) return -1;
    if (cl > 1) cur[cl++] = '/';
    memcpy(cur + cl, seg, n);
    cur[cl + n] = 0;
    return 0;
}

// plain string resolution (no tree probing): ".." is clamped at "/", as the in-root walk clamps it
static void iso_resolve_str(const char *anchor, const char *path, char *out) {
    if (path[0] == '/') memcpy(out, "/", 2);
    else memcpy(out, anchor, slen(anchor) + 1);
    const char *p = path;
    while (*p) {
        while (*p == '/') p++;
        if (!*p) break;
        const char *e = p; while (*e && *e != '/') e++;
        u64 n = (u64)(e - p);
        if (n == 1 && p[0] == '.') { p = e; continue; }
        if (n == 2 && p[0] == '.' && p[1] == '.') { iso_pop(out); p = e; continue; }
        if (iso_push(out, p, n)) return;
        p = e;
    }
}
// The /proc magic doorways, met while walking: /proc/<who>/root is the guest root here, not the
// phone's; /proc/<who>/cwd and /proc/<who>/exe name that process's guest cwd and program.
static int iso_doorway(char *cur, const char *selfcwd, char *scratch) {
    if (!pre(cur, "/proc/")) return 0;
    const char *q = cur + 6, *slash = q;
    while (*slash && *slash != '/') slash++;
    if (!*slash) return 0;
    int self = (slash - q == 4 && pre(q, "self"));
    int numeric = slash > q;
    for (const char *d = q; d < slash; d++) if (*d < '0' || *d > '9') numeric = 0;
    if (!self && !numeric) return 0;
    if (seq(slash, "/root")) { memcpy(cur, "/", 2); return 1; }
    if (seq(slash, "/cwd") || seq(slash, "/exe")) {
        if (self && seq(slash, "/cwd")) { memcpy(cur, selfcwd, slen(selfcwd) + 1); return 1; }
        if (self && seq(slash, "/exe") && g_exe[0]) { memcpy(cur, g_exe, slen(g_exe) + 1); return 1; }
        char lp[64];
        memcpy(lp, "/proc/", 6);
        memcpy(lp + 6, q, (u64)(slash - q));
        memcpy(lp + 6 + (slash - q), slash, slen(slash) + 1);
        long r = S4(NR_readlinkat, AT_FDCWD, lp, scratch, PATH_MAX - 1);
        if (r <= 0) return 0;
        scratch[r] = 0;
        unxl(scratch);
        memcpy(cur, scratch, slen(scratch) + 1);
        return 1;
    }
    return 0;
}

// Walk `path` in guest space from the guest directory `anchor`, expanding the tree's symlinks and
// the /proc doorways; `selfcwd` is this process's cwd in guest terms. Returns 0 with the in-root
// guest landing in b->cur, 1 when the walk steps onto a designed surface (the /proc or /sys
// fallthrough, a host device node), 2 when it cannot be resolved (a missing component, a link
// loop, a mount rewrite it does not model). b->land is not touched.
static int iso_walk(const char *anchor, const char *path, const char *selfcwd, struct isob *b) {
    char *cur = b->cur, *rest = b->rest, *probe = b->probe, *tgt = b->tgt;
    if (path[0] == '/') memcpy(cur, "/", 2);
    else memcpy(cur, anchor, slen(anchor) + 1);
    memcpy(rest, path, slen(path) + 1);
    u64 off = 0;
    int depth = 0;
    for (;;) {
        while (rest[off] == '/') off++;
        if (!rest[off]) return 0;
        u64 e = off;
        while (rest[e] && rest[e] != '/') e++;
        u64 n = e - off;
        const char *seg = rest + off;
        if (n == 1 && seg[0] == '.') { off = e; continue; }
        if (n == 2 && seg[0] == '.' && seg[1] == '.') { iso_pop(cur); off = e; continue; }
        if (iso_push(cur, seg, n)) return 2;
        off = e;
        if (iso_doorway(cur, selfcwd, probe)) continue;
        if (pre(cur, "/proc/") || pre(cur, "/sys/") || seq(cur, "/proc") || seq(cur, "/sys")) {
            memcpy(probe, g_root, g_rootlen);
            memcpy(probe + g_rootlen, cur, slen(cur) + 1);
            struct kstat st;
            if (S4(NR_newfstatat, AT_FDCWD, probe, &st, 0x100) != 0) return 1;   // not the image's: the phone's, by design
        }
        if (pre(cur, "/dev/") && host_dev(cur + 5)) return 1;
        memcpy(probe, g_root, g_rootlen);
        memcpy(probe + g_rootlen, cur, slen(cur) + 1);
        struct kstat st;
        if (S4(NR_newfstatat, AT_FDCWD, probe, &st, 0x100) != 0) return 2;
        if ((st.st_mode & 0170000) != 0120000) continue;
        long tr = S4(NR_readlinkat, AT_FDCWD, probe, tgt, PATH_MAX - 1);
        if (tr <= 0) return 2;
        tgt[tr] = 0;
        if (++depth > 40) return 2;
        iso_pop(cur);
        if (tgt[0] == '/') memcpy(cur, "/", 2);
        u64 tl = slen(tgt), rl = slen(rest + off);
        if (tl + rl + 2 > PATH_MAX) return 2;
        char *nb = b->line;
        memcpy(nb, tgt, tl);
        u64 no = tl;
        if (rl) { nb[no++] = '/'; memcpy(nb + no, rest + off, rl); no += rl; }
        nb[no] = 0;
        memcpy(rest, nb, no + 1);
        off = 0;
    }
}
// The authorisation table next to the root: one host path per line, an optional ro/rw word after
// it, '#' comments; no file, no entries.
static int iso_table_allows(const char *landing, char *content) {
    u64 gl = g_rootlen;
    while (gl && g_root[gl - 1] != '/') gl--;
    if (!gl || gl + 16 > 600) return 0;
    char tp[600];
    memcpy(tp, g_root, gl);
    memcpy(tp + gl, "aemu-shared.txt", 16);
    long fd = S4(NR_openat, AT_FDCWD, tp, 0, 0);
    if (fd < 0) return 0;
    long n = S3(NR_read, fd, content, PATH_MAX - 1);
    S1(NR_close, fd);
    if (n <= 0) return 0;
    content[n] = 0;
    char *p = content;
    while (*p) {
        char *e = p;
        while (*e && *e != '\n') e++;
        char *s = p;
        while (s < e && (*s == ' ' || *s == '\t' || *s == '\r')) s++;
        if (s < e && *s != '#') {
            char *te = s;
            while (te < e && *te != ' ' && *te != '\t' && *te != '\r') te++;
            u64 el = (u64)(te - s), ll = slen(landing);
            int eq = el && el <= ll;
            for (u64 i = 0; eq && i < el; i++) if (landing[i] != s[i]) eq = 0;
            if (eq && (el == ll || landing[el] == '/' || s[el - 1] == '/')) return 1;
        }
        p = *e ? e + 1 : e;
    }
    return 0;
}

static void iso_shadow_report(const char *kind, const char *akind, const char *asked, const char *lands,
                              const char *inroot, struct isob *b) {
    int allow = iso_table_allows(lands, b->rest);
    char comm[32];
    iso_comm(comm);
    char *line = b->line;
    u64 o = 0;
    iso_cat(line, &o, "iso-shadow: ");
    iso_cat(line, &o, comm);
    iso_cat(line, &o, " ");
    iso_cat(line, &o, kind);
    iso_cat(line, &o, " anchor=");
    iso_cat(line, &o, akind);
    iso_cat(line, &o, " asked ");
    iso_cat(line, &o, asked ? asked : "?");
    iso_cat(line, &o, " lands ");
    iso_cat(line, &o, lands);
    iso_cat(line, &o, "; in-root ");
    iso_cat(line, &o, inroot);
    iso_cat(line, &o, allow ? "; would-allow-by-table\n" : "; would-deny\n");
    say(line);
}

static void iso_shadow_open(int fd, long dirfd, const char *asked) {
    if (!ISO_SHADOW || !asked) return;
    struct isob b;
    char *blk1, *blk2;
    iso_blk(&b, &blk1, &blk2);
    const char *akind = (int)dirfd == AT_FDCWD ? "cwd" : "dirfd";
    int anchor_out = 0, walkrc = 2;
    if (!iso_host_dir(dirfd, b.tgt)) goto out;
    if (!(pre(b.tgt, g_root) && (b.tgt[g_rootlen] == '/' || !b.tgt[g_rootlen])) &&
        !(g_sdlen && pre(b.tgt, g_sd) && (b.tgt[g_sdlen] == '/' || !b.tgt[g_sdlen]))) anchor_out = 1;
    if (iso_host_dir(AT_FDCWD, b.land)) unxl(b.land);
    else memcpy(b.land, "/", 2);
    unxl(b.tgt);
    if (asked[0] == '/' || !anchor_out) walkrc = iso_walk(b.tgt, asked, b.land, &b);
    {
        char lp[40] = "/proc/self/fd/", num[24];
        char *d = utoa((u64)(u32)fd, num + sizeof num);
        memcpy(lp + 14, d, slen(d) + 1);
        long r = S4(NR_readlinkat, AT_FDCWD, lp, b.land, PATH_MAX - 1);
        if (r <= 0) goto out;
        b.land[r] = 0;
    }
    if (iso_quiet(b.land)) goto out;
    iso_shadow_report("openat", akind, asked, b.land,
                      (anchor_out && asked[0] != '/') ? "(anchor outside the root)" :
                      walkrc == 1 ? "(designed surface)" : walkrc ? "(unresolved)" : b.cur, &b);
out:
    pb_put(blk1);
    pb_put(blk2);
}
// A bare read of a /proc magic doorway: the answer names a host place. A root doorway always
// leads out (its in-root answer is the guest's own "/"); a cwd/exe naming an in-root file is an
// honest answer and stays silent.
static void iso_shadow_magic(const char *asked, const char *raw) {
    if (!ISO_SHADOW || !asked || !raw) return;
    if (!pre(asked, "/proc/")) return;
    const char *q = asked + 6, *slash = q;
    while (*slash && *slash != '/') slash++;
    if (!*slash) return;
    int self = (slash - q == 4 && pre(q, "self"));
    int numeric = slash > q;
    for (const char *d = q; d < slash; d++) if (*d < '0' || *d > '9') numeric = 0;
    if (!self && !numeric) return;
    int isroot = seq(slash, "/root");
    if (!isroot && !seq(slash, "/cwd") && !seq(slash, "/exe")) return;
    if (!isroot && iso_quiet(raw)) return;
    struct isob b;
    char *blk1, *blk2;
    iso_blk(&b, &blk1, &blk2);
    memcpy(b.land, raw, slen(raw) + 1);
    const char *inroot = "/";
    if (!isroot) {
        memcpy(b.cur, raw, slen(raw) + 1);
        unxl(b.cur);
        inroot = b.cur;
    }
    iso_shadow_report("readlink", "magic", asked, b.land, inroot, &b);
    pb_put(blk1);
    pb_put(blk2);
}

// chdir is resolved before the move (the anchor is gone afterwards) and reported after it, only
// when the process really ended up outside the root.
static void iso_shadow_chdir_pre(const char *asked, char *out) {
    out[0] = 0;
    if (!ISO_SHADOW || !asked) return;
    struct isob b;
    char *blk1, *blk2;
    iso_blk(&b, &blk1, &blk2);
    if (!iso_host_dir(AT_FDCWD, b.tgt)) goto done;
    if (!(pre(b.tgt, g_root) && (b.tgt[g_rootlen] == '/' || !b.tgt[g_rootlen])) &&
        !(g_sdlen && pre(b.tgt, g_sd) && (b.tgt[g_sdlen] == '/' || !b.tgt[g_sdlen]))) goto done;
    unxl(b.tgt);
    memcpy(b.land, b.tgt, slen(b.tgt) + 1);
    if (iso_walk(b.tgt, asked, b.land, &b) == 0) memcpy(out, b.cur, slen(b.cur) + 1);
done:
    pb_put(blk1);
    pb_put(blk2);
}

static void iso_shadow_chdir_post(const char *asked, const char *pre_landing) {
    if (!ISO_SHADOW || !asked) return;
    struct isob b;
    char *blk1, *blk2;
    iso_blk(&b, &blk1, &blk2);
    if (iso_host_dir(AT_FDCWD, b.land) && !iso_quiet(b.land))
        iso_shadow_report("chdir", "cwd", asked, b.land,
                          pre_landing[0] ? pre_landing : "(anchor outside the root)", &b);
    pb_put(blk1);
    pb_put(blk2);
}

// A symlink planted with a relative target: the target is stored as it was given, and the phone's
// kernel will walk it from the link's real directory — out of the root, if it climbs. (An absolute
// target was re-rooted by xl when the link was made, so it cannot climb.)
static void iso_shadow_symlink(long dirfd, const char *target, const char *linkpath) {
    if (!ISO_SHADOW || !target || !linkpath || target[0] == '/') return;
    struct isob b;
    char *blk1, *blk2;
    iso_blk(&b, &blk1, &blk2);
    if (linkpath[0] == '/') {
        const char *last = linkpath;
        for (const char *p = linkpath; *p; p++) if (*p == '/') last = p;
        u64 dl = (u64)(last - linkpath);
        if (!dl) dl = 1;
        memcpy(b.probe, linkpath, dl);
        b.probe[dl] = 0;
        memcpy(b.tgt, g_root, g_rootlen);
        memcpy(b.tgt + g_rootlen, b.probe, dl + 1);
    } else {
        if (!iso_host_dir(dirfd, b.tgt)) goto done;
        memcpy(b.probe, b.tgt, slen(b.tgt) + 1);
        unxl(b.probe);
        const char *last = 0;
        for (const char *p = linkpath; *p; p++) if (*p == '/') last = p;
        if (last) {
            u64 dl = (u64)(last - linkpath), cl = slen(b.probe), hl = slen(b.tgt);
            if (cl > 1) b.probe[cl++] = '/';
            memcpy(b.probe + cl, linkpath, dl);
            b.probe[cl + dl] = 0;
            if (hl > 1) b.tgt[hl++] = '/';
            memcpy(b.tgt + hl, linkpath, dl);
            b.tgt[hl + dl] = 0;
        }
    }
    iso_resolve_str(b.tgt, target, b.land);          // where the phone's kernel will take this link
    if (iso_quiet(b.land)) goto done;
    iso_resolve_str(b.probe, target, b.cur);         // where it should stay
    {
        u64 o = 0;
        iso_cat(b.tgt, &o, target);
        iso_cat(b.tgt, &o, " at ");
        iso_cat(b.tgt, &o, linkpath);
    }
    iso_shadow_report("symlink", "link", b.tgt, b.land, b.cur, &b);
done:
    pb_put(blk1);
    pb_put(blk2);
}

// ptrace is observed, never mediated: the request is performed exactly as the guest made it and
// only the attach-family attempts (traceme, attach, seize) are written down, with their result.
// The guest's own crash machinery lives on this call — debuggerd attaches to a crashing process
// to take its tombstone — so an enforcement phase must not simply refuse it; these lines are the
// evidence that phase will have to separate debuggerd's attaches from a tracer taking over a
// process it should not own.
static void iso_shadow_ptrace(long req, long pid, long r) {
    if (!ISO_SHADOW) return;
    char comm[32];
    iso_comm(comm);
    char line[512];
    u64 o = 0;
    iso_cat(line, &o, "iso-shadow: ");
    iso_cat(line, &o, comm);
    iso_cat(line, &o, " ptrace ");
    iso_cat(line, &o, req == 0 ? "traceme" : req == 16 ? "attach" : "seize");
    iso_cat(line, &o, " pid ");
    char num[24];
    char *d = utoa((u64)pid, num + sizeof num);
    iso_cat(line, &o, d);
    iso_cat(line, &o, " -> ");
    if (r < 0) { iso_cat(line, &o, "-"); d = utoa((u64)(-r), num + sizeof num); iso_cat(line, &o, d); }
    else { d = utoa((u64)r, num + sizeof num); iso_cat(line, &o, d); }
    iso_cat(line, &o, "\n");
    say(line);
}

static long trap2_base(long nr, long *a, void *uc, char **pbp) {
    int err = 0;
    if (g_tun_live) tun_drain();
    hub_pump();                    // the VPN hub's broadcasts (a session ending above all): a no-op with no hub connection
    switch (nr) {
    case NR_openat: {
        char *b1 = PB(0);
        if (P(1)) {
            if (P(1)[0] == '/' && P(1)[1] == 'd') {
                if (seq(P(1), "/dev/ashmem")) {
                    return S2(NR_memfd_create, "aemu-ashmem", ((a[2] & 02000000) ? 1 : 0) | 2 /*MFD_ALLOW_SEALING*/);
                }
                if (seq(P(1), "/dev/binder") || seq(P(1), "/dev/hwbinder") || seq(P(1), "/dev/vndbinder")) return binder_open(a[2], P(1));
                if (pre(P(1), "/dev/input/event")) return input_open(a[2]);
                if (seq(P(1), "/dev/tun") || seq(P(1), "/dev/net/tun")) return tun_open(a[2]);
            }
            u64 pl = slen(P(1));
            if (pre(P(1), "/proc/") && ((pl >= 13 && seq(P(1) + pl - 13, "/attr/current")) || (pl >= 10 && seq(P(1) + pl - 10, "/attr/exec")))) {
                return fake_memfile("aemu-attr", "u:r:su:s0\n", 10);
            }
            // A process's command line starts with the runner (and the translator of a 32-bit one) on the phone; the
            // guest reads its own programs' names there (Google Play services checks which of its processes it is in).
            if (pre(P(1), "/proc/") && pl >= 14 && seq(P(1) + pl - 8, "/cmdline") && !(a[2] & 3)) {
                long f = S4(nr, AT_FDCWD, P(1), 0, 0);
                if (f >= 0) {
                    char t[4096]; long n = S3(NR_read, f, t, sizeof t - 1), o = 0;
                    S1(NR_close, f);
                    if (n < 0) n = 0;
                    t[n] = 0;
                    u64 l = slen(t);
                    if (l > 16 && pre(t + l - 16, "/libaemu64_") && seq(t + l - 3, ".so")) {
                        o = (long)l + 1;
                        if (o < n && g_qemu[0] && seq(t + o, g_qemu)) o += (long)slen(g_qemu) + 1;
                        if (o > n) o = n;
                    }
                    return fake_memfile("aemu-cmdline", t + o, (u64)(n - o));
                }
            }
            if (seq(P(1), "/sys/fs/selinux/enforce")) {
                return fake_memfile("aemu-enforce", "0\n", 2);
            }
        }
        NEEDX(1);
        if ((a[2] & (3 | 0100 | 01000)) && in_system(a[0], h1, PB(2))) return -EROFS;
        { long r = S4(nr, a[0], h1, a[2], a[3]); if (r >= 0) { iso_observe((int)r, P(1), PB(1), PB(2)); iso_shadow_open((int)r, a[0], P(1)); } return r; }
    }
    case NR_newfstatat: {
        // fstat as newer C libraries make it (qemu-arm's does): the same answers as for fstat
        if ((a[3] & 0x1000 /*AT_EMPTY_PATH*/) && P(1) && !P(1)[0]) { long f[6] = { a[0], a[2], 0, 0, 0, 0 }; return trap2(NR_fstat, f, uc, pbp); }
        char *b1 = PB(0);
        if (P(1)) {
            if (seq(P(1), "/dev/binder") || seq(P(1), "/dev/hwbinder") || seq(P(1), "/dev/vndbinder") || seq(P(1), "/dev/ashmem")) {
                struct kstat *st = (struct kstat *)a[2];
                memset(st, 0, sizeof *st);
                st->st_mode = 0020666; // S_IFCHR | 0666
                st->st_rdev = 0x102;
                st->st_nlink = 1;
                return 0;
            }
            if (seq(P(1), "/dev/tun") || seq(P(1), "/dev/net/tun")) {
                struct kstat *st = (struct kstat *)a[2];
                memset(st, 0, sizeof *st);
                st->st_mode = 0020666; // S_IFCHR | 0666
                st->st_rdev = 0xAC8;   // the tun misc device, 10:200
                st->st_nlink = 1;
                return 0;
            }
            u64 pl = slen(P(1));
            if (pre(P(1), "/proc/") && pl >= 13 && seq(P(1) + pl - 13, "/attr/current")) {
                struct kstat *st = (struct kstat *)a[2];
                memset(st, 0, sizeof *st);
                st->st_mode = 0100666; // S_IFREG | 0666
                st->st_size = 10;
                st->st_nlink = 1;
                return 0;
            }
            if (seq(P(1), "/sys/fs/selinux/enforce")) {
                struct kstat *st = (struct kstat *)a[2];
                memset(st, 0, sizeof *st);
                st->st_mode = 0100666;
                st->st_size = 2;
                st->st_nlink = 1;
                return 0;
            }
        }
        NEEDX(1);
        long r = S4(nr, a[0], h1, a[2], a[3]);
        if (r == 0) {
            struct kstat *st = (struct kstat *)a[2];
            fake_owner(st);
            // an app's own data belongs to the app (the class loader refuses an oat directory of another owner)
            u32 own[2];
            if (a[1] && pre(P(1), "/data/") && S4((a[3] & 0x100) ? NR_lgetxattr : NR_getxattr, h1, OWN_ATTR, own, sizeof own) == sizeof own) {
                st->st_uid = own[0]; st->st_gid = own[1];
            } else if (g_uid >= 10000 && st->st_uid == 0 && a[1] && (pre(P(1), "/data/data/") || pre(P(1), "/data/user/"))) {
                st->st_uid = (u32)g_uid; st->st_gid = (u32)g_gid;
            }
        }
        return r;
    }
    case NR_fstat: {
        if (is_tun_fd((int)a[0])) {
            struct kstat *st = (struct kstat *)a[1];
            memset(st, 0, sizeof *st);
            st->st_mode = 0020666; // S_IFCHR | 0666
            st->st_rdev = 0xAC8;   // the tun misc device, 10:200
            st->st_nlink = 1;
            return 0;
        }
        if (is_binder_fd((int)a[0])) {
            struct kstat *st = (struct kstat *)a[1];
            memset(st, 0, sizeof *st);
            st->st_mode = 0020666; // S_IFCHR | 0666
            st->st_rdev = 0x101;
            st->st_nlink = 1;
            return 0;
        }
        long r = S2(nr, a[0], a[1]);
        if (r == 0) {
            struct kstat *st = (struct kstat *)a[1];
            if ((st->st_mode & 0170000) == 0100000 && !st->st_nlink && is_ashmem_fd((int)a[0])) {
                st->st_mode = 0020666; // S_IFCHR | 0666: libcutils checks that a region is the ashmem device
                st->st_rdev = 0x102;
                st->st_nlink = 1;
            }
            // the log as a pipe (and its copies, which qemu-arm keeps): zygote (8.0+) takes no pipes into a fork;
            // to it this is /dev/null, as on a phone
            if (g_logpipe && (st->st_mode & 0170000) == 0010000 && (st->st_ino == g_logino || st->st_ino == g_inino) && st->st_dev == g_logdev) {
                st->st_mode = 0020666; st->st_rdev = 0x103;
            }
            fake_owner(st);
        }
        return r;
    }
    case NR_faccessat: {
        char *b1 = PB(0);
        if (P(1)) {
            if (seq(P(1), "/dev/binder") || seq(P(1), "/dev/hwbinder") || seq(P(1), "/dev/vndbinder") || seq(P(1), "/dev/ashmem")) {
                return 0;
            }
            if (seq(P(1), "/dev/tun") || seq(P(1), "/dev/net/tun")) {
                return 0;
            }
            u64 pl = slen(P(1));
            if (pre(P(1), "/proc/") && pl >= 13 && seq(P(1) + pl - 13, "/attr/current")) return 0;
            if (seq(P(1), "/sys/fs/selinux/enforce")) return 0;
        }
        NEEDX(1);
        return S4(nr, a[0], h1, a[2], a[3]);
    }
    case NR_mkdirat: case NR_unlinkat: case NR_fchmodat: case NR_utimensat: case NR_mknodat:
    case NR_statx: {
        char *b1 = PB(0);
        NEEDX(1);
        if ((nr == NR_unlinkat || nr == NR_fchmodat) && in_system(a[0], h1, PB(2))) return -EROFS;
        // Added for AEmulator Plus (2026-11-21): the emulated root keeps the owner bits (vmount.h, vm_mode_fix)
        return S5(nr, a[0], h1, vm_mode_fix(nr, a[0], h1, a[2]), a[3], a[4]);
    }
    case NR_fchown: own_put(7 /*fsetxattr*/, a[0], a[1], a[2]); return 0;
    case NR_fchownat: {
        char *b1 = PB(0);
        if (P(1) && pre(P(1), "/data/")) {
            NEEDX(1);
            own_put((a[4] & 0x100) ? NR_lsetxattr : NR_setxattr, (long)h1, a[2], a[3]);
        }
        return 0;
    }
    case NR_readlinkat: {
        if (P(1)) {
            if (g_vm) {      // Added for AEmulator Plus (2026-11-21): /proc/<pid>/exe of another guest process, see vmount.h
                char *ex = PB(1);
                if (vm_proc_exe(P(1), ex)) { u64 n = slen(ex); if (n > (u64)a[3]) n = (u64)a[3]; memcpy((void *)a[2], ex, n); return (long)n; }
            }
            if (seq(P(1), "/proc/self/exe") && g_exe[0]) {
                u64 n = slen(g_exe); if (n > (u64)a[3]) n = (u64)a[3];
                memcpy((void *)a[2], g_exe, n); return (long)n;
            }
        }
        char *b1 = PB(0), *t = PB(1);
        NEEDX(1);
        long r = S4(nr, a[0], h1, t, PATH_MAX - 1);
        if (r < 0) return r;
        t[r] = 0;
        iso_shadow_magic(P(1), t);
        if (g_logpipe && pre(P(1), "/proc/") && pre(t, "pipe:")) { memcpy(t, "/dev/null", 10); r = 9; }
        if (pre(P(1), "/proc/") && t[0] == '/') {
            // what an open descriptor is: zygote (8.0+) lists its own before every fork and aborts on a file it does
            // not know. Its output is our log on the phone, outside the image: to the guest that is /dev/null.
            const char *q = P(1) + 6; while (*q && *q != '/') q++;
            if (pre(q, "/fd/")) {
                if (pre(t, "/memfd:aemu-ashmem")) memcpy(t, "/dev/ashmem", 12);
                else if (!(pre(t, g_root) && (t[g_rootlen] == '/' || !t[g_rootlen])) && !pre(t, "/dev/") &&
                         !(g_sdlen && pre(t, g_sd))) memcpy(t, "/dev/null", 10);
            }
        }
        unxl(t);
        u64 n = slen(t); if (n > (u64)a[3]) n = (u64)a[3];
        memcpy((void *)a[2], t, n);
        return (long)n;
    }
    case NR_symlinkat: {             // target, newdirfd, linkpath: an absolute target is re-rooted
        char *b0 = PB(0), *b2 = PB(1);
        NEEDX(0); NEEDX(2);
        long r = S3(nr, h0, a[1], h2);
        if (r >= 0) iso_shadow_symlink(a[1], P(0), P(2));
        return r;
    }
    case NR_linkat: case NR_renameat: case NR_renameat2: {
        char *b1 = PB(0), *b3 = PB(1);
        NEEDX(1); NEEDX(3);
        if (nr != NR_linkat && (in_system(a[0], h1, PB(2)) || in_system(a[2], h3, PB(2)))) return -EROFS;
        return S5(nr, a[0], h1, a[2], h3, a[4]);
    }
    case NR_chdir: {
        char *b0 = PB(0);
        NEEDX(0);
        char *sb = pb_get();
        iso_shadow_chdir_pre(P(0), sb);
        long r = S5(nr, h0, a[1], a[2], a[3], a[4]);
        if (r == 0) iso_shadow_chdir_post(P(0), sb);
        pb_put(sb);
        return r;
    }
    case NR_statfs: case NR_truncate:
    case NR_setxattr: case NR_lsetxattr: case NR_getxattr: case NR_lgetxattr: case NR_listxattr: case NR_llistxattr:
    case NR_removexattr: case NR_lremovexattr: {
        char *b0 = PB(0);
        NEEDX(0);
        long r = S5(nr, h0, a[1], a[2], a[3], a[4]);
        if (nr == NR_statfs && r == 0) {
            // a nearly full phone reads as a nearly full guest disk (no dropbox, low-storage modes): the guest's disk
            // is what is still free plus as much again
            u64 *s = (u64 *)a[1];               /* type, bsize, blocks, bfree, bavail */
            if (s[4] && s[2] > 2 * s[4]) { s[2] = 2 * s[4]; if (s[3] > s[2]) s[3] = s[2]; }
        }
        return r;
    }
    case NR_inotify_add_watch: {
        char *b1 = PB(0);
        NEEDX(1);
        return S3(nr, a[0], h1, a[2]);
    }
    case NR_getcwd: {
        char *t = PB(0);
        long r = S2(nr, t, PATH_MAX);
        if (r < 0) return r;
        unxl(t);
        u64 n = slen(t) + 1;
        if (n > (u64)a[1]) return -34; /* ERANGE */
        memcpy((void *)a[0], t, n);
        return (long)n;
    }
    case NR_bind: case NR_connect:
        if (is_nl_fd((int)a[0])) { if (nr == NR_bind) hub_linkreg((int)a[0], (const u8 *)a[1], (u64)a[2]); return 0; }
        if (a[1] && *(u16 *)a[1] == 16) {           // a real netlink socket: binding to groups is not for an app
            long r = S3(nr, a[0], a[1], a[2]);
            return r < 0 && nr == NR_bind ? 0 : r;
        }
        if (nr == NR_connect) { long out; if (hub_maybe_detour((int)a[0], (const u8 *)a[1], (u64)a[2], &out)) return out; }
        { long r = unix_addr(nr, a[0], (const struct sockaddr_un *)a[1], (u64)a[2], PB(0));
          if (nr == NR_bind && r >= 0) sock_note_bind((int)a[0]);
          return r; }
    case NR_getsockname: {
        long r = S3(nr, a[0], a[1], a[2]);
        if (r == 0 && a[1] && a[2] && *(u32 *)a[2] > 2) {
            // a service's socket lives in the run directory as <name>.sock; the guest made it as /dev/socket/<name>
            struct sockaddr_un *sa = (struct sockaddr_un *)a[1];
            u64 l = sa->family == 1 && sa->path[0] ? slen(sa->path) : 0;
            if (l > 5 && seq(sa->path + l - 5, ".sock")) {
                u64 b = l - 5; while (b && sa->path[b - 1] != '/') b--;
                char name[64]; u64 nl = l - 5 - b;
                if (nl < sizeof name) {
                    memcpy(name, sa->path + b, nl); name[nl] = 0;
                    memcpy(sa->path, "/dev/socket/", 12); memcpy(sa->path + 12, name, nl + 1);
                    *(u32 *)a[2] = (u32)(2 + 12 + nl + 1);
                }
            } else if (l) {
                unxl(sa->path);
                *(u32 *)a[2] = (u32)(2 + slen(sa->path) + 1);
            }
        }
        return r;
    }
    case NR_dup3: {
        // zygote's children put /dev/null back over every descriptor that "was /dev/null": the log stays where it is
        if (g_logino && a[0] != a[1]) {
            struct kstat st;
            if (S2(NR_fstat, a[1], &st) == 0 && st.st_ino == g_logino && st.st_dev == g_logdev &&
                S2(NR_fstat, a[0], &st) == 0 && (st.st_mode & 0170000) == 0020000 && st.st_rdev == 0x103) return a[1];
        }
        { long r = S3(nr, a[0], a[1], a[2]); if (r >= 0) { sock_forget((int)a[1]); hub_linkunreg((int)a[1]); } return r; }
    }
    case NR_ptrace: {
        // observed, never mediated (iso-shadow): the request is performed exactly as made, and
        // only the attach family (traceme 0, attach 16, seize 0x4206) is written down, with its
        // result. Until now the call was not trapped at all and went straight to the phone's
        // kernel — a guest process could take over its own child, proot-style, unseen.
        long r = S4(nr, a[0], a[1], a[2], a[3]);
        if (a[0] == 0 || a[0] == 16 || a[0] == 0x4206) iso_shadow_ptrace(a[0], a[1], r);
        return r;
    }
    case NR_close: {
        if (is_nl_fd((int)a[0])) nl_close((int)a[0]);
        if (is_tun_fd((int)a[0])) say("aemu-tun: the framework closed its tun descriptor (the session ends when the channel runs dry)");
        sock_forget((int)a[0]);
        nlreq_drop((int)a[0]);
        hub_linkunreg((int)a[0]);
        return S1(nr, a[0]);
    }
    case NR_execve:
        return do_exec(P(0), (char *const *)a[1], (char *const *)a[2]);
    case NR_ioctl:
        if (is_tun_fd((int)a[0])) return tun_ioctl((int)a[0], (u32)a[1], (u64)a[2]);
        { long so; if (tun_sioc((u32)a[1], (u64)a[2], &so)) return so; }
        if (((a[1] >> 8) & 0xff) == ASHMEM_MAGIC) return ashmem_ioctl((int)a[0], (u32)a[1], (u64)a[2]);
        if (((a[1] >> 8) & 0xff) == 'E' && is_input_fd((int)a[0])) return input_ioctl((u32)a[1], (u64)a[2]);
        if (((u32)a[1] & 0xffffff00u) == 0x4600) return fb_ioctl((u32)a[1], (u64)a[2]);
        if (((a[1] >> 8) & 0xff) == 'b' && is_binder_fd((int)a[0])) return binder_ioctl((int)a[0], (u32)a[1], (u64)a[2]);
        return S3(nr, a[0], a[1], a[2]);
    case NR_mmap:
        if (a[2] == 1 && is_binder_fd((int)a[4])) return binder_mmap((u64)a[0], (u64)a[1], a[3]);
        if (g_is32 && a[2] == 1 && g_props[0] && (int)a[4] >= 0 && !gles2_for_32()) {
            // 32-bit programs only have the GLES 1 renderer: in their (private) view of the property area the
            // "GLES 2 is there" flag reads 0, so their windows are drawn without hwui. Only that page is a copy.
            static struct kstat ps; struct kstat fs;
            if (!ps.st_ino) S4(NR_newfstatat, AT_FDCWD, g_props, &ps, 0);
            if (ps.st_ino && S2(NR_fstat, a[4], &fs) == 0 && fs.st_ino == ps.st_ino && fs.st_dev == ps.st_dev) {
                long r = S6(nr, a[0], a[1], 3, (a[3] & ~1L) | 2, a[4], a[5]);
                if (r < 0 && r > -4096) return S6(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
                static const char *const key[] = { "ro.kernel.qemu.gles", "qemu.gles", 0 };   // libEGL's and hwui's
                char *m = (char *)r; u64 len = (u64)a[1];
                for (int k = 0; key[k]; k++)
                    for (u64 i = 96; i + 24 <= len; i += 4)
                        if (m[i] == key[k][0] && seq(m + i, key[k])) { if ((m[i - 92] == '1' || m[i - 92] == '2') && !m[i - 91]) m[i - 92] = '0'; break; }
                S3(NR_mprotect, r, a[1], 1);
                return r;
            }
        }
        return S6(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
    case NR_mount: case NR_umount2: case NR_chroot: case NR_capset: case NR_setgroups: case NR_unshare: case 268 /* setns */: return 0;
    case NR_prctl: return 0;                    // only PR_CAPBSET_DROP gets here
    case NR_timerfd_create: {                   // the *_ALARM clocks need CAP_WAKE_ALARM, vendor clocks (power-off alarm)
        long r = S2(nr, a[0], a[1]);            // do not exist here: the plain clocks tick the same
        if (r < 0) r = S2(nr, a[0] == 9 ? 7 : 0, a[1]);
        return r;
    }
    case NR_reboot: S1(NR_exit_group, 0); return 0;
    case NR_capget: {
        struct { u32 e, p, i; } *d = (void *)a[1];
        if (d) { d[0].e = d[0].p = 0xffffffff; d[0].i = 0; d[1].e = d[1].p = 0xffffffff; d[1].i = 0; }
        return 0;
    }
    case NR_getuid: case NR_geteuid: return g_uid;
    case NR_getgid: case NR_getegid: return g_gid;
    case NR_setuid: g_uid = (int)a[0]; return 0;
    case NR_setgid: g_gid = (int)a[0]; return 0;
    case NR_setreuid: case NR_setresuid: if ((int)a[1] != -1) g_uid = (int)a[1]; else if ((int)a[0] != -1) g_uid = (int)a[0]; return 0;
    case NR_setregid: case NR_setresgid: if ((int)a[1] != -1) g_gid = (int)a[1]; else if ((int)a[0] != -1) g_gid = (int)a[0]; return 0;
    case NR_setfsuid: return g_uid;
    case NR_setfsgid: return g_gid;
    case NR_uname: {                            // the firmware kernel's identity, not the host's
        if (!g_uts_rel[0] || !a[0]) return S1(NR_uname, a[0]);   // unknown for this image: the host answers
        char *u = (char *)a[0];
        memset(u, 0, 390);
        memcpy(u, "Linux", 6);
        memcpy(u + 65, "localhost", 10);
        memcpy(u + 130, g_uts_rel, slen(g_uts_rel) + 1);
        memcpy(u + 195, g_uts_ver, slen(g_uts_ver) + 1);
        memcpy(u + 260, "aarch64", 8);
        memcpy(u + 325, "(none)", 7);
        return 0;
    }
    case NR_getresuid: for (int i = 0; i < 3; i++) if (a[i]) *(u32 *)a[i] = (u32)g_uid; return 0;
    case NR_getresgid: for (int i = 0; i < 3; i++) if (a[i]) *(u32 *)a[i] = (u32)g_gid; return 0;
    case NR_getgroups: return 0;
    case NR_rt_sigprocmask: {
        // We are inside a signal handler: the mask that counts is the one the kernel restores on return. SIGSYS
        // stays deliverable whatever the guest asks, or the kernel would kill the process on the next trap.
        // The capture-critical fatal set stays deliverable too (mask mediation, above): a fault the guest has
        // blocked would force-kill the process before fatal_relay could name it.
        u64 *mask = (u64 *)((char *)uc + 40), old = *mask;
        if (a[1]) {
            u64 set = *(u64 *)a[1];
            if (a[0] == 0) *mask = old | set; else if (a[0] == 1) *mask = old & ~set; else if (a[0] == 2) *mask = set; else return -EINVAL;
            *mask &= ~(SYSBIT | (1UL << 8) | (1UL << 18));     // and never SIGKILL, SIGSTOP
            mask_mediate(mask);
            if (g_crash) *mask &= ~(1UL << 7);                  // AEMU64_CRASH also keeps SIGFPE deliverable
        }
        if (a[2]) *(u64 *)a[2] = old;
        return 0;
    }
    case NR_socket: {
        // netlink: routing (protocol 0) is the phone's own while it lets an app have it — the kernel answers every
        // request, mostly with a refusal. The rest (uevents, nflog, netfilter, nl80211) is closed to an app: those
        // get a socket that answers by itself.
        long r = a[0] == 16 && a[2] != 0 ? -1 : S3(nr, a[0], a[1], a[2]);
        if (r < 0 && a[0] == 16) {
            int sv[2];
            if (S4(NR_socketpair, 1 /*AF_UNIX*/, 2 /*SOCK_DGRAM*/ | (a[1] & 02004000), 0, (long)sv) == 0) {
                nl_register(sv[0], sv[1], (int)a[2]);
                return sv[0];
            }
        }
        if (r >= 0 && a[0] == 2) sock_track((int)r, (int)a[1]);   // the VPN detour keeps a table of plain IPv4 sockets
        if (r >= 0 && g_is_netd && a[0] == 16 && a[2] == 0) nlreq_track((int)r);   // netd's route sockets: their writes are answered above, when they belong to the VPN flow
        return r;
    }
    case NR_setsockopt: {
        if (is_nl_fd((int)a[0])) return 0;
        long r = S5(nr, a[0], a[1], a[2], a[3], a[4]);
        if (r < 0 && a[1] == 1 && (a[2] == 33 || a[2] == 32)) {    // SO_RCVBUFFORCE / SO_SNDBUFFORCE need CAP_NET_ADMIN
            S5(nr, a[0], 1, a[2] == 33 ? 8 : 7, a[3], a[4]);
            return 0;
        }
        if (r < 0 && a[1] == 1 && a[2] == 36) return 0;             // SO_MARK (netd tags every app socket): CAP_NET_ADMIN too
        return r;
    }
    case NR_getsockopt: {
        if (is_nl_fd((int)a[0])) return 0;
        if (g_vm && a[1] == 1 && a[2] == 31 /* SO_PEERSEC */) return vm_peersec(a[0], (char *)a[3], (u32 *)a[4]);   // Added for AEmulator Plus (2026-11-21): Zygisk, see vmount.h
        long r = S5(nr, a[0], a[1], a[2], a[3], a[4]);
        // SO_PEERCRED: every guest program runs under the app's uid on the phone. The 5.x zygote lets only root and
        // system name the uid of a new process ("App UIDs may not specify uid's or gid's"), so the peer reads as root.
        if (r == 0 && a[1] == 1 && a[2] == 17 && a[3] && a[4] && *(u32 *)a[4] >= 12) {
            u32 *cred = (u32 *)a[3];            // pid, uid, gid
            if (cred[1] == g_real_uid) { cred[1] = 0; cred[2] = 0; }
        }
        return r;
    }
    case NR_sendto: {
        if (is_nl_fd((int)a[0])) {
            nl_handle_send((int)a[0], (const void *)a[1], (u64)a[2]);
            return a[2];
        }
        if (g_is_netd && nlreq_swallow((int)a[0], (const u8 *)a[1], (u64)a[2])) return a[2];
        return S6(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
    }
    case NR_sendmsg: {
        if (is_nl_fd((int)a[0])) {
            struct msgh *m = (struct msgh *)a[1];
            if (m && m->iov && m->iovlen > 0) {
                nl_handle_send((int)a[0], m->iov[0].base, m->iov[0].len);
                u64 total = 0;
                for (u64 i = 0; i < m->iovlen; i++) total += m->iov[i].len;
                return (long)total;
            }
            return 0;
        }
        if (g_is_netd) {
            struct msgh *m = (struct msgh *)a[1];
            if (m && m->iov && m->iovlen == 1 && nlreq_swallow((int)a[0], (const u8 *)m->iov[0].base, m->iov[0].len))
                return (long)m->iov[0].len;
        }
        return S3(nr, a[0], a[1], a[2]);
    }
    case NR_writev: {
        // trapped in netd only (see install_filter_netd): RouteController's writev on one of
        // its route sockets. Gather the iovecs; if the request is the VPN flow's, it is
        // swallowed and its receive gets the fabricated ack. Anything else is the real call.
        if (g_is_netd && a[1] && nlreq_find((int)a[0]) >= 0) {
            struct kiov { void *base; u64 len; } *iov = (void *)a[1];
            u64 total = 0;
            for (u64 i = 0; i < (u64)a[2]; i++) total += iov[i].len;
            if (total && total <= 1024) {
                u8 buf[1024]; u64 done = 0;
                for (u64 i = 0; i < (u64)a[2]; i++) { memcpy(buf + done, iov[i].base, iov[i].len); done += iov[i].len; }
                if (nlreq_swallow((int)a[0], buf, total)) return (long)total;
            }
        }
        if (g_is_netd && a[1] && nlreq_find((int)a[0]) < 0) nlreq_log_untracked((int)a[0], (const void *)a[1], (u64)a[2]);
        return S3(nr, a[0], a[1], a[2]);
    }
    case NR_recvfrom: {
        // the other half of a swallowed route request: hand back the ack (see nlreq_ack).
        // Everything else — including plain receives on the emulated netlink pairs, whose
        // answers already sit in the pair — is the real call.
        if (g_is_netd) {
            long out;
            if (nlreq_ack((int)a[0], (u8 *)a[1], (u64)a[2], (u16 *)a[4], (u32 *)a[5], &out)) { nlreq_log_ack((int)a[0]); return out; }
        }
        return S6(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
    }
    case NR_recvmsg: {
        // a hub-registered netlink listener gets its events with a kernel-shaped envelope
        // (see nl_listener_recv); every other receive is the host's own. This case is only
        // reachable because NR_recvmsg is in the base filter's trap list — it was missing
        // there until the VPN diagnosis batch, which left nl_listener_recv dead code and
        // every injected event to be read plain off the pair, without credentials, to die
        // as EIO in uevent_kernel_recv (see vpn-diag-desk.md).
        long out;
        if (is_nl_fd((int)a[0])) {
            if (nl_listener_recv((int)a[0], (struct msgh *)a[1], &out)) return out;
            nllink_log_notserved((int)a[0]);
        }
        return S3(nr, a[0], a[1], a[2]);
    }
    case NR_rt_sigaction: {
        struct ksa { void *h; u64 flags; void *restorer; u64 mask; } t;
        if (a[0] == SIGSYS) { if (a[2]) memset((void *)a[2], 0, sizeof t); return 0; }
        if (is_fatal(a[0])) {
            struct ksa old; void *prev = (void *)g_fatal[a[0]];
            long r;
            if (a[1]) {
                t = *(struct ksa *)a[1]; t.mask &= ~(SYSBIT | FATALMASK);   // mask mediation: the relay never runs with the capture set blocked
                void *want = t.h;
                if ((u64)want != 1) { t.h = (void *)fatal_relay; t.flags |= 4; if (!want) t.flags &= ~0x80000000UL; }   // not SA_RESETHAND for our own
                r = S4(nr, a[0], &t, &old, a[3]);
                if (r == 0) g_fatal[a[0]] = want;
            } else r = S4(nr, a[0], 0, &old, a[3]);
            if (r == 0 && a[2]) { if (old.h == (void *)fatal_relay) old.h = prev; *(struct ksa *)a[2] = old; }
            return r;
        }
        if (!a[1]) return S4(nr, a[0], 0, a[2], a[3]);
        t = *(struct ksa *)a[1]; t.mask &= ~(SYSBIT | FATALMASK);   // mask mediation, as in the fatal path above
        if (g_is32 && (u64)t.h > 1 && a[0] > 0 && a[0] < 65) {     // qemu's handlers leave through relay32
            void *want = t.h, *prev = (void *)g_h32[a[0]];
            t.h = (void *)relay32; t.flags |= 4;
            struct ksa old;
            long r = S4(nr, a[0], &t, &old, a[3]);
            if (r == 0) g_h32[a[0]] = want;
            if (r == 0 && a[2]) { if (old.h == (void *)relay32) old.h = prev; *(struct ksa *)a[2] = old; }
            return r;
        }
        return S4(nr, a[0], &t, a[2], a[3]);
    }
    }
    return -ENOSYS;
}

// AEMU64_CRASH=1: fatal signals are reported here (with the mapping of pc and lr) instead of the guest's own handlers

static u64 g_libc_base = 0;

static void where(u64 addr) {
    static char buf[1 << 16];
    long fd = S4(NR_openat, AT_FDCWD, "/proc/self/maps", 0, 0);
    if (fd < 0) return;
    u64 keep = 0; long n;
    while ((n = S3(NR_read, fd, buf + keep, sizeof buf - 1 - keep)) > 0) {
        u64 len = keep + (u64)n; buf[len] = 0;
        char *l = buf;
        for (;;) {
            char *e = l; while (*e && *e != '\n') e++;
            if (!*e) break;
            *e = 0;
            u64 lo = 0, hi = 0; char *q = l;
            for (; *q && *q != '-'; q++) lo = lo * 16 + (u64)(*q <= '9' ? *q - '0' : *q - 'a' + 10);
            if (*q) for (q++; *q && *q != ' '; q++) hi = hi * 16 + (u64)(*q <= '9' ? *q - '0' : *q - 'a' + 10);
            if (addr >= lo && addr < hi) {
                if (!g_libc_base) {
                    for (const char *p = l; *p; p++) {
                        if (pre(p, "/libc.so")) { g_libc_base = lo; break; }
                    }
                }
                say("    "); say(l); say("  +"); hex(addr - lo); say("\n"); S1(NR_close, fd); return;
            }
            l = e + 1;
        }
        keep = slen(l); memmove(buf, l, keep);
    }
    S1(NR_close, fd);
}

// ---------------------------------------------------------------------------------------------------- crash capture
//
// 137 naming: when system_server died of SIGSEGV in the field the guest wrote no tombstone and this
// image printed nothing — the death only showed in the guest log as zygote's "exited due to signal".
// The relay below sees every fatal signal first, so the naming record is taken here. A delivery only
// stashes the context (a few stores, no I/O, nothing on the syscall path); one structured aemu-crash
// block is printed when the signal proves fatal for this process: there is no guest handler
// (on_crash's path, which calls cc_emit itself), or the same fault is delivered again on the same
// thread after the guest's handler returned without handling it. Observation only — the relay then
// does exactly what it did before. Only SIGSEGV and SIGABRT are captured; the other fatal signals
// keep the old behaviour untouched. If a field cannot be read the block still prints, naming the gap.

static volatile int g_cc_done;              // the block for this process is already out
static volatile int g_cc_pend;              // a delivery is stashed in the fields below
static int g_cc_sig;
static long g_cc_tid;
static u64 g_cc_pc, g_cc_fault;

static char *cc_hex(u64 v, char *end) { *--end = 0; do *--end = "0123456789abcdef"[v & 15]; while (v >>= 4); *--end = 'x'; *--end = '0'; return end; }
static u64 cc_hv(char c) { return (u64)(c <= '9' ? c - '0' : c - 'a' + 10); }

// addr -> "<map path>+0x<offset in the file>" from this process's own maps, in the style of where()
// but written into out; bounded (streamed like where(), at most 8192 lines). out always ends up as
// the resolution, "unmapped" (maps read, addr in no segment) or "no-maps" (maps could not be read).
static void cc_mod(u64 addr, char *out, u64 cap) {
    static char buf[1 << 16];
    const char *miss = "unmapped";
    long fd = S4(NR_openat, AT_FDCWD, "/proc/self/maps", 0, 0);
    if (fd < 0) miss = "no-maps";
    u64 keep = 0; long n; int lines = 0, found = 0;
    while (!found && fd >= 0 && lines < 8192 && (n = S3(NR_read, fd, buf + keep, sizeof buf - 1 - keep)) > 0) {
        u64 len = keep + (u64)n; buf[len] = 0;
        char *l = buf;
        for (;;) {
            char *e = l; while (*e && *e != '\n') e++;
            if (!*e) break;
            *e = 0; lines++;
            u64 lo = 0, hi = 0; char *q = l;
            for (; *q && *q != '-'; q++) lo = lo * 16 + cc_hv(*q);
            if (*q) for (q++; *q && *q != ' '; q++) hi = hi * 16 + cc_hv(*q);
            if (addr >= lo && addr < hi) {
                u64 off = 0;
                while (*q == ' ') q++; while (*q && *q != ' ') q++;          // perms
                while (*q == ' ') q++;
                for (; *q && *q != ' '; q++) off = off * 16 + cc_hv(*q);    // file offset
                for (int t = 0; t < 2; t++) { while (*q == ' ') q++; while (*q && *q != ' ') q++; }   // dev, inode
                while (*q == ' ') q++;
                const char *path = *q ? q : "[anon]";
                u64 o = 0;
                for (const char *p = path; *p && o + 1 < cap; p++) out[o++] = *p;
                char hb[24]; const char *hs = cc_hex(addr - lo + off, hb + sizeof hb);
                for (const char *p = "+"; *p && o + 1 < cap; p++) out[o++] = *p;
                for (const char *p = hs; *p && o + 1 < cap; p++) out[o++] = *p;
                out[o] = 0; found = 1;
            }
            l = e + 1;
            if (found || lines >= 8192) break;
        }
        keep = slen(l); memmove(buf, l, keep);
    }
    if (fd >= 0) S1(NR_close, fd);
    if (!found) { u64 o = 0; while (miss[o] && o + 1 < cap) { out[o] = miss[o]; o++; } out[o] = 0; }
}

static void cc_emit(int sig, void *info, void *uc) {
    if (__atomic_exchange_n(&g_cc_done, 1, __ATOMIC_ACQUIRE)) return;
    struct sigctx *c = (struct sigctx *)((char *)uc + 176);
    u64 fault = *(u64 *)((char *)info + 16);                    // siginfo's si_addr, as in on_crash
    int ownfd = -1;
    if (g_sayfd < 0) {                                          // land in <root>/dev/aemu_crash.log too, as on_crash does
        static char lp[600];
        memcpy(lp, g_root, g_rootlen); memcpy(lp + g_rootlen, "/dev/aemu_crash.log", 20);
        ownfd = (int)S4(NR_openat, AT_FDCWD, lp, 02101 /*O_WRONLY|O_CREAT|O_APPEND*/, 0600);
        if (ownfd >= 0) g_sayfd = ownfd;
    }
    static char pcmod[PATH_MAX + 32], lrmod[PATH_MAX + 32];
    cc_mod(c->pc, pcmod, sizeof pcmod);
    cc_mod(c->regs[30], lrmod, sizeof lrmod);
    char t[24];
    say("aemu-crash: pid="); say(utoa((u64)S0(NR_getpid), t + sizeof t));
    say(" comm=");
    { char nm[20]; memset(nm, 0, sizeof nm); S5(NR_prctl, 16 /*PR_GET_NAME*/, nm, 0, 0, 0); say(nm[0] ? nm : g_exe); }
    say(" sig="); say(utoa((u64)sig, t + sizeof t));
    say(" fault="); hex(fault);
    say(" pc="); hex(c->pc); say(" ("); say(pcmod); say(")");
    say(" lr="); hex(c->regs[30]); say(" ("); say(lrmod); say(")");
    say(" sp="); hex(c->sp);
    say("\n");
    if (ownfd >= 0) { S1(NR_close, ownfd); g_sayfd = -1; }
}

// A fatal delivery, seen by the relay: stash it, and if the identical fault comes straight back on
// the same thread, the guest's handler did not handle it — this delivery is the death, so name it.
static void cc_note(int sig, void *info, void *uc) {
    struct sigctx *c = (struct sigctx *)((char *)uc + 176);
    u64 fault = *(u64 *)((char *)info + 16);
    long tid = S0(NR_gettid);
    if (g_cc_pend && g_cc_sig == sig && g_cc_tid == tid && g_cc_pc == c->pc && g_cc_fault == fault)
        cc_emit(sig, info, uc);
    g_cc_pend = 1; g_cc_sig = sig; g_cc_tid = tid; g_cc_pc = c->pc; g_cc_fault = fault;
}

static void on_crash(int sig, void *info, void *uc) {
    struct { u64 fault; u64 regs[31]; u64 sp, pc, pstate; } *c = (void *)((char *)uc + 176);
    char t[24];
    static volatile int busy;                   // one report at a time; the others wait their turn
    while (__atomic_exchange_n(&busy, 1, __ATOMIC_ACQUIRE)) S0(NR_sched_yield);
    {
        static char lp[600];
        memcpy(lp, g_root, g_rootlen); memcpy(lp + g_rootlen, "/dev/aemu_crash.log", 20);
        g_sayfd = (int)S4(NR_openat, AT_FDCWD, lp, 02101 /*O_WRONLY|O_CREAT|O_APPEND*/, 0600);
    }
    if (sig == 11 || sig == 6) cc_emit(sig, info, uc);          // the structured naming line heads the report
    say("aemu64: fatal signal "); say(utoa((u64)sig, t + sizeof t)); say(" in "); say(g_exe);
    { char nm[20]; memset(nm, 0, sizeof nm); S5(NR_prctl, 16 /*PR_GET_NAME*/, nm, 0, 0, 0); say(" ["); say(nm); say("]"); }
    say("\n  pc "); hex(c->pc); say("  lr "); hex(c->regs[30]); say("  addr "); hex(*(u64 *)((char *)info + 16)); say("\n");
    where(c->pc); where(c->regs[30]);
    if (g_libc_base) {
        u64 abort_msg = 0;
        struct { void *b; u64 n; } l = { &abort_msg, sizeof abort_msg }, r = { (void *)(g_libc_base + 0xcd060), sizeof abort_msg };
        if (S6(270 /*process_vm_readv*/, S0(172), &l, 1, &r, 1, 0) == sizeof abort_msg && abort_msg > 0x10000) {
            char msg[512];
            struct { void *b; u64 n; } lm = { msg, sizeof msg - 1 }, rm = { (void *)(abort_msg + 8), sizeof msg - 1 };
            long nr = S6(270, S0(172), &lm, 1, &rm, 1, 0);
            if (nr > 0) {
                msg[nr] = 0;
                say("  abort message: "); say(msg); say("\n");
            }
        }
    }
    for (int i = 0; i < 8; i++) { say("  x"); say(utoa((u64)i, t + sizeof t)); say(" "); hex(c->regs[i]); if (i % 4 == 3) say("\n"); }
    say("  pid "); say(utoa((u64)S0(172), t + sizeof t)); say("  registers and what they point to:\n");
    for (int i = 0; i < 29; i++) {
        u64 w[4] = { 0, 0, 0, 0 };
        struct { void *b; u64 n; } l = { w, sizeof w }, r = { (void *)c->regs[i], sizeof w };
        say("  x"); say(utoa((u64)i, t + sizeof t)); say(" "); hex(c->regs[i]);
        if (c->regs[i] > 0x10000 && S6(270 /*process_vm_readv*/, S0(172), &l, 1, &r, 1, 0) == (long)sizeof w) {
            say(" ->"); for (int k = 0; k < 4; k++) { say(" "); hex(w[k]); }
        }
        say("\n");
    }
    say("  sp "); hex(c->sp); say("  stack words:\n");
    for (int i = 0; i < 32; i++) {
        u64 w = 0;
        struct { void *b; u64 n; } l = { &w, sizeof w }, r = { (void *)(c->sp + i * 8), sizeof w };
        if (S6(270, S0(172), &l, 1, &r, 1, 0) == sizeof w) {
            say("    [sp+"); say(utoa((u64)(i * 8), t + sizeof t)); say("] "); hex(w);
            if (w > 0x10000) {
                char str[80];
                struct { void *b; u64 n; } ls = { str, sizeof str - 1 }, rs = { (void *)w, sizeof str - 1 };
                long sn = S6(270, S0(172), &ls, 1, &rs, 1, 0);
                if (sn > 3) {
                    str[sn] = 0;
                    int printable = 1;
                    for (int j = 0; j < sn && str[j]; j++) {
                        if ((unsigned char)str[j] < 32 || (unsigned char)str[j] > 126) { printable = 0; break; }
                    }
                    if (printable && str[0]) { say(" \""); say(str); say("\""); }
                }
            }
            say("\n");
        }
    }
    say("  backtrace:\n");
    u64 fp = c->regs[29];
    for (int i = 0; i < 24 && fp > 0x10000 && !(fp & 7); i++) {
        u64 lr = ((u64 *)fp)[1]; u64 nfp = ((u64 *)fp)[0];
        where(lr);
        if (nfp <= fp) break;
        fp = nfp;
    }
    S1(NR_exit_group, 128 + sig);
}

// Fatal signals: the kernel always calls fatal_relay; the guest's own handler (ART's fault handler, the linker's
// debuggerd hook) runs from it with the flags and mask it asked for. Only when the guest has nothing left
// (SIG_DFL) the crash is reported here, on the program's stderr, with the mappings of pc and the callers.
static void fatal_relay(int sig, void *info, void *uc) {
    if (sig == 11 || sig == 6) cc_note(sig, info, uc);          // crash capture: stash (and name a repeat fault)
    if (g_crash) {
        on_crash(sig, info, uc);
        return;
    }
    void (*h)(int, void *, void *) = g_fatal[sig & 31];
    if ((u64)h > 1) h(sig, info, uc); else on_crash(sig, info, uc);
    if (g_is32) *(u64 *)((char *)uc + 40) &= ~SYSBIT;
}

static void on_sigsys(int sig, void *info, void *uc) {
    struct sigctx *c = (struct sigctx *)((char *)uc + 176);
    (void)sig; (void)info;
    mask_mediate((u64 *)((char *)uc + 40));                     // mask mediation: the restored mask never blocks the capture-critical set
    if (g_crash) *(u64 *)((char *)uc + 40) &= ~(1UL << 7);      // AEMU64_CRASH also keeps SIGFPE deliverable
    c->regs[0] = (u64)trap((long)c->regs[8], (long *)c->regs, uc);
}

// ---------------------------------------------------------------------------------------------------- filter

struct bpf { u16 code; u8 jt, jf; u32 k; };
#define LD(off) (struct bpf){ 0x20, 0, 0, off }
#define JEQ(k, t, f) (struct bpf){ 0x15, t, f, k }
#define JGE(k, t, f) (struct bpf){ 0x35, t, f, k }
#define RET(k) (struct bpf){ 0x06, 0, 0, k }
#define ALLOW 0x7fff0000u
#define TRAP 0x00030000u

static const u8 trapped[] = {
    NR_setxattr, NR_lsetxattr, NR_getxattr, NR_lgetxattr, NR_listxattr, NR_llistxattr, NR_removexattr, NR_lremovexattr,
    NR_getcwd, NR_inotify_add_watch, NR_ioctl, NR_mknodat, NR_mkdirat, NR_unlinkat, NR_symlinkat, NR_linkat,
    NR_renameat, NR_umount2, NR_mount, NR_statfs, NR_truncate, NR_faccessat, NR_chdir, NR_chroot, NR_fchmodat,
    NR_fchownat, NR_fchown, NR_openat, NR_readlinkat, NR_newfstatat, NR_fstat, NR_utimensat, NR_capget, NR_capset,
    NR_rt_sigaction, NR_reboot, NR_setregid, NR_setgid, NR_setreuid, NR_setuid, NR_setresuid, NR_getresuid,
    NR_setresgid, NR_getresgid, NR_setfsuid, NR_setfsgid, NR_getgroups, NR_setgroups, NR_getuid, NR_geteuid,
    NR_getgid, NR_getegid, NR_bind, NR_connect, NR_execve, NR_setsockopt, NR_unshare, NR_timerfd_create,
    NR_close, NR_getsockopt, NR_sendto, NR_sendmsg, NR_recvmsg, NR_getsockname, NR_socket, NR_dup3, NR_ptrace,
};

enum { L_TRAP = 240, L_ALLOW, L_SIG, L_MMAP, L_NR, L_PRCTL, L_N };

static void install_filter(void) {
    static struct bpf f[160];
    int n = 0, pos[L_N - 240];
#define AT(l) pos[(l) - 240] = n
    f[n++] = LD(4);  f[n++] = JEQ(0xC00000B7u, 0, L_ALLOW);
    f[n++] = LD(12); f[n++] = JEQ(IMAGE_HI, 0, L_NR);          // calls made from this image pass
    f[n++] = LD(8);  f[n++] = JGE(IMAGE_SPAN, L_NR, L_ALLOW);
    AT(L_NR); f[n++] = LD(0);
    for (u64 i = 0; i < sizeof trapped; i++) f[n++] = JEQ(trapped[i], L_TRAP, 0);
    f[n++] = JEQ(NR_renameat2, L_TRAP, 0);
    f[n++] = JEQ(NR_statx, L_TRAP, 0);
    f[n++] = JEQ(NR_uname, L_TRAP, 0);
    if (g_vm) f[n++] = JEQ(268 /* setns */, L_TRAP, 0);
    // Added for AEmulator Plus (2026-11-21): directory reads, to show a device node (a whiteout) as one (vmount.h)
    if (g_vm) f[n++] = JEQ(61 /* getdents64 */, L_TRAP, 0);
    f[n++] = JEQ(NR_rt_sigprocmask, L_SIG, 0);
    f[n++] = JEQ(NR_prctl, L_PRCTL, 0);
    f[n++] = JEQ(NR_mmap, L_MMAP, L_ALLOW);
    AT(L_PRCTL);                                                // PR_CAPBSET_DROP (24), PR_SET_SECCOMP (22), PR_SET_KEEPCAPS (8), PR_CAP_AMBIENT (47)
    f[n++] = LD(16); f[n++] = JEQ(24, L_TRAP, 0);
    f[n++] = JEQ(22, L_TRAP, 0);
    f[n++] = JEQ(8, L_TRAP, 0);
    f[n++] = JEQ(47, L_TRAP, L_ALLOW);
    AT(L_SIG);                                                  // only when a set is given and it is not SIG_UNBLOCK
    f[n++] = LD(16); f[n++] = JEQ(1, L_ALLOW, 0);
    f[n++] = LD(24); f[n++] = JEQ(0, 0, L_TRAP);
    f[n++] = LD(28); f[n++] = JEQ(0, L_ALLOW, L_TRAP);
    AT(L_MMAP);                                                 // read-only file mappings: the binder buffer is one
    f[n++] = LD(32); f[n++] = JEQ(1, 0, L_ALLOW);
    f[n++] = LD(48); f[n++] = JEQ(0xffffffffu, L_ALLOW, L_TRAP);
    AT(L_TRAP); f[n++] = RET(TRAP);
    AT(L_ALLOW); f[n++] = RET(ALLOW);
#undef AT
    for (int i = 0; i < n; i++) {
        if ((f[i].code & 7) != 5) continue;                     // jumps only
        if (f[i].jt >= 240) f[i].jt = (u8)(pos[f[i].jt - 240] - i - 1);
        if (f[i].jf >= 240) f[i].jf = (u8)(pos[f[i].jf - 240] - i - 1);
    }
    struct { u16 len; struct bpf *f; } prog = { (u16)n, f };
    S5(NR_prctl, 38 /*PR_SET_NO_NEW_PRIVS*/, 1, 0, 0, 0);
    if (S3(NR_prctl, 22 /*PR_SET_SECCOMP*/, 2, &prog) < 0) die("seccomp filter refused", "");
}

// netd alone takes two more traps — the writev and recvfrom of RouteController's route
// transactions (see the nlreq section) — so its loader stacks this small filter on top of
// the inherited one. The prologue is the base filter's own: calls from this image pass.
// No other guest process pays for these two traps; a forked child of netd that execs
// something else keeps the filter but its handler passes every such call straight through
// (it tracks no route sockets), which is the shape the base filter already lives with.
static void install_filter_netd(void) {
    static struct bpf f[16];
    int n = 0, pos[L_N - 240];
#define AT(l) pos[(l) - 240] = n
    f[n++] = LD(4);  f[n++] = JEQ(0xC00000B7u, 0, L_ALLOW);
    f[n++] = LD(12); f[n++] = JEQ(IMAGE_HI, 0, L_NR);
    f[n++] = LD(8);  f[n++] = JGE(IMAGE_SPAN, L_NR, L_ALLOW);
    AT(L_NR); f[n++] = LD(0);
    f[n++] = JEQ(NR_writev, L_TRAP, 0);
    f[n++] = JEQ(NR_recvfrom, L_TRAP, L_ALLOW);
    AT(L_TRAP); f[n++] = RET(TRAP);
    AT(L_ALLOW); f[n++] = RET(ALLOW);
#undef AT
    for (int i = 0; i < n; i++) {
        if ((f[i].code & 7) != 5) continue;
        if (f[i].jt >= 240) f[i].jt = (u8)(pos[f[i].jt - 240] - i - 1);
        if (f[i].jf >= 240) f[i].jf = (u8)(pos[f[i].jf - 240] - i - 1);
    }
    struct { u16 len; struct bpf *f; } prog = { (u16)n, f };
    S5(NR_prctl, 38 /*PR_SET_NO_NEW_PRIVS*/, 1, 0, 0, 0);
    if (S3(NR_prctl, 22 /*PR_SET_SECCOMP*/, 2, &prog) < 0) die("netd filter refused", "");
}

// ---------------------------------------------------------------------------------------------------- loader

struct ehdr { u8 ident[16]; u16 type, machine; u32 version; u64 entry, phoff, shoff; u32 flags; u16 ehsize, phentsize, phnum, shentsize, shnum, shstrndx; };
struct phdr { u32 type, flags; u64 offset, vaddr, paddr, filesz, memsz, align; };
struct image { u64 bias, entry, phdr; u16 phnum; char interp[256]; };

#define PG 4096UL
#define DN(x) ((x) & ~(PG - 1))
#define UP(x) DN((x) + PG - 1)

static int load_elf(const char *host, struct image *im) {
    long fd = S4(NR_openat, AT_FDCWD, host, 0x80000 /*O_CLOEXEC*/, 0);
    if (fd < 0) return (int)fd;
    struct ehdr eh; struct phdr ph[32];
    if (S4(NR_pread64, fd, &eh, sizeof eh, 0) != sizeof eh || eh.ident[0] != 0x7f || eh.ident[1] != 'E' ||
        eh.ident[4] != 2 || eh.machine != 183 || eh.phnum > 32) { S1(NR_close, fd); return -ENOEXEC; }
    if (S4(NR_pread64, fd, ph, eh.phnum * sizeof ph[0], eh.phoff) != (long)(eh.phnum * sizeof ph[0])) { S1(NR_close, fd); return -ENOEXEC; }
    u64 lo = ~0UL, hi = 0;
    im->interp[0] = 0; im->phdr = 0;
    for (int i = 0; i < eh.phnum; i++) {
        if (ph[i].type == 1) { if (ph[i].vaddr < lo) lo = ph[i].vaddr; if (ph[i].vaddr + ph[i].memsz > hi) hi = ph[i].vaddr + ph[i].memsz; }
        if (ph[i].type == 3 && ph[i].filesz < sizeof im->interp) { S4(NR_pread64, fd, im->interp, ph[i].filesz, ph[i].offset); im->interp[ph[i].filesz] = 0; }
    }
    lo = DN(lo); hi = UP(hi);
    u64 base = (u64)S6(NR_mmap, eh.type == 3 ? 0 : lo, hi - lo, 0, 0x22 | (eh.type == 3 ? 0 : 0x10), -1, 0);
    if (base >= (u64)-4095) { S1(NR_close, fd); return -ENOMEM; }
    u64 bias = base - lo;
    for (int i = 0; i < eh.phnum; i++) {
        if (ph[i].type != 1) continue;
        int prot = ((ph[i].flags & 4) ? 1 : 0) | ((ph[i].flags & 2) ? 2 : 0) | ((ph[i].flags & 1) ? 4 : 0);
        u64 s = DN(ph[i].vaddr) + bias, fe = ph[i].vaddr + ph[i].filesz + bias, me = UP(ph[i].vaddr + ph[i].memsz + bias);
        if (ph[i].filesz) {
            u64 r = (u64)S6(NR_mmap, s, UP(fe) - s, prot, 0x12 /*PRIVATE|FIXED*/, fd, DN(ph[i].offset));
            if (r >= (u64)-4095) { S1(NR_close, fd); return (int)r; }
        }
        if (ph[i].memsz > ph[i].filesz) {
            if ((prot & 2) && UP(fe) > fe) memset((void *)fe, 0, UP(fe) - fe);
            if (me > UP(fe)) S6(NR_mmap, UP(fe), me - UP(fe), prot, 0x32, -1, 0);
        }
        if (ph[i].offset <= eh.phoff && eh.phoff < ph[i].offset + ph[i].filesz) im->phdr = ph[i].vaddr + (eh.phoff - ph[i].offset) + bias;
    }
    S1(NR_close, fd);
    im->bias = bias; im->entry = eh.entry + bias; im->phnum = eh.phnum;
    return 0;
}

static const char *env_get(char **envp, const char *k) {
    u64 n = slen(k);
    for (; *envp; envp++) if (pre(*envp, k) && (*envp)[n] == '=') return *envp + n + 1;
    return 0;
}

void aemu_main(u64 *sp) {
    long argc = (long)sp[0];
    char **argv = (char **)(sp + 1), **envp = argv + argc + 1;
    u64 *auxv; { char **e = envp; while (*e) e++; auxv = (u64 *)(e + 1); }
    if (argc < 3) die("usage: aemu64 <argv0> <guest program> [args…]", "");

    const char *r = env_get(envp, "AEMU64_ROOT");
    if (!r || !r[0] || slen(r) >= sizeof g_root) die("AEMU64_ROOT is not set", "");
    memcpy(g_root, r, slen(r) + 1); g_rootlen = slen(g_root);
    vm_init();                                                   // (vmount.h)
    if ((r = env_get(envp, "AEMU64_PROPS")) && slen(r) < sizeof g_props) memcpy(g_props, r, slen(r) + 1);
    if ((r = env_get(envp, "AEMU64_UTS_RELEASE")) && slen(r) < sizeof g_uts_rel) memcpy(g_uts_rel, r, slen(r) + 1);
    if ((r = env_get(envp, "AEMU64_UTS_VERSION")) && slen(r) < sizeof g_uts_ver) memcpy(g_uts_ver, r, slen(r) + 1);
    g_crash = env_get(envp, "AEMU64_CRASH") != 0;
    if ((r = env_get(envp, "AEMU64_INPUT")) && slen(r) < sizeof g_input) memcpy(g_input, r, slen(r) + 1);
    if ((r = env_get(envp, "AEMU64_SDCARD")) && slen(r) < sizeof g_sd) { g_sdlen = slen(r); memcpy(g_sd, r, g_sdlen + 1); }
    if ((r = env_get(envp, "AEMU64_QEMU")) && slen(r) < sizeof g_qemu) memcpy(g_qemu, r, slen(r) + 1);
    if ((r = env_get(envp, "AEMU64_BINDER")) && slen(r) < sizeof g_bsock) memcpy(g_bsock, r, slen(r) + 1);
    if ((r = env_get(envp, "AEMU64_HWBINDER")) && slen(r) < sizeof g_hwbsock) memcpy(g_hwbsock, r, slen(r) + 1);
    if ((r = env_get(envp, "AEMU64_VNDBINDER")) && slen(r) < sizeof g_vndbsock) memcpy(g_vndbsock, r, slen(r) + 1);
    if ((r = env_get(envp, "AEMU64_ID"))) { g_uid = (int)atou(r); while (*r && *r != ',') r++; if (*r) g_gid = (int)atou(r + 1); }
    long n = S4(NR_readlinkat, AT_FDCWD, "/proc/self/exe", g_self, sizeof g_self - 1);
    if (n <= 0) die("cannot find myself", "");
    g_self[n] = 0;
    g_real_uid = (u32)S0(NR_getuid);
    { struct kstat st; if (S2(NR_fstat, 2, &st) == 0 && ((st.st_mode & 0170000) == 0100000 || (st.st_mode & 0170000) == 0010000)) {
        g_logdev = st.st_dev; g_logino = st.st_ino; g_logpipe = (st.st_mode & 0170000) == 0010000; }
      if (g_logpipe && S2(NR_fstat, 0, &st) == 0 && (st.st_mode & 0170000) == 0010000 && st.st_dev == g_logdev) g_inino = st.st_ino; }

    // the program: a host path under the root (from our own exec) or a guest path
    char pbuf[PATH_MAX]; int err = 0;
    const char *prog = xl(argv[2], pbuf, &err);
    if (!prog) die("bad path ", argv[2]);

    // "#!interpreter [arg]": run the interpreter on the script
    char head[256];
    long fd = S4(NR_openat, AT_FDCWD, prog, 0x80000, 0);
    if (fd < 0) die("cannot open ", argv[2]);
    long hn = S3(NR_read, fd, head, sizeof head - 1);
    S1(NR_close, fd);
    static char *nargv[4100];
    char **gargv = argv + 2; long gargc = argc - 2;
    const char *argv0 = argv[1];
    if (hn > 2 && head[0] == '#' && head[1] == '!') {
        head[hn] = 0;
        char *p = head + 2, *arg = 0; while (*p == ' ') p++;
        char *interp = p; while (*p && *p != ' ' && *p != '\n') p++;
        if (*p == ' ') { *p++ = 0; while (*p == ' ') p++; if (*p && *p != '\n') { arg = p; while (*p && *p != '\n') p++; } }
        *p = 0;
        static char ibuf[PATH_MAX], script[PATH_MAX];
        memcpy(script, argv[2], slen(argv[2]) + 1); unxl(script);
        long k = 0;
        nargv[k++] = interp; if (arg) nargv[k++] = arg; nargv[k++] = script;
        for (long i = 3; i < argc && k < 4096; i++) nargv[k++] = argv[i];
        nargv[k] = 0; gargv = nargv; gargc = k; argv0 = interp;
        static char ihold[256]; memcpy(ihold, interp, slen(interp) + 1); nargv[0] = ihold;
        if (arg) { static char ahold[256]; memcpy(ahold, arg, slen(arg) + 1); nargv[1] = ahold; }
        argv0 = ihold;
        if (!(prog = xl(ihold, ibuf, &err))) die("bad interpreter ", ihold);
        memcpy(g_exe, ihold, slen(ihold) + 1);
    } else {
        u64 l = slen(prog); if (l >= sizeof g_exe) l = sizeof g_exe - 1;
        memcpy(g_exe, prog, l); g_exe[l] = 0; unxl(g_exe);
    }

    vm_exe_put();    // Added for AEmulator Plus (2026-11-21): /proc/<pid>/exe of this process for the others (vmount.h)
    // sockets init would have made for the service: AEMU64_SOCK_<name>=<host path>,<octal mode>
    static char sockenv[12][80]; int nsock = 0;
    for (char **e = envp; *e; e++) {
        if (!pre(*e, "AEMU64_SOCK_") || nsock >= 12) continue;
        const char *name = *e + 12, *eq = name; while (*eq && *eq != '=') eq++;
        if (!*eq) continue;
        struct sockaddr_un sa; sa.family = 1;
        u64 pl = 0; const char *v = eq + 1; while (v[pl] && v[pl] != ',' && pl < 107) { sa.path[pl] = v[pl]; pl++; }
        sa.path[pl] = 0;
        u64 mode = 0; if (v[pl] == ',') for (const char *m = v + pl + 1; *m >= '0' && *m <= '7'; m++) mode = mode * 8 + (u64)(*m - '0');
        long s = S3(NR_socket, 1, 1 /*SOCK_STREAM*/, 0);
        if (s < 0) continue;
        S3(NR_unlinkat, AT_FDCWD, sa.path, 0);
        if (S3(NR_bind, s, &sa, 2 + pl + 1) < 0) { S1(NR_close, s); continue; }
        S4(NR_fchmodat, AT_FDCWD, sa.path, mode ? mode : 0660, 0);
        char *o = sockenv[nsock++]; u64 nl = (u64)(eq - name); if (nl > 40) nl = 40;
        memcpy(o, "ANDROID_SOCKET_", 15); memcpy(o + 15, name, nl); o[15 + nl] = '=';
        char t[24], *d = utoa((u64)s, t + sizeof t); memcpy(o + 16 + nl, d, slen(d) + 1);
    }

    // routing and firewall tools talk netlink to a kernel that will not answer an app: they "succeed" at once
    {
        const char *b = g_exe, *q;
        for (q = g_exe; *q; q++) if (*q == '/') b = q + 1;
        static const char *const mute[] = { "ip", "tc", "iptables", "ip6tables", "iptables-restore", "ip6tables-restore", "ndppd", 0 };
        for (int i = 0; mute[i]; i++) if (seq(b, mute[i])) S1(NR_exit_group, 0);
    }
    struct image exe, ld;
    int is32 = 0;
    if (gargv != nargv) {
        long f2 = S4(NR_openat, AT_FDCWD, prog, 0x80000, 0);
        if (f2 >= 0) { hn = S3(NR_read, f2, head, 8); S1(NR_close, f2); }
    } else {                                    // a script: what matters is its interpreter
        long f2 = S4(NR_openat, AT_FDCWD, prog, 0x80000, 0);
        hn = f2 >= 0 ? S3(NR_read, f2, head, 8) : 0;
        if (f2 >= 0) S1(NR_close, f2);
    }
    if (hn > 5 && head[0] == 0x7f && head[4] == 1) {
        struct kstat st;
        if (S4(NR_newfstatat, AT_FDCWD, prog, &st, 0) == 0 && st.st_size < 1024) S1(NR_exit_group, 0);   // the engine's "true"
        if (!g_qemu[0]) die("32-bit program and no translator: ", prog);
        // a 32-bit program of a 64-bit firmware: qemu-arm translates the code, and its system calls come back
        // through the same filter. Guest addresses are host addresses (-B 0), so binder and ashmem work unchanged.
        static char *qargv[4100]; long k = 0;
        static char progcopy[PATH_MAX];
        memcpy(progcopy, prog, slen(prog) + 1);
        qargv[k++] = g_qemu; qargv[k++] = "-B"; qargv[k++] = "0"; qargv[k++] = "-0"; qargv[k++] = (char *)argv0; qargv[k++] = progcopy;
        for (long i = 1; i < gargc && k < 4096; i++) qargv[k++] = gargv[i];
        qargv[k] = 0; gargv = qargv; gargc = k; argv0 = g_qemu; prog = g_qemu; is32 = 1; g_is32 = 1;
    }
    // each world has its own libraries
    static char ldpath[64];
    memcpy(ldpath, is32 ? "LD_LIBRARY_PATH=/vendor/lib:/system/lib" : "LD_LIBRARY_PATH=/vendor/lib64:/system/lib64", is32 ? 40 : 44);
    for (char **e = envp; *e; e++) if (pre(*e, "LD_LIBRARY_PATH=")) *e = ldpath;
    if ((err = load_elf(prog, &exe))) die("cannot load ", prog);
    u64 entry = exe.entry, base = 0;
    if (exe.interp[0]) {
        char ib[PATH_MAX];
        const char *ih = xl(exe.interp, ib, &err);
        if (!ih || (err = load_elf(ih, &ld))) die("cannot load the linker ", exe.interp);
        entry = ld.entry; base = ld.bias;
    }

    // this process's own name, for the one process whose route transactions are answered
    // in the supervisor (netd; see the nlreq section and install_filter_netd)
    { const char *base = prog; for (const char *p = prog; *p; p++) if (*p == '/') base = p + 1; g_is_netd = seq(base, "netd"); }

    // signal handler first, then the filter (once per process tree: it survives exec)
    struct { void *h; u64 flags; void *restorer; u64 mask; } sa = { (void *)on_sigsys, 4 /*SA_SIGINFO*/ | 0x40000000 /*SA_NODEFER*/, 0, 0 };
    if (S4(NR_rt_sigaction, SIGSYS, &sa, 0, 8) < 0) die("sigaction", "");
    u64 unblock = SYSBIT | FATALMASK; S4(NR_rt_sigprocmask, 1, &unblock, 0, 8);   // deliverable from the first guest instruction (mask mediation)
    {
        struct { void *h; u64 flags; void *restorer; u64 mask; } ca = { (void *)fatal_relay, 4 /*SA_SIGINFO*/ | 0x40000000 /*SA_NODEFER*/, 0, 0 };
        static const int fatal[] = { 11, 7, 4, 6, 8 };
        for (int i = 0; i < 5; i++) S4(NR_rt_sigaction, fatal[i], &ca, 0, 8);
    }
    if (!env_get(envp, "AEMU64_F")) install_filter();
    if (g_is_netd) install_filter_netd();
    if (g_is_netd) say("aemu-nlreq: netd process: route-request filter installed\n");

    // the guest's initial stack: argc, argv, envp (without our variables), auxv
    u64 ne = 0; while (envp[ne]) ne++;
    u64 na = 0; while (auxv[na * 2]) na++;
    u64 words = 1 + (u64)gargc + 1 + ne + 12 + 1 + (na + 1) * 2;
    // built on the real stack, well below this frame (bionic and ART find the main stack by its mapping)
    u64 *w = (u64 *)(((u64)&words - (256UL << 10) - words * 8) & ~15UL), *top = w;
    *w++ = (u64)gargc;
    *w++ = (u64)argv0;
    for (long i = 1; i < gargc; i++) *w++ = (u64)gargv[i];
    *w++ = 0;
    for (u64 i = 0; i < ne; i++) if (!env_is_ours(envp[i])) *w++ = (u64)envp[i];
    for (int i = 0; i < nsock; i++) *w++ = (u64)sockenv[i];
    *w++ = 0;
    for (u64 i = 0; i < na; i++) {
        u64 t = auxv[i * 2], v = auxv[i * 2 + 1];
        switch (t) {
        case 3: v = exe.phdr; break;            // AT_PHDR
        case 4: v = sizeof(struct phdr); break; // AT_PHENT
        case 5: v = exe.phnum; break;           // AT_PHNUM
        case 7: v = base; break;                // AT_BASE
        case 9: v = exe.entry; break;           // AT_ENTRY
        case 31: v = (u64)argv0; break;         // AT_EXECFN
        }
        *w++ = t; *w++ = v;
    }
    *w++ = 0; *w++ = 0;
    enter_guest((void *)entry, top);
}
