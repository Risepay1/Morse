package morse

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10

internal const val SAMPLE_RATE = 16000

/**
 * Finds the Morse carrier. A Goertzel bank (300..1200 Hz, 10 Hz step) runs over 1024-sample Hann blocks,
 * the power spectrum is smoothed with an EMA, and a peak that stands 12 dB above the median bin is taken
 * as the tone. The frequency may drift (tracked), and a different stable tone is adopted after ~1.5 s.
 */
internal class ToneTracker {
    private val nBins = 91
    private val block = 1024
    private val freqs = DoubleArray(nBins) { 300.0 + 10.0 * it }
    private val coeff = DoubleArray(nBins) { 2.0 * cos(2.0 * PI * freqs[it] / SAMPLE_RATE) }
    private val window = DoubleArray(block) { 0.5 - 0.5 * cos(2.0 * PI * it / (block - 1)) }
    private val ema = DoubleArray(nBins)
    private var emaInit = false
    private val buf = DoubleArray(block)
    private var fill = 0
    private val sorted = DoubleArray(nBins)

    private var cand = Double.NaN
    private var candBlocks = 0

    /** Locked carrier frequency in Hz, or NaN while no clear tone has been heard. */
    var f0: Double = Double.NaN
        private set
    var tonalityDb: Double = 0.0
        private set
    val locked: Boolean get() = !f0.isNaN()

    fun feed(x: DoubleArray, n: Int) {
        for (i in 0 until n) {
            buf[fill++] = x[i]
            if (fill == block) {
                processBlock()
                fill = 0
            }
        }
    }

    private fun processBlock() {
        val p = DoubleArray(nBins)
        for (k in 0 until nBins) {
            var s1 = 0.0
            var s2 = 0.0
            val c = coeff[k]
            for (i in 0 until block) {
                val s0 = buf[i] * window[i] + c * s1 - s2
                s2 = s1
                s1 = s0
            }
            p[k] = s1 * s1 + s2 * s2 - c * s1 * s2
        }
        if (emaInit) {
            for (k in 0 until nBins) ema[k] = 0.92 * ema[k] + 0.08 * p[k]
        } else {
            System.arraycopy(p, 0, ema, 0, nBins)
            emaInit = true
        }
        update()
    }

    private fun update() {
        var imax = 0
        for (k in 1 until nBins) if (ema[k] > ema[imax]) imax = k
        System.arraycopy(ema, 0, sorted, 0, nBins)
        sorted.sort()
        val med = sorted[nBins / 2] + 1e-18
        tonalityDb = 10.0 * log10(ema[imax] / med + 1e-18)
        if (tonalityDb < 12.0 || imax == 0 || imax == nBins - 1) return
        val a = ln(ema[imax - 1] + 1e-18)
        val b = ln(ema[imax] + 1e-18)
        val c = ln(ema[imax + 1] + 1e-18)
        val den = a - 2 * b + c
        val f = freqs[imax] + (if (den != 0.0) 0.5 * (a - c) / den * 10.0 else 0.0)
        if (f0.isNaN()) {
            f0 = f
        } else if (abs(f - f0) < 25.0) {
            f0 += 0.2 * (f - f0)
            cand = Double.NaN
        } else {
            if (!cand.isNaN() && abs(f - cand) < 25.0) {
                candBlocks++
            } else {
                cand = f
                candBlocks = 1
            }
            if (candBlocks >= 24) {
                f0 = f
                cand = Double.NaN
            }
        }
    }
}
