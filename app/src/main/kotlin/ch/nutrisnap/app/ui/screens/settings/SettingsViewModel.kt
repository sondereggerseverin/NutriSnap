package ch.nutrisnap.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.repository.RecipeRepository
import ch.nutrisnap.app.data.repository.UserProfile
import ch.nutrisnap.app.data.repository.UserProfileRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val profile:   UserProfile = UserProfile(),
    val isSaved:   Boolean     = false
)

data class ReanalyzeProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val currentTitle: String = "",
    val finishedOk: Int? = null,
    val error: String? = null
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = UserProfileRepository(NutriDatabase.getInstance(app))
    private val recipeRepo = RecipeRepository(NutriDatabase.getInstance(app), app)

    val uiState: StateFlow<SettingsUiState> = repo.get()
        .map { SettingsUiState(profile = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private val _reanalyze = MutableStateFlow(ReanalyzeProgress())
    val reanalyze: StateFlow<ReanalyzeProgress> = _reanalyze.asStateFlow()

    fun save(profile: UserProfile) {
        viewModelScope.launch {
            repo.update(profile)
        }
    }

    /** Alle lokalen Rezepte neu analysieren (Standards, Fiber, KI für unmatched). */
    fun reanalyzeAllRecipes() {
        if (_reanalyze.value.running) return
        viewModelScope.launch {
            _reanalyze.value = ReanalyzeProgress(running = true)
            runCatching {
                recipeRepo.reanalyzeAllRecipes { done, total, title ->
                    _reanalyze.value = ReanalyzeProgress(
                        running = done < total,
                        done = done,
                        total = total,
                        currentTitle = title
                    )
                }
            }.onSuccess { ok ->
                _reanalyze.value = ReanalyzeProgress(
                    running = false,
                    done = ok,
                    total = ok,
                    finishedOk = ok
                )
            }.onFailure { e ->
                _reanalyze.value = ReanalyzeProgress(
                    running = false,
                    error = e.message ?: "Unbekannter Fehler"
                )
            }
        }
    }

    fun clearReanalyzeMessage() {
        _reanalyze.value = ReanalyzeProgress()
    }
}
