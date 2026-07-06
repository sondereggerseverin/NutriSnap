package ch.nutrisnap.app.ui.screens.home

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.repository.StatsRepository
import ch.nutrisnap.app.data.repository.UserProfileRepository
import ch.nutrisnap.app.data.repository.WeightRepository
import ch.nutrisnap.app.domain.AdaptiveTdeeCalculator
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.theme.KEY_MEAL_ORDER
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

data class MealOverview(
    val type:    MealType,
    val label:   String,
    val icon:    String,
    val color:   Color,
    val kcal:    Float,
    val count:   Int
)

data class HomeUiState(
    val greeting:      String  = "Hallo",
    val totalCalories: Float   = 0f,
    val totalProtein:  Float   = 0f,
    val totalCarbs:    Float   = 0f,
    val totalFat:      Float   = 0f,
    val calorieGoal:   Float   = 2000f,
    val burnedKcal:    Float   = 0f,
    val proteinGoal:   Float   = 120f,
    val carbsGoal:     Float   = 220f,
    val fatGoal:       Float   = 65f,
    val streak:        Int     = 0,
    val lastWeightKg:  Float?  = null,
    val previousWeightKg: Float? = null,
    val meals:         List<MealOverview> = emptyList(),
    // Wenn true, ist calorieGoal bereits das fertige AdaptiveTdeeCalculator-Ziel
    // (inkl. gedämpftem Aktivitätsbonus) — burnedKcal wird dann nur noch angezeigt,
    // nicht nochmal addiert. Wenn false (z.B. Profil unvollständig, zu wenig
    // Verlaufsdaten), gilt die alte, einfache Logik: statisches Ziel + voller Kalorienverbrauch.
    val isAdaptiveTarget: Boolean = false,
    // 0-100, nur relevant wenn isAdaptiveTarget true ist.
    val tdeeConfidence: Int = 0
) {
    /** Budget = Basis-Ziel + verbrannte Aktivitätskalorien (nur wenn nicht schon im adaptiven Ziel enthalten) */
    val adjustedGoal: Float get() = if (isAdaptiveTarget) calorieGoal else calorieGoal + burnedKcal
    /** Übrig = Budget - gegessen (nie negativ) */
    val remaining:    Float get() = (adjustedGoal - totalCalories).coerceAtLeast(0f)
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val db          = NutriDatabase.getInstance(app)
    private val diaryRepo   = DiaryRepository(db)
    private val profileRepo = UserProfileRepository(db)
    private val weightRepo  = WeightRepository(db)
    private val statsRepo   = StatsRepository(db)
    private val hcDao       = db.healthConnectDao()

    private val _streak = MutableStateFlow(0)

    init { refreshStreak() }

    // Rolling window for the adaptive trend: long enough to smooth out noise, short
    // enough to reflect a recent change in routine (e.g. ramping up ride volume).
    private val trendWindowDays = 21

    val uiState: StateFlow<HomeUiState> = combine(
        diaryRepo.getEntriesForDate(LocalDate.now()),
        profileRepo.get(),
        weightRepo.getRecent(1),
        _streak,
        hcDao.getCacheForDate(LocalDate.now()),
        weightRepo.getRecent(trendWindowDays),
        diaryRepo.getWeeklySummary(LocalDate.now().minusDays(trendWindowDays.toLong())),
        hcDao.getLast30Days(),
        app.notifDataStore.data
    ) { args ->
        val entries       = args[0] as List<ch.nutrisnap.app.data.model.DiaryEntry>
        val profile        = args[1] as ch.nutrisnap.app.data.repository.UserProfile
        val weights        = args[2] as List<ch.nutrisnap.app.data.model.WeightEntry>
        val streak         = args[3] as Int
        val hcCache        = args[4] as ch.nutrisnap.app.data.model.HealthConnectCache?
        val trendWeights   = args[5] as List<ch.nutrisnap.app.data.model.WeightEntry>
        val dailySummaries = args[6] as List<ch.nutrisnap.app.data.db.DailySummary>
        val activityDays   = args[7] as List<ch.nutrisnap.app.data.model.HealthConnectCache>
        val prefs          = args[8] as androidx.datastore.preferences.core.Preferences

        val mealOrder = ch.nutrisnap.app.data.model.parseMealOrder(prefs[KEY_MEAL_ORDER])
        val orderedMealMeta = mealOrder.map { type -> MEAL_META.first { it.first == type } }

        val byMeal = entries.groupBy { it.mealType }

        val weightByDate = trendWeights.associate { LocalDate.parse(it.dateStr) to it.weightKg }
        val intakeByDate = dailySummaries.associate { LocalDate.parse(it.dateStr) to it.calories }
        val trend = AdaptiveTdeeCalculator.computeTrendTdee(weightByDate, intakeByDate)

        val todayActiveKcal = hcCache?.activeCaloriesKcal
        val avgActiveKcal = activityDays
            .mapNotNull { it.activeCaloriesKcal }
            .takeIf { it.isNotEmpty() }
            ?.average()

        val adaptiveTarget = AdaptiveTdeeCalculator.computeDailyTarget(
            trend = trend,
            formulaTdee = profile.computedTdee(),
            todayActiveKcal = todayActiveKcal,
            avgActiveKcal = avgActiveKcal
        )

        HomeUiState(
            greeting      = greetingForHour(),
            totalCalories = entries.sumOf { it.calories.toDouble() }.toFloat(),
            totalProtein  = entries.sumOf { it.protein.toDouble() }.toFloat(),
            totalCarbs    = entries.sumOf { it.carbs.toDouble() }.toFloat(),
            totalFat      = entries.sumOf { it.fat.toDouble() }.toFloat(),
            calorieGoal   = adaptiveTarget?.targetKcal?.toFloat() ?: profile.dailyCalorieGoal.toFloat(),
            burnedKcal    = todayActiveKcal?.toFloat() ?: 0f,
            proteinGoal   = profile.proteinGoalG,
            carbsGoal     = profile.carbsGoalG,
            fatGoal       = profile.fatGoalG,
            streak        = streak,
            lastWeightKg  = weights.lastOrNull()?.weightKg ?: profile.weightKg.takeIf { it > 0f },
            previousWeightKg = trendWeights.dropLast(1).lastOrNull()?.weightKg,
            isAdaptiveTarget = adaptiveTarget != null,
            tdeeConfidence   = adaptiveTarget?.confidencePercent ?: 0,
            meals         = orderedMealMeta.map { (type, label, icon, color) ->
                val mealEntries = byMeal[type] ?: emptyList()
                MealOverview(
                    type  = type, label = label, icon = icon, color = color,
                    kcal  = mealEntries.sumOf { it.calories.toDouble() }.toFloat(),
                    count = mealEntries.size
                )
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun logWeight(kg: Float) {
        viewModelScope.launch {
            weightRepo.logWeight(LocalDate.now(), kg)
            refreshStreak()
        }
    }

    fun refreshStreak() {
        viewModelScope.launch { _streak.value = statsRepo.calculateStreak() }
    }

    private fun greetingForHour(): String {
        val hour = java.time.LocalTime.now().hour
        return when {
            hour < 12 -> "Guten Morgen"
            hour < 18 -> "Guten Tag"
            else      -> "Guten Abend"
        }
    }

    companion object {
        val MEAL_META = listOf(
            Quad(MealType.BREAKFAST, "Frühstück",  "☀️", Color(0xFFFF9B45)),
            Quad(MealType.LUNCH,     "Mittagessen","🌤️", Color(0xFF4B8BF5)),
            Quad(MealType.DINNER,    "Abendessen", "🌙", Color(0xFFA259FF)),
            Quad(MealType.SNACK,     "Snacks",     "🍎", Color(0xFF2D7D46))
        )
    }
}

data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
