#include <jni.h>
#include <cstdio>
#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_set>

extern "C" {
#include "raop.h"
#include "dnssd.h"
#include "android_dnssd_shim.h"
}

namespace {
constexpr int maxVideoPacket = 2 * 1024 * 1024;

struct Receiver {
    JavaVM *vm = nullptr;
    jobject callback = nullptr;
    jmethodID video = nullptr;
    jmethodID size = nullptr;
    jmethodID active = nullptr;
    raop_t *raop = nullptr;
    dnssd_t *dns = nullptr;
    std::mutex keysMutex;
    std::unordered_set<std::string> keys;

    void close(JNIEnv *env) {
        if (raop) {
            raop_stop_httpd(raop);
            raop_destroy(raop);
        }
        if (dns) dnssd_destroy(dns);
        if (callback) env->DeleteGlobalRef(callback);
    }
};

class CallbackEnv {
    JavaVM *vm;
    bool detach = false;
public:
    JNIEnv *env = nullptr;

    explicit CallbackEnv(JavaVM *vm) : vm(vm) {
        int status = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        if (status != JNI_EDETACHED) return;
#if defined(__ANDROID__)
        detach = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
#else
        detach = vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) == JNI_OK;
#endif
    }

    ~CallbackEnv() {
        if (env && env->ExceptionCheck()) env->ExceptionClear();
        if (detach) vm->DetachCurrentThread();
    }
};

void video(void *context, raop_ntp_t *, video_decode_struct *packet) {
    if (!packet || !packet->data || packet->data_len < 5 || packet->data_len > maxVideoPacket ||
            packet->data[0] != 0 || packet->is_h265) return;
    auto *receiver = static_cast<Receiver *>(context);
    CallbackEnv callback(receiver->vm);
    if (!callback.env) return;
    jbyteArray bytes = callback.env->NewByteArray(packet->data_len);
    if (!bytes) return;
    callback.env->SetByteArrayRegion(bytes, 0, packet->data_len,
            reinterpret_cast<const jbyte *>(packet->data));
    if (!callback.env->ExceptionCheck())
        callback.env->CallVoidMethod(receiver->callback, receiver->video, bytes,
                static_cast<jlong>(packet->ntp_time_local / 1000));
    callback.env->DeleteLocalRef(bytes);
}

void size(void *context, float *, float *, float *width, float *height) {
    if (!width || !height || !(*width >= 16 && *width <= 1920 && *height >= 16 && *height <= 1920))
        return;
    auto *receiver = static_cast<Receiver *>(context);
    CallbackEnv callback(receiver->vm);
    if (callback.env)
        callback.env->CallVoidMethod(receiver->callback, receiver->size, static_cast<jint>(*width),
                static_cast<jint>(*height));
}

void active(void *context, bool playing) {
    auto *receiver = static_cast<Receiver *>(context);
    CallbackEnv callback(receiver->vm);
    if (callback.env)
        callback.env->CallVoidMethod(receiver->callback, receiver->active,
                static_cast<jboolean>(playing));
}

void registerClient(void *context, const char *, const char *key, const char *) {
    if (!key) return;
    auto *receiver = static_cast<Receiver *>(context);
    std::lock_guard<std::mutex> lock(receiver->keysMutex);
    if (receiver->keys.size() < 8) receiver->keys.emplace(key);
}

bool knownClient(void *context, const char *key) {
    if (!key) return false;
    auto *receiver = static_cast<Receiver *>(context);
    std::lock_guard<std::mutex> lock(receiver->keysMutex);
    return receiver->keys.find(key) != receiver->keys.end();
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_aap_fermata_mirror_AirPlayReceiver_nativeOpen(
        JNIEnv *env, jobject object, jbyteArray address, jstring keyFile, jint pin) {
    if (env->GetArrayLength(address) != 6 || pin < 0 || pin > 9999) return 0;
    auto *receiver = new Receiver;
    receiver->callback = env->NewGlobalRef(object);
    auto cls = env->GetObjectClass(object);
    receiver->video = env->GetMethodID(cls, "onVideo", "([BJ)V");
    receiver->size = env->GetMethodID(cls, "onSize", "(II)V");
    receiver->active = env->GetMethodID(cls, "onActive", "(Z)V");
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck() || !receiver->callback || !receiver->video || !receiver->size ||
            !receiver->active || env->GetJavaVM(&receiver->vm) != JNI_OK) {
        receiver->close(env);
        delete receiver;
        return 0;
    }

    raop_callbacks_t callbacks{};
    callbacks.cls = receiver;
    callbacks.video_process = video;
    callbacks.video_report_size = size;
    callbacks.mirror_video_running = active;
    callbacks.video_reset = [](void *ctx, reset_type_t) { active(ctx, false); };
    callbacks.video_pause = [](void *) {};
    callbacks.video_resume = [](void *) {};
    callbacks.conn_reset = [](void *ctx, int) { active(ctx, false); };
    callbacks.conn_feedback = [](void *) {};
    callbacks.audio_set_client_volume = [](void *) { return 0.0; };
    callbacks.audio_get_format = [](void *, unsigned char *, unsigned short *, bool *, bool *,
            uint64_t *) {};
    callbacks.audio_process = [](void *, raop_ntp_t *, audio_decode_struct *) {};
    callbacks.video_set_codec = [](void *, video_codec_t codec) {
        return codec == VIDEO_CODEC_H265 ? -1 : 0;
    };
    callbacks.register_client = registerClient;
    callbacks.check_register = knownClient;
    receiver->raop = raop_init(&callbacks);
    if (receiver->raop) {
        unsigned char hardware[6];
        env->GetByteArrayRegion(address, 0, 6, reinterpret_cast<jbyte *>(hardware));
        char id[18];
        snprintf(id, sizeof(id), "%02X:%02X:%02X:%02X:%02X:%02X", hardware[0], hardware[1],
                hardware[2], hardware[3], hardware[4], hardware[5]);
        auto *key = env->GetStringUTFChars(keyFile, nullptr);
        int initialized = key ? raop_init2(receiver->raop, 0, id, key) : -1;
        if (key) env->ReleaseStringUTFChars(keyFile, key);
        if (initialized >= 0) {
            raop_set_log_level(receiver->raop, 3);
            raop_set_plist(receiver->raop, "pin", 10000 + pin);
            raop_set_plist(receiver->raop, "width", 1280);
            raop_set_plist(receiver->raop, "height", 720);
            raop_set_plist(receiver->raop, "refreshRate", 30);
            int error = 0;
            constexpr char name[] = "Fermata";
            receiver->dns = dnssd_init(name, sizeof(name) - 1,
                    reinterpret_cast<const char *>(hardware), 6, &error, 1);
            if (receiver->dns) {
                dnssd_set_airplay_features(receiver->dns, 0, 0);
                dnssd_set_airplay_features(receiver->dns, 4, 0);
                dnssd_set_airplay_features(receiver->dns, 42, 0);
                raop_set_dnssd(receiver->raop, receiver->dns);
                unsigned short port = 0;
                if (raop_start_httpd(receiver->raop, &port) >= 0 && port != 0) {
                    raop_set_port(receiver->raop, port);
                    if (dnssd_register_raop(receiver->dns, port) == 0 &&
                            dnssd_register_airplay(receiver->dns, port) == 0)
                        return reinterpret_cast<jlong>(receiver);
                }
            }
        }
    }
    receiver->close(env);
    delete receiver;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_aap_fermata_mirror_AirPlayReceiver_nativePort(JNIEnv *, jobject, jlong handle) {
    return raop_get_port(reinterpret_cast<Receiver *>(handle)->raop);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_me_aap_fermata_mirror_AirPlayReceiver_nativeRecord(
        JNIEnv *env, jobject, jlong handle, jboolean airplay) {
    auto *dns = reinterpret_cast<Receiver *>(handle)->dns;
    int count = airplay ? android_dnssd_get_airplay_txt_count(dns) :
                         android_dnssd_get_raop_txt_count(dns);
    auto cls = env->FindClass("java/lang/String");
    if (!cls) return nullptr;
    auto records = env->NewObjectArray(2 * count + 1, cls, nullptr);
    env->DeleteLocalRef(cls);
    if (!records) return nullptr;
    auto set = [&](int index, const char *string) {
        if (!string || env->ExceptionCheck()) return;
        auto value = env->NewStringUTF(string);
        if (!value) return;
        env->SetObjectArrayElement(records, index, value);
        env->DeleteLocalRef(value);
    };
    set(0, airplay ? "Fermata" : android_dnssd_get_raop_servname(dns));
    for (int i = 0; i < count; ++i) {
        set(i * 2 + 1, airplay ? android_dnssd_get_airplay_txt_key(dns, i) :
                               android_dnssd_get_raop_txt_key(dns, i));
        set(i * 2 + 2, airplay ? android_dnssd_get_airplay_txt_val(dns, i) :
                               android_dnssd_get_raop_txt_val(dns, i));
    }
    return records;
}

extern "C" JNIEXPORT void JNICALL
Java_me_aap_fermata_mirror_AirPlayReceiver_nativeClose(JNIEnv *env, jobject, jlong handle) {
    auto *receiver = reinterpret_cast<Receiver *>(handle);
    receiver->close(env);
    delete receiver;
}
