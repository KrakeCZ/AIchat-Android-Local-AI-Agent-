package com.example.llama.agent.vision.plugins.impl

import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.vision.plugins.VisionFilterPlugin

/**
 * New Object Alerter — klíčový plugin pro UAP detekci.
 *
 * Každý tracking ID, který se objeví poprvé, dostane [⚡ NEW CONTACT] tag.
 * Po určitém počtu framů přejde do "established" (normální zobrazení).
 * Ideální pro monitoring oblohy: čistá obloha = žádné alerty.
 * Náhlý objekt = okamžitý alert.
 */
class NewObjectAlerterPlugin(
    private val confirmationFrames: Int = 3,     // Framů do "established" stavu
    private val memoryFrames: Int = 90           // Framů paměti po ztrátě stopy
) : VisionFilterPlugin {

    override val pluginId = "tracker_new_object_alerter"
    override val displayName = "New Object Alerter (UAP Watch)"

    // ID → počet framů od prvního výskytu
    private val frameCounters = mutableMapOf<Int, Int>()
    // ID → počet framů od ztráty (pro zapomenutí)
    private val lostCounters = mutableMapOf<Int, Int>()

    @Synchronized
    override fun process(detections: List<Detection>, timestampMs: Long): List<Detection> {
        val visibleIds = detections.map { it.id }.toSet()
        val result = mutableListOf<Detection>()

        // Zvyšuj lost countery pro neviděné stopy
        lostCounters.keys.toList().forEach { id ->
            if (id !in visibleIds) {
                val count = lostCounters.getOrDefault(id, 0) + 1
                if (count >= memoryFrames) {
                    // Zapomenout — pokud se znovu objeví, bude opět "NEW"
                    frameCounters.remove(id)
                    lostCounters.remove(id)
                } else {
                    lostCounters[id] = count
                }
            }
        }

        for (det in detections) {
            // Resetuj lost counter pro aktivní stopy
            lostCounters.remove(det.id)

            // Inkrementuj frame counter
            val seen = frameCounters.getOrDefault(det.id, 0) + 1
            frameCounters[det.id] = seen

            if (seen <= confirmationFrames) {
                // ⚡ NOVÝ KONTAKT — první výskyt tohoto ID
                val newTags = det.customTags.toMutableList()
                newTags.add("[⚡ NEW CONTACT #${det.id}]")
                result.add(det.copy(
                    customTags = newTags,
                    threatLevel = maxOf(det.threatLevel, 0.4f)
                ))
            } else {
                result.add(det)
            }
        }

        // Začni lost counter tracking pro ID která zmizela
        for (id in frameCounters.keys) {
            if (id !in visibleIds) {
                lostCounters[id] = lostCounters.getOrDefault(id, 0) + 1
            }
        }

        return result
    }

    @Synchronized
    override fun reset() {
        frameCounters.clear()
        lostCounters.clear()
    }

    override fun release() = reset()
}
