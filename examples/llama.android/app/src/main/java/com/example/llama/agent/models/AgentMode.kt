package com.example.llama.agent.models

/**
 * Reprezentuje stav AI Agenta
 */
sealed class AgentMode {
    /**
     * Agent je neaktivní
     */
    data object Idle : AgentMode()
    
    /**
     * Agent je aktivní a zpracovává příkaz
     */
    data class Active(
        val command: String,
        val startTime: Long = System.currentTimeMillis()
    ) : AgentMode()
    
    /**
     * Agent analyzuje obrazovku a rozhoduje se
     */
    data class Thinking(
        val screenState: ScreenState
    ) : AgentMode()
    
    /**
     * Agent vykonává akci
     */
    data class Executing(
        val action: AgentAction
    ) : AgentMode()
    
    /**
     * Chyba při vykonávání
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : AgentMode()
}
