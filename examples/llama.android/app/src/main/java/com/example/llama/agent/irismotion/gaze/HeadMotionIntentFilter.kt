package com.example.llama.agent.irismotion.gaze

/**
 * P7: Fusional Intent Filter (Head Motion).
 * Analyzuje stabilitu hlavy uživatele před/během mrknutí pomocí cyklického bufferu.
 * Pokud hlava rotuje (Yaw, Pitch, Roll) víc, než je stanovený threshold, 
 * mrknutí je s největší pravděpodobností přirozený reflex uvolňující se 
 * přesunem zraku na jiné místo, nikoliv úmyslný klik.
 *
 * [Zero I/O Allocation]: Cyklický Ring Buffer (O(1)).
 */
class HeadMotionIntentFilter(
    var enabled: Boolean = true,
    var thresholdDeg: Float = 1.5f,     // Maximální povolený pohyb hlavy (úhlová vzdálenost)
    var windowMs: Long = 200L,          // Sledované časové okno před mrknutím
    capacity: Int = 30                  // 30 frámů stačí na 500ms vzorkování při 60fps
) {
    private val bufferYaw = FloatArray(capacity)
    private val bufferPitch = FloatArray(capacity)
    private val bufferTime = LongArray(capacity)
    
    private var headIndex = 0
    private var count = 0

    /**
     * Ukládá čerstvé úhly hlavy v každém snímku.
     * @param yaw Yaw osa ze senzoru (radiany nebo stupně, podle toho v čem engine pracuje - vnitřně počítá absolutní rozdíl)
     * @param pitch Pitch osa ze senzoru
     * @param timestamp Aktuální čas
     */
    fun addPoint(yaw: Float, pitch: Float, timestamp: Long) {
        bufferYaw[headIndex] = Math.toDegrees(yaw.toDouble()).toFloat()
        bufferPitch[headIndex] = Math.toDegrees(pitch.toDouble()).toFloat()
        bufferTime[headIndex] = timestamp

        headIndex = (headIndex + 1) % bufferYaw.size
        if (count < bufferYaw.size) count++
    }

    /**
     * Vyhodnotí, zda hlava byla v určeném časovém okně relativně klidná (nepřekročila thresholdDeg).
     * @param currentTimestamp Čas mrknutí (zpravidla System.currentTimeMillis())
     * @return True pokud je hlava klidná nebo pokud je filter vypnutý.
     */
    fun isHeadStill(currentTimestamp: Long): Boolean {
        if (!enabled) return true
        if (count < 2) return true // Nedostatek dat pro posouzení pohybu

        val targetTime = currentTimestamp - windowMs

        var minYaw = Float.MAX_VALUE
        var maxYaw = -Float.MAX_VALUE
        var minPitch = Float.MAX_VALUE
        var maxPitch = -Float.MAX_VALUE

        var pointsChecked = 0

        for (i in 0 until count) {
            val idx = (headIndex - 1 - i + bufferYaw.size) % bufferYaw.size
            val t = bufferTime[idx]
            
            // Ignorujeme velmi stará data
            if (t < targetTime) {
                // Buffer je chronologicky seřazený (nejnovější -> nejstarší),
                // takže jakmile narazíme na data starší než [windowMs], můžeme breaknout.
                break
            }
            
            val y = bufferYaw[idx]
            val p = bufferPitch[idx]
            
            if (y < minYaw) minYaw = y
            if (y > maxYaw) maxYaw = y
            if (p < minPitch) minPitch = p
            if (p > maxPitch) maxPitch = p
            
            pointsChecked++
        }

        // Pokud nemáme aspoň dva body v okně, pustíme to
        if (pointsChecked < 2) return true

        val yawDelta = maxYaw - minYaw
        val pitchDelta = maxPitch - minPitch
        
        // Cca Pythagorova věta pro "čistý" úhel v 2D (Yaw/Pitch) mapě
        val totalAngularMovement = Math.sqrt((yawDelta * yawDelta + pitchDelta * pitchDelta).toDouble()).toFloat()

        return totalAngularMovement <= thresholdDeg
    }
    
    fun clear() {
        headIndex = 0
        count = 0
    }
}
