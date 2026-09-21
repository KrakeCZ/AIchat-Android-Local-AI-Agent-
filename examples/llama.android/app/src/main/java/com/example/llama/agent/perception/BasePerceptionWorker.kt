package com.example.llama.agent.perception

/**
 * ========================================================================
 * UNIVERSAL PERCEPTION WORKER CONTRACT
 * ========================================================================
 * 
 * Future-proof rozhraní pro všechny AI operace v Perception Matrix.
 * Každý worker (NLP, Audio, Vision) bude dodržovat stejný lifecycle.
 * Zajišťuje memory safety (flush bufferů) a lazy-loading.
 */
interface BasePerceptionWorker {
    val workerId: String
    val requiredPermissions: List<String>
    
    /**
     * Alokace RAM, kontrola (-f model.bin), ověření oprávnění.
     * Může zahrnovat network requesty (stahení modelu) nebo těžké IO.
     */
    suspend fun prepare(): Boolean
    
    /**
     * Spuštění lock-free smyčky nebo asynchronní práce workera.
     */
    fun start()
    
    /**
     * Zastavení smyčky, flush dočasných bufferů (snížení využití RAM).
     */
    fun stop()
    
    /**
     * Úplné uvolnění kontextu z paměti (např. zavolání whisperFreeModel).
     */
    fun destroy()
}
