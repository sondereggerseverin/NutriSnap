package ch.nutrisnap.app.data.api

import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Lightweight OpenFoodFacts name-search helper.
 * Used as the `openFoodFactsSearch` lambda in [FoodSearchRepository].
 */
object OpenFoodFactsApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Searches OpenFoodFacts by product name and returns up to [limit] FoodItems.
     * For barcode lookups, pass "barcode:<code>" — the function detects this prefix
     * and hits the product lookup endpoint instead.
     */
    suspend fun search(query: String, limit: Int = 10): List<FoodItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Barcode lookup
                if (query.startsWith("barcode:")) {
                    val code = query.removePrefix("barcode:")
                    val url = "https://world.openfoodfacts.org/api/v0/product/$code.json?fields=product_name,brands,nutriments,code"
                    val req = Request.Builder().url(url)
                        .header("User-Agent", "NutriSnap/1.0 (Android)").build()
                    val body = client.newCall(req).execute().use { it.body?.string() ?: return@runCatching emptyList() }
                    val root = JSONObject(body)
                    if (root.optInt("status", 0) != 1) return@runCatching emptyList()
                    val p = root.optJSONObject("product") ?: return@runCatching emptyList()
                    productToFoodItem(p, barcode = code)?.let { listOf(it) } ?: emptyList()
                } else {
                    // Name search
                    val encoded = java.net.URLEncoder.encode(query.take(60), "UTF-8")
                    val url = "https://world.openfoodfacts.org/cgi/search.pl" +
                            "?search_terms=$encoded&search_simple=1&action=process&json=1" +
                            "&page_size=$limit&fields=product_name,brands,nutriments,code"
                    val req = Request.Builder().url(url)
                        .header("User-Agent", "NutriSnap/1.0 (Android)").build()
                    val body = client.newCall(req).execute().use { it.body?.string() ?: return@runCatching emptyList() }
                    val products = JSONObject(body).optJSONArray("products")
                        ?: return@runCatching emptyList()
                    (0 until products.length())
                        .mapNotNull { productToFoodItem(products.getJSONObject(it)) }
                }
            }.getOrDefault(emptyList())
        }

    private fun productToFoodItem(p: org.json.JSONObject, barcode: String? = null): FoodItem? {
        val n = p.optJSONObject("nutriments") ?: return null
        val kcal = (n.optDouble("energy-kcal_100g", -1.0).toFloat().takeIf { it > 0 }
            ?: n.optDouble("energy_kcal_100g", -1.0).toFloat().takeIf { it > 0 })
            ?: return null
        val name = p.optString("product_name", "").ifBlank { return null }
        return FoodItem(
            name              = name,
            brand             = p.optString("brands", "").ifBlank { null },
            barcode           = barcode ?: p.optString("code", "").ifBlank { null },
            calories          = kcal,
            protein           = n.optDouble("proteins_100g", 0.0).toFloat(),
            carbs             = n.optDouble("carbohydrates_100g", 0.0).toFloat(),
            fat               = n.optDouble("fat_100g", 0.0).toFloat(),
            fiber             = n.optDouble("fiber_100g", 0.0).toFloat().takeIf { it > 0 },
            source            = FoodSource.OPEN_FOOD_FACTS,
            completenessScore = 50
        )
    }
}
