package ch.nutrisnap.app.ui.screens.diary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.*
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.repository.FavoriteFoodRepository
import ch.nutrisnap.app.data.repository.FoodItemRepository
import ch.nutrisnap.app.data.repository.RecipeRepository
import ch.nutrisnap.app.data.repository.UserProfileRepository
import ch.nutrisnap.app.data.repository.WeightRepository
import ch.nutrisnap.app.domain.AdaptiveTdeeCalculator
import ch.nutrisnap.app.domain.GroqVisionService
import android.graphics.Bitmap
import android.content.Context
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.edit
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.theme.KEY_AGGRESSIVE_SPORT_DAY
import ch.nutrisnap.app.ui.theme.KEY_MANUAL_ACTIVITY_ENABLED
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DiaryUiState(
    val selectedDate:  LocalDate        = LocalDate.now(),
    val entries:       List<DiaryEntry> = emptyList(),
    val totalCalories: Float            = 0f,
    val totalProtein:  Float            = 0f,
    val totalCarbs:    Float            = 0f,
    val totalFat:      Float            = 0f,
    val calorieGoal:   Float            = 2000f,
    val proteinGoal:   Float            = 120f,
    val carbsGoal:     Float            = 220f,
    val fatGoal:       Float            = 65f,
    val isLoading:     Boolean          = false
)

class DiaryViewModel(app: Application) : AndroidViewModel(app) {
    private val db          = NutriDatabase.getInstance(app)
    private val repo        = DiaryRepository(db)
    private val foodRepo    = FoodItemRepository(db)
    private val recipeRepo  = RecipeRepository(db, app)
    private val profileRepo = UserProfileRepository(db)
    private val favRepo     = FavoriteFoodRepository(db)
    private val weightRepo  = WeightRepository(db)
    private val hcDao       = db.healthConnectDao()
    private val manualActivityDao = db.manualActivityDao()
    private val contextRanking = ch.nutrisnap.app.data.repository.ContextualFoodRankingRepository(
        db.foodUsageContextDao(), favRepo
    )

    private val _date = MutableStateFlow(LocalDate.now())
    private val trendWindowDays = 21

    /**
     * Kalorienziel identisch zur Startseite: adaptives TDEE wenn genug Daten,
     * sonst Profilziel + heutige Aktivität.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<DiaryUiState> = _date.flatMapLatest { selected ->
        combine(
            repo.getEntriesForDate(selected),
            profileRepo.get(),
            hcDao.getCacheForDate(selected),
            weightRepo.getRecent(trendWindowDays),
            repo.getWeeklySummary(selected.minusDays(trendWindowDays.toLong())),
            hcDao.getLast30Days(),
            app.notifDataStore.data,
            manualActivityDao.getSince(selected.minusDays(29).toString())
        ) { args ->
            val entries = args[0] as List<DiaryEntry>
            val profile = args[1] as ch.nutrisnap.app.data.repository.UserProfile
            val hcCache = args[2] as HealthConnectCache?
            @Suppress("UNCHECKED_CAST")
            val trendWeights = args[3] as List<WeightEntry>
            @Suppress("UNCHECKED_CAST")
            val dailySummaries = args[4] as List<ch.nutrisnap.app.data.db.DailySummary>
            @Suppress("UNCHECKED_CAST")
            val activityDays = args[5] as List<HealthConnectCache>
            val prefs = args[6] as androidx.datastore.preferences.core.Preferences
            @Suppress("UNCHECKED_CAST")
            val manualActivities = args[7] as List<ManualActivityEntry>

            val manualWeightByDate = trendWeights.associate { LocalDate.parse(it.dateStr) to it.weightKg }
            val hcWeightByDate = activityDays
                .mapNotNull { c -> c.weightKg?.let { kg -> c.date to kg.toFloat() } }
                .toMap()
            val weightByDate = AdaptiveTdeeCalculator.mergeWeightByDate(manualWeightByDate, hcWeightByDate)
            val intakeByDate = dailySummaries.associate { LocalDate.parse(it.dateStr) to it.calories }
            val trend = AdaptiveTdeeCalculator.computeTrendTdee(weightByDate, intakeByDate)

            val manualEnabled = prefs[KEY_MANUAL_ACTIVITY_ENABLED] ?: false
            val manualByDate = if (manualEnabled) {
                manualActivities.associate {
                    LocalDate.parse(it.dateStr) to it.activeCaloriesKcal.toDouble()
                }
            } else emptyMap()
            val manualToday = manualByDate[selected]
            val hcToday = hcCache?.activeCaloriesKcal
            val todayActiveCombined = run {
                val sum = (hcToday ?: 0.0) + (manualToday ?: 0.0)
                sum.takeIf { it > 0.0 }
            }
            val avgActiveCombined = run {
                val byDate = linkedMapOf<LocalDate, Double>()
                for (c in activityDays) {
                    c.activeCaloriesKcal?.let { byDate[c.date] = it }
                }
                for ((d, kcal) in manualByDate) {
                    byDate[d] = (byDate[d] ?: 0.0) + kcal
                }
                byDate.values.takeIf { it.isNotEmpty() }?.average()
            }
            val displayActiveKcal = todayActiveCombined ?: 0.0

            val weeklyLoss = profile.weeklyTargetLossKg?.takeIf { it > 0f }
            val deficitKcal = weeklyLoss?.let { it * AdaptiveTdeeCalculator.KCAL_PER_KG / 7.0 }
                ?: AdaptiveTdeeCalculator.DEFAULT_DEFICIT_KCAL
            val aggressiveSport = prefs[KEY_AGGRESSIVE_SPORT_DAY] ?: false
            val activityFactor = if (aggressiveSport) 1.0 else AdaptiveTdeeCalculator.ACTIVITY_ADJUSTMENT_FACTOR

            val adaptiveTarget = AdaptiveTdeeCalculator.computeDailyTarget(
                trend = trend,
                formulaTdee = profile.computedTdee(),
                todayActiveKcal = todayActiveCombined,
                avgActiveKcal = avgActiveCombined,
                deficitKcal = deficitKcal,
                formulaBmr = profile.computedBmr(),
                activityFactor = activityFactor
            )

            // Gleiche Logik wie HomeUiState.adjustedGoal
            val calorieGoal = if (adaptiveTarget != null) {
                adaptiveTarget.targetKcal.toFloat()
            } else {
                profile.dailyCalorieGoal.toFloat() + displayActiveKcal.toFloat()
            }

            DiaryUiState(
                selectedDate  = selected,
                entries       = entries,
                totalCalories = entries.sumOf { it.calories.toDouble() }.toFloat(),
                totalProtein  = entries.sumOf { it.protein.toDouble() }.toFloat(),
                totalCarbs    = entries.sumOf { it.carbs.toDouble() }.toFloat(),
                totalFat      = entries.sumOf { it.fat.toDouble() }.toFloat(),
                calorieGoal   = calorieGoal,
                proteinGoal   = profile.proteinGoalG,
                carbsGoal     = profile.carbsGoalG,
                fatGoal       = profile.fatGoalG
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryUiState())

    // Food search state
    private val _searchResults  = MutableStateFlow<List<FoodItem>>(emptyList())
    private val _isSearching    = MutableStateFlow(false)
    private val _barcodeResult  = MutableStateFlow<FoodItem?>(null)

    val searchResults:  StateFlow<List<FoodItem>> = _searchResults
    val isSearching:    StateFlow<Boolean>        = _isSearching
    val barcodeResult:  StateFlow<FoodItem?>      = _barcodeResult

    // Favorites — Reihenfolge tageszeit-bewusst (Feature 7): Foods, die üblicherweise
    // um diese Uhrzeit/an diesem Wochentag geloggt werden, stehen zuerst. Inhalt bleibt
    // unverändert List<FoodItem>, nur die Sortierung wechselt — die UI braucht dafür
    // keine Anpassung.
    val favorites: StateFlow<List<FoodItem>> = favRepo.getAll()
        .map { list ->
            val ranked = runCatching { contextRanking.getRankedFavoritesForNow() }.getOrDefault(emptyList())
            if (ranked.isEmpty()) return@map list
            val rankIndex = ranked.mapIndexed { idx, r -> r.foodId to idx }.toMap()
            list.sortedBy { rankIndex[it.id.toString()] ?: Int.MAX_VALUE }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun isFavorite(food: FoodItem): Flow<Boolean> = favRepo.isFavorite(food)
    fun toggleFavorite(food: FoodItem) = viewModelScope.launch { favRepo.toggle(food) }

    fun setDate(date: LocalDate) { _date.value = date }
    fun prevDay()                { _date.value = _date.value.minusDays(1) }
    fun nextDay()                { _date.value = _date.value.plusDays(1) }

    fun searchFood(query: String) {
        if (query.isBlank()) { _searchResults.value = emptyList(); return }
        viewModelScope.launch {
            _isSearching.value = true
            _searchResults.value = foodRepo.searchAll(query)
            _isSearching.value = false
        }
    }

    fun searchBarcode(barcode: String, onResult: (FoodItem?) -> Unit) {
        viewModelScope.launch {
            _isSearching.value = true
            val food = foodRepo.searchBarcode(barcode)
            _isSearching.value = false
            onResult(food)
        }
    }

    fun setBarcodeResult(food: FoodItem) { _barcodeResult.value = food }
    fun clearBarcodeResult()             { _barcodeResult.value = null }

    /**
     * Unbekanntes Produkt: Nährwert-Foto(s) auslesen, als CustomFood mit Barcode speichern
     * und optional sofort ins Tagebuch eintragen.
     */
    fun captureUnknownProduct(
        barcode: String,
        labelBitmap: Bitmap,
        secondBitmap: Bitmap? = null,
        meal: MealType,
        amountGrams: Float = 100f,
        onDone: (FoodItem?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val vision = GroqVisionService()
                val b64 = vision.bitmapToBase64Jpeg(labelBitmap, quality = 85)
                var label = vision.analyzeNutritionLabel(b64).getOrElse { e ->
                    onDone(null)
                    return@launch
                }
                // Zweites Foto (falls Etikett getrennt) – fehlende Felder ergänzen
                if (secondBitmap != null) {
                    val b642 = vision.bitmapToBase64Jpeg(secondBitmap, quality = 85)
                    vision.analyzeNutritionLabel(b642).getOrNull()?.let { second ->
                        label = label.copy(
                            caloriesPer100g = label.caloriesPer100g.takeIf { it > 0f } ?: second.caloriesPer100g,
                            proteinPer100g = label.proteinPer100g.takeIf { it > 0f } ?: second.proteinPer100g,
                            carbsPer100g = label.carbsPer100g.takeIf { it > 0f } ?: second.carbsPer100g,
                            fatPer100g = label.fatPer100g.takeIf { it > 0f } ?: second.fatPer100g,
                            fiberPer100g = label.fiberPer100g.takeIf { it > 0f } ?: second.fiberPer100g,
                            sugarPer100g = label.sugarPer100g.takeIf { it > 0f } ?: second.sugarPer100g,
                            saltPer100g = label.saltPer100g.takeIf { it > 0f } ?: second.saltPer100g,
                            productName = label.productName.ifBlank { second.productName },
                            brand = label.brand.ifBlank { second.brand }
                        )
                    }
                }
                val name = label.productName.ifBlank { "Produkt $barcode" }
                val custom = CustomFoodItem(
                    name = name,
                    brand = label.brand.ifBlank { null },
                    barcode = barcode,
                    calories = label.caloriesPer100g,
                    protein = label.proteinPer100g,
                    carbs = label.carbsPer100g,
                    fat = label.fatPer100g,
                    fiber = label.fiberPer100g,
                    sugar = label.sugarPer100g,
                    salt = label.saltPer100g,
                    portionSizeG = 100f,
                    source = "label_scan"
                )
                foodRepo.saveCustomFoodWithBarcode(custom)
                val food = FoodItem(
                    name = custom.name,
                    brand = custom.brand,
                    barcode = barcode,
                    calories = custom.calories,
                    protein = custom.protein,
                    carbs = custom.carbs,
                    fat = custom.fat,
                    fiber = custom.fiber,
                    sugar = custom.sugar,
                    salt = custom.salt,
                    servingSize = 100f,
                    source = FoodSource.MANUAL,
                    completenessScore = 95
                )
                addEntry(food, amountGrams, meal)
                rememberLastAmount(food, amountGrams)
                onDone(food)
            } catch (e: Exception) {
                onDone(null)
            }
        }
    }

    /** Kopiert alle Einträge von gestern auf das aktuell gewählte Datum. */
    fun copyYesterday(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val target = _date.value
            val source = target.minusDays(1)
            val entries = repo.getEntriesForDateOnce(source)
            var n = 0
            for (e in entries) {
                repo.duplicateEntryToDate(e, target)
                n++
            }
            onDone(n)
        }
    }

    /** Eintrag auf anderes Datum/Mahlzeit verschieben (z.B. falsch heute statt gestern Abend). */
    fun moveEntry(entry: DiaryEntry, date: java.time.LocalDate, meal: MealType, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.updateEntry(entry.copy(dateStr = date.toString(), mealType = meal))
            onDone()
        }
    }

    /**
     * Eintrag auf mehrere Tage kopieren (Meal-Prep).
     * @param dayCount Anzahl Tage inklusive Starttag
     * @param startDate erster Tag (meist Eintragsdatum oder heute)
     * @param meal optional andere Mahlzeit auf allen Kopien
     * @param includeStart wenn false, wird der Starttag übersprungen (nur Folgetage)
     */
    fun copyEntryToDays(
        entry: DiaryEntry,
        dayCount: Int,
        startDate: java.time.LocalDate,
        meal: MealType? = null,
        includeStart: Boolean = true,
        onDone: (Int) -> Unit = {}
    ) {
        viewModelScope.launch {
            val count = dayCount.coerceIn(1, 14)
            var n = 0
            for (i in 0 until count) {
                if (!includeStart && i == 0) continue
                val d = startDate.plusDays(i.toLong())
                // Am Starttag mit gleichem Datum+Meal nicht duplizieren
                if (d.toString() == entry.dateStr && (meal == null || meal == entry.mealType) && i == 0) {
                    continue
                }
                val base = if (meal != null) entry.copy(mealType = meal) else entry
                repo.duplicateEntryToDate(base, d)
                n++
            }
            onDone(n)
        }
    }

    /** Ganze Mahlzeit eines Tages auf N Folgetage kopieren. */
    fun copyMealToDays(
        meal: MealType,
        sourceDate: java.time.LocalDate,
        dayCount: Int,
        includeStart: Boolean = false,
        onDone: (Int) -> Unit = {}
    ) {
        viewModelScope.launch {
            val entries = repo.getEntriesForDateOnce(sourceDate).filter { it.mealType == meal }
            val count = dayCount.coerceIn(1, 14)
            var n = 0
            for (i in 0 until count) {
                if (!includeStart && i == 0) continue
                val d = sourceDate.plusDays(i.toLong())
                if (d == sourceDate) continue
                for (e in entries) {
                    repo.duplicateEntryToDate(e, d)
                    n++
                }
            }
            onDone(n)
        }
    }

    /** Stabiler Speicher-Key: Barcode bevorzugt (gleiches Produkt), sonst Name. */
    private fun amountMemoryKey(food: FoodItem): String {
        val barcode = food.barcode?.trim().orEmpty()
        return if (barcode.isNotEmpty()) {
            "last_amount_bc_$barcode"
        } else {
            "last_amount_" + food.name.lowercase().trim().take(80).hashCode()
        }
    }

    fun rememberLastAmount(food: FoodItem, grams: Float) {
        if (grams <= 0f) return
        viewModelScope.launch {
            val key = floatPreferencesKey(amountMemoryKey(food))
            getApplication<Application>().notifDataStore.edit { it[key] = grams }
            // Zusätzlich unter Namen speichern (Fallback ohne Barcode beim nächsten Mal)
            val nameKey = floatPreferencesKey(
                "last_amount_" + food.name.lowercase().trim().take(80).hashCode()
            )
            getApplication<Application>().notifDataStore.edit { it[nameKey] = grams }
        }
    }

    suspend fun getLastAmount(food: FoodItem): Float? {
        val prefs = getApplication<Application>().notifDataStore.data.first()
        // 1) Barcode-Key
        food.barcode?.trim()?.takeIf { it.isNotEmpty() }?.let { bc ->
            prefs[floatPreferencesKey("last_amount_bc_$bc")]?.let { if (it > 0f) return it }
        }
        // 2) Name-Key (Legacy + Produkte ohne Barcode)
        return prefs[floatPreferencesKey(
            "last_amount_" + food.name.lowercase().trim().take(80).hashCode()
        )]?.takeIf { it > 0f }
    }

    fun addEntryWithMemory(food: FoodItem, grams: Float, meal: MealType) {
        addEntry(food, grams, meal)
        rememberLastAmount(food, grams)
    }

    fun addEntry(food: FoodItem, grams: Float, meal: MealType, date: java.time.LocalDate? = null) {
        viewModelScope.launch {
            repo.addEntry(food, grams, meal, date ?: _date.value)
            contextRanking.recordFoodUsage(food.id.toString(), food.name)
        }
    }

    /**
     * Ein-Tap-Hinzufuegen aus der Quick-Add-Leiste (Favoriten). Meal wird per
     * Tageszeit vorgeschlagen, Menge ist die Standard-Portion des Foods.
     * Liefert den gespeicherten Eintrag zurueck, damit die UI eine Undo-Snackbar
     * anzeigen kann (analog zum bestehenden Loeschen-Undo).
     */
    fun quickAddFavorite(food: FoodItem, grams: Float, meal: MealType, onAdded: (DiaryEntry) -> Unit) {
        viewModelScope.launch {
            val id = repo.addEntry(food, grams, meal, _date.value)
            contextRanking.recordFoodUsage(food.id.toString(), food.name)
            repo.getById(id)?.let { onAdded(it) }
        }
    }

    /**
     * Manueller Eintrag: Name + kcal + optionale Makros, keine FoodItem-Referenz.
     * foodItemId = -999 markiert manuelle Einträge.
     */
    fun addManualEntry(
        name: String,
        kcal: Float,
        protein: Float,
        carbs: Float,
        fat: Float,
        meal: MealType
    ) {
        viewModelScope.launch {
            repo.addManualEntry(name, kcal, protein, carbs, fat, meal, _date.value)
        }
    }

    fun addRecipeAsMeal(
        recipe: Recipe,
        servingsFactor: Float,
        meal: MealType,
        gramsAmount: Float? = null,
        date: java.time.LocalDate? = null
    ) {
        viewModelScope.launch {
            repo.addRecipeAsMeal(recipe, servingsFactor, meal, date ?: _date.value, gramsAmount)
        }
    }

    /**
     * Multi-Komponenten-Rezept: pro Komponente ein Tagebucheintrag mit eigenem Gewicht.
     */
    fun addRecipeComponentsAsMeal(
        recipe: Recipe,
        components: List<RecipeComponent>,
        gramsByComponentId: Map<Long, Float>,
        meal: MealType,
        date: java.time.LocalDate? = null
    ) {
        viewModelScope.launch {
            repo.addRecipeComponentsAsMeal(
                recipe, components, gramsByComponentId, meal, date ?: _date.value
            )
        }
    }

    /**
     * Mengenänderung. [newValue] ist immer in derselben Einheit wie die Anzeige:
     * Gramm bei Lebensmitteln, Portionsfaktor bei Rezepten/Manuell, bzw. der
     * aus Gramm zurückgerechnete Portionsfaktor bei gram-getrackten Rezepten
     * (siehe EditEntryDialog).
     *
     * Manuelle Einträge (amountGrams == 0): newValue = Portionsmultiplikator
     * relativ zur aktuellen „1 Portion“-Basis der gespeicherten Makros — nicht kcal.
     */
    fun updateEntryAmount(entry: DiaryEntry, newValue: Float) {
        if (newValue <= 0f) return
        viewModelScope.launch {
            when {
                // Manuell / Legacy ohne Menge: Portionsfaktor relativ zu 1.0
                entry.amountGrams <= 0f -> {
                    val factor = newValue.coerceAtLeast(0.01f)
                    repo.updateEntry(
                        entry.scaledBy(factor).copy(amountGrams = factor)
                    )
                }
                else -> {
                    val factor = newValue / entry.amountGrams
                    repo.updateEntry(
                        entry.scaledBy(factor).copy(
                            amountGrams = newValue,
                            recipeGrams = entry.recipeGrams?.let { it * factor }
                        )
                    )
                }
            }
        }
    }

    /**
     * Direkte Makro-Korrektur (globale Korrekturebene): überschreibt genau EIN
     * Feld der Endsumme, ohne die zugrunde liegenden Zutaten anzufassen. Vor dem
     * allerersten Override wird der automatisch berechnete Wert je Feld als
     * "original" gesichert, damit ein späteres "Override entfernen" (clearGlobalOverride)
     * die Werte wiederherstellen kann.
     */
    fun setGlobalMacroOverride(entry: DiaryEntry, field: MacroField, newValue: Float) {
        viewModelScope.launch {
            val withSnapshot = entry.copy(
                isGloballyOverridden = true,
                originalCalories = entry.originalCalories ?: entry.calories,
                originalProtein  = entry.originalProtein  ?: entry.protein,
                originalCarbs    = entry.originalCarbs    ?: entry.carbs,
                originalFat      = entry.originalFat      ?: entry.fat,
                originalFiber    = entry.originalFiber    ?: entry.fiber
            )
            val updated = when (field) {
                MacroField.CALORIES -> withSnapshot.copy(calories = newValue)
                MacroField.PROTEIN  -> withSnapshot.copy(protein = newValue)
                MacroField.CARBS    -> withSnapshot.copy(carbs = newValue)
                MacroField.FAT      -> withSnapshot.copy(fat = newValue)
                MacroField.FIBER    -> withSnapshot.copy(fiber = newValue)
            }
            repo.updateEntry(updated)
        }
    }

    /** Entfernt den globalen Override und stellt die automatisch berechneten
     *  (Original-)Werte wieder her. */
    fun clearGlobalOverride(entry: DiaryEntry) {
        if (!entry.isGloballyOverridden) return
        viewModelScope.launch {
            repo.updateEntry(entry.copy(
                isGloballyOverridden = false,
                calories = entry.originalCalories ?: entry.calories,
                protein  = entry.originalProtein  ?: entry.protein,
                carbs    = entry.originalCarbs    ?: entry.carbs,
                fat      = entry.originalFat      ?: entry.fat,
                fiber    = entry.originalFiber    ?: entry.fiber,
                originalCalories = null,
                originalProtein  = null,
                originalCarbs    = null,
                originalFat      = null,
                originalFiber    = null
            ))
        }
    }

    fun deleteEntry(entry: DiaryEntry) = viewModelScope.launch { repo.deleteEntry(entry) }
    fun restoreEntry(entry: DiaryEntry) = viewModelScope.launch { repo.restoreEntry(entry) }

    /** Reihenfolge innerhalb einer Mahlzeit nach Drag-Reorder persistieren. */
    fun reorderEntries(orderedIds: List<Long>) = viewModelScope.launch { repo.updateSortOrder(orderedIds) }

    // Eintrag-Detail (Mikronaehrstoffe), analog Yazio: Antippen eines Tagebuch-
    // Eintrags laedt das zugehoerige FoodItem fuer die volle Naehrwerttabelle.
    // Bei Rezepten wird zusaetzlich das Recipe fuer die Zutatenliste geladen.
    private val _entryDetailFood = MutableStateFlow<FoodItem?>(null)
    val entryDetailFood: StateFlow<FoodItem?> = _entryDetailFood

    private val _entryDetailRecipe = MutableStateFlow<ch.nutrisnap.app.data.model.Recipe?>(null)
    val entryDetailRecipe: StateFlow<ch.nutrisnap.app.data.model.Recipe?> = _entryDetailRecipe

    fun loadEntryDetail(entry: DiaryEntry) {
        _entryDetailFood.value = null
        _entryDetailRecipe.value = null
        viewModelScope.launch {
            when {
                entry.isFoodEntry ->
                    _entryDetailFood.value = foodRepo.getById(entry.foodItemId)
                entry.isRecipeEntry -> {
                    val recipeId = (-entry.foodItemId).toLong()
                    _entryDetailRecipe.value = recipeRepo.getById(recipeId)
                }
            }
        }
    }
    fun clearEntryDetail() {
        _entryDetailFood.value = null
        _entryDetailRecipe.value = null
    }

    fun saveCustomFood(item: FoodItem) = viewModelScope.launch { foodRepo.saveCustomFood(item) }

    // ── Wochen-Autopilot (Mo–Fr Vorlagen) ─────────────────────────────────────
    private val templateRepo = ch.nutrisnap.app.data.repository.MealTemplateRepository(db.mealTemplateDao())

    val autopilotTemplates: StateFlow<List<MealTemplate>> = combine(
        templateRepo.getAll(),
        getApplication<Application>().notifDataStore.data
    ) { templates, prefs ->
        val ids = prefs[ch.nutrisnap.app.ui.screens.mealtemplate.KEY_AUTOPILOT_TEMPLATE_IDS]
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .toSet()
        templates.filter { it.id in ids }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Alle Items einer Autopilot-Vorlage als manuelle Tagebuch-Einträge anlegen. */
    fun applyAutopilotTemplate(template: MealTemplate, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val items = templateRepo.getItems(template.id)
            if (items.isEmpty()) {
                onDone(0)
                return@launch
            }
            for (item in items) {
                repo.addManualEntry(
                    name = item.foodName,
                    kcal = item.calories,
                    protein = item.protein,
                    carbs = item.carbs,
                    fat = item.fat,
                    mealType = template.mealType,
                    date = _date.value
                )
            }
            onDone(items.size)
        }
    }
}
