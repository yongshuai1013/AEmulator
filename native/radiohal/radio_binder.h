typedef __SIZE_TYPE__ size_t;
typedef long ssize_t;
#define NULL ((void *)0)
extern void *memcpy(void *, const void *, size_t);
extern void *memset(void *, int, size_t);
extern size_t strlen(const char *);
extern int strncmp(const char *, const char *, size_t);
extern int open(const char *, int, ...);
extern int close(int);
extern ssize_t read(int, void *, size_t);
extern int ioctl(int, int, ...);
extern void *mmap(void *, size_t, int, int, int, long);
extern int usleep(unsigned);
extern int poll(void *, unsigned long, int);
extern int socket(int, int, int);
extern int connect(int, const void *, unsigned);
extern int __android_log_print(int, const char *, const char *, ...);
__attribute__((noreturn)) extern void _exit(int);
__attribute__((noreturn)) extern void __libc_init(void *raw_args, void (*onexit)(void), int (*slingshot)(int, char **, char **), const void *structors);
#define O_RDWR 2
#define O_RDONLY 0
#define O_CLOEXEC 02000000
#define PROT_READ 1
#define MAP_PRIVATE 2
#define MAP_NORESERVE 0x4000
#define LOG(prio, ...) __android_log_print(prio, "aemu-radio", __VA_ARGS__)
#define L_INFO 4
#define L_WARN 5
#define L_ERR 6

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef int i32;
typedef unsigned long long u64;
typedef __UINTPTR_TYPE__ uptr;

#define BINDER_WRITE_READ 0xc0306201u
#define BINDER_SET_MAX_THREADS 0x40046205u
#define BINDER_VERSION 0xc0046209u

#define BC_TRANSACTION 0x40406300u
#define BC_REPLY 0x40406301u
#define BC_TRANSACTION_SG 0x40486311u
#define BC_REPLY_SG 0x40486312u
#define BC_FREE_BUFFER 0x40086303u
#define BC_INCREFS 0x40046304u
#define BC_ACQUIRE 0x40046305u
#define BC_RELEASE 0x40046306u
#define BC_DECREFS 0x40046307u
#define BC_INCREFS_DONE 0x40106308u
#define BC_ACQUIRE_DONE 0x40106309u
#define BC_ENTER_LOOPER 0x0000630cu
#define BC_REQUEST_DEATH_NOTIFICATION 0x400c630eu
#define BC_CLEAR_DEATH_NOTIFICATION 0x400c630fu
#define BC_DEAD_BINDER_DONE 0x40086310u

#define BR_TRANSACTION 0x80407202u
#define BR_TRANSACTION_SEC_CTX 0x80487202u
#define BR_REPLY 0x80407203u
#define BR_DEAD_REPLY 0x00007205u
#define BR_INCREFS 0x80107207u
#define BR_ACQUIRE 0x80107208u
#define BR_DEAD_BINDER 0x8008720fu
#define BR_FAILED_REPLY 0x00007211u

#define T_BINDER 0x73622a85u
#define T_HANDLE 0x73682a85u
#define T_WEAK_HANDLE 0x77682a85u
#define T_PTR 0x70742a85u
#define BUF_HAS_PARENT 1u
#define TF_ONE_WAY 1u
#define TF_STATUS_CODE 8u
#define UNKNOWN_TRANSACTION (-74)

struct bwr { u64 write_size, write_consumed, write_buffer, read_size, read_consumed, read_buffer; };
struct btd { u64 target, cookie; u32 code, flags; i32 sender_pid; u32 sender_euid; u64 data_size, offsets_size, buffer, offsets; };
struct btd_sg { struct btd tr; u64 buffers_size; };
struct fbo { u32 type, flags; u64 binder, cookie; };
struct bbo { u32 type, flags; u64 buffer, length, parent, parent_offset; };
struct hstr { u64 ptr; u32 size; u8 owns, pad[3]; };

#define A8(x) (((x) + 7) & ~7ULL)

#define HIDL_PACK(a, b, c) ((0x0fu << 24) | ((u32)(a) << 16) | ((u32)(b) << 8) | (u32)(c))
#define IB_PING HIDL_PACK('P', 'N', 'G')
#define IB_CHAIN HIDL_PACK('C', 'H', 'N')
#define IB_DESCRIPTOR HIDL_PACK('D', 'S', 'C')
#define IB_DEBUG HIDL_PACK('D', 'B', 'G')
#define IB_HASH_CHAIN HIDL_PACK('H', 'S', 'H')
#define IB_SYSPROPS HIDL_PACK('S', 'Y', 'S')
#define IB_INSTRUMENTATION HIDL_PACK('I', 'N', 'T')
#define SM_ADD 2

static int g_fd = -1;

#define ARENA (4u << 20)
static u8 g_arena[ARENA] __attribute__((aligned(16)));
static size_t g_ap;
static void *aalloc(size_t n) {
    n = (n + 15) & ~(size_t)15;
    if (g_ap + n > ARENA) { LOG(L_ERR, "reply arena exhausted"); _exit(1); }
    void *p = g_arena + g_ap;
    g_ap += n;
    memset(p, 0, n);
    return p;
}

#define WBUF 65536
static u8 g_w[WBUF] __attribute__((aligned(8)));
static size_t g_wl;
static int rw(u8 *rb, size_t rbsz, size_t *got);
static void w_cmd(u32 cmd, const void *payload, size_t n) {
    if (g_wl + 4 + n > WBUF) rw(0, 0, 0);
    memcpy(g_w + g_wl, &cmd, 4); g_wl += 4;
    if (n) { memcpy(g_w + g_wl, payload, n); g_wl += n; }
}
static void w_u32(u32 cmd, u32 v) { w_cmd(cmd, &v, 4); }
static void w_hc(u32 cmd, u32 handle, u64 cookie) { u8 b[12]; memcpy(b, &handle, 4); memcpy(b + 4, &cookie, 8); w_cmd(cmd, b, 12); }

static int rw(u8 *rb, size_t rbsz, size_t *got) {
    struct bwr b;
    memset(&b, 0, sizeof b);
    b.write_size = g_wl; b.write_buffer = (u64)(uptr)g_w;
    b.read_size = rb ? rbsz : 0; b.read_buffer = (u64)(uptr)rb;
    int r = ioctl(g_fd, (int)BINDER_WRITE_READ, &b);
    g_wl = 0;
    if (r < 0) return -1;
    if (got) *got = (size_t)b.read_consumed;
    return 0;
}

struct pb { u8 *d; size_t dl, cap; u64 *o; size_t no, ocap; u64 sg; };

static void pb_init(struct pb *p, size_t dcap, size_t ocap) {
    p->d = aalloc(dcap); p->cap = dcap; p->dl = 0;
    p->o = aalloc(ocap * 8 + 8); p->ocap = ocap; p->no = 0; p->sg = 0;
}
static void pb_raw(struct pb *p, const void *s, size_t n) {
    if (p->dl + n > p->cap) { LOG(L_ERR, "parcel overflow"); _exit(1); }
    memcpy(p->d + p->dl, s, n); p->dl += n;
}
static void pb_i32(struct pb *p, i32 v) { pb_raw(p, &v, 4); }
static void pb_cstr(struct pb *p, const char *s) {
    size_t n = strlen(s) + 1, padded = (n + 3) & ~(size_t)3;
    static const u8 zero[4];
    pb_raw(p, s, n);
    if (padded > n) pb_raw(p, zero, padded - n);
}
static int pb_object(struct pb *p, const void *obj, size_t n) {
    if (p->no >= p->ocap) { LOG(L_ERR, "parcel: too many objects"); _exit(1); }
    p->o[p->no] = p->dl;
    pb_raw(p, obj, n);
    return (int)p->no++;
}
static int pb_ptr(struct pb *p, const void *buf, u64 len, int parent, u64 parent_offset) {
    struct bbo o;
    memset(&o, 0, sizeof o);
    o.type = T_PTR; o.buffer = (u64)(uptr)buf; o.length = len;
    if (parent >= 0) { o.flags = BUF_HAS_PARENT; o.parent = (u64)parent; o.parent_offset = parent_offset; }
    p->sg += A8(len);
    return pb_object(p, &o, sizeof o);
}
static void pb_node(struct pb *p, void *node) {
    struct fbo f;
    memset(&f, 0, sizeof f);
    f.type = T_BINDER; f.flags = 0x7f | 0x100;
    f.binder = (u64)(uptr)node; f.cookie = (u64)(uptr)node;
    pb_object(p, &f, sizeof f);
}
static void pb_string(struct pb *p, const char *s) {
    size_t n = strlen(s);
    char *chars = aalloc(n + 1);
    memcpy(chars, s, n);
    struct hstr *h = aalloc(sizeof *h);
    h->ptr = (u64)(uptr)chars; h->size = (u32)n;
    int parent = pb_ptr(p, h, sizeof *h, -1, 0);
    pb_ptr(p, chars, n + 1, parent, 0);
}
static void pb_string_vec(struct pb *p, const char *const *v, u32 n) {
    struct hstr *vec = aalloc(sizeof *vec);
    struct hstr *arr = aalloc((size_t)n * sizeof *arr + 16);
    vec->ptr = (u64)(uptr)arr; vec->size = n;
    int pv = pb_ptr(p, vec, sizeof *vec, -1, 0);
    int pa = pb_ptr(p, arr, (u64)n * sizeof *arr, pv, 0);
    for (u32 i = 0; i < n; i++) {
        size_t l = strlen(v[i]);
        char *chars = aalloc(l + 1);
        memcpy(chars, v[i], l);
        arr[i].ptr = (u64)(uptr)chars; arr[i].size = (u32)l;
        pb_ptr(p, chars, l + 1, pa, (u64)i * sizeof *arr);
    }
}
static void pb_empty_vec(struct pb *p) { pb_string_vec(p, NULL, 0); }

static void send_txn(u32 cmd, u32 handle, u32 code, u32 flags, struct pb *p) {
    struct btd_sg t;
    memset(&t, 0, sizeof t);
    t.tr.target = handle; t.tr.code = code; t.tr.flags = flags;
    t.tr.data_size = p->dl; t.tr.offsets_size = p->no * 8;
    t.tr.buffer = (u64)(uptr)p->d; t.tr.offsets = (u64)(uptr)p->o;
    if (p->sg) { t.buffers_size = p->sg; w_cmd(cmd == BC_REPLY ? BC_REPLY_SG : BC_TRANSACTION_SG, &t, sizeof t); }
    else w_cmd(cmd, &t, sizeof t.tr);
}
static void reply_status(int status) {
    i32 *v = aalloc(8);
    *v = status;
    struct btd_sg t;
    memset(&t, 0, sizeof t);
    t.tr.flags = TF_STATUS_CODE; t.tr.data_size = 4; t.tr.buffer = (u64)(uptr)v; t.tr.offsets = (u64)(uptr)v;
    w_cmd(BC_REPLY, &t, sizeof t.tr);
}
static void reply_ok_empty(void) {
    struct pb p;
    pb_init(&p, 16, 1);
    pb_i32(&p, 0);
    send_txn(BC_REPLY, 0, 0, 0, &p);
}

#define MAXOBJ 24
struct obj { u32 type; const struct bbo *b; const struct fbo *f; };
struct req { u32 n; struct obj o[MAXOBJ]; };

static int req_parse(const struct btd *tr, struct req *r) {
    r->n = 0;
    const u8 *data = (const u8 *)(uptr)tr->buffer;
    const u64 *offs = (const u64 *)(uptr)tr->offsets;
    u32 no = (u32)(tr->offsets_size / 8);
    if (no > MAXOBJ) return -1;
    u64 sg = 0;
    for (u32 i = 0; i < no; i++) {
        u64 off = offs[i];
        if (off + 4 > tr->data_size) return -1;
        u32 type;
        memcpy(&type, data + off, 4);
        r->o[i].type = type; r->o[i].b = NULL; r->o[i].f = NULL;
        if (type == T_PTR) {
            if (off + sizeof(struct bbo) > tr->data_size) return -1;
            r->o[i].b = (const struct bbo *)(data + off);
            sg += A8(r->o[i].b->length);
        } else if (type == T_HANDLE || type == T_WEAK_HANDLE || type == T_BINDER) {
            if (off + sizeof(struct fbo) > tr->data_size) return -1;
            r->o[i].f = (const struct fbo *)(data + off);
        } else return -1;
    }
    r->n = no;
    u64 lo = tr->buffer, hi = tr->buffer + A8(tr->data_size) + tr->offsets_size + sg;
    for (u32 i = 0; i < no; i++) {
        const struct bbo *b = r->o[i].b;
        if (b && b->length && (b->buffer < lo || b->buffer + b->length > hi || b->buffer + b->length < b->buffer)) return -1;
    }
    return 0;
}
