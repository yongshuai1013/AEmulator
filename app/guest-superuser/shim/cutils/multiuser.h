/* AEmulator build shim for koush/Superuser's su (app/guest-superuser/build.sh puts this
 * directory on the include path). Upstream links libcutils for exactly one call,
 * multiuser_get_user_id() in daemon.c; its definition is uid / AID_USER_OFFSET, stable since
 * Android 4.2 multi-user. The guest's own libcutils is not linkable from a static NDK build,
 * so the one-liner is provided here instead of patching upstream code. */
#ifndef AEMU_SHIM_CUTILS_MULTIUSER_H
#define AEMU_SHIM_CUTILS_MULTIUSER_H

typedef int user_id_t;

static inline user_id_t multiuser_get_user_id(int uid) {
    return uid / 100000;
}

#endif
