package ch.nutrisnap.app.data.api

import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class UsdaFoodApi(private val apiKey: String) {

    companion object {
        private const val BASE_URL = "https://api.nal.usda.gov/fdc/v1"
    }

    suspend fun search(query: String): List<FoodItem> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("$BASE_URL/foods/search?query=$encodedQuery&dataType=SR%20Legacy,Foundation&pageSize=25&api_key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode != 200) return@withContext emptyList()

            val response = connection.inputStream.bufferedReader().readText()
            parseSearchResponse(response)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseSearchResponse(json: String): List<FoodItem> {
        return try {
            val root = JSONObject(json)
            val foods = root.getJSONArray("foods")
            (0 until foods.length()).mapNotNull { i ->
                parseSingleFood(foods.getJSONObject(i))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseSingleFood(obj: JSONObject): FoodItem? {
        return try {
            val name = obj.optString("description", "").ifEmpty { return null }
            val nutrients = obj.optJSONArray("foodNutrients") ?: return null

            var calories = 0f; var protein = 0f; var carbs = 0f; var fat = 0f
            var fiber: Float? = null; var sodium: Float? = null; var sugar: Float? = null
            var saturatedFat: Float? = null; var potassium: Float? = null

            for (i in 0 until nutrients.length()) {
                val n = nutrients.getJSONObject(i)
                val nutrientId = n.optInt("nutrientId", n.optJSONObject("nutrient")?.optInt("id") ?: 0)
                val value = n.optDouble("value", n.optDouble("amount", 0.0)).toFloat()
                when (nutrientId) {
                    208 -> calories = value
                    203 -> protein = value
                    205 -> carbs = value
                    204 -> fat = value
                    291 -> fiber = value
                    307 -> sodium = value
                    269 -> sugar = value
                    606 -> saturatedFat = value
                    306 -> potassium = value
                }
            }

            val completeness = listOf(calories, protein, carbs, fat, fiber, sodium, sugar)
                .count { it != null && (it as? Float ?: 0f) > 0 } * 14

            FoodItem(
                name = name.lowercase().replaceFirstChar { it.uppercase() },
                brand = obj.optString("brandOwner").ifEmpty { null },
                calories = calories,
                protein = protein,
                carbs = carbs,
                fat = fat,
                fiber = fiber,
                sugar = sugar,
                saturatedFat = saturatedFat,
                sodium = sodium,
                potassium = potassium,
                source = FoodSource.USDA,
                completenessScore = completeness.coerceAtMost(100)
            )
        } catch (e: Exception) {
            null
        }
    }
}
