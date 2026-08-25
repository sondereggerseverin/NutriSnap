package ch.nutrisnap.app.data.api

import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * NEU: USDA FoodData Central API Integration.
 *
 * SETUP:
 *  1. Kostenloser API-Key unter: https://fdc.nal.usda.gov/api-key-signup
 *  2. Key in local.properties eintragen: USDA_API_KEY=dein_key_hier
 *  3. In build.gradle.kts:
 *     buildConfigField("String", "USDA_API_KEY", localProperties["USDA_API_KEY"].toString())
 *
 * Stärke: Sehr gute Abdeckung generischer Lebensmittel (Banane, Hähnchenbrust, etc.)
 */
class UsdaFoodApi(private val apiKey: String) {

    companion object {
        private const val BASE_URL = "https://api.nal.usda.gov/fdc/v1"
    }

    /**
     * Sucht Lebensmittel per Textsuche.
     * Gibt bis zu 25 Ergebnisse zurück, sortiert nach Relevanz.
     */
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

    /**
     * Lookup per FDC-ID (z.B. nach Barcode-Treffer in anderem System).
     */
    suspend fun getById(fdcId: String): FoodItem? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/food/$fdcId?api_key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode != 200) return@withContext null

            val response = connection.inputStream.bufferedReader().readText()
            parseSingleFood(JSONObject(response))
        } catch (e: Exception) {
            null
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
            val name = obj.optString("description", "") .ifEmpty { return null }
            val nutrients = obj.optJSONArray("foodNutrients") ?: return null

            // USDA Nutrient IDs: 208=Energie, 203=Protein, 205=Kohlenhydrate, 204=Fett,
            // 291=Ballaststoffe, 307=Natrium, 269=Gesamtzucker, 606=gesaettigtes Fett,
            // 306=Kalium, 539/1235=zugesetzter Zucker (je nach Datenrelease unterschiedliche ID).
            // Nullable statt var-Float-mit-0-Default: ein Nutrient, der im foodNutrients-Array
            // schlicht fehlt, darf nicht als "0" interpretiert werden.
            var calories: Float? = null; var protein: Float? = null; var carbs: Float? = null; var fat: Float? = null
            var fiber: Float? = null; var sodium: Float? = null; var sugar: Float? = null
            var saturatedFat: Float? = null; var potassium: Float? = null; var addedSugars: Float? = null

            // Vitamine/Mineralstoffe, die USDA FoodData Central tatsaechlich liefert (Nutrient-IDs
            // gemaess offizieller USDA-Nutrientliste, https://fdc.nal.usda.gov). Jod und Biotin bewusst
            // NICHT gemappt: SR/FDC's regulaeres foodNutrients-Array deckt beide praktisch nicht ab
            // (Jod hat bei USDA eine separate Spezial-Datenbank, nicht Teil der Standard-Suche) - ein
            // Mapping wuerde nur scheinbare Vollstaendigkeit vortaeuschen.
            var vitaminA: Float? = null; var vitaminB1: Float? = null; var vitaminB2: Float? = null
            var vitaminB3: Float? = null; var vitaminB5: Float? = null; var vitaminB6: Float? = null
            var vitaminB11: Float? = null; var vitaminB12: Float? = null; var vitaminC: Float? = null
            var vitaminD: Float? = null; var vitaminE: Float? = null; var vitaminK: Float? = null
            var calcium: Float? = null; var iron: Float? = null; var magnesium: Float? = null
            var zinc: Float? = null; var phosphorus: Float? = null; var copper: Float? = null
            var manganese: Float? = null; var selenium: Float? = null

            for (i in 0 until nutrients.length()) {
                val n = nutrients.getJSONObject(i)
                val nutrientId = n.optInt("nutrientId", n.optJSONObject("nutrient")?.optInt("id") ?: 0)
                if (!n.has("value") && !n.has("amount")) continue
                val value = n.optDouble("value", n.optDouble("amount", Double.NaN)).toFloat()
                if (value.isNaN()) continue
                when (nutrientId) {
                    208 -> calories = value
                    203 -> protein = value
                    205 -> carbs = value
                    204 -> fat = value
                    291 -> fiber = value
                    // USDA liefert Natrium/Kalium in mg — intern speichern wir Gramm
                    // (MICRO_META rechnet für die Anzeige ×1000 zurück nach mg).
                    307 -> sodium = value / 1000f
                    269 -> sugar = value
                    606 -> saturatedFat = value
                    306 -> potassium = value / 1000f
                    539, 1235 -> addedSugars = value
                    // Mineralstoffe: USDA liefert mg (Selen: µg) — intern immer Gramm.
                    301 -> calcium = value / 1000f
                    303 -> iron = value / 1000f
                    304 -> magnesium = value / 1000f
                    305 -> phosphorus = value / 1000f
                    309 -> zinc = value / 1000f
                    312 -> copper = value / 1000f
                    315 -> manganese = value / 1000f
                    317 -> selenium = value / 1_000_000f
                    // Vitamine: USDA liefert mg fuer B1/B2/B3/B5/B6/C/E, µg fuer A(RAE)/B11/B12/D/K.
                    404 -> vitaminB1 = value / 1000f
                    405 -> vitaminB2 = value / 1000f
                    406 -> vitaminB3 = value / 1000f
                    410 -> vitaminB5 = value / 1000f
                    415 -> vitaminB6 = value / 1000f
                    401 -> vitaminC = value / 1000f
                    323 -> vitaminE = value / 1000f
                    320 -> vitaminA = value / 1_000_000f
                    417 -> vitaminB11 = value / 1_000_000f
                    418 -> vitaminB12 = value / 1_000_000f
                    328 -> vitaminD = value / 1_000_000f
                    430 -> vitaminK = value / 1_000_000f
                }
            }

            val completeness = listOf(calories, protein, carbs, fat, fiber, sodium, sugar)
                .count { (it ?: 0f) > 0 } * 14

            FoodItem(
                name = name.lowercase().replaceFirstChar { it.uppercase() },
                brand = obj.optString("brandOwner").ifEmpty { null },
                calories = calories,
                protein = protein,
                carbs = carbs,
                fat = fat,
                fiber = fiber,
                sugar = sugar,
                addedSugars = addedSugars,
                saturatedFat = saturatedFat,
                sodium = sodium,
                potassium = potassium,
                vitaminA = vitaminA,
                vitaminB1 = vitaminB1,
                vitaminB2 = vitaminB2,
                vitaminB3 = vitaminB3,
                vitaminB5 = vitaminB5,
                vitaminB6 = vitaminB6,
                vitaminB11 = vitaminB11,
                vitaminB12 = vitaminB12,
                vitaminC = vitaminC,
                vitaminD = vitaminD,
                vitaminE = vitaminE,
                vitaminK = vitaminK,
                calcium = calcium,
                iron = iron,
                magnesium = magnesium,
                zinc = zinc,
                phosphorus = phosphorus,
                copper = copper,
                manganese = manganese,
                selenium = selenium,
                source = FoodSource.USDA,
                completenessScore = completeness.coerceAtMost(100)
            )
        } catch (e: Exception) {
            null
        }
    }
}
