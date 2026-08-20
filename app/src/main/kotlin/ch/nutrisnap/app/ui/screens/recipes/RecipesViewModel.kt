package ch.nutrisnap.app.ui.screens.recipes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeCategory
import ch.nutrisnap.app.data.model.RecipeComponent
import ch.nutrisnap.app.data.model.RecipeScrapeResult
import ch.nutrisnap.app.data.repository.RecipeBudgetScaleResult
import ch.nutrisnap.app.data.repository.RecipeBudgetScaler
import ch.nutrisnap.app.data.repository.RecipeRepository
import ch.nutrisnap.app.domain.RecipeAiParser
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import ch.nutrisnap.app.domain.GroqVisionService
import ch.nutrisnap.app.domain.RecipeGermanMetricConverter
import ch.nutrisnap.app.domain.RecipeComponentSuggester
import ch.nutrisnap.app.domain.RecipeListFilter
import ch.nutrisnap.app.domain.IngredientMatchSync
import ch.nutrisnap.app.domain.RecipeVerifiedNutrition
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.theme.KEY_AUTO_GERMAN_METRIC
import kotlinx.coroutines.flow.first
import android.graphics.Bitmap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class RecipesViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NutriDatabase.getInstance(app)
    private val repo = RecipeRepository(db, app)
    private val matchDao = db.ingredientMatchDao()
    private val budgetScaler = RecipeBudgetScaler(db)

    init {
        // Feature 2: globales Zutaten-Wörterbuch für RecipeNutritionAnalyzer aktivieren
        // (idempotent, auch von FoodScanViewModel aus aufgerufen).
        ch.nutrisnap.app.domain.RecipeNutritionAnalyzer.initGlobalDictionary(
            ch.nutrisnap.app.data.repository.GlobalIngredientDictionary(NutriDatabase.getInstance(app).globalIngredientMatchDao())
        )
        // Einmalig Duplikate bereinigen (Sync-Pull legte früher Rezepte ohne Fingerprint
        // mehrfach an — z.B. 10× derselbe Pfannkuchen).
        viewModelScope.launch {
            runCatching {
                val n = repo.deduplicateRecipes()
                if (n > 0) android.util.Log.i("Recipes", "Rezept-Dedup: $n Duplikate entfernt")
            }
            runCatching {
                val n = repo.repairNullTitleArtifacts()
                if (n > 0) android.util.Log.i("Recipes", "Null-Titel bereinigt: $n Rezepte")
            }
            runCatching {
                var n = 0
                for (r in repo.getAllOnce()) {
                    val guessed = ch.nutrisnap.app.data.model.RecipeCategory.guess(
                        r.title, r.ingredients, r.description
                    )
                    // Leer ODER fälschlich Dessert bei herzhaftem Gericht neu setzen
                    val needs =
                        r.mealCategory.isBlank() ||
                        (r.category() == ch.nutrisnap.app.data.model.RecipeCategory.DESSERT &&
                            guessed == ch.nutrisnap.app.data.model.RecipeCategory.MAIN)
                    if (needs && guessed.name != r.mealCategory) {
                        repo.updateRecipe(r.copy(mealCategory = guessed.name))
                        n++
                    }
                }
                if (n > 0) android.util.Log.i("Recipes", "Kategorien korrigiert: $n Rezepte")
            }
        }
    }

    private val _budgetScaleState = MutableStateFlow(BudgetScaleState())
    val budgetScaleState: StateFlow<BudgetScaleState> = _budgetScaleState.asStateFlow()

    /** Optionales kcal-Ziel aus „Was koche ich?“ — wird beim Öffnen eines Rezepts einmalig angewendet. */
    private val _pendingTargetKcal = MutableStateFlow<Float?>(null)
    val pendingTargetKcal: StateFlow<Float?> = _pendingTargetKcal.asStateFlow()

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

    fun clearPendingTargetKcal() { _pendingTargetKcal.value = null }

    /** Portion auf festes kcal-Ziel skalieren (z.B. aus „Was koche ich?“). */
    fun scaleToTargetKcal(recipe: Recipe, targetKcal: Float) {
        viewModelScope.launch {
            _budgetScaleState.value = BudgetScaleState(isLoading = true)
            runCatching { budgetScaler.scaleToTargetKcal(recipe, targetKcal, allowUpscale = true) }
                .onSuccess { r ->
                    _budgetScaleState.value = if (r != null) BudgetScaleState(result = r)
                    else BudgetScaleState(error = "Keine Kalorienangabe — zuerst Nährwerte berechnen.")
                }
                .onFailure { e -> _budgetScaleState.value = BudgetScaleState(error = e.message ?: "Fehler") }
        }
    }

    /** Übersetzt Zutaten + Zubereitung ins Deutsche und rechnet auf metrische Einheiten um. */
    fun translateToGermanMetric(recipe: Recipe) {
        if (_isTranslating.value) return
        viewModelScope.launch {
            _isTranslating.value = true
            runCatching {
                val converted = RecipeGermanMetricConverter.convertWithAi(recipe).getOrThrow()
                val updated = recipe.copy(
                    title = converted.title.ifBlank { recipe.title },
                    description = converted.description.ifBlank { recipe.description },
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
    private val _categoryFilter = MutableStateFlow<RecipeCategory?>(null)
    private val _ingredientNeedles = MutableStateFlow<List<String>>(emptyList())
    private val _sort           = MutableStateFlow(RecipeSort.NEWEST)
    private val _importState    = MutableStateFlow(ImportState())
    private val _nutritionState = MutableStateFlow(NutritionState())
    private val _batchState     = MutableStateFlow(BatchImportState())
    val batchState: StateFlow<BatchImportState> = _batchState.asStateFlow()

    // Session-Cache für unbestätigte Verify-Edits (überleben Sheet-Schließen in derselben Session).
    // Persistente Quelle der Wahrheit: IngredientMatch (manualAmountG/manualFiberG/isDeleted/componentGroup).
    private val _sessionOverrides = MutableStateFlow<Map<Long, Map<String, IngredientOverride>>>(emptyMap())
    fun getOverridesFor(recipeId: Long): Map<String, IngredientOverride> =
        _sessionOverrides.value[recipeId] ?: emptyMap()
    fun setOverridesFor(recipeId: Long, overrides: Map<String, IngredientOverride>) {
        _sessionOverrides.update { it + (recipeId to overrides) }
    }
    /** Session + persistente Matches → Overrides-Map für Verify/Recalculate. */
    suspend fun resolveOverrides(recipeId: Long): Map<String, IngredientOverride> {
        val session = _sessionOverrides.value[recipeId]
        if (!session.isNullOrEmpty()) return session
        return matchesToOverrides(matchDao.getMatchesForRecipeOnce(recipeId))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val recipeListFlow: Flow<List<Recipe>> = combine(
        _query.flatMapLatest { q ->
            if (q.isBlank()) repo.getAll() else repo.search(q)
        },
        _platformFilter,
        _categoryFilter,
        _ingredientNeedles,
        _sort
    ) { recipes, platformFilter, categoryFilter, needles, sort ->
        RecipeListFilter.filterAndSort(
            recipes = recipes,
            platformFilter = platformFilter,
            categoryFilter = categoryFilter,
            needles = needles,
            sort = sort
        )
    }

    private data class FilterMeta(
        val query: String,
        val platformFilter: String?,
        val categoryFilter: RecipeCategory?,
        val needles: List<String>,
        val sort: RecipeSort
    )

    private val filterMetaFlow = combine(
        _query, _platformFilter, _categoryFilter, _ingredientNeedles, _sort
    ) { q, p, c, n, s -> FilterMeta(q, p, c, n, s) }

    private val importNutFlow = combine(_importState, _nutritionState, _isTranslating) { imp, nut, tr ->
        Triple(imp, nut, tr)
    }

    val uiState: StateFlow<RecipesUiState> = combine(
        recipeListFlow,
        filterMetaFlow,
        importNutFlow
    ) { recipes, meta, triple ->
        val (imp, nut, translating) = triple
        RecipesUiState(
            recipes           = recipes,
            query             = meta.query,
            platformFilter    = meta.platformFilter,
            categoryFilter    = meta.categoryFilter,
            ingredientNeedles = meta.needles,
            sort              = meta.sort,
            isImporting       = imp.isImporting,
            importPhase       = imp.importPhase,
            importError       = imp.importError,
            lastImport        = imp.lastImport,
            instagramBlocked  = imp.instagramBlocked,
            blockedUrl        = imp.blockedUrl,
            nutritionState    = nut,
            isTranslating     = translating
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipesUiState())


    fun setQuery(q: String) { _query.value = q }
    fun setPlatformFilter(p: String?) { _platformFilter.value = p }
    fun setCategoryFilter(c: RecipeCategory?) { _categoryFilter.value = c }
    fun clearCookFilters() {
        _ingredientNeedles.value = emptyList()
        // Kategorie bleibt, User kann separat zurücksetzen
    }

    /**
     * „Was koche ich?“: Zutaten (Komma/Zeilen) müssen im Rezept vorkommen.
     * Optional Kategorie-Filter und Ziel-kcal pro Portion.
     * Query-Feld wird geleert, damit die volle Liste als Basis dient und nur die Needles filtern.
     * [targetKcal] wird beim nächsten Öffnen eines Rezepts einmalig skaliert.
     */
    fun searchByIngredients(raw: String, category: RecipeCategory? = null, targetKcal: Float? = null) {
        val needles = raw.split(Regex("[,;\n]"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
        _query.value = ""
        _ingredientNeedles.value = needles
        if (category != null) _categoryFilter.value = category
        _pendingTargetKcal.value = targetKcal?.takeIf { it > 0f }
    }

    fun setRecipeCategory(recipe: Recipe, category: RecipeCategory) {
        viewModelScope.launch {
            repo.updateRecipe(recipe.copy(mealCategory = category.name))
        }
    }
    fun setSort(s: RecipeSort) { _sort.value = s }
    fun clearError()        { _importState.update { it.copy(importError = null) } }
    fun clearLastImport()   { _importState.update { it.copy(lastImport = null) } }
    fun clearInstagramBlocked() { _importState.update { it.copy(instagramBlocked = false, blockedUrl = "") } }
    fun clearNutrition()    { _nutritionState.value = NutritionState() }

    fun importFromUrl(url: String) {
        viewModelScope.launch {
            _importState.update {
                it.copy(isImporting = true, importPhase = "Starte…", importError = null, instagramBlocked = false)
            }
            // Bestehendes Rezept unter dieser URL nicht neu scrapen/übersetzen/überschreiben
            val existing = repo.findBySourceUrl(url)
            if (existing != null) {
                _importState.update {
                    it.copy(isImporting = false, importPhase = null, lastImport = existing)
                }
                return@launch
            }
            val result: RecipeScrapeResult = repo.importFromUrl(url) { phase ->
                _importState.update { s -> s.copy(importPhase = phase) }
            }
            if (result.success && result.recipe != null) {
                val r = result.recipe
                val platform = (r.platform ?: "").lowercase()
                val forceGerman = platform in setOf("instagram", "tiktok", "bild")
                val updated = repo.applyGermanMetricIfNeeded(
                    r, enabled = shouldAutoGermanMetric(), force = forceGerman
                )
                _importState.update {
                    it.copy(isImporting = false, importPhase = null, lastImport = updated)
                }
                return@launch
            }
            _importState.update { state ->
                when {
                    result.instagramBlocked ->
                        state.copy(isImporting = false, importPhase = null, instagramBlocked = true, blockedUrl = url)
                    else ->
                        state.copy(
                            isImporting = false,
                            importPhase = null,
                            importError = result.error ?: "Fehler beim Importieren"
                        )
                }
            }
        }
    }

    /**
     * Importiert ein oder mehrere Rezepte aus einem Foto/Screenshot (Rezeptkarte, Kochbuchseite,
     * Collage, Blog-Screenshot). Nutzt Vision-KI; speichert jedes erkannte Rezept mit platform = "bild".
     * [ImportState.lastImport] zeigt das erste gespeicherte Rezept (Navigation/Feedback).
     */
    fun importFromImage(bitmap: Bitmap) {
        viewModelScope.launch {
            _importState.update {
                it.copy(isImporting = true, importPhase = "Rezept(e) aus Bild lesen…", importError = null, instagramBlocked = false)
            }
            try {
                val vision = GroqVisionService()
                val base64 = vision.bitmapToBase64JpegForText(bitmap, quality = 85)
                val extractedList = vision.extractRecipesFromImage(base64).getOrElse { e ->
                    _importState.update {
                        it.copy(
                            isImporting = false,
                            importPhase = null,
                            importError = e.message ?: "Bild konnte nicht gelesen werden"
                        )
                    }
                    return@launch
                }
                if (extractedList.isEmpty()) {
                    _importState.update {
                        it.copy(isImporting = false, importPhase = null, importError = "Kein Rezept im Bild erkannt")
                    }
                    return@launch
                }

                var firstSaved: Recipe? = null
                extractedList.forEachIndexed { index, extracted ->
                    _importState.update {
                        it.copy(
                            importPhase = if (extractedList.size > 1)
                                "Speichere Rezept ${index + 1}/${extractedList.size}…"
                            else
                                "Speichere Rezept…"
                        )
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
                        tags = if (extractedList.size > 1) "bild,mehrfach" else "bild"
                    )
                    var saved = recipe.copy(id = repo.saveRecipe(recipe))
                    saved = repo.applyGermanMetricIfNeeded(
                        saved, enabled = shouldAutoGermanMetric(), force = true
                    )
                    // Auto-Nährwerte aus Zutaten berechnen und persistieren
                    saved = repo.analyzeAndPersistNutrition(saved)?.first ?: saved
                    if (firstSaved == null) firstSaved = saved
                }

                _importState.update {
                    it.copy(
                        isImporting = false,
                        importPhase = null,
                        lastImport = firstSaved,
                        importError = if (extractedList.size > 1)
                            null // Erfolg: mehrere gespeichert; UI öffnet firstSaved
                        else null
                    )
                }
            } catch (e: Exception) {
                _importState.update {
                    it.copy(
                        isImporting = false,
                        importPhase = null,
                        importError = e.message ?: "Fehler beim Bild-Import"
                    )
                }
            }
        }
    }

    /**
     * Hybrid-Import: Instagram-/Social-Link (Quelle + Bildvorschau) + optional Rezept-Screenshot
     * (Zutaten/Anleitung aus dem Bild). Typischer Fall: Caption leer/schwach, Rezept steht
     * im Bild oder in den Kommentaren → Screenshot liefert den Text, der Link die Meta-Daten.
     *
     * - Link wird gescraped → imageUrl, sourceUrl, Titel (falls vorhanden)
     * - Screenshot (falls vorhanden) → Vision extrahiert Zutaten/Anleitung (priorisiert)
     * - Ergebnis: ein Rezept mit funktionierendem Link, Bildvorschau und sauberem Text
     */
    fun importHybridFromInstagram(url: String, recipeScreenshot: Bitmap?) {
        viewModelScope.launch {
            _importState.update {
                it.copy(
                    isImporting = true,
                    importPhase = "Link laden…",
                    importError = null,
                    instagramBlocked = false
                )
            }
            try {
                val trimmedUrl = url.trim()
                if (trimmedUrl.isBlank()) {
                    _importState.update {
                        it.copy(isImporting = false, importPhase = null, importError = "Bitte Link einfügen")
                    }
                    return@launch
                }

                // Bereits gespeichert unter dieser URL? → unverändert öffnen, kein Re-Scrape/Overwrite
                val existingByUrl = repo.findBySourceUrl(trimmedUrl)
                if (existingByUrl != null) {
                    _importState.update {
                        it.copy(
                            isImporting = false,
                            importPhase = null,
                            lastImport = existingByUrl
                        )
                    }
                    return@launch
                }

                // 1) Link scrapen (Meta: Bild, Titel, ggf. schwache Caption)
                val scrapeResult = repo.importFromUrl(trimmedUrl) { phase ->
                    _importState.update { s -> s.copy(importPhase = phase) }
                }

                if (scrapeResult.instagramBlocked) {
                    // Blockiert: ohne Screenshot können wir nichts Sinnvolles speichern
                    if (recipeScreenshot == null) {
                        _importState.update {
                            it.copy(
                                isImporting = false,
                                importPhase = null,
                                instagramBlocked = true,
                                blockedUrl = trimmedUrl
                            )
                        }
                        return@launch
                    }
                    // Mit Screenshot: trotzdem aus dem Bild bauen und Link manuell setzen
                    _importState.update { it.copy(importPhase = "Rezept aus Screenshot lesen…") }
                    val vision = GroqVisionService()
                    val base64 = vision.bitmapToBase64JpegForText(recipeScreenshot, quality = 85)
                    val extracted = vision.extractRecipeFromImage(base64).getOrElse { e ->
                        _importState.update {
                            it.copy(
                                isImporting = false,
                                importPhase = null,
                                importError = e.message ?: "Screenshot konnte nicht gelesen werden"
                            )
                        }
                        return@launch
                    }
                    if (extracted.title.isBlank() && extracted.ingredients.isBlank()) {
                        _importState.update {
                            it.copy(
                                isImporting = false,
                                importPhase = null,
                                importError = "Kein Rezept im Screenshot erkannt"
                            )
                        }
                        return@launch
                    }
                    val totalMin = listOfNotNull(extracted.prepTimeMinutes, extracted.cookTimeMinutes)
                        .takeIf { it.isNotEmpty() }?.sum()
                    val recipe = Recipe(
                        title = extracted.title.ifBlank { "Instagram Rezept" },
                        description = extracted.description,
                        sourceUrl = trimmedUrl,
                        platform = "instagram",
                        ingredients = extracted.ingredients,
                        instructions = extracted.instructions,
                        servings = extracted.servings.coerceAtLeast(1),
                        prepTimeMinutes = totalMin ?: extracted.prepTimeMinutes,
                        totalCalories = extracted.caloriesPerServing?.let {
                            it * extracted.servings.coerceAtLeast(1)
                        },
                        proteinPerServing = extracted.proteinPerServing,
                        carbsPerServing = extracted.carbsPerServing,
                        fatPerServing = extracted.fatPerServing,
                        tags = "instagram,hybrid"
                    )
                    var saved = recipe.copy(id = repo.saveRecipe(recipe))
                    saved = repo.applyGermanMetricIfNeeded(
                        saved, enabled = shouldAutoGermanMetric(), force = true
                    )
                    saved = repo.analyzeAndPersistNutrition(saved)?.first ?: saved
                    _importState.update { it.copy(isImporting = false, importPhase = null, lastImport = saved) }
                    return@launch
                }

                if (!scrapeResult.success || scrapeResult.recipe == null) {
                    // Scrape fehlgeschlagen – mit Screenshot allein weitermachen
                    if (recipeScreenshot == null) {
                        _importState.update {
                            it.copy(
                                isImporting = false,
                                importPhase = null,
                                importError = scrapeResult.error ?: "Link konnte nicht geladen werden"
                            )
                        }
                        return@launch
                    }
                }

                var base = scrapeResult.recipe
                    ?: Recipe(
                        title = "Instagram Rezept",
                        sourceUrl = trimmedUrl,
                        platform = "instagram",
                        tags = "instagram,hybrid"
                    )

                // 2) Optional: Screenshot → Zutaten/Anleitung priorisieren
                if (recipeScreenshot != null) {
                    _importState.update { it.copy(importPhase = "Rezept aus Screenshot lesen…") }
                    val vision = GroqVisionService()
                    val base64 = vision.bitmapToBase64JpegForText(recipeScreenshot, quality = 85)
                    val extracted = vision.extractRecipeFromImage(base64).getOrNull()
                    if (extracted != null &&
                        (extracted.ingredients.isNotBlank() || extracted.instructions.isNotBlank())
                    ) {
                        val totalMin = listOfNotNull(extracted.prepTimeMinutes, extracted.cookTimeMinutes)
                            .takeIf { it.isNotEmpty() }?.sum()
                        base = base.copy(
                            title = extracted.title.ifBlank { base.title }.ifBlank { "Instagram Rezept" },
                            description = extracted.description.ifBlank { base.description },
                            ingredients = extracted.ingredients.ifBlank { base.ingredients },
                            instructions = extracted.instructions.ifBlank { base.instructions },
                            servings = if (extracted.servings > 0) extracted.servings else base.servings,
                            prepTimeMinutes = totalMin ?: extracted.prepTimeMinutes ?: base.prepTimeMinutes,
                            totalCalories = extracted.caloriesPerServing?.let {
                                it * extracted.servings.coerceAtLeast(1)
                            } ?: base.totalCalories,
                            proteinPerServing = extracted.proteinPerServing ?: base.proteinPerServing,
                            carbsPerServing = extracted.carbsPerServing ?: base.carbsPerServing,
                            fatPerServing = extracted.fatPerServing ?: base.fatPerServing,
                            sourceUrl = base.sourceUrl ?: trimmedUrl,
                            platform = base.platform ?: "instagram",
                            tags = listOfNotNull(
                                base.tags.takeIf { it.isNotBlank() },
                                "hybrid"
                            ).joinToString(",").ifBlank { "instagram,hybrid" }
                        )
                        // Scrape hatte das Rezept schon gespeichert → updaten statt neu speichern
                        if (base.id > 0) {
                            repo.updateRecipe(base)
                        } else {
                            base = base.copy(id = repo.saveRecipe(base))
                        }
                    }
                }

                // 3) Auto-Übersetzung / Metrik falls gewünscht
                if (shouldAutoGermanMetric()) {
                    _importState.update { it.copy(importPhase = "Übersetze…") }
                }
                base = repo.applyGermanMetricIfNeeded(
                    base, enabled = shouldAutoGermanMetric(), force = false
                )
                base = repo.analyzeAndPersistNutrition(base)?.first ?: base

                _importState.update {
                    it.copy(isImporting = false, importPhase = null, lastImport = base)
                }
            } catch (e: Exception) {
                _importState.update {
                    it.copy(
                        isImporting = false,
                        importPhase = null,
                        importError = e.message ?: "Fehler beim Hybrid-Import"
                    )
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

    /**
     * Gemeinsamer Nachlauf nach Import:
     * optional DE/metrisch, dann Nährwerte persistieren (wenn Zutaten vorhanden).
     */
    private suspend fun postProcessImported(
        recipe: Recipe,
        forceGerman: Boolean = false,
        withNutrition: Boolean = true
    ): Recipe {
        var r = repo.applyGermanMetricIfNeeded(
            recipe,
            enabled = shouldAutoGermanMetric(),
            force = forceGerman
        )
        if (withNutrition && r.ingredients.isNotBlank()) {
            r = repo.analyzeAndPersistNutrition(r)?.first ?: r
        }
        return r
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

    /**
     * Importiert offene Items sequenziell. Instagram: längere Pause + ein automatischer
     * Retry bei Block (Rate-Limit / Login-Wall oft nach kurzer Wartezeit weg).
     * Nach Erfolg: derselbe Postprocess wie Einzel-Import (DE/metrisch + Nährwerte).
     */
    fun runBatchImport() {
        if (_batchState.value.isRunning) return
        viewModelScope.launch {
            _batchState.update { it.copy(isRunning = true) }
            val queue = _batchState.value.items.filter { it.status != BatchStatus.DONE }
            for ((index, item) in queue.withIndex()) {
                val urlLower = item.url.lowercase()
                val isIg = "instagram.com" in urlLower || "instagr.am" in urlLower
                val isTikTok = "tiktok.com" in urlLower
                if (index > 0 && isIg) {
                    kotlinx.coroutines.delay(2_800L)
                }
                _batchState.update { st ->
                    st.copy(items = st.items.map { if (it.url == item.url) it.copy(status = BatchStatus.RUNNING) else it })
                }
                var result = repo.importFromUrl(item.url)
                if (!result.success && result.instagramBlocked && isIg) {
                    kotlinx.coroutines.delay(3_500L)
                    result = repo.importFromUrl(item.url)
                }
                var doneTitle: String? = null
                if (result.success && result.recipe != null) {
                    val forceGerman = isIg || isTikTok ||
                        (result.recipe.platform ?: "").lowercase() in setOf("instagram", "tiktok", "bild")
                    val recipe = postProcessImported(
                        result.recipe,
                        forceGerman = forceGerman,
                        withNutrition = true
                    )
                    doneTitle = recipe.title
                }
                _batchState.update { st ->
                    st.copy(items = st.items.map {
                        if (it.url != item.url) it
                        else when {
                            result.success -> it.copy(
                                status = BatchStatus.DONE,
                                resultTitle = doneTitle ?: result.recipe?.title
                            )
                            result.instagramBlocked -> it.copy(
                                status = BatchStatus.ERROR,
                                error = "Instagram blockiert – manuell einfügen nötig"
                            )
                            else -> it.copy(status = BatchStatus.ERROR, error = result.error ?: "Fehler")
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
     * Kopie zum Anpassen — inkl. Matches & Komponenten (Repository).
     * Als lastImport melden, damit die UI die Kopie öffnen kann.
     */
    fun duplicateRecipe(recipe: Recipe) {
        viewModelScope.launch {
            val saved = repo.duplicateRecipe(recipe)
            _importState.update { it.copy(lastImport = saved) }
        }
    }

    /**
     * "Auswahl übernehmen" — summiert die bereits gematchten/manuell angepassten
     * Zutaten (letztes AnalysisResult + gespeicherte Overrides) neu, OHNE erneut
     * bei OpenFoodFacts/USDA/Groq zu suchen. Gegenstück zu [analyzeNutrition],
     * das immer komplett neu von der Zutatenliste aus sucht.
     */
    fun recalculateFromOverrides(recipe: Recipe) {
        val result = _nutritionState.value.result.takeIf { _nutritionState.value.recipeId == recipe.id } ?: return
        viewModelScope.launch {
            val overrides = resolveOverrides(recipe.id)
            if (overrides.isEmpty()) return@launch
            val states = mergeIngredientOverrides(result.ingredients, overrides)
            val totals = computeVerifiedTotals(states)
            val servDiv = recipe.servings.coerceAtLeast(1)
            val totalWeight = states.sumOf { it.effectiveAmountG.toDouble() }.toFloat().takeIf { it > 0f }
            applyVerifiedNutrition(
                recipe,
                totals.kcal / servDiv, totals.protein / servDiv, totals.carbs / servDiv, totals.fat / servDiv,
                totals.fiber?.div(servDiv), totals.sugar?.div(servDiv), totals.saturatedFat?.div(servDiv),
                totals.salt?.div(servDiv), totals.sodium?.div(servDiv),
                totalIngredientWeightG = totalWeight
            )
        }
    }

    fun updateRecipe(recipe: Recipe) {
        viewModelScope.launch { repo.updateRecipe(recipe) }
    }

    /**
     * Aktualisiert den Zutatentext und markiert Matches, deren Zeile nicht mehr
     * vorkommt, als gelöscht — verhindert, dass nach dem Entfernen einer Zutat
     * (z.B. Seasoning) die Komponenten-Gruppen aus verwaisten Matches
     * durcheinandergeraten.
     */
    fun updateIngredientsAndSyncMatches(recipe: Recipe, newIngredients: String) {
        viewModelScope.launch {
            repo.updateRecipe(recipe.copy(ingredients = newIngredients))
            val existing = matchDao.getMatchesForRecipeOnce(recipe.id)
            if (existing.isEmpty()) return@launch
            val toDelete = IngredientMatchSync.matchIdsToSoftDelete(existing, newIngredients).toSet()
            for (m in existing) {
                if (m.id in toDelete) {
                    matchDao.updateMatch(m.copy(isDeleted = true))
                }
            }
        }
    }

    /**
     * Einmalige Reparatur: Caption-Klumpen in saubere Zutatenzeilen zerlegen
     * und speichern. No-op wenn Text bereits strukturiert ist.
     */
    fun repairMashedIngredientsIfNeeded(recipe: Recipe) {
        if (!RecipeAiParser.looksMashed(recipe.ingredients)) return
        restructureIngredientSections(recipe)
    }

    /**
     * Zutaten neu strukturieren: Abschnitts-Header (Teig/Füllung/…) erkennen,
     * Bullets setzen, offline deutsche Abschnittsnamen nachziehen.
     * Manuell aus der Rezept-Detailansicht aufrufbar.
     */
    fun restructureIngredientSections(recipe: Recipe) {
        viewModelScope.launch {
            var fixed = RecipeAiParser.formatIngredientText(recipe.ingredients)
            if (fixed.isBlank()) return@launch
            // Offline: dough→Teig, cinnamon coffee filling→…, Header-Zeilen behalten
            fixed = RecipeGermanMetricConverter.convertOfflineFull(fixed)
            if (fixed.trim() == recipe.ingredients.trim()) return@launch
            repo.updateRecipe(recipe.copy(ingredients = fixed))
        }
    }

    private val _imageRefreshState = MutableStateFlow<Pair<Long, String?>>(0L to null)
    /** recipeId → Status: null = idle, "loading", "ok", "fail" */
    val imageRefreshState: StateFlow<Pair<Long, String?>> = _imageRefreshState.asStateFlow()

    /** Thumbnail nachladen (oEmbed), wenn imageUrl fehlt. */
    fun refreshRecipeImage(recipe: Recipe) {
        if (recipe.sourceUrl.isNullOrBlank()) {
            _imageRefreshState.value = recipe.id to "fail"
            return
        }
        viewModelScope.launch {
            _imageRefreshState.value = recipe.id to "loading"
            val updated = runCatching { repo.refreshRecipeImage(recipe) }.getOrNull()
            _imageRefreshState.value = recipe.id to if (updated != null) "ok" else "fail"
        }
    }

    /**
     * Freies Rezept ohne Import: Titel + Zutatenzeilen (+ optional Zubereitung).
     * Speichert mit platform=manual; nach dem Speichern als lastImport melden,
     * damit die UI direkt „Ansehen“ anbieten kann.
     */
    fun createManualRecipe(
        title: String,
        ingredients: String,
        instructions: String = "",
        servings: Int = 1,
        mealCategory: String = ""
    ) {
        viewModelScope.launch {
            val cleanTitle = title.trim().ifBlank { "Mein Rezept" }
            val recipe = Recipe(
                title = cleanTitle,
                description = "",
                ingredients = ingredients.trim(),
                instructions = instructions.trim(),
                servings = servings.coerceAtLeast(1),
                platform = "manual",
                tags = "manuell",
                mealCategory = mealCategory,
                sourceUrl = null
            ).withGuessedCategoryIfEmpty().withoutNullArtifacts()
            var saved = recipe.copy(id = repo.saveRecipe(recipe))
            // Manuell oft schon DE – nur Auto-Übersetzung, Nährwerte wenn Zutaten da
            saved = postProcessImported(saved, forceGerman = false, withNutrition = true)
            _importState.update { it.copy(lastImport = saved) }
        }
    }

    /** Favorit umschalten (isFavorite in DB). */
    fun toggleFavorite(recipe: Recipe) {
        viewModelScope.launch {
            repo.updateRecipe(recipe.copy(isFavorite = !recipe.isFavorite))
        }
    }

    fun getComponents(recipeId: Long): Flow<List<RecipeComponent>> = repo.getComponents(recipeId)

    suspend fun getComponentsOnce(recipeId: Long): List<RecipeComponent> =
        repo.getComponentsOnce(recipeId)

    /**
     * Komponenten eines Rezepts setzen (ersetzt bestehende).
     * Leere Liste = zurück zu One-Pot.
     * Optional: Gesamtnährwerte des Rezepts aus den Komponenten neu berechnen.
     *
     * Heilt kaputte Bestandsdaten: Wenn jede Komponente die vollen Rezept-kcal
     * trägt (Summe ≈ n × Rezept-Total) oder alle kcal leer sind, werden die
     * Nährwerte aus Zutaten-Matches bzw. proportional zum Kochgewicht neu gesetzt.
     */
    fun setComponents(recipeId: Long, components: List<RecipeComponent>, updateRecipeTotals: Boolean = true) {
        viewModelScope.launch {
            val recipe = repo.getById(recipeId) ?: return@launch
            // Duplikate nach Name zusammenführen (z. B. 2× „Sauce / Fleisch“)
            val deduped = components
                .groupBy { it.name.trim().lowercase() }
                .map { (_, group) ->
                    if (group.size == 1) group.first()
                    else {
                        // Letztes mit Gewicht > 0 bevorzugen, Nährwerte vom besten
                        val best = group.lastOrNull { it.cookedWeightG > 0f && it.totalCalories > 0f }
                            ?: group.lastOrNull { it.cookedWeightG > 0f }
                            ?: group.last()
                        best
                    }
                }
            // Vom Verify-Sheet kommende Werte (unterschiedliche kcal/100g) nicht überschreiben
            val dens = deduped.map { c ->
                if (c.cookedWeightG > 0f && c.totalCalories > 0f) c.totalCalories / c.cookedWeightG else -1f
            }
            val alreadySplit = deduped.size >= 2 &&
                dens.all { it > 0f } &&
                dens.maxOrNull()!!.let { mx -> dens.minOrNull()!!.let { mn -> mx / mn.coerceAtLeast(0.001f) > 1.08f } }
            var comps = if (alreadySplit) deduped else healComponentNutrition(recipe, deduped)
            repo.setComponents(recipeId, comps)
            if (updateRecipeTotals && comps.isNotEmpty()) {
                val totalKcal = comps.sumOf { it.totalCalories.toDouble() }.toFloat()
                // Nie vorhandene Rezept-Nährwerte mit 0 überschreiben
                if (totalKcal <= 0f) return@launch
                val servings = recipe.servings.coerceAtLeast(1).toFloat()
                val totalProtein = comps.sumOf { it.proteinG.toDouble() }.toFloat()
                val totalCarbs = comps.sumOf { it.carbsG.toDouble() }.toFloat()
                val totalFat = comps.sumOf { it.fatG.toDouble() }.toFloat()
                val totalFiber = comps.sumOf { it.fiberG.toDouble() }.toFloat()
                val totalCooked = comps.sumOf { it.cookedWeightG.toDouble() }.toFloat()
                repo.updateRecipe(
                    recipe.copy(
                        totalCalories = totalKcal,
                        proteinPerServing = totalProtein / servings,
                        carbsPerServing = totalCarbs / servings,
                        fatPerServing = totalFat / servings,
                        fiberPerServing = totalFiber / servings,
                        cookedWeightG = totalCooked.takeIf { it > 0f }
                    )
                )
            }
        }
    }

    /**
     * Korrigiert Komponenten-Nährwerte kanonisch über [enrichComponentsFromMatches]
     * (Matches → Ableitung, sonst proportionale Fallback-Heilung).
     */
    suspend fun healComponentNutrition(
        recipe: Recipe,
        components: List<RecipeComponent>
    ): List<RecipeComponent> {
        if (components.isEmpty()) return components
        val matches = matchDao.getMatchesForRecipeOnce(recipe.id)
        return enrichComponentsFromMatches(recipe, components, matches)
    }

    /**
     * Schlägt Komponenten aus verifizierten Zutaten vor (beliebig viele Gruppen).
     * Gruppiert nach [IngredientMatch.componentGroup]; unbekannte Werte werden
     * heuristisch Beilage vs. Sauce zugeordnet. Kochgewichte bleiben leer.
     */
    suspend fun suggestComponentsFromMatches(recipe: Recipe): List<RecipeComponent> {
        val matches = matchDao.getMatchesForRecipeOnce(recipe.id)
        return RecipeComponentSuggester.suggestFromMatches(recipe.id, matches)
    }

    fun analyzeNutrition(recipe: Recipe, persist: Boolean = false) {
        viewModelScope.launch {
            _nutritionState.value = NutritionState(isAnalyzing = true, recipeId = recipe.id)
            if (persist) {
                // Persist-Logik zentral im Repository (auch Import-Pipeline nutzt sie)
                runCatching { repo.analyzeAndPersistNutrition(recipe) }
                    .onSuccess { pair ->
                        if (pair != null) {
                            _nutritionState.value =
                                NutritionState(result = pair.second, recipeId = pair.first.id)
                        } else {
                            _nutritionState.value =
                                NutritionState(error = "Analyse nicht möglich", recipeId = recipe.id)
                        }
                    }
                    .onFailure { e ->
                        _nutritionState.value = NutritionState(error = e.message, recipeId = recipe.id)
                    }
                return@launch
            }
            val result = runCatching { RecipeNutritionAnalyzer.analyze(recipe) }
            result.onSuccess { analysis ->
                _nutritionState.value = NutritionState(result = analysis, recipeId = recipe.id)
            }.onFailure { e ->
                _nutritionState.value = NutritionState(error = e.message, recipeId = recipe.id)
            }
        }
    }

    /**
     * Setzt das Nährwert-Ergebnis direkt aus bereits gespeicherten IngredientMatches
     * (siehe [ch.nutrisnap.app.domain.RecipeNutritionAnalyzer.fromStoredMatches]) — OHNE
     * DB-/Netzwerk-Neuanalyse. Für "Einsehen"/"Verify" bei bereits verifizierten Rezepten,
     * damit nicht bei jedem Öffnen neu (und ggf. mit abweichenden Treffern) gesucht wird.
     */
    fun setNutritionFromStoredMatches(result: ch.nutrisnap.app.domain.RecipeNutritionAnalyzer.AnalysisResult, recipeId: Long) {
        _nutritionState.value = NutritionState(result = result, recipeId = recipeId)
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
        sodiumPerServ: Float? = null,
        totalIngredientWeightG: Float? = null,
        /** Verifizierte Zutaten (gescannte Produkte + Mengen) ersetzen den alten Text. */
        ingredientsText: String? = null
    ) {
        viewModelScope.launch {
            val updated = RecipeVerifiedNutrition.applyToRecipe(
                recipe = recipe,
                kcalPerServ = kcalPerServ,
                protPerServ = protPerServ,
                carbsPerServ = carbsPerServ,
                fatPerServ = fatPerServ,
                fiberPerServ = fiberPerServ,
                sugarPerServ = sugarPerServ,
                satFatPerServ = satFatPerServ,
                saltPerServ = saltPerServ,
                sodiumPerServ = sodiumPerServ,
                totalIngredientWeightG = totalIngredientWeightG,
                ingredientsText = ingredientsText
            )
            repo.updateRecipe(updated)
            // Session-Overrides räumen – Persistenz liegt in IngredientMatch
            _sessionOverrides.update { it - recipe.id }
            _nutritionState.value = NutritionState()
        }
    }

    /**
     * Merged verifizierte Zutaten als IngredientMatch-Zeilen (kein delete-all).
     * Primär per id, Fallback über normalisierten ingredientName.
     * Bestehende componentGroup / matchedFoodItemId bleiben erhalten, wenn die
     * neue Zeile keinen expliziten neuen Wert mitbringt.
     */
    fun mergeMatchesForRecipe(recipeId: Long, matches: List<ch.nutrisnap.app.data.model.IngredientMatch>) {
        viewModelScope.launch {
            val existing = matchDao.getMatchesForRecipeOnce(recipeId)
            if (matches.isEmpty()) {
                if (existing.isNotEmpty()) matchDao.deleteMatchesForRecipe(recipeId)
                return@launch
            }
            fun norm(s: String) = s.trim().lowercase()
            val byId = existing.associateBy { it.id }
            val byName = existing
                .filter { it.ingredientName.isNotBlank() }
                .associateBy { norm(it.ingredientName) }
            val usedIds = mutableSetOf<Long>()

            for (m in matches) {
                val old = when {
                    m.id > 0L && m.id in byId -> byId[m.id]
                    norm(m.ingredientName).isNotBlank() && norm(m.ingredientName) in byName ->
                        byName[norm(m.ingredientName)]
                    else -> null
                }
                if (old != null) {
                    usedIds += old.id
                    val merged = m.copy(
                        id = old.id,
                        recipeId = recipeId,
                        // Behalte bestehende Zuordnung, wenn neu null
                        componentGroup = m.componentGroup ?: old.componentGroup,
                        matchedFoodItemId = m.matchedFoodItemId ?: old.matchedFoodItemId,
                        matchedFoodName = m.matchedFoodName ?: old.matchedFoodName,
                        matchedCalories = m.matchedCalories ?: old.matchedCalories,
                        matchedProtein = m.matchedProtein ?: old.matchedProtein,
                        matchedCarbs = m.matchedCarbs ?: old.matchedCarbs,
                        matchedFat = m.matchedFat ?: old.matchedFat,
                        matchSource = if (m.matchSource != ch.nutrisnap.app.data.model.MatchSource.UNMATCHED)
                            m.matchSource else old.matchSource,
                        manualAmountG = m.manualAmountG ?: old.manualAmountG,
                        manualFiberG = m.manualFiberG ?: old.manualFiberG,
                        isDeleted = m.isDeleted
                    )
                    matchDao.updateMatch(merged)
                } else {
                    matchDao.insertMatch(m.copy(id = 0, recipeId = recipeId))
                }
            }
            // Entfernte Zeilen löschen
            for (old in existing) {
                if (old.id !in usedIds) matchDao.deleteMatch(old)
            }
        }
    }

    /** @deprecated Nutze [mergeMatchesForRecipe] – bleibt als Alias für Call-Sites. */
    fun replaceMatchesForRecipe(recipeId: Long, matches: List<ch.nutrisnap.app.data.model.IngredientMatch>) =
        mergeMatchesForRecipe(recipeId, matches)

    fun getMatches(recipeId: Long): Flow<List<ch.nutrisnap.app.data.model.IngredientMatch>> =
        matchDao.getMatchesForRecipe(recipeId)

    /**
     * Importiert ein von Claude (Chat) geteiltes Rezept-JSON direkt, ohne Zwischenschritt.
     * Wird still ignoriert, falls der Text kein erkennbares Rezept-JSON ist.
     */
    fun importFromSharedJson(json: String) {
        val recipe = ch.nutrisnap.app.domain.RecipeJsonImport.tryParse(json) ?: return
        viewModelScope.launch {
            var saved = recipe.copy(id = repo.saveRecipe(recipe))
            // JSON aus Chat oft schon DE/metrisch – Auto-Einstellung, Nährwerte nachziehen
            saved = postProcessImported(saved, forceGerman = false, withNutrition = true)
            _importState.update { it.copy(lastImport = saved) }
        }
    }

    fun saveManualRecipe(url: String, title: String?, caption: String) {
        viewModelScope.launch {
            val cleaned = RecipeAiParser.cleanCaption(caption)
            val (ingredients, instructions) = parseCaption(cleaned)
            val platform = when {
                "instagram.com" in url || "instagr.am" in url -> "instagram"
                "tiktok.com" in url -> "tiktok"
                else -> "web"
            }
            val recipe = Recipe(
                title        = title?.ifBlank { null } ?: RecipeAiParser.extractTitle(caption, "Instagram Rezept"),
                description  = cleaned.take(500),
                sourceUrl    = url.ifBlank { null },
                platform     = platform,
                ingredients  = ingredients.ifBlank { cleaned },
                instructions = instructions,
                tags         = "manuell"
            )
            var saved = recipe.copy(id = repo.saveRecipe(recipe))
            val forceGerman = platform in setOf("instagram", "tiktok")
            saved = postProcessImported(saved, forceGerman = forceGerman, withNutrition = true)
            _importState.update { it.copy(lastImport = saved) }
        }
    }

    private fun parseCaption(caption: String): Pair<String, String> =
        RecipeCaptionParser.parseCaption(caption)
}
