package com.example.llama.agent.core

import android.content.Context
import com.arm.aichat.InferenceEngine

/**
 * EngineProvider - Singleton for InferenceEngine access
 *
 * Prevents multiple instances and race conditions between Activity and Service. Centralizes the
 * "Brain" of the application (supporting both Offline GGUF and Online Cloud API via HybridInferenceEngine).
 */
object EngineProvider {

    @Volatile private var engineInstance: HybridInferenceEngine? = null

    fun get(context: Context): InferenceEngine {
        val instance = engineInstance
            ?: synchronized(this) {
                engineInstance
                    ?: HybridInferenceEngine.getInstance(context.applicationContext).also {
                        engineInstance = it
                        android.util.Log.i(
                            "EngineProvider",
                            "⚡ HybridInferenceEngine initialized/retrieved via Provider"
                        )
                    }
            }
        instance.syncWithSettings()
        return instance
    }

    fun getHybrid(context: Context): HybridInferenceEngine {
        return get(context) as HybridInferenceEngine
    }

    fun syncWithSettings(context: Context) {
        getHybrid(context).syncWithSettings()
    }

    // Optional: Reset if strictly needed (e.g. fatal error), but usually avoided
    fun clear() {
        synchronized(this) { engineInstance = null }
    }
}
