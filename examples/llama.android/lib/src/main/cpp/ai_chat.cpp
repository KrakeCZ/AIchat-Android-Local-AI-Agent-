#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <atomic>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <iomanip>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

// FIX: Include ordering and usage of quotes to ensure local headers are found
#include "build-info.h"
#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "llama.h"
#include "logging.h"
#include "mtmd-helper.h"
#include "mtmd.h"
#include "sampling.h"

static std::string g_native_lib_dir      = "";
static std::string g_active_backend_info = "CPU (ARM64)";

template <class T> static std::string join(const std::vector<T> & values, const std::string & delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) {
            str << delim;
        }
    }
    return str.str();
}

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int N_THREADS_MIN      = 2;
constexpr int N_THREADS_MAX      = 4;
constexpr int N_THREADS_HEADROOM = 2;

constexpr int   DEFAULT_CONTEXT_SIZE = 4096;
constexpr int   OVERFLOW_HEADROOM    = 4;
constexpr int   BATCH_SIZE           = 512;
constexpr float DEFAULT_SAMPLER_TEMP = 0.7f;

static llama_model *             g_model;
static llama_context *           g_context;
static llama_batch               g_batch;
static common_chat_templates_ptr g_chat_templates;
static bool                      g_use_jinja = false;  // Resolved at prepare() based on model template
static bool g_template_has_reasoning         = false;  // Resolved at prepare(): true if template uses reasoning_content
static common_sampler * g_sampler;
static mtmd_context *   g_mtmd_ctx = nullptr;

// Global variable to store KV Cache Mode between load() and prepare()/init_context()
// 0=F16, 1=Q8_0, 2=Q4_0, 3=Q4_1, 4=Q5_0, 5=Q5_1, 6=IQ1_S
static int g_kv_cache_mode = 1;  // Default to Q8_0 (Balanced)

// Globals for new parameters
static int  g_n_ctx            = 0;
static int  g_n_batch          = BATCH_SIZE;
static int  g_n_ubatch         = BATCH_SIZE;
static bool g_flash_attn       = true;
// New parameters globals
static bool g_ignore_eos       = false;
static bool g_timing_per_token = false;

// Global sampling params — persistent between setGrammar/configureSampling calls
static common_params_sampling g_sparams;

// Globals for Thinking / Reasoning control
static bool        g_enable_thinking          = true;
static int32_t     g_reasoning_budget_tokens  = 0;  // 0 = unlimited / no constraint
static std::string g_thinking_effort          = "medium";
static std::string g_model_thinking_start_tag = "<think>";
static std::string g_model_thinking_end_tag   = "</think>";

enum class FinishReason : int32_t { EOG = 0, LENGTH = 1, CONTEXT_FULL = 2, CANCELLED = 3, ERROR_STATE = 4 };

static const char * finish_reason_to_string(FinishReason reason) {
    switch (reason) {
        case FinishReason::EOG:
            return "EOG";
        case FinishReason::LENGTH:
            return "LENGTH";
        case FinishReason::CONTEXT_FULL:
            return "CONTEXT_FULL";
        case FinishReason::CANCELLED:
            return "CANCELLED";
        case FinishReason::ERROR_STATE:
            return "ERROR";
    }
    return "UNKNOWN";
}

enum class SemanticEventType : int32_t {
    REASONING_DELTA = 0,
    CONTENT_DELTA   = 1,
    TOOL_CALL_DELTA = 2,
    FINISHED        = 3,
    ERROR_EVENT     = 4
};

struct NativeSemanticEvent {
    int64_t           generation_id = 0;
    int64_t           sequence      = 0;
    SemanticEventType type          = SemanticEventType::CONTENT_DELTA;
    std::string       text;
    // Tool call fields
    int32_t           tool_index = -1;
    std::string       tool_id;
    std::string       tool_name;
    std::string       tool_args_delta;
    // Finished fields
    FinishReason      finish_reason    = FinishReason::EOG;
    int32_t           prompt_tokens    = 0;
    int32_t           generated_tokens = 0;
};

// --- Phase 1: Native Tool Protocol & Turn State ---
enum class NativeToolChoice : int32_t { AUTO = 0, REQUIRED = 1, NONE = 2 };

struct PendingToolCallState {
    std::string id;
    std::string name;
    std::string arguments;
    bool        completed          = false;
    bool        waiting_for_result = false;
};

struct NativeTurnFormat {
    common_chat_format                  format = COMMON_CHAT_FORMAT_CONTENT_ONLY;
    std::string                         prompt;
    std::string                         generation_prompt;
    std::string                         parser_source;
    common_grammar                      grammar;
    bool                                grammar_lazy = false;
    std::vector<common_grammar_trigger> grammar_triggers;
    std::vector<std::string>            preserved_tokens;
    std::vector<std::string>            additional_stops;
};

static std::vector<common_chat_tool> g_configured_tools;
static NativeToolChoice              g_tool_policy = NativeToolChoice::AUTO;
static PendingToolCallState          g_pending_tool_call;
static NativeTurnFormat              g_current_turn_format;

static std::mutex                      g_event_queue_mutex;
static std::deque<NativeSemanticEvent> g_native_event_queue;
static std::atomic<int64_t>            g_current_generation_id{ 0 };
static std::atomic<int64_t>            g_event_sequence_counter{ 0 };
static std::atomic<bool>               g_cancel_requested{ false };

// Returns the length of the valid UTF-8 prefix of str that ends on a complete character boundary.
// Any trailing incomplete multi-byte sequence (1 to 3 bytes) is preserved in pending buffer.
static size_t utf8_valid_complete_prefix_len(const std::string & str) {
    if (str.empty()) {
        return 0;
    }
    size_t len        = str.size();
    size_t i          = 0;
    size_t last_valid = 0;
    while (i < len) {
        unsigned char c       = static_cast<unsigned char>(str[i]);
        size_t        seq_len = 0;
        if ((c & 0x80) == 0x00) {
            seq_len = 1;
        } else if ((c & 0xE0) == 0xC0) {
            seq_len = 2;
        } else if ((c & 0xF0) == 0xE0) {
            seq_len = 3;
        } else if ((c & 0xF8) == 0xF0) {
            seq_len = 4;
        } else {
            // Stray continuation byte or unexpected prefix: skip 1 byte to keep stream moving
            i++;
            last_valid = i;
            continue;
        }

        if (i + seq_len <= len) {
            bool ok = true;
            for (size_t j = 1; j < seq_len; ++j) {
                if ((static_cast<unsigned char>(str[i + j]) & 0xC0) != 0x80) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                i += seq_len;
                last_valid = i;
            } else {
                i++;
                last_valid = i;
            }
        } else {
            // Incomplete trailing sequence: stop here and wait for next token piece
            break;
        }
    }
    return last_valid;
}

static std::string g_pending_content_utf8;
static std::string g_pending_reasoning_utf8;

static void push_semantic_event(NativeSemanticEvent ev) {
    ev.generation_id = g_current_generation_id.load();
    ev.sequence      = g_event_sequence_counter.fetch_add(1);

    if (ev.type == SemanticEventType::CONTENT_DELTA) {
        if (!g_pending_content_utf8.empty() || !ev.text.empty()) {
            if (!g_pending_content_utf8.empty()) {
                ev.text = g_pending_content_utf8 + ev.text;
                g_pending_content_utf8.clear();
            }
            size_t valid_len = utf8_valid_complete_prefix_len(ev.text);
            if (valid_len < ev.text.size()) {
                g_pending_content_utf8 = ev.text.substr(valid_len);
                ev.text                = ev.text.substr(0, valid_len);
            }
            if (ev.text.empty()) {
                return;
            }
        }
    } else if (ev.type == SemanticEventType::REASONING_DELTA) {
        if (!g_pending_reasoning_utf8.empty() || !ev.text.empty()) {
            if (!g_pending_reasoning_utf8.empty()) {
                ev.text = g_pending_reasoning_utf8 + ev.text;
                g_pending_reasoning_utf8.clear();
            }
            size_t valid_len = utf8_valid_complete_prefix_len(ev.text);
            if (valid_len < ev.text.size()) {
                g_pending_reasoning_utf8 = ev.text.substr(valid_len);
                ev.text                  = ev.text.substr(0, valid_len);
            }
            if (ev.text.empty()) {
                return;
            }
        }
    }

    std::lock_guard<std::mutex> lock(g_event_queue_mutex);
    if (!g_native_event_queue.empty()) {
        auto & last = g_native_event_queue.back();
        if (last.generation_id == ev.generation_id) {
            if (last.type == SemanticEventType::REASONING_DELTA && ev.type == SemanticEventType::REASONING_DELTA) {
                last.text += ev.text;
                return;
            }
            if (last.type == SemanticEventType::CONTENT_DELTA && ev.type == SemanticEventType::CONTENT_DELTA) {
                last.text += ev.text;
                return;
            }
        }
    }
    if (g_native_event_queue.size() >= 8192) {
        // Protect critical events from being dropped: only drop oldest text deltas
        bool dropped = false;
        for (auto it = g_native_event_queue.begin(); it != g_native_event_queue.end(); ++it) {
            if (it->type == SemanticEventType::REASONING_DELTA || it->type == SemanticEventType::CONTENT_DELTA) {
                g_native_event_queue.erase(it);
                dropped = true;
                break;
            }
        }
        if (!dropped) {
            g_native_event_queue.pop_front();
        }
    }
    g_native_event_queue.push_back(std::move(ev));
}

static void clear_native_event_queue() {
    std::lock_guard<std::mutex> lock(g_event_queue_mutex);
    g_native_event_queue.clear();
}

static std::string generate_tool_call_id() {
    static std::atomic<uint64_t> counter{ 1 };
    char                         buf[32];
    snprintf(buf, sizeof(buf), "call_%08llx", (unsigned long long) counter.fetch_add(1));
    return std::string(buf);
}

// Per-generation parse state for incremental, model-aware parsing
struct GenerationParseState {
    std::string               generated_raw_text;
    common_chat_msg           last_parsed_msg;
    common_chat_parser_params parser_params;
    bool                      has_parser      = false;
    bool                      in_reasoning    = false;
    size_t                    emitted_raw_pos = 0;

    // Semantic channels accumulated for this generation
    std::string                        accumulated_reasoning;
    std::string                        accumulated_content;
    std::vector<common_chat_tool_call> accumulated_tool_calls;
    std::vector<std::string>           tool_call_ids_cache;

    // Metrics & finalization guard
    int32_t      prompt_tokens_count    = 0;
    int32_t      generated_tokens_count = 0;
    bool         is_active              = false;
    bool         is_finalized           = false;
    FinishReason finish_reason          = FinishReason::EOG;

    // Fast-path parsing optimizations
    int32_t tokens_since_last_parse = 0;
    bool    in_structural_sequence  = false;

    void apply_diff(const common_chat_msg_diff & diff) {
        if (!g_enable_thinking) {
            // When thinking is disabled (OFF), reasoning cannot exist. Treat any parsed text strictly as content.
            std::string text = diff.reasoning_content_delta + diff.content_delta;
            if (!text.empty()) {
                const std::vector<std::string> think_tags = { "<think>", "</think>", "[THINK]", "[/THINK]" };
                for (const auto & tag : think_tags) {
                    size_t pos = text.find(tag);
                    while (pos != std::string::npos) {
                        text.erase(pos, tag.length());
                        pos = text.find(tag);
                    }
                }
                if (!text.empty()) {
                    accumulated_content += text;
                    NativeSemanticEvent ev;
                    ev.type = SemanticEventType::CONTENT_DELTA;
                    ev.text = text;
                    push_semantic_event(std::move(ev));
                    LOGd("[SEMANTIC CONTENT DELTA (OFF)]: `%s`", text.c_str());
                }
            }
            if (diff.tool_call_index != std::string::npos) {
                if (accumulated_tool_calls.size() <= diff.tool_call_index) {
                    accumulated_tool_calls.resize(diff.tool_call_index + 1);
                }
                auto & tc = accumulated_tool_calls[diff.tool_call_index];
                if (!diff.tool_call_delta.id.empty()) {
                    tc.id = diff.tool_call_delta.id;
                }
                if (!diff.tool_call_delta.name.empty()) {
                    tc.name = diff.tool_call_delta.name;
                }
                tc.arguments += diff.tool_call_delta.arguments;

                NativeSemanticEvent ev;
                ev.type            = SemanticEventType::TOOL_CALL_DELTA;
                ev.tool_index      = static_cast<int32_t>(diff.tool_call_index);
                ev.tool_id         = diff.tool_call_delta.id;
                ev.tool_name       = diff.tool_call_delta.name;
                ev.tool_args_delta = diff.tool_call_delta.arguments;
                push_semantic_event(std::move(ev));
            }
            return;
        }

        if (!diff.reasoning_content_delta.empty()) {
            in_reasoning = true;
            accumulated_reasoning += diff.reasoning_content_delta;
            NativeSemanticEvent ev;
            ev.type = SemanticEventType::REASONING_DELTA;
            ev.text = diff.reasoning_content_delta;
            push_semantic_event(std::move(ev));
            LOGd("[SEMANTIC REASONING DELTA]: `%s`", diff.reasoning_content_delta.c_str());
        }
        if (!diff.content_delta.empty()) {
            in_reasoning                = false;
            std::string       text      = diff.content_delta;
            // Guard: If a closing think tag leaked into content delta at transition boundary, strip it
            const std::string think_end = "</think>";
            size_t            pos       = text.find(think_end);
            while (pos != std::string::npos) {
                text.erase(pos, think_end.length());
                pos = text.find(think_end);
            }
            if (!text.empty()) {
                accumulated_content += text;
                NativeSemanticEvent ev;
                ev.type = SemanticEventType::CONTENT_DELTA;
                ev.text = text;
                push_semantic_event(std::move(ev));
                LOGd("[SEMANTIC CONTENT DELTA]: `%s`", text.c_str());
            }
        }
        if (diff.tool_call_index != std::string::npos) {
            if (accumulated_tool_calls.size() <= diff.tool_call_index) {
                accumulated_tool_calls.resize(diff.tool_call_index + 1);
            }
            auto & tc = accumulated_tool_calls[diff.tool_call_index];
            if (!diff.tool_call_delta.id.empty()) {
                tc.id = diff.tool_call_delta.id;
            }
            if (!diff.tool_call_delta.name.empty()) {
                tc.name = diff.tool_call_delta.name;
            }
            tc.arguments += diff.tool_call_delta.arguments;

            NativeSemanticEvent ev;
            ev.type            = SemanticEventType::TOOL_CALL_DELTA;
            ev.tool_index      = static_cast<int32_t>(diff.tool_call_index);
            ev.tool_id         = diff.tool_call_delta.id;
            ev.tool_name       = diff.tool_call_delta.name;
            ev.tool_args_delta = diff.tool_call_delta.arguments;
            push_semantic_event(std::move(ev));
            LOGi("[SEMANTIC TOOL_CALL DELTA]: index=%zu, id='%s', name='%s', args_delta='%s'", diff.tool_call_index,
                 diff.tool_call_delta.id.c_str(), diff.tool_call_delta.name.c_str(),
                 diff.tool_call_delta.arguments.c_str());
        }
    }

    void reset() {
        generated_raw_text.clear();
        last_parsed_msg = common_chat_msg();
        parser_params   = common_chat_parser_params();
        has_parser      = false;
        in_reasoning    = false;
        emitted_raw_pos = 0;
        accumulated_reasoning.clear();
        accumulated_content.clear();
        accumulated_tool_calls.clear();
        tool_call_ids_cache.clear();
        prompt_tokens_count     = 0;
        generated_tokens_count  = 0;
        is_active               = false;
        is_finalized            = false;
        finish_reason           = FinishReason::EOG;
        tokens_since_last_parse = 0;
        in_structural_sequence  = false;
    }
};

static GenerationParseState g_gen_state;

// Tracking of conversational messages and tokens position
constexpr const char * ROLE_SYSTEM    = "system";
constexpr const char * ROLE_USER      = "user";
constexpr const char * ROLE_ASSISTANT = "assistant";
constexpr const char * ROLE_TOOL      = "tool";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos                    system_prompt_position;
static llama_pos                    current_position;
static std::string                  g_deferred_system_prompt;
static size_t                   g_kv_cells_used = 0;  // Skutečné obsazení KV cache buněk (nezávislé na M-RoPE pozicích)
static mtmd_bitmap *            g_cached_bmp    = nullptr;
static std::vector<llama_token> g_cached_tokens;      // Prefix Cache token tracking

extern "C" JNIEXPORT void JNICALL Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv * env,
                                                                                        jobject  thiz,
                                                                                        jstring  nativeLibDir) {
    (void) thiz;  // Unused
    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Loading all CPU backend variants
    const auto * path_to_backend = env->GetStringUTFChars(nativeLibDir, nullptr);
    LOGi("Loading backends from %s", path_to_backend);
    g_native_lib_dir = path_to_backend;

    // Set FastRPC path for DSP skeleton (libggml-htp-v75.so / libggml-htp-v73.so)
    setenv("ADSP_LIBRARY_PATH", path_to_backend, 1);
    setenv("GGML_BACKEND_PATH", path_to_backend, 1);

    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();
    LOGi("Backend initiated; Build: %s; Log handler set.", llama_build_info());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_getLoadedBackendInfoNative(JNIEnv * env, jobject thiz) {
    (void) thiz;
    return env->NewStringUTF(g_active_backend_info.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_loadModelNative(JNIEnv * env,
                                                                 jobject  thiz,
                                                                 jstring  jmodel_path,
                                                                 jstring  jprojector_path,
                                                                 jint     kvCacheMode,
                                                                 jboolean useMmap,
                                                                 jint     nCtx,
                                                                 jint     nBatch,
                                                                 jint     nUBatch,
                                                                 jboolean flashAttn,
                                                                 jstring  jbackend,
                                                                 jboolean strictBackend,
                                                                 jint     gpuLayers) {
    (void) thiz;
    llama_model_params model_params = llama_model_default_params();

    // Dynamické nastavení MMAP dle volby uživatele
    // true = OS spravuje paměť (Doporučeno)
    // false = Načíst celý model do RAM (Riziko OOM)
    model_params.load_mode = useMmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;

    // Store KV Cache mode for context initialization
    g_kv_cache_mode = kvCacheMode;
    g_n_ctx         = nCtx;
    g_n_batch       = nBatch;
    g_n_ubatch      = nUBatch;
    g_flash_attn    = (bool) flashAttn;

    std::string backend_str = "cpu";
    if (jbackend != nullptr) {
        const char * c_backend = env->GetStringUTFChars(jbackend, nullptr);
        if (c_backend) {
            backend_str = c_backend;
            env->ReleaseStringUTFChars(jbackend, c_backend);
        }
    }

    // Static vector to hold null-terminated devices pointer list for llama_model_params
    static std::vector<ggml_backend_dev_t> s_configured_devices;
    s_configured_devices.clear();

    LOGi("🎯 [Backend Engine] Selected backend: '%s' (strict: %d, gpuLayers: %d)", backend_str.c_str(),
         (int) strictBackend, gpuLayers);

    if (backend_str == "opencl") {
        ggml_backend_reg_t reg_ocl = ggml_backend_reg_by_name("OpenCL");
        if (!reg_ocl && !g_native_lib_dir.empty()) {
            std::string ocl_path = g_native_lib_dir + "/libggml-opencl.so";
            LOGi("Attempting explicit load of %s", ocl_path.c_str());
            reg_ocl = ggml_backend_load(ocl_path.c_str());
        }
        if (!reg_ocl || ggml_backend_reg_dev_count(reg_ocl) == 0) {
            LOGe("❌ OpenCL backend unavailable! (reg_ocl=%p)", reg_ocl);
            if (strictBackend) {
                return 101;  // ERR_OPENCL_UNAVAILABLE
            }
            LOGw("Strict mode disabled: falling back to CPU");
            model_params.n_gpu_layers = 0;
            g_active_backend_info     = "CPU (ARM64 Neon) [Fallback]";
        } else {
            ggml_backend_dev_t ocl_dev = ggml_backend_reg_dev_get(reg_ocl, 0);
            s_configured_devices.push_back(ocl_dev);
            s_configured_devices.push_back(nullptr);
            model_params.devices      = s_configured_devices.data();
            model_params.n_gpu_layers = (gpuLayers != 0) ? gpuLayers : -1;
            g_active_backend_info     = std::string("OpenCL (") + ggml_backend_dev_name(ocl_dev) + ")";
            LOGi("✅ OpenCL device selected: %s (%s), offloading %d layers", ggml_backend_dev_name(ocl_dev),
                 ggml_backend_dev_description(ocl_dev), model_params.n_gpu_layers);
        }
    } else if (backend_str == "hexagon" || backend_str == "htp_v75" || backend_str == "htp_v73") {
        if (backend_str == "htp_v75") {
            setenv("GGML_HEXAGON_ARCH", "75", 1);
            LOGi("Configured GGML_HEXAGON_ARCH=75 (Snapdragon 8 Gen 3)");
        } else if (backend_str == "htp_v73") {
            setenv("GGML_HEXAGON_ARCH", "73", 1);
            LOGi("Configured GGML_HEXAGON_ARCH=73 (Snapdragon 8 Gen 1/2)");
        }

        ggml_backend_reg_t reg_hex = ggml_backend_reg_by_name("HTP");
        if (!reg_hex) {
            reg_hex = ggml_backend_reg_by_name("Hexagon");
        }
        if (!reg_hex && !g_native_lib_dir.empty()) {
            std::string hex_path = g_native_lib_dir + "/libggml-hexagon.so";
            LOGi("Attempting explicit load of %s", hex_path.c_str());
            reg_hex = ggml_backend_load(hex_path.c_str());
        }
        if (!reg_hex || ggml_backend_reg_dev_count(reg_hex) == 0) {
            LOGe("❌ Hexagon/HTP backend unavailable! (reg_hex=%p)", reg_hex);
            if (strictBackend) {
                return 102;  // ERR_HEXAGON_UNAVAILABLE
            }
            LOGw("Strict mode disabled: falling back to CPU");
            model_params.n_gpu_layers = 0;
            g_active_backend_info     = "CPU (ARM64 Neon) [Fallback]";
        } else {
            ggml_backend_dev_t hex_dev = ggml_backend_reg_dev_get(reg_hex, 0);
            s_configured_devices.push_back(hex_dev);
            s_configured_devices.push_back(nullptr);
            model_params.devices      = s_configured_devices.data();
            model_params.n_gpu_layers = (gpuLayers != 0) ? gpuLayers : -1;
            g_active_backend_info     = std::string("Hexagon/HTP (") + ggml_backend_dev_name(hex_dev) + ")";
            LOGi("✅ Hexagon/HTP device selected: %s (%s), offloading %d layers", ggml_backend_dev_name(hex_dev),
                 ggml_backend_dev_description(hex_dev), model_params.n_gpu_layers);
        }
    } else {
        // CPU
        model_params.n_gpu_layers  = 0;
        ggml_backend_dev_t cpu_dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        if (cpu_dev) {
            s_configured_devices.push_back(cpu_dev);
            s_configured_devices.push_back(nullptr);
            model_params.devices = s_configured_devices.data();
        }
        g_active_backend_info = "CPU (ARM64 Neon)";
        LOGi("✅ CPU backend selected explicitly. n_gpu_layers = 0");
    }

    const auto *      model_path = env->GetStringUTFChars(jmodel_path, nullptr);
    const std::string model_path_str(model_path ? model_path : "");
    LOGd("%s: Loading model from: \n%s (MMAP: %d, KV-Mode: %d, ctx: %d, batch: %d, ubatch: %d, FA: %d)\n", __func__,
         model_path_str.c_str(), (int) useMmap, kvCacheMode, nCtx, nBatch, nUBatch, (int) flashAttn);

    auto * model = llama_model_load_from_file(model_path_str.c_str(), model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        LOGe("❌ llama_model_load_from_file failed on backend '%s'!", backend_str.c_str());
        return 1;
    }
    g_model = model;

    g_chat_templates = common_chat_templates_init(g_model, "");
    if (g_chat_templates && g_chat_templates.get()) {
        const std::string tmpl_src = common_chat_templates_source(g_chat_templates.get());
        g_use_jinja                = (tmpl_src.find("{%") != std::string::npos);
        g_template_has_reasoning   = (tmpl_src.find("reasoning_content") != std::string::npos);
        LOGi("Chat template initialized in loadModelNative. Jinja2: %s, reasoning: %s", g_use_jinja ? "YES" : "NO",
             g_template_has_reasoning ? "YES" : "NO");
    }

    bool projector_loaded = false;
    if (jprojector_path != nullptr) {
        const char * projector_path = env->GetStringUTFChars(jprojector_path, nullptr);
        if (strlen(projector_path) > 0) {
            LOGi("Loading mtmd multimodal projector from explicit path: %s", projector_path);
            struct mtmd_context_params mparams = mtmd_context_params_default();
            g_mtmd_ctx                         = mtmd_init_from_file(projector_path, g_model, mparams);
            if (!g_mtmd_ctx) {
                LOGe("Failed to initialize mtmd_context projector from explicit path!");
            } else {
                LOGi("mtmd_context vision projector initialized successfully.");
                projector_loaded = true;
            }
        }
        env->ReleaseStringUTFChars(jprojector_path, projector_path);
    }

    if (!projector_loaded && !model_path_str.empty()) {
        // Fallback for All-in-One multimodal GGUF (where vision/audio tensors are inside model GGUF)
        struct mtmd_context_params mparams = mtmd_context_params_default();
        g_mtmd_ctx                         = mtmd_init_from_file(model_path_str.c_str(), g_model, mparams);
        if (g_mtmd_ctx) {
            LOGi("✅ Auto-detected and initialized embedded vision/audio projector from All-in-One GGUF model: %s",
                 model_path_str.c_str());
        } else {
            LOGd("No embedded multimodal projector detected in %s (Pure text model).", model_path_str.c_str());
        }
    }
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processImageNative(JNIEnv *   env,
                                                                    jobject    thiz,
                                                                    jbyteArray image_bytes) {
    (void) thiz;

    if (!g_mtmd_ctx || !g_context) {
        LOGe("MTMD Projector or LLM context not initialized. Cannot process image.");
        return JNI_FALSE;
    }

    jsize   length = env->GetArrayLength(image_bytes);
    jbyte * bytes  = env->GetByteArrayElements(image_bytes, nullptr);

    LOGi("Creating vision embeddings from %d bytes...", length);

    auto bmp_wrapper  = mtmd_helper_bitmap_init_from_buf(g_mtmd_ctx, reinterpret_cast<const unsigned char *>(bytes),
                                                         length, false, mtmd_helper_init_opt_default());
    mtmd_bitmap * bmp = bmp_wrapper.bitmap;
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_hasProjectorNative(JNIEnv * env, jobject thiz) {
    (void) env;
    (void) thiz;
    return (g_mtmd_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

static llama_context * init_context(llama_model * model, const int n_ctx_arg = 0) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    // Multi-threading setup
    const int n_threads =
        std::max(N_THREADS_MIN, std::min(N_THREADS_MAX, (int) sysconf(_SC_NPROCESSORS_ONLN) - N_THREADS_HEADROOM));
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
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...", __func__,
             trained_context_size, n_ctx_to_use);
    }
    ctx_params.n_ctx = n_ctx_to_use;

    // Use global batch size if set, otherwise default
    int n_batch_to_use = (g_n_batch > 0) ? g_n_batch : BATCH_SIZE;
    ctx_params.n_batch = n_batch_to_use;

    // Use global physical batch size (ubatch)
    int n_ubatch_to_use = (g_n_ubatch > 0) ? g_n_ubatch : BATCH_SIZE;
    ctx_params.n_ubatch = n_ubatch_to_use;

    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;

    // Flash Attention
    ctx_params.flash_attn_type = g_flash_attn ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;

    // Mapování int z Kotlinu (arrays.xml index 0-14) na ggml_type
    // Upraveno pro sekvenční ID od uživatele
    switch (g_kv_cache_mode) {
        // --- HIGH QUALITY ---
        case 0:  // F16
            ctx_params.type_k = GGML_TYPE_F16;
            ctx_params.type_v = GGML_TYPE_F16;
            break;
        case 1:  // Q8_0
            ctx_params.type_k = GGML_TYPE_Q8_0;
            ctx_params.type_v = GGML_TYPE_Q8_0;
            break;
        case 2:  // Q6_K
            ctx_params.type_k = GGML_TYPE_Q6_K;
            ctx_params.type_v = GGML_TYPE_Q6_K;
            break;
        case 3:  // Q5_K
            ctx_params.type_k = GGML_TYPE_Q5_K;
            ctx_params.type_v = GGML_TYPE_Q5_K;
            break;
        case 4:  // Q4_K
            ctx_params.type_k = GGML_TYPE_Q4_K;
            ctx_params.type_v = GGML_TYPE_Q4_K;
            break;

        // --- LEGACY ---
        case 5:  // Q5_1
            ctx_params.type_k = GGML_TYPE_Q5_1;
            ctx_params.type_v = GGML_TYPE_Q5_1;
            break;
        case 6:  // Q5_0
            ctx_params.type_k = GGML_TYPE_Q5_0;
            ctx_params.type_v = GGML_TYPE_Q5_0;
            break;
        case 7:  // Q4_1
            ctx_params.type_k = GGML_TYPE_Q4_1;
            ctx_params.type_v = GGML_TYPE_Q4_1;
            break;
        case 8:  // Q4_0
            ctx_params.type_k = GGML_TYPE_Q4_0;
            ctx_params.type_v = GGML_TYPE_Q4_0;
            break;

        // --- IQ SERIES ---
        case 9:  // IQ4_NL
            ctx_params.type_k = GGML_TYPE_IQ4_NL;
            ctx_params.type_v = GGML_TYPE_IQ4_NL;
            break;
        case 10:  // IQ3_M - FALLBACK to IQ3_S (IQ3_M not defined in ggml.h)
            ctx_params.type_k = GGML_TYPE_IQ3_S;
            ctx_params.type_v = GGML_TYPE_IQ3_S;
            __android_log_print(ANDROID_LOG_WARN, "LlamaJNI",
                                "IQ3_M requested but not available in ggml.h. Falling back to IQ3_S.");
            break;
        case 11:  // IQ3_S
            ctx_params.type_k = GGML_TYPE_IQ3_S;
            ctx_params.type_v = GGML_TYPE_IQ3_S;
            break;
        case 12:  // IQ2_XS
            ctx_params.type_k = GGML_TYPE_IQ2_XS;
            ctx_params.type_v = GGML_TYPE_IQ2_XS;
            break;
        case 13:  // IQ2_XXS
            ctx_params.type_k = GGML_TYPE_IQ2_XXS;
            ctx_params.type_v = GGML_TYPE_IQ2_XXS;
            break;
        case 14:  // IQ1_S
            ctx_params.type_k = GGML_TYPE_IQ1_S;
            ctx_params.type_v = GGML_TYPE_IQ1_S;
            break;

        default:
            __android_log_print(ANDROID_LOG_WARN, "LlamaJNI", "Unknown cache mode %d, defaulting to Q8_0",
                                g_kv_cache_mode);
            ctx_params.type_k = GGML_TYPE_Q8_0;
            ctx_params.type_v = GGML_TYPE_Q8_0;
            break;
    }
    LOGi("KV Cache quantization set to mode: %d", g_kv_cache_mode);

    auto * context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

static common_sampler * new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    return common_sampler_init(g_model, sparams);
}

// --- NEW FUNCTION: Configure Sampling (RENAMED TO MATCH KOTLIN) ---
extern "C" JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_configureSamplingNative(JNIEnv * env,
                                                                         jobject  thiz,
                                                                         jfloat   temp,
                                                                         jint     top_k,
                                                                         jfloat   top_p,
                                                                         jfloat   min_p,
                                                                         jfloat   xtc_prob,
                                                                         jfloat   xtc_thresh,
                                                                         jfloat   typical_p,
                                                                         jfloat   dyn_temp_range,
                                                                         jfloat   dyn_temp_exp,
                                                                         jfloat   penalty_repeat,
                                                                         jfloat   penalty_freq,
                                                                         jfloat   penalty_present,
                                                                         jint     penalty_last_n,
                                                                         jint     mirostat,
                                                                         jfloat   mirostat_tau,
                                                                         jfloat   mirostat_eta,
                                                                         jstring  samplers_sequence,
                                                                         jboolean use_backend_sampling,
                                                                         // New parameters
                                                                         jboolean ignore_eos,
                                                                         jboolean no_perf,
                                                                         jboolean timing_per_token) {
    (void) env;
    (void) thiz;
    LOGi("Configuring sampling: T=%.2f, K=%d, P=%.2f, MinP=%.2f, PenRep=%.2f, IgnoreEOS=%d", temp, top_k, top_p, min_p,
         penalty_repeat, ignore_eos);

    // Update globals
    g_ignore_eos       = (bool) ignore_eos;
    g_timing_per_token = (bool) timing_per_token;

    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }

    common_params_sampling sparams;
    sparams.temp              = temp;
    sparams.top_k             = top_k;
    sparams.top_p             = top_p;
    sparams.min_p             = min_p;
    sparams.xtc_probability   = xtc_prob;
    sparams.xtc_threshold     = xtc_thresh;
    sparams.typ_p             = typical_p;
    sparams.dynatemp_range    = dyn_temp_range;
    sparams.dynatemp_exponent = dyn_temp_exp;
    sparams.penalty_repeat    = penalty_repeat;
    sparams.penalty_freq      = penalty_freq;
    sparams.penalty_present   = penalty_present;
    sparams.penalty_last_n    = penalty_last_n;
    sparams.mirostat          = mirostat;
    sparams.mirostat_tau      = mirostat_tau;
    sparams.mirostat_eta      = mirostat_eta;

    sparams.backend_sampling = (bool) use_backend_sampling;
    sparams.no_perf          = (bool) no_perf;
    sparams.ignore_eos       = (bool) ignore_eos;
    sparams.timing_per_token = (bool) timing_per_token;

    // Parse samplers sequence string from Kotlin
    // Supports two formats:
    //   1. Keyword format (semicolon-separated): "penalties;dry;top_n_sigma;top_k;typ_p;top_p;min_p;xtc;temperature"
    //      This is the format used by the Kotlin SettingsManager (DEFAULT_SAMPLERS_SEQUENCE).
    //   2. Legacy single-char format: "kpmytxdnrMv" (kept for backward compatibility)
    const char * seq_chars = env->GetStringUTFChars(samplers_sequence, nullptr);
    if (seq_chars && strlen(seq_chars) > 0) {
        sparams.samplers.clear();
        const std::string seq_str(seq_chars);

        // Auto-detect format: keyword mode if contains ';' or any word longer than 1 char
        const bool keyword_mode = (seq_str.find(';') != std::string::npos || seq_str.find('_') != std::string::npos);

        if (keyword_mode) {
            // --- Keyword parser (semicolon-separated) ---
            std::istringstream stream(seq_str);
            std::string        token;
            while (std::getline(stream, token, ';')) {
                // Trim whitespace
                token.erase(0, token.find_first_not_of(" \t\r\n"));
                token.erase(token.find_last_not_of(" \t\r\n") + 1);
                if (token.empty()) {
                    continue;
                }

                if (token == "top_k") {
                    sparams.samplers.push_back(COMMON_SAMPLER_TYPE_TOP_K);
                } else if (token == "top_p") {
                    sparams.samplers.push_back(COMMON_SAMPLER_TYPE_TOP_P);
                } else if (token == "min_p") {
                    sparams.samplers.push_back(COMMON_SAMPLER_TYPE_MIN_P);
                } else if (token == "typ_p") {
                    sparams.samplers.push_back(COMMON_SAMPLER_TYPE_TYPICAL_P);
                } else if (token == "temperature" || token == "temp") {
                    sparams.samplers.push_back(COMMON_SAMPLER_TYPE_TEMPERATURE);
                } else if (token == "xtc") {
                    sparams.samplers.push_back(COMMON_SAMPLER_TYPE_XTC);
                } else if (token == "dry") {
                    sparams.samplers.push_back(COMMON_SAMPLER_TYPE_DRY);
                } else if (token == "top_n_sigma") {
                    sparams.samplers.push_back(COMMON_SAMPLER_TYPE_TOP_N_SIGMA);
                } else if (token == "penalties" || token == "repetition_penalties") {
                    sparams.samplers.push_back(COMMON_SAMPLER_TYPE_PENALTIES);
                } else if (token == "mirostat") {
                    sparams.mirostat = 1;
                    LOGi("Mirostat V1 activated from keyword sequence.");
                } else if (token == "mirostat_v2") {
                    sparams.mirostat = 2;
                    LOGi("Mirostat V2 activated from keyword sequence.");
                } else {
                    LOGw("Unknown sampler keyword '%s' in sequence, ignoring.", token.c_str());
                }
            }
            LOGi("Applied keyword sampler sequence: %s", seq_chars);
        } else {
            // --- Legacy single-char parser ---
            for (int i = 0; seq_chars[i] != '\0'; i++) {
                switch (seq_chars[i]) {
                    case 'k':
                        sparams.samplers.push_back(COMMON_SAMPLER_TYPE_TOP_K);
                        break;
                    case 'p':
                        sparams.samplers.push_back(COMMON_SAMPLER_TYPE_TOP_P);
                        break;
                    case 'm':
                        sparams.samplers.push_back(COMMON_SAMPLER_TYPE_MIN_P);
                        break;
                    case 'y':
                        sparams.samplers.push_back(COMMON_SAMPLER_TYPE_TYPICAL_P);
                        break;
                    case 't':
                        sparams.samplers.push_back(COMMON_SAMPLER_TYPE_TEMPERATURE);
                        break;
                    case 'x':
                        sparams.samplers.push_back(COMMON_SAMPLER_TYPE_XTC);
                        break;
                    case 'd':
                        sparams.samplers.push_back(COMMON_SAMPLER_TYPE_DRY);
                        break;
                    case 'n':
                        sparams.samplers.push_back(COMMON_SAMPLER_TYPE_TOP_N_SIGMA);
                        break;
                    case 'r':
                        sparams.samplers.push_back(COMMON_SAMPLER_TYPE_PENALTIES);
                        break;
                    case 'M':
                        sparams.mirostat = 1;
                        LOGi("Mirostat V1 sampler activated from sequence.");
                        break;
                    case 'v':
                        sparams.mirostat = 2;
                        LOGi("Mirostat V2 sampler activated from sequence.");
                        break;
                    default:
                        LOGw("Unknown sampler char '%c' in sequence, ignoring.", seq_chars[i]);
                        break;
                }
            }
            LOGi("Applied single-char sampler sequence: %s", seq_chars);
        }
    }
    env->ReleaseStringUTFChars(samplers_sequence, seq_chars);

    // Preserve grammar if already set (from setGrammarNative)
    sparams.grammar = g_sparams.grammar;

    // Store globally for grammar re-use
    g_sparams = sparams;

    g_sampler = common_sampler_init(g_model, g_sparams);
    LOGi("Sampler re-initialized successfully.");
}

extern "C" JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_configureThinkingNative(JNIEnv * env,
                                                                         jobject  thiz,
                                                                         jboolean enabled,
                                                                         jint     reasoningBudgetTokens,
                                                                         jstring  effort) {
    (void) thiz;
    g_enable_thinking         = (bool) enabled;
    g_reasoning_budget_tokens = (int32_t) reasoningBudgetTokens;
    if (effort != nullptr) {
        const char * effort_chars = env->GetStringUTFChars(effort, nullptr);
        if (effort_chars) {
            g_thinking_effort = effort_chars;
            env->ReleaseStringUTFChars(effort, effort_chars);
        }
    } else {
        g_thinking_effort = "";
    }
    LOGi("Thinking configured: enabled=%d, budgetTokens=%d (0=unlimited), effort=%s", g_enable_thinking,
         g_reasoning_budget_tokens, g_thinking_effort.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_supportsThinkingNative(JNIEnv * env, jobject thiz) {
    (void) env;
    (void) thiz;
    if (!g_chat_templates) {
        return JNI_FALSE;
    }
    return common_chat_templates_support_enable_thinking(g_chat_templates.get()) ? JNI_TRUE : JNI_FALSE;
}

// ----------------------------------------

// --- GBNF Grammar Constraint for Agent Mode ---
extern "C" JNIEXPORT void JNICALL Java_com_arm_aichat_internal_InferenceEngineImpl_setGrammarNative(JNIEnv * env,
                                                                                                    jobject  thiz,
                                                                                                    jstring  jgrammar) {
    (void) thiz;
    if (jgrammar == nullptr) {
        // Clear grammar
        g_sparams.grammar = common_grammar();
        LOGi("Grammar constraint cleared.");
    } else {
        const char * grammar_str = env->GetStringUTFChars(jgrammar, nullptr);
        if (grammar_str) {
            g_sparams.grammar = common_grammar(COMMON_GRAMMAR_TYPE_USER, std::string(grammar_str));
            LOGi("Grammar constraint set (%d chars).", (int) g_sparams.grammar.grammar.size());
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

// --- Phase 1 JNI APIs for Native Tool Protocol ---

static std::vector<common_chat_tool> parse_tools_json(const std::string & tools_str) {
    std::vector<common_chat_tool> tools;
    if (tools_str.empty()) {
        return tools;
    }
    try {
        auto parsed = common_json::parse_no_throw(tools_str);
        if (parsed.is_discarded()) {
            LOGe("parse_tools_json: JSON parse failed (discarded)");
            return tools;
        }
        // Try OpenAI format first: [{"type": "function", "function": {...}}]
        try {
            tools = common_chat_tools_parse_oaicompat(parsed);
            if (!tools.empty()) {
                return tools;
            }
        } catch (...) {
            tools.clear();
        }
        // Fallback: direct array [{"name": "...", "description": "...", "parameters": {...}}]
        if (parsed.is_array()) {
            for (size_t i = 0; i < parsed.size(); i++) {
                const auto & item = parsed[i];
                if (item.is_object() && item.contains("name")) {
                    common_chat_tool tool;
                    tool.name        = item.value("name", "");
                    tool.description = item.value("description", "");
                    if (item.contains("parameters")) {
                        tool.parameters = item.at("parameters").dump();
                    } else {
                        tool.parameters = "{}";
                    }
                    tools.push_back(std::move(tool));
                }
            }
        }
    } catch (const std::exception & e) {
        LOGe("parse_tools_json: exception: %s", e.what());
    }
    return tools;
}

extern "C" JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_setToolDefinitionsNative(JNIEnv * env,
                                                                          jobject  thiz,
                                                                          jstring  jtools_json) {
    (void) thiz;
    if (jtools_json == nullptr) {
        g_configured_tools.clear();
        LOGi("setToolDefinitionsNative: Tools cleared (0 tools configured)");
        return;
    }
    const char * c_json    = env->GetStringUTFChars(jtools_json, nullptr);
    std::string  tools_str = c_json ? c_json : "";
    if (c_json) {
        env->ReleaseStringUTFChars(jtools_json, c_json);
    }
    if (tools_str.empty()) {
        g_configured_tools.clear();
        LOGi("setToolDefinitionsNative: Tools cleared (0 tools configured)");
        return;
    }
    g_configured_tools = parse_tools_json(tools_str);
    LOGi("setToolDefinitionsNative: %zu tools successfully configured", g_configured_tools.size());
    for (const auto & t : g_configured_tools) {
        LOGd("  Configured tool: '%s' (desc: %zu chars, params: %zu chars)", t.name.c_str(), t.description.size(),
             t.parameters.size());
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_arm_aichat_internal_InferenceEngineImpl_setToolPolicyNative(JNIEnv * env,
                                                                                                       jobject  thiz,
                                                                                                       jint jpolicy) {
    (void) env;
    (void) thiz;
    switch (jpolicy) {
        case 0:
            g_tool_policy = NativeToolChoice::AUTO;
            LOGi("setToolPolicyNative: AUTO (0)");
            break;
        case 1:
            g_tool_policy = NativeToolChoice::REQUIRED;
            LOGi("setToolPolicyNative: REQUIRED (1)");
            break;
        case 2:
            g_tool_policy = NativeToolChoice::NONE;
            LOGi("setToolPolicyNative: NONE (2)");
            break;
        default:
            LOGw("setToolPolicyNative: unknown policy %d, defaulting to AUTO", jpolicy);
            g_tool_policy = NativeToolChoice::AUTO;
            break;
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_getModelCapabilitiesNative(JNIEnv * env, jobject thiz) {
    (void) thiz;
    bool        has_chat_template   = false;
    bool        supports_jinja      = g_use_jinja;
    bool        supports_thinking   = false;
    bool        supports_tools      = false;
    bool        supports_tool_calls = false;
    int         detected_format     = static_cast<int>(g_current_turn_format.format);
    std::string handler_name        = "default";

    // Detect Jinja2 template: if the model's embedded chat template contains Jinja2 syntax
    // (e.g. "{%"), we must use the Jinja2 parser. Older models without any template use
    // the legacy built-in parser. This resolves once so chat_add_and_format stays correct
    // for all models: Gemma4, Llama4, Qwen3 (use_jinja=true) and legacy Llama2 (use_jinja=false).

    if (g_chat_templates && g_chat_templates.get()) {
        has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
        supports_thinking = common_chat_templates_support_enable_thinking(g_chat_templates.get());
        try {
            auto caps = common_chat_templates_get_caps(g_chat_templates.get());
            if (caps.count("supports_tools")) {
                supports_tools = caps["supports_tools"];
            }
            if (caps.count("supports_tool_calls")) {
                supports_tool_calls = caps["supports_tool_calls"];
            }
        } catch (const std::exception & e) {
            LOGw("getModelCapabilitiesNative: error fetching caps: %s", e.what());
        }
        handler_name = common_chat_format_name(g_current_turn_format.format);
    }

    jclass cls = env->FindClass("com/arm/aichat/NativeModelCapabilities");
    if (!cls) {
        LOGe("getModelCapabilitiesNative: Failed to find NativeModelCapabilities class");
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ZZZZZILjava/lang/String;)V");
    if (!ctor) {
        LOGe("getModelCapabilitiesNative: Failed to find NativeModelCapabilities <init>");
        return nullptr;
    }
    jstring jhandler = env->NewStringUTF(handler_name.c_str());
    jobject obj =
        env->NewObject(cls, ctor, (jboolean) has_chat_template, (jboolean) supports_jinja, (jboolean) supports_thinking,
                       (jboolean) supports_tools, (jboolean) supports_tool_calls, (jint) detected_format, jhandler);
    env->DeleteLocalRef(jhandler);
    return obj;
}

extern "C" JNIEXPORT jint JNICALL Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * env, jobject thiz) {
    (void) env;
    (void) thiz;
    auto * context = init_context(g_model);
    if (!context) {
        return 1;
    }
    g_context = context;

    int n_batch_to_use = (g_n_batch > 0) ? g_n_batch : BATCH_SIZE;
    g_batch            = llama_batch_init(n_batch_to_use, 0, 1);

    if (!g_chat_templates && g_model) {
        g_chat_templates = common_chat_templates_init(g_model, "");
    }
    if (g_chat_templates && g_chat_templates.get()) {
        const std::string tmpl_src = common_chat_templates_source(g_chat_templates.get());
        g_use_jinja                = (tmpl_src.find("{%") != std::string::npos);
        g_template_has_reasoning   = (tmpl_src.find("reasoning_content") != std::string::npos);
        LOGi("Chat template Jinja2 mode: %s (template length: %zu, native reasoning_content in template: %s)",
             g_use_jinja ? "ENABLED" : "DISABLED", tmpl_src.size(), g_template_has_reasoning ? "YES" : "NO");
    }

    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
    return 0;
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *      reg  = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.emplace_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C" JNIEXPORT jstring JNICALL Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv * env,
                                                                                                 jobject  thiz) {
    (void) thiz;
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv * env,
                                                                                                 jobject  thiz,
                                                                                                 jint     pp,
                                                                                                 jint     tg,
                                                                                                 jint     pl,
                                                                                                 jint     nr) {
    (void) thiz;
    auto * context = init_context(g_model, pp);
    if (!context) {
        const auto * const err_msg = "Fail to init_context! Bench aborted.";
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
            common_batch_add(g_batch, 0, i, { 0 }, false);
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
                common_batch_add(g_batch, 0, i, { j }, true);
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

    const auto model_size     = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto        backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | " << backend << " | pp "
           << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | " << backend << " | tg "
           << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
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
    current_position       = 0;
    g_kv_cells_used        = 0;
    g_cached_tokens.clear();
    g_deferred_system_prompt.clear();

    g_pending_tool_call   = PendingToolCallState{};
    g_current_turn_format = NativeTurnFormat{};

    if (!keep_bitmap && g_cached_bmp) {
        mtmd_bitmap_free(g_cached_bmp);
        g_cached_bmp = nullptr;
    }

    if (clear_kv_cache) {
        llama_memory_clear(llama_get_memory(g_context), false);
    }
}

/**
 * Context shifting by discarding the older half of the tokens appended after system prompt:
 * - take the [system_prompt_position] first tokens from the original prompt
 * - take half of the last (system_prompt_position - system_prompt_position) tokens
 * - recompute the logits in batches
 *
 * Returns true if shift succeeded, false if in-place shifting is unsupported or impossible.
 * CRITICAL: Never calls reset_long_term_states() internally to prevent decoding orphaned delta prompts!
 */
static bool shift_context_ex(llama_pos & inout_pos, size_t & inout_kv_cells) {


    // Parciální kontext shift pro běžné textové modely s podporou posunu (Llama, Mistral, Gemma)
    const int n_discard = (inout_pos - system_prompt_position) / 2;
    if (n_discard <= 0) {
        LOGw("%s: n_discard <= 0 (%d). Resetting KV cache back to system prompt boundary.", __func__, n_discard);
        reset_long_term_states(true, false);
        inout_pos      = system_prompt_position;
        inout_kv_cells = system_prompt_position;
        g_cached_tokens.clear();
        return true;
    }

    LOGi("%s: Context full (shift supported). Discarding %d tokens via llama_memory_seq_rm/add (boundary=%d)", __func__,
         n_discard, inout_pos);
    if (!llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position,
                             system_prompt_position + n_discard)) {
        LOGe("%s: llama_memory_seq_rm failed during shift_context!", __func__);
        return false;
    }
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, inout_pos, -n_discard);
    inout_pos -= n_discard;
    if (inout_kv_cells >= (size_t) n_discard) {
        inout_kv_cells -= n_discard;
    } else {
        inout_kv_cells = inout_pos;
    }
    if (g_cached_tokens.size() >= (size_t) (system_prompt_position + n_discard)) {
        g_cached_tokens.erase(g_cached_tokens.begin() + system_prompt_position,
                              g_cached_tokens.begin() + system_prompt_position + n_discard);
    } else {
        g_cached_tokens.clear();
    }
    LOGi("%s: Context shifting done! Active position: %d, kv_cells_used: %zu, cached_tokens: %zu", __func__, inout_pos,
         inout_kv_cells, g_cached_tokens.size());
    return true;
}

static bool shift_context(const bool keep_bitmap = false) {
    (void) keep_bitmap;
    return shift_context_ex(current_position, g_kv_cells_used);
}

static bool format_chat_message(const common_chat_msg & msg, bool is_user, std::string & out_formatted) {
    try {
        out_formatted = common_chat_format_single(g_chat_templates.get(), chat_msgs, msg, is_user, g_use_jinja,
                                                  g_enable_thinking, g_thinking_effort);
        return true;
    } catch (const std::exception & e) {
        LOGe("%s: Exception in common_chat_format_single: %s. Formatting aborted (no raw fallback).", __func__,
             e.what());
        return false;
    } catch (...) {
        LOGe("%s: Unknown exception in common_chat_format_single. Formatting aborted (no raw fallback).", __func__);
        return false;
    }
}

/**
 * Completion loop's short-term states:
 * - stop generation position
 * - token chars caching
 * - current assistant message being generated
 */
static llama_pos          stop_generation_position;
static std::string        cached_token_chars;
static std::ostringstream assistant_ss;

static void finalize_generation(FinishReason reason) {
    if (g_gen_state.is_finalized) {
        return;
    }
    g_gen_state.is_finalized  = true;
    g_gen_state.is_active     = false;
    g_gen_state.finish_reason = reason;

    LOGi("%s: Finalizing generation with reason=%s. Raw tokens generated=%d, total length=%zu", __func__,
         finish_reason_to_string(reason), g_gen_state.generated_tokens_count, g_gen_state.generated_raw_text.size());

    // Final flush of incremental parser for tool calls
    if (g_gen_state.has_parser) {
        try {
            auto final_msg = common_chat_parse(g_gen_state.generated_raw_text,
                                               /* is_partial = */ false, g_gen_state.parser_params);
            if (!final_msg.empty()) {
                final_msg.set_tool_call_ids(g_gen_state.tool_call_ids_cache, generate_tool_call_id);
                if (!final_msg.tool_calls.empty()) {
                    g_gen_state.accumulated_tool_calls     = final_msg.tool_calls;
                    g_gen_state.last_parsed_msg.tool_calls = final_msg.tool_calls;
                }
            }
        } catch (const std::exception & e) {
            LOGw("%s: Final PEG parse exception (preserving last partial parse state): %s", __func__, e.what());
        } catch (...) {
            LOGw("%s: Unknown exception during final PEG parse (preserving last partial parse state)", __func__);
        }
        LOGi(
            "[GENERATION COMPLETE SUMMARY]: Reason: %s | Raw tokens: %d (%zu chars) | Reasoning: %zu chars | Content: "
            "%zu chars | Tool calls: %zu",
            finish_reason_to_string(reason), g_gen_state.generated_tokens_count, g_gen_state.generated_raw_text.size(),
            g_gen_state.accumulated_reasoning.size(), g_gen_state.accumulated_content.size(),
            g_gen_state.accumulated_tool_calls.size());
    }

    // Fallback: If no tool calls parsed by PEG parser, check raw text for <tool_call> tags
    if (g_gen_state.accumulated_tool_calls.empty()) {
        const std::string open_tag = "<tool_call>";
        size_t            pos      = g_gen_state.generated_raw_text.find(open_tag);
        if (pos != std::string::npos) {
            size_t      start    = pos + open_tag.length();
            size_t      end      = g_gen_state.generated_raw_text.find("</tool_call>", start);
            std::string json_str = (end != std::string::npos) ?
                                       g_gen_state.generated_raw_text.substr(start, end - start) :
                                       g_gen_state.generated_raw_text.substr(start);
            size_t      first    = json_str.find_first_not_of(" \r\n\t");
            size_t      last     = json_str.find_last_not_of(" \r\n\t");
            if (first != std::string::npos && last != std::string::npos) {
                json_str = json_str.substr(first, last - first + 1);
                try {
                    auto j = common_json::parse_no_throw(json_str);
                    if (!j.is_discarded() && j.is_object()) {
                        std::string tname = j.value("name", "");
                        if (tname.empty() && j.contains("function") && j["function"].is_object()) {
                            tname = j["function"].value("name", "");
                        }
                        if (!tname.empty()) {
                            common_chat_tool_call tc;
                            tc.id   = generate_tool_call_id();
                            tc.name = tname;
                            if (j.contains("arguments")) {
                                if (j["arguments"].is_string()) {
                                    tc.arguments = j["arguments"].get<std::string>();
                                } else {
                                    tc.arguments = j["arguments"].dump();
                                }
                            } else {
                                tc.arguments = "{}";
                            }
                            g_gen_state.accumulated_tool_calls.push_back(tc);
                            g_gen_state.last_parsed_msg.tool_calls.push_back(tc);
                            LOGi("%s: Fallback parsed <tool_call> in raw text: name='%s', args='%s'", __func__,
                                 tc.name.c_str(), tc.arguments.c_str());
                        }
                    }
                } catch (...) {
                }
            }
        }
    }

    // Emit all accumulated tool calls as SemanticEventType::TOOL_CALL_DELTA to event queue
    if (!g_gen_state.accumulated_tool_calls.empty()) {
        const auto & first_call                = g_gen_state.accumulated_tool_calls.front();
        g_pending_tool_call.id                 = first_call.id;
        g_pending_tool_call.name               = first_call.name;
        g_pending_tool_call.arguments          = first_call.arguments;
        g_pending_tool_call.completed          = false;
        g_pending_tool_call.waiting_for_result = true;

        for (size_t idx = 0; idx < g_gen_state.accumulated_tool_calls.size(); ++idx) {
            const auto &        tc = g_gen_state.accumulated_tool_calls[idx];
            NativeSemanticEvent ev;
            ev.type            = SemanticEventType::TOOL_CALL_DELTA;
            ev.tool_index      = static_cast<int32_t>(idx);
            ev.tool_id         = tc.id;
            ev.tool_name       = tc.name;
            ev.tool_args_delta = tc.arguments;
            push_semantic_event(std::move(ev));
            LOGi("[FINALIZE EMIT TOOL_CALL]: idx=%zu id='%s' name='%s' args='%s'", idx, tc.id.c_str(), tc.name.c_str(),
                 tc.arguments.c_str());
        }
    }

    // Flush any residual un-emitted text from generated_raw_text
    if (g_gen_state.emitted_raw_pos < g_gen_state.generated_raw_text.size()) {
        std::string rem             = g_gen_state.generated_raw_text.substr(g_gen_state.emitted_raw_pos);
        g_gen_state.emitted_raw_pos = g_gen_state.generated_raw_text.size();
        if (g_gen_state.in_reasoning && g_enable_thinking) {
            g_gen_state.accumulated_reasoning += rem;
            g_gen_state.last_parsed_msg.reasoning_content += rem;
            NativeSemanticEvent ev;
            ev.type = SemanticEventType::REASONING_DELTA;
            ev.text = std::move(rem);
            push_semantic_event(std::move(ev));
        } else {
            g_gen_state.accumulated_content += rem;
            g_gen_state.last_parsed_msg.content += rem;
            NativeSemanticEvent ev;
            ev.type = SemanticEventType::CONTENT_DELTA;
            ev.text = std::move(rem);
            push_semantic_event(std::move(ev));
        }
    }

    // Flush any pending UTF-8 bytes at stream end directly to event queue
    {
        std::lock_guard<std::mutex> lock(g_event_queue_mutex);
        if (!g_pending_content_utf8.empty()) {
            NativeSemanticEvent flush_ev;
            flush_ev.generation_id = g_current_generation_id.load();
            flush_ev.sequence      = g_event_sequence_counter.fetch_add(1);
            flush_ev.type          = SemanticEventType::CONTENT_DELTA;
            flush_ev.text          = std::move(g_pending_content_utf8);
            g_pending_content_utf8.clear();
            g_native_event_queue.push_back(std::move(flush_ev));
        }
        if (!g_pending_reasoning_utf8.empty()) {
            NativeSemanticEvent flush_ev;
            flush_ev.generation_id = g_current_generation_id.load();
            flush_ev.sequence      = g_event_sequence_counter.fetch_add(1);
            flush_ev.type          = SemanticEventType::REASONING_DELTA;
            flush_ev.text          = std::move(g_pending_reasoning_utf8);
            g_pending_reasoning_utf8.clear();
            g_native_event_queue.push_back(std::move(flush_ev));
        }
    }

    // Enqueue Finished event to decoupled queue
    NativeSemanticEvent finish_ev;
    finish_ev.type             = SemanticEventType::FINISHED;
    finish_ev.finish_reason    = reason;
    finish_ev.prompt_tokens    = g_gen_state.prompt_tokens_count;
    finish_ev.generated_tokens = g_gen_state.generated_tokens_count;
    push_semantic_event(std::move(finish_ev));

    // Persist structured assistant message to history (Guarantee zero desync)
    if (assistant_ss.tellp() > 0 || !g_gen_state.accumulated_content.empty() ||
        !g_gen_state.accumulated_reasoning.empty() || !g_gen_state.accumulated_tool_calls.empty()) {
        common_chat_msg assistant_msg;
        assistant_msg.role = ROLE_ASSISTANT;
        if (!g_gen_state.accumulated_content.empty() || !g_gen_state.accumulated_reasoning.empty()) {
            assistant_msg.content           = g_gen_state.accumulated_content;
            assistant_msg.reasoning_content = g_gen_state.accumulated_reasoning;
            assistant_msg.tool_calls        = g_gen_state.accumulated_tool_calls;
        } else if (g_gen_state.has_parser && !g_gen_state.last_parsed_msg.empty()) {
            assistant_msg      = g_gen_state.last_parsed_msg;
            assistant_msg.role = ROLE_ASSISTANT;
        } else {
            assistant_msg.content = assistant_ss.str();
        }
        chat_msgs.push_back(std::move(assistant_msg));
        LOGd("%s: Stored assistant message to history (content_len=%zu, reasoning_len=%zu, tool_calls=%zu)", __func__,
             chat_msgs.back().content.length(), chat_msgs.back().reasoning_content.length(),
             chat_msgs.back().tool_calls.size());

        // Register pending tool call if assistant returned tool calls and generation finished cleanly
        if (reason == FinishReason::EOG && !chat_msgs.back().tool_calls.empty()) {
            const auto & tc                        = chat_msgs.back().tool_calls.front();
            g_pending_tool_call.id                 = tc.id;
            g_pending_tool_call.name               = tc.name;
            g_pending_tool_call.arguments          = tc.arguments;
            g_pending_tool_call.completed          = false;
            g_pending_tool_call.waiting_for_result = true;
            LOGi("[TOOL_CALL_COMPLETE]: id='%s', name='%s', args_bytes=%zu", tc.id.c_str(), tc.name.c_str(),
                 tc.arguments.size());
        }
    }
}

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
    g_gen_state.reset();
    g_current_turn_format = NativeTurnFormat{};
    g_pending_content_utf8.clear();
    g_pending_reasoning_utf8.clear();
}

static int decode_tokens_in_batches(llama_context *      context,
                                    llama_batch &        batch,
                                    const llama_tokens & tokens,
                                    const llama_pos      start_pos,
                                    const bool           compute_last_logit = false) {
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);

    const llama_pos checkpoint_pos      = start_pos;
    const size_t    checkpoint_kv_cells = g_kv_cells_used;

    int       current_batch_limit = (g_n_batch > 0) ? g_n_batch : BATCH_SIZE;
    llama_pos active_pos          = start_pos;
    size_t    local_kv_cells      = g_kv_cells_used;
    bool      has_shifted         = false;

    for (int i = 0; i < (int) tokens.size(); i += current_batch_limit) {
        const int cur_batch_size = std::min((int) tokens.size() - i, current_batch_limit);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        // Dynamické klouzání (sliding KV cache) přímo během prefillu velkých textů/promptů
        int context_size = static_cast<int>(llama_n_ctx(context));
        if (local_kv_cells + cur_batch_size >= (size_t) (context_size - OVERFLOW_HEADROOM)) {
            LOGw(
                "%s: Current batch won't fit into context! KV cells: %zu, cur_batch: %d, limit: %d. Shifting "
                "dynamically...",
                __func__, local_kv_cells, cur_batch_size, context_size);
            if (!shift_context_ex(active_pos, local_kv_cells)) {
                LOGe("%s: Dynamic in-prefill shift failed!", __func__);
                if (!has_shifted) {
                    LOGw("%s: Rolling back partial prefill from pos %d back to %d", __func__, active_pos,
                         checkpoint_pos);
                    llama_memory_seq_rm(llama_get_memory(context), 0, checkpoint_pos, -1);
                } else {
                    LOGe("%s: Shift already occurred, resetting context state", __func__);
                    reset_long_term_states(true, false);
                }
                return -1;
            }
            has_shifted = true;
        }

        // Add tokens to the batch with proper positions
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id   = tokens[i + j];
            const llama_pos   position   = active_pos + j;
            const bool        want_logit = compute_last_logit && (i + j == (int) tokens.size() - 1);
            common_batch_add(batch, token_id, position, { 0 }, want_logit);
        }

        // Decode this batch
        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            if (!has_shifted) {
                LOGw("%s: Rolling back KV cache from pos %d back to checkpoint %d (cells: %zu)", __func__, active_pos,
                     checkpoint_pos, checkpoint_kv_cells);
                const bool rollback_ok = llama_memory_seq_rm(llama_get_memory(context), 0, checkpoint_pos, -1);
                if (!rollback_ok) {
                    LOGe(
                        "%s: llama_memory_seq_rm rollback FAILED! Context dirty, resetting long term state to ensure "
                        "consistency.",
                        __func__);
                    reset_long_term_states(true, false);
                } else {
                    current_position = checkpoint_pos;
                    g_kv_cells_used  = checkpoint_kv_cells;
                }
            } else {
                LOGe("%s: Decode failed after context shift - resetting to clean state", __func__);
                reset_long_term_states(true, false);
            }
            return decode_result;
        }

        active_pos += cur_batch_size;
        local_kv_cells += cur_batch_size;
    }

    current_position = active_pos;
    g_kv_cells_used  = local_kv_cells;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(JNIEnv * env,
                                                                     jobject  thiz,
                                                                     jstring  jsystem_prompt) {
    (void) thiz;
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Obtain system prompt from JEnv
    const auto * const system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGv("%s: System prompt received: \n%s", __func__, system_prompt);
    std::string formatted_system_prompt(system_prompt);
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);
    LOGi("%s: System prompt received (%zu chars)", __func__, formatted_system_prompt.length());

    common_chat_msg system_msg;
    system_msg.role    = ROLE_SYSTEM;
    system_msg.content = formatted_system_prompt;

    // Format system prompt if applicable
    const bool has_chat_template = (g_chat_templates && g_chat_templates.get()) ?
                                       common_chat_templates_was_explicit(g_chat_templates.get()) :
                                       false;
    if (has_chat_template) {
        const bool format_ok      = format_chat_message(system_msg, false, formatted_system_prompt);
        const bool prompt_dropped = formatted_system_prompt.empty() ||
                                    (!system_msg.content.empty() &&
                                     formatted_system_prompt.find(system_msg.content.substr(
                                         0, std::min<size_t>(20, system_msg.content.length()))) == std::string::npos);

        if (!format_ok || prompt_dropped) {
            LOGw(
                "%s: Standalone system prompt template formatting unsupported or dropped by model template (e.g. "
                "Gemma/Mistral). Deferring system prompt to first user turn.",
                __func__);
            g_deferred_system_prompt = system_msg.content;
            return 0;
        }
    }

    // Tokenize system prompt
    const auto system_tokens =
        common_tokenize(g_context, formatted_system_prompt, has_chat_template, has_chat_template);
    for (auto id : system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Handle context overflow
    int       context_size   = static_cast<int>(llama_n_ctx(g_context));
    const int max_batch_size = context_size - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d", __func__, (int) system_tokens.size(),
             max_batch_size);
        return 1;
    }

    // Decode system tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Commit system message only after prefill decoding succeeds!
    if (has_chat_template) {
        chat_msgs.push_back(std::move(system_msg));
    }
    system_prompt_position = current_position;
    g_cached_tokens        = system_tokens;
    LOGi("%s: System prompt prefilled and cached in Prefix Cache (%zu tokens).", __func__, g_cached_tokens.size());
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(JNIEnv * env,
                                                                   jobject  thiz,
                                                                   jstring  juser_prompt,
                                                                   jint     n_predict) {
    (void) thiz;
    g_cancel_requested.store(false, std::memory_order_release);

    // If the previous generation was interrupted/cancelled before finalize_generation was called,
    // finalize it now to commit the generated assistant turn into chat_msgs before starting the new user prompt!
    if (!g_gen_state.is_finalized && g_gen_state.is_active) {
        LOGw(
            "%s: Previous generation was active but not finalized (tokens=%d). Finalizing with CANCELLED before new "
            "prompt.",
            __func__, g_gen_state.generated_tokens_count);
        finalize_generation(FinishReason::CANCELLED);
    }

    // Reset short-term states
    reset_short_term_states();

    // Advance generation id and reset sequence for this new turn
    g_current_generation_id.fetch_add(1);
    g_event_sequence_counter.store(0);
    const auto * const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    std::string        formatted_user_prompt(user_prompt);
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);
    LOGi("%s: User prompt received (%zu chars)", __func__, formatted_user_prompt.length());

    // Prevent hanging pending tool call when starting a new non-empty user prompt
    if (g_pending_tool_call.waiting_for_result && !formatted_user_prompt.empty()) {
        LOGw("%s: Resetting hanging pending tool call ('%s') on new user prompt to prevent engine stall.", __func__,
             g_pending_tool_call.name.c_str());
        g_pending_tool_call = PendingToolCallState{};
    }

    const bool is_continuation_after_tool =
        (!chat_msgs.empty() && chat_msgs.back().role == ROLE_TOOL && formatted_user_prompt.empty());

    // If a system prompt was deferred because the template does not support standalone system role (e.g. Gemma/Mistral),
    // merge it into the first user turn now.
    if (!g_deferred_system_prompt.empty() && !is_continuation_after_tool) {
        LOGw("%s: Merging deferred system prompt (%zu chars) into first user turn.", __func__,
             g_deferred_system_prompt.length());
        formatted_user_prompt = g_deferred_system_prompt + "\n\n" + formatted_user_prompt;
        g_deferred_system_prompt.clear();
    }

    common_chat_msg candidate_user_msg;
    candidate_user_msg.role    = ROLE_USER;
    candidate_user_msg.content = formatted_user_prompt;

    // Format user prompt if applicable (without mutating chat_msgs yet!)
    const bool has_chat_template = (g_chat_templates && g_chat_templates.get()) ?
                                       common_chat_templates_was_explicit(g_chat_templates.get()) :
                                       false;
    if (has_chat_template && !is_continuation_after_tool) {
        if (!format_chat_message(candidate_user_msg, true, formatted_user_prompt)) {
            LOGw("%s: User prompt template formatting failed. Falling back to raw prompt.", __func__);
            formatted_user_prompt = candidate_user_msg.content;
        }

        if (!g_enable_thinking) {
            // Strip any prefilled thinking start tag from the end of formatted_user_prompt if present!
            // Some Jinja2 templates (Qwen 3.5, DeepSeek, etc.) unconditionally add <think>\n.
            // We must strip it so it does NOT get committed to the KV cache prefill.
            const std::vector<std::string> think_starts = {
                "<think>\n", "<think>", "[THINK]\n", "[THINK]", "<|channel|>thought\n", "<|channel|>thought"
            };
            for (const auto & ts : think_starts) {
                if (formatted_user_prompt.size() >= ts.size() &&
                    formatted_user_prompt.compare(formatted_user_prompt.size() - ts.size(), ts.size(), ts) == 0) {
                    formatted_user_prompt.erase(formatted_user_prompt.size() - ts.size());
                    LOGi("%s: Stripped prefilled thinking start tag '%s' from prefill prompt (thinking is OFF).",
                         __func__, ts.c_str());
                    break;
                }
            }
        }
    }

    std::string              start_tag = "<think>";
    std::vector<std::string> end_tags  = { "</think>" };

    // Reset base reasoning budget parameters
    g_sparams.generation_prompt.clear();
    g_sparams.reasoning_budget_tokens = -1;
    g_sparams.reasoning_budget_start.clear();
    g_sparams.reasoning_budget_end.clear();
    g_sparams.reasoning_budget_forced.clear();

    // Turn-level sampling params cloned from global persistent params
    common_params_sampling turn_sparams = g_sparams;

    // Detect thinking tags from chat templates if available, but WITHOUT forcing fragile PEG parser or grammar!
    if (has_chat_template && g_chat_templates) {
        try {
            const std::string tmpl_src = common_chat_templates_source(g_chat_templates.get());
            if (tmpl_src.find("<think>") != std::string::npos) {
                start_tag = "<think>";
                end_tags  = { "</think>" };
            } else if (tmpl_src.find("[THINK]") != std::string::npos) {
                start_tag = "[THINK]";
                end_tags  = { "[/THINK]" };
            } else if (tmpl_src.find("<|channel|>thought") != std::string::npos) {
                start_tag = "<|channel|>thought";
                end_tags  = { "<channel|>", "<|channel|>", "</channel>" };
            }
        } catch (...) {
        }
    }

    g_gen_state.has_parser = false; // Decoupled: Fast O(1) streaming and native tool detection via text contract

    bool                           prompt_ends_with_think = false;
    const std::vector<std::string> think_starts           = { "<think>\n",
                                                              "<think>",
                                                              "[THINK]\n",
                                                              "[THINK]",
                                                              "<thought>\n",
                                                              "<thought>",
                                                              "<start_of_thought>\n",
                                                              "<start_of_thought>",
                                                              "<|channel|>thought\n",
                                                              "<|channel|>thought",
                                                              "<|thought|>\n",
                                                              "<|thought|>",
                                                              "<|begin_of_thought|>\n",
                                                              "<|begin_of_thought|>" };
    for (const auto & ts : think_starts) {
        if (formatted_user_prompt.size() >= ts.size() &&
            formatted_user_prompt.compare(formatted_user_prompt.size() - ts.size(), ts.size(), ts) == 0) {
            prompt_ends_with_think = true;
            break;
        }
    }
    g_gen_state.in_reasoning = g_enable_thinking && prompt_ends_with_think;

    // Strict Backup_14 Sampler Lifecycle Parity:
    // Gramatiku v sampleru vynucujeme POUZE pokud byla explicitne nastavena pres setGrammarNative!
    // V beznem chatu ZAKAZ vkladani GBNF gramatiky, ktera spolehlive nici modely (model byl KO).
    if (!g_sparams.grammar.grammar.empty()) {
        turn_sparams.grammar = g_sparams.grammar;
        if (g_sampler) {
            common_sampler_free(g_sampler);
            g_sampler = nullptr;
        }
        g_sampler = common_sampler_init(g_model, turn_sparams);
        LOGi("%s: Sampler initialized with explicit user grammar.", __func__);
    } else if (!g_sampler) {
        g_sampler = common_sampler_init(g_model, g_sparams);
        LOGi("%s: Clean sampler initialized.", __func__);
    }
    // ZADNY common_sampler_reset(g_sampler) v beznem chatu!

    LOGi("processUserPrompt: g_cached_bmp=%s, prompt contains <__image__>=%s", g_cached_bmp ? "YES" : "NO",
         (formatted_user_prompt.find("<__image__>") != std::string::npos ||
          formatted_user_prompt.find("<__media__>") != std::string::npos) ?
             "YES" :
             "NO");

    if (is_continuation_after_tool) {
        LOGi("%s: Continuation turn after tool result. Decoding generation prompt only.", __func__);
        std::string gen_prompt = g_current_turn_format.generation_prompt;
        if (gen_prompt.empty() && has_chat_template && g_chat_templates) {
            common_chat_templates_inputs cont_inputs;
            cont_inputs.use_jinja             = g_use_jinja;
            cont_inputs.messages              = chat_msgs;
            cont_inputs.add_generation_prompt = true;
            try {
                auto cont_params = common_chat_templates_apply(g_chat_templates.get(), cont_inputs);
                gen_prompt       = cont_params.generation_prompt;
            } catch (...) {
            }
        }
        if (!g_enable_thinking) {
            const std::vector<std::string> think_starts = {
                "<think>\n", "<think>", "[THINK]\n", "[THINK]", "<|channel|>thought\n", "<|channel|>thought"
            };
            for (const auto & ts : think_starts) {
                if (gen_prompt.size() >= ts.size() &&
                    gen_prompt.compare(gen_prompt.size() - ts.size(), ts.size(), ts) == 0) {
                    gen_prompt.erase(gen_prompt.size() - ts.size());
                    LOGi("%s: Stripped thinking start tag from continuation generation prompt.", __func__);
                    break;
                }
            }
        }
        if (!gen_prompt.empty()) {
            auto gen_tokens = common_tokenize(g_context, gen_prompt, false, false);
            if (!gen_tokens.empty()) {
                int decode_res = decode_tokens_in_batches(g_context, g_batch, gen_tokens, current_position, true);
                if (decode_res != 0) {
                    LOGe("%s: decode_tokens_in_batches failed for continuation generation prompt: %d", __func__,
                         decode_res);
                    return 2;
                }
                g_gen_state.prompt_tokens_count = static_cast<int32_t>(gen_tokens.size());
                g_cached_tokens.insert(g_cached_tokens.end(), gen_tokens.begin(), gen_tokens.end());
                LOGi("Continuation generation prompt decode OK (%zu tokens). New pos: %d, KV cells: %zu",
                     gen_tokens.size(), current_position, g_kv_cells_used);
            }
        }
    } else if (g_cached_bmp) {
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
        mtmd_input_text     text      = { formatted_user_prompt.c_str(), formatted_user_prompt.length(), false, false };
        const mtmd_bitmap * bitmaps[] = { g_cached_bmp };
        mtmd_input_chunks * chunks    = mtmd_input_chunks_init();

        if (mtmd_tokenize(g_mtmd_ctx, chunks, &text, bitmaps, 1) == 0) {
            llama_pos new_n_past = 0;

            // SECURITY MEASURE: Check if multimodal prompt fits into remaining KV cache
            // CRITICAL: Používáme g_kv_cells_used (skutečné obsazení buněk), NE current_position!
            // Pro M-RoPE modely: obrázek 36x36 zabere 1296 buněk, ale n_pos = max(36,36) = 36.
            // current_position by ukázal jen 36, ale KV cache spotřebuje 1296 buněk.
            int    context_size    = static_cast<int>(llama_n_ctx(g_context));
            size_t n_chunks_tokens = mtmd_helper_get_n_tokens(chunks);
            LOGi("Multimodal chunks: %zu tokens needed. KV cells used: %zu, Current pos: %d, Limit: %d",
                 n_chunks_tokens, g_kv_cells_used, current_position, context_size);

            if (g_kv_cells_used + n_chunks_tokens > (size_t) (context_size - OVERFLOW_HEADROOM)) {
                LOGe(
                    "%s: Cannot process multimodal prompt: Requires %zu tokens, KV cells used: %zu, limit: %d. "
                    "Multimodal KV cannot be shifted in-place.",
                    __func__, n_chunks_tokens, g_kv_cells_used, context_size);
                mtmd_input_chunks_free(chunks);
                mtmd_bitmap_free(g_cached_bmp);
                g_cached_bmp = nullptr;
                return 2;
            }

            const llama_pos checkpoint_pos      = current_position;
            const size_t    checkpoint_kv_cells = g_kv_cells_used;

            int32_t res = mtmd_helper_eval_chunks(g_mtmd_ctx, g_context, chunks, current_position, 0, g_n_batch, true,
                                                  &new_n_past);
            if (res == 0) {
                current_position = new_n_past;
                g_kv_cells_used += n_chunks_tokens;
                g_gen_state.prompt_tokens_count = static_cast<int32_t>(n_chunks_tokens);
                // COMMIT candidate_user_msg to committed history only now!
                if (has_chat_template) {
                    chat_msgs.push_back(std::move(candidate_user_msg));
                }
                LOGi("Multimodal eval OK. User message committed to history. New pos: %d, KV cells used: %zu",
                     current_position, g_kv_cells_used);
            } else {
                LOGe("mtmd_helper_eval_chunks failed with %d (rolling back KV to checkpoint_pos=%d)", res,
                     checkpoint_pos);
                llama_memory_seq_rm(llama_get_memory(g_context), 0, checkpoint_pos, -1);
                current_position = checkpoint_pos;
                g_kv_cells_used  = checkpoint_kv_cells;
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
        std::string clean_prompt = formatted_user_prompt;
        {
            const std::string markers[] = { "<__image__>", "<__media__>" };
            for (const auto & m : markers) {
                size_t pos;
                while ((pos = clean_prompt.find(m)) != std::string::npos) {
                    clean_prompt.erase(pos, m.length());
                }
            }
        }
        // Pokud je current_position == 0 (napr. po odlozenem system promptu u Gemmy 3),
        // MUSI byt add_special = has_chat_template (true), aby se vlozil <bos> token!
        const bool add_bos_special = (current_position == 0) ? has_chat_template : false;
        auto user_tokens = common_tokenize(g_context, clean_prompt, add_bos_special, true);
        for (auto id : user_tokens) {
            LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
        }

        bool           can_shift = false;
        llama_memory_t mem       = llama_get_memory(g_context);
        if (mem && !g_mtmd_ctx) {
            can_shift = llama_memory_can_shift(mem);
        }

        const int user_prompt_size = (int) user_tokens.size();
        int       context_size     = static_cast<int>(llama_n_ctx(g_context));
        const int max_batch_size   = context_size - OVERFLOW_HEADROOM;

        if (!can_shift && (g_kv_cells_used + user_tokens.size() >= (size_t) max_batch_size)) {
            LOGw(
                "%s: Context full for non-shiftable model (%zu used + %zu new >= %d max). Clearing KV cache and "
                "rebuilding conversation...",
                __func__, g_kv_cells_used, user_tokens.size(), max_batch_size);
            reset_long_term_states(true, false);
            while (chat_msgs.size() > 2) {
                if (chat_msgs.front().role == ROLE_SYSTEM) {
                    chat_msgs.erase(chat_msgs.begin() + 1);
                } else {
                    chat_msgs.erase(chat_msgs.begin());
                }
            }
            if (has_chat_template && g_chat_templates) {
                common_chat_templates_inputs prune_inputs;
                prune_inputs.use_jinja       = g_use_jinja;
                prune_inputs.enable_thinking = g_enable_thinking;
                prune_inputs.reasoning_format =
                    g_enable_thinking ? COMMON_REASONING_FORMAT_AUTO : COMMON_REASONING_FORMAT_NONE;
                if (!g_thinking_effort.empty() && g_thinking_effort != "none") {
                    prune_inputs.chat_template_kwargs["reasoning_effort"] = "\"" + g_thinking_effort + "\"";
                }
                auto rebuild_history = chat_msgs;
                rebuild_history.push_back(candidate_user_msg);
                prune_inputs.messages              = rebuild_history;
                prune_inputs.add_generation_prompt = true;
                try {
                    auto rebuild_params          = common_chat_templates_apply(g_chat_templates.get(), prune_inputs);
                    g_current_turn_format.prompt = rebuild_params.prompt;
                    g_current_turn_format.generation_prompt = rebuild_params.generation_prompt;
                    formatted_user_prompt                   = rebuild_params.prompt;
                } catch (const std::exception & e) {
                    LOGw("%s: Failed to rebuild prompt template after reset: %s", __func__, e.what());
                }
            }
            user_tokens = common_tokenize(g_context, formatted_user_prompt, add_bos_special, true);
        } else if (user_prompt_size > max_batch_size) {
            if (!can_shift) {
                LOGe(
                    "%s: User prompt too long! %d tokens exceed max context capacity %d and backend does not support "
                    "shift.",
                    __func__, user_prompt_size, max_batch_size);
                return 1;
            }
            LOGi(
                "%s: Prompt size (%d tokens) exceeds context (%d). Sliding KV cache will dynamically slide during "
                "prefill.",
                __func__, user_prompt_size, context_size);
        }

        // Hard truncation safety net: Never send more tokens to decoder than max context capacity
        if ((int) user_tokens.size() > max_batch_size) {
            LOGw(
                "%s: Prompt tokens (%zu) exceed max context capacity (%d). Clamping tokens to newest %d to fit context "
                "safely.",
                __func__, user_tokens.size(), max_batch_size, max_batch_size);
            user_tokens.erase(user_tokens.begin(), user_tokens.end() - max_batch_size);
        }

        int decode_res = decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true);
        if (decode_res != 0) {
            LOGe("%s: decode_tokens_in_batches failed w/ %d (candidate user msg NOT committed, KV rolled back)",
                 __func__, decode_res);
            return 2;
        }

        g_gen_state.prompt_tokens_count = static_cast<int32_t>(user_tokens.size());
        g_cached_tokens.insert(g_cached_tokens.end(), user_tokens.begin(), user_tokens.end());
        // COMMIT candidate_user_msg to committed history only now!
        if (has_chat_template) {
            chat_msgs.push_back(std::move(candidate_user_msg));
            LOGi("Text prompt decode OK. User message committed to history. New pos: %d, KV cells: %zu, cached: %zu",
                 current_position, g_kv_cells_used, g_cached_tokens.size());
        }
    }

    if (n_predict < 0) {
        // Unlimited: generování do EOG nebo context size (context shifting se postará o overflow)
        stop_generation_position = INT32_MAX;
        LOGi("%s: n_predict=%d -> unlimited generation (until EOG or context shift)", __func__, n_predict);
    } else {
        stop_generation_position = current_position + n_predict;
        LOGi("%s: n_predict=%d -> stop at position %d", __func__, n_predict, stop_generation_position);
    }
    g_gen_state.is_active = true;
    return 0;
}

// --- Phase 1: Native Submit Tool Result ---
extern "C" JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_submitToolResultNative(JNIEnv * env,
                                                                        jobject  thiz,
                                                                        jstring  jcall_id,
                                                                        jstring  jname,
                                                                        jstring  jcontent) {
    (void) thiz;
    if (!g_context || !g_model) {
        LOGe("submitToolResultNative: Model or context not initialized");
        return JNI_FALSE;
    }
    if (g_gen_state.is_active) {
        LOGe("submitToolResultNative: Engine is currently generating");
        return JNI_FALSE;
    }
    const char * c_call_id = env->GetStringUTFChars(jcall_id, nullptr);
    const char * c_name    = env->GetStringUTFChars(jname, nullptr);
    const char * c_content = env->GetStringUTFChars(jcontent, nullptr);

    std::string call_id = c_call_id ? c_call_id : "";
    std::string name    = c_name ? c_name : "";
    std::string content = c_content ? c_content : "";

    if (c_call_id) {
        env->ReleaseStringUTFChars(jcall_id, c_call_id);
    }
    if (c_name) {
        env->ReleaseStringUTFChars(jname, c_name);
    }
    if (c_content) {
        env->ReleaseStringUTFChars(jcontent, c_content);
    }

    // Check if waiting for result or attaching after assistant turn
    bool has_pending = g_pending_tool_call.waiting_for_result && !g_pending_tool_call.completed;
    if (!has_pending) {
        if (chat_msgs.empty() || chat_msgs.back().role != ROLE_ASSISTANT) {
            LOGe("submitToolResultNative: No pending tool call and previous turn is not assistant (chat_msgs=%zu)",
                 chat_msgs.size());
            return JNI_FALSE;
        }
        LOGi(
            "submitToolResultNative: No explicit pending tool call flag, but previous turn is assistant. Attaching "
            "tool result.");
        if (call_id.empty()) {
            call_id = "call_00000001";
        }
    } else {
        // Pending tool call present: accept empty, default, matching ID or matching name
        if (call_id.empty() || call_id == "call_default" || call_id == g_pending_tool_call.id) {
            call_id = g_pending_tool_call.id;
        } else if (name == g_pending_tool_call.name) {
            LOGi("submitToolResultNative: Tool name match '%s' overrides call_id ('%s' -> '%s')", name.c_str(),
                 call_id.c_str(), g_pending_tool_call.id.c_str());
            call_id = g_pending_tool_call.id;
        }
        if (name.empty()) {
            name = g_pending_tool_call.name;
        }
    }

    LOGi("[TOOL_RESULT_BEGIN]: callId='%s', name='%s', content_len=%zu", call_id.c_str(), name.c_str(), content.size());

    // Construct candidate tool msg
    common_chat_msg candidate_tool_msg;
    candidate_tool_msg.role         = ROLE_TOOL;
    candidate_tool_msg.tool_call_id = call_id;
    candidate_tool_msg.tool_name    = name;
    candidate_tool_msg.content      = content;

    std::string formatted_delta;
    if (g_chat_templates && g_chat_templates.get()) {
        try {
            formatted_delta =
                common_chat_format_single(g_chat_templates.get(), chat_msgs, candidate_tool_msg,
                                          /* add_ass = */ false, g_use_jinja, g_enable_thinking, g_thinking_effort);
        } catch (const std::exception & e) {
            LOGw("submitToolResultNative: Template formatting exception: %s", e.what());
            formatted_delta.clear();
        } catch (...) {
            LOGw("submitToolResultNative: Unknown exception during template formatting");
            formatted_delta.clear();
        }
    }

    if (formatted_delta.empty()) {
        LOGw(
            "submitToolResultNative: Model template returned empty for tool role. Applying universal tool fallback "
            "format.");
        formatted_delta = "\n<tool_response>\n" + content + "\n</tool_response>\n";
    }

    LOGi("submitToolResultNative: Formatted delta (%zu chars): `%s`", formatted_delta.size(), formatted_delta.c_str());

    // Tokenize delta
    auto delta_tokens = common_tokenize(g_context, formatted_delta, false, false);
    if (delta_tokens.empty()) {
        LOGe("submitToolResultNative: Tokenization yielded 0 tokens for formatted delta!");
        return JNI_FALSE;
    }

    // Capacity & shiftability check
    int       context_size   = static_cast<int>(llama_n_ctx(g_context));
    const int max_batch_size = context_size - OVERFLOW_HEADROOM;
    if (g_kv_cells_used + delta_tokens.size() > (size_t) max_batch_size) {
        bool           can_shift = false;
        llama_memory_t mem       = llama_get_memory(g_context);
        if (mem && !g_mtmd_ctx) {
            can_shift = llama_memory_can_shift(mem);
        }
        if (!can_shift) {
            LOGe(
                "submitToolResultNative: Required %zu tokens (+ used %zu) exceeds context limit %d and backend does "
                "not support in-place shift!",
                delta_tokens.size(), g_kv_cells_used, max_batch_size);
            return JNI_FALSE;
        }
        LOGi(
            "submitToolResultNative: Tool result size (%zu tokens, used=%zu) exceeds context headroom (%d). Sliding "
            "KV cache will dynamically slide during prefill.",
            delta_tokens.size(), g_kv_cells_used, context_size);
    }

    const llama_pos pos_before = current_position;

    // decode_tokens_in_batches internally handles dynamic in-prefill sliding, checkpointing, and rollback on error
    int decode_res = decode_tokens_in_batches(g_context, g_batch, delta_tokens, current_position, true);
    if (decode_res != 0) {
        LOGe("submitToolResultNative: decode_tokens_in_batches failed with code %d. Rollback handled by batch decoder.",
             decode_res);
        return JNI_FALSE;
    }

    // Commit to chat_msgs and mark pending call completed
    chat_msgs.push_back(std::move(candidate_tool_msg));
    g_pending_tool_call.completed          = true;
    g_pending_tool_call.waiting_for_result = false;
    g_cached_tokens.insert(g_cached_tokens.end(), delta_tokens.begin(), delta_tokens.end());

    LOGi("[TOOL_RESULT_COMMIT]: tokens=%zu, pos_before=%d, pos_after=%d, kv_cells=%zu, total_msgs=%zu, cached=%zu",
         delta_tokens.size(), pos_before, current_position, g_kv_cells_used, chat_msgs.size(), g_cached_tokens.size());

    return JNI_TRUE;
}

static bool is_valid_utf8(const char * string) {
    if (!string) {
        return true;
    }

    const auto * bytes = (const unsigned char *) string;
    int          num;

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

static jclass    g_string_class = nullptr;
static jmethodID g_string_ctor  = nullptr;
static jstring   g_utf8_charset = nullptr;
static jclass    g_event_cls    = nullptr;
static jmethodID g_event_ctor   = nullptr;

static bool is_pure_ascii(const std::string & str) {
    for (unsigned char c : str) {
        if (c >= 0x80) {
            return false;
        }
    }
    return true;
}

static jstring safe_new_string_utf(JNIEnv * env, const std::string & str) {
    if (str.empty()) {
        return nullptr;
    }
    // Fast path: Pure ASCII avoids all JNI overhead
    if (is_pure_ascii(str)) {
        return env->NewStringUTF(str.c_str());
    }

    if (!g_string_class) {
        jclass local_cls = env->FindClass("java/lang/String");
        if (local_cls) {
            g_string_class = (jclass) env->NewGlobalRef(local_cls);
            g_string_ctor  = env->GetMethodID(g_string_class, "<init>", "([BLjava/lang/String;)V");
            env->DeleteLocalRef(local_cls);
        }
        jstring local_cs = env->NewStringUTF("UTF-8");
        if (local_cs) {
            g_utf8_charset = (jstring) env->NewGlobalRef(local_cs);
            env->DeleteLocalRef(local_cs);
        }
    }

    jsize      len   = static_cast<jsize>(str.size());
    jbyteArray bytes = env->NewByteArray(len);
    if (!bytes) {
        return env->NewStringUTF("");
    }
    env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(str.data()));
    jstring result = nullptr;
    if (g_string_class && g_string_ctor && g_utf8_charset) {
        result = static_cast<jstring>(env->NewObject(g_string_class, g_string_ctor, bytes, g_utf8_charset));
    }
    env->DeleteLocalRef(bytes);
    return result ? result : env->NewStringUTF("");
}

extern "C" JNIEXPORT jstring JNICALL Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(JNIEnv * env,
                                                                                                        jobject  thiz) {
    (void) thiz;
    // Check atomic cancellation request before doing any computation
    if (g_cancel_requested.exchange(false, std::memory_order_acq_rel)) {
        LOGi("%s: Atomic cancel requested before token generation.", __func__);
        finalize_generation(FinishReason::CANCELLED);
        return nullptr;
    }

    // Infinite text generation via context shifting
    // Používáme g_kv_cells_used pro přesnou detekci přetečení (M-RoPE divergence fix)
    int context_size = static_cast<int>(llama_n_ctx(g_context));
    if (g_kv_cells_used >= (size_t) (context_size - OVERFLOW_HEADROOM)) {
        LOGw("%s: Context full! KV cells used: %zu, limit: %d. Shifting...", __func__, g_kv_cells_used, context_size);
        shift_context();
    }

    // Stop if reaching the marked position
    if (current_position >= stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d", __func__, stop_generation_position);
        finalize_generation(FinishReason::LENGTH);
        return nullptr;
    }

    // Sample next token
    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    // Populate the batch with new token, then decode
    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, { 0 }, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        finalize_generation(FinishReason::ERROR_STATE);
        return nullptr;
    }

    // Atomic token commit: update KV bookkeeping immediately after successful decode
    current_position++;
    g_kv_cells_used++;
    g_gen_state.generated_tokens_count++;
    g_cached_tokens.push_back(new_token_id);

    // Check atomic cancellation request after token is committed into KV bookkeeping
    if (g_cancel_requested.exchange(false, std::memory_order_acq_rel)) {
        LOGi("%s: Atomic cancel requested after token decode and commit.", __func__);
        finalize_generation(FinishReason::CANCELLED);
        return nullptr;
    }

    // Check for EOG (End of Generation) / EOS
    // Only if NOT ignored by user setting
    if (!g_ignore_eos && llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        finalize_generation(FinishReason::EOG);
        return nullptr;
    }

    // If not EOG, convert to text
    auto new_token_chars = common_token_to_piece(g_context, new_token_id);

    // Append to accumulated raw generated text for this generation
    g_gen_state.generated_raw_text += new_token_chars;

    // Check additional stops from turn format
    for (const auto & stop_str : g_current_turn_format.additional_stops) {
        if (!stop_str.empty() && g_gen_state.generated_raw_text.size() >= stop_str.size()) {
            if (g_gen_state.generated_raw_text.compare(g_gen_state.generated_raw_text.size() - stop_str.size(),
                                                       stop_str.size(), stop_str) == 0) {
                LOGi("Additional stop sequence matched: '%s'. Finalizing generation.", stop_str.c_str());
                g_gen_state.generated_raw_text.erase(g_gen_state.generated_raw_text.size() - stop_str.size());
                finalize_generation(FinishReason::EOG);
                return nullptr;
            }
        }
    }

    // Fast O(1) semantic streaming with multi-token tag boundary support
    if (!new_token_chars.empty()) {
        struct CoTTagDef {
            std::string open_tag;
            std::string close_tag;
        };

        static const std::vector<CoTTagDef> cot_tags = {
            { "<think>",              "</think>"           },
            { "[THINK]",              "[/THINK]"           },
            { "[THOUGHT]",            "[/THOUGHT]"         },
            { "<thought>",            "</thought>"         },
            { "<start_of_thought>",   "<end_of_thought>"   },
            { "<|channel|>thought",   "<channel|>"         },
            { "<|channel|>thought",   "<|channel|>"        },
            { "<|channel|>thought",   "</channel>"         },
            { "<channel>thought",     "<channel|>"         },
            { "<channel>thought",     "</channel>"         },
            { "<|thought|>",          "<|thought|>"        },
            { "<|begin_of_thought|>", "<|end_of_thought|>" }
        };

        LOGv("STREAM DIAG: raw piece='%s' | raw_total=%zu | reasoning_total=%zu | content_total=%zu",
             new_token_chars.c_str(), g_gen_state.generated_raw_text.size(), g_gen_state.accumulated_reasoning.size(),
             g_gen_state.accumulated_content.size());

        while (g_gen_state.emitted_raw_pos < g_gen_state.generated_raw_text.size()) {
            if (!g_gen_state.in_reasoning) {
                // Not in reasoning: look for opening think tag at or after emitted_raw_pos
                size_t            earliest_tag_pos = std::string::npos;
                const CoTTagDef * matched_tag      = nullptr;
                for (const auto & tag_def : cot_tags) {
                    size_t p = g_gen_state.generated_raw_text.find(tag_def.open_tag, g_gen_state.emitted_raw_pos);
                    if (p != std::string::npos && (earliest_tag_pos == std::string::npos || p < earliest_tag_pos)) {
                        earliest_tag_pos = p;
                        matched_tag      = &tag_def;
                    }
                }

                if (matched_tag && earliest_tag_pos != std::string::npos) {
                    // Found complete open tag!
                    if (earliest_tag_pos > g_gen_state.emitted_raw_pos) {
                        std::string c = g_gen_state.generated_raw_text.substr(
                            g_gen_state.emitted_raw_pos, earliest_tag_pos - g_gen_state.emitted_raw_pos);
                        g_gen_state.accumulated_content += c;
                        g_gen_state.last_parsed_msg.content += c;
                        NativeSemanticEvent ev;
                        ev.type = SemanticEventType::CONTENT_DELTA;
                        ev.text = std::move(c);
                        push_semantic_event(std::move(ev));
                    }
                    g_gen_state.emitted_raw_pos = earliest_tag_pos + matched_tag->open_tag.length();
                    g_gen_state.in_reasoning    = true;
                    g_model_thinking_start_tag  = matched_tag->open_tag;
                    g_model_thinking_end_tag    = matched_tag->close_tag;
                    continue;
                }

                // Check if trailing suffix could be a partial prefix of any open tag
                size_t remaining_len   = g_gen_state.generated_raw_text.size() - g_gen_state.emitted_raw_pos;
                size_t max_partial_len = 0;
                for (const auto & tag_def : cot_tags) {
                    for (size_t l = std::min(remaining_len, tag_def.open_tag.length() - 1); l >= 1; --l) {
                        std::string suffix =
                            g_gen_state.generated_raw_text.substr(g_gen_state.generated_raw_text.size() - l);
                        if (tag_def.open_tag.rfind(suffix, 0) == 0) {
                            if (l > max_partial_len) {
                                max_partial_len = l;
                            }
                            break;
                        }
                    }
                }

                size_t safe_len = remaining_len - max_partial_len;
                if (safe_len > 0) {
                    std::string c = g_gen_state.generated_raw_text.substr(g_gen_state.emitted_raw_pos, safe_len);
                    g_gen_state.accumulated_content += c;
                    g_gen_state.last_parsed_msg.content += c;
                    g_gen_state.emitted_raw_pos += safe_len;
                    NativeSemanticEvent ev;
                    ev.type = SemanticEventType::CONTENT_DELTA;
                    ev.text = std::move(c);
                    push_semantic_event(std::move(ev));
                }
                break;
            } else {
                // In reasoning: look for closing think tag at or after emitted_raw_pos
                size_t            earliest_tag_pos = std::string::npos;
                const CoTTagDef * matched_tag      = nullptr;
                for (const auto & tag_def : cot_tags) {
                    size_t p = g_gen_state.generated_raw_text.find(tag_def.close_tag, g_gen_state.emitted_raw_pos);
                    if (p != std::string::npos && (earliest_tag_pos == std::string::npos || p < earliest_tag_pos)) {
                        earliest_tag_pos = p;
                        matched_tag      = &tag_def;
                    }
                }

                if (matched_tag && earliest_tag_pos != std::string::npos) {
                    // Found complete close tag!
                    if (earliest_tag_pos > g_gen_state.emitted_raw_pos) {
                        std::string r = g_gen_state.generated_raw_text.substr(
                            g_gen_state.emitted_raw_pos, earliest_tag_pos - g_gen_state.emitted_raw_pos);
                        if (g_enable_thinking) {
                            g_gen_state.accumulated_reasoning += r;
                            g_gen_state.last_parsed_msg.reasoning_content += r;
                            NativeSemanticEvent ev;
                            ev.type = SemanticEventType::REASONING_DELTA;
                            ev.text = std::move(r);
                            push_semantic_event(std::move(ev));
                        }
                    }
                    g_gen_state.emitted_raw_pos = earliest_tag_pos + matched_tag->close_tag.length();
                    g_gen_state.in_reasoning    = false;
                    continue;
                }

                // Check if trailing suffix could be a partial prefix of any close tag
                size_t remaining_len   = g_gen_state.generated_raw_text.size() - g_gen_state.emitted_raw_pos;
                size_t max_partial_len = 0;
                for (const auto & tag_def : cot_tags) {
                    for (size_t l = std::min(remaining_len, tag_def.close_tag.length() - 1); l >= 1; --l) {
                        std::string suffix =
                            g_gen_state.generated_raw_text.substr(g_gen_state.generated_raw_text.size() - l);
                        if (tag_def.close_tag.rfind(suffix, 0) == 0) {
                            if (l > max_partial_len) {
                                max_partial_len = l;
                            }
                            break;
                        }
                    }
                }

                size_t safe_len = remaining_len - max_partial_len;
                if (safe_len > 0) {
                    std::string r = g_gen_state.generated_raw_text.substr(g_gen_state.emitted_raw_pos, safe_len);
                    g_gen_state.emitted_raw_pos += safe_len;
                    if (g_enable_thinking) {
                        g_gen_state.accumulated_reasoning += r;
                        g_gen_state.last_parsed_msg.reasoning_content += r;
                        NativeSemanticEvent ev;
                        ev.type = SemanticEventType::REASONING_DELTA;
                        ev.text = std::move(r);
                        push_semantic_event(std::move(ev));
                    }
                }
                break;
            }
        }
    }

    cached_token_chars += new_token_chars;

    // Create and return a valid UTF-8 Java string
    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = safe_new_string_utf(env, cached_token_chars);
        LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", new_token_id, cached_token_chars.c_str(), new_token_chars.c_str());
        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = env->NewStringUTF("");
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_cancelGenerationNative(JNIEnv * env, jobject thiz) {
    (void) env;
    (void) thiz;
    LOGi("cancelGenerationNative invoked from JNI - setting atomic cancel flag.");
    g_cancel_requested.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_pollGenerationEventsNative(JNIEnv * env,
                                                                            jobject  thiz,
                                                                            jint     maxEvents) {
    (void) thiz;
    std::vector<NativeSemanticEvent> events;
    {
        std::lock_guard<std::mutex> lock(g_event_queue_mutex);
        int                         count = 0;
        int                         limit = (maxEvents > 0) ? maxEvents : 64;
        while (!g_native_event_queue.empty() && count < limit) {
            events.push_back(std::move(g_native_event_queue.front()));
            g_native_event_queue.pop_front();
            count++;
        }
    }

    if (!g_event_cls) {
        jclass local_cls = env->FindClass("com/arm/aichat/NativeGenerationEvent");
        if (local_cls) {
            g_event_cls = (jclass) env->NewGlobalRef(local_cls);
            g_event_ctor =
                env->GetMethodID(g_event_cls, "<init>",
                                 "(JJILjava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;III)V");
            env->DeleteLocalRef(local_cls);
        }
    }
    if (!g_event_cls || !g_event_ctor) {
        LOGe("Failed to find class com/arm/aichat/NativeGenerationEvent or constructor");
        return nullptr;
    }
    jclass    event_cls = g_event_cls;
    jmethodID ctor      = g_event_ctor;

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(events.size()), event_cls, nullptr);
    if (!result) {
        LOGe("Failed to allocate jobjectArray for NativeGenerationEvent");
        return nullptr;
    }

    for (size_t i = 0; i < events.size(); i++) {
        const auto & ev        = events[i];
        jstring      jtext     = !ev.text.empty() ? safe_new_string_utf(env, ev.text) : nullptr;
        jstring      jtoolId   = !ev.tool_id.empty() ? safe_new_string_utf(env, ev.tool_id) : nullptr;
        jstring      jtoolName = !ev.tool_name.empty() ? safe_new_string_utf(env, ev.tool_name) : nullptr;
        jstring      jtoolArgs = !ev.tool_args_delta.empty() ? safe_new_string_utf(env, ev.tool_args_delta) : nullptr;

        jobject obj = env->NewObject(event_cls, ctor, (jlong) ev.generation_id, (jlong) ev.sequence,
                                     (jint) static_cast<int32_t>(ev.type), jtext, (jint) ev.tool_index, jtoolId,
                                     jtoolName, jtoolArgs, (jint) static_cast<int32_t>(ev.finish_reason),
                                     (jint) ev.prompt_tokens, (jint) ev.generated_tokens);

        env->SetObjectArrayElement(result, static_cast<jsize>(i), obj);

        if (jtext) {
            env->DeleteLocalRef(jtext);
        }
        if (jtoolId) {
            env->DeleteLocalRef(jtoolId);
        }
        if (jtoolName) {
            env->DeleteLocalRef(jtoolName);
        }
        if (jtoolArgs) {
            env->DeleteLocalRef(jtoolArgs);
        }
        env->DeleteLocalRef(obj);
    }

    return result;
}

extern "C" JNIEXPORT void JNICALL Java_com_arm_aichat_internal_InferenceEngineImpl_resetContextNative(JNIEnv * env,
                                                                                                      jobject  thiz) {
    (void) env;
    (void) thiz;
    LOGi("Performing explicit CONTEXT RESET via JNI...");
    g_cancel_requested.store(false, std::memory_order_release);
    if (!g_gen_state.is_finalized && g_gen_state.is_active) {
        finalize_generation(FinishReason::CANCELLED);
    }
    // Reset internal states (KV cache, history, pointers)
    reset_long_term_states();
    reset_short_term_states();

    // Reset sampler
    if (g_sampler) {
        common_sampler_reset(g_sampler);
    }
    g_pending_content_utf8.clear();
    g_pending_reasoning_utf8.clear();
    LOGi("Context reset finished.");
}

extern "C" JNIEXPORT void JNICALL Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv * env, jobject thiz) {
    (void) env;
    (void) thiz;
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();
    clear_native_event_queue();

    // Free up resources
    common_sampler_free(g_sampler);
    g_chat_templates.reset();
    g_configured_tools.clear();
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

extern "C" JNIEXPORT void JNICALL Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv * env,
                                                                                            jobject  thiz) {
    (void) env;
    (void) thiz;
    clear_native_event_queue();
    llama_backend_free();
}
