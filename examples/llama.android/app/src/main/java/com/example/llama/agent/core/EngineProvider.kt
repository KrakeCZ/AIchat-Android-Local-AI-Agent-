package com.example.llama.agent.core

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine

/**
 * EngineProvider - Singleton for InferenceEngine access
 *
 * Prevents multiple instances and race conditions between Activity and Service. Centralizes the
 * "Brain" of the application.
 */
object EngineProvider {

    @Volatile private var engineInstance: InferenceEngine? = null

    fun get(context: Context): InferenceEngine {
        return engineInstance
                ?: synchronized(this) {
                    engineInstance
                            ?: AiChat.getInferenceEngine(context.applicationContext).also {
                                engineInstance = it
                                android.util.Log.i(
                                        "EngineProvider",
                                        "⚡ InferenceEngine initialized/retrieved via Provider"
                                )
                            }
                }
    }

    // Optional: Reset if strictly needed (e.g. fatal error), but usually avoided
    fun clear() {
        synchronized(this) { engineInstance = null }
    }
}
