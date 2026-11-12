package com.example.llama.agent.ui.studio.genstudio.engine

import com.example.llama.data.gen.GenModel

/**
 * Specifikace a hardwarové kontrakty tenzorů pro jednotlivé generativní video architektury.
 * Zajišťuje 100% offline systémovou integritu, správné párování a přesné tvary latentů.
 */
data class ArchitectureSpec(
    val id: String,
    val displayName: String,
    val expectedTextEmbeddingShape: String,
    val seqLength: Int,
    val embeddingDim: Int,
    val expectedLatentShape: String,
    val latentChannels: Int,
    val spatialDownsample: Int,
    val temporalDownsample: Int,
    val textEncoderFamily: String,
    val vaeFamily: String,
    val defaultFps: Int = 16,
    val description: String
)

object GenArchitectureRegistry {

    val WAN_2_1_1_3B = ArchitectureSpec(
        id = "WAN_2_1_1_3B",
        displayName = "Wan 2.1 (1.3B DiT)",
        expectedTextEmbeddingShape = "[1, 512, 1536]",
        seqLength = 512,
        embeddingDim = 1536,
        expectedLatentShape = "[1, 16, F/4, H/8, W/8]",
        latentChannels = 16,
        spatialDownsample = 8,
        temporalDownsample = 4,
        textEncoderFamily = "UMT5-XXL",
        vaeFamily = "Wan 2.1 Causal 3D-VAE (16ch)",
        defaultFps = 16,
        description = "Flow-Matching DiT od Alibaba s 30 bloky a Cross-Attention dimenzí 1536."
    )

    val WAN_2_1_14B = ArchitectureSpec(
        id = "WAN_2_1_14B",
        displayName = "Wan 2.1 (14B DiT)",
        expectedTextEmbeddingShape = "[1, 512, 4096]",
        seqLength = 512,
        embeddingDim = 4096,
        expectedLatentShape = "[1, 16, F/4, H/8, W/8]",
        latentChannels = 16,
        spatialDownsample = 8,
        temporalDownsample = 4,
        textEncoderFamily = "UMT5-XXL",
        vaeFamily = "Wan 2.1 Causal 3D-VAE (16ch)",
        defaultFps = 16,
        description = "Velký 14B Flow-Matching DiT model s 40 bloky a dimenzí 4096."
    )

    val LTX_VIDEO = ArchitectureSpec(
        id = "LTX_VIDEO",
        displayName = "LTX-Video (2B Lightricks)",
        expectedTextEmbeddingShape = "[1, 128, 4096]",
        seqLength = 128,
        embeddingDim = 4096,
        expectedLatentShape = "[1, 128, F/8, H/32, W/32]",
        latentChannels = 128,
        spatialDownsample = 32,
        temporalDownsample = 8,
        textEncoderFamily = "T5-v1.1-XXL",
        vaeFamily = "LTX Causal 3D-VAE (Pixel-Shuffled 128ch)",
        defaultFps = 24,
        description = "Ultra-rychlý realtime DiT se silnou prostorově-časovou kompresí."
    )

    val HUNYUAN_VIDEO = ArchitectureSpec(
        id = "HUNYUAN_VIDEO",
        displayName = "HunyuanVideo (13B Tencent)",
        expectedTextEmbeddingShape = "[1, 226, 4096]",
        seqLength = 226,
        embeddingDim = 4096,
        expectedLatentShape = "[1, 16, F/4, H/8, W/8]",
        latentChannels = 16,
        spatialDownsample = 8,
        temporalDownsample = 4,
        textEncoderFamily = "Dual (LLaMA-3 + CLIP-L)",
        vaeFamily = "Hunyuan 3D-VAE (16ch)",
        defaultFps = 24,
        description = "Duální textový enkodér s nativním 720p rozlišením."
    )

    val COGVIDEO_X = ArchitectureSpec(
        id = "COGVIDEO_X",
        displayName = "CogVideoX (2B/5B THUDM)",
        expectedTextEmbeddingShape = "[1, 226, 4096]",
        seqLength = 226,
        embeddingDim = 4096,
        expectedLatentShape = "[1, 16, F/4, H/8, W/8]",
        latentChannels = 16,
        spatialDownsample = 8,
        temporalDownsample = 4,
        textEncoderFamily = "T5-v1.1-XXL",
        vaeFamily = "CogVideoX 3D-VAE",
        defaultFps = 16,
        description = "Expert 3D-VAE s časovou kauzalitou."
    )

    val WAN_2_5 = ArchitectureSpec(
        id = "WAN_2_5",
        displayName = "Wan 2.5 (DiT Multi-Modal)",
        expectedTextEmbeddingShape = "[1, 512, 1536]",
        seqLength = 512,
        embeddingDim = 1536,
        expectedLatentShape = "[1, 16, F/4, H/8, W/8]",
        latentChannels = 16,
        spatialDownsample = 8,
        temporalDownsample = 4,
        textEncoderFamily = "UMT5-XXL",
        vaeFamily = "Wan 2.x Causal 3D-VAE (16ch)",
        defaultFps = 16,
        description = "Modernizovaný Flow-Matching DiT Wan 2.5 s podporou vyšších rozlišení a plynulého časového vzorkování."
    )

    val FLUX_1 = ArchitectureSpec(
        id = "FLUX_1",
        displayName = "Flux.1 (Schnell / Dev DiT)",
        expectedTextEmbeddingShape = "[1, 512, 4096]",
        seqLength = 512,
        embeddingDim = 4096,
        expectedLatentShape = "[1, 16, 1, H/8, W/8]",
        latentChannels = 16,
        spatialDownsample = 8,
        temporalDownsample = 1,
        textEncoderFamily = "Dual (CLIP-L + T5-XXL)",
        vaeFamily = "Flux 16-channel VAE (ae.safetensors)",
        defaultFps = 1,
        description = "12B Rectified Flow Transformer od Black Forest Labs s fotorealistickým renderingem."
    )

    val SD_3_5 = ArchitectureSpec(
        id = "SD_3_5",
        displayName = "Stable Diffusion 3.5 (MMDiT)",
        expectedTextEmbeddingShape = "[1, 333, 4096]",
        seqLength = 333,
        embeddingDim = 4096,
        expectedLatentShape = "[1, 16, 1, H/8, W/8]",
        latentChannels = 16,
        spatialDownsample = 8,
        temporalDownsample = 1,
        textEncoderFamily = "Triple (CLIP-L + CLIP-G + T5-XXL)",
        vaeFamily = "SD3 16-channel VAE",
        defaultFps = 1,
        description = "Multimodal Diffusion Transformer s odděleným zpracováním textových a obrazových tokenů."
    )

    val ANIMATEDIFF = ArchitectureSpec(
        id = "ANIMATEDIFF",
        displayName = "AnimateDiff (SD 1.5 Motion)",
        expectedTextEmbeddingShape = "[1, 77, 768]",
        seqLength = 77,
        embeddingDim = 768,
        expectedLatentShape = "[1, 4, F, H/8, W/8]",
        latentChannels = 4,
        spatialDownsample = 8,
        temporalDownsample = 1,
        textEncoderFamily = "CLIP-ViT-L/14",
        vaeFamily = "SD 1.5 2D-VAE (840k EMA)",
        defaultFps = 12,
        description = "Časový pozornostní modul integrovaný do 2D latentního prostoru Stable Diffusion."
    )

    val QWEN_IMAGE_2_1 = ArchitectureSpec(
        id = "QWEN_IMAGE_2_1",
        displayName = "Qwen-Image 2.1 (7B DiT)",
        expectedTextEmbeddingShape = "[1, 512, 4096]",
        seqLength = 512,
        embeddingDim = 4096,
        expectedLatentShape = "[1, 16, 1, H/8, W/8]",
        latentChannels = 16,
        spatialDownsample = 8,
        temporalDownsample = 1,
        textEncoderFamily = "Qwen3-VL-8B-Instruct",
        vaeFamily = "Qwen-Image 2.1 VAE (16ch RGBA)",
        defaultFps = 1,
        description = "Sjednocený 7B DiT model od Alibaba pro generování a úpravu obrazu s nativní průhledností (RGBA)."
    )

    /**
     * Automaticky detekuje specifikaci architektury na základě názvu modelu, formátu a tenzorů.
     */
    fun detectSpec(fileName: String, sampleTensorNames: List<String> = emptyList()): ArchitectureSpec {
        val lower = fileName.lowercase()
        return when {
            lower.contains("qwen") || lower.contains("qwen-image") || lower.contains("qwen_image") || lower.contains("qwen2.1") -> QWEN_IMAGE_2_1
            lower.contains("wan-2.5") || lower.contains("wan_2.5") || lower.contains("wan2.5") -> WAN_2_5
            lower.contains("wan") || lower.contains("t2v") || lower.contains("i2v") -> {
                if (lower.contains("14b")) WAN_2_1_14B else WAN_2_1_1_3B
            }
            lower.contains("flux") -> FLUX_1
            lower.contains("sd3") || lower.contains("sd-3") -> SD_3_5
            lower.contains("ltx") -> LTX_VIDEO
            lower.contains("hunyuan") -> HUNYUAN_VIDEO
            lower.contains("cogvideo") -> COGVIDEO_X
            lower.contains("animatediff") || lower.contains("motion") -> ANIMATEDIFF
            else -> {
                // Heuristika podle tenzorů
                if (sampleTensorNames.any { it.contains("double_blocks") }) {
                    FLUX_1
                } else if (sampleTensorNames.any { it.contains("joint_blocks") }) {
                    SD_3_5
                } else if (sampleTensorNames.any { it.contains("blocks.0.cross_attn") }) {
                    WAN_2_1_1_3B
                } else if (sampleTensorNames.any { it.contains("motion_modules") }) {
                    ANIMATEDIFF
                } else {
                    WAN_2_1_1_3B
                }
            }
        }
    }

    /**
     * Zjistí, zda se jedná o samostatné tělo modelu (Backbone Only), které vyžaduje
     * přiřazení externích vah nebo aktivaci vestavěného univerzálního subsystému.
     */
    fun isBackboneOnly(model: GenModel, inspection: ModelTensorInspection?): Boolean {
        if (inspection != null) {
            return inspection.hasDiffusionBackbone && !inspection.hasTextEncoder && !inspection.hasVae
        }
        val lower = model.filePath.lowercase()
        return (lower.contains("wan") || lower.contains("ltx") || lower.contains("hunyuan") || lower.contains("qwen")) &&
                !lower.contains("vae") && !lower.contains("encoder") && !lower.contains("t5") && !lower.contains("vl")
    }
}
