package morse.cli

import morse.Alphabet
import morse.MorseDecoder
import morse.MorseListener
import morse.MorseTables
import java.util.Random
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic self-test, no audio files needed:  ./gradlew :morse-core:run -PmainClass=morse.cli.SelfTestKt
 * (or run `morse.cli.SelfTestKt` from the IDE). Standard 1:3:7 timing, a tone at several pitches and speeds,
 * a slow-release "room" envelope and white noise. Prints one line per case.
 */
fun main() {
    val text = "PARIS CQ DE R2D2 TEST 73 DE MORSE"
    val code = HashMap<Char, String>()
    for ((k, v) in MorseTables.build(Alphabet.EN)) if (v.length == 1) code[v[0]] = k

    data class Case(val tone: Double, val wpm: Double, val rt60: Double, val snrDb: Double)
    val cases = listOf(
        Case(450.0, 8.0, 0.0, 30.0), Case(600.0, 12.0, 0.0, 20.0), Case(800.0, 16.0, 0.0, 10.0),
        Case(1000.0, 20.0, 0.0, 10.0), Case(600.0, 12.0, 0.6, 20.0), Case(700.0, 10.0, 0.6, 5.0),
        Case(550.0, 15.0, 0.4, 10.0), Case(900.0, 6.0, 0.6, 10.0), Case(500.0, 25.0, 0.0, 15.0),
    )
    var worst = 0
    for (c in cases) {
        val got = decode(synth(text, code, c.tone, c.wpm, c.rt60, c.snrDb))
        // the first few characters are used for calibration; judge the rest
        val skip = 6
        val d = edit(got.drop(skip), text.drop(skip))
        worst = max(worst, d)
        println("tone=%4.0f Hz  %4.1f WPM  rt60=%.1f  snr=%2.0f dB  edits(after first %d chars)=%d".format(
            c.tone, c.wpm, c.rt60, c.snrDb, skip, d))
        println("    $got")
    }
    println(if (worst == 0) "ALL CLEAN" else "worst case: $worst edits")
}

private fun synth(
    text: String, code: Map<Char, String>, tone: Double, wpm: Double, rt60: Double, snrDb: Double,
): DoubleArray {
    val sr = 16000
    val unit = 1.2 / wpm
    val key = ArrayList<Boolean>()
    fun add(on: Boolean, units: Int) { repeat((units * unit * sr).toInt()) { key.add(on) } }
    add(false, 3)
    for ((wi, word) in text.split(' ').withIndex()) {
        if (wi > 0) add(false, 7)
        for ((ci, ch) in word.withIndex()) {
            if (ci > 0) add(false, 3)
            val pat = code[ch] ?: continue
            for ((ei, e) in pat.withIndex()) {
                if (ei > 0) add(false, 1)
                add(true, if (e == '-') 3 else 1)
            }
        }
    }
    add(false, 12)
    val n = key.size
    val env = DoubleArray(n)
    val attack = 0.004 * sr
    var level = 0.0
    val release = if (rt60 > 0) 10.0.pow(-3.0 / (rt60 * sr)) else 0.0   // -60 dB over rt60
    for (i in 0 until n) {
        val target = if (key[i]) 1.0 else 0.0
        level = if (target > level) minOf(target, level + 1.0 / attack)
        else if (rt60 > 0) max(target, level * release) else max(target, level - 1.0 / attack)
        env[i] = level
    }
    val rnd = Random(7)
    val noiseRms = 0.3 / 10.0.pow(snrDb / 20.0)
    val x = DoubleArray(n)
    for (i in 0 until n) {
        x[i] = 0.3 * env[i] * sin(2 * PI * tone * i / sr) + rnd.nextGaussian() * noiseRms
    }
    return x
}

private fun decode(x: DoubleArray): String {
    val sb = StringBuilder()
    val dec = MorseDecoder(object : MorseListener {
        override fun onChar(text: String) { sb.append(text) }
        override fun onPattern(pattern: String) {}
    }, Alphabet.EN)
    var i = 0
    while (i < x.size) {
        val c = minOf(1024, x.size - i)
        dec.feed(x.copyOfRange(i, i + c), c)
        i += c
    }
    dec.finish()
    return sb.toString().trim()
}

private fun edit(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        prev = cur
    }
    return prev[b.length]
}
