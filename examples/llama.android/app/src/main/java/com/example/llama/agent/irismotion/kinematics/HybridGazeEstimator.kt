package com.example.llama.agent.irismotion.kinematics

import android.opengl.Matrix
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.sqrt

/**
 * [HybridGazeEstimator] represents a professional-level hardware-agnostic 3D Inverse Kinematics eye
 * tracking engine. It extracts 3D feature points from the Face Mesh, constructs an Un-rotated Head
 * Coordinate Space (HCS) via `android.opengl.Matrix`, and projects the true geometrical eyeball
 * rotation without 2D perspective cross-talk.
 *
 * It uses saccade gating ([SaccadeFilter]) to distinguish macro-jumps (zero lag) from
 * micro-fixations (high filter).
 */
class HybridGazeEstimator {

    private val TAG = "HybridGazeEstimator"

    // 16-float pole pro OpenGL Matrix Math
    private val invertedPoseMatrix = FloatArray(16)

    // Scratch Arrays pro transformace (aby se predeslo GC alokacím)
    private val ptIn = FloatArray(4)
    private val ptOut = FloatArray(4)

    // Střed obličeje (nos) pro korektní rotaci 3D IK transformace
    private var originX = 0f
    private var originY = 0f
    private var originZ = 0f

    // Aspect Ratio korekce
    private var imgW = 1f
    private var imgH = 1f

    // 3D Point Container
    data class Point3D(val x: Float, val y: Float, val z: Float)

    // Strukturovaný výsledek estimace
    data class GazeResult(
        val x: Float,
        val y: Float,
        val openness: Float,
        val isSaccade: Boolean
    )

    // Filtry
    private val saccadeFilter = SaccadeFilter()
    private val opennessFilter = OpennessFilter()

    var fixationDriftRate: Float
        get() = saccadeFilter.fixationDriftRate
        set(value) { saccadeFilter.fixationDriftRate = value }

    /**
     * Vypočítá čistý vektor zraku (X, Y) s kompenzací rotace hlavy.
     * @return Pair(GazeX, GazeY) v normalizovaném zlomku [0.0 - 1.0].
     */
    fun estimateGaze(
            result: FaceLandmarkerResult,
            imageWidth: Int,
            imageHeight: Int,
            trackedEyeMode: Int,
            isMirrored: Boolean,
            headYaw: Float,
            headPitch: Float,
            headTrackingDoF: Int,
            fusionAlpha: Float,
            fusionBeta: Float,
            saccadeVelocityThreshold: Float,
            saccadeFixationBox: Float,
            neuralGazeX: Float = 0.5f,
            neuralGazeY: Float = 0.5f,
            neuralWeight: Float = 0.5f
    ): GazeResult {
        val landmarks = result.faceLandmarks()[0]

        imgW = imageWidth.toFloat()
        imgH = imageHeight.toFloat()

        // Uložení středu obličeje pro správnou osu 3D rotace a převod do pixel izotropního prostoru
        val nose = landmarks[1]
        originX = nose.x() * imgW
        originY = nose.y() * imgH
        originZ = nose.z() * imgW

        // Získáme transformační matici hlavy (pose)
        val matrices = result.facialTransformationMatrixes().orElse(null)
        val m = if (matrices != null && matrices.isNotEmpty()) matrices[0] else null

        var hasValidIK = false
        if (m != null && m.size >= 16) {
            // Vytvoříme inverzní matici pro "Un-rotaci" (Odrotování lebky do strnulé pozice)
            // Křížový problém: MediaPipe 'm' obsahuje translaci v [cm], ale landmarks jsou v
            // [0..1].
            // Řešení: Extrahujeme pouze 3x3 Rotační matici do identity a invertujeme ji.
            // Tím se vyhneme skew/drift artefaktům a hyper-speedu z w-divize.
            val rotMatrix = FloatArray(16)
            Matrix.setIdentityM(rotMatrix, 0)
            rotMatrix[0] = m[0]
            rotMatrix[1] = m[1]
            rotMatrix[2] = m[2]
            rotMatrix[4] = m[4]
            rotMatrix[5] = m[5]
            rotMatrix[6] = m[6]
            rotMatrix[8] = m[8]
            rotMatrix[9] = m[9]
            rotMatrix[10] = m[10]

            hasValidIK = Matrix.invertM(invertedPoseMatrix, 0, rotMatrix, 0)
        }

        val clampedWeight = neuralWeight.coerceIn(0f, 1f)

        // ==================== 1. VÝPOČET OČÍ V PROSTORU (3D IK vs 2D Fallback)
        // ====================

        val leftEyeGaze =
                computeEyeGaze(
                        landmarks,
                        468, // IRIS_LEFT_INDEX
                        33, // L_EYE_LEFT_OUTER
                        133, // L_EYE_RIGHT_INNER
                        159, // L_EYE_TOP
                        145, // L_EYE_BOTTOM
                        hasValidIK,
                        clampedWeight
                )

        val rightEyeGaze =
                computeEyeGaze(
                        landmarks,
                        473, // IRIS_RIGHT_INDEX
                        362, // R_EYE_LEFT_INNER
                        263, // R_EYE_RIGHT_OUTER
                        386, // R_EYE_TOP
                        374, // R_EYE_BOTTOM
                        hasValidIK,
                        clampedWeight
                )

        // ==================== 2. SELEKCE OKA A MULTIPLEXER ====================
        val eyeVecX: Float
        val eyeVecY: Float
        val outOpenness: Float

        when (trackedEyeMode) {
            1 -> { // Uživatelovo Levé = MediaPipe Right (Zrcadlo)
                eyeVecX = rightEyeGaze.first
                eyeVecY = rightEyeGaze.second
                outOpenness = rightEyeGaze.third
            }
            2 -> { // Uživatelovo Pravé = MediaPipe Left
                eyeVecX = leftEyeGaze.first
                eyeVecY = leftEyeGaze.second
                outOpenness = leftEyeGaze.third
            }
            else -> { // Průměrování obou (Maximum robustness, Tobii/Beam approach)
                eyeVecX = (leftEyeGaze.first + rightEyeGaze.first) / 2f
                eyeVecY = (leftEyeGaze.second + rightEyeGaze.second) / 2f
                outOpenness = (leftEyeGaze.third + rightEyeGaze.third) / 2f
            }
        }

        // Zrcadlení enginu
        val mirroredX = if (isMirrored) 1.0f - eyeVecX else eyeVecX
        val mirroredY = eyeVecY

        // ==================== 3. HYBRIDNÍ 4/6DOF FÚZE HLAVY ====================
        // GazeFusionX = Fyzický oční vektor + Rotace Hlavy (Yaw/Pitch) jako offset v turret módu
        val yawContribution = if (headTrackingDoF >= 4) headYaw * fusionAlpha else 0f
        val pitchContribution = if (headTrackingDoF >= 4) headPitch * fusionBeta else 0f

        val ikGazeX = mirroredX + yawContribution
        val ikGazeY = mirroredY + pitchContribution

        // ==================== 4. SENSOR FUSION (IK + Neural Blendshapes) ====================
        // Tři datové kanály sjednoceny do jednoho dokonalého vektoru pohledu:
        // - IK Vector: 3D fyzický skluz zorničky s nulovou rotační kontaminací (od-rotovaný)
        // - Neural Vector: NPU-trénovaný svalový vektor z eyeLook* blendshapes (8 kategorií)
        // clampedWeight (Slider 0-100%):
        // 0.0 = 100% Čisté 2D IK (Legacy stabilní režim)
        // 1.0 = 100% Neural NPU + 3D IK (Maximální rozsah)

        val fusedX = ikGazeX * (1f - clampedWeight) + neuralGazeX * clampedWeight
        val fusedY = ikGazeY * (1f - clampedWeight) + neuralGazeY * clampedWeight

        // ==================== 5. MIKRO-SAKÁDOVÝ FILTR ====================
        // Prostorová Hystereze na sjednoceném Fused Vectoru — Zero-Lag s blokováním jitteru
        val (saccadeX, saccadeY, isSaccade) =
                saccadeFilter.process(
                        fusedX,
                        fusedY,
                        System.currentTimeMillis(),
                        saccadeVelocityThreshold,
                        saccadeFixationBox
                )

        // ==================== 6. EAR STABILIZACE (Microsaccade Filtering) ============
        // Aplikujeme box-hysteresi na oční otevření, aby nodding nezpůsoboval falešné bliky.
        val smoothOpenness = opennessFilter.process(outOpenness, 0.03f)

        return GazeResult(saccadeX, saccadeY, smoothOpenness, isSaccade)
    }

    private fun computeEyeGaze(
            landmarks: List<NormalizedLandmark>,
            irisIdx: Int,
            cornerLeftIdx: Int,
            cornerRightIdx: Int,
            cornerTopIdx: Int,
            cornerBottomIdx: Int,
            hasValidIK: Boolean,
            sliderWeight: Float
    ): Triple<Float, Float, Float> {
        val iris = landmarks[irisIdx]
        val cLeft = landmarks[cornerLeftIdx]
        val cRight = landmarks[cornerRightIdx]
        val cTop = landmarks[cornerTopIdx]
        val cBottom = landmarks[cornerBottomIdx]

        // 1. Získáme 3D body z IK (odrotované do frontálního prostoru)
        val pIris = if (hasValidIK) applyIK(iris) else Point3D(iris.x(), iris.y(), iris.z())
        val pLeft = if (hasValidIK) applyIK(cLeft) else Point3D(cLeft.x(), cLeft.y(), cLeft.z())
        val pRight = if (hasValidIK) applyIK(cRight) else Point3D(cRight.x(), cRight.y(), cRight.z())
        val pTop = if (hasValidIK) applyIK(cTop) else Point3D(cTop.x(), cTop.y(), cTop.z())
        val pBottom = if (hasValidIK) applyIK(cBottom) else Point3D(cBottom.x(), cBottom.y(), cBottom.z())

        val rangeX3D = Math.abs(pRight.x - pLeft.x)

        // [MASTER IT FIX]: Výpočet v izotropním (pixel) prostoru na ODROTOVANÝCH (IK) bodech.
        // Použití pLeft, pRight, pTop, pBottom (místo raw cLeft/cRight/...) eliminuje projekční
        // zkracování vzdálenosti víček při Pitch (nahoru/dolů) a Yaw (vlevo/vpravo) rotaci.
        val dxW = (pRight.x - pLeft.x) * imgW
        val dyW = (pRight.y - pLeft.y) * imgH
        val dzW = (pRight.z - pLeft.z) * imgW
        val widthEuclidean = Math.sqrt((dxW * dxW + dyW * dyW + dzW * dzW).toDouble()).toFloat()

        val dxH = (pBottom.x - pTop.x) * imgW
        val dyH = (pBottom.y - pTop.y) * imgH
        val dzH = (pBottom.z - pTop.z) * imgW
        val heightEuclidean = Math.sqrt((dxH * dxH + dyH * dyH + dzH * dzH).toDouble()).toFloat()

        val trueEar3D = if (widthEuclidean > 1e-6f) heightEuclidean / widthEuclidean else 0f

        // ========================================================
        // 1. 3D INVERSE KINEMATICS (IK) - Prostorový výpočet
        // ========================================================
        // Oprava fyziologického bugu: Horní a spodní víčko se pohybují spolu s okem (Lid-Saccade
        // coupling).
        // Pokud bychom použili víčka jako okraje, hodnota Y by zůstala navždy na 0.5 (oko se hýbe
        // zároveň s okraji).
        // Používáme stabilní oční koutky (cLeft, cRight) jako pevnou kosterní kotvu pro osa Y!

        // KRITICKÝ FIX: Fyzický pupil se vertikálně vůči koutkům posouvá jen o cca 2-3 mm.
        // Původní hodnota 1.5f vytvořila gigantický bounding box, pro který byl 2mm posun oka téměř
        // neviditelný (osa Y zamrzlá).
        // Snížením na 0.25f brutálně amplifikujeme tento slabý fyzikální vertikální signál!
        val rangeY3D = rangeX3D * 0.25f

        val cX3D = (pLeft.x + pRight.x) / 2f
        val cY3D = (pLeft.y + pRight.y) / 2f // Střed na vertikále očních koutků

        val pX3D = if (rangeX3D > 1e-6f) 0.5f + ((pIris.x - cX3D) / rangeX3D) else 0.5f
        var pY3D = if (rangeY3D > 1e-6f) 0.5f + ((pIris.y - cY3D) / rangeY3D) else 0.5f

        // [MASTER IT IMPLEMENTACE] Metoda 2: Asymetrický Pre-Boost pro 3D.
        // Fyzicky je velmi obtížné dívat se očima "pod lebku". Pokud se podíváme nahoru
        // (hodnoty < 0.5), vynásobíme sílu pohybu 2x, abychom překonali asymetrii tváře.
        if (pY3D < 0.5f) {
            pY3D = 0.5f - ((0.5f - pY3D) * 2.0f)
        }

        // ========================================================
        // 2. 2D AFFINE PROJECTION - Invariantní planární výpočet
        // ========================================================
        fun proj2D(p: NormalizedLandmark, a: NormalizedLandmark, b: NormalizedLandmark): Float {
            val dx = b.x() - a.x()
            val dy = b.y() - a.y()
            val lenSq = dx * dx + dy * dy
            if (lenSq < 1e-6f) return 0.5f
            val px = p.x() - a.x()
            val py = p.y() - a.y()
            return (px * dx + py * dy) / lenSq
        }

        // Pro Y osu promítneme bod na umělou vertikální úsečku procházející středem koutků
        val midX2D = (cLeft.x() + cRight.x()) / 2f
        val midY2D = (cLeft.y() + cRight.y()) / 2f

        // Geometrická propast pro 2D je také smrštěna pro shodnou vertikální amplifikaci
        val range2D = Math.abs(cRight.x() - cLeft.x()) * 0.25f

        // Umělá vertikální linie pro projekci (čistě pravoúhlá k horizontální ose očí v 2D)
        val pseudoTopY = midY2D - (range2D / 2f)
        val pseudoBottomY = midY2D + (range2D / 2f)

        val pseudoTop = NormalizedLandmark.create(midX2D, pseudoTopY, 0f)
        val pseudoBottom = NormalizedLandmark.create(midX2D, pseudoBottomY, 0f)

        val pX2D = proj2D(iris, cLeft, cRight)
        val pY2D = proj2D(iris, pseudoTop, pseudoBottom)

        // ========================================================
        // 3. SYNCHRONOUS FIXATION MASK & SLIDER BLEND
        // ========================================================

        // Výzkumná fixační maska (Dynamická fúze 2D a 3D podle úhlu pohledu)
        val divX = Math.abs(pX3D - pX2D)
        val divY = Math.abs(pY3D - pY2D)

        val weight2DX = (0.5f + divX * 5.0f).coerceIn(0.5f, 1.0f)
        val weight2DY = (0.5f + divY * 5.0f).coerceIn(0.5f, 1.0f)

        val weight3DX = 1.0f - weight2DX
        val weight3DY = 1.0f - weight2DY

        val dynamicFusedX = (pX2D * weight2DX) + (pX3D * weight3DX)
        val dynamicFusedY = (pY2D * weight2DY) + (pY3D * weight3DY)

        // [MASTER IT IMPLEMENTATION]: Uživatelův Slider (IK : Neural 0-100%) funguje jako
        // Master Crossfader mezi PURE 2D (legacy stabilita) a HYBRID 3D/Neural.
        // sliderWeight = 0.0 -> PURE p2D (Zero-Lag, absolutní 1:1 HW myš)
        // sliderWeight = 1.0 -> PURE dynamicFused 3D (kompletní prostorový flex)
        val finalIkX = pX2D * (1f - sliderWeight) + dynamicFusedX * sliderWeight
        val finalIkY = pY2D * (1f - sliderWeight) + dynamicFusedY * sliderWeight

        return Triple(finalIkX, finalIkY, trueEar3D)
    }

    /**
     * Un-rotates a 3D MediaPipe landmark point by multiplying it with the inverted head pose
     * matrix.
     */
    private fun applyIK(lm: NormalizedLandmark): Point3D {
        // 1. Přesun bodu do počátku (střed rotace hlavy - nos) a převod do Izotropního (Pixel)
        // prostoru
        // Z NormalizedLandmark je Y komprimováno Aspect Ratiem. Zdílí měřítko s X.
        ptIn[0] = (lm.x() * imgW) - originX
        ptIn[1] = (lm.y() * imgH) - originY
        ptIn[2] = (lm.z() * imgW) - originZ
        ptIn[3] = 1.0f

        // 2. Aplikace inverzní rotační matice hlavy (čisté od-rotování 3D sítě)
        Matrix.multiplyMV(ptOut, 0, invertedPoseMatrix, 0, ptIn, 0)

        // 3. Zpětná homogenizace, vrácení origin offsetu a převod zpět do Normalized prostoru
        val w = if (ptOut[3] != 0f) ptOut[3] else 1.0f
        return Point3D(
                ((ptOut[0] / w) + originX) / imgW,
                ((ptOut[1] / w) + originY) / imgH,
                ((ptOut[2] / w) + originZ) / imgW
        )
    }
}

/**
 * Filter pro rozlišení zrakové Fixace vs Saccade (skoku). Vyvíjeno na principech PyGaze Pupil
 * Tracker algorithm.
 */
class SaccadeFilter {
    private var lastX = -1f
    private var lastY = -1f
    private var lastTime = 0L
    var fixationDriftRate: Float = 0.05f

    fun process(
            currentX: Float,
            currentY: Float,
            nowMs: Long,
            velocityThreshold: Float,
            fixationSmoothingBox: Float
    ): Triple<Float, Float, Boolean> {
        if (lastTime == 0L || lastX == -1f) {
            lastX = currentX
            lastY = currentY
            lastTime = nowMs
            return Triple(currentX, currentY, false)
        }

        val dt = (nowMs - lastTime) / 1000f // Delta času v sekundách
        if (dt <= 0f) return Triple(lastX, lastY, false)

        val dx = currentX - lastX
        val dy = currentY - lastY
        val distance = sqrt(dx * dx + dy * dy)
        val velocity = distance / dt

        var isSaccade = false

        // Saccade vs Fixation Gate (Zrcadlí de facto optický senzor HW pc myši)
        // 1. ZÁMĚRNÝ POHYB OKA (Pursuit/Saccade) nebo překročení šumového boxu
        if (velocity > velocityThreshold) {
            // SACCADE: Oko udělalo rychlý skok.
            isSaccade = true
            // Pustíme signál naplno (ZERO LAG SNAP - Okamžitý přesun jako u PC myši).
            lastX = currentX
            lastY = currentY
        } else {
            // FIXATION / SMOOTH PURSUIT: Prostorová Hystereze (True PC Mouse Sensor)
            // Problém: Jakékoliv průběžné časové filtrování (alpha / exponenciála) způsobuje
            // latenci a pocit "táhnutí", protože enginům trvá n snímků, než dohoní fyzické oko.
            // Řešení: Optická myš NIKDY neaplikuje zpoždění na tah. Myš má pouze hardwarovou mrtvou
            // zónu.
            if (distance > fixationSmoothingBox) {
                // Jakmile zornička opustí šumový box, přesouváme kurzor OKAMŽITĚ 1:1 bez časové
                // ztráty,
                // ale "táhneme mrtvou zónu s sebou" (Spatial Hysteresis). Tím dokonale blokujeme
                // jitter,
                // a zároveň 100% přetavíme fyziologický pohyb oka na absolutně přesné MOUSE pixely!
                val excessDistance = distance - fixationSmoothingBox
                val ratio = excessDistance / distance

                lastX = lastX + (dx * ratio)
                lastY = lastY + (dy * ratio)
            } else {
                // UVNITŘ BOXXU: Zamezení "Gravitační Rohatky" (Ratcheting effect).
                // Mrtvá zóna působí jako dioda na asymetrický šum (např. mrknutí vždy škubne dolů
                // a prorazí box, otevření oka škubne nahoru slaběji a uvízne v boxu).
                // ŘEŠENÍ: Extrémně jemně přitahujeme lastY zpět ke středu aktuálního vidění.
                // Tento mikroskopický drift (výchozí 5 % za snímek, neviditelný pro člověka) dokonale zruší
                // jakoukoliv gravitační ztrátu a udrží absolutní tracking čistý.
                lastX = lastX + (dx * fixationDriftRate)
                lastY = lastY + (dy * fixationDriftRate)
            }
        }

        lastTime = nowMs
        return Triple(lastX, lastY, isSaccade)
    }
}

/**
 * Filter pro stabilizaci očního otevření (EAR).
 * Mikrosakadické chvění víček je odfiltrováno pomocí prostorové hystereze.
 */
class OpennessFilter {
    private var lastOpenness = 1.0f

    fun process(current: Float, boxSize: Float = 0.03f): Float {
        if (Math.abs(current - lastOpenness) > boxSize) {
            lastOpenness = current
        }
        return lastOpenness
    }
}
