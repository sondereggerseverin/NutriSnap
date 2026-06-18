package ch.nutrisnap.app.ui.screens.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * NEU: Wochenstatistik-Screen (von Cronometer inspiriert).
 * Zeigt Kalorien-Balkendiagramm der letzten 7 Tage.
 *
 * KEINE externe Chart-Library nötig – reines Compose Canvas.
 *
 * EINBINDEN in Navigation:
 *   composable("stats") {
 *       val vm: WeeklyStatsViewModel = viewModel()
 *       WeeklyStatsScreen(viewModel = vm)
 *   }
 */

data class DailyStats(
    val date: LocalDate,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float
)

class WeeklyStatsViewModel : ViewModel() {
    // TODO: Aus DiaryRepository befüllen (DiaryEntries der letzten 7 Tage aggregieren)
    private val _weeklyData = MutableStateFlow<List<DailyStats>>(emptyList())
    val weeklyData: StateFlow<List<DailyStats>> = _weeklyData

    private val _calorieGoal = MutableStateFlow(2000f)
    val calorieGoal: StateFlow<Float> = _calorieGoal

    init {
        loadWeeklyStats()
    }

    private fun loadWeeklyStats() {
        viewModelScope.launch {
            // Platzhalter – ersetzen mit echten Daten aus DiaryRepository:
            // val stats = diaryRepository.getWeeklyStats(LocalDate.now().minusDays(6), LocalDate.now())
            val today = LocalDate.now()
            _weeklyData.value = (6 downTo 0).map { daysAgo ->
                DailyStats(
                    date = today.minusDays(daysAgo.toLong()),
                    calories = 0f,
                    protein = 0f,
                    carbs = 0f,
                    fat = 0f
                )
            }
        }
    }
}

@Composable
fun WeeklyStatsScreen(viewModel: WeeklyStatsViewModel) {
    val weeklyData by viewModel.weeklyData.collectAsState()
    val calorieGoal by viewModel.calorieGoal.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "Wochenübersicht",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(24.dp))

        // Durchschnitt-Karte
        if (weeklyData.isNotEmpty()) {
            val avgCalories = weeklyData.filter { it.calories > 0 }.let {
                if (it.isEmpty()) 0f else it.sumOf { d -> d.calories.toDouble() }.toFloat() / it.size
            }
            SummaryCard(avgCalories = avgCalories, goalCalories = calorieGoal)
            Spacer(Modifier.height(16.dp))
        }

        // Balkendiagramm
        Text("Kalorien (letzte 7 Tage)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        CaloriesBarChart(
            data = weeklyData,
            goalCalories = calorieGoal,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        )

        Spacer(Modifier.height(24.dp))

        // Makro-Zusammenfassung Tabelle
        Text("Makronährstoffe (Ø pro Tag)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        MacroSummaryTable(weeklyData = weeklyData)
    }
}

@Composable
private fun CaloriesBarChart(
    data: List<DailyStats>,
    goalCalories: Float,
    modifier: Modifier = Modifier
) {
    val barColor = MaterialTheme.colorScheme.primary
    val goalColor = MaterialTheme.colorScheme.error
    val textColor = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas

        val maxCals = maxOf(data.maxOf { it.calories }, goalCalories, 100f)
        val barWidth = size.width / (data.size * 2f)
        val chartHeight = size.height - 40f

        // Ziel-Linie
        val goalY = chartHeight - (goalCalories / maxCals) * chartHeight
        drawLine(
            color = goalColor,
            start = Offset(0f, goalY),
            end = Offset(size.width, goalY),
            strokeWidth = 2f
        )

        // Balken
        data.forEachIndexed { i, day ->
            val barHeight = if (day.calories > 0) (day.calories / maxCals) * chartHeight else 4f
            val x = i * (size.width / data.size) + barWidth / 2
            val y = chartHeight - barHeight

            drawRect(
                color = if (day.calories > goalCalories) goalColor.copy(alpha = 0.7f) else barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight)
            )
        }
    }

    // Wochentag-Labels
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        data.forEach { day ->
            Text(
                text = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun SummaryCard(avgCalories: Float, goalCalories: Float) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Ø Kalorien", style = MaterialTheme.typography.labelSmall)
                Text(
                    "${avgCalories.toInt()} kcal",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Ziel", style = MaterialTheme.typography.labelSmall)
                Text(
                    "${goalCalories.toInt()} kcal",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val diff = avgCalories - goalCalories
                Text("Differenz", style = MaterialTheme.typography.labelSmall)
                Text(
                    "${if (diff >= 0) "+" else ""}${diff.toInt()} kcal",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (diff > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun MacroSummaryTable(weeklyData: List<DailyStats>) {
    val activeDays = weeklyData.filter { it.calories > 0 }
    val count = activeDays.size.coerceAtLeast(1)
    val avgProtein = activeDays.sumOf { it.protein.toDouble() }.toFloat() / count
    val avgCarbs = activeDays.sumOf { it.carbs.toDouble() }.toFloat() / count
    val avgFat = activeDays.sumOf { it.fat.toDouble() }.toFloat() / count

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        MacroChip("🥩 Protein", "${avgProtein.toInt()}g", MaterialTheme.colorScheme.primary)
        MacroChip("🍞 Kohlenhydrate", "${avgCarbs.toInt()}g", MaterialTheme.colorScheme.secondary)
        MacroChip("🧈 Fett", "${avgFat.toInt()}g", MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun MacroChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
    }
}
