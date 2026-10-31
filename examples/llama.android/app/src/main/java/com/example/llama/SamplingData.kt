package com.example.llama

/**
 * Data class to encapsulate all sampling parameters for clarity.
 * Updated by Android-AP-Master to include Penalty parameters.
 */
data class SamplingConfig(
    val temperature: Float,
    val dynamicTempRange: Float,
    val dynamicTempExponent: Float,
    val topK: Int,
    val topP: Float,
    val minP: Float,
    val typicalP: Float,
    val xtcProbability: Float,
    val xtcThreshold: Float,
    val maxTokens: Int,
    // New Penalty Parameters
    val penaltyRepeat: Float,
    val penaltyFreq: Float,
    val penaltyPresent: Float,
    val penaltyLastN: Int,
    
    // New Mirostat Parameters
    val mirostat: Int,
    val mirostatTau: Float,
    val mirostatEta: Float,
    
    val samplersSequence: String,
    val useBackendSampling: Boolean
)

/**
 * Centralizovaná definice Sampling parametrů pro llama.cpp.
 */
object SamplingConstants {

    // ================================================================================================
    // PREFERENCE KEYS
    // ================================================================================================
    const val KEY_TEMPERATURE = "sampling_temperature"
    const val KEY_DYN_TEMP_RANGE = "sampling_dyn_temp_range"
    const val KEY_DYN_TEMP_EXPONENT = "sampling_dyn_temp_exponent"

    const val KEY_TOP_K = "sampling_top_k"
    const val KEY_TOP_P = "sampling_top_p"
    const val KEY_MIN_P = "sampling_min_p"
    const val KEY_TYPICAL_P = "sampling_typical_p"

    const val KEY_XTC_PROBABILITY = "sampling_xtc_probability"
    const val KEY_XTC_THRESHOLD = "sampling_xtc_threshold"

    // New Penalty Keys
    const val KEY_PENALTY_REPEAT = "sampling_penalty_repeat"
    const val KEY_PENALTY_FREQ = "sampling_penalty_freq"
    const val KEY_PENALTY_PRESENT = "sampling_penalty_present"
    const val KEY_PENALTY_LAST_N = "sampling_penalty_last_n"

    const val KEY_MAX_TOKENS = "sampling_max_tokens"

    const val KEY_SAMPLERS_SEQUENCE = "sampling_sequence"
    const val KEY_BACKEND_SAMPLING = "sampling_backend_enabled"

    // Mirostat Keys
    const val KEY_MIROSTAT_MODE = "sampling_mirostat_mode"
    const val KEY_MIROSTAT_TAU = "sampling_mirostat_tau"
    const val KEY_MIROSTAT_ETA = "sampling_mirostat_eta"

    // ================================================================================================
    // DEFAULT VALUES
    // ================================================================================================
    const val DEFAULT_TEMPERATURE = 0.9f

    const val DEFAULT_DYN_TEMP_RANGE = 0.0f
    const val DEFAULT_DYN_TEMP_EXPONENT = 1.0f

    const val DEFAULT_TOP_K = 40
    const val DEFAULT_TOP_P = 0.95f
    const val DEFAULT_MIN_P = 0.05f
    const val DEFAULT_TYPICAL_P = 1.0f

    const val DEFAULT_XTC_PROBABILITY = 0.0f
    const val DEFAULT_XTC_THRESHOLD = 0.1f

    // New Penalty Defaults (As requested)
    const val DEFAULT_PENALTY_REPEAT = 1.10f
    const val DEFAULT_PENALTY_FREQ = 0.00f
    const val DEFAULT_PENALTY_PRESENT = 0.00f
    const val DEFAULT_PENALTY_LAST_N = 64

    const val DEFAULT_MAX_TOKENS = -1 // Unlimited = generování do EOG nebo context size (n_ctx)

    const val DEFAULT_SAMPLERS_SEQUENCE = "penalties;dry;top_n_sigma;top_k;typ_p;top_p;min_p;xtc;temperature"

    const val DEFAULT_BACKEND_SAMPLING = false

    // Mirostat Defaults
    const val DEFAULT_MIROSTAT_MODE = 0 // 0=Disabled, 1=Mirostat, 2=Mirostat 2.0
    const val DEFAULT_MIROSTAT_TAU = 5.0f
    const val DEFAULT_MIROSTAT_ETA = 0.1f
}
