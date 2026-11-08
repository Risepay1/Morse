package app.morsephone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morsephone.UiState
import morse.Alphabet

@Composable
fun PhoneScreen(
    ui: UiState,
    onToggle: () -> Unit,
    onAlphabet: () -> Unit,
    onClear: () -> Unit,
) {
    // Keep the display on while listening.
    val view = LocalView.current
    DisposableEffect(ui.listening) {
        view.keepScreenOn = ui.listening
        onDispose { view.keepScreenOn = false }
    }

    val lastChar = ui.text.trimEnd().lastOrNull()?.toString() ?: "—"
    val pattern = ui.pattern.replace('.', '·').replace('-', '−')
    val scroll = rememberScrollState()
    LaunchedEffect(ui.text) { scroll.scrollTo(scroll.maxValue) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusRow(ui)
        Spacer(Modifier.height(12.dp))

        Text(
            text = lastChar,
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = if (pattern.isEmpty()) " " else pattern,
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))

        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Box(Modifier.verticalScroll(scroll).padding(12.dp)) {
                Text(
                    text = ui.error ?: ui.text.ifEmpty { "Здесь появится принятый текст" },
                    fontSize = 22.sp,
                    color = if (ui.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onToggle, modifier = Modifier.weight(2f)) {
                Text(if (ui.listening) "Стоп" else "Слушать")
            }
            OutlinedButton(onClick = onAlphabet, modifier = Modifier.weight(1f)) {
                Text(if (ui.alphabet == Alphabet.RU) "RU" else "EN")
            }
            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                Text("Сброс")
            }
        }
    }
}

@Composable
private fun StatusRow(ui: UiState) {
    val st = ui.status
    val label = when {
        !ui.listening -> "остановлено"
        st.toneHz.isNaN() -> "нет тона"
        !st.calibrated -> "${st.toneHz.toInt()} Гц · калибровка"
        else -> "${st.toneHz.toInt()} Гц · ${st.wpm.toInt()} WPM"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(
                    if (ui.listening && st.keyDown) Color(0xFF66BB6A) else Color(0xFF78909C),
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
