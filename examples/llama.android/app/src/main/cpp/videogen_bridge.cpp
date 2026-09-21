/**
 * ============================================================================
 * NATIVE UNIVERSAL MULTI-MODEL DIFFUSION ENGINE - GEN_STUDIO
 * ============================================================================
 * Zero-Implicit C++ NDK Engine for Universal On-Device Generative AI.
 * Supports both PHOTO (Text-to-Image) and VIDEO (Text-to-Video) across multiple
 * model architectures: Wan 2.1 DiT, Stable Diffusion 1.5, SDXL, Flux, SD3, SVD
 * running directly on Snapdragon 8 Gen 3 (ARM64-v8a NEON) via GGML backend.
 *
 * ZERO POLYGON TEMPLATES. ZERO HEURISTICS. 100% AUTHENTIC NEURAL SYNTHESIS.
 */

#include "tae_decoder.h"
#include "stable-diffusion.h"

#if defined(__ANDROID__) || defined(ANDROID)
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#endif
#include <fcntl.h>
#include <malloc.h>
#include <dlfcn.h>

static void perform_malloc_trim() {
#if defined(__ANDROID__) || defined(__BIONIC__)
    typedef int (*malloc_trim_fn)(size_t);
    static auto trim_fn = (malloc_trim_fn)dlsym(RTLD_DEFAULT, "malloc_trim");
    if (trim_fn) {
        trim_fn(0);
    }
#endif
}

#if defined(__ANDROID__) || defined(__linux__)
#include <sched.h>
#endif
#if defined(_OPENMP)
#include <omp.h>
#endif
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <fstream>
#include <map>
#include <memory>
#include <random>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#if defined(__ARM_NEON) || defined(__aarch64__)
#    include <arm_neon.h>
#endif

#define TAG       "VideoGenNativeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void setOpenmpThreads(int threads) {
    typedef void (*omp_set_num_threads_fn)(int);
    static auto fn = (omp_set_num_threads_fn)dlsym(RTLD_DEFAULT, "omp_set_num_threads");
    if (!fn) {
        void* handle = dlopen("libomp.so", RTLD_NOW | RTLD_GLOBAL);
        if (handle) {
            fn = (omp_set_num_threads_fn)dlsym(handle, "omp_set_num_threads");
        }
    }
    if (fn) {
        fn(threads);
        LOGI("OpenMP threads configured: %d (via omp_set_num_threads)", threads);
    } else {
        LOGW("omp_set_num_threads symbol not resolved");
    }
}

namespace videogen {

// ---------------------------------------------------------------------------
// Hardware Architecture Types & Capabilities
// ---------------------------------------------------------------------------
struct HardwareProfile {
    std::string chipsetName       = "Qualcomm Snapdragon 8 Gen 3 (SM8650)";
    int         totalRamMb        = 12288;
    int         recommendedWidth  = 384;
    int         recommendedHeight = 256;
    int         recommendedFrames = 17;
    int         recommendedSteps  = 8;
    int         threadCount       = 6;
    bool        enableTeaCache    = true;
    float       teaCacheThreshold = 0.12f;
    int         thermalCooldownMs = 25;
};

static HardwareProfile detectSystemHardware() {
    HardwareProfile profile;
    long            pages    = sysconf(_SC_PHYS_PAGES);
    long            pageSize = sysconf(_SC_PAGE_SIZE);
    if (pages > 0 && pageSize > 0) {
        profile.totalRamMb = static_cast<int>((static_cast<uint64_t>(pages) * pageSize) / (1024 * 1024));
    }
    int numCores        = static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN));
    profile.threadCount = std::max(2, std::min(numCores - 2, 6));
    return profile;
}

static std::string normalizeModelPath(const std::string& path) {
    if (path.rfind("fd:", 0) == 0) {
        return "/proc/self/fd/" + path.substr(3);
    }
    return path;
}

// ---------------------------------------------------------------------------
// GGUF v3 Universal Tensor Parser & Zero-Copy Memory Mapper
// ---------------------------------------------------------------------------
struct GgufTensorInfo {
    std::string          name;
    uint32_t             nDims = 0;
    std::vector<int64_t> dims;
    uint32_t             dtype  = 0;  // 0=F32, 1=F16, 12=Q4_K, 14=Q6_K
    uint64_t             offset = 0;
};

class GgufModelFile {
  public:
    void *                                          mappedData = nullptr;
    size_t                                          mappedSize = 0;
    int                                             fd         = -1;
    uint64_t                                        dataOffset = 0;
    std::string                                     architecture = "unknown";
    std::unordered_map<std::string, GgufTensorInfo> tensors;

    bool openAndMap(const std::string & path) {
        unmap();
        if (path.empty()) {
            return false;
        }

        std::string actualPath = normalizeModelPath(path);
        try {
            fd = open(actualPath.c_str(), O_RDONLY);
            if (fd < 0) {
                LOGE("Cannot open GGUF model file: %s (errno: %d)", actualPath.c_str(), errno);
                return false;
            }

            struct stat sb;
            if (fstat(fd, &sb) < 0) {
                close(fd);
                fd = -1;
                return false;
            }

            mappedSize = static_cast<size_t>(sb.st_size);
            mappedData = mmap(nullptr, mappedSize, PROT_READ, MAP_SHARED, fd, 0);
            if (mappedData == MAP_FAILED) {
                close(fd);
                fd         = -1;
                mappedData = nullptr;
                return false;
            }

            madvise(mappedData, mappedSize, MADV_RANDOM);
            return parseGgufHeader();
        } catch (const std::exception & e) {
            LOGE("openAndMap exception for %s: %s", actualPath.c_str(), e.what());
            unmap();
            return false;
        } catch (...) {
            LOGE("openAndMap unknown exception for %s", actualPath.c_str());
            unmap();
            return false;
        }
    }

    bool parseGgufHeader() {
        if (!mappedData || mappedSize < 24) {
            return false;
        }

        try {
            const uint8_t * ptr = static_cast<const uint8_t *>(mappedData);

            if (std::memcmp(ptr, "GGUF", 4) != 0) {
                LOGE("Invalid GGUF magic bytes");
                return false;
            }

            uint32_t version  = 0;
            uint64_t nTensors = 0;
            uint64_t nKv      = 0;
            std::memcpy(&version, ptr + 4, 4);
            std::memcpy(&nTensors, ptr + 8, 8);
            std::memcpy(&nKv, ptr + 16, 8);

            size_t idx = 24;

            uint32_t alignment = 32;

            // Parse metadata Key-Value pairs with strict bounds protection
            for (uint64_t i = 0; i < nKv && idx + 12 <= mappedSize; ++i) {
                uint64_t kLen = 0;
                std::memcpy(&kLen, ptr + idx, 8);
                idx += 8;

                if (kLen == 0 || kLen > 512 || idx + kLen > mappedSize) {
                    LOGW("parseGgufHeader: invalid key length %llu at idx %zu", (unsigned long long)kLen, idx);
                    break;
                }

                std::string key(reinterpret_cast<const char*>(ptr + idx), kLen);
                idx += kLen;

                if (idx + 4 > mappedSize) break;
                uint32_t vType = 0;
                std::memcpy(&vType, ptr + idx, 4);
                idx += 4;

                if (vType == 8) {  // string
                    if (idx + 8 > mappedSize) break;
                    uint64_t sLen = 0;
                    std::memcpy(&sLen, ptr + idx, 8);
                    idx += 8;
                    if (sLen > mappedSize || idx + sLen > mappedSize) break;
                    if (key == "general.architecture") {
                        architecture = std::string(reinterpret_cast<const char*>(ptr + idx), sLen);
                    }
                    idx += sLen;
                } else if (vType == 9) {  // array
                    if (idx + 12 > mappedSize) break;
                    uint32_t arrType = 0;
                    uint64_t arrLen  = 0;
                    std::memcpy(&arrType, ptr + idx, 4);
                    std::memcpy(&arrLen, ptr + idx + 4, 8);
                    idx += 12;
                    if (arrType == 8) {  // array of strings
                        for (uint64_t a = 0; a < arrLen && idx + 8 <= mappedSize; ++a) {
                            uint64_t sl = 0;
                            std::memcpy(&sl, ptr + idx, 8);
                            idx += 8;
                            if (sl > mappedSize || idx + sl > mappedSize) {
                                idx = mappedSize;
                                break;
                            }
                            idx += sl;
                        }
                    } else if (arrType == 0 || arrType == 1 || arrType == 7) { // uint8, int8, bool (1 byte)
                        if (arrLen > mappedSize || idx + arrLen > mappedSize) break;
                        idx += arrLen;
                    } else if (arrType == 2 || arrType == 3) { // uint16, int16 (2 bytes)
                        if (arrLen * 2 > mappedSize || idx + arrLen * 2 > mappedSize) break;
                        idx += arrLen * 2;
                    } else if (arrType == 4 || arrType == 5 || arrType == 6) { // uint32, int32, float32 (4 bytes)
                        if (arrLen * 4 > mappedSize || idx + arrLen * 4 > mappedSize) break;
                        idx += arrLen * 4;
                    } else if (arrType == 10 || arrType == 11 || arrType == 12) { // uint64, int64, float64 (8 bytes)
                        if (arrLen * 8 > mappedSize || idx + arrLen * 8 > mappedSize) break;
                        idx += arrLen * 8;
                    } else {
                        LOGW("parseGgufHeader: unknown array element type %u", arrType);
                        break;
                    }
                } else if (vType == 0 || vType == 1 || vType == 7) {     // bool/uint8/int8
                    if (idx + 1 > mappedSize) break;
                    idx += 1;
                } else if (vType == 2 || vType == 3) {                   // uint16/int16
                    if (idx + 2 > mappedSize) break;
                    idx += 2;
                } else if (vType == 4 || vType == 5 || vType == 6) {     // uint32/int32/float32
                    if (idx + 4 > mappedSize) break;
                    if (key == "general.alignment" && vType == 4) {
                        uint32_t alignVal = 0;
                        std::memcpy(&alignVal, ptr + idx, 4);
                        if (alignVal > 0 && (alignVal & (alignVal - 1)) == 0) {
                            alignment = alignVal;
                        }
                    }
                    idx += 4;
                } else if (vType == 10 || vType == 11 || vType == 12) {  // int64/uint64/float64
                    if (idx + 8 > mappedSize) break;
                    idx += 8;
                } else {
                    LOGW("parseGgufHeader: unknown value type %u", vType);
                    break;
                }
            }

            // Parse tensor descriptors
            for (uint64_t i = 0; i < nTensors && idx + 16 <= mappedSize; ++i) {
                uint64_t tLen = 0;
                std::memcpy(&tLen, ptr + idx, 8);
                idx += 8;

                if (tLen == 0 || tLen > 512 || idx + tLen > mappedSize) {
                    LOGW("parseGgufHeader: invalid tensor name length %llu", (unsigned long long)tLen);
                    break;
                }

                std::string tName(reinterpret_cast<const char *>(ptr + idx), tLen);
                idx += tLen;

                if (idx + 4 > mappedSize) break;
                uint32_t nDims = 0;
                std::memcpy(&nDims, ptr + idx, 4);
                idx += 4;

                if (nDims > 8 || idx + nDims * 8 + 12 > mappedSize) {
                    LOGW("parseGgufHeader: invalid tensor nDims %u", nDims);
                    break;
                }

                std::vector<int64_t> dims(nDims);
                for (uint32_t d = 0; d < nDims; ++d) {
                    uint64_t dimVal = 0;
                    std::memcpy(&dimVal, ptr + idx, 8);
                    dims[d] = static_cast<int64_t>(dimVal);
                    idx += 8;
                }

                uint32_t dtype = 0;
                std::memcpy(&dtype, ptr + idx, 4);
                idx += 4;

                uint64_t offset = 0;
                std::memcpy(&offset, ptr + idx, 8);
                idx += 8;

                GgufTensorInfo info;
                info.name   = tName;
                info.nDims  = nDims;
                info.dims   = dims;
                info.dtype  = dtype;
                info.offset = offset;

                tensors[tName] = info;
            }

            // Tensor data alignment (default 32 bytes or general.alignment)
            if (alignment == 0) alignment = 32;
            dataOffset = (idx + (alignment - 1)) & ~static_cast<size_t>(alignment - 1);
            LOGI("Parsed Universal GGUF Model (%s): %zu tensors found, dataOffset: %llu, alignment: %u",
                 architecture.c_str(), tensors.size(), (unsigned long long) dataOffset, alignment);
            return !tensors.empty();
        } catch (const std::exception & e) {
            LOGE("parseGgufHeader exception: %s", e.what());
            return false;
        } catch (...) {
            LOGE("parseGgufHeader unknown exception");
            return false;
        }
    }

    const uint8_t * getTensorData(const std::string & name) const {
        auto it = tensors.find(name);
        if (it == tensors.end()) {
            return nullptr;
        }
        size_t byteOffset = dataOffset + it->second.offset;
        if (byteOffset >= mappedSize) {
            return nullptr;
        }
        return static_cast<const uint8_t *>(mappedData) + byteOffset;
    }

    void unmap() {
        if (mappedData && mappedData != MAP_FAILED) {
            munmap(mappedData, mappedSize);
            mappedData = nullptr;
        }
        if (fd >= 0) {
            close(fd);
            fd = -1;
        }
        mappedSize = 0;
        tensors.clear();
        dataOffset = 0;
        architecture = "unknown";
    }

    ~GgufModelFile() { unmap(); }
};

// ---------------------------------------------------------------------------
// Universal Neural Diffusion Pipeline Context & Tuning
// ---------------------------------------------------------------------------
struct TuningConfig {
    int   threadCount       = 6;
    int   sampleMethod      = 0;  // 0=Euler, 1=Euler_A
    int   scheduler         = 0;  // 0=Discrete, 1=Karras, 2=Exponential, 3=AYS
    bool  dynamicCfg        = false;
    int   affinityMode      = 0;  // 0=Auto, 1=Big Cores, 2=Solo Prime Core (X4/Oryon), 3=Mid Cluster
    int   threadPriority    = 1;  // 0=Urgent (-10), 1=Default (0), 2=Background (+10)
    bool  enableOpenmp      = true;
    bool  enableTeaCache    = true;
    float teaCacheThreshold = 0.15f;
    int   teaCacheMode      = 0;  // 0=EasyCache, 1=Cache-DiT
    bool  fastModeNoCfg     = true; // Wan 2.1 fast mode (CFG 1.0 / no negative prompt)
    int   executionBackend  = 0;    // 0=AUTO, 1=CPU, 2=NPU (HTP v75), 3=HYBRID (NPU DiT + CPU VAE)
};

struct VideoGenContext {
    HardwareProfile hwProfile;
    TuningConfig    tuning;
    std::string     ditModelPath;
    std::string     textEncoderPath;
    std::string     vaePath;
    std::string     resolvedBackendStr;
    bool            isCancelled = false;
    sd_ctx_t*       activeSdCtx = nullptr;

    // Model inspection handle
    GgufModelFile   modelInspector;
};

static void applyThreadPriority(int priorityLevel) {
    int niceVal = 0;
    if (priorityLevel == 0) {
        niceVal = -16; // Turbo Workstation (highest realtime compute priority)
    } else if (priorityLevel == 2) {
        niceVal = 10;  // Background / Eco power saver
    } else {
        niceVal = 0;   // Balanced standard
    }
    setpriority(PRIO_PROCESS, 0, niceVal);
    LOGI("CPU Thread Priority applied: level=%d (nice=%d, tid=%d)", priorityLevel, niceVal, gettid());
}

// ---------------------------------------------------------------------------
// Universal Dynamic Hardware Topology Profiler (Snapdragon 8 Gen 2/3/4 Elite/5+)
// ---------------------------------------------------------------------------
struct CpuCoreInfo {
    int      id          = 0;
    uint64_t maxFreqKHz  = 0;
};

struct CpuTopology {
    int                      totalCores = 0;
    std::vector<CpuCoreInfo> cores;
    uint64_t                 maxFreq = 0;
    uint64_t                 minFreq = 0;
    std::vector<int>         primeCores;       // Absolute highest frequency cores (e.g. Core 7 X4 / Oryon)
    std::vector<int>         performanceCores; // >= 75% of max frequency
    std::vector<int>         midCores;         // Performance cores excluding prime
    std::vector<int>         efficiencyCores;  // < 75% of max frequency
};

static CpuTopology detectCpuTopology() {
    CpuTopology topo;
    int numCores = static_cast<int>(sysconf(_SC_NPROCESSORS_CONF));
    if (numCores <= 0) numCores = static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN));
    if (numCores <= 0) numCores = 8;
    topo.totalCores = numCores;

    for (int i = 0; i < numCores; ++i) {
        CpuCoreInfo info;
        info.id = i;
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        std::ifstream f(path);
        if (f.is_open()) {
            f >> info.maxFreqKHz;
            f.close();
        }
        if (info.maxFreqKHz == 0) {
            // Fallback estimation if sysfs is restricted
            info.maxFreqKHz = (i >= 2) ? 3000000 : 2000000;
            if (i == numCores - 1) info.maxFreqKHz = 3400000;
        }
        topo.cores.push_back(info);
        if (info.maxFreqKHz > topo.maxFreq) topo.maxFreq = info.maxFreqKHz;
        if (topo.minFreq == 0 || info.maxFreqKHz < topo.minFreq) topo.minFreq = info.maxFreqKHz;
    }

    uint64_t perfThreshold = static_cast<uint64_t>(topo.maxFreq * 0.75);
    for (const auto& c : topo.cores) {
        if (c.maxFreqKHz == topo.maxFreq) {
            topo.primeCores.push_back(c.id);
        } else if (c.maxFreqKHz >= perfThreshold) {
            topo.midCores.push_back(c.id);
        } else {
            topo.efficiencyCores.push_back(c.id);
        }

        if (c.maxFreqKHz >= perfThreshold) {
            topo.performanceCores.push_back(c.id);
        }
    }

    LOGI("CPU Topology Profiled: %d cores | Max: %llu kHz | Min: %llu kHz | Prime: %zu | Perf: %zu | Little: %zu",
         topo.totalCores, static_cast<unsigned long long>(topo.maxFreq),
         static_cast<unsigned long long>(topo.minFreq),
         topo.primeCores.size(), topo.performanceCores.size(), topo.efficiencyCores.size());
    return topo;
}

static void applyCpuAffinity(int affinityMode, int threadCount) {
#if defined(__ANDROID__) || defined(__linux__)
    int numCores = static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN));
    if (numCores <= 0) return;

    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);

    if (affinityMode == 0) {
        // Mode 0: Auto / OS EAS Scheduler
        for (int c = 0; c < numCores; ++c) {
            CPU_SET(c, &cpuset);
        }
        sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);
        LOGI("CPU Affinity: Mode 0 (Auto / Android OS Scheduler across all %d cores)", numCores);
        return;
    }

    static CpuTopology topo = detectCpuTopology();

    if (affinityMode == 2) {
        // Mode 2: Solo Prime Core (Cortex-X4 / Oryon Prime)
        for (int coreId : topo.primeCores) {
            CPU_SET(coreId, &cpuset);
            LOGI("CPU Affinity: Mode 2 (Solo Prime Core CPU %d @ %llu kHz)", coreId, static_cast<unsigned long long>(topo.maxFreq));
        }
    } else if (affinityMode == 3) {
        // Mode 3: Mid Cluster Only (Cortex-A720 without Prime)
        for (int coreId : topo.midCores) {
            CPU_SET(coreId, &cpuset);
        }
        LOGI("CPU Affinity: Mode 3 (Mid Cluster %zu cores)", topo.midCores.size());
    } else {
        // Mode 1: All Performance / Big Cores (e.g. 5x A720 + 1x X4)
        for (int coreId : topo.performanceCores) {
            CPU_SET(coreId, &cpuset);
        }
        LOGI("CPU Affinity: Mode 1 (All Big/Performance Cores %zu cores)", topo.performanceCores.size());
    }

    sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);
#else
    (void)affinityMode;
    (void)threadCount;
#endif
}

// ARM NEON Vector Operations
static inline float fp16ToF32(uint16_t h) {
    uint32_t sign = (static_cast<uint32_t>(h) & 0x8000u) << 16;
    uint32_t exp  = (static_cast<uint32_t>(h) & 0x7C00u) >> 10;
    uint32_t mant = (static_cast<uint32_t>(h) & 0x03FFu);
    if (exp == 0) {
        if (mant == 0) {
            uint32_t res = sign;
            float    f   = 0.0f;
            std::memcpy(&f, &res, 4);
            return f;
        }
        while (!(mant & 0x0400u)) {
            mant <<= 1;
            exp--;
        }
        exp++;
        mant &= ~0x0400u;
    } else if (exp == 31) {
        uint32_t res = sign | 0x7F800000u | (mant << 13);
        float    f   = 0.0f;
        std::memcpy(&f, &res, 4);
        return f;
    }
    exp          = exp + (127 - 15);
    mant         = mant << 13;
    uint32_t res = sign | (exp << 23) | mant;
    float    f   = 0.0f;
    std::memcpy(&f, &res, 4);
    return f;
}

static void neonSaxpy(float * y, const float * x, float a, size_t count) {
#if defined(__ARM_NEON) || defined(__aarch64__)
    size_t      i    = 0;
    float32x4_t aVec = vdupq_n_f32(a);
    for (; i + 4 <= count; i += 4) {
        float32x4_t xVec = vld1q_f32(x + i);
        float32x4_t yVec = vld1q_f32(y + i);
        yVec             = vmlaq_f32(yVec, xVec, aVec);
        vst1q_f32(y + i, yVec);
    }
    for (; i < count; ++i) {
        y[i] += a * x[i];
    }
#else
    for (size_t i = 0; i < count; ++i) {
        y[i] += a * x[i];
    }
#endif
}

static float neonNorm(const float * vec, size_t count) {
    double sum = 0.0;
#if defined(__ARM_NEON) || defined(__aarch64__)
    size_t      i      = 0;
    float32x4_t sumVec = vdupq_n_f32(0.0f);
    for (; i + 4 <= count; i += 4) {
        float32x4_t v = vld1q_f32(vec + i);
        sumVec        = vmlaq_f32(sumVec, v, v);
    }
    float temp[4];
    vst1q_f32(temp, sumVec);
    sum = temp[0] + temp[1] + temp[2] + temp[3];
    for (; i < count; ++i) {
        sum += vec[i] * vec[i];
    }
#else
    for (size_t i = 0; i < count; ++i) {
        sum += vec[i] * vec[i];
    }
#endif
    return static_cast<float>(std::sqrt(sum));
}

// Wan 2.1 3D RoPE (Rotary Position Embeddings)
static void applyRope3D(float * qk, int numTokens, int numHeads, int headDim, int F_tokens, int H_tokens, int W_tokens) {
    const int dT = 44;
    const int dH = 42;
    const int dW = 42;

    for (int i = 0; i < numTokens; ++i) {
        int t   = i / (H_tokens * W_tokens);
        int rem = i % (H_tokens * W_tokens);
        int y   = rem / W_tokens;
        int x   = rem % W_tokens;

        for (int h = 0; h < numHeads; ++h) {
            float * headPtr = qk + (i * numHeads + h) * headDim;

            for (int k = 0; k < dT; k += 2) {
                float theta    = std::pow(10000.0f, -static_cast<float>(k) / static_cast<float>(dT));
                float angle    = static_cast<float>(t) * theta;
                float cosA     = std::cos(angle);
                float sinA     = std::sin(angle);
                float x0       = headPtr[k];
                float x1       = headPtr[k + 1];
                headPtr[k]     = x0 * cosA - x1 * sinA;
                headPtr[k + 1] = x0 * sinA + x1 * cosA;
            }

            for (int k = 0; k < dH; k += 2) {
                float theta         = std::pow(10000.0f, -static_cast<float>(k) / static_cast<float>(dH));
                float angle         = static_cast<float>(y) * theta;
                float cosA          = std::cos(angle);
                float sinA          = std::sin(angle);
                float x0            = headPtr[dT + k];
                float x1            = headPtr[dT + k + 1];
                headPtr[dT + k]     = x0 * cosA - x1 * sinA;
                headPtr[dT + k + 1] = x0 * sinA + x1 * cosA;
            }

            for (int k = 0; k < dW; k += 2) {
                float theta              = std::pow(10000.0f, -static_cast<float>(k) / static_cast<float>(dW));
                float angle              = static_cast<float>(x) * theta;
                float cosA               = std::cos(angle);
                float sinA               = std::sin(angle);
                float x0                 = headPtr[dT + dH + k];
                float x1                 = headPtr[dT + dH + k + 1];
                headPtr[dT + dH + k]     = x0 * cosA - x1 * sinA;
                headPtr[dT + dH + k + 1] = x0 * sinA + x1 * cosA;
            }
        }
    }
}

static JavaVM* g_jvm = nullptr;
static std::atomic<bool> g_cancelRequested(false);
struct VideoGenContext;
static VideoGenContext* g_activeContext = nullptr;

// Progress and Logging Bridges for Native Engine
struct ProgressBridgeData {
    jobject   callbackGlobalRef = nullptr;
    jmethodID method = nullptr;
    int       phase = 1;
    int       totalSteps = 1;
    int       expectedDiffusionSteps = 1;
};

static ProgressBridgeData* g_activeBridge = nullptr;
static std::mutex g_generationMutex;

void setJavaVM(JavaVM* vm) {
    g_jvm = vm;
}

static void onSdProgress(int step, int steps, float time, void* data) {
    if (g_cancelRequested.load()) {
        LOGW("onSdProgress: Cancel requested! Signaling sd_cancel_generation.");
        if (g_activeContext && g_activeContext->activeSdCtx) {
            sd_cancel_generation(g_activeContext->activeSdCtx, SD_CANCEL_ALL);
        }
        return;
    }

    auto* bridge = static_cast<ProgressBridgeData*>(data);
    if (!bridge || !bridge->callbackGlobalRef || !bridge->method || !g_jvm) {
        LOGW("onSdProgress: bridge or g_jvm null (bridge=%p, jvm=%p)", bridge, g_jvm);
        return;
    }

    JNIEnv* threadEnv = nullptr;
    bool attached = false;
    jint getEnvRes = g_jvm->GetEnv(reinterpret_cast<void**>(&threadEnv), JNI_VERSION_1_6);
    if (getEnvRes == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&threadEnv, nullptr) == JNI_OK) {
            attached = true;
        } else {
            LOGE("onSdProgress: Failed to attach thread to JVM");
            return;
        }
    } else if (getEnvRes != JNI_OK || !threadEnv) {
        LOGE("onSdProgress: GetEnv failed (%d)", getEnvRes);
        return;
    }

    bool isDiffusionStep = (steps == bridge->expectedDiffusionSteps);
    int reportPhase = bridge->phase;
    int reportStep = step;
    int reportTotal = steps;
    std::ostringstream ss;

    if (isDiffusionStep) {
        bridge->phase = 2;
        bridge->totalSteps = steps;
        reportPhase = 2;
        reportStep = step;
        reportTotal = steps;

        LOGI("⚡ [TELEMETRY] onSdProgress: Step %d/%d (%.2fs)", step, steps, time);
        if (step == 0) {
            ss << "Fáze 2/4: Příprava difúzních tenzorů (0/" << steps << ")";
        } else {
            ss << "Fáze 2/4: Difúze - Krok " << step << "/" << steps << " (" << std::fixed << std::setprecision(1) << time << "s)";
        }
    } else {
        // Tensor loading step (e.g. 825 DiT tensors or 104 VAE tensors)
        LOGI("⚡ [TELEMETRY] Tensor loading progress: %d/%d (%.2fs)", step, steps, time);
        if (bridge->phase >= 3) {
            reportPhase = 3;
            reportStep = (step == steps) ? 1 : 0;
            reportTotal = 1;
            ss << "Fáze 3/4: Načítání VAE vah (" << step << "/" << steps << ")";
        } else {
            reportPhase = 2;
            reportStep = 0;
            reportTotal = bridge->expectedDiffusionSteps;
            ss << "Fáze 2/4: Načítání difúzních vah (" << step << "/" << steps << ")";
        }
    }

    jstring msg = threadEnv->NewStringUTF(ss.str().c_str());
    threadEnv->CallVoidMethod(bridge->callbackGlobalRef, bridge->method, reportPhase, reportStep, reportTotal, msg);
    threadEnv->DeleteLocalRef(msg);

    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

static void onSdLog(enum sd_log_level_t level, const char* text, void* data) {
    (void)data;
    if (!text) return;
    switch (level) {
        case SD_LOG_DEBUG:
        case SD_LOG_VERBOSE:
            __android_log_print(ANDROID_LOG_DEBUG, "SD_CORE", "%s", text);
            break;
        case SD_LOG_INFO:
            __android_log_print(ANDROID_LOG_INFO, "SD_CORE", "%s", text);
            break;
        case SD_LOG_WARN:
            __android_log_print(ANDROID_LOG_WARN, "SD_CORE", "%s", text);
            break;
        case SD_LOG_ERROR:
            __android_log_print(ANDROID_LOG_ERROR, "SD_CORE", "%s", text);
            break;
        default:
            break;
    }

    if (g_activeBridge && g_activeBridge->callbackGlobalRef && g_activeBridge->method && g_jvm) {
        int phase = 0;
        int step = 0;
        int total = 1;
        const char* statusMsg = nullptr;

        if (std::strstr(text, "t5 executing segment") || std::strstr(text, "loading t5xxl")) {
            phase = 1;
            step = 0;
            total = 1;
            g_activeBridge->phase = 1;
            statusMsg = "Fáze 1/4: Sémantické kódování promptu (T5-XXL)...";
        } else if (std::strstr(text, "computing condition graph completed") || std::strstr(text, "get_learned_condition completed")) {
            phase = 1;
            step = 1;
            total = 1;
            g_activeBridge->phase = 1;
            statusMsg = "Fáze 1/4: Textové embeddingy úspěšně připraveny";
        } else if (std::strstr(text, "sampling using") || std::strstr(text, "sample ") || std::strstr(text, "Wan2.1-T2V-1.3B executing segment") || std::strstr(text, "Wan2.1")) {
            if (g_activeBridge->phase < 2) {
                phase = 2;
                step = 0;
                total = g_activeBridge->totalSteps > 0 ? g_activeBridge->totalSteps : 4;
                g_activeBridge->phase = 2;
                statusMsg = "Fáze 2/4: Zahájení neuronové difúze...";
            }
        } else if (std::strstr(text, "using VAE") || std::strstr(text, "decoding") || std::strstr(text, "decode_video_outputs") || std::strstr(text, "wan_vae")) {
            if (g_activeBridge->phase < 3) {
                phase = 3;
                step = 1;
                total = 1;
                g_activeBridge->phase = 3;
                statusMsg = "Fáze 3/4: 3D-VAE Dekódování latentů do RGB snímků...";
            }
        }

        if (statusMsg && phase > 0) {
            JNIEnv* threadEnv = nullptr;
            bool attached = false;
            jint getEnvRes = g_jvm->GetEnv(reinterpret_cast<void**>(&threadEnv), JNI_VERSION_1_6);
            if (getEnvRes == JNI_EDETACHED) {
                if (g_jvm->AttachCurrentThread(&threadEnv, nullptr) == JNI_OK) {
                    attached = true;
                }
            }
            if (threadEnv) {
                LOGI("⚡ [TELEMETRY LOG] Transition: phase=%d step=%d/%d: %s", phase, step, total, statusMsg);
                jstring msg = threadEnv->NewStringUTF(statusMsg);
                threadEnv->CallVoidMethod(g_activeBridge->callbackGlobalRef, g_activeBridge->method, phase, step, total, msg);
                threadEnv->DeleteLocalRef(msg);
                if (attached) {
                    g_jvm->DetachCurrentThread();
                }
            }
        }
    }
}

}  // namespace videogen

// ---------------------------------------------------------------------------
// JNI Exports
// ---------------------------------------------------------------------------
extern "C" {

JNIEXPORT jstring JNICALL
Java_com_example_llama_agent_ui_studio_genstudio_engine_VideoGenEngine_nativeDetectHardware(JNIEnv * env,
                                                                                            jobject /* this */) {
    auto               profile = videogen::detectSystemHardware();
    std::ostringstream ss;
    ss << "{\"chipset\":\"" << profile.chipsetName << "\","
       << "\"totalRamMb\":" << profile.totalRamMb << ","
       << "\"recommendedWidth\":" << profile.recommendedWidth << ","
       << "\"recommendedHeight\":" << profile.recommendedHeight << ","
       << "\"recommendedFrames\":" << profile.recommendedFrames << ","
       << "\"recommendedSteps\":" << profile.recommendedSteps << ","
       << "\"threads\":" << profile.threadCount << ","
       << "\"teaCache\":" << (profile.enableTeaCache ? "true" : "false") << ","
       << "\"cooldownMs\":" << profile.thermalCooldownMs << "}";
    return env->NewStringUTF(ss.str().c_str());
}

extern "C" {
    void ggml_backend_load_all_from_path(const char * dir_path);
}

JNIEXPORT jlong JNICALL
Java_com_example_llama_agent_ui_studio_genstudio_engine_VideoGenEngine_nativeInitPipeline(JNIEnv * env,
                                                                                          jobject /* this */,
                                                                                          jstring ditPath,
                                                                                          jstring textEncPath,
                                                                                          jstring vaePath,
                                                                                          jstring nativeLibDir) {
    if (!videogen::g_jvm) {
        env->GetJavaVM(&videogen::g_jvm);
    }
    auto * ctx     = new videogen::VideoGenContext();
    ctx->hwProfile = videogen::detectSystemHardware();

    if (nativeLibDir != nullptr) {
        const char * cLibDir = env->GetStringUTFChars(nativeLibDir, nullptr);
        if (cLibDir && std::strlen(cLibDir) > 0) {
            // Set FastRPC path for DSP skeleton (libggml-htp-v75.so)
            setenv("ADSP_LIBRARY_PATH", cLibDir, 1);
            // Set GGML dynamic backend loader path for CPU backend library (libggml-hexagon.so)
            setenv("GGML_BACKEND_PATH", cLibDir, 1);
            LOGI("🎯 Configured FastRPC ADSP_LIBRARY_PATH & GGML_BACKEND_PATH: %s", cLibDir);

            // Dynamic scan for backends if libggml-hexagon.so is present
            // Parse individual paths if semicolon separated
            std::string paths(cLibDir);
            std::stringstream ss(paths);
            std::string singlePath;
            while (std::getline(ss, singlePath, ';')) {
                if (!singlePath.empty()) {
                    ggml_backend_load_all_from_path(singlePath.c_str());
                }
            }
        }
        env->ReleaseStringUTFChars(nativeLibDir, cLibDir);
    }

    if (ditPath != nullptr) {
        const char * cDit = env->GetStringUTFChars(ditPath, nullptr);
        ctx->ditModelPath = videogen::normalizeModelPath(std::string(cDit));
        env->ReleaseStringUTFChars(ditPath, cDit);
    }
    if (textEncPath != nullptr) {
        const char * cText   = env->GetStringUTFChars(textEncPath, nullptr);
        ctx->textEncoderPath = videogen::normalizeModelPath(std::string(cText));
        env->ReleaseStringUTFChars(textEncPath, cText);
    }
    if (vaePath != nullptr) {
        const char * cVae = env->GetStringUTFChars(vaePath, nullptr);
        ctx->vaePath      = videogen::normalizeModelPath(std::string(cVae));
        env->ReleaseStringUTFChars(vaePath, cVae);
    }

    // Inspect model via GGUF parser
    if (!ctx->ditModelPath.empty()) {
        ctx->modelInspector.openAndMap(ctx->ditModelPath);
    }

    sd_set_log_callback(videogen::onSdLog, nullptr);

    LOGI("Universal Neural Engine Initialized | Model: %s | Arch: %s | TextEnc: %s | VAE: %s",
         ctx->ditModelPath.c_str(), ctx->modelInspector.architecture.c_str(),
         ctx->textEncoderPath.c_str(), ctx->vaePath.c_str());
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_example_llama_agent_ui_studio_genstudio_engine_VideoGenEngine_nativeExecuteGeneration(
    JNIEnv * env,
    jobject /* this */,
    jlong        nativeHandle,
    jstring      prompt,
    jstring      negativePrompt,
    jint         width,
    jint         height,
    jint         frames,
    jint         steps,
    jfloat       guidance,
    jlong        seed,
    jboolean     enableTeaCache,
    jobjectArray outBitmaps,
    jobject      progressCallback) {

    if (!videogen::g_jvm) {
        env->GetJavaVM(&videogen::g_jvm);
    }

    std::unique_lock<std::mutex> genLock(videogen::g_generationMutex, std::try_to_lock);
    if (!genLock.owns_lock()) {
        LOGE("❌ nativeExecuteGeneration: Another generation is already running!");
        return JNI_FALSE;
    }

    auto * ctx = reinterpret_cast<videogen::VideoGenContext *>(nativeHandle);
    if (!ctx) {
        LOGE("Invalid native context handle");
        return JNI_FALSE;
    }

    jclass    cbClass          = progressCallback ? env->GetObjectClass(progressCallback) : nullptr;
    jmethodID onProgressMethod = cbClass ? env->GetMethodID(cbClass, "onProgress", "(IIILjava/lang/String;)V") : nullptr;

    const char * promptStr  = env->GetStringUTFChars(prompt, nullptr);
    std::string  promptText = promptStr ? promptStr : "";
    if (promptStr) env->ReleaseStringUTFChars(prompt, promptStr);

    const char * negPromptStr = negativePrompt ? env->GetStringUTFChars(negativePrompt, nullptr) : nullptr;
    std::string  negPromptText = negPromptStr ? negPromptStr : "";
    if (negPromptStr) env->ReleaseStringUTFChars(negativePrompt, negPromptStr);

    // Defenzivní 16-pixelové zarovnání pro 3D DiT a VAE architektury (Wan 2.1, Flux, SD3)
    if (width % 16 != 0) {
        int alignedW = ((width + 15) / 16) * 16;
        LOGW("⚠️ Šířka %d není dělitelná 16 pro Wan 2.1 DiT! Automaticky zarovnávám na %d", width, alignedW);
        width = alignedW;
    }
    if (height % 16 != 0) {
        int alignedH = ((height + 15) / 16) * 16;
        LOGW("⚠️ Výška %d není dělitelná 16 pro Wan 2.1 DiT! Automaticky zarovnávám na %d", height, alignedH);
        height = alignedH;
    }

    bool isVideo = (frames > 1);
    LOGI("🚀 Starting Universal Neural Synthesis [%s] | Model: '%s' | Prompt: '%s' | NegPrompt: '%s' | Steps: %d | Frames: %d (%dx%d)",
         isVideo ? "VIDEO" : "PHOTO", ctx->ditModelPath.c_str(), promptText.c_str(), negPromptText.c_str(), steps, frames, width, height);

    videogen::g_cancelRequested.store(false);
    ctx->isCancelled = false;
    videogen::applyThreadPriority(ctx->tuning.threadPriority);
    videogen::applyCpuAffinity(ctx->tuning.affinityMode, ctx->tuning.threadCount);
    if (ctx->tuning.enableOpenmp) {
        setOpenmpThreads(ctx->tuning.threadCount);
    } else {
        setOpenmpThreads(1);
    }

    // -----------------------------------------------------------------------
    // TELEMETRY BRIDGE: Attach JNI Callback Early for Phase 1 & 2
    // -----------------------------------------------------------------------
    videogen::ProgressBridgeData bridgeData;
    bridgeData.callbackGlobalRef = progressCallback ? env->NewGlobalRef(progressCallback) : nullptr;
    bridgeData.method = onProgressMethod;
    bridgeData.phase = 1;
    bridgeData.totalSteps = steps;
    bridgeData.expectedDiffusionSteps = steps;

    if (bridgeData.callbackGlobalRef) {
        videogen::g_activeBridge = &bridgeData;
        sd_set_progress_callback(videogen::onSdProgress, &bridgeData);
        sd_set_log_callback(videogen::onSdLog, &bridgeData);
    } else {
        videogen::g_activeBridge = nullptr;
        sd_set_progress_callback(nullptr, nullptr);
        sd_set_log_callback(nullptr, nullptr);
    }

    if (onProgressMethod && progressCallback) {
        jstring msg = env->NewStringUTF("Fáze 1/4: Načítání vah a sémantické kódování promptu (T5-XXL)...");
        env->CallVoidMethod(progressCallback, onProgressMethod, 1, 0, 1, msg);
        env->DeleteLocalRef(msg);
    }

    // Prime OpenMP thread pool: apply CPU core affinity and scheduling priority to ALL worker threads
#if defined(_OPENMP)
    if (ctx->tuning.enableOpenmp) {
        omp_set_num_threads(ctx->tuning.threadCount);
        #pragma omp parallel
        {
            videogen::applyThreadPriority(ctx->tuning.threadPriority);
            videogen::applyCpuAffinity(ctx->tuning.affinityMode, ctx->tuning.threadCount);
        }
    } else {
        videogen::applyThreadPriority(ctx->tuning.threadPriority);
        videogen::applyCpuAffinity(ctx->tuning.affinityMode, ctx->tuning.threadCount);
    }
#else
    videogen::applyThreadPriority(ctx->tuning.threadPriority);
    videogen::applyCpuAffinity(ctx->tuning.affinityMode, ctx->tuning.threadCount);
#endif

    sd_ctx_params_t ctx_params;
    sd_ctx_params_init(&ctx_params);
    ctx_params.n_threads = ctx->tuning.threadCount;
    ctx_params.enable_mmap = true;
    ctx_params.flash_attn = true;
    ctx_params.diffusion_flash_attn = true;

    // Hardware Execution Backend Routing (Snapdragon 8 Gen 3 HTP / CPU)
    switch (ctx->tuning.executionBackend) {
        case 1: // CPU (ARM NEON)
            ctx->resolvedBackendStr = "CPU";
            ctx_params.backend = ctx->resolvedBackendStr.c_str();
            ctx_params.auto_fit = false;
            break;
        case 2: // NPU (Qualcomm Hexagon Tensor Processor HTP v75)
            ctx->resolvedBackendStr = "diffusion=HTP0,te=HTP0,vae=HTP0";
            ctx_params.backend = ctx->resolvedBackendStr.c_str();
            ctx_params.auto_fit = false;
            break;
        case 3: // HYBRID (DiT on Hexagon HTP, T5-XXL / VAE on CPU)
            ctx->resolvedBackendStr = "diffusion=HTP0,te=CPU,vae=CPU";
            ctx_params.backend = ctx->resolvedBackendStr.c_str();
            ctx_params.auto_fit = false;
            break;
        case 0: // AUTO
        default:
            ctx_params.backend = nullptr;
            ctx_params.auto_fit = true;
            break;
    }

    LOGI("🎯 Native Execution Backend configured: mode=%d (%s, auto_fit=%s)",
         ctx->tuning.executionBackend,
         ctx_params.backend ? ctx_params.backend : "AUTO",
         ctx_params.auto_fit ? "true" : "false");

    // Detect architecture for multi-file vs single-file routing
    std::string archLower = ctx->modelInspector.architecture;
    std::transform(archLower.begin(), archLower.end(), archLower.begin(), ::tolower);
    std::string pathLower = ctx->ditModelPath;
    std::transform(pathLower.begin(), pathLower.end(), pathLower.begin(), ::tolower);

    bool isWan = (archLower.find("wan") != std::string::npos || pathLower.find("wan") != std::string::npos);
    bool isFlux = (archLower.find("flux") != std::string::npos || pathLower.find("flux") != std::string::npos);
    bool isSd3 = (archLower.find("sd3") != std::string::npos || pathLower.find("sd3") != std::string::npos);
    bool isLtx = (archLower.find("ltx") != std::string::npos || pathLower.find("ltx") != std::string::npos);
    bool isQwen = (archLower.find("qwen") != std::string::npos || pathLower.find("qwen") != std::string::npos);

    if (isWan) {
        ctx_params.diffusion_model_path = ctx->ditModelPath.c_str();
        ctx_params.vae_path = ctx->vaePath.empty() ? nullptr : ctx->vaePath.c_str();
        ctx_params.t5xxl_path = ctx->textEncoderPath.empty() ? nullptr : ctx->textEncoderPath.c_str();
        ctx_params.vae_format = SD_VAE_FORMAT_WAN;
    } else if (isFlux) {
        ctx_params.diffusion_model_path = ctx->ditModelPath.c_str();
        ctx_params.vae_path = ctx->vaePath.empty() ? nullptr : ctx->vaePath.c_str();
        ctx_params.t5xxl_path = ctx->textEncoderPath.empty() ? nullptr : ctx->textEncoderPath.c_str();
        ctx_params.clip_l_path = ctx->textEncoderPath.empty() ? nullptr : ctx->textEncoderPath.c_str();
        ctx_params.vae_format = SD_VAE_FORMAT_FLUX;
    } else if (isSd3) {
        ctx_params.diffusion_model_path = ctx->ditModelPath.c_str();
        ctx_params.vae_path = ctx->vaePath.empty() ? nullptr : ctx->vaePath.c_str();
        ctx_params.t5xxl_path = ctx->textEncoderPath.empty() ? nullptr : ctx->textEncoderPath.c_str();
        ctx_params.clip_l_path = ctx->textEncoderPath.empty() ? nullptr : ctx->textEncoderPath.c_str();
        ctx_params.vae_format = SD_VAE_FORMAT_SD3;
    } else if (isLtx) {
        ctx_params.diffusion_model_path = ctx->ditModelPath.c_str();
        ctx_params.vae_path = ctx->vaePath.empty() ? nullptr : ctx->vaePath.c_str();
        ctx_params.t5xxl_path = ctx->textEncoderPath.empty() ? nullptr : ctx->textEncoderPath.c_str();
    } else if (isQwen) {
        ctx_params.diffusion_model_path = ctx->ditModelPath.c_str();
        ctx_params.vae_path = ctx->vaePath.empty() ? nullptr : ctx->vaePath.c_str();
        ctx_params.llm_path = ctx->textEncoderPath.empty() ? nullptr : ctx->textEncoderPath.c_str();
    } else {
        // Monolithic or single-file models (SD 1.5, SDXL, SD-Turbo, AnimateDiff)
        ctx_params.model_path = ctx->ditModelPath.c_str();
        if (!ctx->vaePath.empty()) {
            ctx_params.vae_path = ctx->vaePath.c_str();
        }
        if (!ctx->textEncoderPath.empty()) {
            ctx_params.clip_l_path = ctx->textEncoderPath.c_str();
        }
    }

    auto t0 = std::chrono::high_resolution_clock::now();
    sd_ctx_t* sd_ctx = new_sd_ctx(&ctx_params);
    if (!sd_ctx) {
        LOGE("Failed to create neural stable-diffusion context for: %s (arch: %s)",
             ctx->ditModelPath.c_str(), ctx->modelInspector.architecture.c_str());
        sd_set_progress_callback(nullptr, nullptr);
        sd_set_log_callback(nullptr, nullptr);
        videogen::g_activeBridge = nullptr;
        videogen::g_activeContext = nullptr;
        if (bridgeData.callbackGlobalRef) {
            env->DeleteGlobalRef(bridgeData.callbackGlobalRef);
            bridgeData.callbackGlobalRef = nullptr;
        }
        return JNI_FALSE;
    }
    ctx->activeSdCtx = sd_ctx;
    sd_cancel_generation(sd_ctx, SD_CANCEL_RESET);

    auto t1 = std::chrono::high_resolution_clock::now();
    auto loadTimeMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    LOGI("✅ Neural Model Context ready in %lld ms (%s)", static_cast<long long>(loadTimeMs), sd_get_model_version_name(sd_ctx));

    // -----------------------------------------------------------------------
    // EXECUTION: Authentic Neural Pipeline (Phase 1 T5 -> Phase 2 Diffusion -> Phase 3 VAE)
    // -----------------------------------------------------------------------
    bridgeData.phase = 1;
    bridgeData.totalSteps = steps;

    sd_image_t* outputFrames = nullptr;
    int totalOutputFrames = 0;
    sd_audio_t* outputAudio = nullptr;
    int effectiveFps = 16;
    bool genSuccess = false;

    if (isWan || isVideo) {
        // === VIDEO / WAN 3D-DIT GENERATION BRANCH (Wan 2.1 / SVD / AnimateDiff) ===
        sd_vid_gen_params_t vid_params;
        sd_vid_gen_params_init(&vid_params);
        vid_params.prompt = promptText.c_str();

        bool bypassCfg = (guidance <= 1.0f) || ctx->tuning.fastModeNoCfg;
        if (bypassCfg) {
            vid_params.negative_prompt = "";
            vid_params.sample_params.guidance.txt_cfg = 1.0f;
            LOGI("🚀 Wan Fast Mode: CFG Bypassed (txt_cfg=1.0, single forward pass)");
        } else {
            vid_params.negative_prompt = negPromptText.c_str();
            vid_params.sample_params.guidance.txt_cfg = (guidance > 0.0f) ? guidance : 6.0f;
            LOGI("🎯 Wan Guided Mode: CFG Active (txt_cfg=%.2f, negative_prompt='%s')",
                 vid_params.sample_params.guidance.txt_cfg, vid_params.negative_prompt);
        }

        vid_params.width = width;
        vid_params.height = height;
        vid_params.video_frames = isVideo ? frames : 1;
        vid_params.sample_params.sample_method = static_cast<sample_method_t>(ctx->tuning.sampleMethod);
        vid_params.sample_params.scheduler = static_cast<scheduler_t>(ctx->tuning.scheduler);
        vid_params.sample_params.sample_steps = steps;
        if (ctx->tuning.dynamicCfg) {
            vid_params.sample_params.guidance.slg.scale = 1.5f;
            vid_params.sample_params.guidance.slg.layer_start = 0.2f;
            vid_params.sample_params.guidance.slg.layer_end = 0.8f;
        }
        vid_params.sample_params.flow_shift = 3.0f;
        vid_params.seed = (seed <= 0) ? -1 : seed;

        if (ctx->tuning.enableTeaCache && steps >= 8) {
            auto cacheMode = (ctx->tuning.teaCacheMode == 2) ? SD_CACHE_CACHE_DIT : SD_CACHE_EASYCACHE;
            vid_params.cache.mode = cacheMode;
            // Bezpečný práh: maximálně 0.15 pro zachování fidelity difúze a zabránění přeskočení celého modelu
            float safeThreshold = std::min(ctx->tuning.teaCacheThreshold, 0.15f);
            vid_params.cache.reuse_threshold = safeThreshold;
            vid_params.cache.start_percent = 0.18f; // Prvních 18% kroků nikdy nepřeskakovat (struktura kompozice)
            vid_params.cache.end_percent = 0.85f;   // Posledních 15% kroků nikdy nepřeskakovat (ostrost a detaily)
            LOGI("⚡ TeaCache Active: mode=%d threshold=%.3f (guaranteed steps 0..18%% and 85..100%%)",
                 static_cast<int>(cacheMode), safeThreshold);
        } else {
            vid_params.cache.mode = SD_CACHE_DISABLED;
            LOGI("TeaCache Disabled (Full fidelity steps)");
        }

        genSuccess = generate_video(sd_ctx, &vid_params, &outputFrames, &totalOutputFrames, &outputAudio, &effectiveFps);
    } else {
        // === PHOTO GENERATION BRANCH (SD 1.5 / SDXL / Flux / SD3) ===
        sd_img_gen_params_t img_params;
        sd_img_gen_params_init(&img_params);
        img_params.prompt = promptText.c_str();

        bool bypassCfg = (guidance <= 1.0f) || ctx->tuning.fastModeNoCfg;
        if (bypassCfg) {
            img_params.negative_prompt = "";
            img_params.sample_params.guidance.txt_cfg = 1.0f;
            LOGI("🚀 Photo Fast Mode: CFG Bypassed (txt_cfg=1.0)");
        } else {
            img_params.negative_prompt = negPromptText.c_str();
            img_params.sample_params.guidance.txt_cfg = (guidance > 0.0f) ? guidance : 7.0f;
            LOGI("🎯 Photo Guided Mode: CFG Active (txt_cfg=%.2f, negative_prompt='%s')",
                 img_params.sample_params.guidance.txt_cfg, img_params.negative_prompt);
        }

        img_params.width = width;
        img_params.height = height;
        img_params.batch_count = 1;
        img_params.sample_params.sample_method = static_cast<sample_method_t>(ctx->tuning.sampleMethod);
        img_params.sample_params.scheduler = static_cast<scheduler_t>(ctx->tuning.scheduler);
        img_params.sample_params.sample_steps = steps;
        if (ctx->tuning.dynamicCfg) {
            img_params.sample_params.guidance.slg.scale = 1.5f;
            img_params.sample_params.guidance.slg.layer_start = 0.2f;
            img_params.sample_params.guidance.slg.layer_end = 0.8f;
        }
        img_params.seed = (seed <= 0) ? -1 : seed;

        if (ctx->tuning.enableTeaCache && steps >= 8) {
            auto cacheMode = (ctx->tuning.teaCacheMode == 2) ? SD_CACHE_CACHE_DIT : SD_CACHE_EASYCACHE;
            img_params.cache.mode = cacheMode;
            float safeThreshold = std::min(ctx->tuning.teaCacheThreshold, 0.15f);
            img_params.cache.reuse_threshold = safeThreshold;
            img_params.cache.start_percent = 0.18f;
            img_params.cache.end_percent = 0.85f;
            LOGI("⚡ TeaCache Photo Active: mode=%d threshold=%.3f", static_cast<int>(cacheMode), safeThreshold);
        } else {
            img_params.cache.mode = SD_CACHE_DISABLED;
        }

        genSuccess = generate_image(sd_ctx, &img_params, &outputFrames, &totalOutputFrames);
    }

    if (!genSuccess || !outputFrames || totalOutputFrames <= 0) {
        bool wasCancelled = videogen::g_cancelRequested.load() || ctx->isCancelled;
        if (wasCancelled) {
            LOGI("⏹️ Nativní generování bylo bezpečně zastaveno na žádost uživatele.");
        } else {
            LOGE("Neural generation returned false or empty frames");
        }
        sd_set_progress_callback(nullptr, nullptr);
        sd_set_log_callback(nullptr, nullptr);
        videogen::g_activeBridge = nullptr;
        videogen::g_activeContext = nullptr;
        if (bridgeData.callbackGlobalRef) {
            env->DeleteGlobalRef(bridgeData.callbackGlobalRef);
            bridgeData.callbackGlobalRef = nullptr;
        }
        if (cbClass) {
            env->DeleteLocalRef(cbClass);
        }
        if (outputFrames) free(outputFrames);
        if (outputAudio) free(outputAudio);
        free_sd_ctx(sd_ctx);
        ctx->activeSdCtx = nullptr;
        perform_malloc_trim();
        return JNI_FALSE;
    }

    LOGI("✅ Neural generation complete: %d frame(s) rendered (%dx%d)",
         totalOutputFrames, outputFrames[0].width, outputFrames[0].height);

    // -----------------------------------------------------------------------
    // PHASE 3: Transfer Authentic Neural Frames to Android Bitmaps
    // -----------------------------------------------------------------------
    if (onProgressMethod && progressCallback) {
        jstring msg = env->NewStringUTF("Phase 3/4: Surface Memory Frame Transfer (Zero-Copy)");
        env->CallVoidMethod(progressCallback, onProgressMethod, 3, 1, 1, msg);
        env->DeleteLocalRef(msg);
    }

    int numBitmaps = env->GetArrayLength(outBitmaps);
    int framesToCopy = std::min(numBitmaps, totalOutputFrames);

    for (int i = 0; i < framesToCopy; ++i) {
        jobject bitmapObj = env->GetObjectArrayElement(outBitmaps, i);
        if (!bitmapObj) continue;

        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, bitmapObj, &info) < 0) {
            env->DeleteLocalRef(bitmapObj);
            continue;
        }

        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmapObj, &pixels) < 0 || !pixels) {
            env->DeleteLocalRef(bitmapObj);
            continue;
        }

        uint32_t* dst = static_cast<uint32_t*>(pixels);
        const uint8_t* src = outputFrames[i].data;
        int frameW = static_cast<int>(outputFrames[i].width);
        int frameH = static_cast<int>(outputFrames[i].height);
        int channels = static_cast<int>(outputFrames[i].channel);

        int copyW = std::min(static_cast<int>(info.width), frameW);
        int copyH = std::min(static_cast<int>(info.height), frameH);

        for (int y = 0; y < copyH; ++y) {
            uint8_t* dstRow = static_cast<uint8_t*>(pixels) + y * info.stride;
            const uint8_t* srcRow = src + y * frameW * channels;
            for (int x = 0; x < copyW; ++x) {
                uint8_t r = srcRow[x * channels + 0];
                uint8_t g = (channels > 1) ? srcRow[x * channels + 1] : r;
                uint8_t b = (channels > 2) ? srcRow[x * channels + 2] : r;
                uint8_t a = (channels > 3) ? srcRow[x * channels + 3] : 0xFF;

                dstRow[x * 4 + 0] = r;
                dstRow[x * 4 + 1] = g;
                dstRow[x * 4 + 2] = b;
                dstRow[x * 4 + 3] = a;
            }
        }

        AndroidBitmap_unlockPixels(env, bitmapObj);
        env->DeleteLocalRef(bitmapObj);
    }

    // Clean up stable-diffusion allocations
    sd_set_progress_callback(nullptr, nullptr);
    sd_set_log_callback(nullptr, nullptr);
    videogen::g_activeBridge = nullptr;
    videogen::g_activeContext = nullptr;
    if (bridgeData.callbackGlobalRef) {
        env->DeleteGlobalRef(bridgeData.callbackGlobalRef);
        bridgeData.callbackGlobalRef = nullptr;
    }
    if (cbClass) {
        env->DeleteLocalRef(cbClass);
    }
    free(outputFrames);
    if (outputAudio) {
        free(outputAudio);
    }
    free_sd_ctx(sd_ctx);
    ctx->activeSdCtx = nullptr;

    perform_malloc_trim();

    LOGI("🎉 %d authentic neural frame(s) successfully written to Android Bitmaps! (RAM trimmed)", framesToCopy);
    return JNI_TRUE;
}

extern "C" {
    void omp_set_num_threads(int num_threads);
    int omp_get_max_threads(void);
}

JNIEXPORT void JNICALL
Java_com_example_llama_agent_ui_studio_genstudio_engine_VideoGenEngine_nativeConfigureTuning(
    JNIEnv * /* env */,
    jobject /* this */,
    jlong nativeHandle,
    jint threads,
    jint sampleMethod,
    jint scheduler,
    jboolean dynamicCfg,
    jint affinityMode,
    jint threadPriority,
    jboolean enableOpenmp,
    jboolean enableTeaCache,
    jfloat teaCacheThreshold,
    jint teaCacheMode,
    jboolean fastModeNoCfg,
    jint executionBackend) {
    auto * ctx = reinterpret_cast<videogen::VideoGenContext *>(nativeHandle);
    if (ctx) {
        if (threads >= 1 && threads <= 8) {
            ctx->tuning.threadCount = threads;
            ctx->hwProfile.threadCount = threads;
        }
        ctx->tuning.sampleMethod = sampleMethod;
        ctx->tuning.scheduler = scheduler;
        ctx->tuning.dynamicCfg = dynamicCfg;
        ctx->tuning.affinityMode = affinityMode;
        ctx->tuning.threadPriority = threadPriority;
        ctx->tuning.enableOpenmp = enableOpenmp;
        ctx->tuning.enableTeaCache = enableTeaCache;
        ctx->tuning.teaCacheThreshold = teaCacheThreshold;
        ctx->tuning.teaCacheMode = teaCacheMode;
        ctx->tuning.fastModeNoCfg = fastModeNoCfg;
        ctx->tuning.executionBackend = executionBackend;

        videogen::applyThreadPriority(threadPriority);
        videogen::applyCpuAffinity(affinityMode, ctx->tuning.threadCount);

        if (enableOpenmp) {
            omp_set_num_threads(ctx->tuning.threadCount);
        } else {
            omp_set_num_threads(1);
        }

        LOGI("Configured tuning: threads=%d, sampleMethod=%d, scheduler=%d, dynamicCfg=%d, affinityMode=%d, priority=%d, openmp=%d, teaCache=%d(thresh=%.2f, mode=%d), fastModeNoCfg=%d, backend=%d",
             ctx->tuning.threadCount, sampleMethod, scheduler, dynamicCfg, affinityMode, threadPriority,
             enableOpenmp, enableTeaCache, teaCacheThreshold, teaCacheMode, fastModeNoCfg, executionBackend);
    }
}

JNIEXPORT void JNICALL
Java_com_example_llama_agent_ui_studio_genstudio_engine_VideoGenEngine_nativeCancel(JNIEnv * /* env */,
                                                                                    jobject /* this */,
                                                                                    jlong nativeHandle) {
    videogen::g_cancelRequested.store(true);
    auto * ctx = reinterpret_cast<videogen::VideoGenContext *>(nativeHandle);
    if (ctx) {
        ctx->isCancelled = true;
        if (ctx->activeSdCtx) {
            sd_cancel_generation(ctx->activeSdCtx, SD_CANCEL_ALL);
        }
    }
    if (videogen::g_activeContext && videogen::g_activeContext->activeSdCtx) {
        sd_cancel_generation(videogen::g_activeContext->activeSdCtx, SD_CANCEL_ALL);
    }
    LOGI("Native VideoGen cancellation requested.");
}

JNIEXPORT jboolean JNICALL
Java_com_example_llama_agent_ui_studio_genstudio_engine_VideoGenEngine_nativeIsCancelled(JNIEnv * /* env */,
                                                                                        jobject /* this */) {
    return static_cast<jboolean>(videogen::g_cancelRequested.load());
}

JNIEXPORT void JNICALL
Java_com_example_llama_agent_ui_studio_genstudio_engine_VideoGenEngine_nativeDestroyPipeline(JNIEnv * /* env */,
                                                                                             jobject /* this */,
                                                                                             jlong nativeHandle) {
    std::lock_guard<std::mutex> lock(videogen::g_generationMutex);
    auto * ctx = reinterpret_cast<videogen::VideoGenContext *>(nativeHandle);
    if (ctx) {
        if (ctx->activeSdCtx) {
            sd_cancel_generation(ctx->activeSdCtx, SD_CANCEL_ALL);
            free_sd_ctx(ctx->activeSdCtx);
            ctx->activeSdCtx = nullptr;
        }
        ctx->modelInspector.unmap();
        delete ctx;
        perform_malloc_trim();
        LOGI("Native Universal Pipeline destroyed & RAM trimmed.");
    }
}

JNIEXPORT void JNICALL
Java_com_example_llama_agent_ui_studio_genstudio_engine_VideoGenEngine_nativeRelease(JNIEnv * env,
                                                                                     jobject  thiz,
                                                                                     jlong    nativeHandle) {
    Java_com_example_llama_agent_ui_studio_genstudio_engine_VideoGenEngine_nativeDestroyPipeline(env, thiz,
                                                                                                 nativeHandle);
}

}  // extern "C"
