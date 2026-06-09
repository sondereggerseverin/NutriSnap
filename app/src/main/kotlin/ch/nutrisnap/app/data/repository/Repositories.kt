package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.*
import ch.nutrisnap.app.domain.RecipeScraper
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class DiaryRepository(db: NutriDatabase) {
    private val dao = db.diaryDao()
    private val foodDao = db.foodItemDao()

    fun getEntriesForDate(date: LocalDate): Flow<List<DiaryEntry>> =
        dao.getEntriesForDate(date.toString())

    fun getWeeklySummary(from: LocalDate): Flow<List<ch.nutrisnap.app.data.db.DailySummary>> =
        dao.getWeeklySummary(from.toString())

    suspend fun addEntry(food: FoodItem, amountGrams: Float, mealType: MealType, date: LocalDate): Long {
        val factor = amountGrams / 100f
        return dao.insert(
            DiaryEntry(
                foodItemId = food.id,
                foodName   = food.name + (food.brand?.let { " ($it)" } ?: ""),
                amountGrams = amountGrams,
                mealType   = mealType,
                dateStr    = date.toString(),
                calories   = food.caloriesPer100g * factor,
                protein    = food.proteinPer100g  * factor,
                carbs      = food.carbsPer100g    * factor,
                fat        = food.fatPer100g      * factor
            )
        )
    }

    suspend fun deleteEntry(entry: DiaryEntry) = dao.delete(entry)
}

class RecipeRepository(db: NutriDatabase) {
    private val dao     = db.recipeDao()
    private val scraper = RecipeScraper()

    fun getAll():               Flow<List<Recipe>> = dao.getAll()
    fun search(q: String):      Flow<List<Recipe>> = dao.search(q)

    suspend fun saveRecipe(recipe: Recipe): Long = dao.insert(recipe)
    suspend fun updateRecipe(recipe: Recipe)     = dao.update(recipe)
    suspend fun deleteRecipe(recipe: Recipe)     = dao.delete(recipe)
    suspend fun getById(id: Long)                = dao.getById(id)

    suspend fun importFromUrl(url: String): RecipeScrapeResult {
        val result = scraper.scrape(url)
        if (result.success && result.recipe != null) {
            val saved = result.recipe.copy(id = dao.insert(result.recipe))
            return result.copy(recipe = saved)
        }
        return result
    }
}

class FoodItemRepository(db: NutriDatabase) {
    private val dao    = db.foodItemDao()
    private val remote = FoodSearchRepository()

    fun getCustom(): Flow<List<FoodItem>> = dao.getAllCustom()

    suspend fun searchAll(query: String): List<FoodItem> {
        val local  = dao.search(query)
        val remote = remote.searchByName(query)
        // merge: local first, then remote (deduplicated by name)
        val names  = local.map { it.name.lowercase() }.toSet()
        return local + remote.filter { it.name.lowercase() !in names }
    }

    suspend fun searchBarcode(barcode: String): FoodItem? = remote.searchByBarcode(barcode)

    suspend fun saveCustomFood(item: FoodItem): Long = dao.insert(item)
    suspend fun deleteFood(item: FoodItem)           = dao.delete(item)
}
