package morse

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

interface MorseListener {
    /** A decoded character, a space, or "?" for a pattern that is not in the table. */
    fun onChar(text: String)

    /** The letter being received right now, e.g. ".-" ; empty string once the letter was emitted. */
    fun onPattern(pattern: String)
}

/**
 * Envelope frames (5 ms) -> characters. Nothing here depends on an absolute tone, level or speed.
 *
 * Key state: from the *slope* of the envelope (a room smears the release but not the attack), gated relative to
 * the recent peak and to the noise floor.
 *
 * Phase 1, bootstrap (speed unknown): key-down/up runs are only recorded. Once enough exist, a robust fit finds
 * the dot length T (runs that are ~1T / ~3T earn a reward, everything else earns nothing), the runs are cleaned
 * with it (ripple blips removed, notches glued) and laid out into letters, the letter boundary coming from a
 * 2-means clustering of the gaps themselves.
 *
 * Phase 2, live: thresholds scale with the running dot estimate; dot/dash is a 2-means clustering of the last
 * 48 element lengths (log domain); the letter gap is the median of the observed long gaps.
 */
internal class KeyDecoder(
    @Volatile var table: Map<String, String>,
    private val listener: MorseListener,
) {
    // ---- tunables (dB, dB/s, frames, ms) ----
    private val slopeUp = 35.0
    private val slopeDown = -35.0
    private val wMax = 8                    // delay-line half window (frames)
    private val hBoot = 8                   // slope half window before calibration
    private val peakDecayDbS = 2.0
    private val floorRiseDbS = 3.0
    private val minSnrDb = 12.0
    private val onLevelDb = -12.0           // a new key-down must be within 12 dB of the recent peak
    private val minAbsDb = -66.0
    private val bootOnRuns = 14             // key-down runs (>= 20 ms) needed to calibrate
    private val bootSettleMs = 150.0        // ... and the key must have been up this long
    private val dt = FRAME_MS / 1000.0

    // ---- level tracking / delay line ----
    private var peak = -200.0
    private var floorDb = Double.NaN
    private val win = 2 * wMax + 1
    private val ringL = DoubleArray(win)
    private val ringPk = DoubleArray(win)
    private val ringFl = DoubleArray(win)
    private var nFrames = 0L

    // ---- bootstrap ----
    private val boot = ArrayList<DoubleArray>()     // [isOn (1/0), ms] runs recorded while uncalibrated
    private var runLen = 0.0

    // ---- key state / live run cleaner ----
    private var state = false
    private var onRun = 0.0
    private var offRun = 0.0
    private var inElem = false
    private var onAcc = 0.0
    private var gapAcc = 0.0
    private var preGap = 0.0                // silence that preceded the element being built

    // ---- letters / adaptive statistics ----
    private var letter = ArrayList<Double>()
    private var letterOpen = false
    private var wordPending = false
    private var recent = ArrayList<Double>()
    private var longGaps = ArrayList<Double>()
    private var dot = 120.0
    private var dash = 360.0
    private var calibrated = false

    // ---- observable state ----
    val keyDown: Boolean get() = state
    val dotMs: Double get() = dot
    val isCalibrated: Boolean get() = calibrated
    @Volatile var levelDb: Double = -120.0
        private set

    // ---- thresholds scale with the current dot length ----
    private fun minOn() = max(25.0, 0.45 * dot)
    private fun minOff() = max(20.0, 0.40 * dot)
    private fun letterThr() = 2.2 * dot
    private fun letterGap(): Double = if (longGaps.size < 3) Double.NaN else median(longGaps)
    private fun wordThr(): Double {
        val lg = letterGap()
        val w = if (!lg.isNaN()) 1.7 * lg else 6.2 * dot
        return max(w, 4.5 * dot)
    }
    private fun dashThr() = if (dash > 1.8 * dot) sqrt(dot * dash) else 1.8 * dot
    private fun halfWin(): Int =
        if (!calibrated) hBoot else min(wMax, max(2, Math.rint(dot / 15.0).toInt()))

    /** Feed one envelope value (linear amplitude) per 5 ms. */
    fun frame(env: Double) {
        val l = 20.0 * log10(env + 1e-9)
        peak = max(l, peak - peakDecayDbS * dt)
        floorDb = if (floorDb.isNaN()) l else min(l, floorDb + floorRiseDbS * dt)
        val p = (nFrames % win).toInt()
        ringL[p] = l; ringPk[p] = peak; ringFl[p] = floorDb
        nFrames++
        if (nFrames < win) return

        val centerFrame = nFrames - 1 - wMax
        val c = (centerFrame % win).toInt()
        val h = halfWin()
        val hi = ((centerFrame + h) % win).toInt()
        val lo = ((centerFrame - h) % win).toInt()
        val lc = ringL[c]
        val pk = ringPk[c]
        val fl = ringFl[c]
        val slope = (ringL[hi] - ringL[lo]) / (2 * h * dt)
        val rel = lc - pk
        levelDb = rel
        val ok = (pk - fl) >= minSnrDb && pk >= minAbsDb
        val offLevel = max(-30.0, (fl - pk) + 8.0)
        val next = when {
            !ok || rel < offLevel -> false
            slope > slopeUp -> state || rel >= max(onLevelDb, offLevel)
            slope < slopeDown -> false
            else -> state
        }
        if (calibrated) runs(next) else bootRuns(next)
    }

    // =========================== phase 1: bootstrap ===========================

    private fun bootRuns(next: Boolean) {
        if (next != state) {
            if (runLen > 0) boot.add(doubleArrayOf(if (state) 1.0 else 0.0, runLen))
            runLen = 0.0
        }
        runLen += FRAME_MS
        state = next
        if (!next && runLen >= bootSettleMs) {
            var nOn = 0
            for (r in boot) if (r[0] == 1.0 && r[1] >= 20.0) nOn++
            if (nOn >= bootOnRuns) calibrateBoot()
        }
    }

    /** Robust scale fit by reward. A key-down run earns up to 1 if it is ~1T or ~3T, a short silence earns up to
     *  0.5 if it is ~1T. Ripple, noise and long pauses earn nothing, so they cannot distort the fit. */
    private fun fitDot(runs: List<DoubleArray>): Double {
        val ons = ArrayList<Double>()
        val offs = ArrayList<Double>()
        for ((i, r) in runs.withIndex()) {
            if (r[1] < 20.0) continue
            if (r[0] == 1.0) ons.add(r[1]) else if (i > 0) offs.add(r[1])
        }
        val l3 = ln(3.0)
        val sig2 = 0.28 * 0.28
        var bestR = -1.0
        var bestT = 20.0
        for (k in 0 until 110) {
            val t = 20.0 * (450.0 / 20.0).pow(k / 109.0)
            var tot = 0.0
            for (d in ons) {
                val r = ln(d / t)
                tot += max(0.0, 1.0 - min(r * r, (r - l3) * (r - l3)) / sig2)
            }
            for (d in offs) {
                val r = ln(d / t)
                tot += 0.5 * max(0.0, 1.0 - r * r / sig2)
            }
            if (tot > bestR + 1e-9) { bestR = tot; bestT = t }
        }
        return bestT
    }

    private class Run(var on: Boolean, var d: Double, var glue: Boolean = false)

    /** Blips (< 0.45 T key-down) become silence; notches (< 0.4 T silence) inside a key-down disappear. */
    private fun cleanRuns(runs: List<DoubleArray>, t: Double): List<Run> {
        val out = ArrayList<Run>()
        for (r in runs) {
            var on = r[0] == 1.0
            if (on && r[1] < 0.45 * t) on = false
            val last = out.lastOrNull()
            if (last != null && last.on == on) last.d += r[1] else out.add(Run(on, r[1]))
        }
        val res = ArrayList<Run>()
        for ((i, r) in out.withIndex()) {
            val prev = res.lastOrNull()
            if (!r.on && i > 0 && i < out.size - 1 && r.d < 0.4 * t && prev != null && prev.on) {
                prev.d += r.d
                prev.glue = true
            } else if (r.on && prev != null && prev.glue) {
                prev.d += r.d
                prev.glue = false
            } else {
                res.add(Run(r.on, r.d))
            }
        }
        return res
    }

    private fun calibrateBoot() {
        val openOff = runLen
        val runs = ArrayList(boot)
        while (runs.isNotEmpty() && runs[0][0] == 0.0) runs.removeAt(0)
        if (runs.isEmpty()) { boot.clear(); return }
        val t = fitDot(runs)
        val cleaned = cleanRuns(runs, t)
        val raw = ArrayList<DoubleArray>()          // (gap before, duration)
        var gap = 0.0
        for (r in cleaned) {
            if (r.on) { raw.add(doubleArrayOf(gap, r.d)); gap = 0.0 } else gap += r.d
        }
        if (raw.size < 4) { boot.clear(); return }  // nothing usable yet: start over
        recent = ArrayList(raw.map { it[1] }.takeLast(48))
        dot = t; dash = 3 * t
        recluster(true)
        calibrated = true
        parseRaw(raw)
        // hand over to the live state machine: we are in the middle of a silence
        boot.clear()
        state = false
        offRun = openOff
        gapAcc = openOff
        inElem = false
    }

    /** Lay the bootstrap elements out into letters/words. The letter boundary comes from the gaps themselves
     *  (2-means, log domain), not from a speed prior. */
    private fun parseRaw(raw: List<DoubleArray>) {
        val gaps = ArrayList<Double>()
        for (i in 1 until raw.size) if (raw[i][0] > 0) gaps.add(raw[i][0])
        var thr = letterThr()
        if (gaps.size >= 4) {
            val v = DoubleArray(gaps.size) { ln(gaps[it]) }
            var c0 = v.min()
            var c1 = v.max()
            for (iter in 0 until 20) {
                var s0 = 0.0; var s1 = 0.0; var n0 = 0; var n1 = 0
                for (x in v) if (abs(x - c0) <= abs(x - c1)) { s0 += x; n0++ } else { s1 += x; n1++ }
                val nc0 = if (n0 > 0) s0 / n0 else c0
                val nc1 = if (n1 > 0) s1 / n1 else c1
                val same = abs(nc0 - c0) <= 1e-8 + 1e-5 * abs(c0) && abs(nc1 - c1) <= 1e-8 + 1e-5 * abs(c1)
                c0 = nc0; c1 = nc1
                if (same) break
            }
            if (exp(c1 - c0) >= 2.0) thr = exp((c0 + c1) / 2)
        }
        longGaps = ArrayList(gaps.filter { it >= thr }.takeLast(32))
        var cur = ArrayList<Double>()
        for (i in raw.indices) {
            val g = raw[i][0]
            if (i > 0 && g >= thr) {
                listener.onChar(toChar(cur)); cur = ArrayList()
                if (g >= wordThr()) listener.onChar(" ")
            }
            cur.add(raw[i][1])
        }
        letter = cur
        letterOpen = cur.isNotEmpty()
        if (letterOpen) listener.onPattern(patternOf(letter))
    }

    // =========================== phase 2: live ===========================

    private fun runs(next: Boolean) {
        val d = FRAME_MS
        if (next) {
            if (!state) {                                   // OFF -> ON
                if (inElem && offRun < minOff()) onAcc += offRun    // notch inside an element: merge
                onRun = 0.0
            }
            onRun += d
            offRun = 0.0
        } else {
            if (state) {                                    // ON -> OFF
                val run = onRun
                if (!inElem && run < minOn()) {
                    gapAcc += run                           // blip: treat as silence
                } else {
                    if (!inElem) preGap = gapAcc
                    onAcc = (if (inElem) onAcc else 0.0) + run
                    inElem = true
                }
                onRun = 0.0
                offRun = 0.0
            }
            offRun += d
            if (inElem) {
                if (offRun >= minOff()) {
                    element(preGap, onAcc)
                    inElem = false
                    gapAcc = offRun
                }
            } else {
                gapAcc += d
                gapChecks()
            }
        }
        state = next
    }

    private fun element(pre: Double, dur: Double) {
        recent.add(dur)
        if (recent.size > 48) recent.removeAt(0)
        recluster(false)
        if (pre >= letterThr()) {
            longGaps.add(pre)
            if (longGaps.size > 32) longGaps.removeAt(0)
        }
        letter.add(dur)
        letterOpen = true
        listener.onPattern(patternOf(letter))
    }

    private fun recluster(force: Boolean) {
        val n = recent.size
        if (n < (if (force) 2 else 6)) return
        val v = DoubleArray(n) { ln(recent[it]) }
        val sortedV = v.copyOf().also { it.sort() }
        var c0 = quantile(sortedV, 0.15)
        var c1 = quantile(sortedV, 0.85)
        for (iter in 0 until 12) {
            var s0 = 0.0; var s1 = 0.0; var n0 = 0; var n1 = 0
            for (x in v) if (abs(x - c0) <= abs(x - c1)) { s0 += x; n0++ } else { s1 += x; n1++ }
            val nc0 = if (n0 > 0) s0 / n0 else c0
            val nc1 = if (n1 > 0) s1 / n1 else c1
            val same = abs(nc0 - c0) <= 1e-8 + 1e-5 * abs(c0) && abs(nc1 - c1) <= 1e-8 + 1e-5 * abs(c1)
            c0 = nc0; c1 = nc1
            if (same) break
        }
        val d0 = exp(c0)
        val d1 = exp(c1)
        if (d1 / d0 > 4.5) {
            // a dot/dash pair never differs that much: the low cluster is debris. Drop it and look again.
            val cut = sqrt(d0 * d1)
            val keep = ArrayList(recent.filter { it > cut })
            if (keep.size >= 3) {
                recent = keep
                recluster(force)
            }
            return
        }
        if (d1 / d0 >= 1.8) {
            dot = d0; dash = d1
        } else {                                            // one length class only
            val m = exp(v.average())
            if (m < 1.7 * dot) { dot = m; dash = 3 * m } else if (m > 2.2 * dot) { dot = m / 3; dash = m }
        }
    }

    private fun gapChecks() {
        if (letterOpen && gapAcc >= letterThr()) {
            emitLetter()
            wordPending = true
        }
        if (wordPending && gapAcc >= wordThr()) {
            listener.onChar(" ")
            wordPending = false
        }
    }

    private fun emitLetter() {
        letterOpen = false
        val pat = letter
        letter = ArrayList()
        listener.onPattern("")
        listener.onChar(toChar(pat))
    }

    private fun patternOf(durs: List<Double>): String {
        val thr = dashThr()
        val sb = StringBuilder()
        for (x in durs) sb.append(if (x > thr) '-' else '.')
        return sb.toString()
    }

    private fun toChar(durs: List<Double>): String = table[patternOf(durs)] ?: "?"

    /** Flush whatever is pending (end of input). */
    fun finish() {
        if (!calibrated) {
            if (state || runLen > 0) boot.add(doubleArrayOf(if (state) 1.0 else 0.0, runLen))
            var nOn = 0
            for (r in boot) if (r[0] == 1.0 && r[1] >= 20.0) nOn++
            if (nOn >= 4) calibrateBoot()
        } else if (inElem) {
            element(preGap, onAcc)
            inElem = false
        }
        if (calibrated && letterOpen) { gapAcc = 1e9; gapChecks() }
    }

    private fun quantile(sorted: DoubleArray, q: Double): Double {
        val pos = q * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = min(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    private fun median(x: List<Double>): Double {
        val s = x.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }
}
