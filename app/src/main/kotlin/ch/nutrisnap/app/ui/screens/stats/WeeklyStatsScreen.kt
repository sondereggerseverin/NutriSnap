package ch.nutrisnap.app.ui.screens.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.repository.UserProfileRepository
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class DailyStats(
    val date: LocalDate,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float
)

class WeeklyStatsViewModel(app: Application) : AndroidViewModel(app) {
    private val db          = NutriDatabase.getInstance(app)
    private val diaryRepo   = DiaryRepository(db)
    private val profileRepo = UserProfileRepository(db)

    private val _weeklyData = MutableStateFlow<List<DailyStats>>(emptyList())
    val weeklyData: StateFlow<List<DailyStats>> = _weeklyData

    private val _calorieGoal = MutableStateFlow(2000f)
    val calorieGoal: StateFlow<Float> = _calorieGoal

    init {
        loadWeeklyStats()
    }

    private fun loadWeeklyStats() {
        viewModelScope.launch {
            val profile = profileRepo.get().first()
            _calorieGoal.value = profile.dailyCalorieGoal.toFloat()

            val today = LocalDate.now()
            val from  = today.minusDays(6)
            val byDate = diaryRepo.getWeeklySummary(from).first().associateBy { it.dateStr }

            _weeklyData.value = (6 downTo 0).map { daysAgo ->
                val date = today.minusDays(daysAgo.toLong())
                val s = byDate[date.toString()]
                DailyStats(
                    date     = date,
                    calories = s?.calories ?: 0f,
                    protein  = s?.protein  ?: 0f,
                    carbs    = s?.carbs    ?: 0f,
                    fat      = s?.fat      ?: 0f
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
            .padding(NutriSpacing.lg)
    ) {
        Text(
            "Wochenübersicht",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(NutriSpacing.xxl))

        if (weeklyData.isNotEmpty()) {
            val avgCalories = weeklyData.filter { it.calories > 0 }.let {
                if (it.isEmpty()) 0f else it.sumOf { d -> d.calories.toDouble() }.toFloat() / it.size
            }
            SummaryCard(avgCalories = avgCalories, goalCalories = calorieGoal)
            Spacer(Modifier.height(NutriSpacing.lg))
        }

        Text(
            "Kalorien (letzte 7 Tage)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(NutriSpacing.sm))
        CaloriesBarChart(
            data = weeklyData,
            goalCalories = calorieGoal,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        )

        Spacer(Modifier.height(NutriSpacing.xxl))

        Text(
            "Makronährstoffe (\u00D8 pro Tag)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(NutriSpacing.sm))
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(NutriSpacing.lg)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryItem("\u00D8 Kalorien", "${avgCalories.toInt()} kcal", MaterialTheme.colorScheme.onSurface)
            SummaryItem("Ziel", "${goalCalories.toInt()} kcal", MacroColors.calories)
            SummaryItem(
                "Differenz",
                "${if (avgCalories - goalCalories >= 0) "+" else ""}${(avgCalories - goalCalories).toInt()} kcal",
                if (avgCalories > goalCalories) MaterialTheme.colorScheme.error else MacroColors.calories
            )
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun MacroSummaryTable(weeklyData: List<DailyStats>) {
    val activeDays = weeklyData.filter { it.calories > 0 }
    val count = activeDays.size.coerceAtLeast(1)
    val avgProtein = activeDays.sumOf { it.protein.toDouble() }.toFloat() / count
    val avgCarbs = activeDays.sumOf { it.carbs.toDouble() }.toFloat() / count
    val avgFat = activeDays.sumOf { it.fat.toDouble() }.toFloat() / count

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        MacroStat("\uD83E\uDD69 Protein", "${avgProtein.toInt()}g", MacroColors.protein)
        MacroStat("\uD83C\uDF5E Kohlenhydrate", "${avgCarbs.toInt()}g", MacroColors.carbs)
        MacroStat("\uD83E\uDDC8 Fett", "${avgFat.toInt()}g", MacroColors.fat)
    }
}

@Composable
private fun MacroStat(label: String, value: String, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(NutriRadius.sm))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = NutriSpacing.md, vertical = NutriSpacing.sm)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.8f)
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}
