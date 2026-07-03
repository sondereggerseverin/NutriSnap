package ch.nutrisnap.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.HealthConnectCache
import ch.nutrisnap.app.data.repository.HealthConnectRepository
import ch.nutrisnap.app.data.repository.UserProfileRepository
import ch.nutrisnap.app.data.supabase.SupabaseSync
import ch.nutrisnap.app.domain.AdaptiveCalorieTarget
import ch.nutrisnap.app.domain.AdaptiveTdeeCalculator
import ch.nutrisnap.app.health.HealthConnectManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class HealthConnectUiState(
    val todayData: HealthConnectCache? = null,
    val weeklyData: List<HealthConnectCache> = emptyList(),
    val isLoading: Boolean = false,
    val isHistoricalSyncing: Boolean = false,
    val hasPermission: Boolean = false,
    val isAvailable: Boolean = false,
    val syncError: String? = null,
    val samsungHealthSupported: Boolean = false,
    val samsungHealthPermission: Boolean? = null
)

data class WeeklyStats(
    val avgWeightKg: Double?,       // null if no data
    val weightTrend: Double?,       // kg change vs previous week (negative = lost weight)
    val avgActiveKcal: Double,      // average activity calories this week
    val kcalTrend: Double?,         // kcal change vs previous week
    val daysWithWeight: Int,
    val daysWithKcal: Int
)

class HealthConnectViewModel(app: Application) : AndroidViewModel(app) {

    private val db = NutriDatabase.getInstance(app)
    private val manager = HealthConnectManager(app)
    private val profileRepo = UserProfileRepository(db)
    private val repository = HealthConnectRepository(manager, db.healthConnectDao(), profileRepo, app)

    private val _uiState = MutableStateFlow(HealthConnectUiState())
    val uiState: StateFlow<HealthConnectUiState> = _uiState.asStateFlow()

    /**
     * Kalorienziel = TDEE aus dem Nutzerprofil (BMR * activityFactor).
     * Der activityFactor (z.B. 1.55 = "moderat aktiv") bildet die durchschnittliche
     * Aktivität bereits ab. Health-Connect-Aktivkalorien NICHT zusätzlich addieren,
     * sonst wird Aktivität doppelt gezählt (Bug: vorher 2000 + 100% HC-Aktivkalorien).
     * Fallback auf das manuell gesetzte dailyCalorieGoal, falls TDEE nicht berechenbar ist
     * (fehlende Gewicht/Größe/Alter-Angaben).
     */
    val adjustedCalorieGoal: StateFlow<Int> = profileRepo.get().map { profile ->
        (profile.computedTdee() ?: profile.dailyCalorieGoal.toDouble()).toInt()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 2000)

    /** Weekly stats computed from the last 30 days of data */
    val weeklyStats: StateFlow<WeeklyStats?> = repository.getLast30Days()
        .map { all ->
            if (all.isEmpty()) return@map null
            val sorted = all.sortedBy { it.date }
            val thisWeek  = sorted.takeLast(7)
            val prevWeek  = sorted.dropLast(7).takeLast(7)

            // Weight averages
            val thisWeekWeights = thisWeek.mapNotNull { it.weightKg }
            val prevWeekWeights = prevWeek.mapNotNull { it.weightKg }
            val avgWeight = if (thisWeekWeights.isNotEmpty()) thisWeekWeights.average() else null
            val weightTrend = if (thisWeekWeights.isNotEmpty() && prevWeekWeights.isNotEmpty())
                thisWeekWeights.average() - prevWeekWeights.average() else null

            // Calorie averages (only days with actual activity > 0 and real data)
            val thisWeekKcal = thisWeek.mapNotNull { it.activeCaloriesKcal }.filter { it > 10.0 }
            val prevWeekKcal = prevWeek.mapNotNull { it.activeCaloriesKcal }.filter { it > 10.0 }
            val avgKcal = if (thisWeekKcal.isNotEmpty()) thisWeekKcal.average() else 0.0
            val kcalTrend = if (thisWeekKcal.isNotEmpty() && prevWeekKcal.isNotEmpty())
                thisWeekKcal.average() - prevWeekKcal.average() else null

            WeeklyStats(
                avgWeightKg = avgWeight,
                weightTrend = weightTrend,
                avgActiveKcal = avgKcal,
                kcalTrend = kcalTrend,
                daysWithWeight = thisWeekWeights.size,
                daysWithKcal = thisWeekKcal.size
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Adaptive daily calorie target: uses the real weight+intake trend over the last
     * ~14 days when there's enough data (see AdaptiveTdeeCalculator.MIN_TREND_DAYS),
     * falling back to the BMR*activityFactor formula otherwise. Adds a damped
     * adjustment for how today's Health Connect / Samsung Health active calories
     * compare to the recent average, so a big training day (like a long ride or hike)
     * unlocks extra food without blowing the week's average deficit.
     */
    val adaptiveDailyTarget: StateFlow<AdaptiveCalorieTarget?> = combine(
        profileRepo.get(),
        repository.getLast30Days(),
        weeklyStats
    ) { profile, last30Days, weekly ->
        val fromDate = LocalDate.now().minusDays(13).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val diarySummaries = runCatching { db.diaryDao().getWeeklySummary(fromDate).firstOrNull() }
            .getOrNull() ?: emptyList()

        val weightByDate = last30Days
            .filter { it.weightKg != null }
            .associate { it.date to it.weightKg!!.toFloat() }
        val intakeByDate = diarySummaries
            .filter { it.calories > 0f }
            .associate { runCatching { LocalDate.parse(it.dateStr) }.getOrNull() to it.calories }
            .filterKeys { it != null }
            .mapKeys { it.key!! }

        val trendTdee = AdaptiveTdeeCalculator.computeTrendTdee(weightByDate, intakeByDate)
        val todayActiveKcal = last30Days.find { it.date == LocalDate.now() }?.activeCaloriesKcal

        AdaptiveTdeeCalculator.computeDailyTarget(
            trendTdee = trendTdee,
            formulaTdee = profile.computedTdee(),
            todayActiveKcal = todayActiveKcal,
            avgActiveKcal = weekly?.avgActiveKcal
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        val available = HealthConnectManager.isAvailable(app)
        val samsungSupported = runCatching { repository.isSamsungHealthSupported() }.getOrDefault(false)
        _uiState.update { it.copy(isAvailable = available, samsungHealthSupported = samsungSupported) }
        if (available) {
            observeData()
            checkPermissionsAndSync()
        }
        checkSamsungHealthPermission()
    }

    private fun checkSamsungHealthPermission() {
        if (!runCatching { repository.isSamsungHealthSupported() }.getOrDefault(false)) return
        viewModelScope.launch {
            val granted = runCatching { repository.hasSamsungHealthPermissions() }.getOrDefault(false)
            _uiState.update { it.copy(samsungHealthPermission = granted) }
        }
    }

    /** Must be called with a foreground Activity; Samsung Health shows its own consent dialog. */
    fun requestSamsungHealthPermissions(activity: android.app.Activity) {
        viewModelScope.launch {
            val granted = runCatching { repository.requestSamsungHealthPermissions(activity) }.getOrDefault(false)
            _uiState.update { it.copy(samsungHealthPermission = granted) }
            if (granted) {
                syncNow()
                syncHistorical()
            }
        }
    }

    private fun observeData() {
        viewModelScope.launch {
            repository.getTodayData().collect { cache ->
                _uiState.update { it.copy(todayData = cache) }
            }
        }
        viewModelScope.launch {
            repository.getLast7Days().collect { list ->
                _uiState.update { it.copy(weeklyData = list) }
            }
        }
    }

    private fun checkPermissionsAndSync() {
        viewModelScope.launch {
            val hasPerm = runCatching { repository.hasPermissions() }.getOrDefault(false)
            _uiState.update { it.copy(hasPermission = hasPerm) }
            if (hasPerm) {
                syncNow()
                // Also kick off historical sync in background
                syncHistorical()
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, syncError = null) }
            repository.syncToday()
                .onSuccess { cache ->
                    _uiState.update { it.copy(isLoading = false, todayData = cache, syncError = null) }
                    pushHealthDaily(cache)
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            syncError = "${err::class.simpleName}: ${err.message ?: "Unbekannter Fehler"}"
                        )
                    }
                }
        }
    }

    /**
     * Pusht Aktivkalorien/Schritte eines Tages nach Supabase (health_daily), damit die
     * Web-App den Sport-Bonus im adaptiven Kalorienziel mitberechnen kann. Android bleibt
     * die alleinige Quelle (Health Connect / Samsung Health SDK); Fehler sind nicht
     * kritisch fuer die App-Funktion und werden nur geloggt.
     */
    private fun pushHealthDaily(cache: HealthConnectCache) {
        viewModelScope.launch {
            runCatching {
                SupabaseSync.upsertHealthDaily(
                    dateStr = cache.date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    activeCaloriesKcal = cache.activeCaloriesKcal,
                    steps = cache.steps,
                    weightKg = cache.weightKg
                )
            }.onFailure { android.util.Log.w("HealthConnect", "Push health_daily fehlgeschlagen: ${it.message}") }
        }
    }

    private fun pushHistoricalHealthDaily() {
        viewModelScope.launch {
            runCatching {
                val days = repository.getLast30Days().firstOrNull().orEmpty()
                for (d in days) {
                    if (d.activeCaloriesKcal != null || d.steps > 0 || d.weightKg != null) {
                        SupabaseSync.upsertHealthDaily(
                            dateStr = d.date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            activeCaloriesKcal = d.activeCaloriesKcal,
                            steps = d.steps,
                            weightKg = d.weightKg
                        )
                    }
                }
            }.onFailure { android.util.Log.w("HealthConnect", "Push historical health_daily fehlgeschlagen: ${it.message}") }
        }
    }

    fun syncHistorical() {
        viewModelScope.launch {
            _uiState.update { it.copy(isHistoricalSyncing = true) }
            repository.syncHistorical(30)
                .onSuccess { pushHistoricalHealthDaily() }
                .onFailure { err ->
                    // Historical sync errors are silent (non-blocking)
                    android.util.Log.w("HealthConnect", "Historical sync failed: ${err.message}")
                }
            _uiState.update { it.copy(isHistoricalSyncing = false) }
        }
    }

    fun onPermissionGranted() {
        viewModelScope.launch {
            delay(500)
            val hasPerm = runCatching { repository.hasPermissions() }.getOrDefault(false)
            _uiState.update { it.copy(hasPermission = hasPerm) }
            if (hasPerm) {
                syncNow()
                syncHistorical()
            }
        }
    }

    fun retrySync() {
        viewModelScope.launch {
            _uiState.update { it.copy(syncError = null) }
            checkPermissionsAndSync()
        }
    }
}
