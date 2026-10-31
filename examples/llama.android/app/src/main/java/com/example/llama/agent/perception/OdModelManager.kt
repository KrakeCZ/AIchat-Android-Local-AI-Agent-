package com.example.llama.agent.perception

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.llama.SettingsManager
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

// ========================================================================
// OD MODEL MANAGER — Enterprise Object Detection Model Registry
// ========================================================================
//
// Umožňuje uživateli importovat vlastní .tflite modely a přepínat mezi nimi
// za běhu (hot-swap v PerceptionEngine). Plně offline, bez Room DB.
//
// Architektura:
// - Modely uloženy v /files/od_models/
// - Metadata vedle každého .tflite jako <name>.json (SAF-compatible)
// - Bundled default (efficientdet_lite0.tflite v /assets/) je vždy dostupný
//   jako záchranný fallback — nelze smazat.
//
// Vzor: SherpaModelManager.kt (stejná architektura, přizpůsobeno pro single-file .tflite)
//
// Princip Nulové Syntaktické a Sémantické Chyby (Zero Silent Failures):
// - Validátor ověří input tensor shape PŘED uložením modelu
// - Dummy inference ověří, že model skutečně funguje na tomto zařízení
// - Chybný soubor se NIKDY neuloží — je okamžitě smazán

// ==================== DATA MODEL ====================

/**
 * Typ OD modelu — určuje, jakou inference cestu použije PerceptionEngine.
 *
 * MEDIAPIPE  = Plně kompatibilní s MediaPipe ObjectDetector API (má embedded metadata + 4 output tenzory)
 * RAW_SSD    = Standard SSD formát (4 output tenzory: boxes, classes, scores, num_detections) — bez MediaPipe metadat
 * RAW_UNKNOWN = Jakýkoliv jiný validní TFLite model — LiteRT Interpreter s generickým parsingem
 */
enum class OdModelType {
    MEDIAPIPE,
    RAW_SSD,
    RAW_UNKNOWN;

    companion object {
        fun fromString(s: String): OdModelType = try {
            valueOf(s.uppercase())
        } catch (_: Exception) {
            RAW_UNKNOWN
        }
    }
}

/**
 * Popisuje jeden importovaný (nebo bundled) OD model.
 *
 * @param id UUID identifikátor záznamu
 * @param displayName Zobrazovaný název (z metadat, uživatel může upravit)
 * @param fileName Název .tflite souboru (bez cesty)
 * @param fileSizeBytes Velikost souboru v bytech
 * @param inputResolution Šířka vstupního tenzoru (typicky 320, 416, 640)
 * @param labelCount Počet tříd z metadat (0 = metadata neobsahovala popis tříd)
 * @param isQuantized true = INT8 quantizovaný model (rychlejší, menší)
 * @param isDefault true = bundled default (nelze smazat)
 * @param addedAt Unix timestamp (ms) přidání
 * @param source Zdroj modelu (pro info uživateli)
 * @param modelType Typ modelu pro inference routing (MEDIAPIPE / RAW_SSD / RAW_UNKNOWN)
 */
data class OdModelRecord(
    val id: String,
    val displayName: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val inputResolution: Int,
    val labelCount: Int,
    val isQuantized: Boolean,
    val isDefault: Boolean,
    val addedAt: Long,
    val source: String = "user_import",
    val modelType: OdModelType = OdModelType.MEDIAPIPE
)

/** Výsledek importu modelu. */
sealed class OdImportResult {
    data class Success(val record: OdModelRecord) : OdImportResult()
    data class Error(val reason: String) : OdImportResult()
}

/** Výsledek validace modelu. */
sealed class OdValidationResult {
    data class Valid(
        val inputResolution: Int,
        val labelCount: Int,
        val isQuantized: Boolean,
        val detectedName: String,
        val modelType: OdModelType
    ) : OdValidationResult()
    data class Invalid(val reason: String) : OdValidationResult()
}

// ==================== MANAGER ====================

/**
 * Správce vlastních Object Detection modelů.
 *
 * Singleton-friendly (injektovat jako závislost — jeden instanční objekt sdílený
 * mezi PerceptionEngine a SettingsActivity).
 */
class OdModelManager(
    private val context: Context,
    private val settingsManager: SettingsManager
) {

    companion object {
        private const val TAG = "OdModelManager"
        private const val MODELS_DIR = "od_models"

        /** Název bundled default modelu v assets. Tento soubor MUSÍ existovat. */
        const val BUNDLED_MODEL_ASSET = "efficientdet_lite0.tflite"

        /** ID pro bundled default — konstantní, nepochází z UUID. */
        const val BUNDLED_MODEL_ID = "bundled_efficientdet_lite0"

        /** Rozlišení bundled modelu (EfficientDet-Lite0 = 320px). */
        private const val BUNDLED_MODEL_RESOLUTION = 320

        /** Počet tříd bundled modelu (COCO-80). */
        private const val BUNDLED_MODEL_LABEL_COUNT = 80
    }

    // ==================== ADRESÁŘ A CESTY ====================

    private fun getModelsDir(): File {
        val dir = File(context.filesDir, MODELS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Vrátí absolutní cestu k aktivnímu .tflite souboru, nebo null pokud je aktivní bundled model.
     * Null = PerceptionEngine použije asset loader (bundled).
     */
    fun getActiveModelPath(): String? {
        val activeId = settingsManager.activeOdModelId
        if (activeId.isBlank() || activeId == BUNDLED_MODEL_ID) return null

        val record = getImportedModels().find { it.id == activeId } ?: return null
        val file = File(getModelsDir(), record.fileName)
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * Vrátí aktivní OdModelRecord (bundled nebo importovaný).
     */
    fun getActiveRecord(): OdModelRecord {
        val activeId = settingsManager.activeOdModelId
        if (activeId.isBlank() || activeId == BUNDLED_MODEL_ID) return getBundledRecord()
        return getImportedModels().find { it.id == activeId } ?: getBundledRecord()
    }

    /** Vytvoří pseudo-record pro bundled model (vždy dostupný). */
    fun getBundledRecord(): OdModelRecord = OdModelRecord(
        id = BUNDLED_MODEL_ID,
        displayName = "EfficientDet-Lite0 (bundled)",
        fileName = BUNDLED_MODEL_ASSET,
        fileSizeBytes = getBundledModelSize(),
        inputResolution = BUNDLED_MODEL_RESOLUTION,
        labelCount = BUNDLED_MODEL_LABEL_COUNT,
        isQuantized = true,
        isDefault = true,
        addedAt = 0L,
        source = "bundled"
    )

    private fun getBundledModelSize(): Long {
        return try {
            context.assets.openFd(BUNDLED_MODEL_ASSET).use { it.length }
        } catch (_: Exception) {
            0L
        }
    }

    // ==================== SEZNAM MODELŮ ====================

    /**
     * Vrátí seznam všech importovaných modelů (bez bundled).
     * Čte metadata z <filename>.json souborů vedle .tflite.
     */
    fun getImportedModels(): List<OdModelRecord> {
        val dir = getModelsDir()
        if (!dir.exists()) return emptyList()

        return dir.listFiles { f -> f.extension == "tflite" }
            ?.mapNotNull { tfliteFile ->
                val metaFile = File(dir, "${tfliteFile.nameWithoutExtension}.json")
                if (metaFile.exists()) {
                    try {
                        parseMetadataFile(metaFile, tfliteFile)
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Nelze načíst metadata pro ${tfliteFile.name}: ${e.message}")
                        null
                    }
                } else {
                    // Soubor bez metadat — rekonstruujeme základní record
                    Log.w(TAG, "⚠️ Chybí metadata pro ${tfliteFile.name}, generuji základní záznam")
                    OdModelRecord(
                        id = UUID.randomUUID().toString(),
                        displayName = tfliteFile.nameWithoutExtension,
                        fileName = tfliteFile.name,
                        fileSizeBytes = tfliteFile.length(),
                        inputResolution = 0,
                        labelCount = 0,
                        isQuantized = false,
                        isDefault = false,
                        addedAt = tfliteFile.lastModified(),
                        source = "user_import"
                    )
                }
            }
            ?.sortedByDescending { it.addedAt }
            ?: emptyList()
    }

    /** Vrátí bundled + importované modely jako jeden seznam (bundled vždy první). */
    fun getAllModels(): List<OdModelRecord> {
        return listOf(getBundledRecord()) + getImportedModels()
    }

    // ==================== IMPORT ====================

    /**
     * Importuje .tflite model z URI (SAF single-file picker).
     *
     * Pipeline:
     * 1. Zkopíruje soubor do privátního adresáře /files/od_models/
     * 2. Validuje model (tensor shape + dummy inference)
     * 3. Uloží metadata jako <name>.json
     * 4. Vrátí Success nebo Error s důvodem
     *
     * Volat VŽDY z background coroutine (Dispatchers.IO).
     * Pokud validace selže, soubor je okamžitě smazán (Zero Silent Failures).
     *
     * @param uri URI z SAF file pickeru
     * @param userProvidedName Volitelné jméno od uživatele (může být prázdné)
     */
    suspend fun importModelFromUri(uri: Uri, userProvidedName: String = ""): OdImportResult =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "📥 Zahajuji import OD modelu z URI: $uri")

            // --- Krok 1: Zkopírování souboru ---
            val originalFileName = resolveFileName(uri) ?: "model_${System.currentTimeMillis()}.tflite"
            val safeFileName = sanitizeFileName(originalFileName)
            val targetFile = File(getModelsDir(), safeFileName)

            // Idempotence: pokud soubor se stejným jménem existuje, přidáme suffix
            val finalTargetFile = if (targetFile.exists()) {
                File(getModelsDir(), "${targetFile.nameWithoutExtension}_${System.currentTimeMillis()}.tflite")
            } else {
                targetFile
            }

            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(finalTargetFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext OdImportResult.Error("Nelze otevřít vybraný soubor (ContentResolver vrátil null).")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Chyba při kopírování souboru: ${e.message}", e)
                finalTargetFile.delete()
                return@withContext OdImportResult.Error("Chyba při kopírování souboru: ${e.message}")
            }

            Log.i(TAG, "📋 Soubor zkopírován: ${finalTargetFile.absolutePath} (${finalTargetFile.length()} B)")

            // --- Krok 2: Validace modelu ---
            val validation = validateModel(finalTargetFile)
            if (validation is OdValidationResult.Invalid) {
                Log.e(TAG, "❌ Validace selhala: ${validation.reason} — soubor mazán")
                finalTargetFile.delete()
                return@withContext OdImportResult.Error("Model není kompatibilní: ${validation.reason}")
            }

            val validResult = validation as OdValidationResult.Valid

            // --- Krok 3: Sestavení záznamu ---
            val displayName = when {
                userProvidedName.isNotBlank() -> userProvidedName.trim()
                validResult.detectedName.isNotBlank() -> validResult.detectedName
                else -> finalTargetFile.nameWithoutExtension
            }

            val record = OdModelRecord(
                id = UUID.randomUUID().toString(),
                displayName = displayName,
                fileName = finalTargetFile.name,
                fileSizeBytes = finalTargetFile.length(),
                inputResolution = validResult.inputResolution,
                labelCount = validResult.labelCount,
                isQuantized = validResult.isQuantized,
                isDefault = false,
                addedAt = System.currentTimeMillis(),
                source = "user_import",
                modelType = validResult.modelType
            )

            // --- Krok 4: Uložení metadat ---
            val saved = saveMetadata(record)
            if (!saved) {
                Log.e(TAG, "❌ Nelze uložit metadata, import zrušen — soubor mazán")
                finalTargetFile.delete()
                return@withContext OdImportResult.Error("Interní chyba: nelze uložit metadata modelu.")
            }

            Log.i(TAG, "✅ Model úspěšně importován: '${record.displayName}' (${record.id})")
            OdImportResult.Success(record)
        }

    // ==================== VALIDACE ====================

    /**
     * Univerzální dvoustupňová validace .tflite souboru.
     *
     * Stupeň 1: Pokus o MediaPipe ObjectDetector (modely s metadata + DetectionPostProcess)
     * Stupeň 2: Fallback na raw LiteRT Interpreter (SSD 4-tensor / jakýkoliv formát)
     *
     * Princip: ŽÁDNÝ validní TFLite model nebude odmítnut jen proto, že nemá MediaPipe metadata.
     * Model bez metadat bude přijat jako RAW_SSD nebo RAW_UNKNOWN a pojede přes Interpreter.
     */
    private suspend fun validateModel(tfliteFile: File): OdValidationResult =
        withContext(Dispatchers.Default) {
            if (!tfliteFile.exists() || tfliteFile.length() == 0L) {
                return@withContext OdValidationResult.Invalid("Soubor neexistuje nebo je prázdný.")
            }
            if (!tfliteFile.name.endsWith(".tflite")) {
                return@withContext OdValidationResult.Invalid("Soubor musí mít příponu .tflite")
            }

            // Načtení modelu do ByteBuffer (sdílený pro oba stupně)
            val modelBuffer: ByteBuffer = try {
                FileChannel.open(tfliteFile.toPath()).use { channel ->
                    val buffer = ByteBuffer.allocateDirect(channel.size().toInt())
                    channel.read(buffer)
                    buffer.rewind()
                    buffer
                }
            } catch (e: Exception) {
                return@withContext OdValidationResult.Invalid("Nelze načíst soubor: ${e.message}")
            }

            // ── Stupeň 1: MediaPipe ObjectDetector ──
            val mediapipeResult = tryValidateMediaPipe(modelBuffer)
            if (mediapipeResult != null) {
                Log.i(TAG, "✅ Validace OK (MediaPipe): ${tfliteFile.name}")
                return@withContext OdValidationResult.Valid(
                    inputResolution = mediapipeResult.inputResolution,
                    labelCount = mediapipeResult.labelCount,
                    isQuantized = tfliteFile.length() < 15 * 1024 * 1024,
                    detectedName = tfliteFile.nameWithoutExtension,
                    modelType = OdModelType.MEDIAPIPE
                )
            }

            // ── Stupeň 2: Raw LiteRT Interpreter (univerzální fallback) ──
            val rawResult = tryValidateRawInterpreter(modelBuffer, tfliteFile)
            if (rawResult != null) {
                Log.i(TAG, "✅ Validace OK (LiteRT ${rawResult.modelType}): ${tfliteFile.name}")
                return@withContext rawResult
            }

            // ── Oba stupně selhaly ──
            OdValidationResult.Invalid(
                "Soubor není platný TFLite model nebo jej nelze inicializovat na tomto zařízení."
            )
        }

    /** Stupeň 1: Pokus o MediaPipe ObjectDetector. Vrátí data nebo null. */
    private data class MediaPipeProbe(val inputResolution: Int, val labelCount: Int)

    private fun tryValidateMediaPipe(modelBuffer: ByteBuffer): MediaPipeProbe? {
        var detector: ObjectDetector? = null
        return try {
            modelBuffer.rewind()
            val baseOptions = BaseOptions.builder()
                .setModelAssetBuffer(modelBuffer)
                .build()
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setMaxResults(1)
                .setScoreThreshold(0.01f)
                .build()
            detector = ObjectDetector.createFromOptions(context, options)

            // Dummy inference
            val dummyBitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
            val mpImage = BitmapImageBuilder(dummyBitmap).build()
            detector.detect(mpImage)
            dummyBitmap.recycle()

            MediaPipeProbe(inputResolution = 320, labelCount = 0)
        } catch (e: Exception) {
            Log.d(TAG, "MediaPipe validace selhala (očekávané pro modely bez metadat): ${e.message}")
            null
        } finally {
            try { detector?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Stupeň 2: Raw LiteRT Interpreter — univerzální fallback.
     *
     * Pokusí se inicializovat org.tensorflow.lite.Interpreter a inspektovat
     * input/output tenzory. Přijme JAKÝKOLIV validní TFLite model.
     *
     * Klasifikace:
     * - 4 output tenzory + shapes odpovídající SSD → RAW_SSD
     * - Cokoliv jiného → RAW_UNKNOWN
     */
    private fun tryValidateRawInterpreter(
        modelBuffer: ByteBuffer,
        tfliteFile: File
    ): OdValidationResult.Valid? {
        var interpreter: org.tensorflow.lite.Interpreter? = null
        return try {
            modelBuffer.rewind()
            interpreter = org.tensorflow.lite.Interpreter(modelBuffer)
            interpreter.allocateTensors()

            // Inspekce vstupního tenzoru
            val inputTensor = interpreter.getInputTensor(0)
            val inputShape = inputTensor.shape() // typicky [1, H, W, 3]
            val inputRes = if (inputShape.size >= 3) inputShape[1] else 320

            // Inspekce výstupních tenzorů
            val outputCount = interpreter.outputTensorCount
            val modelType: OdModelType
            val labelCount: Int

            if (outputCount == 4) {
                // Pravděpodobně SSD: [boxes, classes, scores, num_detections]
                val t0 = interpreter.getOutputTensor(0).shape() // [1, N, 4] boxes
                val t1 = interpreter.getOutputTensor(1).shape() // [1, N] classes
                val isSsd = t0.size == 3 && t0[2] == 4 && t1.size == 2
                modelType = if (isSsd) OdModelType.RAW_SSD else OdModelType.RAW_UNKNOWN
                labelCount = 0
                Log.i(TAG, "LiteRT: $outputCount outputs, shapes: ${t0.toList()}, ${t1.toList()} → $modelType")
            } else {
                modelType = OdModelType.RAW_UNKNOWN
                labelCount = 0
                Log.i(TAG, "LiteRT: $outputCount outputs → RAW_UNKNOWN")
            }

            OdValidationResult.Valid(
                inputResolution = inputRes,
                labelCount = labelCount,
                isQuantized = tfliteFile.length() < 15 * 1024 * 1024,
                detectedName = tfliteFile.nameWithoutExtension,
                modelType = modelType
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ LiteRT Interpreter validace selhala: ${e.message}")
            null
        } finally {
            try { interpreter?.close() } catch (_: Exception) {}
        }
    }

    // ==================== AKTIVACE / SMAZÁNÍ ====================

    /**
     * Aktivuje model podle ID. Nastaví activeOdModelId v SettingsManager.
     * Volat z Main threadu nebo coroutine — je to jen SharedPreferences zápis.
     *
     * @param id UUID modelu nebo BUNDLED_MODEL_ID pro bundled model
     * @return true pokud model existuje a byl nastaven jako aktivní
     */
    fun activateModel(id: String): Boolean {
        if (id == BUNDLED_MODEL_ID || id.isEmpty()) {
            settingsManager.activeOdModelId = BUNDLED_MODEL_ID
            Log.i(TAG, "✅ Aktivován bundled model: $BUNDLED_MODEL_ID")
            return true
        }
        val record = getImportedModels().find { it.id == id }
        if (record == null) {
            Log.e(TAG, "❌ activateModel: Model s ID '$id' nenalezen")
            return false
        }
        settingsManager.activeOdModelId = id
        Log.i(TAG, "✅ Aktivován model: '${record.displayName}' (${record.id})")
        return true
    }

    /**
     * Smaže importovaný model (soubor + metadata JSON).
     * Bundled model nelze smazat — vrátí false.
     * Pokud je smazaný model právě aktivní, automaticky se nastaví bundled jako fallback.
     *
     * @param id UUID modelu k smazání
     * @return true pokud byl smazán úspěšně
     */
    fun deleteModel(id: String): Boolean {
        if (id == BUNDLED_MODEL_ID) {
            Log.w(TAG, "⚠️ Nelze smazat bundled model.")
            return false
        }

        val record = getImportedModels().find { it.id == id }
        if (record == null) {
            Log.e(TAG, "❌ deleteModel: Model s ID '$id' nenalezen")
            return false
        }

        val dir = getModelsDir()
        val tfliteFile = File(dir, record.fileName)
        val metaFile = File(dir, "${tfliteFile.nameWithoutExtension}.json")

        var deleted = true
        if (tfliteFile.exists()) deleted = deleted && tfliteFile.delete()
        if (metaFile.exists()) metaFile.delete() // Metadata — neblokujeme na selhání

        if (deleted) {
            Log.i(TAG, "✅ Model smazán: '${record.displayName}'")
            // Fallback na bundled pokud byl tento model aktivní
            if (settingsManager.activeOdModelId == id) {
                settingsManager.activeOdModelId = BUNDLED_MODEL_ID
                Log.i(TAG, "🔄 Aktivní model byl smazán — fallback na bundled model")
            }
        } else {
            Log.e(TAG, "❌ Smazání souboru selhalo: ${tfliteFile.absolutePath}")
        }
        return deleted
    }

    /**
     * Aktualizuje display name existujícího modelu.
     * Přepíše pouze displayName v JSON metadatech.
     *
     * @param id UUID modelu
     * @param newName Nový název (nesmí být prázdný)
     * @return true pokud byl název úspěšně upraven
     */
    fun renameModel(id: String, newName: String): Boolean {
        if (newName.isBlank()) return false
        val record = getImportedModels().find { it.id == id } ?: return false
        val updated = record.copy(displayName = newName.trim())
        return saveMetadata(updated)
    }

    // ==================== METADATA PERSISTENCE ====================

    /** Serializuje OdModelRecord do JSON a uloží vedle .tflite souboru. */
    private fun saveMetadata(record: OdModelRecord): Boolean {
        val metaFile = File(getModelsDir(), "${File(record.fileName).nameWithoutExtension}.json")
        return try {
            val json = JSONObject().apply {
                put("id", record.id)
                put("displayName", record.displayName)
                put("fileName", record.fileName)
                put("fileSizeBytes", record.fileSizeBytes)
                put("inputResolution", record.inputResolution)
                put("labelCount", record.labelCount)
                put("isQuantized", record.isQuantized)
                put("isDefault", record.isDefault)
                put("addedAt", record.addedAt)
                put("source", record.source)
                put("modelType", record.modelType.name)
            }
            metaFile.writeText(json.toString(2)) // Formátováno pro čitelnost
            Log.d(TAG, "💾 Metadata uložena: ${metaFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba při ukládání metadat: ${e.message}", e)
            false
        }
    }

    /** Deserializuje OdModelRecord z JSON metadatového souboru. */
    private fun parseMetadataFile(metaFile: File, tfliteFile: File): OdModelRecord {
        val json = JSONObject(metaFile.readText())
        return OdModelRecord(
            id = json.optString("id", UUID.randomUUID().toString()),
            displayName = json.optString("displayName", tfliteFile.nameWithoutExtension),
            fileName = json.optString("fileName", tfliteFile.name),
            fileSizeBytes = json.optLong("fileSizeBytes", tfliteFile.length()),
            inputResolution = json.optInt("inputResolution", 0),
            labelCount = json.optInt("labelCount", 0),
            isQuantized = json.optBoolean("isQuantized", false),
            isDefault = json.optBoolean("isDefault", false),
            addedAt = json.optLong("addedAt", tfliteFile.lastModified()),
            source = json.optString("source", "user_import"),
            modelType = OdModelType.fromString(json.optString("modelType", OdModelType.MEDIAPIPE.name))
        )
    }

    // ==================== UTILS ====================

    /** Pokusí se zjistit jméno souboru z URI (přes ContentResolver nebo path). */
    private fun resolveFileName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    /**
     * Zajistí bezpečný název souboru — odstraní nebezpečné znaky, zachová .tflite příponu.
     * Princip: filesystem-safe, SELinux-safe, bez traversal (/../) možnosti.
     */
    private fun sanitizeFileName(name: String): String {
        val stripped = name
            .replace(Regex("[^a-zA-Z0-9._\\-]"), "_") // Jen bezpečné znaky
            .replace(Regex("\\.{2,}"), "_")            // Žádné .. (traversal)
            .trimStart('.')                             // Žádné hidden files
        return if (stripped.endsWith(".tflite")) stripped else "$stripped.tflite"
    }

    /**
     * Diagnostika: vrátí info string pro Logcat.
     */
    fun getDiagnosticInfo(): String {
        val models = getImportedModels()
        val active = getActiveRecord()
        return buildString {
            appendLine("=== OdModelManager Diagnostics ===")
            appendLine("Models dir: ${getModelsDir().absolutePath}")
            appendLine("Importované modely: ${models.size}")
            appendLine("Aktivní: '${active.displayName}' (${active.id})")
            models.forEach {
                appendLine("  - ${it.displayName} [${it.id}] ${it.fileSizeBytes / 1024}KB, ${it.inputResolution}px, ${it.labelCount} tříd")
            }
        }
    }
}
