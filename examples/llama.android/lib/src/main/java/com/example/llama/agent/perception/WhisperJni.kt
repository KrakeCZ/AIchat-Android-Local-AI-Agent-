package com.example.llama.agent.perception

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

object WhisperJni {
    private const val TAG = "WhisperJni"
    private val isLoaded = AtomicBoolean(false)
    
    // Uloží ukazatel na whisper_context v nativní vrstvě
    private var ctxPtr: Long = 0L

    init {
        try {
            System.loadLibrary("whisper-jni")
            isLoaded.set(true)
            Log.i(TAG, "libwhisper-jni.so loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libwhisper-jni.so", e)
        }
    }

    /**
     * Inicializuje Whisper model (lazy loading).
     * @param modelPath Absolutní cesta k souboru s modelem (.bin nebo .gguf)
     * @return true pokud inicializace proběhla úspěšně nebo model už běží
     */
    fun init(modelPath: String, computeBackend: String = "CPU"): Boolean {
        if (!isLoaded.get()) {
            Log.e(TAG, "Cannot init model, native library is not loaded!")
            return false
        }
        
        if (ctxPtr != 0L) {
            Log.w(TAG, "Model is already initialized. Call release() first to load a new model.")
            return true
        }

        Log.i(TAG, "Initializing whisper model from: $modelPath [Backend: $computeBackend]")
        ctxPtr = whisperInit(modelPath, computeBackend)
        
        return ctxPtr != 0L
    }

    /**
     * Transkribuje audio PCM data.
     * @param audioData Pole PCM floatů (16kHz mono)
     * @param language Zkratka jazyka ("cs", "en", "auto")
     * @param nThreads Počet výpočetních vláken (doporučeno 2 pro edge zařízeni)
     * @return Přepsaný text, nebo prázdný string při chybě
     */
    fun transcribe(audioData: FloatArray, language: String = "auto", nThreads: Int = 2): String {
        if (ctxPtr == 0L) {
            Log.e(TAG, "Cannot transcribe, model is not initialized!")
            return ""
        }
        
        return whisperTranscribe(ctxPtr, audioData, language, nThreads)
    }

    /**
     * Detekuje jazyk z audia (vrací top jazyk, např. "cs", "en")
     */
    fun detectLanguage(audioData: FloatArray, nThreads: Int = 2): String {
        if (ctxPtr == 0L) {
            Log.e(TAG, "Cannot detect language, model is not initialized!")
            return "en"
        }
        
        return whisperDetectLanguage(ctxPtr, audioData, nThreads)
    }

    /**
     * Uvolní paměť zabranou modelem a ggml kontextem.
     */
    fun release() {
        if (ctxPtr != 0L) {
            Log.i(TAG, "Releasing whisper model...")
            whisperFree(ctxPtr)
            ctxPtr = 0L
        }
    }

    // --- Nativní funkce v C++ ---
    private external fun whisperInit(modelPath: String, computeBackend: String): Long
    private external fun whisperTranscribe(ctxPtr: Long, audioData: FloatArray, language: String, nThreads: Int): String
    private external fun whisperDetectLanguage(ctxPtr: Long, audioData: FloatArray, nThreads: Int): String
    private external fun whisperFree(ctxPtr: Long)
}
