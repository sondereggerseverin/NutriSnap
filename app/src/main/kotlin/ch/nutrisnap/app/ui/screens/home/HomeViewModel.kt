package ch.nutrisnap.app.ui.screens.home

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.repository.StatsRepository
import ch.nutrisnap.app.data.repository.UserProfile
import ch.nutrisnap.app.data.repository.UserProfileRepository
import ch.nutrisnap.app.data.repository.WeightRepository
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
    val proteinGoal:   Float   = 120f,
    val carbsGoal:     Float   = 220f,
    val fatGoal:       Float   = 65f,
    val streak:        Int     = 0,
    val lastWeightKg:  Float?  = null,
    val meals:         List<MealOverview> = emptyList()
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val db          = NutriDatabase.getInstance(app)
    private val diaryRepo   = DiaryRepository(db)
    private val profileRepo = UserProfileRepository(db)
    private val weightRepo  = WeightRepository(db)
    private val statsRepo   = StatsRepository(db)

    private val _streak     = MutableStateFlow(0)
    private val _refreshKey = MutableStateFlow(0)

    init { refreshStreak() }

    val uiState: StateFlow<HomeUiState> = combine(
        diaryRepo.getEntriesForDate(LocalDate.now()),
        profileRepo.get(),
        weightRepo.getRecent(1),
        _streak
    ) { entries, profile, weights, streak ->
        val byMeal = entries.groupBy { it.mealType }

        HomeUiState(
            greeting      = greetingForHour(),
            totalCalories = entries.sumOf { it.calories.toDouble() }.toFloat(),
            totalProtein  = entries.sumOf { it.protein.toDouble() }.toFloat(),
            totalCarbs    = entries.sumOf { it.carbs.toDouble() }.toFloat(),
            totalFat      = entries.sumOf { it.fat.toDouble() }.toFloat(),
            calorieGoal   = profile.computedTdee()?.toFloat() ?: profile.dailyCalorieGoal.toFloat(),
            proteinGoal   = profile.proteinGoalG,
            carbsGoal     = profile.carbsGoalG,
            fatGoal       = profile.fatGoalG,
            streak        = streak,
            lastWeightKg  = weights.lastOrNull()?.weightKg ?: profile.weightKg.takeIf { it > 0f },
            meals         = MEAL_META.map { (type, label, icon, color) ->
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
