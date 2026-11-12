package com.example.llama.agent.perception.nlp.workers

import android.content.Context
import com.example.llama.SettingsManager
import com.example.llama.agent.perception.NlpSummaryWorker

/**
 * Heuristický Expert (TF-IDF).
 * Nezávisí na žádném LLM, běží okamžitě na CPU přes NlpSummaryWorker.
 */
class HeuristicWorker(context: Context) {
    private val nlpSummaryWorker = NlpSummaryWorker(context, SettingsManager(context))

    fun summarize(text: String): String {
        return nlpSummaryWorker.summarizeWithHeuristic(text)
    }
}

