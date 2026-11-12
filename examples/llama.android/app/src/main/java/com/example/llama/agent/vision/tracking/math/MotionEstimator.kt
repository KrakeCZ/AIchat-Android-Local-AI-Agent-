package com.example.llama.agent.vision.tracking.math

import android.graphics.RectF

/**
 * Jednotné abstraktní rozhraní pro všechny matematické estimátory pohybu (filtry).
 * Umožňuje bezpečný Hot-Swap různých implementací Kalmanových filtrů v reálném čase,
 * aniž by na nich byla datová vrstva trackerů přímo závislá.
 */
interface MotionEstimator {
    
    val velocityX: Float
    val velocityY: Float
    val velocityW: Float
    val velocityH: Float

    /**
     * Inicializuje stav filtru na základě prvního detekovaného obdélníku.
     */
    fun initiate(rect: RectF)

    /**
     * Krok predikce (Fyzikální posun v čase).
     * @param timestampMs Reálný čas snímku v milisekundách pro správný výpočet dt.
     */
    fun predict(timestampMs: Long)

    /**
     * Krok korekce (Update na základě reálného měření ze sítě).
     * @param rect Naměřený bounding box z detektoru.
     * @param confidence Spolehlivost detekce (0.0-1.0) — ovlivňuje dynamický šum měření.
     * @param timestampMs Reálný čas snímku v milisekundách.
     */
    fun update(rect: RectF, confidence: Float, timestampMs: Long)

    /**
     * Zapíše predikovaný obdélník do prealokovaného výstupního objektu.
     * Zero-allocation přístup: nevrací nový objekt, zapisuje do existujícího (prevence GC spike).
     */
    fun getPredictedRect(outRect: RectF)
}
