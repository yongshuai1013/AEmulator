#include "radio_binder.h"
#include "radio_emit.h"

#define D_RADIO "android.hardware.radio@1.0::IRadio"
#define D_SEC "vendor.samsung.hardware.radio.secbridge@1.0::ISecBridge"
#define D_BASE "android.hidl.base@1.0::IBase"
#define TOK_RESP "android.hardware.radio@1.0::IRadioResponse"
#define TOK_IND "android.hardware.radio@1.0::IRadioIndication"

#define NODE_RADIO 0
#define NODE_SEC1 1
#define NODE_SEC2 2
static u8 g_nodes[3];

struct peer { u32 handle; int valid; u64 cookie; };
static struct peer g_resp = { 0, 0, 1 };
static struct peer g_ind = { 0, 0, 2 };

static i32 g_state;
static i32 g_pref = -1;
static i32 g_i0, g_i1;
static const char *g_s[5];
static int g_present, g_up, g_rat;
static i32 g_call = -1;
static char g_num[48];
static int g_cs = -1;
static char g_lb[1100];
static u32 g_ll;
struct pfd { int fd; short events, revents; };

static void peer_drop(struct peer *x) {
    if (!x->valid) return;
    w_hc(BC_CLEAR_DEATH_NOTIFICATION, x->handle, x->cookie);
    w_u32(BC_RELEASE, x->handle);
    w_u32(BC_DECREFS, x->handle);
    x->valid = 0;
}

static void peer_set(struct peer *x, u32 handle) {
    peer_drop(x);
    w_u32(BC_INCREFS, handle);
    w_u32(BC_ACQUIRE, handle);
    w_hc(BC_REQUEST_DEATH_NOTIFICATION, handle, x->cookie);
    x->handle = handle;
    x->valid = 1;
}

static void peer_died(u64 cookie) {
    struct peer *x = cookie == g_resp.cookie ? &g_resp : cookie == g_ind.cookie ? &g_ind : NULL;
    w_cmd(BC_DEAD_BINDER_DONE, &cookie, 8);
    if (!x || !x->valid) return;
    LOG(L_INFO, "%s died", x == &g_resp ? "IRadioResponse" : "IRadioIndication");
    peer_drop(x);
    if (x == &g_resp) g_state = 0;
}

static void pb_calls(struct pb *p) {
    struct hstr *vec = aalloc(16);
    u8 *arr = aalloc(96);
    vec->ptr = (u64)(uptr)arr; vec->size = 1;
    i32 *w = (i32 *)arr;
    w[0] = g_call; w[1] = 1; w[2] = g_num[0] == '+' ? 145 : 129;
    arr[13] = 1; arr[15] = 1;
    char *c1, *c2;
    hs_set(arr, 24, g_num, &c1);
    hs_set(arr, 48, "", &c2);
    void *uus = aalloc(16);
    ((struct hstr *)(arr + 72))->ptr = (u64)(uptr)uus;
    int pv = pb_ptr(p, vec, 16, -1, 0);
    int pa = pb_ptr(p, arr, 88, pv, 0);
    pb_ptr(p, c1, strlen(g_num) + 1, pa, 24);
    pb_ptr(p, c2, 1, pa, 48);
    pb_ptr(p, uus, 0, pa, 72);
}

static void respond(u32 rc, i32 serial, i32 err) {
    if (!g_resp.valid) return;
    struct pb p;
    pb_init(&p, 3072, 64);
    pb_cstr(&p, TOK_RESP);
    pb_info(&p, serial, err);
    switch (rc) {
    case 1: pb_card(&p, g_present); break;
    case 2: case 3: case 4: case 5: case 6: case 7: case 8: case 40: case 41: case 43: case 52: case 53: case 56: case 65:
    case 70: case 72: case 74: case 86: case 94: case 98: case 124:
        pb_i32(&p, g_i0); break;
    case 31: case 35: case 102:
        pb_i32(&p, g_i0); pb_i32(&p, g_i1); break;
    case 9: if (g_call >= 0) pb_calls(&p); else pb_empty_vec(&p); break;
    case 33: case 46: case 59: case 66: case 79: case 82: case 99: case 114:
        pb_empty_vec(&p); break;
    case 11: case 49: case 60: case 90: case 95: case 108:
        pb_string(&p, g_s[0]); break;
    case 22:
        pb_string(&p, g_s[0]); pb_string(&p, g_s[1]); pb_string(&p, g_s[2]); break;
    case 85:
        for (int i = 0; i < 5; i++) pb_string(&p, g_s[i]);
        break;
    case 88:
        for (int i = 0; i < 4; i++) pb_string(&p, g_s[i]);
        break;
    case 18: {
        static const u32 offs[1] = { 8 };
        const char *vals[1] = { "" };
        u8 *m = aalloc(32);
        *(i32 *)m = g_i0;
        pb_strstruct(&p, m, 24, offs, vals, 1);
        break;
    }
    case 19: pb_signal(&p); break;
    case 20: pb_reg(&p, 1, g_up, g_rat); break;
    case 21: pb_reg(&p, 0, g_up, g_rat); break;
    case 25: case 26: case 77: case 103: {
        static const u32 offs[1] = { 8 };
        const char *vals[1] = { "" };
        u8 *m = aalloc(32);
        ((i32 *)m)[0] = 1;
        ((i32 *)m)[6] = -1;
        pb_strstruct(&p, m, 32, offs, vals, 1);
        break;
    }
    case 27: pb_setup(&p, err == 0); break;
    case 54: pb_setup_list(&p, g_i0); break;
    case 28: case 97: case 104: case 107: case 115: {
        static const u32 offs[1] = { 8 };
        const char *vals[1] = { g_s[0] };
        u8 *m = aalloc(32);
        ((i32 *)m)[0] = g_i0;
        ((i32 *)m)[1] = g_i1;
        pb_strstruct(&p, m, 24, offs, vals, 1);
        break;
    }
    case 105: pb_i32(&p, g_i0); pb_empty_vec(&p); break;
    case 118: case 119: {
        static const u32 offs[1] = { 16 };
        const char *vals[1] = { "" };
        pb_strstruct(&p, aalloc(48), 40, offs, vals, 1);
        break;
    }
    case 120: case 121: case 122: pb_empty_struct(&p, 8); break;
    case 123: pb_empty_struct(&p, 32); break;
    case 125: {
        pb_i32(&p, 1);
        int pp = pb_ptr(&p, aalloc(48), 32, -1, 0);
        pb_empty_children(&p, pp, 0, 2);
        break;
    }
    default: break;
    }
    send_txn(BC_TRANSACTION, g_resp.handle, rc, TF_ONE_WAY, &p);
}

static void ind(u32 code, int has_arg, i32 arg) {
    if (!g_ind.valid) return;
    struct pb p;
    pb_init(&p, 128, 1);
    pb_cstr(&p, TOK_IND);
    pb_i32(&p, 0);
    if (has_arg) pb_i32(&p, arg);
    send_txn(BC_TRANSACTION, g_ind.handle, code, TF_ONE_WAY, &p);
}

static void ind_bytes(u32 code, const u8 *d, u32 n) {
    if (!g_ind.valid) return;
    struct pb p;
    pb_init(&p, 1024, 8);
    pb_cstr(&p, TOK_IND);
    pb_i32(&p, 0);
    struct hstr *vec = aalloc(16);
    u8 *arr = aalloc(n + 16);
    memcpy(arr, d, n);
    vec->ptr = (u64)(uptr)arr; vec->size = n;
    int pv = pb_ptr(&p, vec, 16, -1, 0);
    pb_ptr(&p, arr, n, pv, 0);
    send_txn(BC_TRANSACTION, g_ind.handle, code, TF_ONE_WAY, &p);
}

static void ind_ring(void) {
    if (!g_ind.valid) return;
    struct pb p;
    pb_init(&p, 512, 8);
    pb_cstr(&p, TOK_IND);
    pb_i32(&p, 0);
    pb_i32(&p, 1);
    pb_ptr(&p, aalloc(16), 4, -1, 0);
    send_txn(BC_TRANSACTION, g_ind.handle, 18, TF_ONE_WAY, &p);
}

static u32 skip_token(const u8 *d, u32 n) {
    u32 i = 0;
    while (i < n && d[i]) i++;
    i++;
    return (i + 3) & ~3u;
}

static i32 rd32(const u8 *d, u32 n, u32 pos) {
    i32 v = 0;
    if (pos + 4 <= n) memcpy(&v, d + pos, 4);
    return v;
}

static void load_state(void) {
    cfg_load();
    g_present = cfg_int("sim") != 0;
    g_up = cfg_int("up") != 0;
    g_rat = cfg_int("rat");
    if (g_pref < 0) g_pref = g_rat == 14 ? 9 : 0;
}

static void on_request(u32 code, const u8 *d, u32 n, const struct req *r) {
    u32 pos = skip_token(d, n);
    if (pos + 4 > n) return;
    i32 serial = rd32(d, n, pos);
    pos += 4;
    u32 rc = code - 1;
    i32 err = 0;
    g_i0 = 0; g_i1 = 0;
    for (int i = 0; i < 5; i++) g_s[i] = "";
    load_state();
    switch (code) {
    case 3: case 4: case 5: case 6: case 7: case 8: case 9:
        err = 6; g_i0 = -1; break;
    case 11: err = 2; break;
    case 12:
        if (!g_present) err = 2;
        g_s[0] = cfg("imsi");
        break;
    case 19: g_i0 = 16; break;
    case 23:
        if (g_up) { g_s[0] = cfg("operator"); g_s[1] = cfg("short"); g_s[2] = cfg("numeric"); }
        break;
    case 24: {
        i32 on = rd32(d, n, pos) != 0;
        respond(rc, serial, 0);
        g_state = on ? 10 : 0;
        ind(1, 1, g_state);
        if (on) {
            if (g_present) ind(19, 0, 0);
            ind(3, 0, 0);
        }
        return;
    }
    case 28:
        if (!g_up) err = 2;
        break;
    case 29: {
        const struct bbo *b = r->n ? r->o[0].b : NULL;
        g_i0 = 0; g_i1 = 0;
        if (!g_present || !b || b->length < 88) { err = 2; break; }
        const u8 *m = (const u8 *)(uptr)b->buffer;
        const struct hstr *hp = (const struct hstr *)(m + 8);
        const char *path = hp->size && hp->ptr ? (const char *)(uptr)hp->ptr : "";
        sim_io(*(const i32 *)m, *(const i32 *)(m + 4), path, *(const i32 *)(m + 24), *(const i32 *)(m + 28), *(const i32 *)(m + 32));
        g_i0 = g_sw1; g_i1 = g_sw2; g_s[0] = g_hex;
        break;
    }
    case 13: case 14: case 15: case 18:
        g_call = -1;
        respond(rc, serial, 0);
        ind(2, 0, 0);
        return;
    case 39:
        if (g_call >= 0) g_call = 0;
        respond(rc, serial, 0);
        ind(2, 0, 0);
        return;
    case 41: g_i0 = 0; break;
    case 47: err = 6; break;
    case 50: g_s[0] = cfg("baseband"); break;
    case 55: g_i0 = g_up ? 1 : 0; break;
    case 60: err = 6; break;
    case 61: err = 6; break;
    case 65: g_pref = rd32(d, n, pos); break;
    case 66: g_i0 = g_pref; break;
    case 71: g_i0 = 2; break;
    case 86: err = 6; break;
    case 87: err = 6; break;
    case 89:
        g_s[0] = cfg("imei"); g_s[1] = cfg("imeisv");
        break;
    case 91: g_s[0] = "+10000000000"; break;
    case 96: err = 6; break;
    case 98: err = 6; break;
    case 99: g_i0 = g_rat; break;
    case 100: err = 6; break;
    case 104: err = 6; break;
    case 105: case 106: case 107: case 108: err = 6; g_i0 = 0; break;
    case 109: case 110: case 111: case 112: err = 6; break;
    case 116: err = 6; break;
    case 119: case 120: case 121: case 122: case 123: case 124: err = 6; break;
    case 126: g_i0 = 0; break;
    case 130: return;
    default: break;
    }
    respond(rc, serial, err);
}

static int node_kind(const struct btd *tr) {
    for (int i = 0; i < 3; i++) if (tr->cookie == (u64)(uptr)&g_nodes[i]) return i;
    return -1;
}

static void ibase(int kind, u32 code, int oneway) {
    const char *desc = kind == NODE_RADIO ? D_RADIO : D_SEC;
    const char *chain[2] = { desc, D_BASE };
    struct pb p;
    switch (code) {
    case IB_PING: case IB_DEBUG: reply_ok_empty(); break;
    case IB_CHAIN:
        pb_init(&p, 512, 8);
        pb_i32(&p, 0);
        pb_string_vec(&p, chain, 2);
        send_txn(BC_REPLY, 0, 0, 0, &p);
        break;
    case IB_DESCRIPTOR:
        pb_init(&p, 256, 4);
        pb_i32(&p, 0);
        pb_string(&p, desc);
        send_txn(BC_REPLY, 0, 0, 0, &p);
        break;
    case IB_HASH_CHAIN:
        pb_init(&p, 128, 4);
        pb_i32(&p, 0);
        pb_empty_vec(&p);
        send_txn(BC_REPLY, 0, 0, 0, &p);
        break;
    case IB_SYSPROPS: case IB_INSTRUMENTATION: break;
    default:
        if (!oneway) reply_status(UNKNOWN_TRANSACTION);
        break;
    }
}

static void set_response_functions(const struct req *r) {
    if (r->n < 2 || !r->o[0].f || !r->o[1].f || r->o[0].type != T_HANDLE || r->o[1].type != T_HANDLE) {
        LOG(L_WARN, "setResponseFunctions without both callbacks");
        reply_ok_empty();
        return;
    }
    peer_set(&g_resp, (u32)r->o[0].f->binder);
    peer_set(&g_ind, (u32)r->o[1].f->binder);
    reply_ok_empty();
    load_state();
    LOG(L_INFO, "telephony connected, SIM %s, network %s", g_present ? "present" : "absent", g_up ? "up" : "down");
    ind(33, 0, 0);
    ind(1, 1, g_state);
}

static void handle_txn(const struct btd *tr) {
    int oneway = (tr->flags & TF_ONE_WAY) != 0;
    int kind = node_kind(tr);
    struct req r;
    int bad = req_parse(tr, &r) < 0;
    if (bad || kind < 0) {
        if (!oneway) reply_status(UNKNOWN_TRANSACTION);
    } else if (tr->code >= 0x0f000000u) {
        ibase(kind, tr->code, oneway);
    } else if (kind == NODE_RADIO && tr->code == 1) {
        set_response_functions(&r);
    } else if (kind == NODE_RADIO && tr->code >= 2 && tr->code <= 130) {
        on_request(tr->code, (const u8 *)(uptr)tr->buffer, (u32)tr->data_size, &r);
    } else if (!oneway) {
        reply_status(UNKNOWN_TRANSACTION);
    }
    u64 buf = tr->buffer;
    w_cmd(BC_FREE_BUFFER, &buf, 8);
}

struct sync { int state; struct btd tr; };
static void process_br(const u8 *buf, size_t len, struct sync *s);

static int wait_reply(struct sync *s) {
    u8 rb[2048] __attribute__((aligned(8)));
    memset(s, 0, sizeof *s);
    int tries = 0;
    while (!s->state) {
        size_t n = 0;
        if (rw(rb, sizeof rb, &n) < 0) {
            struct pfd f;
            f.fd = g_fd; f.events = 1; f.revents = 0;
            poll(&f, 1, 500);
            if (++tries > 40) s->state = 2;
            continue;
        }
        process_br(rb, n, s);
    }
    return s->state == 1 ? 0 : -1;
}

static void process_br(const u8 *buf, size_t len, struct sync *s) {
    const u8 *p = buf, *end = buf + len;
    while (p + 4 <= end) {
        u32 cmd;
        memcpy(&cmd, p, 4);
        u32 sz = (cmd >> 16) & 0x3fff;
        p += 4;
        if (p + sz > end) break;
        switch (cmd) {
        case BR_TRANSACTION: case BR_TRANSACTION_SEC_CTX: {
            struct btd tr;
            memcpy(&tr, p, sizeof tr);
            handle_txn(&tr);
            break;
        }
        case BR_REPLY: {
            struct btd tr;
            memcpy(&tr, p, sizeof tr);
            if (s && !s->state) { s->tr = tr; s->state = 1; }
            else { u64 b = tr.buffer; w_cmd(BC_FREE_BUFFER, &b, 8); }
            break;
        }
        case BR_DEAD_REPLY: case BR_FAILED_REPLY:
            if (s && !s->state) s->state = 2;
            break;
        case BR_INCREFS: case BR_ACQUIRE: {
            u8 b[16];
            memcpy(b, p, 16);
            w_cmd(cmd == BR_INCREFS ? BC_INCREFS_DONE : BC_ACQUIRE_DONE, b, 16);
            break;
        }
        case BR_DEAD_BINDER: { u64 cookie; memcpy(&cookie, p, 8); peer_died(cookie); break; }
        default: break;
        }
        p += sz;
    }
}

static int register_node(int kind, const char *name) {
    struct pb p;
    pb_init(&p, 512, 8);
    pb_cstr(&p, "android.hidl.manager@1.0::IServiceManager");
    pb_string(&p, name);
    pb_node(&p, &g_nodes[kind]);
    send_txn(BC_TRANSACTION, 0, SM_ADD, 0, &p);
    struct sync s;
    int ok = 0;
    if (wait_reply(&s) == 0) {
        const struct btd *tr = &s.tr;
        if (!(tr->flags & TF_STATUS_CODE) && tr->data_size >= 8) {
            i32 status, val;
            memcpy(&status, (const void *)(uptr)tr->buffer, 4);
            memcpy(&val, (const void *)(uptr)(tr->buffer + 4), 4);
            ok = status == 0 && val != 0;
        }
        u64 buf = tr->buffer;
        w_cmd(BC_FREE_BUFFER, &buf, 8);
    }
    rw(NULL, 0, NULL);
    g_ap = 0;
    return ok;
}

static int register_retry(int kind, const char *name, int tries) {
    for (int i = 0; i < tries; i++) {
        if (register_node(kind, name)) return 1;
        usleep(500000);
    }
    return 0;
}

static void cs_close(void) {
    if (g_cs >= 0) close(g_cs);
    g_cs = -1;
    g_ll = 0;
}

static void cs_connect(void) {
    struct { u16 family; char path[108]; } sa;
    memset(&sa, 0, sizeof sa);
    sa.family = 1;
    const char *p = "/dev/socket/aemu-radio";
    for (int i = 0; p[i]; i++) sa.path[i] = p[i];
    int fd = socket(1, 1, 0);
    if (fd < 0) return;
    if (connect(fd, &sa, sizeof sa) < 0) { close(fd); return; }
    g_cs = fd;
    g_ll = 0;
    LOG(L_INFO, "command socket connected");
}

static void cs_line(char *l) {
    u8 d[512];
    LOG(L_INFO, "command: %.12s", l);
    if (strncmp(l, "ring ", 5) == 0) {
        size_t i = 0;
        for (const char *q = l + 5; *q && i < sizeof g_num - 1; q++) g_num[i++] = *q;
        g_num[i] = 0;
        g_call = 4;
        ind(2, 0, 0);
        ind_ring();
    } else if (strncmp(l, "sms ", 4) == 0) {
        int n = hex_to_bytes(l + 4, d, (int)sizeof d);
        if (n > 0) ind_bytes(4, d, (u32)n);
    } else if (strncmp(l, "cb ", 3) == 0) {
        int n = hex_to_bytes(l + 3, d, (int)sizeof d);
        if (n > 0) ind_bytes(21, d, (u32)n);
    }
}

static void cs_read(void) {
    ssize_t n = read(g_cs, g_lb + g_ll, sizeof g_lb - 1 - g_ll);
    if (n <= 0) { cs_close(); return; }
    g_ll += (u32)n;
    u32 start = 0;
    for (u32 i = 0; i < g_ll; i++) {
        if (g_lb[i] != '\n') continue;
        g_lb[i] = 0;
        cs_line(g_lb + start);
        start = i + 1;
    }
    if (start) { memcpy(g_lb, g_lb + start, g_ll - start); g_ll -= start; }
    else if (g_ll >= sizeof g_lb - 1) g_ll = 0;
}

int main(int argc, char **argv, char **envp) {
    (void)argc; (void)argv; (void)envp;
    g_fd = open("/dev/hwbinder", O_RDWR | O_CLOEXEC | 04000);
    if (g_fd < 0) { LOG(L_ERR, "cannot open /dev/hwbinder"); return 1; }
    i32 version[2] = { 0, 0 };
    if (ioctl(g_fd, (int)BINDER_VERSION, version) < 0 || version[0] != 8) LOG(L_WARN, "binder protocol version %d (expected 8)", version[0]);
    if (mmap(NULL, 1024 * 1024 - 8192, PROT_READ, MAP_PRIVATE | MAP_NORESERVE, g_fd, 0) == (void *)-1) { LOG(L_ERR, "cannot map the transaction buffer"); return 1; }
    u32 zero = 0;
    ioctl(g_fd, (int)BINDER_SET_MAX_THREADS, &zero);
    w_cmd(BC_ENTER_LOOPER, NULL, 0);
    rw(NULL, 0, NULL);
    cfg_load();
    if (!register_retry(NODE_RADIO, "slot1", 240)) { LOG(L_ERR, "android.hardware.radio@1.0::IRadio/slot1 was not accepted by hwservicemanager"); return 1; }
    LOG(L_INFO, "registered %s/slot1", D_RADIO);
    if (cfg_int("secbridge")) {
        if (register_retry(NODE_SEC1, "slot1", 10)) LOG(L_INFO, "registered %s/slot1", D_SEC);
        if (register_retry(NODE_SEC2, "slot2", 10)) LOG(L_INFO, "registered %s/slot2", D_SEC);
    }
    for (;;) {
        u8 rb[8192] __attribute__((aligned(8)));
        struct pfd f[2];
        rw(NULL, 0, NULL);
        g_ap = 0;
        memset(f, 0, sizeof f);
        f[0].fd = g_fd; f[0].events = 1;
        f[1].fd = g_cs; f[1].events = 1;
        int nf = g_cs >= 0 ? 2 : 1;
        int pr = poll(f, (unsigned long)nf, g_cs >= 0 ? -1 : 1000);
        if (pr < 0) { usleep(50000); continue; }
        if (g_cs < 0) cs_connect();
        if (f[0].revents & 1) {
            size_t n = 0;
            if (rw(rb, sizeof rb, &n) < 0) continue;
            process_br(rb, n, NULL);
            rw(NULL, 0, NULL);
            g_ap = 0;
        }
        if (f[1].revents & 25) cs_read();
    }
}

typedef void init_func_t(int, char *[], char *[]);
typedef void fini_func_t(void);
__attribute__((section(".preinit_array"), used)) init_func_t *__PREINIT_ARRAY__[2] = { (init_func_t *)-1, 0 };
__attribute__((section(".init_array.0"), used)) init_func_t *__INIT_ARRAY__[2] = { (init_func_t *)-1, 0 };
__attribute__((section(".fini_array.0"), used)) fini_func_t *__FINI_ARRAY__[2] = { (fini_func_t *)-1, 0 };
__attribute__((used)) void *__dso_handle = 0;
__attribute__((used)) static void _start_main(void *raw_args) {
    struct { init_func_t **preinit_array; init_func_t **init_array; fini_func_t **fini_array; } array;
    array.preinit_array = __PREINIT_ARRAY__;
    array.init_array = __INIT_ARRAY__;
    array.fini_array = __FINI_ARRAY__;
    __libc_init(raw_args, 0, main, &array);
}
__asm__(".text; .global _start; .type _start,%function; _start:"
        "bti j; mov x29,#0; mov x30,#0; mov x0,sp; b _start_main; .size _start, .-_start");
