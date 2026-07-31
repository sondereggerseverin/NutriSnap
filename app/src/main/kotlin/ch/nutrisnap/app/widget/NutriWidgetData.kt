package ch.nutrisnap.app.widget

import android.content.Context
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.repository.StatsRepository
import ch.nutrisnap.app.data.repository.UserProfileRepository
import ch.nutrisnap.app.data.repository.WeightRepository
import ch.nutrisnap.app.domain.AdaptiveTdeeCalculator
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** Schlanke, einmalige Momentaufnahme der Home-Kennzahlen fürs Widget. Nutzt exakt
 *  dieselbe Zielberechnung wie [ch.nutrisnap.app.ui.screens.home.HomeViewModel] –
 *  keine eigene/duplizierte Business-Logik. */
data class WidgetSnapshot(
    val totalCalories: Float,
    val calorieGoal: Float,
    val burnedKcal: Float,
    val isAdaptiveTarget: Boolean,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val streak: Int
) {
    val adjustedGoal: Float get() = if (isAdaptiveTarget) calorieGoal else calorieGoal + burnedKcal
    val remaining: Float get() = (adjustedGoal - totalCalories).coerceAtLeast(0f)
    val progress: Float get() = if (adjustedGoal <= 0f) 0f else (totalCalories / adjustedGoal).coerceIn(0f, 1f)
}

object WidgetDataProvider {

    // Gleiches Fenster wie HomeViewModel.trendWindowDays, damit das adaptive Ziel
    // im Widget exakt mit dem in der App angezeigten übereinstimmt.
    private const val TREND_WINDOW_DAYS = 21L

    suspend fun load(context: Context): WidgetSnapshot {
        val db = NutriDatabase.getInstance(context)
        val diaryRepo = DiaryRepository(db)
        val profileRepo = UserProfileRepository(db)
        val weightRepo = WeightRepository(db)
        val statsRepo = StatsRepository(db)
        val hcDao = db.healthConnectDao()

        val today = LocalDate.now()
        val entries = diaryRepo.getEntriesForDate(today).first()
        val profile = profileRepo.get().first()
        val hcCache = hcDao.getCacheForDate(today).first()
        val trendWeights = weightRepo.getRecent(TREND_WINDOW_DAYS.toInt()).first()
        val dailySummaries = diaryRepo.getWeeklySummary(today.minusDays(TREND_WINDOW_DAYS)).first()
        val activityDays = hcDao.getLast30Days().first()
        val streak = statsRepo.calculateStreak()

        val weightByDate = trendWeights.associate { LocalDate.parse(it.dateStr) to it.weightKg }
        val intakeByDate = dailySummaries.associate { LocalDate.parse(it.dateStr) to it.calories }
        val trend = AdaptiveTdeeCalculator.computeTrendTdee(weightByDate, intakeByDate)

        val todayActiveKcal = hcCache?.activeCaloriesKcal
        val avgActiveKcal = activityDays.mapNotNull { it.activeCaloriesKcal }
            .takeIf { it.isNotEmpty() }?.average()

        val adaptiveTarget = AdaptiveTdeeCalculator.computeDailyTarget(
            trend = trend,
            formulaTdee = profile.computedTdee(),
            todayActiveKcal = todayActiveKcal,
            avgActiveKcal = avgActiveKcal
        )

        return WidgetSnapshot(
            totalCalories = entries.sumOf { it.calories.toDouble() }.toFloat(),
            calorieGoal = adaptiveTarget?.targetKcal?.toFloat() ?: profile.dailyCalorieGoal.toFloat(),
            burnedKcal = todayActiveKcal?.toFloat() ?: 0f,
            isAdaptiveTarget = adaptiveTarget != null,
            protein = entries.sumOf { it.protein.toDouble() }.toFloat(),
            carbs = entries.sumOf { it.carbs.toDouble() }.toFloat(),
            fat = entries.sumOf { it.fat.toDouble() }.toFloat(),
            streak = streak
        )
    }
}
