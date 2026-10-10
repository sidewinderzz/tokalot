#include <jni.h>
#include <atomic>
#include <string>
#include "whisper.h"

// Cancel flags come from newAbort(), one per Dictation, so cancelling one dictation (a retry in the app)
// can't stop another (the floating button) that happens to run at the same time. Whisper polls the flag
// while it decodes, from its own worker threads, so it's a plain atomic rather than anything in Java.

static void throwError(JNIEnv *env, const char *msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, msg);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_tokalot_app_WhisperBridge_init(JNIEnv *env, jobject /*thiz*/, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

// Returns the text as UTF-8 bytes: Java's NewStringUTF only takes "modified" UTF-8, and an emoji
// (or a character Whisper split in two) would garble the text or abort a debug build.
// Throws instead of returning "" when Whisper fails, so a failure is never taken for silence.
JNIEXPORT jbyteArray JNICALL
Java_com_tokalot_app_WhisperBridge_transcribe(JNIEnv *env, jobject /*thiz*/, jlong handle, jlong abort,
                                                  jfloatArray samples, jint threads, jstring prompt) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) { throwError(env, "The on-device model isn't loaded"); return nullptr; }

    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    if (data == nullptr) { throwError(env, "Not enough memory for on-device transcription"); return nullptr; }

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.translate = false;
    wp.language = "en";
    wp.n_threads = threads;
    wp.no_context = true;
    wp.no_timestamps = true;
    wp.suppress_blank = true;
    wp.greedy.best_of = 1;  // fastest decode; accuracy is fine for dictation
    wp.abort_callback = [](void *flag) {
        return flag != nullptr && static_cast<std::atomic<bool> *>(flag)->load();
    };
    wp.abort_callback_user_data = reinterpret_cast<std::atomic<bool> *>(abort);

    // Dictionary words go in as Whisper's "initial prompt", which biases spelling toward them.
    std::string promptStr;
    if (prompt != nullptr) {
        const char *pr = env->GetStringUTFChars(prompt, nullptr);
        if (pr != nullptr) {
            promptStr = pr;
            env->ReleaseStringUTFChars(prompt, pr);
        }
    }
    if (!promptStr.empty()) wp.initial_prompt = promptStr.c_str();

    int rc = whisper_full(ctx, wp, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (rc != 0) {
        std::string msg = "On-device transcription failed (error " + std::to_string(rc) + ")";
        throwError(env, msg.c_str());
        return nullptr;
    }

    std::string out;
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        if (t != nullptr) out += t;
    }
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(out.size()));
    if (bytes == nullptr) return nullptr;  // OutOfMemoryError already pending
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return bytes;
}

JNIEXPORT void JNICALL
Java_com_tokalot_app_WhisperBridge_free(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

JNIEXPORT jlong JNICALL
Java_com_tokalot_app_WhisperBridge_newAbort(JNIEnv * /*env*/, jobject /*thiz*/) {
    return reinterpret_cast<jlong>(new std::atomic<bool>(false));
}

JNIEXPORT void JNICALL
Java_com_tokalot_app_WhisperBridge_setAbort(JNIEnv * /*env*/, jobject /*thiz*/, jlong abort, jboolean on) {
    auto *flag = reinterpret_cast<std::atomic<bool> *>(abort);
    if (flag != nullptr) flag->store(on == JNI_TRUE);
}

}  // extern "C"
