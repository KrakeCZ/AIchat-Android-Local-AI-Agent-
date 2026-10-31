package com.example.llama.agent.core

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.example.llama.SettingsManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class EngineOwner {
    FOREGROUND_CHAT,
    FOREGROUND_DICTAPHONE,
    BACKGROUND_AGENT,
    SYSTEM
}

data class EngineConfiguration(
    val modelPath: String,
    val projectorPath: String? = null,
    val nCtx: Int = 4096,
    val useMmap: Boolean = true,
    val nBatch: Int = 512,
    val nUBatch: Int = 512,
    val flashAttn: Boolean = true,
    val kvCacheMode: Int = 0,
    val llamaBackend: SettingsManager.LlamaBackend = SettingsManager.LlamaBackend.CPU,
    val strictBackend: Boolean = false,
    val gpuLayers: Int = 0
)

/**
 * EngineOrchestrator - Centralized Oracle for the single native InferenceEngine.
 * Implements "Foreground Wins" rule.
 */
object EngineOrchestrator {
    private const val TAG = "OracleOrchestrator"

    @Volatile private var engineInstance: InferenceEngine? = null
    private val mutex = Mutex()

    @Volatile private var currentConfig: EngineConfiguration? = null
    val activeConfig: EngineConfiguration? get() = currentConfig
    @Volatile private var currentOwner: EngineOwner? = null
    @Volatile private var isForegroundActive: Boolean = false

    /**
     * Returns the engine without a lock for read-only flows like state observing.
     */
    fun getEngineRaw(context: Context): InferenceEngine {
        return engineInstance ?: synchronized(this) {
            engineInstance ?: AiChat.getInferenceEngine(context.applicationContext).also {
                engineInstance = it
            }
        }
    }

    /**
     * Foreground tasks (AiChat, Dictaphone) use this.
     * Interrupts Background Agent and forces reload if config changes.
     */
    suspend fun <T> withForegroundLock(
        context: Context,
        owner: EngineOwner,
        config: EngineConfiguration? = null,
        block: suspend (InferenceEngine) -> T
    ): T {
        Log.i(TAG, "[$owner] requested Foreground Lock. Config=$config")
        isForegroundActive = true
        try {
            return mutex.withLock {
                val engine = getEngineRaw(context)
                
                // Přerušení probíhajícího Agent tasku pouze pokud skutečně generuje
                if (engine.state.value is InferenceEngine.State.Generating) {
                    engine.cancelGeneration()
                }
                
                val targetConfig = config ?: currentConfig
                if (targetConfig != null) {
                    ensureConfiguration(engine, targetConfig, owner)
                } else {
                    Log.w(TAG, "[$owner] Foreground Lock acquired, but NO config available (model not loaded).")
                }
                
                Log.i(TAG, "[$owner] acquired Foreground Lock.")
                block(engine)
            }
        } finally {
            isForegroundActive = false
            currentOwner = null
            Log.i(TAG, "[$owner] released Foreground Lock.")
        }
    }

    /**
     * Background Agent uses this.
     * If Foreground is active, immediately returns null to avoid interrupting user.
     * Pokud je config null, použije se ten, který je aktuálně načtený (fallback na to co načetlo UI).
     */
    suspend fun <T> withBackgroundLock(
        context: Context,
        config: EngineConfiguration? = null,
        block: suspend (InferenceEngine) -> T
    ): T? {
        if (isForegroundActive) {
            Log.w(TAG, "[BACKGROUND_AGENT] rejected: Foreground is active.")
            return null
        }

        return mutex.withLock {
            if (isForegroundActive) return@withLock null

            val targetConfig = config ?: currentConfig
            if (targetConfig == null) {
                Log.w(TAG, "[BACKGROUND_AGENT] rejected: No active EngineConfiguration found.")
                return@withLock null
            }

            val engine = getEngineRaw(context)
            // Just in case it was cancelled mid-generation by foreground previously
            try { engine.setGrammar(null) } catch (_: Exception) {}

            ensureConfiguration(engine, targetConfig, EngineOwner.BACKGROUND_AGENT)
            
            currentOwner = EngineOwner.BACKGROUND_AGENT
            try {
                block(engine)
            } finally {
                if (currentOwner == EngineOwner.BACKGROUND_AGENT) {
                    currentOwner = null
                }
            }
        }
    }

    private suspend fun ensureConfiguration(
        engine: InferenceEngine,
        newConfig: EngineConfiguration,
        owner: EngineOwner
    ) {
        val reloadNeeded = currentConfig == null ||
                currentConfig?.modelPath != newConfig.modelPath ||
                currentConfig?.projectorPath != newConfig.projectorPath ||
                currentConfig?.nCtx != newConfig.nCtx ||
                currentConfig?.nBatch != newConfig.nBatch ||
                currentConfig?.nUBatch != newConfig.nUBatch ||
                currentConfig?.flashAttn != newConfig.flashAttn ||
                currentConfig?.kvCacheMode != newConfig.kvCacheMode ||
                currentConfig?.useMmap != newConfig.useMmap ||
                currentConfig?.llamaBackend != newConfig.llamaBackend ||
                currentConfig?.strictBackend != newConfig.strictBackend ||
                currentConfig?.gpuLayers != newConfig.gpuLayers

        if (reloadNeeded) {
            Log.i(TAG, "Oracle Reload: OldConfig=$currentConfig, NewConfig=$newConfig")
            try {
                engine.cleanUp() // Uvolnění RAM
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up previous model", e)
            }
            
            engine.loadModel(
                pathToModel = newConfig.modelPath,
                projectorPath = newConfig.projectorPath,
                kvCacheMode = newConfig.kvCacheMode,
                useMmap = newConfig.useMmap,
                nCtx = newConfig.nCtx,
                nBatch = newConfig.nBatch,
                nUBatch = newConfig.nUBatch,
                flashAttn = newConfig.flashAttn,
                backend = newConfig.llamaBackend.id,
                strictBackend = newConfig.strictBackend,
                gpuLayers = newConfig.gpuLayers
            )
            currentConfig = newConfig
            currentOwner = owner
        }
    }
    
    fun resetConfiguration() {
        currentConfig = null
    }
}
