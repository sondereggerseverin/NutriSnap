package ch.nutrisnap.app.ui.screens.analysis

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.DailySummary
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.WeightEntry
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.repository.StatsRepository
import ch.nutrisnap.app.data.repository.UserProfile
import ch.nutrisnap.app.data.repository.UserProfileRepository
import ch.nutrisnap.app.data.repository.WeightRepository
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class DayPoint(
    val date:    LocalDate,
    val label:   String,
    val calories:Float,
    val protein: Float,
    val carbs:   Float,
    val fat:     Float
)

data class AnalysisUiState(
    val days:        List<DayPoint> = emptyList(),
    val weights:     List<WeightEntry> = emptyList(),
    val goals:       UserProfile = UserProfile(),
    val streak:      Int = 0,
    val avgCalories: Int = 0,
    val avgProtein:  Float = 0f,
    val avgCarbs:    Float = 0f,
    val avgFat:      Float = 0f
)

class AnalysisViewModel(app: Application) : AndroidViewModel(app) {
    private val db          = NutriDatabase.getInstance(app)
    private val diaryRepo   = DiaryRepository(db)
    private val profileRepo = UserProfileRepository(db)
    private val weightRepo  = WeightRepository(db)
    private val statsRepo   = StatsRepository(db)

    private val fromDate = LocalDate.now().minusDays(6)

    val uiState: StateFlow<AnalysisUiState> = combine(
        diaryRepo.getWeeklySummary(fromDate),
        weightRepo.getRecent(7),
        profileRepo.get(),
        statsRepoStreakFlow()
    ) { summaries, weights, profile, streak ->
        val days = buildDayPoints(summaries)
        val n    = days.size.coerceAtLeast(1)

        AnalysisUiState(
            days        = days,
            weights     = weights,
            goals       = profile,
            streak      = streak,
            avgCalories = (days.sumOf { it.calories.toDouble() } / n).toInt(),
            avgProtein  = (days.sumOf { it.protein.toDouble() } / n).toFloat(),
            avgCarbs    = (days.sumOf { it.carbs.toDouble() } / n).toFloat(),
            avgFat      = (days.sumOf { it.fat.toDouble() } / n).toFloat()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisUiState())

    /** Polls the streak once on subscribe (streak doesn't need live updates here) */
    private fun statsRepoStreakFlow() = flow {
        emit(statsRepo.calculateStreak())
    }

    private fun buildDayPoints(summaries: List<DailySummary>): List<DayPoint> {
        val byDate = summaries.associateBy { it.dateStr }
        return (0..6).map { offset ->
            val date = fromDate.plusDays(offset.toLong())
            val s = byDate[date.toString()]
            DayPoint(
                date     = date,
                label    = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN),
                calories = s?.calories ?: 0f,
                protein  = s?.protein  ?: 0f,
                carbs    = s?.carbs    ?: 0f,
                fat      = s?.fat      ?: 0f
            )
        }
    }
}
