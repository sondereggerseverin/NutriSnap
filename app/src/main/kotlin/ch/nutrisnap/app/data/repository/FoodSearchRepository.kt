package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.api.NutritionixApi
import ch.nutrisnap.app.data.api.UsdaFoodApi
import ch.nutrisnap.app.data.db.FoodItemDao
import ch.nutrisnap.app.data.model.FoodItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * ERWEITERTES FoodSearchRepository.
 *
 * Implementiert eine API-Fallback-Kette:
 *   1. Lokaler Room-Cache  → schnell, offline
 *   2. OpenFoodFacts       → Markenprodukte, DACH-Markt (bereits vorhanden, hier integriert)
 *   3. USDA FoodData       → generische Lebensmittel (Banane, Hähnchen, etc.)
 *   4. Nutritionix         → Restaurants + Natural Language Parsing
 *
 * ANPASSUNG: Ersetze die bestehende FoodSearchRepository-Klasse durch diese.
 * Die bestehende OpenFoodFacts-Implementierung bleibt erhalten (openFoodFactsSearch-Parameter).
 */
class FoodSearchRepository(
    private val foodItemDao: FoodItemDao,
    private val usdaApi: UsdaFoodApi,
    private val nutritionixApi: NutritionixApi,
    private val openFoodFactsSearch: suspend (String) -> List<FoodItem>   // bestehende Impl übergeben
) {

    /**
     * Hauptsuchfunktion mit Fallback-Kette.
     * Gibt sofort lokale Ergebnisse zurück, fragt APIs parallel ab.
     */
    suspend fun search(query: String): List<FoodItem> {
        // 1. Lokaler Cache (sofort)
        val cached = foodItemDao.searchFoods(query)
        if (cached.size >= 5) return cached.sortedByDescending { it.completenessScore }

        // 2. APIs parallel abfragen
        return coroutineScope {
            val offDeferred = async { runCatching { openFoodFactsSearch(query) }.getOrDefault(emptyList()) }
            val usdaDeferred = async { runCatching { usdaApi.search(query) }.getOrDefault(emptyList()) }
            // Nutritionix nur wenn OFF+USDA wenig liefern (Rate-Limit schonen)
            val off = offDeferred.await()
            val usda = usdaDeferred.await()
            var combined = (cached + off + usda)

            if (combined.size < 5) {
                val nutritionix = runCatching { nutritionixApi.searchBranded(query) }.getOrDefault(emptyList())
                combined = combined + nutritionix
            }

            val result = combined
                .distinctBy { normalizeKey(it) }
                .sortedByDescending { it.completenessScore }

            // Top-Ergebnisse in lokalen Cache schreiben
            foodItemDao.insertAll(result.take(20))

            result
        }
    }

    /**
     * Natural Language Search: "2 Scheiben Toast mit Butter"
     * Delegiert direkt an Nutritionix NLP-Parser.
     */
    suspend fun searchNaturalLanguage(query: String): List<FoodItem> {
        return runCatching { nutritionixApi.parseNaturalLanguage(query) }.getOrDefault(emptyList())
    }

    /**
     * Barcode-Lookup: erst lokaler Cache, dann OpenFoodFacts.
     */
    suspend fun searchByBarcode(barcode: String): FoodItem? {
        return foodItemDao.searchByBarcode(barcode)
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
