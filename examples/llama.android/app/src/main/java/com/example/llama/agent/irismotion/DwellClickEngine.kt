package com.example.llama.agent.irismotion

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * DwellClickEngine — Detekce zástavky pohledu (dwell) → generuje click event.
 *
 * Princip: Uživatel "klikne" tím, že udrží pohled na místě po dobu [dwellTimeMs]. Pokud se pohled
 * pohne o více než [movementThresholdPx] pixelů, timer se resetuje.
 *
 * Stavy automatu: IDLE → pohled mimo element / pohyb > threshold DWELLING → pohled stojí <
 * dwellTimeMs → vizuálně indikovat progress TRIGGERED → klik odeslan → cooldown (aby se neklikalo
 * opakovaně)
 *
 * Thread-safety:
 * - processPosition() volán z Main Thread (po přepnutí z GPU callback)
 * - Handler.postDelayed() pro timeout → vždy Main Thread
 */
class DwellClickEngine(
        private var dwellTimeMs: Long = 800L,
        private var movementThresholdPx: Float = 25.0f,
        private val cooldownMs: Long = 1500L,
        private val onDwellClick: (x: Int, y: Int) -> Unit,
        private val onDwellProgress: ((progress: Float) -> Unit)? = null // 0.0–1.0 pro UI arc
) {
    /**
     * Blink click mode — pokud true, záměrné mrknutí (detekováno FaceLandmarkerEngine) triggeruje
     * okamžitý click bez čekání na dwell timeout. Nastavitelné z SettingsManager /
     * IrisMotionController.
     */
    var blinkClickEnabled: Boolean = false

    /**
     * Reading Guard — pokud true, detekuje čtecí pattern (plynulý horizontální pohyb,
     * minimální vertikální odchylka) a inhibuje dwell trigger během čtení.
     * Eliminuje falešné kliky při čtení textu.
     */
    var readingGuardEnabled: Boolean = true

    /**
     * Maximální vertikální variance (px²) pro detekci reading patternu.
     * Nižší = přísnější detekce (jen velmi rovné čtení). Default 400 (≈20px std dev).
     */
    var readingVarianceThresholdPx: Float = 400f

    companion object {
        private const val TAG = "DwellClickEngine"
        private const val READING_BUFFER_SIZE = 12 // ~250ms při 48fps
    }

    private val handler = Handler(Looper.getMainLooper())

    // Aktuální stav
    private var dwellStartX: Int = -1
    private var dwellStartY: Int = -1
    private var dwellStartTime: Long = 0L
    private var isDwelling: Boolean = false
    private var isCooldown: Boolean = false
    
    // P7: Tracking pro Time-Shift fallback
    private var lastCursorX: Float = -1f
    private var lastCursorY: Float = -1f

    // Reading Guard: buffer posledních N pozic pro detekci čtecího patternu
    private val posBufferX = FloatArray(READING_BUFFER_SIZE)
    private val posBufferY = FloatArray(READING_BUFFER_SIZE)
    private var posBufferIndex = 0
    private var posBufferFilled = false

    // Timeout Runnable pro spuštění kliknutí
    private val dwellTriggerRunnable = Runnable {
        if (isDwelling && !isCooldown) {
            // Reading Guard: inhibice dwell trigger během čtení
            if (readingGuardEnabled && isReadingPattern()) {
                Log.v(TAG, "📖 Reading pattern detekován — dwell click inhibován")
                resetDwell()
                return@Runnable
            }
            Log.d(TAG, "🖱️ Dwell CLICK @ ($dwellStartX, $dwellStartY) po ${dwellTimeMs}ms")
            onDwellClick(dwellStartX, dwellStartY)
            startCooldown()
        }
    }

    // Cooldown Runnable (blokuje opakované kliky)
    private val cooldownResetRunnable = Runnable {
        isCooldown = false
        Log.v(TAG, "🔓 Dwell cooldown reset")
    }

    /**
     * Zpracuje aktuální pozici pohledu. Voláno každý frame ze stabilizátoru (~48x/s).
     *
     * @param x Aktuální screen X pohledu (px)
     * @param y Aktuální screen Y pohledu (px)
     */
    fun processPosition(x: Int, y: Int) {
        if (isCooldown) return

        lastCursorX = x.toFloat()
        lastCursorY = y.toFloat()

        // Reading Guard: aktualizace position bufferu každý frame
        if (readingGuardEnabled) {
            posBufferX[posBufferIndex] = x.toFloat()
            posBufferY[posBufferIndex] = y.toFloat()
            posBufferIndex = (posBufferIndex + 1) % READING_BUFFER_SIZE
            if (posBufferIndex == 0) posBufferFilled = true
        }

        if (!isDwelling) {
            // Start nového dwell cyklu
            startDwell(x, y)
        } else {
            // Zkontroluj pohyb
            val dx = (x - dwellStartX).toFloat()
            val dy = (y - dwellStartY).toFloat()
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)

            if (distance > movementThresholdPx) {
                // Pohyb > threshold → reset (pohled přesunut)
                resetDwell()
                startDwell(x, y)
            } else {
                // Stojíme → zpropagovat progress pro UI
                onDwellProgress?.let {
                    val elapsed = System.currentTimeMillis() - dwellStartTime
                    val progress = (elapsed.toFloat() / dwellTimeMs).coerceIn(0f, 1f)
                    it(progress)
                }
            }
        }
    }

    private fun startDwell(x: Int, y: Int) {
        dwellStartX = x
        dwellStartY = y
        dwellStartTime = System.currentTimeMillis()
        isDwelling = true
        handler.postDelayed(dwellTriggerRunnable, dwellTimeMs)
        Log.v(TAG, "⏱️ Dwell START @ ($x, $y)")
    }

    private fun resetDwell() {
        handler.removeCallbacks(dwellTriggerRunnable)
        isDwelling = false
        onDwellProgress?.invoke(0f) // Reset progress arc
    }

    private fun startCooldown() {
        isDwelling = false
        isCooldown = true
        onDwellProgress?.invoke(0f)
        handler.postDelayed(cooldownResetRunnable, cooldownMs)
    }

    /** Update dwell time za runtime (z nastavení). Okamžitě účinné pro nový cyklus. */
    fun updateDwellTime(newDwellMs: Long) {
        dwellTimeMs = newDwellMs.coerceIn(40L, 10000L) // Aplikuj novou hodnotu
        resetDwell()
        Log.d(TAG, "⚙️ DwellTime update: ${dwellTimeMs}ms — účinné pro nový cyklus")
    }

    /** Update mrtvé zóny za runtime (z nastavení). Okamžitě účinné pro nový cyklus. */
    fun updateDeadzone(newPx: Float) {
        movementThresholdPx = newPx.coerceIn(5f, 150f)
        resetDwell()
        Log.d(TAG, "⚙️ Deadzone update: ${movementThresholdPx}px — účinné pro nový cyklus")
    }

    /**
     * Blink click — voláno z IrisMotionController pokud GazeData.isBlinkDetected == true a
     * blinkClickEnabled == true.
     *
     * Triggeruje okamžitý click na aktuální dwell pozici (dwellStartX/Y). Pokud nejsme v dwell
     * cyklu — click se ignoruje (uživatel musí fixovat pohled). Cooldown platí stejně jako pro
     * dwell click.
     */
    fun onBlinkDetected(
        quality: com.example.llama.agent.irismotion.blink.BlinkQualityValidator.Quality = com.example.llama.agent.irismotion.blink.BlinkQualityValidator.Quality.UNKNOWN,
        timeShiftX: Float? = null,
        timeShiftY: Float? = null
    ) {
        if (!blinkClickEnabled) return
        if (quality == com.example.llama.agent.irismotion.blink.BlinkQualityValidator.Quality.ARTIFACT) {
            Log.v(TAG, "👁 Blink ignorován — detekován jako ARTIFACT")
            return
        }
        if (isCooldown) return
        
        // P7: Time-Shift bypasses isDwelling check for Intentional blinks.
        // Důvod: Bellův fenomén (škubnutí oka těsně před zavřením) fyzicky rozbije
        // Dwell lock. Stejně tak rychlé úmyslné mrknutí provede klik i bez Fixace/Dwellu.
        val isTimeShiftedValid = timeShiftX != null && timeShiftY != null
        
        // Zruš pending dwell timer — blink je rychlejší
        handler.removeCallbacks(dwellTriggerRunnable)
        
        val clickX = timeShiftX?.toInt() ?: lastCursorX.toInt()
        val clickY = timeShiftY?.toInt() ?: lastCursorY.toInt()
        
        Log.d(TAG, "👁 Blink CLICK @ ($clickX, $clickY) [TimeShift: $isTimeShiftedValid]")
        onDwellClick(clickX, clickY)
        startCooldown()
    }

    /** Zastaví veškeré pending timery. Volat z IrisMotionController.stop(). */
    fun cancel() {
        handler.removeCallbacks(dwellTriggerRunnable)
        handler.removeCallbacks(cooldownResetRunnable)
        isDwelling = false
        isCooldown = false
        resetReadingBuffer()
        Log.d(TAG, "🛑 DwellClickEngine cancelled")
    }

    /** Reset position bufferu pro reading guard. */
    private fun resetReadingBuffer() {
        posBufferIndex = 0
        posBufferFilled = false
    }

    /**
     * Reading Guard: Detekce čtecího patternu v position bufferu.
     *
     * Čtecí pattern: oči se pohybují plynule horizontálně (čtení řádku)
     * s minimální vertikální odchylkou. Detekujeme:
     * 1. Variance Y < threshold (oko zůstává na řádku)
     * 2. Konzistentní ΔX — většina po sobě jdoucích framů má ΔX stejného znaménka
     *    (plynulé čtení jedním směrem ≠ fixace na bodě)
     *
     * @return true pokud je detekován čtecí pattern → inhibovat dwell click
     */
    private fun isReadingPattern(): Boolean {
        if (!posBufferFilled) return false // Nedostatek dat

        // 1. Variance Y — musí být nízká (oko zůstává na řádku)
        var sumY = 0f
        var sumY2 = 0f
        for (i in 0 until READING_BUFFER_SIZE) {
            sumY += posBufferY[i]
            sumY2 += posBufferY[i] * posBufferY[i]
        }
        val meanY = sumY / READING_BUFFER_SIZE
        val varianceY = (sumY2 / READING_BUFFER_SIZE) - (meanY * meanY)
        if (varianceY > readingVarianceThresholdPx) return false // Příliš velká Y odchylka

        // 2. Konzistentní ΔX — většina po sobě jdoucích framů má ΔX stejného znaménka
        var positiveCount = 0
        var negativeCount = 0
        for (i in 1 until READING_BUFFER_SIZE) {
            val prevIdx = (posBufferIndex + i - 1) % READING_BUFFER_SIZE
            val currIdx = (posBufferIndex + i) % READING_BUFFER_SIZE
            val deltaX = posBufferX[currIdx] - posBufferX[prevIdx]
            if (deltaX > 0.5f) positiveCount++
            else if (deltaX < -0.5f) negativeCount++
            // deltaX ≈ 0 = stojíme → neutrální
        }

        // Čtecí pattern: ≥60% framů se pohybuje jedním směrem (doprava NEBO doleva)
        val totalDirectional = positiveCount + negativeCount
        val dominantDirection = maxOf(positiveCount, negativeCount)
        val isConsistentHorizontal = totalDirectional >= READING_BUFFER_SIZE / 3 &&
                dominantDirection.toFloat() / maxOf(totalDirectional, 1) >= 0.6f

        return isConsistentHorizontal
    }
}
