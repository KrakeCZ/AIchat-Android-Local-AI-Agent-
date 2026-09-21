package com.example.llama.agent.ui.studio.genstudio.importer

import android.content.Context
import android.util.Log
import com.example.llama.agent.ui.studio.genstudio.engine.GenArchitectureRegistry
import com.example.llama.data.gen.GenDatabase
import com.example.llama.data.gen.GenModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * Inteligentní a autonomní lokální manažer pro propojování modelových subsítí (100% offline).
 * Automaticky detekuje chybějící VAE / Textové enkodéry v DiT modelech a bez zásahu uživatele
 * je spáruje s kompatibilními importovanými subsítěmi v Room DB, nebo aktivuje univerzální vestavěnou odolnost.
 */
class GenModelPairingManager(
    private val context: Context,
    private val database: GenDatabase
) {
    companion object {
        private const val TAG = "GenModelPairingManager"
    }

    enum class ComponentType {
        TEXT_ENCODER,
        VAE_DECODER
    }

    sealed class SubsystemState {
        data class ExternalPaired(val path: String, val displayName: String) : SubsystemState()
        data class UniversalBuiltIn(val shapeInfo: String) : SubsystemState()
    }

    data class ModelPairingProfile(
        val model: GenModel,
        val textEncoderState: SubsystemState,
        val vaeState: SubsystemState,
        val isAutonomousPairingActive: Boolean
    )

    /**
     * Provede komplexní autonomní kontrolu všech modelů v Room DB.
     * Pokud DiT model nemá externí VAE nebo Textový enkodér, pokusí se jej autonomně
     * spárovat s jiným modelem z databáze.
     */
    suspend fun autoPairAllModels(): Int = withContext(Dispatchers.IO) {
        val allModels = database.genModelDao().getAllModels().firstOrNull() ?: return@withContext 0
        if (allModels.isEmpty()) return@withContext 0

        val vaeModels = allModels.filter { isVaeFile(it.displayName, it.filePath) }
        val textEncoders = allModels.filter { isTextEncoderFile(it.displayName, it.filePath) }

        var pairedCount = 0

        for (model in allModels) {
            val isDiT = !isVaeFile(model.displayName, model.filePath) && !isTextEncoderFile(model.displayName, model.filePath)
            if (!isDiT) continue

            val spec = GenArchitectureRegistry.detectSpec(model.displayName)
            val modelNameLower = model.displayName.lowercase()
            val isWanModel = modelNameLower.contains("wan") || modelNameLower.contains("t2v") || modelNameLower.contains("i2v")
            val isFluxModel = modelNameLower.contains("flux")
            val isSd3Model = modelNameLower.contains("sd3") || modelNameLower.contains("sd-3")

            // Autonomní spárování VAE podle kompatibility rodiny
            if (model.vaePath == null && vaeModels.isNotEmpty()) {
                val candidate = vaeModels.firstOrNull { v ->
                    val vd = v.displayName.lowercase()
                    val vf = v.filePath.lowercase()
                    when {
                        isWanModel -> vd.contains("wan") || vf.contains("wan")
                        isFluxModel -> vd.contains("flux") || vf.contains("flux") || vd.contains("ae.safetensors") || vf.contains("ae.safetensors")
                        isSd3Model -> vd.contains("sd3") || vf.contains("sd3")
                        else -> true
                    }
                } ?: vaeModels.firstOrNull { it.id != model.id }

                if (candidate != null) {
                    database.genModelDao().updateVaePath(model.id, candidate.filePath)
                    Log.i(TAG, "🔗 Autonomní propojení VAE (${spec.vaeFamily}): '${candidate.displayName}' -> '${model.displayName}'")
                    pairedCount++
                }
            }

            // Autonomní spárování Textového enkodéru podle kompatibility rodiny
            if (model.textEncoderPath == null && textEncoders.isNotEmpty()) {
                val candidate = textEncoders.firstOrNull { t ->
                    val td = t.displayName.lowercase()
                    val tf = t.filePath.lowercase()
                    when {
                        isWanModel -> td.contains("umt5") || tf.contains("umt5")
                        isFluxModel -> td.contains("t5") || tf.contains("t5") || td.contains("clip") || tf.contains("clip")
                        isSd3Model -> td.contains("t5") || tf.contains("t5") || td.contains("clip") || tf.contains("clip")
                        else -> true
                    }
                } ?: textEncoders.firstOrNull { it.id != model.id }

                if (candidate != null) {
                    database.genModelDao().updateTextEncoderPath(model.id, candidate.filePath)
                    Log.i(TAG, "🔗 Autonomní propojení Text Enkodéru (${spec.textEncoderFamily}): '${candidate.displayName}' -> '${model.displayName}'")
                    pairedCount++
                }
            }
        }

        Log.i(TAG, "✅ Dokončeno autonomní párování: Aktualizováno $pairedCount vazeb.")
        pairedCount
    }

    /**
     * Získá profil párování pro konkrétní model a detailní stav subsítí.
     */
    fun getPairingProfile(model: GenModel): ModelPairingProfile {
        val spec = GenArchitectureRegistry.detectSpec(model.displayName)

        val textState = if (!model.textEncoderPath.isNullOrBlank()) {
            val name = model.textEncoderPath.substringAfterLast("/").substringAfterLast("%2F")
            SubsystemState.ExternalPaired(model.textEncoderPath, name)
        } else {
            SubsystemState.UniversalBuiltIn("Vestavěný univerzální enkodér ${spec.expectedTextEmbeddingShape}")
        }

        val vaeState = if (!model.vaePath.isNullOrBlank()) {
            val name = model.vaePath.substringAfterLast("/").substringAfterLast("%2F")
            SubsystemState.ExternalPaired(model.vaePath, name)
        } else {
            SubsystemState.UniversalBuiltIn("Vestavěný univerzální 3D-VAE (${spec.latentChannels}ch Latent)")
        }

        return ModelPairingProfile(
            model = model,
            textEncoderState = textState,
            vaeState = vaeState,
            isAutonomousPairingActive = model.textEncoderPath == null || model.vaePath == null
        )
    }

    suspend fun pairExplicitly(modelId: String, type: ComponentType, path: String) = withContext(Dispatchers.IO) {
        when (type) {
            ComponentType.TEXT_ENCODER -> database.genModelDao().updateTextEncoderPath(modelId, path)
            ComponentType.VAE_DECODER -> database.genModelDao().updateVaePath(modelId, path)
        }
    }

    suspend fun resetToUniversal(modelId: String, type: ComponentType) = withContext(Dispatchers.IO) {
        when (type) {
            ComponentType.TEXT_ENCODER -> database.genModelDao().updateTextEncoderPath(modelId, null)
            ComponentType.VAE_DECODER -> database.genModelDao().updateVaePath(modelId, null)
        }
    }

    private fun isVaeFile(displayName: String, filePath: String): Boolean {
        val d = displayName.lowercase()
        val f = filePath.lowercase()
        return d.contains("vae") || f.contains("vae")
    }

    private fun isTextEncoderFile(displayName: String, filePath: String): Boolean {
        val d = displayName.lowercase()
        val f = filePath.lowercase()
        return d.contains("umt5") || d.contains("clip") || (d.contains("t5") && !d.contains("t2v") && !d.contains("i2v")) ||
                f.contains("umt5") || f.contains("clip") || (f.contains("t5") && !f.contains("t2v") && !f.contains("i2v"))
    }
}
