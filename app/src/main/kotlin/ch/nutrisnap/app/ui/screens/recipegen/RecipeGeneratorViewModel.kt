package ch.nutrisnap.app.ui.screens.recipegen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.GeneratedRecipeEntity
import ch.nutrisnap.app.domain.GeneratedRecipe
import ch.nutrisnap.app.domain.GroqRecipeGeneratorService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class RecipeGenUiState(
    val isLoading: Boolean = false,
    val recipe: GeneratedRecipe? = null,
    val error: String? = null,
    val history: List<GeneratedRecipeEntity> = emptyList()
)

class RecipeGeneratorViewModel(app: Application) : AndroidViewModel(app) {
    private val service = GroqRecipeGeneratorService()
    private val dao = NutriDatabase.getInstance(app).generatedRecipeDao()
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(RecipeGenUiState())
    val state: StateFlow<RecipeGenUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            dao.getHistory().collect { history ->
                _state.update { it.copy(history = history) }
            }
        }
    }

    fun generate(userInput: String) {
        if (userInput.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, recipe = null) }
            service.generateRecipe(userInput).fold(
                onSuccess = { recipe ->
                    _state.update { it.copy(isLoading = false, recipe = recipe) }
                    dao.insert(GeneratedRecipeEntity(
                        title = recipe.title,
                        description = recipe.description,
                        ingredients = json.encodeToString(recipe.ingredients),
                        steps = json.encodeToString(recipe.steps),
                        servings = recipe.servings,
                        prepTimeMinutes = recipe.prepTimeMinutes,
                        calories = recipe.calories,
                        protein = recipe.protein,
                        carbs = recipe.carbs,
                        fat = recipe.fat
                    ))
                    dao.trimHistory()
                },
                onFailure = { e ->
                    _state.update { it.copy(isLoading = false, error = e.message ?: "Unbekannter Fehler") }
                }
            )
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
    fun clearRecipe() = _state.update { it.copy(recipe = null) }
}
