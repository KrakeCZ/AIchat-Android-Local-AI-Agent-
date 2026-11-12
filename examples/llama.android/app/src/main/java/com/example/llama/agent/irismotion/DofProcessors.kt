package com.example.llama.agent.irismotion

/**
 * Kontrakt pro izolované zpracování pohybu dle zvoleného počtu stupňů svobody (Degrees of Freedom).
 * Definuje Strategy Pattern pro IrisMotionEngine.
 */
interface DofProcessor {
    /**
     * Zpracuje aktuální profiltrovaná poziční data a vrátí pixelový delta offset pro kurzor.
     * @param filteredX Aktuální vyhlazená X souřadnice (používá se jako baseline pro delta).
     * @param filteredY Aktuální vyhlazená Y souřadnice.
     * @param gazeData Originální nezměněná data z enginu (pro tahání headPose atd).
     * @param referenceScale Referenční rozměr displeje (max šířka/výška) pro sjednocení XY os.
     * @param baseSpeedMultiplier Modifikátor rychlosti z UI nastavení.
     * @param headCompensationGain Extra gain modifikátor z UI nastavení (hlavně pro 6DOF).
     * @return Pair<Float, Float> Reprezentující (pxDeltaX, pxDeltaY) k přičtení do akumulátoru.
     */
    fun process(
            filteredX: Float,
            filteredY: Float,
            gazeData: GazeData,
            referenceScale: Float,
            baseSpeedMultiplier: Float,
            headCompensationGain: Float
    ): Pair<Float, Float>

    fun resetState()
}

/**
 * Dof2Processor - Exkluzivně používá izolované svalové Blendshapes. Ignoruje jakékoliv translace a
 * rotační matice hlavy. Extrémně stabilní chování kurzoru pro UI navigaci.
 */
class Dof2Processor : DofProcessor {
    private var isRelativeInit = false
    private var lastFilteredX = 0f
    private var lastFilteredY = 0f

    override fun process(
            filteredX: Float,
            filteredY: Float,
            gazeData: GazeData,
            referenceScale: Float,
            baseSpeedMultiplier: Float,
            headCompensationGain: Float
    ): Pair<Float, Float> {
        if (!isRelativeInit) {
            lastFilteredX = filteredX
            lastFilteredY = filteredY
            isRelativeInit = true
            return Pair(0f, 0f)
        }

        val deltaX = filteredX - lastFilteredX
        val deltaY = filteredY - lastFilteredY

        lastFilteredX = filteredX
        lastFilteredY = filteredY

        val pxDeltaX = deltaX * referenceScale * baseSpeedMultiplier
        val pxDeltaY = deltaY * referenceScale * baseSpeedMultiplier

        return Pair(pxDeltaX, pxDeltaY)
    }

    override fun resetState() {
        isRelativeInit = false
    }
}

/**
 * Dof4Processor - Hybrid mode. Využívá Pupil Raycasting posunutý o Yaw/Pitch hlavy pro Joystick
 * chování, plus zohlednění osy Z (přiblížení/oddálení) a Roll (Náklon k rameni).
 */
class Dof4Processor : DofProcessor {
    private var isRelativeInit = false
    private var lastFilteredX = 0f
    private var lastFilteredY = 0f
    private var lastHeadRoll = 0f

    override fun process(
            filteredX: Float,
            filteredY: Float,
            gazeData: GazeData,
            referenceScale: Float,
            baseSpeedMultiplier: Float,
            headCompensationGain: Float
    ): Pair<Float, Float> {
        if (!isRelativeInit) {
            lastFilteredX = filteredX
            lastFilteredY = filteredY
            lastHeadRoll = gazeData.headRoll
            isRelativeInit = true
            return Pair(0f, 0f)
        }

        val deltaX = filteredX - lastFilteredX
        val deltaY = filteredY - lastFilteredY

        lastFilteredX = filteredX
        lastFilteredY = filteredY

        var pxDeltaX = deltaX * referenceScale * baseSpeedMultiplier
        var pxDeltaY = deltaY * referenceScale * baseSpeedMultiplier

        val headRoll = gazeData.headRoll
        val headTZ = gazeData.headTranslateZ

        val dRoll = headRoll - lastHeadRoll
        lastHeadRoll = headRoll

        // Přidání offsetů pro 4DOF
        if (dRoll != 0f) {
            pxDeltaX += dRoll * 0.8f * referenceScale
        }
        if (headTZ != 0f) {
            val linearZ = (headTZ * 5f).coerceIn(-0.5f, 1.0f)
            val ddcFactor = 1.0f + linearZ
            if (ddcFactor > 0.1f) {
                pxDeltaX *= ddcFactor
                pxDeltaY *= ddcFactor
            }
        }

        return Pair(pxDeltaX, pxDeltaY)
    }

    override fun resetState() {
        isRelativeInit = false
    }
}

/**
 * Dof6Processor - Nejtěžší výpočetní jednotka pro Spatial/HRANÍ. Dědí chování z 4DoF a navíc
 * přidává čistou prostorovou translaci hlavy (X/Y smeknutí ve 3D).
 */
class Dof6Processor : DofProcessor {
    private var isRelativeInit = false
    private var lastFilteredX = 0f
    private var lastFilteredY = 0f
    private var lastHeadRoll = 0f
    private var lastHeadTX = 0f
    private var lastHeadTY = 0f

    override fun process(
            filteredX: Float,
            filteredY: Float,
            gazeData: GazeData,
            referenceScale: Float,
            baseSpeedMultiplier: Float,
            headCompensationGain: Float
    ): Pair<Float, Float> {
        if (!isRelativeInit) {
            lastFilteredX = filteredX
            lastFilteredY = filteredY
            lastHeadRoll = gazeData.headRoll
            lastHeadTX = gazeData.headTranslateX
            lastHeadTY = gazeData.headTranslateY
            isRelativeInit = true
            return Pair(0f, 0f)
        }

        val deltaX = filteredX - lastFilteredX
        val deltaY = filteredY - lastFilteredY

        lastFilteredX = filteredX
        lastFilteredY = filteredY

        var pxDeltaX = deltaX * referenceScale * baseSpeedMultiplier
        var pxDeltaY =
                deltaY *
                        referenceScale *
                        baseSpeedMultiplier *
                        1.5f // Asymetrická vertikální amplifikace

        val headRoll = gazeData.headRoll
        val headTX = gazeData.headTranslateX
        val headTY = gazeData.headTranslateY
        val headTZ = gazeData.headTranslateZ

        val dRoll = headRoll - lastHeadRoll
        val dTX = headTX - lastHeadTX
        val dTY = headTY - lastHeadTY

        lastHeadRoll = headRoll
        lastHeadTX = headTX
        lastHeadTY = headTY

        // 4DOF addony
        if (dRoll != 0f) {
            pxDeltaX += dRoll * 0.8f * referenceScale
        }
        if (headTZ != 0f) {
            val linearZ = (headTZ * 5f).coerceIn(-0.5f, 1.0f)
            val ddcFactor = 1.0f + linearZ
            if (ddcFactor > 0.1f) {
                pxDeltaX *= ddcFactor
                pxDeltaY *= ddcFactor
            }
        }

        // 6DOF addony (lateral translation smeknutí hlavy)
        if (dTX != 0f || dTY != 0f) {
            val gain = headCompensationGain
            if (gain > 0.001f) {
                pxDeltaX += gain * 2.0f * dTX * referenceScale
                pxDeltaY -=
                        gain *
                                2.0f *
                                dTY *
                                referenceScale // Zrcadlení vertikální osy u přední kamery
            }
        }

        return Pair(pxDeltaX, pxDeltaY)
    }

    override fun resetState() {
        isRelativeInit = false
    }
}
