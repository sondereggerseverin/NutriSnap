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

    /**
     * Nutritionix liefert neben den schmalen nf_*-Feldern (nur Makros) zusaetzlich ein
     * "full_nutrients"-Array mit denselben USDA-Nutrient-IDs (attr_id) wie FoodData Central
     * (Nutritionix' Daten sind USDA-basiert) - bisher komplett ignoriert, obwohl dort auch
     * Vitamine/Mineralstoffe drinstecken (Abdeckung schwankt je nach Food/Brand, aehnlich wie
     * bei OpenFoodFacts: nicht jede Marke meldet jeden Nutrienten).
     * attr_id 318 (Vitamin A, IE) bewusst NICHT gemappt - anders als 320 (RAE, µg) laesst sich
     * IE nicht ohne Kenntnis der Vitamin-A-Quelle (tierisch/pflanzlich) verlustfrei umrechnen.
     */
    private fun JSONObject.fullNutrients(): Map<Int, Float> {
        val arr = optJSONArray("full_nutrients") ?: return emptyMap()
        return buildMap {
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                val id = n.optInt("attr_id", -1)
                if (id < 0 || !n.has("value") || n.isNull("value")) continue
                val v = n.optDouble("value", Double.NaN).toFloat()
                if (!v.isNaN()) put(id, v)
            }
        }
    }

    private fun parseNutrientResponse(json: String): List<FoodItem> {
        return try {
            val foods = JSONObject(json).getJSONArray("foods")
            (0 until foods.length()).mapNotNull { i ->
                val f = foods.getJSONObject(i)
                // fn(id) liest einen Wert aus full_nutrients per USDA-attr_id und rechnet
                // mg/µg (Nutritionix-Konvention, identisch zu USDA) in Gramm um.
                val fn = f.fullNutrients()
                fun mg(id: Int) = fn[id]?.div(1000f)
                fun µg(id: Int) = fn[id]?.div(1_000_000f)
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
                    calcium = mg(301), iron = mg(303), magnesium = mg(304), zinc = mg(309),
                    phosphorus = mg(305), copper = mg(312), manganese = mg(315), selenium = µg(317),
                    vitaminB1 = mg(404), vitaminB2 = mg(405), vitaminB3 = mg(406), vitaminB5 = mg(410),
                    vitaminB6 = mg(415), vitaminC = mg(401), vitaminE = mg(323),
                    vitaminA = µg(320), vitaminB11 = µg(417), vitaminB12 = µg(418),
                    vitaminD = µg(328), vitaminK = µg(430),
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
