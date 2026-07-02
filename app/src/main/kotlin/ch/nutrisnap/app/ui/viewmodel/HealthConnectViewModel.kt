package ch.nutrisnap.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.HealthConnectCache
import ch.nutrisnap.app.data.repository.HealthConnectRepository
import ch.nutrisnap.app.data.repository.UserProfileRepository
import ch.nutrisnap.app.health.HealthConnectManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HealthConnectUiState(
    val todayData: HealthConnectCache? = null,
    val weeklyData: List<HealthConnectCache> = emptyList(),
    val isLoading: Boolean = false,
    val isHistoricalSyncing: Boolean = false,
    val hasPermission: Boolean = false,
    val isAvailable: Boolean = false,
    val syncError: String? = null
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
    private val repository = HealthConnectRepository(manager, db.healthConnectDao(), profileRepo)

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

    init {
        val available = HealthConnectManager.isAvailable(app)
        _uiState.update { it.copy(isAvailable = available) }
        if (available) {
            observeData()
            checkPermissionsAndSync()
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

    fun syncHistorical() {
        viewModelScope.launch {
            _uiState.update { it.copy(isHistoricalSyncing = true) }
            repository.syncHistorical(30)
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
