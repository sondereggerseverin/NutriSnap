package ch.nutrisnap.app.data.api

import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class NutritionixApi(
    private val appId: String,
    private val apiKey: String
) {

    companion object {
        private const val BASE_URL = "https://trackapi.nutritionix.com/v2"
    }

    suspend fun parseNaturalLanguage(query: String): List<FoodItem> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/natural/nutrients")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-app-id", appId)
                setRequestProperty("x-app-key", apiKey)
                doOutput = true
            }
            val body = JSONObject().put("query", query).toString()
            OutputStreamWriter(connection.outputStream).use { it.write(body) }

            if (connection.responseCode != 200) return@withContext emptyList()

            val response = connection.inputStream.bufferedReader().readText()
            parseNutrientResponse(response)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun searchBranded(query: String): List<FoodItem> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/search/instant?query=${java.net.URLEncoder.encode(query, "UTF-8")}&branded=true&self=false")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("x-app-id", appId)
                setRequestProperty("x-app-key", apiKey)
            }
            if (connection.responseCode != 200) return@withContext emptyList()
            val response = connection.inputStream.bufferedReader().readText()
            parseBrandedResponse(response)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseNutrientResponse(json: String): List<FoodItem> {
        return try {
            val foods = JSONObject(json).getJSONArray("foods")
            (0 until foods.length()).mapNotNull { i ->
                val f = foods.getJSONObject(i)
                FoodItem(
                    name = f.optString("food_name", "Unbekannt"),
                    brand = f.optString("brand_name").ifEmpty { null },
                    calories = f.optDouble("nf_calories", 0.0).toFloat(),
                    protein = f.optDouble("nf_protein", 0.0).toFloat(),
                    carbs = f.optDouble("nf_total_carbohydrate", 0.0).toFloat(),
                    fat = f.optDouble("nf_total_fat", 0.0).toFloat(),
                    fiber = f.optDouble("nf_dietary_fiber").takeIf { it != 0.0 }?.toFloat(),
                    sugar = f.optDouble("nf_sugars").takeIf { it != 0.0 }?.toFloat(),
                    saturatedFat = f.optDouble("nf_saturated_fat").takeIf { it != 0.0 }?.toFloat(),
                    sodium = f.optDouble("nf_sodium").takeIf { it != 0.0 }?.toFloat(),
                    potassium = f.optDouble("nf_potassium").takeIf { it != 0.0 }?.toFloat(),
                    servingSize = f.optDouble("serving_weight_grams", 100.0).toFloat(),
                    servingUnit = f.optString("serving_unit", "g"),
                    source = FoodSource.NUTRITIONIX,
                    completenessScore = 75
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseBrandedResponse(json: String): List<FoodItem> {
        return try {
            val branded = JSONObject(json).getJSONArray("branded")
            (0 until branded.length()).mapNotNull { i ->
                val f = branded.getJSONObject(i)
                FoodItem(
                    name = f.optString("food_name", ""),
                    brand = f.optString("brand_name").ifEmpty { null },
                    calories = f.optDouble("nf_calories", 0.0).toFloat(),
                    protein = 0f, carbs = 0f, fat = 0f,
                    servingSize = f.optDouble("serving_weight_grams", 100.0).toFloat(),
                    servingUnit = f.optString("serving_unit", "g"),
                    source = FoodSource.NUTRITIONIX,
                    completenessScore = 40
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
