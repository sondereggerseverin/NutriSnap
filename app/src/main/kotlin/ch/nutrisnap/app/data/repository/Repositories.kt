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
        runCatching { block() }.onFailure {
            // Vorher komplett stumm geschluckt -> jetzt sichtbar in Logcat, damit
            // Sync-Fehler (fehlende UNIQUE-Constraint, RLS-Policy, offline, ...) auffindbar sind.
            Log.e("NutriSync", "Push zu Supabase fehlgeschlagen: ${it.message}", it)
        }
    }
}

class DiaryRepository(db: NutriDatabase) {
    private val dao = db.diaryDao()

    fun getEntriesForDate(date: LocalDate): Flow<List<DiaryEntry>> =
        dao.getEntriesForDate(date.toString())

    fun getWeeklySummary(from: LocalDate): Flow<List<ch.nutrisnap.app.data.db.DailySummary>> =
        dao.getWeeklySummary(from.toString())

    suspend fun addEntry(food: FoodItem, amountGrams: Float, mealType: MealType, date: LocalDate): Long {
        val f = amountGrams / 100f
        val id = dao.insert(
            DiaryEntry(
                foodItemId  = food.id,
                foodName    = food.name + (food.brand?.let { " ($it)" } ?: ""),
                amountGrams = amountGrams,
                mealType    = mealType,
                dateStr     = date.toString(),
                calories    = food.calories * f,
                protein     = food.protein  * f,
                carbs       = food.carbs    * f,
                fat         = food.fat      * f
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
        date: LocalDate
    ): Long {
        val perServing  = recipe.servings.coerceAtLeast(1).toFloat()
        val calsPerServ = recipe.totalCalories?.let { it / perServing } ?: 0f
        val calories    = calsPerServ * servingsFactor
        val protein     = (recipe.proteinPerServing ?: 0f) * servingsFactor
        val carbs       = (recipe.carbsPerServing   ?: 0f) * servingsFactor
        val fat         = (recipe.fatPerServing     ?: 0f) * servingsFactor

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
                fat         = fat
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
        date: LocalDate
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
                fat         = fat
            )
        )
        dao.getById(id)?.let { entry -> pushSafely { SupabaseSync.upsertDiaryEntry(entry) } }
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

    suspend fun deleteAllEntries() = dao.deleteAll()

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

class FoodItemRepository(db: NutriDatabase) {
    private val dao = db.foodItemDao()

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
        val local      = dao.search(query)
        val remoteList = remoteRepo.search(query)
        val names      = local.map { it.name.lowercase() }.toSet()
        return local + remoteList.filter { it.name.lowercase() !in names }
    }

    suspend fun searchBarcode(barcode: String): FoodItem? = remoteRepo.searchByBarcode(barcode)
    suspend fun saveCustomFood(item: FoodItem): Long       = dao.insert(item)
    suspend fun deleteFood(item: FoodItem)                 = dao.delete(item)
}
