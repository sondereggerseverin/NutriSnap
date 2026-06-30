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
import kotlin.math.roundToInt
import java.time.LocalDate

data class RecipeGenUiState(
    val isLoading: Boolean = false,
    val recipe: GeneratedRecipe? = null,
    val error: String? = null,
    val savedToDiary: Boolean = false,
    val history: List<GeneratedRecipeEntity> = emptyList()
)

class RecipeGeneratorViewModel(app: Application) : AndroidViewModel(app) {
    private val service   = GroqRecipeGeneratorService()
    private val db        = NutriDatabase.getInstance(app)
    private val dao       = db.generatedRecipeDao()
    private val diaryDao  = db.diaryDao()
    private val json      = Json { ignoreUnknownKeys = true }

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
            _state.update { it.copy(isLoading = true, error = null, recipe = null, savedToDiary = false) }
            service.generateRecipe(userInput).fold(
                onSuccess = { recipe ->
                    _state.update { it.copy(isLoading = false, recipe = recipe) }
                    dao.insert(GeneratedRecipeEntity(
                        title           = recipe.title,
                        description     = recipe.description,
                        ingredients     = json.encodeToString(recipe.ingredients),
                        steps           = json.encodeToString(recipe.steps),
                        servings        = recipe.servings,
                        prepTimeMinutes = recipe.prepTimeMinutes,
                        calories        = recipe.calories.roundToInt(),
                        protein         = recipe.protein,
                        carbs           = recipe.carbs,
                        fat             = recipe.fat
                    ))
                    dao.trimHistory()
                },
                onFailure = { e ->
                    _state.update { it.copy(isLoading = false, error = e.message ?: "Unbekannter Fehler") }
                }
            )
        }
    }

    /** Fügt das KI-Rezept direkt ins heutige Tagebuch ein (pro Portion). */
    fun addToDiary(recipe: GeneratedRecipe, servings: Int, mealType: MealType) {
        viewModelScope.launch {
            val factor = servings.coerceAtLeast(1).toFloat() / recipe.servings.coerceAtLeast(1).toFloat()
            diaryDao.insert(
                DiaryEntry(
                    foodItemId  = 0,
                    foodName    = recipe.title,
                    amountGrams = servings.toFloat(),
                    mealType    = mealType,
                    dateStr     = LocalDate.now().toString(),
                    calories    = recipe.calories * factor,
                    protein     = recipe.protein  * factor,
                    carbs       = recipe.carbs    * factor,
                    fat         = recipe.fat      * factor
                )
            )
            _state.update { it.copy(savedToDiary = true) }
        }
    }

    fun clearError()      = _state.update { it.copy(error = null) }
    fun clearRecipe()     = _state.update { it.copy(recipe = null) }
    fun clearSavedFlag()  = _state.update { it.copy(savedToDiary = false) }
}
