package com.example.llama.agent.vision.plugins.impl

import android.graphics.Path
import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.vision.plugins.VisionFilterPlugin

/**
 * Záznam pohybu (Trajectory/Heatmap) do `trailPath` v Detection.
 * 
 * Sleduje historii pozic každého objektu a sestavuje objekt `Path`,
 * který se pak vykreslí přes BoundingBoxView.
 * Využívá Circular Buffer (omezenou velikost historie), aby nedocházelo
 * k neomezené alokaci paměti (Path objektů se jen recyklují).
 */
class SpatialHeatmapPlugin(
    private val maxTrailLength: Int = 30 // Kolik framů stopy uchovat
) : VisionFilterPlugin {
    
    override val pluginId = "tracker_spatial_heatmap"
    override val displayName = "Spatial Heatmap (Trails)"

    // Key: Track ID, Value: Seznam posledních pozic (cx, cy)
    private val trails = mutableMapOf<Int, MutableList<Pair<Float, Float>>>()
    
    // Recyklovatelné Path objekty pro každý track (aby se nealokovaly nové v každém framu)
    private val cachedPaths = mutableMapOf<Int, Path>()

    override fun process(detections: List<Detection>, timestampMs: Long): List<Detection> {
        val result = mutableListOf<Detection>()
        val currentIds = mutableSetOf<Int>()

        for (det in detections) {
            currentIds.add(det.id)
            
            val cx = det.rect.centerX()
            val cy = det.rect.centerY()
            
            // Zápis do kruhového bufferu historie
            val trail = trails.getOrPut(det.id) { mutableListOf() }
            trail.add(Pair(cx, cy))
            if (trail.size > maxTrailLength) {
                trail.removeAt(0)
            }
            
            // Aktualizace Path objektu
            val path = cachedPaths.getOrPut(det.id) { Path() }
            path.reset()
            
            if (trail.size > 1) {
                path.moveTo(trail[0].first, trail[0].second)
                for (i in 1 until trail.size) {
                    path.lineTo(trail[i].first, trail[i].second)
                }
            }
            
            // Připojíme recyklovaný Path k detekci (BBoxView si udělá případnou kopii)
            result.add(det.copy(trailPath = path))
        }
        
        // Evikce ztracených stop
        val deadIds = trails.keys - currentIds
        deadIds.forEach { 
            trails.remove(it)
            cachedPaths.remove(it) 
        }
        
        return result
    }

    override fun reset() {
        trails.clear()
        cachedPaths.clear()
    }

    override fun release() {
        reset()
    }
}
