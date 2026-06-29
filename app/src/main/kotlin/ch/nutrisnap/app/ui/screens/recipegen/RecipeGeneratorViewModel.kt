package ch.nutrisnap.app.ui.screens.recipegen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.GeneratedRecipeEntity
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.domain.GeneratedRecipe
import ch.nutrisnap.app.domain.GroqRecipeGeneratorService
import ch.nutrisnap.app.domain.RecipeIngredient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

data class RecipeGenUiState(
    val isLoading: Boolean = false,
    val recipe: GeneratedRecipe? = null,
    // Editable copy of ingredients — user can modify these
    val editableIngredients: List<RecipeIngredient> = emptyList(),
    val error: String? = null,
    val history: List<GeneratedRecipeEntity> = emptyList(),
    val addedToDiary: Boolean = false,
    val savedAsRecipe: Boolean = false,
    val savedAndTracked: Boolean = false
)

class RecipeGeneratorViewModel(app: Application) : AndroidViewModel(app) {
    private val service = GroqRecipeGeneratorService()
    private val db = NutriDatabase.getInstance(app)
    private val dao = db.generatedRecipeDao()
    private val diaryDao = db.diaryDao()
    private val recipeDao = db.recipeDao()
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
            _state.update { it.copy(isLoading = true, error = null, recipe = null, editableIngredients = emptyList(), addedToDiary = false, savedAsRecipe = false) }
            service.generateRecipe(userInput).fold(
                onSuccess = { recipe ->
                    _state.update { it.copy(
                        isLoading = false,
                        recipe = recipe,
                        editableIngredients = recipe.effectiveIngredients().toMutableList()
                    )}
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

    // ── Ingredient editing ────────────────────────────────────────────────────

    fun updateIngredient(index: Int, ingredient: RecipeIngredient) {
        val list = _state.value.editableIngredients.toMutableList()
        if (index in list.indices) {
            list[index] = ingredient
            _state.update { it.copy(editableIngredients = list) }
        }
    }

    fun removeIngredient(index: Int) {
        val list = _state.value.editableIngredients.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _state.update { it.copy(editableIngredients = list) }
        }
    }

    fun addIngredient(ingredient: RecipeIngredient) {
        val list = _state.value.editableIngredients.toMutableList()
        list.add(ingredient)
        _state.update { it.copy(editableIngredients = list) }
    }

    /** Recalculates total macros from editable ingredients / servings */
    fun recalcTotals(): GeneratedRecipe? {
        val state = _state.value
        val recipe = state.recipe ?: return null
        val servings = recipe.servings.coerceAtLeast(1)
        val ings = state.editableIngredients
        val totalCal = ings.sumOf { it.calories }
        val totalProt = ings.sumOf { it.protein.toDouble() }.toFloat()
        val totalCarbs = ings.sumOf { it.carbs.toDouble() }.toFloat()
        val totalFat = ings.sumOf { it.fat.toDouble() }.toFloat()
        return recipe.copy(
            structuredIngredients = ings,
            calories = (totalCal / servings),
            protein = totalProt / servings,
            carbs = totalCarbs / servings,
            fat = totalFat / servings
        )
    }

    // ── Save / Track ──────────────────────────────────────────────────────────

    fun addToDiary(servings: Float, meal: MealType, date: LocalDate = LocalDate.now()) {
        val recipe = recalcTotals() ?: return
        viewModelScope.launch {
            val factor = servings / recipe.servings.coerceAtLeast(1).toFloat()
            diaryDao.insert(DiaryEntry(
                foodItemId  = -999,
                foodName    = recipe.title,
                amountGrams = servings,
                mealType    = meal,
                dateStr     = date.toString(),
                calories    = recipe.calories * factor,
                protein     = recipe.protein  * factor,
                carbs       = recipe.carbs    * factor,
                fat         = recipe.fat      * factor
            ))
            _state.update { it.copy(addedToDiary = true) }
        }
    }

    fun saveAsRecipe() {
        val recipe = recalcTotals() ?: return
        viewModelScope.launch {
            val ings = _state.value.editableIngredients
            val ingredientLines = ings.map { "${it.amount} ${it.name}".trim() }
            recipeDao.insert(Recipe(
                title           = recipe.title,
                description     = recipe.description,
                ingredients     = ingredientLines.joinToString("\n"),
                instructions    = recipe.steps.joinToString("\n"),
                servings        = recipe.servings,
                prepTimeMinutes = recipe.prepTimeMinutes,
                totalCalories   = recipe.calories.toFloat(),
                proteinPerServing = recipe.protein,
                carbsPerServing   = recipe.carbs,
                fatPerServing     = recipe.fat,
                platform        = "KI-Koch"
            ))
            _state.update { it.copy(savedAsRecipe = true) }
        }
    }

    fun addToDiaryAndSave(servings: Float, meal: MealType, date: LocalDate = LocalDate.now()) {
        addToDiary(servings, meal, date)
        saveAsRecipe()
        _state.update { it.copy(savedAndTracked = true) }
    }

    // ── Legacy compat ─────────────────────────────────────────────────────────

    @Deprecated("Use addToDiary")
    fun addGeneratedRecipeToDiary(recipe: GeneratedRecipe, servings: Float, meal: MealType, date: LocalDate = LocalDate.now()) =
        addToDiary(servings, meal, date)

    fun clearAddedToDiary() = _state.update { it.copy(addedToDiary = false, savedAsRecipe = false, savedAndTracked = false) }
    fun clearError() = _state.update { it.copy(error = null) }
    fun clearRecipe() = _state.update { it.copy(recipe = null, editableIngredients = emptyList(), addedToDiary = false) }

    fun loadFromHistory(entity: GeneratedRecipeEntity) {
        val recipe = GeneratedRecipe(
            title           = entity.title,
            description     = entity.description,
            ingredients     = runCatching { json.decodeFromString<List<String>>(entity.ingredients) }.getOrDefault(emptyList()),
            steps           = runCatching { json.decodeFromString<List<String>>(entity.steps) }.getOrDefault(emptyList()),
            servings        = entity.servings,
            prepTimeMinutes = entity.prepTimeMinutes,
            calories        = entity.calories,
            protein         = entity.protein,
            carbs           = entity.carbs,
            fat             = entity.fat
        )
        _state.update { it.copy(
            recipe = recipe,
            editableIngredients = recipe.effectiveIngredients(),
            error = null,
            addedToDiary = false
        )}
    }
}
