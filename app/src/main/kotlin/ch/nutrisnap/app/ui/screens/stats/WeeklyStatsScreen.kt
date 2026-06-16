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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class DailyStats(val date: LocalDate, val calories: Float, val protein: Float, val carbs: Float, val fat: Float)

class WeeklyStatsViewModel : ViewModel() {
    private val _weeklyData = MutableStateFlow<List<DailyStats>>(emptyList())
    val weeklyData: StateFlow<List<DailyStats>> = _weeklyData

    private val _calorieGoal = MutableStateFlow(2000f)
    val calorieGoal: StateFlow<Float> = _calorieGoal

    init { loadWeeklyStats() }

    private fun loadWeeklyStats() {
        viewModelScope.launch {
            val today = LocalDate.now()
            _weeklyData.value = (6 downTo 0).map { daysAgo ->
                DailyStats(date = today.minusDays(daysAgo.toLong()), calories = 0f, protein = 0f, carbs = 0f, fat = 0f)
            }
        }
    }
}

@Composable
fun WeeklyStatsScreen(viewModel: WeeklyStatsViewModel) {
    val weeklyData by viewModel.weeklyData.collectAsState()
    val calorieGoal by viewModel.calorieGoal.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Wochenuebersicht", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))

        if (weeklyData.isNotEmpty()) {
            val avgCalories = weeklyData.filter { it.calories > 0 }.let {
                if (it.isEmpty()) 0f else it.sumOf { d -> d.calories.toDouble() }.toFloat() / it.size
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Ø Kalorien", style = MaterialTheme.typography.labelSmall)
                        Text("${avgCalories.toInt()} kcal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Ziel", style = MaterialTheme.typography.labelSmall)
                        Text("${calorieGoal.toInt()} kcal", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("Kalorien (letzte 7 Tage)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        val barColor = MaterialTheme.colorScheme.primary
        val goalColor = MaterialTheme.colorScheme.error

        Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            if (weeklyData.isEmpty()) return@Canvas
            val maxCals = maxOf(weeklyData.maxOf { it.calories }, calorieGoal, 100f)
            val barWidth = size.width / (weeklyData.size * 2f)
            val chartHeight = size.height - 40f
            val goalY = chartHeight - (calorieGoal / maxCals) * chartHeight
            drawLine(color = goalColor, start = Offset(0f, goalY), end = Offset(size.width, goalY), strokeWidth = 2f)
            weeklyData.forEachIndexed { i, day ->
                val barHeight = if (day.calories > 0) (day.calories / maxCals) * chartHeight else 4f
                val x = i * (size.width / weeklyData.size) + barWidth / 2
                drawRect(
                    color = if (day.calories > calorieGoal) goalColor.copy(alpha = 0.7f) else barColor,
                    topLeft = Offset(x, chartHeight - barHeight),
                    size = Size(barWidth, barHeight)
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            weeklyData.forEach { day ->
                Text(
                    text = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
