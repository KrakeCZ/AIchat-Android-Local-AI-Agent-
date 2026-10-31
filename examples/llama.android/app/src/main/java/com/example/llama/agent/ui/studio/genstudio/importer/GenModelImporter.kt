package com.example.llama.agent.ui.studio.genstudio.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.llama.data.gen.GenDatabase
import com.example.llama.data.gen.GenModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ModelInspectionResult(
    val format: String,
    val architecture: String,
    val quantization: String,
    val fileSize: Long,
    val suggestedDisplayName: String,
    val detectedTextEncoder: String? = null,
    val detectedVae: String? = null,
    val supportsVideo: Boolean = true,
    val supportsImage: Boolean = true,
    val isDistilled: Boolean = false
)

/**
 * Senior DevOps / Kernel-level binární a metadatový inspektor modelů pro GEN_Studio.
 * Bezpečně analyzuje hlavičky modelů (Magic Bytes) a extrahuje metadata bez načtení celého souboru do paměti.
 */
class GenModelImporter(
    private val context: Context,
    private val genDatabase: GenDatabase
) {
    companion object {
        private const val TAG = "GenModelImporter"

        // Magic bytes
        private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"
        private val ZIP_TASK_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK.."
    }

    /**
     * Prozkoumá a naimportuje model z Android SAF Content URI do lokální databáze.
     */
    suspend fun importFromUri(uri: Uri, copyToInternalStorage: Boolean = false): Result<GenModel> = withContext(Dispatchers.IO) {
        try {
            val fileName = resolveFileName(uri)
            val fileSize = resolveFileSize(uri)

            Log.i(TAG, "🔍 Začínám inspekci modelu: $fileName (${fileSize / (1024 * 1024)} MB)")

            // Přečtení prvních 256 bajtů pro detekci magic bytes
            val headerBytes = ByteArray(256)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.read(headerBytes, 0, headerBytes.size)
            } ?: return@withContext Result.failure(IllegalStateException("Nelze otevřít stream pro $uri"))

            val inspection = inspectHeader(fileName, fileSize, headerBytes)

            // Cesta k cílovému souboru
            val finalPath: String
            if (copyToInternalStorage) {
                val targetDir = File(context.filesDir, "models/gen")
                targetDir.mkdirs()
                val targetFile = File(targetDir, fileName)
                Log.i(TAG, "📦 Kopíruji soubor do interního úložiště: ${targetFile.absolutePath}")
                
                context.contentResolver.openInputStream(uri)?.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                finalPath = targetFile.absolutePath
            } else {
                // Pokusíme se přeložit SAF URI na přímou POSIX cestu
                val resolvedDirectPath = com.example.llama.agent.ui.studio.genstudio.engine.UriPathResolver.resolveToPath(context, uri.toString())
                finalPath = if (!resolvedDirectPath.isNullOrBlank() && File(resolvedDirectPath).exists() && File(resolvedDirectPath).canRead()) {
                    Log.i(TAG, "🎯 Detekována a uložena přímá cesta k modelu: $resolvedDirectPath")
                    resolvedDirectPath
                } else {
                    // Zachování persistable URI pro přímý přístup přes file descriptor
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Nepodařilo se vzít persistable URI oprávnění: ${e.message}")
                    }
                    uri.toString()
                }
            }

            var detectedVae = inspection.detectedVae
            var detectedText = inspection.detectedTextEncoder

            val lowerFileName = fileName.lowercase()
            val isVaeOnly = lowerFileName.contains("vae")
            val isTextOnly = lowerFileName.contains("umt5") || lowerFileName.contains("clip") || (lowerFileName.contains("t5") && !lowerFileName.contains("t2v"))

            if (!isVaeOnly && detectedVae == null) {
                val dbVae = genDatabase.genModelDao().findAnyVaeModel()
                if (dbVae != null) {
                    detectedVae = dbVae.filePath
                    Log.i(TAG, "🔗 Auto-Discovery: Nalezen VAE dekodér '${dbVae.displayName}', automaticky spárován.")
                }
            }

            if (!isTextOnly && detectedText == null) {
                val dbText = genDatabase.genModelDao().findAnyTextEncoderModel()
                if (dbText != null) {
                    detectedText = dbText.filePath
                    Log.i(TAG, "🔗 Auto-Discovery: Nalezen Textový Enkodér '${dbText.displayName}', automaticky spárován.")
                }
            }

            val genModel = GenModel(
                displayName = inspection.suggestedDisplayName,
                sourceUri = uri.toString(),
                filePath = finalPath,
                fileSize = fileSize,
                format = inspection.format,
                architecture = inspection.architecture,
                quantization = inspection.quantization,
                textEncoderPath = detectedText,
                vaePath = detectedVae,
                supportsVideo = inspection.supportsVideo,
                supportsImage = inspection.supportsImage,
                isDistilled1Step = inspection.isDistilled
            )

            genDatabase.genModelDao().insertModel(genModel)

            // Pokud jsme právě naimportovali VAE, retroaktivně spárujeme s existujícími DiT modely bez VAE
            if (isVaeOnly) {
                val latestVideo = genDatabase.genModelDao().getLatestVideoModel()
                if (latestVideo != null && latestVideo.vaePath == null && latestVideo.id != genModel.id) {
                    genDatabase.genModelDao().updateVaePath(latestVideo.id, finalPath)
                    Log.i(TAG, "🔗 Retroaktivní Auto-Pairing: VAE '${genModel.displayName}' byl přiřazen k modelu '${latestVideo.displayName}'.")
                }
            }

            // Pokud jsme právě naimportovali Text Enkodér, retroaktivně spárujeme
            if (isTextOnly) {
                val latestVideo = genDatabase.genModelDao().getLatestVideoModel()
                if (latestVideo != null && latestVideo.textEncoderPath == null && latestVideo.id != genModel.id) {
                    genDatabase.genModelDao().updateTextEncoderPath(latestVideo.id, finalPath)
                    Log.i(TAG, "🔗 Retroaktivní Auto-Pairing: Textový Enkodér '${genModel.displayName}' byl přiřazen k modelu '${latestVideo.displayName}'.")
                }
            }

            Log.i(TAG, "✅ Model úspěšně zaregistrován v GenDatabase: ${genModel.displayName} [${genModel.format}/${genModel.quantization}]")

            Result.success(genModel)
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Selhal import modelu z URI $uri: ${t.message}", t)
            Result.failure(t)
        }
    }

    /**
     * Provádí binární analýzu hlavičky a heuristickou detekci architektury.
     */
    fun inspectHeader(fileName: String, fileSize: Long, header: ByteArray): ModelInspectionResult {
        val lowerName = fileName.lowercase()

        // 1. Detekce formátu z Magic Bytes a přípony
        val format = when {
            header.startsWith(GGUF_MAGIC) || lowerName.endsWith(".gguf") -> "GGUF"
            header.startsWith(ZIP_TASK_MAGIC) || lowerName.endsWith(".task") -> "TASK"
            lowerName.endsWith(".onnx") -> "ONNX"
            lowerName.endsWith(".safetensors") -> "SAFETENSORS"
            lowerName.endsWith(".tflite") -> "TFLITE"
            else -> "BIN"
        }

        // 2. Detekce Architektury a subsítí
        val isVaeOnly = lowerName.contains("vae") || lowerName.contains("ae.safetensors") || lowerName.contains("ae.gguf")
        val isTextOnly = lowerName.contains("umt5") || lowerName.contains("clip") || (lowerName.contains("t5") && !lowerName.contains("t2v") && !lowerName.contains("i2v"))

        val architecture = when {
            isVaeOnly -> "VAE_DECODER"
            isTextOnly -> "TEXT_ENCODER"
            lowerName.contains("wan2.5") || lowerName.contains("wan-2.5") || lowerName.contains("wan_2.5") -> "FLOW_MATCHING_DIT"
            lowerName.contains("wan2.2") || lowerName.contains("wan-2.2") -> "FLOW_MATCHING_DIT"
            lowerName.contains("wan2.1") || lowerName.contains("wan-2.1") || lowerName.contains("wan") -> "FLOW_MATCHING_DIT"
            lowerName.contains("ltx") -> "FLOW_MATCHING_DIT"
            lowerName.contains("hunyuan") -> "SLIDING_TILE_DIT"
            lowerName.contains("s2dit") || lowerName.contains("mobilewan") -> "SLIDING_TILE_DIT"
            lowerName.contains("show-o") || lowerName.contains("omnigen") || lowerName.contains("lumina") || lowerName.contains("llamagen") -> "AUTOREGRESSIVE"
            lowerName.contains("animatediff") || lowerName.contains("svd") -> "CONVOLUTIONAL"
            lowerName.contains("flux") || lowerName.contains("sana") -> "FLOW_MATCHING_DIT"
            lowerName.contains("cosmos") -> "WORLD_MODEL"
            else -> "FLOW_MATCHING_DIT"
        }

        // 3. Detekce Kvantizace
        val quantization = when {
            lowerName.contains("q4_k_m") -> "Q4_K_M"
            lowerName.contains("q4_k_s") -> "Q4_K_S"
            lowerName.contains("q3_k_s") -> "Q3_K_S"
            lowerName.contains("q3_k_m") -> "Q3_K_M"
            lowerName.contains("q2_k") -> "Q2_K"
            lowerName.contains("q8_0") -> "Q8_0"
            lowerName.contains("fp16") || lowerName.contains("f16") -> "FP16"
            lowerName.contains("fp8") || lowerName.contains("e4m3") -> "FP8"
            lowerName.contains("int8") -> "INT8"
            lowerName.contains("int4") -> "INT4"
            else -> "Q4_K_M"
        }

        // 4. Detekce schopností a destilace
        val isDistilled = lowerName.contains("distill") || lowerName.contains("4step") || lowerName.contains("lightning")
        val isImageOnly = lowerName.contains("flux") || lowerName.contains("sdxl") && !lowerName.contains("video")
        val supportsVideo = !isVaeOnly && !isTextOnly && !isImageOnly
        val supportsImage = !isVaeOnly && !isTextOnly

        val cleanName = fileName.replace(".gguf", "", ignoreCase = true)
            .replace(".safetensors", "", ignoreCase = true)
            .replace(".onnx", "", ignoreCase = true)
            .replace(".task", "", ignoreCase = true)

        return ModelInspectionResult(
            format = format,
            architecture = architecture,
            quantization = quantization,
            fileSize = fileSize,
            suggestedDisplayName = cleanName,
            supportsVideo = supportsVideo,
            supportsImage = supportsImage,
            isDistilled = isDistilled
        )
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    private fun resolveFileName(uri: Uri): String {
        var name = "unknown_model"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun resolveFileSize(uri: Uri): Long {
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex != -1 && cursor.moveToFirst()) {
                size = cursor.getLong(sizeIndex)
            }
        }
        return size
    }
}
