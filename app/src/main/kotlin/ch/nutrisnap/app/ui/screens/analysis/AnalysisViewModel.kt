package ch.nutrisnap.app.ui.screens.analysis

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.DailySummary
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.HealthConnectCache
import ch.nutrisnap.app.data.model.WeightEntry
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.repository.HealthConnectRepository
import ch.nutrisnap.app.data.repository.StatsRepository
import ch.nutrisnap.app.data.repository.UserProfile
import ch.nutrisnap.app.data.repository.UserProfileRepository
import ch.nutrisnap.app.data.repository.WeightRepository
import ch.nutrisnap.app.health.HealthConnectManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class AnalysisPeriod { TAG, WOCHE, MONAT }

data class DayPoint(
    val date:             LocalDate,
    val label:            String,
    val calories:         Float,
    val protein:          Float,
    val carbs:            Float,
    val fat:              Float,
    val activityCalories: Float,
    val weightKg:         Float?
)

/** Aggregierter Wochen-Block innerhalb der Monatsansicht (Ø pro Tag in dieser Woche). */
data class WeekBucket(
    val label:            String,
    val calories:         Float,
    val activityCalories: Float
)

data class AnalysisUiState(
    val period:                AnalysisPeriod = AnalysisPeriod.WOCHE,
    val anchorDate:             LocalDate = LocalDate.now(),
    val rangeFrom:              LocalDate = LocalDate.now(),
    val rangeTo:                LocalDate = LocalDate.now(),
    val rangeLabel:             String = "",
    val isCurrentPeriod:        Boolean = true,
    val days:                   List<DayPoint> = emptyList(),
    val weekBuckets:            List<WeekBucket> = emptyList(),
    val goals:                  UserProfile = UserProfile(),
    val streak:                 Int = 0,
    val avgCalories:            Int = 0,
    val avgProtein:             Float = 0f,
    val avgCarbs:                Float = 0f,
    val avgFat:                  Float = 0f,
    val avgActivityCalories:    Int = 0,
    val totalCalories:          Int = 0,
    val totalActivityCalories:  Int = 0,
    val weightStart:            Float? = null,
    val weightEnd:              Float? = null,
    val isSyncingHistory:       Boolean = false,
    val hasHistoryPermission:        Boolean = false,
    // true, wenn der sichtbare Zeitraum > 30 Tage zurückliegt und die History-Permission
    // noch fehlt -> Health Connect liefert fuer diese Tage sonst einfach nichts zurueck.
    val showHistoryPermissionPrompt: Boolean = false
)

private val dayFormatter   = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)
private val shortFormatter = DateTimeFormatter.ofPattern("d.M.", Locale.GERMAN)

private data class Range(
    val anchor:    LocalDate,
    val from:      LocalDate,
    val to:        LocalDate,
    val label:     String,
    val isCurrent: Boolean
)

class AnalysisViewModel(app: Application) : AndroidViewModel(app) {
    private val db          = NutriDatabase.getInstance(app)
    private val diaryRepo   = DiaryRepository(db)
    private val profileRepo = UserProfileRepository(db)
    private val weightRepo  = WeightRepository(db)
    private val statsRepo   = StatsRepository(db)
    private val healthRepo  = HealthConnectRepository(HealthConnectManager(app), db.healthConnectDao(), profileRepo, app)

    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    private var collectJob: Job? = null

    /** Fuer den PermissionController-Launcher im Screen. */
    val historyPermissionSet: Set<String> = setOf(HealthConnectManager.HISTORY_PERMISSION)

    init {
        loadRange(AnalysisPeriod.WOCHE, LocalDate.now())
    }

    /** Nach Rueckkehr vom History-Permission-Dialog: Status neu pruefen und Range neu laden. */
    fun onHistoryPermissionResult() {
        loadRange(_uiState.value.period, _uiState.value.anchorDate)
    }

    fun selectPeriod(period: AnalysisPeriod) {
        if (period == _uiState.value.period) return
        loadRange(period, _uiState.value.anchorDate)
    }

    fun goToPrevious() {
        val s = _uiState.value
        loadRange(s.period, shiftAnchor(s.period, s.anchorDate, -1))
    }

    fun goToNext() {
        val s = _uiState.value
        if (s.isCurrentPeriod) return
        loadRange(s.period, shiftAnchor(s.period, s.anchorDate, 1))
    }

    fun goToToday() = loadRange(_uiState.value.period, LocalDate.now())

    /** Kalender-Sprung zu einem beliebigen (auch weit zurueckliegenden) Datum. */
    fun goToDate(date: LocalDate) = loadRange(_uiState.value.period, date)

    private fun shiftAnchor(period: AnalysisPeriod, anchor: LocalDate, dir: Int): LocalDate = when (period) {
        AnalysisPeriod.TAG   -> anchor.plusDays(dir.toLong())
        AnalysisPeriod.WOCHE -> anchor.plusWeeks(dir.toLong())
        AnalysisPeriod.MONAT -> anchor.plusMonths(dir.toLong())
    }

    private fun rangeFor(period: AnalysisPeriod, anchor: LocalDate): Range {
        val today = LocalDate.now()
        return when (period) {
            AnalysisPeriod.TAG -> Range(
                anchor    = anchor,
                from      = anchor,
                to        = anchor,
                label     = anchor.format(dayFormatter),
                isCurrent = anchor == today
            )
            AnalysisPeriod.WOCHE -> {
                val from = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val to   = from.plusDays(6)
                Range(
                    anchor    = anchor,
                    from      = from,
                    to        = to,
                    label     = "${from.format(shortFormatter)} \u2013 ${to.format(shortFormatter)}",
                    isCurrent = !today.isBefore(from) && !today.isAfter(to)
                )
            }
            AnalysisPeriod.MONAT -> {
                val ym = YearMonth.from(anchor)
                Range(
                    anchor    = anchor,
                    from      = ym.atDay(1),
                    to        = ym.atEndOfMonth(),
                    label     = "${anchor.month.getDisplayName(TextStyle.FULL, Locale.GERMAN)} ${anchor.year}",
                    isCurrent = YearMonth.from(today) == ym
                )
            }
        }
    }

    /**
     * Laedt einen neuen Zeitraum: bricht den vorherigen Collector ab, aktualisiert
     * Zeitraum-Metadaten sofort (fuer responsives UI), stoesst dann bei Bedarf einen
     * On-Demand-Sync fuer fehlende Health-Connect-Tage an (z.B. bei Kalendersprung in
     * die Vergangenheit) und sammelt danach die kombinierten Daten live weiter.
     */
    private fun loadRange(period: AnalysisPeriod, anchor: LocalDate) {
        collectJob?.cancel()
        val range = rangeFor(period, anchor)
        _uiState.update {
            it.copy(
                period          = period,
                anchorDate      = anchor,
                rangeFrom       = range.from,
                rangeTo         = range.to,
                rangeLabel      = range.label,
                isCurrentPeriod = range.isCurrent,
                isSyncingHistory = true
            )
        }
        collectJob = viewModelScope.launch {
            val hasHistory = healthRepo.hasHistoryPermission()
            // ensureRangeSynced holt trotzdem alles Erreichbare - ohne History-Permission
            // liefert Health Connect fuer Tage > 30 Tage einfach nichts, kein Fehler.
            healthRepo.ensureRangeSynced(range.from, range.to)
            val streak = statsRepo.calculateStreak()

            combine(
                diaryRepo.getSummaryBetween(range.from, range.to),
                healthRepo.getRange(range.from, range.to),
                weightRepo.getAll(),
                profileRepo.get()
            ) { summaries, hcCache, weightEntries, profile ->
                buildState(period, range, summaries, hcCache, weightEntries, profile, streak, hasHistory)
            }.collect { state -> _uiState.value = state }
        }
    }

    private fun buildState(
        period:        AnalysisPeriod,
        range:         Range,
        summaries:     List<DailySummary>,
        hcCache:       List<HealthConnectCache>,
        weightEntries: List<WeightEntry>,
        profile:       UserProfile,
        streak:        Int,
        hasHistory:    Boolean
    ): AnalysisUiState {
        val summaryByDate     = summaries.associateBy { it.dateStr }
        val hcByDate           = hcCache.associateBy { it.date }
        val manualWeightByDate = weightEntries.associateBy { LocalDate.parse(it.dateStr) }

        val dayCount = ChronoUnit.DAYS.between(range.from, range.to).toInt() + 1
        val days = (0 until dayCount).map { offset ->
            val date = range.from.plusDays(offset.toLong())
            val s  = summaryByDate[date.toString()]
            val hc = hcByDate[date]
            // Health Connect ist die primaere Quelle (Waage-Sync); manueller Eintrag
            // von der Startseite dient als Fallback fuer Tage ohne HC-Messung.
            val weight = hc?.weightKg?.toFloat() ?: manualWeightByDate[date]?.weightKg
            DayPoint(
                date             = date,
                label            = if (period == AnalysisPeriod.MONAT) date.dayOfMonth.toString()
                                    else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN),
                calories         = s?.calories ?: 0f,
                protein          = s?.protein  ?: 0f,
                carbs            = s?.carbs    ?: 0f,
                fat              = s?.fat      ?: 0f,
                activityCalories = hc?.activeCaloriesKcal?.toFloat() ?: 0f,
                weightKg         = weight
            )
        }

        val n = days.size.coerceAtLeast(1)
        val weightPoints = days.filter { it.weightKg != null }

        val weekBuckets = if (period == AnalysisPeriod.MONAT) {
            days.chunked(7).map { chunk ->
                WeekBucket(
                    label            = "${chunk.first().date.dayOfMonth}.\u2013${chunk.last().date.dayOfMonth}.",
                    calories         = (chunk.sumOf { it.calories.toDouble() } / chunk.size).toFloat(),
                    activityCalories = (chunk.sumOf { it.activityCalories.toDouble() } / chunk.size).toFloat()
                )
            }
        } else emptyList()

        return AnalysisUiState(
            period                 = period,
            anchorDate             = range.anchor,
            rangeFrom              = range.from,
            rangeTo                = range.to,
            rangeLabel             = range.label,
            isCurrentPeriod        = range.isCurrent,
            days                   = days,
            weekBuckets            = weekBuckets,
            goals                  = profile,
            streak                 = streak,
            avgCalories            = (days.sumOf { it.calories.toDouble() } / n).toInt(),
            avgProtein             = (days.sumOf { it.protein.toDouble() } / n).toFloat(),
            avgCarbs               = (days.sumOf { it.carbs.toDouble() } / n).toFloat(),
            avgFat                 = (days.sumOf { it.fat.toDouble() } / n).toFloat(),
            avgActivityCalories    = (days.sumOf { it.activityCalories.toDouble() } / n).toInt(),
            totalCalories          = days.sumOf { it.calories.toDouble() }.toInt(),
            totalActivityCalories  = days.sumOf { it.activityCalories.toDouble() }.toInt(),
            weightStart            = weightPoints.firstOrNull()?.weightKg,
            weightEnd              = weightPoints.lastOrNull()?.weightKg,
            isSyncingHistory       = false,
            hasHistoryPermission        = hasHistory,
            showHistoryPermissionPrompt = !hasHistory && range.from.isBefore(LocalDate.now().minusDays(29))
        )
    }
}
