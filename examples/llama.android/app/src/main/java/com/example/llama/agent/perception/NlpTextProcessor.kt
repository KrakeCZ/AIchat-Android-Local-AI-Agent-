package com.example.llama.agent.perception

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.smartreply.SmartReply
import com.google.mlkit.nl.smartreply.SmartReplySuggestionResult
import com.google.mlkit.nl.smartreply.TextMessage
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * ======================================================================== NLP TEXT PROCESSOR —
 * Enterprise Utility Layer (Tier 2.5)
 * ========================================================================
 *
 * Oddělené jednorazové NLP operace od batch komprese (NlpSummaryWorker). Poskytuje veřejné API pro
 * celý systém:
 * - Překlad (ML Kit Translate, 59 jazyků, ~30 MB/jazyk)
 * - Sentiment analýza (MediaPipe TextClassifier, MobileBERT ~28 MB z disku)
 * - Smart Reply (ML Kit Smart Reply, EN only)
 * - Language ID (ML Kit, 100+ jazyků)
 * ```
 *    - TODO  Realtime překlad mezi worker na vystuup MULTI > CZ  aneb transkriptem zachytit
 *    - audio  a přeložit z MULTI do CZ  , nebo TeXty
 * ```
 * Translate modely se stahují přes Google Play Services (RemoteModelManager). Sentiment model
 * (MobileBERT) se načítá z files/nlp_models/ (dynamický download).
 *
 * Thread Safety: Všechny metody jsou suspend a thread-safe.
 */
class NlpTextProcessor(private val context: Context) {

    companion object {
        private const val TAG = "NlpTextProcessor"
        private const val TRANSLATE_TIMEOUT_MS = 10_000L
        private const val SMART_REPLY_TIMEOUT_MS = 5_000L

        /** Adresář pro dynamicky stažené NLP modely (sentiment BERT atd.) */
        private const val NLP_MODELS_DIR = "nlp_models"

        /** Cesta k bundled default BERT modelu v assets/ (stažen Gradle taskem při buildu) */
        const val BUNDLED_SENTIMENT_MODEL = "nlp_models/bert_classifier.tflite"

        /**
         * Zkontroluje, zda je AICore (Gemini Nano) runtime k dispozici na zařízení. Kontroluje
         * existenci balíčku com.google.android.aicore.
         *
         * @param context Android Context
         * @return true pokud je AICore nainstalován a dostupný
         */
        fun isAICoreAvailable(context: Context): Boolean {
            return try {
                context.packageManager.getPackageInfo("com.google.android.aicore", 0)
                true
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Vrátí mapu všech 59 podporovaných jazyků pro ML Kit Translate. Klíč: BCP-47 kód, Hodnota:
         * Český název.
         */
        fun getAvailableLanguages(): Map<String, String> {
            return mapOf(
                    "af" to "Afrikánština",
                    "sq" to "Albánština",
                    "ar" to "Arabština",
                    "be" to "Běloruština",
                    "bn" to "Bengálština",
                    "bg" to "Bulharština",
                    "ca" to "Katalánština",
                    "zh" to "Čínština",
                    "hr" to "Chorvatština",
                    "cs" to "Čeština",
                    "da" to "Dánština",
                    "nl" to "Holandština",
                    "en" to "Angličtina",
                    "eo" to "Esperanto",
                    "et" to "Estonština",
                    "fi" to "Finština",
                    "fr" to "Francouzština",
                    "gl" to "Galicijština",
                    "ka" to "Gruzínština",
                    "de" to "Němčina",
                    "el" to "Řečtina",
                    "gu" to "Gudžarátština",
                    "ht" to "Haitština",
                    "he" to "Hebrejština",
                    "hi" to "Hindština",
                    "hu" to "Maďarština",
                    "is" to "Islandština",
                    "id" to "Indonéština",
                    "ga" to "Irština",
                    "it" to "Italština",
                    "ja" to "Japonština",
                    "kn" to "Kannadština",
                    "ko" to "Korejština",
                    "lv" to "Lotyština",
                    "lt" to "Litevština",
                    "mk" to "Makedonština",
                    "ms" to "Malajština",
                    "mt" to "Maltština",
                    "mr" to "Maráthština",
                    "no" to "Norština",
                    "fa" to "Perština",
                    "pl" to "Polština",
                    "pt" to "Portugalština",
                    "ro" to "Rumunština",
                    "ru" to "Ruština",
                    "sk" to "Slovenština",
                    "sl" to "Slovinština",
                    "es" to "Španělština",
                    "sw" to "Svahilština",
                    "sv" to "Švédština",
                    "tl" to "Tagalog",
                    "ta" to "Tamilština",
                    "te" to "Telugština",
                    "th" to "Thajština",
                    "tr" to "Turečtina",
                    "uk" to "Ukrajinština",
                    "ur" to "Urdština",
                    "vi" to "Vietnamština",
                    "cy" to "Velština"
            )
        }
    }

    // ==================== TRANSLATE ====================

    /** Cache aktivních překladačů (source→target → Translator instance) */
    private val translatorCache = mutableMapOf<String, Translator>()

    /**
     * Přeloží text z jednoho jazyka do druhého (offline po stažení modelu).
     *
     * Modely musí být předem staženy přes downloadLanguageModel(). Pokud model není stažen, metoda
     * vrátí originální text (graceful degradation).
     *
     * @param text Text k překladu
     * @param from BCP-47 zdrojový jazyk (např. "cs")
     * @param to BCP-47 cílový jazyk (např. "en")
     * @return Přeložený text nebo originál pokud překlad selže
     */
    suspend fun translateText(text: String, from: String, to: String): String =
            withContext(Dispatchers.Default) {
                if (text.isBlank()) return@withContext text
                if (from == to) return@withContext text

                val sourceCode =
                        mapToBcp47(from)
                                ?: run {
                                    Log.w(TAG, "⚠️ Neznámý zdrojový jazyk: $from")
                                    return@withContext text
                                }
                val targetCode =
                        mapToBcp47(to)
                                ?: run {
                                    Log.w(TAG, "⚠️ Neznámý cílový jazyk: $to")
                                    return@withContext text
                                }

                val cacheKey = "$sourceCode→$targetCode"
                val translator =
                        translatorCache.getOrPut(cacheKey) {
                            val options =
                                    TranslatorOptions.Builder()
                                            .setSourceLanguage(sourceCode)
                                            .setTargetLanguage(targetCode)
                                            .build()
                            Translation.getClient(options).also {
                                Log.i(TAG, "🔤 Translator vytvořen: $cacheKey")
                            }
                        }

                return@withContext try {
                    withTimeout(TRANSLATE_TIMEOUT_MS) {
                        // Stažení modelu pokud chybí (automatický trigger)
                        suspendCancellableCoroutine<Unit> { cont ->
                            translator
                                    .downloadModelIfNeeded()
                                    .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                                    .addOnFailureListener { e ->
                                        Log.w(
                                                TAG,
                                                "⚠️ Model download selhal pro $cacheKey: ${e.message}"
                                        )
                                        if (cont.isActive)
                                                cont.resume(Unit) // Pokračuj, překlad může selhat
                                    }
                        }

                        // Samotný překlad
                        suspendCancellableCoroutine<String> { cont ->
                            translator
                                    .translate(text)
                                    .addOnSuccessListener { translated ->
                                        Log.i(
                                                TAG,
                                                "✅ Překlad $cacheKey dokončen (${text.length}→${translated.length} znaků)"
                                        )
                                        if (cont.isActive) cont.resume(translated)
                                    }
                                    .addOnFailureListener { e ->
                                        Log.e(TAG, "❌ Překlad selhal pro $cacheKey: ${e.message}")
                                        if (cont.isActive) cont.resume(text) // Vrať originál
                                    }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "⏱️ Překlad timeout nebo chyba: ${e.message}")
                    text
                }
            }

    /**
     * Stáhne jazykový model pro offline překlad (~30 MB). Stahování probíhá přes Google Play
     * Services (RemoteModelManager).
     *
     * @param langCode BCP-47 kód jazyka (cs, en, de, ...)
     * @return true pokud stažení proběhlo úspěšně
     */
    suspend fun downloadLanguageModel(langCode: String): Boolean =
            withContext(Dispatchers.IO) {
                val bcp47 =
                        mapToBcp47(langCode)
                                ?: run {
                                    Log.w(TAG, "⚠️ Neznámý jazyk: $langCode")
                                    return@withContext false
                                }

                return@withContext try {
                    val model = TranslateRemoteModel.Builder(bcp47).build()
                    val conditions =
                            DownloadConditions.Builder()
                                    // .requireWifi() // ODSTRANĚNO: blokuje stahování na
                                    // emulátorech/mobilních datech
                                    .build()

                    // Ověření před stahováním (optimalizace)
                    val isAlreadyDownloaded = suspendCancellableCoroutine { cont ->
                        RemoteModelManager.getInstance()
                                .isModelDownloaded(model)
                                .addOnSuccessListener { isDownloaded ->
                                    if (cont.isActive) cont.resume(isDownloaded)
                                }
                                .addOnFailureListener { if (cont.isActive) cont.resume(false) }
                    }

                    if (isAlreadyDownloaded) {
                        Log.i(TAG, "✅ Jazykový model už byl stažen dodatečně ověřen: $langCode")
                        return@withContext true
                    }

                    suspendCancellableCoroutine { cont ->
                        RemoteModelManager.getInstance()
                                .download(model, conditions)
                                .addOnSuccessListener {
                                    // DODATEČNÁ KONTROLA - ML Kit občas vrací success i když není
                                    // staženo
                                    RemoteModelManager.getInstance()
                                            .isModelDownloaded(model)
                                            .addOnSuccessListener { actuallyDownloaded ->
                                                if (actuallyDownloaded) {
                                                    Log.i(
                                                            TAG,
                                                            "✅ Jazykový model reálně stažen: $langCode"
                                                    )
                                                    if (cont.isActive) cont.resume(true)
                                                } else {
                                                    Log.e(
                                                            TAG,
                                                            "❌ False positive: Model $langCode hlásí staženo, ale soubory chybí!"
                                                    )
                                                    if (cont.isActive) cont.resume(false)
                                                }
                                            }
                                            .addOnFailureListener {
                                                if (cont.isActive) cont.resume(false)
                                            }
                                }
                                .addOnFailureListener { e ->
                                    Log.e(
                                            TAG,
                                            "❌ Stažení modelu selhalo pro $langCode: ${e.message}"
                                    )
                                    if (cont.isActive) cont.resume(false)
                                }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Download model chyba: ${e.message}")
                    false
                }
            }

    /**
     * Smaže stažený jazykový model (~30 MB uvolněno).
     *
     * @param langCode BCP-47 kód jazyka
     * @return true pokud smazání proběhlo úspěšně
     */
    suspend fun deleteLanguageModel(langCode: String): Boolean =
            withContext(Dispatchers.IO) {
                val bcp47 = mapToBcp47(langCode) ?: return@withContext false

                return@withContext try {
                    val model = TranslateRemoteModel.Builder(bcp47).build()
                    suspendCancellableCoroutine { cont ->
                        RemoteModelManager.getInstance()
                                .deleteDownloadedModel(model)
                                .addOnSuccessListener {
                                    Log.i(TAG, "✅ Jazykový model smazán: $langCode")
                                    if (cont.isActive) cont.resume(true)
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "❌ Smazání modelu selhalo: ${e.message}")
                                    if (cont.isActive) cont.resume(false)
                                }
                    }
                } catch (_: Exception) {
                    false
                }
            }

    /**
     * Zjistí, které jazykové modely jsou stažené na zařízení.
     *
     * @return Seznam BCP-47 kódů stažených jazyků
     */
    suspend fun getDownloadedLanguages(): List<String> =
            withContext(Dispatchers.IO) {
                return@withContext try {
                    suspendCancellableCoroutine { cont ->
                        RemoteModelManager.getInstance()
                                .getDownloadedModels(TranslateRemoteModel::class.java)
                                .addOnSuccessListener { models ->
                                    val langs = models.map { it.language }
                                    Log.i(TAG, "📦 Stažené jazyky: ${langs.joinToString()}")
                                    if (cont.isActive) cont.resume(langs)
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "❌ Nelze zjistit stažené modely: ${e.message}")
                                    if (cont.isActive) cont.resume(emptyList())
                                }
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }

    /**
     * Stáhne jazykové modely pro dva jazyky najednou (typicky CS + EN). Convenience metoda pro UI
     * tlačítko v Settings → NLP.
     *
     * @param from Zdrojový jazyk (např. "cs")
     * @param to Cílový jazyk (např. "en")
     * @return true pokud oba modely staženy úspěšně
     */
    suspend fun downloadTranslateModels(from: String, to: String): Boolean =
            withContext(Dispatchers.IO) {
                val resultFrom = downloadLanguageModel(from)
                val resultTo = downloadLanguageModel(to)
                Log.i(TAG, "📦 Download models: $from=$resultFrom, $to=$resultTo")
                return@withContext resultFrom && resultTo
            }

    /** Vrátí seznam všech 59 podporovaných jazyků ML Kit Translate. */
    fun getAvailableLanguages(): List<LanguageInfo> {
        return TranslateLanguage.getAllLanguages().map { langCode ->
            LanguageInfo(code = langCode, displayName = java.util.Locale(langCode).displayLanguage)
        }
    }

    /** Info o dostupném jazyce. */
    data class LanguageInfo(
            val code: String, // BCP-47 (cs, en, de, ...)
            val displayName: String // Lokalizovaný název (Czech, English, German, ...)
    )

    // ==================== SMART REPLY ====================

    /**
     * Generuje chytré odpovědi na konverzaci (ML Kit Smart Reply).
     *
     * OMEZENÍ: Funguje POUZE v angličtině. Pro CZ konverzace je nutný překlad CZ→EN TODO.
     *
     * @param conversation Seznam zpráv (min. 1, max. 10 posledních)
     * @param isLocalUser Lambda: vrací true pokud zpráva je od lokálního uživatele
     * @return Seznam navrhovaných odpovědí (max 3) nebo prázdný list
     */
    suspend fun generateSmartReplies(conversation: List<SmartReplyMessage>): List<String> =
            withContext(Dispatchers.Default) {
                if (conversation.isEmpty()) return@withContext emptyList()

                return@withContext try {
                    withTimeout(SMART_REPLY_TIMEOUT_MS) {
                        val mlConversation =
                                conversation.takeLast(10).map { msg ->
                                    if (msg.isLocalUser) {
                                        TextMessage.createForLocalUser(msg.text, msg.timestampMs)
                                    } else {
                                        TextMessage.createForRemoteUser(
                                                msg.text,
                                                msg.timestampMs,
                                                msg.userId
                                        )
                                    }
                                }

                        suspendCancellableCoroutine { cont ->
                            SmartReply.getClient()
                                    .suggestReplies(mlConversation)
                                    .addOnSuccessListener { result ->
                                        if (result.status ==
                                                        SmartReplySuggestionResult.STATUS_SUCCESS
                                        ) {
                                            val suggestions = result.suggestions.map { it.text }
                                            Log.i(
                                                    TAG,
                                                    "💬 Smart Reply: ${suggestions.joinToString()}"
                                            )
                                            if (cont.isActive) cont.resume(suggestions)
                                        } else {
                                            Log.w(TAG, "⚠️ Smart Reply status: ${result.status}")
                                            if (cont.isActive) cont.resume(emptyList())
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        Log.w(TAG, "⚠️ Smart Reply selhala: ${e.message}")
                                        if (cont.isActive) cont.resume(emptyList())
                                    }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⏱️ Smart Reply timeout: ${e.message}")
                    emptyList()
                }
            }

    /** Zpráva pro Smart Reply konverzaci. */
    data class SmartReplyMessage(
            val text: String,
            val timestampMs: Long,
            val isLocalUser: Boolean,
            val userId: String = "remote_user"
    )

    // ==================== SENTIMENT ANALYSIS ====================

    /**
     * Sentiment analýza textu pomocí MediaPipe TextClassifier (MobileBERT).
     *
     * Priorita modelu:
     * 1. Custom model z NlpModelRepository (uživatel importoval přes Settings → NLP)
     * 2. Bundled default model z assets/nlp_models/bert_classifier.tflite (~28 MB)
     *
     * @param text Text k analýze
     * @return "positive", "negative", "neutral" nebo "unknown"
     */
    suspend fun classifySentiment(text: String): String =
            withContext(Dispatchers.Default) {
                if (text.isBlank()) return@withContext "unknown"

                // 1. Priorita: custom model z DB (uživatel importoval vlastní fine-tuned BERT)
                val repository = com.example.llama.data.NlpModelRepository(context)
                val customModelPath = repository.getActiveModelPath()

                val modelSource: String // Popis zdroje pro logy
                val baseOptionsBuilder = com.google.mediapipe.tasks.core.BaseOptions.builder()

                if (customModelPath != null) {
                    // Custom model z filesDir (importovaný uživatelem)
                    baseOptionsBuilder.setModelAssetPath(customModelPath)
                    modelSource = "custom: $customModelPath"
                } else {
                    // 2. Bundled model z assets/
                    baseOptionsBuilder.setModelAssetPath(BUNDLED_SENTIMENT_MODEL)
                    modelSource = "bundled: $BUNDLED_SENTIMENT_MODEL"
                }

                return@withContext try {
                    val baseOptions = baseOptionsBuilder.build()
                    val options =
                            com.google.mediapipe.tasks.text.textclassifier.TextClassifier
                                    .TextClassifierOptions.builder()
                                    .setBaseOptions(baseOptions)
                                    .build()

                    val classifier =
                            com.google.mediapipe.tasks.text.textclassifier.TextClassifier
                                    .createFromOptions(context, options)

                    val result = classifier.classify(text)
                    classifier.close()

                    val topCategory =
                            result.classificationResult()
                                    .classifications()
                                    .firstOrNull()
                                    ?.categories()
                                    ?.maxByOrNull { it.score() }

                    val sentiment = topCategory?.categoryName()?.lowercase() ?: "neutral"
                    val confidence = topCategory?.score() ?: 0f
                    Log.i(TAG, "🎭 Sentiment: $sentiment ($confidence) [$modelSource]")
                    sentiment
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Sentiment analýza selhala [$modelSource]: ${e.message}")
                    "unknown"
                }
            }

    /**
     * Zkontroluje dostupnost sentiment modelu.
     *
     * @return true pokud je dostupný custom model (DB) NEBO bundled model (assets)
     */
    fun isSentimentModelAvailable(): Boolean {
        // Custom model v filesDir?
        val customDir = File(context.filesDir, NLP_MODELS_DIR)
        val hasCustom = customDir.listFiles()?.any { it.extension == "tflite" } == true

        // Bundled model v assets?
        val hasBundled =
                try {
                    context.assets.open(BUNDLED_SENTIMENT_MODEL).use { true }
                } catch (_: Exception) {
                    false
                }

        return hasCustom || hasBundled
    }

    /** Vrátí cestu k adresáři pro NLP modely (custom imported). */
    fun getNlpModelsDir(): File {
        val dir = File(context.filesDir, NLP_MODELS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ==================== LANGUAGE ID (Convenience) ====================

    /**
     * Convenience wrapper pro Language Identification. Deleguje na NlpSummaryWorker jako sdílený
     * singleton (pokud existuje), nebo běží nezávisle.
     */
    suspend fun identifyLanguage(text: String): String =
            withContext(Dispatchers.Default) {
                if (text.length < 20) return@withContext "und"

                return@withContext try {
                    withTimeout(5000L) {
                        suspendCancellableCoroutine { cont ->
                            LanguageIdentification.getClient()
                                    .identifyLanguage(text)
                                    .addOnSuccessListener { lang ->
                                        if (cont.isActive) cont.resume(lang ?: "und")
                                    }
                                    .addOnFailureListener { if (cont.isActive) cont.resume("und") }
                        }
                    }
                } catch (_: Exception) {
                    "und"
                }
            }

    // ==================== ENTITY EXTRACTION (NER) ====================

    data class EntityInfo(val type: String, val text: String)

    /**
     * Extrahuje entity z textu pomocí ML Kit Entity Extraction. Podporuje adresy, data, časy, URL,
     * telefony, e-maily atd.
     */
    suspend fun extractEntities(text: String): List<EntityInfo> =
            withContext(Dispatchers.Default) {
                if (text.isBlank()) return@withContext emptyList()

                try {
                    val entityExtractor =
                            com.google.mlkit.nl.entityextraction.EntityExtraction.getClient(
                                    com.google.mlkit.nl.entityextraction.EntityExtractorOptions
                                            .Builder(
                                                    com.google.mlkit.nl.entityextraction
                                                            .EntityExtractorOptions.ENGLISH
                                            )
                                            .build()
                            )

                    withTimeout(10_000L) {
                        suspendCancellableCoroutine { cont ->
                            entityExtractor
                                    .downloadModelIfNeeded()
                                    .addOnSuccessListener {
                                        entityExtractor
                                                .annotate(text)
                                                .addOnSuccessListener { annotations ->
                                                    val results =
                                                            annotations.flatMap { annotation ->
                                                                annotation.entities.map { entity ->
                                                                    val typeStr =
                                                                            when (entity.type) {
                                                                                com.google.mlkit.nl
                                                                                        .entityextraction
                                                                                        .Entity
                                                                                        .TYPE_ADDRESS ->
                                                                                        "Adresa"
                                                                                com.google.mlkit.nl
                                                                                        .entityextraction
                                                                                        .Entity
                                                                                        .TYPE_DATE_TIME ->
                                                                                        "Čas/Datum"
                                                                                com.google.mlkit.nl
                                                                                        .entityextraction
                                                                                        .Entity
                                                                                        .TYPE_EMAIL ->
                                                                                        "E-mail"
                                                                                com.google.mlkit.nl
                                                                                        .entityextraction
                                                                                        .Entity
                                                                                        .TYPE_FLIGHT_NUMBER ->
                                                                                        "Letadlo"
                                                                                com.google.mlkit.nl
                                                                                        .entityextraction
                                                                                        .Entity
                                                                                        .TYPE_IBAN ->
                                                                                        "IBAN"
                                                                                com.google.mlkit.nl
                                                                                        .entityextraction
                                                                                        .Entity
                                                                                        .TYPE_ISBN ->
                                                                                        "ISBN"
                                                                                com.google.mlkit.nl
                                                                                        .entityextraction
                                                                                        .Entity
                                                                                        .TYPE_MONEY ->
                                                                                        "Peníze"
                                                                                com.google.mlkit.nl
                                                                                        .entityextraction
                                                                                        .Entity
                                                                                        .TYPE_PAYMENT_CARD ->
                                                                                        "Platební karta"
                                                                                com.google.mlkit.nl
                                                                                        .entityextraction
                                                                                        .Entity
                                                                                        .TYPE_PHONE ->
                                                                                        "Telefon"
                                                                                com.google.mlkit.nl
                                                                                        .entityextraction
                                                                                        .Entity
                                                                                        .TYPE_TRACKING_NUMBER ->
                                                                                        "Sledovací číslo"
                                                                                com.google.mlkit.nl
                                                                                        .entityextraction
                                                                                        .Entity
                                                                                        .TYPE_URL ->
                                                                                        "URL"
                                                                                else -> "Neznámá"
                                                                            }
                                                                    EntityInfo(
                                                                            typeStr,
                                                                            annotation.annotatedText
                                                                    )
                                                                }
                                                            }
                                                    if (cont.isActive) cont.resume(results)
                                                }
                                                .addOnFailureListener {
                                                    if (cont.isActive) cont.resume(emptyList())
                                                }
                                    }
                                    .addOnFailureListener {
                                        if (cont.isActive) cont.resume(emptyList())
                                    }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ NER extraction failed: ${e.message}")
                    emptyList()
                }
            }

    // ==================== UTILITY ====================

    /**
     * Mapuje zkrácený kód jazyka na ML Kit TranslateLanguage konstantu. ML Kit používá vlastní
     * kódy, ne vždy shodné s ISO 639-1.
     */
    private fun mapToBcp47(langCode: String): String? {
        val code = langCode.lowercase().take(2)
        return when (code) {
            "af" -> TranslateLanguage.AFRIKAANS
            "sq" -> TranslateLanguage.ALBANIAN
            "ar" -> TranslateLanguage.ARABIC
            "be" -> TranslateLanguage.BELARUSIAN
            "bn" -> TranslateLanguage.BENGALI
            "bg" -> TranslateLanguage.BULGARIAN
            "ca" -> TranslateLanguage.CATALAN
            "zh" -> TranslateLanguage.CHINESE
            "hr" -> TranslateLanguage.CROATIAN
            "cs" -> TranslateLanguage.CZECH
            "da" -> TranslateLanguage.DANISH
            "nl" -> TranslateLanguage.DUTCH
            "en" -> TranslateLanguage.ENGLISH
            "eo" -> TranslateLanguage.ESPERANTO
            "et" -> TranslateLanguage.ESTONIAN
            "fi" -> TranslateLanguage.FINNISH
            "fr" -> TranslateLanguage.FRENCH
            "gl" -> TranslateLanguage.GALICIAN
            "ka" -> TranslateLanguage.GEORGIAN
            "de" -> TranslateLanguage.GERMAN
            "el" -> TranslateLanguage.GREEK
            "gu" -> TranslateLanguage.GUJARATI
            "ht" -> TranslateLanguage.HAITIAN_CREOLE
            "he" -> TranslateLanguage.HEBREW
            "hi" -> TranslateLanguage.HINDI
            "hu" -> TranslateLanguage.HUNGARIAN
            "is" -> TranslateLanguage.ICELANDIC
            "id" -> TranslateLanguage.INDONESIAN
            "ga" -> TranslateLanguage.IRISH
            "it" -> TranslateLanguage.ITALIAN
            "ja" -> TranslateLanguage.JAPANESE
            "kn" -> TranslateLanguage.KANNADA
            "ko" -> TranslateLanguage.KOREAN
            "lv" -> TranslateLanguage.LATVIAN
            "lt" -> TranslateLanguage.LITHUANIAN
            "mk" -> TranslateLanguage.MACEDONIAN
            "ms" -> TranslateLanguage.MALAY
            "mt" -> TranslateLanguage.MALTESE
            "mr" -> TranslateLanguage.MARATHI
            "no" -> TranslateLanguage.NORWEGIAN
            "fa" -> TranslateLanguage.PERSIAN
            "pl" -> TranslateLanguage.POLISH
            "pt" -> TranslateLanguage.PORTUGUESE
            "ro" -> TranslateLanguage.ROMANIAN
            "ru" -> TranslateLanguage.RUSSIAN
            "sk" -> TranslateLanguage.SLOVAK
            "sl" -> TranslateLanguage.SLOVENIAN
            "es" -> TranslateLanguage.SPANISH
            "sw" -> TranslateLanguage.SWAHILI
            "sv" -> TranslateLanguage.SWEDISH
            "tl" -> TranslateLanguage.TAGALOG
            "ta" -> TranslateLanguage.TAMIL
            "te" -> TranslateLanguage.TELUGU
            "th" -> TranslateLanguage.THAI
            "tr" -> TranslateLanguage.TURKISH
            "uk" -> TranslateLanguage.UKRAINIAN
            "ur" -> TranslateLanguage.URDU
            "vi" -> TranslateLanguage.VIETNAMESE
            "cy" -> TranslateLanguage.WELSH
            else -> null
        }
    }

    /** Uvolní všechny zdroje (translator instance, ML Kit klienty). */
    fun destroy() {
        translatorCache.values.forEach { translator ->
            try {
                translator.close()
            } catch (_: Exception) {}
        }
        translatorCache.clear()
        Log.i(TAG, "💀 NlpTextProcessor zničen — všechny translator instance uvolněny")
    }
}
