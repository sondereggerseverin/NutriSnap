package ch.nutrisnap.app.ui.screens.recipegen

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.GeneratedRecipeEntity
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.repository.RecipeRepository
import ch.nutrisnap.app.data.repository.UserProfileRepository
import ch.nutrisnap.app.domain.CookingMethod
import ch.nutrisnap.app.domain.DayPlan
import ch.nutrisnap.app.domain.GeneratedRecipe
import ch.nutrisnap.app.domain.GroqRecipeGeneratorService
import ch.nutrisnap.app.domain.GroqVisionService
import ch.nutrisnap.app.domain.PlannedMeal
import ch.nutrisnap.app.domain.RecipeIngredient
import ch.nutrisnap.app.domain.WorkoutTiming
import ch.nutrisnap.app.domain.ZenMuxImageService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import java.time.LocalDate

/** Wie das aktuell angezeigte Rezept erzeugt wurde – steuert Tab-Auswahl in der UI. */
enum class RecipeGenMode { FREITEXT, ZUTATEN, FILL_UP, ZUFALL, TAGESPLAN }

data class FillUpBudget(
    val calories: Float = 0f,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f
)

data class RecipeGenUiState(
    val mode: RecipeGenMode = RecipeGenMode.FREITEXT,
    val isLoading: Boolean = false,
    val recipe: GeneratedRecipe? = null,
    /** id of the generated_recipes row currently shown, if opened from/saved to history */
    val openHistoryId: Int? = null,
    val error: String? = null,
    val savedToDiary: Boolean = false,
    val savedAsRecipe: Boolean = false,
    val isGeneratingImage: Boolean = false,
    val history: List<GeneratedRecipeEntity> = emptyList(),
    // Zutaten-Modus
    val ingredientChips: List<String> = emptyList(),
    val isScanningFridge: Boolean = false,
    val showFridgeCamera: Boolean = false,
    // Fill-Up-Modus
    val fillUpBudget: FillUpBudget = FillUpBudget(),
    // Tagesplan-Modus
    val dayPlanTargetCalories: String = "",
    val dayPlanTargetProtein: String = "",
    val dayPlanTargetFiber: String = "25",
    val dayPlanIncludeBreakfast: Boolean = true,
    val dayPlanMealCount: Int = 3,
    val dayPlanHighVolume: Boolean = false,
    val dayPlanWorkoutTiming: WorkoutTiming = WorkoutTiming.NONE,
    val dayPlanMustUseIngredients: String = "",
    val dayPlanExtraNotes: String = "",
    val dayPlan: DayPlan? = null,
    val isDayPlanLoading: Boolean = false,
    val dayPlanError: String? = null,
    val dayPlanSavedMealIndices: Set<Int> = emptySet(),
    val dayPlanAllSaved: Boolean = false,
    // Kochgerät (gilt für alle Modi + Tagesplan)
    val cookingMethod: CookingMethod = CookingMethod.STOVETOP,
    val applianceModel: String = "",
    val isAdaptingMethod: Boolean = false
)

class RecipeGeneratorViewModel(app: Application) : AndroidViewModel(app) {
    private val service      = GroqRecipeGeneratorService()
    private val visionService = GroqVisionService()
    private val imageService = ZenMuxImageService(app)
    private val db           = NutriDatabase.getInstance(app)
    private val dao         = db.generatedRecipeDao()
    private val diaryDao    = db.diaryDao()
    private val recipeRepo  = RecipeRepository(db, app)
    private val diaryRepo   = DiaryRepository(db)
    private val profileRepo = UserProfileRepository(db)
    private val json        = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(RecipeGenUiState())
    val state: StateFlow<RecipeGenUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            dao.getHistory().collect { history ->
                _state.update { it.copy(history = history) }
            }
        }
        // Geräteprofil aus den Settings laden, damit Ofen-/Dampfgarer-Rezepte aufs echte Gerät passen.
        viewModelScope.launch {
            profileRepo.get().collect { profile ->
                _state.update { it.copy(applianceModel = profile.applianceModel) }
            }
        }
        // Hält das Fill-Up-Budget (übrige Tages-Makros) live aktuell.
        viewModelScope.launch {
            combine(
                diaryRepo.getEntriesForDate(LocalDate.now()),
                profileRepo.get()
            ) { entries, profile ->
                val eaten = FillUpBudget(
                    calories = entries.sumOf { it.calories.toDouble() }.toFloat(),
                    protein  = entries.sumOf { it.protein.toDouble() }.toFloat(),
                    carbs    = entries.sumOf { it.carbs.toDouble() }.toFloat(),
                    fat      = entries.sumOf { it.fat.toDouble() }.toFloat()
                )
                FillUpBudget(
                    calories = (profile.dailyCalorieGoal - eaten.calories).coerceAtLeast(0f),
                    protein  = (profile.proteinGoalG - eaten.protein).coerceAtLeast(0f),
                    carbs    = (profile.carbsGoalG - eaten.carbs).coerceAtLeast(0f),
                    fat      = (profile.fatGoalG - eaten.fat).coerceAtLeast(0f)
                )
            }.collect { budget ->
                _state.update { it.copy(fillUpBudget = budget) }
            }
        }
    }

    fun setMode(mode: RecipeGenMode) = _state.update { it.copy(mode = mode) }

    fun setCookingMethod(method: CookingMethod) = _state.update { it.copy(cookingMethod = method) }

    /** Passt die Zubereitung des aktuell offenen Rezepts an ein anderes Kochgerät an (Zutaten/Makros bleiben fix). */
    fun adaptCurrentRecipeToMethod(method: CookingMethod) {
        val current = _state.value.recipe ?: return
        val applianceModel = _state.value.applianceModel
        viewModelScope.launch {
            _state.update { it.copy(isAdaptingMethod = true, error = null) }
            service.adaptRecipeMethod(current, method, applianceModel).fold(
                onSuccess = { adapted -> updateRecipe(adapted); _state.update { it.copy(isAdaptingMethod = false, cookingMethod = method) } },
                onFailure = { e -> _state.update { it.copy(isAdaptingMethod = false, error = e.message ?: "Anpassung fehlgeschlagen") } }
            )
        }
    }

    fun generate(userInput: String) {
        if (userInput.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, recipe = null, openHistoryId = null, savedToDiary = false, savedAsRecipe = false) }
            val s = _state.value
            service.generateRecipe(userInput, s.cookingMethod, s.applianceModel).fold(
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

    // ── Zutaten-Modus ────────────────────────────────────────────────────────

    fun addIngredientChip(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        _state.update {
            if (it.ingredientChips.any { c -> c.equals(trimmed, ignoreCase = true) }) it
            else it.copy(ingredientChips = it.ingredientChips + trimmed)
        }
    }

    fun removeIngredientChip(name: String) {
        _state.update { it.copy(ingredientChips = it.ingredientChips.filterNot { c -> c == name }) }
    }

    fun openFridgeCamera()  = _state.update { it.copy(showFridgeCamera = true) }
    fun closeFridgeCamera() = _state.update { it.copy(showFridgeCamera = false) }

    /** Analysiert ein Kühlschrank-/Vorratsfoto und fügt erkannte Zutaten als Chips hinzu. */
    fun analyzeFridgePhoto(bitmap: Bitmap) {
        viewModelScope.launch {
            _state.update { it.copy(isScanningFridge = true, showFridgeCamera = false, error = null) }
            val base64 = visionService.bitmapToBase64Jpeg(bitmap)
            visionService.analyzeFridgePhoto(base64).fold(
                onSuccess = { result ->
                    _state.update { s ->
                        val merged = (s.ingredientChips + result.ingredients)
                            .distinctBy { it.lowercase() }
                        s.copy(isScanningFridge = false, ingredientChips = merged)
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(isScanningFridge = false, error = e.message ?: "Foto konnte nicht analysiert werden") }
                }
            )
        }
    }

    fun generateFromIngredients(note: String = "") {
        val ingredients = _state.value.ingredientChips
        if (ingredients.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, recipe = null, openHistoryId = null, savedToDiary = false, savedAsRecipe = false) }
            val s = _state.value
            service.generateFromIngredients(ingredients, note, s.cookingMethod, s.applianceModel).fold(
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

    // ── Fill-Up-Modus ────────────────────────────────────────────────────────

    fun generateFillUp(mealLabel: String) {
        val budget = _state.value.fillUpBudget
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, recipe = null, openHistoryId = null, savedToDiary = false, savedAsRecipe = false) }
            val s = _state.value
            service.generateFillUp(budget.calories, budget.protein, budget.carbs, budget.fat, mealLabel, s.cookingMethod, s.applianceModel).fold(
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

    // ── Tagesplan-Modus ──────────────────────────────────────────────────────

    fun setDayPlanCalories(v: String) = _state.update { it.copy(dayPlanTargetCalories = v) }
    fun setDayPlanProtein(v: String) = _state.update { it.copy(dayPlanTargetProtein = v) }
    fun setDayPlanFiber(v: String) = _state.update { it.copy(dayPlanTargetFiber = v) }
    fun setDayPlanIncludeBreakfast(v: Boolean) = _state.update { it.copy(dayPlanIncludeBreakfast = v) }
    fun setDayPlanMealCount(v: Int) = _state.update { it.copy(dayPlanMealCount = v.coerceIn(2, 6)) }
    fun setDayPlanHighVolume(v: Boolean) = _state.update { it.copy(dayPlanHighVolume = v) }
    fun setDayPlanWorkoutTiming(v: WorkoutTiming) = _state.update { it.copy(dayPlanWorkoutTiming = v) }
    fun setDayPlanMustUseIngredients(v: String) = _state.update { it.copy(dayPlanMustUseIngredients = v) }
    fun setDayPlanExtraNotes(v: String) = _state.update { it.copy(dayPlanExtraNotes = v) }

    fun clearDayPlan() = _state.update {
        it.copy(dayPlan = null, dayPlanError = null, dayPlanSavedMealIndices = emptySet(), dayPlanAllSaved = false)
    }

    fun clearDayPlanError() = _state.update { it.copy(dayPlanError = null) }
    fun clearDayPlanAllSavedFlag() = _state.update { it.copy(dayPlanAllSaved = false) }

    fun generateDayPlan() {
        val s = _state.value
        val calories = s.dayPlanTargetCalories.toFloatOrNull()
        val protein = s.dayPlanTargetProtein.toFloatOrNull()
        if (calories == null || protein == null) {
            _state.update { it.copy(dayPlanError = "Bitte Kalorien- und Proteinziel angeben") }
            return
        }
        val fiber = s.dayPlanTargetFiber.toFloatOrNull() ?: 25f
        val ingredients = s.dayPlanMustUseIngredients.split(",").map { it.trim() }.filter { it.isNotBlank() }

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isDayPlanLoading = true, dayPlanError = null, dayPlan = null,
                    dayPlanSavedMealIndices = emptySet(), dayPlanAllSaved = false
                )
            }
            service.generateDayPlan(
                targetCalories = calories,
                targetProtein = protein,
                targetFiber = fiber,
                includeBreakfast = s.dayPlanIncludeBreakfast,
                mealCount = s.dayPlanMealCount,
                highVolume = s.dayPlanHighVolume,
                workoutTiming = s.dayPlanWorkoutTiming,
                mustUseIngredients = ingredients,
                extraNotes = s.dayPlanExtraNotes,
                cookingMethod = s.cookingMethod,
                applianceModel = s.applianceModel
            ).fold(
                onSuccess = { plan -> _state.update { it.copy(isDayPlanLoading = false, dayPlan = plan) } },
                onFailure = { e -> _state.update { it.copy(isDayPlanLoading = false, dayPlanError = e.message ?: "Unbekannter Fehler") } }
            )
        }
    }

    /** Trägt eine einzelne geplante Mahlzeit ins heutige Tagebuch ein. */
    fun addPlannedMealToDiary(meal: PlannedMeal, index: Int) {
        viewModelScope.launch {
            diaryDao.insert(
                DiaryEntry(
                    foodItemId  = 0,
                    foodName    = meal.title,
                    amountGrams = 1f,
                    mealType    = meal.mealType.toMealTypeOrDefault(),
                    dateStr     = LocalDate.now().toString(),
                    calories    = meal.calories,
                    protein     = meal.protein,
                    carbs       = meal.carbs,
                    fat         = meal.fat
                )
            )
            _state.update { it.copy(dayPlanSavedMealIndices = it.dayPlanSavedMealIndices + index) }
        }
    }

    /** Trägt alle Mahlzeiten des generierten Tagesplans auf einmal ins heutige Tagebuch ein. */
    fun addAllPlannedMealsToDiary() {
        val plan = _state.value.dayPlan ?: return
        viewModelScope.launch {
            plan.meals.forEach { meal ->
                diaryDao.insert(
                    DiaryEntry(
                        foodItemId  = 0,
                        foodName    = meal.title,
                        amountGrams = 1f,
                        mealType    = meal.mealType.toMealTypeOrDefault(),
                        dateStr     = LocalDate.now().toString(),
                        calories    = meal.calories,
                        protein     = meal.protein,
                        carbs       = meal.carbs,
                        fat         = meal.fat
                    )
                )
            }
            _state.update {
                it.copy(dayPlanSavedMealIndices = plan.meals.indices.toSet(), dayPlanAllSaved = true)
            }
        }
    }

    private fun String.toMealTypeOrDefault(): MealType =
        runCatching { MealType.valueOf(this) }.getOrDefault(MealType.LUNCH)

    // ── Zufalls-Modus ────────────────────────────────────────────────────────

    fun generateRandomRecipe() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, recipe = null, openHistoryId = null, savedToDiary = false, savedAsRecipe = false) }
            val s = _state.value
            service.generateRandom(s.cookingMethod, s.applianceModel).fold(
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

    /** Speichert das KI-Rezept dauerhaft im Rezepte-Tab (Kochbuch).
     *  Erzeugt vorher per ZenMux ein KI-Bild fürs Rezept (statt des orangen
     *  Platzhalters). Schlägt die Bildgenerierung fehl (kein API-Key, offline,
     *  API-Fehler), wird das Rezept trotzdem ganz normal ohne Bild gespeichert. */
    fun saveAsRecipe() {
        val r = _state.value.recipe ?: return
        viewModelScope.launch {
            _state.update { it.copy(isGeneratingImage = true) }
            val generatedImageUri = imageService
                .generateRecipeImage(r.title, r.description)
                .getOrNull()

            recipeRepo.saveRecipe(
                Recipe(
                    title             = r.title,
                    description       = r.description,
                    imageUrl          = generatedImageUri,
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
            _state.update { it.copy(savedAsRecipe = true, isGeneratingImage = false) }
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
