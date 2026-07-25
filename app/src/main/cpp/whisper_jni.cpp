#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "whisper_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_aicallresponder_WhisperNative_initContext(
        JNIEnv *env, jobject /* this */, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU inference on Android

    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params returned null");
        return 0;
    }
    LOGI("whisper context initialized");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_aicallresponder_WhisperNative_freeContext(
        JNIEnv * /* env */, jobject /* this */, jlong ptr) {
    if (ptr != 0) {
        whisper_free(reinterpret_cast<whisper_context *>(ptr));
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_aicallresponder_WhisperNative_transcribe(
        JNIEnv *env, jobject /* this */, jlong ptr, jfloatArray audio, jstring lang, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    const jsize n = env->GetArrayLength(audio);
    std::vector<float> samples(n);
    env->GetFloatArrayRegion(audio, 0, n, samples.data());

    const char *clang = env->GetStringUTFChars(lang, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;   // keep Nepali as Nepali, do not translate to English
    params.no_timestamps    = true;
    params.single_segment   = false;
    params.suppress_blank   = true;
    params.language         = clang;   // e.g. "ne"
    params.n_threads        = threads > 0 ? threads : 4;

    const int rc = whisper_full(ctx, params, samples.data(), static_cast<int>(samples.size()));
    env->ReleaseStringUTFChars(lang, clang);

    if (rc != 0) {
        LOGE("whisper_full failed rc=%d", rc);
        return env->NewStringUTF("");
    }

    std::string result;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) {
            result += text;
        }
    }
    return env->NewStringUTF(result.c_str());
}
