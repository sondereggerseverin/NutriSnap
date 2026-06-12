package ch.nutrisnap.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.repository.UserProfile
import ch.nutrisnap.app.data.repository.UserProfileRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val profile:   UserProfile = UserProfile(),
    val isSaved:   Boolean     = false
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = UserProfileRepository(NutriDatabase.getInstance(app))

    val uiState: StateFlow<SettingsUiState> = repo.get()
        .map { SettingsUiState(profile = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun save(profile: UserProfile) {
        viewModelScope.launch {
            repo.update(profile)
        }
    }
}
