package com.example.llama.agent.vision.plugins.impl

import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.vision.plugins.VisionFilterPlugin
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Physics Violation Detector — detekce fyzikálně nemožného pohybu.
 * Korunní jewel UAP detekce: žádný hmotný objekt nemůže porušit tato kritéria.
 *
 * Tři typy narušení Newtonovy mechaniky:
 *
 * 1. HYPERJUMP
 *    Centrum bboxu skočí > [maxJumpFraction × normalizeBy] px v jednom framu (33ms).
 *    Příčiny: DKF tracker špatně asocioval vzdálený bod, nebo objekt se teleportoval.
 *    Při správně nastavené DKF by nemělo nastat → maximální threat level.
 *
 * 2. INSTANT_STOP
 *    Objekt přechází z rychlosti > [instantStopThreshold] na < 10% té rychlosti
 *    v jednom framu. Fyzikálně nemožné: i raketa potřebuje čas na deceleration.
 *
 * 3. IMPOSSIBLE_TURN
 *    Angulární změna směru > [impossibleTurnDegrees]° při zachování signifikantní
 *    rychlosti. Stíhačka zvládne max ~30°/frame při 9G; UAP bez setrvačnosti = 180°+.
 *
 * @param normalizeBy    Normalizační faktor pro HYPERJUMP — shoduje se s rozlišením
 *                       detection space (px). Pro YOLO 640px input: normalizeBy=640f.
 */
class PhysicsViolationPlugin(
    private val maxJumpFraction: Float       = 0.22f,    // HYPERJUMP: 22% šířky detection space
    private val instantStopThreshold: Float  = 0.007f,   // INSTANT_STOP: min rychlost pred stop
    private val impossibleTurnDegrees: Float = 115f,     // IMPOSSIBLE_TURN: max úhel za frame
    private val normalizeBy: Float           = 640f,     // Detection space width (pro HYPERJUMP)
    private val historyDepth: Int            = 4         // Počet uchovaných stavů na track
) : VisionFilterPlugin {

    override val pluginId    = "tracker_physics_violation"
    override val displayName = "Physics Violation Detector (UAP Alert)"

    /**
     * Interní stav jednoho tracking ID.
     * Ukládáme ORIGINÁLNÍ hodnoty z Detection — před případnou modifikací jinými pluginy.
     */
    private data class TrackState(
        val cx    : Float,   // Centrum X v detection space
        val cy    : Float,   // Centrum Y v detection space
        val vx    : Float,   // Rychlost X (normalizovaná — stejné měřítko jako DKF výstup)
        val vy    : Float,   // Rychlost Y
        val speed : Float    // Skalární rychlost = sqrt(vx² + vy²)
    )

    // ID → kroužková fronta stavů (depth = historyDepth)
    private val history = mutableMapOf<Int, ArrayDeque<TrackState>>()

    @Synchronized
    override fun process(detections: List<Detection>, timestampMs: Long): List<Detection> {
        val result     = mutableListOf<Detection>()
        val visibleIds = detections.map { it.id }.toSet()

        for (det in detections) {
            val cx    = det.rect.centerX()
            val cy    = det.rect.centerY()
            val speed = sqrt(det.vx * det.vx + det.vy * det.vy)
            val state = TrackState(cx, cy, det.vx, det.vy, speed)

            val track = history.getOrPut(det.id) { ArrayDeque(historyDepth) }

            // Potřebujeme aspoň 2 stavy pro porovnání
            if (track.size < 2) {
                if (track.size >= historyDepth) track.removeFirst()
                track.addLast(state)
                result.add(det)
                continue
            }

            val prev       = track.last()
            val violations = mutableListOf<String>()
            var maxThreat  = det.threatLevel

            // ── 1. HYPERJUMP ───────────────────────────────────────────────────────────────
            // Normalizujeme skok vůči detection space → výsledek je bezrozměrný (0.0–1.0+)
            val dcx        = abs(cx - prev.cx)
            val dcy        = abs(cy - prev.cy)
            val jumpPixels = sqrt(dcx * dcx + dcy * dcy)
            val jumpNorm   = jumpPixels / normalizeBy  // < 0: neexistuje; 0.22+ = HYPERJUMP

            if (jumpNorm > maxJumpFraction) {
                violations.add(
                    "[HYPERJUMP d=${String.format("%.0f", jumpPixels)}px / ${String.format("%.1f", jumpNorm * 100f)}%]"
                )
                maxThreat = maxOf(maxThreat, 0.95f)
            }

            // ── 2. INSTANT_STOP ────────────────────────────────────────────────────────────
            // Objekt byl v pohybu, nyní je skoro statický → narušení setrvačnosti
            val prevWasMoving   = prev.speed  >= instantStopThreshold
            val currIsStationary = speed      <  instantStopThreshold * 0.10f  // <10% původní rychlosti

            if (prevWasMoving && currIsStationary) {
                violations.add(
                    "[INSTANT-STOP v=${String.format("%.4f", prev.speed)}→${String.format("%.5f", speed)}]"
                )
                maxThreat = maxOf(maxThreat, 0.90f)
            }

            // ── 3. IMPOSSIBLE_TURN ─────────────────────────────────────────────────────────
            // Počítáme pouze pokud OBA stavy mají signifikantní rychlost (vylučuje stop-start)
            val prevMoving = prev.speed >= instantStopThreshold
            val currMoving = speed      >= instantStopThreshold

            if (prevMoving && currMoving) {
                val prevAngle  = atan2(prev.vy.toDouble(), prev.vx.toDouble())
                val currAngle  = atan2(det.vy.toDouble(),  det.vx.toDouble())
                var angleDelta = Math.toDegrees(abs(currAngle - prevAngle))

                // Wrap-around korekce: úhel > 180° → vezmeme doplněk do 360°
                if (angleDelta > 180.0) angleDelta = 360.0 - angleDelta

                if (angleDelta > impossibleTurnDegrees) {
                    violations.add(
                        "[IMPOSSIBLE-TURN ${String.format("%.0f", angleDelta)}deg]"
                    )
                    maxThreat = maxOf(maxThreat, 0.85f)
                }
            }

            // ── Aktualizuj historii a výstup ──────────────────────────────────────────────
            if (track.size >= historyDepth) track.removeFirst()
            track.addLast(state)

            result.add(
                if (violations.isNotEmpty()) {
                    det.copy(
                        customTags  = det.customTags + violations,
                        threatLevel = maxThreat
                    )
                } else {
                    det
                }
            )
        }

        // Evikce stop které zmizely z detekce — uvolnění paměti
        history.keys.retainAll(visibleIds)

        return result
    }

    @Synchronized
    override fun reset() {
        history.clear()
    }

    override fun release() = reset()
}
