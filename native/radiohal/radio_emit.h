#define CONF_PATH "/vendor/etc/aemu-radio.conf"
#define INT_MAX_U 0x7fffffff

static char g_cfg[8192];
static size_t g_cfgl;

static void cfg_load(void) {
    g_cfgl = 0;
    int fd = open(CONF_PATH, O_RDONLY);
    if (fd < 0) { g_cfg[0] = 0; return; }
    for (;;) {
        ssize_t n = read(fd, g_cfg + g_cfgl, sizeof g_cfg - 1 - g_cfgl);
        if (n <= 0) break;
        g_cfgl += (size_t)n;
        if (g_cfgl >= sizeof g_cfg - 1) break;
    }
    close(fd);
    g_cfg[g_cfgl] = 0;
    for (size_t i = 0; i < g_cfgl; i++) if (g_cfg[i] == '\n' || g_cfg[i] == '\r') g_cfg[i] = 0;
}

static const char *cfg(const char *key) {
    size_t kl = strlen(key), i = 0;
    while (i < g_cfgl) {
        size_t l = strlen(g_cfg + i);
        if (l > kl && g_cfg[i + kl] == '=' && strncmp(g_cfg + i, key, kl) == 0) return g_cfg + i + kl + 1;
        i += l + 1;
    }
    return "";
}

static i32 cfg_int(const char *key) {
    const char *s = cfg(key);
    i32 v = 0;
    int neg = 0;
    if (*s == '-') { neg = 1; s++; }
    while (*s >= '0' && *s <= '9') { v = v * 10 + (*s - '0'); s++; }
    return neg ? -v : v;
}

static int hexval(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static int hex_to_bytes(const char *s, u8 *out, int cap) {
    int n = 0;
    if (!s[0]) return -1;
    while (s[0] && s[1] && n < cap) {
        int a = hexval(s[0]), b = hexval(s[1]);
        if (a < 0 || b < 0) return -1;
        out[n++] = (u8)((a << 4) | b);
        s += 2;
    }
    return n;
}

static char g_hex[256];
static i32 g_sw1, g_sw2;

static void put_hex(char **d, u32 v, int digits) {
    static const char H[] = "0123456789ABCDEF";
    for (int i = digits - 1; i >= 0; i--) *(*d)++ = H[(v >> (4 * i)) & 15];
}

static void hex_set_str(const char *s) {
    size_t i = 0;
    while (s[i] && i < sizeof g_hex - 1) { g_hex[i] = s[i]; i++; }
    g_hex[i] = 0;
}

static int path_is_mf(const char *p) {
    if (!p[0]) return 1;
    return strlen(p) == 4 && (p[0] == '3') && (p[1] == 'F' || p[1] == 'f') && p[2] == '0' && p[3] == '0';
}

static void sim_io(i32 cmd, i32 file, const char *path, i32 p1, i32 p2, i32 p3) {
    const char *key = NULL;
    int record = 0;
    g_hex[0] = 0;
    switch (file) {
    case 0x2fe2: if (path_is_mf(path)) key = "ef_iccid"; break;
    case 0x6f46: key = "ef_spn"; break;
    case 0x6fad: key = "ef_ad"; break;
    case 0x6f40: key = "ef_msisdn"; record = 1; break;
    default: break;
    }
    u8 data[64];
    int n = key ? hex_to_bytes(cfg(key), data, (int)sizeof data) : -1;
    g_sw1 = 0x90; g_sw2 = 0;
    if (cmd == 0xf2) { hex_set_str("00000000" "7F20" "02" "0000000000" "09" "00" "00" "0A" "03" "00" "838A838A"); return; }
    if (n < 0) { g_sw1 = 0x6a; g_sw2 = 0x82; return; }
    if (p1 < 0 || p1 > 255 || p2 < 0 || p2 > 255 || p3 < 0 || p3 > 255) { g_sw1 = 0x6a; g_sw2 = 0x86; return; }
    char *d = g_hex;
    if (cmd == 0xc0) {
        if (p1 != 0 || p2 != 0 || p3 != 15) { g_sw1 = 0x6a; g_sw2 = 0x86; return; }
        memcpy(d, "0000", 4); d += 4;
        put_hex(&d, (u32)n, 4);
        put_hex(&d, (u32)file, 4);
        memcpy(d, "04000000000102", 14); d += 14;
        put_hex(&d, record ? 1 : 0, 2);
        put_hex(&d, record ? (u32)n : 0, 2);
    } else if (cmd == 0xb0) {
        int off = (p1 << 8) | p2;
        if (record || p3 == 0 || off + p3 > n) { g_sw1 = 0x6a; g_sw2 = 0x86; return; }
        for (int i = 0; i < p3; i++) put_hex(&d, data[off + i], 2);
    } else if (cmd == 0xb2) {
        if (!record || p2 != 4 || p1 != 1 || p3 != n) { g_sw1 = 0x6a; g_sw2 = 0x86; return; }
        for (int i = 0; i < p3; i++) put_hex(&d, data[i], 2);
    } else { g_sw1 = 0x6d; g_sw2 = 0; return; }
    *d = 0;
}

static void pb_info(struct pb *p, i32 serial, i32 err) {
    i32 *m = aalloc(16);
    m[0] = 0; m[1] = serial; m[2] = err;
    pb_ptr(p, m, 12, -1, 0);
}

static struct hstr *hs_set(u8 *m, u32 off, const char *s, char **chars_out) {
    size_t n = strlen(s);
    char *chars = aalloc(n + 1);
    memcpy(chars, s, n);
    struct hstr *h = (struct hstr *)(m + off);
    h->ptr = (u64)(uptr)chars; h->size = (u32)n;
    *chars_out = chars;
    return h;
}

static void pb_strstruct(struct pb *p, u8 *m, size_t size, const u32 *offs, const char *const *vals, int n) {
    char *chars[8];
    for (int i = 0; i < n; i++) hs_set(m, offs[i], vals[i], &chars[i]);
    int parent = pb_ptr(p, m, size, -1, 0);
    for (int i = 0; i < n; i++) pb_ptr(p, chars[i], strlen(vals[i]) + 1, parent, offs[i]);
}

static void pb_empty_children(struct pb *p, int parent, u64 first_off, int count) {
    for (int i = 0; i < count; i++) pb_ptr(p, aalloc(16), 0, parent, first_off + 16 * (u64)i);
}

static void pb_card(struct pb *p, int present) {
    u8 *m = aalloc(40);
    i32 *w = (i32 *)m;
    int n = present ? 1 : 0;
    w[0] = present ? 1 : 0; w[1] = 0; w[2] = present ? 0 : -1; w[3] = -1; w[4] = -1;
    u8 *arr = aalloc(80);
    struct hstr *vec = (struct hstr *)(m + 24);
    vec->ptr = (u64)(uptr)arr; vec->size = (u32)n;
    i32 *a = (i32 *)arr;
    a[0] = 2; a[1] = 5; a[2] = 0;
    char *c1, *c2;
    hs_set(arr, 16, "", &c1);
    hs_set(arr, 32, "", &c2);
    int pp = pb_ptr(p, m, 40, -1, 0);
    int pa = pb_ptr(p, arr, (u64)n * 64, pp, 24);
    if (n) {
        pb_ptr(p, c1, 1, pa, 16);
        pb_ptr(p, c2, 1, pa, 32);
    }
}

static void pb_signal(struct pb *p) {
    i32 *m = aalloc(64);
    for (int i = 0; i < 15; i++) m[i] = INT_MAX_U;
    m[0] = 20; m[1] = 99; m[8] = 99;
    pb_ptr(p, m, 60, -1, 0);
}

static void pb_reg(struct pb *p, int voice, int up, int rat) {
    size_t size = voice ? 120 : 104;
    u32 base = voice ? 32 : 16;
    u8 *m = aalloc(size);
    i32 *w = (i32 *)m;
    w[0] = up ? 1 : 0;
    w[1] = up ? rat : 0;
    if (!voice) w[3] = 1;
    *(i32 *)(m + base) = up ? 1 : 0;
    u8 *arr = aalloc(64);
    struct hstr *v0 = (struct hstr *)(m + base + 8);
    char *mcc = NULL, *mnc = NULL;
    const char *smcc = cfg("mcc"), *smnc = cfg("mnc");
    if (up) {
        v0->ptr = (u64)(uptr)arr; v0->size = 1;
        hs_set(arr, 0, smcc, &mcc);
        hs_set(arr, 16, smnc, &mnc);
        *(i32 *)(arr + 32) = 1;
        *(i32 *)(arr + 36) = 1;
    }
    int pp = pb_ptr(p, m, size, -1, 0);
    int pa = pb_ptr(p, arr, up ? 48 : 0, pp, base + 8);
    if (up) {
        pb_ptr(p, mcc, strlen(smcc) + 1, pa, 0);
        pb_ptr(p, mnc, strlen(smnc) + 1, pa, 16);
    }
    pb_empty_children(p, pp, base + 8 + 16, 4);
}

static void pb_empty_struct(struct pb *p, size_t size) { pb_ptr(p, aalloc(size + 8), size, -1, 0); }

static const u32 SETUP_OFFS[6] = { 16, 32, 48, 64, 80, 96 };

static void setup_fill(u8 *m, int ok, const char **vals) {
    static const char *const OK[6] = { "IP", "rmnet0", "10.0.2.15/24", "8.8.8.8 1.1.1.1", "10.0.2.2", "" };
    static const char *const NO[6] = { "", "", "", "", "", "" };
    i32 *w = (i32 *)m;
    w[0] = 0; w[1] = -1; w[2] = ok ? 1 : -1; w[3] = ok ? 2 : 0;
    w[28] = ok ? 1500 : 0;
    for (int i = 0; i < 6; i++) vals[i] = ok ? OK[i] : NO[i];
}

static void pb_setup(struct pb *p, int ok) {
    const char *vals[6];
    u8 *m = aalloc(128);
    setup_fill(m, ok, vals);
    pb_strstruct(p, m, 120, SETUP_OFFS, vals, 6);
}

static void pb_setup_list(struct pb *p, int n) {
    struct hstr *vec = aalloc(16);
    u8 *arr = aalloc((size_t)n * 128 + 16);
    vec->ptr = (u64)(uptr)arr; vec->size = (u32)n;
    int pv = pb_ptr(p, vec, 16, -1, 0);
    int pa = pb_ptr(p, arr, (u64)n * 120, pv, 0);
    for (int i = 0; i < n; i++) {
        const char *vals[6];
        char *chars[6];
        u8 *e = arr + (size_t)i * 120;
        setup_fill(e, 1, vals);
        for (int k = 0; k < 6; k++) hs_set(e, SETUP_OFFS[k], vals[k], &chars[k]);
        for (int k = 0; k < 6; k++) pb_ptr(p, chars[k], strlen(vals[k]) + 1, pa, (u64)i * 120 + SETUP_OFFS[k]);
    }
}
