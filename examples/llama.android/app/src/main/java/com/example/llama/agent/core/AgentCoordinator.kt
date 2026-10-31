package com.example.llama.agent.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.arm.aichat.InferenceEngine
import com.example.llama.SettingsManager
import com.example.llama.agent.core.AgentHelper as AgentHelperUtil
import com.example.llama.agent.macro.Macro
import com.example.llama.agent.macro.MacroAction
import com.example.llama.agent.macro.MacroEngine
import com.example.llama.agent.models.ActionType
import com.example.llama.agent.models.AgentAction
import com.example.llama.agent.models.AgentMode
import com.example.llama.agent.models.ScreenState
import com.example.llama.agent.perception.AudioDetectionEvent
import com.example.llama.agent.perception.AudioLabelTranslations
import com.example.llama.agent.perception.EngineState
import com.example.llama.agent.perception.EventPriority
import com.example.llama.agent.perception.GazeStateEvent
import com.example.llama.agent.perception.NlpSummaryWorker
import com.example.llama.agent.perception.PerceptionEvent
import com.example.llama.agent.perception.PerceptionEventBus
import com.example.llama.agent.perception.PerceptionLifecycleEvent
import com.example.llama.agent.perception.PerceptionStateRepository
import com.example.llama.agent.perception.RequestNLPCompressionEvent
import com.example.llama.agent.perception.SceneChangeEvent
import com.example.llama.agent.perception.ScreenContextEvent
import com.example.llama.agent.perception.SystemDrmEvent
import com.example.llama.agent.perception.TimestampedSummary
import com.example.llama.agent.perception.TranscriptEvent
import com.example.llama.agent.perception.UiAction
import com.example.llama.agent.perception.UiActionEvent
import com.example.llama.agent.perception.VisionUpdateEvent
import com.example.llama.agent.service.AiAgentAccessibilityService
import com.example.llama.agent.utils.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.launch

/**
 * AgentCoordinator - Hlavní orchestrátor AI Agenta
 *
 * Spojuje všechny komponenty dohromady (Integrated):
 * - ScreenParser (UI → text)
 * - InferenceEngine (LLM)
 * - JsonParser (JSON → AgentAction)
 * - Action Executor (AgentAction → Android gestures)
 * - SafetyGuard (security checks)
 *
 * Flow:
 * 1. Parse screen (ScreenParser)
 * 2. Safety checks (SafetyGuard)
 * 3. Build prompt (ScreenParser)
 * 4. Get LLM decision (InferenceEngine)
 * 5. Parse JSON (JsonParser)
 * 6. Execute action (ActionExecutor)
 */
class AgentCoordinator(
        private val appContext: Context,
        private val screenParserProvider: () -> ScreenParser?,
        private val actionExecutor: ActionExecutor,
        private val safetyGuard: SafetyGuard,
        private val inferenceEngine: InferenceEngine,
        private val settingsManager: SettingsManager,
        private val systemController: SystemController? = null,
        private val rootNodeProvider: (() -> AccessibilityNodeInfo?)? = null,
        private val screenshotProvider: (suspend () -> android.graphics.Bitmap?)? = null,
        private val perceptionRepository: PerceptionStateRepository? = null,
        private val ttsCallback: ((String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "AgentCoordinator"

        /**
         * GBNF Grammar pro Agent JSON output. Vynucuje strukturu: {"thought": "...", "action":
         * "...", "target_id": N, "input_text": "..."} action je omezena na povolené enum hodnoty.
         * target_id a input_text jsou volitelné.
         */
        private val AGENT_JSON_GRAMMAR =
                """
root ::= "{" ws thought-kv "," ws action-kv target-opt input-opt detections-opt ws "}"
thought-kv ::= "\"thought\"" ws ":" ws string
action-kv ::= "\"action\"" ws ":" ws action-val
target-opt ::= ("," ws "\"target_id\"" ws ":" ws number)?
input-opt ::= ("," ws "\"input_text\"" ws ":" ws string)?
detections-opt ::= ("," ws "\"detections\"" ws ":" ws "[" ws detection ("," ws detection)* ws "]")?
detection ::= "{" ws label-kv "," ws conf-kv "," ws box-kv ws "}"
label-kv ::= "\"label\"" ws ":" ws string
conf-kv ::= "\"confidence\"" ws ":" ws number-float
box-kv ::= "\"xMin\"" ws ":" ws number-float "," ws "\"yMin\"" ws ":" ws number-float "," ws "\"xMax\"" ws ":" ws number-float "," ws "\"yMax\"" ws ":" ws number-float
number-float ::= [0-9]+ ("." [0-9]+)?
action-val ::= "\"click\"" | "\"scroll_down\"" | "\"scroll_up\"" | "\"scroll_to_top\"" | "\"scroll_to_bottom\"" | "\"scroll_little_up\"" | "\"scroll_little_down\"" | "\"scroll_page_up\"" | "\"scroll_page_down\"" | "\"zoom_in\"" | "\"zoom_out\"" | "\"edit_delete_word\"" | "\"edit_delete_all\"" | "\"edit_select_all\"" | "\"edit_copy\"" | "\"edit_paste\"" | "\"edit_enter\"" | "\"edit_undo\"" | "\"edit_move_cursor_start\"" | "\"edit_move_cursor_end\"" | "\"nav_up\"" | "\"nav_down\"" | "\"nav_left\"" | "\"nav_right\"" | "\"nav_center\"" | "\"nav_tab\"" | "\"input_text\"" | "\"back\"" | "\"home\"" | "\"speak\"" | "\"open_app\"" | "\"recent_apps\"" | "\"notifications\"" | "\"call\"" | "\"sms\"" | "\"open_url\"" | "\"compose_email\"" | "\"answer_call\"" | "\"reject_call\"" | "\"open_settings\"" | "\"set_wifi\"" | "\"set_bluetooth\"" | "\"set_flashlight\"" | "\"set_nfc\"" | "\"set_airplane\"" | "\"kill_app\"" | "\"set_volume\"" | "\"set_brightness\"" | "\"set_rotation\"" | "\"set_default_ime\"" | "\"screenshot\"" | "\"lock_screen\"" | "\"quick_settings\"" | "\"split_screen\"" | "\"power_dialog\"" | "\"swipe_left\"" | "\"swipe_right\"" | "\"swipe_up\"" | "\"swipe_down\"" | "\"long_press\"" | "\"double_tap\"" | "\"rapid_tap\"" | "\"media_play_pause\"" | "\"media_next\"" | "\"media_previous\"" | "\"volume_up\"" | "\"volume_down\"" | "\"brightness_up\"" | "\"brightness_down\"" | "\"nlp_proofread\"" | "\"nlp_rewrite\"" | "\"nlp_translate\"" | "\"nlp_summarize\"" | "\"nlp_sentiment\"" | "\"nlp_smart_reply\""
string ::= "\"" char* "\""
char ::= [^"\\] | "\\" ["\\/bfnrt]
number ::= [0-9]+
number-float ::= [0-9]+ ("." [0-9]+)?
ws ::= [ \t\n\r]*
""".trimIndent()
    }

    private val jsonParser = JsonParser()
    private val directCommandMatcher = DirectCommandMatcher(systemController)
    private val contextTracker = CommandContextTracker()

    // Background scope pro asynchronní Tier 2.5 operace
    private val coordinatorScope = CoroutineScope(Dispatchers.Default + Job())

    // NlpSummaryWorker (Tier 2.5) — On-Demand komprese textu (lazy init)
    private val nlpSummaryWorker by lazy { NlpSummaryWorker(appContext, settingsManager) }

    // NlpActionHandler (Tier 2.5) — On-Demand NLP akce pro explicitní UI tlačítka
    private val nlpActionHandler by lazy { com.example.llama.agent.core.NlpActionHandler(appContext, settingsManager) }

    // Floating UI (Tier 2.5 NLP a Vision Output Popup)
    private val floatingPopup by lazy { com.example.llama.agent.ui.FloatingResultPopup(appContext) }

    // Smart Select UI okna
    private val smartSelectOverlay by lazy {
        com.example.llama.agent.ui.SmartSelectOverlay(
            appContext,
            rootNodeProvider = { rootNodeProvider?.invoke() },
            screenshotProvider = { screenshotProvider?.invoke() }
        )
    }

    private val contextMenuToolbar by lazy {
        com.example.llama.agent.ui.ContextMenuToolbar(appContext)
    }

    // MacroEngine pro compound command execution (lazy init — vyžaduje non-null dependencies)
    private val macroEngine: MacroEngine? by lazy {
        if (systemController != null && rootNodeProvider != null) {
            MacroEngine(
                    screenParserProvider,
                    actionExecutor,
                    systemController,
                    rootNodeProvider,
                    appContext
            )
        } else {
            Log.w(TAG, "MacroEngine not available — missing systemController or rootNodeProvider")
            null
        }
    }

    /**
     * Main entry point: Execute user command
     *
     * @param userCommand Příkaz od uživatele (např. "Najdi video o kočkách")
     * @param rootNode AccessibilityNodeInfo root (current screen)
     * @return Result<String> - success message nebo error
     */
    suspend fun executeCommand(
            userCommand: String,
            rootNode: AccessibilityNodeInfo?,
            onStateUpdate: (AgentMode) -> Unit = {}
    ): Result<String> = runCatching {

        // File-based Tier trace (Samsung potlačuje logcat)
        val tierTrace = StringBuilder()
        tierTrace.appendLine("--- Coordinator Trace ---")
        tierTrace.appendLine("Command: \"$userCommand\"")
        tierTrace.appendLine(
                "rootNode: ${if (rootNode != null) "OK (${rootNode.packageName}, ${rootNode.childCount})" else "NULL"}"
        )
        tierTrace.appendLine("rootNodeProvider: ${if (rootNodeProvider != null) "SET" else "NULL"}")
        tierTrace.appendLine("macroEngine: ${if (macroEngine != null) "OK" else "NULL"}")
        tierTrace.appendLine(
                "inferenceEngine state: ${inferenceEngine.state.value.javaClass.simpleName}"
        )

        fun writeCoordinatorTrace() {
            try {
                val f = java.io.File(appContext.filesDir, "agent_coordinator_trace.txt")
                f.writeText(tierTrace.toString())
            } catch (_: Exception) {}
        }

        Log.i(TAG, "🚀 Executing command: \"$userCommand\"")
        // Reset trackeru pouze pro TOP-LEVEL volání, ne pro rekurzivní (partial compound).
        // Rekurzivní volání přichází s už obohaceným příkazem a má kontext z předchozích kroků.
        val isRecursiveCall = userCommand.startsWith("=== Kontext předchozích kroků ===")
        if (!isRecursiveCall) contextTracker.reset()

        // [INTEGRATION] getContextSummary: Logování kontextu při rekurzivním volání
        if (isRecursiveCall) {
            val ctx = contextTracker.getContextSummary()
            if (ctx.isNotEmpty()) Log.d(TAG, "📝 Context summary for recursive call:\n$ctx")
        }

        // ==================== STEP 0.5: Direct Command Match (pre-LLM, <1ms) ====================
        // Inspirováno Samsung/Google Voice Access — jednoduché příkazy bez LLM
        // Asynchronní zpracování v Default Threadu, aby neblokovalo UI thread.
        val matchResult =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    directCommandMatcher.matchWithContext(userCommand)
                }
        tierTrace.appendLine(
                "[Tier1] matchWithContext: ${if (matchResult != null) "MATCH (${matchResult.action.type})" else "NO_MATCH"}"
        )
        if (matchResult != null) {
            val directMatch = matchResult.action
            Log.i(TAG, "⚡ Direct command: ${directMatch.type} — skipping LLM pipeline")

            // Compound: provede přes MacroEngine (context-aware, wait-for-element)
            val pendingActions = directCommandMatcher.consumePendingActions()
            if (!pendingActions.isNullOrEmpty()) {
                val allActions = listOf(directMatch) + pendingActions
                contextTracker.recordStep(directMatch.thought, directMatch.type, success = true)
                return@runCatching executeCompoundViaMacro(allActions, onStateUpdate)
            }

            // Standalone deferred akce (Tier 1.5) → MacroEngine pro screen-aware execution
            if (directMatch.thought.startsWith("[DEFERRED:")) {
                Log.i(TAG, "🎯 Standalone deferred → MacroEngine: ${directMatch.thought}")
                return@runCatching executeCompoundViaMacro(listOf(directMatch), onStateUpdate)
            }

            // PARTIAL COMPOUND: Tier 1 část provedena, zbytek jde do Tier 2 (nebo LLM pokud
            // enabled)
            if (matchResult.remainingCommand != null) {
                executeTier1Action(directMatch, onStateUpdate)
                contextTracker.recordStep(directMatch.thought, directMatch.type, success = true)
                if (settingsManager.agentUseLlmFallback) {
                    // LLM fallback povolený: pošli zbytek příkazu do LLM s kontextem
                    Log.i(TAG, "⚡ Partial compound: Tier1 done, sending remainder to LLM")
                    val contextualCommand =
                            buildContextualPrompt(
                                    remaining = matchResult.remainingCommand,
                                    completedSteps = matchResult.completedSteps
                            )
                    return@runCatching executeCommand(contextualCommand, rootNode, onStateUpdate)
                            .getOrElse {
                                "⚡ ${directMatch.thought} (LLM část selhala: ${it.message})"
                            }
                } else {
                    // LLM bypass: pošli zbytek příkazu zpět do executeCommand → Tier 2 ho zkusí
                    Log.i(
                            TAG,
                            "⚡ Partial compound (no-LLM): Tier1 done, sending remainder to Tier2: \"${matchResult.remainingCommand}\""
                    )
                    return@runCatching executeCommand(
                                    matchResult.remainingCommand,
                                    rootNode,
                                    onStateUpdate
                            )
                            .getOrElse { "⚡ ${directMatch.thought} (zbytek: ${it.message})" }
                }
            }

            // Single akce — přímé provedení
            tierTrace.appendLine("[Tier1] Executing single action: ${directMatch.type}")
            writeCoordinatorTrace()
            executeTier1Action(directMatch, onStateUpdate)
            contextTracker.recordStep(directMatch.thought, directMatch.type, success = true)
            return@runCatching "⚡ ${directMatch.thought}"
        }

        // ==================== STEP 0.6: Tier 2 — Screen-Aware Match ====================
        // Po screen parse, ale před LLM — matchWithScreen hledá element na obrazovce
        val quickRoot = rootNodeProvider?.invoke()
        tierTrace.appendLine(
                "[Tier2] rootNodeProvider: ${if (quickRoot != null) "OK (${quickRoot.packageName}, ${quickRoot.childCount})" else "NULL"}"
        )
        if (quickRoot != null) {
            Log.d(
                    TAG,
                    "👁️ Tier 2: rootNodeProvider OK — package=${quickRoot.packageName}, children=${quickRoot.childCount}"
            )

            // Asynchronní parsování a matchování obrazovky, velmi důležité pro plynulost
            val tier2ResultPair =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        val parser = screenParserProvider()
                        val qs = parser?.parseScreen(quickRoot)
                        val sm =
                                if (qs != null)
                                        directCommandMatcher.matchWithScreen(userCommand, qs)
                                else null
                        Pair(qs, sm)
                    }

            val quickScreen = tier2ResultPair.first
            val screenMatch = tier2ResultPair.second

            if (quickScreen != null) {
                tierTrace.appendLine(
                        "[Tier2] parseScreen OK: ${quickScreen.elements.size} elements"
                )
                Log.d(TAG, "👁️ Tier 2: Parsed ${quickScreen.elements.size} elements")

                if (screenMatch != null) {
                    Log.i(TAG, "⚡ Tier 2 match: ${screenMatch.type} — skipping LLM pipeline")
                    onStateUpdate(AgentMode.Executing(screenMatch))
                    val tier2Result = actionExecutor.execute(screenMatch, quickScreen)
                    quickScreen.recycle()
                    // Emit výsledek do ActionResultBus
                    ActionResultBus.emit(
                            ActionResultBus.ActionResult(
                                    actionType = screenMatch.type,
                                    success = tier2Result.isSuccess,
                                    errorMessage = tier2Result.exceptionOrNull()?.message,
                                    targetId = screenMatch.targetId,
                                    thought = screenMatch.thought
                            )
                    )
                    tier2Result.getOrThrow()
                    return@runCatching "⚡ ${screenMatch.thought}"
                }
                quickScreen.recycle()
            } else {
                Log.w(
                        TAG,
                        "⚠️ Tier 2: screenParser.parseScreen() vrátil NULL — UI tree je prázdný nebo nastala chyba"
                )
                tierTrace.appendLine("[Tier2] parseScreen=NULL")
            }
        } else {
            Log.w(
                    TAG,
                    "⚠️ Tier 2: rootNodeProvider vrátil NULL — engine NEVIDÍ obrazovku pro Tier 2 matching"
            )
            tierTrace.appendLine("[Tier2] SKIPPED (rootNodeProvider=NULL)")
        }

        // ==================== STEP 0.65: Tier 2.5 — Perception-Aware Routing ====================
        // Inspirováno: Google Project Astra (I/O 2024) — perception → fast response
        //              Cascaded LLM Frameworks (arxiv 2024) — deferral policy
        //
        // Pokud PerceptionEngine běží a uživatel se ptá na fyzické okolí,
        // odpovíme PŘÍMO z PerceptionState BEZ probouzení LLM.
        // Výhody: 0 latence, 0 baterie, 0 KV cache, 0 thermal impact.
        if (perceptionRepository != null) {
            val perceptionSnapshot = perceptionRepository.snapshot.value
            val isPerceptionQuery = isPerceptionRelatedQuery(userCommand)

            tierTrace.appendLine(
                    "[Tier2.5] isPerceptionQuery=$isPerceptionQuery, hasContext=${perceptionSnapshot.hasUsefulContext()}"
            )

            if (isPerceptionQuery && perceptionSnapshot.hasUsefulContext()) {
                val perceptionResponse = perceptionSnapshot.toReadableContext()
                Log.i(
                        TAG,
                        "🌍 Tier 2.5: Perception-aware response (LLM bypassed): $perceptionResponse"
                )
                tierTrace.appendLine("[Tier2.5] MATCH — responding from PerceptionState")
                writeCoordinatorTrace()
                return@runCatching "🌍 $perceptionResponse"
            }
        }

        // ==================== STEP 0.7: LLM Bypass Guard ====================
        // Pokud agentUseLlmFallback=false (výchozí=OFF), Tier 3 LLM se nesmí volat.
        // Tier 1 a Tier 2 musí pokrýt 100% příkazů. Pokud selžou → informativní chyba přes TTS.
        if (!settingsManager.agentUseLlmFallback) {
            tierTrace.appendLine("[LLM_BYPASS] useLlmFallback=false → SKIPPING Tier 3 LLM")
            writeCoordinatorTrace()
            Log.w(
                    TAG,
                    "🚫 Tier 1/2 miss for: \"$userCommand\" — LLM bypass active (Tier 3 disabled)"
            )
            // Vrátíme descriptivní chybu — AiAgentAccessibilityService ji přeloží přes TTS
            throw IllegalStateException("Příkaz nepochopen: \"$userCommand\". Zkuste jinými slovy.")
        }

        Log.i(TAG, "🔄 Tier 1/2 miss → falling through to Tier 3 LLM pipeline")
        tierTrace.appendLine("[LLM_BYPASS] useLlmFallback=true → proceeding to Tier 3")

        val currentState = inferenceEngine.state.value
        tierTrace.appendLine("[ModelCheck] state=${currentState.javaClass.simpleName}")
        if (!AgentHelperUtil.isModelReady(currentState)) {
            // FIX: Rozlišení stavů — Initialized/Uninitialized = model není vůbec loaded
            // Generating/ProcessingUserPrompt = model loaded, jen busy → cancel+wait
            val stateName = currentState.javaClass.simpleName

            if (currentState is com.arm.aichat.InferenceEngine.State.Uninitialized ||
                            currentState is com.arm.aichat.InferenceEngine.State.Initialized
            ) {
                Log.w(TAG, "🚫 Model není načtený (stav: $stateName). Tier 3 LLM nedostupný.")
                tierTrace.appendLine("[ModelCheck] NOT_LOADED ($stateName) — LLM pipeline skipped")
                writeCoordinatorTrace()
                return@runCatching "Model není načtený. Načtěte model v záložce Chat před použitím agenta."
            }

            Log.w(TAG, "⏳ Model not ready (state: $stateName). Cancelling generation + waiting...")
            // CANCEL probíhající generování (chat) — nastaví _cancelGeneration flag
            inferenceEngine.cancelGeneration()
            try {
                // [CONFIG] LLM Cancel Wait z nastavení
                val cancelTimeout = settingsManager.agentLlmCancelTimeout
                kotlinx.coroutines.withTimeout(cancelTimeout) {
                    inferenceEngine.state.first { AgentHelperUtil.isModelReady(it) }
                }
                Log.i(TAG, "✓ Model became ready after cancel+wait")
                tierTrace.appendLine("[ModelCheck] OK after cancel+wait")
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "⚠️ LLM stále zaneprázdněn po cancel (timeout)")
                tierTrace.appendLine("[ModelCheck] TIMEOUT — LLM stále zaneprázdněn po cancel")
                writeCoordinatorTrace()
                return@runCatching "Model je zaneprázdněn. Zkuste zavřít chat a znovu."
            }
        }

        // ==================== STEP 0.8: Visual Trigger Interception (Multimodal Fusion)
        // ====================
        val isVisualQuery =
                userCommand.contains("vidíš", ignoreCase = true) ||
                        userCommand.contains("obrazov", ignoreCase = true) ||
                        userCommand.contains("ukazuj", ignoreCase = true) ||
                        userCommand.contains("co je na", ignoreCase = true)

        var finalLlmPrompt = ""

        // ==================== STEP 1: Parse Screen ====================
        if (rootNode == null) {
            Log.e(TAG, "🚨 Tier 3: rootNode=NULL! Engine nevidí obrazovku")
            tierTrace.appendLine("[Tier3] rootNode=NULL → ABORT")
            writeCoordinatorTrace()
            throw IllegalStateException(
                    "Nevidím obrazovku — zkontrolujte oprávnění Accessibility Service"
            )
        }

        val currentParser = screenParserProvider()
        if (currentParser == null) {
            Log.e(TAG, "🚨 Tier 3: ScreenParser je offline (OFF režim). Nelze pokračovat v LLM pipeline.")
            tierTrace.appendLine("[Tier3] ScreenParser=NULL → ABORT")
            writeCoordinatorTrace()
            throw IllegalStateException("Aktivujte ScreenParser v nastavení pro plnou analýzu obrazovky.")
        }

        val screenState =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    currentParser.parseScreen(rootNode)
                }
                        ?: run {
                            tierTrace.appendLine("[Tier3] parseScreen=NULL → ABORT")
                            writeCoordinatorTrace()
                            throw IllegalStateException("Failed to parse screen")
                        }

        if (isVisualQuery) {
            Log.i(TAG, "👁️ Vizuální dotaz detekován. Provádím State Capture (Screenshot)...")

            try {
                if (screenshotProvider != null) {
                    val bitmap = screenshotProvider.invoke()
                    if (bitmap != null) {
                        Log.i(TAG, "📸 Screenshot získán asynchronně. Zpracovávám pro AI...")
                        val compressedBytes =
                                com.example.llama.agent.vision.VisionSourceManager()
                                        .processAndCompressImageBlocking(
                                                bitmap,
                                                targetResolution =
                                                        settingsManager.smartVisionTargetResolution,
                                                recycleSource = true
                                        )
                        if (compressedBytes != null) {
                            Log.i(
                                    TAG,
                                    "Odesílám ${compressedBytes.size} bajtů obrazu do Inference Engine."
                            )
                            val success = inferenceEngine.processImage(compressedBytes)
                            if (success) {
                                Log.i(TAG, "Obrázek byl úspěšně zpracován pro multimodal dotaz.")
                            } else {
                                Log.e(
                                        TAG,
                                        "InferenceEngine selhal při zpracování multimodálního obrazu."
                                )
                            }
                        } else {
                            Log.w(
                                    TAG,
                                    "VisionSourceManager vrátil null, obrazovka nebyla zpracována."
                            )
                        }
                    } else {
                        Log.w(TAG, "screenshotProvider vrátil null.")
                    }
                } else {
                    Log.w(TAG, "screenshotProvider není k dispozici.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Selhání state capture při vizuálním dotazu", e)
            }

            var omniParserContext = ""
            if (settingsManager.smartVisionOmniParserEnabled) {
                Log.i(
                        TAG,
                        "🧠 OmniParser Fúze aktivní: Generuji sémantický profil obrazovky pro prompt."
                )
                omniParserContext =
                        "\n--- NATIVNÍ UI KONTEXT ---\n" +
                                "Níže je uveden sémantický strom interaktivních prvků rozpoznaných systémem na obrazovce. " +
                                "Slouží jako kompenzace obrazového halucinování a přesně definuje, jaké prvky a texty se tam nachází:\n" +
                                currentParser.captureOmniParserTree(rootNode, settingsManager) +
                                "\n--------------------------\n\n"
            }

            finalLlmPrompt =
                    "<__image__>\n$omniParserContext" +
                            "Uživatel se ptá na vizuální obsah přiloženého obrázku (např. fotografie, hra, menu, systémové rozhraní). " +
                            "Zohledni jak zachycený snímek, tak poskytnutý sémantický strom (Nativní UI kontext).\n" +
                            "Dotaz: \"$userCommand\"\n" +
                            "Odpověz přirozeně, přesně a popiš požadovanou část vizuálního/sémantického obsahu."

            onStateUpdate(AgentMode.Thinking(screenState))
        }

        tierTrace.appendLine(
                "[Tier3] parseScreen OK: ${screenState.elements.size} elements, pkg=${screenState.packageName}"
        )

        try {
            Log.d(
                    TAG,
                    "Parsed ${screenState.elements.size} UI elements from ${screenState.packageName}"
            )

            // USE: ScreenState.toMarkdown() (Debugging)
            Log.v(TAG, "Screen Context Markdown:\n${screenState.toMarkdown()}")

            // USE: AgentMode.Thinking
            onStateUpdate(AgentMode.Thinking(screenState))

            // ==================== STEP 2: Safety Checks ====================

            // Check 1: Sensitive app?
            if (!safetyGuard.isSafePackage(screenState.packageName)) {
                throw SecurityException(
                        "🚫 Cannot operate in sensitive app: ${screenState.packageName}"
                )
            }

            // Check 2: Loop detection
            if (safetyGuard.detectLoop(screenState)) {
                throw IllegalStateException("🔄 Loop detected - aborting to prevent infinite cycle")
            }

            // ==================== STEP 3: Build Prompt ====================
            // Inject installed apps context so LLM knows what's available
            val installedApps = systemController?.getInstalledAppsList()
            var prompt =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        currentParser.buildPrompt(screenState, userCommand, installedApps)
                    }

            // ==================== PERCEPTION CONTEXT INJECTION ====================
            // Pokud PerceptionEngine běží a má užitečná data z kamery (Track B),
            // injektujeme real-world kontext do promptu pro informovanější LLM rozhodování.
            // Toto NENAHRAZUJE vizuální query pipeline — pouze obohacuje standardní prompt.
            if (perceptionRepository != null) {
                val perceptionSnapshot = perceptionRepository.snapshot.value
                if (perceptionSnapshot.hasUsefulContext()) {
                    val perceptionContext = perceptionSnapshot.toReadableContext()
                    prompt =
                            "--- KONTEXT OKOLÍ (Perception Engine) ---\n" +
                                    "$perceptionContext\n" +
                                    "--- KONEC KONTEXTU OKOLÍ ---\n\n" +
                                    prompt
                    Log.i(
                            TAG,
                            "🌍 Perception context injected into prompt (${perceptionContext.length} chars)"
                    )
                    tierTrace.appendLine("[Perception] Context injected: $perceptionContext")
                }
            }
            // ==================== END PERCEPTION INJECTION ====================

            // FÚZE DAT: Pokud to byl vizuální query, nahraď konvenční prompt Fused Promptem
            if (isVisualQuery && finalLlmPrompt.isNotEmpty()) {
                prompt = finalLlmPrompt
            }

            Log.d(TAG, "Prompt built (${prompt.length} chars)")

            // Log prompt pro debugging (truncated)
            Log.v(TAG, "Prompt:\n${prompt.take(500)}...")

            // ==================== STEP 4: LLM Inference (Grammar-Constrained) ====================
            // Orchestrace: Použití Background Locku přes Oracle
            val llmResponse: String? = EngineOrchestrator.withBackgroundLock(appContext, null) { engine ->
                // FIX: Vizuální (multimodální) dotazy vyžadují přirozený výstup, nezapínej JSON gramatiku
                if (!isVisualQuery) {
                    try {
                        engine.setGrammar(AGENT_JSON_GRAMMAR)
                    } catch (e: Exception) {
                        Log.w(TAG, "Grammar constraint not available, proceeding without", e)
                    }
                } else {
                    Log.i(TAG, "Vizuální dotaz (SmartVision) - Vypínám JSON gramatiku pro přirozenou odpověď")
                    try {
                        engine.setGrammar(null)
                    } catch (_: Exception) {}
                }

                try {
                    getLLMResponse(engine, prompt)
                } finally {
                    try { engine.setGrammar(null) } catch (_: Exception) {}
                }
            }
            
            if (llmResponse == null) {
                Log.w(TAG, "Agent execution aborted: Foreground took priority or model not loaded.")
                onStateUpdate(com.example.llama.agent.models.AgentMode.Idle)
                return@runCatching "Agent execution aborted: Foreground took priority or model not loaded."
            }

            Log.d(TAG, "LLM Response: $llmResponse")

            // ==================== STEP 5: Parse JSON → AgentAction ====================
            val action: AgentAction =
                    if (isVisualQuery) {
                        // LLM vygeneroval přirozený text (ne JSON), takže rovnou vytvoříme SPEAK
                        // akci.
                        // Pokud je prázdný nebo obsahuje jen fail fallback "{}", vrátíme dlouhou
                        // chybovou hlášku,
                        // která zaručeně spustí vizuální dialog (délka > 80 znaků).
                        val responseText =
                                if (llmResponse.isBlank() ||
                                                llmResponse == "{}" ||
                                                llmResponse.trim().length < 5
                                ) {
                                    "Omlouvám se, model nevrátil smysluplnou odpověď na vizuální dotaz. Zkontrolujte logy, zda používáte správný 'mmproj' projektor pro váš multimodální GGUF model."
                                } else {
                                    llmResponse
                                }
                        AgentAction(
                                thought = responseText,
                                type = ActionType.SPEAK,
                                targetId = null,
                                inputText = null
                        )
                    } else {
                        try {
                            jsonParser.parse(llmResponse, maxElementId = screenState.elements.size)
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ Failed to parse JSON, falling back to simple thought", e)
                            // Fallback: Use response as thought/speech only
                            AgentAction(
                                            thought =
                                                    llmResponse, // Use the full response as thought
                                            type = ActionType.SPEAK, // [FIX] Use SPEAK action so
                                            // agent
                                            // speaks the text
                                            targetId = null,
                                            inputText = null
                                    )
                                    .also {
                                        Log.w(
                                                TAG,
                                                "Fallback action created due to JSON error: $llmResponse"
                                        )
                                    }
                        }
                    }

            Log.i(TAG, "Parsed action: $action")

            // [VISION] Vizualizace detekcí pokud jsou přítomny (Smart Vision overlay)
            if (action.detections != null) {
                AiAgentAccessibilityService.getInstance()?.showVisualDetections(action.detections)
            }

            // ==================== STEP 5.1: Validate Action ====================
            // Pre-execution guard: ověření že akce má všechny povinné parametry
            // (CLICK → targetId/coords, INPUT_TEXT → inputText, CALL/SMS/URL → inputText)
            action.validate().onFailure { e ->
                Log.w(TAG, "⚠️ Action validation failed: ${e.message}")
                throw IllegalArgumentException("Neplatná akce: ${e.message}")
            }

            // ==================== STEP 5.5: TOCTOU Pre-Execute Revalidation ====================
            // VUL-004 FIX: Re-fetch čerstvý rootNode a porovnej packageName.
            // Mezi LLM inference (sekundy) se mohla obrazovka změnit.
            if (screenState.isStale()) {
                Log.w(TAG, "⚠️ ScreenState is stale (>10s old), re-validating...")
            }

            val freshRoot = rootNodeProvider?.invoke()
            if (freshRoot != null) {
                val freshPackage = freshRoot.packageName?.toString() ?: "unknown"
                if (freshPackage != screenState.packageName) {
                    Log.e(
                            TAG,
                            "🚫 TOCTOU DETECTED: Package changed from '${screenState.packageName}' to '$freshPackage' during inference — ABORTING"
                    )
                    throw SecurityException(
                            "Obrazovka se změnila během rozhodování — akce zrušena pro bezpečnost"
                    )
                }
                // VUL-003 FIX: Re-check safety na aktuálním packageName
                if (!safetyGuard.isSafeToExecute(freshPackage)) {
                    throw SecurityException(
                            "🚫 Citlivá aplikace detekována při pre-execute check: $freshPackage"
                    )
                }
            }

            // ==================== STEP 6: Execute Action (Self-Healing, max 2 pokusy)
            // ====================
            // Při selhání UI elementu: re-parse obrazovky → nový LLM prompt s chybovým kontextem.
            // Max 2 pokusy — pojistka proti nekonečné smyčce.
            onStateUpdate(AgentMode.Executing(action))

            var currentAction = action
            var lastError: String? = null
            var execResult: Result<String>? = null
            // retryScreen je sledován mimo try blok pro správný lifecycle
            var activeScreen: ScreenState = screenState

            for (attempt in 1..2) {
                execResult = actionExecutor.execute(currentAction, activeScreen)
                if (execResult.isSuccess) {
                    lastError = null
                    break
                }
                lastError = execResult.exceptionOrNull()?.message ?: "Neznámá chyba"
                Log.w(TAG, "⚠️ Attempt $attempt failed: $lastError")
                contextTracker.recordStep(
                        currentAction.thought,
                        currentAction.type,
                        success = false,
                        errorMessage = lastError
                )

                // Emit do ActionResultBus pro reaktivní komponenty
                ActionResultBus.emit(
                        ActionResultBus.ActionResult(
                                actionType = currentAction.type,
                                success = false,
                                errorMessage = lastError,
                                targetId = currentAction.targetId,
                                thought = currentAction.thought
                        )
                )

                if (attempt < 2) {
                    // Re-fetch čerstvou obrazovku a zkus nový LLM call s chybovým kontextem
                    val retryRoot = rootNodeProvider?.invoke()
                    if (retryRoot != null) {
                        val retryParser = screenParserProvider()
                        val retryScreen = retryParser?.parseScreen(retryRoot)
                        if (retryScreen != null) {
                            // Recycle předchozí retryScreen pokud existuje (screenState recycle je
                            // v finally)
                            if (activeScreen !== screenState) activeScreen.recycle()
                            activeScreen = retryScreen
                            try {
                                // lastError je non-null String (přiřazeno na ř.309 v bloku po
                                // selhání)
                                val retryContext =
                                        contextTracker.getRetryContext(
                                                currentAction.type,
                                                lastError
                                        )
                                val retryPrompt =
                                        kotlinx.coroutines.withContext(
                                                kotlinx.coroutines.Dispatchers.Default
                                        ) {
                                            retryParser?.buildPrompt(
                                                    retryScreen,
                                                    userCommand,
                                                    null,
                                                    retryContext
                                            ) ?: ""
                                        }
                                Log.i(TAG, "🔄 Self-healing retry: new LLM call with error context")
                                val retryResponse = EngineOrchestrator.withBackgroundLock(appContext, null) { engine ->
                                    try {
                                        if (!isVisualQuery) engine.setGrammar(AGENT_JSON_GRAMMAR)
                                    } catch (_: Exception) {}
                                    try {
                                        getLLMResponse(engine, retryPrompt)
                                    } finally {
                                        try { engine.setGrammar(null) } catch (_: Exception) {}
                                    }
                                }
                                if (retryResponse != null) {
                                    currentAction = jsonParser.parse(retryResponse, retryScreen.elements.size)
                                } else {
                                    Log.w(TAG, "Self-healing retry aborted: Foreground took priority")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Self-healing retry LLM call failed", e)
                                // Pokud LLM retry selhal, pokračujeme s původní akcí
                            }
                        }
                    }
                }
            }

            // Recycle retryScreen pokud byl použit (screenState recycle je v finally)
            if (activeScreen !== screenState) activeScreen.recycle()

            if (execResult?.isFailure == true) {
                throw RuntimeException("Akce selhala po 2 pokusech: $lastError")
            }

            val result = execResult!!.getOrThrow()
            Log.d(TAG, "Execution result: $result")

            // Emit úspěch do ActionResultBus
            ActionResultBus.emit(
                    ActionResultBus.ActionResult(
                            actionType = currentAction.type,
                            success = true,
                            targetId = currentAction.targetId,
                            thought = currentAction.thought
                    )
            )
            contextTracker.recordStep(currentAction.thought, currentAction.type, success = true)

            // [INTEGRATION] Logování stavu trackeru po dokončení
            // Využívá hasFailedSteps() a lastStep() z CommandContextTracker
            if (contextTracker.hasFailedSteps()) {
                Log.w(
                        TAG,
                        "⚠️ Command completed with some failed steps. Last: ${contextTracker.lastStep()?.description}"
                )
            } else {
                Log.d(TAG, "✅ All steps succeeded. Last: ${contextTracker.lastStep()?.description}")
            }

            // Success! Reset safety guard history
            safetyGuard.resetHistory()

            Log.i(TAG, "✅ Command completed successfully")
            "✅ ${currentAction.thought}"
        } finally {
            // [CRITICAL RED TEAM FIX] Prevent Memory Leak
            // Recycle all AccessibilityNodeInfo instances held by ScreenState
            screenState.recycle()
            Log.v(TAG, "♻️ ScreenState recycled to prevent memory leak")
        }
    }

    /** Get LLM response (collect all tokens from Flow) */
    private suspend fun getLLMResponse(engine: InferenceEngine, prompt: String): String {
        val sb = StringBuilder()

        // InferenceEngine.sendUserPrompt() returns Flow<String>
        // Note: Using a default maxTokens of 256 for agent actions
        engine.sendUserPrompt(prompt, 256).fold(initial = "") { acc, token ->
            sb.append(token)
            acc + token
        }

        return sb.toString().trim().ifEmpty {
            Log.w(TAG, "⚠️ LLM returned EMPTY response!")
            "{}" // Return empty JSON object to fail gracefully in parser or create a fallback
            // action
        }
    }

    /** Called when screen changes (for future proactive monitoring) */
    fun onScreenChanged(rootNode: AccessibilityNodeInfo?) {
        // Zpracováváme pouze, pokud je ScreenParser aktivní (ON nebo probuzený AUTO)
        val parser = screenParserProvider() ?: return
        
        // Pro future: Proactive agent ("Vidím, že máš notifikaci...")
        // INTEGRATED: Log current package name from root node
        if (rootNode != null) {
            Log.d(TAG, "Screen changed event received: ${rootNode.packageName ?: "unknown"}")
        }
    }

    // ==================== ASSIST CONTEXT (VOICE INTERACTION) ====================
    private var lastAssistStructure: android.app.assist.AssistStructure? = null
    private var lastAssistContent: android.app.assist.AssistContent? = null
    private var lastAssistData: android.os.Bundle? = null

    /**
     * Implementováno: Příjem kontextu z AiAgentSession.onHandleAssist. Ukládá AssistStructure,
     * AssistContent a data (Bundle) nezávisle na rootInActiveWindow. Připraveno pro pokročilé Tier
     * 3 LLM promptování (GGUF vision / deep link handling).
     */
    fun updateAssistContext(
            structure: android.app.assist.AssistStructure?,
            content: android.app.assist.AssistContent?,
            data: android.os.Bundle?
    ) {
        lastAssistStructure = structure
        lastAssistContent = content
        lastAssistData = data
        Log.i(
                TAG,
                "📋 AssistContext updated: structure=${structure != null}, content=${content != null}, data elements=${data?.size() ?: 0}"
        )
    }

    /**
     * Generuje obohacený příkaz pro LLM s kontextem provedených Tier 1 kroků.
     *
     * Použití: Po partial Tier 1 match — LLM dostane jen zbytek příkazu s jasným kontextem co už
     * bylo provedeno. Zabraňuje halucinacím.
     *
     * Příklad výstupu: "=== Kontext předchozích kroků ===
     * 1. ✅ Otevři nastavení === Aktuální úkol === zapni Wi-Fi"
     */
    private fun buildContextualPrompt(remaining: String, completedSteps: List<String>): String {
        if (completedSteps.isEmpty()) return remaining
        return buildString {
            appendLine("=== Kontext předchozích kroků ===")
            completedSteps.forEachIndexed { i, step -> appendLine("${i + 1}. ✅ $step") }
            appendLine("=== Aktuální úkol ===")
            append(remaining)
        }
    }

    /**
     * Execute single Tier 1 action with appropriate ScreenState.
     *
     * INPUT_TEXT actions need real screen context (auto-find editable element). Other actions
     * (domů, zpět, screenshot...) don't need screen context.
     */
    private suspend fun executeTier1Action(
            action: AgentAction,
            onStateUpdate: (AgentMode) -> Unit
    ) {
        onStateUpdate(AgentMode.Executing(action))

        if (action.type == ActionType.INPUT_TEXT || action.type == ActionType.OPEN_URL) {
            // INPUT_TEXT a OPEN_URL potřebují screen context
            val inputRoot = rootNodeProvider?.invoke()
            val parser = screenParserProvider()
            val inputScreen = if (inputRoot != null && parser != null) parser.parseScreen(inputRoot) else null
            try {
                actionExecutor.execute(action, inputScreen ?: ScreenState.empty()).getOrThrow()
            } finally {
                inputScreen?.recycle()
            }
        } else {
            actionExecutor.execute(action, ScreenState.empty()).getOrThrow()
        }
    }

    // ==================== COMPOUND EXECUTION VIA MACRO ENGINE ====================

    /**
     * Konvertuje List<AgentAction> na Macro a spustí přes MacroEngine.
     *
     * MacroEngine automaticky:
     * - Čeká na screen change po OPEN_APP (waitForElement)
     * - Re-parsuje UI před každým krokem
     * - Hledá elementy/tlačítka na nové obrazovce
     * - Exponential backoff při polling
     *
     * Fallback: pokud MacroEngine není dostupný, provede akce sekvenčně s delay.
     */
    private suspend fun executeCompoundViaMacro(
            actions: List<AgentAction>,
            onStateUpdate: (AgentMode) -> Unit
    ): String {
        val thoughts = actions.map { it.thought }
        Log.i(TAG, "⚡ Compound command: ${actions.size} steps — ${actions.map { it.type }}")

        // Informuj UI o compound execution
        onStateUpdate(AgentMode.Executing(actions.first()))

        val engine = macroEngine
        if (engine != null) {
            // === MacroEngine path: context-aware execution ===
            val macroSteps = actions.flatMap { agentActionToMacroSteps(it) }
            val macro =
                    Macro(name = "Compound: ${thoughts.joinToString(" → ")}", steps = macroSteps)

            Log.i(TAG, "⚡ Macro created: ${macroSteps.size} steps from ${actions.size} actions")
            val success = engine.executeMacro(macro)
            if (!success) {
                Log.e(TAG, "Compound macro failed — some steps didn't complete")
            }
        } else {
            // === Fallback: sekvenční execution s delay ===
            Log.w(TAG, "MacroEngine not available — using simple sequential execution")
            for (action in actions) {
                executeTier1Action(action, onStateUpdate)
                kotlinx.coroutines.delay(800L)
            }
        }

        return "⚡ ${thoughts.joinToString(" → ")}"
    }

    /**
     * Konvertuje jeden AgentAction na seznam MacroAction kroků.
     *
     * Každá screen-changing akce (OPEN_APP, HOME, BACK) dostane navíc Wait krok, aby MacroEngine
     * čekal na novou obrazovku před dalším příkazem.
     */
    private fun agentActionToMacroSteps(action: AgentAction): List<MacroAction> {
        // === DEFERRED akce (Tier 1.5) → SmartClick / SmartInput ===
        // thought format: "[DEFERRED:smartclick:intentName]" nebo
        // "[DEFERRED:smartinput:intentName]"
        if (action.thought.startsWith("[DEFERRED:")) {
            val inputData = action.inputText ?: ""

            if (action.thought.startsWith("[DEFERRED:smartinput:")) {
                // SmartInput: inputText format = "label1|label2|||text_k_zadani"
                val parts = inputData.split("|||", limit = 2)
                val labels = parts[0].split("|").filter { it.isNotEmpty() }
                val text = if (parts.size > 1) parts[1] else ""
                val intentName =
                        action.thought.removePrefix("[DEFERRED:smartinput:").removeSuffix("]")
                Log.i(
                        TAG,
                        "🎯 Deferred → SmartInput: intent='$intentName' labels=$labels text='$text'"
                )
                return listOf(
                        MacroAction.SmartInput(
                                intent = intentName,
                                text = text,
                                fieldLabels = labels,
                                // [CONFIG] SmartInput Timeout z nastavení
                                timeoutMs = settingsManager.agentSmartClickTimeout
                        )
                )
            }

            if (action.thought.startsWith("[DEFERRED:smartclick:")) {
                // SmartClick: inputText format = "label1|label2|label3"
                val labels = inputData.split("|").filter { it.isNotEmpty() }
                val intentName =
                        action.thought.removePrefix("[DEFERRED:smartclick:").removeSuffix("]")
                Log.i(TAG, "🎯 Deferred → SmartClick: intent='$intentName' labels=$labels")
                return listOf(
                        MacroAction.SmartClick(
                                intent = intentName,
                                labels = labels,
                                // [CONFIG] SmartClick Timeout z nastavení
                                timeoutMs = settingsManager.agentSmartClickTimeout
                        )
                )
            }
        }

        return when (action.type) {
            // OPEN_APP → OpenApp + Wait na načtení
            ActionType.OPEN_APP -> {
                val pkg = action.inputText ?: "unknown"
                listOf(
                        MacroAction.OpenApp(pkg),
                        // [CONFIG] App Load Wait z nastavení
                        MacroAction.Wait(timeoutMs = settingsManager.agentAppLoadWait)
                )
            }

            // INPUT_TEXT → Input (MacroEngine re-parsuje screen a najde editable)
            ActionType.INPUT_TEXT -> {
                val text = action.inputText ?: ""
                listOf(MacroAction.Input(text = text, clearFirst = false))
            }

            // CLICK → Click (MacroEngine hledá element na živé obrazovce)
            ActionType.CLICK -> {
                listOf(
                        MacroAction.Click(
                                text = action.inputText,
                                id = action.targetId?.toString(),
                                // [CONFIG] Click Timeout (sjednoceno se SmartClick)
                                timeoutMs = settingsManager.agentSmartClickTimeout
                        )
                )
            }

            // OPEN_URL → OpenUrl přes SystemController (nikoliv OpenApp!)
            ActionType.OPEN_URL -> {
                val url = action.inputText ?: ""
                listOf(MacroAction.OpenUrl(url), MacroAction.Wait(timeoutMs = 2000L))
            }

            // Navigace (HOME, BACK, RECENT_APPS, NOTIFICATIONS)
            ActionType.HOME ->
                    listOf(
                            MacroAction.Global(AccessibilityService.GLOBAL_ACTION_HOME),
                            MacroAction.Wait(timeoutMs = 500L)
                    )
            ActionType.BACK ->
                    listOf(
                            MacroAction.Global(AccessibilityService.GLOBAL_ACTION_BACK),
                            MacroAction.Wait(timeoutMs = 500L)
                    )
            ActionType.RECENT_APPS ->
                    listOf(
                            MacroAction.Global(AccessibilityService.GLOBAL_ACTION_RECENTS),
                            MacroAction.Wait(timeoutMs = 500L)
                    )
            ActionType.NOTIFICATIONS ->
                    listOf(
                            MacroAction.Global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
                            MacroAction.Wait(timeoutMs = 500L)
                    )

            // Scroll
            ActionType.SCROLL_DOWN,
            ActionType.SCROLL_UP -> listOf(MacroAction.Scroll(action.type))

            // Swipe gesta — přímé provedení přes ActionExecutor
            ActionType.SWIPE_UP,
            ActionType.SWIPE_DOWN,
            ActionType.SWIPE_LEFT,
            ActionType.SWIPE_RIGHT -> listOf(MacroAction.DirectExecute(action))

            // Volume / Brightness — přímé provedení
            ActionType.VOLUME_UP,
            ActionType.VOLUME_DOWN,
            ActionType.BRIGHTNESS_UP,
            ActionType.BRIGHTNESS_DOWN,
            ActionType.SET_VOLUME,
            ActionType.SET_BRIGHTNESS -> listOf(MacroAction.DirectExecute(action))

            // Connectivity / VoIP — přímé provedení
            ActionType.SET_NFC,
            ActionType.SET_AIRPLANE,
            ActionType.ANSWER_CALL,
            ActionType.REJECT_CALL -> listOf(MacroAction.DirectExecute(action))

            // Ostatní akce — přímé provedení přes Global (fallback)
            else -> {
                Log.d(
                        TAG,
                        "Compound step ${action.type} — no macro mapping, using direct execution"
                )
                listOf(MacroAction.DirectExecute(action))
            }
        }
    }

    /**
     * Tier 2.5 — Perception Query Detector.
     *
     * Detekuje příkazy, které se ptají na FYZICKÉ OKOLÍ (ne na obrazovku). Tyto dotazy může
     * zodpovědět PerceptionState přímo, bez LLM.
     *
     * Inspirováno: Google Project Astra keyword triggers + Cascaded LLM deferral policies.
     *
     * @param command Uživatelský příkaz (čeština i angličtina)
     * @return true pokud je příkaz dotazem na fyzické okolí
     */
    private fun isPerceptionRelatedQuery(command: String): Boolean {
        val lowerCommand = command.lowercase()

        // Česky: dotazy na fyzické okolí
        val czPerceptionKeywords =
                listOf(
                        "co vidíš",
                        "co vidis",
                        "co je přede mnou",
                        "co je prede mnou",
                        "co je kolem",
                        "co je okolo",
                        "co máš před sebou",
                        "co mas pred sebou",
                        "kolik lidí",
                        "kolik lidi",
                        "kdo je přede",
                        "kdo je prede",
                        "popiš okolí",
                        "popis okoli",
                        "co je v místnosti",
                        "co je v mistnosti",
                        "co je na stole",
                        "co je venku",
                        "co je tady",
                        "co je tam",
                        "vidíš něco",
                        "vidis neco",
                        "rozhlédni se",
                        "rozhledni se",
                        "co snímaš",
                        "co snimas"
                )

        // Anglicky: dotazy na fyzické okolí
        val enPerceptionKeywords =
                listOf(
                        "what do you see",
                        "what's around",
                        "what is around",
                        "what's in front",
                        "what is in front",
                        "how many people",
                        "describe surroundings",
                        "look around",
                        "what's here",
                        "what is here",
                        "what's nearby",
                        "what is nearby"
                )

        return czPerceptionKeywords.any { lowerCommand.contains(it) } ||
                enPerceptionKeywords.any { lowerCommand.contains(it) }
    }

    // ==================== PERCEPTION EVENT BUS SUBSCRIBER ====================

    /**
     * Tracks whether PerceptionEngine is currently active. Updated by PerceptionLifecycleEvent from
     * EventBus. Used by Tier 2.5 routing to decide if perception data is fresh.
     */
    @Volatile private var isPerceptionEngineActive: Boolean = false

    /** Active EventBus collector job — cancelled on shutdown */
    private var eventCollectorJob: Job? = null

    /**
     * Start collecting perception events from PerceptionEventBus.
     *
     * Must be called from Service's lifecycle scope (serviceScope.launch). Implements exhaustive
     * `when` matching over all PerceptionEvent subclasses.
     *
     * Event handling strategy:
     * - VisionUpdateEvent: Silent logging (high frequency, ~1-2 FPS)
     * - SceneChangeEvent: Log + future TTS notification ("Nová scéna: 3 nové objekty")
     * - AudioDetectionEvent: Priority-based — CRITICAL → immediate TTS warning
     * - ScreenContextEvent: Silent logging (reserved for future ScreenParser bridge)
     * - GazeStateEvent: Silent logging (reserved for future attention tracking)
     * - PerceptionLifecycleEvent: State tracking (isPerceptionEngineActive)
     *
     * Research Foundation:
     * - Compound AI Systems (2024): Event-driven decoupling between perception → decision
     * - Google Project Astra (I/O 2024): Perception → low-latency response pattern
     * - SharedFlow best practices: replay=0, lifecycle-aware scope, exhaustive when
     *
     * @param scope Coroutine scope tied to Service lifecycle (serviceScope)
     */
    fun startPerceptionEventCollector(scope: CoroutineScope) {
        // Cancel previous collector if any (idempotent restart)
        eventCollectorJob?.cancel()

        eventCollectorJob =
                scope.launch {
                    Log.i(TAG, "📡 Perception EventBus collector STARTED")

                    PerceptionEventBus.events.collect { event -> onPerceptionEvent(event) }
                }
    }

    /** Stop the event collector (called on Service shutdown). */
    fun stopPerceptionEventCollector() {
        eventCollectorJob?.cancel()
        eventCollectorJob = null
        isPerceptionEngineActive = false
        Log.i(TAG, "📡 Perception EventBus collector STOPPED")
    }

    /**
     * Central event handler — exhaustive `when` over all PerceptionEvent subclasses.
     *
     * Design: Each event type has a dedicated handler block. Adding a new PerceptionEvent subclass
     * → Kotlin compiler forces adding handler here.
     *
     * Thread: Runs on Dispatchers.Default (from engineScope/serviceScope).
     */
    private fun onPerceptionEvent(event: PerceptionEvent) {
        when (event) {
            is com.example.llama.agent.perception.ReloadNightModeModelEvent -> {
                // Ignore in AgentCoordinator, Handled by PerceptionEngine
            }
            is com.example.llama.agent.perception.AudioLevelEvent -> {
                // Silent: Zpracovává Studio
            }
            // ---- Camera Preview Event (Zpracovává přímo FloatingResultPopup) ----
            is com.example.llama.agent.perception.CameraPreviewEvent -> {
                // Silent: Koordinátor ignoruje raw bitmapu, aby nebrzdil AI pipeline.
            }
            is com.example.llama.agent.perception.NightModePreviewEvent -> {
                // Silent: Zpracovává Studio
            }
            // ---- Vision: High-frequency snapshot (1-2 FPS) ----
            is VisionUpdateEvent -> {
                // Silent: Log only at VERBOSE level to avoid log spam
                Log.v(
                        TAG,
                        "👁️ VisionUpdate: ${event.objectCount} objects, " +
                                "text=${event.hasText}, faces=${event.faceCount}"
                )
            }

            // ---- Vision: Scene change (significant delta) ----
            is SceneChangeEvent -> {
                Log.i(
                        TAG,
                        "🔄 SceneChange: ${event.previousObjectCount}→${event.currentObjectCount} " +
                                "objects, new=[${event.newEntities.joinToString()}]"
                )
                // Proaktivní TTS: oznamuj jen při zapnutém TTS + významné změny (min N nových
                // objektů)
                val minEntities = settingsManager.perceptionSceneMinNewEntities
                if (settingsManager.perceptionTtsSceneChange &&
                                event.newEntities.size >= minEntities &&
                                ttsCallback != null
                ) {
                    val entityList = event.newEntities.take(5).joinToString(", ")
                    ttsCallback.invoke("Nové objekty v okolí: $entityList")
                }
            }

            // ---- Audio: Sound event (YAMNet 521-class) ----
            is AudioDetectionEvent -> {
                Log.i(
                        TAG,
                        "🔊 AudioDetection: '${event.label}' (conf=${event.confidence}, " +
                                "priority=${event.priority}, speech=${event.isSpeech})"
                )
                // CRITICAL priority → TTS varování (pouze pokud zapnuto v Settings)
                if (settingsManager.perceptionTtsCriticalAudio &&
                                event.priority == EventPriority.CRITICAL &&
                                ttsCallback != null
                ) {
                    val czLabel = AudioLabelTranslations.translate(event.label, settingsManager.yamnetActiveLang)
                    Log.w(
                            TAG,
                            "🚨 CRITICAL AUDIO EVENT: ${event.label} ($czLabel) — triggering alert"
                    )
                    ttsCallback.invoke("POZOR! Detekován zvuk: $czLabel")
                }
            }

            // ---- Screen: UI context change (future ScreenParser bridge) ----
            is ScreenContextEvent -> {
                Log.v(
                        TAG,
                        "📱 ScreenContext: package=${event.appPackage}, " +
                                "elements=${event.elementCount}, text=${event.visibleText.take(50)}"
                )
            }

            // ---- Gaze: User attention state (future IrisMotion bridge) ----
            is GazeStateEvent -> {
                Log.v(
                        TAG,
                        "👀 GazeState: lookingAtScreen=${event.isLookingAtScreen}, " +
                                "fixation=${event.fixationDurationMs}ms"
                )
            }

            // ---- Lifecycle: Engine state tracking ----
            is PerceptionLifecycleEvent -> {
                isPerceptionEngineActive =
                        when (event.state) {
                            EngineState.STARTED,
                            EngineState.THERMAL_RESUMED,
                            EngineState.SCREEN_ON_RESUMED -> true
                            EngineState.STOPPED,
                            EngineState.THERMAL_PAUSED,
                            EngineState.SCREEN_OFF_PAUSED,
                            EngineState.ERROR -> false
                        }
                Log.i(
                        TAG,
                        "⚙️ PerceptionLifecycle: ${event.state} (reason=${event.reason}), " +
                                "engineActive=$isPerceptionEngineActive"
                )
            }

            // ---- Transcript: whisper.cpp ASR segment (Tier 2.5) ----
            is TranscriptEvent -> {
                val status = if (event.isFinal) "FINAL" else "partial"
                Log.i(
                        TAG,
                        "📝 Transcript [$status, lang=${event.language}]: " + event.text.take(80)
                )
                // Ukládání textu se děje automaticky přímo přes PerceptionEngine
                // Pokud bychom chtěli z TranscriptAgenta tlačit přímo sem, tak:
                // perceptionRepository?.appendTranscript(event.text)
            }

            // ---- Rolling Summary (Tier 2.5 komprese) ----
            is RequestNLPCompressionEvent -> {
                Log.i(
                        TAG,
                        "🧠 Přijat požadavek na Rolling Summary. Text len: ${event.rawText.length}"
                )
                coordinatorScope.launch {
                    val result = nlpSummaryWorker.summarize(event.rawText)
                    when (result) {
                        is NlpSummaryWorker.SummaryResult.Success -> {
                            Log.i(TAG, "✅ Komprese úspěšná (Tier 2.5): ${result.wordCount} slov.")
                            val ts =
                                    TimestampedSummary(
                                            text = result.summaryText,
                                            startTimeMs = event.timestampMs, // zjednodušeno
                                            endTimeMs = System.currentTimeMillis(),
                                            wordCount = result.wordCount
                                    )
                            perceptionRepository?.addCompressedSummary(ts)
                        }
                        is NlpSummaryWorker.SummaryResult.NEEDS_LLM -> {
                            Log.w(
                                    TAG,
                                    "⚠️ Tier 2.5 nestačí, eskalační volání na Tier 3 LLM! (Zatím neimplementováno)"
                            )
                            // TODO: Zavolat Tier 2.9  pokud nebude stačit zavolat InferenceEngine
                            // pro plnohodnotné zamyšlení The Heavy LLM
                        }
                        is NlpSummaryWorker.SummaryResult.Error -> {
                            Log.e(TAG, "❌ Komprese selhala.")
                        }
                    }
                }
            }

            // ---- DRM / FLAG_SECURE detekce (Slepé/Hluché místo) ----
            is SystemDrmEvent -> {
                Log.w(TAG, "🔒 DRM Restriction: ${event.reason}")
                // Informovat UI (AgentNotifier) o DRM blokaci — budoucí TTS varování pro
                // handicapované
            }

            // ---- UI Action Events (Popup Window Management) ----
            is UiActionEvent -> {
                coordinatorScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    when (event.action) {
                        UiAction.SHOW_POPUP -> floatingPopup.show()
                        UiAction.HIDE_POPUP -> floatingPopup.hide()
                        UiAction.TOGGLE_POPUP -> floatingPopup.toggle()
                        UiAction.TOGGLE_WAKEUP_HACK -> com.example.llama.agent.perception.SystemAudioInterceptor.toggleWakeupHackVolume()
                        UiAction.TRIGGER_SMART_SELECT -> smartSelectOverlay.show()
                        UiAction.HIDE_CONTEXT_MENU -> {
                            contextMenuToolbar.hide()
                            smartSelectOverlay.hide()
                        }
                        UiAction.TOGGLE_FULLSCREEN_BOXES -> {
                            // Zpracovává SmartVisionOverlayManager
                        }
                        UiAction.OBJECT_CLICKED -> {
                            // Zpracovává SmartVisionOverlayManager nebo někdo jiný
                        }
                        UiAction.TOGGLE_SCREEN_DETECTION -> {
                            com.example.llama.agent.perception.MediaProjectionPermissionActivity.start(appContext) { resultCode, data ->
                                if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                                    val serviceIntent = android.content.Intent(
                                        appContext,
                                        com.example.llama.agent.perception.TranscriptForegroundService::class.java
                                    ).apply {
                                        action = com.example.llama.agent.perception.TranscriptForegroundService.ACTION_START_SYSTEM_VIDEO
                                    }
                                    try {
                                        androidx.core.content.ContextCompat.startForegroundService(appContext, serviceIntent)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "❌ Nelze spustit TranscriptForegroundService pro VIDEO: ${e.message}", e)
                                        settingsManager.perceptionCameraSource = 0
                                    }
                                } else {
                                    Log.w(TAG, "MediaProjection pro video zamítnuto uživatelem.")
                                    settingsManager.perceptionCameraSource = 0
                                }
                            }
                        }
                        UiAction.TRIGGER_SUMMARIZE,
                        UiAction.TRIGGER_TRANSLATE,
                        UiAction.TRIGGER_PROOFREAD,
                        UiAction.TRIGGER_SENTIMENT,
                        UiAction.TRIGGER_REWRITE,
                        UiAction.TRIGGER_SMART_REPLY,
                        UiAction.TRIGGER_NER,
                        UiAction.TRIGGER_QA,
                        UiAction.TRIGGER_NLI,
                        UiAction.TRIGGER_MLM -> {
                            floatingPopup.show()
                            coordinatorScope.launch(kotlinx.coroutines.Dispatchers.Default) inner@{
                                try {
                                    val rootNode = rootNodeProvider?.invoke()
                                    val parser = screenParserProvider() ?: ScreenParser(settingsManager)
                                    val screenText = parser.parseSemanticContent(rootNode) ?: ""

                                    if (screenText.isBlank()) {
                                        PerceptionEventBus.emit(com.example.llama.agent.perception.NlpResultEvent("💡 Chyba", "Nevidím na obrazovce žádný rozpoznatelný text."))
                                        return@inner
                                    }

                                    val (actionType, title) = when (event.action) {
                                        UiAction.TRIGGER_SUMMARIZE    -> Pair(ActionType.NLP_SUMMARIZE,   "🧠 AI Shrnutí (Screen Context)")
                                        UiAction.TRIGGER_TRANSLATE    -> Pair(ActionType.NLP_TRANSLATE,   "🌐 AI Překlad (Do nastaveného jazyka)")
                                        UiAction.TRIGGER_PROOFREAD    -> Pair(ActionType.NLP_PROOFREAD,   "✏️ AI Korektura/Oprava")
                                        UiAction.TRIGGER_SENTIMENT    -> Pair(ActionType.NLP_SENTIMENT,   "💜 Sentiment Analýza (Nálada textu)")
                                        UiAction.TRIGGER_REWRITE      -> Pair(ActionType.NLP_REWRITE,     "✍️ AI Přepis (Professional)")
                                        UiAction.TRIGGER_SMART_REPLY  -> Pair(ActionType.NLP_SMART_REPLY, "💬 AI Návrhy odpovědí")
                                        UiAction.TRIGGER_NER          -> Pair(ActionType.NLP_NER,         "🏷️ Extrakce entit z obrazovky")
                                        UiAction.TRIGGER_QA           -> Pair(ActionType.NLP_QA,          "🔍 QA — Hledání odpovědi")
                                        UiAction.TRIGGER_NLI          -> Pair(ActionType.NLP_NLI,         "⚖️ Logická kontrola (NLI)")
                                        UiAction.TRIGGER_MLM          -> Pair(ActionType.NLP_MLM,         "🎭 Doplnění [MASK] tokenu")
                                        else -> return@inner
                                    }

                                    PerceptionEventBus.emit(com.example.llama.agent.perception.NlpResultEvent(title, "⏳ Zpracovávám text... (${screenText.length} znaků)"))

                                    // Sentiment: speciální cesta přes BertSentimentWorker
                                    if (event.action == UiAction.TRIGGER_SENTIMENT) {
                                        val worker = com.example.llama.agent.perception.BertSentimentWorker(appContext)
                                        val sentimentText = worker.classifySentiment(screenText)
                                        worker.close()
                                        PerceptionEventBus.emit(com.example.llama.agent.perception.NlpResultEvent(title, sentimentText))
                                        return@inner
                                    }

                                    val result = nlpActionHandler.execute(actionType, screenText)
                                    PerceptionEventBus.emit(com.example.llama.agent.perception.NlpResultEvent(title, result))
                                } catch (e: Exception) {
                                    Log.e(TAG, "Ad-Hoc NLP Akce selhala", e)
                                    PerceptionEventBus.emit(com.example.llama.agent.perception.NlpResultEvent("❌ Selhání", "Akce se nepovedla: ${e.message}"))
                                }
                            }
                        }
                    }
                }
            }

            // ---- Smart Select Complete Event ----
            is com.example.llama.agent.perception.SmartSelectCompleteEvent -> {
                Log.i(TAG, "🎯 Smart Select zachycen! Oříznuto bounds: ${event.selectedRect}. Zobrazuji SubMenu.")
                coordinatorScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    contextMenuToolbar.show(event.selectedRect, event.extractedText, event.croppedBitmap)
                }
            }

            // ---- Sub-Menu Action Event (Contextual Task z Výběru) ----
            is com.example.llama.agent.perception.SubMenuActionEvent -> {
                Log.i(TAG, "🚀 Zpracovávám kontextovou akci: ${event.action}")
                coordinatorScope.launch(kotlinx.coroutines.Dispatchers.Main) { floatingPopup.show() }
                
                coordinatorScope.launch(kotlinx.coroutines.Dispatchers.Default) inner@{
                    val targetText = event.sourceText
                    
                    if (targetText.isBlank() && event.action != com.example.llama.agent.perception.SubMenuAction.VISION_ANALYZE) {
                        PerceptionEventBus.emit(com.example.llama.agent.perception.NlpResultEvent("💡 Upozornění", "Nebyl vybrán žádný nativní text v dané oblasti."))
                        event.croppedBitmap?.recycle() // Prevence OutOfMemoryError
                        PerceptionEventBus.emit(com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.HIDE_CONTEXT_MENU))
                        return@inner
                    }
                    
                    val (actionType, title) = when (event.action) {
                        com.example.llama.agent.perception.SubMenuAction.SUMMARIZE         -> Pair(ActionType.NLP_SUMMARIZE,   "🧠 AI Shrnutí (Oblast)")
                        com.example.llama.agent.perception.SubMenuAction.TRANSLATE         -> Pair(ActionType.NLP_TRANSLATE,   "🌐 AI Překlad (Oblast)")
                        com.example.llama.agent.perception.SubMenuAction.PROOFREAD         -> Pair(ActionType.NLP_PROOFREAD,   "✏️ AI Korektura (Oblast)")
                        com.example.llama.agent.perception.SubMenuAction.REWRITE           -> Pair(ActionType.NLP_REWRITE,     "✍️ AI Přepis (Professional)")
                        com.example.llama.agent.perception.SubMenuAction.SMART_REPLY       -> Pair(ActionType.NLP_SMART_REPLY, "💬 AI Návrhy odpovědí")
                        com.example.llama.agent.perception.SubMenuAction.EXTRACT_ENTITIES  -> Pair(ActionType.NLP_NER,         "🏷️ Entity z textu")
                        com.example.llama.agent.perception.SubMenuAction.QA_SEARCH         -> Pair(ActionType.NLP_QA,          "🔍 Hledání odpovědi (QA)")
                        com.example.llama.agent.perception.SubMenuAction.NLI_CHECK         -> Pair(ActionType.NLP_NLI,         "⚖️ Logická kontrola (NLI)")
                        com.example.llama.agent.perception.SubMenuAction.MLM_FILL          -> Pair(ActionType.NLP_MLM,         "🎭 Maskované doplňování")
                        com.example.llama.agent.perception.SubMenuAction.SENTIMENT -> {
                            val worker = com.example.llama.agent.perception.BertSentimentWorker(appContext)
                            val sentimentText = worker.classifySentiment(targetText)
                            worker.close()
                            PerceptionEventBus.emit(com.example.llama.agent.perception.NlpResultEvent("💜 Sentiment Analýza", sentimentText))
                            event.croppedBitmap?.recycle()
                            PerceptionEventBus.emit(com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.HIDE_CONTEXT_MENU))
                            return@inner
                        }
                        com.example.llama.agent.perception.SubMenuAction.VISION_ANALYZE -> {
                            if (event.croppedBitmap != null) {
                                PerceptionEventBus.emit(com.example.llama.agent.perception.NlpResultEvent("👁️ ML Analýza Obrazu", "Velikost výřezu: ${event.croppedBitmap.width}x${event.croppedBitmap.height}px\nModul pro detekci obrazu z bitmapy zatím zablokován."))
                            } else {
                                PerceptionEventBus.emit(com.example.llama.agent.perception.NlpResultEvent("❌ ML Analýza Obrazu", "Nepodařilo se zachytit výřez obazovky pro analýzu."))
                            }
                            event.croppedBitmap?.recycle()
                            PerceptionEventBus.emit(com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.HIDE_CONTEXT_MENU))
                            return@inner
                        }
                    }
                    
                    try {
                        PerceptionEventBus.emit(com.example.llama.agent.perception.NlpResultEvent(title, "Zpracovávám text z výběru... (Délka: ${targetText.length} znaků)"))
                        val result = nlpActionHandler.execute(actionType, targetText)
                        PerceptionEventBus.emit(com.example.llama.agent.perception.NlpResultEvent(title, result))
                    } catch (e: Exception) {
                        Log.e(TAG, "Chyba při provádění výběrové akce", e)
                        PerceptionEventBus.emit(com.example.llama.agent.perception.NlpResultEvent("❌ Selhání", "Chyba zpracování: ${e.message}"))
                    } finally {
                        event.croppedBitmap?.recycle() 
                        // Jakmile dokončíme NLP akci, můžeme původní smart select box rovnou schovat
                        PerceptionEventBus.emit(UiActionEvent(UiAction.HIDE_CONTEXT_MENU))
                    }
                }
            }

            // ---- NLP Result Event (zobrazení ve FloatingResultPopup) ----
            is com.example.llama.agent.perception.NlpResultEvent -> {
                // Zpracováno přímo ve FloatingResultPopup přes PerceptionEventBus subscription.
                // AgentCoordinator ho jen loguje.
                Log.d(TAG, "📋 NlpResult: ${event.title}")
            }

            // ---- Studio Busy Event (AudioStudio exkluzivita) ----
            is com.example.llama.agent.perception.StudioBusyEvent -> {
                Log.d(TAG, "🎙️ StudioBusyEvent: ${event.task.label}")
            }

            // ---- Overlay Visual Events ----
            is com.example.llama.agent.perception.SemanticMaskEvent -> {
                // Zpracovává SmartVisionOverlayManager, koordinátor ignoruje
            }
            is com.example.llama.agent.perception.PoseUpdateEvent -> {
                // Zpracovává SmartVisionOverlayManager, koordinátor ignoruje
            }
            is com.example.llama.agent.perception.OpticalLockEvent -> {
                // Zpracovává PerceptionEngine, koordinátor ignoruje
            }
        }
    }
}
