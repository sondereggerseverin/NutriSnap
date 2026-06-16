package ch.nutrisnap.app.ui.screens.recipes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeScrapeResult
import ch.nutrisnap.app.data.repository.RecipeRepository
import ch.nutrisnap.app.domain.RecipeAiParser
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private data class ImportState(
    val isImporting:      Boolean = false,
    val importError:      String? = null,
    val lastImport:       Recipe? = null,
    val instagramBlocked: Boolean = false,
    val blockedUrl:       String  = ""
)

data class NutritionState(
    val isAnalyzing: Boolean = false,
    val result: RecipeNutritionAnalyzer.AnalysisResult? = null,
    val error: String? = null,
    val recipeId: Long = -1L
)

data class RecipesUiState(
    val recipes:          List<Recipe> = emptyList(),
    val query:            String       = "",
    val isImporting:      Boolean      = false,
    val importError:      String?      = null,
    val lastImport:       Recipe?      = null,
    val instagramBlocked: Boolean      = false,
    val blockedUrl:       String       = "",
    val nutritionState:   NutritionState = NutritionState()
)

class RecipesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RecipeRepository(NutriDatabase.getInstance(app), app)

    private val _query         = MutableStateFlow("")
    private val _importState   = MutableStateFlow(ImportState())
    private val _nutritionState = MutableStateFlow(NutritionState())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<RecipesUiState> = combine(
        _query.flatMapLatest { q ->
            if (q.isBlank()) repo.getAll() else repo.search(q)
        },
        _query,
        _importState,
        _nutritionState
    ) { recipes, q, imp, nut ->
        RecipesUiState(
            recipes          = recipes,
            query            = q,
            isImporting      = imp.isImporting,
            importError      = imp.importError,
            lastImport       = imp.lastImport,
            instagramBlocked = imp.instagramBlocked,
            blockedUrl       = imp.blockedUrl,
            nutritionState   = nut
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipesUiState())

    fun setQuery(q: String) { _query.value = q }
    fun clearError()        { _importState.update { it.copy(importError = null) } }
    fun clearLastImport()   { _importState.update { it.copy(lastImport = null) } }
    fun clearInstagramBlocked() { _importState.update { it.copy(instagramBlocked = false, blockedUrl = "") } }
    fun clearNutrition()    { _nutritionState.value = NutritionState() }

    fun importFromUrl(url: String) {
        viewModelScope.launch {
            _importState.update { it.copy(isImporting = true, importError = null, instagramBlocked = false) }
            val result: RecipeScrapeResult = repo.importFromUrl(url)
            _importState.update { state ->
                when {
                    result.instagramBlocked ->
                        state.copy(isImporting = false, instagramBlocked = true, blockedUrl = url)
                    result.success ->
                        state.copy(isImporting = false, lastImport = result.recipe)
                    else ->
                        state.copy(isImporting = false, importError = result.error ?: "Fehler beim Importieren")
                }
            }
        }
    }

    fun deleteRecipe(recipe: Recipe) {
        viewModelScope.launch { repo.deleteRecipe(recipe) }
    }

    fun updateRecipe(recipe: Recipe) {
        viewModelScope.launch { repo.updateRecipe(recipe) }
    }

    /** Analyze recipe ingredients via OpenFoodFacts and update macros in DB */
    fun analyzeNutrition(recipe: Recipe) {
        viewModelScope.launch {
            _nutritionState.value = NutritionState(isAnalyzing = true, recipeId = recipe.id)
            val result = runCatching { RecipeNutritionAnalyzer.analyze(recipe) }
            result.onSuccess { analysis ->
                // Update recipe in DB with calculated macros
                val macroLine = "📊 Pro Portion: ${analysis.caloriesPerServing.toInt()} kcal" +
                    " · ${analysis.proteinPerServing.toInt()}g Protein" +
                    " · ${analysis.carbsPerServing.toInt()}g Kohlenhydrate" +
                    " · ${analysis.fatPerServing.toInt()}g Fett"
                val baseDesc = recipe.description.lines()
                    .filterNot { it.startsWith("📊") }.joinToString("\n").trim()
                val newDesc = if (baseDesc.isNotBlank()) "$baseDesc\n\n$macroLine" else macroLine
                val updated = recipe.copy(
                    totalCalories     = analysis.totalCalories,
                    proteinPerServing = analysis.proteinPerServing,
                    carbsPerServing   = analysis.carbsPerServing,
                    fatPerServing     = analysis.fatPerServing,
                    description       = newDesc
                )
                repo.updateRecipe(updated)
                _nutritionState.value = NutritionState(result = analysis, recipeId = recipe.id)
            }.onFailure { e ->
                _nutritionState.value = NutritionState(error = e.message, recipeId = recipe.id)
            }
        }
    }

    fun saveManualRecipe(url: String, title: String?, caption: String) {
        viewModelScope.launch {
            val cleaned = RecipeAiParser.cleanCaption(caption)
            val (ingredients, instructions) = parseCaption(cleaned)
            val recipe = Recipe(
                title        = title?.ifBlank { null } ?: RecipeAiParser.extractTitle(caption, "Instagram Rezept"),
                description  = cleaned.take(500),
                sourceUrl    = url.ifBlank { null },
                platform     = when {
                    "instagram.com" in url || "instagr.am" in url -> "instagram"
                    "tiktok.com" in url -> "tiktok"
                    else -> "web"
                },
                ingredients  = ingredients.ifBlank { cleaned },
                instructions = instructions,
                tags         = "manuell"
            )
            val saved = recipe.copy(id = repo.saveRecipe(recipe))
            _importState.update { it.copy(lastImport = saved) }
        }
    }

    private fun parseCaption(caption: String): Pair<String, String> {
        val lower = caption.lowercase()
        val instrKw = listOf("zubereitung","anleitung","so geht","preparation","method","instructions","steps","how to","zubereiten:")
        val ingrKw  = listOf("zutaten","zutaten:","ingredients","du brauchst","das brauchst","you need","für das rezept")
        val instrIdx = instrKw.firstNotNullOfOrNull { kw -> lower.indexOf(kw).takeIf { it > 5 } }
        val ingrIdx  = ingrKw.firstNotNullOfOrNull  { kw -> lower.indexOf(kw).takeIf { it >= 0 } }
        return when {
            ingrIdx != null && instrIdx != null && instrIdx > ingrIdx ->
                caption.substring(ingrIdx, instrIdx).trim() to caption.substring(instrIdx).trim()
            instrIdx != null -> caption.substring(0, instrIdx).trim() to caption.substring(instrIdx).trim()
            ingrIdx != null  -> caption.substring(ingrIdx).trim() to ""
            else             -> caption to ""
        }
    }
}
