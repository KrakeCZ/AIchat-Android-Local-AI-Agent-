package com.example.llama

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arm.aichat.gguf.GgufMetadataReader
import com.example.llama.data.AiModel
import com.example.llama.data.ModelDetailState
import com.example.llama.data.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ModelRepository(application)
    private val settingsManager = SettingsManager(application) // Ensure we use SettingsManager here

    // Create reader that keeps the chat template (by removing it from skip keys)
    private val ggufReader = GgufMetadataReader.create(
        skipKeys = setOf(
            // We want the chat template, so we DON'T skip "tokenizer.chat_template"
            "tokenizer.ggml.scores",
            "tokenizer.ggml.tokens",
            "tokenizer.ggml.token_type"
        )
    )

    
    // Agent State
    private val _isAgentEnabled = MutableStateFlow(false)
    val isAgentEnabled: StateFlow<Boolean> = _isAgentEnabled.asStateFlow()

    init {
        // Init state from settings
        _isAgentEnabled.value = settingsManager.isAgentEnabled
    }

    // Seznam modelů přímo z DB, transformovaný na StateFlow pro UI
    val savedModels: StateFlow<List<AiModel>> = repository.allModels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _modelStatus = MutableStateFlow("Select a model")
    val modelStatus: StateFlow<String> = _modelStatus.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    


    // Event for the Model Info Dialog (SharedFlow handles one-off events better than StateFlow)
    private val _modelDetail = MutableSharedFlow<ModelDetailState>()
    val modelDetail: SharedFlow<ModelDetailState> = _modelDetail.asSharedFlow()

    companion object {
        private const val TAG = "MainViewModel"
    }

    /**
     * Importuje nový model (kopíruje) a následně připraví cestu pro načtení.
     */
    fun importAndLoadModel(uri: Uri, onPathReady: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _modelStatus.value = "Importing model to internal storage...\nThis may take a while."

                // 1. Import (Copy) + DB Insert
                val modelId = repository.importNewModel(uri)

                // 2. Load logic
                val dbModel = repository.getModel(modelId)
                if (dbModel != null && dbModel.displayName.contains("mmproj", ignoreCase = true)) {
                    _modelStatus.value = "Vision projektor \uD83D\uDC41\uFE0F importován.\nNyní jej můžete zvolit v Nastavení -> SmartVision."
                    _isLoading.value = false
                } else {
                    loadLocalModel(modelId, onPathReady, onError)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error importing model", e)
                _modelStatus.value = "Import Error: ${e.localizedMessage}"
                onError(e.localizedMessage ?: "Unknown error")
                _isLoading.value = false
            }
        }
    }

    /**
     * Načte již existující model z DB/Disku.
     */
    fun loadLocalModel(modelId: String, onPathReady: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
             try {
                _isLoading.value = true
                _modelStatus.value = "Verifying model file..."

                // Get Path from DB (it is now a local path)
                val nativePath = repository.prepareModelPathForNative(modelId)

                // Diagnostic
                val checkFile = File(nativePath)
                val exists = checkFile.exists()
                val canRead = checkFile.canRead()
                _modelStatus.value = "Path: $nativePath\nExists: $exists, Read: $canRead"

                onPathReady(nativePath)

            } catch (e: Exception) {
                Log.e(TAG, "Error loading local model", e)
                _modelStatus.value = "Load Error: ${e.localizedMessage}"
                onError(e.localizedMessage ?: "Unknown error")
                _isLoading.value = false
            }
        }
    }

    fun deleteModel(model: AiModel) {
        viewModelScope.launch {
            try {
                repository.deleteModel(model)
                // Pokud smažeme aktuálně vybraný model, měli bychom resetovat status,
                // ale pro jednoduchost to necháme, engine to zvládne (soubor už má otevřený nebo je unloadnutý).
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete model", e)
            }
        }
    }

    /**
     * Loads detailed metadata for the specified model and opens the dialog.
     */
    fun showModelInfo(modelId: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val dbModel = repository.getModel(modelId)

                if (dbModel == null) {
                    _modelStatus.value = "Error: Model not found in DB"
                    _isLoading.value = false
                    return@launch
                }

                // Parse GGUF metadata in background
                val detailState = withContext(Dispatchers.IO) {
                    val file = File(dbModel.sourceUri)
                    if (!file.exists()) {
                         throw java.io.FileNotFoundException("File not found: ${dbModel.sourceUri}")
                    }

                    // Read GGUF header
                    val metadata = FileInputStream(file).use { stream ->
                        ggufReader.readStructuredMetadata(stream)
                    }

                    // Convert bytes to human readable size
                    val sizeInGb = dbModel.fileSize.toDouble() / (1024 * 1024 * 1024)
                    val formattedSize = "%.2f GB".format(sizeInGb)

                    // Helper for param count (e.g. 7B) - rough estimate if not in metadata
                    // Using safe calls (?.) and elvis operator (?:) for robust handling

                    ModelDetailState(
                        name = dbModel.displayName,
                        filePath = dbModel.sourceUri,
                        contextSize = metadata.dimensions?.contextLength?.toLong() ?: 0L,
                        modelSize = formattedSize,
                        parameters = metadata.basic.sizeLabel ?: "N/A",
                        embeddingSize = metadata.dimensions?.embeddingSize?.toLong() ?: 0L,
                        vocabSize = metadata.architecture?.vocabSize?.toLong() ?: 0L,
                        quantization = metadata.architecture?.fileType?.toString() ?: "Unknown",
                        chatTemplate = metadata.tokenizer?.chatTemplate ?: "",
                        architecture = metadata.architecture?.architecture ?: "Unknown"
                    )
                }

                // Emit event to open dialog
                _modelDetail.emit(detailState)

            } catch (e: Exception) {
                Log.e(TAG, "Error loading model info", e)
                _modelStatus.value = "Info Error: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    
    fun toggleAgentMode() {
        val newState = !settingsManager.isAgentEnabled
        settingsManager.isAgentEnabled = newState
        _isAgentEnabled.value = newState
        
        // Notify Service (if running) via Intent is handled by MainActivity observing this or we can trigger it here if context available.
        // But ViewModel shouldn't depend on Context for starting services if possible, or use the Application context.
        // Ideally, MainActivity observes this and updates the service.
    }
}
