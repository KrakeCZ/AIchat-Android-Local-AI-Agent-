#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "WhisperJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,    TAG, __VA_ARGS__)

static inline void whisper_android_log_callback(enum ggml_log_level level, const char* text, void* user_data) {
    (void)user_data;
    int prio = ANDROID_LOG_DEFAULT;
    switch(level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default: break;
    }
    __android_log_write(prio, TAG, text);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_llama_agent_perception_WhisperJni_whisperInit(
    JNIEnv *env, jobject thiz, jstring modelPath, jstring computeBackend) {
    (void)thiz;
    
    // Set up ggml logging
    whisper_log_set(whisper_android_log_callback, nullptr);

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    const char *backend = env->GetStringUTFChars(computeBackend, nullptr);
    LOGI("Initializing whisper.cpp from: %s with backend: %s", path, backend);

    struct whisper_context_params cparams = whisper_context_default_params();
    
    // Zapnout GPU/NPU delegáty (Whisper ggml backendy to obslouží přes ggml_backend)
    if (strcmp(backend, "GPU") == 0 || strcmp(backend, "AUTO") == 0 || strcmp(backend, "NNAPI") == 0) {
        cparams.use_gpu = true;
    } else {
        cparams.use_gpu = false; // CPU fallback
    }

    // DEBUG: Print all registered ggml backends
    size_t dev_count = ggml_backend_dev_count();
    LOGI("GGML backend device count: %zu", dev_count);
    for (size_t i = 0; i < dev_count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        LOGI("GGML Device %zu: %s (type %d)", i, ggml_backend_dev_name(dev), ggml_backend_dev_type(dev));
    }
    
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    env->ReleaseStringUTFChars(computeBackend, backend);
    
    if (ctx == nullptr) {
        LOGE("Failed to load whisper model");
        return 0;
    }
    
    LOGI("Whisper model loaded successfully. Pointer: %p", (void*)ctx);
    return reinterpret_cast<jlong>(ctx);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_llama_agent_perception_WhisperJni_whisperTranscribe(
    JNIEnv *env, jobject thiz, jlong ctxPtr, jfloatArray audioData, jstring language, jint nThreads) {
    (void)thiz;
    
    if (ctxPtr == 0) {
        LOGE("Invalid context pointer");
        return env->NewStringUTF("");
    }
    
    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    
    jsize numSamples = env->GetArrayLength(audioData);
    jfloat *samples = env->GetFloatArrayElements(audioData, nullptr);
    if (samples == nullptr) {
        LOGE("Failed to get audio data");
        return env->NewStringUTF("");
    }
    
    const char *langChr = env->GetStringUTFChars(language, nullptr);
    
    // Prepare inference parameters
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.n_threads = nThreads > 0 ? nThreads : 2;
    // Whisper expects input at 16kHz
    wparams.offset_ms = 0;
    wparams.translate = false;
    wparams.no_context = true; // For 10s chunks, context from previous might hallucinate
    wparams.single_segment = false;
    wparams.language = langChr;
    
    LOGI("Starting whisper_full inference (Threads: %d, Lang: %s, Samples: %d)", wparams.n_threads, langChr, numSamples);
    
    int ret = whisper_full(ctx, wparams, samples, numSamples);
    
    env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, langChr);
    
    if (ret != 0) {
        LOGE("whisper_full failed with error code: %d", ret);
        return env->NewStringUTF("");
    }
    
    // Collect transcription
    const int n_segments = whisper_full_n_segments(ctx);
    std::string result = "";
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) {
            result += text;
        }
    }
    
    LOGI("Whisper transcription successful: %d segments", n_segments);
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_llama_agent_perception_WhisperJni_whisperDetectLanguage(
    JNIEnv *env, jobject thiz, jlong ctxPtr, jfloatArray audioData, jint nThreads) {
    (void)thiz;
    
    if (ctxPtr == 0) {
        return env->NewStringUTF("en"); // fallback
    }
    
    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    jsize numSamples = env->GetArrayLength(audioData);
    jfloat *samples = env->GetFloatArrayElements(audioData, nullptr);
    
    // Auto-detect needs mel spectrogram
    if (whisper_pcm_to_mel(ctx, samples, numSamples, nThreads) != 0) {
        LOGE("Failed to compute mel spectrogram for language detection");
        env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);
        return env->NewStringUTF("en");
    }
    env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);
    
    std::vector<float> lang_probs(whisper_lang_max_id() + 1, 0.0f);
    int top_lang_id = whisper_lang_auto_detect(ctx, 0, nThreads, lang_probs.data());
    
    if (top_lang_id < 0) {
        LOGE("Language auto-detection failed");
        return env->NewStringUTF("en"); // fallback
    }
    
    const char *lang_str = whisper_lang_str(top_lang_id);
    LOGI("Detected language: %s (id: %d)", lang_str, top_lang_id);
    
    return env->NewStringUTF(lang_str);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_llama_agent_perception_WhisperJni_whisperFree(
    JNIEnv *env, jobject thiz, jlong ctxPtr) {
    (void)env;
    (void)thiz;
    
    if (ctxPtr != 0) {
        struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
        whisper_free(ctx);
        LOGI("Whisper context freed");
    }
}
