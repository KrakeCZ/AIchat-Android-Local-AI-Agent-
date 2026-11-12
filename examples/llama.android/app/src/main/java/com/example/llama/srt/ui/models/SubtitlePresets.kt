package com.example.llama.srt.ui.models

/**
 * Factory pro výchozí SubtitleStyleConfig presety.
 * Deleguje na SubtitleStyleRegistry pro konzistenci.
 *
 * Použití: SubtitlePresets.getPreset(SubtitlePresetId.TIKTOK_WORD_POP)
 */
object SubtitlePresets {

    /**
     * Vrátí [SubtitleStyleConfig] pro zadaný preset.
     * Pokud preset neexistuje, vrátí TIKTOK_WORD_POP jako fallback.
     */
    fun getPreset(presetId: SubtitlePresetId): SubtitleStyleConfig {
        val all = SubtitleStyleRegistry.all()
        return all.firstOrNull { it.presetId == presetId }
            ?: all.firstOrNull()
            ?: SubtitleStyleConfig() // Ultimátní fallback
    }
}
