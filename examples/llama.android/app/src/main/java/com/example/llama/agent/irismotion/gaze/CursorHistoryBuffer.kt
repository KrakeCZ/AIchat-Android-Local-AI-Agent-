package com.example.llama.agent.irismotion.gaze

/**
 * Záznamník historie kurzoru pro Time-Shifted Clicking.
 * 
 * [Zero I/O Allocation]: Používá pre-alokovaný Ring Buffer (cyklické pole) nad
 * primitivními typy FloatArray a LongArray. Zápis má O(1) komplexitu a nevytváří 
 * žádný Garbage Collection overhead za běhu.
 * 
 * Kapacita 120 snímků odpovídá ~4 vteřinám při 30FPS streamu kamery, 
 * což bohatě přesahuje požadovaných maximálních 800ms.
 */
class CursorHistoryBuffer(private val capacity: Int = 120) {
    private val bufferX = FloatArray(capacity)
    private val bufferY = FloatArray(capacity)
    private val bufferTime = LongArray(capacity)
    
    private var headIndex = 0
    private var count = 0

    /** Přidá novou absolutní zobrazenou pozici kurzoru do historie. */
    fun addPoint(x: Float, y: Float, timestamp: Long) {
        bufferX[headIndex] = x
        bufferY[headIndex] = y
        bufferTime[headIndex] = timestamp

        headIndex = (headIndex + 1) % capacity
        if (count < capacity) count++
    }

    /**
     * Vrátí pozici kurzoru o zadaný počet milisekund do minulosti.
     * Hledá frame, jehož časové razítko je nejblíže hledanému (currentTimestamp - offsetMs).
     *
     * @param offsetMs Požadovaný offset do minulosti (např. 120L pro "před 120ms").
     * @param currentTimestamp Aktuální časový základ pro výpočet.
     * @return Souřadnice (X, Y) z historie, nebo null, pokud neexistuje dostatek dat.
     */
    fun getPositionAt(offsetMs: Long, currentTimestamp: Long): Pair<Float, Float>? {
        if (count == 0 || offsetMs <= 0L) return null

        val targetTime = currentTimestamp - offsetMs

        var bestIdx = -1
        var smallestTimeDiff = Long.MAX_VALUE

        // Projdeme historii pozpátku a najdeme frame nejblíže požadovanému času v minulosti
        for (i in 0 until count) {
            val idx = (headIndex - 1 - i + capacity) % capacity
            val t = bufferTime[idx]
            
            val diff = Math.abs(t - targetTime)
            if (diff < smallestTimeDiff) {
                smallestTimeDiff = diff
                bestIdx = idx
            }
            
            // Optimalizace: pokud časový rozdíl začne narůstat (jdeme už moc do minulosti), můžeme přerušit
            // Protože buffer je vždy časově seřazený díky přirozenému vkládání.
            if (t < targetTime - 200L) {
                break
            }
        }

        if (bestIdx == -1) return null

        return Pair(bufferX[bestIdx], bufferY[bestIdx])
    }
    
    /** Promaže historii (např. při restartu enginu). */
    fun clear() {
        headIndex = 0
        count = 0
    }
}
