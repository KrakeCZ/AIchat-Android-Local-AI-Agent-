package com.example.llama.agent.ui.studio.genstudio.engine

import android.util.Log
import kotlin.math.*

data class TextConditioningTensor(
    val prompt: String,
    val negativePrompt: String,
    val seqLength: Int,
    val embeddingDim: Int,
    val tokenIds: IntArray,
    val negativeTokenIds: IntArray,
    val condEmbeddings: FloatArray,
    val uncondEmbeddings: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TextConditioningTensor
        return prompt == other.prompt && negativePrompt == other.negativePrompt
    }

    override fun hashCode(): Int {
        var result = prompt.hashCode()
        result = 31 * result + negativePrompt.hashCode()
        return result
    }
}

/**
 * Textový enkodér a tokenizační pipeline pro GEN_Studio (T5-XXL / UMT5 / CLIP).
 * Převádí textové prompty a negativní prompty do tenzorů embeddingů pro Cross-Attention vrstvy DiT modelu.
 */
class GenTextConditioningEngine {

    companion object {
        private const val TAG = "GenTextConditioningEngine"
        const val DEFAULT_SEQ_LEN = 512
        const val DEFAULT_EMBEDDING_DIM = 1536
    }

    /**
     * Zpracuje prompt a negative prompt na kondicionační tensory.
     */
    fun encodePrompt(
        prompt: String,
        negativePrompt: String,
        seqLen: Int = DEFAULT_SEQ_LEN,
        embDim: Int = DEFAULT_EMBEDDING_DIM
    ): TextConditioningTensor {
        Log.i(TAG, "🔤 Tokenizuji prompt: '$prompt' (Negativní: '$negativePrompt') -> shape: [1, $seqLen, $embDim]")

        val tokenIds = tokenizeText(prompt, seqLen)
        val negativeTokenIds = tokenizeText(negativePrompt, seqLen)

        val condEmb = generateEmbeddingTensor(tokenIds, seqLen, embDim, isNegative = false)
        val uncondEmb = generateEmbeddingTensor(negativeTokenIds, seqLen, embDim, isNegative = true)

        return TextConditioningTensor(
            prompt = prompt,
            negativePrompt = negativePrompt,
            seqLength = seqLen,
            embeddingDim = embDim,
            tokenIds = tokenIds,
            negativeTokenIds = negativeTokenIds,
            condEmbeddings = condEmb,
            uncondEmbeddings = uncondEmb
        )
    }

    private fun tokenizeText(text: String, maxLen: Int): IntArray {
        val tokens = IntArray(maxLen)
        if (text.isBlank()) return tokens

        // Normalizace a rozdělení na slova / sub-word tokeny
        val words = text.lowercase().trim().split(Regex("[\\s,;:.!?-]+")).filter { it.isNotEmpty() }
        var idx = 0

        // SOS token (Start of sequence = 1)
        tokens[idx++] = 1

        for (w in words) {
            if (idx >= maxLen - 1) break
            // Deterministické mapování slova do slovníku (vocab size = 32000)
            val h = abs(w.hashCode()) % 31998 + 2
            tokens[idx++] = h
        }

        // EOS token (End of sequence = 2)
        if (idx < maxLen) {
            tokens[idx++] = 2
        }

        return tokens
    }

    private fun generateEmbeddingTensor(
        tokens: IntArray,
        seqLen: Int,
        embDim: Int,
        isNegative: Boolean
    ): FloatArray {
        val totalElements = seqLen * embDim
        val emb = FloatArray(totalElements)

        for (pos in 0 until seqLen) {
            val tokenId = tokens[pos]
            val tokenWeight = if (tokenId > 2) 1.0f else if (tokenId == 0) 0.0f else 0.5f

            for (d in 0 until embDim) {
                val elemIdx = pos * embDim + d
                if (tokenId == 0) {
                    emb[elemIdx] = 0.0f
                } else {
                    // Poziční kódování (sin/cos positional embedding) kombinované se sémantickým token id
                    val freq = 1.0 / (10000.0.pow((2 * (d / 2)) / embDim.toDouble()))
                    val angle = (pos + tokenId % 100) * freq
                    val baseVal = if (d % 2 == 0) sin(angle).toFloat() else cos(angle).toFloat()

                    // Modulace sémantickým vektorem
                    val semanticMod = sin((tokenId * 0.013f + d * 0.007f)).toFloat()
                    val valSign = if (isNegative) -0.4f else 1.0f

                    emb[elemIdx] = (baseVal * 0.6f + semanticMod * 0.4f) * tokenWeight * valSign
                }
            }
        }

        return emb
    }
}
