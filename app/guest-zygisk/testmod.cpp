/* AEmulator addition, 2026-11-26. GPL-3.0; see LICENSE. */
//
// The Zygisk spike's test module (id aemu-spike), built against the
// official API v4 header vendored next to this file. It does nothing but
// leave evidence lines in logcat under the "azygisk-spike" tag:
//   onLoad           - the bridge called the module entry at all;
//   preAppSpecialize - the module ran in the forked child before
//                      specialization, with the target's nice_name, and
//                      reports what the honest edges answer today
//                      (connectCompanion must be -1: no companion exists;
//                      getModuleDir must succeed in pre);
//   postAppSpecialize- the module ran again after specialization.
// After post, the bridge strips this module's API table; the module
// never calls the API again, which is the contract.

#include <android/log.h>
#include <jni.h>
#include <sys/types.h>

#include "zygisk.hpp"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "azygisk-spike", __VA_ARGS__)

class SpikeModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
        LOGI("module onLoad id=aemu-spike");
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        const char *name = nullptr;
        if (args->nice_name) name = env_->GetStringUTFChars(args->nice_name, nullptr);
        int companion = api_->connectCompanion();
        int dir = api_->getModuleDir();
        LOGI("module pre id=aemu-spike nice_name=%s companion=%d moduledir=%s",
             name ? name : "?", companion, dir >= 0 ? "ok" : "fail");
        if (name) env_->ReleaseStringUTFChars(args->nice_name, name);
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *args) override {
        (void)args;
        LOGI("module post id=aemu-spike");
    }

private:
    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
};

REGISTER_ZYGISK_MODULE(SpikeModule)
