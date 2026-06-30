package ch.nutrisnap.app.ui.screens.recipegen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.GeneratedRecipeEntity
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.repository.RecipeRepository
import ch.nutrisnap.app.domain.GeneratedRecipe
import ch.nutrisnap.app.domain.GroqRecipeGeneratorService
import ch.nutrisnap.app.domain.RecipeIngredient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import java.time.LocalDate

data class RecipeGenUiState(
    val isLoading: Boolean = false,
    val recipe: GeneratedRecipe? = null,
    /** id of the generated_recipes row currently shown, if opened from/saved to history */
    val openHistoryId: Int? = null,
    val error: String? = null,
    val savedToDiary: Boolean = false,
    val savedAsRecipe: Boolean = false,
    val history: List<GeneratedRecipeEntity> = emptyList()
)

class RecipeGeneratorViewModel(app: Application) : AndroidViewModel(app) {
    private val service     = GroqRecipeGeneratorService()
    private val db          = NutriDatabase.getInstance(app)
    private val dao         = db.generatedRecipeDao()
    private val diaryDao    = db.diaryDao()
    private val recipeRepo  = RecipeRepository(db, app)
    private val json        = Json { ignoreUnknownKeys = true }

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
            _state.update { it.copy(isLoading = true, error = null, recipe = null, openHistoryId = null, savedToDiary = false, savedAsRecipe = false) }
            service.generateRecipe(userInput).fold(
                onSuccess = { recipe ->
                    val newId = dao.insert(recipe.toEntity())
                    _state.update { it.copy(isLoading = false, recipe = recipe, openHistoryId = newId.toInt()) }
                    dao.trimHistory()
                },
                onFailure = { e ->
                    _state.update { it.copy(isLoading = false, error = e.message ?: "Unbekannter Fehler") }
                }
            )
        }
    }

    /** Öffnet ein zuvor generiertes Rezept aus dem Verlauf zur Ansicht/Bearbeitung. */
    fun openFromHistory(entity: GeneratedRecipeEntity) {
        _state.update {
            it.copy(
                recipe = entity.toDomain(json),
                openHistoryId = entity.id,
                error = null,
                savedToDiary = false,
                savedAsRecipe = false
            )
        }
    }

    fun deleteFromHistory(entity: GeneratedRecipeEntity) {
        viewModelScope.launch {
            dao.delete(entity)
            if (_state.value.openHistoryId == entity.id) {
                _state.update { it.copy(recipe = null, openHistoryId = null) }
            }
        }
    }

    /** Persistiert Edits (Titel, Makros, einzelne Zutaten) am aktuell geöffneten Rezept. */
    fun updateRecipe(updated: GeneratedRecipe) {
        viewModelScope.launch {
            _state.update { it.copy(recipe = updated) }
            val id = _state.value.openHistoryId
            if (id != null) {
                dao.insert(updated.toEntity().copy(id = id))
            }
        }
    }

    fun removeIngredient(index: Int) {
        val current = _state.value.recipe ?: return
        val newIngredients = current.structuredIngredients.toMutableList().also {
            if (index in it.indices) it.removeAt(index)
        }
        updateRecipe(current.copy(structuredIngredients = newIngredients))
    }

    fun updateIngredient(index: Int, ingredient: RecipeIngredient) {
        val current = _state.value.recipe ?: return
        val newIngredients = current.structuredIngredients.toMutableList().also {
            if (index in it.indices) it[index] = ingredient
        }
        updateRecipe(current.copy(structuredIngredients = newIngredients))
    }

    /** Speichert das KI-Rezept dauerhaft im Rezepte-Tab (Kochbuch). */
    fun saveAsRecipe() {
        val r = _state.value.recipe ?: return
        viewModelScope.launch {
            recipeRepo.saveRecipe(
                Recipe(
                    title             = r.title,
                    description       = r.description,
                    ingredients       = r.effectiveIngredients().joinToString("\n") { ing ->
                        if (ing.amount.isNotBlank()) "${ing.amount} ${ing.name}" else ing.name
                    },
                    instructions      = r.steps.joinToString("\n"),
                    totalCalories     = r.calories,
                    proteinPerServing = r.protein,
                    carbsPerServing   = r.carbs,
                    fatPerServing     = r.fat,
                    servings          = r.servings,
                    prepTimeMinutes   = r.prepTimeMinutes,
                    platform          = "ki"
                )
            )
            _state.update { it.copy(savedAsRecipe = true) }
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

    fun clearError()       = _state.update { it.copy(error = null) }
    fun clearRecipe()      = _state.update { it.copy(recipe = null, openHistoryId = null) }
    fun clearSavedFlag()   = _state.update { it.copy(savedToDiary = false) }
    fun clearSavedAsRecipeFlag() = _state.update { it.copy(savedAsRecipe = false) }

    private fun GeneratedRecipe.toEntity() = GeneratedRecipeEntity(
        title           = title,
        description     = description,
        ingredients     = json.encodeToString(structuredIngredients.ifEmpty {
            ingredients.map { RecipeIngredient(name = it) }
        }),
        steps           = json.encodeToString(steps),
        servings        = servings,
        prepTimeMinutes = prepTimeMinutes,
        calories        = calories.roundToInt(),
        protein         = protein,
        carbs           = carbs,
        fat             = fat
    )

    private fun GeneratedRecipeEntity.toDomain(json: Json): GeneratedRecipe {
        val structured = runCatching {
            json.decodeFromString<List<RecipeIngredient>>(ingredients)
        }.getOrElse {
            runCatching { json.decodeFromString<List<String>>(ingredients).map { RecipeIngredient(name = it) } }
                .getOrDefault(emptyList())
        }
        val stepsList = runCatching { json.decodeFromString<List<String>>(steps) }.getOrDefault(emptyList())
        return GeneratedRecipe(
            title                 = title,
            description           = description,
            structuredIngredients = structured,
            steps                 = stepsList,
            servings              = servings,
            prepTimeMinutes       = prepTimeMinutes,
            calories              = calories.toFloat(),
            protein               = protein,
            carbs                 = carbs,
            fat                   = fat
        )
    }
}
