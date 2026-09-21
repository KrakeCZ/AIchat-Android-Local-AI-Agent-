package com.example.llama.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ModelDetailState(
    val name: String = "",
    val filePath: String = "",
    val contextSize: Long = 0,
    val modelSize: String = "",
    val parameters: String = "",
    val embeddingSize: Long = 0,
    val vocabSize: Long = 0,
    val quantization: String = "",
    val chatTemplate: String = "",
    val architecture: String = ""
) : Parcelable
