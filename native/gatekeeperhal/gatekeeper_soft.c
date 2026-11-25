/*
 * gatekeeper.aemu.so — a software GateKeeper legacy HAL module for AEmulator (Android 8.1, api 27).
 *
 * The 8.1 gatekeeper HIDL implementation (android.hardware.gatekeeper@1.0-impl.so, shipped in the
 * n64-27 engine set) is a passthrough: it opens the legacy "gatekeeper" hw_module and forwards
 * enroll/verify/delete to it. On real hardware that module is gatekeeper.<board>.so backed by the
 * TEE (QSEE on angler); in the guest there is no TEE, the stock module's open() fails, the HIDL
 * service aborts with "Unable to open GateKeeper HAL", gatekeeperd never registers, and
 * system_server dies in LockSettingsService (reportDeviceSetupComplete on a null service).
 *
 * This module is the missing legacy module, with real software semantics mirroring AOSP's
 * libgatekeeper (system/gatekeeper, android-8.1.0) and its software implementation:
 *   - password handles use the exact 8.1 password_handle_t layout (58 bytes packed);
 *   - the handle signature is scrypt(N=16384, r=8, p=1) over (handle metadata || password),
 *     additionally keyed with a persistent random master key that never leaves this module
 *     (the role the TEE key plays on hardware);
 *   - failure throttling follows libgatekeeper's ComputeRetryTimeout table, with failure
 *     records persisted next to the key in /data/misc/gatekeeper;
 *   - verify() mints a real hw_auth_token_t, HMAC-SHA256-signed with the master key.
 * Nothing here answers "yes" unconditionally: a wrong password fails scrypt comparison exactly
 * like on hardware.
 *
 * TreeFixer plants this file over the firmware's own gatekeeper.*.so legacy module (same name,
 * same directory), so hw_get_module_by_class() finds it through the stock variant search.
 * Only libc/libm/libdl/liblog are used (sphal-allowed); layouts are defined locally, as in the
 * other AEmulator HALs.
 */
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

extern void *malloc(unsigned long n);
extern void *calloc(unsigned long n, unsigned long size);
extern void free(void *p);
extern void *memcpy(void *d, const void *s, unsigned long n);
extern void *memset(void *d, int c, unsigned long n);
extern int memcmp(const void *a, const void *b, unsigned long n);
extern unsigned long strlen(const char *s);
extern int strcmp(const char *a, const char *b);
extern int open(const char *path, int flags, ...);
extern long read(int fd, void *buf, unsigned long n);
extern long write(int fd, const void *buf, unsigned long n);
extern int close(int fd);
extern int rename(const char *oldp, const char *newp);
extern int unlink(const char *path);
extern int mkdir(const char *path, unsigned mode);
extern int getpid(void);
extern int clock_gettime(int clk, void *ts);
extern int pthread_mutex_lock(void *m);
extern int pthread_mutex_unlock(void *m);

#ifdef GK_HOST_TEST
#include <stdio.h>
#define GK_LOG(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#else
extern int __android_log_print(int prio, const char *tag, const char *fmt, ...);
#define GK_LOG(...) __android_log_print(4 /* ANDROID_LOG_INFO */, "gatekeeper-aemu", __VA_ARGS__)
#endif

#define GK_O_RDONLY 0
#define GK_O_WRONLY 1
#define GK_O_CREAT  0100
#define GK_O_TRUNC  01000
#define GK_EINVAL   22
#define GK_ENOMEM   12

#define HARDWARE_MODULE_TAG 0x484d4f44u   /* "HMOD" */
#define HARDWARE_DEVICE_TAG 0x48574454u   /* "HWDT" */
#define GATEKEEPER_MODULE_API_VERSION_0_1 0x0001

struct hw_module;
struct hw_device;
struct hw_module_methods { int (*open)(const struct hw_module *m, const char *id, struct hw_device **dev); };
struct hw_module {
    uint32_t tag;
    uint16_t module_api_version, hal_api_version;
    const char *id, *name, *author;
    struct hw_module_methods *methods;
    void *dso;
    uint32_t reserved[32 - 7];
};
struct hw_device {
    uint32_t tag, version;
    struct hw_module *module;
    uint32_t reserved[12];
    int (*close)(struct hw_device *dev);
};

struct gatekeeper_device {
    struct hw_device common;
    int (*enroll)(const struct gatekeeper_device *dev, uint32_t uid,
            const uint8_t *current_password_handle, uint32_t current_password_handle_length,
            const uint8_t *current_password, uint32_t current_password_length,
            const uint8_t *desired_password, uint32_t desired_password_length,
            uint8_t **enrolled_password_handle, uint32_t *enrolled_password_handle_length);
    int (*verify)(const struct gatekeeper_device *dev, uint32_t uid, uint64_t challenge,
            const uint8_t *enrolled_password_handle, uint32_t enrolled_password_handle_length,
            const uint8_t *provided_password, uint32_t provided_password_length,
            uint8_t **auth_token, uint32_t *auth_token_length, bool *request_reenroll);
    int (*delete_user)(const struct gatekeeper_device *dev, uint32_t uid);
    int (*delete_all_users)(const struct gatekeeper_device *dev);
};

/* The 8.1 password_handle_t (system/gatekeeper password_handle.h), byte for byte: gatekeeperd
 * reads version/user_id/hardware_backed out of it and rejects enrolled handles of any other
 * length, so this layout is a hard external contract, not a private choice. */
#define GK_HANDLE_VERSION 2
#define GK_HANDLE_FLAG_THROTTLE_SECURE 1u
struct __attribute__((packed)) gk_handle {
    uint8_t  version;           /* offset 0 */
    uint64_t user_id;           /* offset 1: the secure user id */
    uint64_t flags;             /* offset 9 */
    uint64_t salt;              /* offset 17 */
    uint8_t  signature[32];     /* offset 25 */
    uint8_t  hardware_backed;   /* offset 57 */
};                              /* 58 bytes total */

/* hw_auth_token_t (hardware/hw_auth_token.h), byte for byte. */
struct __attribute__((packed)) gk_auth_token {
    uint8_t  version;           /* 0 */
    uint64_t challenge;
    uint64_t user_id;
    uint64_t authenticator_id;
    uint32_t authenticator_type; /* network order */
    uint64_t timestamp;          /* network order */
    uint8_t  hmac[32];
};                               /* 69 bytes total */
#define GK_HW_AUTH_PASSWORD (1u << 0)

/* ------------------------------------------------------------------ SHA-256 */

struct sha256_ctx {
    uint32_t h[8];
    uint64_t len;
    uint8_t block[64];
    unsigned blocklen;
};

static const uint32_t k256[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

static uint32_t rotr32(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

static void sha256_block(struct sha256_ctx *c, const uint8_t *p) {
    uint32_t w[64];
    for (int i = 0; i < 16; i++)
        w[i] = ((uint32_t)p[i * 4] << 24) | ((uint32_t)p[i * 4 + 1] << 16) |
               ((uint32_t)p[i * 4 + 2] << 8) | (uint32_t)p[i * 4 + 3];
    for (int i = 16; i < 64; i++) {
        uint32_t s0 = rotr32(w[i - 15], 7) ^ rotr32(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = rotr32(w[i - 2], 17) ^ rotr32(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = c->h[0], b = c->h[1], d = c->h[3], e = c->h[4],
             f = c->h[5], g = c->h[6], h = c->h[7], cc = c->h[2];
    for (int i = 0; i < 64; i++) {
        uint32_t S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
        uint32_t ch = (e & f) ^ (~e & g);
        uint32_t t1 = h + S1 + ch + k256[i] + w[i];
        uint32_t S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
        uint32_t mj = (a & b) ^ (a & cc) ^ (b & cc);
        uint32_t t2 = S0 + mj;
        h = g; g = f; f = e; e = d + t1; d = cc; cc = b; b = a; a = t1 + t2;
    }
    c->h[0] += a; c->h[1] += b; c->h[2] += cc; c->h[3] += d;
    c->h[4] += e; c->h[5] += f; c->h[6] += g; c->h[7] += h;
}

static void sha256_init(struct sha256_ctx *c) {
    static const uint32_t h0[8] = { 0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
                                    0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19 };
    memcpy(c->h, h0, sizeof(h0));
    c->len = 0;
    c->blocklen = 0;
}

static void sha256_update(struct sha256_ctx *c, const uint8_t *p, unsigned long n) {
    c->len += n;
    while (n) {
        unsigned take = 64 - c->blocklen;
        if (take > n) take = (unsigned)n;
        memcpy(c->block + c->blocklen, p, take);
        c->blocklen += take;
        p += take;
        n -= take;
        if (c->blocklen == 64) {
            sha256_block(c, c->block);
            c->blocklen = 0;
        }
    }
}

static void sha256_final(struct sha256_ctx *c, uint8_t out[32]) {
    uint64_t bits = c->len * 8;
    uint8_t pad = 0x80;
    sha256_update(c, &pad, 1);
    pad = 0;
    while (c->blocklen != 56) sha256_update(c, &pad, 1);
    uint8_t lenbuf[8];
    for (int i = 0; i < 8; i++) lenbuf[i] = (uint8_t)(bits >> (56 - 8 * i));
    sha256_update(c, lenbuf, 8);
    for (int i = 0; i < 8; i++) {
        out[i * 4] = (uint8_t)(c->h[i] >> 24);
        out[i * 4 + 1] = (uint8_t)(c->h[i] >> 16);
        out[i * 4 + 2] = (uint8_t)(c->h[i] >> 8);
        out[i * 4 + 3] = (uint8_t)c->h[i];
    }
}

static void sha256(const uint8_t *p, unsigned long n, uint8_t out[32]) {
    struct sha256_ctx c;
    sha256_init(&c);
    sha256_update(&c, p, n);
    sha256_final(&c, out);
}

/* ------------------------------------------------------------------ HMAC / PBKDF2 */

static void hmac_sha256(const uint8_t *key, unsigned long keylen,
                        const uint8_t *msg, unsigned long msglen, uint8_t out[32]) {
    uint8_t kblock[64], pad[64], inner[32];
    struct sha256_ctx c;
    memset(kblock, 0, sizeof(kblock));
    if (keylen > 64) sha256(key, keylen, kblock);
    else memcpy(kblock, key, keylen);
    for (int i = 0; i < 64; i++) pad[i] = (uint8_t)(kblock[i] ^ 0x36);
    sha256_init(&c);
    sha256_update(&c, pad, 64);
    sha256_update(&c, msg, msglen);
    sha256_final(&c, inner);
    for (int i = 0; i < 64; i++) pad[i] = (uint8_t)(kblock[i] ^ 0x5c);
    sha256_init(&c);
    sha256_update(&c, pad, 64);
    sha256_update(&c, inner, 32);
    sha256_final(&c, out);
}

static void pbkdf2_hmac_sha256(const uint8_t *pw, unsigned long pwlen,
                               const uint8_t *salt, unsigned long saltlen,
                               uint32_t iterations, uint8_t *out, unsigned long outlen) {
    uint32_t block = 1;
    while (outlen) {
        unsigned long take = outlen > 32 ? 32 : outlen;
        uint8_t *msg = malloc(saltlen + 4);
        if (!msg) return;
        memcpy(msg, salt, saltlen);
        msg[saltlen] = (uint8_t)(block >> 24);
        msg[saltlen + 1] = (uint8_t)(block >> 16);
        msg[saltlen + 2] = (uint8_t)(block >> 8);
        msg[saltlen + 3] = (uint8_t)block;
        uint8_t u[32], t[32];
        hmac_sha256(pw, pwlen, msg, saltlen + 4, u);
        free(msg);
        memcpy(t, u, 32);
        for (uint32_t i = 1; i < iterations; i++) {
            hmac_sha256(pw, pwlen, u, 32, u);
            for (int j = 0; j < 32; j++) t[j] ^= u[j];
        }
        memcpy(out, t, take);
        out += take;
        outlen -= take;
        block++;
    }
}

/* ------------------------------------------------------------------ scrypt (RFC 7914) */

static uint32_t le32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static void putle32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}
static uint32_t rotl32(uint32_t x, int n) { return (x << n) | (x >> (32 - n)); }

static void salsa20_8(uint8_t b[64]) {
    uint32_t x[16], orig[16];
    for (int i = 0; i < 16; i++) x[i] = orig[i] = le32(b + i * 4);
    for (int i = 0; i < 8; i += 2) {
        x[ 4] ^= rotl32(x[ 0] + x[12], 7);  x[ 8] ^= rotl32(x[ 4] + x[ 0], 9);
        x[12] ^= rotl32(x[ 8] + x[ 4], 13); x[ 0] ^= rotl32(x[12] + x[ 8], 18);
        x[ 9] ^= rotl32(x[ 5] + x[ 1], 7);  x[13] ^= rotl32(x[ 9] + x[ 5], 9);
        x[ 1] ^= rotl32(x[13] + x[ 9], 13); x[ 5] ^= rotl32(x[ 1] + x[13], 18);
        x[14] ^= rotl32(x[10] + x[ 6], 7);  x[ 2] ^= rotl32(x[14] + x[10], 9);
        x[ 6] ^= rotl32(x[ 2] + x[14], 13); x[10] ^= rotl32(x[ 6] + x[ 2], 18);
        x[ 3] ^= rotl32(x[15] + x[11], 7);  x[ 7] ^= rotl32(x[ 3] + x[15], 9);
        x[11] ^= rotl32(x[ 7] + x[ 3], 13); x[15] ^= rotl32(x[11] + x[ 7], 18);
        x[ 1] ^= rotl32(x[ 0] + x[ 3], 7);  x[ 2] ^= rotl32(x[ 1] + x[ 0], 9);
        x[ 3] ^= rotl32(x[ 2] + x[ 1], 13); x[ 0] ^= rotl32(x[ 3] + x[ 2], 18);
        x[ 6] ^= rotl32(x[ 5] + x[ 4], 7);  x[ 7] ^= rotl32(x[ 6] + x[ 5], 9);
        x[ 4] ^= rotl32(x[ 7] + x[ 6], 13); x[ 5] ^= rotl32(x[ 4] + x[ 7], 18);
        x[11] ^= rotl32(x[10] + x[ 9], 7);  x[ 8] ^= rotl32(x[11] + x[10], 9);
        x[ 9] ^= rotl32(x[ 8] + x[11], 13); x[10] ^= rotl32(x[ 9] + x[ 8], 18);
        x[12] ^= rotl32(x[15] + x[14], 7);  x[13] ^= rotl32(x[12] + x[15], 9);
        x[14] ^= rotl32(x[13] + x[12], 13); x[15] ^= rotl32(x[14] + x[13], 18);
    }
    for (int i = 0; i < 16; i++) putle32(b + i * 4, x[i] + orig[i]);
}

static void block_mix(const uint8_t *b, uint8_t *y, uint32_t r) {
    uint8_t x[64];
    memcpy(x, b + (2 * r - 1) * 64, 64);
    for (uint32_t i = 0; i < 2 * r; i++) {
        for (int j = 0; j < 64; j++) x[j] ^= b[i * 64 + j];
        salsa20_8(x);
        memcpy(y + i * 64, x, 64);
    }
    /* B' = (Y0, Y2, ..., Y2r-2, Y1, Y3, ..., Y2r-1): written back over the caller's buffer by romix */
}

static void ro_mix(uint8_t *b, uint32_t r, uint32_t n, uint8_t *v, uint8_t *tmp) {
    unsigned long chunk = 128ul * r;
    uint8_t *y = tmp;              /* 128*r bytes */
    uint8_t *b2 = tmp + chunk;     /* 128*r bytes */
    for (uint32_t i = 0; i < n; i++) {
        memcpy(v + (unsigned long)i * chunk, b, chunk);
        block_mix(b, y, r);
        /* reorder y into b */
        for (uint32_t i2 = 0; i2 < r; i2++) memcpy(b + (unsigned long)i2 * 64, y + (unsigned long)(2 * i2) * 64, 64);
        for (uint32_t i2 = 0; i2 < r; i2++) memcpy(b + (unsigned long)(r + i2) * 64, y + (unsigned long)(2 * i2 + 1) * 64, 64);
    }
    for (uint32_t i = 0; i < n; i++) {
        uint32_t j = le32(b + chunk - 64) & (n - 1);   /* Integerify mod N (N is a power of two) */
        for (unsigned long k = 0; k < chunk; k++) b2[k] = (uint8_t)(b[k] ^ v[(unsigned long)j * chunk + k]);
        block_mix(b2, y, r);
        for (uint32_t i2 = 0; i2 < r; i2++) memcpy(b + (unsigned long)i2 * 64, y + (unsigned long)(2 * i2) * 64, 64);
        for (uint32_t i2 = 0; i2 < r; i2++) memcpy(b + (unsigned long)(r + i2) * 64, y + (unsigned long)(2 * i2 + 1) * 64, 64);
    }
}

static int gk_scrypt(const uint8_t *pw, unsigned long pwlen,
                     const uint8_t *salt, unsigned long saltlen,
                     uint32_t n, uint32_t r, uint32_t p, uint8_t *out, unsigned long outlen) {
    unsigned long chunk = 128ul * r;
    uint8_t *b = malloc(chunk * p);
    uint8_t *v = malloc(chunk * n);
    uint8_t *tmp = malloc(chunk * 2);
    if (!b || !v || !tmp) { free(b); free(v); free(tmp); return -1; }
    pbkdf2_hmac_sha256(pw, pwlen, salt, saltlen, 1, b, chunk * p);
    for (uint32_t i = 0; i < p; i++) ro_mix(b + (unsigned long)i * chunk, r, n, v, tmp);
    pbkdf2_hmac_sha256(pw, pwlen, b, chunk * p, 1, out, outlen);
    free(b); free(v); free(tmp);
    return 0;
}

/* ------------------------------------------------------------------ helpers */

static int ct_equal(const uint8_t *a, const uint8_t *b, unsigned long n) {
    uint8_t diff = 0;
    for (unsigned long i = 0; i < n; i++) diff |= (uint8_t)(a[i] ^ b[i]);
    return diff == 0;
}

struct gk_timespec { long tv_sec; long tv_nsec; };

static uint64_t now_ms_since_boot(void) {
    struct gk_timespec ts;
    if (clock_gettime(7 /* CLOCK_BOOTTIME */, &ts) != 0) return 0;
    /* tv_nsec < 1e9, so the division stays 32-bit (no __aeabi_uldivmod on armv7) */
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)((uint32_t)ts.tv_nsec / 1000000u);
}

static int read_urandom(uint8_t *buf, unsigned long n) {
    int fd = open("/dev/urandom", GK_O_RDONLY);
    if (fd < 0) return -1;
    unsigned long got = 0;
    while (got < n) {
        long r = read(fd, buf + got, n - got);
        if (r <= 0) { close(fd); return -1; }
        got += (unsigned long)r;
    }
    close(fd);
    return 0;
}

static uint64_t g_rng_state;
static void random_bytes(uint8_t *buf, unsigned long n) {
    if (read_urandom(buf, n) == 0) return;
    /* /dev/urandom is on the supervisor's pass list, so this is only a last resort */
    if (!g_rng_state) {
        struct gk_timespec ts;
        clock_gettime(7, &ts);
        g_rng_state = (uint64_t)ts.tv_nsec ^ ((uint64_t)getpid() << 32) ^ (uint64_t)ts.tv_sec;
        if (!g_rng_state) g_rng_state = 0x9e3779b97f4a7c15ull;
    }
    for (unsigned long i = 0; i < n; i++) {
        g_rng_state ^= g_rng_state >> 12;
        g_rng_state ^= g_rng_state << 25;
        g_rng_state ^= g_rng_state >> 27;
        buf[i] = (uint8_t)((g_rng_state * 0x2545F4914F6CDD1Dull) >> 56);
    }
    GK_LOG("warning: /dev/urandom unavailable, weak random fallback in use");
}

static uint64_t random_u64(void) {
    uint8_t b[8];
    random_bytes(b, sizeof(b));
    uint64_t v = 0;
    for (int i = 0; i < 8; i++) v |= (uint64_t)b[i] << (8 * i);
    return v;
}

/* ------------------------------------------------------------------ persistent state
 *
 * One file holds the master key, this authenticator's id, and the failure records.
 * The key plays the TEE key's role: password signatures are scrypt over
 * (key || handle metadata || password), and auth tokens are HMAC'd with it, so a leaked
 * handle file alone is not brute-forceable offline. The file is MAC'd with the key
 * itself; a corrupt or foreign file is discarded and a fresh state is generated (the
 * same consequence as wiping the TEE applet: old handles stop verifying).
 */
#define GK_MAX_RECORDS 16
struct gk_record {
    uint32_t uid;
    uint32_t failures;
    uint64_t user_id;
    uint64_t last_checked_ms;
};
struct gk_disk_state {
    uint32_t magic;              /* 'GKAE' */
    uint32_t version;            /* 1 */
    uint8_t  key[32];
    uint64_t authenticator_id;
    uint32_t record_count;
    uint32_t reserved;
    struct gk_record records[GK_MAX_RECORDS];
    uint8_t  mac[32];            /* HMAC-SHA256(key, everything before this field) */
};
#define GK_STATE_MAGIC 0x474b4145u

static struct gk_disk_state g_state;
static int g_state_loaded;
static int g_state_persistent;   /* the state reached a file at least once */
static char g_state_path[128];
static long g_lock[8];           /* zero-initialised pthread mutex (bionic and glibc agree) */

static const char *const g_state_dirs[] = {
    "/data/misc/gatekeeper",
    "/data/system/gatekeeper",
    "/data/vendor/gatekeeper",
    0
};
#define GK_STATE_NAME "aemu_gatekeeper.state"

static void state_save_locked(void) {
    if (!g_state_path[0]) return;
    g_state.magic = GK_STATE_MAGIC;
    g_state.version = 1;
    hmac_sha256(g_state.key, 32, (const uint8_t *)&g_state,
                (unsigned long)((uint8_t *)&g_state.mac - (uint8_t *)&g_state), g_state.mac);
    char tmp[160];
    unsigned long pl = strlen(g_state_path);
    memcpy(tmp, g_state_path, pl);
    memcpy(tmp + pl, ".tmp", 5);
    int fd = open(tmp, GK_O_WRONLY | GK_O_CREAT | GK_O_TRUNC, 0600);
    if (fd < 0) return;
    const uint8_t *p = (const uint8_t *)&g_state;
    unsigned long left = sizeof(g_state);
    while (left) {
        long w = write(fd, p, left);
        if (w <= 0) { close(fd); unlink(tmp); return; }
        p += w;
        left -= (unsigned long)w;
    }
    close(fd);
    if (rename(tmp, g_state_path) == 0) g_state_persistent = 1;
    else unlink(tmp);
}

static void state_init_locked(void) {
    if (g_state_loaded) return;
    g_state_loaded = 1;
    memset(&g_state, 0, sizeof(g_state));
    for (int d = 0; g_state_dirs[d]; d++) {
        char path[160];
        unsigned long dl = strlen(g_state_dirs[d]);
        memcpy(path, g_state_dirs[d], dl);
        path[dl] = '/';
        memcpy(path + dl + 1, GK_STATE_NAME, sizeof(GK_STATE_NAME));
        int fd = open(path, GK_O_RDONLY);
        if (fd < 0) continue;
        struct gk_disk_state disk;
        unsigned long got = 0;
        uint8_t *p = (uint8_t *)&disk;
        while (got < sizeof(disk)) {
            long r = read(fd, p + got, sizeof(disk) - got);
            if (r <= 0) break;
            got += (unsigned long)r;
        }
        close(fd);
        if (got != sizeof(disk)) continue;
        if (disk.magic != GK_STATE_MAGIC || disk.version != 1) continue;
        uint8_t mac[32];
        hmac_sha256(disk.key, 32, (const uint8_t *)&disk,
                    (unsigned long)((uint8_t *)&disk.mac - (uint8_t *)&disk), mac);
        if (!ct_equal(mac, disk.mac, 32)) continue;
        g_state = disk;
        memcpy(g_state_path, path, strlen(path) + 1);
        g_state_persistent = 1;
        GK_LOG("state loaded from %s", g_state_path);
        return;
    }
    /* fresh state */
    random_bytes(g_state.key, 32);
    do { g_state.authenticator_id = random_u64(); } while (!g_state.authenticator_id);
    g_state.record_count = 0;
    for (int d = 0; g_state_dirs[d]; d++) {
        mkdir(g_state_dirs[d], 0770);
        unsigned long dl = strlen(g_state_dirs[d]);
        memcpy(g_state_path, g_state_dirs[d], dl);
        g_state_path[dl] = '/';
        memcpy(g_state_path + dl + 1, GK_STATE_NAME, sizeof(GK_STATE_NAME));
        state_save_locked();
        if (g_state_persistent) {
            GK_LOG("new state created at %s", g_state_path);
            return;
        }
        g_state_path[0] = 0;
    }
    GK_LOG("warning: no writable state directory; running in-memory only, "
           "enrolled handles will not survive a service restart");
}

static void state_init(void) {
    pthread_mutex_lock(g_lock);
    state_init_locked();
    pthread_mutex_unlock(g_lock);
}

/* ------------------------------------------------------------------ failure records / throttling
 *
 * Mirrors libgatekeeper (gatekeeper.cpp, android-8.1.0): the record is fetched, an
 * in-progress timeout is honoured before the password is even looked at, the counter
 * is incremented up front, and ComputeRetryTimeout's table turns the new counter into
 * the timeout reported for a failed attempt.
 */
static struct gk_record *record_for_locked(uint32_t uid, uint64_t user_id) {
    struct gk_record *slot = 0;
    for (uint32_t i = 0; i < g_state.record_count && i < GK_MAX_RECORDS; i++) {
        if (g_state.records[i].uid == uid) { slot = &g_state.records[i]; break; }
    }
    if (!slot) {
        if (g_state.record_count >= GK_MAX_RECORDS) return 0;
        slot = &g_state.records[g_state.record_count++];
        memset(slot, 0, sizeof(*slot));
        slot->uid = uid;
    }
    if (slot->user_id != user_id) {
        slot->user_id = user_id;
        slot->failures = 0;
        slot->last_checked_ms = 0;
    }
    return slot;
}

static uint32_t compute_retry_timeout(uint32_t failures) {
    static const uint32_t failure_timeout_ms = 30000;
    if (failures == 0) return 0;
    if (failures <= 10) return failures % 5 == 0 ? failure_timeout_ms : 0;
    if (failures < 30) return failure_timeout_ms;
    if (failures < 140) return failure_timeout_ms << ((failures - 30) / 10);
    return 24u * 60u * 60u * 1000u;   /* one day */
}

/* Returns the remaining timeout (>0) if this attempt must be refused outright,
 * 0 if it may proceed. Caller holds the lock; now is ms since boot. */
static uint32_t throttle_gate_locked(struct gk_record *rec, uint64_t now) {
    uint32_t timeout = compute_retry_timeout(rec->failures);
    if (!timeout) return 0;
    uint64_t last = rec->last_checked_ms;
    if (now > last && now < last + timeout) return (uint32_t)(timeout - (now - last));
    if (now <= last) {
        /* clock went backwards (host boottime differs across a guest restart):
         * restart the window instead of counting a new failure */
        rec->last_checked_ms = now;
        state_save_locked();
        return timeout;
    }
    return 0;
}

/* ------------------------------------------------------------------ password signature / tokens */

/* signature = scrypt(password = master_key || handle[0..17) || password,
 *                    salt = handle salt (8 bytes), N=16384, r=8, p=1, 32 bytes out).
 * The metadata prefix is libgatekeeper's: version || user_id || flags, the first
 * 17 bytes of the packed handle. The key prefix is the hardware key's software stand-in. */
static int password_signature(const struct gk_handle *handle, const uint8_t *password,
                              uint32_t password_length, uint8_t out[32]) {
    unsigned long meta = sizeof(handle->version) + sizeof(handle->user_id) + sizeof(handle->flags);
    unsigned long total = 32 + meta + password_length;
    uint8_t *buf = malloc(total);
    if (!buf) return -1;
    memcpy(buf, g_state.key, 32);
    memcpy(buf + 32, handle, meta);
    if (password_length) memcpy(buf + 32 + meta, password, password_length);
    uint8_t saltbuf[8];
    memcpy(saltbuf, &handle->salt, 8);
    int rc = gk_scrypt(buf, total, saltbuf, 8, 16384, 8, 1, out, 32);
    /* scrub the keyed copy */
    memset(buf, 0, total);
    free(buf);
    return rc;
}

static int handle_matches(const struct gk_handle *handle, const uint8_t *password,
                          uint32_t password_length) {
    uint8_t sig[32];
    if (password_signature(handle, password, password_length, sig) != 0) return 0;
    return ct_equal(sig, handle->signature, 32);
}

static void mint_auth_token(const struct gk_handle *handle, uint64_t challenge,
                            uint8_t **out_token, uint32_t *out_length) {
    struct gk_auth_token *token = calloc(1, sizeof(*token));
    if (!token) { *out_token = 0; *out_length = 0; return; }
    token->version = 0;
    token->challenge = challenge;
    token->user_id = handle->user_id;
    token->authenticator_id = g_state.authenticator_id;
    token->authenticator_type = (uint32_t)GK_HW_AUTH_PASSWORD << 24;   /* htonl on a little-endian host */
    uint64_t ts = now_ms_since_boot();
    uint64_t be = 0;
    for (int i = 0; i < 8; i++) be |= ((ts >> (8 * i)) & 0xff) << (56 - 8 * i);
    token->timestamp = be;                              /* htobe64 */
    hmac_sha256(g_state.key, 32, (const uint8_t *)token,
                (unsigned long)((uint8_t *)&token->hmac - (uint8_t *)token), token->hmac);
    *out_token = (uint8_t *)token;
    *out_length = (uint32_t)sizeof(*token);
}

/* ------------------------------------------------------------------ device operations */

static int gk_enroll(const struct gatekeeper_device *dev, uint32_t uid,
        const uint8_t *current_password_handle, uint32_t current_password_handle_length,
        const uint8_t *current_password, uint32_t current_password_length,
        const uint8_t *desired_password, uint32_t desired_password_length,
        uint8_t **enrolled_password_handle, uint32_t *enrolled_password_handle_length) {
    (void)dev;
    if (!desired_password || desired_password_length == 0) return -GK_EINVAL;
    if (!enrolled_password_handle || !enrolled_password_handle_length) return -GK_EINVAL;
    *enrolled_password_handle = 0;
    *enrolled_password_handle_length = 0;
    pthread_mutex_lock(g_lock);
    state_init_locked();
    uint64_t user_id;
    if (current_password_handle && current_password_handle_length) {
        if (current_password_handle_length != sizeof(struct gk_handle)) {
            pthread_mutex_unlock(g_lock);
            return -GK_EINVAL;
        }
        const struct gk_handle *cur = (const struct gk_handle *)current_password_handle;
        if (cur->version > GK_HANDLE_VERSION) {
            pthread_mutex_unlock(g_lock);
            return -GK_EINVAL;
        }
        user_id = cur->user_id;
        uint32_t timeout_after = 0;
        if (cur->version >= GK_HANDLE_VERSION) {
            struct gk_record *rec = record_for_locked(uid, user_id);
            if (!rec) { pthread_mutex_unlock(g_lock); return -1; }
            uint64_t now = now_ms_since_boot();
            uint32_t remaining = throttle_gate_locked(rec, now);
            if (remaining) { pthread_mutex_unlock(g_lock); return (int)remaining; }
            rec->failures++;
            rec->last_checked_ms = now;
            state_save_locked();
            timeout_after = compute_retry_timeout(rec->failures);
        }
        if (!handle_matches(cur, current_password, current_password_length)) {
            pthread_mutex_unlock(g_lock);
            return timeout_after ? (int)timeout_after : -GK_EINVAL;
        }
    } else {
        do { user_id = random_u64(); } while (!user_id);
    }
    struct gk_record *rec = record_for_locked(uid, user_id);
    if (rec) {
        rec->failures = 0;
        rec->last_checked_ms = 0;
        state_save_locked();
    }
    struct gk_handle *handle = calloc(1, sizeof(*handle));
    if (!handle) { pthread_mutex_unlock(g_lock); return -GK_ENOMEM; }
    handle->version = GK_HANDLE_VERSION;
    handle->user_id = user_id;
    handle->flags = GK_HANDLE_FLAG_THROTTLE_SECURE;
    handle->salt = random_u64();
    handle->hardware_backed = 1;
    if (password_signature(handle, desired_password, desired_password_length,
                           handle->signature) != 0) {
        free(handle);
        pthread_mutex_unlock(g_lock);
        return -1;
    }
    pthread_mutex_unlock(g_lock);
    *enrolled_password_handle = (uint8_t *)handle;
    *enrolled_password_handle_length = (uint32_t)sizeof(*handle);
    GK_LOG("enroll: uid %u ok", uid);
    return 0;
}

static int gk_verify(const struct gatekeeper_device *dev, uint32_t uid, uint64_t challenge,
        const uint8_t *enrolled_password_handle, uint32_t enrolled_password_handle_length,
        const uint8_t *provided_password, uint32_t provided_password_length,
        uint8_t **auth_token, uint32_t *auth_token_length, bool *request_reenroll) {
    (void)dev;
    if (auth_token) *auth_token = 0;
    if (auth_token_length) *auth_token_length = 0;
    if (request_reenroll) *request_reenroll = false;
    if (!enrolled_password_handle || enrolled_password_handle_length != sizeof(struct gk_handle) ||
        !provided_password) {
        return -GK_EINVAL;
    }
    const struct gk_handle *handle = (const struct gk_handle *)enrolled_password_handle;
    if (handle->version > GK_HANDLE_VERSION) return -GK_EINVAL;
    pthread_mutex_lock(g_lock);
    state_init_locked();
    struct gk_record *rec = 0;
    uint32_t timeout_after = 0;
    if (handle->version >= GK_HANDLE_VERSION) {
        rec = record_for_locked(uid, handle->user_id);
        if (!rec) { pthread_mutex_unlock(g_lock); return -1; }
        uint64_t now = now_ms_since_boot();
        uint32_t remaining = throttle_gate_locked(rec, now);
        if (remaining) { pthread_mutex_unlock(g_lock); return (int)remaining; }
        rec->failures++;
        rec->last_checked_ms = now;
        state_save_locked();
        timeout_after = compute_retry_timeout(rec->failures);
    }
    if (handle_matches(handle, provided_password, provided_password_length)) {
        if (rec) {
            rec->failures = 0;
            rec->last_checked_ms = 0;
            state_save_locked();
        }
        if (auth_token && auth_token_length)
            mint_auth_token(handle, challenge, auth_token, auth_token_length);
        if (request_reenroll) *request_reenroll = handle->version < GK_HANDLE_VERSION;
        pthread_mutex_unlock(g_lock);
        GK_LOG("verify: uid %u ok", uid);
        return 0;
    }
    pthread_mutex_unlock(g_lock);
    GK_LOG("verify: uid %u rejected", uid);
    return timeout_after ? (int)timeout_after : -GK_EINVAL;
}

static int gk_delete_user(const struct gatekeeper_device *dev, uint32_t uid) {
    (void)dev;
    pthread_mutex_lock(g_lock);
    state_init_locked();
    uint32_t w = 0;
    for (uint32_t i = 0; i < g_state.record_count && i < GK_MAX_RECORDS; i++)
        if (g_state.records[i].uid != uid) g_state.records[w++] = g_state.records[i];
    if (w != g_state.record_count) {
        g_state.record_count = w;
        state_save_locked();
    }
    pthread_mutex_unlock(g_lock);
    return 0;
}

static int gk_delete_all_users(const struct gatekeeper_device *dev) {
    (void)dev;
    pthread_mutex_lock(g_lock);
    state_init_locked();
    /* Only the throttle records live here; password handles are self-contained and
     * owned by LockSettings, and the master key must survive gatekeeperd's cold-boot
     * clear_state_if_needed() call, which lands here via deleteAllUsers. */
    if (g_state.record_count) {
        g_state.record_count = 0;
        state_save_locked();
    }
    pthread_mutex_unlock(g_lock);
    return 0;
}

static int gk_close(struct hw_device *device) {
    free(device);
    return 0;
}

static int gk_open(const struct hw_module *module, const char *id, struct hw_device **out) {
    if (!id || strcmp(id, "gatekeeper") != 0) return -GK_EINVAL;
    state_init();
    struct gatekeeper_device *dev = calloc(1, sizeof(*dev));
    if (!dev) return -GK_ENOMEM;
    dev->common.tag = HARDWARE_DEVICE_TAG;
    dev->common.version = GATEKEEPER_MODULE_API_VERSION_0_1;
    dev->common.module = (struct hw_module *)module;
    dev->common.close = gk_close;
    dev->enroll = gk_enroll;
    dev->verify = gk_verify;
    dev->delete_user = gk_delete_user;
    dev->delete_all_users = gk_delete_all_users;
    *out = &dev->common;
    GK_LOG("gatekeeper device opened (persistent state: %s)",
           g_state_persistent ? g_state_path : "unavailable");
    return 0;
}

static struct hw_module_methods gk_methods = { gk_open };

struct gk_module {
    struct hw_module common;
};

__attribute__((visibility("default"))) struct gk_module HMI = {
    .common = {
        .tag = HARDWARE_MODULE_TAG,
        .module_api_version = GATEKEEPER_MODULE_API_VERSION_0_1,
        .hal_api_version = 0,
        .id = "gatekeeper",
        .name = "AEmulator software gatekeeper HAL",
        .author = "AEmulator",
        .methods = &gk_methods,
    }
};
