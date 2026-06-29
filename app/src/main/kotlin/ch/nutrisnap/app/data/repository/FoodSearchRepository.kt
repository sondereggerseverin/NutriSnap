package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.api.NutritionixApi
import ch.nutrisnap.app.data.api.SwissFoodApi
import ch.nutrisnap.app.data.api.UsdaFoodApi
import ch.nutrisnap.app.data.db.FoodItemDao
import ch.nutrisnap.app.data.model.FoodItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow

class FoodSearchRepository(
    private val foodItemDao: FoodItemDao,
    private val usdaApi: UsdaFoodApi,
    private val nutritionixApi: NutritionixApi,
    private val openFoodFactsSearch: suspend (String) -> List<FoodItem>
) {

    suspend fun search(query: String): List<FoodItem> {
        val cached = foodItemDao.searchFoods(query)
        if (cached.size >= 5) return cached.sortedByDescending { it.completenessScore }

        return coroutineScope {
            val offDeferred = async { runCatching { openFoodFactsSearch(query) }.getOrDefault(emptyList()) }
            val usdaDeferred = async { runCatching { usdaApi.search(query) }.getOrDefault(emptyList()) }
            val swissDeferred = async { runCatching { SwissFoodApi.search(query) }.getOrDefault(emptyList()) }

            val off = offDeferred.await()
            val usda = usdaDeferred.await()
            val swiss = swissDeferred.await()

            var combined = (cached + swiss + off + usda)

            if (combined.size < 5) {
                val nutritionix = runCatching { nutritionixApi.searchBranded(query) }.getOrDefault(emptyList())
                combined = combined + nutritionix
            }

            val result = combined
                .distinctBy { normalizeKey(it) }
                .sortedByDescending { it.completenessScore }

            foodItemDao.insertAll(result.take(20))
            result
        }
    }

    suspend fun searchNaturalLanguage(query: String): List<FoodItem> {
        return runCatching { nutritionixApi.parseNaturalLanguage(query) }.getOrDefault(emptyList())
    }

    suspend fun searchByBarcode(barcode: String): FoodItem? {
        return foodItemDao.searchByBarcode(barcode)
            ?: runCatching { SwissFoodApi.search("barcode:$barcode").firstOrNull() }.getOrNull()
            ?: runCatching { openFoodFactsSearch("barcode:$barcode").firstOrNull() }.getOrNull()
    }

    fun getRecentFoods(): Flow<List<FoodItem>> = foodItemDao.getRecentFoods()
    fun getFrequentFoods(): Flow<List<FoodItem>> = foodItemDao.getFrequentFoods()

    suspend fun incrementUsage(foodItem: FoodItem) {
        if (foodItem.id != 0) foodItemDao.incrementUsage(foodItem.id)
    }

    private fun normalizeKey(item: FoodItem): String =
        (item.barcode ?: item.name.lowercase().trim())
}
