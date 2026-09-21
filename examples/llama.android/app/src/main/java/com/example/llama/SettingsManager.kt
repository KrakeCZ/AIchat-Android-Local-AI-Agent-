package com.example.llama

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SettingsManager(context: Context) {

    enum class ScreenParserMode { ON, OFF, AUTO }

    val prefs: SharedPreferences =
            context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    companion object {
        // General
        private const val KEY_SYSTEM_MESSAGE = "system_message"
        private const val KEY_SHOW_SYSTEM_MESSAGE = "show_system_message"
        private const val DEFAULT_SYSTEM_MESSAGE = "Jsi užitečný a inteligentní AI asistent."
        private const val KEY_MAX_IMPORT_SIZE = "max_import_size"
        private const val DEFAULT_MAX_IMPORT_SIZE = 512 * 1024L // 512 KB

        // Display
        private const val KEY_SHOW_STATS = "show_stats"
        private const val KEY_SHOW_TOKEN_COUNT = "show_token_count"
        private const val KEY_SHOW_GEN_TIME = "show_gen_time"
        private const val KEY_SHOW_THOUGHTS = "show_thoughts"
        private const val KEY_KEEP_STATS = "keep_stats"
        private const val KEY_SHOW_MIC = "show_mic"
        private const val KEY_RENDER_MARKDOWN = "render_markdown"
        private const val KEY_DISABLE_AUTO_SCROLL = "disable_auto_scroll"
        private const val KEY_ALWAYS_SHOW_SIDEBAR = "always_show_sidebar"
        private const val KEY_AUTO_SHOW_SIDEBAR = "auto_show_sidebar"
        private const val KEY_BUBBLE_COPY = "bubble_copy"
        private const val KEY_BACKGROUND_GEN = "background_gen"
        private const val KEY_CHAT_STYLE = "chat_style"
        private const val KEY_HIDE_STATUS_BAR = "hide_status_bar"

        // Memory & Performance (Advanced)
        private const val KEY_USE_MMAP = "use_mmap"
        private const val KEY_KV_CACHE_MODE = "kv_cache_mode"
        private const val KEY_CONTEXT_SIZE = "context_size"
        private const val KEY_BATCH_SIZE = "batch_size"
        private const val KEY_UBATCH_SIZE = "ubatch_size"
        private const val KEY_FLASH_ATTN = "flash_attn"

        // Voice Services (Advanced) - DEFAULT: FALSE (Disabled by default as requested)
        private const val KEY_STT_ENABLED = "stt_enabled"
        private const val KEY_TTS_ENABLED = "tts_enabled"

        // AI Agent Settings
        private const val KEY_AGENT_ENABLED = "agent_enabled"
        private const val KEY_AGENT_TTS_ENABLED = "agent_tts_enabled"
        private const val KEY_AGENT_STT_ENABLED = "agent_stt_enabled"
        private const val KEY_AGENT_VOICE_FEEDBACK = "agent_voice_feedback"
        private const val KEY_AGENT_BACKGROUND_ENABLED = "agent_background_enabled"
        private const val KEY_AGENT_WAKE_WORD_ENABLED = "agent_wake_word_enabled"
        private const val KEY_AGENT_WAKE_WORD = "agent_wake_word"
        private const val KEY_AGENT_VOICE_MODE = "agent_voice_mode"

        const val VOICEMODE_PUSH_TO_TALK = "push_to_talk"
        const val VOICEMODE_OPEN_MIC = "open_mic"

        private const val KEY_AGENT_SPEECH_ENGINE = "agent_speech_engine"
        private const val KEY_TRANSCRIPT_ANDROID_ENGINE = "transcript_android_engine"
        private const val KEY_AGENT_TTS_ENGINE = "agent_tts_engine"

        // Language Settings (Dual Configuration)
        private const val KEY_AGENT_LANGUAGE_ONLINE = "agent_language_online"
        private const val KEY_AGENT_LANGUAGE_OFFLINE = "agent_language_offline"
        private const val KEY_AGENT_STT_FORCE_OFFLINE = "agent_stt_force_offline"
        private const val KEY_AGENT_ONLINE_ENABLED = "agent_online_enabled"
        private const val KEY_AGENT_TIMEOUT = "agent_timeout_ms"
        const val DEFAULT_AGENT_TIMEOUT_MS = 30000L // 30s default

        // Onboarding
        private const val KEY_LAST_DIALER_PROMPT = "last_dialer_prompt_time"

        // App Layout Cache (Advanced)
        private const val KEY_CACHE_TTL = "app_layout_cache_ttl"
        const val DEFAULT_CACHE_TTL = 5000L

        // Agent Timeouty (Advanced)
        private const val KEY_AGENT_LLM_CANCEL_TIMEOUT = "agent_llm_cancel_timeout"
        const val DEFAULT_LLM_CANCEL_TIMEOUT = 5000L // 5s
        private const val KEY_AGENT_SMARTCLICK_TIMEOUT = "agent_smartclick_timeout"
        const val DEFAULT_SMARTCLICK_TIMEOUT = 8000L // 8s
        private const val KEY_AGENT_APP_LOAD_WAIT = "agent_app_load_wait"
        const val DEFAULT_APP_LOAD_WAIT = 2000L // 2s
        private const val KEY_AGENT_GESTURE_TIMEOUT = "agent_gesture_timeout"
        const val DEFAULT_GESTURE_TIMEOUT = 3000L // 3s
        private const val KEY_AGENT_TOGGLE_TIMEOUT = "agent_toggle_timeout"
        const val DEFAULT_TOGGLE_TIMEOUT = 5000L // 5s

        // Agent Interaction Customization
        private const val KEY_AGENT_SCROLL_DISTANCE = "agent_scroll_distance"
        const val DEFAULT_SCROLL_DISTANCE = 0.8f // 80% screen height
        private const val KEY_AGENT_ZOOM_FACTOR = "agent_zoom_factor"
        const val DEFAULT_ZOOM_FACTOR = 2.0f // 2x zoom
        private const val KEY_AGENT_TAP_INTERVAL = "agent_tap_interval"
        const val DEFAULT_TAP_INTERVAL = 50L // 50ms
        private const val KEY_AGENT_TAP_COUNT = "agent_tap_count"
        const val DEFAULT_TAP_COUNT = 10 // 10 taps default

        private const val KEY_INVERT_SWIPE_VERTICAL = "invert_swipe_vertical"
        private const val KEY_INVERT_SWIPE_HORIZONTAL = "invert_swipe_horizontal"

        // Agent Memory (Screen Context Memory)
        private const val KEY_MEMORY_MAX_APPS = "agent_memory_max_apps"
        const val DEFAULT_MEMORY_MAX_APPS = 30
        private const val KEY_MEMORY_MAX_PATTERNS = "agent_memory_max_patterns"
        const val DEFAULT_MEMORY_MAX_PATTERNS = 50
        private const val KEY_MEMORY_FLUSH_INTERVAL = "agent_memory_flush_interval"
        const val DEFAULT_MEMORY_FLUSH_INTERVAL = 60000L // 60s
        private const val KEY_MEMORY_PATTERN_MAX_AGE = "agent_memory_pattern_max_age"
        const val DEFAULT_MEMORY_PATTERN_MAX_AGE = 7 // 7 dní
        
        // --- VISION PRE-PROCESSORS ---
        private const val KEY_PERCEPTION_CLAHE_PREPROCESSOR = "perception_clahe_preprocessor"
        const val DEFAULT_PERCEPTION_CLAHE_PREPROCESSOR = false
        private const val KEY_PERCEPTION_UNSHARP_MASK = "perception_unsharp_mask"
        const val DEFAULT_PERCEPTION_UNSHARP_MASK = false
        private const val KEY_PERCEPTION_LOG_STRETCH = "perception_log_stretch"
        const val DEFAULT_PERCEPTION_LOG_STRETCH = false
        private const val KEY_MEMORY_MIN_CONFIDENCE = "agent_memory_min_confidence"
        const val DEFAULT_MEMORY_MIN_CONFIDENCE = 0.7f
        private const val KEY_MEMORY_RAM_LIMIT_KB = "agent_memory_ram_limit"
        const val DEFAULT_MEMORY_RAM_LIMIT_KB = 512 // 512 KB

        // Night Mode (Low-Light Enhancement)
        private const val KEY_NIGHT_MODE_ENABLED = "night_mode_enabled"
        const val DEFAULT_NIGHT_MODE_ENABLED = false
        private const val KEY_NIGHT_MODE_PLUGIN = "night_mode_plugin"
        const val DEFAULT_NIGHT_MODE_PLUGIN = "night_retinex"
        private const val KEY_NIGHT_MODE_CUSTOM_MODEL = "night_mode_custom_model"

        // LLM Fallback (Tier 3) — výchozně VYPNUTO. Tier 1/2 pokrývají 100% příkazů.
        // Zapnout pouze pokud chceš LLM jako last-resort pro neznámé příkazy.
        private const val KEY_AGENT_USE_LLM_FALLBACK = "agent_use_llm_fallback"
        const val DEFAULT_AGENT_USE_LLM_FALLBACK = false

        // IrisMotion (Tier 2.2 — Eye Tracking Cursor)
        private const val KEY_IRISMOTION_ENABLED = "irismotion_enabled"
        private const val KEY_IRISMOTION_REMOTE_MODE_ENABLED = "irismotion_remote_mode_enabled"
        private const val KEY_IRISMOTION_BT_SENSITIVITY = "irismotion_bt_sensitivity"
        private const val KEY_IRISMOTION_BT_DEADZONE = "irismotion_bt_deadzone"
        private const val KEY_IRISMOTION_BT_ACCELERATION = "irismotion_bt_acceleration"
        private const val KEY_IRISMOTION_BT_INVERT_X = "irismotion_bt_invert_x"
        private const val KEY_IRISMOTION_BT_INVERT_Y = "irismotion_bt_invert_y"
        private const val KEY_IRISMOTION_BT_REMOTE_WIDTH = "irismotion_bt_remote_width"
        private const val KEY_IRISMOTION_BT_REMOTE_HEIGHT = "irismotion_bt_remote_height"
        const val DEFAULT_IRISMOTION_REMOTE_MODE_ENABLED = false
        const val DEFAULT_IRISMOTION_BT_SENSITIVITY = 3.0f
        const val DEFAULT_IRISMOTION_BT_DEADZONE = 2.0f
        const val DEFAULT_IRISMOTION_BT_ACCELERATION = 1.5f
        const val DEFAULT_IRISMOTION_BT_INVERT_X = false
        const val DEFAULT_IRISMOTION_BT_INVERT_Y = false
        const val DEFAULT_IRISMOTION_BT_REMOTE_WIDTH = 1920
        const val DEFAULT_IRISMOTION_BT_REMOTE_HEIGHT = 1080
        private const val KEY_IRISMOTION_NEURAL_CORE = "irismotion_neural_core"
        private const val KEY_IRISMOTION_DWELL_ENABLED = "irismotion_dwell_enabled"
        
        // P7: Intentionality Engine
        private const val KEY_IRISMOTION_TIMESHIFT_ENABLED = "irismotion_timeshift_enabled"
        private const val KEY_IRISMOTION_TIMESHIFT_MS = "irismotion_timeshift_ms"
        private const val KEY_IRISMOTION_INTENT_FILTER_ENABLED = "irismotion_intent_filter_enabled"
        private const val KEY_IRISMOTION_INTENT_FILTER_THRESHOLD_DEG = "irismotion_intent_filter_threshold_deg"
        
        // P8: Auto-Calibration EAR
        private const val KEY_IRISMOTION_PHYSIO_OPEN_EAR = "irismotion_physio_open_ear"
        private const val KEY_IRISMOTION_BLINK_SENSITIVITY = "irismotion_blink_sensitivity"
        
        private const val KEY_IRISMOTION_DWELL_MS = "irismotion_dwell_ms"
        private const val KEY_IRISMOTION_CURSOR_SIZE = "irismotion_cursor_size"
        private const val KEY_IRISMOTION_SENSITIVITY = "irismotion_sensitivity"
        private const val KEY_IRISMOTION_CALIBRATED = "irismotion_calibrated"
        private const val KEY_IRISMOTION_TRACKED_EYE = "irismotion_tracked_eye"
        private const val KEY_IRISMOTION_CAMERA_MIRRORED = "irismotion_camera_mirrored"
        private const val KEY_IRISMOTION_EDGE_DECAY_ENABLED = "irismotion_edge_decay"
        private const val KEY_IRISMOTION_FILTER_BETA = "irismotion_filter_beta"
        private const val KEY_IRISMOTION_FILTER_CUTOFF = "irismotion_filter_cutoff"
        private const val KEY_IRISMOTION_ALPHABETA_ALPHA = "irismotion_alphabeta_alpha"
        private const val KEY_IRISMOTION_ALPHABETA_BETA = "irismotion_alphabeta_beta"
        private const val KEY_IRISMOTION_GAZE_AMP_X = "irismotion_gaze_amp_x"
        private const val KEY_IRISMOTION_GAZE_AMP_Y = "irismotion_gaze_amp_y"

        // SmartVision settings
        private const val KEY_VISION_MODEL_ID = "active_vision_model_id"
        private const val KEY_VISION_NATIVE_MODEL = "is_native_vision_model"

        // OmniParser SmartVision
        private const val KEY_SMARTVISION_OMNIPARSER_ENABLED = "smartvision_omniparser_enabled"
        private const val KEY_SMARTVISION_FILTER_EMPTY_IMAGEVIEW = "smartvision_filter_empty_imageview"
        private const val KEY_SMARTVISION_FILTER_NON_INTERACTIVE = "smartvision_filter_non_interactive"
        private const val KEY_SMARTVISION_FILTER_SCROLLABLE = "smartvision_filter_scrollable"
        private const val KEY_SMARTVISION_FILTER_WEBVIEW = "smartvision_filter_webview"
        private const val KEY_SMARTVISION_FILTER_EMPTY_EDITTEXT = "smartvision_filter_empty_edittext"
        private const val KEY_SMARTVISION_FILTER_OFFSCREEN = "smartvision_filter_offscreen"
        private const val KEY_SMARTVISION_FILTER_CONTAINERS = "smartvision_filter_containers"
        private const val KEY_SMARTVISION_MAX_DEPTH = "smartvision_max_depth"
        private const val KEY_SMARTVISION_TARGET_RESOLUTION = "smartvision_target_resolution"

        private const val DEFAULT_SMARTVISION_OMNIPARSER_ENABLED = false
        private const val DEFAULT_SMARTVISION_FILTER_EMPTY_IMAGEVIEW = true
        private const val DEFAULT_SMARTVISION_FILTER_NON_INTERACTIVE = false
        private const val DEFAULT_SMARTVISION_FILTER_SCROLLABLE = true
        private const val DEFAULT_SMARTVISION_FILTER_WEBVIEW = false
        private const val DEFAULT_SMARTVISION_FILTER_EMPTY_EDITTEXT = false
        private const val DEFAULT_SMARTVISION_FILTER_OFFSCREEN = true
        private const val DEFAULT_SMARTVISION_FILTER_CONTAINERS = true
        private const val DEFAULT_SMARTVISION_MAX_DEPTH = 10
        private const val DEFAULT_SMARTVISION_TARGET_RESOLUTION = 1024

        private const val KEY_IRISMOTION_AMP_UP = "irismotion_amp_up"
        private const val KEY_IRISMOTION_AMP_DOWN = "irismotion_amp_down"
        private const val KEY_IRISMOTION_AMP_LEFT = "irismotion_amp_left"
        private const val KEY_IRISMOTION_AMP_RIGHT = "irismotion_amp_right"

        private const val KEY_IRISMOTION_INVERT_X = "irismotion_invert_x"
        private const val KEY_IRISMOTION_INVERT_Y = "irismotion_invert_y"
        private const val KEY_IRISMOTION_GAZE_AMP_LEFT_EYE_LEFT =
                "irismotion_gaze_amp_left_eye_left"
        private const val KEY_IRISMOTION_GAZE_AMP_LEFT_EYE_RIGHT =
                "irismotion_gaze_amp_left_eye_right"
        private const val KEY_IRISMOTION_GAZE_AMP_RIGHT_EYE_LEFT =
                "irismotion_gaze_amp_right_eye_left"
        private const val KEY_IRISMOTION_GAZE_AMP_RIGHT_EYE_RIGHT =
                "irismotion_gaze_amp_right_eye_right"
        private const val KEY_IRISMOTION_CENTER_X_OFFSET = "irismotion_center_x_offset"
        private const val KEY_IRISMOTION_CENTER_Y_OFFSET = "irismotion_center_y_offset"
        private const val KEY_IRISMOTION_FILTER_SEQUENCE = "irismotion_filter_sequence"
        private const val KEY_IRISMOTION_DEADZONE_PX = "irismotion_deadzone_px"
        private const val KEY_IRISMOTION_MEDIAN_WINDOW = "irismotion_median_window"
        private const val KEY_IRISMOTION_KALMAN_Q_NOISE = "irismotion_kalman_q_noise"
        private const val KEY_IRISMOTION_KALMAN_R_NOISE = "irismotion_kalman_r_noise"
        private const val KEY_IRISMOTION_FILTER_D_CUTOFF = "irismotion_filter_d_cutoff"
        private const val KEY_IRISMOTION_BLINK_TIMEOUT = "irismotion_blink_timeout"
        private const val KEY_IRISMOTION_DOUBLE_BLINK_INTERVAL = "irismotion_double_blink_interval"
        private const val KEY_IRISMOTION_TARGET_SNAP = "irismotion_target_snap"
        private const val KEY_IRISMOTION_TARGET_SNAP_RADIUS = "irismotion_target_snap_radius"
        private const val KEY_IRISMOTION_SPHERICAL_COMPENSATION =
                "irismotion_spherical_compensation"

        // Nové klíče: Dva oddělené sensitivity (globální + filtr) + Fyziologické konstanty + Edge
        // Margin
        private const val KEY_IRISMOTION_FILTER_SENSITIVITY = "irismotion_filter_sensitivity"
        private const val KEY_IRISMOTION_PHYSIO_REST_X = "irismotion_physio_rest_x"
        private const val KEY_IRISMOTION_PHYSIO_REST_Y = "irismotion_physio_rest_y"
        private const val KEY_IRISMOTION_INTRINSIC_AMP_X = "irismotion_intrinsic_amp_x"
        private const val KEY_IRISMOTION_INTRINSIC_AMP_Y = "irismotion_intrinsic_amp_y"
        private const val KEY_IRISMOTION_EDGE_MARGIN = "irismotion_edge_margin"
        private const val KEY_IRISMOTION_HEAD_TRACKING_DOF = "irismotion_head_tracking_dof"
        private const val KEY_IRISMOTION_HEAD_COMPENSATION_GAIN =
                "irismotion_head_compensation_gain"
        private const val KEY_IRISMOTION_BASE_SPEED = "irismotion_base_speed"
        private const val KEY_IRISMOTION_REENGAGEMENT_TIMEOUT = "irismotion_reengagement_timeout"
        private const val KEY_IRISMOTION_DUCHENNE_PENALTY = "irismotion_duchenne_penalty"
        const val DEFAULT_IRISMOTION_DUCHENNE_PENALTY = 0.5f
        private const val KEY_IRISMOTION_AUTO_CENTER = "irismotion_auto_center"
        private const val KEY_IRISMOTION_AUTO_CENTER_SPEED = "irismotion_auto_center_speed"
        private const val KEY_IRISMOTION_AUTO_CENTER_DELAY_MS = "irismotion_auto_center_delay_ms"
        private const val KEY_IRISMOTION_CAMERA_ZOOM = "irismotion_camera_zoom"
        private const val KEY_IRISMOTION_CAMERA_EXPOSURE = "irismotion_camera_exposure"
        private const val KEY_IRISMOTION_SACCADE_VELOCITY = "irismotion_saccade_velocity"
        private const val KEY_IRISMOTION_SACCADE_BOX = "irismotion_saccade_box"
        private const val KEY_IRISMOTION_FIXATION_DRIFT = "irismotion_fixation_drift"
        // Sensor Fusion: Váha Neural vs IK výpočtu (0.0 = čisté IK, 1.0 = čistý Neural)
        private const val KEY_IRISMOTION_NEURAL_WEIGHT = "irismotion_neural_weight"
        // Tracking Paradigm: true = Absolutní (Gaze Pointing), false = Relativní (HW Mouse)
        private const val KEY_IRISMOTION_TRACKING_ABSOLUTE = "irismotion_tracking_absolute"
        const val DEFAULT_IRISMOTION_NEURAL_CORE = true
        const val DEFAULT_IRISMOTION_DWELL_MS = 800L // 800ms dwell před kliknutím
        const val DEFAULT_IRISMOTION_CURSOR_SIZE = 80 // 80px průměr kurzoru
        const val DEFAULT_IRISMOTION_TARGET_SNAP = false // Default OFF
        const val DEFAULT_IRISMOTION_TARGET_SNAP_RADIUS = 60f // 60px default radius
        const val DEFAULT_IRISMOTION_SPHERICAL_COMPENSATION = false // Výchozí OFF pro freeAir
        const val DEFAULT_IRISMOTION_SENSITIVITY = 1.0f // 1.0 = normální globální citlivost
        const val DEFAULT_IRISMOTION_FILTER_SENSITIVITY = 1.0f // 1.0 = normální OneEuro sensitivity
        const val DEFAULT_IRISMOTION_TRACKED_EYE = 0 // 0 = Averaged, 1 = Left, 2 = Right
        const val DEFAULT_IRISMOTION_CAMERA_MIRRORED = true
        const val DEFAULT_IRISMOTION_FILTER_BETA = 2.5f // Reaktivita 1€ filtru (Casiez: 0.001–20.0)
        const val DEFAULT_IRISMOTION_FILTER_CUTOFF = 0.05f // Frekvence vyhlazení v klidu (Hz)
        const val DEFAULT_IRISMOTION_FILTER_D_CUTOFF = 1.0f // Frekvence derivace vzorkování
        const val DEFAULT_IRISMOTION_ALPHABETA_ALPHA = 0.5f // AlphaBetaFilter pozice
        const val DEFAULT_IRISMOTION_ALPHABETA_BETA = 0.1f // AlphaBetaFilter rychlost
        const val DEFAULT_IRISMOTION_KALMAN_Q_NOISE = 0.001f // Kalman Process Noise
        const val DEFAULT_IRISMOTION_KALMAN_R_NOISE = 0.02f // Kalman Measurement Noise
        const val DEFAULT_IRISMOTION_FIXATION_DRIFT = 0.05f // Dotahovací rychlost v klidu
        const val DEFAULT_IRISMOTION_GAZE_AMP_X = 1.3f // Horizontální zesílení pohybu
        const val DEFAULT_IRISMOTION_GAZE_AMP_Y = 1.2f // Vertikální zesílení pohybu
        const val DEFAULT_IRISMOTION_AMP_UP = 1.6f
        const val DEFAULT_IRISMOTION_AMP_DOWN = 1.6f
        const val DEFAULT_IRISMOTION_AMP_LEFT = 1.8f
        const val DEFAULT_IRISMOTION_AMP_RIGHT = 1.8f

        const val DEFAULT_IRISMOTION_INVERT_X = false
        const val DEFAULT_IRISMOTION_INVERT_Y = false
        const val DEFAULT_IRISMOTION_GAZE_AMP_LEFT_EYE_LEFT =
                1.0f // Per-eye: levé oko, pohled doleva (neutrální)
        const val DEFAULT_IRISMOTION_GAZE_AMP_LEFT_EYE_RIGHT =
                1.0f // Per-eye: levé oko, pohled doprava
        const val DEFAULT_IRISMOTION_GAZE_AMP_RIGHT_EYE_LEFT =
                1.0f // Per-eye: pravé oko, pohled doleva
        const val DEFAULT_IRISMOTION_GAZE_AMP_RIGHT_EYE_RIGHT =
                1.0f // Per-eye: pravé oko, pohled doprava
        const val DEFAULT_IRISMOTION_CENTER_X_OFFSET =
                0.5f // Master IT Fix: Absolutní fyzický střed senzoru X
        const val DEFAULT_IRISMOTION_CENTER_Y_OFFSET =
                0.30f // Master IT Fix: Absolutní fyzický fyziologický posed Y
        const val DEFAULT_IRISMOTION_DEADZONE_PX = 30f // Mrtvá zóna pro DwellClick (px)
        const val DEFAULT_IRISMOTION_FILTER_SEQUENCE =
                "MEDIAN,KALMAN,ONEEURO" // Dle uživatele (Kalman default)
        const val DEFAULT_IRISMOTION_MEDIAN_WINDOW = 3 // Mediánový filtr okno (liché 1–9)

        // Fyziologické konstanty — přístupné z UI (pokročilé nastavení)
        const val DEFAULT_IRISMOTION_PHYSIO_REST_X = 0.50f // Fyziologický nedbal X
        const val DEFAULT_IRISMOTION_PHYSIO_REST_Y = 0.35f // Fyziologický nedbal Y (horní víčko)
        const val DEFAULT_IRISMOTION_INTRINSIC_AMP_X = 2.5f // Intrinsic amplifikace X
        const val DEFAULT_IRISMOTION_INTRINSIC_AMP_Y = 2.5f // Intrinsic amplifikace Y
        const val DEFAULT_IRISMOTION_EDGE_MARGIN = 0.08f // Edge Decay margin (8%)
        const val DEFAULT_IRISMOTION_HEAD_TRACKING_DOF = 2 // 2 = Yaw+Pitch (default)
        const val DEFAULT_IRISMOTION_HEAD_COMPENSATION_GAIN =
                0.3f // Síla kompenzace translace hlavy (0.0–1.0)
        const val DEFAULT_IRISMOTION_BASE_SPEED = 1.6f // Base speed multiplier (0.0–10.0)
        const val DEFAULT_IRISMOTION_REENGAGEMENT_TIMEOUT = 266L // Anti-Snapback ramp (ms)
        const val DEFAULT_IRISMOTION_AUTO_CENTER = false
        const val DEFAULT_IRISMOTION_AUTO_CENTER_SPEED = 0.05f // 5% přesun za frame k centru
        const val DEFAULT_IRISMOTION_AUTO_CENTER_DELAY_MS = 1500L // Prodleva 1.5s
        const val DEFAULT_IRISMOTION_CAMERA_ZOOM = 1.0f // 1x = bez zoomu

        const val DEFAULT_IRISMOTION_SACCADE_VELOCITY = 1.7f // PyGaze velocity spike threshold
        
        // P8.2 Oprava Driftingu: Hodnota 0.005 byla moc úzká a zachycovala mikrotřes (jitter).
        // Zvednuto na 0.015 (1.5% displeje), aby kurzor stabilně držel na mrtvém bodě.
        const val DEFAULT_IRISMOTION_SACCADE_BOX = 0.015f // PyGaze fixation micro-jitter box
        const val DEFAULT_IRISMOTION_NEURAL_WEIGHT = 0.5f // Sensor Fusion: 50% IK + 50% Neural
        const val DEFAULT_IRISMOTION_TRACKING_ABSOLUTE = false // Default: Relativní mód (HW Mouse)

        // --- EYE GESTURES (LEFT / RIGHT / BOTH) ---
        // Akce uloženy jako INT konstanty: 0=None, 1=Click, 2=Back, 3=Home, 4=Flashlight,
        // 5=Notifications, 6=Quick Settings, 7=App Launch
        const val GESTURE_ACTION_NONE = 0
        const val GESTURE_ACTION_CLICK = 1
        const val GESTURE_ACTION_BACK = 2
        const val GESTURE_ACTION_HOME = 3
        const val GESTURE_ACTION_TORCH = 4
        const val GESTURE_ACTION_NOTIFICATIONS = 5
        const val GESTURE_ACTION_QUICK_SETTINGS = 6
        const val GESTURE_ACTION_LAUNCH_APP = 7
        const val GESTURE_ACTION_SWIPE_UP = 8
        const val GESTURE_ACTION_SWIPE_DOWN = 9
        const val GESTURE_ACTION_DOUBLE_CLICK = 10

        private const val KEY_IRISMOTION_LEFT_BLINK_TIMEOUT = "irismotion_left_blink_timeout"
        private const val KEY_IRISMOTION_RIGHT_BLINK_TIMEOUT = "irismotion_right_blink_timeout"
        private const val KEY_IRISMOTION_BOTH_BLINK_TIMEOUT = "irismotion_both_blink_timeout"
        private const val KEY_IRISMOTION_SMILE_TIMEOUT = "irismotion_smile_timeout"
        private const val KEY_IRISMOTION_FROWN_TIMEOUT = "irismotion_frown_timeout"
        private const val KEY_IRISMOTION_RAISE_EYEBROWS_TIMEOUT = "irismotion_raise_eyebrows_timeout"

        private const val KEY_IRISMOTION_SMILE_THRESHOLD = "irismotion_smile_threshold"
        private const val KEY_IRISMOTION_FROWN_THRESHOLD = "irismotion_frown_threshold"
        private const val KEY_IRISMOTION_RAISE_EYEBROWS_THRESHOLD = "irismotion_raise_eyebrows_threshold"

        private const val KEY_IRISMOTION_LEFT_BLINK_ACTION = "irismotion_left_blink_action"
        private const val KEY_IRISMOTION_RIGHT_BLINK_ACTION = "irismotion_right_blink_action"
        private const val KEY_IRISMOTION_BOTH_BLINK_ACTION = "irismotion_both_blink_action"
        private const val KEY_IRISMOTION_SMILE_ACTION = "irismotion_smile_action"
        private const val KEY_IRISMOTION_FROWN_ACTION = "irismotion_frown_action"
        private const val KEY_IRISMOTION_RAISE_EYEBROWS_ACTION = "irismotion_raise_eyebrows_action"
        private const val KEY_IRISMOTION_BOTH_BLINK_APP = "irismotion_both_blink_app"

        // Biologické filtry pro gesta
        private const val KEY_IRISMOTION_EXPRESSIONS_ONEEURO_ENABLED = "irismotion_expressions_oneeuro_enabled"
        private const val KEY_IRISMOTION_EXPRESSIONS_CROSSTALK_ENABLED = "irismotion_expressions_crosstalk_enabled"
        private const val KEY_IRISMOTION_EXPRESSIONS_ABT_ENABLED = "irismotion_expressions_abt_enabled"

        const val DEFAULT_IRISMOTION_LEFT_BLINK_TIMEOUT = 300L
        const val DEFAULT_IRISMOTION_RIGHT_BLINK_TIMEOUT = 300L
        const val DEFAULT_IRISMOTION_BOTH_BLINK_TIMEOUT = 800L // 800ms pro dlouhé zavření obou
        const val DEFAULT_IRISMOTION_SMILE_TIMEOUT = 500L
        const val DEFAULT_IRISMOTION_FROWN_TIMEOUT = 500L
        const val DEFAULT_IRISMOTION_RAISE_EYEBROWS_TIMEOUT = 500L

        const val DEFAULT_IRISMOTION_SMILE_THRESHOLD = 0.3f
        const val DEFAULT_IRISMOTION_FROWN_THRESHOLD = 0.3f
        const val DEFAULT_IRISMOTION_RAISE_EYEBROWS_THRESHOLD = 0.2f

        const val DEFAULT_IRISMOTION_LEFT_BLINK_ACTION = GESTURE_ACTION_CLICK
        const val DEFAULT_IRISMOTION_RIGHT_BLINK_ACTION = GESTURE_ACTION_BACK
        const val DEFAULT_IRISMOTION_BOTH_BLINK_ACTION = GESTURE_ACTION_LAUNCH_APP
        const val DEFAULT_IRISMOTION_SMILE_ACTION = GESTURE_ACTION_NONE
        const val DEFAULT_IRISMOTION_FROWN_ACTION = GESTURE_ACTION_NONE
        const val DEFAULT_IRISMOTION_RAISE_EYEBROWS_ACTION = GESTURE_ACTION_NONE
        const val DEFAULT_IRISMOTION_BOTH_BLINK_APP = "" // Prázdný = nepoužito

        const val DEFAULT_IRISMOTION_EXPRESSIONS_ONEEURO_ENABLED = true
        const val DEFAULT_IRISMOTION_EXPRESSIONS_CROSSTALK_ENABLED = true
        const val DEFAULT_IRISMOTION_EXPRESSIONS_ABT_ENABLED = true

        // ==================== PERCEPTION ENGINE SETTINGS ====================
        // Vision Engine (PerceptionEngine / MediaPipe OD + ML Kit OCR)
        private const val KEY_PERCEPTION_ENABLED = "perception_enabled"
        private const val KEY_PERCEPTION_OD_CONFIDENCE = "perception_od_confidence"
        private const val KEY_PERCEPTION_OD_MAX_RESULTS = "perception_od_max_results"
        private const val KEY_PERCEPTION_OD_TIMEOUT_MS = "perception_od_timeout_ms"
        private const val KEY_PERCEPTION_OCR_TIMEOUT_MS = "perception_ocr_timeout_ms"
        private const val KEY_PERCEPTION_OCR_MAX_LENGTH = "perception_ocr_max_length"
        private const val KEY_PERCEPTION_THERMAL_SKIP = "perception_thermal_skip"
        private const val KEY_PERCEPTION_THERMAL_GATE_1 = "perception_thermal_gate_1"
        private const val KEY_PERCEPTION_THERMAL_GATE_2 = "perception_thermal_gate_2"
        private const val KEY_PERCEPTION_THREAD_PRIORITY = "perception_thread_priority"
        private const val KEY_PERCEPTION_RESOLUTION = "perception_resolution"
        private const val KEY_PERCEPTION_ACCELERATOR_MODE = "perception_accelerator_mode"
        private const val KEY_PERCEPTION_CPU_THREADS = "perception_cpu_threads"
        private const val KEY_PERCEPTION_ASPECT_RATIO_MODE = "perception_aspect_ratio_mode"

        private const val KEY_PERCEPTION_TRACKER_ENABLED = "perception_tracker_enabled"
        private const val KEY_ACTIVE_VISION_PLUGIN_ID = "active_vision_plugin_id"

        // --- Pose Estimation (MediaPipe Tasks — BlazePose 33 landmarks) ---
        private const val KEY_PERCEPTION_POSE_ENABLED = "perception_pose_enabled"
        private const val KEY_PERCEPTION_POSE_CONFIDENCE = "perception_pose_confidence"
        private const val KEY_PERCEPTION_POSE_MAX_POSES = "perception_pose_max_poses"
        private const val KEY_PERCEPTION_POSE_MODEL_COMPLEXITY = "perception_pose_model_complexity"
        private const val KEY_PERCEPTION_POSE_TIMEOUT_MS = "perception_pose_timeout_ms"
        private const val KEY_PERCEPTION_POSE_RESOLUTION = "perception_pose_resolution"

        const val DEFAULT_PERCEPTION_POSE_ENABLED = false
        const val DEFAULT_PERCEPTION_POSE_CONFIDENCE = 0.5f
        const val DEFAULT_PERCEPTION_POSE_MAX_POSES = 1
        const val DEFAULT_PERCEPTION_POSE_MODEL_COMPLEXITY = "lite" // "lite" | "full" | "heavy"
        const val DEFAULT_PERCEPTION_POSE_TIMEOUT_MS = 2000L
        const val DEFAULT_PERCEPTION_POSE_RESOLUTION = 256 // 0 = Native/Unscaled, 256=Fast, 384, 512, 1024, 2048

        const val DEFAULT_PERCEPTION_ENABLED = false
        const val DEFAULT_PERCEPTION_TRACKER_ENABLED = true
        const val DEFAULT_ACTIVE_VISION_PLUGIN_ID = "tracker_iou_edge"
        const val DEFAULT_PERCEPTION_OD_CONFIDENCE = 0.4f
        const val DEFAULT_PERCEPTION_OD_MAX_RESULTS = 15
        const val DEFAULT_PERCEPTION_OD_TIMEOUT_MS = 2000L
        const val DEFAULT_PERCEPTION_OCR_TIMEOUT_MS = 3000L
        const val DEFAULT_PERCEPTION_OCR_MAX_LENGTH = 512
        const val DEFAULT_PERCEPTION_THERMAL_SKIP = 3
        const val DEFAULT_PERCEPTION_THERMAL_GATE_1 = 3 // SEVERE
        const val DEFAULT_PERCEPTION_THERMAL_GATE_2 = 2 // MODERATE
        const val DEFAULT_PERCEPTION_THREAD_PRIORITY = 0 // DEFAULT
        const val DEFAULT_PERCEPTION_RESOLUTION = 1024
        const val DEFAULT_PERCEPTION_ACCELERATOR_MODE = "CPU"
        const val DEFAULT_PERCEPTION_CPU_THREADS = 4
        const val DEFAULT_PERCEPTION_ASPECT_RATIO_MODE = 0

        private const val KEY_PERCEPTION_CAMERA_SOURCE = "perception_camera_source"
        const val DEFAULT_PERCEPTION_CAMERA_SOURCE = 0 // 0 = BACK, 1 = FRONT

        // Audio Engine (AudioPerceptionEngine / YAMNet)
        private const val KEY_PERCEPTION_AUDIO_ENABLED = "perception_audio_enabled"
        private const val KEY_PERCEPTION_AUDIO_CONFIDENCE = "perception_audio_confidence"
        private const val KEY_PERCEPTION_AUDIO_MAX_RESULTS = "perception_audio_max_results"
        private const val KEY_PERCEPTION_AUDIO_INTERVAL_MS = "perception_audio_interval_ms"
        private const val KEY_PERCEPTION_AUDIO_COOLDOWN_MS = "perception_audio_cooldown_ms"

        const val DEFAULT_PERCEPTION_AUDIO_ENABLED = false
        const val DEFAULT_PERCEPTION_AUDIO_CONFIDENCE = 0.3f
        const val DEFAULT_PERCEPTION_AUDIO_MAX_RESULTS = 5
        const val DEFAULT_PERCEPTION_AUDIO_INTERVAL_MS = 1000L
        const val DEFAULT_PERCEPTION_AUDIO_COOLDOWN_MS = 5000L

        // YAMNet aktivní jazyková databáze (název souboru bez .csv, pr.: "cz", "sk", "eng")
        private const val KEY_YAMNET_ACTIVE_LANG = "yamnet_active_lang"
        const val DEFAULT_YAMNET_ACTIVE_LANG = "eng" // OEM fallback

         // NLP & OCR (Omni-Control Toggles)
        private const val KEY_OCR_ENABLED = "ocr_enabled"
        private const val KEY_SUMMARIZATION_ENABLED = "summarization_enabled"
        const val DEFAULT_OCR_ENABLED = false
        const val DEFAULT_SUMMARIZATION_ENABLED = false

        // Transcript Agent (whisper.cpp / ASR / Sherpa-ONNX)
        private const val KEY_TRANSCRIPT_ENABLED = "transcript_enabled"
        private const val KEY_TRANSCRIPT_SOURCE = "transcript_source"     // "mic" | "system_audio"
        private const val KEY_TRANSCRIPT_ENGINE = "transcript_engine"     // "android" | "whisper" | "sherpa"
        private const val KEY_SHERPA_MODEL_PATH = "sherpa_model_path"
        private const val KEY_SHERPA_TTS_MODEL_PATH = "sherpa_tts_model_path"
        private const val KEY_SHERPA_TTS_SHARED_VOCODER = "sherpa_tts_shared_vocoder"
        
        // Sherpa-ONNX ZipVoice (TTS) Advanced Config
        private const val KEY_SHERPA_TTS_ZIPVOICE_THREADS = "sherpa_tts_zipvoice_threads"
        private const val KEY_SHERPA_TTS_ZIPVOICE_SAMPLE_RATE = "sherpa_tts_zipvoice_sample_rate"
        private const val KEY_SHERPA_TTS_CUSTOM_PROMPT_TEXT = "sherpa_tts_custom_prompt_text"
        private const val KEY_SHERPA_TTS_CUSTOM_PROMPT_PATH = "sherpa_tts_custom_prompt_path"
        private const val KEY_SHERPA_TTS_ZIPVOICE_MIC_DUR = "sherpa_tts_zipvoice_mic_dur"
        
        const val DEFAULT_TRANSCRIPT_ENABLED = false
        const val DEFAULT_TRANSCRIPT_SOURCE = "mic"
        const val DEFAULT_TRANSCRIPT_ENGINE = "android" // SpeechRecognizer = okamžitý, whisper = přesný offline
        
        const val TRANSCRIPT_ENGINE_SHERPA = "sherpa"

        // Whisper Sliding Window Config (Engine B — Continuous Overlapping Chunking)
        private const val KEY_WHISPER_HISTORY_SEC = "whisper_history_sec"
        private const val KEY_WHISPER_PROCESSING_INTERVAL_MS = "whisper_processing_interval_ms"
        private const val KEY_WHISPER_LANGUAGE = "whisper_language"
        private const val KEY_WHISPER_N_THREADS = "whisper_n_threads"
        private const val KEY_WHISPER_SOFTWARE_GAIN = "whisper_software_gain"

        const val DEFAULT_WHISPER_HISTORY_SEC = 3000L
        const val DEFAULT_WHISPER_PROCESSING_INTERVAL_MS = 500L
        const val DEFAULT_WHISPER_LANGUAGE = "cs"
        const val DEFAULT_WHISPER_N_THREADS = 4
        const val DEFAULT_WHISPER_SOFTWARE_GAIN = 1.0f

        // Sherpa-ONNX Sliding Window Config (Engine C)
        private const val KEY_SHERPA_HISTORY_SEC = "sherpa_history_sec"
        private const val KEY_SHERPA_PROCESSING_INTERVAL_MS = "sherpa_processing_interval_ms"
        private const val KEY_SHERPA_LANGUAGE = "sherpa_language"
        private const val KEY_SHERPA_N_THREADS = "sherpa_n_threads"
        private const val KEY_SHERPA_SOFTWARE_GAIN = "sherpa_software_gain"

        const val DEFAULT_SHERPA_HISTORY_SEC = 3000L
        const val DEFAULT_SHERPA_PROCESSING_INTERVAL_MS = 500L
        const val DEFAULT_SHERPA_LANGUAGE = "cs"
        const val DEFAULT_SHERPA_N_THREADS = 1
        const val DEFAULT_SHERPA_SOFTWARE_GAIN = 1.0f

        private const val KEY_WHISPER_ACCELERATOR_MODE = "whisper_accelerator_mode"
        private const val KEY_SHERPA_ACCELERATOR_MODE = "sherpa_accelerator_mode"
        const val DEFAULT_WHISPER_ACCELERATOR_MODE = "CPU"
        const val DEFAULT_SHERPA_ACCELERATOR_MODE = "CPU"

        // Summarization Backend Selection
        private const val KEY_SUMMARIZATION_BACKEND = "summarization_backend" // "aicore" | "mlkit" | "heuristic" | "llm"
        const val DEFAULT_SUMMARIZATION_BACKEND = "mlkit"

        // NLP Multilang & Translation
        private const val KEY_TRANSLATE_ENABLED = "nlp_translate_enabled"
        const val DEFAULT_TRANSLATE_ENABLED = true
        private const val KEY_TRANSLATE_AUTO_DOWNLOAD = "nlp_translate_auto_download"
        const val DEFAULT_TRANSLATE_AUTO_DOWNLOAD = false

        // Jazyky překladu (Multi↔Multi) — uživatelův jazyk a cílový cizí jazyk
        private const val KEY_TRANSLATE_MY_LANG    = "nlp_translate_my_lang"
        private const val KEY_TRANSLATE_FOREIGN_LANG = "nlp_translate_foreign_lang"
        const val DEFAULT_TRANSLATE_MY_LANG      = "cs"  // Czeština (výchozí)
        const val DEFAULT_TRANSLATE_FOREIGN_LANG = "en"  // Angličtina (výchozí)

        // NLP Sentiment Analysis
        private const val KEY_SENTIMENT_ENABLED = "nlp_sentiment_enabled"
        const val DEFAULT_SENTIMENT_ENABLED = false

        // NLP Smart Reply
        private const val KEY_SMART_REPLY_ENABLED = "nlp_smart_reply_enabled"
        const val DEFAULT_SMART_REPLY_ENABLED = true

        // GenAI Premium
        private const val KEY_GENAI_PROOFREAD_ENABLED = "nlp_genai_proofread_enabled"
        const val DEFAULT_GENAI_PROOFREAD_ENABLED = false
        private const val KEY_GENAI_REWRITE_ENABLED = "nlp_genai_rewrite_enabled"
        const val DEFAULT_GENAI_REWRITE_ENABLED = false

        private const val KEY_NLP_WEIGHT_TASKS = "nlp_weight_tasks"
        private const val KEY_NLP_WEIGHT_SLANG = "nlp_weight_slang"
        private const val KEY_NLP_WEIGHT_TECH = "nlp_weight_tech"
        private const val KEY_NLP_WEIGHT_URGENCY = "nlp_weight_urgency"

        private const val DEFAULT_NLP_WEIGHT_TASKS = 2.5f
        private const val DEFAULT_NLP_WEIGHT_SLANG = 2.0f
        private const val DEFAULT_NLP_WEIGHT_TECH = 1.5f
        private const val DEFAULT_NLP_WEIGHT_URGENCY = 3.0f

        // Rolling Summary Config (Tier 2.5)
        private const val KEY_TRANSCRIPT_ROLLING_MINUTES = "transcript_rolling_minutes"
        private const val KEY_TRANSCRIPT_MAX_BUFFER_CHARS = "transcript_max_buffer_chars"
        const val DEFAULT_TRANSCRIPT_ROLLING_MINUTES = 3
        const val DEFAULT_TRANSCRIPT_MAX_BUFFER_CHARS = 30000

        // Dictaphone Template Persistence
        private const val KEY_LAST_DICTAPHONE_TEMPLATE = "last_dictaphone_template_id"

        // TTS Notifikace (AgentCoordinator perception reactions) — default OFF (ticho)
        private const val KEY_PERCEPTION_TTS_SCENE_CHANGE = "perception_tts_scene_change"
        private const val KEY_PERCEPTION_TTS_CRITICAL_AUDIO = "perception_tts_critical_audio"
        private const val KEY_PERCEPTION_SCENE_MIN_NEW_ENTITIES = "perception_scene_min_new_entities"

        const val DEFAULT_PERCEPTION_TTS_SCENE_CHANGE = false // Default OFF — ticho
        const val DEFAULT_PERCEPTION_TTS_CRITICAL_AUDIO = false // Default OFF — ticho
        const val DEFAULT_PERCEPTION_SCENE_MIN_NEW_ENTITIES = 3

        // ==================== AGENT WORKER SETTINGS ====================
        private const val KEY_SCREEN_PARSER_MODE = "screen_parser_mode"

        // ScreenParser — UI tree traversal limits
        private const val KEY_SCREEN_PARSER_MAX_DEPTH = "screen_parser_max_depth"
        private const val KEY_SCREEN_PARSER_MAX_ELEMENTS = "screen_parser_max_elements"
        const val DEFAULT_SCREEN_PARSER_MAX_DEPTH = 50
        const val DEFAULT_SCREEN_PARSER_MAX_ELEMENTS = 500

        // SafetyGuard — anti-loop protection
        private const val KEY_SAFETY_GUARD_ENABLED = "safety_guard_enabled"
        private const val KEY_SAFETY_GUARD_MAX_HISTORY = "safety_guard_max_history"
        private const val KEY_SAFETY_GUARD_LOOP_THRESHOLD = "safety_guard_loop_threshold"
        const val DEFAULT_SAFETY_GUARD_ENABLED = true
        const val DEFAULT_SAFETY_GUARD_MAX_HISTORY = 5
        const val DEFAULT_SAFETY_GUARD_LOOP_THRESHOLD = 3

        // AppLayoutCache — UI cache limits
        private const val KEY_APP_CACHE_MAX_APPS = "app_cache_max_apps"
        private const val KEY_APP_CACHE_MAX_ELEMENTS = "app_cache_max_elements_per_app"
        const val DEFAULT_APP_CACHE_MAX_APPS = 20
        const val DEFAULT_APP_CACHE_MAX_ELEMENTS = 60

        // FloatingAgentButton — visual + interaction + OLED protection
        private const val KEY_FLOATING_BUTTON_SIZE_DP = "floating_button_size_dp"
        private const val KEY_FLOATING_BUTTON_CLICK_THRESHOLD_MS = "floating_button_click_threshold_ms"
        private const val KEY_FLOATING_BUTTON_MOVE_THRESHOLD_PX = "floating_button_move_threshold_px"
        private const val KEY_FLOATING_BUTTON_AUTO_HIDE_SEC = "floating_button_auto_hide_sec"
        private const val KEY_FLOATING_BUTTON_LOCKSCREEN_HIDE = "floating_button_lockscreen_hide"
        const val DEFAULT_FLOATING_BUTTON_SIZE_DP = 56
        const val DEFAULT_FLOATING_BUTTON_CLICK_THRESHOLD_MS = 200
        const val DEFAULT_FLOATING_BUTTON_MOVE_THRESHOLD_PX = 10
        const val DEFAULT_FLOATING_BUTTON_AUTO_HIDE_SEC = 0 // 0 = vypnuto (nikdy nezmizí)
        const val DEFAULT_FLOATING_BUTTON_LOCKSCREEN_HIDE = true // Default: skrýt na lock screen

        // AgentNotifier - Floating Bubbles (Visual Notifications)
        private const val KEY_FLOATING_NOTIFY_ENABLED = "floating_notify_enabled"
        private const val KEY_FLOATING_NOTIFY_STYLE = "floating_notify_style"
        private const val KEY_FLOATING_NOTIFY_POSITION = "floating_notify_position"
        private const val KEY_FLOATING_NOTIFY_SHOW_WARNINGS = "floating_notify_show_warnings"
        private const val KEY_FLOATING_NOTIFY_SHOW_VISION = "floating_notify_show_vision"
        private const val KEY_FLOATING_NOTIFY_SHOW_AUDIO = "floating_notify_show_audio"

        const val DEFAULT_FLOATING_NOTIFY_ENABLED = true
        const val DEFAULT_FLOATING_NOTIFY_STYLE = 0 // 0=Emoji+Text, 1=Emoji, 2=Text
        const val DEFAULT_FLOATING_NOTIFY_POSITION = 0 // 0=TopCenter, 1=TopLeft, 2=TopRight, 3=BotLeft, 4=BotRight
        const val DEFAULT_FLOATING_NOTIFY_SHOW_WARNINGS = true
        const val DEFAULT_FLOATING_NOTIFY_SHOW_VISION = false
        const val DEFAULT_FLOATING_NOTIFY_SHOW_AUDIO = true

        // Kalman Tracker (MotionEstimator)
        private const val KEY_KALMAN_PROCESS_NOISE = "kalman_process_noise"
        private const val KEY_KALMAN_MEASUREMENT_NOISE = "kalman_measurement_noise"
        private const val KEY_KALMAN_STD_WEIGHT_POSITION = "kalman_std_weight_position"
        private const val KEY_KALMAN_STD_WEIGHT_VELOCITY = "kalman_std_weight_velocity"
        
        const val DEFAULT_KALMAN_PROCESS_NOISE = 0.001f
        const val DEFAULT_KALMAN_MEASUREMENT_NOISE = 0.02f
        const val DEFAULT_KALMAN_STD_WEIGHT_POSITION = 0.05f
        const val DEFAULT_KALMAN_STD_WEIGHT_VELOCITY = 0.00625f
    }

    // --- Helpers pro bezpečné načítání typů ---
    private fun getFloatSafe(key: String, def: Float): Float {
        return try {
            prefs.getFloat(key, def)
        } catch (_: ClassCastException) {
            prefs.getString(key, def.toString())?.toFloatOrNull() ?: def
        }
    }

    private fun getIntSafe(key: String, def: Int): Int {
        return try {
            prefs.getInt(key, def)
        } catch (_: ClassCastException) {
            prefs.getString(key, def.toString())?.toIntOrNull() ?: def
        }
    }

    private fun getLongSafe(key: String, def: Long): Long {
        return try {
            prefs.getLong(key, def)
        } catch (_: ClassCastException) {
            prefs.getString(key, def.toString())?.toLongOrNull() ?: def
        }
    }

    // --- General Properties ---
    var systemMessage: String
        get() =
                prefs.getString(KEY_SYSTEM_MESSAGE, DEFAULT_SYSTEM_MESSAGE)
                        ?: DEFAULT_SYSTEM_MESSAGE
        set(value) = prefs.edit { putString(KEY_SYSTEM_MESSAGE, value) }

    var showSystemMessage: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SYSTEM_MESSAGE, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_SYSTEM_MESSAGE, value) }

    var maxImportSize: Long
        get() = getLongSafe(KEY_MAX_IMPORT_SIZE, DEFAULT_MAX_IMPORT_SIZE)
        set(value) = prefs.edit { putLong(KEY_MAX_IMPORT_SIZE, value) }

    // --- Display Properties ---
    var showStats: Boolean
        get() = prefs.getBoolean(KEY_SHOW_STATS, true)
        set(v) = prefs.edit { putBoolean(KEY_SHOW_STATS, v) }

    var showTokenCount: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TOKEN_COUNT, false)
        set(v) = prefs.edit { putBoolean(KEY_SHOW_TOKEN_COUNT, v) }

    var showGenTime: Boolean
        get() = prefs.getBoolean(KEY_SHOW_GEN_TIME, false)
        set(v) = prefs.edit { putBoolean(KEY_SHOW_GEN_TIME, v) }

    var showThoughtInProgress: Boolean
        get() = prefs.getBoolean(KEY_SHOW_THOUGHTS, false)
        set(v) = prefs.edit { putBoolean(KEY_SHOW_THOUGHTS, v) }

    var keepStatsVisible: Boolean
        get() = prefs.getBoolean(KEY_KEEP_STATS, false)
        set(v) = prefs.edit { putBoolean(KEY_KEEP_STATS, v) }

    var showMicrophone: Boolean
        get() = prefs.getBoolean(KEY_SHOW_MIC, false)
        set(v) = prefs.edit { putBoolean(KEY_SHOW_MIC, v) }

    var renderMarkdown: Boolean
        get() = prefs.getBoolean(KEY_RENDER_MARKDOWN, false)
        set(v) = prefs.edit { putBoolean(KEY_RENDER_MARKDOWN, v) }

    var disableAutoScroll: Boolean
        get() = prefs.getBoolean(KEY_DISABLE_AUTO_SCROLL, false)
        set(v) = prefs.edit { putBoolean(KEY_DISABLE_AUTO_SCROLL, v) }

    var alwaysShowSidebar: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_SHOW_SIDEBAR, false)
        set(v) = prefs.edit { putBoolean(KEY_ALWAYS_SHOW_SIDEBAR, v) }

    var autoShowSidebar: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SHOW_SIDEBAR, true)
        set(v) = prefs.edit { putBoolean(KEY_AUTO_SHOW_SIDEBAR, v) }

    var enableBubbleCopy: Boolean
        get() = prefs.getBoolean(KEY_BUBBLE_COPY, true)
        set(v) = prefs.edit { putBoolean(KEY_BUBBLE_COPY, v) }

    var backgroundGeneration: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_GEN, false)
        set(v) = prefs.edit { putBoolean(KEY_BACKGROUND_GEN, v) }

    var chatStyle: String
        get() = prefs.getString(KEY_CHAT_STYLE, "oem") ?: "oem"
        set(v) = prefs.edit { putString(KEY_CHAT_STYLE, v) }

    // --- Memory & Performance ---
    var useMmap: Boolean
        get() = prefs.getBoolean(KEY_USE_MMAP, true)
        set(v) = prefs.edit { putBoolean(KEY_USE_MMAP, v) }

    var kvCacheMode: Int
        get() = getIntSafe(KEY_KV_CACHE_MODE, 1) // Default 1 (Q8_0 - Balanced)
        set(v) = prefs.edit { putInt(KEY_KV_CACHE_MODE, v) }

    var contextSize: Int
        get() = getIntSafe(KEY_CONTEXT_SIZE, 0) // Default 0 (Auto/Model Default)
        set(v) = prefs.edit { putInt(KEY_CONTEXT_SIZE, v) }

    var batchSize: Int
        get() = getIntSafe(KEY_BATCH_SIZE, 512) // Default 512
        set(v) = prefs.edit { putInt(KEY_BATCH_SIZE, v) }

    var ubatchSize: Int
        get() = getIntSafe(KEY_UBATCH_SIZE, 512) // Default 512
        set(v) = prefs.edit { putInt(KEY_UBATCH_SIZE, v) }

    var flashAttention: Boolean
        get() = prefs.getBoolean(KEY_FLASH_ATTN, true) // Default true
        set(v) = prefs.edit { putBoolean(KEY_FLASH_ATTN, v) }

    // --- Fullscreen Mode ---
    var hideStatusBar: Boolean
        get() = prefs.getBoolean(KEY_HIDE_STATUS_BAR, false)
        set(v) = prefs.edit { putBoolean(KEY_HIDE_STATUS_BAR, v) }

    // --- Voice Services ---
    var isSttEnabled: Boolean
        get() = prefs.getBoolean(KEY_STT_ENABLED, false)
        set(v) = prefs.edit { putBoolean(KEY_STT_ENABLED, v) }

    var isTtsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, false)
        set(v) = prefs.edit { putBoolean(KEY_TTS_ENABLED, v) }

    // --- Sampling Properties (Public Vars) ---
    var temperature: Float
        get() =
                getFloatSafe(
                        SamplingConstants.KEY_TEMPERATURE,
                        SamplingConstants.DEFAULT_TEMPERATURE
                )
        set(v) = prefs.edit { putFloat(SamplingConstants.KEY_TEMPERATURE, v) }

    var dynTempRange: Float
        get() =
                getFloatSafe(
                        SamplingConstants.KEY_DYN_TEMP_RANGE,
                        SamplingConstants.DEFAULT_DYN_TEMP_RANGE
                )
        set(v) = prefs.edit { putFloat(SamplingConstants.KEY_DYN_TEMP_RANGE, v) }

    var dynTempExp: Float
        get() =
                getFloatSafe(
                        SamplingConstants.KEY_DYN_TEMP_EXPONENT,
                        SamplingConstants.DEFAULT_DYN_TEMP_EXPONENT
                )
        set(v) = prefs.edit { putFloat(SamplingConstants.KEY_DYN_TEMP_EXPONENT, v) }

    var topK: Int
        get() = getIntSafe(SamplingConstants.KEY_TOP_K, SamplingConstants.DEFAULT_TOP_K)
        set(v) = prefs.edit { putInt(SamplingConstants.KEY_TOP_K, v) }

    var topP: Float
        get() = getFloatSafe(SamplingConstants.KEY_TOP_P, SamplingConstants.DEFAULT_TOP_P)
        set(v) = prefs.edit { putFloat(SamplingConstants.KEY_TOP_P, v) }

    var minP: Float
        get() = getFloatSafe(SamplingConstants.KEY_MIN_P, SamplingConstants.DEFAULT_MIN_P)
        set(v) = prefs.edit { putFloat(SamplingConstants.KEY_MIN_P, v) }

    var xtcProb: Float
        get() =
                getFloatSafe(
                        SamplingConstants.KEY_XTC_PROBABILITY,
                        SamplingConstants.DEFAULT_XTC_PROBABILITY
                )
        set(v) = prefs.edit { putFloat(SamplingConstants.KEY_XTC_PROBABILITY, v) }

    var xtcThreshold: Float
        get() =
                getFloatSafe(
                        SamplingConstants.KEY_XTC_THRESHOLD,
                        SamplingConstants.DEFAULT_XTC_THRESHOLD
                )
        set(v) = prefs.edit { putFloat(SamplingConstants.KEY_XTC_THRESHOLD, v) }

    var typicalP: Float
        get() = getFloatSafe(SamplingConstants.KEY_TYPICAL_P, SamplingConstants.DEFAULT_TYPICAL_P)
        set(v) = prefs.edit { putFloat(SamplingConstants.KEY_TYPICAL_P, v) }

    // --- New Penalty Properties (Added) ---
    var penaltyRepeat: Float
        get() =
                getFloatSafe(
                        SamplingConstants.KEY_PENALTY_REPEAT,
                        SamplingConstants.DEFAULT_PENALTY_REPEAT
                )
        set(v) = prefs.edit { putFloat(SamplingConstants.KEY_PENALTY_REPEAT, v) }

    var penaltyFreq: Float
        get() =
                getFloatSafe(
                        SamplingConstants.KEY_PENALTY_FREQ,
                        SamplingConstants.DEFAULT_PENALTY_FREQ
                )
        set(v) = prefs.edit { putFloat(SamplingConstants.KEY_PENALTY_FREQ, v) }

    var penaltyPresent: Float
        get() =
                getFloatSafe(
                        SamplingConstants.KEY_PENALTY_PRESENT,
                        SamplingConstants.DEFAULT_PENALTY_PRESENT
                )
        set(v) = prefs.edit { putFloat(SamplingConstants.KEY_PENALTY_PRESENT, v) }

    var penaltyLastN: Int
        get() =
                getIntSafe(
                        SamplingConstants.KEY_PENALTY_LAST_N,
                        SamplingConstants.DEFAULT_PENALTY_LAST_N
                )
        set(v) = prefs.edit { putInt(SamplingConstants.KEY_PENALTY_LAST_N, v) }

    var maxTokens: Int
        get() = getIntSafe(SamplingConstants.KEY_MAX_TOKENS, SamplingConstants.DEFAULT_MAX_TOKENS)
        set(v) = prefs.edit { putInt(SamplingConstants.KEY_MAX_TOKENS, v) }

    var samplersSequence: String
        get() =
                prefs.getString(
                        SamplingConstants.KEY_SAMPLERS_SEQUENCE,
                        SamplingConstants.DEFAULT_SAMPLERS_SEQUENCE
                )
                        ?: SamplingConstants.DEFAULT_SAMPLERS_SEQUENCE
        set(v) = prefs.edit { putString(SamplingConstants.KEY_SAMPLERS_SEQUENCE, v) }

    var backendSampling: Boolean
        get() =
                prefs.getBoolean(
                        SamplingConstants.KEY_BACKEND_SAMPLING,
                        SamplingConstants.DEFAULT_BACKEND_SAMPLING
                )
        set(v) = prefs.edit { putBoolean(SamplingConstants.KEY_BACKEND_SAMPLING, v) }

    // --- Mirostat Properties ---
    var mirostatMode: Int
        get() =
                getIntSafe(
                        SamplingConstants.KEY_MIROSTAT_MODE,
                        SamplingConstants.DEFAULT_MIROSTAT_MODE
                )
        set(v) = prefs.edit { putInt(SamplingConstants.KEY_MIROSTAT_MODE, v) }

    var mirostatTau: Float
        get() =
                getFloatSafe(
                        SamplingConstants.KEY_MIROSTAT_TAU,
                        SamplingConstants.DEFAULT_MIROSTAT_TAU
                )
        set(v) = prefs.edit { putFloat(SamplingConstants.KEY_MIROSTAT_TAU, v) }

    var mirostatEta: Float
        get() =
                getFloatSafe(
                        SamplingConstants.KEY_MIROSTAT_ETA,
                        SamplingConstants.DEFAULT_MIROSTAT_ETA
                )
        set(v) = prefs.edit { putFloat(SamplingConstants.KEY_MIROSTAT_ETA, v) }

    fun loadSamplingConfig(): SamplingConfig {
        return SamplingConfig(
                temperature = temperature,
                dynamicTempRange = dynTempRange,
                dynamicTempExponent = dynTempExp,
                topK = topK,
                topP = topP,
                minP = minP,
                typicalP = typicalP,
                xtcProbability = xtcProb,
                xtcThreshold = xtcThreshold,
                maxTokens = maxTokens,
                penaltyRepeat = penaltyRepeat,
                penaltyFreq = penaltyFreq,
                penaltyPresent = penaltyPresent,
                penaltyLastN = penaltyLastN,
                mirostat = mirostatMode,
                mirostatTau = mirostatTau,
                mirostatEta = mirostatEta,
                samplersSequence = samplersSequence,
                useBackendSampling = backendSampling
        )
    }
    // --- AI Agent Settings ---
    var activeVisionModelId: String
        get() = prefs.getString(KEY_VISION_MODEL_ID, "") ?: ""
        set(value) = prefs.edit { putString(KEY_VISION_MODEL_ID, value) }

    var isNativeVisionModel: Boolean
        get() = prefs.getBoolean(KEY_VISION_NATIVE_MODEL, false)
        set(value) = prefs.edit { putBoolean(KEY_VISION_NATIVE_MODEL, value) }

    var isAgentEnabled: Boolean
        get() = prefs.getBoolean(KEY_AGENT_ENABLED, false)
        set(v) = prefs.edit { putBoolean(KEY_AGENT_ENABLED, v) }

    var isAgentTtsEnabled: Boolean
        get() = prefs.getBoolean(KEY_AGENT_TTS_ENABLED, true) // Default ON for agent
        set(v) = prefs.edit { putBoolean(KEY_AGENT_TTS_ENABLED, v) }

    var isAgentSttEnabled: Boolean
        get() = prefs.getBoolean(KEY_AGENT_STT_ENABLED, true) // Default ON for agent
        set(v) = prefs.edit { putBoolean(KEY_AGENT_STT_ENABLED, v) }

    var agentVoiceFeedback: Boolean
        get() = prefs.getBoolean(KEY_AGENT_VOICE_FEEDBACK, true) // Voice feedback enabled
        set(v) = prefs.edit { putBoolean(KEY_AGENT_VOICE_FEEDBACK, v) }

    var irisMotionTargetSnap: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_TARGET_SNAP, DEFAULT_IRISMOTION_TARGET_SNAP)
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_TARGET_SNAP, v) }

    var irisMotionSphericalCompensation: Boolean
        get() =
                prefs.getBoolean(
                        KEY_IRISMOTION_SPHERICAL_COMPENSATION,
                        DEFAULT_IRISMOTION_SPHERICAL_COMPENSATION
                )
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_SPHERICAL_COMPENSATION, v) }

    // --- SmartVision Settings ---

    var isSmartVisionBoundingBoxEnabled: Boolean
        get() = prefs.getBoolean("smartvision_bounding_boxes_enabled", false)
        set(v) = prefs.edit { putBoolean("smartvision_bounding_boxes_enabled", v) }

    var smartVisionLiveFps: Int
        get() = prefs.getInt("smartvision_live_fps", 1)
        set(v) = prefs.edit { putInt("smartvision_live_fps", v) }

    var isSmartVisionCameraLiveEnabled: Boolean
        get() = prefs.getBoolean("smartvision_camera_live_enabled", false)
        set(v) = prefs.edit { putBoolean("smartvision_camera_live_enabled", v) }

    // Náhled příloh v chatu (obrázky/video/audio thumbnails).
    // Výchozí OFF = zero allocation, low RAM. Zapnutí povolí Bitmap thumbnaily v bublinách.
    var isMediaPreviewEnabled: Boolean
        get() = prefs.getBoolean("media_preview_enabled", false)
        set(v) = prefs.edit { putBoolean("media_preview_enabled", v) }

    // --- Perception Engine Settings ---

    /** Master switch: zapne/vypne PerceptionEngine (Vision — Track B, MediaPipe OD + OCR) */
    var isPerceptionEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERCEPTION_ENABLED, DEFAULT_PERCEPTION_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_PERCEPTION_ENABLED, v) }

    /** Object Detection engine (MediaPipe) — On-Demand přes Submenu. Default ON. */
    var isObjectDetectionEnabled: Boolean
        get() = prefs.getBoolean("perception_object_detection_enabled", true)
        set(v) = prefs.edit { putBoolean("perception_object_detection_enabled", v) }

    /** Camera Preview (Zero-Leak) — On-Demand přes Submenu. Default OFF. */
    var isCameraPreviewEnabled: Boolean
        get() = prefs.getBoolean("perception_camera_preview_enabled", false)
        set(v) = prefs.edit { putBoolean("perception_camera_preview_enabled", v) }

    /**
     * OD Model Manager — UUID aktivního importovaného modelu.
     * Prázdný string nebo BUNDLED_MODEL_ID = použít bundled efficientdet_lite0.tflite z assets.
     */
    var activeOdModelId: String
        get() = prefs.getString("od_active_model_id", "") ?: ""
        set(v) = prefs.edit { putString("od_active_model_id", v) }

    /** Object Detection minimální confidence (0.1–0.9). Default 0.4. */
    var perceptionOdConfidence: Float
        get() = getFloatSafe(KEY_PERCEPTION_OD_CONFIDENCE, DEFAULT_PERCEPTION_OD_CONFIDENCE).coerceIn(0.1f, 0.9f)
        set(v) = prefs.edit { putFloat(KEY_PERCEPTION_OD_CONFIDENCE, v.coerceIn(0.1f, 0.9f)) }

    /** Max OD výsledků per frame (1–50). Default 15. */
    var perceptionOdMaxResults: Int
        get() = getIntSafe(KEY_PERCEPTION_OD_MAX_RESULTS, DEFAULT_PERCEPTION_OD_MAX_RESULTS).coerceIn(1, 50)
        set(v) = prefs.edit { putInt(KEY_PERCEPTION_OD_MAX_RESULTS, v.coerceIn(1, 50)) }

    /** OD inference timeout (500–10000ms). Default 2000. */
    var perceptionOdTimeoutMs: Long
        get() = getLongSafe(KEY_PERCEPTION_OD_TIMEOUT_MS, DEFAULT_PERCEPTION_OD_TIMEOUT_MS).coerceIn(500L, 10000L)
        set(v) = prefs.edit { putLong(KEY_PERCEPTION_OD_TIMEOUT_MS, v.coerceIn(500L, 10000L)) }

    /** OCR inference timeout (500–10000ms). Default 3000. */
    var perceptionOcrTimeoutMs: Long
        get() = getLongSafe(KEY_PERCEPTION_OCR_TIMEOUT_MS, DEFAULT_PERCEPTION_OCR_TIMEOUT_MS).coerceIn(500L, 10000L)
        set(v) = prefs.edit { putLong(KEY_PERCEPTION_OCR_TIMEOUT_MS, v.coerceIn(500L, 10000L)) }

    /** OCR max text length (64–2048). Default 512. */
    var perceptionOcrMaxLength: Int
        get() = getIntSafe(KEY_PERCEPTION_OCR_MAX_LENGTH, DEFAULT_PERCEPTION_OCR_MAX_LENGTH).coerceIn(64, 2048)
        set(v) = prefs.edit { putInt(KEY_PERCEPTION_OCR_MAX_LENGTH, v.coerceIn(64, 2048)) }

    /** CLAHE Mathematical Pre-Processor pro zvýraznění hran na zašuměném zoomu. */
    var isClahePreProcessorEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERCEPTION_CLAHE_PREPROCESSOR, DEFAULT_PERCEPTION_CLAHE_PREPROCESSOR)
        set(v) = prefs.edit { putBoolean(KEY_PERCEPTION_CLAHE_PREPROCESSOR, v) }

    /** Hlavní přepínač pro Night Mode (Low-Light Enhancement) pro pre-processing obrazu. */
    var isNightModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_MODE_ENABLED, DEFAULT_NIGHT_MODE_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_NIGHT_MODE_ENABLED, v) }

    /** ID aktivního Night Mode pluginu (např. night_retinex, night_tflite_mobileie). */
    var activeNightModePluginId: String
        get() = prefs.getString(KEY_NIGHT_MODE_PLUGIN, DEFAULT_NIGHT_MODE_PLUGIN) ?: DEFAULT_NIGHT_MODE_PLUGIN
        set(v) = prefs.edit { putString(KEY_NIGHT_MODE_PLUGIN, v) }

    /** Cesta k vlastnímu .tflite modelu pro noční vidění. */
    var customNightModeModelPath: String?
        get() = prefs.getString(KEY_NIGHT_MODE_CUSTOM_MODEL, null)
        set(v) {
            if (v == null) prefs.edit { remove(KEY_NIGHT_MODE_CUSTOM_MODEL) }
            else prefs.edit { putString(KEY_NIGHT_MODE_CUSTOM_MODEL, v) }
        }

    /** Zapíná dynamické roztažení kontrastu (Auto-Level) pro Night Mode. */
    var nightModeAutoContrast: Boolean
        get() = prefs.getBoolean("night_mode_auto_contrast", false)
        set(v) = prefs.edit { putBoolean("night_mode_auto_contrast", v) }

    /** Ruční nastavení vstupního škálování modelu (Input Scale) - např. 255.0 pro [0, 1] nebo 127.5 pro [-1, 1]. */
    var nightModeInputScale: Float
        get() = prefs.getFloat("night_mode_input_scale", 255.0f)
        set(v) = prefs.edit { putFloat("night_mode_input_scale", v) }

    /** Ruční nastavení vstupního posunu modelu (Input Offset) - např. 0.0 pro [0, 1] nebo 1.0 pro [-1, 1]. */
    var nightModeInputOffset: Float
        get() = prefs.getFloat("night_mode_input_offset", 0.0f)
        set(v) = prefs.edit { putFloat("night_mode_input_offset", v) }

    var nightModeOutputScale: Float
        get() = prefs.getFloat("night_mode_output_scale", 255.0f)
        set(v) = prefs.edit { putFloat("night_mode_output_scale", v) }

    var nightModeOutputOffset: Float
        get() = prefs.getFloat("night_mode_output_offset", 0.0f)
        set(v) = prefs.edit { putFloat("night_mode_output_offset", v) }

    var academicYuvInputScale: Float
        get() = prefs.getFloat("academic_yuv_input_scale", 255.0f)
        set(v) = prefs.edit { putFloat("academic_yuv_input_scale", v) }

    var academicYuvInputOffset: Float
        get() = prefs.getFloat("academic_yuv_input_offset", 0.0f)
        set(v) = prefs.edit { putFloat("academic_yuv_input_offset", v) }

    var academicYuvOutputScale: Float
        get() = prefs.getFloat("academic_yuv_output_scale", 255.0f)
        set(v) = prefs.edit { putFloat("academic_yuv_output_scale", v) }

    var academicYuvOutputOffset: Float
        get() = prefs.getFloat("academic_yuv_output_offset", 0.0f)
        set(v) = prefs.edit { putFloat("academic_yuv_output_offset", v) }

    /** Přepíná mezi CPU a GPU zpracováním pro Academic YUV Engine */
    var academicYuvHardwareAcceleration: String
        get() = prefs.getString("academic_yuv_hardware_acceleration", "CPU") ?: "CPU"
        set(v) = prefs.edit { putString("academic_yuv_hardware_acceleration", v) }

    /** Zda se mají použít ruční hodnoty škálování (sliders) místo automatických/databázových. */
    var nightModeManualScaling: Boolean
        get() = prefs.getBoolean("night_mode_manual_scaling", false)
        set(v) = prefs.edit { putBoolean("night_mode_manual_scaling", v) }

    /** Přepíná celý systém z optimalizovaného RGBA8888 Bitmap enginu do syrového akademického YUV_420_888 módu. */
    var academicNightVisionMode: Boolean
        get() = prefs.getBoolean("academic_night_vision_mode", false)
        set(v) = prefs.edit { putBoolean("academic_night_vision_mode", v) }

    var isSkyModeEnabled: Boolean
        get() = prefs.getBoolean("sky_mode_enabled", false)
        set(v) = prefs.edit { putBoolean("sky_mode_enabled", v) }

    var isNightSkyModeEnabled: Boolean
        get() = prefs.getBoolean("night_sky_mode_enabled", false)
        set(v) = prefs.edit { putBoolean("night_sky_mode_enabled", v) }

    /** Fast Unsharp Mask pre-processor (Low-SNR hrany). */
    var isUnsharpMaskEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERCEPTION_UNSHARP_MASK, DEFAULT_PERCEPTION_UNSHARP_MASK)
        set(v) = prefs.edit { putBoolean(KEY_PERCEPTION_UNSHARP_MASK, v) }

    /** Logarithmic Stretch pre-processor (HDR pro temné/vzdálené objekty). */
    var isLogStretchEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERCEPTION_LOG_STRETCH, DEFAULT_PERCEPTION_LOG_STRETCH)
        set(v) = prefs.edit { putBoolean(KEY_PERCEPTION_LOG_STRETCH, v) }

    /** Thermal moderate frame skip (1–20). Default 3. */
    var perceptionThermalSkip: Int
        get() = getIntSafe(KEY_PERCEPTION_THERMAL_SKIP, DEFAULT_PERCEPTION_THERMAL_SKIP).coerceIn(1, 20)
        set(v) = prefs.edit { putInt(KEY_PERCEPTION_THERMAL_SKIP, v.coerceIn(1, 20)) }

    var perceptionThermalGate1: Int
        get() = getIntSafe(KEY_PERCEPTION_THERMAL_GATE_1, DEFAULT_PERCEPTION_THERMAL_GATE_1)
        set(v) = prefs.edit { putInt(KEY_PERCEPTION_THERMAL_GATE_1, v) }

    var perceptionThermalGate2: Int
        get() = getIntSafe(KEY_PERCEPTION_THERMAL_GATE_2, DEFAULT_PERCEPTION_THERMAL_GATE_2)
        set(v) = prefs.edit { putInt(KEY_PERCEPTION_THERMAL_GATE_2, v) }

    var perceptionThreadPriority: Int
        get() = getIntSafe(KEY_PERCEPTION_THREAD_PRIORITY, DEFAULT_PERCEPTION_THREAD_PRIORITY)
        set(v) = prefs.edit { putInt(KEY_PERCEPTION_THREAD_PRIORITY, v) }

    /** Track B bitmap resolution (160–4000px). Default 1024. */
    var perceptionResolution: Int
        get() = getIntSafe(KEY_PERCEPTION_RESOLUTION, DEFAULT_PERCEPTION_RESOLUTION).coerceIn(160, 4000)
        set(v) = prefs.edit { putInt(KEY_PERCEPTION_RESOLUTION, v.coerceIn(160, 4000)) }

    var perceptionAcceleratorMode: String
        get() = prefs.getString(KEY_PERCEPTION_ACCELERATOR_MODE, DEFAULT_PERCEPTION_ACCELERATOR_MODE) ?: DEFAULT_PERCEPTION_ACCELERATOR_MODE
        set(v) = prefs.edit { putString(KEY_PERCEPTION_ACCELERATOR_MODE, v) }

    var perceptionCpuThreads: Int
        get() = getIntSafe(KEY_PERCEPTION_CPU_THREADS, DEFAULT_PERCEPTION_CPU_THREADS).coerceIn(1, 8)
        set(v) = prefs.edit { putInt(KEY_PERCEPTION_CPU_THREADS, v.coerceIn(1, 8)) }

    /**
     * 0 = SQUASH (Fullscreen s deformací)
     * 1 = LETTERBOX (Zachování proporcí s pruhy)
     * 2 = CENTER CROP (Výřez 1:1 bez deformace, bez pruhů)
     */
    var perceptionAspectRatioMode: Int
        get() = getIntSafe(KEY_PERCEPTION_ASPECT_RATIO_MODE, DEFAULT_PERCEPTION_ASPECT_RATIO_MODE).coerceIn(0, 2)
        set(v) = prefs.edit { putInt(KEY_PERCEPTION_ASPECT_RATIO_MODE, v.coerceIn(0, 2)) }

    /** Master switch: zapne/vypne AudioPerceptionEngine (YAMNet) */
    var isAudioPerceptionEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERCEPTION_AUDIO_ENABLED, DEFAULT_PERCEPTION_AUDIO_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_PERCEPTION_AUDIO_ENABLED, v) }

    /** Audio detection minimální confidence (0.1–0.9). Default 0.3. */
    var perceptionAudioConfidence: Float
        get() = getFloatSafe(KEY_PERCEPTION_AUDIO_CONFIDENCE, DEFAULT_PERCEPTION_AUDIO_CONFIDENCE).coerceIn(0.1f, 0.9f)
        set(v) = prefs.edit { putFloat(KEY_PERCEPTION_AUDIO_CONFIDENCE, v.coerceIn(0.1f, 0.9f)) }

    /** Audio max results per inference (1–20). Default 5. */
    var perceptionAudioMaxResults: Int
        get() = getIntSafe(KEY_PERCEPTION_AUDIO_MAX_RESULTS, DEFAULT_PERCEPTION_AUDIO_MAX_RESULTS).coerceIn(1, 20)
        set(v) = prefs.edit { putInt(KEY_PERCEPTION_AUDIO_MAX_RESULTS, v.coerceIn(1, 20)) }

    /** Audio inference interval (250–5000ms). Default 1000. */
    var perceptionAudioIntervalMs: Long
        get() = getLongSafe(KEY_PERCEPTION_AUDIO_INTERVAL_MS, DEFAULT_PERCEPTION_AUDIO_INTERVAL_MS).coerceIn(250L, 5000L)
        set(v) = prefs.edit { putLong(KEY_PERCEPTION_AUDIO_INTERVAL_MS, v.coerceIn(250L, 5000L)) }

    /** Audio event cooldown per label (1000–30000ms). Default 5000. */
    var perceptionAudioCooldownMs: Long
        get() = getLongSafe(KEY_PERCEPTION_AUDIO_COOLDOWN_MS, DEFAULT_PERCEPTION_AUDIO_COOLDOWN_MS).coerceIn(1000L, 30000L)
        set(v) = prefs.edit { putLong(KEY_PERCEPTION_AUDIO_COOLDOWN_MS, v.coerceIn(1000L, 30000L)) }

    /** Aktivní YAMNet jazyková databáze (název bez .csv). Default "eng" (OEM). */
    var yamnetActiveLang: String
        get() = prefs.getString(KEY_YAMNET_ACTIVE_LANG, DEFAULT_YAMNET_ACTIVE_LANG) ?: DEFAULT_YAMNET_ACTIVE_LANG
        set(v) = prefs.edit { putString(KEY_YAMNET_ACTIVE_LANG, v) }

    /** TTS oznámení při SceneChange (nové objekty). Default OFF. */
    var perceptionTtsSceneChange: Boolean
        get() = prefs.getBoolean(KEY_PERCEPTION_TTS_SCENE_CHANGE, DEFAULT_PERCEPTION_TTS_SCENE_CHANGE)
        set(v) = prefs.edit { putBoolean(KEY_PERCEPTION_TTS_SCENE_CHANGE, v) }

    /** TTS varování při CRITICAL audio eventech. Default OFF. */
    var perceptionTtsCriticalAudio: Boolean
        get() = prefs.getBoolean(KEY_PERCEPTION_TTS_CRITICAL_AUDIO, DEFAULT_PERCEPTION_TTS_CRITICAL_AUDIO)
        set(v) = prefs.edit { putBoolean(KEY_PERCEPTION_TTS_CRITICAL_AUDIO, v) }

    /** Min nových entit pro TTS SceneChange (1–10). Default 3. */
    var perceptionSceneMinNewEntities: Int
        get() = getIntSafe(KEY_PERCEPTION_SCENE_MIN_NEW_ENTITIES, DEFAULT_PERCEPTION_SCENE_MIN_NEW_ENTITIES).coerceIn(1, 10)
        set(v) = prefs.edit { putInt(KEY_PERCEPTION_SCENE_MIN_NEW_ENTITIES, v.coerceIn(1, 10)) }

    // ==================== POSE ESTIMATION (BlazePose 33) ====================

    /** Master switch: Pose Estimation (MediaPipe PoseLandmarker, BlazePose 33). Default OFF — nezávislé na OD. */
    var isPoseEstimationEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERCEPTION_POSE_ENABLED, DEFAULT_PERCEPTION_POSE_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_PERCEPTION_POSE_ENABLED, v) }

    /** Min. confidence pro detekci/přítomnost/tracking pózy (0.1–0.9). Default 0.5. */
    var perceptionPoseConfidence: Float
        get() = getFloatSafe(KEY_PERCEPTION_POSE_CONFIDENCE, DEFAULT_PERCEPTION_POSE_CONFIDENCE).coerceIn(0.1f, 0.9f)
        set(v) = prefs.edit { putFloat(KEY_PERCEPTION_POSE_CONFIDENCE, v.coerceIn(0.1f, 0.9f)) }

    /** Max počet současně sledovaných osob v terénu (1–10 pro IZS / dronní průzkum). Default 1. */
    var perceptionPoseMaxPoses: Int
        get() = getIntSafe(KEY_PERCEPTION_POSE_MAX_POSES, DEFAULT_PERCEPTION_POSE_MAX_POSES).coerceIn(1, 10)
        set(v) = prefs.edit { putInt(KEY_PERCEPTION_POSE_MAX_POSES, v.coerceIn(1, 10)) }

    /** Model tier: "lite" (bundled, rychlý) | "full" | "heavy" (nutno importovat ručně do assets). */
    var perceptionPoseModelComplexity: String
        get() = prefs.getString(KEY_PERCEPTION_POSE_MODEL_COMPLEXITY, DEFAULT_PERCEPTION_POSE_MODEL_COMPLEXITY)
            ?: DEFAULT_PERCEPTION_POSE_MODEL_COMPLEXITY
        set(v) = prefs.edit { putString(KEY_PERCEPTION_POSE_MODEL_COMPLEXITY, v) }

    /** Pose inference timeout (500–10000ms). Default 2000. */
    var perceptionPoseTimeoutMs: Long
        get() = getLongSafe(KEY_PERCEPTION_POSE_TIMEOUT_MS, DEFAULT_PERCEPTION_POSE_TIMEOUT_MS).coerceIn(500L, 10000L)
        set(v) = prefs.edit { putLong(KEY_PERCEPTION_POSE_TIMEOUT_MS, v.coerceIn(500L, 10000L)) }

    /**
     * Vstupní rozlišení tenzoru pro Pose Estimation (0 = Nativní/Nezmenšovat, 256, 384, 512, 1024, 2048).
     * 256 = Bleskový 2-3ms výpočet pro mobilní zásahy v terénu.
     * >0 = Možnost experimentovat s vyšším rozlišením pro vzdálený dronní průzkum.
     */
    var perceptionPoseResolution: Int
        get() = getIntSafe(KEY_PERCEPTION_POSE_RESOLUTION, DEFAULT_PERCEPTION_POSE_RESOLUTION).coerceIn(0, 4000)
        set(v) = prefs.edit { putInt(KEY_PERCEPTION_POSE_RESOLUTION, v.coerceIn(0, 4000)) }

    // ==================== OCR / SUMARIZACE / TRANSCRIPT (Omni-Control) ====================

    /** OCR Skener (ML Kit TextRecognizer) — On-Demand přes Omni-Control. Default OFF. */
    var isOcrEnabled: Boolean
        get() = prefs.getBoolean(KEY_OCR_ENABLED, DEFAULT_OCR_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_OCR_ENABLED, v) }

    /** NLP Sumarizace — On-Demand přes Omni-Control. Default OFF. */
    var isSummarizationEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUMMARIZATION_ENABLED, DEFAULT_SUMMARIZATION_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_SUMMARIZATION_ENABLED, v) }

    /** Transcript Agent (whisper.cpp ASR) — On-Demand přes Omni-Control. Default OFF. */
    var isTranscriptEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRANSCRIPT_ENABLED, DEFAULT_TRANSCRIPT_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_TRANSCRIPT_ENABLED, v) }

    /** Transcript zdroj zvuku: "mic" (mikrofon) | "system_audio" (MediaProjection). Default "mic". */
    var transcriptSource: String
        get() = prefs.getString(KEY_TRANSCRIPT_SOURCE, DEFAULT_TRANSCRIPT_SOURCE) ?: DEFAULT_TRANSCRIPT_SOURCE
        set(v) = prefs.edit { putString(KEY_TRANSCRIPT_SOURCE, v) }

    /**
     * Transcript ASR engine: "android" (SpeechRecognizer — okamžitý, partial results ~200ms)
     *                        "whisper" (whisper.cpp JNI — přesný offline, batch 3-15s latence)
     *                        "sherpa"  (Sherpa-ONNX Zipformer — bleskový offline, realtime)
     * Default "android" pro real-time titulky.
     */
    var transcriptEngine: String
        get() = prefs.getString(KEY_TRANSCRIPT_ENGINE, DEFAULT_TRANSCRIPT_ENGINE) ?: DEFAULT_TRANSCRIPT_ENGINE
        set(v) = prefs.edit { putString(KEY_TRANSCRIPT_ENGINE, v) }

    /** Absolute path ke složce s modely Sherpa-ONNX STT (obsahující encoder.onnx, decoder.onnx, atd.) */
    var sherpaModelPath: String
        get() = prefs.getString(KEY_SHERPA_MODEL_PATH, "") ?: ""
        set(v) = prefs.edit { putString(KEY_SHERPA_MODEL_PATH, v) }

    /** Absolute path ke složce s modelem Sherpa-ONNX TTS (VITS/Piper/ZipVoice/...) */
    var sherpaTtsModelPath: String
        get() = prefs.getString(KEY_SHERPA_TTS_MODEL_PATH, "") ?: ""
        set(v) = prefs.edit { putString(KEY_SHERPA_TTS_MODEL_PATH, v) }

    /**
     * Absolute path ke sdílenému vocoder souboru (vocos_24khz.onnx / hifigan_v2.onnx).
     * CD-Player Strategy: vocoder je "přehrávač", hlasové modely jsou "disky".
     * Jeden vocoder sdílený napříč všemi hlasy dané TTS rodiny.
     */
    var sherpaSharedVocoderPath: String
        get() = prefs.getString(KEY_SHERPA_TTS_SHARED_VOCODER, "") ?: ""
        set(v) = prefs.edit { putString(KEY_SHERPA_TTS_SHARED_VOCODER, v) }

    // ==================== SHERPA TTS ZIPVOICE CONFIG ====================
    
    var sherpaTtsZipvoiceThreads: Int
        get() = getIntSafe(KEY_SHERPA_TTS_ZIPVOICE_THREADS, 1).coerceIn(1, 8)
        set(v) = prefs.edit { putInt(KEY_SHERPA_TTS_ZIPVOICE_THREADS, v.coerceIn(1, 8)) }

    var sherpaTtsZipvoiceChunkSize: Int
        get() = getIntSafe("sherpa_tts_zipvoice_chunk_size", 1500).coerceAtLeast(0)
        set(v) = prefs.edit { putInt("sherpa_tts_zipvoice_chunk_size", v.coerceAtLeast(0)) }

    var sherpaTtsZipvoicePriority: Int
        get() = getIntSafe("sherpa_tts_zipvoice_priority", 1) // 0=Background, 1=Default, 2=Urgent
        set(v) = prefs.edit { putInt("sherpa_tts_zipvoice_priority", v) }

    var sherpaTtsZipvoiceSampleRate: Int
        get() = getIntSafe(KEY_SHERPA_TTS_ZIPVOICE_SAMPLE_RATE, 16000).coerceIn(8000, 48000)
        set(v) = prefs.edit { putInt(KEY_SHERPA_TTS_ZIPVOICE_SAMPLE_RATE, v.coerceIn(8000, 48000)) }

    var sherpaTtsCustomPromptText: String
        get() = prefs.getString(KEY_SHERPA_TTS_CUSTOM_PROMPT_TEXT, "") ?: ""
        set(v) = prefs.edit { putString(KEY_SHERPA_TTS_CUSTOM_PROMPT_TEXT, v) }

    var sherpaTtsCustomPromptPath: String
        get() = prefs.getString(KEY_SHERPA_TTS_CUSTOM_PROMPT_PATH, "") ?: ""
        set(v) = prefs.edit { putString(KEY_SHERPA_TTS_CUSTOM_PROMPT_PATH, v) }

    var sherpaTtsZipvoiceMicDur: Int
        get() = getIntSafe(KEY_SHERPA_TTS_ZIPVOICE_MIC_DUR, 5).coerceIn(3, 7)
        set(v) = prefs.edit { putInt(KEY_SHERPA_TTS_ZIPVOICE_MIC_DUR, v.coerceIn(3, 7)) }

    /** Backend pro NLP Sumarizaci: "aicore" | "mlkit" | "heuristic" | "llm". Default "mlkit". */
    var summarizationBackend: String
        get() = prefs.getString(KEY_SUMMARIZATION_BACKEND, DEFAULT_SUMMARIZATION_BACKEND) ?: DEFAULT_SUMMARIZATION_BACKEND
        set(v) = prefs.edit { putString(KEY_SUMMARIZATION_BACKEND, v) }

    // ==================== NLP TIER 2.5 (Translation, Sentiment, Smart Reply) ====================

    var translateEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRANSLATE_ENABLED, DEFAULT_TRANSLATE_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_TRANSLATE_ENABLED, v) }

    var translateAutoDownload: Boolean
        get() = prefs.getBoolean(KEY_TRANSLATE_AUTO_DOWNLOAD, DEFAULT_TRANSLATE_AUTO_DOWNLOAD)
        set(v) = prefs.edit { putBoolean(KEY_TRANSLATE_AUTO_DOWNLOAD, v) }

    /** Můj (uživatelův) jazyk — BCP-47 kód, např. "cs". Používá se jako source/target při překladu. */
    var translateMyLang: String
        get() = prefs.getString(KEY_TRANSLATE_MY_LANG, DEFAULT_TRANSLATE_MY_LANG) ?: DEFAULT_TRANSLATE_MY_LANG
        set(v) = prefs.edit { putString(KEY_TRANSLATE_MY_LANG, v) }

    /** Cílový cizí jazyk — BCP-47 kód, např. "en". Směr překladu se reverzí dle auto-detekce. */
    var translateForeignLang: String
        get() = prefs.getString(KEY_TRANSLATE_FOREIGN_LANG, DEFAULT_TRANSLATE_FOREIGN_LANG) ?: DEFAULT_TRANSLATE_FOREIGN_LANG
        set(v) = prefs.edit { putString(KEY_TRANSLATE_FOREIGN_LANG, v) }

    var sentimentAnalysisEnabled: Boolean
        get() = prefs.getBoolean(KEY_SENTIMENT_ENABLED, DEFAULT_SENTIMENT_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_SENTIMENT_ENABLED, v) }

    var smartReplyEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMART_REPLY_ENABLED, DEFAULT_SMART_REPLY_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_SMART_REPLY_ENABLED, v) }

    var genaiProofreadEnabled: Boolean
        get() = prefs.getBoolean(KEY_GENAI_PROOFREAD_ENABLED, DEFAULT_GENAI_PROOFREAD_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_GENAI_PROOFREAD_ENABLED, v) }

    var genaiRewriteEnabled: Boolean
        get() = prefs.getBoolean(KEY_GENAI_REWRITE_ENABLED, DEFAULT_GENAI_REWRITE_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_GENAI_REWRITE_ENABLED, v) }

    // --- NLP Linguistic Tuner weights (Enterprise Grade 2026.1) ---
    var nlpWeightTasks: Float
        get() = prefs.getFloat(KEY_NLP_WEIGHT_TASKS, DEFAULT_NLP_WEIGHT_TASKS)
        set(v) = prefs.edit { putFloat(KEY_NLP_WEIGHT_TASKS, v) }

    var nlpWeightSlang: Float
        get() = prefs.getFloat(KEY_NLP_WEIGHT_SLANG, DEFAULT_NLP_WEIGHT_SLANG)
        set(v) = prefs.edit { putFloat(KEY_NLP_WEIGHT_SLANG, v) }

    var nlpWeightTech: Float
        get() = prefs.getFloat(KEY_NLP_WEIGHT_TECH, DEFAULT_NLP_WEIGHT_TECH)
        set(v) = prefs.edit { putFloat(KEY_NLP_WEIGHT_TECH, v) }

    var nlpWeightUrgency: Float
        get() = prefs.getFloat(KEY_NLP_WEIGHT_URGENCY, DEFAULT_NLP_WEIGHT_URGENCY)
        set(v) = prefs.edit { putFloat(KEY_NLP_WEIGHT_URGENCY, v) }

    /** Po kolika minutách má Tier 2.5 Worker vyvolat Rolling kompresi. Default 3. */
    var transcriptRollingMinutes: Int
        get() = getIntSafe(KEY_TRANSCRIPT_ROLLING_MINUTES, DEFAULT_TRANSCRIPT_ROLLING_MINUTES).coerceIn(1, 30)
        set(v) = prefs.edit { putInt(KEY_TRANSCRIPT_ROLLING_MINUTES, v.coerceIn(1, 30)) }

    /** Ochrana RAM (OOM) — fallback na znaky, pokud se mluví extrémně rychle. Default 30 000. */
    var transcriptMaxBufferChars: Int
        get() = getIntSafe(KEY_TRANSCRIPT_MAX_BUFFER_CHARS, DEFAULT_TRANSCRIPT_MAX_BUFFER_CHARS).coerceIn(1000, 100000)
        set(v) = prefs.edit { putInt(KEY_TRANSCRIPT_MAX_BUFFER_CHARS, v.coerceIn(1000, 100000)) }

    // --- Dictaphone Template Persistence ---
    var lastDictaphoneTemplateId: String
        get() = prefs.getString(KEY_LAST_DICTAPHONE_TEMPLATE, "") ?: ""
        set(v) = prefs.edit { putString(KEY_LAST_DICTAPHONE_TEMPLATE, v) }

    // --- Map-Reduce Toggle ---
    var enableMapReduce: Boolean
        get() = prefs.getBoolean("enable_map_reduce_summarization", false)
        set(v) = prefs.edit { putBoolean("enable_map_reduce_summarization", v) }

    // ==================== WHISPER SLIDING WINDOW (Engine B — Pokročilé STT) ====================

    /**
     * Whisper Sliding Window: Délka audio historie (kontext pro gramatiku).
     * Rozsah: 500–30000 ms. Default 3000ms.
     * Vyšší = lepší česká gramatika (AI vidí delší větu), ale více RAM + CPU.
     * POZOR: Změna vyžaduje restart enginu (realokace ShortArray).
     */
    var whisperHistorySec: Long
        get() = getLongSafe(KEY_WHISPER_HISTORY_SEC, DEFAULT_WHISPER_HISTORY_SEC).coerceIn(500L, 30000L)
        set(v) = prefs.edit { putLong(KEY_WHISPER_HISTORY_SEC, v.coerceIn(500L, 30000L)) }

    /**
     * Whisper Sliding Window: Interval zpracování (jak často AI aktualizuje titulky).
     * Rozsah: 200–2000ms. Default 500ms.
     * Nižší = rychlejší titulky, ale CPU se zahřívá (Thermal Throttling risk).
     * Vyšší = šetří baterii, vhodné pro slabší procesory.
     */
    var whisperProcessingIntervalMs: Long
        get() = getLongSafe(KEY_WHISPER_PROCESSING_INTERVAL_MS, DEFAULT_WHISPER_PROCESSING_INTERVAL_MS).coerceIn(200L, 2000L)
        set(v) = prefs.edit { putLong(KEY_WHISPER_PROCESSING_INTERVAL_MS, v.coerceIn(200L, 2000L)) }

    /**
     * Whisper: Jazyk pro inference (ISO 639-1).
     * Default "cs" (čeština). Podporované: "cs", "sk", "pl", "en", "de", "auto".
     * "auto" = automatická detekce jazyka (pomalejší o ~200ms).
     */
    var whisperLanguage: String
        get() = prefs.getString(KEY_WHISPER_LANGUAGE, DEFAULT_WHISPER_LANGUAGE) ?: DEFAULT_WHISPER_LANGUAGE
        set(v) = prefs.edit { putString(KEY_WHISPER_LANGUAGE, v) }

    /**
     * Whisper: Počet výpočetních vláken pro inference.
     * Rozsah: 1–8. Default 4.
     * ⚠️ Na telefonech s heterogenním CPU (big.LITTLE) může nThreads > 4
     * způsobit zaplnění úsporných jader → Thermal Throttling.
     * Power users: snížit na 2 pro úsporu baterie.
     */
    var whisperNThreads: Int
        get() = getIntSafe(KEY_WHISPER_N_THREADS, DEFAULT_WHISPER_N_THREADS).coerceIn(1, 8)
        set(v) = prefs.edit { putInt(KEY_WHISPER_N_THREADS, v.coerceIn(1, 8)) }

    /**
     * Whisper: Software Gain pro zesílení tichého audia (MediaProjection).
     * Rozsah: 0.5–5.0. Default 1.0 (beze změny).
     * Aplikuje se POUZE na system_audio zdroj (ne na mikrofon).
     * Hodnoty > 3.0 mohou způsobit clipping (saturaci).
     */
    var whisperSoftwareGain: Float
        get() = getFloatSafe(KEY_WHISPER_SOFTWARE_GAIN, DEFAULT_WHISPER_SOFTWARE_GAIN).coerceIn(0.5f, 5.0f)
        set(v) = prefs.edit { putFloat(KEY_WHISPER_SOFTWARE_GAIN, v.coerceIn(0.5f, 5.0f)) }

    var whisperAcceleratorMode: String
        get() = prefs.getString(KEY_WHISPER_ACCELERATOR_MODE, DEFAULT_WHISPER_ACCELERATOR_MODE) ?: DEFAULT_WHISPER_ACCELERATOR_MODE
        set(v) = prefs.edit { putString(KEY_WHISPER_ACCELERATOR_MODE, v) }


    // ==================== SHERPA SLIDING WINDOW (Engine C — Pokročilé STT) ====================
    var sherpaHistorySec: Long
        get() = getLongSafe(KEY_SHERPA_HISTORY_SEC, DEFAULT_SHERPA_HISTORY_SEC).coerceIn(500L, 30000L)
        set(v) = prefs.edit { putLong(KEY_SHERPA_HISTORY_SEC, v.coerceIn(500L, 30000L)) }

    var sherpaProcessingIntervalMs: Long
        get() = getLongSafe(KEY_SHERPA_PROCESSING_INTERVAL_MS, DEFAULT_SHERPA_PROCESSING_INTERVAL_MS).coerceIn(200L, 2000L)
        set(v) = prefs.edit { putLong(KEY_SHERPA_PROCESSING_INTERVAL_MS, v.coerceIn(200L, 2000L)) }

    var sherpaLanguage: String
        get() = prefs.getString(KEY_SHERPA_LANGUAGE, DEFAULT_SHERPA_LANGUAGE) ?: DEFAULT_SHERPA_LANGUAGE
        set(v) = prefs.edit { putString(KEY_SHERPA_LANGUAGE, v) }

    var sherpaNThreads: Int
        get() = getIntSafe(KEY_SHERPA_N_THREADS, DEFAULT_SHERPA_N_THREADS).coerceIn(1, 8)
        set(v) = prefs.edit { putInt(KEY_SHERPA_N_THREADS, v.coerceIn(1, 8)) }

    var sherpaSoftwareGain: Float
        get() = getFloatSafe(KEY_SHERPA_SOFTWARE_GAIN, DEFAULT_SHERPA_SOFTWARE_GAIN).coerceIn(0.5f, 5.0f)
        set(v) = prefs.edit { putFloat(KEY_SHERPA_SOFTWARE_GAIN, v.coerceIn(0.5f, 5.0f)) }

    var sherpaAcceleratorMode: String
        get() = prefs.getString(KEY_SHERPA_ACCELERATOR_MODE, DEFAULT_SHERPA_ACCELERATOR_MODE) ?: DEFAULT_SHERPA_ACCELERATOR_MODE
        set(v) = prefs.edit { putString(KEY_SHERPA_ACCELERATOR_MODE, v) }


    // ==================== AGENT WORKER SETTINGS ====================

    var screenParserMode: ScreenParserMode
        get() = try {
            ScreenParserMode.valueOf(prefs.getString(KEY_SCREEN_PARSER_MODE, ScreenParserMode.AUTO.name) ?: ScreenParserMode.AUTO.name)
        } catch (e: IllegalArgumentException) {
            ScreenParserMode.AUTO
        }
        set(v) = prefs.edit { putString(KEY_SCREEN_PARSER_MODE, v.name) }

    /** ScreenParser: max hloubka UI stromu (5–100). Default 50. */
    var screenParserMaxDepth: Int
        get() = getIntSafe(KEY_SCREEN_PARSER_MAX_DEPTH, DEFAULT_SCREEN_PARSER_MAX_DEPTH).coerceIn(5, 100)
        set(v) = prefs.edit { putInt(KEY_SCREEN_PARSER_MAX_DEPTH, v.coerceIn(5, 100)) }

    /** ScreenParser: max UI elementů k parsování (50–2000). Default 500. */
    var screenParserMaxElements: Int
        get() = getIntSafe(KEY_SCREEN_PARSER_MAX_ELEMENTS, DEFAULT_SCREEN_PARSER_MAX_ELEMENTS).coerceIn(50, 2000)
        set(v) = prefs.edit { putInt(KEY_SCREEN_PARSER_MAX_ELEMENTS, v.coerceIn(50, 2000)) }

    var safetyGuardEnabled: Boolean
        get() = prefs.getBoolean(KEY_SAFETY_GUARD_ENABLED, DEFAULT_SAFETY_GUARD_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_SAFETY_GUARD_ENABLED, v) }

    /** SafetyGuard: velikost akční historie pro loop detekci (3–20). Default 5. */
    var safetyGuardMaxHistory: Int
        get() = getIntSafe(KEY_SAFETY_GUARD_MAX_HISTORY, DEFAULT_SAFETY_GUARD_MAX_HISTORY).coerceIn(3, 20)
        set(v) = prefs.edit { putInt(KEY_SAFETY_GUARD_MAX_HISTORY, v.coerceIn(3, 20)) }

    /** SafetyGuard: práh detekce cyklu — po kolika opakováních agent zastav (2–10). Default 3. */
    var safetyGuardLoopThreshold: Int
        get() = getIntSafe(KEY_SAFETY_GUARD_LOOP_THRESHOLD, DEFAULT_SAFETY_GUARD_LOOP_THRESHOLD).coerceIn(2, 10)
        set(v) = prefs.edit { putInt(KEY_SAFETY_GUARD_LOOP_THRESHOLD, v.coerceIn(2, 10)) }

    /** AppLayoutCache: max cachovaných app (5–100). Default 20. */
    var appCacheMaxApps: Int
        get() = getIntSafe(KEY_APP_CACHE_MAX_APPS, DEFAULT_APP_CACHE_MAX_APPS).coerceIn(5, 100)
        set(v) = prefs.edit { putInt(KEY_APP_CACHE_MAX_APPS, v.coerceIn(5, 100)) }

    /** AppLayoutCache: max UI prvků per cached app (10–200). Default 60. */
    var appCacheMaxElements: Int
        get() = getIntSafe(KEY_APP_CACHE_MAX_ELEMENTS, DEFAULT_APP_CACHE_MAX_ELEMENTS).coerceIn(10, 200)
        set(v) = prefs.edit { putInt(KEY_APP_CACHE_MAX_ELEMENTS, v.coerceIn(10, 200)) }

    /** FloatingAgentButton: velikost v dp (32–96). Default 56. */
    var floatingButtonSizeDp: Int
        get() = getIntSafe(KEY_FLOATING_BUTTON_SIZE_DP, DEFAULT_FLOATING_BUTTON_SIZE_DP).coerceIn(32, 96)
        set(v) = prefs.edit { putInt(KEY_FLOATING_BUTTON_SIZE_DP, v.coerceIn(32, 96)) }

    /** FloatingAgentButton: click vs drag rozlišení v ms (50–500). Default 200. */
    var floatingButtonClickThresholdMs: Int
        get() = getIntSafe(KEY_FLOATING_BUTTON_CLICK_THRESHOLD_MS, DEFAULT_FLOATING_BUTTON_CLICK_THRESHOLD_MS).coerceIn(50, 500)
        set(v) = prefs.edit { putInt(KEY_FLOATING_BUTTON_CLICK_THRESHOLD_MS, v.coerceIn(50, 500)) }

    /** FloatingAgentButton: min pohyb pro drag v px (3–30). Default 10. */
    var floatingButtonMoveThresholdPx: Int
        get() = getIntSafe(KEY_FLOATING_BUTTON_MOVE_THRESHOLD_PX, DEFAULT_FLOATING_BUTTON_MOVE_THRESHOLD_PX).coerceIn(3, 30)
        set(v) = prefs.edit { putInt(KEY_FLOATING_BUTTON_MOVE_THRESHOLD_PX, v.coerceIn(3, 30)) }

    /** FloatingAgentButton: auto-hide po X sekundách nečinnosti (0=vypnuto, 3–60). Default 0. OLED ochrana. */
    var floatingButtonAutoHideSec: Int
        get() = getIntSafe(KEY_FLOATING_BUTTON_AUTO_HIDE_SEC, DEFAULT_FLOATING_BUTTON_AUTO_HIDE_SEC).let {
            if (it == 0) 0 else it.coerceIn(3, 60)
        }
        set(v) = prefs.edit { putInt(KEY_FLOATING_BUTTON_AUTO_HIDE_SEC, if (v <= 0) 0 else v.coerceIn(3, 60)) }

    /** FloatingAgentButton: skrýt na zamčené obrazovce. Default true. OLED ochrana. */
    var floatingButtonLockscreenHide: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_BUTTON_LOCKSCREEN_HIDE, DEFAULT_FLOATING_BUTTON_LOCKSCREEN_HIDE)
        set(v) = prefs.edit { putBoolean(KEY_FLOATING_BUTTON_LOCKSCREEN_HIDE, v) }

    // ==================== AGENT NOTIFIER ====================
    var floatingNotifyEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_NOTIFY_ENABLED, DEFAULT_FLOATING_NOTIFY_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_FLOATING_NOTIFY_ENABLED, v) }

    var floatingNotifyStyle: Int
        get() = getIntSafe(KEY_FLOATING_NOTIFY_STYLE, DEFAULT_FLOATING_NOTIFY_STYLE).coerceIn(0, 2)
        set(v) = prefs.edit { putInt(KEY_FLOATING_NOTIFY_STYLE, v.coerceIn(0, 2)) }

    var floatingNotifyPosition: Int
        get() = getIntSafe(KEY_FLOATING_NOTIFY_POSITION, DEFAULT_FLOATING_NOTIFY_POSITION).coerceIn(0, 4)
        set(v) = prefs.edit { putInt(KEY_FLOATING_NOTIFY_POSITION, v.coerceIn(0, 4)) }

    var floatingNotifyShowWarnings: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_NOTIFY_SHOW_WARNINGS, DEFAULT_FLOATING_NOTIFY_SHOW_WARNINGS)
        set(v) = prefs.edit { putBoolean(KEY_FLOATING_NOTIFY_SHOW_WARNINGS, v) }

    var floatingNotifyShowVision: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_NOTIFY_SHOW_VISION, DEFAULT_FLOATING_NOTIFY_SHOW_VISION)
        set(v) = prefs.edit { putBoolean(KEY_FLOATING_NOTIFY_SHOW_VISION, v) }

    var floatingNotifyShowAudio: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_NOTIFY_SHOW_AUDIO, DEFAULT_FLOATING_NOTIFY_SHOW_AUDIO)
        set(v) = prefs.edit { putBoolean(KEY_FLOATING_NOTIFY_SHOW_AUDIO, v) }

    var perceptionCameraSource: Int
        get() = getIntSafe(KEY_PERCEPTION_CAMERA_SOURCE, DEFAULT_PERCEPTION_CAMERA_SOURCE).coerceIn(0, 2)
        set(v) = prefs.edit { putInt(KEY_PERCEPTION_CAMERA_SOURCE, v.coerceIn(0, 2)) }

    /**
     * Perzistentní ID aktivního Vision pluginu.
     * Default = "tracker_iou_edge" (Nativní EdgeTracker).
     * RAW bypass = "core_raw_bypass".
     */
    var activeVisionPluginId: String
        get() = prefs.getString(KEY_ACTIVE_VISION_PLUGIN_ID, DEFAULT_ACTIVE_VISION_PLUGIN_ID)
                ?: DEFAULT_ACTIVE_VISION_PLUGIN_ID
        set(v) = prefs.edit { putString(KEY_ACTIVE_VISION_PLUGIN_ID, v) }

    /**
     * ⚠️ LAB MODE: Přesměruje výstup Lupa pluginu zpět do PerceptionEngine (Tensor větve).
     * OFF (default) = Tensor dostává čistá surová data → Kalman konverguje stabilně.
     * ON = Tensor dostává vizuálně zpracovanou bitmapu z Lupa pipeline → degradace SNR (pedagogická demonstrace).
     * POZOR: Při přepnutí ON musí být voláno pluginManager.resetActive() pro čistý restart Kalmanu.
     */
    var lupaVisionDebugMode: Boolean
        get() = prefs.getBoolean("lupa_vision_debug_mode", false)
        set(v) = prefs.edit { putBoolean("lupa_vision_debug_mode", v) }

    /**
     * Zpětná kompatibilita: isTrackerEnabled deleguje na activeVisionPluginId.
     * true = jakýkoliv plugin mimo RAW bypass, false = RAW bypass.
     */
    var isTrackerEnabled: Boolean
        get() = activeVisionPluginId != com.example.llama.agent.vision.plugins.VisionPluginManager.RAW_BYPASS_ID
        set(v) {
            activeVisionPluginId = if (v) DEFAULT_ACTIVE_VISION_PLUGIN_ID
                else com.example.llama.agent.vision.plugins.VisionPluginManager.RAW_BYPASS_ID
        }

    // --- Kalman Filter (MotionEstimator) ---
    var kalmanProcessNoise: Float
        get() = getFloatSafe(KEY_KALMAN_PROCESS_NOISE, DEFAULT_KALMAN_PROCESS_NOISE)
        set(v) = prefs.edit { putFloat(KEY_KALMAN_PROCESS_NOISE, v) }

    var kalmanMeasurementNoise: Float
        get() = getFloatSafe(KEY_KALMAN_MEASUREMENT_NOISE, DEFAULT_KALMAN_MEASUREMENT_NOISE)
        set(v) = prefs.edit { putFloat(KEY_KALMAN_MEASUREMENT_NOISE, v) }

    var kalmanStdWeightPosition: Float
        get() = getFloatSafe(KEY_KALMAN_STD_WEIGHT_POSITION, DEFAULT_KALMAN_STD_WEIGHT_POSITION)
        set(v) = prefs.edit { putFloat(KEY_KALMAN_STD_WEIGHT_POSITION, v) }

    var kalmanStdWeightVelocity: Float
        get() = getFloatSafe(KEY_KALMAN_STD_WEIGHT_VELOCITY, DEFAULT_KALMAN_STD_WEIGHT_VELOCITY)
        set(v) = prefs.edit { putFloat(KEY_KALMAN_STD_WEIGHT_VELOCITY, v) }

    /**
     * Atomický zápis konfigurace (Race-condition prevence pro Option A Hard Flush)
     */
    fun applyKalmanConfigAtomically(q: Float, r: Float, posW: Float, velW: Float) {
        prefs.edit()
            .putFloat(KEY_KALMAN_PROCESS_NOISE, q)
            .putFloat(KEY_KALMAN_MEASUREMENT_NOISE, r)
            .putFloat(KEY_KALMAN_STD_WEIGHT_POSITION, posW)
            .putFloat(KEY_KALMAN_STD_WEIGHT_VELOCITY, velW)
            .apply() // .apply() uloží atomicky celou mapu v paměti a asynchronně na disk
    }

    fun resetToDefaults() {
        prefs.edit { clear() }
    }
    /**
     * Agent background mode enabled Mirror sync: Pokud agent background ON → auto-enable Display
     * background
     */
    var isAgentBackgroundEnabled: Boolean
        get() = backgroundGeneration
        set(value) {
            backgroundGeneration = value
        }

    var isWakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_AGENT_WAKE_WORD_ENABLED, false)
        set(v) = prefs.edit { putBoolean(KEY_AGENT_WAKE_WORD_ENABLED, v) }

    var wakeWord: String
        get() = prefs.getString(KEY_AGENT_WAKE_WORD, "hej agente") ?: "hej agente"
        set(v) = prefs.edit { putString(KEY_AGENT_WAKE_WORD, v) }

    var agentVoiceMode: String
        get() =
                prefs.getString(KEY_AGENT_VOICE_MODE, VOICEMODE_PUSH_TO_TALK)
                        ?: VOICEMODE_PUSH_TO_TALK
        set(v) = prefs.edit { putString(KEY_AGENT_VOICE_MODE, v) }

    var agentSpeechEngine: String
        get() = prefs.getString(KEY_AGENT_SPEECH_ENGINE, "") ?: ""
        set(v) = prefs.edit { putString(KEY_AGENT_SPEECH_ENGINE, v) }

    var transcriptAndroidEngine: String
        get() = prefs.getString(KEY_TRANSCRIPT_ANDROID_ENGINE, "") ?: ""
        set(v) = prefs.edit { putString(KEY_TRANSCRIPT_ANDROID_ENGINE, v) }

    var agentTtsEngine: String
        get() = prefs.getString(KEY_AGENT_TTS_ENGINE, "") ?: ""
        set(v) = prefs.edit { putString(KEY_AGENT_TTS_ENGINE, v) }

    var agentLanguageOnline: String
        get() = prefs.getString(KEY_AGENT_LANGUAGE_ONLINE, "") ?: "" // Empty = Auto/Default
        set(v) = prefs.edit { putString(KEY_AGENT_LANGUAGE_ONLINE, v) }

    var agentLanguageOffline: String
        get() = prefs.getString(KEY_AGENT_LANGUAGE_OFFLINE, "") ?: "" // Empty = Use system default
        set(v) = prefs.edit { putString(KEY_AGENT_LANGUAGE_OFFLINE, v) }

    var isSttForceOffline: Boolean
        get() = prefs.getBoolean(KEY_AGENT_STT_FORCE_OFFLINE, false)
        set(v) = prefs.edit { putBoolean(KEY_AGENT_STT_FORCE_OFFLINE, v) }

    var isAgentOnlineEnabled: Boolean
        get() =
                prefs.getBoolean(
                        KEY_AGENT_ONLINE_ENABLED,
                        true
                ) // Default true (Hybrid if ForceOffline=true)
        set(v) = prefs.edit { putBoolean(KEY_AGENT_ONLINE_ENABLED, v) }

    /** Agent pipeline timeout v milisekundách. Rozsah 5000-120000ms (5s-120s). Default 30s. */
    var agentTimeoutMs: Long
        get() = getLongSafe(KEY_AGENT_TIMEOUT, DEFAULT_AGENT_TIMEOUT_MS).coerceIn(5000L, 120000L)
        set(v) = prefs.edit { putLong(KEY_AGENT_TIMEOUT, v.coerceIn(5000L, 120000L)) }

    // --- Onboarding / Role Manager ---
    // KEY_LAST_DIALER_PROMPT moved to companion object

    fun getLastDialerPromptTime(): Long {
        return getLongSafe(KEY_LAST_DIALER_PROMPT, 0L)
    }

    fun setLastDialerPromptTime(timestamp: Long) {
        prefs.edit { putLong(KEY_LAST_DIALER_PROMPT, timestamp) }
    }

    var appLayoutCacheTtl: Long
        get() = getLongSafe(KEY_CACHE_TTL, DEFAULT_CACHE_TTL)
        set(v) = prefs.edit { putLong(KEY_CACHE_TTL, v) }

    // --- Agent Timeouty (Advanced) ---

    /** LLM cancel wait timeout (ms). Jak dlouho čekat po cancel na ModelReady. Rozsah 1-15s. */
    var agentLlmCancelTimeout: Long
        get() =
                getLongSafe(KEY_AGENT_LLM_CANCEL_TIMEOUT, DEFAULT_LLM_CANCEL_TIMEOUT)
                        .coerceIn(1000L, 15000L)
        set(v) = prefs.edit { putLong(KEY_AGENT_LLM_CANCEL_TIMEOUT, v.coerceIn(1000L, 15000L)) }

    /** SmartClick/SmartInput timeout (ms). Max čekání na nalezení UI elementu. Rozsah 3-30s. */
    var agentSmartClickTimeout: Long
        get() =
                getLongSafe(KEY_AGENT_SMARTCLICK_TIMEOUT, DEFAULT_SMARTCLICK_TIMEOUT)
                        .coerceIn(3000L, 30000L)
        set(v) = prefs.edit { putLong(KEY_AGENT_SMARTCLICK_TIMEOUT, v.coerceIn(3000L, 30000L)) }

    /** App load wait (ms). Čekání po otevření appky před SmartClick. Rozsah 500-10000ms. */
    var agentAppLoadWait: Long
        get() = getLongSafe(KEY_AGENT_APP_LOAD_WAIT, DEFAULT_APP_LOAD_WAIT).coerceIn(500L, 10000L)
        set(v) = prefs.edit { putLong(KEY_AGENT_APP_LOAD_WAIT, v.coerceIn(500L, 10000L)) }

    /** Gesture dispatch timeout (ms). Max čekání na odpověď systému na gesto. Rozsah 1-10s. */
    var agentGestureTimeout: Long
        get() =
                getLongSafe(KEY_AGENT_GESTURE_TIMEOUT, DEFAULT_GESTURE_TIMEOUT)
                        .coerceIn(1000L, 10000L)
        set(v) = prefs.edit { putLong(KEY_AGENT_GESTURE_TIMEOUT, v.coerceIn(1000L, 10000L)) }

    /** Toggle timeout (ms). Auto-reset pending toggle action. Rozsah 2-15s. */
    var agentToggleTimeout: Long
        get() =
                getLongSafe(KEY_AGENT_TOGGLE_TIMEOUT, DEFAULT_TOGGLE_TIMEOUT)
                        .coerceIn(2000L, 15000L)
        set(v) = prefs.edit { putLong(KEY_AGENT_TOGGLE_TIMEOUT, v.coerceIn(2000L, 15000L)) }

    // --- Agent Interaction Customization ---

    /** Scroll distance factor (0.1 - 1.0). Default 0.8 (80%). */
    var agentScrollDistance: Float
        get() =
                getFloatSafe(KEY_AGENT_SCROLL_DISTANCE, DEFAULT_SCROLL_DISTANCE)
                        .coerceIn(0.1f, 1.0f)
        set(v) = prefs.edit { putFloat(KEY_AGENT_SCROLL_DISTANCE, v.coerceIn(0.1f, 1.0f)) }

    /** Zoom factor (1.0 - 5.0). Default 2.0. */
    var agentZoomFactor: Float
        get() = getFloatSafe(KEY_AGENT_ZOOM_FACTOR, DEFAULT_ZOOM_FACTOR).coerceIn(1.0f, 5.0f)
        set(v) = prefs.edit { putFloat(KEY_AGENT_ZOOM_FACTOR, v.coerceIn(1.0f, 5.0f)) }

    /** Rapid Tap interval (ms). Default 50ms. */
    var agentTapInterval: Long
        get() = getLongSafe(KEY_AGENT_TAP_INTERVAL, DEFAULT_TAP_INTERVAL).coerceIn(10L, 500L)
        set(v) = prefs.edit { putLong(KEY_AGENT_TAP_INTERVAL, v.coerceIn(10L, 500L)) }

    /** Rapid Tap count. Default 10. */
    var agentTapCount: Int
        get() = prefs.getInt(KEY_AGENT_TAP_COUNT, DEFAULT_TAP_COUNT).coerceIn(2, 100)
        set(v) = prefs.edit { putInt(KEY_AGENT_TAP_COUNT, v.coerceIn(2, 100)) }

    // --- Invert Gestures ---
    var isInvertSwipeVertical: Boolean
        get() = prefs.getBoolean(KEY_INVERT_SWIPE_VERTICAL, false)
        set(v) = prefs.edit { putBoolean(KEY_INVERT_SWIPE_VERTICAL, v) }

    var isInvertSwipeHorizontal: Boolean
        get() = prefs.getBoolean(KEY_INVERT_SWIPE_HORIZONTAL, false)
        set(v) = prefs.edit { putBoolean(KEY_INVERT_SWIPE_HORIZONTAL, v) }

    // --- Agent Memory (Screen Context Memory) ---

    /** Max cached apps v paměti. Rozsah 5-100. Default 30. */
    var memoryMaxApps: Int
        get() = getIntSafe(KEY_MEMORY_MAX_APPS, DEFAULT_MEMORY_MAX_APPS).coerceIn(5, 100)
        set(v) = prefs.edit { putInt(KEY_MEMORY_MAX_APPS, v.coerceIn(5, 100)) }

    /** Max naučených patternů per app. Rozsah 10-200. Default 50. */
    var memoryMaxPatterns: Int
        get() = getIntSafe(KEY_MEMORY_MAX_PATTERNS, DEFAULT_MEMORY_MAX_PATTERNS).coerceIn(10, 200)
        set(v) = prefs.edit { putInt(KEY_MEMORY_MAX_PATTERNS, v.coerceIn(10, 200)) }

    /** Flush interval (ms). Jak často se ukládá paměť na disk. Rozsah 15s-300s. Default 60s. */
    var memoryFlushInterval: Long
        get() =
                getLongSafe(KEY_MEMORY_FLUSH_INTERVAL, DEFAULT_MEMORY_FLUSH_INTERVAL)
                        .coerceIn(15000L, 300000L)
        set(v) = prefs.edit { putLong(KEY_MEMORY_FLUSH_INTERVAL, v.coerceIn(15000L, 300000L)) }

    /** Max stáří patternu (dny). Rozsah 1-30 dní. Default 7. */
    var memoryPatternMaxAge: Int
        get() =
                getIntSafe(KEY_MEMORY_PATTERN_MAX_AGE, DEFAULT_MEMORY_PATTERN_MAX_AGE)
                        .coerceIn(1, 30)
        set(v) = prefs.edit { putInt(KEY_MEMORY_PATTERN_MAX_AGE, v.coerceIn(1, 30)) }

    /** Min confidence pro recall (0.5-0.95). Default 0.7. */
    var memoryMinConfidence: Float
        get() =
                getFloatSafe(KEY_MEMORY_MIN_CONFIDENCE, DEFAULT_MEMORY_MIN_CONFIDENCE)
                        .coerceIn(0.5f, 0.95f)
        set(v) = prefs.edit { putFloat(KEY_MEMORY_MIN_CONFIDENCE, v.coerceIn(0.5f, 0.95f)) }

    /** RAM limit pro paměť (KB). Rozsah 128-4096 KB. Default 512 KB. */
    var memoryRamLimitKb: Int
        get() = getIntSafe(KEY_MEMORY_RAM_LIMIT_KB, DEFAULT_MEMORY_RAM_LIMIT_KB).coerceIn(128, 4096)
        set(v) = prefs.edit { putInt(KEY_MEMORY_RAM_LIMIT_KB, v.coerceIn(128, 4096)) }

    /**
     * LLM Fallback (Tier 3) — výchozně VYPNUTO. false = Tier 1/2 dostatečné, chyba vrácena přes TTS
     * (bez zahřívání, instantní). true = Tier 2 miss → LLM inference (3-6s, náročné na baterii).
     * Zapnout ručně v Settings → Agent → Pokročilé.
     */
    var agentUseLlmFallback: Boolean
        get() = prefs.getBoolean(KEY_AGENT_USE_LLM_FALLBACK, DEFAULT_AGENT_USE_LLM_FALLBACK)
        set(v) = prefs.edit { putBoolean(KEY_AGENT_USE_LLM_FALLBACK, v) }

    // ==================== IrisMotion (Tier 2.2) ====================

    /** IrisMotion master switch. Výchozně OFF — vyžaduje kalibraci před prvním použitím. */
    var isIrisMotionEnabled: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_ENABLED, false)
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_ENABLED, v) }

    var isRemoteModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_REMOTE_MODE_ENABLED, DEFAULT_IRISMOTION_REMOTE_MODE_ENABLED)
        set(value) = prefs.edit { putBoolean(KEY_IRISMOTION_REMOTE_MODE_ENABLED, value) }

    var irismotionBtSensitivity: Float
        get() = getFloatSafe(KEY_IRISMOTION_BT_SENSITIVITY, DEFAULT_IRISMOTION_BT_SENSITIVITY)
        set(value) = prefs.edit { putFloat(KEY_IRISMOTION_BT_SENSITIVITY, value.coerceIn(0.1f, 10.0f)) }

    var irismotionBtDeadzone: Float
        get() = getFloatSafe(KEY_IRISMOTION_BT_DEADZONE, DEFAULT_IRISMOTION_BT_DEADZONE)
        set(value) = prefs.edit { putFloat(KEY_IRISMOTION_BT_DEADZONE, value.coerceIn(0.0f, 20.0f)) }

    var irismotionBtAcceleration: Float
        get() = getFloatSafe(KEY_IRISMOTION_BT_ACCELERATION, DEFAULT_IRISMOTION_BT_ACCELERATION)
        set(value) = prefs.edit { putFloat(KEY_IRISMOTION_BT_ACCELERATION, value.coerceIn(1.0f, 5.0f)) }

    var irismotionBtInvertX: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_BT_INVERT_X, DEFAULT_IRISMOTION_BT_INVERT_X)
        set(value) = prefs.edit { putBoolean(KEY_IRISMOTION_BT_INVERT_X, value) }

    var irismotionBtInvertY: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_BT_INVERT_Y, DEFAULT_IRISMOTION_BT_INVERT_Y)
        set(value) = prefs.edit { putBoolean(KEY_IRISMOTION_BT_INVERT_Y, value) }

    var irismotionBtRemoteWidth: Int
        get() = prefs.getInt(KEY_IRISMOTION_BT_REMOTE_WIDTH, DEFAULT_IRISMOTION_BT_REMOTE_WIDTH)
        set(value) = prefs.edit { putInt(KEY_IRISMOTION_BT_REMOTE_WIDTH, value.coerceAtLeast(640)) }

    var irismotionBtRemoteHeight: Int
        get() = prefs.getInt(KEY_IRISMOTION_BT_REMOTE_HEIGHT, DEFAULT_IRISMOTION_BT_REMOTE_HEIGHT)
        set(value) = prefs.edit { putInt(KEY_IRISMOTION_BT_REMOTE_HEIGHT, value.coerceAtLeast(480)) }

    /** Přepínač mezi novým Neural Core (Blendshapes) a Legacy 2D modelem. */
    var isIrisMotionNeuralCoreEnabled: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_NEURAL_CORE, DEFAULT_IRISMOTION_NEURAL_CORE)
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_NEURAL_CORE, v) }

    /** Master switch pro Dwell Clicker. Výchozí hodnota je TRUE (zapnuto). */
    var isIrisMotionDwellEnabled: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_DWELL_ENABLED, true)
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_DWELL_ENABLED, v) }

    // P7: Intentionality Engine
    var irisMotionTimeShiftEnabled: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_TIMESHIFT_ENABLED, true)
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_TIMESHIFT_ENABLED, v) }

    var irisMotionTimeShiftMs: Long
        get() = prefs.getLong(KEY_IRISMOTION_TIMESHIFT_MS, 120L).coerceIn(0L, 800L)
        set(v) = prefs.edit { putLong(KEY_IRISMOTION_TIMESHIFT_MS, v) }

    var irisMotionIntentFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_INTENT_FILTER_ENABLED, true)
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_INTENT_FILTER_ENABLED, v) }

    var irisMotionIntentFilterThresholdDeg: Float
        get() = getFloatSafe(KEY_IRISMOTION_INTENT_FILTER_THRESHOLD_DEG, 3.0f).coerceIn(1.0f, 10.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_INTENT_FILTER_THRESHOLD_DEG, v) }

    // P8: Auto-Calibration EAR
    var irisMotionPhysioOpenEAR: Float
        get() = getFloatSafe(KEY_IRISMOTION_PHYSIO_OPEN_EAR, 0.35f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_PHYSIO_OPEN_EAR, v) }

    var irisMotionBlinkSensitivity: Float
        get() = getFloatSafe(KEY_IRISMOTION_BLINK_SENSITIVITY, 0.80f).coerceIn(0.40f, 0.95f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_BLINK_SENSITIVITY, v) }

    /**
     * Dwell time (ms) — jak dlouho musí pohled stát na elementu před kliknutím. Rozsah 40-10000ms.
     */
    var irisMotionDwellMs: Long
        get() =
                getLongSafe(KEY_IRISMOTION_DWELL_MS, DEFAULT_IRISMOTION_DWELL_MS)
                        .coerceIn(40L, 10000L)
        set(v) = prefs.edit { putLong(KEY_IRISMOTION_DWELL_MS, v.coerceIn(40L, 10000L)) }

    /** Kurzor průměr (px). Rozsah 40-120px. Default 80px. */
    var irisMotionCursorSize: Int
        get() =
                getIntSafe(KEY_IRISMOTION_CURSOR_SIZE, DEFAULT_IRISMOTION_CURSOR_SIZE)
                        .coerceIn(40, 120)
        set(v) = prefs.edit { putInt(KEY_IRISMOTION_CURSOR_SIZE, v.coerceIn(40, 120)) }

    /** Globální citlivost pohybu oka (multiplikátor amplifikace). Rozsah 0.3-3.0. Default 1.0. */
    var irisMotionSensitivity: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_SENSITIVITY, DEFAULT_IRISMOTION_SENSITIVITY)
                        .coerceIn(0.3f, 3.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_SENSITIVITY, v.coerceIn(0.3f, 3.0f)) }

    /** OneEuro Filter specifická citlivost (beta scaling). Rozsah 0.1-5.0. Default 1.0. */
    var irisMotionFilterSensitivity: Float
        get() =
                getFloatSafe(
                                KEY_IRISMOTION_FILTER_SENSITIVITY,
                                DEFAULT_IRISMOTION_FILTER_SENSITIVITY
                        )
                        .coerceIn(0.1f, 5.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_FILTER_SENSITIVITY, v.coerceIn(0.1f, 5.0f)) }

    /** Fyziologický klidový bod oka X (MediaPipe socket). Rozsah 0.2-0.8. Default 0.50. */
    var irisMotionPhysioRestX: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_PHYSIO_REST_X, DEFAULT_IRISMOTION_PHYSIO_REST_X)
                        .coerceIn(0.2f, 0.8f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_PHYSIO_REST_X, v.coerceIn(0.2f, 0.8f)) }

    /** Fyziologický klidový bod oka Y (MediaPipe socket). Rozsah 0.1-0.7. Default 0.35. */
    var irisMotionPhysioRestY: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_PHYSIO_REST_Y, DEFAULT_IRISMOTION_PHYSIO_REST_Y)
                        .coerceIn(0.1f, 0.7f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_PHYSIO_REST_Y, v.coerceIn(0.1f, 0.7f)) }

    /** Intrinsic amplifikace X (base škálování duhovky). Rozsah 1.0-5.0. Default 2.5. */
    var irisMotionIntrinsicAmpX: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_INTRINSIC_AMP_X, DEFAULT_IRISMOTION_INTRINSIC_AMP_X)
                        .coerceIn(1.0f, 5.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_INTRINSIC_AMP_X, v.coerceIn(1.0f, 5.0f)) }

    /** Intrinsic amplifikace Y (base škálování duhovky). Rozsah 1.0-5.0. Default 2.5. */
    var irisMotionIntrinsicAmpY: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_INTRINSIC_AMP_Y, DEFAULT_IRISMOTION_INTRINSIC_AMP_Y)
                        .coerceIn(1.0f, 5.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_INTRINSIC_AMP_Y, v.coerceIn(1.0f, 5.0f)) }

    /** Edge Decay margin (kompresní zóna). Rozsah 0.02-0.25. Default 0.08. */
    var irisMotionEdgeMargin: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_EDGE_MARGIN, DEFAULT_IRISMOTION_EDGE_MARGIN)
                        .coerceIn(0.02f, 0.25f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_EDGE_MARGIN, v.coerceIn(0.02f, 0.25f)) }

    /**
     * Příznak dokončené kalibrace. Nastavuje CalibrationRepository po úspěšné kalibraci. Readonly z
     * pohledu uživatele — mění se automaticky.
     */
    var isIrisMotionCalibrated: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_CALIBRATED, false)
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_CALIBRATED, v) }

    /** Výběr oka pro tracking: 0 = Obě (průměr), 1 = Levé, 2 = Pravé */
    var irisMotionTrackedEye: Int
        get() = prefs.getInt(KEY_IRISMOTION_TRACKED_EYE, DEFAULT_IRISMOTION_TRACKED_EYE)
        set(v) = prefs.edit { putInt(KEY_IRISMOTION_TRACKED_EYE, v) }

    /**
     * Dosah magnetického přitahování (Target Snap Radius) v pixelech. Rozsah 4-320px. Default 60px.
     */
    var irisMotionTargetSnapRadius: Float
        get() =
                getFloatSafe(
                                KEY_IRISMOTION_TARGET_SNAP_RADIUS,
                                DEFAULT_IRISMOTION_TARGET_SNAP_RADIUS
                        )
                        .coerceIn(4f, 320f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_TARGET_SNAP_RADIUS, v.coerceIn(4f, 320f)) }

    /** Zda je obraz kamery a gaze vektoru horizontálně zrcadlen (Mirroring) */
    var isIrisMotionCameraMirrored: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_CAMERA_MIRRORED, DEFAULT_IRISMOTION_CAMERA_MIRRORED)
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_CAMERA_MIRRORED, v) }

    var isIrisMotionEdgeDecayEnabled: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_EDGE_DECAY_ENABLED, true)
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_EDGE_DECAY_ENABLED, v) }

    // --- IrisMotion: Filtrační a kalibrační parametry (rozšířené rozsahy dle Casiez 2012) ---

    /** Rychlost přitahování/dotahu kurzoru k zorničce v rámci šumového boxu (0.01 - 1.0) */
    var irisMotionFixationDrift: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_FIXATION_DRIFT, DEFAULT_IRISMOTION_FIXATION_DRIFT)
                        .coerceIn(0.01f, 1.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_FIXATION_DRIFT, v.coerceIn(0.01f, 1.0f)) }

    /**
     * Reaktivita One Euro Filteru (BETA). Rozsah 0.001–20.0. Vyšší = rychlejší odezva, větší třes.
     */
    var irisMotionFilterBeta: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_FILTER_BETA, DEFAULT_IRISMOTION_FILTER_BETA)
                        .coerceIn(0.0f, 20.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_FILTER_BETA, v.coerceIn(0.0f, 20.0f)) }

    /** Frekvence vyhlazení v klidu (MIN_CUTOFF Hz). Rozsah 0.001–1.0. Vyšší = kurzor "zkamení". */
    var irisMotionFilterCutoff: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_FILTER_CUTOFF, DEFAULT_IRISMOTION_FILTER_CUTOFF)
                        .coerceIn(0.01f, 1.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_FILTER_CUTOFF, v.coerceIn(0.01f, 1.0f)) }

    /**
     * Master IT Fix: Jemný tuning fyzického středu kamery a usazení očí na X-ové ose. Rozsah
     * 0.1-0.9. Default 0.5.
     */
    var irisMotionCenterXOffset: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_CENTER_X_OFFSET, DEFAULT_IRISMOTION_CENTER_X_OFFSET)
                        .coerceIn(0.1f, 0.9f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_CENTER_X_OFFSET, v.coerceIn(0.1f, 0.9f)) }

    /**
     * Master IT Fix: Jemný tuning biologického odsazení duhovky vůči očnímu víčku. Rozsah 0.1-0.9.
     * Default 0.30.
     */
    var irisMotionCenterYOffset: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_CENTER_Y_OFFSET, DEFAULT_IRISMOTION_CENTER_Y_OFFSET)
                        .coerceIn(0.1f, 0.9f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_CENTER_Y_OFFSET, v.coerceIn(0.1f, 0.9f)) }

    /** Frekvence derivace vzorkování One Euro Filtru (D_CUTOFF). Rozsah 0.1-10.0. */
    var irisMotionFilterDCutoff: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_FILTER_D_CUTOFF, DEFAULT_IRISMOTION_FILTER_D_CUTOFF)
                        .coerceIn(0.1f, 10.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_FILTER_D_CUTOFF, v.coerceIn(0.1f, 10.0f)) }

    /** Prediktivní váha AlphaBeta filtru (Alpha - pozice). Rozsah 0.1-1.0. */
    var irisMotionAlphaBetaAlpha: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_ALPHABETA_ALPHA, DEFAULT_IRISMOTION_ALPHABETA_ALPHA)
                        .coerceIn(0.01f, 1.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_ALPHABETA_ALPHA, v.coerceIn(0.01f, 1.0f)) }

    /** Prediktivní váha AlphaBeta filtru (Beta - hybnost/rychlost). Rozsah 0.001-1.0. */
    var irisMotionAlphaBetaBeta: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_ALPHABETA_BETA, DEFAULT_IRISMOTION_ALPHABETA_BETA)
                        .coerceIn(0.001f, 1.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_ALPHABETA_BETA, v.coerceIn(0.001f, 1.0f)) }

    /** Míra vlastního zrychleného pohybu zornice (Process Noise Q). Míra predikce sakády. */
    var irisMotionKalmanQNoise: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_KALMAN_Q_NOISE, DEFAULT_IRISMOTION_KALMAN_Q_NOISE)
                        .coerceIn(0.0001f, 0.1f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_KALMAN_Q_NOISE, v.coerceIn(0.0001f, 0.1f)) }

    /**
     * Míra odfiltrování optického zrnění a tmy kamery (Measurement Noise R). Výše = více filtru.
     */
    var irisMotionKalmanRNoise: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_KALMAN_R_NOISE, DEFAULT_IRISMOTION_KALMAN_R_NOISE)
                        .coerceIn(0.001f, 1.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_KALMAN_R_NOISE, v.coerceIn(0.001f, 1.0f)) }

    /** Horizontální zesílení pohybu oka (gain). Rozsah -3.0–25.0. Default 1.3. */
    var irisMotionGazeAmpX: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_GAZE_AMP_X, DEFAULT_IRISMOTION_GAZE_AMP_X)
                        .coerceIn(-3.0f, 25.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_GAZE_AMP_X, v.coerceIn(-3.0f, 25.0f)) }

    var irisMotionAmpUp: Float
        get() = getFloatSafe(KEY_IRISMOTION_AMP_UP, DEFAULT_IRISMOTION_AMP_UP).coerceIn(0.1f, 25.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_AMP_UP, v.coerceIn(0.1f, 25.0f)) }

    var irisMotionAmpDown: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_AMP_DOWN, DEFAULT_IRISMOTION_AMP_DOWN)
                        .coerceIn(0.1f, 25.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_AMP_DOWN, v.coerceIn(0.1f, 25.0f)) }

    var irisMotionAmpLeft: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_AMP_LEFT, DEFAULT_IRISMOTION_AMP_LEFT)
                        .coerceIn(0.1f, 25.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_AMP_LEFT, v.coerceIn(0.1f, 25.0f)) }

    var irisMotionAmpRight: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_AMP_RIGHT, DEFAULT_IRISMOTION_AMP_RIGHT)
                        .coerceIn(0.1f, 25.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_AMP_RIGHT, v.coerceIn(0.1f, 25.0f)) }

    var irisMotionInvertX: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_INVERT_X, DEFAULT_IRISMOTION_INVERT_X)
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_INVERT_X, v) }

    var irisMotionInvertY: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_INVERT_Y, DEFAULT_IRISMOTION_INVERT_Y)
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_INVERT_Y, v) }

    /** Vertikální zesílení pohybu oka (gain). Rozsah -3.0–25.0. Default 1.2. */
    var irisMotionGazeAmpY: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_GAZE_AMP_Y, DEFAULT_IRISMOTION_GAZE_AMP_Y)
                        .coerceIn(-3.0f, 25.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_GAZE_AMP_Y, v.coerceIn(-3.0f, 25.0f)) }

    // ── Per-Eye, Per-Direction Asymetrické Multiplikátory (-10.0–10.0, default 1.0) ──

    /** Levé oko — pohled doleva multiplikátor. Rozsah -10.0–10.0. Default 1.0. */
    var irisMotionGazeAmpLeftEyeLeft: Float
        get() =
                getFloatSafe(
                                KEY_IRISMOTION_GAZE_AMP_LEFT_EYE_LEFT,
                                DEFAULT_IRISMOTION_GAZE_AMP_LEFT_EYE_LEFT
                        )
                        .coerceIn(-10.0f, 10.0f)
        set(v) =
                prefs.edit {
                    putFloat(KEY_IRISMOTION_GAZE_AMP_LEFT_EYE_LEFT, v.coerceIn(-10.0f, 10.0f))
                }

    /** Levé oko — pohled doprava multiplikátor. Rozsah -10.0–10.0. Default 1.0. */
    var irisMotionGazeAmpLeftEyeRight: Float
        get() =
                getFloatSafe(
                                KEY_IRISMOTION_GAZE_AMP_LEFT_EYE_RIGHT,
                                DEFAULT_IRISMOTION_GAZE_AMP_LEFT_EYE_RIGHT
                        )
                        .coerceIn(-10.0f, 10.0f)
        set(v) =
                prefs.edit {
                    putFloat(KEY_IRISMOTION_GAZE_AMP_LEFT_EYE_RIGHT, v.coerceIn(-10.0f, 10.0f))
                }

    /** Pravé oko — pohled doleva multiplikátor. Rozsah -10.0–10.0. Default 1.0. */
    var irisMotionGazeAmpRightEyeLeft: Float
        get() =
                getFloatSafe(
                                KEY_IRISMOTION_GAZE_AMP_RIGHT_EYE_LEFT,
                                DEFAULT_IRISMOTION_GAZE_AMP_RIGHT_EYE_LEFT
                        )
                        .coerceIn(-10.0f, 10.0f)
        set(v) =
                prefs.edit {
                    putFloat(KEY_IRISMOTION_GAZE_AMP_RIGHT_EYE_LEFT, v.coerceIn(-10.0f, 10.0f))
                }

    /** Pravé oko — pohled doprava multiplikátor. Rozsah -10.0–10.0. Default 1.0. */
    var irisMotionGazeAmpRightEyeRight: Float
        get() =
                getFloatSafe(
                                KEY_IRISMOTION_GAZE_AMP_RIGHT_EYE_RIGHT,
                                DEFAULT_IRISMOTION_GAZE_AMP_RIGHT_EYE_RIGHT
                        )
                        .coerceIn(-10.0f, 10.0f)
        set(v) =
                prefs.edit {
                    putFloat(KEY_IRISMOTION_GAZE_AMP_RIGHT_EYE_RIGHT, v.coerceIn(-10.0f, 10.0f))
                }

    /** Pořadí a aktivace filtrů: Median, AlphaBeta (Kalman proxy), OneEuro */
    var irisMotionFilterSequence: List<String>
        get() =
                (prefs.getString(KEY_IRISMOTION_FILTER_SEQUENCE, DEFAULT_IRISMOTION_FILTER_SEQUENCE)
                                ?: DEFAULT_IRISMOTION_FILTER_SEQUENCE)
                        .split(",")
                        .map { it.trim().uppercase() }
                        .filter { it.isNotEmpty() }
        set(v) =
                prefs.edit {
                    putString(KEY_IRISMOTION_FILTER_SEQUENCE, v.joinToString(",").uppercase())
                }

    /** Mrtvá zóna pro DwellClickEngine (px). Rozsah 5–150. Profi: 5–10% viewport. */
    var irisMotionDeadzonePx: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_DEADZONE_PX, DEFAULT_IRISMOTION_DEADZONE_PX)
                        .coerceIn(5f, 150f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_DEADZONE_PX, v.coerceIn(5f, 150f)) }

    /** Velikost okna mediánového pre-filtru (liché 1–9). 1 = vypnuto. */
    var irisMotionMedianWindow: Int
        get() =
                getIntSafe(KEY_IRISMOTION_MEDIAN_WINDOW, DEFAULT_IRISMOTION_MEDIAN_WINDOW)
                        .coerceIn(1, 9)
        set(v) = prefs.edit { putInt(KEY_IRISMOTION_MEDIAN_WINDOW, v.coerceIn(1, 9)) }

    var irisMotionAutoCenter: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_AUTO_CENTER, DEFAULT_IRISMOTION_AUTO_CENTER)
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_AUTO_CENTER, v) }

    var irisMotionAutoCenterSpeed: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_AUTO_CENTER_SPEED, DEFAULT_IRISMOTION_AUTO_CENTER_SPEED)
                        .coerceIn(0.01f, 1.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_AUTO_CENTER_SPEED, v.coerceIn(0.01f, 1.0f)) }

    var irisMotionAutoCenterDelayMs: Long
        get() =
                getLongSafe(
                                KEY_IRISMOTION_AUTO_CENTER_DELAY_MS,
                                DEFAULT_IRISMOTION_AUTO_CENTER_DELAY_MS
                        )
                        .coerceIn(1L, 5000L)
        set(v) = prefs.edit { putLong(KEY_IRISMOTION_AUTO_CENTER_DELAY_MS, v.coerceIn(1L, 5000L)) }

    var irisMotionLeftBlinkTimeout: Long
        get() =
                getLongSafe(
                        KEY_IRISMOTION_LEFT_BLINK_TIMEOUT,
                        DEFAULT_IRISMOTION_LEFT_BLINK_TIMEOUT
                )
        set(value) = prefs.edit { putLong(KEY_IRISMOTION_LEFT_BLINK_TIMEOUT, value) }

    var irisMotionRightBlinkTimeout: Long
        get() =
                getLongSafe(
                        KEY_IRISMOTION_RIGHT_BLINK_TIMEOUT,
                        DEFAULT_IRISMOTION_RIGHT_BLINK_TIMEOUT
                )
        set(value) = prefs.edit { putLong(KEY_IRISMOTION_RIGHT_BLINK_TIMEOUT, value) }

    var irisMotionBothBlinkTimeout: Long
        get() =
                getLongSafe(
                        KEY_IRISMOTION_BOTH_BLINK_TIMEOUT,
                        DEFAULT_IRISMOTION_BOTH_BLINK_TIMEOUT
                )
        set(value) = prefs.edit { putLong(KEY_IRISMOTION_BOTH_BLINK_TIMEOUT, value) }

    var irisMotionSmileTimeout: Long
        get() =
                getLongSafe(
                        KEY_IRISMOTION_SMILE_TIMEOUT,
                        DEFAULT_IRISMOTION_SMILE_TIMEOUT
                )
        set(value) = prefs.edit { putLong(KEY_IRISMOTION_SMILE_TIMEOUT, value) }

    var irisMotionFrownTimeout: Long
        get() =
                getLongSafe(
                        KEY_IRISMOTION_FROWN_TIMEOUT,
                        DEFAULT_IRISMOTION_FROWN_TIMEOUT
                )
        set(value) = prefs.edit { putLong(KEY_IRISMOTION_FROWN_TIMEOUT, value) }

    var irisMotionExpressionsOneEuroEnabled: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_EXPRESSIONS_ONEEURO_ENABLED, DEFAULT_IRISMOTION_EXPRESSIONS_ONEEURO_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_EXPRESSIONS_ONEEURO_ENABLED, v) }

    var irisMotionExpressionsCrosstalkEnabled: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_EXPRESSIONS_CROSSTALK_ENABLED, DEFAULT_IRISMOTION_EXPRESSIONS_CROSSTALK_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_EXPRESSIONS_CROSSTALK_ENABLED, v) }

    var irisMotionExpressionsAbtEnabled: Boolean
        get() = prefs.getBoolean(KEY_IRISMOTION_EXPRESSIONS_ABT_ENABLED, DEFAULT_IRISMOTION_EXPRESSIONS_ABT_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_EXPRESSIONS_ABT_ENABLED, v) }

    var irisMotionRaiseEyebrowsTimeout: Long
        get() =
                getLongSafe(
                        KEY_IRISMOTION_RAISE_EYEBROWS_TIMEOUT,
                        DEFAULT_IRISMOTION_RAISE_EYEBROWS_TIMEOUT
                )
        set(value) = prefs.edit { putLong(KEY_IRISMOTION_RAISE_EYEBROWS_TIMEOUT, value) }

    var irisMotionSmileThreshold: Float
        get() =
                getFloatSafe(
                        KEY_IRISMOTION_SMILE_THRESHOLD,
                        DEFAULT_IRISMOTION_SMILE_THRESHOLD
                ).coerceIn(0.01f, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_IRISMOTION_SMILE_THRESHOLD, value.coerceIn(0.01f, 1.0f)) }

    var irisMotionFrownThreshold: Float
        get() =
                getFloatSafe(
                        KEY_IRISMOTION_FROWN_THRESHOLD,
                        DEFAULT_IRISMOTION_FROWN_THRESHOLD
                ).coerceIn(0.01f, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_IRISMOTION_FROWN_THRESHOLD, value.coerceIn(0.01f, 1.0f)) }

    var irisMotionRaiseEyebrowsThreshold: Float
        get() =
                getFloatSafe(
                        KEY_IRISMOTION_RAISE_EYEBROWS_THRESHOLD,
                        DEFAULT_IRISMOTION_RAISE_EYEBROWS_THRESHOLD
                ).coerceIn(0.01f, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_IRISMOTION_RAISE_EYEBROWS_THRESHOLD, value.coerceIn(0.01f, 1.0f)) }

    var irisMotionLeftBlinkAction: Int
        get() = getIntSafe(KEY_IRISMOTION_LEFT_BLINK_ACTION, DEFAULT_IRISMOTION_LEFT_BLINK_ACTION)
        set(value) = prefs.edit { putInt(KEY_IRISMOTION_LEFT_BLINK_ACTION, value) }

    var irisMotionRightBlinkAction: Int
        get() = getIntSafe(KEY_IRISMOTION_RIGHT_BLINK_ACTION, DEFAULT_IRISMOTION_RIGHT_BLINK_ACTION)
        set(value) = prefs.edit { putInt(KEY_IRISMOTION_RIGHT_BLINK_ACTION, value) }

    var irisMotionBothBlinkAction: Int
        get() = getIntSafe(KEY_IRISMOTION_BOTH_BLINK_ACTION, DEFAULT_IRISMOTION_BOTH_BLINK_ACTION)
        set(value) = prefs.edit { putInt(KEY_IRISMOTION_BOTH_BLINK_ACTION, value) }
        
    var irisMotionSmileAction: Int
        get() = getIntSafe(KEY_IRISMOTION_SMILE_ACTION, DEFAULT_IRISMOTION_SMILE_ACTION)
        set(value) = prefs.edit { putInt(KEY_IRISMOTION_SMILE_ACTION, value) }

    var irisMotionFrownAction: Int
        get() = getIntSafe(KEY_IRISMOTION_FROWN_ACTION, DEFAULT_IRISMOTION_FROWN_ACTION)
        set(value) = prefs.edit { putInt(KEY_IRISMOTION_FROWN_ACTION, value) }

    var irisMotionRaiseEyebrowsAction: Int
        get() = getIntSafe(KEY_IRISMOTION_RAISE_EYEBROWS_ACTION, DEFAULT_IRISMOTION_RAISE_EYEBROWS_ACTION)
        set(value) = prefs.edit { putInt(KEY_IRISMOTION_RAISE_EYEBROWS_ACTION, value) }

    var irisMotionBothBlinkApp: String
        get() =
                prefs.getString(KEY_IRISMOTION_BOTH_BLINK_APP, DEFAULT_IRISMOTION_BOTH_BLINK_APP)
                        ?: DEFAULT_IRISMOTION_BOTH_BLINK_APP
        set(value) = prefs.edit { putString(KEY_IRISMOTION_BOTH_BLINK_APP, value) }

    /**
     * Head Tracking DoF přepínač: 2 = Yaw+Pitch (default, accessibility), 4 = +Roll+TranslateZ, 6 =
     * plný 6DoF (Yaw+Pitch+Roll+TX+TY+TZ). Validní hodnoty: 2, 4, 6. Coerce na nejbližší validní.
     */
    var irisMotionHeadTrackingDoF: Int
        get() {
            val raw =
                    getIntSafe(
                            KEY_IRISMOTION_HEAD_TRACKING_DOF,
                            DEFAULT_IRISMOTION_HEAD_TRACKING_DOF
                    )
            return when {
                raw <= 2 -> 2
                raw <= 4 -> 4
                else -> 6
            }
        }
        set(v) {
            val valid =
                    when {
                        v <= 2 -> 2
                        v <= 4 -> 4
                        else -> 6
                    }
            prefs.edit { putInt(KEY_IRISMOTION_HEAD_TRACKING_DOF, valid) }
        }

    /**
     * Head Compensation Gain — síla kompenzace laterální translace hlavy. Aktivní pouze v 6DoF
     * režimu. Rozsah 0.0–1.0. Default 0.3. 0.0 = žádná kompenzace, 1.0 = plná kompenzace
     * (agresivní).
     */
    var irisMotionHeadCompensationGain: Float
        get() =
                getFloatSafe(
                                KEY_IRISMOTION_HEAD_COMPENSATION_GAIN,
                                DEFAULT_IRISMOTION_HEAD_COMPENSATION_GAIN
                        )
                        .coerceIn(0.0f, 1.0f)
        set(v) =
                prefs.edit {
                    putFloat(KEY_IRISMOTION_HEAD_COMPENSATION_GAIN, v.coerceIn(0.0f, 1.0f))
                }

    /**
     * Base Speed Multiplier — celkový rychlostní koeficient kurzoru. Ovlivňuje převod normalizované
     * delty na pixely. Rozsah 0.0–10.0. Default 1.6.
     */
    var irisMotionBaseSpeed: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_BASE_SPEED, DEFAULT_IRISMOTION_BASE_SPEED)
                        .coerceIn(0.0f, 10.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_BASE_SPEED, v.coerceIn(0.01f, 10.0f)) }

    /**
     * Re-Engagement Timeout — doba trvání Anti-Snapback rampy po otevření oka (ms).
     * Patent-Grade: RIT Frozen Position + LMU Blink Interpolation.
     * Rozsah 5–300ms. Default 266ms (~8 framů při 30fps).
     */
    var irisMotionReEngagementTimeout: Long
        get() = prefs.getLong(KEY_IRISMOTION_REENGAGEMENT_TIMEOUT,
                DEFAULT_IRISMOTION_REENGAGEMENT_TIMEOUT).coerceIn(5L, 300L)
        set(v) = prefs.edit { putLong(KEY_IRISMOTION_REENGAGEMENT_TIMEOUT, v.coerceIn(5L, 300L)) }

    var irisMotionDuchennePenalty: Float
        get() = getFloatSafe(KEY_IRISMOTION_DUCHENNE_PENALTY, DEFAULT_IRISMOTION_DUCHENNE_PENALTY).coerceIn(0.01f, 1.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_DUCHENNE_PENALTY, v.coerceIn(0.01f, 1.0f)) }

    var irisMotionCameraZoom: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_CAMERA_ZOOM, DEFAULT_IRISMOTION_CAMERA_ZOOM)
                        .coerceIn(1.0f, 4.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_CAMERA_ZOOM, v.coerceIn(1.0f, 4.0f)) }

    var irisMotionSaccadeVelocity: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_SACCADE_VELOCITY, DEFAULT_IRISMOTION_SACCADE_VELOCITY)
                        .coerceIn(0.1f, 10.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_SACCADE_VELOCITY, v.coerceIn(0.1f, 10.0f)) }

    var irisMotionSaccadeBox: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_SACCADE_BOX, DEFAULT_IRISMOTION_SACCADE_BOX)
                        .coerceIn(0.001f, 0.1f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_SACCADE_BOX, v.coerceIn(0.001f, 0.1f)) }

    // Sensor Fusion: Váha Neural Blendshapes vůči 3D IK (0.0 = čistá fyzika, 1.0 = čistý NPU
    // Neural)
    var irisMotionNeuralWeight: Float
        get() =
                getFloatSafe(KEY_IRISMOTION_NEURAL_WEIGHT, DEFAULT_IRISMOTION_NEURAL_WEIGHT)
                        .coerceIn(0.0f, 1.0f)
        set(v) = prefs.edit { putFloat(KEY_IRISMOTION_NEURAL_WEIGHT, v.coerceIn(0.0f, 1.0f)) }

    // Tracking Paradigm: Absolutní (Gaze Pointing) vs Relativní (HW Mouse)
    var irisMotionTrackingAbsolute: Boolean
        get() =
                prefs.getBoolean(
                        KEY_IRISMOTION_TRACKING_ABSOLUTE,
                        DEFAULT_IRISMOTION_TRACKING_ABSOLUTE
                )
        set(v) = prefs.edit { putBoolean(KEY_IRISMOTION_TRACKING_ABSOLUTE, v) }

    // OmniParser
    var smartVisionOmniParserEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMARTVISION_OMNIPARSER_ENABLED, DEFAULT_SMARTVISION_OMNIPARSER_ENABLED)
        set(v) = prefs.edit { putBoolean(KEY_SMARTVISION_OMNIPARSER_ENABLED, v) }

    var smartVisionFilterEmptyImageView: Boolean
        get() = prefs.getBoolean(KEY_SMARTVISION_FILTER_EMPTY_IMAGEVIEW, DEFAULT_SMARTVISION_FILTER_EMPTY_IMAGEVIEW)
        set(v) = prefs.edit { putBoolean(KEY_SMARTVISION_FILTER_EMPTY_IMAGEVIEW, v) }

    var smartVisionFilterNonInteractive: Boolean
        get() = prefs.getBoolean(KEY_SMARTVISION_FILTER_NON_INTERACTIVE, DEFAULT_SMARTVISION_FILTER_NON_INTERACTIVE)
        set(v) = prefs.edit { putBoolean(KEY_SMARTVISION_FILTER_NON_INTERACTIVE, v) }

    var smartVisionFilterScrollable: Boolean
        get() = prefs.getBoolean(KEY_SMARTVISION_FILTER_SCROLLABLE, DEFAULT_SMARTVISION_FILTER_SCROLLABLE)
        set(v) = prefs.edit { putBoolean(KEY_SMARTVISION_FILTER_SCROLLABLE, v) }

    var smartVisionFilterWebView: Boolean
        get() = prefs.getBoolean(KEY_SMARTVISION_FILTER_WEBVIEW, DEFAULT_SMARTVISION_FILTER_WEBVIEW)
        set(v) = prefs.edit { putBoolean(KEY_SMARTVISION_FILTER_WEBVIEW, v) }

    var smartVisionFilterEmptyEditText: Boolean
        get() = prefs.getBoolean(KEY_SMARTVISION_FILTER_EMPTY_EDITTEXT, DEFAULT_SMARTVISION_FILTER_EMPTY_EDITTEXT)
        set(v) = prefs.edit { putBoolean(KEY_SMARTVISION_FILTER_EMPTY_EDITTEXT, v) }

    var smartVisionFilterOffscreen: Boolean
        get() = prefs.getBoolean(KEY_SMARTVISION_FILTER_OFFSCREEN, DEFAULT_SMARTVISION_FILTER_OFFSCREEN)
        set(v) = prefs.edit { putBoolean(KEY_SMARTVISION_FILTER_OFFSCREEN, v) }

    var smartVisionFilterContainers: Boolean
        get() = prefs.getBoolean(KEY_SMARTVISION_FILTER_CONTAINERS, DEFAULT_SMARTVISION_FILTER_CONTAINERS)
        set(v) = prefs.edit { putBoolean(KEY_SMARTVISION_FILTER_CONTAINERS, v) }

    var smartVisionMaxDepth: Int
        get() = getIntSafe(KEY_SMARTVISION_MAX_DEPTH, DEFAULT_SMARTVISION_MAX_DEPTH).coerceIn(1, 50)
        set(v) = prefs.edit { putInt(KEY_SMARTVISION_MAX_DEPTH, v.coerceIn(1, 50)) }

    var smartVisionTargetResolution: Int
        get() = prefs.getInt(KEY_SMARTVISION_TARGET_RESOLUTION, DEFAULT_SMARTVISION_TARGET_RESOLUTION).coerceIn(160, 4000)
        set(v) = prefs.edit { putInt(KEY_SMARTVISION_TARGET_RESOLUTION, v.coerceIn(160, 4000)) }
}
