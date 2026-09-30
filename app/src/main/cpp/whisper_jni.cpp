#include <jni.h>
#include <string>
#include "whisper.h"

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

JNIEXPORT jstring JNICALL
Java_com_tokalot_app_WhisperBridge_transcribe(JNIEnv *env, jobject /*thiz*/, jlong handle,
                                                  jfloatArray samples, jint threads, jstring prompt) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return env->NewStringUTF("");

    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);

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

    // Dictionary words go in as Whisper's "initial prompt", which biases spelling toward them.
    const char *pr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptStr(pr);
    env->ReleaseStringUTFChars(prompt, pr);
    if (!promptStr.empty()) wp.initial_prompt = promptStr.c_str();

    int rc = whisper_full(ctx, wp, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (rc != 0) return env->NewStringUTF("");

    std::string out;
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) {
        out += whisper_full_get_segment_text(ctx, i);
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_tokalot_app_WhisperBridge_free(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

}  // extern "C"
