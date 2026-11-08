package morse

import kotlin.math.max

/** Snapshot for the UI. */
data class MorseStatus(
    val toneHz: Double,        // NaN until a clear tone has been found
    val tonalityDb: Double,    // how far the tone stands above the noise floor of the band
    val wpm: Double,           // estimated speed (PARIS), 0 until calibrated
    val keyDown: Boolean,
    val levelDb: Double,       // current level relative to the recent peak, <= 0
    val calibrated: Boolean,
)

/**
 * Real-time Morse decoder. Feed 16 kHz mono PCM16 as it arrives; characters come back through [listener].
 * Call from a single thread (the audio thread). The tone and the speed are discovered, not configured.
 */
class MorseDecoder(
    private val listener: MorseListener,
    alphabet: Alphabet = Alphabet.RU,
    punctuation: Boolean = false,
) {
    private var tone = ToneTracker()
    private var front = Frontend()
    private var key = KeyDecoder(MorseTables.build(alphabet, punctuation), listener)
    private var scratch = DoubleArray(4096)

    fun setAlphabet(alphabet: Alphabet, punctuation: Boolean = false) {
        key.table = MorseTables.build(alphabet, punctuation)
    }

    fun feed(pcm: ShortArray, count: Int = pcm.size) {
        if (scratch.size < count) scratch = DoubleArray(count)
        for (i in 0 until count) scratch[i] = pcm[i] / 32768.0
        feed(scratch, count)
    }

    fun feed(x: DoubleArray, count: Int) {
        tone.feed(x, count)
        val f0 = if (tone.locked) tone.f0 else 600.0
        front.feed(x, count, f0) { env -> if (tone.locked) key.frame(env) }
    }

    /** Emit the character still being received (call when listening stops). */
    fun finish() = key.finish()

    fun reset() {
        val table = key.table
        tone = ToneTracker()
        front = Frontend()
        key = KeyDecoder(table, listener)
    }

    fun status(): MorseStatus = MorseStatus(
        toneHz = tone.f0,
        tonalityDb = tone.tonalityDb,
        wpm = if (key.isCalibrated) 1200.0 / max(key.dotMs, 1.0) else 0.0,
        keyDown = key.keyDown,
        levelDb = key.levelDb,
        calibrated = key.isCalibrated,
    )
}
