package com.example.llama.agent.vision.models

import androidx.annotation.Keep

/**
 * Striktní definice zdroje labelů pro Object Detection modely.
 * Umožňuje deterministické rozlišení na úrovni databáze (Room) a runtime inference.
 */
@Keep
enum class OdLabelStrategy(val strategyId: String) {
    /** Model obsahuje metadata i slovník uvnitř .tflite (MediaPipe/TFHub standard) */
    EMBEDDED_METADATA("embedded_metadata"),
    
    /** Model nemá metadata, ale výstupní tenzor odpovídá 80 třídám (MS COCO) */
    FALLBACK_COCO_80("fallback_coco_80"),
    
    /** Model nemá metadata, výstupní tenzor odpovídá 1000 třídám (ImageNet) */
    FALLBACK_IMAGENET_1000("fallback_imagenet_1000"),
    
    /** Model vyžaduje externí .txt soubor od uživatele (Custom) */
    EXTERNAL_TXT("external_txt"),
    
    /** Neznámý stav - vyžaduje manuální zásah uživatele */
    UNKNOWN("unknown");

    companion object {
        fun fromId(id: String?): OdLabelStrategy {
            if (id == null) return UNKNOWN
            return entries.find { it.strategyId == id } ?: UNKNOWN
        }
    }
}
