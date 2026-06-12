package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.*
import ch.nutrisnap.app.domain.RecipeScraper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class DiaryRepository(db: NutriDatabase) {
    private val dao = db.diaryDao()

    fun getEntriesForDate(date: LocalDate): Flow<List<DiaryEntry>> =
        dao.getEntriesForDate(date.toString())

    fun getWeeklySummary(from: LocalDate): Flow<List<ch.nutrisnap.app.data.db.DailySummary>> =
        dao.getWeeklySummary(from.toString())

    suspend fun addEntry(food: FoodItem, amountGrams: Float, mealType: MealType, date: LocalDate): Long {
        val factor = amountGrams / 100f
        return dao.insert(
            DiaryEntry(
                foodItemId  = food.id,
                foodName    = food.name + (food.brand?.let { " ($it)" } ?: ""),
                amountGrams = amountGrams,
                mealType    = mealType,
                dateStr     = date.toString(),
                calories    = food.caloriesPer100g * factor,
                protein     = food.proteinPer100g  * factor,
                carbs       = food.carbsPer100g    * factor,
                fat         = food.fatPer100g      * factor
            )
        )
    }

    /** Add every ingredient line of a recipe as a single combined diary entry */
    suspend fun addRecipeAsMeal(
        recipe: Recipe,
        servingsFactor: Float,
        mealType: MealType,
        date: LocalDate
    ): Long {
        val totalCals = (recipe.totalCalories ?: 0f) * servingsFactor
        // Rough macro split: 50% carbs, 30% protein, 20% fat by default if unknown
        val protein = totalCals * 0.30f / 4f
        val carbs   = totalCals * 0.50f / 4f
        val fat     = totalCals * 0.20f / 9f
        return dao.insert(
            DiaryEntry(
                foodItemId  = 0L,
                foodName    = recipe.title + if (servingsFactor != 1f) " (×%.1f)".format(servingsFactor) else "",
                amountGrams = 0f,
                mealType    = mealType,
                dateStr     = date.toString(),
                calories    = totalCals,
                protein     = protein,
                carbs       = carbs,
                fat         = fat
            )
        )
    }

    suspend fun updateEntry(entry: DiaryEntry) = dao.update(entry)

    suspend fun deleteEntry(entry: DiaryEntry) = dao.delete(entry)
}

class RecipeRepository(db: NutriDatabase, private val context: android.content.Context) {
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
        val names  = local.map { it.name.lowercase() }.toSet()
        return local + remote.filter { it.name.lowercase() !in names }
    }

    suspend fun searchBarcode(barcode: String): FoodItem? = remote.searchByBarcode(barcode)

    suspend fun saveCustomFood(item: FoodItem): Long = dao.insert(item)
    suspend fun deleteFood(item: FoodItem)           = dao.delete(item)
}

class UserProfileRepository(db: NutriDatabase) {
    private val dao = db.userProfileDao()

    fun get(): Flow<UserProfile> = dao.get().map { it ?: UserProfile() }

    suspend fun save(profile: UserProfile) = dao.save(profile)

    suspend fun getCurrent(): UserProfile = dao.getCurrent() ?: UserProfile()
}
