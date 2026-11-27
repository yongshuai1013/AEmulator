/*
 * libGLES_aemu.so - the EGL/GLES driver of the guest that draws on the phone's GPU.
 *
 * A program's GLES 2/3 context becomes a context of the host application (host.c): every GL call is written into
 * a command stream, one unix socket per drawing thread, and played there by a thread that has the real context
 * current. eglSwapBuffers brings the finished frame back into the window's buffer, which goes on to the guest's
 * compositor as before.
 *
 * Everything else stays on SwiftShader, which this driver loads and stands in front of: EGL displays and configs
 * are SwiftShader's own, GLES 1 contexts, the compositor's and the window toolkit's contexts are created there and
 * their calls are passed straight through. Which side a context goes to is decided when it is created (policy()).
 * Each thread has a table of entry points: SwiftShader's GLES 2 or GLES 1 library, or the encoders.
 *
 * Without a SwiftShader (the x86 firmwares: "solo") every context is the host's. The host has GLES 3 contexts only, so a GLES 1
 * context (the boot animation, old games and wallpapers) gets the fixed-function pipeline from gles1.h, which writes matrices, lighting,
 * fog, texture environments, glDrawTex* and the rest in GLSL over the same encoders. What the host can do beyond protocol 1 (a frame
 * converted into the window buffer's layout, window buffers as textures) it announces after OP_HELLO (AEMU_CAP_*); against a host that
 * does not, the guest does that work itself, as it did before.
 *
 * Built for every bionic from 5.0 on: libc, libdl and liblog only; libnativewindow is looked up at run time.
 */
#include <dlfcn.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
/* (the libc of 1.x may lack poll, prctl and getprogname. They are not imported, not even weak: the linker of 1.x gives an unresolved weak
 * import the library's own base address, not 0, so a "if (prctl)" test passed and the call ran the ELF header - SIGILL in SurfaceFlinger.
 * They are looked up in libc at run time instead: dlsym on a handle answers 0 for a missing name, on every release - see libc_sym.) */
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include "common.h"
#include "../grallocwrap/note.h"

extern int __android_log_print(int prio, const char *tag, const char *fmt, ...);
extern int __system_property_get(const char *name, char *value);
#define LOG(...) __android_log_print(4, "aemu-gl", __VA_ARGS__)
#define EXPORT __attribute__((visibility("default")))
#define BLOB(n) ((n) == NIL ? 0u : (((n) + 3u) & ~3u))

struct ts;
static __thread void *const *t_tbl;
static __thread struct ts *t_ts;
static void *const *default_tbl(void);
#define TBL() (__builtin_expect(t_tbl != 0, 1) ? t_tbl : default_tbl())
#define TS() (t_ts)

/* ---- what the driver remembers of a bridged context's GL state ---- */
struct attr {
    int on, size, type, norm, integer, stride, div; unsigned buf; const void *ptr;
    const void *sent; uint32_t sent_n; uint64_t sent_sum;     /* what the host's copy of this array holds */
};
struct map { unsigned target, access; long offset, length; void *ptr; };
struct gstate {
    unsigned array, elem, vao, pack_buf, unpack_buf;
    int p_align, p_row, p_skip_rows, p_skip_pix;                     /* GL_PACK_* */
    int u_align, u_row, u_skip_rows, u_skip_pix, u_img_h, u_skip_img;  /* GL_UNPACK_* */
    struct attr attr[16];
    struct map maps[8];
    char *str[5];
    char **ext; int next;
    uint32_t count;         /* how many values the glGet* being sent gives */
    unsigned elem_vao; int elem_vao_known;          /* the element buffer of the bound vertex array, when it was bound here */
    struct { unsigned target, name; } bind[12];     /* the other targets' buffers */
    uint32_t root;          /* the host's id of the first context of the share group (buffers are the group's) */
    /* What the host's context has, so that only changes are sent: a program that draws thousands of small batches
     * (Skia) binds its buffers, sets its arrays up and enables and disables them around every draw - all of it
     * the same as the last time. Enabling the arrays of vertex array 0 waits for the next draw (host_on). */
    uint8_t host_on[16];
    unsigned host_array, host_elem; int host_elem_ok;
    struct hptr { int ok, size, type, norm, integer, stride; unsigned buf; const void *ptr; } hp[16];
    /* GL_TEXTURE_2D bound to each unit, and the textures that are an EGLImage of a window buffer (see tex_mirror) */
    unsigned unit, tex2d[32];
    struct { unsigned tex; void *img; } timg[16];
};

/* ---- the stream ---- */
struct ectx;
struct esurf;
struct ts {
    int fd, dead;
    uint32_t caps;          /* AEMU_CAP_* of the host (asked once after OP_HELLO) */
    uint8_t *buf;
    uint32_t n, cap;
    struct ectx *ctx;
    struct esurf *draw, *read;
};

static void wr_all(struct ts *T, const void *p, size_t n) {
    const char *c = p;
    while (n && !T->dead) {
        ssize_t k = send(T->fd, c, n, MSG_NOSIGNAL);
        if (k <= 0) { if (k < 0 && errno == EINTR) continue; T->dead = 1; LOG("the host is gone (write, errno %d)", errno); return; }
        c += k; n -= (size_t)k;
    }
}
static void rd_all(struct ts *T, void *p, size_t n) {
    char *c = p;
    while (n && !T->dead) {
        ssize_t k = read(T->fd, c, n);
        if (k <= 0) { if (k < 0 && errno == EINTR) continue; T->dead = 1; LOG("the host is gone (read, errno %d)", errno); break; }
        c += k; n -= (size_t)k;
    }
    if (n) memset(c, 0, n);
}
static void flush(struct ts *T) { if (T->n) { wr_all(T, T->buf, T->n); T->n = 0; } }
static inline void room(struct ts *T, uint32_t n) { if (T->n + n > T->cap) flush(T); }
static inline void put32(struct ts *T, uint32_t v) { room(T, 4); memcpy(T->buf + T->n, &v, 4); T->n += 4; }
static inline void putf(struct ts *T, float v) { room(T, 4); memcpy(T->buf + T->n, &v, 4); T->n += 4; }
static inline void put64(struct ts *T, uint64_t v) { room(T, 8); memcpy(T->buf + T->n, &v, 8); T->n += 8; }
static inline void begin(struct ts *T, uint32_t op, uint32_t fixed, uint32_t total) {
    room(T, 8 + fixed);
    put32(T, op); put32(T, total);
}
static void putraw(struct ts *T, const void *p, uint32_t n) {
    static const char zero[4];
    uint32_t pad = (4 - (n & 3)) & 3;
    if (n + pad <= T->cap - T->n || n + pad <= T->cap / 4) {
        room(T, n + pad);
        memcpy(T->buf + T->n, p, n); memset(T->buf + T->n + n, 0, pad); T->n += n + pad;
    } else { flush(T); wr_all(T, p, n); wr_all(T, zero, pad); }
}
static void putblob(struct ts *T, const void *p, uint32_t n) {
    put32(T, n);
    if (n != NIL) putraw(T, p, n);
}
static inline void rsync(struct ts *T) { flush(T); }
static void get(struct ts *T, void *p, uint32_t n) {
    if (p) { rd_all(T, p, n); return; }
    char t[256];
    while (n) { uint32_t k = n < sizeof(t) ? n : sizeof(t); rd_all(T, t, k); n -= k; }
}
static inline uint32_t get32v(struct ts *T) { uint32_t v = 0; rd_all(T, &v, 4); return v; }
static inline uint64_t get64v(struct ts *T) { uint64_t v = 0; rd_all(T, &v, 8); return v; }

struct g1;                  /* the fixed-function state of a GLES 1 context (gles1.h) */
struct ectx {
    int bridged, version, current;
    void *ss;               /* SwiftShader's context */
    uint32_t host;          /* the host's context */
    void *dpy, *cfg;
    struct gstate g;
    struct g1 *g1;          /* GLES 1 contexts only: made when first used */
};
static struct gstate *G(void) { return &t_ts->ctx->g; }
#define aemu_get_count(pname) (G()->count)

#include "g_api.h"

/* ---- copies of index buffers ----
 * A draw with indices in a buffer and vertices in the program's memory needs the largest index to know how many
 * vertices go along. Reading the buffer back from the host makes the phone's GPU finish everything queued (Skia's
 * particles: a stall per draw, 4 frames a second), so the indices written while a buffer is bound as
 * GL_ELEMENT_ARRAY_BUFFER are kept here as well. A copy is dropped as soon as the buffer may change some other way
 * (a write through a target whose buffer is not known here, copies, transform feedback, compute). */
struct shadow { uint32_t root; unsigned name; long size; uint8_t *data; long m_off, m_max; int m_count; uint32_t m_isz; };   /* m_*: the last answer of sh_max */
static struct shadow g_sh[64];
static pthread_mutex_t g_sh_lock = PTHREAD_MUTEX_INITIALIZER;
#define SHADOW_MAX (4L << 20)
static struct shadow *sh_find(uint32_t root, unsigned name) {
    for (int i = 0; name && i < 64; i++) if (g_sh[i].data && g_sh[i].root == root && g_sh[i].name == name) return &g_sh[i];
    return 0;
}
static void sh_drop(uint32_t root, unsigned name) {
    pthread_mutex_lock(&g_sh_lock);
    struct shadow *x = sh_find(root, name);
    if (x) { free(x->data); memset(x, 0, sizeof(*x)); }
    pthread_mutex_unlock(&g_sh_lock);
}
static void sh_drop_all(uint32_t root) {
    pthread_mutex_lock(&g_sh_lock);
    for (int i = 0; i < 64; i++) if (g_sh[i].data && g_sh[i].root == root) { free(g_sh[i].data); memset(&g_sh[i], 0, sizeof(g_sh[i])); }
    pthread_mutex_unlock(&g_sh_lock);
}
/* the buffer bound to a target; *known 0: not known here */
static unsigned bound_to(struct gstate *g, GLenum target, int *known) {
    *known = 1;
    if (target == 0x8892) return g->array;
    if (target == 0x8893) { if (!g->vao) return g->elem; *known = g->elem_vao_known; return g->elem_vao; }
    for (int i = 0; i < 12; i++) if (g->bind[i].target == target) return g->bind[i].name;
    return 0;
}
static void sh_write(GLenum target, long offset, const void *p, long size, int whole) {
    struct gstate *g = G();
    int known;
    unsigned name = bound_to(g, target, &known);
    if (!known) { sh_drop_all(g->root); return; }
    if (!name) return;
    pthread_mutex_lock(&g_sh_lock);
    struct shadow *x = sh_find(g->root, name);
    if (whole) {
        if (x) { free(x->data); memset(x, 0, sizeof(*x)); }
        if (target == 0x8893 && size > 0 && size <= SHADOW_MAX) {
            for (int i = 0; i < 64 && !x; i++) if (!g_sh[i].data) x = &g_sh[i];
            if (!x) { x = &g_sh[(name * 7u) & 63]; free(x->data); }
            x->data = p ? malloc((size_t)size) : calloc(1, (size_t)size);
            if (x->data) { if (p) memcpy(x->data, p, (size_t)size); x->root = g->root; x->name = name; x->size = size; x->m_count = 0; }
            else memset(x, 0, sizeof(*x));
        }
    } else if (x) {
        if (p && offset >= 0 && size >= 0 && offset + size <= x->size) { memcpy(x->data + offset, p, (size_t)size); x->m_count = 0; }
        else { free(x->data); memset(x, 0, sizeof(*x)); }
    }
    pthread_mutex_unlock(&g_sh_lock);
}
/* the largest of count indices at offset in the bound element buffer, from the copy; -1: no copy */
static long sh_max(struct gstate *g, long offset, int count, uint32_t isz) {
    long r = -1;
    pthread_mutex_lock(&g_sh_lock);
    struct shadow *x = sh_find(g->root, g->elem);
    if (x && offset >= 0 && offset + (long)count * isz <= x->size) {
        if (x->m_count == count && x->m_off == offset && x->m_isz == isz) r = x->m_max;      /* (the same quads again and again: text, Skia) */
        else {
            const uint8_t *ix = x->data + offset;
            uint32_t max = 0;
            for (int i = 0; i < count; i++) {
                uint32_t v = isz == 1 ? ix[i] : isz == 2 ? ((const uint16_t *)ix)[i] : ((const uint32_t *)ix)[i];
                if (v > max) max = v;
            }
            r = max;
            x->m_count = count; x->m_off = offset; x->m_isz = isz; x->m_max = r;
        }
    }
    pthread_mutex_unlock(&g_sh_lock);
    return r;
}
static void k_glBufferData(GLenum target, GLsizeiptr size, const void *data, GLenum usage) { (void)usage; sh_write(target, 0, data, size, 1); }
static void k_glBufferSubData(GLenum target, GLintptr offset, GLsizeiptr size, const void *data) { sh_write(target, offset, data, size, 0); }
static void k_glCopyBufferSubData(GLenum r, GLenum w, GLintptr ro, GLintptr wo, GLsizeiptr size) {
    struct gstate *g = G();
    int known;
    unsigned name = bound_to(g, w, &known);
    (void)r; (void)ro; (void)wo; (void)size;
    if (!known) sh_drop_all(g->root); else sh_drop(g->root, name);
}
static void bind_note(GLenum target, GLuint b) {
    struct gstate *g = G();
    int spare = -1;
    for (int i = 0; i < 12; i++) {
        if (g->bind[i].target == target) { g->bind[i].name = b; return; }
        if (!g->bind[i].target && spare < 0) spare = i;
    }
    if (spare >= 0) { g->bind[spare].target = target; g->bind[spare].name = b; }
}
static void k_glBindBufferBase(GLenum target, GLuint i, GLuint b) { (void)i; bind_note(target, b); }
static void k_glBindBufferRange(GLenum target, GLuint i, GLuint b, GLintptr o, GLsizeiptr n) { (void)i; (void)o; (void)n; bind_note(target, b); }
/* (the GPU may write buffers from here on) */
static void k_glBeginTransformFeedback(GLenum mode) { (void)mode; sh_drop_all(G()->root); }
static void k_glDispatchCompute(GLuint x, GLuint y, GLuint z) { (void)x; (void)y; (void)z; sh_drop_all(G()->root); }
static void k_glDispatchComputeIndirect(GLintptr o) { (void)o; sh_drop_all(G()->root); }
static void k_glMemoryBarrier(GLbitfield b) { (void)b; sh_drop_all(G()->root); }
static void k_glMemoryBarrierByRegion(GLbitfield b) { (void)b; sh_drop_all(G()->root); }

/* ---- notes taken before a call is sent ---- */
static void k_glBindBuffer(GLenum target, GLuint b) {
    struct gstate *g = G();
    switch (target) {
    case 0x8892: g->array = b; break;
    case 0x8893: if (!g->vao) g->elem = b; break;
    case 0x88EB: g->pack_buf = b; break;
    case 0x88EC: g->unpack_buf = b; break;
    }
    if (target == 0x8893 && g->vao) { g->elem_vao = b; g->elem_vao_known = 1; }
    if (target != 0x8892 && target != 0x8893) bind_note(target, b);
}
static void k_glPixelStorei(GLenum pname, GLint v) {
    struct gstate *g = G();
    switch (pname) {
    case 0x0D05: g->p_align = v; break;
    case 0x0D02: g->p_row = v; break;
    case 0x0D03: g->p_skip_rows = v; break;
    case 0x0D04: g->p_skip_pix = v; break;
    case 0x0CF5: g->u_align = v; break;
    case 0x0CF2: g->u_row = v; break;
    case 0x0CF3: g->u_skip_rows = v; break;
    case 0x0CF4: g->u_skip_pix = v; break;
    case 0x806E: g->u_img_h = v; break;
    case 0x806D: g->u_skip_img = v; break;
    }
}
static void k_glBindVertexArray(GLuint a) { struct gstate *g = G(); g->vao = a; g->elem_vao_known = 0; g->host_elem_ok = 0; }
static void k_glEnableVertexAttribArray(GLuint i) { struct gstate *g = G(); if (!g->vao && i < 16) g->attr[i].on = 1; }
static void k_glDisableVertexAttribArray(GLuint i) { struct gstate *g = G(); if (!g->vao && i < 16) g->attr[i].on = 0; }
static void k_glVertexAttribDivisor(GLuint i, GLuint d) { struct gstate *g = G(); if (!g->vao && i < 16) g->attr[i].div = (int)d; }
static void k_glDeleteBuffers(GLsizei n, const GLuint *b) {
    struct gstate *g = G();
    for (int i = 0; b && i < n; i++) {
        if (!b[i]) continue;
        sh_drop(g->root, b[i]);
        if (g->host_array == b[i]) g->host_array = 0;
        if (g->host_elem == b[i]) g->host_elem_ok = 0;
        for (int k = 0; k < 16; k++) if (g->hp[k].buf == b[i]) g->hp[k].ok = 0;
        if (g->array == b[i]) g->array = 0;
        if (g->elem == b[i]) g->elem = 0;
        if (g->pack_buf == b[i]) g->pack_buf = 0;
        if (g->unpack_buf == b[i]) g->unpack_buf = 0;
    }
}
static void k_glDeleteVertexArrays(GLsizei n, const GLuint *a) {
    struct gstate *g = G();
    for (int i = 0; a && i < n; i++) if (a[i] && g->vao == a[i]) g->vao = 0;
}

/* ---- textures that are an image of a buffer ----
 * A buffer of a gralloc that leaves no note (the engine's 4.4 one) is carried to the host as a snapshot of the guest's memory
 * when a texture is bound to its image. A program that writes such a texture with the GPU (AssetAtlasService: glTexSubImage2D
 * into the atlas image, in system_server) wrote the host's copy only, and every other process then bound memory that was never
 * written: hwui drew all framework nine-patches and icons from an empty atlas. The pixels of such a write go into the buffer's
 * memory as well. */
static void k_glActiveTexture(GLenum texture) { struct gstate *g = G(); if (texture >= 0x84C0 && texture < 0x84C0 + 32) g->unit = texture - 0x84C0; }
static void k_glBindTexture(GLenum target, GLuint tex) { struct gstate *g = G(); if (target == 0x0DE1 && g->unit < 32) g->tex2d[g->unit] = tex; }
static void timg_set(struct gstate *g, unsigned tex, void *img) {
    int spare = -1;
    for (int i = 0; tex && i < 16; i++) {
        if (g->timg[i].tex == tex) { g->timg[i].img = img; if (!img) g->timg[i].tex = 0; return; }
        if (!g->timg[i].tex && spare < 0) spare = i;
    }
    if (img && spare >= 0) { g->timg[spare].tex = tex; g->timg[spare].img = img; }
}
static void k_glDeleteTextures(GLsizei n, const GLuint *t) {
    struct gstate *g = G();
    for (int i = 0; t && i < n; i++) {
        if (!t[i]) continue;
        timg_set(g, t[i], 0);
        for (int u = 0; u < 32; u++) if (g->tex2d[u] == t[i]) g->tex2d[u] = 0;
    }
}
static void k_count(GLenum pname) {
    uint32_t name = 0, c = aemu_get_count0(pname, &name);
    if (!c) { GLint v = 0; e_glGetIntegerv(name, &v); c = v > 0 ? (uint32_t)v : 0; }
    G()->count = c;
}
static void k_glGetBooleanv(GLenum pname, GLboolean *d) { (void)d; k_count(pname); }
static void k_glGetFloatv(GLenum pname, GLfloat *d) { (void)d; k_count(pname); }
static void k_glGetIntegerv(GLenum pname, GLint *d) { (void)d; k_count(pname); }
static void k_glGetInteger64v(GLenum pname, GLint64 *d) { (void)d; k_count(pname); }

/* ---- state sent only when it changed ---- */
static void e_glBindBuffer(GLenum target, GLuint b) {
    struct gstate *g = G();
    k_glBindBuffer(target, b);
    if (target == 0x8892) { if (g->host_array == b) return; g->host_array = b; }
    if (target == 0x8893) {
        if (g->vao) g->host_elem_ok = 0;
        else { if (g->host_elem_ok && g->host_elem == b) return; g->host_elem = b; g->host_elem_ok = 1; }
    }
    e0_glBindBuffer(target, b);
}
static void e_glEnableVertexAttribArray(GLuint i) { struct gstate *g = G(); k_glEnableVertexAttribArray(i); if (g->vao || i >= 16) e0_glEnableVertexAttribArray(i); }
static void e_glDisableVertexAttribArray(GLuint i) { struct gstate *g = G(); k_glDisableVertexAttribArray(i); if (g->vao || i >= 16) e0_glDisableVertexAttribArray(i); }
/* before a draw (or a question about the arrays): the arrays of vertex array 0 enabled as the program left them */
static void arrays_sync(void) {
    struct gstate *g = G();
    if (g->vao) return;
    for (int i = 0; i < 16; i++) if (g->host_on[i] != (uint8_t)g->attr[i].on) {
        g->host_on[i] = (uint8_t)g->attr[i].on;
        if (g->attr[i].on) e0_glEnableVertexAttribArray((GLuint)i); else e0_glDisableVertexAttribArray((GLuint)i);
    }
}
static void k_glDrawArraysIndirect(GLenum m, const void *o) { (void)m; (void)o; arrays_sync(); }
static void k_glDrawElementsIndirect(GLenum m, GLenum t, const void *o) { (void)m; (void)t; (void)o; arrays_sync(); }
static void k_glGetVertexAttribfv(GLuint i, GLenum n, GLfloat *v) { (void)i; (void)n; (void)v; arrays_sync(); }
static void k_glGetVertexAttribiv(GLuint i, GLenum n, GLint *v) { (void)i; (void)n; (void)v; arrays_sync(); }
static void k_glGetVertexAttribIiv(GLuint i, GLenum n, GLint *v) { (void)i; (void)n; (void)v; arrays_sync(); }
static void k_glGetVertexAttribIuiv(GLuint i, GLenum n, GLuint *v) { (void)i; (void)n; (void)v; arrays_sync(); }

/* ---- calls encoded by hand ---- */
static void e_glFlush(void) { struct ts *T = TS(); begin(T, IDX_glFlush, 0, 0); flush(T); }
static void e_glFinish(void) { struct ts *T = TS(); begin(T, IDX_glFinish, 0, 0); rsync(T); get32v(T); }

static void e_glVertexAttribPointer(GLuint index, GLint size, GLenum type, GLboolean norm, GLsizei stride, const void *ptr) {
    struct ts *T = TS();
    struct gstate *g = G();
    if (!g->vao && index < 16) {
        struct attr *a = &g->attr[index];
        a->size = size; a->type = (int)type; a->norm = norm; a->integer = 0; a->stride = stride; a->buf = g->array; a->ptr = ptr;
        if (!g->array) return;      /* memory of the program: goes with each draw */
        struct hptr *h = &g->hp[index];
        if (h->ok && h->buf == g->array && h->size == size && h->type == (int)type && h->norm == norm && !h->integer && h->stride == stride && h->ptr == ptr) return;
        h->ok = 1; h->buf = g->array; h->size = size; h->type = (int)type; h->norm = norm; h->integer = 0; h->stride = stride; h->ptr = ptr;
    } else if (index < 16) g->hp[index].ok = 0;
    begin(T, IDX_glVertexAttribPointer, 28, 28);
    put32(T, index); put32(T, (uint32_t)size); put32(T, type); put32(T, norm); put32(T, (uint32_t)stride); put64(T, (uint64_t)(uintptr_t)ptr);
}
static void e_glVertexAttribIPointer(GLuint index, GLint size, GLenum type, GLsizei stride, const void *ptr) {
    struct ts *T = TS();
    struct gstate *g = G();
    if (!g->vao && index < 16) {
        struct attr *a = &g->attr[index];
        a->size = size; a->type = (int)type; a->norm = 0; a->integer = 1; a->stride = stride; a->buf = g->array; a->ptr = ptr;
        if (!g->array) return;
        struct hptr *h = &g->hp[index];
        if (h->ok && h->buf == g->array && h->size == size && h->type == (int)type && h->integer && h->stride == stride && h->ptr == ptr) return;
        h->ok = 1; h->buf = g->array; h->size = size; h->type = (int)type; h->norm = 0; h->integer = 1; h->stride = stride; h->ptr = ptr;
    } else if (index < 16) g->hp[index].ok = 0;
    begin(T, IDX_glVertexAttribIPointer, 24, 24);
    put32(T, index); put32(T, (uint32_t)size); put32(T, type); put32(T, (uint32_t)stride); put64(T, (uint64_t)(uintptr_t)ptr);
}
static void e_glGetVertexAttribPointerv(GLuint index, GLenum pname, void **pointer) {
    struct gstate *g = G();
    (void)pname;
    if (pointer) *pointer = (!g->vao && index < 16) ? (void *)g->attr[index].ptr : 0;
}

static int type_size(int type) {
    switch (type) {
    case 0x1400: case 0x1401: return 1;
    case 0x1402: case 0x1403: case 0x140B: case 0x8D61: return 2;
    }
    return 4;
}
/* Hashing an array to find out whether the host still has it costs more than sending it when the guest's CPU is a translator (x86 firmware
 * runs in one: ~2 emulated instructions a byte against a copy the kernel makes at full speed), so there the arrays always go. */
#if defined(__i386__) || defined(__x86_64__)
#define ARRAY_DEDUP 0
#else
#define ARRAY_DEDUP 1
#endif
static uint64_t sum_of(const void *p, uint32_t n) {
    const uint8_t *c = p;
    uint64_t h = 0x9e3779b97f4a7c15ull ^ n, w;
    for (; n >= 8; n -= 8, c += 8) { memcpy(&w, c, 8); h = (h ^ w) * 0xff51afd7ed558ccdull; h ^= h >> 29; }
    for (w = 0; n; n--) w = w << 8 | c[n - 1];
    h = (h ^ w) * 0xc4ceb9fe1a85ec53ull;
    return h ^ h >> 32;
}
static int client_arrays(const struct gstate *g) {
    if (g->vao) return 0;
    for (int i = 0; i < 16; i++) if (g->attr[i].on && !g->attr[i].buf && g->attr[i].ptr) return 1;
    return 0;
}
static void map_read(struct ts *T, GLenum target, long offset, long length, void *to) {
    begin(T, OP_MAP_READ, 16, 16);
    put32(T, target); put64(T, (uint64_t)offset); put32(T, (uint32_t)length);
    rsync(T);
    get(T, to, (uint32_t)length);
}
/* A draw that takes vertices (and maybe indices) from the program's memory: they travel with it.
 * type 0: glDrawArrays. Sent as: mode, count, type, instanced, instances, the number of arrays; the indices (a block,
 * or NIL and their offset in the bound buffer); for each array index, size, type, normalized | integer << 1, stride,
 * divisor and its bytes from the first vertex used. The host draws from vertex 0. */
static void e_draw(GLenum mode, GLint first, GLsizei count, GLenum type, const void *indices, int instanced, GLsizei instances) {
    struct ts *T = TS();
    struct gstate *g = G();
    if (count <= 0) return;
    uint32_t isz = type ? (type == 0x1401 ? 1u : type == 0x1403 ? 2u : 4u) : 0, ibytes = isz * (uint32_t)count;
    void *fetched = 0;
    int vfirst = first, vcount = count;
    if (type) {
        const void *ix = indices;
        long known = g->elem ? sh_max(g, (long)(uintptr_t)indices, count, isz) : -1;
        if (g->elem && known < 0) {      /* indices in a buffer, vertices in memory: the range of vertices is in the buffer */
            ix = fetched = malloc(ibytes);
            if (!fetched) return;
            map_read(T, 0x8893, (long)(uintptr_t)indices, (long)ibytes, fetched);
        }
        uint32_t max = known >= 0 ? (uint32_t)known : 0;
        for (int i = 0; known < 0 && i < count; i++) {
            uint32_t v = isz == 1 ? ((const uint8_t *)ix)[i] : isz == 2 ? ((const uint16_t *)ix)[i] : ((const uint32_t *)ix)[i];
            if (v > max) max = v;
        }
        vfirst = 0; vcount = (int)max + 1;
    }
    uint32_t len[16], nattr = 0, total = 24 + (type && !g->elem ? 4 + BLOB(ibytes) : 12);
    uint64_t sum[16];
    int same[16];
    for (int i = 0; i < 16; i++) {
        struct attr *a = &g->attr[i];
        len[i] = 0;
        if (!a->on || a->buf || !a->ptr) continue;
        int esz = (a->type == 0x8D9F || a->type == 0x8368) ? 4 : a->size * type_size(a->type);
        int st = a->stride ? a->stride : esz;
        int n = a->div ? ((instanced && instances > 0 ? instances : 1) + a->div - 1) / a->div : vcount;
        len[i] = (uint32_t)((n - 1) * st + esz);
        /* the same bytes as the last time (a mesh drawn again and again): the host still has them */
        const void *from = (const char *)a->ptr + (a->div ? 0 : (long)vfirst * st);
        sum[i] = ARRAY_DEDUP ? sum_of(from, len[i]) : 0;
        same[i] = ARRAY_DEDUP && a->sent == from && a->sent_n == len[i] && a->sent_sum == sum[i];
        a->sent = from; a->sent_n = len[i]; a->sent_sum = sum[i];
        total += 28 + (same[i] ? 0 : BLOB(len[i]));
        nattr++;
    }
    arrays_sync();
    begin(T, OP_DRAW_CLIENT, 28, total);
    put32(T, mode); put32(T, (uint32_t)count); put32(T, type); put32(T, (uint32_t)instanced); put32(T, (uint32_t)instances);
    put32(T, nattr);
    if (type && !g->elem) putblob(T, indices, ibytes);
    else { put32(T, NIL); put64(T, (uint64_t)(uintptr_t)indices); }
    for (int i = 0; i < 16; i++) {
        struct attr *a = &g->attr[i];
        if (!len[i]) continue;
        int esz = (a->type == 0x8D9F || a->type == 0x8368) ? 4 : a->size * type_size(a->type);
        int st = a->stride ? a->stride : esz;
        g->hp[i].ok = 0;
        put32(T, (uint32_t)i); put32(T, (uint32_t)a->size); put32(T, (uint32_t)a->type);
        put32(T, (uint32_t)(a->norm | a->integer << 1)); put32(T, (uint32_t)st); put32(T, (uint32_t)a->div);
        if (same[i]) put32(T, NIL);
        else putblob(T, (const char *)a->ptr + (a->div ? 0 : (long)vfirst * st), len[i]);
    }
    free(fetched);
}
static void e_glDrawArrays(GLenum mode, GLint first, GLsizei count) {
    struct ts *T = TS();
    arrays_sync();
    if (client_arrays(G())) { e_draw(mode, first, count, 0, 0, 0, 0); return; }
    begin(T, IDX_glDrawArrays, 12, 12); put32(T, mode); put32(T, (uint32_t)first); put32(T, (uint32_t)count);
}
static void e_glDrawArraysInstanced(GLenum mode, GLint first, GLsizei count, GLsizei n) {
    struct ts *T = TS();
    arrays_sync();
    if (client_arrays(G())) { e_draw(mode, first, count, 0, 0, 1, n); return; }
    begin(T, IDX_glDrawArraysInstanced, 16, 16); put32(T, mode); put32(T, (uint32_t)first); put32(T, (uint32_t)count); put32(T, (uint32_t)n);
}
static void e_glDrawElements(GLenum mode, GLsizei count, GLenum type, const void *indices) {
    struct ts *T = TS();
    struct gstate *g = G();
    if (client_arrays(g) || (!g->vao && !g->elem)) { e_draw(mode, 0, count, type, indices, 0, 0); return; }
    arrays_sync();
    begin(T, IDX_glDrawElements, 20, 20); put32(T, mode); put32(T, (uint32_t)count); put32(T, type); put64(T, (uint64_t)(uintptr_t)indices);
}
static void e_glDrawElementsInstanced(GLenum mode, GLsizei count, GLenum type, const void *indices, GLsizei n) {
    struct ts *T = TS();
    struct gstate *g = G();
    if (client_arrays(g) || (!g->vao && !g->elem)) { e_draw(mode, 0, count, type, indices, 1, n); return; }
    arrays_sync();
    begin(T, IDX_glDrawElementsInstanced, 24, 24);
    put32(T, mode); put32(T, (uint32_t)count); put32(T, type); put64(T, (uint64_t)(uintptr_t)indices); put32(T, (uint32_t)n);
}
static void e_glDrawRangeElements(GLenum mode, GLuint start, GLuint end, GLsizei count, GLenum type, const void *indices) {
    (void)start; (void)end;
    e_glDrawElements(mode, count, type, indices);
}

static void e_glShaderSource(GLuint shader, GLsizei count, const GLchar *const *string, const GLint *length) {
    struct ts *T = TS();
    uint32_t total = 0;
    for (int i = 0; i < count; i++) if (string[i]) total += (length && length[i] >= 0) ? (uint32_t)length[i] : (uint32_t)strlen(string[i]);
    char *all = malloc(total + 1), *o = all;
    if (!all) return;
    for (int i = 0; i < count; i++) if (string[i]) {
        uint32_t n = (length && length[i] >= 0) ? (uint32_t)length[i] : (uint32_t)strlen(string[i]);
        memcpy(o, string[i], n); o += n;
    }
    begin(T, IDX_glShaderSource, 8, 8 + BLOB(total));
    put32(T, shader); putblob(T, all, total);
    free(all);
}

/* a list of names: one block, each name followed by its 0 */
static char *names_of(GLsizei count, const GLchar *const *names, uint32_t *n) {
    uint32_t total = 0;
    for (int i = 0; i < count; i++) total += (uint32_t)strlen(names[i] ? names[i] : "") + 1;
    char *all = malloc(total + 1), *o = all;
    if (!all) return 0;
    for (int i = 0; i < count; i++) { size_t k = strlen(names[i] ? names[i] : "") + 1; memcpy(o, names[i] ? names[i] : "", k); o += k; }
    *n = total;
    return all;
}
static void e_glTransformFeedbackVaryings(GLuint program, GLsizei count, const GLchar *const *varyings, GLenum mode) {
    struct ts *T = TS();
    uint32_t n = 0;
    char *all = count > 0 && varyings ? names_of(count, varyings, &n) : 0;
    begin(T, IDX_glTransformFeedbackVaryings, 16, 16 + BLOB(all ? n : NIL));
    put32(T, program); put32(T, (uint32_t)(all ? count : 0)); put32(T, mode); putblob(T, all, all ? n : NIL);
    free(all);
}
static void e_glGetUniformIndices(GLuint program, GLsizei count, const GLchar *const *names, GLuint *indices) {
    struct ts *T = TS();
    uint32_t n = 0;
    char *all = count > 0 && names ? names_of(count, names, &n) : 0;
    if (!all) return;
    begin(T, IDX_glGetUniformIndices, 12, 12 + BLOB(n));
    put32(T, program); put32(T, (uint32_t)count); putblob(T, all, n);
    free(all);
    rsync(T);
    get(T, indices, (uint32_t)count * 4);
}
static GLuint e_glCreateShaderProgramv(GLenum type, GLsizei count, const GLchar *const *strings) {
    struct ts *T = TS();
    uint32_t total = 0;
    for (int i = 0; strings && i < count; i++) if (strings[i]) total += (uint32_t)strlen(strings[i]);
    char *all = malloc(total + 1), *o = all;
    if (!all) return 0;
    for (int i = 0; strings && i < count; i++) if (strings[i]) { size_t k = strlen(strings[i]); memcpy(o, strings[i], k); o += k; }
    begin(T, IDX_glCreateShaderProgramv, 8, 8 + BLOB(total));
    put32(T, type); putblob(T, all, total);
    free(all);
    rsync(T);
    return get32v(T);
}

static char *host_string(struct ts *T, GLenum name, uint32_t index) {
    begin(T, OP_GET_STRING, 8, 8); put32(T, name); put32(T, index);
    rsync(T);
    uint32_t n = get32v(T);
    if (n == NIL) return 0;
    char *s = malloc(BLOB(n) + 1);
    if (!s) { get(T, 0, BLOB(n)); return 0; }
    get(T, s, BLOB(n)); s[n] = 0;
    return s;
}
/* What a program that asks for the strings before (or without) a working context gets, instead of a NULL it then hands to strncmp:
 * SPen (S Note, Quick Memo, Flash Annotate) compares GL_RENDERER with a name right after a failed eglCreateContext /
 * eglMakeCurrent and died in libc. Nothing is made up here: the strings are the ones the host answers (the VM's GPU identity
 * from the SoC / GPU spoofer when it names one, the phone's own otherwise; the host answers them without a context too), and
 * the last of those is kept for a host that cannot be asked at the moment. With neither, an empty string, which a comparison
 * survives. */
static char *g_seen[5];
static struct ts *ts_get(void);
static int str_index(GLenum name) { return name >= 0x1F00 && name <= 0x1F03 ? (int)name - 0x1F00 : name == 0x8B8C ? 4 : -1; }
static void str_seen(int i, const char *s) {
    if (i < 0 || i > 4 || !s || g_seen[i]) return;
    size_t n = strlen(s);
    char *c = malloc(n + 1);
    if (!c) return;
    memcpy(c, s, n + 1);
    if (!__sync_bool_compare_and_swap(&g_seen[i], (char *)0, c)) free(c);
}
static const GLubyte *str_fallback(GLenum name) {
    int i = str_index(name);
    if (i < 0) return 0;
    if (!g_seen[i]) {
        struct ts *T = ts_get();
        char *s = T ? host_string(T, name, NIL) : 0;
        if (s) { str_seen(i, s); free(s); }
    }
    return (const GLubyte *)(g_seen[i] ? g_seen[i] : "");
}
static const GLubyte *e_glGetString(GLenum name) {
    struct gstate *g = G();
    int i = str_index(name);
    if (i < 0) return 0;
    if (!g->str[i]) g->str[i] = host_string(TS(), name, NIL);
    if (!g->str[i]) return str_fallback(name);
    str_seen(i, g->str[i]);
    return (const GLubyte *)g->str[i];
}
static const GLubyte *e_glGetStringi(GLenum name, GLuint index) {
    struct gstate *g = G();
    if (name != 0x1F03) return 0;
    if (!g->ext) {
        GLint n = 0;
        e_glGetIntegerv(0x821D, &n);
        g->ext = calloc((size_t)(n > 0 ? n : 1), sizeof(char *));
        g->next = g->ext ? n : 0;
    }
    if ((int)index >= g->next) return 0;
    if (!g->ext[index]) g->ext[index] = host_string(TS(), name, index);
    return (const GLubyte *)g->ext[index];
}

/* bytes of w x h x d pixels of a format and type as the pixel store parameters lay them out */
static uint32_t pix_bytes(int w, int h, int d, GLenum format, GLenum type, int align, int row, int skip_rows, int skip_pix, int img_h, int skip_img) {
    int comps;
    switch (format) {
    case 0x1906: case 0x1909: case 0x1903: case 0x8D94: case 0x1902: case 0x1901: comps = 1; break;
    case 0x190A: case 0x8227: case 0x8228: case 0x84F9: comps = 2; break;
    case 0x1907: case 0x8D98: case 0x8C40: comps = 3; break;
    default: comps = 4;
    }
    int bpp;
    switch (type) {
    case 0x1400: case 0x1401: bpp = comps; break;
    case 0x8363: case 0x8033: case 0x8034: bpp = 2; break;
    case 0x1402: case 0x1403: case 0x140B: case 0x8D61: bpp = comps * 2; break;
    case 0x8368: case 0x8C3B: case 0x8C3E: case 0x84FA: bpp = 4; break;
    case 0x8DAD: bpp = 8; break;
    default: bpp = comps * 4;
    }
    if (w <= 0 || h <= 0 || d <= 0) return 0;
    if (align < 1) align = 4;
    long rowlen = row > 0 ? row : w;
    long stride = (rowlen * bpp + align - 1) / align * align;
    long ih = img_h > 0 ? img_h : h;
    long rows = (long)(d - 1 + skip_img) * ih + skip_rows + h;
    return (uint32_t)((rows - 1) * stride + (long)(skip_pix + w) * bpp);
}
static uint32_t unpack_bytes(int w, int h, int d, GLenum format, GLenum type) {
    struct gstate *g = G();
    return pix_bytes(w, h, d, format, type, g->u_align, g->u_row, g->u_skip_rows, g->u_skip_pix, g->u_img_h, g->u_skip_img);
}
/* a pointer to pixels: u32 kind - 0 none, 1 a block follows, 2 a u64 offset into the bound buffer follows */
static uint32_t pixels_len(const void *p, uint32_t n) { return 4 + (G()->unpack_buf ? 8 : p ? 4 + BLOB(n) : 0); }
static void put_pixels(struct ts *T, const void *p, uint32_t n) {
    if (G()->unpack_buf) { put32(T, 2); put64(T, (uint64_t)(uintptr_t)p); }
    else if (p) { put32(T, 1); putblob(T, p, n); }
    else put32(T, 0);
}
static void tex_mirror(GLint x, GLint y, GLsizei w, GLsizei h, GLenum format, GLenum type, const void *pixels);
static void e_glTexImage2D(GLenum target, GLint level, GLint ifmt, GLsizei w, GLsizei h, GLint border, GLenum format, GLenum type, const void *pixels) {
    struct ts *T = TS();
    if (target == 0x0DE1 && level == 0 && G()->unit < 32) timg_set(G(), G()->tex2d[G()->unit], 0);      /* (re-specified: no longer the image's) */
    uint32_t n = unpack_bytes(w, h, 1, format, type);
    begin(T, IDX_glTexImage2D, 36, 32 + pixels_len(pixels, n));
    put32(T, target); put32(T, (uint32_t)level); put32(T, (uint32_t)ifmt); put32(T, (uint32_t)w); put32(T, (uint32_t)h);
    put32(T, (uint32_t)border); put32(T, format); put32(T, type);
    put_pixels(T, pixels, n);
}
static void e_glTexSubImage2D(GLenum target, GLint level, GLint x, GLint y, GLsizei w, GLsizei h, GLenum format, GLenum type, const void *pixels) {
    struct ts *T = TS();
    if (target == 0x0DE1 && level == 0 && pixels && !G()->unpack_buf) tex_mirror(x, y, w, h, format, type, pixels);
    uint32_t n = unpack_bytes(w, h, 1, format, type);
    begin(T, IDX_glTexSubImage2D, 36, 32 + pixels_len(pixels, n));
    put32(T, target); put32(T, (uint32_t)level); put32(T, (uint32_t)x); put32(T, (uint32_t)y); put32(T, (uint32_t)w);
    put32(T, (uint32_t)h); put32(T, format); put32(T, type);
    put_pixels(T, pixels, n);
}
static void e_glTexImage3D(GLenum target, GLint level, GLint ifmt, GLsizei w, GLsizei h, GLsizei d, GLint border, GLenum format, GLenum type, const void *pixels) {
    struct ts *T = TS();
    uint32_t n = unpack_bytes(w, h, d, format, type);
    begin(T, IDX_glTexImage3D, 40, 36 + pixels_len(pixels, n));
    put32(T, target); put32(T, (uint32_t)level); put32(T, (uint32_t)ifmt); put32(T, (uint32_t)w); put32(T, (uint32_t)h);
    put32(T, (uint32_t)d); put32(T, (uint32_t)border); put32(T, format); put32(T, type);
    put_pixels(T, pixels, n);
}
static void e_glTexSubImage3D(GLenum target, GLint level, GLint x, GLint y, GLint z, GLsizei w, GLsizei h, GLsizei d, GLenum format, GLenum type, const void *pixels) {
    struct ts *T = TS();
    uint32_t n = unpack_bytes(w, h, d, format, type);
    begin(T, IDX_glTexSubImage3D, 44, 40 + pixels_len(pixels, n));
    put32(T, target); put32(T, (uint32_t)level); put32(T, (uint32_t)x); put32(T, (uint32_t)y); put32(T, (uint32_t)z);
    put32(T, (uint32_t)w); put32(T, (uint32_t)h); put32(T, (uint32_t)d); put32(T, format); put32(T, type);
    put_pixels(T, pixels, n);
}
static void e_glCompressedTexImage2D(GLenum target, GLint level, GLenum ifmt, GLsizei w, GLsizei h, GLint border, GLsizei size, const void *data) {
    struct ts *T = TS();
    uint32_t n = size > 0 ? (uint32_t)size : 0;
    begin(T, IDX_glCompressedTexImage2D, 32, 28 + pixels_len(data, n));
    put32(T, target); put32(T, (uint32_t)level); put32(T, ifmt); put32(T, (uint32_t)w); put32(T, (uint32_t)h);
    put32(T, (uint32_t)border); put32(T, n);
    put_pixels(T, data, n);
}
static void e_glCompressedTexSubImage2D(GLenum target, GLint level, GLint x, GLint y, GLsizei w, GLsizei h, GLenum format, GLsizei size, const void *data) {
    struct ts *T = TS();
    uint32_t n = size > 0 ? (uint32_t)size : 0;
    begin(T, IDX_glCompressedTexSubImage2D, 36, 32 + pixels_len(data, n));
    put32(T, target); put32(T, (uint32_t)level); put32(T, (uint32_t)x); put32(T, (uint32_t)y); put32(T, (uint32_t)w);
    put32(T, (uint32_t)h); put32(T, format); put32(T, n);
    put_pixels(T, data, n);
}
static void e_glCompressedTexImage3D(GLenum target, GLint level, GLenum ifmt, GLsizei w, GLsizei h, GLsizei d, GLint border, GLsizei size, const void *data) {
    struct ts *T = TS();
    uint32_t n = size > 0 ? (uint32_t)size : 0;
    begin(T, IDX_glCompressedTexImage3D, 36, 32 + pixels_len(data, n));
    put32(T, target); put32(T, (uint32_t)level); put32(T, ifmt); put32(T, (uint32_t)w); put32(T, (uint32_t)h);
    put32(T, (uint32_t)d); put32(T, (uint32_t)border); put32(T, n);
    put_pixels(T, data, n);
}
static void e_glCompressedTexSubImage3D(GLenum target, GLint level, GLint x, GLint y, GLint z, GLsizei w, GLsizei h, GLsizei d, GLenum format, GLsizei size, const void *data) {
    struct ts *T = TS();
    uint32_t n = size > 0 ? (uint32_t)size : 0;
    begin(T, IDX_glCompressedTexSubImage3D, 44, 40 + pixels_len(data, n));
    put32(T, target); put32(T, (uint32_t)level); put32(T, (uint32_t)x); put32(T, (uint32_t)y); put32(T, (uint32_t)z);
    put32(T, (uint32_t)w); put32(T, (uint32_t)h); put32(T, (uint32_t)d); put32(T, format); put32(T, n);
    put_pixels(T, data, n);
}
static void e_glReadPixels(GLint x, GLint y, GLsizei w, GLsizei h, GLenum format, GLenum type, void *pixels) {
    struct ts *T = TS();
    struct gstate *g = G();
    uint32_t n = g->pack_buf ? NIL : pix_bytes(w, h, 1, format, type, g->p_align, g->p_row, g->p_skip_rows, g->p_skip_pix, 0, 0);
    begin(T, IDX_glReadPixels, 36, 36);
    put32(T, (uint32_t)x); put32(T, (uint32_t)y); put32(T, (uint32_t)w); put32(T, (uint32_t)h); put32(T, format); put32(T, type);
    put32(T, n); put64(T, (uint64_t)(uintptr_t)pixels);
    if (n == NIL) { sh_drop(g->root, g->pack_buf); return; }      /* into the bound buffer */
    rsync(T);
    get(T, pixels, n);
}

static struct map *map_of(GLenum target, int make) {
    struct gstate *g = G();
    for (int i = 0; i < 8; i++) if (g->maps[i].ptr && g->maps[i].target == target) return &g->maps[i];
    if (make) for (int i = 0; i < 8; i++) if (!g->maps[i].ptr) return &g->maps[i];
    return 0;
}
static void map_write(struct ts *T, GLenum target, long offset, const void *p, long length) {
    if (length <= 0) return;
    sh_write(target, offset, p, length, 0);
    begin(T, OP_MAP_WRITE, 16, 16 + BLOB((uint32_t)length));
    put32(T, target); put64(T, (uint64_t)offset); putblob(T, p, (uint32_t)length);
}
/* A mapped buffer is memory of the guest: read from the host when it is mapped (unless the program gives the old
 * contents up), written back when it is unmapped or flushed. */
static void *e_glMapBufferRange(GLenum target, GLintptr offset, GLsizeiptr length, GLbitfield access) {
    struct ts *T = TS();
    struct map *m = map_of(target, 1);
    if (!m || m->ptr || length <= 0) return 0;
    void *p = malloc((size_t)length);
    if (!p) return 0;
    if ((access & 1) || !(access & (4 | 8))) map_read(T, target, offset, length, p);
    m->target = target; m->access = access; m->offset = offset; m->length = length; m->ptr = p;
    return p;
}
static GLboolean e_glUnmapBuffer(GLenum target) {
    struct ts *T = TS();
    struct map *m = map_of(target, 0);
    if (!m) return 0;
    if ((m->access & 2) && !(m->access & 0x10)) map_write(T, target, m->offset, m->ptr, m->length);
    free(m->ptr); m->ptr = 0;
    return 1;
}
static void e_glFlushMappedBufferRange(GLenum target, GLintptr offset, GLsizeiptr length) {
    struct map *m = map_of(target, 0);
    if (m && offset >= 0 && offset + length <= m->length) map_write(TS(), target, m->offset + offset, (char *)m->ptr + offset, length);
}
static void e_glGetBufferPointerv(GLenum target, GLenum pname, void **params) {
    struct map *m = map_of(target, 0);
    (void)pname;
    if (params) *params = m ? m->ptr : 0;
}
static void *e_glMapBufferOES(GLenum target, GLenum access) {
    GLint size = 0;
    (void)access;
    e_glGetBufferParameteriv(target, 0x8764, &size);
    return e_glMapBufferRange(target, 0, size, 2);
}

static void get_uniform(int kind, GLuint program, GLint location, void *params) {
    struct ts *T = TS();
    uint32_t v[16];
    begin(T, OP_GET_UNIFORM, 12, 12); put32(T, (uint32_t)kind); put32(T, program); put32(T, (uint32_t)location);
    rsync(T);
    uint32_t n = get32v(T);
    get(T, v, sizeof(v));
    if (params && n <= 16) memcpy(params, v, n * 4);
}
static void e_glGetUniformfv(GLuint program, GLint location, GLfloat *params) { get_uniform(0, program, location, params); }
static void e_glGetUniformiv(GLuint program, GLint location, GLint *params) { get_uniform(1, program, location, params); }
/* GL_EXT_robustness / GL_KHR_robustness (offered when the phone's driver has them and the host's contexts lose
 * themselves on a reset): Chromium's WebView enables WebGL only then. A reset is not passed on: no reset seen. */
static GLenum e_glGetGraphicsResetStatusEXT(void) { return 0; }
static GLenum e_glGetGraphicsResetStatusKHR(void) { return 0; }
static void e_glReadnPixelsEXT(GLint x, GLint y, GLsizei w, GLsizei h, GLenum format, GLenum type, GLsizei n, void *data) { (void)n; e_glReadPixels(x, y, w, h, format, type, data); }
static void e_glReadnPixelsKHR(GLint x, GLint y, GLsizei w, GLsizei h, GLenum format, GLenum type, GLsizei n, void *data) { (void)n; e_glReadPixels(x, y, w, h, format, type, data); }
static void e_glGetnUniformfvEXT(GLuint program, GLint location, GLsizei n, GLfloat *params) { (void)n; get_uniform(0, program, location, params); }
static void e_glGetnUniformivEXT(GLuint program, GLint location, GLsizei n, GLint *params) { (void)n; get_uniform(1, program, location, params); }
static void e_glGetnUniformfvKHR(GLuint program, GLint location, GLsizei n, GLfloat *params) { (void)n; get_uniform(0, program, location, params); }
static void e_glGetnUniformivKHR(GLuint program, GLint location, GLsizei n, GLint *params) { (void)n; get_uniform(1, program, location, params); }
static void e_glGetUniformuiv(GLuint program, GLint location, GLuint *params) { get_uniform(2, program, location, params); }

/* ---- GLES 1 on the host's GLES 3 contexts: the fixed-function pipeline, written over the encoders above ---- */
#include "gles1.h"

/* ---- the window system's types, as libnativewindow lays them out ---- */
struct native_base { int magic, version; void *reserved[4]; void (*incRef)(struct native_base *); void (*decRef)(struct native_base *); };
struct ANativeWindowBuffer {
    struct native_base common;
    int width, height, stride, format, usage_deprecated;
    uintptr_t layerCount;
    void *reserved[1];
    const void *handle;
    uint64_t usage;
};
struct ANativeWindow {
    struct native_base common;
    uint32_t flags;
    int minSwapInterval, maxSwapInterval;
    float xdpi, ydpi;
    intptr_t oem[4];
    int (*setSwapInterval)(struct ANativeWindow *, int);
    int (*dequeueBuffer_DEPRECATED)(struct ANativeWindow *, struct ANativeWindowBuffer **);
    int (*lockBuffer_DEPRECATED)(struct ANativeWindow *, struct ANativeWindowBuffer *);
    int (*queueBuffer_DEPRECATED)(struct ANativeWindow *, struct ANativeWindowBuffer *);
    int (*query)(const struct ANativeWindow *, int what, int *value);
    int (*perform)(struct ANativeWindow *, int op, ...);
    int (*cancelBuffer_DEPRECATED)(struct ANativeWindow *, struct ANativeWindowBuffer *);
    int (*dequeueBuffer)(struct ANativeWindow *, struct ANativeWindowBuffer **, int *fence);
    int (*queueBuffer)(struct ANativeWindow *, struct ANativeWindowBuffer *, int fence);
    int (*cancelBuffer)(struct ANativeWindow *, struct ANativeWindowBuffer *, int fence);
};
/*
 * The window's call table across releases (AOSP system/core/include/system/window.h, android_natives.h before 2.3):
 *   2.1 - 2.2:  setSwapInterval, dequeueBuffer(w, &b), lockBuffer(w, b), queueBuffer(w, b), query, perform, reserved_proc[3]
 *   2.3 - 4.1:  the same, with cancelBuffer(w, b) in the first reserved slot (reserved_proc[2] after it)
 *   4.2 and later: those six slots stay as *_DEPRECATED (kept for old drivers), and the fence variants dequeueBuffer(w, &b, &fence),
 *               queueBuffer(w, b, fence), cancelBuffer(w, b, fence) follow cancelBuffer_DEPRECATED.
 * The first seven slots are the same offsets in every release, so one struct serves all; common.version is sizeof() of the window
 * the release was built with: the 4.2+ table is one pointer longer than the older ones. Before 4.2 there are no fences, and the
 * buffer must be locked after it is dequeued (AOSP's libagl, opengl/libagl/egl.cpp of 2.0 - 4.0.4: set usage, dequeue, lock, draw,
 * queue, dequeue, lock; it never connects the window, so neither does this driver).
 * Android 1.x - 2.0 hand EGL an egl_native_window_t of a different shape: not carried (the engine keeps those on its own driver).
 */
static int sdk_level(void) {
    static int v = -1;
    if (v < 0) {
        char s[92] = "";
        __system_property_get("ro.build.version.sdk", s);
        v = 0;
        for (const char *q = s; *q >= '0' && *q <= '9'; q++) v = v * 10 + (*q - '0');
        if (!v) v = 99;                /* (unknown: assume a release that has it, as before) */
    }
    return v;
}
static int win_new_abi(const struct ANativeWindow *w) { return w->common.version >= (int)sizeof(struct ANativeWindow); }
static int w_dequeue(struct ANativeWindow *w, struct ANativeWindowBuffer **b, int *fence) {
    *fence = -1;
    if (win_new_abi(w)) return w->dequeueBuffer(w, b, fence);
    int r = w->dequeueBuffer_DEPRECATED(w, b);
    if (r == 0 && *b && w->lockBuffer_DEPRECATED) r = w->lockBuffer_DEPRECATED(w, *b);
    return r;
}
static int w_queue(struct ANativeWindow *w, struct ANativeWindowBuffer *b) {
    return win_new_abi(w) ? w->queueBuffer(w, b, -1) : w->queueBuffer_DEPRECATED(w, b);
}
static void w_cancel(struct ANativeWindow *w, struct ANativeWindowBuffer *b) {
    if (win_new_abi(w)) w->cancelBuffer(w, b, -1);
    else if (sdk_level() >= 9 && w->cancelBuffer_DEPRECATED && (uintptr_t)w->cancelBuffer_DEPRECATED > 0xfff) w->cancelBuffer_DEPRECATED(w, b);
    /* (2.1 / 2.2 have no cancelBuffer: that slot is reserved_proc[0] of a window that is not cleared - a Nexus One 2.1 launcher held
     * 0x00770064 there and died jumping to it when its RenderScript surface was destroyed) */
}
/*
 * Android 1.0 - 1.6: EGL is not given an ANativeWindow but an egl_native_window_t (opengl/include/EGL/eglnatives.h of 1.6, magic 0x600913,
 * the first word of both kinds). It is a mapped buffer, not a queue: libEGL (opengl/libs/EGL/egl.cpp of 1.6) has already incRef'ed and
 * connect()ed it, so width/height/stride/format and base + offset (the pixels of the back page) are set; the driver draws into them
 * (AOSP's libagl: bindDrawSurface) and calls swapBuffers(window), which posts the page (a Surface: unlockAndPost + lock; the display
 * window of SurfaceFlinger: the framebuffer pan, hence the two pages of fb0 the 1.0 / 1.1 translator must report) and returns
 * EGL_NATIVES_FLAG_SIZE_CHANGED when width / height / stride / format / base / offset have changed. The window's stride is in pixels.
 * Here the frame comes back from the host into base + offset in the window's own format and swapBuffers is called.
 */
#define EGL_NATIVE_WINDOW_MAGIC 0x600913
#define EGL_NATIVES_FLAG_SIZE_CHANGED 1
struct egl_native_window_t {
    uint32_t magic, version, ident;
    int width, height, stride, format;
    intptr_t offset;
    uint32_t flags;
    float xdpi, ydpi, fps;
    intptr_t base;
    int fd;
    uint8_t memory_type, reserved_pad[3];          /* memory_type 2: NATIVE_MEMORY_TYPE_FB */
    int reserved[8];
    int vstride;
    void (*incRef)(struct egl_native_window_t *);
    void (*decRef)(struct egl_native_window_t *);
    uint32_t (*swapBuffers)(struct egl_native_window_t *);
    void (*reserved_proc_0)(void), (*reserved_proc_1)(void), (*reserved_proc_2)(void);
    void (*connect)(struct egl_native_window_t *);
    void (*disconnect)(struct egl_native_window_t *);
};
_Static_assert(sizeof(void *) != 4 || __builtin_offsetof(struct egl_native_window_t, swapBuffers) == 104, "egl_native_window_t of 1.6");
static int (*ahb_lock)(void *b, uint64_t usage, int fence, const void *rect, void **va);
static int (*ahb_unlock)(void *b, int *fence);
static void *(*anwb_ahb)(struct ANativeWindowBuffer *b);
/* before 8.0 there is no AHardwareBuffer API: the gralloc module locks the window buffer itself */
struct gr_module {
    uint32_t tag; uint16_t mver, hver; const char *id, *name, *author; void *methods, *dso;
#ifdef __LP64__
    uint64_t reserved[32 - 7];
#else
    uint32_t reserved[32 - 7];
#endif
    int (*registerBuffer)(const struct gr_module *, const void *);
    int (*unregisterBuffer)(const struct gr_module *, const void *);
    int (*lock)(const struct gr_module *, const void *, int usage, int l, int t, int w, int h, void **vaddr);
    int (*unlock)(const struct gr_module *, const void *);
};
static const struct gr_module *g_gr;
/* A handle the gralloc module does not recognise. Nexus One 2.1 (Android ERD79) hands RenderScript windows (the live wallpaper, the
 * launcher's all-apps surface) buffers whose native_handle is not the engine gralloc's: version 12, 1 fd, 5 ints = the magic 0x5f736768,
 * size, base (the mapping the buffer already has in THIS process), flags, pid of the allocator. The module answers "invalid gralloc
 * handle" for each, every frame was dropped and the screen stayed black with the phone's GPU on (software rendering locks the same buffers
 * through libui). The pixels are already mapped: when the module refuses and the handle has that shape, take base from it, provided the
 * mapping is really there (mincore) and holds the whole frame. 32-bit guests only (the handle holds 32-bit words). */
static void *libc_sym(const char *name);
static int g_foreign_prefer;            /* 1 once a refused handle was served from its own mapping: asked first from then on */
static __thread int t_foreign;          /* the lock of this thread is of that kind: unlock has nothing to tell the module */
static int g_foreign_why;               /* why the last foreign_base answered 0 (for the log) */
static void *foreign_base(struct ANativeWindowBuffer *b) {
#ifdef __LP64__
    (void)b; return 0;
#else
    g_foreign_why = 0;
    const int *h = (const int *)b->handle;
    if (!h || h[0] != 12 || h[1] != 1 || h[2] < 4 || h[2] > 12) { g_foreign_why = 1; return 0; }
    int bpp = b->format == 1 || b->format == 2 ? 4 : b->format == 3 ? 3 : b->format == 4 ? 2 : 0;
    if (!bpp || b->width <= 0 || b->height <= 0 || b->stride < b->width) { g_foreign_why = 2; return 0; }
    uint32_t size = (uint32_t)h[5], base = (uint32_t)h[6];
    uint32_t need = ((uint32_t)b->stride * (uint32_t)(b->height - 1) + (uint32_t)b->width) * (uint32_t)bpp;
    if (!base || (base & 0xfff) || (size & 0xfff) || size < need) { g_foreign_why = 3; return 0; }
    static int (*mc)(void *, size_t, unsigned char *);
    static int looked;
    if (!looked) { mc = (int (*)(void *, size_t, unsigned char *))libc_sym("mincore"); looked = 1; }
    if (!mc) { g_foreign_why = 4; return 0; }
    static unsigned char vec[2048];
    size_t pages = need / 4096 + 1;
    if (pages > sizeof vec) { g_foreign_why = 5; return 0; }
    if (mc((void *)(uintptr_t)base, need, vec) != 0) { g_foreign_why = 6; return 0; }      /* (not mapped here: the handle is not what it looks like) */
    return (void *)(uintptr_t)base;
#endif
}
static int buf_api(void) {
    static int state;
    if (!state) {
        void *l = dlopen("libnativewindow.so", RTLD_NOW);
        ahb_lock = l ? dlsym(l, "AHardwareBuffer_lock") : 0;
        ahb_unlock = l ? dlsym(l, "AHardwareBuffer_unlock") : 0;
        anwb_ahb = l ? dlsym(l, "ANativeWindowBuffer_getHardwareBuffer") : 0;
        state = ahb_lock && ahb_unlock ? 1 : -1;
        if (state < 0) {
            void *hw = dlopen("libhardware.so", RTLD_NOW);
            int (*get)(const char *, const void **) = hw ? (int (*)(const char *, const void **))dlsym(hw, "hw_get_module") : 0;
            const void *m = 0;
            if (get && get("gralloc", &m) == 0 && m && ((const struct gr_module *)m)->lock) { g_gr = m; state = 2; }
        }
        if (state < 0) LOG("no AHardwareBuffer_lock nor gralloc module in this release: frames cannot be handed over");
    }
    return state > 0;
}
/* (an AHardwareBuffer is the ANativeWindowBuffer part of the same GraphicBuffer) */
static void *ahb_of(struct ANativeWindowBuffer *b) { return anwb_ahb ? anwb_ahb(b) : (void *)b; }
/* what the module was given, once: the header of the native_handle (version, numFds, numInts) and its first ints */
static void gr_refused(struct ANativeWindowBuffer *b, int usage, int served) {
    static int said;
    if (said++ >= 2) return;
    const int *h = (const int *)b->handle;
    if (!h) { LOG("gralloc refused a window buffer: handle is NULL (buffer %p, usage 0x%x)", (void *)b, usage); return; }
    LOG("gralloc refused a window buffer %p, usage 0x%x (%s): handle %p = version %d, numFds %d, numInts %d, then %08x %08x %08x %08x %08x %08x %08x",
        (void *)b, usage, served ? "served from the handle's own mapping" : (g_foreign_why == 1 ? "not usable: handle shape" : g_foreign_why == 2 ? "not usable: format" : g_foreign_why == 3 ? "not usable: size or base" : g_foreign_why == 6 ? "not usable: not mapped" : "not usable either"), (const void *)h, h[0], h[1], h[2],
        (unsigned)h[3], (unsigned)h[4], (unsigned)h[5], (unsigned)h[6], (unsigned)h[7], (unsigned)h[8], (unsigned)h[9]);
}
static void *buf_lock(struct ANativeWindowBuffer *b, int write) {
    void *va = 0;
    if (!buf_api()) return 0;
    int usage = write ? 0x30 : 0x3;
    t_foreign = 0;
    if (g_gr) {
        if (g_foreign_prefer && (va = foreign_base(b))) { b->common.incRef(&b->common); t_foreign = 1; return va; }
        if (g_gr->lock(g_gr, b->handle, usage, 0, 0, b->width, b->height, &va) == 0 && va) return va;
        va = foreign_base(b);
        gr_refused(b, usage, va != 0);
        if (va) { g_foreign_prefer = 1; t_foreign = 1; b->common.incRef(&b->common); }
        return va;
    }
    if (ahb_lock(ahb_of(b), usage, -1, 0, &va) != 0) return 0;
    return va;
}
static void buf_unlock(struct ANativeWindowBuffer *b) {
    /* (the reference keeps the buffer, and so its mapping, alive while the pixels are written: a window that is resized or torn down
     * meanwhile - the launcher's all-apps surface going from 480x800 to 480x762 - unmaps the old buffers, which crashed the process) */
    if (t_foreign) { t_foreign = 0; b->common.decRef(&b->common); }
    else if (g_gr) g_gr->unlock(g_gr, b->handle);
    else ahb_unlock(ahb_of(b), 0);
}

/* ---- SwiftShader behind ---- */
#ifdef __LP64__
#define LIBDIR "lib64"
#else
#define LIBDIR "lib"
#endif
static struct { void *egl, *v2, *v1; void *t2[N_FUNCS], *t1[N_FUNCS], *enc[N_FUNCS], *enc1[N_FUNCS]; volatile int up; } SS;
/* glGetString through the table of "no bridged context is current": the software side answers NULL without a context of its own */
static const GLubyte *(*ss_gs2)(GLenum), *(*ss_gs1)(GLenum);
static const GLubyte *ss_glGetString2(GLenum name) { const GLubyte *r = ss_gs2 ? ss_gs2(name) : 0; return r ? r : str_fallback(name); }
static const GLubyte *ss_glGetString1(GLenum name) { const GLubyte *r = ss_gs1 ? ss_gs1(name) : 0; return r ? r : str_fallback(name); }
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static long nothing(void) { return 0; }
static long not_carried(void) {
    static int said;
    if (said < 20) { said++; LOG("a GL call the bridge does not carry yet"); }
    return 0;
}
static void *ss_open(const char *name) {
    static const char *const dirs[] = { "/vendor/" LIBDIR "/egl/", "/system/" LIBDIR "/egl/" };
    for (unsigned i = 0; i < 2; i++) {
        char p[128];
        strcpy(p, dirs[i]); strcat(p, name);
        void *h = dlopen(p, RTLD_NOW | RTLD_LOCAL);
        if (h) return h;
    }
    return 0;
}
static void ss_load(void) {
    pthread_mutex_lock(&g_lock);
    if (!SS.up) {
        SS.egl = ss_open("libEGL_swiftshader.so");
        SS.v2 = ss_open("libGLESv2_swiftshader.so");
        SS.v1 = ss_open("libGLESv1_CM_swiftshader.so");
#ifndef __LP64__
        /* the 32-bit engine (KK) has no SwiftShader: behind this driver stands its own one-library bridge (glsplit) */
        if (!SS.egl || !SS.v2) {
            void *k = ss_open("libGLES_split.so");
            if (k) SS.egl = SS.v2 = SS.v1 = k;
        }
#endif
        if (!SS.egl || !SS.v2) LOG("SwiftShader is not there: %s", dlerror());
        for (int i = 0; i < N_FUNCS; i++) {
            void *f = SS.v2 ? dlsym(SS.v2, kNames[i]) : 0;
            SS.t2[i] = f ? f : (void *)nothing;
            f = SS.v1 ? dlsym(SS.v1, kNames[i]) : 0;
            SS.t1[i] = f ? f : (void *)nothing;
            SS.enc[i] = kEnc[i] ? kEnc[i] : (void *)not_carried;
        }
        /* Calls that only inform tools (the guest's libEGL offers GL_EXT_debug_marker itself and programs use it) have no effect on
         * the picture: taken and dropped, not reported as missing. */
        static const int kNop[] = { IDX_glInsertEventMarkerEXT, IDX_glPushGroupMarkerEXT, IDX_glPopGroupMarkerEXT, IDX_glLabelObjectEXT,
                                    IDX_glGetObjectLabelEXT, IDX_glStartTilingQCOM, IDX_glEndTilingQCOM };
        for (unsigned i = 0; i < sizeof kNop / sizeof kNop[0]; i++) SS.enc[kNop[i]] = (void *)nothing;
        if (SS.t2[IDX_glGetString] != (void *)nothing) ss_gs2 = (const GLubyte *(*)(GLenum))SS.t2[IDX_glGetString];
        if (SS.t1[IDX_glGetString] != (void *)nothing) ss_gs1 = (const GLubyte *(*)(GLenum))SS.t1[IDX_glGetString];
        SS.t2[IDX_glGetString] = (void *)ss_glGetString2;
        SS.t1[IDX_glGetString] = (void *)ss_glGetString1;
        /* a GLES 1 context: the same encoders, the fixed-function entries (gles1.h) over them */
        memcpy(SS.enc1, SS.enc, sizeof(SS.enc1));
        g1_install(SS.enc1);
        SS.up = 1;
    }
    pthread_mutex_unlock(&g_lock);
}
static void *const *default_tbl(void) { if (!SS.up) ss_load(); return SS.t2; }
static void *sse(const char *name) { if (!SS.up) ss_load(); return SS.egl ? dlsym(SS.egl, name) : 0; }

/* ---- EGL ---- */
typedef void *EGLDisplay, *EGLConfig, *EGLSurface, *EGLContext, *EGLImageKHR, *EGLSyncKHR, *EGLClientBuffer;
typedef int32_t EGLint;
typedef unsigned int EGLBoolean, EGLenum;
#define EGL_SUCCESS 0x3000
#define EGL_BAD_ALLOC 0x3003
#define EGL_BAD_CONTEXT 0x3006
#define EGL_BAD_MATCH 0x3009
#define EGL_BAD_SURFACE 0x300D
#define EGL_HEIGHT 0x3056
#define EGL_WIDTH 0x3057
#define EGL_NONE 0x3038
static __thread EGLint t_err;


/* ---- no software renderer behind (x86 / x86-64 firmware): the driver is the whole EGL ----
 * SwiftShader exists for the phone's own architecture only. A firmware that is translated (an x86-64 Android 10 SDK image: no
 * libEGL_swiftshader.so, no libGLES_split.so, nothing in /vendor) used to get an EGL with no display: eglGetDisplay answered 0,
 * libEGL could not initialize the driver and SurfaceFlinger aborted in RenderEngine ("eglQueryStringImplementationANDROID(
 * EGL_VERSION) failed"). In this mode the display, the configs and the strings are ours, and every context - the
 * compositor's too - is made on the host (solo() turns policy() off). Window and pbuffer surfaces, images and sync objects of
 * bridged contexts were ours already. */
static int solo(void) { if (!SS.up) ss_load(); return !SS.egl || !SS.v2; }
static char g_solo_dpy[8];
struct cfg { uint8_t r, g, b, a, depth, stencil; uint8_t visual; };
/* (visual: the HAL pixel format of the buffers a window of this config takes: 1 RGBA_8888, 2 RGBX_8888, 4 RGB_565) */
static const struct cfg kCfg[] = {
    { 8, 8, 8, 8, 24, 8, 1 }, { 8, 8, 8, 8, 16, 0, 1 }, { 8, 8, 8, 8, 0, 0, 1 },
    { 8, 8, 8, 0, 24, 8, 2 }, { 8, 8, 8, 0, 16, 0, 2 }, { 8, 8, 8, 0, 0, 0, 2 },
    { 5, 6, 5, 0, 24, 8, 4 }, { 5, 6, 5, 0, 16, 0, 4 }, { 5, 6, 5, 0, 0, 0, 4 },
};
#define N_CFG ((int)(sizeof kCfg / sizeof kCfg[0]))
static const struct cfg *cfg_of(void *c) {
    uintptr_t i = (uintptr_t)c;
    return i >= 1 && i <= (uintptr_t)N_CFG ? &kCfg[i - 1] : 0;
}
static EGLBoolean solo_cfg_attrib(const struct cfg *c, uintptr_t id, EGLint a, EGLint *v) {
    int rgb = c->r + c->g + c->b + c->a;
    switch (a) {
    case 0x3020: *v = rgb; break;                   /* EGL_BUFFER_SIZE */
    case 0x3021: *v = c->a; break;                  /* EGL_ALPHA_SIZE */
    case 0x3022: *v = c->b; break;
    case 0x3023: *v = c->g; break;
    case 0x3024: *v = c->r; break;
    case 0x3025: *v = c->depth; break;
    case 0x3026: *v = c->stencil; break;
    case 0x3027: *v = 0x3038; break;                /* EGL_CONFIG_CAVEAT: EGL_NONE */
    case 0x3028: *v = (EGLint)id; break;            /* EGL_CONFIG_ID */
    case 0x3029: *v = 0; break;                     /* EGL_LEVEL */
    case 0x302A: case 0x302C: *v = 4096; break;     /* EGL_MAX_PBUFFER_HEIGHT / WIDTH */
    case 0x302B: *v = 4096 * 4096; break;           /* EGL_MAX_PBUFFER_PIXELS */
    case 0x302D: *v = 1; break;                     /* EGL_NATIVE_RENDERABLE */
    case 0x302E: *v = c->visual; break;             /* EGL_NATIVE_VISUAL_ID */
    case 0x302F: *v = 0x3038; break;                /* EGL_NATIVE_VISUAL_TYPE */
    case 0x3031: case 0x3032: *v = 0; break;        /* EGL_SAMPLES / EGL_SAMPLE_BUFFERS */
    case 0x3033: *v = 0x1 | 0x4; break;             /* EGL_SURFACE_TYPE: PBUFFER | WINDOW */
    case 0x3034: *v = 0x3038; break;                /* EGL_TRANSPARENT_TYPE: EGL_NONE */
    case 0x3035: case 0x3036: case 0x3037: *v = 0; break;
    case 0x3039: *v = 0; break;                     /* EGL_BIND_TO_TEXTURE_RGB */
    case 0x303A: *v = 0; break;                     /* EGL_BIND_TO_TEXTURE_RGBA */
    case 0x303B: *v = 0; break;                     /* EGL_MIN_SWAP_INTERVAL */
    case 0x303C: *v = 1; break;                     /* EGL_MAX_SWAP_INTERVAL */
    case 0x303D: case 0x303E: *v = 0; break;        /* EGL_LUMINANCE_SIZE / EGL_ALPHA_MASK_SIZE */
    case 0x303F: *v = 0x308E; break;                /* EGL_COLOR_BUFFER_TYPE: EGL_RGB_BUFFER */
    case 0x3040: *v = 0x1 | 0x4 | 0x40; break;      /* EGL_RENDERABLE_TYPE: ES | ES2 | ES3 */
    case 0x3042: *v = 0x1 | 0x4 | 0x40; break;      /* EGL_CONFORMANT */
    case 0x3142: *v = 1; break;                     /* EGL_RECORDABLE_ANDROID */
    case 0x3147: *v = c->visual != 4; break;        /* EGL_FRAMEBUFFER_TARGET_ANDROID */
    default: return 0;
    }
    return 1;
}
static EGLBoolean solo_eglGetConfigAttrib(EGLDisplay d, EGLConfig c, EGLint a, EGLint *v) {
    (void)d;
    const struct cfg *k = cfg_of(c);
    if (!k || !v) { t_err = k ? 0x300C /* EGL_BAD_PARAMETER */ : 0x3005 /* EGL_BAD_CONFIG */; return 0; }
    if (!solo_cfg_attrib(k, (uintptr_t)c, a, v)) { t_err = 0x3004; /* EGL_BAD_ATTRIBUTE */ return 0; }
    return 1;
}
static EGLBoolean solo_eglGetConfigs(EGLDisplay d, EGLConfig *out, EGLint size, EGLint *n) {
    (void)d;
    if (!n) { t_err = 0x300C; return 0; }
    if (!out) { *n = N_CFG; return 1; }
    int k = 0;
    for (; k < N_CFG && k < size; k++) out[k] = (EGLConfig)(uintptr_t)(k + 1);
    *n = k;
    return 1;
}
/* eglChooseConfig as the spec has it: the sizes are minimums, the bit masks must be contained, the rest must be equal; a
 * value of EGL_DONT_CARE (-1) matches everything. Order: more colour bits (of the channels asked for), then smaller buffer,
 * depth and stencil, then the lower id. */
static EGLBoolean solo_eglChooseConfig(EGLDisplay d, const EGLint *attribs, EGLConfig *out, EGLint size, EGLint *n) {
    (void)d;
    if (!n) { t_err = 0x300C; return 0; }
    int ok[N_CFG];
    for (int i = 0; i < N_CFG; i++) ok[i] = 1;
    int want_r = 0, want_g = 0, want_b = 0, want_a = 0;
    int renderable_given = 0;
    for (const EGLint *a = attribs; a && *a != EGL_NONE; a += 2) {
        EGLint name = a[0], want = a[1];
        if (name == 0x3024) want_r = want > 0;
        if (name == 0x3023) want_g = want > 0;
        if (name == 0x3022) want_b = want > 0;
        if (name == 0x3021) want_a = want > 0;
        if (name == 0x3040) renderable_given = 1;
        if (want == -1) continue;
        for (int i = 0; i < N_CFG; i++) {
            EGLint have;
            if (!solo_cfg_attrib(&kCfg[i], (uintptr_t)(i + 1), name, &have)) { t_err = 0x3004; return 0; }
            switch (name) {
            case 0x3020: case 0x3021: case 0x3022: case 0x3023: case 0x3024: case 0x3025: case 0x3026:
            case 0x3031: case 0x3032: case 0x303D: case 0x303E:
                { if (have < want) ok[i] = 0; break; }
            case 0x3033: case 0x3040: case 0x3042:
                { if ((have & want) != want) ok[i] = 0; break; }
            case 0x3028: case 0x3029: case 0x302D: case 0x302E: case 0x302F: case 0x3027: case 0x3034:
            case 0x3039: case 0x303A: case 0x303B: case 0x303C: case 0x303F: case 0x3142: case 0x3147:
                { if (have != want) ok[i] = 0; break; }
            default: break;      /* (max pbuffer sizes and the transparent values: not a selection criterion here) */
            }
        }
    }
    (void)renderable_given;
    int idx[N_CFG], cnt = 0;
    for (int i = 0; i < N_CFG; i++) if (ok[i]) idx[cnt++] = i;
    /* insertion sort: nine entries */
    for (int i = 1; i < cnt; i++) {
        int v = idx[i], j = i - 1;
        for (; j >= 0; j--) {
            const struct cfg *x = &kCfg[idx[j]], *y = &kCfg[v];
            int bx = (want_r || (!want_g && !want_b && !want_a) ? x->r : 0) + (want_g || (!want_r && !want_b && !want_a) ? x->g : 0) +
                     (want_b || (!want_r && !want_g && !want_a) ? x->b : 0) + (want_a ? x->a : 0);
            int by = (want_r || (!want_g && !want_b && !want_a) ? y->r : 0) + (want_g || (!want_r && !want_b && !want_a) ? y->g : 0) +
                     (want_b || (!want_r && !want_g && !want_a) ? y->b : 0) + (want_a ? y->a : 0);
            int before;     /* does y (the one being placed) sort before x? */
            if (by != bx) before = by > bx;
            else if (y->r + y->g + y->b + y->a != x->r + x->g + x->b + x->a) before = (y->r + y->g + y->b + y->a) < (x->r + x->g + x->b + x->a);
            else if (y->depth != x->depth) before = y->depth < x->depth;
            else if (y->stencil != x->stencil) before = y->stencil < x->stencil;
            else before = v < idx[j];
            if (!before) break;
            idx[j + 1] = idx[j];
        }
        idx[j + 1] = v;
    }
    if (!out) { *n = cnt; return 1; }
    int k = 0;
    for (; k < cnt && k < size; k++) out[k] = (EGLConfig)(uintptr_t)(idx[k] + 1);
    *n = k;
    return 1;
}
static EGLDisplay solo_eglGetDisplay(void *native) { (void)native; return g_solo_dpy; }
static EGLBoolean solo_eglInitialize(EGLDisplay d, EGLint *major, EGLint *minor) {
    if (d != g_solo_dpy) { t_err = 0x3008; /* EGL_BAD_DISPLAY */ return 0; }
    if (major) *major = 1;
    if (minor) *minor = 4;
    return 1;
}
static EGLBoolean solo_eglTerminate(EGLDisplay d) { (void)d; return 1; }
static EGLBoolean solo_eglBindAPI(EGLenum api) { if (api == 0x30A0) return 1; t_err = 0x300C; return 0; }
static EGLenum solo_eglQueryAPI(void) { return 0x30A0; }
static EGLBoolean solo_eglWaitNative(EGLint e) { (void)e; return 1; }
static const char *solo_QueryString(EGLDisplay d, EGLint name) {
    (void)d;
    switch (name) {
    case 0x3053: return "AEmulator";
    case 0x3054: return "1.4 AEmulator GL bridge";
    case 0x308D: return "OpenGL_ES";
    case 0x3055: return "EGL_KHR_image EGL_KHR_image_base EGL_KHR_gl_texture_2D_image EGL_ANDROID_image_native_buffer "
                        "EGL_KHR_surfaceless_context EGL_ANDROID_recordable EGL_ANDROID_framebuffer_target";
    }
    t_err = 0x300C;
    return 0;
}

#define FWD(ret, name, params, args) \
    EXPORT ret name params { static ret (*f) params; if (!f) f = sse(#name); return f ? f args : solo_##name args; }
#define SSF(ret, name, params) static ret (*name) params; if (!name) name = sse("egl" #name)

FWD(EGLDisplay, eglGetDisplay, (void *native), (native))
FWD(EGLBoolean, eglInitialize, (EGLDisplay d, EGLint *major, EGLint *minor), (d, major, minor))
FWD(EGLBoolean, eglTerminate, (EGLDisplay d), (d))
FWD(EGLBoolean, eglGetConfigs, (EGLDisplay d, EGLConfig *c, EGLint size, EGLint *n), (d, c, size, n))
FWD(EGLBoolean, eglChooseConfig, (EGLDisplay d, const EGLint *a, EGLConfig *c, EGLint size, EGLint *n), (d, a, c, size, n))
FWD(EGLBoolean, eglGetConfigAttrib, (EGLDisplay d, EGLConfig c, EGLint a, EGLint *v), (d, c, a, v))
/* The extensions get EGL_EXT_create_context_robustness: contexts on the phone's GPU lose themselves on a reset (host.c,
 * g_robust), and ANGLE (Chromium's WebView from 9x on) turns WebGL on only for a context it asked that of. */
EXPORT const char *eglQueryString(EGLDisplay d, EGLint name) {
    static const char *(*f)(EGLDisplay, EGLint);
    static char *ext;
    if (!f) f = sse("eglQueryString");
    const char *s = f ? f(d, name) : solo_QueryString(d, name);
    if (name != 0x3055 /* EGL_EXTENSIONS */ || !s) return s;
    /* and EGL_KHR_fence_sync (the sync objects below): the 4.4 engine's driver leaves it out, and WebView 44 (6.0)
     * creates a fence anyway, through a function it bound only for that extension - a call to null */
    static const char *const add[] = { "EGL_EXT_create_context_robustness", "EGL_KHR_fence_sync" };
    if (!ext) {
        size_t n = strlen(s);
        char *e = malloc(n + 64);
        if (!e) return s;
        memcpy(e, s, n); e[n] = 0;
        for (unsigned i = 0; i < sizeof add / sizeof add[0]; i++) {
            if (strstr(s, add[i])) continue;
            if (n && e[strlen(e) - 1] != ' ') strcat(e, " ");
            strcat(e, add[i]);
        }
        ext = e;
    }
    return ext;
}
FWD(EGLBoolean, eglBindAPI, (EGLenum api), (api))
FWD(EGLenum, eglQueryAPI, (void), ())
FWD(EGLBoolean, eglWaitNative, (EGLint engine), (engine))

enum { S_WINDOW, S_PBUFFER };
struct esurf {
    int kind, w, h, current, doomed, ready;
    void *dpy, *cfg;
    struct ANativeWindow *win;
    struct egl_native_window_t *ewin;   /* Android 1.0 - 1.6: the window is one of these instead (win is 0) */
    struct ANativeWindowBuffer *buf;    /* dequeued for the frame being drawn */
    void *ss; int ss_tried;
    uint32_t key;
    EGLint attribs[32];
    uint8_t *tmp; uint32_t tmp_n;
};
static volatile uint32_t g_keys;
/* (gles1.h: the viewport before the program sets one is the whole surface) */
static void g1_surface_size(int *w, int *h) {
    struct esurf *s = t_ts && t_ts->ctx ? t_ts->draw : 0;
    *w = *h = 0;
    if (!s) return;
    *w = s->w; *h = s->h;
    if (s->ewin) { *w = s->ewin->width; *h = s->ewin->height; }
    else if (s->kind == S_WINDOW && !s->buf && s->win && s->win->query) { int v = 0; s->win->query(s->win, 0, &v); *w = v; v = 0; s->win->query(s->win, 1, &v); *h = v; }
}

/* A function of libc that an old release may not have: 0 when it is missing (looked up once per name by the callers) */
static void *libc_sym(const char *name) {
    static void *h;
    if (!h) h = dlopen("libc.so", RTLD_NOW);
    return h ? dlsym(h, name) : 0;
}
/* the calling thread's name (prctl PR_GET_NAME) into a 17-byte buffer; left empty where libc has no prctl */
static void thread_name(char *name) {
    static int (*pr)(int, ...);
    static int looked;
    if (!looked) { pr = (int (*)(int, ...))libc_sym("prctl"); looked = 1; }
    if (pr) pr(PR_GET_NAME, name);
}

/* (bionic has getprogname from 5.0; before it: the translator shows the guest's own argv in /proc/self/cmdline) */
static const char *prog_name(void) {
    static char name[128];
    static const char *(*gp)(void);
    static int looked;
    if (!looked) { gp = (const char *(*)(void))libc_sym("getprogname"); looked = 1; }
    const char *c = gp ? gp() : 0;
    if (c) return c;
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) { ssize_t n = read(fd, name, sizeof(name) - 1); name[n > 0 ? n : 0] = 0; close(fd); }
    return name;
}

/* ---- which side a new context goes to ---- */
static int policy(int version) {
    static int mode = -1, process;      /* process: 1 a program that may draw on the GPU, 2 one that may not */
    if (mode < 0) {
        char v[96] = "";
        __system_property_get("debug.aemu.gl", v);
        /* off unless asked for: 1 - a program's own contexts, 2 - the window toolkit's as well */
        mode = v[0] >= '0' && v[0] <= '9' ? v[0] - '0' : 0;
    }
    if (!process) {
        /* (the name libc was started with: /proc/self/cmdline is the engine's runner) */
        const char *c = prog_name();
        /* (the compositor draws on the GPU in mode 3) */
        process = strstr(c, "bootanim") || (strstr(c, "surfaceflinger") && mode < 3) ? 2 : 1;
        LOG("program %s: %s", c, process == 2 ? "SwiftShader only" : "GPU contexts allowed");
    }
    if (!mode || version < 2 || process == 2) return 0;
    if (mode >= 2) return 1;
    /* the window toolkit and Chromium hand textures to each other, and their drawing is light: left where it was */
    static const char *const soft[] = { "RenderThread", "hwuiTask", "GrallocUpload", "Chrome_", "CrGpu", "Compositor", "GpuThread", "Viz" };
    char name[17] = "";
    thread_name(name);
    for (unsigned i = 0; i < sizeof(soft) / sizeof(soft[0]); i++) if (!strncmp(name, soft[i], strlen(soft[i]))) return 0;
    return 1;
}

/* ---- the calling thread's connection ---- */
static pthread_key_t g_key;
static pthread_once_t g_key_once = PTHREAD_ONCE_INIT;
static void ts_free(void *p) {
    struct ts *T = p;
    if (T->fd >= 0) close(T->fd);
    free(T->buf); free(T);
}
static void key_make(void) { pthread_key_create(&g_key, ts_free); }
static struct ts *ts_get(void) {
    if (t_ts) return t_ts;
    struct ts *T = calloc(1, sizeof(*T));
    if (!T) return 0;
    T->cap = 256 * 1024;
    T->buf = malloc(T->cap);
    T->fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un a = { .sun_family = AF_UNIX };
    strcpy(a.sun_path, AEMU_GL_SOCKET);
    if (!T->buf || T->fd < 0 || connect(T->fd, (struct sockaddr *)&a, sizeof(a)) < 0) {
        LOG("no host renderer at " AEMU_GL_SOCKET " (errno %d)", errno);
        if (T->fd >= 0) close(T->fd);
        free(T->buf); free(T);
        return 0;
    }
    int big = 4 << 20;
    setsockopt(T->fd, SOL_SOCKET, SO_SNDBUF, &big, sizeof(big));
    setsockopt(T->fd, SOL_SOCKET, SO_RCVBUF, &big, sizeof(big));
    begin(T, OP_HELLO, 4, 4); put32(T, AEMU_GL_PROTOCOL);
    rsync(T);
    if (get32v(T) != AEMU_GL_PROTOCOL) { LOG("the host renderer speaks another protocol"); close(T->fd); free(T->buf); free(T); return 0; }
    /* what else the host does: a host that knows the probe answers 1 | caps << 1, an older one just 1 */
    begin(T, OP_PING, 4, 4); put32(T, AEMU_CAPS_PROBE);
    rsync(T);
    { uint32_t r = get32v(T); T->caps = (r & 1) && r > 1 ? r >> 1 : 0; }
    pthread_once(&g_key_once, key_make);
    pthread_setspecific(g_key, T);
    t_ts = T;
    return T;
}

/* ---- surfaces ---- */
static int attr_of(const EGLint *a, EGLint name, EGLint def) {
    for (; a && *a != EGL_NONE; a += 2) if (*a == name) return a[1];
    return def;
}
static void copy_attribs(struct esurf *s, const EGLint *a) {
    int n = 0;
    for (; a && *a != EGL_NONE && n < 30; a += 2) { s->attribs[n++] = a[0]; s->attribs[n++] = a[1]; }
    s->attribs[n] = EGL_NONE;
}
EXPORT EGLSurface eglCreateWindowSurface(EGLDisplay d, EGLConfig c, void *win, const EGLint *attribs) {
    struct esurf *s = calloc(1, sizeof(*s));
    if (!s || !win) { free(s); t_err = EGL_BAD_ALLOC; return 0; }
    s->kind = S_WINDOW; s->dpy = d; s->cfg = c;
    s->key = __sync_add_and_fetch(&g_keys, 1);
    copy_attribs(s, attribs);
    if (*(uint32_t *)win == EGL_NATIVE_WINDOW_MAGIC) {
        s->ewin = win;
        s->ewin->incRef(s->ewin);
        s->w = s->ewin->width; s->h = s->ewin->height;
    } else {
        s->win = win;
        s->win->common.incRef(&s->win->common);
    }
    return s;
}
EXPORT EGLSurface eglCreatePbufferSurface(EGLDisplay d, EGLConfig c, const EGLint *attribs) {
    struct esurf *s = calloc(1, sizeof(*s));
    if (!s) { t_err = EGL_BAD_ALLOC; return 0; }
    s->kind = S_PBUFFER; s->dpy = d; s->cfg = c;
    s->w = attr_of(attribs, EGL_WIDTH, 1); s->h = attr_of(attribs, EGL_HEIGHT, 1);
    if (s->w < 1) s->w = 1;
    if (s->h < 1) s->h = 1;
    s->key = __sync_add_and_fetch(&g_keys, 1);
    copy_attribs(s, attribs);
    return s;
}
EXPORT EGLSurface eglCreatePixmapSurface(EGLDisplay d, EGLConfig c, void *pixmap, const EGLint *attribs) {
    (void)d; (void)c; (void)pixmap; (void)attribs;
    t_err = EGL_BAD_MATCH;
    return 0;
}
/* SwiftShader's surface for ours: made when a SwiftShader context first draws to it */
static void *ss_surface(struct esurf *s) {
    if (!s || s->ewin) return 0;           /* (a 1.x window is not one SwiftShader knows) */
    if (!s->ss && !s->ss_tried) {
        s->ss_tried = 1;
        if (s->kind == S_WINDOW) {
            SSF(EGLSurface, CreateWindowSurface, (EGLDisplay, EGLConfig, void *, const EGLint *));
            if (s->buf) { w_cancel(s->win, s->buf); s->buf = 0; }
            s->ss = CreateWindowSurface ? CreateWindowSurface(s->dpy, s->cfg, s->win, s->attribs) : 0;
        } else {
            SSF(EGLSurface, CreatePbufferSurface, (EGLDisplay, EGLConfig, const EGLint *));
            s->ss = CreatePbufferSurface ? CreatePbufferSurface(s->dpy, s->cfg, s->attribs) : 0;
        }
    }
    return s->ss;
}
#if defined(__arm__) && !defined(__LP64__)
/* A guest process that dies of SIGSEGV leaves nothing in the log but "terminated by signal (11)" (no debuggerd in these images). Say where,
 * once, and let the signal run its course (SA_RESETHAND: the faulting instruction runs again and kills the process as before). */
struct bsigaction { void (*handler)(int, void *, void *); unsigned long mask; unsigned long flags; void (*restorer)(void); };
static uint32_t hexv(const char **pp, char stop) {
    uint32_t v = 0;
    const char *q = *pp;
    for (; *q && *q != stop && *q != ' '; q++) v = v * 16 + (uint32_t)((*q <= '9') ? *q - '0' : (*q | 32) - 'a' + 10);
    *pp = q;
    return v;
}
static void where_is(const char *what, uint32_t a) {
    static char maps[65536];
    int (*op)(const char *, int, ...) = (int (*)(const char *, int, ...))libc_sym("open");
    long (*rd)(int, void *, unsigned long) = (long (*)(int, void *, unsigned long))libc_sym("read");
    int (*cl)(int) = (int (*)(int))libc_sym("close");
    if (!op || !rd || !cl) return;
    int fd = op("/proc/self/maps", 0);
    if (fd < 0) { LOG("  %s %08x", what, a); return; }
    long n = rd(fd, maps, sizeof maps - 1);
    cl(fd);
    if (n <= 0) return;
    maps[n] = 0;
    for (char *l = maps; l && *l;) {
        char *e = l;
        while (*e && *e != '\n') e++;
        if (*e) *e = 0; else e = 0;
        const char *q = l;
        uint32_t lo = hexv(&q, '-');
        if (*q == '-') q++;
        uint32_t hi = hexv(&q, ' ');
        if (a >= lo && a < hi) {
            while (*q == ' ') q++;
            while (*q && *q != ' ') q++;                 /* perms */
            while (*q == ' ') q++;
            uint32_t off = hexv(&q, ' ');
            for (int sp = 0; *q && sp < 2; ) { while (*q == ' ') q++; while (*q && *q != ' ') q++; sp++; }   /* dev, inode */
            while (*q == ' ') q++;
            LOG("  %s %08x = %s +0x%x", what, a, *q ? q : "(anonymous)", a - lo + off);
            return;
        }
        l = e ? e + 1 : 0;
    }
    LOG("  %s %08x is in no mapping", what, a);
}
static void segv_log(int sig, void *info, void *uc) {
    const uint32_t *c = (const uint32_t *)uc;
    uint32_t fa = info ? ((const uint32_t *)info)[3] : 0u;
    LOG("signal %d: fault address %08x", sig, fa);
    where_is("pc", c[23]);
    where_is("lr", c[22]);
    where_is("fault", fa);
    LOG("  r0 %08x r1 %08x r2 %08x r3 %08x r4 %08x r5 %08x sp %08x", c[8], c[9], c[10], c[11], c[12], c[13], c[21]);
}
static void crash_logger_install(void) {
    static int done;
    if (done) return;
    done = 1;
    int (*sa)(int, const void *, void *) = (int (*)(int, const void *, void *))libc_sym("sigaction");
    if (!sa) return;
    struct bsigaction a = { segv_log, 0, 4 /* SA_SIGINFO */ | 0x80000000u /* SA_RESETHAND */, 0 };
    sa(11, &a, 0);
    sa(7, &a, 0);
}
#else
static void crash_logger_install(void) {}
#endif
static void surf_free(struct esurf *s) {
    if (s->ss) { SSF(EGLBoolean, DestroySurface, (EGLDisplay, EGLSurface)); if (DestroySurface) DestroySurface(s->dpy, s->ss); }
    if (s->win) {
        if (s->buf) w_cancel(s->win, s->buf);
        s->win->common.decRef(&s->win->common);
    }
    if (s->ewin) s->ewin->decRef(s->ewin);
    free(s->tmp); free(s);
}
static void surf_unref(struct esurf *s) { if (s && --s->current <= 0 && s->doomed) surf_free(s); }
EXPORT EGLBoolean eglDestroySurface(EGLDisplay d, EGLSurface surface) {
    struct esurf *s = surface;
    (void)d;
    if (!s) { t_err = EGL_BAD_SURFACE; return 0; }
    struct ts *T = t_ts;
    if (T && T->ctx && T->ctx->bridged && s->ready) { begin(T, OP_SURF_DESTROY, 4, 4); put32(T, s->key); }
    if (s->current > 0) s->doomed = 1; else surf_free(s);
    return 1;
}
static void win_dequeue(struct esurf *s) {
    int fence = -1;
    if (s->ewin) { s->w = s->ewin->width; s->h = s->ewin->height; return; }      /* (1.x: the window is always its own buffer) */
    if (s->buf) return;
    if (!s->ready) {
        /* (before 4.2: SW_READ_OFTEN | SW_WRITE_OFTEN, what AOSP's libagl asks for; the compositor adds its own texture usage. HW_RENDER / HW_COMPOSER would send a vendor
         * gralloc to pmem or the GPU's own memory, which the bridge can neither lock nor fill) */
        s->win->perform(s->win, 0 /* NATIVE_WINDOW_SET_USAGE */, win_new_abi(s->win) ? 0x933 : 0x33);
        s->ready = 1;
    }
    s->buf = 0;
    /* (a window can report success and still hand back no buffer, e.g. when its gralloc allocation failed: treat that as a failed dequeue,
     * every caller already copes with s->buf == 0 - dereferencing it killed SurfaceFlinger with SIGSEGV at address 0x20) */
    if (w_dequeue(s->win, &s->buf, &fence) != 0 || !s->buf) {
        if (fence >= 0) close(fence);
        s->buf = 0;
        return;
    }
    if (fence >= 0) {
        static int (*pl)(struct pollfd *, unsigned long, int);
        static int looked;
        struct pollfd p = { fence, POLLIN, 0 };
        if (!looked) { pl = (int (*)(struct pollfd *, unsigned long, int))libc_sym("poll"); looked = 1; }
        if (pl) pl(&p, 1, 1000);
        close(fence);
    }
    s->w = s->buf->width; s->h = s->buf->height;
}
EXPORT EGLBoolean eglQuerySurface(EGLDisplay d, EGLSurface surface, EGLint attr, EGLint *value) {
    struct esurf *s = surface;
    if (!s || !value) { t_err = EGL_BAD_SURFACE; return 0; }
    if (s->ss) { SSF(EGLBoolean, QuerySurface, (EGLDisplay, EGLSurface, EGLint, EGLint *)); return QuerySurface(d, s->ss, attr, value); }
    switch (attr) {
    case EGL_WIDTH: case EGL_HEIGHT:
        if (s->ewin) *value = attr == EGL_WIDTH ? s->ewin->width : s->ewin->height;
        else if (s->kind == S_WINDOW && !s->buf) { int v = 0; s->win->query(s->win, attr == EGL_WIDTH ? 0 : 1, &v); *value = v; }
        else *value = attr == EGL_WIDTH ? s->w : s->h;
        return 1;
    case 0x3086: *value = 0x3084; return 1;     /* EGL_RENDER_BUFFER: EGL_BACK_BUFFER */
    case 0x3093: *value = 0x3095; return 1;     /* EGL_SWAP_BEHAVIOR: EGL_BUFFER_DESTROYED */
    case 0x3028: return eglGetConfigAttrib(d, s->cfg, 0x3028, value);   /* EGL_CONFIG_ID */
    }
    *value = 0;
    return 1;
}
EXPORT EGLBoolean eglSurfaceAttrib(EGLDisplay d, EGLSurface surface, EGLint attr, EGLint value) {
    struct esurf *s = surface;
    if (s && s->ss) { SSF(EGLBoolean, SurfaceAttrib, (EGLDisplay, EGLSurface, EGLint, EGLint)); return SurfaceAttrib(d, s->ss, attr, value); }
    return 1;
}
EXPORT EGLBoolean eglBindTexImage(EGLDisplay d, EGLSurface s, EGLint b) { (void)d; (void)s; (void)b; t_err = EGL_BAD_SURFACE; return 0; }
EXPORT EGLBoolean eglReleaseTexImage(EGLDisplay d, EGLSurface s, EGLint b) { (void)d; (void)s; (void)b; t_err = EGL_BAD_SURFACE; return 0; }
EXPORT EGLBoolean eglCopyBuffers(EGLDisplay d, EGLSurface s, void *target) { (void)d; (void)s; (void)target; t_err = EGL_BAD_SURFACE; return 0; }

/* ---- contexts ---- */
EXPORT EGLContext eglCreateContext(EGLDisplay d, EGLConfig cfg, EGLContext share, const EGLint *attribs) {
    struct ectx *sh = share, *c = calloc(1, sizeof(*c));
    if (!c) { t_err = EGL_BAD_ALLOC; return 0; }
    c->version = attr_of(attribs, 0x3098 /* EGL_CONTEXT_CLIENT_VERSION */, 1);
    c->dpy = d; c->cfg = cfg;
    c->g.p_align = c->g.u_align = 4;
    if (sh ? sh->bridged : (solo() || policy(c->version))) {
        struct ts *T = ts_get();
        if (T) {
            begin(T, OP_CTX_CREATE, 8, 8); put32(T, sh ? sh->host : 0); put32(T, (uint32_t)c->version);
            rsync(T);
            c->host = get32v(T);
            c->g.root = sh ? sh->g.root : c->host;
            if (!c->host && sh) {
                /* The phone's driver refused a context that shares with another. SPen (S Note, Quick Memo, Flash Annotate) answers
                 * a refused window context with an eglMakeCurrent on nothing and then reads GL_RENDERER from no context: the program
                 * dies. A context of its own draws (its objects are its own, not the group's) and the program lives. */
                LOG("the host refused a GLES %d context sharing with %u: a context of its own", c->version, sh->host);
                begin(T, OP_CTX_CREATE, 8, 8); put32(T, 0); put32(T, (uint32_t)c->version);
                rsync(T);
                c->host = get32v(T);
                c->g.root = c->host;
            }
        }
        if (c->host) {
            char name[17] = "";
            thread_name(name);
            c->bridged = 1;
            LOG("a GLES %d context on the phone's GPU (%u), thread %s", c->version, c->host, name);
        crash_logger_install();
            return c;
        }
        if (sh) { free(c); t_err = EGL_BAD_ALLOC; return 0; }
        if (solo()) { free(c); t_err = EGL_BAD_ALLOC; LOG("the host gave no context and there is no software renderer"); return 0; }
        LOG("the host gave no context: SwiftShader");
    }
    SSF(EGLContext, CreateContext, (EGLDisplay, EGLConfig, EGLContext, const EGLint *));
    /* (SwiftShader may not know the robustness attributes the extension list offers: left out for it) */
    EGLint own[64]; int k = 0;
    for (int i = 0; attribs && attribs[i] != EGL_NONE && k < 60; i += 2)
        if (attribs[i] != 0x3138 /* RESET_NOTIFICATION_STRATEGY */ && attribs[i] != 0x30BF /* ROBUST_ACCESS */) { own[k++] = attribs[i]; own[k++] = attribs[i + 1]; }
    own[k] = EGL_NONE;
    c->ss = CreateContext ? CreateContext(d, cfg, sh ? sh->ss : 0, attribs ? own : 0) : 0;
    if (!c->ss) { free(c); return 0; }
    return c;
}
static void ctx_free(struct ectx *c) {
    if (c->bridged) {
        struct ts *T = ts_get();
        if (T) { begin(T, OP_CTX_DESTROY, 4, 4); put32(T, c->host); flush(T); }
        if (c->g.root == c->host) sh_drop_all(c->host);
        for (int i = 0; i < 5; i++) free(c->g.str[i]);
        for (int i = 0; i < c->g.next; i++) free(c->g.ext[i]);
        free(c->g.ext);
        for (int i = 0; i < 8; i++) free(c->g.maps[i].ptr);
        free(c->g1);
    } else { SSF(EGLBoolean, DestroyContext, (EGLDisplay, EGLContext)); if (DestroyContext) DestroyContext(c->dpy, c->ss); }
    free(c);
}
EXPORT EGLBoolean eglDestroyContext(EGLDisplay d, EGLContext context) {
    struct ectx *c = context;
    (void)d;
    if (!c) { t_err = EGL_BAD_CONTEXT; return 0; }
    if (c->current > 0) c->current |= 0x10000;      /* freed when it is released */
    else ctx_free(c);
    return 1;
}
EXPORT EGLBoolean eglQueryContext(EGLDisplay d, EGLContext context, EGLint attr, EGLint *value) {
    struct ectx *c = context;
    if (!c || !value) { t_err = EGL_BAD_CONTEXT; return 0; }
    if (!c->bridged) { SSF(EGLBoolean, QueryContext, (EGLDisplay, EGLContext, EGLint, EGLint *)); return QueryContext(d, c->ss, attr, value); }
    switch (attr) {
    case 0x3098: *value = c->version; return 1;
    case 0x3097: *value = 0x30A0; return 1;     /* EGL_CONTEXT_CLIENT_TYPE: EGL_OPENGL_ES_API */
    case 0x3086: *value = 0x3084; return 1;
    case 0x3028: return eglGetConfigAttrib(d, c->cfg, 0x3028, value);
    }
    *value = 0;
    return 1;
}

static __thread struct ectx *t_ss_ctx;      /* the SwiftShader context current on this thread */
static __thread struct esurf *t_ss_draw, *t_ss_read;
static void ss_release(EGLDisplay d) {
    if (!t_ss_ctx) return;
    SSF(EGLBoolean, MakeCurrent, (EGLDisplay, EGLSurface, EGLSurface, EGLContext));
    if (MakeCurrent) MakeCurrent(d, 0, 0, 0);
    struct ectx *c = t_ss_ctx;
    t_ss_ctx = 0;
    surf_unref(t_ss_draw); if (t_ss_read != t_ss_draw) surf_unref(t_ss_read);
    t_ss_draw = t_ss_read = 0;
    if (--c->current == 0x10000) ctx_free(c);
}
static void bridge_release(void) {
    struct ts *T = t_ts;
    if (!T || !T->ctx) return;
    begin(T, OP_RELEASE, 0, 0);
    rsync(T); get32v(T);
    struct ectx *c = T->ctx;
    T->ctx = 0;
    surf_unref(T->draw); if (T->read != T->draw) surf_unref(T->read);
    T->draw = T->read = 0;
    if (--c->current == 0x10000) ctx_free(c);
}
EXPORT EGLBoolean eglMakeCurrent(EGLDisplay d, EGLSurface draw, EGLSurface read, EGLContext context) {
    struct ectx *c = context;
    struct esurf *sd = draw, *sr = read;
    if (!c) {
        bridge_release(); ss_release(d);
        t_tbl = 0;
        return 1;
    }
    if (c->bridged) {
        struct ts *T = ts_get();
        if (!T) { t_err = EGL_BAD_CONTEXT; return 0; }
        ss_release(d);
        if (T->ctx == c && T->draw == sd && T->read == sr) return 1;
        if (sd && sd->kind == S_WINDOW) {
            if (sd->ss) { t_err = EGL_BAD_MATCH; LOG("a window of a SwiftShader context given to a GPU context"); return 0; }
            win_dequeue(sd);
        }
        struct ectx *old = T->ctx;
        struct esurf *od = T->draw, *orr = T->read;
        EGLint samples = 0;
        if (sd && !eglGetConfigAttrib(d, sd->cfg, 0x3031 /* EGL_SAMPLES */, &samples)) samples = 0;
        begin(T, OP_MAKE_CURRENT, 20, 20);
        put32(T, c->host); put32(T, sd ? sd->key : 0); put32(T, sd ? (uint32_t)sd->w : 0); put32(T, sd ? (uint32_t)sd->h : 0);
        put32(T, (uint32_t)(samples > 0 ? samples : 0));
        rsync(T);
        if (!get32v(T)) { t_err = EGL_BAD_CONTEXT; LOG("the host did not make context %u current", c->host); return 0; }
        c->current++; if (sd) sd->current++; if (sr && sr != sd) sr->current++;
        T->ctx = c; T->draw = sd; T->read = sr;
        if (old) {
            surf_unref(od); if (orr != od) surf_unref(orr);
            if (--old->current == 0x10000) ctx_free(old);
        }
        t_tbl = c->version == 1 ? SS.enc1 : SS.enc;
        return 1;
    }
    bridge_release();
    SSF(EGLBoolean, MakeCurrent, (EGLDisplay, EGLSurface, EGLSurface, EGLContext));
    void *a = ss_surface(sd), *b = sr == sd ? a : ss_surface(sr);
    if ((sd && !a) || (sr && !b)) { t_err = EGL_BAD_SURFACE; return 0; }
    if (!MakeCurrent || !MakeCurrent(d, a, b, c->ss)) return 0;
    c->current++; if (sd) sd->current++; if (sr && sr != sd) sr->current++;
    struct ectx *old = t_ss_ctx;
    struct esurf *od = t_ss_draw, *orr = t_ss_read;
    t_ss_ctx = c; t_ss_draw = sd; t_ss_read = sr;
    if (old) {
        surf_unref(od); if (orr != od) surf_unref(orr);
        if (--old->current == 0x10000) ctx_free(old);
    }
    t_tbl = c->version == 1 && SS.v1 ? SS.t1 : SS.t2;
    return 1;
}
EXPORT EGLContext eglGetCurrentContext(void) { return t_ts && t_ts->ctx ? (void *)t_ts->ctx : (void *)t_ss_ctx; }
EXPORT EGLSurface eglGetCurrentSurface(EGLint which) {
    if (t_ts && t_ts->ctx) return which == 0x305A ? t_ts->read : t_ts->draw;
    return which == 0x305A ? t_ss_read : t_ss_draw;
}
EXPORT EGLDisplay eglGetCurrentDisplay(void) {
    struct ectx *c = eglGetCurrentContext();
    return c ? c->dpy : 0;
}
EXPORT EGLint eglGetError(void) {
    if (t_err) { EGLint e = t_err; t_err = 0; return e; }
    SSF(EGLint, GetError, (void));
    return GetError ? GetError() : EGL_SUCCESS;
}
EXPORT EGLBoolean eglReleaseThread(void) {
    bridge_release();
    if (t_ss_ctx) ss_release(t_ss_ctx->dpy);
    t_tbl = 0;
    SSF(EGLBoolean, ReleaseThread, (void));
    return ReleaseThread ? ReleaseThread() : 1;
}
EXPORT EGLBoolean eglWaitGL(void) {
    if (t_ts && t_ts->ctx) { e_glFinish(); return 1; }
    SSF(EGLBoolean, WaitGL, (void));
    return WaitGL ? WaitGL() : 1;
}
EXPORT EGLBoolean eglWaitClient(void) { return eglWaitGL(); }

/* ---- the layout of the framebuffer's own memory ----
 * A window buffer with GRALLOC_USAGE_HW_FB (0x1000) lives in fb0's memory and has fb0's pixel layout, whatever format the window asked for
 * (the fbdev gralloc of an x86 firmware). Read once from fb0 (FBIOGET_VSCREENINFO): bits per pixel and where each channel sits. */
struct fbfmt { int ok, bpp, ro, rl, go, gl, bo, bl, ao, al; };
static struct fbfmt g_fb;
static const struct fbfmt *fb_layout(void) {
    static volatile int tried;
    if (!tried) {
        tried = 1;
        uint32_t v[40];
        static const char *const dev[] = { "/dev/graphics/fb0", "/dev/fb0" };
        for (unsigned i = 0; i < 2 && !g_fb.ok; i++) {
            int fd = open(dev[i], O_RDONLY | O_CLOEXEC);
            if (fd < 0) continue;
            memset(v, 0, sizeof(v));
            if (ioctl(fd, 0x4600 /* FBIOGET_VSCREENINFO */, v) == 0 && (v[6] == 16 || v[6] == 24 || v[6] == 32)) {
                g_fb.bpp = (int)v[6];
                g_fb.ro = (int)v[8]; g_fb.rl = (int)v[9]; g_fb.go = (int)v[11]; g_fb.gl = (int)v[12];
                g_fb.bo = (int)v[14]; g_fb.bl = (int)v[15]; g_fb.ao = (int)v[17]; g_fb.al = (int)v[18];
                /* a driver that left the channels empty: the usual layouts */
                if (!g_fb.rl || !g_fb.gl || !g_fb.bl) {
                    if (g_fb.bpp == 16) { g_fb.ro = 11; g_fb.rl = 5; g_fb.go = 5; g_fb.gl = 6; g_fb.bo = 0; g_fb.bl = 5; g_fb.ao = g_fb.al = 0; }
                    else { g_fb.ro = 0; g_fb.rl = 8; g_fb.go = 8; g_fb.gl = 8; g_fb.bo = 16; g_fb.bl = 8; g_fb.ao = 24; g_fb.al = g_fb.bpp == 32 ? 8 : 0; }
                }
                g_fb.ok = 1;
                LOG("fb0: %d bpp, r %d:%d g %d:%d b %d:%d a %d:%d", g_fb.bpp, g_fb.ro, g_fb.rl, g_fb.go, g_fb.gl, g_fb.bo, g_fb.bl, g_fb.ao, g_fb.al);
            }
            close(fd);
        }
        if (!g_fb.ok) LOG("fb0: layout not readable, a framebuffer window buffer is taken as RGB_565");
    }
    return g_fb.ok ? &g_fb : 0;
}
/* one row of RGBA (the host's readback) into the memory of a buffer of this layout */
static void fb_row(uint8_t *dst, const uint8_t *src, uint32_t w, const struct fbfmt *f) {
    for (uint32_t x = 0; x < w; x++, src += 4) {
        uint32_t v = (uint32_t)(src[0] >> (8 - f->rl)) << f->ro | (uint32_t)(src[1] >> (8 - f->gl)) << f->go | (uint32_t)(src[2] >> (8 - f->bl)) << f->bo;
        if (f->al) v |= (uint32_t)(src[3] >> (8 - f->al)) << f->ao;
        if (f->bpp == 16) ((uint16_t *)dst)[x] = (uint16_t)v;
        else if (f->bpp == 24) { dst[x * 3] = (uint8_t)v; dst[x * 3 + 1] = (uint8_t)(v >> 8); dst[x * 3 + 2] = (uint8_t)(v >> 16); }
        else ((uint32_t *)dst)[x] = v;
    }
}

/* The layout a pixel format (HAL_PIXEL_FORMAT_* = PixelFormat of 1.x) names; 0: one the bridge does not know */
static const struct fbfmt *fmt_layout(int format, struct fbfmt *own) {
    *own = (struct fbfmt){ 1, 32, 0, 8, 8, 8, 16, 8, 24, 8 };       /* RGBA_8888 */
    switch (format) {
    case 1: return own;                                                                       /* RGBA_8888 */
    case 2: own->al = 0; return own;                                                          /* RGBX_8888 */
    case 5: own->ro = 16; own->bo = 0; return own;                                            /* BGRA_8888 */
    case 3: own->bpp = 24; own->al = 0; return own;                                           /* RGB_888 */
    case 4: *own = (struct fbfmt){ 1, 16, 11, 5, 5, 6, 0, 5, 0, 0 }; return own;             /* RGB_565 */
    }
    return 0;
}

/* What a window buffer's memory looks like: a framebuffer buffer has fb0's layout (see fb_layout), any other the layout its HAL
 * format names. 0: a format the bridge does not know. */
static const struct fbfmt *buf_layout(const struct ANativeWindowBuffer *b, struct fbfmt *own, int *hw_fb) {
    const struct fbfmt *lay = 0;
    *hw_fb = (b->usage_deprecated & 0x1000) != 0;
    if (*hw_fb) lay = fb_layout();
    if (!lay) {
        lay = fmt_layout(b->format, own);
        if (!lay && *hw_fb) { *own = (struct fbfmt){ 1, 16, 11, 5, 5, 6, 0, 5, 0, 0 }; lay = own; }     /* (fb0 unreadable: 16 bit, as before) */
    }
    return lay;
}

/* Android 1.0 - 1.6: the frame goes into the window's own pixels (base + offset), then the window posts / flips (see egl_native_window_t) */
static EGLBoolean bridge_swap_egl(struct ts *T, struct esurf *s) {
    struct egl_native_window_t *e = s->ewin;
    uint32_t w = e->width > 0 ? (uint32_t)e->width : 0, h = e->height > 0 ? (uint32_t)e->height : 0;
    struct fbfmt own;
    const struct fbfmt *lay = e->memory_type == 2 ? fb_layout() : 0;                 /* NATIVE_MEMORY_TYPE_FB: fb0's own layout */
    if (!lay) lay = fmt_layout(e->format, &own);
    if (!lay && e->memory_type == 2) { own = (struct fbfmt){ 1, 16, 11, 5, 5, 6, 0, 5, 0, 0 }; lay = &own; }
    uint8_t *va = e->base ? (uint8_t *)(e->base + e->offset) : 0;
    {   static int said;
        if (said < 3) { said++; LOG("1.x window %ux%u stride %d format %d memory %d -> %d bpp", w, h, e->stride, e->format, e->memory_type, lay ? lay->bpp : 0); }
    }
    if (w && h) {
        if (!lay) { static int said; if (said++ < 5) LOG("a frame is dropped: window format %d has no layout the bridge knows", e->format); }
        if (!va) { static int said; if (said++ < 5) LOG("a frame is dropped: the window has no pixels"); }
        uint32_t need = w * h * 4;
        if (lay && (T->caps & AEMU_CAP_SWAP_FMT)) {
            uint32_t px = lay->bpp / 8, stride = e->stride >= (int)w ? (uint32_t)e->stride : w, pitch = stride * px;
            uint32_t total = pitch * (h - 1) + w * px;
            begin(T, OP_SWAP_FMT, 48, 48);
            put32(T, w); put32(T, h); put32(T, (uint32_t)lay->bpp); put32(T, pitch);
            put32(T, (uint32_t)lay->ro); put32(T, (uint32_t)lay->rl); put32(T, (uint32_t)lay->go); put32(T, (uint32_t)lay->gl);
            put32(T, (uint32_t)lay->bo); put32(T, (uint32_t)lay->bl); put32(T, (uint32_t)lay->ao); put32(T, (uint32_t)lay->al);
            rsync(T);
            if (va) rd_all(T, va, total); else get(T, 0, total);
        } else {
            if (s->tmp_n < need) { free(s->tmp); s->tmp = malloc(need); s->tmp_n = s->tmp ? need : 0; }
            begin(T, OP_SWAP, 8, 8); put32(T, w); put32(T, h);
            rsync(T);
            get(T, s->tmp, need);
            if (va && lay && s->tmp) {
                uint32_t stride = e->stride >= (int)w ? (uint32_t)e->stride : w;
                for (uint32_t y = 0; y < h; y++) fb_row(va + (size_t)y * stride * (uint32_t)(lay->bpp / 8), s->tmp + (size_t)(h - 1 - y) * w * 4, w, lay);
            }
        }
    }
    uint32_t flags = e->swapBuffers(e);
    if ((flags & EGL_NATIVES_FLAG_SIZE_CHANGED) || (uint32_t)e->width != w || (uint32_t)e->height != h) {
        s->w = e->width; s->h = e->height;
        begin(T, OP_SURF_SIZE, 8, 8); put32(T, (uint32_t)s->w); put32(T, (uint32_t)s->h);
    }
    return 1;
}

/* The frame of a bridged context goes into the window's buffer. A buffer of the engine's gralloc stays on the host (the frame is
 * copied into the host's buffer of the same number); any other is filled from a read-back: by the host in the buffer's own layout, top
 * row first, straight into the memory (the host's CPU does what a translated guest's would take milliseconds for), or - a host
 * that does not offer that - from RGBA, bottom row first, converted here. */
/* how much of a block is not zero (every 4th byte looked at): the log says where a frame turns black */
static unsigned nz_pct(const uint8_t *p, size_t n) {
    if (!p || !n) return 0;
    size_t hit = 0, seen = 0;
    for (size_t i = 0; i < n; i += 4, seen++) if (p[i] | p[i + 1 < n ? i + 1 : i]) hit++;
    return (unsigned)(hit * 100 / (seen ? seen : 1));
}
static EGLBoolean bridge_swap(struct ts *T, struct esurf *s) {
    if (s->kind != S_WINDOW) return 1;
    if (s->ewin) return bridge_swap_egl(T, s);
    win_dequeue(s);
    if (!s->buf) { t_err = EGL_BAD_SURFACE; return 0; }
    struct ANativeWindowBuffer *b = s->buf;
    uint32_t w = (uint32_t)b->width, h = (uint32_t)b->height, need = w * h * 4;
    /* A buffer of the engine's gralloc stays on the host: the frame is copied into the host's buffer of the same
     * number, and the note tells gralloc to fetch it if a program opens the memory (grallocwrap). */
    struct meta_note *note = meta_note_at(b->handle);
    /* (a framebuffer buffer - 6.0's compositor posting to fb0 - is read back: the screen shows fb0's memory) */
    if (note && note->magic == META_NOTE && note->id && !(note->usage & 0x1000 /* GRALLOC_USAGE_HW_FB */) &&
        (b->format == 1 || b->format == 2 || b->format == 4)) {
        uint64_t frame = ++note->pad[1];
        begin(T, OP_SWAP_BUF, 32, 32); put32(T, w); put32(T, h); put64(T, note->id); put64(T, note->pad[0]); put64(T, frame);
        flush(T);
        s->buf = 0;
        w_queue(s->win, b);
        win_dequeue(s);
        if (s->buf && ((uint32_t)s->w != w || (uint32_t)s->h != h)) { begin(T, OP_SURF_SIZE, 8, 8); put32(T, (uint32_t)s->w); put32(T, (uint32_t)s->h); }
        return 1;
    }
    struct fbfmt own;
    int hw_fb = 0;
    const struct fbfmt *lay = buf_layout(b, &own, &hw_fb);
    {   static int said;
        if (said < 3) { said++; LOG("window buffer %dx%d stride %d format %d usage 0x%x -> %d bpp%s%s", b->width, b->height, b->stride, b->format, b->usage_deprecated, lay ? lay->bpp : 0, hw_fb ? " (framebuffer)" : "", (T->caps & AEMU_CAP_SWAP_FMT) ? ", converted by the host" : ""); }
    }
    if (!lay) { static int said; if (said++ < 5) LOG("a frame is dropped: window buffer format %d has no layout the bridge knows", b->format); }
    if (lay && (T->caps & AEMU_CAP_SWAP_FMT)) {
        uint32_t px = lay->bpp / 8, stride = b->stride >= (int)w ? (uint32_t)b->stride : w, pitch = stride * px;
        uint32_t total = pitch * (h - 1) + w * px;
        uint8_t *va = buf_lock(b, 1);
        int swap_log = 0;
        if (!va) { static int said; if (said++ < 5) LOG("a frame is dropped: the gralloc would not lock the window buffer (usage 0x%x)", b->usage_deprecated); }
        {   static __thread unsigned n, dropped;
            n++; if (!va) dropped++;
            swap_log = (n == 1 || n == 10 || n == 60 || n == 300 || n % 1500 == 0);
            if (swap_log) { char nm[17] = ""; thread_name(nm); LOG("swaps of thread %s: %u, %u without pixels (%dx%d usage 0x%x)", nm, n, dropped, b->width, b->height, b->usage_deprecated); }
        }
        begin(T, OP_SWAP_FMT, 48, 48);
        put32(T, w); put32(T, h); put32(T, (uint32_t)lay->bpp); put32(T, pitch);
        put32(T, (uint32_t)lay->ro); put32(T, (uint32_t)lay->rl); put32(T, (uint32_t)lay->go); put32(T, (uint32_t)lay->gl);
        put32(T, (uint32_t)lay->bo); put32(T, (uint32_t)lay->bl); put32(T, (uint32_t)lay->ao); put32(T, (uint32_t)lay->al);
        rsync(T);
        if (va) { rd_all(T, va, total); if (swap_log) { char nm[17] = ""; thread_name(nm); LOG("  frame of thread %s read back from the host: %u%% not zero", nm, nz_pct(va, total)); } buf_unlock(b); }
        else get(T, 0, total);                     /* (the frame has to be taken off the socket either way) */
    } else {
        if (s->tmp_n < need) { free(s->tmp); s->tmp = malloc(need); s->tmp_n = s->tmp ? need : 0; }
        begin(T, OP_SWAP, 8, 8); put32(T, w); put32(T, h);
        rsync(T);
        get(T, s->tmp, need);
        uint8_t *va = s->tmp ? buf_lock(b, 1) : 0;
        if (!va && lay) { static int said; if (said++ < 5) LOG("a frame is dropped: the gralloc would not lock the window buffer (usage 0x%x)", b->usage_deprecated); }
        if (va) {
            if (lay) for (uint32_t y = 0; y < h; y++)
                fb_row(va + (size_t)y * (uint32_t)b->stride * (uint32_t)(lay->bpp / 8), s->tmp + (size_t)(h - 1 - y) * w * 4, w, lay);
            buf_unlock(b);
        }
    }
    s->buf = 0;
    w_queue(s->win, b);
    win_dequeue(s);
    if (s->buf && ((uint32_t)s->w != w || (uint32_t)s->h != h)) { begin(T, OP_SURF_SIZE, 8, 8); put32(T, (uint32_t)s->w); put32(T, (uint32_t)s->h); }
    return 1;
}
/* A bridged context's window is queued by this driver: the interval goes to the window itself (0: the program does
 * not wait for the display) */
EXPORT EGLBoolean eglSwapInterval(EGLDisplay d, EGLint interval) {
    struct ts *T = t_ts;
    if (T && T->ctx) {
        struct esurf *s = T->draw;
        if (s && s->kind == S_WINDOW && s->win && s->win->setSwapInterval) s->win->setSwapInterval(s->win, interval < 0 ? 0 : interval);
        return 1;
    }
    SSF(EGLBoolean, SwapInterval, (EGLDisplay, EGLint));
    return SwapInterval ? SwapInterval(d, interval) : 1;
}
EXPORT EGLBoolean eglSwapBuffers(EGLDisplay d, EGLSurface surface) {
    struct esurf *s = surface;
    if (!s) { t_err = EGL_BAD_SURFACE; return 0; }
    if (t_ts && t_ts->ctx && t_ts->draw == s) return bridge_swap(t_ts, s);
    if (!s->ss) return 1;
    SSF(EGLBoolean, SwapBuffers, (EGLDisplay, EGLSurface));
    return SwapBuffers(d, s->ss);
}
EXPORT EGLBoolean eglSwapBuffersWithDamageKHR(EGLDisplay d, EGLSurface s, EGLint *rects, EGLint n) { (void)rects; (void)n; return eglSwapBuffers(d, s); }
EXPORT EGLBoolean eglSetDamageRegionKHR(EGLDisplay d, EGLSurface s, EGLint *rects, EGLint n) { (void)d; (void)s; (void)rects; (void)n; return 1; }

/* ---- YUV buffers: converted to RGBA on the guest side, then carried like any RGBA buffer ----
 * The host GPU bridge only knows RGBA/RGBX/RGB888/RGB565 (host.c buf_fill), so a video decoder's NV21 / YV12 buffer
 * is turned into a tight RGBA block here and goes out through the existing OP_IMAGE_DATA / OP_IMAGE_TEX paths.
 * The layouts are the ones the engine's gralloc gives (gralloc5/mapper.c planes(), grallocwrap/gralloc_wrap.c):
 *   0x11 / 0x23  NV21 in one block: Y (stride = width), then V and U interleaved, no row padding
 *   0x32315659   YV12: Y, V, U; the Y stride aligned to 16, the chroma stride to 16 of half that */
#define YUV_NV21 1
#define YUV_YV12 2
static int yuv_kind(uint32_t f) { return f == 0x11 || f == 0x23 ? YUV_NV21 : f == 0x32315659 ? YUV_YV12 : 0; }
static inline uint8_t yuv_clamp(int v) { return (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v); }
/* The buffer's format: its own, else what the note says (the note is the gralloc's truth for the buffer) */
static int yuv_kind_of(const struct ANativeWindowBuffer *b, const struct meta_note *note) {
    int k = yuv_kind((uint32_t)b->format);
    if (!k && note && note->magic == META_NOTE) k = yuv_kind((uint32_t)note->format);
    return k;
}
/* Returns w*h*4 bytes of RGBA (top row first, alpha 255, malloc'd), or 0: not a YUV buffer this converts, or its
 * memory cannot be read. BT.709 video range, integer math (BT.601 when the note's dataspace says so). */
static uint8_t *yuv_take(struct ANativeWindowBuffer *b, const struct meta_note *note) {
    static int said;
    int kind = yuv_kind_of(b, note);
    if (!kind || b->width <= 0 || b->height <= 0 || b->width > 8192 || b->height > 8192) return 0;
    uint32_t w = (uint32_t)b->width, h = (uint32_t)b->height, cw = (w + 1) / 2, ch = (h + 1) / 2;
    int have = note && note->magic == META_NOTE;
    uint32_t ys = w, cs = 0, stride = have && note->stride > 0 ? (uint32_t)note->stride : b->stride > 0 ? (uint32_t)b->stride : 0;
    uint64_t need;
    if (kind == YUV_YV12) {
        ys = stride >= w ? stride : (w + 15) & ~15u;
        cs = (ys / 2 + 15) & ~15u;
        need = (uint64_t)ys * h + 2 * (uint64_t)cs * ch;
    } else need = (uint64_t)w * h + 2 * (uint64_t)cw * ch;
    uint8_t *out = malloc((size_t)w * h * 4);
    if (!out) return 0;
    /* (a buffer that refuses a plain lock is read through the note's base, without a fence) */
    uint8_t *va = buf_lock(b, 0);
    int locked = va != 0;
    if (!va) {
        const struct rgb_note_handle *p = note_handle_of(b->handle);
        if (p && p->base && p->size > 0 && (uint64_t)p->size >= need) va = (uint8_t *)(uintptr_t)p->base;
        if (va && said < 5) LOG("a YUV buffer would not lock: read through the note's base");
    }
    if (!va) { free(out); return 0; }
    /* BT.709 by default; BT.601 for the dataspace standards 2 and 3 (BT601_625, BT601_525) */
    uint32_t std = have ? ((uint32_t)note->dataspace >> 16) & 0x3f : 0;
    int crv = 459, cgu = 55, cgv = 136, cbu = 541;
    if (std == 2 || std == 3) { crv = 409; cgu = 100; cgv = 208; cbu = 516; }
    const uint8_t *Y = va, *UV = va + (size_t)w * h, *V = va + (size_t)ys * h, *U = V + (size_t)cs * ch;
    for (uint32_t y = 0; y < h; y++) {
        const uint8_t *yr = Y + (size_t)y * ys;
        const uint8_t *uv = UV + (size_t)(y >> 1) * 2 * cw, *vr = V + (size_t)(y >> 1) * cs, *ur = U + (size_t)(y >> 1) * cs;
        uint8_t *d = out + (size_t)y * w * 4;
        for (uint32_t x = 0; x < w; x++, d += 4) {
            int v, u, c = (yr[x] - 16) * 298;
            if (kind == YUV_NV21) { v = uv[(x >> 1) * 2]; u = uv[(x >> 1) * 2 + 1]; }
            else { v = vr[x >> 1]; u = ur[x >> 1]; }
            u -= 128; v -= 128;
            d[0] = yuv_clamp((c + crv * v + 128) >> 8);
            d[1] = yuv_clamp((c - cgu * u - cgv * v + 128) >> 8);
            d[2] = yuv_clamp((c + cbu * u + 128) >> 8);
            d[3] = 255;
        }
    }
    if (locked) buf_unlock(b);
    if (said < 5) { said++; LOG("a %ux%u YUV frame (format 0x%x, stride %u): converted for the phone's GPU", w, h, (unsigned)b->format, ys); }
    return out;
}

/* ---- images: SwiftShader's own, and for each the buffer it was made of (a bridged context uploads its pixels) ---- */
/* host: an image of a bridged context's texture; own: a handle of ours that only carries the buffer to the upload */
static struct { void *img; struct ANativeWindowBuffer *buf; uint32_t host; int own; } g_img[256];
/* An image that is only the buffer: what a YUV buffer gets when SwiftShader (or a bridged context) cannot make one */
static EGLImageKHR own_image(struct ANativeWindowBuffer *b) {
    void *h = b ? malloc(8) : 0;
    int ok = 0;
    if (!h) { t_err = EGL_BAD_ALLOC; return 0; }
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < 256; i++) if (!g_img[i].img) { g_img[i].img = h; g_img[i].buf = b; g_img[i].host = 0; g_img[i].own = 1; ok = 1; break; }
    pthread_mutex_unlock(&g_lock);
    if (!ok) { free(h); t_err = EGL_BAD_ALLOC; return 0; }
    b->common.incRef(&b->common);
    return h;
}
EXPORT EGLImageKHR eglCreateImageKHR(EGLDisplay d, EGLContext context, EGLenum target, EGLClientBuffer buffer, const EGLint *attribs) {
    struct ectx *c = context;
    SSF(EGLImageKHR, CreateImageKHR, (EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLint *));
    {   static int said;
        if (said++ < 4) LOG("eglCreateImageKHR: target 0x%x, buffer %p, context %p (bridged %d), solo %d", (unsigned)target, buffer, context, c ? c->bridged : -1, solo());
    }
    /* (no software renderer: a native buffer is only carried to the upload, whatever context - or none - the call names;
     * RenderEngine makes its images with EGL_NO_CONTEXT) */
    if (solo() && target == 0x3140 /* EGL_NATIVE_BUFFER_ANDROID */ && buffer && !(c && !c->bridged)) return own_image(buffer);
    if (c && c->bridged) {
        /* a bridged context's texture: the image is the host's, and only bridged contexts can use it */
        struct ts *T = t_ts;
        void *h = 0;
        uint32_t id = 0;
        /* (a native buffer is only carried: its pixels go to the host when a texture is bound to the image) */
        if (target == 0x3140 /* EGL_NATIVE_BUFFER_ANDROID */ && buffer) return own_image(buffer);
        if (target != 0x30B1 /* EGL_GL_TEXTURE_2D_KHR */ || !T || !T->ctx) { t_err = EGL_BAD_MATCH; return 0; }
        begin(T, OP_IMAGE_CREATE, 8, 8); put32(T, (uint32_t)(uintptr_t)buffer); put32(T, (uint32_t)attr_of(attribs, 0x30BC, 0));
        rsync(T);
        id = get32v(T);
        if (id && (h = malloc(8))) {
            pthread_mutex_lock(&g_lock);
            for (int i = 0; i < 256; i++) if (!g_img[i].img) { g_img[i].img = h; g_img[i].buf = 0; g_img[i].host = id; g_img[i].own = 0; id = 0; break; }
            pthread_mutex_unlock(&g_lock);
        }
        if (!h || id) { free(h); t_err = EGL_BAD_ALLOC; return 0; }
        return h;
    }
    void *img = CreateImageKHR ? CreateImageKHR(d, c ? c->ss : 0, target, buffer, attribs) : 0;
    /* SwiftShader may decline a YUV buffer: the image is then ours, and only carries the buffer to the upload */
    if (!img && target == 0x3140 /* EGL_NATIVE_BUFFER_ANDROID */ && buffer &&
        yuv_kind_of(buffer, meta_note_at(((struct ANativeWindowBuffer *)buffer)->handle)))
        return own_image(buffer);
    if (img && target == 0x3140 /* EGL_NATIVE_BUFFER_ANDROID */) {
        struct ANativeWindowBuffer *b = buffer;
        pthread_mutex_lock(&g_lock);
        for (int i = 0; i < 256; i++) if (!g_img[i].img) { g_img[i].img = img; g_img[i].buf = b; g_img[i].host = 0; g_img[i].own = 0; b->common.incRef(&b->common); break; }
        pthread_mutex_unlock(&g_lock);
    }
    return img;
}
EXPORT EGLBoolean eglDestroyImageKHR(EGLDisplay d, EGLImageKHR img) {
    struct ANativeWindowBuffer *b = 0;
    pthread_mutex_lock(&g_lock);
    uint32_t host = 0;
    int own = 0;
    for (int i = 0; i < 256; i++) if (g_img[i].img == img) { b = g_img[i].buf; host = g_img[i].host; own = g_img[i].own; g_img[i].img = 0; g_img[i].buf = 0; g_img[i].host = 0; g_img[i].own = 0; break; }
    pthread_mutex_unlock(&g_lock);
    if (b) b->common.decRef(&b->common);
    if (own) { free(img); return 1; }     /* (ours: nothing of SwiftShader's behind it) */
    if (host) {
        struct ts *T = ts_get();
        if (T) { begin(T, OP_IMAGE_DESTROY, 4, 4); put32(T, host); }
        free(img);
        return 1;
    }
    SSF(EGLBoolean, DestroyImageKHR, (EGLDisplay, EGLImageKHR));
    return DestroyImageKHR ? DestroyImageKHR(d, img) : 0;
}
static void image_upload(GLenum target, GLeglImageOES image) {
    struct ts *T = TS();
    struct ANativeWindowBuffer *b = 0;
    pthread_mutex_lock(&g_lock);
    uint32_t host = 0;
    for (int i = 0; i < 256; i++) if (g_img[i].img == image) { b = g_img[i].buf; host = g_img[i].host; break; }
    pthread_mutex_unlock(&g_lock);
    if (host) { begin(T, OP_IMAGE_TARGET, 8, 8); put32(T, target); put32(T, host); return; }
    if (!b) { not_carried(); return; }
    uint32_t w = (uint32_t)b->width, h = (uint32_t)b->height;
    /* A buffer of the engine's gralloc has a number and a count of writes (grallocwrap/note.h): the host keeps a
     * buffer of its own under that number, shared by every context that binds it, and the pixels go there only
     * when the guest's memory changed - what a GPU context drew into it stays. */
    struct meta_note *note = meta_note_at(b->handle);
    int bpp = b->format == 1 || b->format == 2 ? 4 : b->format == 3 ? 3 : b->format == 4 ? 2 : 0;
    if (note && note->magic == META_NOTE && note->id) {
        uint64_t count = note->pad[0];
        begin(T, OP_IMAGE_BIND, 28, 28);
        put32(T, target); put64(T, note->id); put64(T, count); put32(T, w); put32(T, h);
        rsync(T);
        uint32_t wanted = get32v(T);
        /* (it may be drawn into from now on: gralloc asks the host before the memory is read) */
        if (target == 0x0DE1 && !note->pad[1]) note->pad[1] = 1;
        if (!wanted) return;
        if (!bpp && yuv_kind_of(b, note)) {     /* video: converted to RGBA, then as an RGBA buffer of the width's stride */
            uint8_t *rgba = yuv_take(b, note);
            if (!rgba) return;
            begin(T, OP_IMAGE_DATA, 36, 36 + BLOB(w * h * 4));
            put64(T, note->id); put64(T, count); put32(T, w); put32(T, h); put32(T, w); put32(T, 1 /* RGBA_8888 */);
            putblob(T, rgba, w * h * 4);
            free(rgba);
            return;
        }
        if (!bpp) return;
        uint8_t *px = buf_lock(b, 0);
        if (!px) return;
        uint32_t n = (uint32_t)b->stride * h * (uint32_t)bpp;
        begin(T, OP_IMAGE_DATA, 36, 36 + BLOB(n));
        put64(T, note->id); put64(T, count); put32(T, w); put32(T, h); put32(T, (uint32_t)b->stride); put32(T, (uint32_t)b->format);
        putblob(T, px, n);
        buf_unlock(b);
        return;
    }
    if (!bpp && yuv_kind_of(b, note)) {
        uint8_t *rgba = yuv_take(b, note);
        if (rgba) {
            begin(T, OP_IMAGE_TEX, 16, 16 + BLOB(w * h * 4));
            put32(T, target); put32(T, w); put32(T, h); putblob(T, rgba, w * h * 4);
            free(rgba);
        }
        return;
    }
    if (!bpp) { not_carried(); return; }
    uint8_t *va = buf_lock(b, 0), *tight = 0;
    {   static unsigned n, nopx;
        n++; if (!va) nopx++;
        if (n <= 6 || n == 50 || n == 500 || n % 5000 == 0) { char nm[17] = ""; thread_name(nm); LOG("texture upload %u in thread %s: %dx%d format %d stride %d usage 0x%x, %s (%u without pixels), %u%% not zero", n, nm, b->width, b->height, b->format, b->stride, b->usage_deprecated, va ? "pixels read" : "NO PIXELS", nopx, va ? nz_pct(va, (size_t)b->stride * (size_t)b->height * (size_t)(bpp ? bpp : 4)) : 0u); }
    }
    if (!va) return;
    if ((T->caps & AEMU_CAP_IMAGE_TEX2) && b->format >= 1 && b->format <= 4 && b->stride >= (int)w) {
        /* the rows as the buffer holds them (the pitch, the format): the host's GPU takes any of them, the guest repacks nothing */
        uint32_t n = ((uint32_t)(b->stride) * (h - 1) + w) * (uint32_t)bpp;
        begin(T, OP_IMAGE_TEX2, 24, 24 + BLOB(n));
        put32(T, target); put32(T, w); put32(T, h); put32(T, (uint32_t)b->stride); put32(T, (uint32_t)b->format);
        putblob(T, va, n);
        buf_unlock(b);
        return;
    }
    if (bpp != 4) {     /* RGB_565 / RGB_888 (a camera preview's window, say): widened to RGBA */
        if (!(tight = malloc((size_t)w * h * 4))) { buf_unlock(b); return; }
        for (uint32_t y = 0; y < h; y++) {
            const uint8_t *s = va + (size_t)y * (uint32_t)b->stride * bpp;
            uint8_t *d = tight + (size_t)y * w * 4;
            for (uint32_t x = 0; x < w; x++, d += 4) {
                if (bpp == 2) { uint16_t v = ((const uint16_t *)s)[x]; d[0] = (uint8_t)((v >> 11) << 3 | v >> 13); d[1] = (uint8_t)((v >> 5 & 63) << 2 | (v >> 9 & 3)); d[2] = (uint8_t)((v & 31) << 3 | (v >> 2 & 7)); }
                else { d[0] = s[x * 3]; d[1] = s[x * 3 + 1]; d[2] = s[x * 3 + 2]; }
                d[3] = 255;
            }
        }
    } else if ((uint32_t)b->stride != w && (tight = malloc((size_t)w * h * 4)))
        for (uint32_t y = 0; y < h; y++) memcpy(tight + (size_t)y * w * 4, va + (size_t)y * (uint32_t)b->stride * 4, (size_t)w * 4);
    if ((uint32_t)b->stride == w || tight) {
        begin(T, OP_IMAGE_TEX, 16, 16 + BLOB(w * h * 4));
        put32(T, target); put32(T, w); put32(T, h); putblob(T, tight ? tight : va, w * h * 4);
    }
    free(tight);
    buf_unlock(b);
}


/* glTexSubImage2D into a texture that is an image of a buffer without a note: the rows go into the buffer's memory too */
static void tex_mirror(GLint x, GLint y, GLsizei w, GLsizei h, GLenum format, GLenum type, const void *pixels) {
    struct gstate *g = G();
    void *img = 0;
    struct ANativeWindowBuffer *b = 0;
    unsigned tex = g->unit < 32 ? g->tex2d[g->unit] : 0;
    for (int i = 0; tex && i < 16; i++) if (g->timg[i].tex == tex) { img = g->timg[i].img; break; }
    if (!img || format != 0x1908 || type != 0x1401 || x < 0 || y < 0 || w <= 0 || h <= 0) return;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < 256; i++) if (g_img[i].img == img) { b = g_img[i].host ? 0 : g_img[i].buf; break; }
    pthread_mutex_unlock(&g_lock);
    if (!b || x + w > b->width || y + h > b->height || b->stride < x + w || (b->format != 1 && b->format != 2 && b->format != 5)) return;
    struct meta_note *note = meta_note_at(b->handle);
    if (note && note->magic == META_NOTE && note->id) return;       /* (the host keeps this buffer's pixels itself) */
    long align = g->u_align < 1 ? 4 : g->u_align, rowlen = g->u_row > 0 ? g->u_row : w;
    long stride = (rowlen * 4 + align - 1) / align * align;
    const uint8_t *src = (const uint8_t *)pixels + (long)g->u_skip_rows * stride + (long)g->u_skip_pix * 4;
    uint8_t *va = buf_lock(b, 1);
    if (!va) return;
    for (int r = 0; r < h; r++) {
        const uint8_t *s = src + (long)r * stride;
        uint8_t *d = va + ((size_t)(y + r) * (size_t)b->stride + (size_t)x) * 4;
        if (b->format == 5) for (int k = 0; k < w; k++) { d[k * 4] = s[k * 4 + 2]; d[k * 4 + 1] = s[k * 4 + 1]; d[k * 4 + 2] = s[k * 4]; d[k * 4 + 3] = s[k * 4 + 3]; }
        else memcpy(d, s, (size_t)w * 4);
    }
    buf_unlock(b);
}

/* The bytes of a window buffer's pixels as a texture reads them (0: not a buffer this driver carries) */
static uint64_t image_sum(GLeglImageOES image, struct ANativeWindowBuffer **bo) {
    struct ANativeWindowBuffer *b = 0;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < 256; i++) if (g_img[i].img == image) { b = g_img[i].host ? 0 : g_img[i].buf; break; }
    pthread_mutex_unlock(&g_lock);
    if (bo) *bo = b;
    if (!b) return 0;
    int bpp = b->format == 1 || b->format == 2 ? 4 : b->format == 3 ? 3 : b->format == 4 ? 2 : 0;
    if (!bpp || b->height <= 0 || b->stride <= 0) return 0;
    uint8_t *px = buf_lock(b, 0);
    if (!px) return 0;
    uint64_t s = sum_of(px, (uint32_t)b->stride * (uint32_t)b->height * (uint32_t)bpp) | 1;
    buf_unlock(b);
    return s;
}
static void e_glEGLImageTargetTexture2DOES(GLenum target, GLeglImageOES image) {
    image_upload(target, image);
    if (target == 0x0DE1 && t_ts && t_ts->ctx && !t_ts->ctx->g1 && t_ts->ctx->g.unit < 32) timg_set(&t_ts->ctx->g, t_ts->ctx->g.tex2d[t_ts->ctx->g.unit], image);
    /* a GLES 1 context (SurfaceFlinger of 2.x - 4.1) keeps the image with the texture, see aemu_image_refresh */
    if (target == 0x0DE1 && t_ts && t_ts->ctx && t_ts->ctx->g1) {
        struct g1 *s = G1();
        struct g1_tex *r = g1_target_rec(s, target, 1);
        if (r) {
            /* (the texture's size is the buffer's: glDrawTexiOES - how SurfaceFlinger of 2.x draws a layer - takes its crop rectangle
             * against it and drops the draw when it is 0; only glTexImage2D used to say it, so every layer of the phone's GPU was dropped) */
            struct ANativeWindowBuffer *bb = 0;
            r->img = image; r->img_sum = image_sum(image, &bb);
            if (bb && bb->width > 0 && bb->height > 0) { r->w = bb->width; r->h = bb->height; r->defined = 1; }
        }
    }
}
static void aemu_image_refresh(void *image, uint64_t *sum) {
    struct ANativeWindowBuffer *b = 0;
    uint64_t now = image_sum(image, &b);
    if (!b || !now || now == *sum) return;
    *sum = now;
    image_upload(0x0DE1, image);
}

/* ---- sync objects: SwiftShader's, or for a bridged context one that is signalled once the host caught up ---- */
#define BRIDGE_SYNC ((void *)(uintptr_t)0xAE5C0001)
EXPORT EGLSyncKHR eglCreateSyncKHR(EGLDisplay d, EGLenum type, const EGLint *attribs) {
    if (t_ts && t_ts->ctx) {        /* (what was drawn is finished when this returns) */
        struct ts *T = t_ts;
        begin(T, OP_PING, 0, 0); rsync(T); get32v(T);
        return BRIDGE_SYNC;
    }
    if (solo()) return BRIDGE_SYNC;     /* (nothing of ours is drawn anywhere else) */
    SSF(EGLSyncKHR, CreateSyncKHR, (EGLDisplay, EGLenum, const EGLint *));
    return CreateSyncKHR ? CreateSyncKHR(d, type, attribs) : 0;
}
EXPORT EGLBoolean eglDestroySyncKHR(EGLDisplay d, EGLSyncKHR s) {
    if (s == BRIDGE_SYNC) return 1;
    SSF(EGLBoolean, DestroySyncKHR, (EGLDisplay, EGLSyncKHR));
    return DestroySyncKHR ? DestroySyncKHR(d, s) : 0;
}
EXPORT EGLint eglClientWaitSyncKHR(EGLDisplay d, EGLSyncKHR s, EGLint flags, uint64_t timeout) {
    if (s == BRIDGE_SYNC) {
        struct ts *T = t_ts;
        if (T && T->ctx) { begin(T, OP_PING, 0, 0); rsync(T); get32v(T); }
        return 0x30F6;      /* EGL_CONDITION_SATISFIED_KHR */
    }
    SSF(EGLint, ClientWaitSyncKHR, (EGLDisplay, EGLSyncKHR, EGLint, uint64_t));
    return ClientWaitSyncKHR ? ClientWaitSyncKHR(d, s, flags, timeout) : 0;
}
EXPORT EGLBoolean eglGetSyncAttribKHR(EGLDisplay d, EGLSyncKHR s, EGLint attr, EGLint *value) {
    if (s == BRIDGE_SYNC) {
        if (value) *value = attr == 0x30F1 ? 0x30F2 /* SIGNALED */ : attr == 0x30F7 ? 0x30F9 /* SYNC_FENCE */ : 0x30F0;
        return 1;
    }
    SSF(EGLBoolean, GetSyncAttribKHR, (EGLDisplay, EGLSyncKHR, EGLint, EGLint *));
    return GetSyncAttribKHR ? GetSyncAttribKHR(d, s, attr, value) : 0;
}

EXPORT void *eglGetProcAddress(const char *name) {
    if (!name) return 0;
    if (name[0] == 'g' && name[1] == 'l') {
        for (int i = 0; i < N_FUNCS; i++) if (!strcmp(kNames[i], name)) return kStubs[i];
        return 0;
    }
    /* (a table, not dlsym on ourselves: dlopen(RTLD_NOLOAD) of this library finds nothing on 6.0, and Chromium then
     * called a null eglCreateSyncKHR) */
#define P(f) { #f, (void *)f }
    static const struct { const char *n; void *f; } egl[] = {
        P(eglCreateImageKHR), P(eglDestroyImageKHR), P(eglCreateSyncKHR), P(eglDestroySyncKHR), P(eglClientWaitSyncKHR),
        P(eglGetSyncAttribKHR), P(eglSetDamageRegionKHR), P(eglSwapBuffersWithDamageKHR),
    };
#undef P
    for (unsigned i = 0; i < sizeof egl / sizeof egl[0]; i++) if (!strcmp(egl[i].n, name)) return egl[i].f;
    return 0;
}
