package ch.nutrisnap.app.ui.screens.fasting

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.model.FastingType
import ch.nutrisnap.app.service.FastingTimerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * NEU: Intervallfasten-Screen (von YAZIO inspiriert).
 * Startet/stoppt den FastingTimerService und zeigt Fortschritt.
 */

class FastingViewModel : ViewModel() {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _startTime = MutableStateFlow<LocalDateTime?>(null)
    val startTime: StateFlow<LocalDateTime?> = _startTime

    private val _selectedType = MutableStateFlow(FastingType.SIXTEEN_EIGHT)
    val selectedType: StateFlow<FastingType> = _selectedType

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds

    fun startFasting() {
        _startTime.value = LocalDateTime.now()
        _isRunning.value = true
        viewModelScope.launch {
            while (_isRunning.value) {
                delay(1000)
                _elapsedSeconds.value = ChronoUnit.SECONDS.between(_startTime.value, LocalDateTime.now())
            }
        }
    }

    fun stopFasting() {
        _isRunning.value = false
        _startTime.value = null
        _elapsedSeconds.value = 0
    }

    fun selectType(type: FastingType) {
        if (!_isRunning.value) _selectedType.value = type
    }
}

@Composable
fun FastingScreen(viewModel: FastingViewModel) {
    val context = LocalContext.current
    val isRunning by viewModel.isRunning.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()
    val elapsedSeconds by viewModel.elapsedSeconds.collectAsState()

    val goalSeconds = selectedType.hours * 3600L
    val progress = (elapsedSeconds.toFloat() / goalSeconds).coerceIn(0f, 1f)
    val remainingSeconds = (goalSeconds - elapsedSeconds).coerceAtLeast(0)

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Intervallfasten", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(16.dp))

        // Fasten-Typ Auswahl
        if (!isRunning) {
            Text("Wähle deinen Plan:", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FastingType.entries.forEach { type ->
                    FilterChip(
                        selected = type == selectedType,
                        onClick = { viewModel.selectType(type) },
                        label = { Text(type.label) }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // Fortschrittsring
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(200.dp),
                strokeWidth = 16.dp,
                color = if (progress >= 1f) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (isRunning) {
                    Text(
                        text = formatTime(elapsedSeconds),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("von ${selectedType.hours}h", style = MaterialTheme.typography.bodyMedium)
                    if (progress < 1f) {
                        Text(
                            "noch ${formatTime(remainingSeconds)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text("🎉 Ziel erreicht!", color = MaterialTheme.colorScheme.tertiary)
                    }
                } else {
                    Text(selectedType.label, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("${selectedType.hours}h Fasten", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        // Start/Stop Button
        Button(
            onClick = {
                if (isRunning) {
                    viewModel.stopFasting()
                    stopFastingService(context)
                } else {
                    viewModel.startFasting()
                    startFastingService(context, selectedType.hours)
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.error
                                 else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(
                if (isRunning) "Fasten beenden" else "Fasten starten",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (isRunning) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Gestartet um ${viewModel.startTime.value?.toLocalTime()?.toString()?.substring(0, 5) ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%dh %02dm" .format(h, m) else "%02d:%02d".format(m, s)
}

private fun startFastingService(context: Context, goalHours: Int) {
    val intent = Intent(context, FastingTimerService::class.java).apply {
        putExtra(FastingTimerService.EXTRA_GOAL_HOURS, goalHours)
        putExtra(FastingTimerService.EXTRA_START_TIME, System.currentTimeMillis())
    }
    context.startForegroundService(intent)
}

private fun stopFastingService(context: Context) {
    context.stopService(Intent(context, FastingTimerService::class.java))
}
