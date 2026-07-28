package ch.nutrisnap.app.data.repository

import android.content.Context
import android.util.Log
import ch.nutrisnap.app.BuildConfig
import ch.nutrisnap.app.data.api.NutritionixApi
import ch.nutrisnap.app.data.api.OpenFoodFactsApi
import ch.nutrisnap.app.data.api.UsdaFoodApi
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.*
import ch.nutrisnap.app.data.supabase.SupabaseSync
import ch.nutrisnap.app.data.supabase.SyncStatusHolder
import ch.nutrisnap.app.domain.RecipeScraper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Fire-and-forget scope for pushing local changes to Supabase. A failed push
 *  (e.g. offline) never breaks the local save — it's caught and swallowed. */
private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private fun pushSafely(block: suspend () -> Unit) {
    syncScope.launch {
        SyncStatusHolder.opStarted()
        runCatching { block() }
            .onSuccess { SyncStatusHolder.opSucceeded() }
            .onFailure {
                // Vorher komplett stumm geschluckt -> jetzt sichtbar in Logcat UND im
                // SyncStatusHolder, damit Sync-Fehler (fehlende UNIQUE-Constraint,
                // RLS-Policy, offline, ...) auffindbar sind statt im Nirvana zu landen.
                Log.e("NutriSync", "Push zu Supabase fehlgeschlagen: ${it.message}", it)
                SyncStatusHolder.opFailed(it.message)
            }
    }
}

class DiaryRepository(db: NutriDatabase) {
    private val dao = db.diaryDao()

    fun getEntriesForDate(date: LocalDate): Flow<List<DiaryEntry>> =
        dao.getEntriesForDate(date.toString())

    fun getWeeklySummary(from: LocalDate): Flow<List<ch.nutrisnap.app.data.db.DailySummary>> =
        dao.getWeeklySummary(from.toString())

    /** Fuer Tages-/Wochen-/Monats-/Kalenderansicht in der Analyse: begrenzter Zeitraum. */
    fun getSummaryBetween(from: LocalDate, to: LocalDate): Flow<List<ch.nutrisnap.app.data.db.DailySummary>> =
        dao.getSummaryBetween(from.toString(), to.toString())

    /** Fuer Quick-Add: nach dem Insert den vollen Eintrag laden (fuer Undo-Snackbar). */
    suspend fun getById(id: Long): DiaryEntry? = dao.getById(id)

    suspend fun addEntry(food: FoodItem, amountGrams: Float, mealType: MealType, date: LocalDate): Long {
        val f = amountGrams / 100f
        // Ein Tagebuch-Eintrag braucht konkrete Zahlen (fuer Tagessummen/Ziele) - anders als
        // FoodItem selbst darf DiaryEntry keine unbekannten Makros haben. Das 0-Fallback passiert
        // deshalb bewusst genau hier, an der Erfassungsgrenze, statt still in der Datenquelle.
        val id = dao.insert(
            DiaryEntry(
                foodItemId  = food.id,
                foodName    = food.name + (food.brand?.let { " ($it)" } ?: ""),
                amountGrams = amountGrams,
                mealType    = mealType,
                dateStr     = date.toString(),
                calories    = (food.calories ?: 0f) * f,
                protein     = (food.protein  ?: 0f) * f,
                carbs       = (food.carbs    ?: 0f) * f,
                fat         = (food.fat      ?: 0f) * f,
                fiber       = (food.fiber ?: 0f) * f,
                sugar       = (food.sugar ?: 0f) * f,
                saturatedFat = (food.saturatedFat ?: 0f) * f,
                salt        = (food.salt ?: 0f) * f,
                sodium      = (food.sodium ?: 0f) * f
            )
        )
        dao.getById(id)?.let { entry -> pushSafely { SupabaseSync.upsertDiaryEntry(entry) } }
        return id
    }

    /**
     * Add a recipe as a diary entry.
     * amountGrams stores the servingsFactor (e.g. 1.0 = 1 portion, 2.0 = 2 portions)
     * so the edit dialog can show and modify portions correctly.
     * foodItemId = -(recipe.id) to mark as recipe-type entry.
     */
    suspend fun addRecipeAsMeal(
        recipe: Recipe,
        servingsFactor: Float,
        mealType: MealType,
        date: LocalDate,
        gramsAmount: Float? = null
    ): Long {
        val perServing  = recipe.servings.coerceAtLeast(1).toFloat()
        val calsPerServ = recipe.totalCalories?.let { it / perServing } ?: 0f
        val calories    = calsPerServ * servingsFactor
        val protein     = (recipe.proteinPerServing ?: 0f) * servingsFactor
        val carbs       = (recipe.carbsPerServing   ?: 0f) * servingsFactor
        val fat         = (recipe.fatPerServing     ?: 0f) * servingsFactor
        val fiber       = (recipe.fiberPerServing   ?: 0f) * servingsFactor
        val sugar       = (recipe.sugarPerServing   ?: 0f) * servingsFactor
        val saturatedFat = (recipe.saturatedFatPerServing ?: 0f) * servingsFactor
        val salt        = (recipe.saltPerServing    ?: 0f) * servingsFactor
        val sodium      = (recipe.sodiumPerServing  ?: 0f) * servingsFactor

        val id = dao.insert(
            DiaryEntry(
                foodItemId  = -(recipe.id.toInt()).coerceAtMost(-1), // negative = recipe entry
                foodName    = recipe.title,
                amountGrams = servingsFactor,   // stores portions, not grams
                mealType    = mealType,
                dateStr     = date.toString(),
                calories    = calories,
                protein     = protein,
                carbs       = carbs,
                fat         = fat,
                fiber       = fiber,
                sugar       = sugar,
                saturatedFat = saturatedFat,
                salt        = salt,
                sodium      = sodium,
                recipeGrams = gramsAmount
            )
        )
        dao.getById(id)?.let { entry -> pushSafely { SupabaseSync.upsertDiaryEntry(entry) } }
        return id
    }

    /**
     * Manual entry: user types name + kcal + optional macros directly.
     * foodItemId = -999 marks manual entries. amountGrams = 0 (no gram-based amount).
     */
    suspend fun addManualEntry(
        name: String,
        kcal: Float,
        protein: Float,
        carbs: Float,
        fat: Float,
        mealType: MealType,
        date: LocalDate,
        fiber: Float = 0f,
        sugar: Float = 0f,
        saturatedFat: Float = 0f,
        salt: Float = 0f,
        sodium: Float = 0f
    ): Long {
        val id = dao.insert(
            DiaryEntry(
                foodItemId  = -999,
                foodName    = name,
                amountGrams = 0f,
                mealType    = mealType,
                dateStr     = date.toString(),
                calories    = kcal,
                protein     = protein,
                carbs       = carbs,
                fat         = fat,
                fiber        = fiber,
                sugar        = sugar,
                saturatedFat = saturatedFat,
                salt         = salt,
                sodium       = sodium
            )
        )
        dao.getById(id)?.let { entry -> pushSafely { SupabaseSync.upsertDiaryEntry(entry) } }
        return id
    }

    /**
     * Generischer Insert-Pfad fuer Aufrufer ausserhalb dieses Repositories (z.B.
     * KI-Tagesplan in RecipeGeneratorViewModel), die einen fertigen DiaryEntry
     * (foodItemId bereits gesetzt, z.B. -999) direkt anlegen wollen. Stellt sicher,
     * dass JEDER Insert-Pfad ueber Supabase synct statt db.diaryDao().insert()
     * direkt aufzurufen und den Push zu umgehen.
     */
    suspend fun insertAndSync(entry: DiaryEntry): Long {
        val id = dao.insert(entry)
        dao.getById(id)?.let { saved -> pushSafely { SupabaseSync.upsertDiaryEntry(saved) } }
        return id
    }

    suspend fun updateEntry(entry: DiaryEntry) {
        dao.update(entry)
        pushSafely { SupabaseSync.upsertDiaryEntry(entry) }
    }

    suspend fun deleteEntry(entry: DiaryEntry) {
        dao.delete(entry)
        pushSafely { SupabaseSync.deleteDiaryEntry(entry.id) }
    }

    /** Für Undo nach Löschen: legt den Eintrag mit neuer ID erneut an. */
    suspend fun restoreEntry(entry: DiaryEntry): Long {
        val id = dao.insert(entry.copy(id = 0))
        pushSafely { SupabaseSync.upsertDiaryEntry(entry.copy(id = id)) }
        return id
    }

    suspend fun deleteAllEntries() = dao.deleteAll()

    /** Einmaliger Snapshot aller Tagebuch-Eintraege, u.a. fuer Dedup-Checks beim Bulk-Import. */
    suspend fun getAllEntriesOnce() = dao.getAllOnce()

    /**
     * Persistiert die manuell per Drag-Handle geänderte Reihenfolge innerhalb einer Mahlzeit.
     * orderedIds = Einträge in der neuen Anzeigereihenfolge (Index = neue sortOrder).
     * Kein Supabase-Sync nötig, da sortOrder rein lokale UI-Präferenz ist.
     */
    suspend fun updateSortOrder(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> dao.updateSortOrder(id, index) }
    }
}

class RecipeRepository(db: NutriDatabase, context: Context) {
    private val dao     = db.recipeDao()
    private val scraper = RecipeScraper(context)

    fun getAll():          Flow<List<Recipe>> = dao.getAll()
    fun search(q: String): Flow<List<Recipe>> = dao.search(q)

    suspend fun saveRecipe(r: Recipe): Long {
        val id = dao.insert(r)
        dao.getById(id)?.let { saved -> pushSafely { SupabaseSync.upsertRecipe(saved) } }
        return id
    }

    suspend fun updateRecipe(r: Recipe) {
        dao.update(r)
        pushSafely { SupabaseSync.upsertRecipe(r) }
    }

    suspend fun deleteRecipe(r: Recipe) {
        dao.delete(r)
        pushSafely { SupabaseSync.deleteRecipe(r.id) }
    }

    suspend fun getById(id: Long) = dao.getById(id)

    suspend fun importFromUrl(url: String): RecipeScrapeResult {
        val result = scraper.scrape(url)
        if (result.success && result.recipe != null) {
            val newId = dao.insert(result.recipe)
            val saved = result.recipe.copy(id = newId)
            pushSafely { SupabaseSync.upsertRecipe(saved) }
            return result.copy(recipe = saved)
        }
        return result
    }
}

/** Wandelt einen importierten Yazio-/Eigenprodukt-Eintrag in ein [FoodItem] fuer
 *  die Suchergebnisliste um. completenessScore = 90: verifizierte eigene Daten,
 *  vertrauenswuerdiger als generische OFF-Treffer (50), aber unter einem exakten
 *  Barcode-Treffer der Swiss-DB (85 fuer SwissFoodApi liegt sogar knapp darunter,
 *  da dies hier explizit selbst gepflegte/gegessene Produkte des Nutzers sind). */
private fun CustomFoodItem.toFoodItem(): FoodItem = FoodItem(
    name = name,
    brand = brand,
    barcode = barcode,
    calories = calories,
    protein = protein,
    carbs = carbs,
    fat = fat,
    fiber = fiber,
    servingSize = portionSizeG,
    source = FoodSource.MANUAL,
    completenessScore = 90
)

class FoodItemRepository(db: NutriDatabase) {
    private val dao = db.foodItemDao()
    private val customFoodDao = db.customFoodDao()

    /**
     * FoodSearchRepository wired with real API clients.
     * API keys are read from BuildConfig; empty strings are safe — both
     * UsdaFoodApi and NutritionixApi catch exceptions gracefully.
     */
    private val remoteRepo = FoodSearchRepository(
        foodItemDao         = dao,
        usdaApi             = UsdaFoodApi(apiKey = BuildConfig.USDA_API_KEY),
        nutritionixApi      = NutritionixApi(
            appId  = BuildConfig.NUTRITIONIX_APP_ID,
            apiKey = BuildConfig.NUTRITIONIX_API_KEY
        ),
        openFoodFactsSearch = { query -> OpenFoodFactsApi.search(query) }
    )

    fun getCustom(): Flow<List<FoodItem>> = dao.getAllCustom()

    suspend fun searchAll(query: String): List<FoodItem> {
        // Barcode shortcut: pure digit string 8–14 chars
        if (query.all { it.isDigit() } && query.length in 8..14) {
            val barcodeResult = remoteRepo.searchByBarcode(query)
            if (barcodeResult != null) return listOf(barcodeResult)
        }
        // BUG-FIX: dao.search() gecachte lokale API-Treffer koennen ueber mehrere Suchen
        // hinweg als echte Duplikate im Cache landen (insertAll nutzt OnConflictStrategy.
        // IGNORE auf einer autogenerierten ID, die bei jedem Insert neu vergeben wird -
        // "Konflikt" tritt also nie ein). distinctBy hier faengt das an der Anzeige ab,
        // ohne die Cache-Tabelle selbst migrieren zu muessen.
        val local  = dao.search(query).distinctBy { it.barcode ?: it.name.lowercase().trim() }
        // BUG-FIX: der importierte Yazio-/Eigenprodukt-Katalog (custom_foods, befuellt
        // ueber YazioImportViewModel.importBundledFoods) wurde bisher komplett ignoriert -
        // die Suche kannte nur food_items (API-Cache). Eigene Produkte wie "Sour Cream"
        // waren dadurch nie auffindbar, obwohl sie in der DB lagen.
        val custom = customFoodDao.searchOnce(query).map { it.toFoodItem() }
        val remoteList = remoteRepo.search(query)
        val names      = (local + custom).map { it.name.lowercase() }.toSet()
        val combined   = local + custom + remoteList.filter { it.name.lowercase() !in names }
        // local ist eine rohe DB-LIKE-Query ohne Relevanz-Reihenfolge — ohne diese
        // Sortierung landen zufällige lokale Cache-Treffer vor besser passenden
        // Remote-Ergebnissen (z.B. "Mini Chinois" vor "Apfel naturtrüb" bei Suche "apfel").
        return combined
            .distinctBy { it.barcode ?: (it.name.lowercase().trim() + "|" + (it.brand?.lowercase()?.trim() ?: "")) }
            .sortedWith(FoodSearchRepository.relevanceComparator(query))
    }

    suspend fun searchBarcode(barcode: String): FoodItem? = remoteRepo.searchByBarcode(barcode)
    suspend fun getById(id: Int): FoodItem?                = dao.getById(id)
    suspend fun saveCustomFood(item: FoodItem): Long       = dao.insert(item)
    suspend fun deleteFood(item: FoodItem)                 = dao.delete(item)
}

