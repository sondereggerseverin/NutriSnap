package ch.nutrisnap.app.data.repository

import android.content.Context
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.*
import ch.nutrisnap.app.domain.RecipeScraper
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class DiaryRepository(db: NutriDatabase) {
    private val dao = db.diaryDao()

    fun getEntriesForDate(date: LocalDate): Flow<List<DiaryEntry>> =
        dao.getEntriesForDate(date.toString())

    fun getWeeklySummary(from: LocalDate): Flow<List<ch.nutrisnap.app.data.db.DailySummary>> =
        dao.getWeeklySummary(from.toString())

    suspend fun addEntry(food: FoodItem, amountGrams: Float, mealType: MealType, date: LocalDate): Long {
        val f = amountGrams / 100f
        return dao.insert(
            DiaryEntry(
                foodItemId  = food.id,
                foodName    = food.name + (food.brand?.let { " ($it)" } ?: ""),
                amountGrams = amountGrams,
                mealType    = mealType,
                dateStr     = date.toString(),
                calories    = food.caloriesPer100g * f,
                protein     = food.proteinPer100g  * f,
                carbs       = food.carbsPer100g    * f,
                fat         = food.fatPer100g      * f
            )
        )
    }

    suspend fun deleteEntry(entry: DiaryEntry) = dao.delete(entry)
}

class RecipeRepository(db: NutriDatabase, context: Context) {
    private val dao     = db.recipeDao()
    private val scraper = RecipeScraper(context)   // Context passed for WebView

    fun getAll():           Flow<List<Recipe>> = dao.getAll()
    fun search(q: String):  Flow<List<Recipe>> = dao.search(q)

    suspend fun saveRecipe(r: Recipe): Long  = dao.insert(r)
    suspend fun updateRecipe(r: Recipe)      = dao.update(r)
    suspend fun deleteRecipe(r: Recipe)      = dao.delete(r)
    suspend fun getById(id: Long)            = dao.getById(id)

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
        if (query.all { it.isDigit() } && query.length in 8..14) {
            val barcodeResult = remote.searchByBarcode(query)
            if (barcodeResult != null) return listOf(barcodeResult)
        }
        val local  = dao.search(query)
        val remote = remote.searchByName(query)
        val names  = local.map { it.name.lowercase() }.toSet()
        return local + remote.filter { it.name.lowercase() !in names }
    }

    suspend fun searchBarcode(barcode: String): FoodItem? = remote.searchByBarcode(barcode)
    suspend fun saveCustomFood(item: FoodItem): Long      = dao.insert(item)
    suspend fun deleteFood(item: FoodItem)                = dao.delete(item)
}
