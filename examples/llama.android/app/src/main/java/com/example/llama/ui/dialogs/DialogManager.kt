package com.example.llama.ui.dialogs

import android.content.Context
import android.content.Intent
import com.example.llama.ChatHistoryManager
import com.example.llama.R
import com.example.llama.SettingsActivity
import com.example.llama.SettingsManager
import com.example.llama.data.AiModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Továrna na dialogová okna.
 * Odděluje UI logiku (vytváření dialogů) od Activity.
 */
object DialogManager {

    /**
     * Zobrazí výběr modelu (včetně možnosti přepnutí na Online Cloud API BYOK).
     * @param context Context aktivity.
     * @param models Seznam dostupných lokálních GGUF modelů.
     * @param onModelSelect Callback při výběru lokálního GGUF modelu.
     * @param onImportClick Callback pro tlačítko Import.
     * @param onDeleteClick Callback pro tlačítko Mazání (otevře další dialog).
     * @param onCloudApiSelect Volitelný callback při aktivaci Online Cloud API.
     */
    fun showModelSelector(
        context: Context,
        models: List<AiModel>,
        onModelSelect: (AiModel) -> Unit,
        onImportClick: () -> Unit,
        onDeleteClick: () -> Unit,
        onCloudApiSelect: (() -> Unit)? = null
    ) {
        val settings = SettingsManager(context)
        val hasGeminiKey = settings.geminiApiKey.isNotBlank()
        val isLocalOpenAiEndpoint = settings.cloudBaseUrl.contains("localhost") ||
            settings.cloudBaseUrl.contains("127.0.0.1") ||
            settings.cloudBaseUrl.contains("192.168.") ||
            settings.cloudBaseUrl.contains("10.")
        val hasOpenAiKeyOrLocal = settings.openAiApiKey.isNotBlank() || isLocalOpenAiEndpoint

        val cloudEntries = mutableListOf<Pair<String, () -> Unit>>()
        if (hasGeminiKey) {
            val activeTag = if (settings.aiProviderType == SettingsManager.AiProviderType.GOOGLE_GEMINI) " [AKTIVNÍ]" else ""
            val geminiModel = settings.getSelectedModelForProvider(SettingsManager.AiProviderType.GOOGLE_GEMINI).ifBlank { "automatický výběr" }
            cloudEntries.add("☁️ Google AI Studio: $geminiModel (0 MB RAM)$activeTag" to {
                settings.aiProviderType = SettingsManager.AiProviderType.GOOGLE_GEMINI
                onCloudApiSelect?.invoke()
            })
        }
        if (hasOpenAiKeyOrLocal) {
            val activeTag = if (settings.aiProviderType == SettingsManager.AiProviderType.OPENAI_COMPATIBLE) " [AKTIVNÍ]" else ""
            val openAiModel = settings.getSelectedModelForProvider(SettingsManager.AiProviderType.OPENAI_COMPATIBLE).ifBlank { "automatický výběr" }
            val transportBadge = if (isLocalOpenAiEndpoint) {
                if (settings.localConnectionTransport.equals("WIFI", ignoreCase = true)) "📶 Wi-Fi ${settings.localWifiHostIp}" else "🔌 ADB 127.0.0.1"
            } else {
                "☁️ Cloud"
            }
            cloudEntries.add("$transportBadge Studio/OpenAI API: $openAiModel (0 MB RAM)$activeTag" to {
                settings.aiProviderType = SettingsManager.AiProviderType.OPENAI_COMPATIBLE
                onCloudApiSelect?.invoke()
            })
        }
        if (!hasGeminiKey && !hasOpenAiKeyOrLocal) {
            cloudEntries.add("☁️ Nastavit Online Cloud API (Google AI Studio / OpenAI BYOK)..." to {
                val intent = Intent(context, SettingsActivity::class.java)
                context.startActivity(intent)
            })
        }

        val localEntries = models.map { model ->
            val sizeMb = model.fileSize / (1024 * 1024)
            val isVision = model.displayName.contains("mmproj", ignoreCase = true)
            val prefix = if (isVision) "[👁 VISION] " else "📱 "
            "$prefix${model.displayName} (${sizeMb} MB)"
        }

        val allLabels = (cloudEntries.map { it.first } + localEntries).toTypedArray()

        val builder = MaterialAlertDialogBuilder(context)
        builder.setTitle(R.string.dialog_select_model)

        builder.setItems(allLabels) { _, which ->
            if (which < cloudEntries.size) {
                cloudEntries[which].second.invoke()
            } else {
                val modelIdx = which - cloudEntries.size
                if (modelIdx in models.indices) {
                    onModelSelect(models[modelIdx])
                }
            }
        }

        builder.setPositiveButton(R.string.dialog_import_new) { _, _ ->
            onImportClick()
        }

        if (models.isNotEmpty()) {
            builder.setNeutralButton(R.string.dialog_delete_model) { _, _ ->
                onDeleteClick()
            }
        }

        builder.setNegativeButton(R.string.dialog_cancel, null)
        builder.show()
    }

    /**
     * Zobrazí seznam modelů pro smazání.
     */
    fun showDeleteModelConfirm(
        context: Context,
        models: List<AiModel>,
        onConfirmDelete: (AiModel) -> Unit
    ) {
        if (models.isEmpty()) return

        val modelNames = models.map { it.displayName }.toTypedArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_delete_model)
            .setItems(modelNames) { _, which ->
                onConfirmDelete(models[which])
            }
            .setNegativeButton(R.string.dialog_back, null)
            .show()
    }

    /**
     * Zobrazí výběr formátu pro export chatu.
     */
    fun showExportFormatSelector(
        context: Context,
        onFormatSelected: (ChatHistoryManager.ExportFormat) -> Unit
    ) {
        val formats = ChatHistoryManager.ExportFormat.entries
        val formatNames = formats.map { it.name }.toTypedArray()

        MaterialAlertDialogBuilder(context)
            .setTitle("Export Chat")
            .setItems(formatNames) { _, which ->
                onFormatSelected(formats[which])
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Zobrazí potvrzení smazání aktuální konverzace.
     */
    fun showDeleteChatConfirm(
        context: Context,
        onConfirm: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.msg_delete_conversation_title)
            .setMessage(R.string.msg_delete_conversation_body)
            .setPositiveButton(R.string.action_delete_confirm) { _, _ ->
                onConfirm()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
