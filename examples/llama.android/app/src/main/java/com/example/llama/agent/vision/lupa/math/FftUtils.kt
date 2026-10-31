package com.example.llama.agent.vision.lupa.math

/**
 * Cooley-Tukey radix-2 FFT — pure Kotlin, zero dependencies.
 * Používán Wiener Deconvolution a Phase Correlation pluginy.
 * Vyžaduje délku pole == mocnina 2.
 */
object FftUtils {

    /** 1D FFT in-place. re, im musí mít stejnou délku == mocnina 2. */
    fun fft(re: FloatArray, im: FloatArray, inverse: Boolean = false) {
        val n = re.size
        require(n > 0 && n and (n - 1) == 0) { "FFT délka musí být mocnina 2, dostali jsme: $n" }

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        // Butterfly stages
        var len = 2
        while (len <= n) {
            val ang = (if (inverse) 2.0 else -2.0) * Math.PI / len
            val wRe = Math.cos(ang).toFloat()
            val wIm = Math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var uRe = 1f; var uIm = 0f
                for (k in 0 until len / 2) {
                    val eRe = re[i + k]; val eIm = im[i + k]
                    val oRe = re[i + k + len / 2] * uRe - im[i + k + len / 2] * uIm
                    val oIm = re[i + k + len / 2] * uIm + im[i + k + len / 2] * uRe
                    re[i + k] = eRe + oRe;  im[i + k] = eIm + oIm
                    re[i + k + len / 2] = eRe - oRe; im[i + k + len / 2] = eIm - oIm
                    val nu = uRe * wRe - uIm * wIm
                    uIm = uRe * wIm + uIm * wRe; uRe = nu
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            val inv = 1f / n
            for (i in 0 until n) { re[i] *= inv; im[i] *= inv }
        }
    }

    /** 2D FFT separabilní — nejprve řádky, pak sloupce. */
    fun fft2D(re: Array<FloatArray>, im: Array<FloatArray>, inverse: Boolean = false) {
        val rows = re.size
        val cols = re[0].size
        for (r in 0 until rows) fft(re[r], im[r], inverse)
        val colRe = FloatArray(rows); val colIm = FloatArray(rows)
        for (c in 0 until cols) {
            for (r in 0 until rows) { colRe[r] = re[r][c]; colIm[r] = im[r][c] }
            fft(colRe, colIm, inverse)
            for (r in 0 until rows) { re[r][c] = colRe[r]; im[r][c] = colIm[r] }
        }
    }

    /** Vrátí nejmenší mocninu 2 ≥ n. */
    fun nextPow2(n: Int): Int { var p = 1; while (p < n) p = p shl 1; return p }
}