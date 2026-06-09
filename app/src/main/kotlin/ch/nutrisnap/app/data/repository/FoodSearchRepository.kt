package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.OFFSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Queries the Open Food Facts API (free, no key needed).
 * Docs: https://wiki.openfoodfacts.org/API
 */
class FoodSearchRepository {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun searchByName(query: String): List<FoodItem> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://world.openfoodfacts.org/cgi/search.pl" +
                    "?search_terms=$encoded" +
                    "&search_simple=1" +
                    "&action=process" +
                    "&json=1" +
                    "&fields=product_name,brands,nutriments,image_url" +
                    "&page_size=20"

            val raw = fetch(url)
            val resp = json.decodeFromString<OFFSearchResponse>(raw)

            resp.products.mapNotNull { p ->
                val name = p.product_name?.trim()?.ifBlank { null } ?: return@mapNotNull null
                val n    = p.nutriments ?: return@mapNotNull null
                FoodItem(
                    name            = name,
                    brand           = p.brands?.split(",")?.firstOrNull()?.trim(),
                    caloriesPer100g = n.energy_kcal_100g ?: return@mapNotNull null,
                    proteinPer100g  = n.proteins_100g ?: 0f,
                    carbsPer100g    = n.carbohydrates_100g ?: 0f,
                    fatPer100g      = n.fat_100g ?: 0f,
                    fiberPer100g    = n.fiber_100g ?: 0f,
                    isCustom        = false
                )
            }
        }.getOrElse { emptyList() }
    }

    suspend fun searchByBarcode(barcode: String): FoodItem? = withContext(Dispatchers.IO) {
        runCatching {
            val raw  = fetch("https://world.openfoodfacts.org/api/v0/product/$barcode.json")
            // minimal inline parse for single product endpoint
            val json2 = Json { ignoreUnknownKeys = true; coerceInputValues = true }
            data class SingleResp(val status: Int = 0, val product: ch.nutrisnap.app.data.model.OFFProduct? = null)
            val resp  = json2.decodeFromString<SingleResp>(raw)
            if (resp.status != 1) return@runCatching null
            val p = resp.product ?: return@runCatching null
            val n = p.nutriments ?: return@runCatching null
            FoodItem(
                name            = p.product_name?.trim() ?: "Unbekannt",
                brand           = p.brands?.split(",")?.firstOrNull()?.trim(),
                caloriesPer100g = n.energy_kcal_100g ?: return@runCatching null,
                proteinPer100g  = n.proteins_100g ?: 0f,
                carbsPer100g    = n.carbohydrates_100g ?: 0f,
                fatPer100g      = n.fat_100g ?: 0f,
                fiberPer100g    = n.fiber_100g ?: 0f,
                isCustom        = false
            )
        }.getOrNull()
    }

    private fun fetch(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpsURLConnection
        conn.setRequestProperty("User-Agent", "NutriSnap/1.0 (Android; ch.nutrisnap.app)")
        conn.connectTimeout = 10_000
        conn.readTimeout    = 15_000
        return conn.inputStream.bufferedReader().readText()
    }
}
