package com.example.llama.agent.ui.studio.hud

/**
 * Ultra-rychlý kruhový buffer s integrovaným výpočtem vektoru.
 * Pamatuje si posledních 120 framů a počítá Lead Indicator bez garbage collection overheadu.
 */
class TacticalHistoryBuffer(val maxPoints: Int = 120) {
    val historyX = FloatArray(maxPoints)
    val historyY = FloatArray(maxPoints)
    val timestamps = LongArray(maxPoints)
    var head = 0
    var size = 0
    var lockedTargetId: String? = null

    fun push(x: Float, y: Float, targetId: String, timeMs: Long) {
        if (targetId != lockedTargetId) {
            clear()
            lockedTargetId = targetId
        }
        historyX[head] = x
        historyY[head] = y
        timestamps[head] = timeMs
        head = (head + 1) % maxPoints
        if (size < maxPoints) size++
    }

    fun clear() {
        size = 0
        head = 0
        lockedTargetId = null
    }

    // Vrací [vx, vy] v pixelech za sekundu pro Lead Indicator
    fun getVelocityVector(): Pair<Float, Float> {
        if (size < 10) return Pair(0f, 0f) // Čekáme na nasbírání dat
        
        val currentIdx = (head - 1 + maxPoints) % maxPoints
        val oldIdx = (head - 10 + maxPoints) % maxPoints // Zkoumáme posun za posledních 10 framů
        
        val dt = (timestamps[currentIdx] - timestamps[oldIdx]) / 1000f
        if (dt <= 0f) return Pair(0f, 0f)
        
        val dx = historyX[currentIdx] - historyX[oldIdx]
        val dy = historyY[currentIdx] - historyY[oldIdx]
        return Pair(dx / dt, dy / dt)
    }
}
