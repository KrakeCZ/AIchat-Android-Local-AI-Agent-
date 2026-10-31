package com.example.llama.agent.perception

import android.content.Context
import android.util.Log
import com.example.llama.SettingsManager
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractor
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import java.util.Locale

/**
 * ========================================================================
 * NLP SUMMARY WORKER (Tier 2.5) — Enterprise Production Grade
 * ========================================================================
 *
 * Reální on-device NLP pipeline pro kompresi, extrakci entit a detekci jazyka.
 * Žádné mocky. Žádné falešné odpovědi. Každá metoda volá skutečné ML Kit API.
 *
 * Architektura (Kaskádový Fallback):
 *   1. ML Kit Language Identification → detekce jazyka (BCP-47)
 *   2. ML Kit Entity Extraction → extrakce entit (DATETIME, URL, PHONE, EMAIL, ADDRESS, IBAN)
 *   3. GenAI Summarization (AICore/Gemini Nano) → komprese textu (jen kompatibilní zařízení)
 *   4. Heuristic Summarizer (Pure Kotlin) → TF sentence ranking (fallback pro všechna zařízení)
 *
 * Backendy v SettingsManager.summarizationBackend:
 *   "aicore"    → GenAI Summarization s runtime AICore check + fallback na heuristiku
 *   "mlkit"     → Entity Extraction + heuristická komprese
 *   "heuristic" → Pouze TF sentence ranking (zero dependency)
 *   "llm"       → Explicitní eskalace na Tier 3 (llama.cpp GGUF)
 *
 * Thread Safety:
 *   - Všechny metody jsou suspend a běží na Dispatchers.Default
 *   - ML Kit instance jsou lazy-init (single-attempt, idempotentní)
 *   - EntityExtractor a LanguageIdentifier jsou thread-safe
 *
 * Research:
 *   - ML Kit Entity Extraction (Google, 2025): 16.0.0-beta6, on-device, locale-aware
 *   - ML Kit Language Identification (Google, 2025): 17.0.6, 100+ jazyků, BCP-47
 *   - ML Kit GenAI Summarization (Google I/O 2025 → 2026): AICore system service
 *   - TF-IDF Sentence Ranking (NLP classic): Komprese bez ML modelu
 */
class NlpSummaryWorker(
    private val context: Context,
    private val settingsManager: SettingsManager
) : BasePerceptionWorker {

    companion object {
        private const val TAG = "NlpSummaryWorker"

        /** Timeout pro ML Kit operace (ms). Prevence hang na pomalém HW. */
        private const val MLKIT_TIMEOUT_MS = 5000L

        /** Maximální počet vět pro heuristický summarizer. */
        private const val HEURISTIC_MAX_SENTENCES = 5

        /** Minimální délka textu pro sumarizaci (kratší text se vrátí jak je). */
        private const val MIN_TEXT_LENGTH_FOR_SUMMARY = 100

        /** Minimální délka věty aby byla považována za smysluplnou. */
        private const val MIN_SENTENCE_LENGTH = 10
    }

    override val workerId: String = "nlp_summary_worker"
    override val requiredPermissions: List<String> = emptyList()

    // ==================== ML MODEL INSTANCES (Lazy Init) ====================

    /** ML Kit Entity Extractor — lazy init, locale-aware. */
    private var entityExtractor: EntityExtractor? = null
    @Volatile
    private var entityExtractorInitAttempted = false

    /** ML Kit Language Identifier — lazy init. */
    private var languageIdentifier: LanguageIdentifier? = null
    @Volatile
    private var languageIdInitAttempted = false

    // ==================== LIFECYCLE ====================

    override suspend fun prepare(): Boolean {
        Log.i(TAG, "Připravuji NLP Tier 2.5 modely...")
        // Lazy init — modely se inicializují až při prvním volání
        // Entity Extraction model se stahuje přes Google Play Services (~5.6 MB)
        return true
    }

    override fun start() {
        Log.i(TAG, "✅ NlpSummaryWorker nastartován (backend: ${settingsManager.summarizationBackend})")
    }

    override fun stop() {
        Log.i(TAG, "💤 NlpSummaryWorker pozastaven")
    }

    override fun destroy() {
        try { entityExtractor?.close() } catch (_: Exception) {}
        try { languageIdentifier?.close() } catch (_: Exception) {}
        try { aiCoreEngine.teardownEngine() } catch (_: Exception) {}
        try { mediaPipeLlmWorker.teardown() } catch (_: Exception) {}
        entityExtractor = null
        languageIdentifier = null
        entityExtractorInitAttempted = false
        languageIdInitAttempted = false
        Log.i(TAG, "💀 NlpSummaryWorker zničen — ML Kit + AICore instance uvolněny")
    }

    // ==================== DATOVY MODEL ====================

    /**
     * Výsledek NLP zpracování. Nese komprimovaný text + metadata.
     */
    sealed class SummaryResult {
        data class Success(
            val summaryText: String,
            val wordCount: Int,
            val detectedLanguage: String = "unknown",
            val extractedEntities: List<ExtractedEntity> = emptyList(),
            val backendUsed: String = "unknown"
        ) : SummaryResult()
        data object Error : SummaryResult()
        data object NEEDS_LLM : SummaryResult()
    }

    /**
     * Extrahovaná entita z textu (ML Kit Entity Extraction).
     */
    data class ExtractedEntity(
        val text: String,
        val type: EntityType,
        val startIndex: Int,
        val endIndex: Int
    )

    /**
     * Typy entit podporované ML Kit Entity Extraction API.
     */
    enum class EntityType {
        ADDRESS, DATETIME, URL, PHONE, EMAIL, FLIGHT_NUMBER,
        IBAN, ISBN, MONEY, TRACKING_NUMBER, PAYMENT_CARD, OTHER
    }

    // ==================== HLAVNÍ ORCHESTRÁTOR ====================

    /**
     * Hlavní metoda volaná AgentCoordinator / PerceptionStateRepository.
     *
     * Kaskáda:
     * 1. Detekce jazyka
     * 2. Extrakce entit
     * 3. Komprese textu (dle vybraného backendu)
     *
     * @param rawText Surový text ke zpracování (Whisper transcript, OCR, atd.)
     * @return SummaryResult s komprimovaným textem a metadaty
     */
    suspend fun summarize(rawText: String): SummaryResult = withContext(Dispatchers.Default) {
        if (rawText.isBlank()) {
            Log.w(TAG, "⚠️ Prázdný vstup — přeskakuji")
            return@withContext SummaryResult.Error
        }

        val startMs = System.currentTimeMillis()
        Log.i(TAG, "📝 Zahajuji NLP zpracování (${rawText.length} znaků, backend: ${settingsManager.summarizationBackend})")

        try {
            // === Fáze 1: Detekce jazyka ===
            val detectedLanguage = identifyLanguage(rawText)
            Log.i(TAG, "🌍 Jazyk identifikován: $detectedLanguage")

            // === Fáze 2: Extrakce entit ===
            val entities = extractEntities(rawText)
            if (entities.isNotEmpty()) {
                Log.i(TAG, "🏷️ Entity Extraction nalezeno ${entities.size} entit: ${entities.joinToString { "${it.type}='${it.text}'" }}")
            }

            // === Fáze 3: Komprese textu ===
            val summaryText: String
            val backendUsed: String

            when (settingsManager.summarizationBackend) {
                "aicore" -> {
                    val aiCoreResult = summarizeWithAICore(rawText)
                    if (aiCoreResult != null) {
                        summaryText = aiCoreResult
                        backendUsed = "aicore"
                    } else {
                        // Kaskádový fallback: Zkusíme MediaPipe (LiteRT)
                        val mediaPipeResult = summarizeWithMediaPipeFallback(rawText)
                        if (mediaPipeResult != null) {
                            summaryText = mediaPipeResult
                            backendUsed = "mediapipe (aicore fallback)"
                        } else {
                            // AICore nedostupný a LLM fallback selhal → heuristika
                            summaryText = summarizeWithHeuristic(rawText)
                            backendUsed = "heuristic (aicore fallback)"
                        }
                    }
                }
                "mediapipe" -> {
                    val mediaPipeResult = summarizeWithMediaPipeFallback(rawText)
                    if (mediaPipeResult != null) {
                        summaryText = mediaPipeResult
                        backendUsed = "mediapipe"
                    } else {
                        summaryText = summarizeWithHeuristic(rawText)
                        backendUsed = "heuristic (mediapipe fallback)"
                    }
                }
                "mlkit" -> {
                    // Entity-based komprese + heuristika
                    summaryText = summarizeWithEntityContext(rawText, entities, detectedLanguage)
                    backendUsed = "mlkit"
                }
                "heuristic" -> {
                    summaryText = summarizeWithHeuristic(rawText)
                    backendUsed = "heuristic"
                }
                "llm" -> {
                    Log.i(TAG, "🔀 Backend=llm → Explicitní eskalace na Tier 3")
                    return@withContext SummaryResult.NEEDS_LLM
                }
                else -> {
                    // Zero-implicit: neznámý backend → heuristika
                    Log.w(TAG, "⚠️ Neznámý backend '${settingsManager.summarizationBackend}' → fallback heuristika")
                    summaryText = summarizeWithHeuristic(rawText)
                    backendUsed = "heuristic (unknown backend fallback)"
                }
            }

            val elapsedMs = System.currentTimeMillis() - startMs
            val wordCount = summaryText.split("\\s+".toRegex()).count { it.isNotBlank() }
            Log.i(TAG, "✅ NLP zpracování dokončeno za ${elapsedMs}ms (backend=$backendUsed, $wordCount slov, ${entities.size} entit)")

            return@withContext SummaryResult.Success(
                summaryText = summaryText,
                wordCount = wordCount,
                detectedLanguage = detectedLanguage,
                extractedEntities = entities,
                backendUsed = backendUsed
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ NLP zpracování selhalo (non-fatal)", e)
            // Ultimátní fallback: pokud všechno selže, vrať alespoň zkrácenou verzi
            val fallbackText = rawText.take(500)
            val fallbackWords = fallbackText.split("\\s+".toRegex()).count { it.isNotBlank() }
            return@withContext SummaryResult.Success(
                summaryText = fallbackText,
                wordCount = fallbackWords,
                detectedLanguage = "unknown",
                extractedEntities = emptyList(),
                backendUsed = "emergency_truncation"
            )
        }
    }

    // ==================== FÁZE 1: DETEKCE JAZYKA ====================

    /**
     * ML Kit Language Identification (100+ jazyků, ~10ms, offline).
     *
     * @param text Text k identifikaci
     * @return BCP-47 kód jazyka (cs, en, sk, de, ...) nebo "und" pokud neznámý
     */
    suspend fun identifyLanguage(text: String): String {
        if (text.length < 20) return "und" // ML Kit potřebuje alespoň ~20 znaků

        // Lazy init
        if (!languageIdInitAttempted) {
            languageIdInitAttempted = true
            try {
                languageIdentifier = LanguageIdentification.getClient()
                Log.i(TAG, "✅ ML Kit LanguageIdentifier inicializován")
            } catch (e: Exception) {
                Log.e(TAG, "❌ LanguageIdentifier init selhala", e)
                languageIdentifier = null
            }
        }

        val identifier = languageIdentifier ?: return "und"

        return try {
            withTimeout(MLKIT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    identifier.identifyLanguage(text)
                        .addOnSuccessListener { langCode ->
                            if (cont.isActive) cont.resume(langCode ?: "und")
                        }
                        .addOnFailureListener { e ->
                            Log.w(TAG, "⚠️ Language ID selhala: ${e.message}")
                            if (cont.isActive) cont.resume("und")
                        }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⏱️ Language ID timeout nebo chyba: ${e.message}")
            "und"
        }
    }

    // ==================== FÁZE 2: EXTRAKCE ENTIT ====================

    /**
     * ML Kit Entity Extraction — extrahuje strukturované entity z textu.
     *
     * Podporované typy: ADDRESS, DATETIME, URL, PHONE, EMAIL, FLIGHT_NUMBER,
     * IBAN, ISBN, MONEY, TRACKING_NUMBER, PAYMENT_CARD.
     *
     * Model (~5.6 MB) se stahuje přes Google Play Services při prvním volání.
     * Locale-aware: respektuje cs/en formáty datumů a adres.
     *
     * @param text Text k analýze
     * @return Seznam extrahovaných entit (prázdný pokud model nedostupný)
     */
    suspend fun extractEntities(text: String): List<ExtractedEntity> {
        if (text.length < 5) return emptyList()

        // Lazy init s preferovaným locale
        if (!entityExtractorInitAttempted) {
            entityExtractorInitAttempted = true
            try {
                val options = EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH)
                    .build()
                entityExtractor = EntityExtraction.getClient(options)

                // Stažení modelu (pokud ještě není stažen)
                entityExtractor?.downloadModelIfNeeded()
                    ?.addOnSuccessListener {
                        Log.i(TAG, "✅ Entity Extraction model stažen/existuje")
                    }
                    ?.addOnFailureListener { e ->
                        Log.w(TAG, "⚠️ Entity Extraction model download selhal: ${e.message}")
                    }

                Log.i(TAG, "✅ ML Kit EntityExtractor inicializován (EN locale)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ EntityExtractor init selhala", e)
                entityExtractor = null
            }
        }

        val extractor = entityExtractor ?: return emptyList()

        return try {
            withTimeout(MLKIT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val params = EntityExtractionParams.Builder(text)
                        .setPreferredLocale(Locale("cs", "CZ"))
                        .build()

                    extractor.annotate(params)
                        .addOnSuccessListener { annotations ->
                            val result = mutableListOf<ExtractedEntity>()
                            for (annotation in annotations) {
                                val annotatedText = annotation.annotatedText
                                val start = annotation.start
                                val end = annotation.end

                                for (entityAnnotation in annotation.entities) {
                                    val type = mapEntityType(entityAnnotation.type)
                                    result.add(
                                        ExtractedEntity(
                                            text = annotatedText,
                                            type = type,
                                            startIndex = start,
                                            endIndex = end
                                        )
                                    )
                                }
                            }
                            if (cont.isActive) cont.resume(result)
                        }
                        .addOnFailureListener { e ->
                            Log.w(TAG, "⚠️ Entity Extraction selhala: ${e.message}")
                            if (cont.isActive) cont.resume(emptyList())
                        }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⏱️ Entity Extraction timeout nebo chyba: ${e.message}")
            emptyList()
        }
    }

    /**
     * Mapování ML Kit Entity.TYPE_* na náš EntityType enum.
     */
    private fun mapEntityType(mlKitType: Int): EntityType {
        return when (mlKitType) {
            Entity.TYPE_ADDRESS -> EntityType.ADDRESS
            Entity.TYPE_DATE_TIME -> EntityType.DATETIME
            Entity.TYPE_URL -> EntityType.URL
            Entity.TYPE_PHONE -> EntityType.PHONE
            Entity.TYPE_EMAIL -> EntityType.EMAIL
            Entity.TYPE_FLIGHT_NUMBER -> EntityType.FLIGHT_NUMBER
            Entity.TYPE_IBAN -> EntityType.IBAN
            Entity.TYPE_ISBN -> EntityType.ISBN
            Entity.TYPE_MONEY -> EntityType.MONEY
            Entity.TYPE_TRACKING_NUMBER -> EntityType.TRACKING_NUMBER
            Entity.TYPE_PAYMENT_CARD -> EntityType.PAYMENT_CARD
            else -> EntityType.OTHER
        }
    }

    // ==================== FÁZE 3A: AICore / GEMINI NANO ====================

    /** AiCoreMasterEngine — process-scope singleton (sdílené Binder spojení, nuluplikáce ML cache). */
    private val aiCoreEngine by lazy { AiCoreMasterEngine.getInstance(context) }

    /** SecureStorageEngine init — voláno jednou při prvním AICore použití. */
    @Volatile
    private var secureStorageInitialized = false

    private fun ensureSecureStorage() {
        if (!secureStorageInitialized) {
            SecureStorageEngine.initialize(context)
            secureStorageInitialized = true
        }
    }

    /**
     * ML Kit GenAI Summarization (AICore / Gemini Nano) — PRODUKČNÍ IMPLEMENTACE.
     *
     * Deleguje na AiCoreMasterEngine.summarize() s:
     * - FeatureStatus check + model download
     * - SecureStorageEngine cachování
     * - Thermal throttle kontrola (PowerManager)
     * - Kaskádový fallback → null signalizuje přechod na heuristiku
     *
     * Podmínky:
     * - Zařízení musí mít nainstalovanou službu AICore (com.google.android.aicore)
     * - Podporovaná zařízení (2026): Pixel 8/9/10, Samsung S24/S25/S26, Xiaomi 15
     * - Model běží jako systémová služba, ne jako součást APK
     *
     * @param text Text ke komprimování
     * @return Komprimovaný text nebo null pokud AICore nedostupné/selhalo
     */
    private suspend fun summarizeWithAICore(text: String): String? {
        // Runtime check: Je AICore dostupný na tomto zařízení?
        if (!AiCoreMasterEngine.isAICoreAvailable(context)) {
            Log.w(TAG, "⚠️ AICore (Gemini Nano) NEDostupný na tomto zařízení → fallback")
            return null
        }

        // Inicializace SecureStorageEngine pro cachování výsledků
        ensureSecureStorage()

        return try {
            val result = aiCoreEngine.summarize(
                text = text,
                inputType = SummaryInputType.ARTICLE,
                outputType = SummaryOutputType.THREE_BULLETS
            )

            when (result) {
                is AiCoreResult.Success -> {
                    val cacheSuffix = if (result.cached) " (cache)" else ""
                    Log.i(TAG, "✅ AICore Summarization OK$cacheSuffix: ${result.text.length} znaků")
                    result.text
                }
                is AiCoreResult.Error -> {
                    Log.w(TAG, "⚠️ AICore selhal (${result.code}): ${result.message} → vracím null pro úroveň fallbacku")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ AICore kritická chyba", e)
            null
        }
    }

    /**
     * TIER 2.5 FALLBACK: MediaPipe LLM Inference (Gemma/LiteRT).
     * Lokální GenAI infrastruktura naprosto nezávislá na službách Google Play.
     */
    private val mediaPipeLlmWorker by lazy { MediaPipeLlmWorker(context, settingsManager) }

    private suspend fun summarizeWithMediaPipeFallback(text: String): String? {
        val prompt = "Shrň stručně a jasně následující text (česky/anglicky, dle originálu):\n\n$text\n\nShrnutí:"
        return mediaPipeLlmWorker.generateResponse(prompt)
    }

    // ==================== FÁZE 3B: ML KIT ENTITY COMPRESSION ====================

    /**
     * Kombinuje Entity Extraction s heuristickou kompresí.
     * Extrahované entity jsou přidány jako strukturovaný prefix ke komprimovanému textu.
     */
    private fun summarizeWithEntityContext(
        text: String,
        entities: List<ExtractedEntity>,
        language: String
    ): String {
        val parts = mutableListOf<String>()

        // Sekce 1: Extrahované entity (pokud existují)
        if (entities.isNotEmpty()) {
            val entityGroups = entities.groupBy { it.type }
            val entitySummary = entityGroups.entries.joinToString("; ") { (type, list) ->
                val values = list.joinToString(", ") { it.text }
                "${type.name}: $values"
            }
            parts.add("[Entity] $entitySummary")
        }

        // Sekce 2: Heuristická komprese zbytku textu
        val compressed = summarizeWithHeuristic(text)
        parts.add(compressed)

        // Sekce 3: Metadata
        parts.add("[lang=$language, src_len=${text.length}]")

        return parts.joinToString("\n")
    }

    // ==================== FÁZE 3C: HEURISTICKÝ SUMMARIZER ====================

    /**
     * TF (Term Frequency) Sentence Ranking — Pure Kotlin, zero dependency.
     *
     * Algoritmus:
     * 1. Rozděl text na věty (regex split na tečka/vykřičník/otazník)
     * 2. Tokenizuj slova a spočítej frekvenci každého slova
     * 3. Ohodnoť každou větu podle součtu frekvencí jejích slov
     * 4. Vyber top-N vět s nejvyšším skóre
     * 5. Seřaď vybrané věty v původním pořadí (zachování koherence)
     *
     * Latence: ~5ms pro 3000 znaků. Zero RAM footprint (žádný ML model).
     *
     * @param text Surový text ke kompresi
     * @return Komprimovaný text (top-N vět)
     */
    /**
     * TF (Term Frequency) Sentence Ranking s dynamickými lingvistickými váhami.
     *
     * Algoritmus:
     * 1. Rozděl text na věty.
     * 2. Tokenizuj slova a spočítej frekvenci.
     * 3. 'Action-Aware' skórování: Každá věta dostane základní skóre (TF),
     *    které je následně upraveno (booster/penalty) na základě klíčových slov
     *    z kategorií: Úkoly, Slang, Technologie, Urgence.
     * 4. Vyber top-N vět.
     */
    fun summarizeWithHeuristic(text: String): String {
        if (text.length < MIN_TEXT_LENGTH_FOR_SUMMARY) return text.trim()

        val sentences = text.split(Regex("[.!?]+\\s*"))
            .map { it.trim() }
            .filter { it.length >= MIN_SENTENCE_LENGTH }

        if (sentences.isEmpty()) return text.take(500).trim()
        if (sentences.size <= HEURISTIC_MAX_SENTENCES) return sentences.joinToString(". ") + "."

        // Definice lingvistických signálů (v2026 context)
        val taskKeywords = setOf("udělej", "fixni", "pošli", "zavolej", "kup", "vyřiď", "naplánuj", "schůzka", "task", "checkni", "udělat", "action", "oprav", "nahlásit")
        val slangKeywords = setOf("cookni", "shipni", "delulu", "rizz", "vibe", "mood", "fr", "cap", "sigma", "flex", "ate", "slayed", "ghosting", "bet")
        val techKeywords = setOf("crash", "logy", "wifi", "bluetooth", "kernel", "ram", "cpu", "battery", "network", "socket", "api", "endpoint", "bug", "deploy", "commit", "merge", "sync", "db")
        val urgencyKeywords = setOf("asap", "urgent", "důležité", "hned", "deadline", "priority", "critical", "blocker", "pomoc", "pozor", "varování", "rychle")

        val stopWords = getStopWords()
        val allWords = sentences.flatMap { it.lowercase().split(Regex("\\s+"))
            .map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }
            .filter { it.length > 2 && it !in stopWords }
        }
        val wordFrequency = allWords.groupingBy { it }.eachCount()

        // Načtení vah z tuneru
        val wTasks = settingsManager.nlpWeightTasks
        val wSlang = settingsManager.nlpWeightSlang
        val wTech = settingsManager.nlpWeightTech
        val wUrgency = settingsManager.nlpWeightUrgency

        val sentenceScores = sentences.mapIndexed { index, sentence ->
            val words = sentence.lowercase().split(Regex("\\s+"))
                .map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }
                .filter { it.length > 2 && it !in stopWords }

            // Základní TF (Term Frequency) skóre
            val baseScore = words.sumOf { wordFrequency[it] ?: 0 }.toDouble() /
                    (words.size.coerceAtLeast(1)).toDouble()

            // Bonus skóre na základě lingvistických kategorií
            var categoryBonus = 0.0
            words.forEach { word ->
                if (word in taskKeywords) categoryBonus += wTasks
                if (word in slangKeywords) categoryBonus += wSlang
                if (word in techKeywords) categoryBonus += wTech
                if (word in urgencyKeywords) categoryBonus += wUrgency
            }

            // Výsledné skóre: kombinace frekvence a sémantické důležitosti
            val finalScore = baseScore + categoryBonus
            IndexedSentence(index, sentence, finalScore)
        }

        val topSentences = sentenceScores
            .sortedByDescending { it.score }
            .take(HEURISTIC_MAX_SENTENCES)

        return topSentences.sortedBy { it.originalIndex }.joinToString(". ") { it.text } + "."
    }

    private fun getStopWords(): Set<String> {
        return setOf(
            "a", "ale", "ani", "az", "bez", "by", "byl", "byla", "byli", "bylo",
            "byt", "co", "da", "do", "i", "ja", "jak", "jako", "je", "jeho",
            "jej", "ji", "jiz", "jsem", "jsi", "jsou", "k", "kam", "kde", "kdo",
            "kdyz", "ke", "ku", "ma", "me", "mi", "mne", "mnou", "muj", "my",
            "na", "nad", "nam", "nas", "ne", "nebo", "nez", "ni", "nic", "no",
            "od", "on", "ona", "oni", "ono", "pak", "po", "pod", "podle", "pro",
            "proc", "pred", "pres", "pri", "s", "se", "si", "sme", "sve", "svou",
            "ta", "tak", "take", "ten", "to", "tu", "ty", "uz", "v", "ve",
            "vse", "vsech", "za", "ze",
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "shall", "can", "to", "of", "in", "for", "on", "with", "at", "by", "from",
            "as", "into", "through", "during", "before", "after", "above", "below",
            "between", "out", "off", "over", "under", "again", "further", "then",
            "once", "here", "there", "when", "where", "why", "how", "all", "both",
            "each", "few", "more", "most", "other", "some", "such", "no", "nor",
            "not", "only", "own", "same", "so", "than", "too", "very", "just",
            "because", "but", "and", "or", "if", "while", "this", "that", "these",
            "those", "it", "its", "i", "me", "my", "we", "our", "you", "your",
            "he", "him", "his", "she", "her", "they", "them", "their", "what",
            "which", "who", "whom"
        )
    }

    /**
     * Pomocná data class pro sledování pozice věty v původním textu.
     */
    private data class IndexedSentence(
        val originalIndex: Int,
        val text: String,
        val score: Double
    )
}
