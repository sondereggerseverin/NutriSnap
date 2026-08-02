package ch.nutrisnap.app.ui.screens.recipes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeScrapeResult
import ch.nutrisnap.app.data.repository.RecipeBudgetScaleResult
import ch.nutrisnap.app.data.repository.RecipeBudgetScaler
import ch.nutrisnap.app.data.repository.RecipeRepository
import ch.nutrisnap.app.domain.RecipeAiParser
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import ch.nutrisnap.app.domain.GroqVisionService
import ch.nutrisnap.app.domain.RecipeGermanMetricConverter
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.theme.KEY_AUTO_GERMAN_METRIC
import kotlinx.coroutines.flow.first
import android.graphics.Bitmap
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
    val sort:             RecipeSort   = RecipeSort.NEWEST,
    val isImporting:      Boolean      = false,
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

class RecipesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RecipeRepository(NutriDatabase.getInstance(app), app)
    private val budgetScaler = RecipeBudgetScaler(NutriDatabase.getInstance(app))

    init {
        // Feature 2: globales Zutaten-Wörterbuch für RecipeNutritionAnalyzer aktivieren
        // (idempotent, auch von FoodScanViewModel aus aufgerufen).
        ch.nutrisnap.app.domain.RecipeNutritionAnalyzer.initGlobalDictionary(
            ch.nutrisnap.app.data.repository.GlobalIngredientDictionary(NutriDatabase.getInstance(app).globalIngredientMatchDao())
        )
    }

    private val _budgetScaleState = MutableStateFlow(BudgetScaleState())
    val budgetScaleState: StateFlow<BudgetScaleState> = _budgetScaleState.asStateFlow()

    private val _isTranslating = MutableStateFlow(false)

    /** Feature 1: Rezeptportion auf das heutige Kalorien-Restbudget skalieren. */
    fun scaleToRemainingBudget(recipe: Recipe) {
        viewModelScope.launch {
            _budgetScaleState.value = BudgetScaleState(isLoading = true)
            runCatching { budgetScaler.scaleToRemainingBudget(recipe) }
                .onSuccess { r ->
                    _budgetScaleState.value = if (r != null) BudgetScaleState(result = r)
                        else BudgetScaleState(error = "Für dieses Rezept sind keine Kalorienangaben hinterlegt — erst \"Analysieren\" ausführen.")
                }
                .onFailure { e -> _budgetScaleState.value = BudgetScaleState(error = e.message ?: "Unbekannter Fehler") }
        }
    }

    fun clearBudgetScale() { _budgetScaleState.value = BudgetScaleState() }

    /** Übersetzt Zutaten + Zubereitung ins Deutsche und rechnet auf metrische Einheiten um. */
    fun translateToGermanMetric(recipe: Recipe) {
        if (_isTranslating.value) return
        viewModelScope.launch {
            _isTranslating.value = true
            runCatching {
                val converted = RecipeGermanMetricConverter.convertWithAi(recipe).getOrThrow()
                val updated = recipe.copy(
                    title = converted.title.ifBlank { recipe.title },
                    ingredients = converted.ingredients.ifBlank { recipe.ingredients },
                    instructions = converted.instructions.ifBlank { recipe.instructions }
                )
                repo.updateRecipe(updated)
            }
            _isTranslating.value = false
        }
    }

    private val _query          = MutableStateFlow("")
    private val _platformFilter = MutableStateFlow<String?>(null)
    private val _sort           = MutableStateFlow(RecipeSort.NEWEST)
    private val _importState    = MutableStateFlow(ImportState())
    private val _nutritionState = MutableStateFlow(NutritionState())
    private val _batchState     = MutableStateFlow(BatchImportState())
    val batchState: StateFlow<BatchImportState> = _batchState.asStateFlow()

    // Manuelle Zutaten-Anpassungen aus dem Verifizieren-Sheet, pro Rezept-ID.
    // Überleben Schließen des Sheets UND "Neu berechnen" (frische AnalysisResult).
    private val _ingredientOverrides = MutableStateFlow<Map<Long, Map<String, IngredientOverride>>>(emptyMap())
    fun getOverridesFor(recipeId: Long): Map<String, IngredientOverride> = _ingredientOverrides.value[recipeId] ?: emptyMap()
    fun setOverridesFor(recipeId: Long, overrides: Map<String, IngredientOverride>) {
        _ingredientOverrides.update { it + (recipeId to overrides) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<RecipesUiState> = combine(
        _query.flatMapLatest { q ->
            if (q.isBlank()) repo.getAll() else repo.search(q)
        },
        _query,
        _platformFilter,
        _sort,
        _importState,
        _nutritionState,
        _isTranslating
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val recipes        = values[0] as List<Recipe>
        val q              = values[1] as String
        val platformFilter = values[2] as String?
        val sort           = values[3] as RecipeSort
        val imp            = values[4] as ImportState
        val nut            = values[5] as NutritionState
        val translating    = values[6] as Boolean

        val filtered = if (platformFilter == null) recipes
            else recipes.filter { (it.platform ?: "web").lowercase() == platformFilter }
        val sorted = when (sort) {
            RecipeSort.NEWEST   -> filtered.sortedByDescending { it.savedAt }
            RecipeSort.NAME     -> filtered.sortedBy { it.title.lowercase() }
            RecipeSort.CALORIES -> filtered.sortedByDescending { it.totalCalories ?: -1f }
        }

        RecipesUiState(
            recipes          = sorted,
            query            = q,
            platformFilter   = platformFilter,
            sort             = sort,
            isImporting      = imp.isImporting,
            importError      = imp.importError,
            lastImport       = imp.lastImport,
            instagramBlocked = imp.instagramBlocked,
            blockedUrl       = imp.blockedUrl,
            nutritionState   = nut,
            isTranslating    = translating
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipesUiState())

    fun setQuery(q: String) { _query.value = q }
    fun setPlatformFilter(p: String?) { _platformFilter.value = p }
    fun setSort(s: RecipeSort) { _sort.value = s }
    fun clearError()        { _importState.update { it.copy(importError = null) } }
    fun clearLastImport()   { _importState.update { it.copy(lastImport = null) } }
    fun clearInstagramBlocked() { _importState.update { it.copy(instagramBlocked = false, blockedUrl = "") } }
    fun clearNutrition()    { _nutritionState.value = NutritionState() }

    fun importFromUrl(url: String) {
        viewModelScope.launch {
            _importState.update { it.copy(isImporting = true, importError = null, instagramBlocked = false) }
            val result: RecipeScrapeResult = repo.importFromUrl(url)
            if (result.success && result.recipe != null && shouldAutoGermanMetric()) {
                val r = result.recipe
                val converted = RecipeGermanMetricConverter.convertWithAi(r).getOrNull()
                if (converted != null) {
                    val updated = r.copy(
                        title = converted.title.ifBlank { r.title },
                        ingredients = converted.ingredients.ifBlank { r.ingredients },
                        instructions = converted.instructions.ifBlank { r.instructions }
                    )
                    repo.updateRecipe(updated)
                    _importState.update { it.copy(isImporting = false, lastImport = updated) }
                    return@launch
                }
            }
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

    /**
     * Importiert ein Rezept aus einem Foto/Screenshot (Rezeptkarte, Blog-Screenshot, etc.).
     * Nutzt Vision-KI, speichert direkt als Rezept mit platform = "bild".
     */
    fun importFromImage(bitmap: Bitmap) {
        viewModelScope.launch {
            _importState.update { it.copy(isImporting = true, importError = null, instagramBlocked = false) }
            try {
                val vision = GroqVisionService()
                val base64 = vision.bitmapToBase64Jpeg(bitmap, quality = 80)
                val extracted = vision.extractRecipeFromImage(base64).getOrElse { e ->
                    _importState.update {
                        it.copy(isImporting = false, importError = e.message ?: "Bild konnte nicht gelesen werden")
                    }
                    return@launch
                }
                if (extracted.title.isBlank() && extracted.ingredients.isBlank()) {
                    _importState.update {
                        it.copy(isImporting = false, importError = "Kein Rezept im Bild erkannt")
                    }
                    return@launch
                }
                val totalMin = listOfNotNull(extracted.prepTimeMinutes, extracted.cookTimeMinutes)
                    .takeIf { it.isNotEmpty() }?.sum()
                val recipe = Recipe(
                    title = extracted.title.ifBlank { "Rezept aus Bild" },
                    description = extracted.description,
                    platform = "bild",
                    ingredients = extracted.ingredients,
                    instructions = extracted.instructions,
                    servings = extracted.servings.coerceAtLeast(1),
                    prepTimeMinutes = totalMin ?: extracted.prepTimeMinutes,
                    totalCalories = extracted.caloriesPerServing?.let { it * extracted.servings.coerceAtLeast(1) },
                    proteinPerServing = extracted.proteinPerServing,
                    carbsPerServing = extracted.carbsPerServing,
                    fatPerServing = extracted.fatPerServing,
                    tags = "bild"
                )
                var saved = recipe.copy(id = repo.saveRecipe(recipe))
                if (shouldAutoGermanMetric()) {
                    val converted = RecipeGermanMetricConverter.convertWithAi(saved).getOrNull()
                    if (converted != null) {
                        saved = saved.copy(
                            title = converted.title.ifBlank { saved.title },
                            ingredients = converted.ingredients.ifBlank { saved.ingredients },
                            instructions = converted.instructions.ifBlank { saved.instructions }
                        )
                        repo.updateRecipe(saved)
                    }
                }
                _importState.update { it.copy(isImporting = false, lastImport = saved) }
                // Auto-Nährwerte aus Zutaten berechnen
                analyzeNutrition(saved)
            } catch (e: Exception) {
                _importState.update {
                    it.copy(isImporting = false, importError = e.message ?: "Fehler beim Bild-Import")
                }
            }
        }
    }

    private suspend fun shouldAutoGermanMetric(): Boolean {
        return runCatching {
            val prefs = getApplication<Application>().notifDataStore.data.first()
            prefs[KEY_AUTO_GERMAN_METRIC] == true
        }.getOrDefault(false)
    }

    /** Fügt neue URLs zur Batch-Queue hinzu (Duplikate werden ignoriert). */
    fun addBatchUrls(urls: List<String>) {
        val existing = _batchState.value.items.map { it.url }.toSet()
        val newItems = urls.distinct().filter { it !in existing }.map { BatchImportItem(url = it) }
        if (newItems.isNotEmpty()) _batchState.update { it.copy(items = it.items + newItems) }
    }

    fun removeBatchItem(url: String) {
        _batchState.update { it.copy(items = it.items.filterNot { i -> i.url == url }) }
    }

    fun clearBatch() { _batchState.value = BatchImportState() }

    /** Importiert alle noch offenen Items sequenziell (schont Insta/TikTok-Endpunkte, vermeidet Rate-Limits). */
    fun runBatchImport() {
        if (_batchState.value.isRunning) return
        viewModelScope.launch {
            _batchState.update { it.copy(isRunning = true) }
            val queue = _batchState.value.items.filter { it.status != BatchStatus.DONE }
            for (item in queue) {
                _batchState.update { st ->
                    st.copy(items = st.items.map { if (it.url == item.url) it.copy(status = BatchStatus.RUNNING) else it })
                }
                val result = repo.importFromUrl(item.url)
                _batchState.update { st ->
                    st.copy(items = st.items.map {
                        if (it.url != item.url) it
                        else when {
                            result.success            -> it.copy(status = BatchStatus.DONE, resultTitle = result.recipe?.title)
                            result.instagramBlocked    -> it.copy(status = BatchStatus.ERROR, error = "Instagram blockiert – manuell einfügen nötig")
                            else                       -> it.copy(status = BatchStatus.ERROR, error = result.error ?: "Fehler")
                        }
                    })
                }
            }
            _batchState.update { it.copy(isRunning = false) }
        }
    }

    fun deleteRecipe(recipe: Recipe) {
        viewModelScope.launch { repo.deleteRecipe(recipe) }
    }

    /**
     * "Auswahl übernehmen" — summiert die bereits gematchten/manuell angepassten
     * Zutaten (letztes AnalysisResult + gespeicherte Overrides) neu, OHNE erneut
     * bei OpenFoodFacts/USDA/Groq zu suchen. Gegenstück zu [analyzeNutrition],
     * das immer komplett neu von der Zutatenliste aus sucht.
     */
    fun recalculateFromOverrides(recipe: Recipe) {
        val result = _nutritionState.value.result.takeIf { _nutritionState.value.recipeId == recipe.id } ?: return
        val overrides = getOverridesFor(recipe.id)
        val states = mergeIngredientOverrides(result.ingredients, overrides)
        val totals = computeVerifiedTotals(states)
        val servDiv = recipe.servings.coerceAtLeast(1)
        applyVerifiedNutrition(
            recipe,
            totals.kcal / servDiv, totals.protein / servDiv, totals.carbs / servDiv, totals.fat / servDiv,
            totals.fiber?.div(servDiv), totals.sugar?.div(servDiv), totals.saturatedFat?.div(servDiv),
            totals.salt?.div(servDiv), totals.sodium?.div(servDiv)
        )
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
                val servDiv = recipe.servings.coerceAtLeast(1)
                val updated = recipe.copy(
                    totalCalories     = analysis.totalCalories,
                    proteinPerServing = analysis.proteinPerServing,
                    carbsPerServing   = analysis.carbsPerServing,
                    fatPerServing     = analysis.fatPerServing,
                    // Ballaststoffe & Co. wurden bisher berechnet aber nie persistiert,
                    // daher liefen Tagebuch-Summe/Home-Übersicht immer auf 0 zurück.
                    // Bei unvollständigen Zutaten-Daten wird die (ggf. unvollständige)
                    // Summe trotzdem gespeichert statt verworfen — die Karte zeigt in
                    // dem Fall zusätzlich einen Hinweis (siehe fiberComplete).
                    fiberPerServing        = analysis.totalMicros["fiber"]?.div(servDiv) ?: recipe.fiberPerServing,
                    sugarPerServing        = analysis.totalMicros["sugar"]?.div(servDiv) ?: recipe.sugarPerServing,
                    saturatedFatPerServing = analysis.totalMicros["saturatedFat"]?.div(servDiv) ?: recipe.saturatedFatPerServing,
                    saltPerServing         = analysis.totalMicros["salt"]?.div(servDiv) ?: recipe.saltPerServing,
                    sodiumPerServing       = analysis.totalMicros["sodium"]?.div(servDiv) ?: recipe.sodiumPerServing,
                    description       = newDesc
                )
                repo.updateRecipe(updated)
                _nutritionState.value = NutritionState(result = analysis, recipeId = recipe.id)
            }.onFailure { e ->
                _nutritionState.value = NutritionState(error = e.message, recipeId = recipe.id)
            }
        }
    }

    fun applyVerifiedNutrition(
        recipe: Recipe,
        kcalPerServ: Float,
        protPerServ: Float,
        carbsPerServ: Float,
        fatPerServ: Float,
        fiberPerServ: Float? = null,
        sugarPerServ: Float? = null,
        satFatPerServ: Float? = null,
        saltPerServ: Float? = null,
        sodiumPerServ: Float? = null
    ) {
        viewModelScope.launch {
            val macroLine = "📊 Pro Portion: ${kcalPerServ.toInt()} kcal" +
                " · ${protPerServ.toInt()}g Protein" +
                " · ${carbsPerServ.toInt()}g Kohlenhydrate" +
                " · ${fatPerServ.toInt()}g Fett (verifiziert)"
            val baseDesc = recipe.description.lines()
                .filterNot { it.startsWith("📊") }.joinToString("\n").trim()
            val newDesc = if (baseDesc.isNotBlank()) "$baseDesc\n\n$macroLine" else macroLine
            val updated = recipe.copy(
                totalCalories     = kcalPerServ * recipe.servings,
                proteinPerServing = protPerServ,
                carbsPerServing   = carbsPerServ,
                fatPerServing     = fatPerServ,
                // Bisher wurden diese Werte hier verworfen, obwohl die Verifizierungs-
                // sheet sie bereits pro Zutat berechnet — dadurch fiel Ballaststoffe
                // nach dem Verifizieren auf den (oft leeren) analyzeNutrition-Stand zurück.
                fiberPerServing        = fiberPerServ  ?: recipe.fiberPerServing,
                sugarPerServing        = sugarPerServ  ?: recipe.sugarPerServing,
                saturatedFatPerServing = satFatPerServ ?: recipe.saturatedFatPerServing,
                saltPerServing         = saltPerServ   ?: recipe.saltPerServing,
                sodiumPerServing       = sodiumPerServ ?: recipe.sodiumPerServing,
                description       = newDesc
            )
            repo.updateRecipe(updated)
            // Clear so IngredientVerifySheet re-initialises fresh if reopened
            _nutritionState.value = NutritionState()
        }
    }

    /**
     * Importiert ein von Claude (Chat) geteiltes Rezept-JSON direkt, ohne Zwischenschritt.
     * Wird still ignoriert, falls der Text kein erkennbares Rezept-JSON ist.
     */
    fun importFromSharedJson(json: String) {
        val recipe = ch.nutrisnap.app.domain.RecipeJsonImport.tryParse(json) ?: return
        viewModelScope.launch {
            val saved = recipe.copy(id = repo.saveRecipe(recipe))
            _importState.update { it.copy(lastImport = saved) }
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
