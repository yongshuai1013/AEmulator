/* Host-side test for gatekeeper_soft.c: crypto vectors (SHA-256, HMAC, scrypt) plus the
 * full legacy-device behaviour (enroll/verify/throttle/re-enroll/persistence/tamper).
 * Build: gcc -DGK_HOST_TEST -O1 -o /tmp/test_gk test_gatekeeper.c && /tmp/test_gk
 * The module's first state directory is /data/misc/gatekeeper; the test host must let it
 * create that directory (the AEmulator VM runs as root, so it can). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define GK_HOST_TEST 1
#include "gatekeeper_soft.c"

static int failures;
#define CHECK(cond) do { \
    if (!(cond)) { printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); failures++; } \
    else { printf("ok: %s\n", #cond); } \
} while (0)

static int hexeq(const uint8_t *a, const char *hex, unsigned n) {
    for (unsigned i = 0; i < n; i++) {
        unsigned v;
        if (sscanf(hex + 2 * i, "%2x", &v) != 1) return 0;
        if (a[i] != (uint8_t)v) return 0;
    }
    return 1;
}

static void reset_state(const char *wipe_file) {
    if (wipe_file) unlink("/data/misc/gatekeeper/aemu_gatekeeper.state");
    pthread_mutex_lock(g_lock);
    memset(&g_state, 0, sizeof(g_state));
    g_state_loaded = 0;
    g_state_persistent = 0;
    g_state_path[0] = 0;
    pthread_mutex_unlock(g_lock);
}

int main(void) {
    uint8_t out[64];

    /* --- SHA-256 vectors --- */
    sha256((const uint8_t *)"", 0, out);
    CHECK(hexeq(out, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", 32));
    sha256((const uint8_t *)"abc", 3, out);
    CHECK(hexeq(out, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 32));
    {
        struct sha256_ctx c;
        uint8_t chunk[1000];
        memset(chunk, 'a', sizeof(chunk));
        sha256_init(&c);
        for (int i = 0; i < 1000; i++) sha256_update(&c, chunk, sizeof(chunk));
        sha256_final(&c, out);
        CHECK(hexeq(out, "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", 32));
    }

    /* --- HMAC-SHA256 RFC 4231 --- */
    {
        uint8_t key[20];
        memset(key, 0x0b, sizeof(key));
        hmac_sha256(key, 20, (const uint8_t *)"Hi There", 8, out);
        CHECK(hexeq(out, "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", 32));
        hmac_sha256((const uint8_t *)"Jefe", 4,
                    (const uint8_t *)"what do ya want for nothing?", 28, out);
        CHECK(hexeq(out, "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", 32));
    }

    /* --- scrypt RFC 7914 vectors --- */
    CHECK(gk_scrypt((const uint8_t *)"", 0, (const uint8_t *)"", 0, 16, 1, 1, out, 64) == 0);
    CHECK(hexeq(out, "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442fc"
                     "d0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906", 64));
    CHECK(gk_scrypt((const uint8_t *)"password", 8, (const uint8_t *)"NaCl", 4,
                    1024, 8, 16, out, 64) == 0);
    CHECK(hexeq(out, "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b3731622e"
                     "af30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640", 64));

    /* --- device flow --- */
    reset_state("wipe");
    struct hw_device *raw = 0;
    CHECK(HMI.common.methods->open(&HMI.common, "lights", &raw) == -GK_EINVAL);
    CHECK(HMI.common.methods->open(&HMI.common, "gatekeeper", &raw) == 0 && raw != 0);
    struct gatekeeper_device *dev = (struct gatekeeper_device *)raw;
    CHECK(strcmp(HMI.common.id, "gatekeeper") == 0);

    const uint8_t *pw1 = (const uint8_t *)"1234";
    const uint8_t *pw2 = (const uint8_t *)"9876";
    uint8_t *handle = 0;
    uint32_t handle_len = 0;
    CHECK(dev->enroll(dev, 0, 0, 0, 0, 0, pw1, 4, &handle, &handle_len) == 0);
    CHECK(handle_len == 58);
    struct gk_handle *h = (struct gk_handle *)handle;
    CHECK(h->version == 2 && h->hardware_backed == 1 && h->user_id != 0);
    uint64_t sid = h->user_id;

    uint8_t *token = 0;
    uint32_t token_len = 0;
    bool reenroll = true;
    CHECK(dev->verify(dev, 0, 42, handle, handle_len, pw1, 4, &token, &token_len, &reenroll) == 0);
    CHECK(token_len == 69 && reenroll == false);
    struct gk_auth_token *tok = (struct gk_auth_token *)token;
    CHECK(tok->version == 0 && tok->challenge == 42 && tok->user_id == sid);
    {
        const uint8_t *tb = token;
        CHECK(tb[25] == 0 && tb[26] == 0 && tb[27] == 0 && tb[28] == 1); /* authenticator_type, network order */
        uint8_t mac[32];
        hmac_sha256(g_state.key, 32, token, 37, mac);
        CHECK(ct_equal(mac, tok->hmac, 32));
    }
    free(token);
    token = 0;

    /* wrong password: plain failures first (counter 1..4 -> -EINVAL) */
    for (int i = 0; i < 4; i++)
        CHECK(dev->verify(dev, 0, 0, handle, handle_len, pw2, 4, &token, &token_len, &reenroll) == -GK_EINVAL);
    /* 5th failure trips the 30 s timeout (returned as a positive value) */
    CHECK(dev->verify(dev, 0, 0, handle, handle_len, pw2, 4, &token, &token_len, &reenroll) == 30000);
    /* inside the window even the right password is refused with the remaining time */
    int gated = dev->verify(dev, 0, 0, handle, handle_len, pw1, 4, &token, &token_len, &reenroll);
    CHECK(gated > 0 && gated <= 30000);
    /* wind the clock back: move the record's timestamp 31 s into the past */
    pthread_mutex_lock(g_lock);
    for (uint32_t i = 0; i < g_state.record_count; i++)
        if (g_state.records[i].uid == 0) g_state.records[i].last_checked_ms -= 31000;
    pthread_mutex_unlock(g_lock);
    CHECK(dev->verify(dev, 0, 0, handle, handle_len, pw1, 4, &token, &token_len, &reenroll) == 0);
    free(token);
    token = 0;

    /* tampered handles fail */
    {
        uint8_t copy[58];
        memcpy(copy, handle, 58);
        copy[3] ^= 0x40;   /* inside user_id */
        CHECK(dev->verify(dev, 0, 0, copy, 58, pw1, 4, &token, &token_len, &reenroll) == -GK_EINVAL);
        memcpy(copy, handle, 58);
        copy[40] ^= 0x01;  /* inside signature */
        CHECK(dev->verify(dev, 0, 0, copy, 58, pw1, 4, &token, &token_len, &reenroll) == -GK_EINVAL);
    }

    /* re-enroll: wrong current password fails, right one keeps the secure user id */
    uint8_t *handle2 = 0;
    uint32_t handle2_len = 0;
    CHECK(dev->enroll(dev, 0, handle, handle_len, pw2, 4, pw2, 4, &handle2, &handle2_len) == -GK_EINVAL);
    CHECK(dev->enroll(dev, 0, handle, handle_len, pw1, 4, pw2, 4, &handle2, &handle2_len) == 0);
    struct gk_handle *h2 = (struct gk_handle *)handle2;
    CHECK(handle2_len == 58 && h2->user_id == sid && h2->salt != h->salt);
    CHECK(dev->verify(dev, 0, 0, handle2, handle2_len, pw1, 4, &token, &token_len, &reenroll) == -GK_EINVAL);
    CHECK(dev->verify(dev, 0, 0, handle2, handle2_len, pw2, 4, &token, &token_len, &reenroll) == 0);
    free(token);
    token = 0;

    /* persistence: drop the in-memory state; the key must come back from the file */
    reset_state(0);
    CHECK(dev->verify(dev, 0, 0, handle2, handle2_len, pw2, 4, &token, &token_len, &reenroll) == 0);
    free(token);
    token = 0;
    CHECK(g_state_persistent == 1);

    /* a corrupted state file is discarded: old handles stop verifying (TEE-wipe semantics) */
    {
        int fd = open("/data/misc/gatekeeper/aemu_gatekeeper.state", 2 /* O_RDWR */);
        CHECK(fd >= 0);
        uint8_t b = 0;
        read(fd, &b, 1);
        b ^= 0xff;
        extern long lseek(int, long, int);
        lseek(fd, 0, 0);
        write(fd, &b, 1);
        close(fd);
    }
    reset_state(0);
    CHECK(dev->verify(dev, 0, 0, handle2, handle2_len, pw2, 4, &token, &token_len, &reenroll) == -GK_EINVAL);

    CHECK(dev->delete_user(dev, 0) == 0);
    CHECK(dev->delete_all_users(dev) == 0);
    CHECK(raw->close(raw) == 0);
    free(handle);
    free(handle2);

    printf(failures ? "RESULT: %d FAILURE(S)\n" : "RESULT: ALL PASS\n", failures);
    return failures ? 1 : 0;
}
