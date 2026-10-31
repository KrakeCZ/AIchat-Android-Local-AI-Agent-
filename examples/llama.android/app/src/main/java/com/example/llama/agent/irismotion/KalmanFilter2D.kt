package com.example.llama.agent.irismotion

/**
 * MRE-level Plnohodnotný Diskrétní Kalmanův filtr (State-Space Model). Navržený na základě principů
 * "Probabilistic Robotics" a "Autonomous Robots Lab".
 *
 * Stavový vektor X: [x, y, vx, vy]^T Modeluje rychlost změny (okamžitá sakáda) pro dosažení
 * Zero-Lag efektu, což eliminuje zpoždění klasických DEMA / LowPass filtrů.
 *
 * Pevná maticová implementace v primitivních polích pro maximální výkon O(1) na Androidu (bez
 * alokace objektů v rámci Predict/Update smyčky).
 *
 * OPRAVY (DeepWriteShell-Android):
 * - BUG 2: update() nyní používá pipeline timestamp místo SystemClock → synchronizace dt s
 * ostatními filtry v řetězci (AlphaBeta, OneEuro)
 * - BUG 3: reset() nastavuje lazy reinit flag → první skutečné měření inicializuje stav, ne
 * hardcoded (0.5, 0.5) které teleportovalo kurzor do středu po mrknutí
 * - BUG 5: Odstraněny nepoužité proměnné term0/term1 v P matici Update stepu
 */
class KalmanFilter2D(
        initialX: Float = 0.5f,
        initialY: Float = 0.5f,
        private val processNoise: Float =
                0.001f, // Q matice (Šum modelu - jak moc se "reálně" může oko zrychlit)
        private val measurementNoise: Float =
                0.02f // R matice (Šum senzoru - jak moc věříme kameře)
) : GazeFilter {
    // Stavový Vektor X: [x, y, vx, vy]
    private val X = FloatArray(4)

    // Kovarianční matice P (4x4)
    private val P = FloatArray(16)

    // Pomocné matice pro výpočty
    private val X_pred = FloatArray(4)
    private val P_pred = FloatArray(16)

    // Q - Process noise covariance (4x4)
    private val Q = FloatArray(16)

    // R - Measurement noise covariance (2x2)
    private val R = FloatArray(4)

    // H - Measurement model matrix (2x4) (Mapuje měření jen na x, y)
    @Suppress("unused") private val H = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f)

    // BUG 3 FIX: Lazy reinit flag — reset() nenastaví pevné 0.5/0.5,
    // ale flag pro inicializaci na první skutečné měření
    private var needsReinit = true
    private var lastTimeMs = 0L

    init {
        // Inicializace stavu
        X[0] = initialX
        X[1] = initialY
        X[2] = 0f // vx
        X[3] = 0f // vy

        // Inicializace kovariance (nejistota na začátku)
        for (i in 0..3) {
            P[i * 4 + i] = 0.1f
            Q[i * 4 + i] = processNoise
        }
        // U zrychlení/rychlosti (vx, vy) je šum přirozeně vyšší
        Q[2 * 4 + 2] = processNoise * 5f
        Q[3 * 4 + 3] = processNoise * 5f

        R[0] = measurementNoise
        R[3] = measurementNoise
    }

    /**
     * Update State (Oko posílá nová MediaPipe data).
     *
     * BUG 2 FIX: Používá timestampMs z pipeline místo SystemClock.elapsedRealtime(). Synchronizuje
     * dt se všemi ostatními filtry v řetězci (AlphaBeta, OneEuro).
     *
     * @param measX Zaměřená pozice X (normalizovaná, Free-Air: může být <0 nebo >1)
     * @param measY Zaměřená pozice Y (normalizovaná, Free-Air: může být <0 nebo >1)
     * @param timestampMs Časové razítko z pipeline v milisekundách
     * @param dynamicR Dynamický šum (Pokud mrká nebo je špatné světlo, R je vyšší -> věříme méně
     * kameře a více predikci)
     * @return [Vycentrované Zero-Lag X, Vycentrované Zero-Lag Y]
     */
    fun update(
            measX: Float,
            measY: Float,
            timestampMs: Long,
            dynamicR: Float = measurementNoise
    ): Pair<Float, Float> {
        // BUG 3 FIX: Lazy reinit — první skutečné měření po reset() inicializuje stav
        if (needsReinit) {
            hardReset(measX, measY)
            needsReinit = false
            lastTimeMs = timestampMs
            return Pair(measX, measY)
        }

        // BUG 2 FIX: dt z pipeline timestampu, ne SystemClock
        var dt = (timestampMs - lastTimeMs) / 1000f // časový krok (sekundy)
        if (dt <= 0f) dt = 0.016f // Prevence dělení nulou (60FPS fallback)
        lastTimeMs = timestampMs

        // Pokud je prodleva obrovská (aplikace na pozadí), zresetujeme filtr
        if (dt > 1.0f) {
            hardReset(measX, measY)
            return Pair(measX, measY)
        }

        // --- 1. PREDICT STEP ---
        // X_pred = A * X
        // A matice:
        // [1, 0, dt, 0]
        // [0, 1, 0, dt]
        // [0, 0, 1, 0]
        // [0, 0, 0, 1]
        X_pred[0] = X[0] + X[2] * dt
        X_pred[1] = X[1] + X[3] * dt
        X_pred[2] = X[2]
        X_pred[3] = X[3]

        // P_pred = A * P * A^T + Q
        // (optimalizované maticové násobení pro fixní velikost 4x4 matice A)
        for (i in 0..3) {
            for (j in 0..3) {
                var sum = 0f
                for (k in 0..3) {
                    val a_ik = if (i == k) 1f else if (k - i == 2) dt else 0f
                    var p_a_sum = 0f
                    for (l in 0..3) {
                        val a_jl = if (j == l) 1f else if (l - j == 2) dt else 0f
                        p_a_sum += P[k * 4 + l] * a_jl
                    }
                    sum += a_ik * p_a_sum
                }
                P_pred[i * 4 + j] = sum + (if (i == j) Q[i * 4 + j] else 0f)
            }
        }

        // --- 2. UPDATE STEP ---
        // Y - Inovace: V = Z - H * X_pred
        val vX = measX - X_pred[0]
        val vY = measY - X_pred[1]

        // S = H * P_pred * H^T + R
        // S je 2x2 matice
        R[0] = dynamicR
        R[3] = dynamicR

        val S00 = P_pred[0] + R[0]
        val S01 = P_pred[1]
        val S10 = P_pred[4]
        val S11 = P_pred[5] + R[3]

        // K = P_pred * H^T * S^-1
        // Inverze S (2x2 matice)
        val detS = S00 * S11 - S01 * S10
        if (kotlin.math.abs(detS) < 1e-10f) {
            // Degenerovaná matice — singularita, přeskočíme update a vrátíme predikci
            X[0] = X_pred[0]
            X[1] = X_pred[1]
            X[2] = X_pred[2]
            X[3] = X_pred[3]
            return Pair(X[0], X[1])
        }
        val S_inv00 = S11 / detS
        val S_inv01 = -S01 / detS
        val S_inv10 = -S10 / detS
        val S_inv11 = S00 / detS

        // K je 4x2 matice: K = P_pred * H^T * S_inv
        // P_pred * H^T odpovídá prvním dvěma sloupcům P_pred
        val K = FloatArray(8)
        for (i in 0..3) {
            val ph0 = P_pred[i * 4 + 0]
            val ph1 = P_pred[i * 4 + 1]
            K[i * 2 + 0] = ph0 * S_inv00 + ph1 * S_inv10
            K[i * 2 + 1] = ph0 * S_inv01 + ph1 * S_inv11
        }

        // X = X_pred + K * V
        X[0] = X_pred[0] + K[0] * vX + K[1] * vY
        X[1] = X_pred[1] + K[2] * vX + K[3] * vY
        X[2] = X_pred[2] + K[4] * vX + K[5] * vY
        X[3] = X_pred[3] + K[6] * vX + K[7] * vY

        // BUG 5 FIX: P = (I - K * H) * P_pred — čistý výpočet bez zbytečných proměnných
        for (i in 0..3) {
            for (j in 0..3) {
                P[i * 4 + j] =
                        P_pred[i * 4 + j] -
                                (K[i * 2 + 0] * P_pred[0 * 4 + j] +
                                        K[i * 2 + 1] * P_pred[1 * 4 + j])
            }
        }

        return Pair(X[0], X[1])
    }

    /**
     * Interní tvrdý reset — inicializuje stav na konkrétní pozici. Používáno z lazy reinit a z
     * update() při obrovském časovém skoku.
     */
    private fun hardReset(x: Float, y: Float) {
        X[0] = x
        X[1] = y
        X[2] = 0f
        X[3] = 0f
        for (i in 0..3) {
            for (j in 0..3) {
                P[i * 4 + j] = if (i == j) 0.1f else 0f
            }
        }
    }

    /**
     * GazeFilter interface: Předává pipeline timestamp do Kalmanova filtru. Z-osa je předána beze
     * změny (Kalman filtruje pouze X/Y).
     */
    override fun filter(
            x: Float,
            y: Float,
            z: Float,
            timestamp: Long
    ): Triple<Float, Float, Float> {
        val (resX, resY) = update(x, y, timestampMs = timestamp, dynamicR = measurementNoise)
        return Triple(resX, resY, z)
    }

    /**
     * BUG 3 FIX: Reset nastaví lazy reinit flag. Příští update() se inicializuje na SKUTEČNOU
     * pozici oka, ne na hardcoded (0.5, 0.5). Tím se eliminuje teleportace kurzoru do středu po
     * mrknutí.
     */
    override fun reset() {
        needsReinit = true
        X[2] = 0f
        X[3] = 0f
        for (i in 0..3) {
            for (j in 0..3) {
                P[i * 4 + j] = if (i == j) 0.1f else 0f
            }
        }
    }
}
