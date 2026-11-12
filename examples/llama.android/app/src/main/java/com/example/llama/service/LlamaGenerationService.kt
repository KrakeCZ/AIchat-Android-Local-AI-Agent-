package com.example.llama.service


import android.app.Service

import android.content.Intent

import android.os.Binder

import android.os.IBinder
import android.os.PowerManager
import android.util.Log

import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded


import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.example.llama.ChatHistoryManager
import com.example.llama.Message
import com.example.llama.SettingsManager

// Definice stavů služby pro reaktivní komunikaci s UI
sealed class GenerationState {
    object Idle : GenerationState()
    object Standby : GenerationState()
    data class Generating(
        val token: String,
        val tokensPerSecond: Double,
        val partialResponse: String,
        val tokenCount: Int,      // PŘIDÁNO: Pro realtime statistiku v UI
        val elapsedTime: Double   // PŘIDÁNO: Pro realtime statistiku v UI
    ) : GenerationState()
    data class Complete(val fullText: String) : GenerationState()
    data class Error(val message: String) : GenerationState()
}

class LlamaGenerationService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var generationJob: Job? = null

    private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generationState: StateFlow<GenerationState> = _generationState.asStateFlow()

    private var wakeLock: PowerManager.WakeLock? = null
    private var engine: InferenceEngine? = null
    private lateinit var historyManager: ChatHistoryManager
    private lateinit var settingsManager: com.example.llama.SettingsManager
    
    // --- NOVÉ: Notification Helper ---
    private lateinit var notificationHelper: LlamaNotificationHelper

    companion object {
        private val TAG = LlamaGenerationService::class.java.simpleName
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created - RAM Anchor Active")
        
        // 1. Init Helper (vytvoří channel)
        notificationHelper = LlamaNotificationHelper(this)
        
        // Odstraněno: engine = com.example.llama.agent.core.EngineProvider.get(applicationContext)
        // Llama se načte líně až při prvním dotazu (Lazy Load)
        historyManager = ChatHistoryManager(applicationContext)
        settingsManager = SettingsManager(applicationContext)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Llama::ComputeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == LlamaNotificationHelper.ACTION_RELEASE_RAM) {
            Log.i(TAG, "User requested RAM release via notification.")
            // ZMĚNA: Neničit engine explicitně! 
            // "Uvolnit z RAM" znamená pouze "Přestat být Foreground Service".
            // Pokud je aplikace připojená (Bound), služba běží dál a model zůstává načtený.
            // Pokud není připojená, služba se ukončí (stopSelf) a systém proces časem zabije.
            _generationState.value = GenerationState.Idle
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        } else if (intent?.action == LlamaNotificationHelper.ACTION_START_RESIDENT) {
            Log.d(TAG, "Starting as Resident Service (Foreground Anchor)")
            // Povýšení na Foreground Service (RAM Anchor)
            refreshNotification()
            return START_STICKY
        } else if (intent?.action == LlamaNotificationHelper.ACTION_STOP_RESIDENT) {
            Log.d(TAG, "Stopping Resident Service (dropping Foreground, keeping Bound)")
            // Zastavit Foreground režim â€” služba zůstane živá pro binding,
            // ale ztratí foreground prioritu a notifikaci.
            stopForeground(STOP_FOREGROUND_REMOVE)
            return START_NOT_STICKY
        } else if (intent?.action == LlamaNotificationHelper.ACTION_DISABLE_AGENT_TTS) {
             Log.d(TAG, "Action: Disable Agent TTS")
             settingsManager.isAgentTtsEnabled = false
             refreshNotification()
             return START_STICKY
        } else if (intent?.action == LlamaNotificationHelper.ACTION_DISABLE_AGENT_STT) {
             Log.d(TAG, "Action: Disable Agent STT")
             settingsManager.isAgentSttEnabled = false
             refreshNotification()
             return START_STICKY
        } else if (intent?.action == LlamaNotificationHelper.ACTION_DISABLE_AGENT) {
             Log.d(TAG, "Action: Disable Agent")
             settingsManager.isAgentEnabled = false
             refreshNotification()
             return START_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        Log.d(TAG, "Service Destroyed - Cleanup")
        serviceScope.cancel()
        releaseWakeLock()
        // FIX: Nevolat engine.destroy() zde! 
        // Pokud služba končí jen kvůli odpojení (unbind) při rotaci (Background OFF),
        // zničili bychom engine i pro novou Activity. 
        // Engine se uvolní buď explicitně (ACTION_RELEASE_RAM) nebo s procesem.
        super.onDestroy()
    }

    // --- Veřejné API pro Activity ---

    private fun getOrLoadEngine(): InferenceEngine {
        if (engine == null) {
            Log.i(TAG, "Lazy loading InferenceEngine (Tier 3) upon first request...")
            engine = com.example.llama.agent.core.EngineProvider.get(applicationContext)
        }
        return engine!!
    }

    private var lastSessionId: String? = null

    fun startGeneration(
        sessionId: String,
        currentHistory: List<Message>,
        prompt: String,
        maxTokens: Int,
        assistantMessageId: String,
        systemPrompt: String? = null,
        resetContext: Boolean = false
    ) {
        if (generationJob?.isActive == true) return

        // Lazy load enginu až v momentě, kdy ho agent/user opravdu potřebuje (Tier 3)
        val currentEngine = getOrLoadEngine()

        // GUARD: Ověření, zda je engine připraven
        if (!currentEngine.state.value.isModelLoaded) {
            Log.e(TAG, "Attempt to generate with unloaded/destroyed engine.")
            _generationState.value = GenerationState.Error("Model není načten (RAM byla uvolněna). Načtěte model znovu.")
            refreshNotification()
            return
        }

        acquireWakeLock()
        
        // ZMĚNA: Použití helperu
        // ZMĚNA: Použití helperu
        refreshNotification()

        generationJob = serviceScope.launch {
            // SESSION TRACKING: System prompt dodáván přes prepend v delegateGenerationToService().
            if (lastSessionId != sessionId) {
                 Log.i(TAG, "New session detected ($sessionId). Previous: $lastSessionId")
                 lastSessionId = sessionId
            }

            val processingMessages = currentHistory.toMutableList()

            com.example.llama.agent.core.EngineOrchestrator.withForegroundLock(
                context = applicationContext,
                owner = com.example.llama.agent.core.EngineOwner.FOREGROUND_DICTAPHONE,
                config = null // Fallback na to, co vybral UI. Dictaphone může v budoucnu předávat overrides.
            ) { engine ->
                try {
                    if (resetContext) {
                        engine.resetContext()
                    }
                    if (systemPrompt != null) {
                        engine.setSystemPrompt(systemPrompt)
                    }

                    val toolsMode = settingsManager.toolsMode
                    when (toolsMode) {
                        SettingsManager.ToolsMode.OFF -> {
                            engine.setToolPolicy(2)
                            engine.setToolDefinitions("")
                        }
                        SettingsManager.ToolsMode.ON -> {
                            engine.setToolPolicy(1)
                            engine.setToolDefinitions(com.example.llama.agent.tools.web.WebToolsDispatcher.getToolsJson())
                        }
                        SettingsManager.ToolsMode.AUTO -> {
                            engine.setToolPolicy(0)
                            engine.setToolDefinitions(com.example.llama.agent.tools.web.WebToolsDispatcher.getToolsJson())
                        }
                    }

                    val fullResponseBuilder = StringBuilder()
                    // BUFFER: Pro Service Throttling
                    val tokenBuffer = StringBuilder()
                    
                    // Načtení nastavení (sampling)
                    val sc = settingsManager.loadSamplingConfig()
                    engine.configureSampling(
                        sc.temperature, sc.topK, sc.topP, sc.minP,
                        sc.xtcProbability, sc.xtcThreshold, sc.typicalP,
                        sc.dynamicTempRange, sc.dynamicTempExponent,
                        sc.penaltyRepeat, sc.penaltyFreq, sc.penaltyPresent, sc.penaltyLastN,
                        sc.mirostat, sc.mirostatTau, sc.mirostatEta,
                        sc.samplersSequence, sc.useBackendSampling
                    )
                    // SOTA 2026: Reasoning / Thinking profile configuration
                    val tp = settingsManager.thinkingProfile
                    engine.configureThinking(tp.isEnabled, tp.maxTokens, tp.effort)
                    
                    var startTime = 0L
                    var tokenCount = 0
                    var lastUpdateTime = 0L

                    engine.sendUserPrompt(prompt, maxTokens)
                        .onCompletion { cause ->
                            // Handle Cancellation (Stop button) OR Success same way for data flushing
                        val isCancelled = cause is CancellationException
                        if (cause != null && !isCancelled) {
                             _generationState.value = GenerationState.Error(cause.message ?: "Unknown error")
                             refreshNotification()
                        } else {
                             // Success OR Cancelled -> Flush buffer
                             if (tokenBuffer.isNotEmpty()) {
                                  fullResponseBuilder.append(tokenBuffer)
                                  tokenBuffer.clear()
                             }
                             
                             val finalResponse = fullResponseBuilder.toString()
                             _generationState.value = GenerationState.Complete(finalResponse)
                             
                             if (finalResponse.isNotEmpty()) {
                                 val assistantMsg = Message(id = assistantMessageId, contentBuilder = StringBuilder(finalResponse), isUser = false)
                                 processingMessages.add(assistantMsg)
                                 historyManager.saveSession(sessionId, processingMessages)
                             }
                             _generationState.value = GenerationState.Standby
                             refreshNotification()
                        }
                    }
                    .collect { token ->
                        if (!isActive) return@collect
                        if (startTime == 0L) startTime = System.currentTimeMillis()
                        tokenCount++
                        
                        tokenBuffer.append(token)
                        
                        val currentTime = System.currentTimeMillis()
                        // Batching: Aktualizuj UI stav maximálně jednou za 100ms
                        if (currentTime - lastUpdateTime >= 60) {
                            val chunk = tokenBuffer.toString()
                            tokenBuffer.clear()
                            
                            fullResponseBuilder.append(chunk)

                            val elapsedTime = (currentTime - startTime) / 1000.0
                            val speed = if (elapsedTime > 0) tokenCount / elapsedTime else 0.0

                            _generationState.value = GenerationState.Generating(
                                token = chunk, // Posíláme naakumulovaný kus textu
                                tokensPerSecond = speed,
                                partialResponse = fullResponseBuilder.toString(),
                                tokenCount = tokenCount,
                                elapsedTime = elapsedTime
                            )
                            lastUpdateTime = currentTime
                        }
                    }

            } catch (e: CancellationException) {
                // Normální operace: uživatel zastavil generování (Stop/Nový chat).
                // Logujeme jako INFO pro debugging, ale NE jako Error stav.
                Log.i(TAG, "Generation cancelled: ${e.message}")
                throw e
            } catch (e: Exception) {
                // Skutečná chyba â€“ logujeme a zobrazíme uživateli.
                Log.e(TAG, "Generation flow failed", e)
                _generationState.value = GenerationState.Error(e.message ?: "Critical failure")
                refreshNotification() // Zpět do Standby (nebo error stav)
            } finally {
                releaseWakeLock()
            }
            } // Close withForegroundLock
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        _generationState.value = GenerationState.Standby
        refreshNotification()
    }

    // --- Infrastruktura ---

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    inner class LocalBinder : Binder() {
        fun getService(): LlamaGenerationService = this@LlamaGenerationService
    }

    private fun refreshNotification() {
        val isGenerating = _generationState.value is GenerationState.Generating
        val isAgentEnabled = settingsManager.isAgentEnabled
        
        if (isAgentEnabled) {
            val state = AgentNotificationState(
                isAgentActive = true,
                isListening = false, // TODO: Connect to Listen Service State if avail
                isTtsEnabled = settingsManager.isAgentTtsEnabled,
                isSttEnabled = settingsManager.isAgentSttEnabled
            )
            notificationHelper.updateNotificationWithAgent(isGenerating, state)
        } else {
            notificationHelper.updateNotification(isGenerating)
        }
    }
}


