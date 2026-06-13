package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Analyzes recipe ingredients by:
 * 1. Parsing each ingredient line into (amount, unit, food name)
 * 2. Searching OpenFoodFacts for each food
 * 3. Calculating macros per ingredient
 * 4. Summing everything up per serving
 *
 * Returns an enriched Recipe with real calculated macros.
 */
object RecipeNutritionAnalyzer {

    data class IngredientResult(
        val line:       String,       // original line
        val parsed:     ParsedIngredient?,
        val foodItem:   FoodItem?,    // matched from OFD
        val calories:   Float = 0f,
        val protein:    Float = 0f,
        val carbs:      Float = 0f,
        val fat:        Float = 0f,
        val matched:    Boolean = false
    )

    data class ParsedIngredient(
        val amountG:  Float,          // normalized to grams
        val name:     String          // search term
    )

    data class AnalysisResult(
        val ingredients:       List<IngredientResult>,
        val totalCalories:     Float,
        val totalProtein:      Float,
        val totalCarbs:        Float,
        val totalFat:          Float,
        val caloriesPerServing: Float,
        val proteinPerServing:  Float,
        val carbsPerServing:    Float,
        val fatPerServing:      Float,
        val matchedCount:      Int,
        val totalCount:        Int
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ── Unit conversion to grams/ml ──────────────────────────────────────────

    private val UNIT_TO_G = mapOf(
        "kg" to 1000f, "g" to 1f, "mg" to 0.001f,
        "lb" to 453.6f, "lbs" to 453.6f, "oz" to 28.35f,
        "l" to 1000f, "ml" to 1f, "dl" to 100f,
        "cup" to 240f, "cups" to 240f,
        "tbsp" to 15f, "tbs" to 15f, "el" to 15f,
        "tsp" to 5f, "tl" to 5f,
        "fl oz" to 29.57f
    )

    // Average gram weights for countable items
    private val COUNT_WEIGHTS = mapOf(
        "egg" to 55f, "eggs" to 55f, "ei" to 55f, "eier" to 55f,
        "onion" to 110f, "zwiebel" to 110f, "zwiebeln" to 110f,
        "garlic" to 3f, "knoblauch" to 3f, "clove" to 3f,
        "lime" to 60f, "lemon" to 80f, "zitrone" to 80f,
        "tomato" to 120f, "tomate" to 120f,
        "potato" to 150f, "kartoffel" to 150f,
        "avocado" to 150f,
        "banana" to 120f, "banane" to 120f
    )

    // ── Ingredient line parser ────────────────────────────────────────────────

    fun parseIngredientLine(line: String): ParsedIngredient? {
        // Strip all common bullet/prefix chars including * used by some scrapers
        val clean = line.trimStart('•', '-', '*', '·', '–', '→', ' ').trim()
        if (clean.isBlank() || clean.length < 3) return null

        // Pattern: "1200g Chicken" | "2.5 Tsp Salt" | "1 and 1/2 Limes" | "3 large eggs"
        val numRegex = Regex("""^(\d+(?:[.,/]\d+)?(?:\s+and\s+\d+/\d+)?)\s*""")
        val numMatch = numRegex.find(clean) ?: return ParsedIngredient(100f, clean) // no number → assume 100g

        val numStr = numMatch.value.trim()
        val rest   = clean.removePrefix(numMatch.value).trim()

        // Parse fraction/mixed number
        val amount = parseNumber(numStr)

        // Find unit
        val unitMatch = UNIT_TO_G.entries
            .sortedByDescending { it.key.length } // longest first to avoid "g" matching "kg"
            .firstOrNull { (unit, _) -> rest.lowercase().startsWith(unit) }

        return if (unitMatch != null) {
            val amountG  = amount * unitMatch.value
            val foodName = rest.removePrefix(unitMatch.key)
                .removePrefix(unitMatch.key.uppercase())
                .trim().trimStart(',').trim()
                .replace(Regex("""\s*(,|;).*"""), "") // strip after comma
                .take(50)
            ParsedIngredient(amountG.coerceAtLeast(1f), foodName.ifBlank { rest })
        } else {
            // No unit → might be count ("3 eggs") or just "Olive Oil"
            val countKey = COUNT_WEIGHTS.keys.firstOrNull { rest.lowercase().startsWith(it) }
            val gramWeight = if (countKey != null) amount * (COUNT_WEIGHTS[countKey] ?: 100f) else amount * 100f
            val foodName = rest.replace(Regex("""\s*(,|;).*"""), "").take(50)
            ParsedIngredient(gramWeight.coerceAtLeast(1f), foodName)
        }
    }

    private fun parseNumber(s: String): Float {
        val clean = s.trim()
        // "1 and 1/2" or "1 1/2"
        val mixed = Regex("""(\d+)\s+(?:and\s+)?(\d+)/(\d+)""").find(clean)
        if (mixed != null) {
            val whole = mixed.groupValues[1].toFloatOrNull() ?: 0f
            val num   = mixed.groupValues[2].toFloatOrNull() ?: 0f
            val den   = mixed.groupValues[3].toFloatOrNull() ?: 1f
            return whole + num / den
        }
        // "3/4"
        val frac = Regex("""(\d+)/(\d+)""").find(clean)
        if (frac != null) {
            val num = frac.groupValues[1].toFloatOrNull() ?: 0f
            val den = frac.groupValues[2].toFloatOrNull() ?: 1f
            return num / den
        }
        return clean.replace(',', '.').toFloatOrNull() ?: 1f
    }

    // ── OpenFoodFacts search ──────────────────────────────────────────────────

    private fun searchOFF(query: String): FoodItem? {
        return runCatching {
            val encoded = java.net.URLEncoder.encode(query.take(40), "UTF-8")
            val url = "https://world.openfoodfacts.org/cgi/search.pl?search_terms=$encoded&search_simple=1&action=process&json=1&page_size=5&fields=product_name,brands,nutriments"
            val req = Request.Builder().url(url)
                .header("User-Agent", "NutriSnap/1.0 (Android)")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string() ?: return null }
            val products = JSONObject(body).optJSONArray("products") ?: return null

            for (i in 0 until products.length()) {
                val p  = products.getJSONObject(i)
                val n  = p.optJSONObject("nutriments") ?: continue
                val kcal = n.optDouble("energy-kcal_100g", -1.0).toFloat()
                    .takeIf { it > 0 } ?: n.optDouble("energy_kcal_100g", -1.0).toFloat()
                    .takeIf { it > 0 } ?: continue
                val prot  = n.optDouble("proteins_100g",      0.0).toFloat()
                val carbs = n.optDouble("carbohydrates_100g", 0.0).toFloat()
                val fat   = n.optDouble("fat_100g",           0.0).toFloat()
                val name  = p.optString("product_name", query).ifBlank { query }
                return FoodItem(
                    name            = name,
                    caloriesPer100g = kcal,
                    proteinPer100g  = prot,
                    carbsPer100g    = carbs,
                    fatPer100g      = fat,
                    isCustom        = false
                )
            }
            null
        }.getOrNull()
    }

    // ── Main analysis function ────────────────────────────────────────────────

    suspend fun analyze(recipe: Recipe): AnalysisResult = withContext(Dispatchers.IO) {
        val lines = recipe.ingredients.lines()
            .map { it.trim() }
            .filter { line ->
                if (line.isBlank()) return@filter false
                val stripped = line.trimStart('•', '-', '*', '·', '–', ' ').trim()
                // Skip pure section headers: no digits and no units
                val hasDigit = stripped.any { it.isDigit() }
                val hasUnit  = listOf("g","ml","kg","l","oz","cup","tsp","tbsp","tl","el")
                    .any { u -> stripped.lowercase().contains(u) }
                hasDigit || hasUnit || stripped.length < 4
            }

        // Parse all lines in parallel
        val results = lines.map { line ->
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
        }.awaitAll()

        val servings = recipe.servings.coerceAtLeast(1).toFloat()
        val totCal  = results.sumOf { it.calories.toDouble() }.toFloat()
        val totProt = results.sumOf { it.protein.toDouble() }.toFloat()
        val totCarb = results.sumOf { it.carbs.toDouble() }.toFloat()
        val totFat  = results.sumOf { it.fat.toDouble() }.toFloat()

        AnalysisResult(
            ingredients        = results,
            totalCalories      = totCal,
            totalProtein       = totProt,
            totalCarbs         = totCarb,
            totalFat           = totFat,
            caloriesPerServing  = totCal  / servings,
            proteinPerServing   = totProt / servings,
            carbsPerServing     = totCarb / servings,
            fatPerServing       = totFat  / servings,
            matchedCount       = results.count { it.matched },
            totalCount         = results.size
        )
    }
}
