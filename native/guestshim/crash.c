/*
 * Мини-debuggerd для гостя.
 *
 * Настоящий debuggerd работает через ptrace, а под qemu-user его нет — падения native-кода остаются без
 * стека («Unable to open connection to debuggerd»). Здесь на SIGSEGV/SIGBUS/SIGILL/SIGFPE/SIGABRT/SIGSTKFLT
 * ставится свой обработчик: он пишет в журнал гостя (тег aemu_crash, видно в logcat и в журнале эмулятора)
 * регистры, адрес сбоя и все слова стека, похожие на адрес возврата в исполняемый код, в виде
 * «библиотека+смещение», после чего возвращает действие по умолчанию — процесс падает как обычно.
 *
 * Внутри обработчика — только системные вызовы и статические буферы.
 */

typedef unsigned int u32;

extern int sigaction(int sig, const void *act, void *old);

/* Addresses and registers are word-sized (ureg): the same source builds the 32-bit ARM
 * variant and the AArch64 one; only the syscall layer and the ucontext layout differ. */
typedef unsigned long ureg;

#if defined(__aarch64__)
__attribute__((naked, noinline)) static long cr_sys3(long n, long a, long b, long c) {
    __asm__ volatile("mov x8, x0; mov x0, x1; mov x1, x2; mov x2, x3; svc #0; ret");
}
__attribute__((naked, noinline)) static long cr_sys4(long n, long a, long b, long c, long d) {
    __asm__ volatile("mov x8, x0; mov x0, x1; mov x1, x2; mov x2, x3; mov x3, x4; svc #0; ret");
}
#define SYS_read 63
#define SYS_write 64
#define SYS_close 57
#define SYS_getpid 172
#define SYS_gettid 178
#define SYS_tgkill 131
#define SYS_getdents64 61
#define CROPEN(p, f) cr_sys4(56 /* openat */, -100 /* AT_FDCWD */, (long)(p), (f), 0)
#else
__attribute__((naked, noinline)) static long cr_sys3(long n, long a, long b, long c) {
    __asm__ volatile(
        "push {r7}; mov r7, r0; mov r0, r1; mov r1, r2; mov r2, r3; svc #0; pop {r7}; bx lr");
}
#define SYS_read 3
#define SYS_write 4
#define SYS_close 6
#define SYS_getpid 20
#define SYS_gettid 224
#define SYS_tgkill 268
#define SYS_getdents64 217
#define CROPEN(p, f) cr_sys3(5 /* open */, (long)(p), (f), 0)
#endif

/* ---- вывод ---- */

static char g_line[512];
static int g_len;

static void put(const char *s) { while (*s && g_len < (int)sizeof(g_line) - 1) g_line[g_len++] = *s++; }
static void puthex(ureg v) {
    char b[20]; b[0] = '0'; b[1] = 'x';
    int digits = (int)sizeof(ureg) * 2;
    for (int i = 0; i < digits; i++) { ureg d = (v >> ((digits - 1 - i) * 4)) & 15; b[2 + i] = (char)(d < 10 ? '0' + d : 'a' + d - 10); }
    b[2 + digits] = 0; put(b);
}
static void putdec(ureg v) {
    char b[24]; int i = 23; b[i] = 0;
    do { b[--i] = (char)('0' + v % 10); v /= 10; } while (v && i > 0);
    put(b + i);
}

static int g_logfd = -1;

/* одна запись журнала: [приоритет][тег]\0[сообщение]\0 — так пишет liblog гостя в файл /dev/log/main */
static void flush_line(void) {
    g_line[g_len] = 0;
    if (g_logfd >= 0) {
        static char rec[600];
        int n = 0;
        rec[n++] = 7; /* ANDROID_LOG_FATAL */
        const char *tag = "aemu_crash";
        while (*tag) rec[n++] = *tag++;
        rec[n++] = 0;
        for (int i = 0; i < g_len && n < (int)sizeof(rec) - 2; i++) rec[n++] = g_line[i];
        rec[n++] = 0;
        cr_sys3(SYS_write, g_logfd, (long)rec, n);
    }
    g_line[g_len] = '\n';
    cr_sys3(SYS_write, 2, (long)g_line, g_len + 1);
    g_len = 0;
}

/* ---- карта памяти ---- */

#define MAXMAP 1024
static char g_maps[256 * 1024];
static ureg g_ms[MAXMAP], g_me[MAXMAP], g_mo[MAXMAP];
static const char *g_mp[MAXMAP];
static int g_nmaps;
static ureg g_sp, g_stack_end;   /* граница отображения, где лежит sp (любые права) */

static ureg hexnum(const char **pp) {
    const char *p = *pp; ureg v = 0;
    for (;;) {
        char c = *p;
        if (c >= '0' && c <= '9') v = v * 16 + (ureg)(c - '0');
        else if (c >= 'a' && c <= 'f') v = v * 16 + (ureg)(c - 'a' + 10);
        else break;
        p++;
    }
    *pp = p; return v;
}

/* the map line holding sp, or the nearest lines around it (a stack that is gone, or a guard page) */
static const char *g_sp_map, *g_below, *g_above;
static ureg g_below_e, g_above_s;
static void load_maps(void) {
    g_nmaps = 0; g_sp_map = g_below = g_above = 0; g_below_e = 0; g_above_s = 0;
    long fd = CROPEN("/proc/self/maps", 0);
    if (fd < 0) return;
    long total = 0;
    for (;;) {
        long n = cr_sys3(SYS_read, fd, (long)(g_maps + total), (long)(sizeof(g_maps) - 1 - total));
        if (n <= 0) break;
        total += n;
        if (total >= (long)sizeof(g_maps) - 1) break;
    }
    cr_sys3(SYS_close, fd, 0, 0);
    g_maps[total] = 0;
    char *p = g_maps;
    while (*p && g_nmaps < MAXMAP) {
        char *line = p;
        while (*p && *p != '\n') p++;
        if (*p) *p++ = 0;
        const char *q = line;
        ureg s = hexnum(&q); if (*q != '-') continue; q++;
        ureg e = hexnum(&q); if (*q != ' ') continue; q++;
        int exec = q[2] == 'x';
        if (g_sp >= s && g_sp < e) { g_stack_end = e; g_sp_map = line; }
        if (e <= g_sp && e > g_below_e) { g_below_e = e; g_below = line; }
        if (s > g_sp && (!g_above || s < g_above_s)) { g_above_s = s; g_above = line; }
        q += 5;
        ureg off = hexnum(&q);
        /* путь — после пятого поля */
        int field = 3;
        while (*q && field < 6) { while (*q == ' ') q++; if (field == 5) break; while (*q && *q != ' ') q++; field++; }
        while (*q == ' ') q++;
        if (!exec) continue;
        g_ms[g_nmaps] = s; g_me[g_nmaps] = e; g_mo[g_nmaps] = off; g_mp[g_nmaps] = q;
        g_nmaps++;
    }
}

static int find_map(ureg a) {
    for (int i = 0; i < g_nmaps; i++) if (a >= g_ms[i] && a < g_me[i]) return i;
    return -1;
}

static void put_sym(ureg a) {
    int m = find_map(a);
    if (m < 0) { put("?"); return; }
    const char *path = g_mp[m];
    const char *base = path;
    for (const char *c = path; *c; c++) if (*c == '/') base = c + 1;
    /* путь внутри образа прошивки обрезаем до гостевого */
    const char *sys = path;
    for (const char *c = path; *c; c++) if (c[0] == '/' && c[1] == 's' && c[2] == 'y' && c[3] == 's' && c[4] == 't' && c[5] == 'e' && c[6] == 'm' && c[7] == '/') { sys = c; break; }
    put(*sys ? sys : base);
    put("+"); puthex(a - g_ms[m] + g_mo[m]);
}

/* ---- обработчик ---- */

/* struct sigaction as bionic defines it (libc/include/bits/signal_types.h): LP64 is
 * flags-first, ILP32 handler-first; the mask is word-sized on both ABIs. Must match
 * aemushim.c's struct bionic_sigaction — the two are the write/read sides of one conversion. */
#if defined(__LP64__)
struct bsigaction { u32 flags; void *handler; unsigned long mask; void *restorer; };
#else
struct bsigaction { void *handler; unsigned long mask; u32 flags; void *restorer; };
#endif

static const int g_sigs[] = { 4, 6, 7, 8, 11, 16 };
#define SIGUSR2 12
static volatile int g_dump_lock;   /* SIGUSR2: потоки печатают стек по очереди */
static volatile int g_dump_active;
static const char *g_names[] = { "SIGILL", "SIGABRT", "SIGBUS", "SIGFPE", "SIGSEGV", "SIGSTKFLT" };
static volatile int g_busy;

/* regs[] layout, filled by grab_regs: ARM r0..r15 at 0..15; AArch64 x0..x30 at 0..30, sp at 31, pc at 32 */
#if defined(__aarch64__)
#define REG_SP(r) ((r)[31])
#define REG_LR(r) ((r)[30])
#define REG_PC(r) ((r)[32])
#else
#define REG_SP(r) ((r)[13])
#define REG_LR(r) ((r)[14])
#define REG_PC(r) ((r)[15])
#endif

static void dump_current(const char *title, ureg addr, ureg *regs) {
    g_logfd = (int)CROPEN("/dev/log/main", 1 | 02000 /* O_WRONLY|O_APPEND */);
    g_sp = REG_SP(regs); g_stack_end = 0;
    load_maps();
    put("*** "); put(title); put(" pid "); putdec((ureg)cr_sys3(SYS_getpid, 0, 0, 0));
    put(" tid "); putdec((ureg)cr_sys3(SYS_gettid, 0, 0, 0)); put(" addr "); puthex(addr); flush_line();
    put("pc "); puthex(REG_PC(regs)); put(" "); put_sym(REG_PC(regs)); flush_line();
    put("lr "); puthex(REG_LR(regs)); put(" "); put_sym(REG_LR(regs)); flush_line();
#if defined(__aarch64__)
    for (int r = 0; r < 31; r += 4) {
        for (int k = r; k < r + 4 && k < 31; k++) { put("x"); putdec((ureg)k); put("="); puthex(regs[k]); put(" "); }
        flush_line();
    }
#else
    for (int r = 0; r < 13; r += 4) {
        for (int k = r; k < r + 4 && k < 13; k++) { put("r"); putdec((ureg)k); put("="); puthex(regs[k]); put(" "); }
        flush_line();
    }
#endif
    put("sp "); puthex(REG_SP(regs)); flush_line();
    if (g_sp_map) {
        put("sp in "); put(g_sp_map); flush_line();
        put("next above "); put(g_above ? g_above : "-"); flush_line();
    }
    else {
        put("sp unmapped; below "); put(g_below ? g_below : "-"); flush_line();
        put("sp unmapped; above "); put(g_above ? g_above : "-"); flush_line();
    }
    /* стек: всё, что похоже на адрес возврата в исполняемый код */
    ureg *sp = (ureg *)REG_SP(regs);
    ureg words = g_stack_end > REG_SP(regs) ? (g_stack_end - REG_SP(regs)) / sizeof(ureg) : 0;
    /* sp on a guard page (the stack ran out): walk the stack itself, from its lowest address */
    if (g_sp_map && g_above && g_stack_end == g_above_s) {
        const char *q = g_above;
        hexnum(&q); q++;
        ureg e = hexnum(&q);
        sp = (ureg *)g_above_s; words = (e - g_above_s) / sizeof(ureg);
    }
    if (words > 4096) words = 4096;
    int shown = 0;
    for (ureg i = 0; i < words && shown < 48; i++) {
        ureg w = sp[i];
        if (find_map(w) < 0) continue;
        put("  #"); putdec((ureg)shown); put(" sp+"); puthex(i * sizeof(ureg)); put("  "); puthex(w); put(" "); put_sym(w & ~(ureg)1);
        flush_line();
        shown++;
    }
    put("*** end"); flush_line();
    if (g_logfd >= 0) cr_sys3(SYS_close, g_logfd, 0, 0);
}

/* extract the register set from the kernel ucontext into the layout dump_current prints */
static void grab_regs(void *ucv, ureg *regs) {
#if defined(__aarch64__)
    /* kernel ucontext: uc_mcontext at byte 168; sigcontext: fault_address, regs[31], sp, pc, pstate */
    ureg *mc = (ureg *)((char *)ucv + 168 + 8);
    for (int i = 0; i < 31; i++) regs[i] = mc[i];
    regs[31] = mc[31];   /* sp */
    regs[32] = mc[32];   /* pc */
#else
    u32 *uc = (u32 *)ucv;
    /* ucontext ARM: flags, link, stack(3), затем sigcontext: trap_no, error_code, oldmask, r0..r10, fp, ip, sp, lr, pc, cpsr, fault_address */
    u32 *r = uc + 5 + 3;
    for (int i = 0; i < 16; i++) regs[i] = r[i];
#endif
}

static ureg sig_addr(void *info) {
    if (!info) return 0;
#if defined(__aarch64__)
    return *(ureg *)((char *)info + 16);   /* siginfo.si_addr: the union is 8-aligned on LP64 */
#else
    return (ureg)((u32 *)info)[3];         /* siginfo.si_addr */
#endif
}

static void handler(int sig, void *info, void *ucv) {
    if (g_busy) return;
    g_busy = 1;
    ureg regsbuf[33];
    grab_regs(ucv, regsbuf);
    ureg *regs = regsbuf;
    ureg addr = sig_addr(info);
    const char *name = "?";
    for (int i = 0; i < 6; i++) if (g_sigs[i] == sig) name = g_names[i];
    dump_current(name, addr, regs);
    /* дальше — как без нас: действие по умолчанию, инструкция повторится и процесс упадёт */
    struct bsigaction dfl = { 0, 0, 0, 0 };
    sigaction(sig, &dfl, 0);
    g_busy = 0;
}

/*
 * SIGUSR2 — снимок стеков всех потоков процесса (для зависаний native-служб: kill -USR2 <pid>).
 * Первый получивший сигнал поток рассылает его остальным потокам (tgkill), каждый печатает свой стек.
 */
static void usr2_handler(int sig, void *info, void *ucv) {
    (void)sig; (void)info;
    ureg regsbuf[33];
    grab_regs(ucv, regsbuf);
    ureg *regs = regsbuf;
    int self = (int)cr_sys3(SYS_gettid, 0, 0, 0);
    int pid = (int)cr_sys3(SYS_getpid, 0, 0, 0);
    if (!__sync_lock_test_and_set(&g_dump_active, 1)) {
        /* первый: разослать остальным */
        static char dents[8192];
        long fd = CROPEN("/proc/self/task", 0x4000 /* O_DIRECTORY */);
        if (fd >= 0) {
            for (;;) {
                long n = cr_sys3(SYS_getdents64, fd, (long)dents, sizeof(dents));
                if (n <= 0) break;
                for (long off = 0; off < n;) {
                    unsigned short reclen = *(unsigned short *)(dents + off + 16);
                    const char *nm = dents + off + 19;
                    int tid = 0;
                    for (const char *c = nm; *c >= '0' && *c <= '9'; c++) tid = tid * 10 + (*c - '0');
                    if (tid > 0 && tid != self) cr_sys3(SYS_tgkill, pid, tid, SIGUSR2);
                    off += reclen;
                }
            }
            cr_sys3(SYS_close, fd, 0, 0);
        }
    }
    while (__sync_lock_test_and_set(&g_dump_lock, 1)) { }
    dump_current("thread stack", 0, regs);
    __sync_lock_release(&g_dump_lock);
}

__attribute__((constructor)) static void crash_init(void) {
    struct bsigaction sa;
    sa.handler = (void *)handler;
    sa.mask = 0;
    sa.flags = 4 | 0x10000000; /* SA_SIGINFO | SA_RESTART */
    sa.restorer = 0;
    for (int i = 0; i < 6; i++) sigaction(g_sigs[i], &sa, 0);
    struct bsigaction su = { .handler = (void *)usr2_handler, .mask = 0, .flags = 4 | 0x10000000, .restorer = 0 };
    /* SIGUSR2 занимаем, только если процесс его ещё не использует */
    struct bsigaction old = { 0, 0, 0, 0 };
    if (sigaction(SIGUSR2, 0, &old) == 0 && old.handler == 0) sigaction(SIGUSR2, &su, 0);
}
