package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.repository.FoodSearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

// ── Data classes ──────────────────────────────────────────────────────────────

data class ParsedIngredient(
    val raw:        String,
    val amountG:    Float,      // best-effort grams (0 if unknown)
    val searchTerm: String      // cleaned name for API lookup
)

data class AnalyzedIngredient(
    val parsed:    ParsedIngredient,
    val foodItem:  FoodItem?,    // null = not found in OFF
    val calories:  Float,
    val protein:   Float,
    val carbs:     Float,
    val fat:       Float
) {
    val found get() = foodItem != null
}

data class RecipeNutritionResult(
    val ingredients:    List<AnalyzedIngredient>,
    val totalCalories:  Float,
    val totalProtein:   Float,
    val totalCarbs:     Float,
    val totalFat:       Float,
    val servings:       Int,
    val calsPerServing: Float get() = totalCalories / servings.coerceAtLeast(1),
    val protPerServing: Float get() = totalProtein  / servings.coerceAtLeast(1),
    val carbsPerServing:Float get() = totalCarbs    / servings.coerceAtLeast(1),
    val fatPerServing:  Float get() = totalFat      / servings.coerceAtLeast(1)
)

// ── Analyzer ──────────────────────────────────────────────────────────────────

class RecipeNutritionAnalyzer(
    private val remote: FoodSearchRepository = FoodSearchRepository()
) {

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun analyze(
        ingredientText: String,
        servings: Int = 1
    ): RecipeNutritionResult = withContext(Dispatchers.IO) {

        val lines = ingredientText
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 2 }
            // skip header lines like "Zutaten:" "For the sauce:"
            .filterNot { it.endsWith(":") && it.split(" ").size <= 4 }

        // Parse and fetch in parallel (max concurrency is bounded by OFF rate limits;
        // grouping by 5 is polite)
        val parsed = lines.map { parseIngredient(it) }
        val analyzed = parsed
            .chunked(5)
            .flatMap { chunk ->
                chunk.map { p ->
                    async { lookupIngredient(p) }
                }.awaitAll()
            }

        val totalCals = analyzed.sumOf { it.calories.toDouble() }.toFloat()
        val totalProt = analyzed.sumOf { it.protein.toDouble() }.toFloat()
        val totalCarb = analyzed.sumOf { it.carbs.toDouble() }.toFloat()
        val totalFat  = analyzed.sumOf { it.fat.toDouble() }.toFloat()

        RecipeNutritionResult(analyzed, totalCals, totalProt, totalCarb, totalFat, servings)
    }

    // ── Ingredient parser ─────────────────────────────────────────────────────

    fun parseIngredient(raw: String): ParsedIngredient {
        // Normalize bullet points
        val line = raw.trimStart('•', '-', '*', '–', '·', ' ')

        // Convert imperial → metric first
        val metricLine = convertToMetric(line)

        val amountG: Float
        val searchTerm: String

        // Try to extract amount + unit + name
        val m = AMOUNT_REGEX.find(metricLine)
        if (m != null) {
            val numStr = m.groupValues[1].replace(',', '.').replace('/', '÷')
            val num    = evalFraction(numStr)
            val unit   = m.groupValues[2].trim().lowercase()
            val name   = metricLine.substring(m.range.last + 1).trim()
                .trimStart(':', 'v', 'o', 'n', ' ')   // strip "von" in German
            amountG    = toGrams(num, unit, name)
            searchTerm = cleanName(name)
        } else {
            // No quantity found — treat whole line as search term, assume 100g
            amountG    = 100f
            searchTerm = cleanName(metricLine)
        }

        return ParsedIngredient(raw, amountG, searchTerm)
    }

    // ── OFF lookup ────────────────────────────────────────────────────────────

    private suspend fun lookupIngredient(p: ParsedIngredient): AnalyzedIngredient {
        if (p.searchTerm.isBlank() || p.amountG <= 0f) {
            return AnalyzedIngredient(p, null, 0f, 0f, 0f, 0f)
        }
        val results = runCatching { remote.searchByName(p.searchTerm) }.getOrElse { emptyList() }
        val best    = results.firstOrNull()

        return if (best == null) {
            AnalyzedIngredient(p, null, 0f, 0f, 0f, 0f)
        } else {
            val f = p.amountG / 100f
            AnalyzedIngredient(
                parsed   = p,
                foodItem = best,
                calories = best.caloriesPer100g * f,
                protein  = best.proteinPer100g  * f,
                carbs    = best.carbsPer100g    * f,
                fat      = best.fatPer100g      * f
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun evalFraction(s: String): Float {
        if (s.contains('÷')) {
            val parts = s.split('÷')
            return (parts[0].toFloatOrNull() ?: 1f) / (parts[1].toFloatOrNull() ?: 1f)
        }
        return s.toFloatOrNull() ?: 1f
    }

    /** Best-effort conversion of an amount + unit to grams */
    private fun toGrams(amount: Float, unit: String, name: String): Float = when {
        unit in listOf("g", "gr", "gramm", "gram", "grams") -> amount
        unit in listOf("kg", "kilogramm", "kilogram")        -> amount * 1000f
        unit in listOf("ml", "milliliter", "millilitre")     -> amount  // water density ≈ 1
        unit in listOf("l", "liter", "litre", "lt")          -> amount * 1000f
        unit in listOf("tl", "tsp", "teaspoon")              -> amount * 5f
        unit in listOf("el", "tbsp", "tablespoon", "esslöffel") -> amount * 15f
        unit in listOf("cup", "cups", "tasse")               -> amount * 240f
        unit in listOf("oz", "ounce", "ounces")              -> amount * 28.35f
        unit in listOf("lb", "lbs", "pound", "pounds")       -> amount * 453.6f
        unit == "stk" || unit == "stück" || unit == "pcs" || unit == "pc" || unit == "" ->
            estimateWeightFromName(name, amount)
        unit == "handvoll" || unit == "handful"              -> 30f * amount
        unit == "prise" || unit == "pinch"                   -> 2f * amount
        else -> amount * 15f   // fallback: assume tablespoon-ish
    }

    /** Rough per-item weight estimates for common ingredients */
    private fun estimateWeightFromName(name: String, count: Float): Float {
        val lower = name.lowercase()
        val perItem = when {
            "ei" in lower || "egg" in lower                         -> 55f
            "avocado" in lower                                      -> 170f
            "banana" in lower || "banane" in lower                  -> 120f
            "apple" in lower || "apfel" in lower                    -> 150f
            "potato" in lower || "kartoffel" in lower               -> 130f
            "sweet potato" in lower || "süsskartoffel" in lower     -> 150f
            "zucchini" in lower || "zucchetti" in lower             -> 220f
            "carrot" in lower || "karotte" in lower || "rüebli" in lower -> 80f
            "onion" in lower || "zwiebel" in lower                  -> 150f
            "garlic" in lower || "knoblauch" in lower               -> 4f
            "lemon" in lower || "limette" in lower || "zitrone" in lower -> 85f
            "lime" in lower                                         -> 67f
            "tomato" in lower || "tomate" in lower                  -> 120f
            "mushroom" in lower || "champignon" in lower            -> 20f
            "bell pepper" in lower || "peperoni" in lower || "paprika" in lower -> 160f
            "can" in lower || "dose" in lower                       -> 400f
            "pack" in lower || "packet" in lower || "packung" in lower -> 200f
            "filet" in lower || "brust" in lower || "breast" in lower -> 200f
            "scheibe" in lower || "slice" in lower                  -> 30f
            else                                                    -> 100f
        }
        return perItem * count
    }

    private fun cleanName(raw: String): String =
        raw
            .replace(Regex("\\(.*?\\)"), "")          // remove parentheses content
            .replace(Regex("[,;].*$"), "")             // cut after comma
            .replace(Regex("\\b(of|von|de|di|fresh|frisch|gehackt|chopped|minced|sliced|diced|gerieben|cooked)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(40)

    companion object {
        // Matches: "200g", "1.5 kg", "2 cups", "½ tsp", "1/2 EL", etc.
        private val AMOUNT_REGEX = Regex(
            """^([\d]+(?:[.,/][\d]+)?)\s*(g|gr|kg|ml|l|lt|tl|el|tbsp|tsp|cup|cups|tasse|oz|lb|lbs|stk|stück|pcs|pc|stk\.|handvoll|handful|prise|pinch|dose|can|packung|pack|packet|litre|liter|letre|gramm|gram|grams|kilogramm|teaspoon|tablespoon|esslöffel|milliliter|millilitre)?\b""",
            setOf(RegexOption.IGNORE_CASE)
        )

        private val CONVERSIONS = listOf(
            Regex("""(\d+(?:[.,]\d+)?)\s*(?:cups?|Cup)""") to { v: Double -> "${(v * 240).toInt()}ml" },
            Regex("""(\d+(?:[.,]\d+)?)\s*(?:tbsp|Tbsp|EL)""") to { v: Double -> "${(v * 15).toInt()}ml" },
            Regex("""(\d+(?:[.,]\d+)?)\s*(?:tsp|Tsp|TL)""") to { v: Double -> "${(v * 5).toInt()}ml" },
            Regex("""(\d+(?:[.,]\d+)?)\s*(?:oz|Oz)\b""") to { v: Double -> "${(v * 28.35).toInt()}g" },
            Regex("""(\d+(?:[.,]\d+)?)\s*(?:lbs?|Lbs?|pounds?)""") to { v: Double -> "${(v * 453.6).toInt()}g" },
            Regex("""(\d+(?:[.,]\d+)?)\s*fl\.?\s*oz""") to { v: Double -> "${(v * 29.57).toInt()}ml" }
        )

        fun convertToMetric(text: String): String {
            var result = text
            for ((pattern, fn) in CONVERSIONS) {
                result = pattern.replace(result) { mr ->
                    val v = mr.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@replace mr.value
                    fn(v)
                }
            }
            return result
        }
    }
}
