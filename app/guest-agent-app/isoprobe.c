// aemu-isoprobe: the guest-side half of the isolation measurement batch.
//
// Run inside the guest by the agent service (AgentStub's ISORUN) after the host has planted two
// markers for this run: a host marker OUTSIDE the guest root (its content is the OUT token) and a
// decoy at the same path string INSIDE the root (the IN token). Each cell below tries one way of
// getting at the host marker and reports one line:
//
//     ISO <cell> <PASS|LEAK|NA> <what actually happened>
//
// PASS = the attempt was kept inside the root (it read the decoy, or was refused). LEAK = the
// attempt read the host marker itself. NA = this environment cannot exercise the cell at all
// (the reason is in the line). The probe changes nothing outside its own scratch links in the
// working directory, and it never pretends a cell it could not run.
//
// Usage: aemu-isoprobe <host marker path> <OUT token> <IN token> <working dir>
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static const char *g_marker, *g_out, *g_in;

static void report(const char *cell, const char *verdict, const char *detail) {
    printf("ISO %s %s %s\n", cell, verdict, detail);
    fflush(stdout);
}

/* what a read of a file came back with: 1 = the host marker, 0 = the in-root decoy,
   -1 = the read failed (*err set), 2 = something else entirely */
static int classify_fd(int fd, int *err) {
    char buf[256];
    ssize_t n = read(fd, buf, sizeof buf - 1);
    if (n < 0) { *err = errno; return -1; }
    buf[n] = 0;
    if (strncmp(buf, g_out, strlen(g_out)) == 0) return 1;
    if (strncmp(buf, g_in, strlen(g_in)) == 0) return 0;
    return 2;
}

static int classify_path(const char *path, int *err) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) { *err = errno; return -1; }
    int r = classify_fd(fd, err);
    close(fd);
    return r;
}

static const char *outcome(int c, int err, char *buf, size_t cap) {
    if (c == 1) return "read the HOST marker";
    if (c == 0) return "read the in-root decoy";
    if (c == 2) return "read unexpected content";
    snprintf(buf, cap, "open refused (errno %d)", err);
    return buf;
}

/* "../" x n followed by the marker path without its leading slash: from anywhere at most n
   levels deep this climbs to the host root and descends onto the marker itself. */
static void climb_path(char *dst, size_t cap, int n) {
    size_t o = 0;
    for (int i = 0; i < n && o + 3 < cap; i++) { memcpy(dst + o, "../", 3); o += 3; }
    const char *m = g_marker + (g_marker[0] == '/');
    size_t l = strlen(m);
    if (o + l >= cap) l = cap - o - 1;
    memcpy(dst + o, m, l);
    dst[o + l] = 0;
}

// ---------------------------------------------------------------------------------------------------- cells

static void cell_control(void) {
    int err = 0;
    int c = classify_path(g_marker, &err);
    if (c == 1)
        report("control", "LEAK", "a plain open of the host marker path read the host marker itself: paths are not translated at all");
    else if (c == 0)
        report("control", "PASS", "a plain open of the host marker path read the in-root decoy: translation is on, the control cell is sane");
    else if (c == -1) {
        char d[320];
        snprintf(d, sizeof d, "the marker path could not be read at all (errno %d): the host setup did not plant the decoy, or translation maps the path elsewhere — the other cells' PASSes are suspect", err);
        report("control", "NA", d);
    } else
        report("control", "NA", "the marker path read back unexpected content: the host setup is not what the probe was told");
}

static void cell_symlink(void) {
    char ea[64], eb[64], da[512], db[512];
    /* A: an absolute target — the layer is known to re-root these at creation in this engine */
    unlink("iso-link-a");
    int a;
    if (symlink(g_marker, "iso-link-a") != 0) {
        snprintf(da, sizeof da, "symlink refused (errno %d)", errno);
        a = -2;
    } else {
        int err = 0;
        a = classify_path("iso-link-a", &err);
        snprintf(da, sizeof da, "abs: %s", outcome(a, err, ea, sizeof ea));
    }
    /* B: a relative target that climbs — stored verbatim, resolved by the host kernel
       against the link's real directory */
    char tgt[8192];
    climb_path(tgt, sizeof tgt, 24);
    unlink("iso-link-b");
    int b;
    if (symlink(tgt, "iso-link-b") != 0) {
        snprintf(db, sizeof db, "symlink refused (errno %d)", errno);
        b = -2;
    } else {
        int err = 0;
        b = classify_path("iso-link-b", &err);
        snprintf(db, sizeof db, "rel-climb: %s", outcome(b, err, eb, sizeof eb));
    }
    char d[1200];
    snprintf(d, sizeof d, "%s; %s", da, db);
    if (a == 1 || b == 1) report("symlink", "LEAK", d);
    else if (a == -2 && b == -2) report("symlink", "NA", d);
    else report("symlink", "PASS", d);
}

static void cell_dotdot(void) {
    /* A: ".." inside an absolute guest path — the layer folds these lexically before prefixing */
    char pa[8192];
    size_t o = 0;
    o += (size_t)snprintf(pa + o, sizeof pa - o, "/data/local/tmp");
    for (int i = 0; i < 8; i++) o += (size_t)snprintf(pa + o, sizeof pa - o, "/..");
    snprintf(pa + o, sizeof pa - o, "%s", g_marker);
    int ea = 0;
    int a = classify_path(pa, &ea);
    /* B: ".." in a path relative to the working directory — the layer passes relative paths
       through, and the host kernel resolves them against the real directory */
    char pb[8192];
    climb_path(pb, sizeof pb, 24);
    int eb = 0;
    int b = classify_path(pb, &eb);
    char xa[64], xb[64], d[1200];
    snprintf(d, sizeof d, "absolute: %s; relative-from-cwd: %s",
             outcome(a, ea, xa, sizeof xa), outcome(b, eb, xb, sizeof xb));
    if (a == 1 || b == 1) report("dotdot", "LEAK", d);
    else report("dotdot", "PASS", d);
}

static void cell_proc(void) {
    char pa[8192], pb[8192], rl[512];
    snprintf(pa, sizeof pa, "/proc/self/root%s", g_marker);
    snprintf(pb, sizeof pb, "/proc/1/root%s", g_marker);
    int ea = 0, eb = 0;
    int a = classify_path(pa, &ea);
    int b = classify_path(pb, &eb);
    ssize_t n = readlink("/proc/self/root", rl, sizeof rl - 1);
    if (n < 0) snprintf(rl, sizeof rl, "<readlink errno %d>", errno);
    else rl[n] = 0;
    char xa[64], xb[64], d[1400];
    snprintf(d, sizeof d, "self/root: %s; 1/root: %s; readlink /proc/self/root = %s",
             outcome(a, ea, xa, sizeof xa), outcome(b, eb, xb, sizeof xb), rl);
    if (a == 1 || b == 1) report("proc", "LEAK", d);
    else report("proc", "PASS", d);
}

static void cell_dirfd(void) {
    int dfd = open(".", O_RDONLY | O_DIRECTORY);
    if (dfd < 0) {
        char d[128];
        snprintf(d, sizeof d, "the working directory itself could not be opened (errno %d)", errno);
        report("dirfd", "NA", d);
        return;
    }
    char rel[8192];
    climb_path(rel, sizeof rel, 24);
    int err = 0;
    int fd = openat(dfd, rel, O_RDONLY);
    int c;
    if (fd < 0) { err = errno; c = -1; }
    else { c = classify_fd(fd, &err); close(fd); }
    close(dfd);
    char x[64], d[512];
    snprintf(d, sizeof d, "openat(dirfd, 24x../ + marker): %s", outcome(c, err, x, sizeof x));
    if (c == 1) report("dirfd", "LEAK", d);
    else report("dirfd", "PASS", d);
}

static void cell_ptrace(void) {
    int pipefd[2];
    if (pipe(pipefd) != 0) {
        report("ptrace", "NA", "pipe() failed: the cell cannot be staged");
        return;
    }
    pid_t c = fork();
    if (c < 0) {
        report("ptrace", "NA", "fork() failed: the cell cannot be staged");
        return;
    }
    if (c == 0) {
        /* the middle process: it has a child of its own and tries to take that child over,
           the way a proot-style tracer would. The verdict travels back over the pipe. */
        close(pipefd[0]);
        pid_t g = fork();
        if (g < 0) { dprintf(pipefd[1], "stage errno %d", errno); _exit(0); }
        if (g == 0) { for (;;) pause(); }
        int r = ptrace(PTRACE_ATTACH, g, 0, 0);
        if (r == 0) {
            int st = 0;
            pid_t w = waitpid(g, &st, 0);
            int stopped = (w == g && WIFSTOPPED(st)) ? 1 : 0;
            ptrace(PTRACE_DETACH, g, 0, 0);
            dprintf(pipefd[1], "attached stopped=%d", stopped);
        } else {
            dprintf(pipefd[1], "refused errno %d", errno);
        }
        kill(g, SIGKILL);
        waitpid(g, NULL, 0);
        _exit(0);
    }
    close(pipefd[1]);
    char buf[256];
    ssize_t n = read(pipefd[0], buf, sizeof buf - 1);
    close(pipefd[0]);
    int st = 0;
    waitpid(c, &st, 0);
    if (n <= 0) {
        report("ptrace", "NA", "the staging child produced no verdict");
        return;
    }
    buf[n] = 0;
    char d[512];
    if (strncmp(buf, "attached", 8) == 0) {
        snprintf(d, sizeof d, "a guest process attached to its own child and stopped it (%s): a proot-style tracer can take over guest processes — the layer does not own tracing exclusively", buf);
        report("ptrace", "LEAK", d);
    } else if (strncmp(buf, "refused", 7) == 0) {
        snprintf(d, sizeof d, "PTRACE_ATTACH on an own child was %s: guest processes cannot take each other over", buf);
        report("ptrace", "PASS", d);
    } else {
        snprintf(d, sizeof d, "the cell could not be staged (%s)", buf);
        report("ptrace", "NA", d);
    }
}

/* io_uring, raw: the structures are the kernel's uapi, defined here so the probe does not
   depend on which headers the NDK ships. */
struct iso_sqring_offsets { uint32_t head, tail, ring_mask, ring_entries, flags, dropped, array, resv1, resv2; };
struct iso_cqring_offsets { uint32_t head, tail, ring_mask, ring_entries, overflow, cqes, flags, resv1, resv2; };
struct iso_uring_params {
    uint32_t sq_entries, cq_entries, flags, sq_thread_cpu, sq_thread_idle, features, wq_fd, resv[3];
    struct iso_sqring_offsets sq_off;
    struct iso_cqring_offsets cq_off;
};
struct iso_sqe {
    uint8_t opcode, flags;
    uint16_t ioprio;
    int32_t fd;
    uint64_t off;
    uint64_t addr;
    uint32_t len;
    uint32_t open_flags;
    uint64_t user_data;
    uint16_t buf_group;
    uint16_t personality;
    int32_t splice_fd_in;
    uint32_t pad[2];
};
struct iso_cqe { uint64_t user_data; int32_t res; uint32_t flags; };

#ifndef __NR_io_uring_setup
#define __NR_io_uring_setup 425
#endif
#ifndef __NR_io_uring_enter
#define __NR_io_uring_enter 426
#endif
#define ISO_IORING_OP_OPENAT 18

static void cell_uring(void) {
    struct iso_uring_params prm;
    memset(&prm, 0, sizeof prm);
    int rfd = (int)syscall(__NR_io_uring_setup, 8, &prm);
    if (rfd < 0) {
        int e = errno;
        char d[256];
        if (e == ENOSYS)
            report("uring", "NA", "io_uring_setup answered ENOSYS: this host kernel has no io_uring, the cell cannot be exercised");
        else {
            snprintf(d, sizeof d, "io_uring_setup refused (errno %d): the host kernel's own policy closes the ring here, the cell cannot be exercised", e);
            report("uring", "NA", d);
        }
        return;
    }
    size_t sq_sz = prm.sq_off.array + prm.sq_entries * sizeof(uint32_t);
    size_t cq_sz = prm.cq_off.cqes + prm.cq_entries * sizeof(struct iso_cqe);
    char *sq_ring = mmap(NULL, sq_sz, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_POPULATE, rfd, 0);
    char *cq_ring = mmap(NULL, cq_sz, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_POPULATE, rfd, 0x8000000ULL);
    struct iso_sqe *sqes = mmap(NULL, prm.sq_entries * sizeof(struct iso_sqe), PROT_READ | PROT_WRITE,
                                MAP_SHARED | MAP_POPULATE, rfd, 0x10000000ULL);
    if (sq_ring == MAP_FAILED || cq_ring == MAP_FAILED || sqes == MAP_FAILED) {
        report("uring", "NA", "io_uring_setup succeeded but the rings could not be mapped: the cell cannot be exercised");
        close(rfd);
        return;
    }
    uint32_t *sq_tail = (uint32_t *)(sq_ring + prm.sq_off.tail);
    uint32_t *sq_mask = (uint32_t *)(sq_ring + prm.sq_off.ring_mask);
    uint32_t *sq_array = (uint32_t *)(sq_ring + prm.sq_off.array);
    uint32_t *cq_head = (uint32_t *)(cq_ring + prm.cq_off.head);
    uint32_t *cq_tail = (uint32_t *)(cq_ring + prm.cq_off.tail);
    uint32_t *cq_mask = (uint32_t *)(cq_ring + prm.cq_off.ring_mask);
    struct iso_cqe *cqes = (struct iso_cqe *)(cq_ring + prm.cq_off.cqes);
    uint32_t tail = *sq_tail;
    uint32_t idx = tail & *sq_mask;
    struct iso_sqe *sqe = &sqes[idx];
    memset(sqe, 0, sizeof *sqe);
    sqe->opcode = ISO_IORING_OP_OPENAT;
    sqe->fd = AT_FDCWD;
    sqe->addr = (uint64_t)g_marker;
    sqe->open_flags = O_RDONLY;
    sqe->user_data = 0x150;
    sq_array[idx] = idx;
    __sync_synchronize();
    *sq_tail = tail + 1;
    int r = (int)syscall(__NR_io_uring_enter, rfd, 1, 1, 1 /* IORING_ENTER_GETEVENTS */, NULL, 0);
    if (r < 0) {
        char d[256];
        snprintf(d, sizeof d, "the ring was created but io_uring_enter refused (errno %d): the cell cannot be exercised", errno);
        report("uring", "NA", d);
        close(rfd);
        return;
    }
    if (*cq_tail == *cq_head) {
        report("uring", "NA", "io_uring_enter returned without a completion: the cell cannot be exercised");
        close(rfd);
        return;
    }
    struct iso_cqe *cqe = &cqes[*cq_head & *cq_mask];
    int32_t res = cqe->res;
    *cq_head = *cq_head + 1;
    if (res >= 0) {
        int err = 0;
        int c = classify_fd(res, &err);
        close(res);
        if (c == 1)
            report("uring", "LEAK", "an io_uring OPENAT opened the host marker directly: ring operations run in the kernel, past the layer's syscall interception");
        else {
            char d[256];
            snprintf(d, sizeof d, "the ring opened something that is not the host marker (classify=%d): unexpected, recorded as not-a-leak", c);
            report("uring", "PASS", d);
        }
    } else if (res == -EINVAL) {
        report("uring", "NA", "the ring was created (io_uring_setup is not intercepted) but OPENAT is a kernel 5.6+ opcode and this host runs 5.4: no path-resolving opcode exists here to complete the test — re-measure on a 5.6+ host");
    } else {
        char d[256];
        snprintf(d, sizeof d, "the ring's open was refused by the kernel itself (res %d)", res);
        report("uring", "PASS", d);
    }
    close(rfd);
}

int main(int argc, char **argv) {
    if (argc < 5) {
        printf("ISO setup NA usage: aemu-isoprobe <marker> <out token> <in token> <workdir>\n");
        return 2;
    }
    g_marker = argv[1];
    g_out = argv[2];
    g_in = argv[3];
    if (chdir(argv[4]) != 0) {
        char d[256];
        snprintf(d, sizeof d, "the working directory %s could not be entered (errno %d)", argv[4], errno);
        report("setup", "NA", d);
        return 2;
    }
    cell_control();
    cell_symlink();
    cell_dotdot();
    cell_proc();
    cell_dirfd();
    cell_ptrace();
    cell_uring();
    printf("ISO done - 7 cells reported\n");
    fflush(stdout);
    return 0;
}
