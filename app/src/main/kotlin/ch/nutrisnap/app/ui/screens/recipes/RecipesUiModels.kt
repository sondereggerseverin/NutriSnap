package ch.nutrisnap.app.ui.screens.recipes

import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeCategory
import ch.nutrisnap.app.data.repository.RecipeBudgetScaleResult
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer

internal data class ImportState(
    val isImporting:      Boolean = false,
    val importPhase:      String? = null,
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

enum class RecipeSort { NEWEST, NAME, CALORIES }

enum class BatchStatus { PENDING, RUNNING, DONE, ERROR }

data class BatchImportItem(
    val url:         String,
    val status:      BatchStatus = BatchStatus.PENDING,
    val resultTitle: String?     = null,
    val error:       String?     = null
)

data class BatchImportState(
    val items:     List<BatchImportItem> = emptyList(),
    val isRunning: Boolean = false
) {
    val doneCount: Int get() = items.count { it.status == BatchStatus.DONE }
}

data class RecipesUiState(
    val recipes:          List<Recipe> = emptyList(),
    val query:            String       = "",
    val platformFilter:   String?      = null,   // null = alle
    val categoryFilter:   RecipeCategory? = null, // null = alle Kategorien
    val ingredientNeedles: List<String> = emptyList(), // alle müssen vorkommen
    val sort:             RecipeSort   = RecipeSort.NEWEST,
    val isImporting:      Boolean      = false,
    val importPhase:      String?      = null,
    val importError:      String?      = null,
    val lastImport:       Recipe?      = null,
    val instagramBlocked: Boolean      = false,
    val blockedUrl:       String       = "",
    val nutritionState:   NutritionState = NutritionState(),
    val isTranslating:    Boolean      = false
)

data class BudgetScaleState(
    val isLoading: Boolean = false,
    val result: RecipeBudgetScaleResult? = null,
    val error: String? = null
)

