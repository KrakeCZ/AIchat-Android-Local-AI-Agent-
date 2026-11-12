package com.example.llama.managers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

import com.example.llama.Message
import com.example.llama.service.GenerationState
import com.example.llama.service.LlamaGenerationService
import com.example.llama.service.LlamaNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Klient pro komunikaci s LlamaGenerationService.
 * Zapouzdřuje ServiceConnection, binding a state mirroring.
 */
class LlamaServiceManager(private val context: Context, private val scope: CoroutineScope) {

    private var llamaService: LlamaGenerationService? = null
    private var isBound = false
    private var collectorJob: kotlinx.coroutines.Job? = null

    // StateFlow, který UI sleduje. Defaultně Idle.
    // Jakmile se připojíme, začneme zrcadlit stav ze služby.
    private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generationState: StateFlow<GenerationState> = _generationState.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as LlamaGenerationService.LocalBinder
            llamaService = binder.getService()
            isBound = true
            Log.d(TAG, "Service Connected via Manager")

            // Začneme odebírat stav ze služby a posílat ho do našeho Flow
            collectorJob?.cancel()
            collectorJob = scope.launch(Dispatchers.Main) {
                llamaService?.generationState?.collect { state ->
                    _generationState.value = state
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            llamaService = null
            isBound = false
            Log.d(TAG, "Service Disconnected")
            _generationState.value = GenerationState.Idle
        }
    }

    /**
     * Nastartuje službu (Intent) a naváže spojení (Bind).
     * Volat v onCreate Activity.
     * @param asResident Pokud true, služba poběží jako Foreground (RAM Anchor). Pokud false, běží jen dokud je Activity připojená.
     *
     * CRITICAL FIX (2026-02-17):
     * Na Android O+ je NUTNÉ použít startForegroundService() pro správné povýšení
     * na foreground službu. startService() nestačí â€” Samsung OneUI (Device Care)
     * klasifikuje službu s nízkou prioritou a může ji zabít.
     * Po odstranění HyperCallService (InCallService) se ztratila implicitní vysoká
     * priorita procesu, která dříve držela celou aplikaci v rezidenční paměti.
     *
     * BIND_ABOVE_CLIENT zajistí, že systém považuje službu za důležitější než Activity â€”
     * Service zůstane živá i po swipe-away Activity z recentů.
     */
    fun connect(asResident: Boolean) {
        val intent = Intent(context, LlamaGenerationService::class.java)
        
        if (asResident) {
            intent.action = LlamaNotificationHelper.ACTION_START_RESIDENT
            // Start Foreground Service (aby běžela i bez bindingu - RAM Anchor)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        // Bind Service (pro komunikaci) - vždy potřeba
        // BIND_ABOVE_CLIENT: Pouze v resident režimu â€” zvýší prioritu služby nad Activity.
        // Bez resident režimu: standardní BIND_AUTO_CREATE (služba žije jen dokud je Activity připojená).
        val bindFlags = if (asResident) {
            Context.BIND_AUTO_CREATE or Context.BIND_ABOVE_CLIENT
        } else {
            Context.BIND_AUTO_CREATE
        }
        context.bindService(intent, serviceConnection, bindFlags)
    }

    /**
     * Odpojí se od služby. Volat v onDestroy Activity.
     * Poznámka: Nezastaví službu (stopService), jen odpojí UI. Služba běží dál (RAM Anchor).
     */
    fun disconnect() {
        if (isBound) {
            collectorJob?.cancel()
            collectorJob = null
            context.unbindService(serviceConnection)
            isBound = false
        }
    }
    
    /**
     * Úplné zastavení služby (Kill).
     */
    fun stopService() {
        disconnect()
        context.stopService(Intent(context, LlamaGenerationService::class.java))
    }

    // --- Proxy Metody ---

    fun startGeneration(
        sessionId: String,
        currentHistory: List<Message>,
        prompt: String,
        maxTokens: Int,
        assistantMessageId: String,
        systemPrompt: String? = null,
        resetContext: Boolean = false
    ) {
        if (llamaService == null) {
            Log.w(TAG, "Cannot start generation: Service not connected")
            return
        }
        llamaService?.startGeneration(sessionId, currentHistory, prompt, maxTokens, assistantMessageId, systemPrompt, resetContext)
    }

    fun stopGeneration() {
        llamaService?.stopGeneration()
        _generationState.value = GenerationState.Standby
    }
    
    /**
     * Spustí jednorázovou generaci a asynchronně počká na výsledek.
     * Využívá se pro Map-Reduce LLM post-processing (nikoliv pro plynulý chat).
     */
    suspend fun awaitGeneration(prompt: String, maxTokens: Int = 2048, systemPrompt: String? = null, resetContext: Boolean = false): String? {
        if (!isBound || llamaService == null) {
            Log.w(TAG, "Cannot await generation: Service not connected")
            return null
        }
        
        if (generationState.value !is GenerationState.Idle && generationState.value !is GenerationState.Standby) {
             stopGeneration()
             kotlinx.coroutines.delay(100)
        }

        startGeneration(
            sessionId = "post_process_${System.currentTimeMillis()}",
            currentHistory = emptyList(),
            prompt = prompt,
            maxTokens = maxTokens,
            assistantMessageId = "assistant_${System.currentTimeMillis()}",
            systemPrompt = systemPrompt,
            resetContext = resetContext
        )
        
        val finalState = generationState.first { state ->
            state is GenerationState.Complete || state is GenerationState.Error
        }
        
        if (finalState is GenerationState.Error) {
            throw Exception(finalState.message)
        }
        
        return if (finalState is GenerationState.Complete) {
            finalState.fullText
        } else {
            null
        }
    }

    fun isServiceConnected(): Boolean = isBound

    companion object {
        private const val TAG = "LlamaServiceManager"
    }
}
