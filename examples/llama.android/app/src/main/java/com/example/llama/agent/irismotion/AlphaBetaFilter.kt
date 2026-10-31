package com.example.llama.agent.irismotion

import android.util.Log

/**
 * AlphaBetaFilter — Odlehčený prediktivní (Kalman-like) filtr se zaměřením na fyzikální model
 * pohybu očí (pozice + hybnost/rychlost).
 *
 * Speciálně navržen s ohledem na robustnost pro systémy kritické infrastruktury (drony,
 * přístupnostní pomůcky).
 *
 * Ochranné mechanismy:
 * 1. Ochrana proti NaN a nekonečnu.
 * 2. Ochrana proti časovým anomáliím (timestamp cestující v čase).
 * 3. Ochrana proti extrémním výkyvům rychlosti (fyzikální clipping).
 */
class AlphaBetaFilter(
        private val alpha: Float = 0.5f,
        private val beta: Float = 0.1f,
        private val maxVelocity: Float = 10.0f // Maximální povolená rychlost v jednotkách/sekundu
) : GazeFilter {

    companion object {
        private const val TAG = "AlphaBetaFilter"
    }

    private var isInitialized = false

    // Interní stavy pro pozici a rychlost
    var estX = 0f
        private set
    var velX = 0f
        private set

    var estY = 0f
        private set
    var velY = 0f
        private set

    var estZ = 0f
        private set
    var velZ = 0f
        private set

    private var lastTimestamp = 0L

    override fun filter(
            x: Float,
            y: Float,
            z: Float,
            timestamp: Long
    ): Triple<Float, Float, Float> {
        // ROBUSTNOST: Ochrana proti poškozeným vstupům
        if (x.isNaN() ||
                        y.isNaN() ||
                        z.isNaN() ||
                        x.isInfinite() ||
                        y.isInfinite() ||
                        z.isInfinite()
        ) {
            Log.e(TAG, "Detekován invalidní vstup (NaN/Inf). Vracím poslední známý stav.")
            return Triple(estX, estY, estZ)
        }

        if (!isInitialized) {
            estX = x
            velX = 0f
            estY = y
            velY = 0f
            estZ = z
            velZ = 0f
            lastTimestamp = timestamp
            isInitialized = true
            return Triple(estX, estY, estZ)
        }

        // Časová diference (dt) v sekundách
        // Může přijít v milisekundách nebo mikrosekundách. Běžně v MediaPipe je to µs (10^-6 s).
        // Ověříme, jaká byla doposud zvyklost, ale bezpečně budeme počítat s absolutní dif.
        val dtMillis = timestamp - lastTimestamp

        // ROBUSTNOST: Ochrana proti zápornému nebo nulovému času (Cestování v čase)
        if (dtMillis <= 0L) {
            return Triple(estX, estY, estZ)
        }

        // Předpokládáme milisekundy (dle normy GazeStabilizer).
        // Pokud by vstup byl v mikrosekundách, pak by dt byl ohromný a rychlost neadekvátní.
        // Tady pro jistotu normalizujeme podle MediaPipe, který dává microseconds (timestamp / 1000
        // = ms)
        // nebo klasické ms z currentTimeMillis. Abychom se vyhnuli chybě, omezíme dt.
        // Timestampy jsou vždy v ms ze SystemClock.uptimeMillis() (FaceLandmarkerEngine:310)
        val dt = dtMillis / 1000.0f

        // ROBUSTNOST: Ochrana proti výpadkům kamery (pokud dt je např 2 sekundy, nevystřelíme
        // kurzor mimo obrazovku)
        if (dt > 1.0f) {
            Log.w(TAG, "Detekován obrovský časový skok ($dt s). Resetuji filtr pro bezpečnost.")
            reset()
            return filter(x, y, z, timestamp)
        }

        // 1. Predikce pozice podle minulé rychlosti
        var predX = estX + velX * dt
        var predY = estY + velY * dt
        var predZ = estZ + velZ * dt

        // 2. Výpočet meze (reziduum mezi senzorem a predikcí)
        val resX = x - predX
        val resY = y - predY
        val resZ = z - predZ

        // 3. Ochrana: Oříznutí rezidua pro zabránění teleportaci kurzoru (např. odlesk)
        // Toto efektivně nahrazuje jednoduchý Outlier removal.
        // BUG 4 FIX: Zvětšeno z 0.5f na 2.0f — Free-Air data s gazeAmp mohou přesáhnout
        // rozsah 0-1, takže 0.5 fungovalo jako gumička tahající kurzor ke středu.
        val maxResidual = 2.0f // Max skok za jeden frame — pokryje i extrémní gazeAmp
        val safeResX = resX.coerceIn(-maxResidual, maxResidual)
        val safeResY = resY.coerceIn(-maxResidual, maxResidual)
        val safeResZ = resZ.coerceIn(-maxResidual, maxResidual)

        // 4. Korekce stavu (Alpha) a rychlosti (Beta)
        estX = predX + alpha * safeResX
        velX = velX + (beta / dt) * safeResX

        estY = predY + alpha * safeResY
        velY = velY + (beta / dt) * safeResY

        estZ = predZ + alpha * safeResZ
        velZ = velZ + (beta / dt) * safeResZ

        // ROBUSTNOST: Fyzikální oříznutí rychlosti (aby nám například dron nezačal dělat salta)
        velX = velX.coerceIn(-maxVelocity, maxVelocity)
        velY = velY.coerceIn(-maxVelocity, maxVelocity)
        velZ = velZ.coerceIn(-maxVelocity, maxVelocity)

        lastTimestamp = timestamp
        return Triple(estX, estY, estZ)
    }

    override fun reset() {
        isInitialized = false
        lastTimestamp =
                0L // OPRAVA: reset timestamps zabraňuje anomálnímu dt při prvním frame po resetu
        velX = 0f
        velY = 0f
        velZ = 0f
    }
}
