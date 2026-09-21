#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <string>
#include <unistd.h>
#include <vector>
#include <sstream>
#include <cstring>

// FIX: Include ordering and usage of quotes to ensure local headers are found
#include "logging.h"
#include "llama.h"
#include "common.h"
#include "sampling.h"
#include "chat.h"
#include "mtmd.h"
#include "mtmd-helper.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

constexpr int   DEFAULT_CONTEXT_SIZE    = 4096;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.7f;

static llama_model                      * g_model;
static llama_context                    * g_context;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;
static mtmd_context                     * g_mtmd_ctx = nullptr;

// Global variable to store KV Cache Mode between load() and prepare()/init_context()
// 0=F16, 1=Q8_0, 2=Q4_0, 3=Q4_1, 4=Q5_0, 5=Q5_1, 6=IQ1_S
static int                                g_kv_cache_mode = 1; // Default to Q8_0 (Balanced)

// Globals for new parameters
static int                                g_n_ctx = 0;
static int                                g_n_batch = BATCH_SIZE;
static int                                g_n_ubatch = BATCH_SIZE;
static bool                               g_flash_attn = true;
// New parameters globals
static bool                               g_ignore_eos = false;
static bool                               g_timing_per_token = false;

// Global sampling params — persistent between setGrammar/configureSampling calls
static common_params_sampling             g_sparams;

// Tracking of conversational messages and tokens position
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;
static size_t g_kv_cells_used = 0;  // Skutečné obsazení KV cache buněk (nezávislé na M-RoPE pozicích)
static mtmd_bitmap * g_cached_bmp = nullptr;

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject thiz, jstring nativeLibDir) {
    (void)thiz; // Unused
    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Loading all CPU backend variants
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, nullptr);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();
    print_build_info();
    LOGi("Backend initiated; Log handler set.");
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_loadModelNative(
        JNIEnv *env,
        jobject thiz,
        jstring jmodel_path,
        jstring jprojector_path,
        jint kvCacheMode,
        jboolean useMmap,
        jint nCtx,
        jint nBatch,
        jint nUBatch,
        jboolean flashAttn
) {
    (void)thiz;
    llama_model_params model_params = llama_model_default_params();

    // Dynamické nastavení MMAP dle volby uživatele
    // true = OS spravuje paměť (Doporučeno)
    // false = Načíst celý model do RAM (Riziko OOM)
    model_params.use_mmap = (bool) useMmap;

    // Store KV Cache mode for context initialization
    g_kv_cache_mode = kvCacheMode;
    g_n_ctx = nCtx;
    g_n_batch = nBatch;
    g_n_ubatch = nUBatch;
    g_flash_attn = (bool) flashAttn;

    const auto *model_path = env->GetStringUTFChars(jmodel_path, nullptr);
    LOGd("%s: Loading model from: \n%s (MMAP: %d, KV-Mode: %d, ctx: %d, batch: %d, ubatch: %d, FA: %d)\n",
         __func__, model_path, (int)useMmap, kvCacheMode, nCtx, nBatch, nUBatch, (int)flashAttn);

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    g_model = model;

    if (jprojector_path != nullptr) {
        const char *projector_path = env->GetStringUTFChars(jprojector_path, nullptr);
        if (strlen(projector_path) > 0) {
            LOGi("Loading mtmd multimodal projector: %s", projector_path);
            struct mtmd_context_params mparams = mtmd_context_params_default();
            g_mtmd_ctx = mtmd_init_from_file(projector_path, g_model, mparams);
            if (!g_mtmd_ctx) {
                LOGe("Failed to initialize mtmd_context projector!");
            } else {
                LOGi("mtmd_context vision projector initialized successfully.");
            }
        }
        env->ReleaseStringUTFChars(jprojector_path, projector_path);
    }
    return 0;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processImageNative(
        JNIEnv *env, jobject thiz, jbyteArray image_bytes) {
    (void)thiz;
    
    if (!g_mtmd_ctx || !g_context) {
        LOGe("MTMD Projector or LLM context not initialized. Cannot process image.");
        return JNI_FALSE;
    }
    
    jsize length = env->GetArrayLength(image_bytes);
    jbyte* bytes = env->GetByteArrayElements(image_bytes, nullptr);
    
    LOGi("Creating vision embeddings from %d bytes...", length);
    
    mtmd_bitmap * bmp = mtmd_helper_bitmap_init_from_buf(g_mtmd_ctx, reinterpret_cast<const unsigned char*>(bytes), length);
    env->ReleaseByteArrayElements(image_bytes, bytes, JNI_ABORT);
    
    if (!bmp) {
        LOGe("Failed to extract image bitmap via mtmd_helper_bitmap_init_from_buf.");
        return JNI_FALSE;
    }
    
    // Cache the bitmap for Delayed Evaluation inside processUserPrompt
    if (g_cached_bmp) {
        mtmd_bitmap_free(g_cached_bmp);
    }
    g_cached_bmp = bmp;
    LOGi("Image bitmap cached successfully for the next user prompt.");
    
    return JNI_TRUE;
}

static llama_context *init_context(llama_model *model, const int n_ctx_arg = 0) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    // Multi-threading setup
    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                     N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads", __func__, n_threads);

    // Context parameters setup
    llama_context_params ctx_params = llama_context_default_params();

    // Resolve context size
    // Priority: Argument > Global Setting > Default Constant
    int n_ctx_to_use = DEFAULT_CONTEXT_SIZE;
    if (n_ctx_arg > 0) {
        n_ctx_to_use = n_ctx_arg;
    } else if (g_n_ctx > 0) {
        n_ctx_to_use = g_n_ctx;
    }

    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx_to_use > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx_to_use);
    }
    ctx_params.n_ctx = n_ctx_to_use;

    // Use global batch size if set, otherwise default
    int n_batch_to_use = (g_n_batch > 0) ? g_n_batch : BATCH_SIZE;
    ctx_params.n_batch = n_batch_to_use;
    
    // Use global physical batch size (ubatch)
    int n_ubatch_to_use = (g_n_ubatch > 0) ? g_n_ubatch : BATCH_SIZE;
    ctx_params.n_ubatch = n_ubatch_to_use;

    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;

    // Flash Attention
    ctx_params.flash_attn_type = g_flash_attn ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;

    // Mapování int z Kotlinu (arrays.xml index 0-14) na ggml_type
    // Upraveno pro sekvenční ID od uživatele
    switch (g_kv_cache_mode) {
        // --- HIGH QUALITY ---
        case 0: // F16
            ctx_params.type_k = GGML_TYPE_F16;
            ctx_params.type_v = GGML_TYPE_F16;
            break;
        case 1: // Q8_0
            ctx_params.type_k = GGML_TYPE_Q8_0;
            ctx_params.type_v = GGML_TYPE_Q8_0;
            break;
        case 2: // Q6_K
            ctx_params.type_k = GGML_TYPE_Q6_K;
            ctx_params.type_v = GGML_TYPE_Q6_K;
            break;
        case 3: // Q5_K
            ctx_params.type_k = GGML_TYPE_Q5_K;
            ctx_params.type_v = GGML_TYPE_Q5_K;
            break;
        case 4: // Q4_K
            ctx_params.type_k = GGML_TYPE_Q4_K;
            ctx_params.type_v = GGML_TYPE_Q4_K;
            break;

        // --- LEGACY ---
        case 5: // Q5_1
            ctx_params.type_k = GGML_TYPE_Q5_1;
            ctx_params.type_v = GGML_TYPE_Q5_1;
            break;
        case 6: // Q5_0
            ctx_params.type_k = GGML_TYPE_Q5_0;
            ctx_params.type_v = GGML_TYPE_Q5_0;
            break;
        case 7: // Q4_1
            ctx_params.type_k = GGML_TYPE_Q4_1;
            ctx_params.type_v = GGML_TYPE_Q4_1;
            break;
        case 8: // Q4_0
            ctx_params.type_k = GGML_TYPE_Q4_0;
            ctx_params.type_v = GGML_TYPE_Q4_0;
            break;

        // --- IQ SERIES ---
        case 9: // IQ4_NL
            ctx_params.type_k = GGML_TYPE_IQ4_NL;
            ctx_params.type_v = GGML_TYPE_IQ4_NL;
            break;
        case 10: // IQ3_M - FALLBACK to IQ3_S (IQ3_M not defined in ggml.h)
            ctx_params.type_k = GGML_TYPE_IQ3_S;
            ctx_params.type_v = GGML_TYPE_IQ3_S;
            __android_log_print(ANDROID_LOG_WARN, "LlamaJNI", "IQ3_M requested but not available in ggml.h. Falling back to IQ3_S.");
            break;
        case 11: // IQ3_S
            ctx_params.type_k = GGML_TYPE_IQ3_S;
            ctx_params.type_v = GGML_TYPE_IQ3_S;
            break;
        case 12: // IQ2_XS
            ctx_params.type_k = GGML_TYPE_IQ2_XS;
            ctx_params.type_v = GGML_TYPE_IQ2_XS;
            break;
        case 13: // IQ2_XXS
            ctx_params.type_k = GGML_TYPE_IQ2_XXS;
            ctx_params.type_v = GGML_TYPE_IQ2_XXS;
            break;
        case 14: // IQ1_S
            ctx_params.type_k = GGML_TYPE_IQ1_S;
            ctx_params.type_v = GGML_TYPE_IQ1_S;
            break;

        default:
            __android_log_print(ANDROID_LOG_WARN, "LlamaJNI", "Unknown cache mode %d, defaulting to Q8_0", g_kv_cache_mode);
            ctx_params.type_k = GGML_TYPE_Q8_0;
            ctx_params.type_v = GGML_TYPE_Q8_0;
            break;
    }
    LOGi("KV Cache quantization set to mode: %d", g_kv_cache_mode);

    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    return common_sampler_init(g_model, sparams);
}

// --- NEW FUNCTION: Configure Sampling (RENAMED TO MATCH KOTLIN) ---
extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_configureSamplingNative(
        JNIEnv *env, jobject thiz,
        jfloat temp,
        jint top_k,
        jfloat top_p,
        jfloat min_p,
        jfloat xtc_prob,
        jfloat xtc_thresh,
        jfloat typical_p,
        jfloat dyn_temp_range,
        jfloat dyn_temp_exp,
        jfloat penalty_repeat,
        jfloat penalty_freq,
        jfloat penalty_present,
        jint penalty_last_n,
        jint mirostat,
        jfloat mirostat_tau,
        jfloat mirostat_eta,
        jstring samplers_sequence,
        jboolean use_backend_sampling,
        // New parameters
        jboolean ignore_eos,
        jboolean no_perf,
        jboolean timing_per_token
) {
    (void)env; (void)thiz;
    LOGi("Configuring sampling: T=%.2f, K=%d, P=%.2f, MinP=%.2f, PenRep=%.2f, IgnoreEOS=%d", temp, top_k, top_p, min_p, penalty_repeat, ignore_eos);
    
    // Update globals
    g_ignore_eos = (bool) ignore_eos;
    g_timing_per_token = (bool) timing_per_token;

    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }

    common_params_sampling sparams;
    sparams.temp = temp;
    sparams.top_k = top_k;
    sparams.top_p = top_p;
    sparams.min_p = min_p;
    sparams.xtc_probability = xtc_prob;
    sparams.xtc_threshold = xtc_thresh;
    sparams.typ_p = typical_p;
    sparams.dynatemp_range = dyn_temp_range;
    sparams.dynatemp_exponent = dyn_temp_exp;
    sparams.penalty_repeat = penalty_repeat;
    sparams.penalty_freq = penalty_freq;
    sparams.penalty_present = penalty_present;
    sparams.penalty_last_n = penalty_last_n;
    sparams.mirostat = mirostat;
    sparams.mirostat_tau = mirostat_tau;
    sparams.mirostat_eta = mirostat_eta;

    sparams.backend_sampling = (bool)use_backend_sampling;
    sparams.no_perf = (bool)no_perf;
    sparams.ignore_eos = (bool)ignore_eos;
    sparams.timing_per_token = (bool)timing_per_token;

    // Parse samplers sequence string from Kotlin
    const char * seq_chars = env->GetStringUTFChars(samplers_sequence, nullptr);
    if (seq_chars && strlen(seq_chars) > 0) {
        sparams.samplers.clear();
        for (int i = 0; seq_chars[i] != '\0'; i++) {
            switch (seq_chars[i]) {
                case 'k': sparams.samplers.push_back(COMMON_SAMPLER_TYPE_TOP_K); break;
                case 'p': sparams.samplers.push_back(COMMON_SAMPLER_TYPE_TOP_P); break;
                case 'm': sparams.samplers.push_back(COMMON_SAMPLER_TYPE_MIN_P); break;
                case 'y': sparams.samplers.push_back(COMMON_SAMPLER_TYPE_TYPICAL_P); break;
                case 't': sparams.samplers.push_back(COMMON_SAMPLER_TYPE_TEMPERATURE); break;
                case 'x': sparams.samplers.push_back(COMMON_SAMPLER_TYPE_XTC); break;
                case 'd': sparams.samplers.push_back(COMMON_SAMPLER_TYPE_DRY); break;
                case 'n': sparams.samplers.push_back(COMMON_SAMPLER_TYPE_TOP_N_SIGMA); break;
                case 'r': sparams.samplers.push_back(COMMON_SAMPLER_TYPE_PENALTIES); break; // r for repetition/penalties
                case 'M': sparams.samplers.push_back(COMMON_SAMPLER_TYPE_MIROSTAT); break;    // Mirostat V1
                case 'v': sparams.samplers.push_back(COMMON_SAMPLER_TYPE_MIROSTAT_V2); break; // Mirostat V2
                default:
                    LOGw("Unknown sampler char '%c' in sequence, ignoring.", seq_chars[i]);
                    break;
            }
        }
        LOGi("Applied custom sampler sequence: %s", seq_chars);
    }
    env->ReleaseStringUTFChars(samplers_sequence, seq_chars);

    // Preserve grammar if already set (from setGrammarNative)
    sparams.grammar = g_sparams.grammar;

    // Store globally for grammar re-use
    g_sparams = sparams;

    g_sampler = common_sampler_init(g_model, g_sparams);
    LOGi("Sampler re-initialized successfully.");
}
// ----------------------------------------

// --- GBNF Grammar Constraint for Agent Mode ---
extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_setGrammarNative(
        JNIEnv *env, jobject thiz, jstring jgrammar
) {
    (void)thiz;
    if (jgrammar == nullptr) {
        // Clear grammar
        g_sparams.grammar.clear();
        LOGi("Grammar constraint cleared.");
    } else {
        const char *grammar_str = env->GetStringUTFChars(jgrammar, nullptr);
        if (grammar_str) {
            g_sparams.grammar = std::string(grammar_str);
            LOGi("Grammar constraint set (%d chars).", (int)g_sparams.grammar.size());
        }
        env->ReleaseStringUTFChars(jgrammar, grammar_str);
    }

    // Reinitialize sampler with updated grammar
    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    g_sampler = common_sampler_init(g_model, g_sparams);
    LOGi("Sampler re-initialized with grammar.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    auto *context = init_context(g_model);
    if (!context) { return 1; }
    g_context = context;

    int n_batch_to_use = (g_n_batch > 0) ? g_n_batch : BATCH_SIZE;
    g_batch = llama_batch_init(n_batch_to_use, 0, 1);

    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
    return 0;
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.emplace_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject thiz) {
    (void)thiz;
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject thiz, jint pp, jint tg,
                                                      jint pl, jint nr) {
    (void)thiz;
    auto *context = init_context(g_model, pp);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i;
    int j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch_clear(g_batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(g_batch, 0, i, {0}, false);
        }

        g_batch.logits[g_batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, g_batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(g_batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(g_batch, 0, i, {j}, true);
            }

            if (llama_decode(context, g_batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
static void reset_long_term_states(const bool clear_kv_cache = true, const bool keep_bitmap = false) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;
    g_kv_cells_used = 0;
    
    if (!keep_bitmap && g_cached_bmp) {
        mtmd_bitmap_free(g_cached_bmp);
        g_cached_bmp = nullptr;
    }

    if (clear_kv_cache) {
        llama_memory_clear(llama_get_memory(g_context), false);
    }
}

/**
 * TODO-hyin: implement sliding-window version as a better alternative
 *
 * Context shifting by discarding the older half of the tokens appended after system prompt:
 * - take the [system_prompt_position] first tokens from the original prompt
 * - take half of the last (system_prompt_position - system_prompt_position) tokens
 * - recompute the logits in batches
 */
static void shift_context(const bool keep_bitmap = false) {
    // U multimodálních modelů s M-ROPE (Qwen2, atd.) parciální `llama_memory_seq_add` padá na SIGABRT.
    // Proč? 3D RoPE pozicování u obrázků nelze jednoduše blokově posunout v llama.cpp KV cache.
    // Proto provedeme bezpečný plný reset (Graceful Full KV Cache Reset), pokud je aktivován projektor.
    if (g_mtmd_ctx) {
        LOGi("%s: Context full (Multimodal/M-RoPE active). Performing FULL KV cache clear to avoid SIGABRT crash.", __func__);
        reset_long_term_states(true, keep_bitmap);
    } else {
        // Obnoven původní kód: Parciální kontext shift pro běžné textové modely
        const int n_discard = (current_position - system_prompt_position) / 2;
        LOGi("%s: Context full (Text-Only). Discarding %d tokens via llama_memory_seq_rm/add", __func__, n_discard);
        llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
        llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
        current_position -= n_discard;
        LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
    }
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, /* use_jinja */ false);
    chat_msgs.push_back(new_msg);
    LOGd("%s: Formatted and added %s message: \n%s\n", __func__, role.c_str(), formatted.c_str());
    return formatted;
}

/**
 * Completion loop's short-term states:
 * - stop generation position
 * - token chars caching
 * - current assistant message being generated
 */
static llama_pos stop_generation_position;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
}

static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    // Process tokens in batches using the global batch
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);

    // Use configured batch size instead of constant BATCH_SIZE
    int current_batch_limit = (g_n_batch > 0) ? g_n_batch : BATCH_SIZE;

    for (int i = 0; i < (int) tokens.size(); i += current_batch_limit) {
        const int cur_batch_size = std::min((int) tokens.size() - i, current_batch_limit);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        // Shift context if current batch cannot fit into the context
        // Používáme g_kv_cells_used místo start_pos, protože pro M-RoPE modely
        // pozice diverguje od skutečného obsazení KV cache buněk.
        int context_size = static_cast<int>(llama_n_ctx(context)); // Get actual context size
        if (g_kv_cells_used + cur_batch_size >= (size_t)(context_size - OVERFLOW_HEADROOM)) {
            LOGw("%s: Current batch won't fit into context! KV cells used: %zu, batch: %d, limit: %d. Shifting...",
                 __func__, g_kv_cells_used, cur_batch_size, context_size);
            shift_context();
        }

        // Add tokens to the batch with proper positions
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == (int)tokens.size() - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }

        // Decode this batch
        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject thiz,
        jstring jsystem_prompt
) {
    (void)thiz;
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Obtain system prompt from JEnv
    const auto *const system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGv("%s: System prompt received: \n%s", __func__, system_prompt);
    std::string formatted_system_prompt(system_prompt);
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    // Format system prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }

    // Tokenize system prompt
    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                               has_chat_template, has_chat_template);
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Handle context overflow
    int context_size = static_cast<int>(llama_n_ctx(g_context));
    const int max_batch_size = context_size - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    // Decode system tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    system_prompt_position = current_position = (int) system_tokens.size();
    g_kv_cells_used = system_tokens.size();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject thiz,
        jstring juser_prompt,
        jint n_predict
) {
    (void)thiz;
    // Reset short-term states
    reset_short_term_states();

    // Obtain and tokenize user prompt
    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGv("%s: User prompt received: \n%s", __func__, user_prompt);
    std::string formatted_user_prompt(user_prompt);
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    // Format user prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, formatted_user_prompt);
    }

    LOGi("processUserPrompt: g_cached_bmp=%s, prompt contains <__image__>=%s",
         g_cached_bmp ? "YES" : "NO",
         (formatted_user_prompt.find("<__image__>") != std::string::npos || formatted_user_prompt.find("<__media__>") != std::string::npos) ? "YES" : "NO");
    
    if (g_cached_bmp) {
        // AUTO-PREPEND: Pokud prompt neobsahuje žádný media/image marker,
        // automaticky ho přidáme na začátek. Bez markeru mtmd_tokenize selže,
        // protože count(markers) != count(bitmaps).
        // Toto řeší Chat režim (bez Agenta), kde uživatel uploadne obrázek
        // a pak pošle textový dotaz bez markeru.
        // Agent/SmartVision režim marker přidává sám v Kotlin vrstvě.
        if (formatted_user_prompt.find("<__image__>") == std::string::npos &&
            formatted_user_prompt.find("<__media__>") == std::string::npos) {
            LOGi("No media marker found in prompt. Auto-prepending <__media__> (Chat mode fallback).");
            formatted_user_prompt = std::string(mtmd_default_marker()) + formatted_user_prompt;
        }
        
        LOGi("Found cached image bitmap! Injecting it into prompt at media marker...");
        mtmd_input_text text = { formatted_user_prompt.c_str(), false, false };
        const mtmd_bitmap * bitmaps[] = { g_cached_bmp };
        mtmd_input_chunks * chunks = mtmd_input_chunks_init();

        if (mtmd_tokenize(g_mtmd_ctx, chunks, &text, bitmaps, 1) == 0) {
            llama_pos new_n_past = 0;
            
            // SECURITY MEASURE: Check if multimodal prompt fits into remaining KV cache
            // CRITICAL: Používáme g_kv_cells_used (skutečné obsazení buněk), NE current_position!
            // Pro M-RoPE modely: obrázek 36x36 zabere 1296 buněk, ale n_pos = max(36,36) = 36.
            // current_position by ukázal jen 36, ale KV cache spotřebuje 1296 buněk.
            int context_size = static_cast<int>(llama_n_ctx(g_context));
            size_t n_chunks_tokens = mtmd_helper_get_n_tokens(chunks);
            LOGi("Multimodal chunks: %zu tokens needed. KV cells used: %zu, Current pos: %d, Limit: %d",
                 n_chunks_tokens, g_kv_cells_used, current_position, context_size);
            
            if (g_kv_cells_used + n_chunks_tokens > (size_t)(context_size - OVERFLOW_HEADROOM)) {
                LOGw("%s: KV cache full! cells_used=%zu + new=%zu > limit=%d. Shifting/Resetting...",
                     __func__, g_kv_cells_used, n_chunks_tokens, context_size);
                shift_context(true);
            }

            // SECONDARY SAFETY CHECK: If it still exceeds after shift, abort to prevent SIGABRT or memory slot failures!
            if (g_kv_cells_used + n_chunks_tokens > (size_t)(context_size - OVERFLOW_HEADROOM)) {
                LOGe("%s: Cannot process multimodal prompt: Requires %zu tokens, KV cells after reset: %zu, limit: %d.",
                     __func__, n_chunks_tokens, g_kv_cells_used, context_size);
                mtmd_input_chunks_free(chunks);
                mtmd_bitmap_free(g_cached_bmp);
                g_cached_bmp = nullptr;
                return 2;
            }

            // mtmd_helper evaluates the chunks (text parts and image embeddings) sequentially into KV cache
            // CRITICAL FIX: logits_last MUST BE true so that llama_decode generates logits for the last token.
            // Otherwise, get_logits_ith will throw "invalid logits id -1, reason: corrupt output buffer" inside generateNextToken().
            int32_t res = mtmd_helper_eval_chunks(g_mtmd_ctx, g_context, chunks, current_position, 0, g_n_batch, true, &new_n_past);
            if (res == 0) {
                current_position = new_n_past;
                g_kv_cells_used += n_chunks_tokens;
                LOGi("Multimodal eval OK. New pos: %d, KV cells used: %zu", current_position, g_kv_cells_used);
            } else {
                LOGe("mtmd_helper_eval_chunks failed with %d", res);
                mtmd_input_chunks_free(chunks);
                mtmd_bitmap_free(g_cached_bmp);
                g_cached_bmp = nullptr;
                return 2;
            }
        } else {
            LOGe("mtmd_tokenize failed to parse the prompt chunks.");
            mtmd_input_chunks_free(chunks);
            mtmd_bitmap_free(g_cached_bmp);
            g_cached_bmp = nullptr;
            return 2;
        }

        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(g_cached_bmp);
        g_cached_bmp = nullptr;
    } else {
        LOGi("No cached image bitmap. Processing text-only prompt.");
        // Strip any remaining image markers from text-only prompt to avoid LLM seeing them as text
        std::string clean_prompt = formatted_user_prompt;
        {
            const std::string markers[] = {"<__image__>", "<__media__>"};
            for (const auto& m : markers) {
                size_t pos;
                while ((pos = clean_prompt.find(m)) != std::string::npos) {
                    clean_prompt.erase(pos, m.length());
                }
            }
        }
        // Decode text-only prompt
        auto user_tokens = common_tokenize(g_context, clean_prompt, has_chat_template, has_chat_template);
        for (auto id: user_tokens) {
            LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
        }

        const int user_prompt_size = (int) user_tokens.size();
        int context_size = static_cast<int>(llama_n_ctx(g_context));
        const int max_batch_size = context_size - OVERFLOW_HEADROOM;
        if (user_prompt_size > max_batch_size) {
            const int skipped_tokens = user_prompt_size - max_batch_size;
            user_tokens.resize(max_batch_size);
            LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
        }

        // Decode user tokens in batches
        if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
            LOGe("%s: llama_decode() failed!", __func__);
            return 2;
        }

        current_position += user_tokens.size();
        g_kv_cells_used += user_tokens.size();
    }

    if (n_predict < 0) {
        // Unlimited: generování do EOG nebo context size (context shifting se postará o overflow)
        stop_generation_position = INT32_MAX;
        LOGi("%s: n_predict=%d -> unlimited generation (until EOG or context shift)", __func__, n_predict);
    } else {
        stop_generation_position = current_position + n_predict;
        LOGi("%s: n_predict=%d -> stop at position %d", __func__, n_predict, stop_generation_position);
    }
    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject thiz
) {
    (void)thiz;
    // Infinite text generation via context shifting
    // Používáme g_kv_cells_used pro přesnou detekci přetečení (M-RoPE divergence fix)
    int context_size = static_cast<int>(llama_n_ctx(g_context));
    if (g_kv_cells_used >= (size_t)(context_size - OVERFLOW_HEADROOM)) {
        LOGw("%s: Context full! KV cells used: %zu, limit: %d. Shifting...", __func__, g_kv_cells_used, context_size);
        shift_context();
    }

    // Stop if reaching the marked position
    if (current_position >= stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d", __func__, stop_generation_position);
        // Desync fix: Přidat dosavadní odpověď do chat_msgs, i když není ukončena EOG.
        // Bez tohoto by KV cache obsahovala odpověď, ale chat_msgs ne -> desync při dalším promptu.
        if (assistant_ss.tellp() > 0) {
            chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        }
        return nullptr;
    }

    // Sample next token
    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    // Populate the batch with new token, then decode
    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }

    // Update position
    current_position++;
    g_kv_cells_used++;

    // Check for EOG (End of Generation) / EOS
    // Only if NOT ignored by user setting
    // [MODIFIED: Controlled by g_ignore_eos]
    if (!g_ignore_eos && llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    // If not EOG, convert to text
    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;

    // Create and return a valid UTF-8 Java string
    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", new_token_id, cached_token_chars.c_str(), new_token_chars.c_str());

        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = env->NewStringUTF("");
    }
    return result;
}



extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_resetContextNative(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    LOGi("Performing explicit CONTEXT RESET via JNI...");
    // Reset internal states (KV cache, history, pointers)
    reset_long_term_states();
    reset_short_term_states();
    
    // Reset sampler
    if (g_sampler) {
         common_sampler_reset(g_sampler);
    }
    LOGi("Context reset finished.");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Free up resources
    common_sampler_free(g_sampler);
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    if (g_cached_bmp) {
        mtmd_bitmap_free(g_cached_bmp);
        g_cached_bmp = nullptr;
    }
    if (g_mtmd_ctx) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
        LOGi("mtmd_context vision projector was freed.");
    }
    llama_free(g_context);
    llama_model_free(g_model);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    llama_backend_free();
}
