package com.example.llama.agent.perception

import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import com.example.llama.managers.LlamaServiceManager
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * AiCoreMasterEngine — Fasáda pro VŠECH 6 ML Kit GenAI API (AICore / Gemini Nano).
 *
 * Implementuje:
 * 1. Summarization (InputType.ARTICLE/CONVERSATION, OutputType.ONE_BULLET/THREE_BULLETS)
 * 2. Proofreading (automatická oprava gramatiky/pravopisu)
 * 3. Rewriting (6 tónů: ELABORATE, EMOJIFY, SHORTEN, FRIENDLY, PROFESSIONAL, REPHRASE)
 * 4. Prompt API (custom text prompty)
 * 5. Image Description (Bitmap → text popis)
 * 6. Speech Recognition (AICore on-device ASR)
 *
 * ARCHITEKTONICKÉ ROZHODNUTÍ:
 * ML Kit GenAI API (beta1) používá ListenableFuture (Guava), NE GMS Task.
 * API surface se mezi beta verzemi mění (factory metody, builder parametry).
 * Proto VŠECHNY 6 API používají reflection-safe dynamický přístup:
 * - Žádná compile-time závislost na přesných method signatures
 * - Graceful degradation pokud se API změní mezi beta verzemi
 * - Runtime discovery reálných factory/builder vzorů
 *
 * Požadavky:
 * - AICore systémová služba (com.google.android.aicore) nainstalována
 * - Kompatibilní HW: Pixel 8/9/10, Samsung S24/S25/S26, Xiaomi 15
 * - compileSdk 35, minSdk 26+ (runtime graceful degradation)
 */
class AiCoreMasterEngine(private val context: Context) {

    companion object {
        private const val TAG = "AiCoreMasterEngine"

        /** AICore inference timeout (ms). */
        private const val INFERENCE_TIMEOUT_MS = 15_000L

        /** Maximální délka vstupu pro Summarization (~4000 tokenů). */
        private const val MAX_SUMMARIZATION_INPUT_LENGTH = 12_000

        /** Maximální délka vstupu pro Proofreading/Rewriting (~256 tokenů). */
        private const val MAX_SHORT_INPUT_LENGTH = 1_000

        /** Executor pro ListenableFuture callbacky. */
        private val callbackExecutor = Executors.newSingleThreadExecutor()

        // ======================================================================
        // PROCESS-SCOPE SINGLETON — prevence duplicitního načtění ML Kit klientů
        // ML Kit Summarizer/Proofreader jsou IPC/Binder klienti k
        // systémové službě AICore (com.google.android.aicore).
        // Mít více instancí = více Binder spojení + duplicitní ML-model cache.
        // ApplicationContext zajišťuje žádnou vázbu na lifecycle Activity.
        // ======================================================================
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile
        private var singletonInstance: AiCoreMasterEngine? = null

        /**
         * Vrátí sdílenou process-scope instanci.
         * Použij místo `AiCoreMasterEngine(context)` kÞkoliv, kde není potřeba
         * izolace lifecycle (tj. všude kromě přímé Activity injection).
         */
        fun getInstance(context: Context): AiCoreMasterEngine {
            return singletonInstance ?: synchronized(this) {
                singletonInstance ?: AiCoreMasterEngine(
                    context.applicationContext // ApplicationContext — není vázán na Activity lifecycle
                ).also { singletonInstance = it }
            }
        }

        /**
         * Runtime detekce AICore systémové služby.
         */
        fun isAICoreAvailable(context: Context): Boolean {
            return try {
                context.packageManager.getPackageInfo("com.google.android.aicore", 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    // ==================== CACHED KLIENTI (lazy, reflection-created) ====================

    @Volatile private var cachedSummarizer: Any? = null
    @Volatile private var cachedProofreader: Any? = null
    private val cachedRewriters = mutableMapOf<RewriteTone, Any>()

    // ==================== API 1: SUMMARIZATION ====================

    /**
     * Sumarizace textu přes AICore Gemini Nano.
     *
     * @param text Vstupní text (max ~12 000 znaků)
     * @param inputType Typ vstupu (ARTICLE nebo CONVERSATION)
     * @param outputType Výstupní formát (ONE_BULLET, THREE_BULLETS, SHORT)
     * @return AiCoreResult.Success s textem sumarizace, nebo AiCoreResult.Error
     */
    suspend fun summarize(
        text: String,
        inputType: SummaryInputType = SummaryInputType.ARTICLE,
        outputType: SummaryOutputType = SummaryOutputType.THREE_BULLETS
    ): AiCoreResult = withContext(Dispatchers.Default) {
        val preCheck = performPreChecks("Summarization", text, MAX_SUMMARIZATION_INPUT_LENGTH)
        if (preCheck != null) return@withContext preCheck

        // Cache hit?
        val cacheKey = "sum_${inputType}_${outputType}_$text"
        SecureStorageEngine.getOrNull(SecureStorageEngine.Namespace.SUMMARIZATION, cacheKey)?.let {
            Log.i(TAG, "✅ Summarization cache HIT (${it.length} znaků)")
            return@withContext AiCoreResult.Success(it, cached = true)
        }

        try {
            val result = withTimeout(INFERENCE_TIMEOUT_MS) {
                executeSummarization(text, inputType, outputType)
            }

            if (result != null) {
                SecureStorageEngine.put(SecureStorageEngine.Namespace.SUMMARIZATION, cacheKey, result)
                Log.i(TAG, "✅ Summarization OK (${result.length} znaků)")
                AiCoreResult.Success(result)
            } else {
                AiCoreResult.Error(ErrorCode.GENERATION_FAILED, "Summarizer vrátil prázdný výsledek")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Summarization selhala: ${e.javaClass.simpleName}: ${e.message}")
            
            val isAicoreBlocked = e.message?.contains("Feature 621 is not available", ignoreCase = true) == true ||
                                  e.message?.contains("606", ignoreCase = true) == true ||
                                  e.message?.contains("UNAVAILABLE", ignoreCase = true) == true
            
            if (isAicoreBlocked) {
                Log.w(TAG, "⚠️ AICore zablokováno. Cesta 3: Deleguji na Llama.cpp.")
                val systemPrompt = "Jsi asistent, který shrnuje text do $outputType. Jazyk: čeština."
                val llamaResult = executeLlamaCppFallback(text, systemPrompt)
                if (llamaResult != null) {
                    return@withContext AiCoreResult.Success(llamaResult)
                }
            }
            
            AiCoreResult.Error(ErrorCode.GENERATION_FAILED, e.message ?: "Neznámá chyba")
        }
    }

    // ==================== API 2: PROOFREADING ====================

    /**
     * Korektura gramatiky/pravopisu přes AICore.
     *
     * @param text Vstupní text (max ~1000 znaků)
     * @return AiCoreResult.Success s opraveným textem
     */
    suspend fun proofread(text: String): AiCoreResult = withContext(Dispatchers.Default) {
        val preCheck = performPreChecks("Proofreading", text, MAX_SHORT_INPUT_LENGTH)
        if (preCheck != null) return@withContext preCheck

        SecureStorageEngine.getOrNull(SecureStorageEngine.Namespace.PROOFREADING, text)?.let {
            Log.i(TAG, "✅ Proofreading cache HIT")
            return@withContext AiCoreResult.Success(it, cached = true)
        }

        try {
            val result = withTimeout(INFERENCE_TIMEOUT_MS) {
                executeProofreading(text)
            }

            if (result != null) {
                SecureStorageEngine.put(SecureStorageEngine.Namespace.PROOFREADING, text, result)
                Log.i(TAG, "✅ Proofreading OK")
                AiCoreResult.Success(result)
            } else {
                AiCoreResult.Error(ErrorCode.GENERATION_FAILED, "Proofreader vrátil prázdný výsledek")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Proofreading selhala: ${e.message}")
            
            val isAicoreBlocked = e.message?.contains("Feature", ignoreCase = true) == true ||
                                  e.message?.contains("606", ignoreCase = true) == true ||
                                  e.message?.contains("UNAVAILABLE", ignoreCase = true) == true
            
            if (isAicoreBlocked) {
                Log.w(TAG, "⚠️ AICore zablokováno. Cesta 3: Deleguji na Llama.cpp.")
                val systemPrompt = "Oprav v následujícím textu gramatiku a pravopis. Vypiš POUZE opravený text, nic víc:"
                val llamaResult = executeLlamaCppFallback(text, systemPrompt)
                if (llamaResult != null) {
                    return@withContext AiCoreResult.Success(llamaResult)
                }
            }
            
            AiCoreResult.Error(ErrorCode.GENERATION_FAILED, e.message ?: "Neznámá chyba")
        }
    }

    // ==================== API 3: REWRITING ====================

    /**
     * Přepis textu v různých tónech přes AICore.
     *
     * @param text Vstupní text (max ~1000 znaků)
     * @param tone Cílový tón
     * @return AiCoreResult.Success s přepsaným textem
     */
    suspend fun rewrite(
        text: String,
        tone: RewriteTone = RewriteTone.PROFESSIONAL
    ): AiCoreResult = withContext(Dispatchers.Default) {
        val preCheck = performPreChecks("Rewriting", text, MAX_SHORT_INPUT_LENGTH)
        if (preCheck != null) return@withContext preCheck

        val cacheKey = "${tone.name}_$text"
        SecureStorageEngine.getOrNull(SecureStorageEngine.Namespace.REWRITING, cacheKey)?.let {
            Log.i(TAG, "✅ Rewriting cache HIT (tone=${tone.name})")
            return@withContext AiCoreResult.Success(it, cached = true)
        }

        try {
            val result = withTimeout(INFERENCE_TIMEOUT_MS) {
                executeRewriting(text, tone)
            }

            if (result != null) {
                SecureStorageEngine.put(SecureStorageEngine.Namespace.REWRITING, cacheKey, result)
                Log.i(TAG, "✅ Rewriting OK (tone=${tone.name})")
                AiCoreResult.Success(result)
            } else {
                AiCoreResult.Error(ErrorCode.GENERATION_FAILED, "Rewriter vrátil prázdný výsledek")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Rewriting selhala: ${e.message}")
            
            val isAicoreBlocked = e.message?.contains("Feature", ignoreCase = true) == true ||
                                  e.message?.contains("606", ignoreCase = true) == true ||
                                  e.message?.contains("UNAVAILABLE", ignoreCase = true) == true
            
            if (isAicoreBlocked) {
                Log.w(TAG, "⚠️ AICore zablokováno. Cesta 3: Deleguji na Llama.cpp.")
                val systemPrompt = "Přepiš tento text v tónu: ${tone.name}. Vypiš POUZE přepsaný text:"
                val llamaResult = executeLlamaCppFallback(text, systemPrompt)
                if (llamaResult != null) {
                    return@withContext AiCoreResult.Success(llamaResult)
                }
            }
            
            AiCoreResult.Error(ErrorCode.GENERATION_FAILED, e.message ?: "Neznámá chyba")
        }
    }

    // ==================== API 4: PROMPT API ====================

    /**
     * Volný prompt přes AICore Gemini Nano.
     *
     * @param promptText Textový prompt
     * @return AiCoreResult.Success s generovaným textem
     */
    suspend fun prompt(promptText: String): AiCoreResult = withContext(Dispatchers.Default) {
        val preCheck = performPreChecks("Prompt", promptText, MAX_SUMMARIZATION_INPUT_LENGTH)
        if (preCheck != null) return@withContext preCheck

        SecureStorageEngine.getOrNull(SecureStorageEngine.Namespace.PROMPT, promptText)?.let {
            Log.i(TAG, "✅ Prompt cache HIT")
            return@withContext AiCoreResult.Success(it, cached = true)
        }

        try {
            val result = withTimeout(INFERENCE_TIMEOUT_MS) {
                // ══ CESTA A.0: ML Kit GenAI 1.0.0-beta1 — Generation.getClient() (Nové nativní API) ══
                val beta1_0_0 = executePromptGeneration(promptText)
                if (beta1_0_0 != null) return@withTimeout beta1_0_0

                // ══ CESTA A.1: ML Kit GenAI beta2 — Generation.getClient() (Starý návrh s PromptRequest) ══
                val beta2 = tryBeta2EntryPoint(
                    entryPointClass = "com.google.mlkit.genai.prompt.Generation",
                    requestPackage = "com.google.mlkit.genai.prompt",
                    requestClassName = "PromptRequest",
                    inputText = promptText
                )
                if (beta2 != null) return@withTimeout beta2

                // ══ CESTA B: ML Kit GenAI beta1 — PromptApiClient (starý vzor, fallback) ══
                Log.d(TAG, "ℹ️ Prompt: beta2 Generation nedostupná → zkouším beta1 PromptApiClient")
                executeGenAiReflection(
                    packagePrefix = "com.google.mlkit.genai.prompt",
                    clientClassName = "PromptApiClient",
                    optionsClassName = "PromptApiClientOptions",
                    requestClassName = "PromptRequest",
                    inputText = promptText,
                    inputSetterName = "setPrompt"
                )
            }

            if (result != null) {
                SecureStorageEngine.put(SecureStorageEngine.Namespace.PROMPT, promptText, result)
                Log.i(TAG, "✅ Prompt API OK (${result.length} znaků)")
                AiCoreResult.Success(result)
            } else {
                AiCoreResult.Error(ErrorCode.GENERATION_FAILED, "Prompt API vrátil prázdný výsledek — AICore model není stažen nebo zařízení nepodporuje Gemini Nano")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Prompt API selhalo: ${e.javaClass.simpleName}: ${e.message}")
            
            // FALLBACK NA LLAMA (Cesta 3)
            val isAicoreBlocked = e.message?.contains("Feature 636 is not available", ignoreCase = true) == true ||
                                  e.message?.contains("606", ignoreCase = true) == true ||
                                  e.message?.contains("UNAVAILABLE", ignoreCase = true) == true
            
            if (isAicoreBlocked) {
                Log.w(TAG, "⚠️ AICore zablokováno (Experimental build). Cesta 3: Deleguji na Llama.cpp.")
                val llamaResult = executeLlamaCppFallback(promptText)
                if (llamaResult != null) {
                    return@withContext AiCoreResult.Success(llamaResult)
                }
            }
            
            AiCoreResult.Error(ErrorCode.GENERATION_FAILED, e.message ?: "Neznámá chyba")
        }
    }

    /**
     * Cesta 3: Fallback přes lokální Llama.cpp s využitím GGUF modelu.
     */
    private suspend fun executeLlamaCppFallback(prompt: String, systemPrompt: String? = null): String? {
        Log.i(TAG, "🚀 Startuji Llama.cpp fallback...")
        
        val settings = com.example.llama.SettingsManager(context)
        val enableMapReduce = settings.enableMapReduce
        
        var resultText: String? = null
        val tempScope = CoroutineScope(Dispatchers.Default)
        val manager = com.example.llama.managers.LlamaServiceManager(context, tempScope)
        
        try {
            manager.connect(asResident = false)
            
            // Čekání na připojení (max 2 sekundy)
            var retries = 0
            while (!manager.isServiceConnected() && retries < 20) {
                delay(100)
                retries++
            }
            
            if (manager.isServiceConnected()) {
                val chunkSize = 8000 // cca 1500 tokenů
                if (enableMapReduce && prompt.length > chunkSize) {
                    Log.i(TAG, "Map-Reduce zapnut: Text má ${prompt.length} znaků, dělím na bloky...")
                    val chunks = prompt.chunked(chunkSize)
                    val partialSummaries = StringBuilder()
                    
                    for ((index, chunk) in chunks.withIndex()) {
                        Log.i(TAG, "Zpracovávám Map část ${index + 1} z ${chunks.size}")
                        val partialResult = manager.awaitGeneration(
                            prompt = chunk,
                            maxTokens = 1024,
                            systemPrompt = "Shrň následující část textu (část ${index+1}):",
                            resetContext = true
                        ) ?: throw Exception("Generování částečného souhrnu selhalo")
                        partialSummaries.append(partialResult).append("\n\n")
                    }
                    
                    Log.i(TAG, "Reduce: Slévám dílčí souhrny do finálního výsledku.")
                    resultText = manager.awaitGeneration(
                        prompt = partialSummaries.toString(),
                        maxTokens = 4096,
                        systemPrompt = systemPrompt ?: "Vytvoř finální ucelené shrnutí z těchto dílčích zápisků.",
                        resetContext = true
                    )
                } else {
                    Log.i(TAG, "🚀 Llama.cpp připojena, čekám na výsledek generování...")
                    resultText = manager.awaitGeneration(
                        prompt = prompt,
                        maxTokens = 4096,
                        systemPrompt = systemPrompt,
                        resetContext = true
                    )
                }
            } else {
                Log.e(TAG, "❌ Llama.cpp fallback selhal: Nelze se připojit k LlamaGenerationService")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Llama.cpp fallback vyhodil výjimku: ${e.message}")
            if (e.message?.contains("Model není načten", ignoreCase = true) == true) {
                throw Exception("Model není načten")
            }
        } finally {
            manager.disconnect()
        }
        
        return resultText
    }

    /**
     * Zkusí nové API ML Kit GenAI (1.0.0-beta1) s třídami GenerativeModel a GenerativeModelFutures
     */
    private suspend fun executePromptGeneration(promptText: String): String? {
        return try {
            val generationClass = Class.forName("com.google.mlkit.genai.prompt.Generation")
            
            // OPRAVA NPE: getClient() je voláno na instanci (singletonu) INSTANCE, nikoliv staticky
            val instanceField = generationClass.getDeclaredField("INSTANCE")
            val receiver = instanceField.get(null)
            
            val getClientMethod = generationClass.getMethod("getClient")
            val client = getClientMethod.invoke(receiver) // Předáváme receiver místo null

            val futuresClass = Class.forName("com.google.mlkit.genai.prompt.java.GenerativeModelFutures")
            val generativeModelClass = Class.forName("com.google.mlkit.genai.prompt.GenerativeModel")
            val fromMethod = futuresClass.getMethod("from", generativeModelClass)
            val futuresClient = fromMethod.invoke(null, client)
            
            // Ověření statusu (0=UNAVAILABLE)
            val checkStatusMethod = futuresClass.getMethod("checkStatus")
            val statusFuture = checkStatusMethod.invoke(futuresClient)
            val status = awaitListenableFutureRawObject(statusFuture) as? Int ?: 3
            if (status == 0) {
                Log.w(TAG, "⚠️ GenerativeModel (1.0.0-beta1) nedostupný (status=0)")
                return null
            }
            if (status in 1..2) {
                Log.i(TAG, "ℹ️ GenerativeModel status=$status, stahuje se na pozadí")
            }

            // Ošetření Token limitu
            try {
                val getTokenLimitMethod = futuresClass.getMethod("getTokenLimit")
                val tokenLimitFuture = getTokenLimitMethod.invoke(futuresClient)
                val tokenLimit = awaitListenableFutureRawObject(tokenLimitFuture) as? Int ?: 8000
                Log.i(TAG, "ℹ️ GenerativeModel tokenLimit=$tokenLimit")
                
                // Můžeme omezit promptText pokud by byl moc dlouhý, např. odříznout konec.
                if (promptText.length > tokenLimit * 3) {
                    Log.w(TAG, "⚠️ Prompt je pravděpodobně delší než token limit ($tokenLimit). LLM může selhat.")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Nelze zjistit tokenLimit: ${e.message}")
            }

            Log.i(TAG, "🚀 GenerativeModel status=$status, volám generateContent")
            val generateContentMethod = futuresClass.getMethod("generateContent", String::class.java)
            val responseFuture = generateContentMethod.invoke(futuresClient, promptText)
            val response = awaitListenableFutureRawObject(responseFuture)
            
            // response je GenerateContentResponse, získáme text z candidates
            val getCandidatesMethod = response?.javaClass?.getMethod("getCandidates")
            val candidates = getCandidatesMethod?.invoke(response) as? List<*>
            val firstCandidate = candidates?.firstOrNull()
            val getTextMethod = firstCandidate?.javaClass?.getMethod("getText")
            val text = getTextMethod?.invoke(firstCandidate) as? String
            text
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ GenerativeModel (1.0.0-beta1) fallback selhal: ${e.message}")
            null
        }
    }

    // ==================== API 5: IMAGE DESCRIPTION ====================

    /**
     * Popis obrázku přes AICore Gemini Nano (multimodální).
     *
     * @param bitmap Vstupní obrázek
     * @param promptHint Volitelný hint pro model
     * @return AiCoreResult.Success s textovým popisem
     */
    suspend fun describeImage(
        bitmap: Bitmap,
        promptHint: String = "Describe this image in detail."
    ): AiCoreResult = withContext(Dispatchers.Default) {
        if (!isAICoreAvailable(context)) {
            return@withContext AiCoreResult.Error(
                ErrorCode.DEVICE_NOT_SUPPORTED, "AICore nedostupný na tomto zařízení"
            )
        }
        if (isThermalThrottling()) {
            return@withContext AiCoreResult.Error(ErrorCode.THERMAL_THROTTLE, "Zařízení přehřáté")
        }

        try {
            val result = withTimeout(INFERENCE_TIMEOUT_MS) {
                executeImageDescription(bitmap, promptHint)
            }

            if (result != null) {
                Log.i(TAG, "✅ Image Description OK (${result.length} znaků)")
                AiCoreResult.Success(result)
            } else {
                AiCoreResult.Error(ErrorCode.GENERATION_FAILED, "ImageDescriber vrátil prázdný výsledek")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Image Description selhala: ${e.message}")
            AiCoreResult.Error(ErrorCode.GENERATION_FAILED, e.message ?: "Neznámá chyba")
        }
    }

    // ==================== API 6: SPEECH RECOGNITION ====================

    /**
     * Rozpoznávání řeči přes AICore (on-device ASR, alpha).
     *
     * @param audioData PCM audio data (16-bit, mono)
     * @param sampleRate Vzorkovací frekvence
     * @return AiCoreResult.Success s rozpoznaným textem
     */
    suspend fun recognizeSpeech(
        audioData: ByteArray,
        sampleRate: Int = 16000
    ): AiCoreResult = withContext(Dispatchers.Default) {
        if (!isAICoreAvailable(context)) {
            return@withContext AiCoreResult.Error(
                ErrorCode.DEVICE_NOT_SUPPORTED, "AICore nedostupný na tomto zařízení"
            )
        }
        if (isThermalThrottling()) {
            return@withContext AiCoreResult.Error(ErrorCode.THERMAL_THROTTLE, "Zařízení přehřáté")
        }

        try {
            val result = withTimeout(INFERENCE_TIMEOUT_MS) {
                executeSpeechRecognition(audioData, sampleRate)
            }

            if (result != null) {
                Log.i(TAG, "✅ Speech Recognition OK (${result.length} znaků)")
                AiCoreResult.Success(result)
            } else {
                AiCoreResult.Error(ErrorCode.GENERATION_FAILED, "SpeechRecognizer vrátil prázdný výsledek")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Speech Recognition selhala: ${e.message}")
            AiCoreResult.Error(ErrorCode.GENERATION_FAILED, e.message ?: "Neznámá chyba")
        }
    }

    // ==================== LIFECYCLE ====================

    /**
     * Uvolní všechny AICore klienty a IPC resources.
     */
    @Synchronized
    fun teardownEngine() {
        closeClientSafe(cachedSummarizer)
        closeClientSafe(cachedProofreader)
        cachedRewriters.values.forEach { closeClientSafe(it) }

        cachedSummarizer = null
        cachedProofreader = null
        cachedRewriters.clear()

        Log.i(TAG, "💀 AiCoreMasterEngine teardown — všechny klienty uvolněny")
    }

    // ==================== INTERNÍ IMPLEMENTACE (REFLECTION-SAFE) ====================

    /**
     * Summarization — reflection-safe volání ML Kit GenAI Summarizer.
     *
     * ML Kit GenAI beta1 API surface:
     * - SummarizerOptions.builder(Context) → Builder
     * - Builder.setInputType(int) / .setOutputType(int) / .build() → SummarizerOptions
     * - Summarizer (factory varies: getClient/create) → Summarizer instance
     * - summarizer.checkFeatureStatus() → ListenableFuture<Int>
     * - SummarizationRequest(text) or builder pattern
     * - summarizer.runInference(request) → ListenableFuture<SummarizationResult>
     */
    /**
     * Summarization — dual-path reflection.
     *
     * CESTA A (beta2, 2026): Summarization.getClient() → checkStatus() → generateContent()
     * CESTA B (beta1 fallback): SummarizerOptions.builder(Context) → Summarizer.getClient(options) → runInference()
     */
    private suspend fun executeSummarization(
        text: String,
        inputType: SummaryInputType,
        outputType: SummaryOutputType
    ): String? {
        // ══ CESTA A: Přímé compile-time Beta1 volání (1.0.0-beta1 dependency) ══
        try {
            val res = executeSummarizationDirect(text, inputType, outputType)
            if (res != null) return res
        } catch (e: NoClassDefFoundError) {
            Log.w(TAG, "⚠️ Beta1 Summarization třídy chybí (${e.message}) — zkouším Beta2 reflection")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "⚠️ Beta1 native library chybí — zkouším Beta2 reflection")
        }

        // ══ CESTA B: Beta2 reflection fallback (budoucí API nebo jiná verze) ══
        val beta2Result = tryBeta2EntryPoint(
            entryPointClass = "com.google.mlkit.genai.summarization.Summarization",
            requestPackage = "com.google.mlkit.genai.summarization",
            requestClassName = "SummarizationRequest",
            inputText = text,
            extraOptions = mapOf("setInputType" to mapInputType(inputType), "setOutputType" to mapOutputType(outputType))
        )
        if (beta2Result != null) return beta2Result
        
        // ══ CESTA C: Fallback na Prompt API (GenerativeModel) pokud zařízení nemá dedikovaný Summarizer ══
        Log.w(TAG, "⚠️ Dedikovaný Summarizer (Feature 622) selhal nebo chybí. Zkouším fallback na nativní Prompt API (Gemini Nano).")
        val promptInstruction = when (outputType) {
            SummaryOutputType.ONE_BULLET -> "Shrň následující text do jedné výstižné věty:\n\n$text"
            SummaryOutputType.THREE_BULLETS -> "Shrň následující text do 3 stručných bodů:\n\n$text"
            SummaryOutputType.SHORT -> "Vytvoř krátké shrnutí následujícího textu:\n\n$text"
        }
        val promptResult = executePromptGeneration(promptInstruction)
        if (promptResult != null) return promptResult

        Log.e(TAG, "❌ Summarization: všechny cesty (včetně Prompt API) selhaly")
        return null
    }

    /**
     * Přímé Beta1 volání Summarization API.
     * Používá compile-time třídy — NoClassDefFoundError pokud AICore/ML Kit není přítomný.
     *
     * Beta1 FeatureStatus ordinals:
     *   UNAVAILABLE  = 0  → model nepodporovaný HW
     *   DOWNLOADABLE = 1  → model lze stáhnout
     *   DOWNLOADING  = 2  → model se stahuje
     *   AVAILABLE    = 3  → ✅ model připraven
     */
    private suspend fun executeSummarizationDirect(
        text: String,
        inputType: SummaryInputType,
        outputType: SummaryOutputType
    ): String? {
        val opts = com.google.mlkit.genai.summarization.SummarizerOptions
            .builder(context)
            .setInputType(
                when (inputType) {
                    SummaryInputType.ARTICLE      -> com.google.mlkit.genai.summarization.SummarizerOptions.InputType.ARTICLE
                    SummaryInputType.CONVERSATION -> com.google.mlkit.genai.summarization.SummarizerOptions.InputType.CONVERSATION
                }
            )
            .setOutputType(
                when (outputType) {
                    SummaryOutputType.ONE_BULLET    -> com.google.mlkit.genai.summarization.SummarizerOptions.OutputType.ONE_BULLET
                    SummaryOutputType.THREE_BULLETS -> com.google.mlkit.genai.summarization.SummarizerOptions.OutputType.THREE_BULLETS
                    SummaryOutputType.SHORT         -> com.google.mlkit.genai.summarization.SummarizerOptions.OutputType.ONE_BULLET
                }
            )
            .build()

        val client = com.google.mlkit.genai.summarization.Summarization.getClient(opts)
        return try {
            // FeatureStatus ordinals: UNAVAILABLE=0, DOWNLOADABLE=1, DOWNLOADING=2, AVAILABLE=3
            // DŮLEŽITÉ: exception z checkFeatureStatus() = Feature not available na tomto AICore.
            val statusOrdinal: Int = withContext(Dispatchers.IO) {
                try {
                    val fs = client.checkFeatureStatus().get()
                    // Čti ordinal přes reflection (enum typ se liší mezi beta verzemi)
                    try { fs?.javaClass?.getMethod("ordinal")?.invoke(fs) as? Int ?: 3 }
                    catch (_: Exception) {
                        // Fallback: parsuj enum name
                        fs?.toString()?.let { s -> when {
                            s.contains("UNAVAILABLE", ignoreCase = true) -> 0
                            s.contains("DOWNLOADABLE", ignoreCase = true) -> 1
                            s.contains("DOWNLOADING", ignoreCase = true) -> 2
                            s.contains("AVAILABLE", ignoreCase = true) -> 3
                            else -> 3
                        }} ?: 3
                    }
                } catch (e: Exception) {
                    // Exception z checkFeatureStatus() = AICore hlásí Feature not available
                    Log.w(TAG, "⚠️ Summarization checkFeatureStatus() threw: ${e.message}")
                    -1
                }
            }
            Log.i(TAG, "Summarization FeatureStatus ordinal: $statusOrdinal")
            when {
                statusOrdinal == -1 || statusOrdinal == 0 -> {
                    Log.w(TAG, "⚠️ Summarization: UNAVAILABLE (ordinal=$statusOrdinal) — model nepodporovaný na tomto HW/AICore verzi")
                    null
                }
                statusOrdinal in 1..2 -> {
                    Log.i(TAG, "ℹ️ Summarization: DOWNLOADABLE/DOWNLOADING — spouštím requestFeatureInstall()")
                    tryRequestInstall(client)
                    null // Model ještě není připraven — fallback na LLM
                }
                else -> {
                    // ordinal >= 3 = AVAILABLE
                    runSummarizationInferenceDirect(client, text)
                }
            }
        } finally {
            closeClientSafe(client)
        }
    }

    private suspend fun runSummarizationInferenceDirect(
        client: com.google.mlkit.genai.summarization.Summarizer,
        text: String
    ): String? {
        val req = com.google.mlkit.genai.summarization.SummarizationRequest.builder(text).build()
        return withContext(Dispatchers.IO) {
            try { client.runInference(req).get().summary?.takeIf { it.isNotBlank() } }
            catch (e: Exception) { Log.e(TAG, "Summarization inference selhala: ${e.message}"); null }
        }
    }

    /**
     * Proofreading - dual-path reflection.
     *
     * CESTA A (beta2): Proofreading.getClient() -> checkStatus() -> generateContent()
     * CESTA B (beta1): ProofreaderOptions.builder(Context) -> runInference()
     */
    private suspend fun executeProofreading(text: String): String? {
        val beta2Result = tryBeta2EntryPoint(
            entryPointClass = "com.google.mlkit.genai.proofreading.Proofreading",
            requestPackage = "com.google.mlkit.genai.proofreading",
            requestClassName = "ProofreadingRequest",
            inputText = text
        )
        if (beta2Result != null) return beta2Result

        val beta1Result = try {
            val optionsClass = Class.forName("com.google.mlkit.genai.proofreading.ProofreaderOptions")
            val clientClass = Class.forName("com.google.mlkit.genai.proofreading.Proofreader")
            val requestClass = Class.forName("com.google.mlkit.genai.proofreading.ProofreadingRequest")

            val options = buildOptionsReflective(optionsClass, emptyMap())
            val client = getOrCreateClientReflective(clientClass, optionsClass, options)
            if (client != null && checkFeatureStatusReflective(client)) {
                val request = buildRequestReflective(requestClass, text)
                if (request != null) {
                    runInferenceReflective(client, request, requestClass)
                } else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Proofreading beta1 selhala: ${e.message}")
            null
        }
        if (beta1Result != null) return beta1Result
        
        Log.w(TAG, "⚠️ Dedikovaný Proofreader selhal nebo chybí. Zkouším fallback na nativní Prompt API (Gemini Nano).")
        val promptInstruction = "Oprav gramatické a pravopisné chyby v následujícím textu, ponech původní význam:\n\n$text"
        return executePromptGeneration(promptInstruction)
    }

    /**
     * Rewriting - dual-path reflection.
     *
     * CESTA A (beta2): Rewriting.getClient() -> checkStatus() -> generateContent(request+tone)
     * CESTA B (beta1): RewriterOptions.builder(Context).setTone(int) -> runInference()
     */
    private suspend fun executeRewriting(text: String, tone: RewriteTone): String? {
        val beta2Result = tryBeta2EntryPoint(
            entryPointClass = "com.google.mlkit.genai.rewriting.Rewriting",
            requestPackage = "com.google.mlkit.genai.rewriting",
            requestClassName = "RewritingRequest",
            inputText = text,
            extraOptions = mapOf("setTone" to mapRewriteTone(tone))
        )
        if (beta2Result != null) return beta2Result

        val beta1Result = try {
            val optionsClass = Class.forName("com.google.mlkit.genai.rewriting.RewriterOptions")
            val clientClass = Class.forName("com.google.mlkit.genai.rewriting.Rewriter")
            val requestClass = Class.forName("com.google.mlkit.genai.rewriting.RewritingRequest")

            val toneValue = mapRewriteTone(tone)
            val options = buildOptionsReflective(optionsClass, mapOf("setTone" to toneValue))
            val client = getOrCreateClientReflective(clientClass, optionsClass, options)
            if (client != null && checkFeatureStatusReflective(client)) {
                val request = buildRequestReflective(requestClass, text)
                if (request != null) {
                    runInferenceReflective(client, request, requestClass)
                } else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Rewriting beta1 selhala: ${e.message}")
            null
        }
        if (beta1Result != null) return beta1Result
        
        Log.w(TAG, "⚠️ Dedikovaný Rewriter selhal nebo chybí. Zkouším fallback na nativní Prompt API (Gemini Nano).")
        val promptInstruction = "Přepiš následující text v tónu '${tone.name}':\n\n$text"
        return executePromptGeneration(promptInstruction)
    }
    /** Image Description - reflection-safe. */
    private suspend fun executeImageDescription(bitmap: Bitmap, promptHint: String): String? {
        return try {
            executeGenAiReflectionWithBitmap(
                packagePrefix = "com.google.mlkit.genai.imagedescription",
                clientClassName = "ImageDescriber",
                optionsClassName = "ImageDescriberOptions",
                requestClassName = "ImageDescriptionRequest",
                bitmap = bitmap
            )
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "⚠️ Image Description třídy nenalezeny: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Image Description selhala: ${e.message}")
            null
        }
    }

    /**
     * Speech Recognition — reflection-safe (alpha API).
     */
    private suspend fun executeSpeechRecognition(audioData: ByteArray, sampleRate: Int): String? {
        return try {
            val clientClass = Class.forName("com.google.mlkit.genai.speechrecognition.SpeechRecognizer")
            val optionsClass = Class.forName("com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions")

            val options = buildOptionsReflective(optionsClass, emptyMap())
            val client = getOrCreateClientReflective(clientClass, optionsClass, options)
                ?: return null

            // Pokus o různé inference metody (alpha API — nepředvídatelný surface)
            val methods = client.javaClass.methods
            val runMethod = methods.firstOrNull { it.name == "runInference" || it.name == "recognize" }
            if (runMethod == null) {
                Log.w(TAG, "⚠️ Speech Recognition: nenalezena inference metoda")
                return null
            }

            val future = when (runMethod.parameterCount) {
                1 -> runMethod.invoke(client, audioData)
                2 -> runMethod.invoke(client, audioData, sampleRate)
                else -> null
            }

            awaitListenableFutureText(future)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "⚠️ Speech Recognition třídy nenalezeny (alpha API): ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Speech Recognition selhala: ${e.message}")
            null
        }
    }

    // ==================== ML KIT GENAI BETA2 ENTRY POINT (2026) ====================

    /**
     * Prim\u00e1rn\u00ed pokus o vyvol\u00e1n\u00ed ML Kit GenAI API p\u0159es beta2 API surface (aktu\u00e1ln\u00ed k 05/2026).
     *
     * Spr\u00e1vn\u00fd vzor pro ML Kit GenAI beta2:
     *   val client = [EntryPoint].getClient()           // napr. Generation.getClient()
     *   val status = client.checkStatus()               // nebo checkFeatureStatus()
     *   val request = [Request].builder().setText(t).build()
     *   val result  = client.generateContent(request)   // nebo runInference()
     *
     * EntryPoint objekty (beta2 singletons):
     *   - com.google.mlkit.genai.prompt.Generation
     *   - com.google.mlkit.genai.summarization.Summarization
     *   - com.google.mlkit.genai.proofreading.Proofreading
     *   - com.google.mlkit.genai.rewriting.Rewriting
     *
     * @param entryPointClass  Fully-qualified n\u00e1zev entry point t\u0159\u00eddy (singleton/companion)
     * @param requestPackage   Package pro request t\u0159\u00eddu
     * @param requestClassName N\u00e1zev request t\u0159\u00eddy (bez package)
     * @param inputText        Text k zpracov\u00e1n\u00ed
     * @param extraOptions     Voliteln\u00e9 setter parametry pro request (tone, inputType, atd.)
     * @return Textov\u00fd v\u00fdsledek, nebo null pokud beta2 API nen\u00ed dostupn\u00e9
     */
    private suspend fun tryBeta2EntryPoint(
        entryPointClass: String,
        requestPackage: String,
        requestClassName: String,
        inputText: String,
        extraOptions: Map<String, Any> = emptyMap()
    ): String? {
        return try {
            val epClass = Class.forName(entryPointClass)

            // \u2500\u2500 1. Z\u00edskat klienta p\u0159es getClient() bez parametr\u016f \u2500\u2500
            // Beta2: [EntryPoint].getClient() — static, 0 param\u016f
            // Kompatibilita: getClient(Context) — 1 param
            val getClientMethod = epClass.methods.firstOrNull { m ->
                (m.name == "getClient" || m.name == "getInstance" || m.name == "create") &&
                m.parameterCount <= 1
            } ?: run {
                Log.w(TAG, "⚠️ beta2: getClient() nenalezena na ${epClass.simpleName}")
                return null
            }

            val receiver = try {
                epClass.getDeclaredField("INSTANCE").get(null)
            } catch (e: Exception) {
                null
            }

            val client = if (getClientMethod.parameterCount == 0) {
                try {
                    getClientMethod.invoke(receiver) ?: return null
                } catch (e: Exception) {
                    try { getClientMethod.invoke(null) ?: return null } catch (e2: Exception) { return null }
                }
            } else {
                val paramType = getClientMethod.parameterTypes[0]
                if (android.content.Context::class.java.isAssignableFrom(paramType)) {
                    try {
                        getClientMethod.invoke(receiver, context) ?: return null
                    } catch (e: Exception) {
                        try { getClientMethod.invoke(null, context) ?: return null } catch (e2: Exception) { return null }
                    }
                } else if (paramType.simpleName.endsWith("Options")) {
                    try {
                        val options = buildOptionsReflective(paramType, extraOptions)
                        getClientMethod.invoke(receiver, options) ?: return null
                    } catch (e: Exception) {
                        try {
                            val options = buildOptionsReflective(paramType, extraOptions)
                            getClientMethod.invoke(null, options) ?: return null
                        } catch (e2: Exception) {
                            Log.e(TAG, "Chyba při sestavování options pro Beta2 getClient: ${e.message}")
                            return null
                        }
                    }
                } else {
                    Log.w(TAG, "Neznámý parametr pro beta2 getClient: ${paramType.name}")
                    return null
                }
            }

            Log.d(TAG, "✅ beta2: ${epClass.simpleName}.${getClientMethod.name}() úspěšně")

            // ── 2. Check status: checkStatus() nebo checkFeatureStatus() ──
            val statusMethod = client.javaClass.methods.firstOrNull { m ->
                (m.name == "checkStatus" || m.name == "checkFeatureStatus") &&
                m.parameterCount == 0
            }

            if (statusMethod != null) {
                val statusFuture = statusMethod.invoke(client)
                // Zkus\u00edme p\u0159e\u010d\u00edst stav \u2014 pokud je ListenableFuture<FeatureStatus> nebo ListenableFuture<Int>
                try {
                    val statusVal = awaitListenableFutureRawObject(statusFuture)
                    // FeatureStatus.AVAILABLE = 0, nebo enum s name "AVAILABLE"
                    val isAvailable = when (statusVal) {
                        is Int -> statusVal == 0
                        else -> statusVal?.toString()?.contains("AVAILABLE", ignoreCase = true) == true
                    }
                    if (!isAvailable) {
                        Log.w(TAG, "⚠️ beta2: ${epClass.simpleName} status = $statusVal (NOT AVAILABLE) — model není stažen")
                        return null
                    }
                    Log.i(TAG, "✅ beta2: ${epClass.simpleName} AVAILABLE (status=$statusVal)")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ beta2: checkStatus() výsledek nelze interpretovat (${e.message}) — pokračuji s inferencí")
                }
            }

            // ── 3. Build request ──
            val requestClass = try {
                Class.forName("$requestPackage.$requestClassName")
            } catch (e: ClassNotFoundException) {
                null
            }
            
            val request = if (requestClass != null) {
                buildBeta2Request(requestClass, inputText, extraOptions)
            } else null

            if (request == null && requestClass != null) {
                Log.w(TAG, "⚠️ beta2: Request builder nenalezen pro $requestClassName")
                return null
            }

            // ── 4. Inference: generateContent() nebo runInference() ──
            val inferMethod = client.javaClass.methods.firstOrNull { m ->
                (m.name == "generateContent" || m.name == "runInference" ||
                 m.name == "annotate" || m.name == "run") &&
                m.parameterCount == 1 &&
                (request != null || m.parameterTypes[0] == String::class.java)
            } ?: run {
                Log.w(TAG, "⚠️ beta2: Inference metoda nenalezena na ${client.javaClass.simpleName}")
                return null
            }

            val arg = request ?: inputText
            Log.d(TAG, "🧠 beta2: Vyvolávám ${client.javaClass.simpleName}.${inferMethod.name}()")
            val future = inferMethod.invoke(client, arg)
            val result = awaitListenableFutureText(future)

            if (result != null) {
                Log.i(TAG, "\u2705 beta2: ${epClass.simpleName} inference OK (${result.length} znak\u016f)")
            }
            result

        } catch (e: ClassNotFoundException) {
            // Ticho \u2014 beta2 API nen\u00ed v tomto ML Kit buildu, p\u0159ejdeme na beta1 fallback
            Log.d(TAG, "\u2139\ufe0f beta2: T\u0159\u00edda '$entryPointClass' nenalezena \u2014 pou\u017eiji\u00edm beta1 fallback")
            null
        } catch (e: Exception) {
            Log.w(TAG, "\u26a0\ufe0f beta2: ${entryPointClass.substringAfterLast('.')} selhal (${e.javaClass.simpleName}: ${e.message}) \u2014 p\u0159ech\u00e1z\u00edm na beta1")
            null
        }
    }

    /**
     * Sestavuje Request objekt pro beta2 API.
     * Zkou\u0161\u00ed: builder().setText/setPrompt/setSourceText/setInputText(text).extraSetters().build()
     * Fallback: konstruktor(String), statick\u00e9 create(String).
     */
    private fun buildBeta2Request(
        requestClass: Class<*>,
        inputText: String,
        extraOptions: Map<String, Any>
    ): Any? {
        // Vzor 1: builder() → text setter → extra setters → build()
        try {
            val builderFactory = requestClass.methods.firstOrNull { m ->
                (m.name == "builder" || m.name == "newBuilder") && m.parameterCount == 0
            }
            if (builderFactory != null) {
                val builder = builderFactory.invoke(null)
                // Text setters — zkouší v pořadí priorit
                val textSetters = listOf("setText", "setPrompt", "setSourceText", "setInputText", "setContent")
                for (setterName in textSetters) {
                    val setter = builder.javaClass.methods.firstOrNull { m ->
                        m.name == setterName && m.parameterCount == 1
                    }
                    if (setter != null) {
                        setter.invoke(builder, inputText)
                        break
                    }
                }
                // Extra options (tone, inputType, outputType)
                for ((setterName, value) in extraOptions) {
                    try {
                        val setter = builder.javaClass.methods.firstOrNull { m ->
                            m.name == setterName && m.parameterCount == 1
                        }
                        setter?.invoke(builder, value)
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ beta2 request setter '$setterName' selhal: ${e.message}")
                    }
                }
                val buildMethod = builder.javaClass.methods.firstOrNull { m ->
                    m.name == "build" && m.parameterCount == 0
                }
                if (buildMethod != null) return buildMethod.invoke(builder)
            }
        } catch (_: Exception) {}

        // Vzor 2: konstruktor(String)
        try {
            val ctor = requestClass.constructors.firstOrNull { c ->
                c.parameterCount == 1 && c.parameterTypes[0] == String::class.java
            }
            if (ctor != null) return ctor.newInstance(inputText)
        } catch (_: Exception) {}

        // Vzor 3: statické create(String)
        try {
            val createM = requestClass.methods.firstOrNull { m ->
                m.name == "create" && m.parameterCount == 1 &&
                m.parameterTypes[0] == String::class.java
            }
            if (createM != null) return createM.invoke(null, inputText)
        } catch (_: Exception) {}

        return null
    }

    // ==================== REFLECTION UTILITY METHODS ====================


    /**
     * Generický builder pro Options třídy.
     * Hledá: OptionsClass.builder(Context) → Builder → .set*(value) → .build()
     */
    private fun buildOptionsReflective(
        optionsClass: Class<*>,
        setterParams: Map<String, Any>
    ): Any {
        // Hledáme builder factory: builder(Context) nebo newBuilder(Context)
        val builderMethod = optionsClass.methods.firstOrNull {
            (it.name == "builder" || it.name == "newBuilder") &&
            (it.parameterCount == 0 || (it.parameterCount == 1 && android.content.Context::class.java.isAssignableFrom(it.parameterTypes[0])))
        } ?: throw IllegalStateException("Options builder nenalezen pro ${optionsClass.simpleName}")

        val builder = if (builderMethod.parameterCount == 0) {
            builderMethod.invoke(null)
        } else {
            builderMethod.invoke(null, context)
        }

        // Aplikace setter parametrů
        for ((setterName, value) in setterParams) {
            try {
                val setter = builder.javaClass.methods.firstOrNull {
                    it.name == setterName && it.parameterCount == 1
                }
                setter?.invoke(builder, value)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Setter '$setterName' selhal: ${e.message}")
            }
        }

        // .build()
        val buildMethod = builder.javaClass.methods.firstOrNull { it.name == "build" && it.parameterCount == 0 }
            ?: throw IllegalStateException("build() nenalezena na ${builder.javaClass.simpleName}")

        return buildMethod.invoke(builder)
    }

    /**
     * Generický factory pro klienta.
     * Hledá: ClientClass.getClient(Options) nebo ClientClass.create(Options)
     */
    private fun getOrCreateClientReflective(
        clientClass: Class<*>,
        optionsClass: Class<*>,
        options: Any
    ): Any? {
        // Hledáme factory: getClient(Options), create(Options), nebo newClient(Options)
        val factoryNames = listOf("getClient", "create", "newClient")
        for (name in factoryNames) {
            try {
                val method = clientClass.methods.firstOrNull {
                    it.name == name && it.parameterCount == 1
                }
                if (method != null) {
                    return method.invoke(null, options)
                }
            } catch (_: Exception) { /* zkus další */ }
        }

        Log.e(TAG, "❌ Factory metoda nenalezena na ${clientClass.simpleName}")
        return null
    }

    /**
     * FeatureStatus check - vrací true pokud API je AVAILABLE.
     * checkFeatureStatus() → ListenableFuture<Int> kde 0 = AVAILABLE
     */
    private suspend fun checkFeatureStatusReflective(client: Any): Boolean {
        return try {
            val checkMethod = client.javaClass.methods.firstOrNull {
                it.name == "checkFeatureStatus" && it.parameterCount == 0
            } ?: return true // Pokud metoda neexistuje, předpokládáme dostupnost

            val future = checkMethod.invoke(client)
            // Enum ordinal nebo Int: UNAVAILABLE=0, DOWNLOADABLE=1, DOWNLOADING=2, AVAILABLE=3
            val rawValue = awaitListenableFutureRawObject(future)
            val ordinal: Int = when (rawValue) {
                is Int -> rawValue
                else   -> rawValue?.let {
                    try { it.javaClass.getMethod("ordinal").invoke(it) as? Int ?: -1 }
                    catch (_: Exception) {
                        rawValue.toString().let { s ->
                            when {
                                s.contains("AVAILABLE", true) && !s.contains("UN") && !s.contains("DOWN") -> 3
                                s.contains("DOWNLOADING", true) -> 2
                                s.contains("DOWNLOADABLE", true) -> 1
                                else -> 0
                            }
                        }
                    }
                } ?: -1
            }

            // OPRAVA: AVAILABLE = 3, UNAVAILABLE = 0. Původní kód měl invertovanou logiku.
            // ordinal -1 = exception / neznámý = zacházíme jako UNAVAILABLE
            when {
                ordinal == 0 || ordinal == -1 -> {
                    Log.w(TAG, "⚠️ FeatureStatus ordinal=$ordinal (UNAVAILABLE) — model nepodporovaný")
                    false
                }
                ordinal in 1..2 -> {
                    Log.i(TAG, "ℹ️ FeatureStatus ordinal=$ordinal (DOWNLOADABLE/DOWNLOADING) — spouštím download")
                    tryRequestInstall(client)
                    false // Model ještě není ready, nespouštět inferenci
                }
                else -> {
                    Log.i(TAG, "✅ FeatureStatus ordinal=$ordinal (AVAILABLE)")
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ FeatureStatus check selhal: ${e.message}")
            false
        }
    }

    /**
     * Pokusí se spustit stažení modelu přes requestFeatureInstall().
     * Volá se asynchronně — model se stahuje na pozadí.
     */
    private fun tryRequestInstall(client: Any) {
        try {
            val installMethod = client.javaClass.methods.firstOrNull {
                (it.name == "requestFeatureInstall" || it.name == "requestInstall" || it.name == "download") &&
                it.parameterCount == 0
            }
            if (installMethod != null) {
                installMethod.invoke(client)
                Log.i(TAG, "✅ requestFeatureInstall() zavolán — model se stahuje na pozadí")
            } else {
                Log.w(TAG, "⚠️ requestFeatureInstall() metoda nenalezena na ${client.javaClass.simpleName}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ requestFeatureInstall selhal: ${e.message}")
        }
    }

    /**
     * Generický request builder.
     * Hledá: RequestClass(text) konstruktor, nebo builder pattern.
     */
    private fun buildRequestReflective(requestClass: Class<*>, inputText: String): Any? {
        // Vzor 1: Konstruktor s String parametrem
        try {
            val ctor = requestClass.constructors.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
            }
            if (ctor != null) return ctor.newInstance(inputText)
        } catch (_: Exception) {}

        // Vzor 2: builder() → .setText(text) / .setSourceText(text) / .setPrompt(text) → .build()
        try {
            val builderFactory = requestClass.methods.firstOrNull {
                (it.name == "builder" || it.name == "newBuilder") && it.parameterCount <= 1
            }
            if (builderFactory != null) {
                val builder = if (builderFactory.parameterCount == 0) {
                    builderFactory.invoke(null)
                } else if (builderFactory.parameterTypes[0] == String::class.java) {
                    builderFactory.invoke(null, inputText)
                } else {
                    builderFactory.invoke(null, context)
                }

                // Hledáme text setter
                val setterNames = listOf("setText", "setSourceText", "setInputText", "setPrompt")
                for (setter in setterNames) {
                    try {
                        val method = builder.javaClass.methods.firstOrNull {
                            it.name == setter && it.parameterCount == 1
                        }
                        if (method != null) {
                            method.invoke(builder, inputText)
                            break
                        }
                    } catch (_: Exception) {}
                }

                val buildMethod = builder.javaClass.methods.firstOrNull {
                    it.name == "build" && it.parameterCount == 0
                }
                if (buildMethod != null) {
                    return buildMethod.invoke(builder)
                }
            }
        } catch (_: Exception) {}

        // Vzor 3: Statická create(text) metoda
        try {
            val create = requestClass.methods.firstOrNull {
                it.name == "create" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
            }
            if (create != null) return create.invoke(null, inputText)
        } catch (_: Exception) {}

        Log.e(TAG, "❌ Request builder nenalezen pro ${requestClass.simpleName}")
        return null
    }

    /**
     * Generické runInference volání — vrací textový výsledek.
     * client.runInference(request) → ListenableFuture<Result>
     */
    private suspend fun runInferenceReflective(client: Any, request: Any, requestClass: Class<*>): String? {
        val runMethod = client.javaClass.methods.firstOrNull {
            (it.name == "runInference" || it.name == "run" || it.name == "annotate") &&
            it.parameterCount == 1
        } ?: run {
            Log.e(TAG, "❌ runInference metoda nenalezena na ${client.javaClass.simpleName}")
            return null
        }

        val future = runMethod.invoke(client, request)
        return awaitListenableFutureText(future)
    }

    /**
     * Generický reflection-based GenAI volání (pro Prompt API a podobné).
     */
    private suspend fun executeGenAiReflection(
        packagePrefix: String,
        clientClassName: String,
        optionsClassName: String,
        requestClassName: String,
        inputText: String,
        inputSetterName: String = "setText"
    ): String? {
        val clientClass = Class.forName("$packagePrefix.$clientClassName")
        val optionsClass = Class.forName("$packagePrefix.$optionsClassName")
        val requestClass = Class.forName("$packagePrefix.$requestClassName")

        // ── OPRAVA: PromptApiClientOptions nemá vždy builder(Context) ──
        // Zkouší 3 vzory v pořadí:
        // 1. builder(Context) → build() — standardní vzor
        // 2. getClient(Context) přímo na clientClass — vzor bez Options
        // 3. create(Context) přímo na clientClass — alternativní factory
        val client: Any? = run {
            // Vzor 1: standardní Options builder
            try {
                val options = buildOptionsReflective(optionsClass, emptyMap())
                getOrCreateClientReflective(clientClass, optionsClass, options)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ [$clientClassName] Options builder selhal (${e.javaClass.simpleName}: ${e.message}) — zkouším přímý Context factory")
                // Vzor 2: ClientClass.getClient(Context)
                val direct = tryContextFactoryOnClass(clientClass)
                if (direct == null) {
                    Log.w(TAG, "⚠️ [$clientClassName] Přímý Context factory také selhal — API není dostupné na tomto zařízení/verzi ML Kit")
                }
                direct
            }
        } ?: return null
        @Suppress("UNNECESSARY_NOT_NULL_ASSERTION") // client je garantovaně non-null díky ?: return null výše
        val clientNonNull: Any = client!!

        // ── Ověř dostupnost modelu ──
        val statusOk = checkFeatureStatusReflective(clientNonNull)
        if (!statusOk) {
            Log.w(TAG, "⚠️ [$clientClassName] checkFeatureStatus vrátil NOT_AVAILABLE — model není stažen nebo zařízení nepodporuje")
            return null
        }

        val request = buildRequestReflective(requestClass, inputText) ?: return null
        return runInferenceReflective(clientNonNull, request, requestClass)
    }

    /**
     * Pokusí se získat klienta přímo přes Context (bez Options).
     * Hledá: ClientClass.getClient(Context), ClientClass.create(Context).
     */
    private fun tryContextFactoryOnClass(clientClass: Class<*>): Any? {
        val factoryNames = listOf("getClient", "create", "newClient", "getInstance")
        for (name in factoryNames) {
            try {
                val method = clientClass.methods.firstOrNull {
                    it.name == name &&
                    it.parameterCount == 1 &&
                    Context::class.java.isAssignableFrom(it.parameterTypes[0])
                }
                if (method != null) {
                    Log.i(TAG, "✅ Context factory nalezen: ${clientClass.simpleName}.$name(Context)")
                    return method.invoke(null, context)
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Context factory '$name' selhal: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return null
    }

    /**
     * Reflection-based GenAI volání s Bitmap inputem (Image Description).
     */
    private suspend fun executeGenAiReflectionWithBitmap(
        packagePrefix: String,
        clientClassName: String,
        optionsClassName: String,
        requestClassName: String,
        bitmap: Bitmap
    ): String? {
        val clientClass = Class.forName("$packagePrefix.$clientClassName")
        val optionsClass = Class.forName("$packagePrefix.$optionsClassName")
        val requestClass = Class.forName("$packagePrefix.$requestClassName")

        val options = buildOptionsReflective(optionsClass, emptyMap())
        val client = getOrCreateClientReflective(clientClass, optionsClass, options) ?: return null

        // Build request s Bitmap
        val request = try {
            val builderFactory = requestClass.methods.firstOrNull {
                it.name == "builder" || it.name == "newBuilder"
            }
            if (builderFactory != null) {
                val builder = if (builderFactory.parameterCount == 0) {
                    builderFactory.invoke(null)
                } else {
                    builderFactory.invoke(null, context)
                }
                val setter = builder.javaClass.methods.firstOrNull {
                    it.name == "setImage" && it.parameterCount == 1
                }
                setter?.invoke(builder, bitmap)
                val buildMethod = builder.javaClass.methods.firstOrNull { it.name == "build" }
                buildMethod?.invoke(builder)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Image request build selhal: ${e.message}")
            null
        } ?: return null

        return runInferenceReflective(client, request, requestClass)
    }

    // ==================== LISTENABLE FUTURE HELPERS ====================

    /**
     * Await ListenableFuture a extrahuj textový výsledek.
     */
    private suspend fun awaitListenableFutureText(future: Any?): String? {
        if (future == null) return null

        val rawResult = awaitListenableFutureRawObject(future)
        return extractTextFromResult(rawResult)
    }

    /**
     * Await ListenableFuture<Int> pro FeatureStatus.
     */
    private suspend fun awaitListenableFutureRaw(future: Any?): Int {
        if (future == null) return -1

        return suspendCancellableCoroutine { cont ->
            if (future is ListenableFuture<*>) {
                future.addListener({
                    try {
                        val result = future.get()
                        cont.resume(result as? Int ?: -1)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ ListenableFuture selhala: ${e.message}")
                        cont.resume(-1)
                    }
                }, callbackExecutor)
            } else {
                // Pokus přes reflection (get())
                try {
                    val getMethod = future.javaClass.getMethod("get")
                    val result = getMethod.invoke(future)
                    cont.resume(result as? Int ?: -1)
                } catch (e: Exception) {
                    cont.resume(-1)
                }
            }
        }
    }

    /**
     * Await ListenableFuture<Any> pro inference výsledky.
     */
    private suspend fun awaitListenableFutureRawObject(future: Any?): Any? {
        if (future == null) return null

        return suspendCancellableCoroutine { cont ->
            if (future is ListenableFuture<*>) {
                future.addListener({
                    try {
                        val result = future.get()
                        cont.resume(result)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ ListenableFuture inference selhala: ${e.message}")
                        cont.resume(null)
                    }
                }, callbackExecutor)
            } else {
                try {
                    val getMethod = future.javaClass.getMethod("get")
                    cont.resume(getMethod.invoke(future))
                } catch (e: Exception) {
                    cont.resume(null)
                }
            }
        }
    }

    /**
     * Extrahuje textový výsledek z ML Kit GenAI responses.
     * Zkouší: getSummary(), getText(), getResult(), getDescription()
     */
    private fun extractTextFromResult(result: Any?): String? {
        if (result == null) return null

        // Beta1 getter names pro všechny GenAI výsledkové třídy
        val getterNames = listOf(
            "getSummary",       // SummarizationResult
            "getCorrectedText", // ProofreadingResult
            "getRewrittenText", // RewritingResult
            "getText",          // PromptResult / obecné
            "getResult",
            "getDescription",   // ImageDescriptionResult
            "getTranscript",    // SpeechRecognitionResult
            "getContent",
            "getGeneratedText"
        )
        for (getter in getterNames) {
            try {
                val method = result.javaClass.getMethod(getter)
                val value = method.invoke(result)
                if (value is String && value.isNotBlank()) return value
            } catch (_: Exception) {}
        }

        // Fallback: toString() — pokud to není FQCN objektu
        val str = result.toString()
        return str.takeIf { it.isNotBlank() && it != "null" && !it.startsWith("com.google.") }
    }

    /**
     * Bezpečné zavření klienta přes reflection.
     */
    private fun closeClientSafe(client: Any?) {
        if (client == null) return
        try {
            val closeMethod = client.javaClass.methods.firstOrNull {
                it.name == "close" && it.parameterCount == 0
            }
            closeMethod?.invoke(client)
        } catch (_: Exception) {}
    }

    // ==================== PRE-CHECKS & THERMAL ====================

    private fun performPreChecks(
        apiName: String,
        inputText: String,
        maxLength: Int
    ): AiCoreResult? {
        if (!isAICoreAvailable(context)) {
            Log.w(TAG, "⚠️ $apiName: AICore nedostupný → fallback")
            return AiCoreResult.Error(ErrorCode.DEVICE_NOT_SUPPORTED, "AICore nedostupný")
        }
        if (inputText.isBlank()) {
            return AiCoreResult.Error(ErrorCode.INPUT_TOO_LONG, "Vstupní text je prázdný")
        }
        if (inputText.length > maxLength) {
            return AiCoreResult.Error(
                ErrorCode.INPUT_TOO_LONG,
                "Vstup (${inputText.length} znaků) překračuje limit $maxLength"
            )
        }
        if (isThermalThrottling()) {
            return AiCoreResult.Error(ErrorCode.THERMAL_THROTTLE, "Zařízení přehřáté")
        }
        return null
    }

    /**
     * PowerManager thermal status check.
     * THERMAL_STATUS_SEVERE nebo vyšší → true.
     */
    private fun isThermalThrottling(): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm != null && pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        } catch (_: Exception) {
            false
        }
    }

    // ==================== ENUM MAPOVÁNÍ NA INT KONSTANTY ====================

    /**
     * Mapování SummaryInputType → int (ML Kit GenAI SummarizerOptions.InputType).
     * ARTICLE = 0, CONVERSATION = 1 (dle ML Kit beta1 decompile)
     */
    private fun mapInputType(type: SummaryInputType): Int = when (type) {
        SummaryInputType.ARTICLE -> 0
        SummaryInputType.CONVERSATION -> 1
    }

    /**
     * Mapování SummaryOutputType → int (ML Kit GenAI SummarizerOptions.OutputType).
     * ONE_BULLET = 0, THREE_BULLETS = 2 (dle ML Kit beta1 decompile)
     */
    private fun mapOutputType(type: SummaryOutputType): Int = when (type) {
        SummaryOutputType.ONE_BULLET -> 0
        SummaryOutputType.THREE_BULLETS -> 2
        SummaryOutputType.SHORT -> 0
    }

    /**
     * Mapování RewriteTone → int (ML Kit GenAI RewriterOptions constants).
     * Hodnoty dle ML Kit beta1 decompile.
     */
    private fun mapRewriteTone(tone: RewriteTone): Int = when (tone) {
        RewriteTone.ELABORATE -> 0
        RewriteTone.EMOJIFY -> 1
        RewriteTone.SHORTEN -> 2
        RewriteTone.FRIENDLY -> 3
        RewriteTone.PROFESSIONAL -> 4
        RewriteTone.REPHRASE -> 5
    }
}
