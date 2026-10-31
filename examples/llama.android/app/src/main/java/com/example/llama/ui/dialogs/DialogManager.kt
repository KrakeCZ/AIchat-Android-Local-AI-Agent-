package com.example.llama.ui.dialogs

import android.content.Context

import com.example.llama.ChatHistoryManager
import com.example.llama.R
import com.example.llama.data.AiModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Továrna na dialogová okna.
 * Odděluje UI logiku (vytváření dialogů) od Activity.
 */
object DialogManager {

    /**
     * Zobrazí výběr modelu.
     * @param context Context aktivity.
     * @param models Seznam dostupných modelů.
     * @param onModelSelect Callback při výběru modelu.
     * @param onImportClick Callback pro tlačítko Import.
     * @param onDeleteClick Callback pro tlačítko Mazání (otevře další dialog).
     */
    fun showModelSelector(
        context: Context,
        models: List<AiModel>,
        onModelSelect: (AiModel) -> Unit,
        onImportClick: () -> Unit,
        onDeleteClick: () -> Unit
    ) {
        val builder = MaterialAlertDialogBuilder(context)
        builder.setTitle(R.string.dialog_select_model)

        if (models.isEmpty()) {
            builder.setMessage(R.string.dialog_no_models)
        } else {
            // Formátování seznamu: "Název (Velikost MB)"
            val modelNames = models.map { model ->
                val sizeMb = model.fileSize / (1024 * 1024)
                val isVision = model.displayName.contains("mmproj", ignoreCase = true)
                val prefix = if (isVision) "[👁 VISION] " else ""
                "$prefix${model.displayName} (${sizeMb} MB)"
            }.toTypedArray()

            builder.setItems(modelNames) { _, which ->
                onModelSelect(models[which])
            }
        }

        builder.setPositiveButton(R.string.dialog_import_new) { _, _ ->
            onImportClick()
        }

        // Tlačítko pro mazání (Neutral) - zobrazí se jen pokud jsou modely
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
            .setNegativeButton(R.string.dialog_back, null) // "Back" vrátí uživatele zpět (tlačítko nic nedělá, dialog zmizí)
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
            .setTitle("Export Chat") // String resource by byl lepší, ale pro zachování kompatibility hardcoded
            .setItems(formatNames) { _, which ->
                onFormatSelected(formats[which])
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Zobrazí potvrzení smazání aktuální konverzace.
     * (Tuto funkci volal ChatSessionManager, ale můžeme ji mít centralizovanou zde).
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
