package ch.nutrisnap.app.ui.screens.recipegen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.GeneratedRecipeEntity
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.domain.GeneratedRecipe
import ch.nutrisnap.app.domain.GroqRecipeGeneratorService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

data class RecipeGenUiState(
    val isLoading: Boolean = false,
    val recipe: GeneratedRecipe? = null,
    val error: String? = null,
    val history: List<GeneratedRecipeEntity> = emptyList(),
    val addedToDiary: Boolean = false
)

class RecipeGeneratorViewModel(app: Application) : AndroidViewModel(app) {
    private val service = GroqRecipeGeneratorService()
    private val db = NutriDatabase.getInstance(app)
    private val dao = db.generatedRecipeDao()
    private val diaryDao = db.diaryDao()
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
            _state.update { it.copy(isLoading = true, error = null, recipe = null, addedToDiary = false) }
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

    fun addGeneratedRecipeToDiary(
        recipe: GeneratedRecipe,
        servings: Float,
        meal: MealType,
        date: LocalDate = LocalDate.now()
    ) {
        viewModelScope.launch {
            val factor = servings / recipe.servings.coerceAtLeast(1).toFloat()
            diaryDao.insert(
                DiaryEntry(
                    foodItemId  = -999, // marks as generated recipe
                    foodName    = recipe.title,
                    amountGrams = servings,
                    mealType    = meal,
                    dateStr     = date.toString(),
                    calories    = recipe.calories * factor,
                    protein     = recipe.protein  * factor,
                    carbs       = recipe.carbs    * factor,
                    fat         = recipe.fat      * factor
                )
            )
            _state.update { it.copy(addedToDiary = true) }
        }
    }

    fun clearAddedToDiary() = _state.update { it.copy(addedToDiary = false) }
    fun clearError() = _state.update { it.copy(error = null) }
    fun clearRecipe() = _state.update { it.copy(recipe = null, addedToDiary = false) }
}
