package app.morsewatch

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.lifecycle.ViewModel
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import morse.Alphabet
import morse.MorseDecoder
import morse.MorseListener
import morse.MorseStatus

data class UiState(
    val text: String = "",
    val pattern: String = "",
    val status: MorseStatus = MorseStatus(Double.NaN, 0.0, 0.0, false, -120.0, false),
    val listening: Boolean = false,
    val alphabet: Alphabet = Alphabet.RU,
    val error: String? = null,
)

/** Owns the microphone loop and the decoder. Decoding runs on a dedicated audio thread. */
class MorseViewModel : ViewModel() {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val decoder = MorseDecoder(
        listener = object : MorseListener {
            override fun onChar(text: String) {
                _ui.update { it.copy(text = (it.text + text).takeLast(MAX_TEXT)) }
            }

            override fun onPattern(pattern: String) {
                _ui.update { it.copy(pattern = pattern) }
            }
        },
        alphabet = Alphabet.RU,
    )

    @Volatile private var running = false
    private var worker: Thread? = null

    /** The caller must hold RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (running) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // UNPROCESSED keeps noise suppression / AGC from eating a steady tone; fall back to MIC.
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            fail("Микрофон недоступен")
            return
        }
        val record = AudioRecord(
            source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 8192) * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            fail("Не удалось открыть микрофон")
            return
        }

        decoder.reset()
        running = true
        _ui.update { it.copy(listening = true, error = null, pattern = "") }
        worker = thread(name = "morse-audio", isDaemon = true) {
            val buf = ShortArray(CHUNK)
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n < 0) {
                        fail("Ошибка чтения звука ($n)")
                        break
                    }
                    if (n == 0) continue
                    decoder.feed(buf, n)
                    val st = decoder.status()
                    _ui.update { it.copy(status = st) }
                }
            } finally {
                try { record.stop() } catch (e: IllegalStateException) { /* already stopped */ }
                record.release()
                decoder.finish()
                running = false
                _ui.update { it.copy(listening = false, pattern = "") }
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
    }

    fun toggleAlphabet() {
        val next = if (_ui.value.alphabet == Alphabet.RU) Alphabet.EN else Alphabet.RU
        decoder.setAlphabet(next)
        _ui.update { it.copy(alphabet = next) }
    }

    fun clear() {
        _ui.update { it.copy(text = "", pattern = "") }
    }

    fun fail(message: String) {
        _ui.update { it.copy(error = message, listening = false) }
    }

    override fun onCleared() {
        stop()
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK = 1024          // 64 ms
        const val MAX_TEXT = 400
    }
}
