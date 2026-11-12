package com.example.llama.agent.vision.tracking.math

import android.graphics.RectF

/**
 * Vysoce výkonný 2D Kalmanův filtr s dynamickým šumem měření.
 * 
 * 8-stavový vektor: [cx, cy, a, h, v_cx, v_cy, v_a, v_h]
 *   cx, cy = střed bounding boxu
 *   a      = poměr stran (width / height)
 *   h      = výška boxu
 *   v_*    = rychlostní složky
 * 
 * Klíčový rozdíl oproti OD_KalmanFilter2D:
 *   - Sleduje i rozměry boxu (aspect ratio + výška), ne jen střed
 *   - Šum měření R se dynamicky mění podle confidence skóre ML sítě
 *     (nízká confidence → větší R → filtr více důvěřuje historii)
 * 
 * Optimalizováno pro Zero-GC: FloatArray matice, žádné alokace v hlavní smyčce.
 */
class DynamicKalmanFilter2D(
    private val params: EstimatorParameters = DefaultEstimatorProfiles.DYNAMIC_DEFAULT
) : MotionEstimator {

    // Stavový vektor: [cx, cy, a, h, v_cx, v_cy, v_a, v_h]
    private val x = FloatArray(8)

    override val velocityX: Float get() = x[4]
    override val velocityY: Float get() = x[5]
    // w = a * h, derivace: dw/dt = a * dh/dt + h * da/dt
    override val velocityW: Float get() = x[2] * x[7] + x[3] * x[6]
    override val velocityH: Float get() = x[7]
    
    // Kovarianční matice odhadu P (8x8 uložená jako 1D pole pro Cache Locality)
    private val P = FloatArray(64)

    /**
     * Inicializuje stav filtru na základě prvního detekovaného obdélníku.
     */
    override fun initiate(rect: RectF) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val h = rect.height().coerceAtLeast(params.minHeight)
        val a = rect.width() / h

        x[0] = cx; x[1] = cy; x[2] = a; x[3] = h
        x[4] = 0f; x[5] = 0f; x[6] = 0f; x[7] = 0f

        // Inicializace diagonály kovarianční matice P pro normalizované souřadnice (0.0 - 1.0)
        for (i in 0 until 64) P[i] = 0f
        P[0] = 0.01f   // cx (stddev 0.1 = 10% of screen)
        P[9] = 0.01f   // cy
        P[18] = 0.1f   // a (aspect ratio)
        P[27] = 0.01f  // h
        P[36] = 0.0001f // v_cx (stddev 0.01 = 1% of screen per frame)
        P[45] = 0.0001f // v_cy
        P[54] = 0.001f  // v_a
        P[63] = 0.0001f // v_h
    }

    /**
     * Krok predikce (Fyzikální posun v čase podle modelu konstantní rychlosti).
     * Rovnice: x = F * x, P = F * P * F^T + Q
     */
    override fun predict(timestampMs: Long) {
        // Ignorujeme timestampMs, DKF modeluje konstantní framový krok (dt=1)
        
        // 1. Predikce stavu: x = F * x (dt = 1 frame)
        x[0] += x[4] // cx = cx + v_cx
        x[1] += x[5] // cy = cy + v_cy
        x[2] += x[6] // a  = a  + v_a
        x[3] += x[7] // h  = h  + v_h

        // 2. Generování šumu procesu Q (adaptivní podle výšky objektu, min 1% obrazovky)
        val currentH = x[3].coerceAtLeast(0.01f)
        val qPos = (params.stdWeightPosition * currentH) * (params.stdWeightPosition * currentH)
        val qVel = (params.stdWeightVelocity * currentH) * (params.stdWeightVelocity * currentH)

        // 3. In-place update kovarianční matice P = F * P * F^T + Q
        // cx (0) and v_cx (4)
        P[0] += P[4] + P[32] + P[36] + qPos
        P[4] += P[36]
        P[32] += P[36]
        P[36] += qVel

        // cy (1) and v_cy (5)
        P[9] += P[13] + P[41] + P[45] + qPos
        P[13] += P[45]
        P[41] += P[45]
        P[45] += qVel

        // a (2) and v_a (6)
        P[18] += P[22] + P[50] + P[54] + qPos
        P[22] += P[54]
        P[50] += P[54]
        P[54] += qVel

        // h (3) and v_h (7)
        P[27] += P[31] + P[59] + P[63] + qPos
        P[31] += P[63]
        P[59] += P[63]
        P[63] += qVel
    }

    /**
     * Krok korekce (Update na základě reálného měření ze sítě).
     * Využívá confidence score pro dynamické vážení šumu měření R.
     */
    override fun update(rect: RectF, confidence: Float, timestampMs: Long) {
        val h = rect.height().coerceAtLeast(0.001f)
        val zCx = rect.centerX()
        val zCy = rect.centerY()
        val zA = rect.width() / h
        val zH = h

        // Výpočet inovačního rezidua: y = z - H * x
        val y0 = zCx - x[0]
        val y1 = zCy - x[1]
        val y2 = zA  - x[2]
        val y3 = zH  - x[3]

        // Dynamický šum měření R (nepřímo úměrný spolehlivosti sítě)
        val clampedConfidence = confidence.coerceIn(0.1f, 1.0f)
        val noiseScale = 1.0f + (1.0f - clampedConfidence) * 3.0f
        val currentH = x[3].coerceAtLeast(0.01f)
        val rPos = (params.stdWeightPosition * currentH * noiseScale) * (params.stdWeightPosition * currentH * noiseScale)

        // Inovační kovariance S = H * P * H^T + R (diagonální zjednodušení)
        val s0 = P[0]  + rPos
        val s1 = P[9]  + rPos
        val s2 = P[18] + rPos
        val s3 = P[27] + rPos

        // Pojistka: Pokud S je příliš malé, přeskočíme update (prevence dělení nulou)
        if (s0 < 1e-10f || s1 < 1e-10f || s2 < 1e-10f || s3 < 1e-10f) return

        // Kalmanův zisk K = P * H^T * S^-1
        val k0 = P[0]  / s0
        val k1 = P[9]  / s1
        val k2 = P[18] / s2
        val k3 = P[27] / s3

        // Nový odhad stavu: x = x + K * y
        x[0] += k0 * y0
        x[1] += k1 * y1
        x[2] += k2 * y2
        x[3] += k3 * y3

        // Update složek rychlosti (opraven Kalman Gain)
        val k_vcx = P[32] / s0
        val k_vcy = P[41] / s1
        val k_va  = P[50] / s2
        val k_vh  = P[59] / s3
        
        x[4] += k_vcx * y0
        x[5] += k_vcy * y1
        x[6] += k_va * y2
        x[7] += k_vh * y3

        // Update kovarianční matice: P = (I - K * H) * P
        // Pro cx (i=0, j=4)
        val p00 = P[0]; val p04 = P[4]; val p40 = P[32]; val p44 = P[36]
        P[0] = (1.0f - k0) * p00
        P[4] = (1.0f - k0) * p04
        P[32] = -k_vcx * p00 + p40
        P[36] = -k_vcx * p04 + p44

        // Pro cy (i=1, j=5)
        val p11 = P[9]; val p15 = P[13]; val p51 = P[41]; val p55 = P[45]
        P[9] = (1.0f - k1) * p11
        P[13] = (1.0f - k1) * p15
        P[41] = -k_vcy * p11 + p51
        P[45] = -k_vcy * p15 + p55

        // Pro a (i=2, j=6)
        val p22 = P[18]; val p26 = P[22]; val p62 = P[50]; val p66 = P[54]
        P[18] = (1.0f - k2) * p22
        P[22] = (1.0f - k2) * p26
        P[50] = -k_va * p22 + p62
        P[54] = -k_va * p26 + p66

        // Pro h (i=3, j=7)
        val p33 = P[27]; val p37 = P[31]; val p73 = P[59]; val p77 = P[63]
        P[27] = (1.0f - k3) * p33
        P[31] = (1.0f - k3) * p37
        P[59] = -k_vh * p33 + p73
        P[63] = -k_vh * p37 + p77

        // Bezpečnostní pojistky proti degeneraci rozměrů v normalizovaném prostoru 0..1
        if (x[2] < params.minAspectRatio) x[2] = params.minAspectRatio
        if (x[3] < params.minHeight) x[3] = params.minHeight
    }

    /**
     * Zapíše predikovaný RectF do prealokovaného výstupního objektu.
     * Zero-allocation: nevrací nový objekt, zapisuje do existujícího.
     * 
     * @param outRect Cílový RectF, do kterého se zapíší souřadnice
     */
    override fun getPredictedRect(outRect: RectF) {
        val w = x[2] * x[3]
        val h = x[3]
        val left = x[0] - w / 2f
        val top = x[1] - h / 2f
        outRect.set(left, top, left + w, top + h)
    }
}
