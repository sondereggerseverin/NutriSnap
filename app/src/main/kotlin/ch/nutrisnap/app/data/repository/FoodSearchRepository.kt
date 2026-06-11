package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.OFFProduct
import ch.nutrisnap.app.data.model.OFFSearchResponse
import ch.nutrisnap.app.data.model.SingleProductResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class FoodSearchRepository {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "NutriSnap/1.1 (Android; ch.nutrisnap.app)")
                    .header("Accept", "application/json")
                    .build()
            )
        }
        .build()

    suspend fun searchByName(query: String): List<FoodItem> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://world.openfoodfacts.org/cgi/search.pl" +
                    "?search_terms=$encoded" +
                    "&search_simple=1" +
                    "&action=process" +
                    "&json=1" +
                    "&fields=product_name,brands,nutriments,image_front_small_url" +
                    "&page_size=25" +
                    "&sort_by=unique_scans_n"

            val raw = fetch(url)
            val resp = json.decodeFromString<OFFSearchResponse>(raw)
            resp.products.mapNotNull { it.toFoodItem() }
        }.getOrElse { emptyList() }
    }

    suspend fun searchByBarcode(barcode: String): FoodItem? = withContext(Dispatchers.IO) {
        runCatching {
            val raw  = fetch("https://world.openfoodfacts.org/api/v0/product/$barcode.json")
            val resp = json.decodeFromString<SingleProductResponse>(raw)
            if (resp.status != 1) return@runCatching null
            resp.product?.toFoodItem()
        }.getOrNull()
    }

    private fun OFFProduct.toFoodItem(): FoodItem? {
        val name = product_name?.trim()?.ifBlank { null } ?: return null
        val n    = nutriments ?: return null
        val kcal = n.kcalPer100g ?: return null
        return FoodItem(
            name            = name,
            brand           = brands?.split(",")?.firstOrNull()?.trim(),
            caloriesPer100g = kcal,
            proteinPer100g  = n.proteins100g ?: 0f,
            carbsPer100g    = n.carbs100g    ?: 0f,
            fatPer100g      = n.fat100g      ?: 0f,
            fiberPer100g    = n.fiber100g    ?: 0f,
            isCustom        = false
        )
    }

    private fun fetch(url: String): String {
        val req = Request.Builder().url(url).build()
        return client.newCall(req).execute().use { resp ->
            resp.body?.string() ?: throw Exception("Empty response")
        }
    }
}
