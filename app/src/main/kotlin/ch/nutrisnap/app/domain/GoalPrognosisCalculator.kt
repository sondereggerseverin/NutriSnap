package ch.nutrisnap.app.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.WeightEntry
import ch.nutrisnap.app.data.repository.UserProfileRepository
import ch.nutrisnap.app.data.repository.WeightRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// ============================================================
// FEATURE 3: Ziel-Prognose mit Live-Nachjustierung
//
// Integration:
//  1. GoalPrognosisViewModel (unten, manuell instanziiert wie HomeViewModel — kein Hilt)
//     im Stats-/Dashboard-Screen einbinden.
//  2. UserProfile wurde um targetWeightKg / weeklyTargetLossKg / lastPrognosisDateStr
//     erweitert (siehe NutriDatabase MIGRATION_21_22). Es gibt aber noch KEIN Eingabefeld
//     dafür in SettingsScreen — bis das ergänzt ist, ist targetWeightKg immer null und
//     calculatePrognosis() liefert daher immer null. Das ist erwartetes Verhalten, kein Bug.
//  3. Abweichung vom Original-Entwurf: Es gab keine fertige API für "kg/Woche-Trend"
//     (AdaptiveTdeeCalculator.computeTrendTdee() liefert eine TDEE-Schätzung in kcal, keine
//     Gewichtsänderungsrate) — die wöchentliche Rate wird hier separat und mit derselben
//     Rausch-Dämpfung wie computeTrendTdee (erste/letzte 2 Einträge mitteln) berechnet.
// ============================================================

data class GoalPrognosis(
    val estimatedGoalDate: LocalDate,
    val daysRemaining: Long,
    val actualWeeklyRateKg: Double,
    val targetWeeklyRateKg: Double,
    val isOnTrack: Boolean,
    val suggestedKcalAdjustment: Int,
    val previousEstimatedDate: LocalDate?,
    val daysEarlierOrLater: Long       // positiv = früher, negativ = später
)

class GoalPrognosisCalculator(
    private val weightRepository: WeightRepository,
    private val userProfileRepository: UserProfileRepository
) {
    companion object {
        const val KCAL_PER_KG_FAT = 7700.0
        const val ON_TRACK_TOLERANCE_KG_PER_WEEK = 0.15
        const val TREND_WINDOW_DAYS = 28
        const val MIN_TREND_ENTRIES = 4
    }

    suspend fun calculatePrognosis(): GoalPrognosis? {
        val profile = userProfileRepository.get().first()
        val targetWeight = profile.targetWeightKg ?: return null
        val weeklyTargetLoss = profile.weeklyTargetLossKg ?: 0.5
        val currentWeight = weightRepository.getLatest()?.weightKg ?: return null

        val weightToLose = currentWeight - targetWeight
        if (abs(weightToLose) < 0.1) return null

        val recentWeights = weightRepository.getRecent(TREND_WINDOW_DAYS).first()
        val actualWeeklyRate = computeActualWeeklyRate(recentWeights) ?: (-weeklyTargetLoss)

        val weeksRemaining = if (abs(actualWeeklyRate) > 0.05)
            abs(weightToLose / actualWeeklyRate)
        else
            abs(weightToLose / weeklyTargetLoss)

        val estimatedDate = LocalDate.now().plusDays((weeksRemaining * 7).toLong())

        val previousPrognosis = profile.lastPrognosisDateStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val daysEarlierOrLater = previousPrognosis?.let {
            ChronoUnit.DAYS.between(estimatedDate, it)
        } ?: 0L

        val rateDelta = actualWeeklyRate - (-weeklyTargetLoss)
        val isOnTrack = abs(rateDelta) <= ON_TRACK_TOLERANCE_KG_PER_WEEK
        val kcalAdjustment = if (!isOnTrack) {
            val neededDeltaKg = -weeklyTargetLoss - actualWeeklyRate
            ((neededDeltaKg * KCAL_PER_KG_FAT) / 7).toInt()
        } else 0

        userProfileRepository.update(profile.copy(lastPrognosisDateStr = estimatedDate.toString()))

        return GoalPrognosis(
            estimatedGoalDate = estimatedDate,
            daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), estimatedDate),
            actualWeeklyRateKg = actualWeeklyRate,
            targetWeeklyRateKg = -weeklyTargetLoss,
            isOnTrack = isOnTrack,
            suggestedKcalAdjustment = kcalAdjustment,
            previousEstimatedDate = previousPrognosis,
            daysEarlierOrLater = daysEarlierOrLater
        )
    }

    /** kg/Woche (negativ = Abnahme), aus den ersten/letzten 2 Gewichtseinträgen gemittelt
     *  (gleiche Rausch-Dämpfung wie AdaptiveTdeeCalculator.computeTrendTdee). Null, wenn
     *  zu wenige bzw. zu eng beieinanderliegende Einträge für einen belastbaren Trend da sind. */
    private fun computeActualWeeklyRate(weights: List<WeightEntry>): Double? {
        if (weights.size < MIN_TREND_ENTRIES) return null
        val sorted = weights.sortedBy { it.dateStr }
        val firstDate = LocalDate.parse(sorted.first().dateStr)
        val lastDate = LocalDate.parse(sorted.last().dateStr)
        val spanDays = ChronoUnit.DAYS.between(firstDate, lastDate)
        if (spanDays < MIN_TREND_ENTRIES - 1) return null

        val startWeight = sorted.take(2).map { it.weightKg }.average()
        val endWeight = sorted.takeLast(2).map { it.weightKg }.average()
        val changeKg = endWeight - startWeight
        return changeKg / (spanDays / 7.0)
    }
}

// ============================================================
// ViewModel — manuell instanziiert wie HomeViewModel (kein Hilt im Projekt)
// ============================================================

class GoalPrognosisViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NutriDatabase.getInstance(app)
    private val calculator = GoalPrognosisCalculator(WeightRepository(db), UserProfileRepository(db))

    private val _prognosis = MutableStateFlow<GoalPrognosis?>(null)
    val prognosis: StateFlow<GoalPrognosis?> = _prognosis.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _prognosis.value = calculator.calculatePrognosis()
        }
    }
}
