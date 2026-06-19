package ch.nutrisnap.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.model.HealthConnectCache
import ch.nutrisnap.app.data.repository.HealthConnectRepository
import ch.nutrisnap.app.health.HealthConnectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HealthConnectUiState(
    val todayData: HealthConnectCache? = null,
    val weeklyData: List<HealthConnectCache> = emptyList(),
    val isLoading: Boolean = false,
    val hasPermission: Boolean = false,
    val syncError: String? = null,
    val lastSyncedText: String = "Noch nie"
)

@HiltViewModel
class HealthConnectViewModel @Inject constructor(
    private val repository: HealthConnectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HealthConnectUiState())
    val uiState: StateFlow<HealthConnectUiState> = _uiState.asStateFlow()

    /** Dynamisches Kalorienziel: Basis + Aktivitätskalorien */
    val adjustedCalorieGoal: StateFlow<Int> = _uiState.map { state ->
        val baseGoal = 2000 // TODO: aus User-Profil laden
        val activityCalories = state.todayData?.totalActivityCalories ?: 0
        baseGoal + activityCalories
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 2000)

    init {
        loadData()
        observeTodayData()
    }

    private fun observeTodayData() {
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

    private fun loadData() {
        viewModelScope.launch {
            val hasPerm = repository.hasPermissions()
            _uiState.update { it.copy(hasPermission = hasPerm) }
            if (hasPerm) syncNow()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, syncError = null) }
            repository.syncToday()
                .onSuccess { cache ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            todayData = cache,
                            lastSyncedText = "Gerade eben"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            syncError = error.message ?: "Fehler beim Laden"
                        )
                    }
                }
        }
    }

    fun onPermissionGranted() {
        _uiState.update { it.copy(hasPermission = true) }
        syncNow()
    }
}
