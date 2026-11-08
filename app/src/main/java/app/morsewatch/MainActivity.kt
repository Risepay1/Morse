package app.morsewatch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.MaterialTheme
import app.morsewatch.ui.MorseScreen

class MainActivity : ComponentActivity() {
    private val vm: MorseViewModel by viewModels()

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.start(this) else vm.fail("Нужен доступ к микрофону")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val ui = vm.ui.collectAsStateWithLifecycle().value
                MorseScreen(
                    ui = ui,
                    onToggle = ::toggle,
                    onAlphabet = vm::toggleAlphabet,
                    onClear = vm::clear,
                )
            }
        }
    }

    private fun toggle() {
        if (vm.ui.value.listening) {
            vm.stop()
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            vm.start(this)
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** No foreground service in this version: stop listening when the app leaves the screen. */
    override fun onStop() {
        super.onStop()
        vm.stop()
    }
}
