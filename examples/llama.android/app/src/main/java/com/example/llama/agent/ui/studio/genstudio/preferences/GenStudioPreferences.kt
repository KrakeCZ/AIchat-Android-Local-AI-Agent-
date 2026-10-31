package com.example.llama.agent.ui.studio.genstudio.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Správce perzistence relace pro GEN_Studio.
 * Zajišťuje, že uživatel nepřijde o svůj prompt, nastavení seedu, kroků difúze,
 * rozlišení ani konfiguraci hardwaru při opuštění aplikace nebo restartu systému.
 */
class GenStudioPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "gen_studio_session_prefs"

        private const val KEY_PROMPT = "prompt"
        private const val KEY_NEGATIVE_PROMPT = "negative_prompt"
        private const val KEY_SEED = "seed"
        private const val KEY_FRAME_COUNT = "frame_count"
        private const val KEY_STEPS = "steps"
        private const val KEY_GUIDANCE_SCALE = "guidance_scale"
        private const val KEY_WIDTH = "selected_width"
        private const val KEY_HEIGHT = "selected_height"
        private const val KEY_AUTO_60FPS = "auto_interpolate_60fps"
        private const val KEY_PHOTO_MODE = "is_photo_mode"
        private const val KEY_SHOW_TELEMETRY = "show_telemetry"
        private const val KEY_THREAD_COUNT = "thread_count"
        private const val KEY_ENABLE_AFFINITY = "enable_cpu_affinity"
        private const val KEY_AFFINITY_MODE = "affinity_mode"
        private const val KEY_THREAD_PRIORITY = "thread_priority"
        private const val KEY_SAMPLE_METHOD = "sample_method"
        private const val KEY_SCHEDULER = "scheduler"
        private const val KEY_DYNAMIC_CFG = "dynamic_cfg_thresholding"
        private const val KEY_ENABLE_OPENMP = "enable_openmp"
        private const val KEY_ENABLE_TEACACHE = "enable_teacache"
        private const val KEY_TEACACHE_THRESHOLD = "teacache_threshold"
        private const val KEY_TEACACHE_MODE = "teacache_mode"
        private const val KEY_FAST_MODE_NO_CFG = "fast_mode_no_cfg"
        private const val KEY_POWER_PROFILE = "power_profile_mode"
        private const val KEY_EXECUTION_BACKEND = "execution_backend"

        const val PROFILE_ECO = 0
        const val PROFILE_BALANCED = 1
        const val PROFILE_TURBO = 2

        const val BACKEND_AUTO    = 0
        const val BACKEND_CPU     = 1
        const val BACKEND_NPU     = 2
        const val BACKEND_HYBRID  = 3
        const val BACKEND_GPU     = 4  // Qualcomm Adreno OpenCL
    }

    var executionBackend: Int
        get() = prefs.getInt(KEY_EXECUTION_BACKEND, BACKEND_AUTO)
        set(value) = prefs.edit().putInt(KEY_EXECUTION_BACKEND, value).apply()

    var powerProfile: Int
        get() = prefs.getInt(KEY_POWER_PROFILE, PROFILE_TURBO)
        set(value) = prefs.edit().putInt(KEY_POWER_PROFILE, value).apply()

    var prompt: String
        get() = prefs.getString(KEY_PROMPT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROMPT, value).apply()

    var negativePrompt: String
        get() = prefs.getString(KEY_NEGATIVE_PROMPT, "blurry, low quality, distorted, watermark, bad anatomy, deformed")
            ?: "blurry, low quality, distorted, watermark, bad anatomy, deformed"
        set(value) = prefs.edit().putString(KEY_NEGATIVE_PROMPT, value).apply()

    var seed: String
        get() = prefs.getString(KEY_SEED, "-1") ?: "-1"
        set(value) = prefs.edit().putString(KEY_SEED, value).apply()

    var frameCount: Float
        get() = prefs.getFloat(KEY_FRAME_COUNT, 17f)
        set(value) = prefs.edit().putFloat(KEY_FRAME_COUNT, value).apply()

    var steps: Float
        get() = prefs.getFloat(KEY_STEPS, 16f)
        set(value) = prefs.edit().putFloat(KEY_STEPS, value).apply()

    var guidanceScale: Float
        get() = prefs.getFloat(KEY_GUIDANCE_SCALE, 5.0f)
        set(value) = prefs.edit().putFloat(KEY_GUIDANCE_SCALE, value).apply()

    var selectedWidth: Int
        get() = prefs.getInt(KEY_WIDTH, 384)
        set(value) = prefs.edit().putInt(KEY_WIDTH, value).apply()

    var selectedHeight: Int
        get() {
            val h = prefs.getInt(KEY_HEIGHT, 256)
            return if (h == 360) 368 else h
        }
        set(value) = prefs.edit().putInt(KEY_HEIGHT, value).apply()

    var autoInterpolate60Fps: Boolean
        get() = prefs.getBoolean(KEY_AUTO_60FPS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_60FPS, value).apply()

    var isPhotoMode: Boolean
        get() = prefs.getBoolean(KEY_PHOTO_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_PHOTO_MODE, value).apply()

    var showTelemetry: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TELEMETRY, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TELEMETRY, value).apply()

    var threadCount: Int
        get() = prefs.getInt(KEY_THREAD_COUNT, 6) // Výchozí: 6 velkých jader (Snapdragon 8 Gen 3: 1x X4 + 5x A720)
        set(value) = prefs.edit().putInt(KEY_THREAD_COUNT, value).apply()

    var affinityMode: Int
        get() = prefs.getInt(KEY_AFFINITY_MODE, 0) // 0=Auto, 1=Big Cores, 2=Solo X4 Prime Core, 3=Mid Cluster
        set(value) = prefs.edit().putInt(KEY_AFFINITY_MODE, value).apply()

    var enableCpuAffinity: Boolean
        get() = affinityMode != 0
        set(value) {
            if (value && affinityMode == 0) affinityMode = 1
            else if (!value) affinityMode = 0
        }

    var threadPriority: Int
        get() = prefs.getInt(KEY_THREAD_PRIORITY, 1) // 0=Urgent (-10), 1=Default (0), 2=Background (+10)
        set(value) = prefs.edit().putInt(KEY_THREAD_PRIORITY, value).apply()

    var sampleMethod: Int
        get() = prefs.getInt(KEY_SAMPLE_METHOD, 0) // 0 = EULER_SAMPLE_METHOD
        set(value) = prefs.edit().putInt(KEY_SAMPLE_METHOD, value).apply()

    var scheduler: Int
        get() = prefs.getInt(KEY_SCHEDULER, 0) // 0 = DISCRETE_SCHEDULER
        set(value) = prefs.edit().putInt(KEY_SCHEDULER, value).apply()

    var dynamicCfgThresholding: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_CFG, false) // Výchozí: false
        set(value) = prefs.edit().putBoolean(KEY_DYNAMIC_CFG, value).apply()

    var enableOpenmp: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_OPENMP, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_OPENMP, value).apply()

    var enableTeaCache: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_TEACACHE, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_TEACACHE, value).apply()

    var teaCacheThreshold: Float
        get() = prefs.getFloat(KEY_TEACACHE_THRESHOLD, 0.12f)
        set(value) = prefs.edit().putFloat(KEY_TEACACHE_THRESHOLD, value).apply()

    var teaCacheMode: Int
        get() = prefs.getInt(KEY_TEACACHE_MODE, 1) // 1=EasyCache, 2=Cache-DiT
        set(value) = prefs.edit().putInt(KEY_TEACACHE_MODE, value).apply()

    var fastModeNoCfg: Boolean
        get() = prefs.getBoolean(KEY_FAST_MODE_NO_CFG, true) // Výchozí: true pro bleskové generování bez zbytečného negativního promptu
        set(value) = prefs.edit().putBoolean(KEY_FAST_MODE_NO_CFG, value).apply()

    fun saveAll(
        prompt: String,
        negativePrompt: String,
        seed: String,
        steps: Float,
        guidanceScale: Float,
        width: Int,
        height: Int,
        auto60Fps: Boolean,
        photoMode: Boolean,
        telemetry: Boolean,
        threadCount: Int,
        sampleMethod: Int,
        scheduler: Int,
        dynamicCfg: Boolean,
        enableCpuAffinity: Boolean = this.enableCpuAffinity,
        affinityMode: Int = this.affinityMode,
        threadPriority: Int = this.threadPriority,
        enableOpenmp: Boolean = this.enableOpenmp,
        enableTeaCache: Boolean = this.enableTeaCache,
        teaCacheThreshold: Float = this.teaCacheThreshold,
        teaCacheMode: Int = this.teaCacheMode,
        fastModeNoCfg: Boolean = this.fastModeNoCfg,
        powerProfile: Int = this.powerProfile,
        executionBackend: Int = this.executionBackend
    ) {
        this.prompt = prompt
        this.negativePrompt = negativePrompt
        this.seed = seed
        this.steps = steps
        this.guidanceScale = guidanceScale
        this.selectedWidth = width
        this.selectedHeight = height
        this.autoInterpolate60Fps = auto60Fps
        this.isPhotoMode = photoMode
        this.showTelemetry = telemetry
        this.threadCount = threadCount
        this.sampleMethod = sampleMethod
        this.scheduler = scheduler
        this.dynamicCfgThresholding = dynamicCfg
        this.enableCpuAffinity = enableCpuAffinity
        this.affinityMode = affinityMode
        this.threadPriority = threadPriority
        this.enableOpenmp = enableOpenmp
        this.enableTeaCache = enableTeaCache
        this.teaCacheThreshold = teaCacheThreshold
        this.teaCacheMode = teaCacheMode
        this.fastModeNoCfg = fastModeNoCfg
        this.powerProfile = powerProfile
        this.executionBackend = executionBackend
    }

    fun applyPowerProfile(profile: Int) {
        this.powerProfile = profile
        when (profile) {
            PROFILE_ECO -> {
                this.threadCount = 2
                this.enableCpuAffinity = true
                this.affinityMode = 3 // Cortex-A720 cluster / mid cores
                this.threadPriority = 2 // Background (Nice +10)
                this.enableOpenmp = true
            }
            PROFILE_BALANCED -> {
                this.threadCount = 4
                this.enableCpuAffinity = true
                this.affinityMode = 3 // Mid cluster
                this.threadPriority = 1 // Default (Nice 0)
                this.enableOpenmp = true
            }
            PROFILE_TURBO -> {
                this.threadCount = 6
                this.enableCpuAffinity = true
                this.affinityMode = 1 // All Big Cores (Prime X4 + A720 cluster)
                this.threadPriority = 0 // Urgent (Nice -16)
                this.enableOpenmp = true
                this.fastModeNoCfg = true
            }
        }
    }
}
