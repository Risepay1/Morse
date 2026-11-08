package morse.cli

import morse.Alphabet
import morse.MorseDecoder
import morse.MorseListener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** JVM test harness: `WavDecodeKt <file.wav> [ru|en]` for 16 kHz mono 16-bit PCM. Feeds 1024-sample chunks. */
fun main(args: Array<String>) {
    val alphabet = if (args.getOrNull(1) == "en") Alphabet.EN else Alphabet.RU
    val bytes = File(args[0]).readBytes()
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var pos = 12
    var dataStart = -1
    var dataLen = 0
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4, Charsets.US_ASCII)
        val len = bb.getInt(pos + 4)
        if (id == "data") { dataStart = pos + 8; dataLen = minOf(len, bytes.size - dataStart); break }
        pos += 8 + len + (len and 1)
    }
    require(dataStart >= 0) { "no data chunk" }
    val n = dataLen / 2
    val pcm = ShortArray(n) { bb.getShort(dataStart + 2 * it) }

    val out = StringBuilder()
    val dec = MorseDecoder(object : MorseListener {
        override fun onChar(text: String) { out.append(text) }
        override fun onPattern(pattern: String) {}
    }, alphabet)
    var i = 0
    while (i < n) {
        val c = minOf(1024, n - i)
        dec.feed(pcm.copyOfRange(i, i + c), c)
        i += c
    }
    dec.finish()
    val st = dec.status()
    println(out)
    System.err.println("tone=%.1f Hz  tonality=%.1f dB  wpm=%.1f".format(st.toneHz, st.tonalityDb, st.wpm))
}
