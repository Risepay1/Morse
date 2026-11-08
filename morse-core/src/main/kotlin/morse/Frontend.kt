package morse

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

internal const val HOP = 80          // 5 ms at 16 kHz
internal const val BOX = 240         // 15 ms boxcar, about +-65 Hz bandwidth
internal const val FRAME_MS = HOP * 1000.0 / SAMPLE_RATE

/** Complex demodulation at the tracked carrier followed by a boxcar; emits one envelope value per 5 ms. */
internal class Frontend {
    private var pr = 1.0                  // phasor e^{-j phi} = (cos phi, -sin phi)
    private var pi = 0.0
    private val ringRe = DoubleArray(BOX)
    private val ringIm = DoubleArray(BOX)
    private var pos = 0
    private var sumRe = 0.0
    private var sumIm = 0.0
    private var count = 0L

    fun feed(x: DoubleArray, n: Int, f0: Double, out: (Double) -> Unit) {
        val w = 2.0 * PI * f0 / SAMPLE_RATE
        val cw = cos(w)
        val sw = sin(w)
        for (i in 0 until n) {
            val s = x[i]
            val re = s * pr
            val im = s * pi
            val npr = pr * cw + pi * sw
            val npi = -pr * sw + pi * cw
            pr = npr
            pi = npi
            sumRe += re - ringRe[pos]
            sumIm += im - ringIm[pos]
            ringRe[pos] = re
            ringIm[pos] = im
            pos = if (pos + 1 == BOX) 0 else pos + 1
            count++
            if ((count and 1023L) == 0L) renormalize()
            if (count % HOP == 0L) out(hypot(sumRe, sumIm) / BOX)
        }
    }

    private fun renormalize() {
        val nrm = sqrt(pr * pr + pi * pi)
        pr /= nrm
        pi /= nrm
        if ((count and ((1L shl 20) - 1)) == 0L) {   // refresh running sums against drift
            var a = 0.0
            var b = 0.0
            for (k in 0 until BOX) {
                a += ringRe[k]
                b += ringIm[k]
            }
            sumRe = a
            sumIm = b
        }
    }
}
