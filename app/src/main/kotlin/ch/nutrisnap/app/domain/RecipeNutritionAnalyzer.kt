package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Analyzes recipe ingredients by searching OpenFoodFacts for each item
 * and computing total + per-serving macros.
 */
object RecipeNutritionAnalyzer {

    data class IngredientResult(
        val line:     String,
        val parsed:   ParsedIngredient?,
        val foodItem: FoodItem?,
        val calories: Float = 0f,
        val protein:  Float = 0f,
        val carbs:    Float = 0f,
        val fat:      Float = 0f,
        val matched:  Boolean = false
    )

    data class ParsedIngredient(
        val amountG: Float,
        val name:    String
    )

    data class AnalysisResult(
        val ingredients:        List<IngredientResult>,
        val totalCalories:      Float,
        val totalProtein:       Float,
        val totalCarbs:         Float,
        val totalFat:           Float,
        val caloriesPerServing: Float,
        val proteinPerServing:  Float,
        val carbsPerServing:    Float,
        val fatPerServing:      Float,
        val matchedCount:       Int,
        val totalCount:         Int
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val UNIT_TO_G = mapOf(
        "kg" to 1000f, "g" to 1f,
        "lb" to 453.6f, "lbs" to 453.6f, "oz" to 28.35f,
        "l" to 1000f, "ml" to 1f, "dl" to 100f,
        "cups" to 240f, "cup" to 240f,
        "tbsp" to 15f, "tbs" to 15f, "el" to 15f,
        "tsp" to 5f, "tl" to 5f
    )

    private val COUNT_WEIGHTS = mapOf(
        "eggs" to 55f, "egg" to 55f, "ei" to 55f, "eier" to 55f,
        "onion" to 110f, "zwiebel" to 110f, "zwiebeln" to 110f,
        "garlic" to 3f, "knoblauch" to 3f, "clove" to 3f,
        "lime" to 60f, "lemon" to 80f, "zitrone" to 80f,
        "tomato" to 120f, "tomate" to 120f,
        "potato" to 150f, "kartoffel" to 150f,
        "avocado" to 150f, "banana" to 120f, "banane" to 120f,
        "scharlotte" to 80f, "schalotte" to 80f, "knoblauchzehe" to 3f,
        "knoblauchzehen" to 3f
    )

    // Called from outside the withContext block to avoid non-local return issues
    private fun isIngredientLine(line: String): Boolean {
        val s = line.trimStart('*', '-', 'u2022', ' ').trim()
        if (s.isBlank() || s.length < 3) return false
        val hasDigit = s.any { it.isDigit() }
        val lc = s.lowercase()
        val hasUnit = lc.contains("g ") || lc.contains("ml") || lc.contains("kg") ||
            lc.contains("oz") || lc.contains("cup") || lc.contains("tsp") ||
            lc.contains("tbsp") || lc.endsWith("g") || lc.endsWith("ml") ||
            lc.contains(" tl") || lc.contains(" el") || lc.contains("liter") ||
            lc.contains("scharlotte") || lc.contains("knoblauch") || lc.contains("zehe")
        return hasDigit || hasUnit
    }

    fun parseIngredientLine(line: String): ParsedIngredient? {
        // Strip bullet/asterisk/dash prefixes
        val clean = line.trimStart('*', '-', '\u2022', '\u00b7', ' ').trim()
        if (clean.isBlank() || clean.length < 2) return null

        val numRegex = Regex("""^(\d+(?:[.,/]\d+)?(?:\s+and\s+\d+/\d+)?)\s*""")
        val numMatch = numRegex.find(clean) ?: return ParsedIngredient(100f, clean.take(50))

        val amount = parseNumber(numMatch.value.trim())
        val rest   = clean.removePrefix(numMatch.value).trim()

        // Find unit (longest match first)
        val unitMatch = UNIT_TO_G.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (unit, _) -> rest.lowercase().startsWith(unit) && (rest.length == unit.length || !rest[unit.length].isLetter()) }

        return if (unitMatch != null) {
            val amountG  = amount * unitMatch.value
            val foodName = rest.substring(unitMatch.key.length).trim().trimStart(',').trim()
                .replace(Regex("""\s*(,|;).*"""), "").take(50)
            ParsedIngredient(amountG.coerceAtLeast(1f), foodName.ifBlank { rest }.take(50))
        } else {
            val lc = rest.lowercase()
            val countKey = COUNT_WEIGHTS.keys.firstOrNull { lc.startsWith(it) }
            val gramWeight = if (countKey != null) amount * (COUNT_WEIGHTS[countKey] ?: 100f) else amount * 100f
            val foodName = rest.replace(Regex("""\s*(,|;).*"""), "").take(50)
            ParsedIngredient(gramWeight.coerceAtLeast(1f), foodName)
        }
    }

    private fun parseNumber(s: String): Float {
        val clean = s.trim()
        val mixed = Regex("""(\d+)\s+(?:and\s+)?(\d+)/(\d+)""").find(clean)
        if (mixed != null) {
            val whole = mixed.groupValues[1].toFloatOrNull() ?: 0f
            val num   = mixed.groupValues[2].toFloatOrNull() ?: 0f
            val den   = mixed.groupValues[3].toFloatOrNull() ?: 1f
            return whole + num / den
        }
        val frac = Regex("""(\d+)/(\d+)""").find(clean)
        if (frac != null) {
            val num = frac.groupValues[1].toFloatOrNull() ?: 0f
            val den = frac.groupValues[2].toFloatOrNull() ?: 1f
            return num / den
        }
        return clean.replace(',', '.').toFloatOrNull() ?: 1f
    }

    private fun searchOFF(query: String): FoodItem? {
        return runCatching {
            val encoded = java.net.URLEncoder.encode(query.take(40), "UTF-8")
            val url = "https://world.openfoodfacts.org/cgi/search.pl?search_terms=$encoded&search_simple=1&action=process&json=1&page_size=5&fields=product_name,brands,nutriments"
            val req = Request.Builder().url(url).header("User-Agent", "NutriSnap/1.0 (Android)").build()
            val body = client.newCall(req).execute().use { it.body?.string() ?: return null }
            val products = JSONObject(body).optJSONArray("products") ?: return null
            for (i in 0 until products.length()) {
                val p    = products.getJSONObject(i)
                val n    = p.optJSONObject("nutriments") ?: continue
                val kcal = (n.optDouble("energy-kcal_100g", -1.0).toFloat()
                    .takeIf { it > 0 } ?: n.optDouble("energy_kcal_100g", -1.0).toFloat()
                    .takeIf { it > 0 }) ?: continue
                val name = p.optString("product_name", query).ifBlank { query }
                return FoodItem(
                    name            = name,
                    caloriesPer100g = kcal,
                    proteinPer100g  = n.optDouble("proteins_100g", 0.0).toFloat(),
                    carbsPer100g    = n.optDouble("carbohydrates_100g", 0.0).toFloat(),
                    fatPer100g      = n.optDouble("fat_100g", 0.0).toFloat(),
                    isCustom        = false
                )
            }
            null
        }.getOrNull()
    }

    suspend fun analyze(recipe: Recipe): AnalysisResult {
        val lines = recipe.ingredients.lines()
            .map { it.trim() }
            .filter { isIngredientLine(it) }

        // coroutineScope provides the CoroutineScope needed for async{}
        val results = withContext(Dispatchers.IO) {
            coroutineScope {
                lines.map { line ->
                    async {
                        val parsed = parseIngredientLine(line)
                        if (parsed == null || parsed.name.isBlank() || parsed.name.length < 2) {
                            return@async IngredientResult(line, null, null)
                        }
                        val food = searchOFF(parsed.name)
                        if (food != null) {
                            val factor = parsed.amountG / 100f
                            IngredientResult(
                                line     = line,
                                parsed   = parsed,
                                foodItem = food,
                                calories = food.caloriesPer100g * factor,
                                protein  = food.proteinPer100g  * factor,
                                carbs    = food.carbsPer100g    * factor,
                                fat      = food.fatPer100g      * factor,
                                matched  = true
                            )
                        } else {
                            IngredientResult(line, parsed, null)
                        }
                    }
                }.map { it.await() }
            }
        }

        val servings = recipe.servings.coerceAtLeast(1).toFloat()
        val totCal   = results.sumOf { it.calories.toDouble() }.toFloat()
        val totProt  = results.sumOf { it.protein.toDouble() }.toFloat()
        val totCarb  = results.sumOf { it.carbs.toDouble() }.toFloat()
        val totFat   = results.sumOf { it.fat.toDouble() }.toFloat()

        return AnalysisResult(
            ingredients        = results,
            totalCalories      = totCal,
            totalProtein       = totProt,
            totalCarbs         = totCarb,
            totalFat           = totFat,
            caloriesPerServing = totCal  / servings,
            proteinPerServing  = totProt / servings,
            carbsPerServing    = totCarb / servings,
            fatPerServing      = totFat  / servings,
            matchedCount       = results.count { it.matched },
            totalCount         = results.size
        )
    }
}
