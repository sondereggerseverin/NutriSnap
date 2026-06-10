package ch.nutrisnap.app.ui.screens.recipes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeScrapeResult
import ch.nutrisnap.app.data.repository.RecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class RecipesUiState(
    val recipes:     List<Recipe> = emptyList(),
    val query:       String       = "",
    val isImporting: Boolean      = false,
    val importError: String?      = null,
    val lastImport:  Recipe?      = null
)

class RecipesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RecipeRepository(NutriDatabase.getInstance(app))

    private val _query       = MutableStateFlow("")
    private val _isImporting = MutableStateFlow(false)
    private val _importError = MutableStateFlow<String?>(null)
    private val _lastImport  = MutableStateFlow<Recipe?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<RecipesUiState> = combine(
        _query.flatMapLatest { q ->
            if (q.isBlank()) repo.getAll() else repo.search(q)
        },
        _query, _isImporting, _importError, _lastImport
    ) { recipes, q, importing, error, last ->
        RecipesUiState(recipes, q, importing, error, last)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipesUiState())

    fun setQuery(q: String) { _query.value = q }
    fun clearError()        { _importError.value = null }
    fun clearLastImport()   { _lastImport.value = null }

    fun importFromUrl(url: String) {
        viewModelScope.launch {
            _isImporting.value = true
            _importError.value = null
            val result: RecipeScrapeResult = repo.importFromUrl(url)
            _isImporting.value = false
            if (result.success) _lastImport.value = result.recipe
            else _importError.value = result.error ?: "Fehler beim Importieren"
        }
    }

    fun deleteRecipe(recipe: Recipe) {
        viewModelScope.launch { repo.deleteRecipe(recipe) }
    }
}
