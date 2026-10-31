package com.example.llama.agent.irismotion

import android.graphics.PointF

/**
 * CalibrationMapper — Mapuje normalizované iris souřadnice (0.0–1.0) na pixely obrazovky.
 *
 * Metoda: Bilineární interpolace s 4 kalibračními body (rohy obrazovky). Tato metoda kompenzuje:
 * - Offset přední kamery (není ve středu rámu)
 * - Optické zkreslení čočky (barrel/pincushion)
 * - Individuální anatomii oka (vzdálenost = různá citlivost)
 *
 * Nekalibrovaný fallback: Edge Decay komprese (lineární střed + exponenciální útlum na okrajích).
 * Eliminuje zamrzání kurzoru na hranách displeje při zachování plného free-air rozsahu ve středu
 * obrazovky.
 *
 * Thread-safe: pouze pro čtení za runtime (setCalibrationData volán jen jednou během init).
 */
class CalibrationMapper(private val screenWidth: Int, private val screenHeight: Int) {
    // Kalibrační body v normalizovaném prostoru kamery (0.0–1.0)
    // Default: rohy kamery (hrubý fallback bez kalibrace)
    private var topLeft = PointF(0.2f, 0.2f)
    private var topRight = PointF(0.8f, 0.2f)
    private var bottomLeft = PointF(0.2f, 0.8f)
    private var bottomRight = PointF(0.8f, 0.8f)

    // Polynomiální koeficienty (nový model)
    private var coeffX: FloatArray = FloatArray(6)
    private var coeffY: FloatArray = FloatArray(6)
    private var isPolynomialCalibrated: Boolean = false

    /** True pokud byla provedena 4-bodová kalibrace */
    var isCalibrated: Boolean = false
        private set

    /** Zda pouzivat sferickou sinusovou kompenzaci zobrazeni. Vypnuto = linearni pohyb. */
    var useSphericalCompensation: Boolean = false

    /**
     * Edge Decay margin — hranice lineární zóny. Hodnoty mimo [0, 1] se exponenciálně kompresují k
     * 0 / 1. Výchozí 0.08 (8% obrazovky). Konfigurovatelné z UI. Rozsah: 0.02–0.25. Vyšší = měkčí
     * přechod na okraji.
     */
    var edgeMargin: Float = 0.0f

    /**
     * Mapuje surové iris souřadnice na absolutní pixely obrazovky.
     *
     * Algoritmus:
     * 1. Interpolace levé hranice X pro aktuální Y (topLeft.x → bottomLeft.x)
     * 2. Interpolace pravé hranice X pro aktuální Y (topRight.x → bottomRight.x)
     * 3. Normalizace rawX v rámci těchto hranic → normalizedX ∈ [0,1]
     * 4. Shodně pro Y osu
     * 5. Clamp + scale na pixely obrazovky
     *
     * @param rawX Surová X souřadnice z MediaPipe iris landmark (0.0–1.0, osa kamery)
     * @param rawY Surová Y souřadnice z MediaPipe iris landmark (0.0–1.0, osa kamery)
     * @return Absolutní pixel souřadnice na obrazovce PointF(screenX, screenY)
     */
    fun map(rawX: Float, rawY: Float): PointF {
        if (!isCalibrated && !isPolynomialCalibrated) {
            // Edge Decay: lineární 1:1 ve středu [0, 1],
            // exponenciální komprese mimo tento rozsah.
            // Eliminuje zamrzání na hranách bez mrtvých zón (na rozdíl od hard clamp).
            val compressedX = edgeCompress(rawX, edgeMargin)
            val compressedY = edgeCompress(rawY, edgeMargin)
            return PointF(compressedX * screenWidth, compressedY * screenHeight)
        }

        // === POLYNOMIÁLNÍ MODEL 2. ŘÁDU ===
        if (isPolynomialCalibrated) {
            val f = floatArrayOf(1f, rawX, rawY, rawX * rawY, rawX * rawX, rawY * rawY)
            val nx = f.indices.sumOf { (f[it] * coeffX[it]).toDouble() }.toFloat().coerceIn(0f, 1f)
            val ny = f.indices.sumOf { (f[it] * coeffY[it]).toDouble() }.toFloat().coerceIn(0f, 1f)
            
            // Sférická kompenzace se aplikuje na normalizovaný výstup modelu
            val sx = if (useSphericalCompensation) spherical(nx) else nx
            val sy = if (useSphericalCompensation) spherical(ny) else ny
            
            val fx = edgeCompress(sx, edgeMargin)
            val fy = edgeCompress(sy, edgeMargin)
            return PointF(fx * screenWidth, fy * screenHeight)
        }

        // === BILINEÁRNÍ INTERPOLACE ===

        // 1. Interpolace X-hranic pro aktuální Y (vertikální interpolace hranic)
        val leftEdgeX = lerp(topLeft.x, bottomLeft.x, rawY)
        val rightEdgeX = lerp(topRight.x, bottomRight.x, rawY)

        // 2. Normalizace rawX v rámci těchto hranic
        val dX = rightEdgeX - leftEdgeX
        // Ochrana proti dělení nulou (degenerate kalibrace) + podpora negativního dX (zrcadlení)
        val normalizedX = if (kotlin.math.abs(dX) > 0.001f) (rawX - leftEdgeX) / dX else 0.5f

        // 3. Interpolace Y-hranic pro aktuální X (horizontální interpolace hranic)
        val topEdgeY = lerp(topLeft.y, topRight.y, rawX)
        val bottomEdgeY = lerp(bottomLeft.y, bottomRight.y, rawX)

        // 4. Normalizace rawY
        val dY = bottomEdgeY - topEdgeY
        val normalizedY = if (kotlin.math.abs(dY) > 0.001f) (rawY - topEdgeY) / dY else 0.5f

        // 4.5 Sférická kompenzace čočky a očního bulbu (Non-linear projection)
        // Oči jsou kulaté a čočka kamery má barrel distortion. Interpolování "na placku" způsobuje,
        // že kurzor u okrajů displeje zrychluje a je hyper-citlivý, kdežto ve středu je pomalý.
        // Sine projekce tento fenomén inverzně balancuje.
        val sphericalX = if (useSphericalCompensation) spherical(normalizedX) else normalizedX
        val sphericalY = if (useSphericalCompensation) spherical(normalizedY) else normalizedY

        // 5. Měkký clamp [0,1] s podporou Edge Decay a scale na pixely
        // V případě 4 bodové kalibrace se spoléháme na edgeCompress stejně jako na nekalibrovanou.
        val finalX = edgeCompress(sphericalX, edgeMargin)
        val finalY = edgeCompress(sphericalY, edgeMargin)

        val screenX = finalX * screenWidth
        val screenY = finalY * screenHeight

        return PointF(screenX, screenY)
    }

    /**
     * Nastaví kalibrační body z [CalibrationRepository]. Volat jednou při startu
     * IrisMotionController pokud existuje platná kalibrace.
     *
     * @param tl Top-left iris souřadnice (z CalibrationActivity)
     * @param tr Top-right iris souřadnice
     * @param bl Bottom-left iris souřadnice
     * @param br Bottom-right iris souřadnice
     */
    fun setCalibrationData(tl: PointF, tr: PointF, bl: PointF, br: PointF) {
        topLeft = tl
        topRight = tr
        bottomLeft = bl
        bottomRight = br
        isCalibrated = true
    }

    /**
     * Nastaví polynomiální kalibraci z [CalibrationRepository].
     * @param cx Koeficienty pro X (velikost 6)
     * @param cy Koeficienty pro Y (velikost 6)
     */
    fun setPolynomialCalibration(cx: FloatArray, cy: FloatArray) {
        require(cx.size == 6 && cy.size == 6) { "Polynomiální model vyžaduje přesně 6 koeficientů" }
        coeffX = cx.copyOf()
        coeffY = cy.copyOf()
        isPolynomialCalibrated = true
    }

    /** Invaliduje kalibraci (např. při změně orientace). */
    fun resetCalibration() {
        isCalibrated = false
        isPolynomialCalibrated = false
    }

    // Sférická kompenzace pro X/Y
    private fun spherical(n: Float): Float {
        val clamped = n.coerceIn(0.01f, 0.99f)
        val mapped = (clamped - 0.5f) * 2f
        return (kotlin.math.asin(mapped.toDouble()) / kotlin.math.PI).toFloat() + 0.5f
    }

    // Lineární interpolace: lerp(a, b, t) = a + (b-a)*t
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * Edge Decay komprese — evoluce komerčních eye tracking systémů.
     *
     * Princip:
     * - Hodnoty v rozsahu [0, 1] → beze změny (lineární 1:1, žádná degradace přesnosti ve středu)
     * - Hodnoty < 0 → asymptoticky se blíží k 0 (nikdy nedosáhne záporné hodnoty)
     * - Hodnoty > 1 → asymptoticky se blíží k 1 (nikdy nepřesáhne)
     *
     * Matematika: Pro x < 0: result = margin * (1 - e^(x/margin))
     * - x je záporný → x/margin je záporný → e^(záporné) ∈ (0, 1) → result ∈ (0, margin)
     * - Čím vzdálenější od 0, tím blíže k 0, ale nikdy záporný
     *
     * Pro x > 1: result = 1 - margin * (1 - e^(-(x-1)/margin))
     * - symetrická verze pro horní okraj
     *
     * @param x Normalizovaná gaze souřadnice (může být mimo [0,1] při vysoké amplifikaci)
     * @param margin Šířka kompresní zóny (0.02–0.25). Vyšší = měkčí přechod.
     * @return Kompresovaná hodnota vždy v rozsahu [0, 1]
     */
    fun edgeCompress(x: Float, margin: Float): Float {
        val m = margin.coerceIn(0.02f, 0.25f)
        return when {
            x < 0f -> {
                // Exponenciální přitažení k 0: asymptoticky klesá k 0, nikdy záporný
                // margin * (1 - e^(x/m)) kde x < 0 → e^(x/m) < 1
                m * (1f - kotlin.math.exp(x / m).toFloat())
            }
            x > 1f -> {
                // Symetrická komprese pro horní okraj: asymptoticky roste k 1, nikdy nepřesáhne
                1f - m * (1f - kotlin.math.exp(-(x - 1f) / m).toFloat())
            }
            else -> x // Lineární 1:1 — ŽÁDNÁ modifikace ve středu obrazovky
        }
    }
}
