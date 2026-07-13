package ch.nutrisnap.app.ui.screens.recipes

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Erkennt eine in einem Kochschritt genannte Dauer (z.B. "20 Minuten", "1 Stunde",
 * "30 Sekunden", "1,5 Std") und gibt sie in Sekunden zurueck.
 * Keine erkannte Dauer -> null; die UI zeigt dann einen manuellen Default (5 Min)
 * den der Nutzer selbst per +/- anpassen kann, statt eine Dauer zu erraten.
 */
private val durationRegex = Regex(
    """(\d+(?:[.,]\d+)?)\s*(sekunden?|sek\.?|minuten?|min\.?|stunden?|std\.?|h\b)""",
    RegexOption.IGNORE_CASE
)

fun detectDurationSeconds(text: String): Int? {
    val match = durationRegex.find(text) ?: return null
    val value = match.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
    val unit = match.groupValues[2].lowercase()
    val seconds = when {
        unit.startsWith("sek") -> value
        unit.startsWith("min") -> value * 60
        unit.startsWith("std") || unit.startsWith("stunde") || unit == "h" -> value * 3600
        else -> return null
    }
    return seconds.toInt().coerceIn(5, 4 * 3600)
}

private fun formatTime(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}

/**
 * Zustand eines einzelnen Schritt-Timers. Wird pro Schritt-Index in
 * CookingModeScreen in einer mutableStateMapOf gehalten, damit beim
 * Vor-/Zurueckblaettern zwischen Schritten der Countdown nicht verloren geht.
 */
class StepTimerState(initialSeconds: Int) {
    var totalSeconds by mutableIntStateOf(initialSeconds)
    var remainingSeconds by mutableIntStateOf(initialSeconds)
    var isRunning by mutableStateOf(false)
    var isFinished by mutableStateOf(false)
}

private fun vibrateDone(context: Context) {
    runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
    }
    runCatching {
        ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
    }
}

@Composable
fun CookingStepTimer(state: StepTimerState) {
    val context = LocalContext.current

    LaunchedEffect(state.isRunning) {
        while (state.isRunning && state.remainingSeconds > 0) {
            delay(1000)
            state.remainingSeconds--
        }
        if (state.isRunning && state.remainingSeconds <= 0) {
            state.isRunning = false
            state.isFinished = true
            vibrateDone(context)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.isFinished)
                MaterialTheme.colorScheme.tertiaryContainer
            else
                MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Timer, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.isFinished) "Fertig! ⏰" else formatTime(state.remainingSeconds),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))

            val notStarted = !state.isRunning && !state.isFinished && state.remainingSeconds == state.totalSeconds
            if (notStarted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedIconButton(onClick = {
                        val newVal = (state.totalSeconds - 60).coerceAtLeast(5)
                        state.totalSeconds = newVal; state.remainingSeconds = newVal
                    }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Remove, "1 Min weniger", modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(16.dp))
                    OutlinedIconButton(onClick = {
                        val newVal = state.totalSeconds + 60
                        state.totalSeconds = newVal; state.remainingSeconds = newVal
                    }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Add, "1 Min mehr", modifier = Modifier.size(20.dp)) }
                }
                Spacer(Modifier.height(12.dp))
            }

            Row {
                if (state.isFinished) {
                    Button(onClick = {
                        state.isFinished = false
                        state.remainingSeconds = state.totalSeconds
                    }) {
                        Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Zuruecksetzen")
                    }
                } else {
                    Button(onClick = { state.isRunning = !state.isRunning }) {
                        Icon(if (state.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.isRunning) "Pause"
                            else if (state.remainingSeconds < state.totalSeconds) "Weiter"
                            else "Start"
                        )
                    }
                    if (state.remainingSeconds < state.totalSeconds || state.isRunning) {
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = {
                            state.isRunning = false
                            state.remainingSeconds = state.totalSeconds
                        }) {
                            Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Reset")
                        }
                    }
                }
            }
        }
    }
}
