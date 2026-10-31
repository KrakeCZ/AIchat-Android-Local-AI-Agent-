package com.example.llama.agent.perception

/**
 * AiCoreContracts — Doménové kontrakty pro AICore (Gemini Nano) GenAI API.
 *
 * Definuje stavový automat, error kódy a konfigurační enumy pro:
 * - Summarization, Proofreading, Rewriting, Prompt API
 * - Image Description, Speech Recognition
 *
 * Tyto kontrakty jsou sdílené mezi AiCoreMasterEngine, NlpSummaryWorker
 * a AgentCoordinator pro konzistentní stavový management.
 */

// ==================== STAVOVÝ AUTOMAT ====================

/**
 * Deterministický stavový automat pro AICore operace.
 *
 * Přechodový graf:
 *   Idle → Initializing → Generating → Success
 *                    ↘         ↘
 *                  SystemError  SystemError
 */
sealed class AiState {
    /** Počáteční stav — žádná operace neběží. */
    data object Idle : AiState()

    /** Model se stahuje nebo inicializuje (FeatureStatus check + download). */
    data class Initializing(val featureName: String) : AiState()

    /** Inference/generování probíhá (IPC volání do AICore systémové služby). */
    data class Generating(val featureName: String) : AiState()

    /** Úspěšný výsledek. */
    data class Success(val result: String) : AiState()

    /** Systémová chyba — nerecoverable v rámci aktuální operace. */
    data class SystemError(val code: ErrorCode, val message: String) : AiState()
}

// ==================== ERROR KÓDY ====================

/**
 * Enumerace chybových stavů pro AICore operace.
 * Mapují na reálné ML Kit GenAI exception typy.
 */
enum class ErrorCode {
    /** Zařízení nepodporuje AICore (chybí com.google.android.aicore). */
    DEVICE_NOT_SUPPORTED,

    /** Model není dostupný nebo se nepodařil stáhnout. */
    MODEL_UNAVAILABLE,

    /** Model se stahuje — operaci nelze provést. */
    MODEL_DOWNLOADING,

    /** Inference selhala (timeout, OOM, IPC crash). */
    GENERATION_FAILED,

    /** Zařízení je přehřáté — operace odložena (PowerManager thermal). */
    THERMAL_THROTTLE,

    /** EncryptedSharedPreferences Keystore corrupt — Self-Healing proběhne. */
    KEYSTORE_CORRUPT,

    /** AICore FeatureStatus vrátil neočekávaný stav. */
    FEATURE_NOT_AVAILABLE,

    /** Vstupní text překročil maximální délku pro dané API. */
    INPUT_TOO_LONG,

    /** Neznámá chyba. */
    UNKNOWN
}

// ==================== KONFIGURAČNÍ ENUMY ====================

/**
 * Výstupní formát sumarizace.
 * Mapuje přímo na ML Kit GenAI SummarizerOptions.OutputType.
 */
enum class SummaryOutputType {
    /** Jediný odrážkový bod s klíčovou myšlenkou. */
    ONE_BULLET,

    /** Tři odrážkové body s hlavními body. */
    THREE_BULLETS,

    /** Krátký souvislý text (~1-2 věty). */
    SHORT
}

/**
 * Typ vstupního textu pro sumarizaci.
 * Mapuje přímo na ML Kit GenAI SummarizerOptions.InputType.
 */
enum class SummaryInputType {
    /** Článek / delší text (blog, zpráva, dokumentace). */
    ARTICLE,

    /** Konverzace / dialog (chat, transkripty). */
    CONVERSATION
}

/**
 * Tón přepisu (Rewriting).
 * Mapuje přímo na ML Kit GenAI RewriterOptions.Tone.
 */
enum class RewriteTone {
    /** Rozšířit a detailněji vysvětlit. */
    ELABORATE,

    /** Přidat emoji a neformální tón. */
    EMOJIFY,

    /** Zkrátit a zestručnit. */
    SHORTEN,

    /** Přátelský, neformální tón. */
    FRIENDLY,

    /** Profesionální, formální tón. */
    PROFESSIONAL,

    /** Přeformulovat jinak (zachovat význam). */
    REPHRASE
}

/**
 * Jazyk pro korekturu (Proofreading).
 * Mapuje přímo na ML Kit GenAI ProofreaderOptions.Language.
 *
 * Poznámka: Čeština (CS) zatím není podporována v beta1.
 * Při požadavku na CS se provede fallback na EN.
 */
enum class ProofreadLanguage {
    EN, DE, FR, ES, IT, JA, KO
}

// ==================== RESULT WRAPPERY ====================

/**
 * Výsledek AICore operace.
 * Zapouzdřuje Success/Error stav s typovým kontraktem.
 */
sealed class AiCoreResult {
    data class Success(val text: String, val cached: Boolean = false) : AiCoreResult()
    data class Error(val code: ErrorCode, val message: String) : AiCoreResult()

    /** Pohodlný helper pro kontrolu úspěchu. */
    val isSuccess: Boolean get() = this is Success

    /** Bezpečné získání textu (vrací null pro Error). */
    fun textOrNull(): String? = (this as? Success)?.text
}
