/* AEmulator addition, 2026-11-26. GPL-3.0; see LICENSE. */
/*
 * aemu-substitute: our own daemon for the Magisk slots (design: daemon-substitute-desk.md).
 *
 * One native binary in the shape of Magisk's own: the `magisk` CLI, the `su` applet and the
 * daemon in a single body, speaking the v30.7 wire protocol on <magisk tmp>/.magisk/device/socket
 * and keeping its state in a magisk.db with the official schema (sqlite.cpp of v30.7, verbatim),
 * so the official Magisk manager app works against it and an official daemon could take over the
 * same files later with no migration.
 *
 * What it deliberately does NOT do (the desk's honesty list, said out loud where a caller can
 * see it instead of faked): no Zygisk (the ZYGISK request is refused, the settings row is seeded
 * 0 so the app shows it off), no sepolicy live patching (argv[0] magiskpolicy/supolicy is a shell
 * that refuses with a non-zero exit), no magic mount (module files are placed by the host's
 * offline module host; this daemon only runs the stage bookkeeping and the scripts), no mount
 * namespace isolation (denylist exec refuses), no resetprop applet yet (P1). The su prompt
 * conversation with the manager app (core/su/connect.rs) IS implemented: a policy of Query
 * is put to the manager over the su_request FIFO, and logging/notification are reported
 * back the same way the original reports them.
 *
 * The sqlite engine is the system's own libsqlite.so, dlopen'd exactly like Magisk's sqlite.cpp
 * does it; nothing is vendored.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <ctype.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <ftw.h>
#include <getopt.h>
#include <grp.h>
#include <limits.h>
#include <poll.h>
#include <pthread.h>
#include <pwd.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#ifndef DESKTOP_TEST
#include <sys/system_properties.h>
#endif

/* ---- version claim: bound to the pinned generation in three places (this binary, the
 * util_functions.sh constants of the payload, the manager app's handshake). Do not bump one
 * without the others. */
#define SUBST_VERSION "30.7"
#define SUBST_VER_CODE 30700

#define SECURE_DIR "/data/adb"
#define MODULEROOT SECURE_DIR "/modules"
#define MODULEUPGRADE SECURE_DIR "/modules_update"
#define DATABIN SECURE_DIR "/magisk"
#define MAGISKDB SECURE_DIR "/magisk.db"
#define LOGFILE "/cache/magisk.log"
#define MAIN_SOCKET ".magisk/device/socket"

/* RequestCode (core/lib.rs of v30.7) */
enum {
    REQ_START_DAEMON = 0, REQ_CHECK_VERSION = 1, REQ_CHECK_VERSION_CODE = 2, REQ_STOP_DAEMON = 3,
    REQ_SUPERUSER = 5, REQ_ZYGOTE_RESTART = 6, REQ_DENYLIST = 7, REQ_SQLITE_CMD = 8,
    REQ_REMOVE_MODULES = 9, REQ_ZYGISK = 10,
    REQ_POST_FS_DATA = 12, REQ_LATE_START = 13, REQ_BOOT_COMPLETE = 14, REQ_END = 15
};
/* RespondCode */
enum { RESP_ERROR = -1, RESP_OK = 0, RESP_ROOT_REQUIRED = 1, RESP_ACCESS_DENIED = 2 };
/* SuPolicy */
enum { SU_QUERY = 0, SU_DENY = 1, SU_ALLOW = 2, SU_RESTRICT = 3 };
/* DenyRequest / DenyResponse (core/deny/deny.hpp) */
enum { DENY_ENFORCE = 0, DENY_DISABLE = 1, DENY_ADD = 2, DENY_REMOVE = 3, DENY_LIST = 4, DENY_STATUS = 5 };
enum {
    DENYRESP_OK = 0, DENYRESP_ENFORCED = 1, DENYRESP_NOT_ENFORCED = 2, DENYRESP_ITEM_EXIST = 3,
    DENYRESP_ITEM_NOT_EXIST = 4, DENYRESP_INVALID_PKG = 5, DENYRESP_NO_NS = 6, DENYRESP_ERROR = 7
};

#define AID_ROOT 0
#define AID_SHELL 2000

/* ---- small io helpers: full reads/writes, the protocol's POD + string framing ------------- */

static int write_all(int fd, const void *buf, size_t len) {
    const char *p = buf;
    while (len > 0) {
        ssize_t n = send(fd, p, len, MSG_NOSIGNAL);
        if (n < 0) {
            if (errno == EINTR) continue;
            /* send() only works on sockets; fall back for plain fds */
            n = write(fd, p, len);
            if (n < 0) {
                if (errno == EINTR) continue;
                return -1;
            }
        }
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

static int read_all(int fd, void *buf, size_t len) {
    char *p = buf;
    while (len > 0) {
        ssize_t n = read(fd, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

static int write_i32(int fd, int32_t v) { return write_all(fd, &v, 4); }
static int read_i32(int fd, int32_t *v) { return read_all(fd, v, 4); }

/* Encodable for String/[T]: i32 length/count, then the raw items (core/socket.rs). */
static int write_string(int fd, const char *s) {
    int32_t len = (int32_t)strlen(s);
    if (write_i32(fd, len)) return -1;
    return len > 0 ? write_all(fd, s, (size_t)len) : 0;
}

static char *read_string(int fd) {
    int32_t len;
    if (read_i32(fd, &len) || len < 0 || len > (16 << 20)) return NULL;
    char *s = malloc((size_t)len + 1);
    if (!s) return NULL;
    if (len > 0 && read_all(fd, s, (size_t)len)) { free(s); return NULL; }
    s[len] = '\0';
    return s;
}

/* send_fd/recv_fd (core/socket.rs): an i32 count, the fds riding as SCM_RIGHTS when count >= 1. */
static int send_fd(int fd, int to_send) {
    if (to_send < 0) {
        int32_t zero = 0;
        return write_all(fd, &zero, 4);
    }
    int32_t one = 1;
    char cbuf[CMSG_SPACE(sizeof(int))];
    struct iovec iov = { &one, 4 };
    struct msghdr msg = {0};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cbuf;
    msg.msg_controllen = sizeof(cbuf);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &to_send, sizeof(int));
    ssize_t n;
    do { n = sendmsg(fd, &msg, MSG_NOSIGNAL); } while (n < 0 && errno == EINTR);
    return n == 4 ? 0 : -1;
}

static int recv_fd(int fd) {
    /* Peek the count first, like the original: a bare count of 0 carries no ancillary data. */
    int32_t count = 0;
    ssize_t pk;
    do { pk = recv(fd, &count, 4, MSG_PEEK | MSG_WAITALL); } while (pk < 0 && errno == EINTR);
    if (pk != 4) return -1;
    if (count < 1) {
        read_i32(fd, &count);
        return -1;
    }
    char cbuf[CMSG_SPACE(sizeof(int) * 4)];
    int32_t sink = 0;
    struct iovec iov = { &sink, 4 };
    struct msghdr msg = {0};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cbuf;
    msg.msg_controllen = sizeof(cbuf);
    ssize_t n;
    do { n = recvmsg(fd, &msg, 0); } while (n < 0 && errno == EINTR);
    if (n != 4) return -1;
    for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
        if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS &&
            cmsg->cmsg_len >= CMSG_LEN(sizeof(int))) {
            int got;
            memcpy(&got, CMSG_DATA(cmsg), sizeof(int));
            /* close any extras, like the original */
            size_t total = (cmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int);
            for (size_t i = 1; i < total; i++) {
                int extra;
                memcpy(&extra, (char *)CMSG_DATA(cmsg) + i * sizeof(int), sizeof(int));
                close(extra);
            }
            return got;
        }
    }
    return -1;
}

/* ---- daemon log: /cache/magisk.log, the file the manager app's log page reads -------------- */

static pthread_mutex_t g_log_lock = PTHREAD_MUTEX_INITIALIZER;

static void slog(const char *fmt, ...) {
    char msg[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tm;
    localtime_r(&ts.tv_sec, &tm);
    pthread_mutex_lock(&g_log_lock);
    FILE *f = fopen(LOGFILE, "a");
    if (f) {
        fprintf(f, "%02d-%02d %02d:%02d:%02d.%03d %5d %5d I Magisk: %s\n",
                tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec,
                (int)(ts.tv_nsec / 1000000), getpid(), getpid(), msg);
        fclose(f);
    }
    pthread_mutex_unlock(&g_log_lock);
}

/* get_magisk_tmp (core/utils.cpp): the first of /debug_ramdisk, /sbin that holds .magisk. */
static const char *magisk_tmp(void) {
    static const char *path = NULL;
    if (!path) {
        if (access("/debug_ramdisk/.magisk", F_OK) == 0) path = "/debug_ramdisk";
        else if (access("/sbin/.magisk", F_OK) == 0) path = "/sbin";
        else path = "";
    }
    return path;
}

static void socket_path(char *out, size_t cap) {
    snprintf(out, cap, "%s/%s", magisk_tmp(), MAIN_SOCKET);
}

static void get_prop(const char *name, char *out, size_t cap) {
    out[0] = '\0';
#ifndef DESKTOP_TEST
    char buf[PROP_VALUE_MAX];
    if (__system_property_get(name, buf) > 0) snprintf(out, cap, "%s", buf);
#else
    (void)name; (void)out; (void)cap;
#endif
}

/* ---- sqlite: the system libsqlite.so, dlopen'd like Magisk's sqlite.cpp --------------------
 * Only the entry points used below are resolved. The schema SQL is verbatim v30.7. */

typedef struct sqlite3 sqlite3;
typedef struct sqlite3_stmt sqlite3_stmt;
#define SQLITE_OK 0
#define SQLITE_ROW 100
#define SQLITE_DONE 101
#define SQLITE_OPEN_READWRITE 0x2
#define SQLITE_OPEN_CREATE 0x4
#define SQLITE_OPEN_FULLMUTEX 0x10000
#define SQLITE_TRANSIENT ((void (*)(void *))-1)

static int (*p_open_v2)(const char *, sqlite3 **, int, const char *);
static int (*p_close)(sqlite3 *);
static int (*p_prepare_v2)(sqlite3 *, const char *, int, sqlite3_stmt **, const char **);
static int (*p_step)(sqlite3_stmt *);
static int (*p_finalize)(sqlite3_stmt *);
static int (*p_reset)(sqlite3_stmt *);
static int (*p_column_count)(sqlite3_stmt *);
static const char *(*p_column_name)(sqlite3_stmt *, int);
static const unsigned char *(*p_column_text)(sqlite3_stmt *, int);
static long long (*p_column_int64)(sqlite3_stmt *, int);
static int (*p_bind_int64)(sqlite3_stmt *, int, long long);
static int (*p_bind_text)(sqlite3_stmt *, int, const char *, int, void (*)(void *));
static int (*p_changes)(sqlite3 *);
static const char *(*p_errmsg)(sqlite3 *);

static sqlite3 *g_db = NULL;
static pthread_mutex_t g_db_lock = PTHREAD_MUTEX_INITIALIZER;

static int load_sqlite(void) {
    if (p_open_v2) return 0;
#ifdef DESKTOP_TEST
    void *h = dlopen("libsqlite3.so.0", RTLD_NOW);
#else
    void *h = dlopen("libsqlite.so", RTLD_NOW);
#endif
    if (!h) return -1;
#define RESOLVE(sym, ptr) do { *(void **)&(ptr) = dlsym(h, sym); if (!(ptr)) return -1; } while (0)
    RESOLVE("sqlite3_open_v2", p_open_v2);
    RESOLVE("sqlite3_close", p_close);
    RESOLVE("sqlite3_prepare_v2", p_prepare_v2);
    RESOLVE("sqlite3_step", p_step);
    RESOLVE("sqlite3_finalize", p_finalize);
    RESOLVE("sqlite3_reset", p_reset);
    RESOLVE("sqlite3_column_count", p_column_count);
    RESOLVE("sqlite3_column_name", p_column_name);
    RESOLVE("sqlite3_column_text", p_column_text);
    RESOLVE("sqlite3_column_int64", p_column_int64);
    RESOLVE("sqlite3_bind_int64", p_bind_int64);
    RESOLVE("sqlite3_bind_text", p_bind_text);
    RESOLVE("sqlite3_changes", p_changes);
    RESOLVE("sqlite3_errmsg", p_errmsg);
#undef RESOLVE
    return 0;
}

/* open_and_init_db (core/sqlite.cpp): same tables, same user_version (12). Caller holds no lock. */
static void db_open_and_init(void) {
    if (load_sqlite()) { slog("sqlite3: cannot load the system libsqlite, the database is unavailable"); return; }
    if (p_open_v2(MAGISKDB, &g_db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, NULL) != SQLITE_OK) {
        slog("sqlite3: cannot open %s: %s", MAGISKDB, g_db ? p_errmsg(g_db) : "out of memory");
        if (g_db) { p_close(g_db); g_db = NULL; }
        return;
    }
    /* A database from a newer Magisk is not downgraded: delete and retry, like the original. */
    sqlite3_stmt *st = NULL;
    int ver = 0;
    if (p_prepare_v2(g_db, "PRAGMA user_version", -1, &st, NULL) == SQLITE_OK && st) {
        if (p_step(st) == SQLITE_ROW) ver = (int)p_column_int64(st, 0);
        p_finalize(st);
    }
    if (ver > 12) {
        slog("sqlite3: downgrading the database is not supported, recreating it");
        p_close(g_db); g_db = NULL;
        unlink(MAGISKDB);
        db_open_and_init();
        return;
    }
    static const char *const ddl[] = {
        "CREATE TABLE IF NOT EXISTS policies (uid INT, policy INT, until INT, logging INT, notification INT, PRIMARY KEY(uid))",
        "CREATE TABLE IF NOT EXISTS settings (key TEXT, value INT, PRIMARY KEY(key))",
        "CREATE TABLE IF NOT EXISTS strings (key TEXT, value TEXT, PRIMARY KEY(key))",
        "CREATE TABLE IF NOT EXISTS denylist (package_name TEXT, process TEXT, PRIMARY KEY(package_name, process))",
        "PRAGMA user_version=12",
        NULL
    };
    for (int i = 0; ddl[i]; i++) {
        sqlite3_stmt *s2 = NULL;
        if (p_prepare_v2(g_db, ddl[i], -1, &s2, NULL) == SQLITE_OK && s2) {
            p_step(s2);
            p_finalize(s2);
        }
    }
    /* The substitute never injects: where the official daemon would default "zygisk" to 1 on an
     * emulator, our default is materialised as an explicit 0 so the manager app shows it off
     * (design 4.3/6.1). An explicit value the user set later always wins — this only fills a gap. */
    sqlite3_stmt *s3 = NULL;
    if (p_prepare_v2(g_db, "INSERT OR IGNORE INTO settings (key, value) VALUES('zygisk', 0)", -1, &s3, NULL) == SQLITE_OK && s3) {
        p_step(s3);
        p_finalize(s3);
    }
}

/* Settings with the official defaults (core/db.rs get_db_setting). */
static int db_get_setting(const char *key, int dflt) {
    pthread_mutex_lock(&g_db_lock);
    int val = dflt;
    if (g_db) {
        sqlite3_stmt *st = NULL;
        if (p_prepare_v2(g_db, "SELECT value FROM settings WHERE key=?", -1, &st, NULL) == SQLITE_OK && st) {
            p_bind_text(st, 1, key, -1, SQLITE_TRANSIENT);
            if (p_step(st) == SQLITE_ROW) val = (int)p_column_int64(st, 0);
            p_finalize(st);
        }
    }
    pthread_mutex_unlock(&g_db_lock);
    return val;
}

/* A row of the strings table ("" when absent): the manager package lives there (db.rs
 * DbEntryKey::SuManager = "requester"), written by the manager app itself. */
static void db_get_string(const char *key, char *out, size_t cap) {
    out[0] = '\0';
    pthread_mutex_lock(&g_db_lock);
    if (g_db) {
        sqlite3_stmt *st = NULL;
        if (p_prepare_v2(g_db, "SELECT value FROM strings WHERE key=?", -1, &st, NULL) == SQLITE_OK && st) {
            p_bind_text(st, 1, key, -1, SQLITE_TRANSIENT);
            if (p_step(st) == SQLITE_ROW) {
                const unsigned char *v = p_column_text(st, 0);
                if (v) snprintf(out, cap, "%s", (const char *)v);
            }
            p_finalize(st);
        }
    }
    pthread_mutex_unlock(&g_db_lock);
}

static void db_set_setting(const char *key, int value) {
    pthread_mutex_lock(&g_db_lock);
    if (g_db) {
        sqlite3_stmt *st = NULL;
        if (p_prepare_v2(g_db, "INSERT OR REPLACE INTO settings (key,value) VALUES(?,?)", -1, &st, NULL) == SQLITE_OK && st) {
            p_bind_text(st, 1, key, -1, SQLITE_TRANSIENT);
            p_bind_int64(st, 2, value);
            p_step(st);
            p_finalize(st);
        }
    }
    pthread_mutex_unlock(&g_db_lock);
}

/* ---- child reaping: one sigwait thread fills a pid->status table, so detached script
 * children never zombie and synchronous children can be waited for from any thread. --------- */

#define CHILD_TABLE 512
static struct { pid_t pid; int status; int taken; } g_children[CHILD_TABLE];
static pthread_mutex_t g_child_lock = PTHREAD_MUTEX_INITIALIZER;

static void *reaper_main(void *arg) {
    (void)arg;
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, SIGCHLD);
    for (;;) {
        int sig = 0;
        if (sigwait(&set, &sig) != 0) continue;
        for (;;) {
            int status = 0;
            pid_t pid = waitpid(-1, &status, WNOHANG);
            if (pid <= 0) break;
            pthread_mutex_lock(&g_child_lock);
            int slot = -1;
            for (int i = 0; i < CHILD_TABLE; i++) {
                if (g_children[i].pid == pid) { slot = i; break; }
                if (slot < 0 && g_children[i].taken) slot = i;
                if (slot < 0 && g_children[i].pid == 0) slot = i;
            }
            if (slot < 0) slot = 0;
            g_children[slot].pid = pid;
            g_children[slot].status = status;
            g_children[slot].taken = 0;
            pthread_mutex_unlock(&g_child_lock);
        }
    }
    return NULL;
}

/* The exit code of a child (WEXITSTATUS, like the original's waitpid read), or -1 on timeout. */
static int wait_for_pid(pid_t pid, int timeout_ms) {
    int waited = 0;
    for (;;) {
        pthread_mutex_lock(&g_child_lock);
        for (int i = 0; i < CHILD_TABLE; i++) {
            if (g_children[i].pid == pid && !g_children[i].taken) {
                int status = g_children[i].status;
                g_children[i].taken = 1;
                pthread_mutex_unlock(&g_child_lock);
                return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
            }
        }
        pthread_mutex_unlock(&g_child_lock);
        if (timeout_ms >= 0 && waited >= timeout_ms) return -1;
        struct timespec ts = { 0, 5 * 1000 * 1000 };
        nanosleep(&ts, NULL);
        waited += 5;
    }
}

static void unblock_signals(void) {
    sigset_t empty;
    sigemptyset(&empty);
    pthread_sigmask(SIG_SETMASK, &empty, NULL);
}

/* ---- scripts (core/scripting.cpp): busybox sh with the script environment ----------------- */

static void busybox_path(char *out, size_t cap) {
    snprintf(out, cap, "%s/.magisk/busybox/busybox", magisk_tmp());
}

/* Runs one script. detach=0: wait for it (bounded by budget_ms when >= 0; on budget exhaustion
 * the child is left running and 0 is returned). Script output lands in the daemon log file. */
static int run_script(const char *path, int detach, int budget_ms) {
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        unblock_signals();
        setsid();
        setenv("ASH_STANDALONE", "1", 1);
        const char *old = getenv("PATH");
        char newpath[PATH_MAX + 64];
        snprintf(newpath, sizeof(newpath), "%s:%s", old ? old : "/system/bin:/system/xbin", magisk_tmp());
        setenv("PATH", newpath, 1);
        int nullfd = open("/dev/null", O_RDONLY);
        if (nullfd >= 0) { dup2(nullfd, STDIN_FILENO); if (nullfd > 2) close(nullfd); }
        int logfd = open(LOGFILE, O_WRONLY | O_CREAT | O_APPEND, 0644);
        if (logfd >= 0) { dup2(logfd, STDOUT_FILENO); dup2(logfd, STDERR_FILENO); if (logfd > 2) close(logfd); }
        char bb[PATH_MAX];
        busybox_path(bb, sizeof(bb));
        if (access(bb, X_OK) == 0) execl(bb, "sh", path, (char *)NULL);
        else execl("/system/bin/sh", "sh", path, (char *)NULL);
        _exit(127);
    }
    if (detach) return 0;
    return wait_for_pid(pid, budget_ms);
}

/* Every executable regular file of /data/adb/<stage>.d, in name order for determinism. */
static void exec_common_scripts(const char *stage, int detach, int budget_ms) {
    char dirpath[128];
    snprintf(dirpath, sizeof(dirpath), "%s/%s.d", SECURE_DIR, stage);
    DIR *dir = opendir(dirpath);
    if (!dir) return;
    slog("* Running %s.d scripts", stage);
    char *names[256];
    int n = 0;
    struct dirent *e;
    while ((e = readdir(dir)) && n < 256) {
        char full[PATH_MAX];
        snprintf(full, sizeof(full), "%s/%s", dirpath, e->d_name);
        struct stat st;
        if (stat(full, &st) == 0 && S_ISREG(st.st_mode) && access(full, X_OK) == 0)
            names[n++] = strdup(e->d_name);
    }
    closedir(dir);
    for (int i = 0; i < n; i++)
        for (int j = i + 1; j < n; j++)
            if (strcmp(names[i], names[j]) > 0) { char *t = names[i]; names[i] = names[j]; names[j] = t; }
    long deadline = -1;
    if (!detach && budget_ms >= 0) {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        deadline = ts.tv_sec * 1000L + ts.tv_nsec / 1000000L + budget_ms;
    }
    for (int i = 0; i < n; i++) {
        char full[PATH_MAX];
        snprintf(full, sizeof(full), "%s/%s", dirpath, names[i]);
        slog("%s.d: exec [%s]", stage, names[i]);
        if (!detach && deadline >= 0) {
            struct timespec ts;
            clock_gettime(CLOCK_MONOTONIC, &ts);
            long left = deadline - (ts.tv_sec * 1000L + ts.tv_nsec / 1000000L);
            if (left <= 0) {
                slog("* %s scripts blocking phase timeout", stage);
                run_script(full, 1, -1);
            } else run_script(full, 0, (int)left);
        } else run_script(full, detach, -1);
        free(names[i]);
    }
}

static int remove_tree_cb(const char *fpath, const struct stat *sb, int typeflag, struct FTW *ftwbuf) {
    (void)sb; (void)typeflag; (void)ftwbuf;
    remove(fpath);
    return 0;
}

static void remove_tree(const char *path) {
    nftw(path, remove_tree_cb, 32, FTW_DEPTH | FTW_PHYS);
}

static int path_exists(const char *path) { struct stat st; return lstat(path, &st) == 0; }

/* ---- modules: the flag bookkeeping of handle_modules (core/module.rs), nothing more.
 * The daemon never writes module files into the system tree — that is the host's offline
 * placement, recorded in its ledger; here we only honour the flags the manager app sets. ----- */

#define MAX_MODULES 256
static char g_module_list[MAX_MODULES][128];
static int g_module_count = 0;

/* upgrade_modules(): modules_update merges into modules, a replaced module keeps its
 * disabled state; the staging directory is emptied afterwards. */
static void upgrade_modules(void) {
    DIR *dir = opendir(MODULEUPGRADE);
    if (!dir) return;
    struct dirent *e;
    while ((e = readdir(dir))) {
        if (e->d_name[0] == '.') continue;
        char src[PATH_MAX], dst[PATH_MAX];
        snprintf(src, sizeof(src), "%s/%s", MODULEUPGRADE, e->d_name);
        struct stat st;
        if (stat(src, &st) != 0 || !S_ISDIR(st.st_mode)) continue;
        snprintf(dst, sizeof(dst), "%s/%s", MODULEROOT, e->d_name);
        int disable = 0;
        if (path_exists(dst)) {
            char flag[PATH_MAX];
            snprintf(flag, sizeof(flag), "%s/disable", dst);
            disable = path_exists(flag);
            remove_tree(dst);
        }
        if (rename(src, dst) == 0) {
            slog("Upgrade / New module: %s", e->d_name);
            if (disable) {
                char flag[PATH_MAX];
                snprintf(flag, sizeof(flag), "%s/disable", dst);
                int fd = open(flag, O_RDONLY | O_CREAT, 0);
                if (fd >= 0) close(fd);
            }
        }
    }
    closedir(dir);
    remove_tree(MODULEUPGRADE);
    mkdir(MODULEUPGRADE, 0755);
}

/* collect_modules(): remove flags are honoured (uninstall.sh first, then the directory goes),
 * update flags are cleared, disabled modules are skipped. Fills g_module_list. */
static void collect_modules(void) {
    g_module_count = 0;
    DIR *dir = opendir(MODULEROOT);
    if (!dir) return;
    struct dirent *e;
    while ((e = readdir(dir))) {
        if (e->d_name[0] == '.' || !strcmp(e->d_name, ".core")) continue;
        char mdir[PATH_MAX];
        snprintf(mdir, sizeof(mdir), "%s/%s", MODULEROOT, e->d_name);
        struct stat st;
        if (stat(mdir, &st) != 0 || !S_ISDIR(st.st_mode)) continue;
        char flag[PATH_MAX];
        snprintf(flag, sizeof(flag), "%s/remove", mdir);
        if (path_exists(flag)) {
            slog("%s: remove", e->d_name);
            char script[PATH_MAX];
            snprintf(script, sizeof(script), "%s/uninstall.sh", mdir);
            if (path_exists(script)) run_script(script, 0, -1);
            remove_tree(mdir);
            continue;
        }
        snprintf(flag, sizeof(flag), "%s/update", mdir);
        unlink(flag);
        snprintf(flag, sizeof(flag), "%s/disable", mdir);
        if (path_exists(flag)) continue;
        if (g_module_count < MAX_MODULES) {
            snprintf(g_module_list[g_module_count], 128, "%s", e->d_name);
            g_module_count++;
        }
    }
    closedir(dir);
}

static void exec_module_scripts(const char *stage, int detach, int budget_ms) {
    if (g_module_count == 0) return;
    slog("* Running module %s scripts", stage);
    long deadline = -1;
    if (!detach && budget_ms >= 0) {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        deadline = ts.tv_sec * 1000L + ts.tv_nsec / 1000000L + budget_ms;
    }
    for (int i = 0; i < g_module_count; i++) {
        char path[PATH_MAX];
        snprintf(path, sizeof(path), "%s/%s/%s.sh", MODULEROOT, g_module_list[i], stage);
        if (access(path, F_OK) != 0) continue;
        slog("%s: exec [%s.sh]", g_module_list[i], stage);
        if (!detach && deadline >= 0) {
            struct timespec ts;
            clock_gettime(CLOCK_MONOTONIC, &ts);
            long left = deadline - (ts.tv_sec * 1000L + ts.tv_nsec / 1000000L);
            if (left <= 0) {
                slog("* module %s scripts blocking phase timeout", stage);
                run_script(path, 1, -1);
            } else run_script(path, 0, (int)left);
        } else run_script(path, detach, -1);
    }
}

static void disable_all_modules(void) {
    DIR *dir = opendir(MODULEROOT);
    if (!dir) return;
    struct dirent *e;
    while ((e = readdir(dir))) {
        if (e->d_name[0] == '.') continue;
        char flag[PATH_MAX];
        snprintf(flag, sizeof(flag), "%s/%s/disable", MODULEROOT, e->d_name);
        struct stat st;
        char mdir[PATH_MAX];
        snprintf(mdir, sizeof(mdir), "%s/%s", MODULEROOT, e->d_name);
        if (stat(mdir, &st) == 0 && S_ISDIR(st.st_mode)) {
            int fd = open(flag, O_RDONLY | O_CREAT, 0);
            if (fd >= 0) close(fd);
        }
    }
    closedir(dir);
}

/* ---- boot stages (core/bootstages.rs, the file-and-script parts) --------------------------- */

static pthread_mutex_t g_stage_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_pfs_done = 0;
static int g_boot_done = 0;
static int g_deny_enforced = 0;

static void stage_post_fs_data(void) {
    slog("** post-fs-data mode running");
    struct stat st;
    if (stat(SECURE_DIR, &st) != 0) mkdir(SECURE_DIR, 0700);
    /* setup_magisk_env's directory part; the tmp side is the host's prepareTmp. */
    mkdir(DATABIN, 0755);
    mkdir(MODULEROOT, 0755);
    mkdir(SECURE_DIR "/post-fs-data.d", 0755);
    mkdir(SECURE_DIR "/service.d", 0755);
    char bb[PATH_MAX];
    busybox_path(bb, sizeof(bb));
    if (access(bb, X_OK) != 0)
        slog("* no busybox in the payload tmp, module scripts run with /system/bin/sh");

    /* Safe mode: the bootloop counter (read before increment, like the original) or the
     * safemode properties. Volume-key detection is not done (design 1.3). */
    int boot_cnt = db_get_setting("bootloop", 0);
    db_set_setting("bootloop", boot_cnt + 1);
    char prop[64];
    get_prop("persist.sys.safemode", prop, sizeof(prop));
    int safe = boot_cnt >= 2 || !strcmp(prop, "1");
    if (!safe) { get_prop("ro.sys.safemode", prop, sizeof(prop)); safe = !strcmp(prop, "1"); }
    if (safe) {
        slog("* Safe mode triggered");
        disable_all_modules();
        db_set_setting("zygisk", 0);
        g_pfs_done = 1;
        return;
    }

    exec_common_scripts("post-fs-data", 0, 35000);
    g_deny_enforced = db_get_setting("denylist", 0) != 0;
    /* handle_modules: upgrade, collect (flags), module post-fs-data scripts. No mount step:
     * module files take effect through the host's placement, system.prop through the host's
     * property merge — both recorded in the host's ledger, not here. */
    upgrade_modules();
    collect_modules();
    exec_module_scripts("post-fs-data", 0, 35000);
    g_pfs_done = 1;
}

static void stage_late_start(void) {
    slog("** late_start service mode running");
    if (!g_pfs_done) { slog("* late_start before post-fs-data, ignored"); return; }
    exec_common_scripts("service", 1, -1);
    exec_module_scripts("service", 1, -1);
}

static void stage_boot_complete(void) {
    slog("** boot-complete triggered");
    if (!g_pfs_done) return;
    db_set_setting("bootloop", 0);
    struct stat st;
    if (stat(SECURE_DIR, &st) != 0) mkdir(SECURE_DIR, 0700);
    /* ensure_manager is the host's job (it installs the manager app); preinit does not exist. */
    g_boot_done = 1;
}

/* ---- SQLITE_CMD (core/db.rs db_exec_for_cli): rows as "col=value|..." strings, an empty
 * string ends the answer. Errors are swallowed into the same shape, like the original. ------- */

static void handle_sqlite_cmd(int fd) {
    char *sql = read_string(fd);
    if (!sql) return;
    pthread_mutex_lock(&g_db_lock);
    if (g_db) {
        const char *tail = sql;
        while (tail && *tail) {
            sqlite3_stmt *st = NULL;
            if (p_prepare_v2(g_db, tail, -1, &st, &tail) != SQLITE_OK) {
                slog("sqlite3: %s", p_errmsg(g_db));
                break;
            }
            if (!st) continue;
            int cols = p_column_count(st);
            int rc;
            while ((rc = p_step(st)) == SQLITE_ROW) {
                char line[4096];
                size_t off = 0;
                for (int i = 0; i < cols && off < sizeof(line) - 2; i++) {
                    const unsigned char *val = p_column_text(st, i);
                    int w = snprintf(line + off, sizeof(line) - off, "%s%s=%s",
                                     i ? "|" : "", p_column_name(st, i),
                                     val ? (const char *)val : "");
                    if (w < 0) break;
                    off += (size_t)w;
                }
                line[off < sizeof(line) ? off : sizeof(line) - 1] = '\0';
                write_string(fd, line);
            }
            if (rc != SQLITE_DONE) slog("sqlite3: %s", p_errmsg(g_db));
            p_finalize(st);
        }
    }
    pthread_mutex_unlock(&g_db_lock);
    write_string(fd, "");
    free(sql);
}

/* ---- DENYLIST (core/deny): the list lives in magisk.db; enforcement is the engine's view
 * layer, this side only keeps the config truth (design 4.3). ------------------------------- */

static int deny_validate(const char *s, int is_proc) {
    if (!s[0] || strlen(s) >= 256) return 0;
    for (const char *p = s; *p; p++) {
        char c = *p;
        if (isalnum((unsigned char)c) || c == '.' || c == '_' || c == '$') continue;
        if (is_proc && (c == ':' )) continue;
        return 0;
    }
    return 1;
}

static void handle_denylist(int fd) {
    int32_t req;
    if (read_i32(fd, &req)) return;
    int res = DENYRESP_ERROR;
    if (req == DENY_ENFORCE) {
        db_set_setting("denylist", 1);
        g_deny_enforced = 1;
        slog("* Enable DenyList");
        res = DENYRESP_OK;
    } else if (req == DENY_DISABLE) {
        db_set_setting("denylist", 0);
        g_deny_enforced = 0;
        slog("* Disable DenyList");
        res = DENYRESP_OK;
    } else if (req == DENY_STATUS) {
        res = g_deny_enforced ? DENYRESP_ENFORCED : DENYRESP_NOT_ENFORCED;
    } else if (req == DENY_ADD || req == DENY_REMOVE) {
        char *pkg = read_string(fd);
        char *proc = read_string(fd);
        if (!pkg || !proc) { free(pkg); free(proc); write_i32(fd, DENYRESP_ERROR); return; }
        /* An empty process means the package itself (add) / all of it (remove). */
        int proc_empty = proc[0] == '\0';
        char procbuf[256];
        snprintf(procbuf, sizeof(procbuf), "%s", proc_empty ? pkg : proc);
        if (req == DENY_ADD) {
            if (!deny_validate(pkg, 0) || !deny_validate(procbuf, 1)) {
                res = DENYRESP_INVALID_PKG;
            } else {
                pthread_mutex_lock(&g_db_lock);
                res = DENYRESP_ERROR;
                if (g_db) {
                    sqlite3_stmt *st = NULL;
                    int exists = 0;
                    if (p_prepare_v2(g_db, "SELECT 1 FROM denylist WHERE package_name=? AND process=?", -1, &st, NULL) == SQLITE_OK && st) {
                        p_bind_text(st, 1, pkg, -1, SQLITE_TRANSIENT);
                        p_bind_text(st, 2, procbuf, -1, SQLITE_TRANSIENT);
                        exists = p_step(st) == SQLITE_ROW;
                        p_finalize(st);
                    }
                    if (exists) res = DENYRESP_ITEM_EXIST;
                    else {
                        sqlite3_stmt *ins = NULL;
                        if (p_prepare_v2(g_db, "INSERT INTO denylist (package_name, process) VALUES(?, ?)", -1, &ins, NULL) == SQLITE_OK && ins) {
                            p_bind_text(ins, 1, pkg, -1, SQLITE_TRANSIENT);
                            p_bind_text(ins, 2, procbuf, -1, SQLITE_TRANSIENT);
                            res = p_step(ins) == SQLITE_DONE ? DENYRESP_OK : DENYRESP_ERROR;
                            p_finalize(ins);
                        }
                    }
                }
                pthread_mutex_unlock(&g_db_lock);
                if (res == DENYRESP_OK) slog("denylist add: [%s/%s]", pkg, procbuf);
            }
        } else {
            pthread_mutex_lock(&g_db_lock);
            res = DENYRESP_ERROR;
            if (g_db) {
                sqlite3_stmt *st = NULL;
                const char *sql = proc_empty
                    ? "DELETE FROM denylist WHERE package_name=?"
                    : "DELETE FROM denylist WHERE package_name=? AND process=?";
                if (p_prepare_v2(g_db, sql, -1, &st, NULL) == SQLITE_OK && st) {
                    p_bind_text(st, 1, pkg, -1, SQLITE_TRANSIENT);
                    if (!proc_empty) p_bind_text(st, 2, procbuf, -1, SQLITE_TRANSIENT);
                    if (p_step(st) == SQLITE_DONE)
                        res = p_changes(g_db) > 0 ? DENYRESP_OK : DENYRESP_ITEM_NOT_EXIST;
                    p_finalize(st);
                }
            }
            pthread_mutex_unlock(&g_db_lock);
            if (res == DENYRESP_OK) slog("denylist rm: [%s/%s]", pkg, procbuf);
        }
        free(pkg);
        free(proc);
    } else if (req == DENY_LIST) {
        write_i32(fd, DENYRESP_OK);
        pthread_mutex_lock(&g_db_lock);
        if (g_db) {
            sqlite3_stmt *st = NULL;
            if (p_prepare_v2(g_db, "SELECT package_name, process FROM denylist ORDER BY package_name, process", -1, &st, NULL) == SQLITE_OK && st) {
                while (p_step(st) == SQLITE_ROW) {
                    const unsigned char *pkg = p_column_text(st, 0);
                    const unsigned char *proc = p_column_text(st, 1);
                    char line[600];
                    snprintf(line, sizeof(line), "%s|%s",
                             pkg ? (const char *)pkg : "", proc ? (const char *)proc : "");
                    write_string(fd, line);
                }
                p_finalize(st);
            }
        }
        pthread_mutex_unlock(&g_db_lock);
        write_string(fd, "");
        return; /* ls_list returns without the trailing response int */
    }
    write_i32(fd, res);
}

/* ---- the manager conversation (core/su/connect.rs), read line by line for this batch ----
 * When a request's policy is Query, the daemon asks the manager app: it creates a FIFO at
 * <tmp>/.magisk/su_request_<client pid> (0600, owned by the manager uid), launches the app
 * through app_process ("am start" with action/fifo/uid/pid extras), and reads one big-endian
 * i32 SuPolicy back from the FIFO, waiting at most 70 seconds; any failure is a Deny. Log and
 * notify go the other way as fire-and-forget calls (content provider first, intent second)
 * from a detached child. The manager is the package in strings."requester", else the stock
 * com.topjohnwu.magisk; its uid is the owner of its data directory (package.rs get_manager
 * resolves the same pair through the package manager, which the guest answers too, but the
 * data-dir owner is the same fact read without a binder round-trip). */

static int sdk_int(void) {
    char prop[16];
    get_prop("ro.build.version.sdk", prop, sizeof(prop));
    return atoi(prop);
}

static void manager_info(char *pkg, size_t pkg_cap, int *uid_out, int user) {
    db_get_string("requester", pkg, pkg_cap);
    if (!pkg[0]) snprintf(pkg, pkg_cap, "com.topjohnwu.magisk");
    *uid_out = -1;
    char path[PATH_MAX];
    struct stat st;
    snprintf(path, sizeof(path), "/data/data/%s", pkg);
    if (stat(path, &st) == 0) { *uid_out = (int)st.st_uid; return; }
    snprintf(path, sizeof(path), "/data/user/%d/%s", user, pkg);
    if (stat(path, &st) == 0) *uid_out = (int)st.st_uid;
}

/* Runs /system/bin/app_process with the given argv (argv[0] is the class path directory,
 * argv[1] the class, as the original's Command does), CLASSPATH set, output captured into
 * out. Bounded, unlike the original's output(): a wedged app_process must not pile up
 * detached children; the bound only ever bites where the original would hang forever. */
static int run_app_process(char *const argv[], const char *classpath, char *out, size_t cap, int timeout_ms) {
    int pipefd[2];
    if (pipe(pipefd) != 0) return -1;
    pid_t pid = fork();
    if (pid < 0) { close(pipefd[0]); close(pipefd[1]); return -1; }
    if (pid == 0) {
        unblock_signals();
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[0]);
        if (pipefd[1] > 2) close(pipefd[1]);
        int nullfd = open("/dev/null", O_RDONLY);
        if (nullfd >= 0) { dup2(nullfd, STDIN_FILENO); if (nullfd > 2) close(nullfd); }
        setenv("CLASSPATH", classpath, 1);
        execv("/system/bin/app_process", argv);
        _exit(127);
    }
    close(pipefd[1]);
    size_t used = 0;
    int waited = 0, rc = -1;
    for (;;) {
        struct pollfd pfd = { pipefd[0], POLLIN, 0 };
        int pr = poll(&pfd, 1, 100);
        if (pr > 0 && (pfd.revents & (POLLIN | POLLHUP))) {
            char buf[1024];
            ssize_t n = read(pipefd[0], buf, sizeof(buf));
            if (n > 0) {
                size_t room = cap - 1 - used;
                size_t take = (size_t)n < room ? (size_t)n : room;
                memcpy(out + used, buf, take);
                used += take;
            } else if (n == 0) { rc = 0; break; }
        } else if (pr == 0) {
            waited += 100;
            if (waited >= timeout_ms) { kill(pid, SIGKILL); break; }
        } else if (errno != EINTR) break;
        if (pfd.revents & POLLHUP && pr > 0) { /* drain on the next pass, then EOF */ }
    }
    close(pipefd[0]);
    int status = 0;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) { }
    if (cap) out[used < cap ? used : cap - 1] = '\0';
    return rc;
}

/* app_request(): ask the manager, return the SuPolicy it answers (Deny on any failure). */
static int app_request(int client_pid, int eval_uid, const char *mgr_pkg, int mgr_uid) {
    char fifo[PATH_MAX];
    snprintf(fifo, sizeof(fifo), "%s/.magisk/su_request_%d", magisk_tmp(), client_pid);
    unlink(fifo);
    if (mkfifo(fifo, 0600) != 0) return SU_DENY;
    if (chown(fifo, (uid_t)mgr_uid, (uid_t)mgr_uid)) { /* best effort, like the mode */ }

    char user[16], uid_s[16], pid_s[16];
    snprintf(user, sizeof(user), "%d", eval_uid / 100000);
    snprintf(uid_s, sizeof(uid_s), "%d", eval_uid);
    snprintf(pid_s, sizeof(pid_s), "%d", client_pid);
    char *argv[] = {
        (char *)"app_process", (char *)"/system/bin", (char *)"com.android.commands.am.Am",
        (char *)"start", (char *)"-p", (char *)mgr_pkg, (char *)"--user", user,
        (char *)"-a", (char *)"android.intent.action.VIEW", (char *)"-f", (char *)"0x18800020",
        (char *)"--es", (char *)"action", (char *)"request",
        (char *)"--es", (char *)"fifo", fifo,
        (char *)"--ei", (char *)"uid", uid_s,
        (char *)"--ei", (char *)"pid", pid_s,
        NULL
    };
    /* The original retries "am start" until it prints something; bounded here (the FIFO
     * poll below is the real gate, and it has the official 70 seconds). */
    char out[2048];
    for (int i = 0; i < 5; i++) {
        out[0] = '\0';
        if (run_app_process(argv, "/system/framework/am.jar", out, sizeof(out), 10000) == 0 && out[0]) break;
        struct timespec ts = { 0, 200 * 1000 * 1000 };
        nanosleep(&ts, NULL);
    }

    int fd = open(fifo, O_RDWR | O_CLOEXEC);
    if (fd < 0) { unlink(fifo); return SU_DENY; }
    struct pollfd pfd = { fd, POLLIN, 0 };
    int pr;
    do { pr = poll(&pfd, 1, 70 * 1000); } while (pr < 0 && errno == EINTR);
    unlink(fifo);
    int policy = SU_DENY;
    if (pr > 0 && (pfd.revents & POLLIN)) {
        uint8_t b[4];
        size_t got = 0;
        while (got < 4) {
            ssize_t n = read(fd, b + got, 4 - got);
            if (n > 0) got += (size_t)n;
            else if (n < 0 && errno == EINTR) continue;
            else break;
        }
        if (got == 4) {
            int32_t v = (int32_t)((b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]);
            if (v >= SU_QUERY && v <= SU_RESTRICT) policy = v;
        }
    }
    close(fd);
    return policy;
}

/* One extra of the log/notify calls, in the original's three shapes. */
struct su_extra { const char *key; int kind; long num; const char *str; };
enum { EX_INT = 0, EX_BOOL = 1, EX_STR = 2, EX_INTLIST = 3 };

static void extra_bind(char *dst, size_t cap, const struct su_extra *e, int legacy) {
    if (e->kind == EX_INT) snprintf(dst, cap, "%s:i:%ld", e->key, e->num);
    else if (e->kind == EX_BOOL) snprintf(dst, cap, "%s:b:%s", e->key, e->num ? "true" : "false");
    else if (e->kind == EX_INTLIST) snprintf(dst, cap, "%s:s:%s", e->key, e->str);
    else if (legacy) snprintf(dst, cap, "%s:s:%s", e->key, e->str);
    else {
        /* the SDK 30+ binding escapes backslash and colon in string values */
        char esc[1024];
        size_t o = 0;
        for (const char *p = e->str; *p && o + 2 < sizeof(esc); p++) {
            if (*p == '\\' || *p == ':') esc[o++] = '\\';
            esc[o++] = *p;
        }
        esc[o] = '\0';
        snprintf(dst, cap, "%s:s:%s", e->key, esc);
    }
}

/* app_log()/app_notify(): runs in a detached child; never returns. Provider call first
 * (its output must not contain "Error"), the intent form as the fallback. */
static void app_notify_log(int is_log, const char *mgr_pkg, int eval_uid,
                           int from_uid, int client_pid, int policy,
                           int target_uid, int target_pid, const char *context,
                           const char *command, const uint32_t *gids, int gid_count,
                           int notify_flag) {
    char gidstr[256] = "";
    for (int i = 0; i < gid_count; i++) {
        char tmp[24];
        snprintf(tmp, sizeof(tmp), "%s%u", i ? "," : "", gids[i]);
        strncat(gidstr, tmp, sizeof(gidstr) - strlen(gidstr) - 1);
    }
    struct su_extra ex[10];
    int n = 0;
    ex[n++] = (struct su_extra){ "from.uid", EX_INT, from_uid, NULL };
    if (is_log) ex[n++] = (struct su_extra){ "to.uid", EX_INT, target_uid, NULL };
    ex[n++] = (struct su_extra){ "pid", EX_INT, client_pid, NULL };
    ex[n++] = (struct su_extra){ "policy", EX_INT, policy, NULL };
    if (is_log) {
        ex[n++] = (struct su_extra){ "target", EX_INT, target_pid, NULL };
        ex[n++] = (struct su_extra){ "context", EX_STR, 0, context };
        ex[n++] = (struct su_extra){ "gids", EX_INTLIST, 0, gidstr };
        ex[n++] = (struct su_extra){ "command", EX_STR, 0, command };
        ex[n++] = (struct su_extra){ "notify", EX_BOOL, notify_flag, NULL };
    }
    const char *method = is_log ? "log" : "notify";
    char user[16];
    snprintf(user, sizeof(user), "%d", eval_uid / 100000);
    int legacy = sdk_int() < 30;

    char uri[PATH_MAX];
    snprintf(uri, sizeof(uri), "content://%s.provider", mgr_pkg);
    char binds[10][1100];
    char *pargv[24];
    int pi = 0;
    pargv[pi++] = (char *)"app_process";
    pargv[pi++] = (char *)"/system/bin";
    pargv[pi++] = (char *)"com.android.commands.content.Content";
    pargv[pi++] = (char *)"call";
    pargv[pi++] = (char *)"--uri"; pargv[pi++] = uri;
    pargv[pi++] = (char *)"--user"; pargv[pi++] = user;
    pargv[pi++] = (char *)"--method"; pargv[pi++] = (char *)method;
    for (int i = 0; i < n; i++) {
        extra_bind(binds[i], sizeof(binds[i]), &ex[i], legacy);
        pargv[pi++] = (char *)"--extra"; pargv[pi++] = binds[i];
    }
    pargv[pi] = NULL;
    char out[2048];
    out[0] = '\0';
    if (run_app_process(pargv, "/system/framework/content.jar", out, sizeof(out), 15000) == 0 &&
        !strstr(out, "Error"))
        _exit(0); /* the provider call succeeded */

    char vals[10][1100];
    char *iargv[32];
    int ii = 0;
    iargv[ii++] = (char *)"app_process";
    iargv[ii++] = (char *)"/system/bin";
    iargv[ii++] = (char *)"com.android.commands.am.Am";
    iargv[ii++] = (char *)"start";
    iargv[ii++] = (char *)"-p"; iargv[ii++] = (char *)mgr_pkg;
    iargv[ii++] = (char *)"--user"; iargv[ii++] = user;
    iargv[ii++] = (char *)"-a"; iargv[ii++] = (char *)"android.intent.action.VIEW";
    iargv[ii++] = (char *)"-f"; iargv[ii++] = (char *)"0x18800020";
    iargv[ii++] = (char *)"--es"; iargv[ii++] = (char *)"action"; iargv[ii++] = (char *)method;
    for (int i = 0; i < n; i++) {
        const struct su_extra *e = &ex[i];
        if (e->kind == EX_INT) {
            snprintf(vals[i], sizeof(vals[i]), "%ld", e->num);
            iargv[ii++] = (char *)"--ei";
        } else if (e->kind == EX_BOOL) {
            snprintf(vals[i], sizeof(vals[i]), "%s", e->num ? "true" : "false");
            iargv[ii++] = (char *)"--ez";
        } else {
            snprintf(vals[i], sizeof(vals[i]), "%s", e->kind == EX_INTLIST ? gidstr : e->str);
            iargv[ii++] = (char *)"--es";
        }
        iargv[ii++] = (char *)e->key;
        iargv[ii++] = vals[i];
    }
    iargv[ii] = NULL;
    out[0] = '\0';
    run_app_process(iargv, "/system/framework/am.jar", out, sizeof(out), 15000);
    _exit(0);
}

struct su_request {
    int32_t target_uid;
    int32_t target_pid;
    int login, keep_env, drop_cap;
    char *shell, *command, *context;
    uint32_t gids[32];
    int gid_count;
};

static void free_su_request(struct su_request *r) {
    free(r->shell); free(r->command); free(r->context);
}

static int read_su_request(int fd, struct su_request *r) {
    memset(r, 0, sizeof(*r));
    int32_t v;
    uint8_t b;
    if (read_i32(fd, &r->target_uid) || read_i32(fd, &r->target_pid)) return -1;
    if (read_all(fd, &b, 1)) return -1;
    r->login = b != 0;
    if (read_all(fd, &b, 1)) return -1;
    r->keep_env = b != 0;
    if (read_all(fd, &b, 1)) return -1;
    r->drop_cap = b != 0;
    if (!(r->shell = read_string(fd))) return -1;
    if (!(r->command = read_string(fd))) { free_su_request(r); return -1; }
    if (!(r->context = read_string(fd))) { free_su_request(r); return -1; }
    if (read_i32(fd, &v) || v < 0 || v > 32) { free_su_request(r); return -1; }
    r->gid_count = v;
    for (int i = 0; i < v; i++) {
        uint32_t g;
        if (read_all(fd, &g, 4)) { free_su_request(r); return -1; }
        r->gids[i] = g;
    }
    return 0;
}

/* The stored half of the policy decision (build_su_info + RootSettings, core/su/db.rs):
 * the multiuser evaluation and the policies row. RootSettings' defaults are Query with
 * logging and notification off. The manager conversation and the root_access gates are
 * applied by handle_superuser, in the original's order. */
struct su_lookup_result { int policy, log, notify, eval_uid; };

static void su_lookup(int uid, struct su_lookup_result *r) {
    r->policy = SU_QUERY;
    r->log = 0;
    r->notify = 0;
    r->eval_uid = uid;
    if (uid == AID_ROOT) { r->policy = SU_ALLOW; return; }
    int multiuser = db_get_setting("multiuser_mode", 0);      /* OwnerOnly */
    if (multiuser == 0) {                                     /* OwnerOnly */
        if (uid / 100000 != 0) { r->policy = SU_DENY; return; }
    } else if (multiuser == 1) {                              /* OwnerManaged */
        r->eval_uid = uid % 100000;
    }
    pthread_mutex_lock(&g_db_lock);
    if (g_db) {
        sqlite3_stmt *st = NULL;
        if (p_prepare_v2(g_db, "SELECT policy, logging, notification FROM policies WHERE uid=? AND (until=0 OR until>strftime('%s','now'))", -1, &st, NULL) == SQLITE_OK && st) {
            p_bind_int64(st, 1, r->eval_uid);
            if (p_step(st) == SQLITE_ROW) {
                r->policy = (int)p_column_int64(st, 0);
                r->log = (int)p_column_int64(st, 1) != 0;
                r->notify = (int)p_column_int64(st, 2) != 0;
            }
            p_finalize(st);
        }
    }
    pthread_mutex_unlock(&g_db_lock);
}

static void drop_caps(void) {
    /* su.cpp drop_caps: drop the bounding set except CAP_SETUID (7), then SECBIT_NOROOT so
     * the exec keeps no capabilities. Drops past the last valid capability fail with EINVAL
     * and are ignored, like the original's scan. */
    for (int cap = 0; cap <= 40; cap++) {
        if (cap == 7 /* CAP_SETUID */) continue;
        prctl(PR_CAPBSET_DROP, cap, 0, 0, 0);
    }
    prctl(PR_SET_SECUREBITS, 0x10 /* SECBIT_NOROOT */, 0, 0, 0);
}

/* The daemon-side shell runner: the second half of the su conversation. Runs in a forked
 * child of the connection thread; never returns. */
static void exec_root_shell(int client, int client_pid, struct su_request *req) {
    setsid();
    int infd = recv_fd(client);
    int outfd = recv_fd(client);
    int errfd = recv_fd(client);
    int ptsfd = -1;
    if (infd < 0 || outfd < 0 || errfd < 0) {
        /* A stream is a tty: the app side needs a pty (su.cpp exec_root_shell). */
        int ptmx = open("/dev/ptmx", O_RDWR);
        if (ptmx < 0) _exit(1);
        grantpt(ptmx);
        unlockpt(ptmx);
        send_fd(client, ptmx);
        char *slave = ptsname(ptmx);
        if (!slave) _exit(1);
        ptsfd = open(slave, O_RDWR);
        if (ptsfd < 0) _exit(1);
        close(ptmx);
    }
    dup2(infd < 0 ? ptsfd : infd, STDIN_FILENO);
    dup2(outfd < 0 ? ptsfd : outfd, STDOUT_FILENO);
    dup2(errfd < 0 ? ptsfd : errfd, STDERR_FILENO);
    if (infd > 2) close(infd);
    if (outfd > 2) close(outfd);
    if (errfd > 2) close(errfd);
    if (ptsfd > 2) close(ptsfd);
    close(client);

    /* The client's working directory and environment, like the original. */
    char path[64], cwd[PATH_MAX];
    snprintf(path, sizeof(path), "/proc/%d/cwd", client_pid);
    ssize_t cl = readlink(path, cwd, sizeof(cwd) - 1);
    if (cl > 0) { cwd[cl] = '\0'; if (chdir(cwd)) { /* keep ours */ } }
    snprintf(path, sizeof(path), "/proc/%d/environ", client_pid);
    FILE *ef = fopen(path, "rb");
    if (ef) {
        char env[16384];
        size_t used = fread(env, 1, sizeof(env) - 1, ef);
        env[used] = '\0';
        fclose(ef);
        clearenv();
        for (size_t pos = 0; pos < used;) {
            putenv(env + pos);
            pos += strlen(env + pos) + 1;
        }
    }
    if (!req->keep_env) {
        struct passwd *pw = getpwuid((uid_t)req->target_uid);
        if (pw) {
            setenv("HOME", pw->pw_dir, 1);
            setenv("USER", pw->pw_name, 1);
            setenv("LOGNAME", pw->pw_name, 1);
            setenv("SHELL", req->shell, 1);
        }
    }
    umask(022);
    if (req->drop_cap) drop_caps();
    unblock_signals();
    const char *argv[4] = { NULL, NULL, NULL, NULL };
    argv[0] = req->login ? "-" : req->shell;
    if (req->command && req->command[0]) { argv[1] = "-c"; argv[2] = req->command; }
    execvp(req->shell, (char *const *)argv);
    fprintf(stderr, "Cannot execute %s: %s\n", req->shell, strerror(errno));
    _exit(1);
}

static void handle_superuser(int fd, int client_uid, int raw_uid, int client_pid) {
    struct su_request req;
    if (read_su_request(fd, &req)) {
        write_i32(fd, SU_DENY);
        return;
    }
    struct su_lookup_result lk;
    su_lookup(client_uid, &lk);
    /* The manager is resolved only when the conversation can happen (the original resolves
     * it for Query, logging or notification), and the manager itself is silently allowed —
     * before the root_access gates, so it can always turn root back on. */
    char mgr_pkg[256] = "";
    int mgr_uid = -1;
    int need_mgr = lk.policy == SU_QUERY || lk.log || lk.notify;
    if (need_mgr) manager_info(mgr_pkg, sizeof(mgr_pkg), &mgr_uid, lk.eval_uid / 100000);
    if (mgr_uid >= 0 && client_uid % 100000 == mgr_uid % 100000) {
        lk.policy = SU_ALLOW;
    } else if (lk.policy != SU_DENY) {
        int root_access = db_get_setting("root_access", 3);       /* AppsAndAdb */
        if (root_access == 0) lk.policy = SU_DENY;                 /* Disabled */
        else if (root_access == 2 && client_uid != AID_SHELL) lk.policy = SU_DENY; /* AdbOnly */
        else if (root_access == 1 && client_uid == AID_SHELL) lk.policy = SU_DENY; /* AppsOnly */
    }
    /* connect_app: an undecided policy is put to the manager; with no manager installed
     * there is nobody to ask and the request is denied, as the original does. */
    if (lk.policy == SU_QUERY) {
        if (!need_mgr) manager_info(mgr_pkg, sizeof(mgr_pkg), &mgr_uid, lk.eval_uid / 100000);
        if (mgr_uid < 0) {
            slog("su: request from uid %d undecided and no manager is installed, denied", client_uid);
            lk.policy = SU_DENY;
        } else {
            lk.policy = app_request(client_pid, lk.eval_uid, mgr_pkg, mgr_uid);
            slog("su: manager answered policy %d for uid %d", lk.policy, client_uid);
        }
    }
    if ((lk.log || lk.notify) && mgr_uid >= 0) {
        pid_t p = fork();
        if (p == 0) {
            app_notify_log(lk.log, mgr_pkg, lk.eval_uid, raw_uid, client_pid, lk.policy,
                           req.target_uid, req.target_pid, req.context,
                           req.command && req.command[0] ? req.command : req.shell,
                           req.gids, req.gid_count, lk.notify);
            _exit(0);
        }
    }
    if (lk.policy == SU_DENY) {
        slog("su: request from uid %d rejected", client_uid);
        write_i32(fd, SU_DENY);
        free_su_request(&req);
        return;
    }
    if (lk.policy == SU_RESTRICT) req.drop_cap = 1;
    pid_t child = fork();
    if (child == 0) {
        /* Identity first, while a refusal can still be the ack the client reads: process
         * identity in the guest is the supervisor's business (it assigns ids at exec), so a
         * non-root target is attempted for real and refused honestly when it cannot be had —
         * never silently served as root. */
        if (req.target_uid != AID_ROOT) {
            if (req.gid_count > 0) setgroups((size_t)req.gid_count, req.gids);
            gid_t gid = req.gid_count > 0 ? (gid_t)req.gids[0] : (gid_t)req.target_uid;
            if (setresgid(gid, gid, gid) != 0) { /* the setresuid below is the gate that matters */ }
            if (setresuid((uid_t)req.target_uid, (uid_t)req.target_uid, (uid_t)req.target_uid) != 0) {
                slog("su: cannot switch to uid %d in this environment, denied", req.target_uid);
                write_i32(fd, SU_DENY);
                _exit(1);
            }
        } else if (req.gid_count > 0) {
            setgroups((size_t)req.gid_count, req.gids);
        }
        write_i32(fd, 0); /* ack */
        exec_root_shell(fd, client_pid, &req);
        _exit(1);
    }
    if (child < 0) {
        write_i32(fd, SU_DENY);
        free_su_request(&req);
        return;
    }
    int code = wait_for_pid(child, -1);
    write_i32(fd, code);
    free_su_request(&req);
}

/* ---- request dispatch (core/daemon.rs handle_requests) ------------------------------------ */

/* The caller's guest uid: SO_PEERCRED names the host process, whose ids the supervisor does
 * not fake; the guest's own /proc/<pid>/status is the supervisor's view and carries the guest
 * identity it assigned at exec. Prefer the guest view when it can be read. */
static int client_guest_uid(pid_t pid, uid_t cred_uid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/status", (int)pid);
    FILE *f = fopen(path, "r");
    if (!f) return (int)cred_uid;
    char line[256];
    int uid = (int)cred_uid;
    while (fgets(line, sizeof(line), f)) {
        if (!strncmp(line, "Uid:", 4)) {
            int v = -1;
            if (sscanf(line + 4, "%d", &v) == 1 && v >= 0) uid = v;
            break;
        }
    }
    fclose(f);
    return uid;
}

static void *client_main(void *arg) {
    int fd = (int)(intptr_t)arg;
    struct ucred cred;
    socklen_t clen = sizeof(cred);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &clen) != 0) { close(fd); return NULL; }
    int uid = client_guest_uid(cred.pid, cred.uid);
    int is_root = uid == AID_ROOT;
    int is_shell = uid == AID_SHELL;

    int32_t code;
    if (read_i32(fd, &code)) { close(fd); return NULL; }
    if (code < 0 || code >= REQ_END || code == 4 || code == 11) { close(fd); return NULL; }

    /* Permission checks, in the original's order. */
    switch (code) {
        case REQ_POST_FS_DATA: case REQ_LATE_START: case REQ_BOOT_COMPLETE:
        case REQ_ZYGOTE_RESTART: case REQ_SQLITE_CMD: case REQ_DENYLIST: case REQ_STOP_DAEMON:
            if (!is_root) { write_i32(fd, RESP_ROOT_REQUIRED); close(fd); return NULL; }
            break;
        case REQ_REMOVE_MODULES:
            if (!is_root && !is_shell) { write_i32(fd, RESP_ACCESS_DENIED); close(fd); return NULL; }
            break;
        case REQ_ZYGISK:
            /* Only a zygote-context caller may use it; contexts are synthetic here and the
             * substitute does not inject at all, so every caller gets the refusal. */
            write_i32(fd, RESP_ACCESS_DENIED); close(fd); return NULL;
        default: break;
    }

    /* P1 requests are refused at the response word instead of being faked. */
    if (code == REQ_STOP_DAEMON || code == REQ_REMOVE_MODULES) {
        write_i32(fd, RESP_ERROR);
        close(fd);
        return NULL;
    }

    if (write_i32(fd, RESP_OK)) { close(fd); return NULL; }

    switch (code) {
        case REQ_CHECK_VERSION:
            write_string(fd, SUBST_VERSION ":MAGISK:R");
            break;
        case REQ_CHECK_VERSION_CODE:
            write_i32(fd, SUBST_VER_CODE);
            break;
        case REQ_START_DAEMON:
            break; /* the original only sets up the log file here; ours is already up */
        case REQ_SUPERUSER:
            handle_superuser(fd, uid, (int)cred.uid, (int)cred.pid);
            break;
        case REQ_DENYLIST:
            handle_denylist(fd);
            break;
        case REQ_SQLITE_CMD:
            handle_sqlite_cmd(fd);
            break;
        case REQ_ZYGOTE_RESTART:
            slog("** zygote restarted");
            break;
        case REQ_POST_FS_DATA:
            pthread_mutex_lock(&g_stage_lock);
            if (!g_pfs_done) stage_post_fs_data();
            pthread_mutex_unlock(&g_stage_lock);
            break;
        case REQ_LATE_START:
            pthread_mutex_lock(&g_stage_lock);
            stage_late_start();
            pthread_mutex_unlock(&g_stage_lock);
            break;
        case REQ_BOOT_COMPLETE:
            pthread_mutex_lock(&g_stage_lock);
            if (!g_boot_done) stage_boot_complete();
            pthread_mutex_unlock(&g_stage_lock);
            break;
        default: break;
    }
    close(fd);
    return NULL;
}

static void daemon_entry(void) {
    prctl(PR_SET_NAME, "magiskd", 0, 0, 0);
    /* SIGCHLD is collected by the reaper thread via sigwait, SIGPIPE must never kill a
     * handler thread: block both before any thread or child exists. */
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, SIGCHLD);
    sigaddset(&set, SIGPIPE);
    pthread_sigmask(SIG_BLOCK, &set, NULL);
    pthread_t reaper;
    if (pthread_create(&reaper, NULL, reaper_main, NULL) == 0) pthread_detach(reaper);
    setsid();
    int nullfd = open("/dev/null", O_RDWR);
    if (nullfd >= 0) {
        dup2(nullfd, STDIN_FILENO);
        dup2(nullfd, STDOUT_FILENO);
        dup2(nullfd, STDERR_FILENO);
        if (nullfd > 2) close(nullfd);
    }
    mkdir(SECURE_DIR, 0700);
    db_open_and_init();
    slog("Magisk %s(%d) daemon started [aemu substitute]", SUBST_VERSION, SUBST_VER_CODE);

    char spath[PATH_MAX];
    socket_path(spath, sizeof(spath));
    unlink(spath);
    int srv = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (srv < 0) { slog("daemon: socket: %s", strerror(errno)); exit(1); }
    struct sockaddr_un un = {0};
    un.sun_family = AF_UNIX;
    snprintf(un.sun_path, sizeof(un.sun_path), "%s", spath);
    if (bind(srv, (struct sockaddr *)&un, sizeof(un)) != 0) {
        slog("daemon: bind %s: %s", spath, strerror(errno));
        exit(1);
    }
    chmod(spath, 0666);
    listen(srv, 32);
    for (;;) {
        int c = accept4(srv, NULL, NULL, SOCK_CLOEXEC);
        if (c < 0) {
            if (errno == EINTR) continue;
            slog("daemon: accept: %s", strerror(errno));
            exit(1);
        }
        pthread_t th;
        if (pthread_create(&th, NULL, client_main, (void *)(intptr_t)c) == 0)
            pthread_detach(th);
        else close(c);
    }
}

/* ---- client side: connect_daemon (core/daemon.rs), with the original's create=true: a
 * root caller whose connect fails starts the daemon itself, so the host never has to exec
 * --daemon; the first root client of a boot (the host's stage seat) wakes it. A refused
 * request (a response code) is final and never triggers a spawn. --------------------------- */

static int g_connect_errno = 0;

static int connect_once(int code, int *refused) {
    *refused = 0;
    char spath[PATH_MAX];
    socket_path(spath, sizeof(spath));
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un un = {0};
    un.sun_family = AF_UNIX;
    snprintf(un.sun_path, sizeof(un.sun_path), "%s", spath);
    if (connect(fd, (struct sockaddr *)&un, sizeof(un)) != 0) {
        g_connect_errno = errno;
        close(fd);
        return -1;
    }
    if (write_i32(fd, code)) { close(fd); return -1; }
    int32_t res;
    if (read_i32(fd, &res)) { close(fd); return -1; }
    switch (res) {
        case RESP_OK: return fd;
        case RESP_ROOT_REQUIRED: fprintf(stderr, "Root is required for this operation\n"); break;
        case RESP_ACCESS_DENIED: fprintf(stderr, "Accessed denied\n"); break;
        default: fprintf(stderr, "Daemon error\n"); break;
    }
    *refused = 1;
    close(fd);
    return -1;
}

static int connect_daemon(int code) {
    int refused = 0;
    int fd = connect_once(code, &refused);
    if (fd >= 0 || refused) return fd;
    if (getuid() == 0) {
        pid_t pid = fork();
        if (pid == 0) {
            setsid();
            int nullfd = open("/dev/null", O_RDWR);
            if (nullfd >= 0) { dup2(nullfd, 0); dup2(nullfd, 1); dup2(nullfd, 2); }
            char *dargv[] = { (char *)"magisk", (char *)"--daemon", NULL };
            execv("/proc/self/exe", dargv);
            _exit(127);
        }
        if (pid > 0) {
            int status;
            while (waitpid(pid, &status, 0) < 0 && errno == EINTR) { }
            fd = connect_once(code, &refused);
            if (fd >= 0 || refused) return fd;
        }
    }
    fprintf(stderr, "Cannot connect to daemon: %s (os error %d)\n",
            strerror(g_connect_errno), g_connect_errno);
    return -1;
}

/* ---- the CLI (core/magisk.rs MagiskAction) ------------------------------------------------- */

static void usage(FILE *out) {
    fprintf(out,
        "Magisk %s (%d) [aemu substitute] - Multi-purpose Utility\n"
        "\n"
        "Usage: magisk [applet [arguments]...]\n"
        "   or: magisk [options]...\n"
        "\n"
        "Options:\n"
        "   -c                        print current binary version\n"
        "   -v                        print running daemon version\n"
        "   -V                        print running daemon version code\n"
        "   --list                    list all available applets\n"
        "   --remove-modules [-n]     remove all modules (P1 in the substitute)\n"
        "   --install-module ZIP      install a module\n"
        "   --daemon                  start the daemon (the host normally does this)\n"
        "   --stop                    stop the daemon (P1 in the substitute)\n"
        "   --post-fs-data            run post-fs-data stage\n"
        "   --service                 run late_start service stage\n"
        "   --boot-complete           run boot-complete stage\n"
        "   --zygote-restart          notify the daemon that zygote restarted\n"
        "   --unlock-blocks           set all block devices to read-write\n"
        "   --restorecon              restore SELinux context on Magisk files\n"
        "   --clone-attr FROM TO      clone attributes from one file to another\n"
        "   --clone FROM TO           clone a file and its attributes\n"
        "   --sqlite SQL              exec SQL commands to the Magisk database\n"
        "   --path                    print the Magisk tmpfs path\n"
        "   --denylist [action ...]   denylist configuration\n"
        "   --preinit-device          print the preinit device (none here)\n"
        "\n"
        "Available applets:\n"
        "    su, resetprop\n", SUBST_VERSION, SUBST_VER_CODE);
}

/* --denylist (core/deny/cli.cpp): same messages and return shapes. */
static int denylist_cli(int argc, char *argv[]) {
    if (argc == 0) { usage(stderr); return 1; }
    int req;
    if (!strcmp(argv[0], "enable")) req = DENY_ENFORCE;
    else if (!strcmp(argv[0], "disable")) req = DENY_DISABLE;
    else if (!strcmp(argv[0], "add")) req = DENY_ADD;
    else if (!strcmp(argv[0], "rm")) req = DENY_REMOVE;
    else if (!strcmp(argv[0], "ls")) req = DENY_LIST;
    else if (!strcmp(argv[0], "status")) req = DENY_STATUS;
    else if (!strcmp(argv[0], "exec")) {
        /* Mount-namespace isolation does not exist here; refuse instead of pretending. */
        fprintf(stderr, "denylist exec: mount namespace isolation is not supported in this environment\n");
        return 1;
    } else { usage(stderr); return 1; }
    if ((req == DENY_ADD || req == DENY_REMOVE) && argc < 2) { usage(stderr); return 1; }

    int fd = connect_daemon(REQ_DENYLIST);
    if (fd < 0) return 1;
    write_i32(fd, req);
    if (req == DENY_ADD || req == DENY_REMOVE) {
        write_string(fd, argv[1]);
        write_string(fd, argc > 2 ? argv[2] : "");
    }
    int32_t res;
    if (read_i32(fd, &res)) { close(fd); return 1; }
    if (res < 0 || res >= 8) res = DENYRESP_ERROR;
    switch (res) {
        case DENYRESP_NOT_ENFORCED: fprintf(stderr, "Denylist is not enforced\n"); break;
        case DENYRESP_ENFORCED: fprintf(stderr, "Denylist is enforced\n"); break;
        case DENYRESP_ITEM_EXIST: fprintf(stderr, "Target already exists in denylist\n"); break;
        case DENYRESP_ITEM_NOT_EXIST: fprintf(stderr, "Target does not exist in denylist\n"); break;
        case DENYRESP_NO_NS: fprintf(stderr, "The kernel does not support mount namespace\n"); break;
        case DENYRESP_INVALID_PKG: fprintf(stderr, "Invalid package / process name\n"); break;
        case DENYRESP_ERROR: fprintf(stderr, "deny: Daemon error\n"); close(fd); return -1;
        default: break;
    }
    if (req == DENY_LIST) {
        for (;;) {
            char *line = read_string(fd);
            if (!line || !line[0]) { free(line); break; }
            printf("%s\n", line);
            free(line);
        }
    }
    close(fd);
    return req == DENY_STATUS ? res != DENYRESP_ENFORCED : res != DENYRESP_OK;
}

static int clone_attr(const char *from, const char *to) {
    struct stat st;
    if (stat(from, &st) != 0) return 1;
    if (chmod(to, st.st_mode & 07777) != 0) return 1;
    if (chown(to, st.st_uid, st.st_gid) != 0) return 1;
    return 0;
}

static int clone_file(const char *from, const char *to) {
    int in = open(from, O_RDONLY);
    if (in < 0) return 1;
    int out = open(to, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (out < 0) { close(in); return 1; }
    char buf[65536];
    ssize_t n;
    int rc = 0;
    while ((n = read(in, buf, sizeof(buf))) > 0)
        if (write_all(out, buf, (size_t)n)) { rc = 1; break; }
    if (n < 0) rc = 1;
    close(in);
    close(out);
    if (!rc) rc = clone_attr(from, to);
    return rc;
}

/* --install-module (core/scripting.cpp install_module): a local busybox run of the payload's
 * util_functions.sh; the result lands in modules_update and merges at the next post-fs-data. */
static int install_module_cli(const char *zip) {
    if (getuid() != 0) { fprintf(stderr, "Run this command with root\n"); return 1; }
    char bb[PATH_MAX];
    busybox_path(bb, sizeof(bb));
    if (access(DATABIN, F_OK) || access(bb, X_OK) || access(DATABIN "/util_functions.sh", F_OK)) {
        fprintf(stderr, "Incomplete Magisk install\n");
        return 1;
    }
    if (access(zip, F_OK)) { fprintf(stderr, "'%s' does not exist\n", zip); return 1; }
    char *real = realpath(zip, NULL);
    setenv("OUTFD", "1", 1);
    setenv("ZIPFILE", real, 1);
    setenv("ASH_STANDALONE", "1", 1);
    setenv("MAGISKTMP", magisk_tmp(), 0);
    int nullfd = open("/dev/null", O_RDONLY);
    if (nullfd >= 0) { dup2(nullfd, STDERR_FILENO); if (nullfd > 2) close(nullfd); }
    const char *args[] = { bb, "sh", "-c", ". /data/adb/magisk/util_functions.sh\ninstall_module\nexit 0", NULL };
    execv(bb, (char *const *)args);
    fprintf(stdout, "Failed to execute BusyBox shell\n");
    return 1;
}

/* --daemon: when a daemon already answers, this is the START_DAEMON request; when none does,
 * the host's stage flow expects this command to be what brings it up (design 3.2). */
static int daemon_cli(void) {
    int fd = connect_daemon(REQ_START_DAEMON);
    if (fd >= 0) { close(fd); return 0; }
    pid_t pid = fork();
    if (pid < 0) return 1;
    if (pid == 0) {
        daemon_entry();
        _exit(1);
    }
    /* The parent stays until the socket is there, so the caller can rely on it. */
    char spath[PATH_MAX];
    socket_path(spath, sizeof(spath));
    for (int i = 0; i < 100; i++) {
        if (access(spath, F_OK) == 0) return 0;
        struct timespec ts = { 0, 50 * 1000 * 1000 };
        nanosleep(&ts, NULL);
    }
    return 0;
}

static int magisk_cli(int argc, char *argv[]) {
    if (argc < 2) { usage(stderr); return 1; }
    const char *a = argv[1];
    if (!strcmp(a, "-c")) {
        printf("%s:MAGISK:R (%d)\n", SUBST_VERSION, SUBST_VER_CODE);
        return 0;
    }
    if (!strcmp(a, "-v") || !strcmp(a, "-V")) {
        int fd = connect_daemon(!strcmp(a, "-v") ? REQ_CHECK_VERSION : REQ_CHECK_VERSION_CODE);
        if (fd < 0) return 1;
        if (!strcmp(a, "-v")) {
            char *ver = read_string(fd);
            close(fd);
            if (!ver) return 1;
            printf("%s\n", ver);
            free(ver);
        } else {
            int32_t code;
            int rc = read_i32(fd, &code);
            close(fd);
            if (rc) return 1;
            printf("%d\n", code);
        }
        return 0;
    }
    if (!strcmp(a, "--list")) { printf("su\nresetprop\n"); return 0; }
    if (!strcmp(a, "--daemon")) return daemon_cli();
    if (!strcmp(a, "--stop")) {
        int fd = connect_daemon(REQ_STOP_DAEMON);
        if (fd < 0) return 1;
        int32_t v;
        int rc = read_i32(fd, &v);
        close(fd);
        return rc ? 1 : v;
    }
    if (!strcmp(a, "--post-fs-data")) {
        int fd = connect_daemon(REQ_POST_FS_DATA);
        if (fd < 0) return 1;
        struct pollfd pfd = { fd, POLLIN, 0 };
        poll(&pfd, 1, 40000); /* POST_FS_DATA_WAIT_TIME, like the original */
        close(fd);
        return 0;
    }
    if (!strcmp(a, "--service")) {
        int fd = connect_daemon(REQ_LATE_START);
        if (fd < 0) return 1;
        close(fd);
        return 0;
    }
    if (!strcmp(a, "--boot-complete")) {
        int fd = connect_daemon(REQ_BOOT_COMPLETE);
        if (fd < 0) return 1;
        close(fd);
        return 0;
    }
    if (!strcmp(a, "--zygote-restart")) {
        int fd = connect_daemon(REQ_ZYGOTE_RESTART);
        if (fd < 0) return 1;
        close(fd);
        return 0;
    }
    if (!strcmp(a, "--remove-modules")) {
        int fd = connect_daemon(REQ_REMOVE_MODULES);
        if (fd < 0) return 1;
        uint8_t do_reboot = (argc < 3 || strcmp(argv[2], "-n")) ? 1 : 0;
        write_all(fd, &do_reboot, 1);
        int32_t v;
        int rc = read_i32(fd, &v);
        close(fd);
        return rc ? 1 : v;
    }
    if (!strcmp(a, "--install-module")) {
        if (argc < 3) { usage(stderr); return 1; }
        return install_module_cli(argv[2]);
    }
    if (!strcmp(a, "--sqlite")) {
        if (argc < 3) { usage(stderr); return 1; }
        int fd = connect_daemon(REQ_SQLITE_CMD);
        if (fd < 0) return 1;
        write_string(fd, argv[2]);
        for (;;) {
            char *line = read_string(fd);
            if (!line) { close(fd); return 1; }
            if (!line[0]) { free(line); break; }
            printf("%s\n", line);
            free(line);
        }
        close(fd);
        return 0;
    }
    if (!strcmp(a, "--path")) {
        const char *tmp = magisk_tmp();
        if (!tmp[0]) return 1;
        printf("%s\n", tmp);
        return 0;
    }
    if (!strcmp(a, "--denylist")) return denylist_cli(argc - 2, argv + 2);
    if (!strcmp(a, "--preinit-device")) return 1; /* no preinit partition exists here */
    if (!strcmp(a, "--unlock-blocks")) return 0;  /* no real block devices: a no-op success */
    if (!strcmp(a, "--restorecon")) return 0;     /* synthetic contexts: a no-op success */
    if (!strcmp(a, "--clone-attr")) {
        if (argc < 4) { usage(stderr); return 1; }
        return clone_attr(argv[2], argv[3]);
    }
    if (!strcmp(a, "--clone")) {
        if (argc < 4) { usage(stderr); return 1; }
        return clone_file(argv[2], argv[3]);
    }
    usage(stderr);
    return 1;
}

/* ---- the su applet (core/su/su.cpp su_client_main) ----------------------------------------- */

static volatile sig_atomic_t g_winch = 0;
static void on_winch(int sig) { (void)sig; g_winch = 1; }

static void su_usage(int code) {
    fprintf(stderr,
        "MagiskSU %s\n\n"
        "Usage: su [options] [-] [user [argument...]]\n\n"
        "Options:\n"
        "  -c, --command COMMAND         pass COMMAND to the invoked shell\n"
        "  -h, --help                    display this help message and exit\n"
        "  -i, --interactive             keep the shell interactive even with a command\n"
        "  -l, --login                   pretend the shell to be a login shell\n"
        "  -m, -p,\n"
        "      --preserve-environment    do not change environment variables\n"
        "  -s, --shell SHELL             use SHELL instead of the default /system/bin/sh\n"
        "  -v, --version                 print the version\n"
        "  -V                            print the version code\n"
        "  -Z, --context CONTEXT         the SELinux context (accepted, not applied here)\n"
        "  -M, --mount-master            run in the global mount namespace\n"
        "  -t, --target PID              use the mount namespace of PID\n"
        "  -g, --group GID               set the primary group\n"
        "  -G, --supp-group GID          add a supplementary group\n"
        "  -d, --drop-cap                drop capabilities (restricted shell)\n", SUBST_VERSION);
    exit(code);
}

static int su_client_main(int argc, char *argv[]) {
    static struct option long_opts[] = {
        { "command", required_argument, NULL, 'c' },
        { "help", no_argument, NULL, 'h' },
        { "login", no_argument, NULL, 'l' },
        { "preserve-environment", no_argument, NULL, 'p' },
        { "shell", required_argument, NULL, 's' },
        { "version", no_argument, NULL, 'v' },
        { "context", required_argument, NULL, 'Z' },
        { "mount-master", no_argument, NULL, 'M' },
        { "target", required_argument, NULL, 't' },
        { "group", required_argument, NULL, 'g' },
        { "supp-group", required_argument, NULL, 'G' },
        { "interactive", no_argument, NULL, 'i' },
        { "drop-cap", no_argument, NULL, 'd' },
        { NULL, 0, NULL, 0 }
    };
    struct su_request req;
    memset(&req, 0, sizeof(req));
    req.target_uid = AID_ROOT;
    req.target_pid = -1;
    req.shell = strdup("/system/bin/sh");
    req.command = strdup("");
    req.context = strdup("");
    int interactive = 0;

    for (int i = 0; i < argc; i++) {
        if (!strcmp(argv[i], "-cn") || !strcmp(argv[i], "-z")) strcpy(argv[i], "-Z");
        else if (!strcmp(argv[i], "-mm")) strcpy(argv[i], "-M");
    }
    int c;
    while ((c = getopt_long(argc, argv, "c:hlimpds:VvuZ:Mt:g:G:", long_opts, NULL)) != -1) {
        switch (c) {
            case 'c': {
                /* the command is everything from here on, joined with spaces (su.cpp) */
                size_t cap = 1;
                for (int i = optind - 1; i < argc; i++) cap += strlen(argv[i]) + 1;
                char *cmd = malloc(cap);
                cmd[0] = '\0';
                for (int i = optind - 1; i < argc; i++) {
                    if (cmd[0]) strcat(cmd, " ");
                    strcat(cmd, argv[i]);
                }
                free(req.command);
                req.command = cmd;
                optind = argc;
                break;
            }
            case 'h': su_usage(EXIT_SUCCESS);
            case 'i': interactive = 1; break;
            case 'l': req.login = 1; break;
            case 'm': case 'p': req.keep_env = 1; break;
            case 'd': req.drop_cap = 1; break;
            case 's': free(req.shell); req.shell = strdup(optarg); break;
            case 'V': printf("%d\n", SUBST_VER_CODE); return EXIT_SUCCESS;
            case 'v': printf("%s:MAGISKSU\n", SUBST_VERSION); return EXIT_SUCCESS;
            case 'Z': free(req.context); req.context = strdup(optarg); break;
            case 'M': case 't':
                if (req.target_pid != -1) { fprintf(stderr, "Can't use -M and -t at the same time\n"); su_usage(EXIT_FAILURE); }
                if (c == 'M') req.target_pid = 0;
                else {
                    char *end;
                    long v = strtol(optarg, &end, 10);
                    if (*optarg == '-' || end == optarg || *end) { fprintf(stderr, "Invalid PID: %s\n", optarg); su_usage(EXIT_FAILURE); }
                    req.target_pid = (int)v;
                }
                break;
            case 'g': case 'G': {
                char *end;
                long v = strtol(optarg, &end, 10);
                if (end == optarg || *end || v < 0) { fprintf(stderr, "Invalid GID: %s\n", optarg); su_usage(EXIT_FAILURE); }
                if (req.gid_count < 32) {
                    if (c == 'g') {
                        memmove(&req.gids[1], &req.gids[0], (size_t)req.gid_count * sizeof(uint32_t));
                        req.gids[0] = (uint32_t)v;
                    } else req.gids[req.gid_count] = (uint32_t)v;
                    req.gid_count++;
                }
                break;
            }
            default: fprintf(stderr, "\n"); su_usage(2);
        }
    }
    if (optind < argc && !strcmp(argv[optind], "-")) { req.login = 1; optind++; }
    if (optind < argc) {
        struct passwd *pw = getpwnam(argv[optind]);
        if (pw) req.target_uid = (int)pw->pw_uid;
        else req.target_uid = atoi(argv[optind]);
        optind++;
    }

    int fd = connect_daemon(REQ_SUPERUSER);
    if (fd < 0) return 1;

    /* Send the request (Encodable field order of SuRequest). */
    write_i32(fd, req.target_uid);
    write_i32(fd, req.target_pid);
    uint8_t b;
    b = req.login ? 1 : 0; write_all(fd, &b, 1);
    b = req.keep_env ? 1 : 0; write_all(fd, &b, 1);
    b = req.drop_cap ? 1 : 0; write_all(fd, &b, 1);
    write_string(fd, req.shell);
    write_string(fd, req.command);
    write_string(fd, req.context);
    write_i32(fd, req.gid_count);
    for (int i = 0; i < req.gid_count; i++) write_all(fd, &req.gids[i], 4);

    int32_t ack;
    if (read_i32(fd, &ack) || ack != 0) {
        fprintf(stderr, "%s\n", strerror(EACCES));
        close(fd);
        return EACCES;
    }

    interactive = interactive || !req.command[0];
    int atty = 0;
    if (isatty(STDIN_FILENO) && interactive) atty |= 1;
    if (isatty(STDOUT_FILENO) && interactive) atty |= 2;
    if (isatty(STDERR_FILENO) && interactive) atty |= 4;
    send_fd(fd, (atty & 1) ? -1 : STDIN_FILENO);
    send_fd(fd, (atty & 2) ? -1 : STDOUT_FILENO);
    send_fd(fd, (atty & 4) ? -1 : STDERR_FILENO);

    if (atty) {
        int ptmx = recv_fd(fd);
        if (ptmx >= 0) {
            struct termios orig;
            int have_term = tcgetattr(STDIN_FILENO, &orig) == 0;
            if (have_term) {
                struct termios raw = orig;
                cfmakeraw(&raw);
                tcsetattr(STDIN_FILENO, TCSANOW, &raw);
                struct winsize ws;
                if (ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) == 0) ioctl(ptmx, TIOCSWINSZ, &ws);
            }
            struct sigaction sa = {0};
            sa.sa_handler = on_winch;
            sigaction(SIGWINCH, &sa, NULL);
            int in_open = 1;
            for (;;) {
                struct pollfd pf[2] = {
                    { in_open ? STDIN_FILENO : -1, POLLIN, 0 },
                    { ptmx, POLLIN, 0 }
                };
                if (poll(pf, 2, 50) < 0) { if (errno == EINTR) continue; break; }
                if (g_winch) {
                    g_winch = 0;
                    struct winsize ws;
                    if (ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) == 0) ioctl(ptmx, TIOCSWINSZ, &ws);
                }
                if (pf[0].revents & POLLIN) {
                    char buf[4096];
                    ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
                    if (n <= 0) { in_open = 0; }
                    else if (write_all(ptmx, buf, (size_t)n)) break;
                }
                if (pf[0].revents & (POLLHUP | POLLERR)) in_open = 0;
                if (pf[1].revents & POLLIN) {
                    char buf[4096];
                    ssize_t n = read(ptmx, buf, sizeof(buf));
                    if (n <= 0) break;
                    if (write_all(STDOUT_FILENO, buf, (size_t)n)) break;
                }
                if (pf[1].revents & (POLLHUP | POLLERR)) {
                    /* drain what is left, then done */
                    char buf[4096];
                    ssize_t n;
                    while ((n = read(ptmx, buf, sizeof(buf))) > 0)
                        if (write_all(STDOUT_FILENO, buf, (size_t)n)) break;
                    break;
                }
            }
            if (have_term) tcsetattr(STDIN_FILENO, TCSANOW, &orig);
            close(ptmx);
        }
    }

    int32_t code;
    if (read_i32(fd, &code)) { close(fd); return 1; }
    close(fd);
    return code;
}

/* ---- applet dispatch (core/applets.cpp) ----------------------------------------------- */

static int resetprop_refusal(void) {
    fprintf(stderr, "resetprop: not supported by the aemu substitute yet (P1)\n");
    return 1;
}

static int magiskpolicy_refusal(void) {
    fprintf(stderr, "magiskpolicy: sepolicy live patching is not supported in this environment\n");
    return 1;
}

int main(int argc, char *argv[]) {
    if (argc < 1) return 1;
    umask(0);
    if (argv[0][0] == '\0') {
        /* Private applets (empty argv[0]): zygisk lives there; the substitute has none. */
        if (argc < 2) return 1;
        fprintf(stderr, "%s: applet not found\n", argv[1]);
        return 1;
    }
    const char *base = strrchr(argv[0], '/');
    base = base ? base + 1 : argv[0];
    if (!strcmp(base, "su")) return su_client_main(argc, argv);
    if (!strcmp(base, "resetprop")) return resetprop_refusal();
    if (!strcmp(base, "magiskpolicy") || !strcmp(base, "supolicy")) return magiskpolicy_refusal();
    if (!strcmp(base, "magisk") || !strcmp(base, "magisk64") || !strcmp(base, "magisk32")) {
        if (argc > 1 && argv[1][0] != '-') {
            if (!strcmp(argv[1], "su")) return su_client_main(argc - 1, argv + 1);
            if (!strcmp(argv[1], "resetprop")) return resetprop_refusal();
            fprintf(stderr, "%s: applet not found\n", argv[1]);
            return 1;
        }
        if (argc > 1 && !strcmp(argv[1], "--daemon")) return daemon_cli();
        return magisk_cli(argc, argv);
    }
    fprintf(stderr, "%s: applet not found\n", base);
    return 1;
}
