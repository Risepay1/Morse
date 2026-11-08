package app.morsewatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import app.morsewatch.UiState

/** Side of the screen next to the watch's physical buttons. Galaxy Watch default: right. */
private const val BUTTONS_ON_RIGHT = true

private val Faint = Color(0xFF5A5A5A)      // dim grey; the rest of the screen is pure black (OLED pixels off)

/**
 * Discreet mode: black screen, one small dim character at the edge by the buttons.
 * Tap = start / stop listening, long press = switch RU / EN.
 */
@Composable
fun MorseScreen(
    ui: UiState,
    onToggle: () -> Unit,
    onAlphabet: () -> Unit,
    onClear: () -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(ui.listening) {
        view.keepScreenOn = ui.listening
        onDispose { view.keepScreenOn = false }
    }

    val last = ui.text.trimEnd().lastOrNull()?.toString()
    val shown = when {
        ui.error != null -> "!"                 // e.g. no microphone permission
        last != null -> last
        ui.listening -> "·"                     // listening, nothing decoded yet
        else -> ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(onToggle, onAlphabet) {
                detectTapGestures(onTap = { onToggle() }, onLongPress = { onAlphabet() })
            },
    ) {
        Text(
            text = shown,
            fontSize = 16.sp,
            color = Faint,
            maxLines = 1,
            modifier = Modifier
                .align(if (BUTTONS_ON_RIGHT) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 8.dp),
        )
    }
}
