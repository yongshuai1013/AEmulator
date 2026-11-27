/*
 * gles1.h - GLES 1.x for the bridge's GLES 2/3 contexts (included by guest.c).
 *
 * The host renderer (host.c) only makes GLES 3 contexts. A guest program that asks for a GLES 1 context (Android 4.x's
 * boot animation, old games, the Eclair..KitKat launchers' 2D paths) used to get one of those and then had every
 * fixed-function call dropped ("a GL call the bridge does not carry yet"): the boot animation stayed black. This file is
 * the fixed-function pipeline written on top of the encoders of the GLES 2 core (the same way ANGLE and the Android
 * emulator's guest-side GLES 1 translator do it): the matrix stacks, the current colour / normal / texture coordinates,
 * the client arrays and the texture environment are the driver's own state; a draw picks (or builds, once) a GLSL ES 1.00
 * program for what is switched on, uploads the uniforms that changed since that program last drew and sends the arrays as
 * generic vertex attributes through the encoders that already know about client memory, buffers and caching.
 *
 * Carried: the matrix stack (model-view, projection, two texture units), glDrawArrays/Elements with vertex, colour,
 * normal and texture-coordinate arrays (byte / short / fixed / float), current colour/normal/texcoord, 2D texturing on two
 * units with MODULATE / REPLACE / DECAL / BLEND / ADD (and the right behaviour of an ALPHA texture), the alpha test,
 * linear / exp / exp2 fog, per-vertex lighting (8 lights, spot lights, attenuation, colour material), point size and point
 * sprites, GL_OES_draw_texture, GL_OES_matrix_get, GL_OES_fixed_point (every x entry), GL_OES_framebuffer_object and
 * GL_OES_blend_* (the same functions as the core ones), GL_GENERATE_MIPMAP.
 * Not carried (accepted and ignored): TEXTURE_ENV_MODE COMBINE (taken as MODULATE), clip planes, logic ops, matrix
 * palette, point size arrays, GL_FLAT shading (always smooth), texture generation, cube maps.
 *
 * Only what guest.c already links against is used: no libm (sin/cos/sqrt below), no printf (sb_*).
 */
#ifndef AEMU_GLES1_H
#define AEMU_GLES1_H

#define G1_MAXMV 32
#define G1_NTEX 2
#define G1_NLIGHT 8
#define G1_NPROG 48
#define G1_NREC 256
/* GL's default texture (name 0) is a real texture in GLES 1: the boot animation's one-shot parts (count 1) draw through it. Its record lives
 * under this key, which no glGenTextures name reaches. */
#define G1_DEFTEX 0xfffffff0u

/* attribute slots of the generated programs */
enum { A_POS, A_COL, A_NRM, A_TC0, A_TC1, A_N };
/* groups of uniforms that change together: a program remembers which version of each it was sent last */
enum { U_MV, U_PROJ, U_TX0, U_TX1, U_COLOR, U_NRM, U_MTC0, U_MTC1, U_PSIZE, U_AREF, U_FOG, U_TEC0, U_TEC1, U_LIGHT, U_N };
/* uniforms: name and group */
enum { L_MV, L_PROJ, L_TX0, L_TX1, L_COLOR, L_NRM, L_MTC0, L_MTC1, L_PSIZE, L_AREF, L_FOGP, L_FOGC, L_TEC0, L_TEC1,
       L_LMAMB, L_MAMB, L_MDIF, L_MSPC, L_MEMI, L_MSHIN, L_LAMB, L_LDIF, L_LSPC, L_LPOS, L_LSPOT, L_LATT, L_LCUT,
       L_S0, L_S1, L_N };
static const struct { const char *name; int group; } kLoc[L_N] = {
    { "u_mv", U_MV }, { "u_proj", U_PROJ }, { "u_tx0", U_TX0 }, { "u_tx1", U_TX1 }, { "u_color", U_COLOR }, { "u_nrm", U_NRM },
    { "u_mtc0", U_MTC0 }, { "u_mtc1", U_MTC1 }, { "u_psize", U_PSIZE }, { "u_aref", U_AREF }, { "u_fogp", U_FOG },
    { "u_fogc", U_FOG }, { "u_tec0", U_TEC0 }, { "u_tec1", U_TEC1 }, { "u_lmamb", U_LIGHT }, { "u_mamb", U_LIGHT },
    { "u_mdif", U_LIGHT }, { "u_mspc", U_LIGHT }, { "u_memi", U_LIGHT }, { "u_mshin", U_LIGHT }, { "u_lamb", U_LIGHT },
    { "u_ldif", U_LIGHT }, { "u_lspc", U_LIGHT }, { "u_lpos", U_LIGHT }, { "u_lspot", U_LIGHT }, { "u_latt", U_LIGHT },
    { "u_lcut", U_LIGHT }, { "u_s0", -1 }, { "u_s1", -1 },
};

struct g1_tex { unsigned name; int w, h, alpha, genmip, defined, crop[4]; void *img; uint64_t img_sum; };
/* (guest.c) a texture made from an EGLImage of a window buffer: its pixels go to the host again when the guest has drawn into the buffer since */
static void aemu_image_refresh(void *image, uint64_t *sum);
struct g1_prog { uint64_t key; unsigned prog; int ok; int loc[L_N]; unsigned sent[U_N]; };
struct g1_arr { int on, size, type, norm, stride; unsigned buf; const void *ptr; };
struct g1_light { float amb[4], dif[4], spc[4], pos[4], dir[4], att[4], cut; int spot; };
struct g1 {
    int mode, active, client;                       /* matrix mode, active texture unit, client active texture unit */
    float mv[G1_MAXMV][16], pr[2][16], tx[G1_NTEX][2][16];
    int mvd, prd, txd[G1_NTEX];
    unsigned v[U_N];                                /* the versions of the uniform groups */
    float color[4], normal[3], mtc[G1_NTEX][4];
    int tex2d[G1_NTEX]; unsigned bound[G1_NTEX];
    int texx[G1_NTEX]; unsigned boundx[G1_NTEX];    /* GL_TEXTURE_EXTERNAL_OES (OES_EGL_image_external): enabled, bound (SurfaceFlinger of 4.x draws every layer with one) */
    int texenv[G1_NTEX]; float tec[G1_NTEX][4];
    int alpha_on, alpha_func; float alpha_ref;
    int fog_on, fog_mode; float fog_density, fog_start, fog_end, fog_color[4];
    int lighting, cmat, normalize, psprite, psprite_coord; unsigned lights;
    struct g1_light light[G1_NLIGHT];
    float lm_amb[4], mat_amb[4], mat_dif[4], mat_spc[4], mat_emi[4], mat_shin;
    float psize, lwidth; int shade;
    struct g1_arr arr[A_N];
    int vp[4], vp_set;
    unsigned cur_prog;                              /* the program the host's context has in use */
    struct g1_tex rec[G1_NREC];
    struct g1_prog prog[G1_NPROG]; int nprog, evict;
    int said_nodraw, said_unsupported;
};

/* ---- small things the driver cannot import ---- */
struct sb { char *p; int n, cap; };
static void sb_s(struct sb *b, const char *s) {
    int l = (int)strlen(s);
    if (b->n + l >= b->cap) return;
    memcpy(b->p + b->n, s, (size_t)l); b->n += l; b->p[b->n] = 0;
}
static void sb_i(struct sb *b, int v) {
    char t[12], o[14]; int k = 0, m = 0;
    if (v < 0) { o[m++] = '-'; v = -v; }
    if (!v) t[k++] = '0';
    while (v) { t[k++] = (char)('0' + v % 10); v /= 10; }
    while (k) o[m++] = t[--k];
    o[m] = 0; sb_s(b, o);
}
static float g1_sqrt(float x) {
    if (!(x > 0.f)) return 0.f;
    uint32_t i; float y;
    memcpy(&i, &x, 4); i = 0x1fbd1df5u + (i >> 1); memcpy(&y, &i, 4);
    y = 0.5f * (y + x / y); y = 0.5f * (y + x / y); y = 0.5f * (y + x / y); y = 0.5f * (y + x / y);
    return y;
}
static void g1_sincos(float x, float *s, float *c) {
    const float half_pi = 1.57079632679f;
    if (x > 1e6f) x = 1e6f; else if (x < -1e6f) x = -1e6f;
    float kf = x / half_pi;
    int k = (int)(kf < 0.f ? kf - 0.5f : kf + 0.5f);
    float r = x - (float)k * half_pi, r2 = r * r;
    float sn = r * (1.f + r2 * (-1.f / 6 + r2 * (1.f / 120 + r2 * (-1.f / 5040 + r2 * (1.f / 362880)))));
    float cs = 1.f + r2 * (-0.5f + r2 * (1.f / 24 + r2 * (-1.f / 720 + r2 * (1.f / 40320))));
    switch (k & 3) {
    case 0: *s = sn; *c = cs; break;
    case 1: *s = cs; *c = -sn; break;
    case 2: *s = -sn; *c = -cs; break;
    default: *s = -cs; *c = sn; break;
    }
}
static inline float fxf(int v) { return (float)v * (1.0f / 65536.0f); }

/* ---- state ---- */
static void g1_ident(float *m) { memset(m, 0, 64); m[0] = m[5] = m[10] = m[15] = 1.f; }
static void g1_init(struct g1 *s) {
    s->mode = 0x1700; s->client = 0;
    g1_ident(s->mv[0]); g1_ident(s->pr[0]);
    for (int u = 0; u < G1_NTEX; u++) { g1_ident(s->tx[u][0]); s->mtc[u][3] = 1.f; s->texenv[u] = 0x2100; }
    for (int i = 0; i < U_N; i++) s->v[i] = 1;
    s->color[0] = s->color[1] = s->color[2] = s->color[3] = 1.f;
    s->normal[2] = 1.f;
    s->alpha_func = 0x207; s->fog_mode = 0x0800; s->fog_density = 1.f; s->fog_end = 1.f;
    for (int i = 0; i < G1_NLIGHT; i++) {
        struct g1_light *l = &s->light[i];
        l->amb[3] = 1.f; l->pos[2] = 1.f; l->dir[2] = -1.f; l->att[0] = 1.f; l->cut = 180.f;
        if (i == 0) for (int k = 0; k < 4; k++) l->dif[k] = l->spc[k] = 1.f; else l->dif[3] = l->spc[3] = 1.f;
    }
    s->lm_amb[0] = s->lm_amb[1] = s->lm_amb[2] = s->lm_amb[3] = 0.2f; s->lm_amb[3] = 1.f;
    s->mat_amb[0] = s->mat_amb[1] = s->mat_amb[2] = 0.2f; s->mat_amb[3] = 1.f;
    s->mat_dif[0] = s->mat_dif[1] = s->mat_dif[2] = 0.8f; s->mat_dif[3] = 1.f;
    s->mat_spc[3] = 1.f; s->mat_emi[3] = 1.f;
    s->psize = s->lwidth = 1.f; s->shade = 0x1D01;
}
static struct g1 *G1(void) {
    struct ectx *c = t_ts->ctx;
    if (!c->g1) { c->g1 = calloc(1, sizeof(struct g1)); if (c->g1) g1_init(c->g1); }
    return c->g1;
}
static void g1_surface_size(int *w, int *h);

/* ---- textures the driver knows (sizes for glDrawTex, ALPHA textures for the texture environment) ---- */
static struct g1_tex *g1_rec(struct g1 *s, unsigned name, int make) {
    if (!name) name = G1_DEFTEX;
    unsigned h = name % G1_NREC;
    for (unsigned i = 0; i < G1_NREC; i++) {
        struct g1_tex *r = &s->rec[(h + i) % G1_NREC];
        if (r->name == name) return r;
    }
    if (!make) return 0;
    for (unsigned i = 0; i < G1_NREC; i++) {
        struct g1_tex *r = &s->rec[(h + i) % G1_NREC];
        if (!r->name) { memset(r, 0, sizeof(*r)); r->name = name; return r; }
    }
    struct g1_tex *r = &s->rec[h];          /* full: the oldest-looking slot goes */
    memset(r, 0, sizeof(*r)); r->name = name;
    return r;
}
static struct g1_tex *g1_bound_rec(struct g1 *s, int make) { return g1_rec(s, s->bound[s->active], make); }
/* the record of what is bound to a texture target of the active unit: 2D, or external (0: any other target) */
static struct g1_tex *g1_target_rec(struct g1 *s, GLenum target, int make) {
    if (target == 0x0DE1) return g1_bound_rec(s, make);
    if (target == 0x8D65) return g1_rec(s, s->boundx[s->active], make);
    return 0;
}

/* ---- matrices ---- */
static float *g1_cur(struct g1 *s) {
    switch (s->mode) {
    case 0x1700: return s->mv[s->mvd];
    case 0x1701: return s->pr[s->prd];
    default: return s->tx[s->active][s->txd[s->active]];
    }
}
static void g1_touch(struct g1 *s) { s->v[s->mode == 0x1700 ? U_MV : s->mode == 0x1701 ? U_PROJ : U_TX0 + s->active]++; }
static void g1_mul(float *o, const float *a, const float *b) {
    float t[16];
    for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++)
        t[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
    memcpy(o, t, 64);
}
static void g1_post(const float *m) { struct g1 *s = G1(); float *c = g1_cur(s); g1_mul(c, c, m); g1_touch(s); }
static void e1_glMatrixMode(GLenum mode) { struct g1 *s = G1(); if (mode == 0x1700 || mode == 0x1701 || mode == 0x1702) s->mode = (int)mode; }
static void e1_glLoadIdentity(void) { struct g1 *s = G1(); g1_ident(g1_cur(s)); g1_touch(s); }
static void e1_glLoadMatrixf(const GLfloat *m) { struct g1 *s = G1(); if (m) { memcpy(g1_cur(s), m, 64); g1_touch(s); } }
static void e1_glMultMatrixf(const GLfloat *m) { if (m) g1_post(m); }
static void e1_glPushMatrix(void) {
    struct g1 *s = G1();
    if (s->mode == 0x1700) { if (s->mvd < G1_MAXMV - 1) { memcpy(s->mv[s->mvd + 1], s->mv[s->mvd], 64); s->mvd++; } }
    else if (s->mode == 0x1701) { if (s->prd < 1) { memcpy(s->pr[1], s->pr[0], 64); s->prd++; } }
    else { int u = s->active; if (s->txd[u] < 1) { memcpy(s->tx[u][1], s->tx[u][0], 64); s->txd[u]++; } }
}
static void e1_glPopMatrix(void) {
    struct g1 *s = G1();
    if (s->mode == 0x1700) { if (s->mvd > 0) { s->mvd--; g1_touch(s); } }
    else if (s->mode == 0x1701) { if (s->prd > 0) { s->prd--; g1_touch(s); } }
    else { int u = s->active; if (s->txd[u] > 0) { s->txd[u]--; g1_touch(s); } }
}
static void e1_glTranslatef(GLfloat x, GLfloat y, GLfloat z) { float m[16]; g1_ident(m); m[12] = x; m[13] = y; m[14] = z; g1_post(m); }
static void e1_glScalef(GLfloat x, GLfloat y, GLfloat z) { float m[16]; g1_ident(m); m[0] = x; m[5] = y; m[10] = z; g1_post(m); }
static void e1_glRotatef(GLfloat a, GLfloat x, GLfloat y, GLfloat z) {
    float len = g1_sqrt(x * x + y * y + z * z), sn, cs, m[16];
    if (len == 0.f) return;
    x /= len; y /= len; z /= len;
    g1_sincos(a * 0.0174532925199f, &sn, &cs);
    float t = 1.f - cs;
    g1_ident(m);
    m[0] = t * x * x + cs;      m[4] = t * x * y - sn * z;  m[8] = t * x * z + sn * y;
    m[1] = t * x * y + sn * z;  m[5] = t * y * y + cs;      m[9] = t * y * z - sn * x;
    m[2] = t * x * z - sn * y;  m[6] = t * y * z + sn * x;  m[10] = t * z * z + cs;
    g1_post(m);
}
static void e1_glOrthof(GLfloat l, GLfloat r, GLfloat b, GLfloat t, GLfloat n, GLfloat f) {
    float m[16];
    if (l == r || b == t || n == f) return;
    g1_ident(m);
    m[0] = 2.f / (r - l); m[5] = 2.f / (t - b); m[10] = -2.f / (f - n);
    m[12] = -(r + l) / (r - l); m[13] = -(t + b) / (t - b); m[14] = -(f + n) / (f - n);
    g1_post(m);
}
static void e1_glFrustumf(GLfloat l, GLfloat r, GLfloat b, GLfloat t, GLfloat n, GLfloat f) {
    float m[16];
    if (l == r || b == t || n == f || n <= 0.f || f <= 0.f) return;
    memset(m, 0, 64);
    m[0] = 2.f * n / (r - l); m[5] = 2.f * n / (t - b); m[8] = (r + l) / (r - l); m[9] = (t + b) / (t - b);
    m[10] = -(f + n) / (f - n); m[11] = -1.f; m[14] = -2.f * f * n / (f - n);
    g1_post(m);
}
/* (the fixed-point entries: 16.16 values) */
static void e1_glLoadMatrixx(const GLfixed *m) { float f[16]; if (!m) return; for (int i = 0; i < 16; i++) f[i] = fxf(m[i]); e1_glLoadMatrixf(f); }
static void e1_glMultMatrixx(const GLfixed *m) { float f[16]; if (!m) return; for (int i = 0; i < 16; i++) f[i] = fxf(m[i]); e1_glMultMatrixf(f); }
static void e1_glTranslatex(GLfixed x, GLfixed y, GLfixed z) { e1_glTranslatef(fxf(x), fxf(y), fxf(z)); }
static void e1_glScalex(GLfixed x, GLfixed y, GLfixed z) { e1_glScalef(fxf(x), fxf(y), fxf(z)); }
static void e1_glRotatex(GLfixed a, GLfixed x, GLfixed y, GLfixed z) { e1_glRotatef(fxf(a), fxf(x), fxf(y), fxf(z)); }
static void e1_glOrthox(GLfixed l, GLfixed r, GLfixed b, GLfixed t, GLfixed n, GLfixed f) { e1_glOrthof(fxf(l), fxf(r), fxf(b), fxf(t), fxf(n), fxf(f)); }
static void e1_glFrustumx(GLfixed l, GLfixed r, GLfixed b, GLfixed t, GLfixed n, GLfixed f) { e1_glFrustumf(fxf(l), fxf(r), fxf(b), fxf(t), fxf(n), fxf(f)); }

/* ---- the current values ---- */
static void e1_glColor4f(GLfloat r, GLfloat g, GLfloat b, GLfloat a) {
    struct g1 *s = G1();
    s->color[0] = r; s->color[1] = g; s->color[2] = b; s->color[3] = a; s->v[U_COLOR]++;
}
static void e1_glColor4ub(GLubyte r, GLubyte g, GLubyte b, GLubyte a) { e1_glColor4f(r / 255.f, g / 255.f, b / 255.f, a / 255.f); }
static void e1_glColor4x(GLfixed r, GLfixed g, GLfixed b, GLfixed a) { e1_glColor4f(fxf(r), fxf(g), fxf(b), fxf(a)); }
static void e1_glNormal3f(GLfloat x, GLfloat y, GLfloat z) { struct g1 *s = G1(); s->normal[0] = x; s->normal[1] = y; s->normal[2] = z; s->v[U_NRM]++; }
static void e1_glNormal3x(GLfixed x, GLfixed y, GLfixed z) { e1_glNormal3f(fxf(x), fxf(y), fxf(z)); }
static void e1_glMultiTexCoord4f(GLenum t, GLfloat a, GLfloat b, GLfloat c, GLfloat d) {
    struct g1 *s = G1();
    unsigned u = t - 0x84C0;
    if (u >= G1_NTEX) return;
    s->mtc[u][0] = a; s->mtc[u][1] = b; s->mtc[u][2] = c; s->mtc[u][3] = d; s->v[U_MTC0 + u]++;
}
static void e1_glMultiTexCoord4x(GLenum t, GLfixed a, GLfixed b, GLfixed c, GLfixed d) { e1_glMultiTexCoord4f(t, fxf(a), fxf(b), fxf(c), fxf(d)); }
static void e1_glPointSize(GLfloat v) { struct g1 *s = G1(); s->psize = v > 0.f ? v : 1.f; s->v[U_PSIZE]++; }
static void e1_glPointSizex(GLfixed v) { e1_glPointSize(fxf(v)); }
static void e1_glAlphaFunc(GLenum func, GLfloat ref) { struct g1 *s = G1(); s->alpha_func = (int)func; s->alpha_ref = ref; s->v[U_AREF]++; }
static void e1_glAlphaFuncx(GLenum func, GLfixed ref) { e1_glAlphaFunc(func, fxf(ref)); }
static void e1_glShadeModel(GLenum m) { G1()->shade = (int)m; }
static void e1_glLogicOp(GLenum op) { (void)op; }
static void e1_glClipPlanef(GLenum p, const GLfloat *e) { (void)p; (void)e; }
static void e1_glClipPlanex(GLenum p, const GLfixed *e) { (void)p; (void)e; }
static void e1_glGetClipPlanef(GLenum p, GLfloat *e) { (void)p; if (e) memset(e, 0, 16); }
static void e1_glGetClipPlanex(GLenum p, GLfixed *e) { (void)p; if (e) memset(e, 0, 16); }
static void e1_glPointParameterf(GLenum p, GLfloat v) { (void)p; (void)v; }
static void e1_glPointParameterfv(GLenum p, const GLfloat *v) { (void)p; (void)v; }
static void e1_glPointParameterx(GLenum p, GLfixed v) { (void)p; (void)v; }
static void e1_glPointParameterxv(GLenum p, const GLfixed *v) { (void)p; (void)v; }
static void e1_glPointSizePointerOES(GLenum t, GLsizei st, const void *p) { (void)t; (void)st; (void)p; }
static void e1_glLineWidthx(GLfixed w) { e_glLineWidth(fxf(w)); }
static void e1_glPolygonOffsetx(GLfixed f, GLfixed u) { e_glPolygonOffset(fxf(f), fxf(u)); }
static void e1_glSampleCoveragex(GLclampx v, GLboolean i) { e_glSampleCoverage(fxf(v), i); }
static void e1_glClearColorx(GLfixed r, GLfixed g, GLfixed b, GLfixed a) { e_glClearColor(fxf(r), fxf(g), fxf(b), fxf(a)); }
static void e1_glClearDepthx(GLfixed d) { e_glClearDepthf(fxf(d)); }
static void e1_glDepthRangex(GLfixed n, GLfixed f) { e_glDepthRangef(fxf(n), fxf(f)); }
static void e1_glHint(GLenum target, GLenum mode) {
    if (target >= 0x0C50 && target <= 0x0C54) return;        /* perspective correction, point / line smooth, fog: GLES 1 only */
    e_glHint(target, mode);
}

/* ---- fog, lights, materials ---- */
static void g1_fogfv(GLenum pname, const float *p) {
    struct g1 *s = G1();
    switch (pname) {
    case 0x0B65: s->fog_mode = (int)p[0]; break;
    case 0x0B62: s->fog_density = p[0]; break;
    case 0x0B63: s->fog_start = p[0]; break;
    case 0x0B64: s->fog_end = p[0]; break;
    case 0x0B66: memcpy(s->fog_color, p, 16); break;
    default: return;
    }
    s->v[U_FOG]++;
}
static void e1_glFogfv(GLenum pname, const GLfloat *p) { if (p) g1_fogfv(pname, p); }
static void e1_glFogf(GLenum pname, GLfloat v) { g1_fogfv(pname, &v); }
static void e1_glFogxv(GLenum pname, const GLfixed *p) {
    float f[4];
    if (!p) return;
    if (pname == 0x0B65) f[0] = (float)p[0]; else for (int i = 0; i < (pname == 0x0B66 ? 4 : 1); i++) f[i] = fxf(p[i]);
    g1_fogfv(pname, f);
}
static void e1_glFogx(GLenum pname, GLfixed v) { e1_glFogxv(pname, &v); }

/* a vector through the model-view matrix (a light's position is stored in eye coordinates), a direction through its upper 3x3 */
static void g1_xform(const float *m, const float *v, float *o) {
    for (int r = 0; r < 4; r++) o[r] = m[r] * v[0] + m[4 + r] * v[1] + m[8 + r] * v[2] + m[12 + r] * v[3];
}
static void g1_lightfv(GLenum light, GLenum pname, const float *p) {
    struct g1 *s = G1();
    unsigned i = light - 0x4000;
    if (i >= G1_NLIGHT) return;
    struct g1_light *l = &s->light[i];
    const float *mv = s->mv[s->mvd];
    switch (pname) {
    case 0x1200: memcpy(l->amb, p, 16); break;
    case 0x1201: memcpy(l->dif, p, 16); break;
    case 0x1202: memcpy(l->spc, p, 16); break;
    case 0x1203: g1_xform(mv, p, l->pos); break;
    case 0x1204: {
        /* the direction goes through the inverse transpose of the upper 3x3: cofactors, then normalised */
        const float *m0 = mv, *m1 = mv + 4, *m2 = mv + 8;
        float c0[3] = { m1[1] * m2[2] - m1[2] * m2[1], m1[2] * m2[0] - m1[0] * m2[2], m1[0] * m2[1] - m1[1] * m2[0] };
        float c1[3] = { m2[1] * m0[2] - m2[2] * m0[1], m2[2] * m0[0] - m2[0] * m0[2], m2[0] * m0[1] - m2[1] * m0[0] };
        float c2[3] = { m0[1] * m1[2] - m0[2] * m1[1], m0[2] * m1[0] - m0[0] * m1[2], m0[0] * m1[1] - m0[1] * m1[0] };
        float det = m0[0] * c0[0] + m0[1] * c0[1] + m0[2] * c0[2], d[3], n;
        for (int k = 0; k < 3; k++) d[k] = c0[k] * p[0] + c1[k] * p[1] + c2[k] * p[2];
        n = g1_sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        if (n > 0.f) { if (det < 0.f) n = -n; for (int k = 0; k < 3; k++) l->dir[k] = d[k] / n; }
        break; }
    case 0x1205: l->att[3] = p[0]; break;
    case 0x1206: l->cut = p[0]; break;
    case 0x1207: l->att[0] = p[0]; break;
    case 0x1208: l->att[1] = p[0]; break;
    case 0x1209: l->att[2] = p[0]; break;
    default: return;
    }
    s->v[U_LIGHT]++;
}
static int g1_vec_n(GLenum pname) { return pname == 0x1203 || pname == 0x1200 || pname == 0x1201 || pname == 0x1202 || pname == 0x1600 || pname == 0x1602 || pname == 0x0B53 ? 4 : pname == 0x1204 ? 3 : 1; }
static void e1_glLightfv(GLenum l, GLenum pname, const GLfloat *p) { if (p) g1_lightfv(l, pname, p); }
static void e1_glLightf(GLenum l, GLenum pname, GLfloat v) { g1_lightfv(l, pname, &v); }
static void e1_glLightxv(GLenum l, GLenum pname, const GLfixed *p) { float f[4]; if (!p) return; for (int i = 0; i < g1_vec_n(pname); i++) f[i] = fxf(p[i]); g1_lightfv(l, pname, f); }
static void e1_glLightx(GLenum l, GLenum pname, GLfixed v) { e1_glLightxv(l, pname, &v); }
static void g1_lightmodelfv(GLenum pname, const float *p) {
    struct g1 *s = G1();
    if (pname == 0x0B53) { memcpy(s->lm_amb, p, 16); s->v[U_LIGHT]++; }
}
static void e1_glLightModelfv(GLenum pname, const GLfloat *p) { if (p) g1_lightmodelfv(pname, p); }
static void e1_glLightModelf(GLenum pname, GLfloat v) { g1_lightmodelfv(pname, &v); }
static void e1_glLightModelxv(GLenum pname, const GLfixed *p) { float f[4]; if (!p) return; for (int i = 0; i < g1_vec_n(pname); i++) f[i] = fxf(p[i]); g1_lightmodelfv(pname, f); }
static void e1_glLightModelx(GLenum pname, GLfixed v) { e1_glLightModelxv(pname, &v); }
static void g1_materialfv(GLenum face, GLenum pname, const float *p) {
    struct g1 *s = G1();
    (void)face;
    switch (pname) {
    case 0x1200: memcpy(s->mat_amb, p, 16); break;
    case 0x1201: memcpy(s->mat_dif, p, 16); break;
    case 0x1602: memcpy(s->mat_amb, p, 16); memcpy(s->mat_dif, p, 16); break;
    case 0x1202: memcpy(s->mat_spc, p, 16); break;
    case 0x1600: memcpy(s->mat_emi, p, 16); break;
    case 0x1601: s->mat_shin = p[0]; break;
    default: return;
    }
    s->v[U_LIGHT]++;
}
static void e1_glMaterialfv(GLenum f, GLenum pname, const GLfloat *p) { if (p) g1_materialfv(f, pname, p); }
static void e1_glMaterialf(GLenum f, GLenum pname, GLfloat v) { g1_materialfv(f, pname, &v); }
static void e1_glMaterialxv(GLenum f, GLenum pname, const GLfixed *p) { float v[4]; if (!p) return; for (int i = 0; i < g1_vec_n(pname); i++) v[i] = fxf(p[i]); g1_materialfv(f, pname, v); }
static void e1_glMaterialx(GLenum f, GLenum pname, GLfixed v) { e1_glMaterialxv(f, pname, &v); }
static void e1_glGetLightfv(GLenum light, GLenum pname, GLfloat *o) {
    struct g1 *s = G1(); unsigned i = light - 0x4000;
    if (i >= G1_NLIGHT || !o) return;
    const struct g1_light *l = &s->light[i];
    switch (pname) {
    case 0x1200: memcpy(o, l->amb, 16); break; case 0x1201: memcpy(o, l->dif, 16); break;
    case 0x1202: memcpy(o, l->spc, 16); break; case 0x1203: memcpy(o, l->pos, 16); break;
    case 0x1204: memcpy(o, l->dir, 12); break; case 0x1205: o[0] = l->att[3]; break;
    case 0x1206: o[0] = l->cut; break; case 0x1207: o[0] = l->att[0]; break;
    case 0x1208: o[0] = l->att[1]; break; case 0x1209: o[0] = l->att[2]; break;
    }
}
static void e1_glGetMaterialfv(GLenum face, GLenum pname, GLfloat *o) {
    struct g1 *s = G1(); (void)face;
    if (!o) return;
    switch (pname) {
    case 0x1200: memcpy(o, s->mat_amb, 16); break; case 0x1201: memcpy(o, s->mat_dif, 16); break;
    case 0x1202: memcpy(o, s->mat_spc, 16); break; case 0x1600: memcpy(o, s->mat_emi, 16); break;
    case 0x1601: o[0] = s->mat_shin; break;
    }
}
static void e1_glGetLightxv(GLenum l, GLenum pname, GLfixed *o) { float f[4] = { 0, 0, 0, 0 }; e1_glGetLightfv(l, pname, f); if (o) for (int i = 0; i < g1_vec_n(pname); i++) o[i] = (int)(f[i] * 65536.f); }
static void e1_glGetMaterialxv(GLenum fc, GLenum pname, GLfixed *o) { float f[4] = { 0, 0, 0, 0 }; e1_glGetMaterialfv(fc, pname, f); if (o) for (int i = 0; i < g1_vec_n(pname); i++) o[i] = (int)(f[i] * 65536.f); }

/* ---- capabilities ---- */
static int g1_cap(struct g1 *s, GLenum cap, int on, int query) {
    switch (cap) {
    case 0x0DE1: if (query) return s->tex2d[s->active]; s->tex2d[s->active] = on; return 1;
    case 0x8D65: if (query) return s->texx[s->active]; s->texx[s->active] = on; return 1;      /* GL_TEXTURE_EXTERNAL_OES: not a host capability, a texture unit's mode here */
    case 0x0BC0: if (query) return s->alpha_on; s->alpha_on = on; return 1;
    case 0x0B50: if (query) return s->lighting; s->lighting = on; return 1;
    case 0x0B57: if (query) return s->cmat; s->cmat = on; return 1;
    case 0x0B60: if (query) return s->fog_on; s->fog_on = on; return 1;
    case 0x0BA1: if (query) return s->normalize; s->normalize = on; return 1;
    case 0x8861: if (query) return s->psprite; s->psprite = on; return 1;
    case 0x803A: case 0x0B10: case 0x0B20: case 0x0BF2: case 0x809D: case 0x809F: return query ? 0 : 1;
    }
    if (cap >= 0x4000 && cap < 0x4000 + G1_NLIGHT) {
        unsigned b = 1u << (cap - 0x4000);
        if (query) return (s->lights & b) != 0;
        if (on) s->lights |= b; else s->lights &= ~b;
        return 1;
    }
    if (cap >= 0x3000 && cap < 0x3006) return query ? 0 : 1;     /* clip planes: accepted, not carried */
    return 0;
}
static void e1_glEnable(GLenum cap) { if (!g1_cap(G1(), cap, 1, 0)) e_glEnable(cap); }
static void e1_glDisable(GLenum cap) { if (!g1_cap(G1(), cap, 0, 0)) e_glDisable(cap); }
static GLboolean e1_glIsEnabled(GLenum cap) {
    struct g1 *s = G1();
    if (cap == 0x0DE1 || cap == 0x8D65 || cap == 0x0BC0 || cap == 0x0B50 || cap == 0x0B57 || cap == 0x0B60 || cap == 0x0BA1 || cap == 0x8861 ||
        (cap >= 0x4000 && cap < 0x4000 + G1_NLIGHT) || cap == 0x803A || cap == 0x0B10 || cap == 0x0B20 || cap == 0x0BF2 ||
        cap == 0x809D || cap == 0x809F || (cap >= 0x3000 && cap < 0x3006))
        return (GLboolean)g1_cap(s, cap, 0, 1);
    return e_glIsEnabled(cap);
}

/* ---- textures ---- */
static void e1_glActiveTexture(GLenum t) { struct g1 *s = G1(); unsigned u = t - 0x84C0; if (u < G1_NTEX) s->active = (int)u; e_glActiveTexture(t); }
static void e1_glClientActiveTexture(GLenum t) { struct g1 *s = G1(); unsigned u = t - 0x84C0; if (u < G1_NTEX) s->client = (int)u; }
static void e1_glBindTexture(GLenum target, GLuint name) {
    struct g1 *s = G1();
    if (target == 0x0DE1) { s->bound[s->active] = name; if (name) g1_rec(s, name, 1); }
    else if (target == 0x8D65) { s->boundx[s->active] = name; if (name) g1_rec(s, name, 1); }
    e_glBindTexture(target, name);
    /* Gingerbread's SurfaceFlinger binds a layer's buffer to its texture once (glEGLImageTargetTexture2DOES, when the buffer is new) and then only
     * glBindTextures it: on a GPU the image aliases the buffer's memory, here the host holds a copy, so the copy is renewed whenever the buffer changed */
    if (target == 0x0DE1 && name) { struct g1_tex *r = g1_rec(s, name, 0); if (r && r->img) aemu_image_refresh(r->img, &r->img_sum); }
}
static void e1_glDeleteTextures(GLsizei n, const GLuint *names) {
    struct g1 *s = G1();
    for (int i = 0; names && i < n; i++) {
        if (!names[i]) continue;            /* (glDeleteTextures ignores 0: the default texture stays) */
        struct g1_tex *r = g1_rec(s, names[i], 0);
        if (r) r->name = 0;
        for (int u = 0; u < G1_NTEX; u++) { if (s->bound[u] == names[i]) s->bound[u] = 0; if (s->boundx[u] == names[i]) s->boundx[u] = 0; }
    }
    e_glDeleteTextures(n, names);
}
static void g1_defined(GLenum target, GLint level, GLint ifmt, int w, int h, int compressed) {
    struct g1 *s = G1();
    if (target != 0x0DE1) return;
    struct g1_tex *r = g1_bound_rec(s, 1);
    if (!r) return;
    if (level == 0) {
        r->w = w; r->h = h; r->defined = 1; r->img = 0;
        r->alpha = ifmt == 0x1906;           /* GL_ALPHA */
        (void)compressed;
        if (r->genmip) e_glGenerateMipmap(target);
    }
}
static void e1_glTexImage2D(GLenum t, GLint lv, GLint ifmt, GLsizei w, GLsizei h, GLint b, GLenum f, GLenum ty, const void *px) {
    e_glTexImage2D(t, lv, ifmt, w, h, b, f, ty, px);
    g1_defined(t, lv, ifmt, w, h, 0);
}
static void e1_glTexSubImage2D(GLenum t, GLint lv, GLint x, GLint y, GLsizei w, GLsizei h, GLenum f, GLenum ty, const void *px) {
    struct g1_tex *r;
    e_glTexSubImage2D(t, lv, x, y, w, h, f, ty, px);
    if (t == 0x0DE1 && lv == 0 && (r = g1_bound_rec(G1(), 0)) && r->genmip) e_glGenerateMipmap(t);
}
static void e1_glCompressedTexImage2D(GLenum t, GLint lv, GLenum ifmt, GLsizei w, GLsizei h, GLint b, GLsizei n, const void *d) {
    e_glCompressedTexImage2D(t, lv, ifmt, w, h, b, n, d);
    g1_defined(t, lv, ifmt == 0x8D64 ? 0x1907 : 0x1908, w, h, 1);
}
static void e1_glCopyTexImage2D(GLenum t, GLint lv, GLenum ifmt, GLint x, GLint y, GLsizei w, GLsizei h, GLint b) {
    e_glCopyTexImage2D(t, lv, ifmt, x, y, w, h, b);
    g1_defined(t, lv, (GLint)ifmt, w, h, 0);
}
static void g1_texparam(GLenum target, GLenum pname, const int *v, int n) {
    struct g1_tex *r = g1_target_rec(G1(), target, 1);
    if (pname == 0x8B9D /* GL_TEXTURE_CROP_RECT_OES */) { if (r && n >= 4) memcpy(r->crop, v, 16); return; }
    if (pname == 0x8191 /* GL_GENERATE_MIPMAP */) { if (r) { r->genmip = v[0] != 0; if (r->genmip && r->defined) e_glGenerateMipmap(target); } return; }
    if (n == 1) e_glTexParameteri(target, pname, v[0]);
}
static void e1_glTexParameteri(GLenum t, GLenum p, GLint v) { g1_texparam(t, p, &v, 1); }
static void e1_glTexParameterf(GLenum t, GLenum p, GLfloat v) {
    if (p == 0x8191) { int i = v != 0.f; g1_texparam(t, p, &i, 1); } else e_glTexParameterf(t, p, v);
}
static void e1_glTexParameteriv(GLenum t, GLenum p, const GLint *v) {
    if (!v) return;
    if (p == 0x8B9D) g1_texparam(t, p, v, 4); else if (p == 0x8191) g1_texparam(t, p, v, 1); else e_glTexParameteriv(t, p, v);
}
static void e1_glTexParameterfv(GLenum t, GLenum p, const GLfloat *v) {
    if (!v) return;
    if (p == 0x8B9D) { int i[4]; for (int k = 0; k < 4; k++) i[k] = (int)v[k]; g1_texparam(t, p, i, 4); }
    else if (p == 0x8191) { int i = v[0] != 0.f; g1_texparam(t, p, &i, 1); }
    else e_glTexParameterfv(t, p, v);
}
static void e1_glTexParameterx(GLenum t, GLenum p, GLfixed v) { g1_texparam(t, p, &v, 1); }
static void e1_glTexParameterxv(GLenum t, GLenum p, const GLfixed *v) { if (v) g1_texparam(t, p, v, p == 0x8B9D ? 4 : 1); }
static void e1_glGetTexParameteriv(GLenum t, GLenum p, GLint *o) {
    if (p == 0x8B9D) { struct g1_tex *r = g1_target_rec(G1(), t, 0); if (o) { if (r) memcpy(o, r->crop, 16); else memset(o, 0, 16); } return; }
    if (p == 0x8191) { struct g1_tex *r = g1_target_rec(G1(), t, 0); if (o) *o = r && r->genmip; return; }
    e_glGetTexParameteriv(t, p, o);
}
static void e1_glGetTexParameterxv(GLenum t, GLenum p, GLfixed *o) { GLint v[4] = { 0, 0, 0, 0 }; e1_glGetTexParameteriv(t, p, v); if (o) *o = v[0]; }

/* texture environment: mode, colour; the point sprite's coordinate replacement */
static void g1_texenv(GLenum target, GLenum pname, const float *p) {
    struct g1 *s = G1();
    int u = s->active;
    if (target == 0x8861 /* GL_POINT_SPRITE_OES */) { if (pname == 0x8862) s->psprite_coord = p[0] != 0.f; return; }
    if (target != 0x2300) return;
    if (pname == 0x2200) {
        int m = (int)p[0];
        if (m == 0x8570 && !s->said_unsupported) { s->said_unsupported = 1; LOG("GL_COMBINE texture environment is taken as MODULATE"); }
        s->texenv[u] = m; s->v[U_TEC0 + u]++;
    } else if (pname == 0x2201) { memcpy(s->tec[u], p, 16); s->v[U_TEC0 + u]++; }
}
static void e1_glTexEnvf(GLenum t, GLenum p, GLfloat v) { g1_texenv(t, p, &v); }
static void e1_glTexEnvfv(GLenum t, GLenum p, const GLfloat *v) { if (v) g1_texenv(t, p, v); }
static void e1_glTexEnvi(GLenum t, GLenum p, GLint v) { float f = (float)v; g1_texenv(t, p, &f); }
static void e1_glTexEnviv(GLenum t, GLenum p, const GLint *v) {
    float f[4];
    if (!v) return;
    if (p == 0x2201) for (int i = 0; i < 4; i++) f[i] = (float)v[i] / 2147483647.f; else f[0] = (float)v[0];
    g1_texenv(t, p, f);
}
static void e1_glTexEnvx(GLenum t, GLenum p, GLfixed v) { float f = p == 0x2200 || p == 0x8862 ? (float)v : fxf(v); g1_texenv(t, p, &f); }
static void e1_glTexEnvxv(GLenum t, GLenum p, const GLfixed *v) {
    float f[4];
    if (!v) return;
    if (p == 0x2201) for (int i = 0; i < 4; i++) f[i] = fxf(v[i]); else f[0] = p == 0x2200 || p == 0x8862 ? (float)v[0] : fxf(v[0]);
    g1_texenv(t, p, f);
}
static void e1_glGetTexEnvfv(GLenum t, GLenum p, GLfloat *o) {
    struct g1 *s = G1();
    if (!o || t != 0x2300) return;
    if (p == 0x2200) o[0] = (float)s->texenv[s->active]; else if (p == 0x2201) memcpy(o, s->tec[s->active], 16);
}
static void e1_glGetTexEnviv(GLenum t, GLenum p, GLint *o) {
    float f[4] = { 0, 0, 0, 0 };
    e1_glGetTexEnvfv(t, p, f);
    if (o) { if (p == 0x2201) for (int i = 0; i < 4; i++) o[i] = (int)(f[i] * 2147483647.f); else o[0] = (int)f[0]; }
}
static void e1_glGetTexEnvxv(GLenum t, GLenum p, GLfixed *o) {
    float f[4] = { 0, 0, 0, 0 };
    e1_glGetTexEnvfv(t, p, f);
    if (o) { if (p == 0x2201) for (int i = 0; i < 4; i++) o[i] = (int)(f[i] * 65536.f); else o[0] = (int)f[0]; }
}

/* ---- client arrays ---- */
static int g1_arr_of(struct g1 *s, GLenum array) {
    switch (array) {
    case 0x8074: return A_POS;
    case 0x8075: return A_NRM;
    case 0x8076: return A_COL;
    case 0x8078: return A_TC0 + s->client;
    }
    return -1;      /* (GL_POINT_SIZE_ARRAY_OES and anything else: not carried) */
}
static void e1_glEnableClientState(GLenum a) { struct g1 *s = G1(); int i = g1_arr_of(s, a); if (i >= 0) s->arr[i].on = 1; }
static void e1_glDisableClientState(GLenum a) { struct g1 *s = G1(); int i = g1_arr_of(s, a); if (i >= 0) s->arr[i].on = 0; }
static void g1_pointer(int i, GLint size, GLenum type, int norm, GLsizei stride, const void *p) {
    struct g1_arr *a = &G1()->arr[i];
    a->size = size; a->type = (int)type; a->norm = norm; a->stride = stride; a->ptr = p; a->buf = G()->array;
}
static void e1_glVertexPointer(GLint size, GLenum type, GLsizei stride, const void *p) { g1_pointer(A_POS, size, type, 0, stride, p); }
static void e1_glNormalPointer(GLenum type, GLsizei stride, const void *p) { g1_pointer(A_NRM, 3, type, 1, stride, p); }
static void e1_glColorPointer(GLint size, GLenum type, GLsizei stride, const void *p) { g1_pointer(A_COL, size, type, type == 0x1401, stride, p); }
static void e1_glTexCoordPointer(GLint size, GLenum type, GLsizei stride, const void *p) { g1_pointer(A_TC0 + G1()->client, size, type, 0, stride, p); }
static void e1_glGetPointerv(GLenum pname, void **o) {
    struct g1 *s = G1();
    int i = pname == 0x8756 ? A_POS : pname == 0x8760 ? A_NRM : pname == 0x8761 ? A_COL : pname == 0x8758 ? A_TC0 + s->client : -1;
    if (o) *o = i >= 0 ? (void *)s->arr[i].ptr : 0;
}

/* ---- viewport (glDrawTex works in window coordinates) ---- */
static void e1_glViewport(GLint x, GLint y, GLsizei w, GLsizei h) {
    struct g1 *s = G1();
    s->vp[0] = x; s->vp[1] = y; s->vp[2] = w; s->vp[3] = h; s->vp_set = 1;
    e_glViewport(x, y, w, h);
}
static void g1_viewport(struct g1 *s, int *vp) {
    if (s->vp_set) { memcpy(vp, s->vp, 16); return; }
    vp[0] = vp[1] = 0; g1_surface_size(&vp[2], &vp[3]);
}

/* ---- programs ---- */
#define K_TEX(u) (1ull << (u))
#define K_MODE(u, m) ((uint64_t)(m) << (2 + 3 * (u)))
#define K_MODE_OF(k, u) ((int)(((k) >> (2 + 3 * (u))) & 7))
#define K_ALPHA(u) (1ull << (8 + (u)))
#define K_COLARR (1ull << 10)
#define K_NRMARR (1ull << 11)
#define K_TCARR(u) (1ull << (12 + (u)))
#define K_ATEST (1ull << 14)
#define K_AFUNC(f) ((uint64_t)(f) << 15)
#define K_AFUNC_OF(k) ((int)(((k) >> 15) & 7))
#define K_FOG (1ull << 18)
#define K_FOGMODE(m) ((uint64_t)(m) << 19)
#define K_FOGMODE_OF(k) ((int)(((k) >> 19) & 3))
#define K_LIGHT (1ull << 21)
#define K_CMAT (1ull << 22)
#define K_LMASK(m) ((uint64_t)(m) << 23)
#define K_LMASK_OF(k) ((unsigned)(((k) >> 23) & 0xff))
#define K_PSPRITE (1ull << 31)
#define K_DRAWTEX (1ull << 32)
#define K_EXT(u) (1ull << (33 + (u)))      /* the unit samples an external texture */

/* what a texture unit samples: 0 nothing, 1 a 2D texture, 2 an external one (which wins when both are on, as in
 * OES_EGL_image_external) */
static int g1_unit_kind(struct g1 *s, int u) {
    if (s->texx[u] && s->boundx[u]) return 2;
    if (!s->tex2d[u]) return 0;
    if (s->bound[u]) return 1;
    /* name 0 is the default texture: it samples once the program has given it an image (until then it is incomplete, i.e. texturing is off) */
    struct g1_tex *d = g1_rec(s, 0, 0);
    return d && d->defined ? 1 : 0;
}
static int g1_unit_on(struct g1 *s, int u) { return g1_unit_kind(s, u) != 0; }
static unsigned g1_unit_name(struct g1 *s, int u) { return g1_unit_kind(s, u) == 2 ? s->boundx[u] : s->bound[u]; }
static int g1_mode_code(int m) { return m == 0x1E01 ? 1 : m == 0x2101 ? 2 : m == 0x0BE2 ? 3 : m == 0x0104 ? 4 : 0; }
static uint64_t g1_key(struct g1 *s, int drawtex) {
    uint64_t k = 0;
    for (int u = 0; u < G1_NTEX; u++) {
        if (!g1_unit_on(s, u)) continue;
        struct g1_tex *r = g1_rec(s, g1_unit_name(s, u), 0);
        k |= K_TEX(u) | K_MODE(u, g1_mode_code(s->texenv[u]));
        if (g1_unit_kind(s, u) == 2) k |= K_EXT(u);
        else if (r && r->alpha) k |= K_ALPHA(u);
        if (!drawtex && s->arr[A_TC0 + u].on) k |= K_TCARR(u);
    }
    if (drawtex) return k | K_DRAWTEX;
    if (s->arr[A_COL].on) k |= K_COLARR;
    if (s->alpha_on) k |= K_ATEST | K_AFUNC(s->alpha_func >= 0x200 && s->alpha_func <= 0x207 ? s->alpha_func - 0x200 : 7);
    if (s->fog_on) k |= K_FOG | K_FOGMODE(s->fog_mode == 0x2601 ? 0 : s->fog_mode == 0x0801 ? 2 : 1);
    if (s->lighting) {
        k |= K_LIGHT | K_LMASK(s->lights & 0xff);
        if (s->cmat) k |= K_CMAT;
        if (s->arr[A_NRM].on) k |= K_NRMARR;
    }
    if (s->psprite && s->psprite_coord && (k & K_TEX(0))) k |= K_PSPRITE;
    return k;
}

static void g1_gen(uint64_t k, struct sb *vs, struct sb *fs) {
    int dt = (k & K_DRAWTEX) != 0, light = (k & K_LIGHT) != 0, cmat = (k & K_CMAT) != 0, colarr = (k & K_COLARR) != 0;
    int fog = (k & K_FOG) != 0, atest = (k & K_ATEST) != 0, psp = (k & K_PSPRITE) != 0;
    unsigned lm = K_LMASK_OF(k);
    int tex[G1_NTEX], tca[G1_NTEX];
    for (int u = 0; u < G1_NTEX; u++) { tex[u] = (k & K_TEX(u)) != 0; tca[u] = dt ? tex[u] : (k & K_TCARR(u)) != 0; }

    /* vertex shader */
    sb_s(vs, "attribute vec4 a_pos;\n");
    if (colarr) sb_s(vs, "attribute vec4 a_col;\n"); else sb_s(vs, "uniform vec4 u_color;\n");
    if ((k & K_NRMARR) && light) sb_s(vs, "attribute vec3 a_nrm;\n"); else if (light) sb_s(vs, "uniform vec3 u_nrm;\n");
    for (int u = 0; u < G1_NTEX; u++) if (tex[u]) {
        sb_s(vs, "attribute vec4 a_tc"); sb_i(vs, u); sb_s(vs, ";\n");
        if (!dt) {
            sb_s(vs, "uniform mat4 u_tx"); sb_i(vs, u); sb_s(vs, ";\n");
            if (!tca[u]) { sb_s(vs, "uniform vec4 u_mtc"); sb_i(vs, u); sb_s(vs, ";\n"); }
        }
        sb_s(vs, "varying highp vec4 v_t"); sb_i(vs, u); sb_s(vs, ";\n");
    }
    if (!dt) sb_s(vs, "uniform mat4 u_mv;\nuniform mat4 u_proj;\nuniform float u_psize;\n");
    sb_s(vs, "varying mediump vec4 v_col;\n");
    if (fog) sb_s(vs, "varying highp float v_fog;\n");
    if (light) {
        sb_s(vs, "uniform vec4 u_lmamb, u_mamb, u_mdif, u_mspc, u_memi;\nuniform float u_mshin;\n");
        sb_s(vs, "uniform vec4 u_lamb[8], u_ldif[8], u_lspc[8], u_lpos[8], u_lspot[8], u_latt[8];\nuniform float u_lcut[8];\n");
    }
    sb_s(vs, "void main() {\n");
    if (dt) {
        sb_s(vs, "  gl_Position = a_pos;\n  v_col = u_color;\n");
        for (int u = 0; u < G1_NTEX; u++) if (tex[u]) { sb_s(vs, "  v_t"); sb_i(vs, u); sb_s(vs, " = a_tc"); sb_i(vs, u); sb_s(vs, ";\n"); }
    } else {
        sb_s(vs, "  vec4 pe = u_mv * a_pos;\n  gl_Position = u_proj * pe;\n  gl_PointSize = u_psize;\n");
        sb_s(vs, colarr ? "  vec4 col = a_col;\n" : "  vec4 col = u_color;\n");
        if (light) {
            sb_s(vs, (k & K_NRMARR) ? "  vec3 n0 = a_nrm;\n" : "  vec3 n0 = u_nrm;\n");
            sb_s(vs, "  vec3 m0 = u_mv[0].xyz, m1 = u_mv[1].xyz, m2 = u_mv[2].xyz;\n"
                     "  vec3 N = normalize(cross(m1, m2) * n0.x + cross(m2, m0) * n0.y + cross(m0, m1) * n0.z);\n"
                     "  if (dot(m0, cross(m1, m2)) < 0.0) N = -N;\n");
            sb_s(vs, cmat ? "  vec4 Am = col, Dm = col;\n" : "  vec4 Am = u_mamb, Dm = u_mdif;\n");
            sb_s(vs, "  vec3 sum = u_memi.rgb + u_lmamb.rgb * Am.rgb;\n");
            for (int i = 0; i < G1_NLIGHT; i++) if (lm & (1u << i)) {
                sb_s(vs, "  {\n    vec3 L; float att = 1.0;\n    if (u_lpos["); sb_i(vs, i);
                sb_s(vs, "].w == 0.0) L = normalize(u_lpos["); sb_i(vs, i);
                sb_s(vs, "].xyz);\n    else { vec3 d = u_lpos["); sb_i(vs, i);
                sb_s(vs, "].xyz - pe.xyz; float dist = length(d); L = d / dist; att = 1.0 / (u_latt["); sb_i(vs, i);
                sb_s(vs, "].x + u_latt["); sb_i(vs, i); sb_s(vs, "].y * dist + u_latt["); sb_i(vs, i);
                sb_s(vs, "].z * dist * dist); }\n    float spot = 1.0;\n    if (u_lcut["); sb_i(vs, i);
                sb_s(vs, "] > -1.5) { float c = dot(-L, u_lspot["); sb_i(vs, i);
                sb_s(vs, "].xyz); spot = c >= u_lcut["); sb_i(vs, i); sb_s(vs, "] ? pow(max(c, 0.0), u_latt["); sb_i(vs, i);
                sb_s(vs, "].w) : 0.0; }\n    float ndl = max(dot(N, L), 0.0);\n    vec3 s = u_lamb["); sb_i(vs, i);
                sb_s(vs, "].rgb * Am.rgb + ndl * u_ldif["); sb_i(vs, i);
                sb_s(vs, "].rgb * Dm.rgb;\n    if (ndl > 0.0) { vec3 h = normalize(L + vec3(0.0, 0.0, 1.0)); s += pow(max(dot(N, h), 0.0), u_mshin) * u_lspc[");
                sb_i(vs, i); sb_s(vs, "].rgb * u_mspc.rgb; }\n    sum += att * spot * s;\n  }\n");
            }
            sb_s(vs, "  col = vec4(sum, Dm.a);\n");
        }
        sb_s(vs, "  v_col = clamp(col, 0.0, 1.0);\n");
        for (int u = 0; u < G1_NTEX; u++) if (tex[u]) {
            sb_s(vs, "  v_t"); sb_i(vs, u); sb_s(vs, " = u_tx"); sb_i(vs, u); sb_s(vs, " * ");
            if (tca[u]) { sb_s(vs, "a_tc"); sb_i(vs, u); } else { sb_s(vs, "u_mtc"); sb_i(vs, u); }
            sb_s(vs, ";\n");
        }
        if (fog) sb_s(vs, "  v_fog = abs(pe.z);\n");
    }
    sb_s(vs, "}\n");

    /* fragment shader */
    if ((k & K_EXT(0)) || (k & K_EXT(1))) sb_s(fs, "#extension GL_OES_EGL_image_external : require\n");
    sb_s(fs, "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n");
    if ((k & K_EXT(0)) || (k & K_EXT(1))) sb_s(fs, "precision lowp samplerExternalOES;\n");
    sb_s(fs, "varying mediump vec4 v_col;\n");
    for (int u = 0; u < G1_NTEX; u++) if (tex[u]) {
        sb_s(fs, "varying highp vec4 v_t"); sb_i(fs, u); sb_s(fs, (k & K_EXT(u)) ? ";\nuniform samplerExternalOES u_s" : ";\nuniform sampler2D u_s"); sb_i(fs, u);
        sb_s(fs, ";\nuniform vec4 u_tec"); sb_i(fs, u); sb_s(fs, ";\n");
    }
    if (fog) sb_s(fs, "varying highp float v_fog;\nuniform vec4 u_fogp;\nuniform vec4 u_fogc;\n");
    if (atest) sb_s(fs, "uniform float u_aref;\n");
    sb_s(fs, "void main() {\n  vec4 c = v_col;\n");
    for (int u = 0; u < G1_NTEX; u++) if (tex[u]) {
        int m = K_MODE_OF(k, u), alpha = (k & K_ALPHA(u)) != 0;
        sb_s(fs, "  { vec4 t = ");
        if (psp && u == 0 && !dt) sb_s(fs, "texture2D(u_s0, gl_PointCoord);\n");
        else { sb_s(fs, "texture2DProj(u_s"); sb_i(fs, u); sb_s(fs, ", v_t"); sb_i(fs, u); sb_s(fs, ");\n"); }
        if (alpha) sb_s(fs, m == 1 ? "    c.a = t.a;\n" : "    c.a *= t.a;\n");          /* (an ALPHA texture leaves the colour alone) */
        else switch (m) {
        case 1: sb_s(fs, "    c = t;\n"); break;
        case 2: sb_s(fs, "    c.rgb = mix(c.rgb, t.rgb, t.a);\n"); break;
        case 3: sb_s(fs, "    c.rgb = mix(c.rgb, u_tec"); sb_i(fs, u); sb_s(fs, ".rgb, t.rgb); c.a *= t.a;\n"); break;
        case 4: sb_s(fs, "    c.rgb += t.rgb; c.a *= t.a;\n"); break;
        default: sb_s(fs, "    c *= t;\n"); break;
        }
        sb_s(fs, "  }\n");
    }
    if (fog) {
        int fm = K_FOGMODE_OF(k);
        sb_s(fs, fm == 0 ? "  float f = (u_fogp.z - v_fog) * u_fogp.w;\n"
               : fm == 2 ? "  float f = exp(-(u_fogp.x * v_fog) * (u_fogp.x * v_fog));\n"
                         : "  float f = exp(-u_fogp.x * v_fog);\n");
        sb_s(fs, "  c.rgb = mix(u_fogc.rgb, c.rgb, clamp(f, 0.0, 1.0));\n");
    }
    if (atest) {
        static const char *const op[8] = { 0, "<", "==", "<=", ">", "!=", ">=", 0 };
        int f = K_AFUNC_OF(k);
        if (f == 0) sb_s(fs, "  discard;\n");
        else if (f != 7) { sb_s(fs, "  if (!(c.a "); sb_s(fs, op[f]); sb_s(fs, " u_aref)) discard;\n"); }
    }
    sb_s(fs, "  gl_FragColor = c;\n}\n");
}

static int g1_uses(const char *vs, const char *fs, const char *name) { return strstr(vs, name) || strstr(fs, name); }

static void g1_build(struct g1 *s, struct g1_prog *p, uint64_t key) {
    char *vsb = malloc(12000), *fsb = malloc(6000);
    struct sb vs = { vsb, 0, 12000 }, fs = { fsb, 0, 6000 };
    if (!vsb || !fsb) { free(vsb); free(fsb); p->ok = 0; return; }
    vsb[0] = fsb[0] = 0;
    g1_gen(key, &vs, &fs);
    p->key = key; p->ok = 0; p->prog = 0;
    for (int i = 0; i < L_N; i++) p->loc[i] = -1;
    for (int i = 0; i < U_N; i++) p->sent[i] = 0;
    GLuint v = e_glCreateShader(0x8B31), f = e_glCreateShader(0x8B30), pr = e_glCreateProgram();
    const GLchar *src = vsb;
    e_glShaderSource(v, 1, &src, 0); e_glCompileShader(v);
    src = fsb;
    e_glShaderSource(f, 1, &src, 0); e_glCompileShader(f);
    e_glAttachShader(pr, v); e_glAttachShader(pr, f);
    e_glBindAttribLocation(pr, A_POS, "a_pos"); e_glBindAttribLocation(pr, A_COL, "a_col");
    e_glBindAttribLocation(pr, A_NRM, "a_nrm"); e_glBindAttribLocation(pr, A_TC0, "a_tc0");
    e_glBindAttribLocation(pr, A_TC1, "a_tc1");
    e_glLinkProgram(pr);
    GLint ok = 0;
    e_glGetProgramiv(pr, 0x8B82 /* GL_LINK_STATUS */, &ok);
    if (!ok) {
        char log[600]; GLsizei n = 0;
        log[0] = 0;
        e_glGetProgramInfoLog(pr, 599, &n, log); log[n > 0 && n < 600 ? n : 0] = 0;
        LOG("GLES 1 emulation: a program (key %x%08x) did not link: %s", (unsigned)(key >> 32), (unsigned)key, log);
        n = 0; log[0] = 0;
        e_glGetShaderInfoLog(v, 599, &n, log); log[n > 0 && n < 600 ? n : 0] = 0;
        if (log[0]) LOG("  vertex shader: %s", log);
        n = 0; log[0] = 0;
        e_glGetShaderInfoLog(f, 599, &n, log); log[n > 0 && n < 600 ? n : 0] = 0;
        if (log[0]) LOG("  fragment shader: %s", log);
    }
    e_glDeleteShader(v); e_glDeleteShader(f);
    if (ok) {
        p->prog = pr; p->ok = 1;
        e_glUseProgram(pr); s->cur_prog = pr;
        for (int i = 0; i < L_N; i++) if (g1_uses(vsb, fsb, kLoc[i].name)) {
            /* (the arrays are looked up by their first element) */
            p->loc[i] = e_glGetUniformLocation(pr, kLoc[i].name);
        }
        if (p->loc[L_S0] >= 0) e_glUniform1i(p->loc[L_S0], 0);
        if (p->loc[L_S1] >= 0) e_glUniform1i(p->loc[L_S1], 1);
    } else e_glDeleteProgram(pr);
    free(vsb); free(fsb);
}
static struct g1_prog *g1_program(struct g1 *s, uint64_t key) {
    for (int i = 0; i < s->nprog; i++) if (s->prog[i].key == key) return &s->prog[i];
    struct g1_prog *p;
    if (s->nprog < G1_NPROG) p = &s->prog[s->nprog++];
    else {
        p = &s->prog[s->evict++ % G1_NPROG];
        if (p->ok) { e_glDeleteProgram(p->prog); if (s->cur_prog == p->prog) s->cur_prog = 0; }
    }
    g1_build(s, p, key);
    return p;
}

/* the uniforms of one program that changed since it last drew */
static void g1_upload(struct g1 *s, struct g1_prog *p) {
    unsigned dirty = 0;
    for (int g = 0; g < U_N; g++) if (p->sent[g] != s->v[g]) dirty |= 1u << g;
    if (!dirty) return;
    for (int i = 0; i < L_N; i++) {
        int loc = p->loc[i], g = kLoc[i].group;
        if (loc < 0 || g < 0 || !(dirty & (1u << g))) continue;
        switch (i) {
        case L_MV: e_glUniformMatrix4fv(loc, 1, 0, s->mv[s->mvd]); break;
        case L_PROJ: e_glUniformMatrix4fv(loc, 1, 0, s->pr[s->prd]); break;
        case L_TX0: e_glUniformMatrix4fv(loc, 1, 0, s->tx[0][s->txd[0]]); break;
        case L_TX1: e_glUniformMatrix4fv(loc, 1, 0, s->tx[1][s->txd[1]]); break;
        case L_COLOR: e_glUniform4fv(loc, 1, s->color); break;
        case L_NRM: e_glUniform3fv(loc, 1, s->normal); break;
        case L_MTC0: e_glUniform4fv(loc, 1, s->mtc[0]); break;
        case L_MTC1: e_glUniform4fv(loc, 1, s->mtc[1]); break;
        case L_PSIZE: e_glUniform1f(loc, s->psize); break;
        case L_AREF: e_glUniform1f(loc, s->alpha_ref); break;
        case L_FOGP: { float f[4] = { s->fog_density, s->fog_start, s->fog_end, s->fog_end != s->fog_start ? 1.f / (s->fog_end - s->fog_start) : 1.f }; e_glUniform4fv(loc, 1, f); break; }
        case L_FOGC: e_glUniform4fv(loc, 1, s->fog_color); break;
        case L_TEC0: e_glUniform4fv(loc, 1, s->tec[0]); break;
        case L_TEC1: e_glUniform4fv(loc, 1, s->tec[1]); break;
        case L_LMAMB: e_glUniform4fv(loc, 1, s->lm_amb); break;
        case L_MAMB: e_glUniform4fv(loc, 1, s->mat_amb); break;
        case L_MDIF: e_glUniform4fv(loc, 1, s->mat_dif); break;
        case L_MSPC: e_glUniform4fv(loc, 1, s->mat_spc); break;
        case L_MEMI: e_glUniform4fv(loc, 1, s->mat_emi); break;
        case L_MSHIN: e_glUniform1f(loc, s->mat_shin); break;
        case L_LAMB: case L_LDIF: case L_LSPC: case L_LPOS: case L_LSPOT: case L_LATT: {
            float a[G1_NLIGHT * 4];
            for (int l = 0; l < G1_NLIGHT; l++) {
                const struct g1_light *L = &s->light[l];
                const float *v = i == L_LAMB ? L->amb : i == L_LDIF ? L->dif : i == L_LSPC ? L->spc : i == L_LPOS ? L->pos : i == L_LSPOT ? L->dir : L->att;
                memcpy(a + l * 4, v, 16);
            }
            e_glUniform4fv(loc, G1_NLIGHT, a); break; }
        case L_LCUT: {
            float c[G1_NLIGHT], sn, cs;
            for (int l = 0; l < G1_NLIGHT; l++) {
                float cut = s->light[l].cut;
                if (cut >= 180.f) c[l] = -2.f; else { g1_sincos(cut * 0.0174532925199f, &sn, &cs); c[l] = cs; }
            }
            e_glUniform1fv(loc, G1_NLIGHT, c); break; }
        }
    }
    for (int g = 0; g < U_N; g++) if (dirty & (1u << g)) p->sent[g] = s->v[g];
}

/* the arrays the program reads, as generic attributes; the others off. Buffer bindings the program had are put back. */
static void g1_arrays(struct g1 *s, uint64_t key) {
    struct gstate *g = G();
    unsigned saved = g->array;
    for (int a = 0; a < A_N; a++) {
        const struct g1_arr *r = &s->arr[a];
        int want = a == A_POS ? 1 : a == A_COL ? (key & K_COLARR) != 0 : a == A_NRM ? (key & K_NRMARR) != 0 :
                   (key & K_TCARR(a - A_TC0)) != 0;
        if (want && a != A_POS && !r->on) want = 0;
        if (a == A_POS) want = r->on;
        if (want) {
            if (g->array != r->buf) e_glBindBuffer(0x8892, r->buf);
            e_glVertexAttribPointer((GLuint)a, r->size, (GLenum)r->type, (GLboolean)r->norm, r->stride, r->ptr);
            e_glEnableVertexAttribArray((GLuint)a);
        } else e_glDisableVertexAttribArray((GLuint)a);
    }
    if (g->array != saved) e_glBindBuffer(0x8892, saved);
}
/* before a draw: the program for the state in use, its uniforms, its arrays. 0: nothing to draw */
static int g1_prepare(struct g1 *s) {
    if (!s->arr[A_POS].on || !s->arr[A_POS].ptr + 0 == 0) { if (!s->arr[A_POS].on) return 0; }
    uint64_t key = g1_key(s, 0);
    struct g1_prog *p = g1_program(s, key);
    if (!p->ok) { if (!s->said_nodraw) { s->said_nodraw = 1; LOG("GLES 1 emulation: a draw is dropped (no program)"); } return 0; }
    if (s->cur_prog != p->prog) { e_glUseProgram(p->prog); s->cur_prog = p->prog; }
    g1_upload(s, p);
    g1_arrays(s, key);
    return 1;
}
static void e1_glDrawArrays(GLenum mode, GLint first, GLsizei count) { struct g1 *s = G1(); if (g1_prepare(s)) e_glDrawArrays(mode, first, count); }
static void e1_glDrawElements(GLenum mode, GLsizei count, GLenum type, const void *indices) { struct g1 *s = G1(); if (g1_prepare(s)) e_glDrawElements(mode, count, type, indices); }

/* GL_OES_draw_texture: a screen-aligned rectangle at window coordinates, the texture's crop rectangle as its texture coordinates */
static void g1_drawtex(float x, float y, float z, float w, float h) {
    struct g1 *s = G1();
    if (w <= 0.f || h <= 0.f) return;
    int vp[4];
    g1_viewport(s, vp);
    if (vp[2] <= 0 || vp[3] <= 0) return;
    float pos[16], tc[G1_NTEX][8];
    float x0 = 2.f * (x - (float)vp[0]) / (float)vp[2] - 1.f, x1 = 2.f * (x + w - (float)vp[0]) / (float)vp[2] - 1.f;
    float y0 = 2.f * (y - (float)vp[1]) / (float)vp[3] - 1.f, y1 = 2.f * (y + h - (float)vp[1]) / (float)vp[3] - 1.f;
    float zn = (z < 0.f ? 0.f : z > 1.f ? 1.f : z) * 2.f - 1.f;
    float q[16] = { x0, y0, zn, 1.f, x1, y0, zn, 1.f, x0, y1, zn, 1.f, x1, y1, zn, 1.f };
    memcpy(pos, q, sizeof(q));
    for (int u = 0; u < G1_NTEX; u++) {
        if (!g1_unit_on(s, u)) continue;
        struct g1_tex *r = g1_rec(s, g1_unit_name(s, u), 0);
        if (g1_unit_kind(s, u) == 2 && (!r || r->w <= 0 || r->h <= 0)) {      /* (an external texture's size is the buffer's: the whole of it) */
            static const float whole[8] = { 0, 0, 1, 0, 0, 1, 1, 1 };
            memcpy(tc[u], whole, sizeof(whole));
            continue;
        }
        if (!r || r->w <= 0 || r->h <= 0) return;          /* (a texture this driver did not see made: no size to take the crop rectangle from) */
        float u0 = (float)r->crop[0] / (float)r->w, v0 = (float)r->crop[1] / (float)r->h;
        float u1 = (float)(r->crop[0] + r->crop[2]) / (float)r->w, v1 = (float)(r->crop[1] + r->crop[3]) / (float)r->h;
        float t[8] = { u0, v0, u1, v0, u0, v1, u1, v1 };
        memcpy(tc[u], t, sizeof(t));
    }
    uint64_t key = g1_key(s, 1);
    struct g1_prog *p = g1_program(s, key);
    if (!p->ok) return;
    if (s->cur_prog != p->prog) { e_glUseProgram(p->prog); s->cur_prog = p->prog; }
    g1_upload(s, p);
    struct gstate *g = G();
    unsigned saved = g->array;
    if (saved) e_glBindBuffer(0x8892, 0);
    e_glVertexAttribPointer(A_POS, 4, 0x1406, 0, 0, pos);
    e_glEnableVertexAttribArray(A_POS);
    e_glDisableVertexAttribArray(A_COL); e_glDisableVertexAttribArray(A_NRM);
    for (int u = 0; u < G1_NTEX; u++) {
        if (key & K_TEX(u)) { e_glVertexAttribPointer((GLuint)(A_TC0 + u), 2, 0x1406, 0, 0, tc[u]); e_glEnableVertexAttribArray((GLuint)(A_TC0 + u)); }
        else e_glDisableVertexAttribArray((GLuint)(A_TC0 + u));
    }
    e_glDrawArrays(5 /* GL_TRIANGLE_STRIP */, 0, 4);
    if (saved) e_glBindBuffer(0x8892, saved);
}
static void e1_glDrawTexfOES(GLfloat x, GLfloat y, GLfloat z, GLfloat w, GLfloat h) { g1_drawtex(x, y, z, w, h); }
static void e1_glDrawTexfvOES(const GLfloat *c) { if (c) g1_drawtex(c[0], c[1], c[2], c[3], c[4]); }
static void e1_glDrawTexiOES(GLint x, GLint y, GLint z, GLint w, GLint h) { g1_drawtex((float)x, (float)y, (float)z, (float)w, (float)h); }
static void e1_glDrawTexivOES(const GLint *c) { if (c) g1_drawtex((float)c[0], (float)c[1], (float)c[2], (float)c[3], (float)c[4]); }
static void e1_glDrawTexsOES(GLshort x, GLshort y, GLshort z, GLshort w, GLshort h) { g1_drawtex((float)x, (float)y, (float)z, (float)w, (float)h); }
static void e1_glDrawTexsvOES(const GLshort *c) { if (c) g1_drawtex((float)c[0], (float)c[1], (float)c[2], (float)c[3], (float)c[4]); }
static void e1_glDrawTexxOES(GLfixed x, GLfixed y, GLfixed z, GLfixed w, GLfixed h) { g1_drawtex(fxf(x), fxf(y), fxf(z), fxf(w), fxf(h)); }
static void e1_glDrawTexxvOES(const GLfixed *c) { if (c) g1_drawtex(fxf(c[0]), fxf(c[1]), fxf(c[2]), fxf(c[3]), fxf(c[4])); }

/* ---- questions with GLES 1 names ---- */
static const char kEs1Version[] = "OpenGL ES-CM 1.1 AEmulator GL bridge";
static const char kEs1Ext[] =
    "GL_OES_blend_equation_separate GL_OES_blend_func_separate GL_OES_blend_subtract GL_OES_byte_coordinates "
    "GL_OES_compressed_ETC1_RGB8_texture GL_OES_depth24 GL_OES_draw_texture GL_OES_fixed_point GL_OES_framebuffer_object "
    "GL_OES_matrix_get GL_OES_packed_depth_stencil GL_OES_point_sprite GL_OES_read_format GL_OES_rgb8_rgba8 "
    "GL_OES_single_precision GL_OES_stencil8 GL_OES_texture_mirrored_repeat GL_OES_texture_npot GL_OES_EGL_image "
    "GL_OES_EGL_image_external "
    "GL_EXT_texture_format_BGRA8888";
static const GLubyte *e1_glGetString(GLenum name) {
    if (name == 0x1F02) return (const GLubyte *)kEs1Version;
    if (name == 0x1F03) return (const GLubyte *)kEs1Ext;
    return e_glGetString(name);
}
/* a GLES 1 query the driver answers itself: the values (up to 16) and how many; 0: the host's answer */
static int g1_query(struct g1 *s, GLenum pname, float *o) {
    switch (pname) {
    case 0x0BA6: memcpy(o, s->mv[s->mvd], 64); return 16;
    case 0x0BA7: memcpy(o, s->pr[s->prd], 64); return 16;
    case 0x0BA8: memcpy(o, s->tx[s->active][s->txd[s->active]], 64); return 16;
    case 0x0BA0: o[0] = (float)s->mode; return 1;
    case 0x0BA3: o[0] = (float)(s->mvd + 1); return 1;
    case 0x0BA4: o[0] = (float)(s->prd + 1); return 1;
    case 0x0BA5: o[0] = (float)(s->txd[s->active] + 1); return 1;
    case 0x0D36: o[0] = (float)G1_MAXMV; return 1;
    case 0x0D38: case 0x0D39: o[0] = 2.f; return 1;
    case 0x84E2: o[0] = (float)G1_NTEX; return 1;
    case 0x0D31: o[0] = (float)G1_NLIGHT; return 1;
    case 0x0D32: o[0] = 0.f; return 1;
    case 0x84E1: o[0] = (float)(0x84C0 + s->client); return 1;
    case 0x0B00: memcpy(o, s->color, 16); return 4;
    case 0x0B02: memcpy(o, s->normal, 12); return 3;
    case 0x0BC1: o[0] = (float)s->alpha_func; return 1;
    case 0x0BC2: o[0] = s->alpha_ref; return 1;
    case 0x0B54: o[0] = (float)s->shade; return 1;
    case 0x0B11: o[0] = s->psize; return 1;
    case 0x0B65: o[0] = (float)s->fog_mode; return 1;
    case 0x0B62: o[0] = s->fog_density; return 1;
    case 0x0B63: o[0] = s->fog_start; return 1;
    case 0x0B64: o[0] = s->fog_end; return 1;
    case 0x0B66: memcpy(o, s->fog_color, 16); return 4;
    case 0x0B53: memcpy(o, s->lm_amb, 16); return 4;
    case 0x8072: o[0] = 0; return 0;
    }
    return 0;
}
static void e1_glGetFloatv(GLenum pname, GLfloat *d) {
    float v[16];
    int n = g1_query(G1(), pname, v);
    if (n && d) memcpy(d, v, (size_t)n * 4); else e_glGetFloatv(pname, d);
}
static void e1_glGetIntegerv(GLenum pname, GLint *d) {
    float v[16];
    int n = g1_query(G1(), pname, v);
    if (n && d) {
        /* (a colour or a normal is mapped to the integer range, the rest is rounded) */
        for (int i = 0; i < n; i++)
            d[i] = (pname == 0x0B00 || pname == 0x0B02 || pname == 0x0B66 || pname == 0x0B53) ? (GLint)(v[i] * 2147483647.f) : (GLint)(v[i] < 0.f ? v[i] - 0.5f : v[i] + 0.5f);
    } else e_glGetIntegerv(pname, d);
}
static void e1_glGetFixedv(GLenum pname, GLfixed *d) {
    float v[16];
    int n = g1_query(G1(), pname, v);
    if (n && d) for (int i = 0; i < n; i++) d[i] = (GLfixed)(v[i] * 65536.f);
    else { GLfloat f = 0; e_glGetFloatv(pname, &f); if (d) d[0] = (GLfixed)(f * 65536.f); }
}
static void e1_glGetBooleanv(GLenum pname, GLboolean *d) {
    float v[16];
    int n = g1_query(G1(), pname, v);
    if (n && d) for (int i = 0; i < n; i++) d[i] = v[i] != 0.f;
    else e_glGetBooleanv(pname, d);
}

/* ---- the table of a GLES 1 context: the encoders of the core, with the fixed-function entries over them ---- */
static void g1_install(void **t) {
#define SET(name, fn) t[IDX_##name] = (void *)(fn)
#define SET2(name, fn) SET(name, fn); SET(name##OES, fn)
    SET(glMatrixMode, e1_glMatrixMode); SET(glLoadIdentity, e1_glLoadIdentity); SET(glLoadMatrixf, e1_glLoadMatrixf);
    SET(glMultMatrixf, e1_glMultMatrixf); SET(glPushMatrix, e1_glPushMatrix); SET(glPopMatrix, e1_glPopMatrix);
    SET(glTranslatef, e1_glTranslatef); SET(glScalef, e1_glScalef); SET(glRotatef, e1_glRotatef);
    SET(glOrthof, e1_glOrthof); SET(glFrustumf, e1_glFrustumf); SET(glOrthofOES, e1_glOrthof); SET(glFrustumfOES, e1_glFrustumf);
    SET2(glLoadMatrixx, e1_glLoadMatrixx); SET2(glMultMatrixx, e1_glMultMatrixx); SET2(glTranslatex, e1_glTranslatex);
    SET2(glScalex, e1_glScalex); SET2(glRotatex, e1_glRotatex); SET2(glOrthox, e1_glOrthox); SET2(glFrustumx, e1_glFrustumx);
    SET(glColor4f, e1_glColor4f); SET(glColor4ub, e1_glColor4ub); SET2(glColor4x, e1_glColor4x);
    SET(glNormal3f, e1_glNormal3f); SET2(glNormal3x, e1_glNormal3x);
    SET(glMultiTexCoord4f, e1_glMultiTexCoord4f); SET2(glMultiTexCoord4x, e1_glMultiTexCoord4x);
    SET(glPointSize, e1_glPointSize); SET2(glPointSizex, e1_glPointSizex);
    SET(glAlphaFunc, e1_glAlphaFunc); SET2(glAlphaFuncx, e1_glAlphaFuncx);
    SET(glShadeModel, e1_glShadeModel); SET(glLogicOp, e1_glLogicOp);
    SET(glClipPlanef, e1_glClipPlanef); SET(glClipPlanefOES, e1_glClipPlanef); SET2(glClipPlanex, e1_glClipPlanex);
    SET(glGetClipPlanef, e1_glGetClipPlanef); SET(glGetClipPlanefOES, e1_glGetClipPlanef); SET2(glGetClipPlanex, e1_glGetClipPlanex);
    SET(glPointParameterf, e1_glPointParameterf); SET(glPointParameterfv, e1_glPointParameterfv);
    SET2(glPointParameterx, e1_glPointParameterx); SET2(glPointParameterxv, e1_glPointParameterxv);
    SET(glPointSizePointerOES, e1_glPointSizePointerOES);
    SET2(glLineWidthx, e1_glLineWidthx); SET2(glPolygonOffsetx, e1_glPolygonOffsetx); SET2(glSampleCoveragex, e1_glSampleCoveragex);
    SET2(glClearColorx, e1_glClearColorx); SET2(glClearDepthx, e1_glClearDepthx); SET2(glDepthRangex, e1_glDepthRangex);
    SET(glHint, e1_glHint);
    SET(glFogf, e1_glFogf); SET(glFogfv, e1_glFogfv); SET2(glFogx, e1_glFogx); SET2(glFogxv, e1_glFogxv);
    SET(glLightf, e1_glLightf); SET(glLightfv, e1_glLightfv); SET2(glLightx, e1_glLightx); SET2(glLightxv, e1_glLightxv);
    SET(glLightModelf, e1_glLightModelf); SET(glLightModelfv, e1_glLightModelfv);
    SET2(glLightModelx, e1_glLightModelx); SET2(glLightModelxv, e1_glLightModelxv);
    SET(glMaterialf, e1_glMaterialf); SET(glMaterialfv, e1_glMaterialfv); SET2(glMaterialx, e1_glMaterialx); SET2(glMaterialxv, e1_glMaterialxv);
    SET(glGetLightfv, e1_glGetLightfv); SET2(glGetLightxv, e1_glGetLightxv);
    SET(glGetMaterialfv, e1_glGetMaterialfv); SET2(glGetMaterialxv, e1_glGetMaterialxv);
    SET(glEnable, e1_glEnable); SET(glDisable, e1_glDisable); SET(glIsEnabled, e1_glIsEnabled);
    SET(glActiveTexture, e1_glActiveTexture); SET(glClientActiveTexture, e1_glClientActiveTexture);
    SET(glBindTexture, e1_glBindTexture); SET(glDeleteTextures, e1_glDeleteTextures);
    SET(glTexImage2D, e1_glTexImage2D); SET(glTexSubImage2D, e1_glTexSubImage2D);
    SET(glCompressedTexImage2D, e1_glCompressedTexImage2D); SET(glCopyTexImage2D, e1_glCopyTexImage2D);
    SET(glTexParameteri, e1_glTexParameteri); SET(glTexParameterf, e1_glTexParameterf);
    SET(glTexParameteriv, e1_glTexParameteriv); SET(glTexParameterfv, e1_glTexParameterfv);
    SET2(glTexParameterx, e1_glTexParameterx); SET2(glTexParameterxv, e1_glTexParameterxv);
    SET(glGetTexParameteriv, e1_glGetTexParameteriv); SET2(glGetTexParameterxv, e1_glGetTexParameterxv);
    SET(glTexEnvf, e1_glTexEnvf); SET(glTexEnvfv, e1_glTexEnvfv); SET(glTexEnvi, e1_glTexEnvi); SET(glTexEnviv, e1_glTexEnviv);
    SET2(glTexEnvx, e1_glTexEnvx); SET2(glTexEnvxv, e1_glTexEnvxv);
    SET(glGetTexEnvfv, e1_glGetTexEnvfv); SET(glGetTexEnviv, e1_glGetTexEnviv); SET2(glGetTexEnvxv, e1_glGetTexEnvxv);
    SET(glEnableClientState, e1_glEnableClientState); SET(glDisableClientState, e1_glDisableClientState);
    SET(glVertexPointer, e1_glVertexPointer); SET(glNormalPointer, e1_glNormalPointer);
    SET(glColorPointer, e1_glColorPointer); SET(glTexCoordPointer, e1_glTexCoordPointer); SET(glGetPointerv, e1_glGetPointerv);
    SET(glViewport, e1_glViewport); SET(glDrawArrays, e1_glDrawArrays); SET(glDrawElements, e1_glDrawElements);
    SET(glDrawTexfOES, e1_glDrawTexfOES); SET(glDrawTexfvOES, e1_glDrawTexfvOES); SET(glDrawTexiOES, e1_glDrawTexiOES);
    SET(glDrawTexivOES, e1_glDrawTexivOES); SET(glDrawTexsOES, e1_glDrawTexsOES); SET(glDrawTexsvOES, e1_glDrawTexsvOES);
    SET(glDrawTexxOES, e1_glDrawTexxOES); SET(glDrawTexxvOES, e1_glDrawTexxvOES);
    SET(glGetString, e1_glGetString); SET(glGetFloatv, e1_glGetFloatv); SET(glGetIntegerv, e1_glGetIntegerv);
    SET(glGetBooleanv, e1_glGetBooleanv); SET2(glGetFixedv, e1_glGetFixedv);
    /* GL_OES_framebuffer_object, GL_OES_blend_*, ...: the core functions under their OES names */
    SET(glBindFramebufferOES, e_glBindFramebuffer); SET(glBindRenderbufferOES, e_glBindRenderbuffer);
    SET(glCheckFramebufferStatusOES, e_glCheckFramebufferStatus); SET(glDeleteFramebuffersOES, e_glDeleteFramebuffers);
    SET(glDeleteRenderbuffersOES, e_glDeleteRenderbuffers); SET(glFramebufferRenderbufferOES, e_glFramebufferRenderbuffer);
    SET(glFramebufferTexture2DOES, e_glFramebufferTexture2D); SET(glGenFramebuffersOES, e_glGenFramebuffers);
    SET(glGenRenderbuffersOES, e_glGenRenderbuffers); SET(glGenerateMipmapOES, e_glGenerateMipmap);
    SET(glGetFramebufferAttachmentParameterivOES, e_glGetFramebufferAttachmentParameteriv);
    SET(glGetRenderbufferParameterivOES, e_glGetRenderbufferParameteriv); SET(glIsFramebufferOES, e_glIsFramebuffer);
    SET(glIsRenderbufferOES, e_glIsRenderbuffer); SET(glRenderbufferStorageOES, e_glRenderbufferStorage);
    SET(glBlendEquationOES, e_glBlendEquation); SET(glBlendEquationSeparateOES, e_glBlendEquationSeparate);
    SET(glBlendFuncSeparateOES, e_glBlendFuncSeparate); SET(glClearDepthfOES, e_glClearDepthf); SET(glDepthRangefOES, e_glDepthRangef);
#undef SET2
#undef SET
}
#endif
