package com.example.llama.agent.vision.models

import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TfliteVisionAnalyzer — Zero-Copy detektor vlastností Vision modelů.
 * Analyzuje .tflite bez načítání celého souboru do Java Heap (prevence OOM).
 * Spoléhá se čistě na litert-core (nepotřebuje těžké TFLite Support knihovny).
 */
object TfliteVisionAnalyzer {

    private const val TAG = "TfliteVisionAnalyzer"

    data class VisionModelProfile(
        val isValid: Boolean,
        val taskType: VisionTaskType, // PŘIDÁNO: Typ úlohy
        val labelStrategy: OdLabelStrategy,
        val inputWidth: Int,
        val inputHeight: Int,
        val classCount: Int,
        val isQuantized: Boolean,
        val isNchw: Boolean = false,
        val explicitArchitecture: String? = null,
        val errorMessage: String? = null
    )

    /**
     * Provede hloubkovou inspekci .tflite souboru.
     * @param modelFile Fyzický soubor na disku (ověřeno volajícím, že existuje)
     * @return VisionModelProfile s doporučenou strategií
     */
    fun analyzeModel(modelFile: File): VisionModelProfile {
        if (!modelFile.exists() || !modelFile.canRead()) {
            return VisionModelProfile(
                isValid = false,
                taskType = VisionTaskType.UNKNOWN,
                labelStrategy = OdLabelStrategy.UNKNOWN,
                inputWidth = 0,
                inputHeight = 0,
                classCount = 0,
                isQuantized = false,
                isNchw = false,
                explicitArchitecture = null,
                errorMessage = "Soubor neexistuje nebo chybí práva ke čtení: ${modelFile.absolutePath}"
            )
        }

        var mappedBuffer: MappedByteBuffer? = null
        var interpreter: Interpreter? = null

        return try {
            // 1. Memory-mapped I/O pro obcházení alokace v heap paměti
            mappedBuffer = FileInputStream(modelFile).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, 0L, modelFile.length())
            }

            // 2. CPU-only inicializace pro čistou inspekci struktury (žádný GPU/NNAPI delegate)
            val options = Interpreter.Options().apply {
                numThreads = 1
            }
            interpreter = Interpreter(mappedBuffer, options)

            // 3. Analýza Vstupu (Input Tensors)
            val inputTensor = interpreter.getInputTensor(0)
            val inputShape = inputTensor.shape() // Běžně [1, H, W, 3]
            
            var inWidth = 0
            var inHeight = 0
            var inChannels = 0
            var isNchwFormat = false
            if (inputShape.size == 4) {
                if (inputShape[1] == 1 || inputShape[1] == 3) {
                    // NCHW formát (častý u YOLO a PyTorch exportů)
                    inChannels = inputShape[1]
                    inHeight = inputShape[2]
                    inWidth = inputShape[3]
                    isNchwFormat = true
                } else {
                    // NHWC formát (standardní TFLite)
                    inHeight = inputShape[1]
                    inWidth = inputShape[2]
                    inChannels = inputShape[3]
                }
            }

            val isQuantized = (inputTensor.dataType() == org.tensorflow.lite.DataType.UINT8 || 
                               inputTensor.dataType() == org.tensorflow.lite.DataType.INT8)

            // 🔴 NOVÝ BLOK: Pokročilá Multi-Domain Matematická Heuristika výstupů 🔴
            var detectedClasses = 0
            var taskType = VisionTaskType.UNKNOWN
            var labelFileFromMetadata: String? = null
            var explicitArchitecture: String? = null
            var isLegacyCocoShape = false

            val outputCount = interpreter.outputTensorCount
            for (i in 0 until outputCount) {
                val outShape = interpreter.getOutputTensor(i).shape()
                
                // 1. Klasifikace (např. [1, 1000])
                if (outShape.size == 2 && outShape[0] == 1) {
                    detectedClasses = outShape[1]
                    taskType = VisionTaskType.CLASSIFICATION
                    break
                }
                
                // 2. Semantic/Medical Segmentation (např. [1, 256, 256, 21])
                if (outShape.size == 4 && outShape[0] == 1) {
                    if (outShape[1] > 32 && outShape[2] > 32) { // Heuristika: Je to mapa pixelů
                        if (outShape[3] == 1) {
                            taskType = VisionTaskType.DEPTH_ESTIMATION // [1, H, W, 1]
                        } else {
                            taskType = VisionTaskType.SEMANTIC_SEGMENTATION
                            detectedClasses = outShape[3]
                        }
                        break
                    }
                }
                
                // 3. Pose Estimation (např. MoveNet: [1, 1, 17, 3])
                if (outShape.size == 4 && outShape[2] == 17 && outShape[3] == 3) {
                    taskType = VisionTaskType.POSE_ESTIMATION
                    detectedClasses = 17 // 17 kloubů těla
                    break
                }

                // 4. Object Detection (YOLO rodina, RT-DETR)
                if (outShape.size == 3) {
                    val lastDim = outShape[2]
                    val midDim = outShape[1]
                    if (lastDim == 80 || lastDim == 84 || lastDim == 85 || lastDim == 90 || lastDim == 1000) {
                        detectedClasses = if (lastDim == 85) 80 else lastDim
                        taskType = VisionTaskType.OBJECT_DETECTION
                        isLegacyCocoShape = true
                        break
                    }
                    if (midDim == 80 || midDim == 84 || midDim == 85) { 
                        detectedClasses = if (midDim == 85) 80 else midDim 
                        taskType = VisionTaskType.OBJECT_DETECTION
                        isLegacyCocoShape = true
                        break
                    }
                }
            }

            // 2. VYLEPŠENÍ PŘES MANIFEST (Pokud existuje, přepíše základní heuristiku)
            val manifestFile = File(modelFile.parentFile, "${modelFile.nameWithoutExtension}_manifest.json")
            if (manifestFile.exists()) {
                try {
                    val manifestJson = org.json.JSONObject(manifestFile.readText())
                    val manifestClasses = manifestJson.optInt("classCount", 0)
                    if (manifestClasses > 0) detectedClasses = manifestClasses
                    
                    val arch = manifestJson.optString("architecture", "")
                    if (arch.isNotEmpty()) {
                        explicitArchitecture = arch
                        taskType = VisionTaskType.OBJECT_DETECTION
                    }
                    Log.i(TAG, "📦 Načten BYOM Manifest: $explicitArchitecture ($detectedClasses tříd)")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Chyba parsování manifestu: ${manifestFile.name}", e)
                }
            }

            // 5. Zjištění přítomnosti TFLITE_METADATA FlatBufferu (Heuristika přes ByteArray signature)
            // Poznámka: Plná analýza vyžaduje FlatBuffer parser, zde provádíme rychlý string matching
            // na prvních 4KB bufferu, kde metadata obvykle sedí.
            val isEmbedded = hasEmbeddedMetadata(mappedBuffer)
            if (isEmbedded) {
                try {
                    val extractor = org.tensorflow.lite.support.metadata.MetadataExtractor(mappedBuffer)
                    val modelName = extractor.modelMetadata.name() ?: ""
                    val modelDesc = extractor.modelMetadata.description() ?: ""
                    Log.i(TAG, "🔍 Nalezena TFLite Metadata: $modelName ($modelDesc)")

                    val files = extractor.associatedFileNames
                    if (files.isNotEmpty()) {
                        labelFileFromMetadata = files.first()
                        val labelsStream = extractor.getAssociatedFile(labelFileFromMetadata)
                        val labelsList = labelsStream.bufferedReader().readLines().filter { it.isNotBlank() }
                        if (labelsList.isNotEmpty()) {
                            detectedClasses = labelsList.size
                        }
                        Log.i(TAG, "🔍 Extraktováno $detectedClasses tříd z přibaleného $labelFileFromMetadata")
                    }

                    // Pokud název detekuje YOLO rodinu nebo DETR, přepíšeme task na OD
                    if (modelName.contains("YOLO", true) || modelDesc.contains("YOLO", true) ||
                        modelName.contains("DETR", true) || modelDesc.contains("DETR", true)) {
                        
                        explicitArchitecture = when {
                            modelName.contains("YOLOv11", true) || modelDesc.contains("YOLOv11", true) -> "YOLOv11"
                            modelName.contains("YOLOv10", true) || modelDesc.contains("YOLOv10", true) -> "YOLOv10"
                            modelName.contains("YOLOv9", true) || modelDesc.contains("YOLOv9", true) -> "YOLOv9"
                            modelName.contains("YOLOv8", true) || modelDesc.contains("YOLOv8", true) -> "YOLOv8"
                            modelName.contains("YOLOv5", true) || modelDesc.contains("YOLOv5", true) -> "YOLOv5"
                            modelName.contains("RF-DETR", true) || modelDesc.contains("RF-DETR", true) -> "RF-DETR"
                            modelName.contains("RT-DETR", true) || modelDesc.contains("RT-DETR", true) -> "RT-DETR"
                            else -> "UNKNOWN_OD_MODEL"
                        }
                        taskType = VisionTaskType.OBJECT_DETECTION
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "⚠️ Nelze přečíst TFLite Metadata", e)
                }
            }

            // 4. Rozhodovací strom pro Label Strategy
            val strategy = when {
                isEmbedded -> OdLabelStrategy.EMBEDDED_METADATA
                taskType == VisionTaskType.OBJECT_DETECTION && isLegacyCocoShape -> OdLabelStrategy.FALLBACK_COCO_80
                taskType == VisionTaskType.CLASSIFICATION && detectedClasses == 1000 -> OdLabelStrategy.FALLBACK_IMAGENET_1000
                else -> OdLabelStrategy.EXTERNAL_TXT // Vyžaduje soubor od uživatele
            }

            Log.i(TAG, "🚀 Zero-Guessing Analyzer: ${modelFile.name} -> Arch: $explicitArchitecture, Task: $taskType, Strategy: ${strategy.name}, Classes: $detectedClasses, Input: ${inWidth}x${inHeight}")

            VisionModelProfile(
                isValid = taskType != VisionTaskType.UNKNOWN,
                taskType = taskType,
                labelStrategy = strategy,
                inputWidth = inWidth,
                inputHeight = inHeight,
                classCount = detectedClasses,
                isQuantized = isQuantized,
                isNchw = isNchwFormat,
                explicitArchitecture = explicitArchitecture
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Fatální chyba při analýze modelu: ${modelFile.absolutePath}", e)
            VisionModelProfile(false, VisionTaskType.UNKNOWN, OdLabelStrategy.UNKNOWN, 0, 0, 0, false, false, null, e.message)
        } finally {
            // Kritické: Vždy uzavřít interpreter k uvolnění nativní C++ paměti
            interpreter?.close()
        }
    }

    /**
     * Rychlý byte-scan pro zjištění, zda hlavička obsahuje definici TFLITE_METADATA.
     * Minimalizuje nutnost implementovat celý FlatBuffers parser.
     */
    private fun hasEmbeddedMetadata(buffer: MappedByteBuffer): Boolean {
        buffer.position(0)
        val limit = Math.min(buffer.capacity(), 4096)
        val bytes = ByteArray(limit)
        buffer.get(bytes, 0, limit)
        val headerString = String(bytes, Charsets.US_ASCII)
        return headerString.contains("TFLITE_METADATA") || headerString.contains("TENSOR_AXIS_LABELS")
    }
}
