package ch.nutrisnap.app.ui.screens.water

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.WaterEntry
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

// ─── ViewModel ────────────────────────────────────────────────────────────────

class WaterViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = NutriDatabase.getInstance(app).waterEntryDao()
    private val today = LocalDate.now()
    val DAILY_GOAL_ML = 2500

    val totalToday: StateFlow<Int> = dao.getTotalForDate(today)
        .map { it ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val entries: StateFlow<List<WaterEntry>> = dao.getEntriesForDate(today)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addWater(amountMl: Int) {
        viewModelScope.launch {
            dao.insert(WaterEntry(date = today, amountMl = amountMl))
        }
    }

    fun removeEntry(entry: WaterEntry) {
        viewModelScope.launch { dao.delete(entry) }
    }
}

// ─── Quick-Portionen ──────────────────────────────────────────────────────────

private val QUICK_AMOUNTS = listOf(
    150 to "☕ Kaffee",
    200 to "🥛 Glas",
    330 to "🥤 Dose",
    500 to "💧 Flasche",
    750 to "🍶 Groß"
)

// ─── Screen ───────────────────────────────────────────────────────────────────

@Composable
fun WaterTrackingScreen(viewModel: WaterViewModel = viewModel()) {
    val total   by viewModel.totalToday.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val progress = (total.toFloat() / viewModel.DAILY_GOAL_ML).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Wassertrinken",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(24.dp))

        WaterProgressCircle(
            progress = progress,
            totalMl = total,
            goalMl = viewModel.DAILY_GOAL_ML
        )

        Spacer(Modifier.height(24.dp))

        Text("Schnell hinzufügen", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(QUICK_AMOUNTS) { (ml, label) ->
                OutlinedButton(onClick = { viewModel.addWater(ml) }) {
                    Text("$label\n${ml}ml", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("Heute getrunken", style = MaterialTheme.typography.titleSmall)
        entries.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("💧 ${entry.amountMl} ml")
                Text(
                    entry.timestamp.toLocalTime().toString().substring(0, 5),
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { viewModel.removeEntry(entry) }) {
                    Text("✕", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun WaterProgressCircle(progress: Float, totalMl: Int, goalMl: Int) {
    Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(160.dp),
            strokeWidth = 12.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${totalMl}ml",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "von ${goalMl}ml",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
