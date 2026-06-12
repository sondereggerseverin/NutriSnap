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

// Separate state holder to keep combine() under 6 flows
private data class ImportState(
    val isImporting:      Boolean = false,
    val importError:      String? = null,
    val lastImport:       Recipe? = null,
    val instagramBlocked: Boolean = false,
    val blockedUrl:       String  = ""
)

data class RecipesUiState(
    val recipes:          List<Recipe> = emptyList(),
    val query:            String       = "",
    val isImporting:      Boolean      = false,
    val importError:      String?      = null,
    val lastImport:       Recipe?      = null,
    val instagramBlocked: Boolean      = false,
    val blockedUrl:       String       = ""
)

class RecipesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RecipeRepository(NutriDatabase.getInstance(app), app)

    private val _query       = MutableStateFlow("")
    private val _importState = MutableStateFlow(ImportState())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<RecipesUiState> = combine(
        _query.flatMapLatest { q ->
            if (q.isBlank()) repo.getAll() else repo.search(q)
        },
        _query,
        _importState
    ) { recipes, q, imp ->
        RecipesUiState(
            recipes          = recipes,
            query            = q,
            isImporting      = imp.isImporting,
            importError      = imp.importError,
            lastImport       = imp.lastImport,
            instagramBlocked = imp.instagramBlocked,
            blockedUrl       = imp.blockedUrl
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipesUiState())

    fun setQuery(q: String) { _query.value = q }

    fun clearError()        { _importState.update { it.copy(importError = null) } }
    fun clearLastImport()   { _importState.update { it.copy(lastImport = null) } }
    fun clearInstagramBlocked() {
        _importState.update { it.copy(instagramBlocked = false, blockedUrl = "") }
    }

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

    fun saveManualRecipe(url: String, title: String?, caption: String) {
        viewModelScope.launch {
            val (ingredients, instructions) = parseCaption(caption)
            val recipe = Recipe(
                title        = title?.ifBlank { null } ?: buildTitleFromCaption(caption),
                description  = caption.take(500),
                sourceUrl    = url.ifBlank { null },
                platform     = when {
                    "instagram.com" in url || "instagr.am" in url -> "instagram"
                    "tiktok.com" in url -> "tiktok"
                    else -> "web"
                },
                ingredients  = ingredients.ifBlank { caption },
                instructions = instructions,
                tags         = "manuell"
            )
            val saved = recipe.copy(id = repo.saveRecipe(recipe))
            _importState.update { it.copy(lastImport = saved) }
        }
    }

    private fun buildTitleFromCaption(caption: String) =
        caption.lines()
            .firstOrNull { it.trim().length > 4 && it.any { c -> c.isLetter() } }
            ?.trim()?.take(60) ?: "Instagram Rezept"

    private fun parseCaption(caption: String): Pair<String, String> {
        val lower = caption.lowercase()
        val instrKw = listOf("zubereitung", "anleitung", "so geht", "preparation",
            "method", "instructions", "steps", "how to", "zubereiten:")
        val ingrKw  = listOf("zutaten", "zutaten:", "ingredients", "du brauchst",
            "das brauchst", "you need", "für das rezept")
        val instrIdx = instrKw.firstNotNullOfOrNull { kw -> lower.indexOf(kw).takeIf { it > 5 } }
        val ingrIdx  = ingrKw.firstNotNullOfOrNull  { kw -> lower.indexOf(kw).takeIf { it >= 0 } }
        return when {
            ingrIdx != null && instrIdx != null && instrIdx > ingrIdx ->
                caption.substring(ingrIdx, instrIdx).trim() to caption.substring(instrIdx).trim()
            instrIdx != null ->
                caption.substring(0, instrIdx).trim() to caption.substring(instrIdx).trim()
            ingrIdx != null  -> caption.substring(ingrIdx).trim() to ""
            else             -> caption to ""
        }
    }
}
