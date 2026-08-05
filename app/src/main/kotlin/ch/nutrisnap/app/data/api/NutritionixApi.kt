package ch.nutrisnap.app.data.api

import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * NEU: Nutritionix API Integration.
 *
 * SETUP (kostenloser Account, 500 Anfragen/Tag reichen für Personal Use):
 *  1. Account unter: https://www.nutritionix.com/business/api
 *  2. In local.properties:
 *       NUTRITIONIX_APP_ID=deine_app_id
 *       NUTRITIONIX_API_KEY=dein_api_key
 *
 * STÄRKE: Natural Language Parsing ("2 Scheiben Vollkornbrot mit Butter")
 *         + Restaurant/Fast-Food-Daten (McDonalds, Subway, etc.)
 */
class NutritionixApi(
    private val appId: String,
    private val apiKey: String
) {

    companion object {
        private const val BASE_URL = "https://trackapi.nutritionix.com/v2"
    }

    /**
     * Natural Language Food Search.
     * Parst Freitexteingaben wie "250g Hähnchenbrust gebraten" oder "ein Apfel".
     */
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

    /**
     * Suche nach Branded Foods (Markenprodukte + Restaurants).
     */
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

    /** Liest ein Nutritionix-Feld als Float, oder null wenn nicht vorhanden/kein Zahlenwert.
     *  Vorherige Version nutzte optDouble(key) ohne Default (= NaN bei fehlendem Feld) und
     *  filterte nur "!= 0.0" - das liess NaN durch und speicherte es als kaputten Float statt
     *  null. Ausserdem wurden echte Nullwerte faelschlich zu null (siehe Docstring anderswo). */
    private fun JSONObject.g(key: String): Float? =
        if (has(key) && !isNull(key)) optDouble(key, Double.NaN).toFloat().takeIf { !it.isNaN() } else null

    private fun parseNutrientResponse(json: String): List<FoodItem> {
        return try {
            val foods = JSONObject(json).getJSONArray("foods")
            (0 until foods.length()).mapNotNull { i ->
                val f = foods.getJSONObject(i)
                FoodItem(
                    name = f.optString("food_name", "Unbekannt"),
                    brand = f.optString("brand_name").ifEmpty { null },
                    calories = f.g("nf_calories"),
                    protein = f.g("nf_protein"),
                    carbs = f.g("nf_total_carbohydrate"),
                    fat = f.g("nf_total_fat"),
                    fiber = f.g("nf_dietary_fiber"),
                    sugar = f.g("nf_sugars"),
                    addedSugars = f.g("nf_added_sugars"),
                    saturatedFat = f.g("nf_saturated_fat"),
                    // Nutritionix liefert Natrium/Kalium in mg → intern Gramm
                    sodium = f.g("nf_sodium")?.div(1000f),
                    potassium = f.g("nf_potassium")?.div(1000f),
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
                    calories = f.g("nf_calories"),
                    protein = null, carbs = null, fat = null, // werden beim Detail-Abruf befüllt, bis dahin unbekannt statt 0
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
