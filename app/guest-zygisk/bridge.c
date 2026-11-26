/* AEmulator addition, 2026-11-26. GPL-3.0; see LICENSE. */
//
// libazygisk.so - the Zygisk bridge spike (route B of
// aemu-verify-probe/zygisk-feasibility-desk.md). ART loads this library in
// zygote through ro.dalvik.vm.native.bridge; the property is pointed at us
// and the library is planted in /system/lib64 by TreeFixer before boot,
// only while a VM's Zygisk spike switch is on (ZygiskSpikeSupport).
//
// We claim no translation ability: isCompatibleWith installs the hooks and
// then returns false, the shape the shipped Magisk bridge uses - the load
// itself is the door. (In Android 6.0's LoadNativeBridge flow, initialize
// is only called after a true answer, so it is unreachable here; the
// "bridge alive" evidence line is written from isCompatibleWith instead.)
// Inside zygote we
//   * rewrite the fnPtr slot of nativeForkAndSpecialize in
//     libandroid_runtime's static JNINativeMethod table (registration
//     reads the table after the bridge loads, so the wrapper is what gets
//     registered), matched by name and by the Android 6.0 signature
//     verbatim; a signature mismatch leaves the table untouched;
//   * rewrite libandroid_runtime's PLT slot for fork() so the original
//     nativeForkAndSpecialize - which forks internally - is handed the
//     pid of the child the wrapper forked first. Module pre-specialize
//     runs in that child while it still carries zygote's identity; the
//     original then performs the real specialization in the same child,
//     and the wrapper runs post-specialize on its return.
// Modules are listed in a manifest the host plants at
// /system/etc/aemu-zygisk-manifest (one module directory per line); each
// module's zygisk/arm64-v8a.so is dlopened in the forked child and driven
// through the official API v4 table (zygisk.hpp next to this file; the
// struct layouts below mirror it field for field).
// Honest edges of the spike: connectCompanion returns -1 (no companion
// daemon exists yet), the PLT-hook and JNI-hook API entry points report
// unsupported, 32-bit is not attempted (the secondary zygote simply fails
// to load a 64-bit bridge and starts without one), and the
// nativeForkSystemServer path is not hooked. Every failure is fail-open:
// one log line, the module is skipped, specialization proceeds.

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <jni.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define TAG "azygisk-spike"
#define MANIFEST_PATH "/system/etc/aemu-zygisk-manifest"
#define MAX_MODULES 8

static void slog(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_write(ANDROID_LOG_INFO, TAG, buf);
}

/* ------------------------------------------------------------------ */
/* ELF file reading and /proc/self/maps lookup. Everything is parsed  */
/* from the on-disk file; runtime addresses are bias + vaddr.         */

typedef struct {
    uint8_t *data;
    size_t size;
    uintptr_t bias; /* runtime address = bias + vaddr */
} ElfFile;

static bool elf_read(const char *path, ElfFile *e) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    struct stat st;
    if (fstat(fd, &st) != 0) { close(fd); return false; }
    e->data = malloc((size_t)st.st_size);
    if (!e->data) { close(fd); return false; }
    size_t got = 0;
    while (got < (size_t)st.st_size) {
        ssize_t r = read(fd, e->data + got, (size_t)st.st_size - got);
        if (r <= 0) { free(e->data); close(fd); return false; }
        got += (size_t)r;
    }
    close(fd);
    e->size = got;
    if (got < sizeof(Elf64_Ehdr) ||
        memcmp(e->data, ELFMAG, SELFMAG) != 0) { free(e->data); return false; }
    return true;
}

static const Elf64_Phdr *elf_phdr(const ElfFile *e, int type, int idx) {
    const Elf64_Ehdr *eh = (const Elf64_Ehdr *)e->data;
    for (int i = 0; i < eh->e_phnum; i++) {
        const Elf64_Phdr *p = (const Elf64_Phdr *)(e->data + eh->e_phoff +
                                (size_t)i * sizeof(Elf64_Phdr));
        if ((int)p->p_type == type && idx-- == 0) return p;
    }
    return NULL;
}

/* vaddr <-> file offset through PT_LOAD segments. */
static bool elf_v2o(const ElfFile *e, uintptr_t va, size_t *off) {
    for (int i = 0;; i++) {
        const Elf64_Phdr *p = elf_phdr(e, PT_LOAD, i);
        if (!p) return false;
        if (va >= p->p_vaddr && va < p->p_vaddr + p->p_filesz) {
            *off = (size_t)(p->p_offset + (va - p->p_vaddr));
            return true;
        }
    }
}
static bool elf_o2v(const ElfFile *e, size_t off, uintptr_t *va) {
    for (int i = 0;; i++) {
        const Elf64_Phdr *p = elf_phdr(e, PT_LOAD, i);
        if (!p) return false;
        if (off >= p->p_offset && off < p->p_offset + p->p_filesz) {
            *va = p->p_vaddr + (off - p->p_offset);
            return true;
        }
    }
}

/* Load bias of an already-mapped library: anchor on its lowest file-offset
 * mapping in /proc/self/maps and the PT_LOAD covering that offset. */
static bool elf_bias(const char *path_suffix, const ElfFile *e,
                     uintptr_t *bias, char *path_out, size_t path_cap) {
    FILE *f = fopen("/proc/self/maps", "re");
    if (!f) return false;
    char line[512];
    bool found = false;
    while (fgets(line, sizeof(line), f)) {
        unsigned long start, off;
        char perm[8], pth[256] = "";
        if (sscanf(line, "%lx-%*lx %7s %lx %*s %*s %255[^\n]",
                   &start, perm, &off, pth) < 4) continue;
        size_t lp = strlen(pth), ls = strlen(path_suffix);
        if (lp < ls || strcmp(pth + lp - ls, path_suffix) != 0) continue;
        for (int i = 0;; i++) {
            const Elf64_Phdr *p = elf_phdr(e, PT_LOAD, i);
            if (!p) break;
            if (off >= p->p_offset && off < p->p_offset + p->p_filesz) {
                *bias = (uintptr_t)start - (p->p_vaddr + (off - p->p_offset));
                if (path_out) snprintf(path_out, path_cap, "%s", pth);
                found = true;
                break;
            }
        }
        if (found) break;
    }
    fclose(f);
    return found;
}

/* Find a NUL-terminated string in the file; return its vaddr. */
static bool elf_find_str(const ElfFile *e, const char *s, uintptr_t *va) {
    size_t len = strlen(s) + 1;
    if (e->size < len) return false;
    for (size_t i = 0; i + len <= e->size; i++) {
        if (memcmp(e->data + i, s, len) == 0 && elf_o2v(e, i, va)) return true;
    }
    return false;
}

/* Make one runtime page writable, write a pointer, restore read-only. */
static bool write_ptr(void *addr, void *value) {
    long ps = sysconf(_SC_PAGESIZE);
    uintptr_t page = (uintptr_t)addr & ~((uintptr_t)ps - 1);
    if (mprotect((void *)page, (size_t)ps, PROT_READ | PROT_WRITE) != 0)
        return false;
    *(void **)addr = value;
    __builtin___clear_cache((char *)addr, (char *)addr + sizeof(void *));
    mprotect((void *)page, (size_t)ps, PROT_READ);
    return true;
}

static bool elf_dyn(const ElfFile *e, int64_t tag, uint64_t *val) {
    const Elf64_Phdr *d = elf_phdr(e, PT_DYNAMIC, 0);
    if (!d) return false;
    size_t off = (size_t)d->p_offset;
    for (;;) {
        const Elf64_Dyn *dyn = (const Elf64_Dyn *)(e->data + off);
        if (dyn->d_tag == DT_NULL) return false;
        if (dyn->d_tag == tag) { *val = dyn->d_un.d_ptr; return true; }
        off += sizeof(Elf64_Dyn);
        if (off >= e->size) return false;
    }
}

/* The GOT slot (vaddr) of a PLT relocation for `sym`. */
static bool elf_plt_slot(const ElfFile *e, const char *sym, uintptr_t *slot_va) {
    uint64_t jmprel, pltrelsz, symtab, strtab;
    if (!elf_dyn(e, DT_JMPREL, &jmprel) || !elf_dyn(e, DT_PLTRELSZ, &pltrelsz) ||
        !elf_dyn(e, DT_SYMTAB, &symtab) || !elf_dyn(e, DT_STRTAB, &strtab))
        return false;
    size_t roff, soff, stroff;
    if (!elf_v2o(e, jmprel, &roff) || !elf_v2o(e, symtab, &soff) ||
        !elf_v2o(e, strtab, &stroff)) return false;
    for (size_t i = 0; i < pltrelsz / sizeof(Elf64_Rela); i++) {
        const Elf64_Rela *r = (const Elf64_Rela *)(e->data + roff +
                                i * sizeof(Elf64_Rela));
        if (ELF64_R_TYPE(r->r_info) != R_AARCH64_JUMP_SLOT) continue;
        const Elf64_Sym *s = (const Elf64_Sym *)(e->data + soff +
                              ELF64_R_SYM(r->r_info) * sizeof(Elf64_Sym));
        if (s->st_name == 0) continue;
        const char *name = (const char *)(e->data + stroff + s->st_name);
        if (strcmp(name, sym) == 0) { *slot_va = r->r_offset; return true; }
    }
    return false;
}

/* Locate a JNINativeMethod entry in .rela.dyn: three consecutive RELATIVE
 * relocations whose addends are the name string, the signature string and
 * a pointer into an executable segment (the original function). Returns
 * the fnPtr slot vaddr and the original function's vaddr. */
static bool elf_jni_slot(const ElfFile *e, uintptr_t name_va, uintptr_t sig_va,
                         uintptr_t *fn_slot_va, uintptr_t *fn_va) {
    uint64_t rela, relasz;
    if (!elf_dyn(e, DT_RELA, &rela) || !elf_dyn(e, DT_RELASZ, &relasz))
        return false;
    size_t roff;
    if (!elf_v2o(e, rela, &roff)) return false;
    size_t n = relasz / sizeof(Elf64_Rela);
    for (size_t i = 0; i + 2 < n; i++) {
        const Elf64_Rela *a = (const Elf64_Rela *)(e->data + roff +
                                i * sizeof(Elf64_Rela));
        const Elf64_Rela *b = (const Elf64_Rela *)(e->data + roff +
                                (i + 1) * sizeof(Elf64_Rela));
        const Elf64_Rela *c = (const Elf64_Rela *)(e->data + roff +
                                (i + 2) * sizeof(Elf64_Rela));
        if (ELF64_R_TYPE(a->r_info) != R_AARCH64_RELATIVE ||
            ELF64_R_TYPE(b->r_info) != R_AARCH64_RELATIVE ||
            ELF64_R_TYPE(c->r_info) != R_AARCH64_RELATIVE) continue;
        if ((uintptr_t)a->r_addend != name_va ||
            (uintptr_t)b->r_addend != sig_va) continue;
        if (b->r_offset != a->r_offset + sizeof(void *) ||
            c->r_offset != b->r_offset + sizeof(void *)) continue;
        for (int j = 0;; j++) {
            const Elf64_Phdr *p = elf_phdr(e, PT_LOAD, j);
            if (!p) return false;
            if ((p->p_flags & PF_X) &&
                (uintptr_t)c->r_addend >= p->p_vaddr &&
                (uintptr_t)c->r_addend < p->p_vaddr + p->p_filesz) {
                *fn_slot_va = c->r_offset;
                *fn_va = (uintptr_t)c->r_addend;
                return true;
            }
        }
    }
    return false;
}

/* ------------------------------------------------------------------ */
/* The zygisk API v4 table, mirrored in C from zygisk.hpp (internal::  */
/* api_table / module_abi); pointer-for-pointer identical layouts.    */

typedef struct ModuleRt ModuleRt;
typedef struct {
    long api_version;
    void *impl;
    void (*preAppSpecialize)(void *, void *);
    void (*postAppSpecialize)(void *, const void *);
    void (*preServerSpecialize)(void *, void *);
    void (*postServerSpecialize)(void *, const void *);
} ModuleAbi;

typedef struct {
    void *impl;
    bool (*registerModule)(void *, ModuleAbi *);
    void (*hookJniNativeMethods)(JNIEnv *, const char *, JNINativeMethod *, int);
    void (*pltHookRegister)(dev_t, ino_t, const char *, void *, void **);
    bool (*exemptFd)(int);
    bool (*pltHookCommit)(void);
    int (*connectCompanion)(void *);
    void (*setOption)(void *, int);
    int (*getModuleDir)(void *);
    uint32_t (*getFlags)(void *);
} ApiTable;

typedef void (*ModuleEntry)(ApiTable *, JNIEnv *);

/* AppSpecializeArgs as the host assembles it: one pointer per reference
 * member of zygisk.hpp's struct, in declaration order, plus the API v5
 * tail slot (always null here - Android 6.0 has no such JNI argument). */
typedef struct {
    jint *uid;
    jint *gid;
    jintArray *gids;
    jint *runtime_flags;
    jobjectArray *rlimits;
    jint *mount_external;
    jstring *se_info;
    jstring *nice_name;
    jstring *instruction_set;
    jstring *app_data_dir;
    jintArray *fds_to_ignore;
    jboolean *is_child_zygote;
    jboolean *is_top_app;
    jobjectArray *pkg_data_info_list;
    jobjectArray *whitelisted_data_info_list;
    jboolean *mount_data_dirs;
    jboolean *mount_storage_dirs;
    void *mount_sysprop_overrides;
} ArgsV4;

struct ModuleRt {
    char id[64];
    char dir[256];
    void *handle;
    ModuleAbi *abi;
    ApiTable tbl;
    int dir_fd;
    bool in_pre;
    bool dlclose_after;
    bool loaded;
};

static bool tbl_register_module(void *tbl, ModuleAbi *abi) {
    ModuleRt *rt = (ModuleRt *)((ApiTable *)tbl)->impl;
    if (abi->api_version < 2 || abi->api_version > 5) {
        slog("module %s: unsupported api version %ld", rt->id, abi->api_version);
        return false;
    }
    rt->abi = abi;
    return true;
}
static void tbl_hook_jni(JNIEnv *env, const char *cls, JNINativeMethod *m, int n) {
    (void)env; (void)cls;
    for (int i = 0; i < n; i++) m[i].fnPtr = NULL;
    slog("hookJniNativeMethods: not supported in the spike, entries nulled");
}
static void tbl_plt_register(dev_t d, ino_t i, const char *s, void *n, void **o) {
    (void)d; (void)i; (void)n;
    if (o) *o = NULL;
    slog("pltHookRegister(%s): not supported in the spike", s ? s : "?");
}
static bool tbl_exempt_fd(int fd) {
    slog("exemptFd(%d): not supported in the spike", fd);
    return false;
}
static bool tbl_plt_commit(void) {
    slog("pltHookCommit: not supported in the spike");
    return false;
}
static int tbl_connect_companion(void *impl) {
    ModuleRt *rt = (ModuleRt *)impl;
    slog("module %s: connectCompanion -> -1 (no companion in the spike)", rt->id);
    return -1;
}
static void tbl_set_option(void *impl, int opt) {
    ModuleRt *rt = (ModuleRt *)impl;
    if (opt == 1 /* DLCLOSE_MODULE_LIBRARY */) rt->dlclose_after = true;
    else slog("module %s: setOption(%d) recorded, no effect in the spike", rt->id, opt);
}
static int tbl_get_module_dir(void *impl) {
    ModuleRt *rt = (ModuleRt *)impl;
    return (rt->in_pre && rt->dir_fd >= 0) ? rt->dir_fd : -1;
}
static uint32_t tbl_get_flags(void *impl) {
    (void)impl;
    return 0;
}

static void fill_table(ModuleRt *rt) {
    memset(&rt->tbl, 0, sizeof(rt->tbl));
    rt->tbl.impl = rt;
    rt->tbl.registerModule = tbl_register_module;
    rt->tbl.hookJniNativeMethods = tbl_hook_jni;
    rt->tbl.pltHookRegister = tbl_plt_register;
    rt->tbl.exemptFd = tbl_exempt_fd;
    rt->tbl.pltHookCommit = tbl_plt_commit;
    rt->tbl.connectCompanion = tbl_connect_companion;
    rt->tbl.setOption = tbl_set_option;
    rt->tbl.getModuleDir = tbl_get_module_dir;
    rt->tbl.getFlags = tbl_get_flags;
}

/* Read the manifest (one module directory per line) and drive every
 * module through entry + preAppSpecialize. Runs in the forked child,
 * before the original specialization. Fail-open per module. */
static int load_modules(JNIEnv *env, ArgsV4 *args, ModuleRt *mods) {
    FILE *f = fopen(MANIFEST_PATH, "re");
    if (!f) return 0;
    int n = 0;
    char line[256];
    while (n < MAX_MODULES && fgets(line, sizeof(line), f)) {
        char *nl = strchr(line, '\n');
        if (nl) *nl = 0;
        if (line[0] == 0 || line[0] == '#') continue;
        ModuleRt *rt = &mods[n];
        memset(rt, 0, sizeof(*rt));
        rt->dir_fd = -1;
        snprintf(rt->dir, sizeof(rt->dir), "%s", line);
        const char *base = strrchr(line, '/');
        snprintf(rt->id, sizeof(rt->id), "%s", base ? base + 1 : line);
        char so[320];
        snprintf(so, sizeof(so), "%s/zygisk/arm64-v8a.so", line);
        rt->handle = dlopen(so, RTLD_NOW | RTLD_LOCAL);
        if (!rt->handle) {
            slog("module %s: dlopen failed: %s", rt->id, dlerror());
            continue;
        }
        ModuleEntry entry = (ModuleEntry)dlsym(rt->handle, "zygisk_module_entry");
        if (!entry) {
            slog("module %s: no zygisk_module_entry", rt->id);
            dlclose(rt->handle);
            rt->handle = NULL;
            continue;
        }
        fill_table(rt);
        entry(&rt->tbl, env); /* registerModule + onLoad run inside */
        if (!rt->abi) {
            slog("module %s: did not register, skipped", rt->id);
            dlclose(rt->handle);
            rt->handle = NULL;
            continue;
        }
        rt->loaded = true;
        rt->dir_fd = open(rt->dir, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        rt->in_pre = true;
        rt->abi->preAppSpecialize(rt->abi->impl, args);
        rt->in_pre = false;
        n++;
    }
    fclose(f);
    return n;
}

/* postAppSpecialize for every loaded module, then the API table the
 * module holds is stripped - after post, Api calls must stop working
 * (zygisk.hpp's contract), which the nulled table enforces. */
static void post_modules(ArgsV4 *args, ModuleRt *mods, int n) {
    for (int i = 0; i < n; i++) {
        ModuleRt *rt = &mods[i];
        if (!rt->loaded) continue;
        rt->abi->postAppSpecialize(rt->abi->impl, args);
        memset(&rt->tbl, 0, sizeof(rt->tbl));
        slog("api invalidated for %s", rt->id);
        if (rt->dir_fd >= 0) { close(rt->dir_fd); rt->dir_fd = -1; }
        if (rt->dlclose_after && rt->handle) dlclose(rt->handle);
    }
}

/* ------------------------------------------------------------------ */
/* The fork cache and the nativeForkAndSpecialize wrapper.            */

typedef pid_t (*ForkFn)(void);
static ForkFn g_real_fork = NULL;
static volatile int g_ctx_active = 0;
static volatile pid_t g_ctx_pid = -1;

static pid_t fork_hook(void) {
    if (g_ctx_active) return (pid_t)g_ctx_pid;
    return g_real_fork();
}

typedef jint (*FasFn)(JNIEnv *, jclass, jint, jint, jintArray, jint,
                      jobjectArray, jint, jstring, jstring, jintArray,
                      jstring, jstring);
static FasFn g_orig_fas = NULL;

static jint wrapped_fas(JNIEnv *env, jclass clazz, jint uid, jint gid,
                        jintArray gids, jint debug_flags,
                        jobjectArray rlimits, jint mount_external,
                        jstring se_info, jstring nice_name,
                        jintArray fds_to_close, jstring instruction_set,
                        jstring app_data_dir) {
    const char *nn = nice_name ?
        (*env)->GetStringUTFChars(env, nice_name, NULL) : NULL;
    slog("forkAndSpecialize uid=%d name=%s", uid, nn ? nn : "?");
    ArgsV4 args;
    args.uid = &uid;
    args.gid = &gid;
    args.gids = &gids;
    args.runtime_flags = &debug_flags;
    args.rlimits = &rlimits;
    args.mount_external = &mount_external;
    args.se_info = &se_info;
    args.nice_name = &nice_name;
    args.instruction_set = &instruction_set;
    args.app_data_dir = &app_data_dir;
    args.fds_to_ignore = NULL;
    args.is_child_zygote = NULL;
    args.is_top_app = NULL;
    args.pkg_data_info_list = NULL;
    args.whitelisted_data_info_list = NULL;
    args.mount_data_dirs = NULL;
    args.mount_storage_dirs = NULL;
    args.mount_sysprop_overrides = NULL;
    ModuleRt mods[MAX_MODULES];
    int nmods = 0;
    pid_t pid = g_real_fork();
    if (pid < 0) { /* fail-open: no pre-fork, straight to the original */
        if (nn) (*env)->ReleaseStringUTFChars(env, nice_name, nn);
        return g_orig_fas(env, clazz, uid, gid, gids, debug_flags, rlimits,
                          mount_external, se_info, nice_name, fds_to_close,
                          instruction_set, app_data_dir);
    }
    g_ctx_pid = pid;
    g_ctx_active = 1;
    if (pid == 0) nmods = load_modules(env, &args, mods);
    jint result = g_orig_fas(env, clazz, uid, gid, gids, debug_flags,
                             rlimits, mount_external, se_info, nice_name,
                             fds_to_close, instruction_set, app_data_dir);
    if (pid == 0) post_modules(&args, mods, nmods);
    g_ctx_active = 0;
    g_ctx_pid = -1;
    if (nn) (*env)->ReleaseStringUTFChars(env, nice_name, nn);
    return result;
}

/* Install both hooks in libandroid_runtime. Order matters: the fork PLT
 * goes first, so the JNI wrapper never runs without a real fork and a
 * cache behind it. Any failure leaves zygote untouched (fail-open). */
static bool install_hooks(void) {
    char path[256] = "";
    FILE *mf = fopen("/proc/self/maps", "re");
    if (!mf) { slog("hook failed: no maps"); return false; }
    char line[512];
    while (fgets(line, sizeof(line), mf)) {
        char pth[256] = "";
        if (sscanf(line, "%*s %*s %*s %*s %*s %255[^\n]", pth) == 1) {
            size_t lp = strlen(pth);
            if (lp >= 22 && strcmp(pth + lp - 22, "/libandroid_runtime.so") == 0) {
                snprintf(path, sizeof(path), "%s", pth);
                break;
            }
        }
    }
    fclose(mf);
    if (!path[0]) { slog("hook failed: libandroid_runtime not mapped"); return false; }
    ElfFile e;
    if (!elf_read(path, &e)) { slog("hook failed: cannot read %s", path); return false; }
    bool ok = false;
    uintptr_t name_va, sig_va, got_va, fn_slot_va, fn_va;
    if (!elf_bias("/libandroid_runtime.so", &e, &e.bias, NULL, 0)) {
        slog("hook failed: no load bias");
        goto out;
    }
    g_real_fork = (ForkFn)dlsym(RTLD_DEFAULT, "fork");
    if (!g_real_fork) { slog("hook failed: no real fork"); goto out; }
    if (!elf_plt_slot(&e, "fork", &got_va)) {
        slog("hook failed: fork PLT slot not found");
        goto out;
    }
    if (!write_ptr((void *)(e.bias + got_va), (void *)fork_hook)) {
        slog("hook failed: fork PLT write");
        goto out;
    }
    if (!elf_find_str(&e, "nativeForkAndSpecialize", &name_va) ||
        !elf_find_str(&e, "(II[II[[IILjava/lang/String;Ljava/lang/String;"
                          "[ILjava/lang/String;Ljava/lang/String;)I", &sig_va)) {
        slog("hook failed: method name or 6.0 signature not found");
        goto out;
    }
    if (!elf_jni_slot(&e, name_va, sig_va, &fn_slot_va, &fn_va)) {
        slog("hook failed: JNI table entry not found");
        goto out;
    }
    if (*(void **)(e.bias + fn_slot_va) != (void *)(e.bias + fn_va)) {
        slog("hook failed: JNI slot content mismatch");
        goto out;
    }
    g_orig_fas = (FasFn)(e.bias + fn_va);
    if (!write_ptr((void *)(e.bias + fn_slot_va), (void *)wrapped_fas)) {
        slog("hook failed: JNI slot write");
        goto out;
    }
    ok = true;
out:
    free(e.data);
    return ok;
}

/* The native bridge entry point, in the reduced layout the shipped
 * Magisk bridge uses: version first, isCompatibleWith at offset 48. */
struct NativeBridgeCallbacksMin {
    uint32_t version;
    void *padding[5];
    bool (*isCompatibleWith)(uint32_t);
};

static bool nb_is_compatible(uint32_t bridge_version) {
    static int installed = 0;
    (void)bridge_version;
    slog("bridge alive");
    if (!installed) {
        installed = 1;
        if (install_hooks()) slog("hooks installed");
    }
    return false;
}

struct NativeBridgeCallbacksMin NativeBridgeItf = {
    2, { NULL, NULL, NULL, NULL, NULL }, nb_is_compatible
};
