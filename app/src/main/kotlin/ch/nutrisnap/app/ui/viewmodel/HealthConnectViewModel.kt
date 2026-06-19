package ch.nutrisnap.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.HealthConnectCache
import ch.nutrisnap.app.data.repository.HealthConnectRepository
import ch.nutrisnap.app.health.HealthConnectManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HealthConnectUiState(
    val todayData: HealthConnectCache? = null,
    val weeklyData: List<HealthConnectCache> = emptyList(),
    val isLoading: Boolean = false,
    val hasPermission: Boolean = false,
    val isAvailable: Boolean = false,
    val syncError: String? = null
)

class HealthConnectViewModel(app: Application) : AndroidViewModel(app) {

    private val db = NutriDatabase.getInstance(app)
    private val manager = HealthConnectManager(app)
    private val repository = HealthConnectRepository(manager, db.healthConnectDao())

    private val _uiState = MutableStateFlow(HealthConnectUiState())
    val uiState: StateFlow<HealthConnectUiState> = _uiState.asStateFlow()

    /** Dynamisches Kalorienziel: Basis-TDEE + Aktivitätskalorien aus HC */
    val adjustedCalorieGoal: StateFlow<Int> = _uiState.map { state ->
        2000 + (state.todayData?.totalActivityCalories ?: 0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 2000)

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
            if (hasPerm) syncNow()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, syncError = null) }
            repository.syncToday()
                .onSuccess { cache ->
                    _uiState.update { it.copy(isLoading = false, todayData = cache) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isLoading = false,
                        syncError = err.message ?: "Fehler beim Laden") }
                }
        }
    }

    fun onPermissionGranted() {
        _uiState.update { it.copy(hasPermission = true) }
        syncNow()
    }
}
