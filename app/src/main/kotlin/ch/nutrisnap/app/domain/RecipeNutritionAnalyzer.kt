package ch.nutrisnap.app.domain

import ch.nutrisnap.app.BuildConfig
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Analyzes recipe ingredients by looking up macros for each line, in order:
 *  1. Curated local nutrition DB (covers ~200 common generic ingredients)
 *  2. OpenFoodFacts search (covers specific/branded products)
 *  3. AI estimate via Groq (covers anything neither source has — spice
 *     blends, regional ingredients, prepared products, typos, etc.)
 *
 * The AI step is a single batched call for ALL still-unmatched ingredients
 * (not one call per ingredient), so a 14-ingredient recipe with 3 unknown
 * items costs exactly one extra request, not three.
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
        val matched:  Boolean = false,
        /** True if this result came from the AI estimate step rather than a real DB. */
        val estimated: Boolean = false
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
        val totalCount:         Int,
        /** How many of the matched ingredients came from the AI estimate (vs. real DB data). */
        val estimatedCount:     Int = 0
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val aiClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"

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

    private fun isIngredientLine(line: String): Boolean {
        val s = line.trimStart('*', '-', '\u2022', '\u00b7', ' ').trim()
        if (s.isBlank() || s.length < 3) return false

        val lc = s.lowercase()

        // Skip obvious non-ingredients
        val skipPrefixes = listOf("schritt", "step", "zubereitung", "instructions", "method",
            "preparation", "storage", "aufbewahrung", "heating", "erhitzen",
            "note:", "hinweis", "tip:", "tipp:", "#", "http", "www.", "@",
            "comment", "kommentar", "dm me", "link in bio", "per serving", "pro portion",
            "gesamtnährwerte", "total nutrition", "kcal:", "kalorien:", "calories:",
            "fett:", "protein:", "kohlenhydrate:", "for the", "für die", "für den",
            "sauce:", "dressing:", "topping:", "marinade:", "das rezept")
        if (skipPrefixes.any { lc.startsWith(it) }) return false

        val hasDigit = s.any { it.isDigit() }
        val hasUnit = lc.contains(" g ") || lc.contains(" g,") || lc.endsWith(" g") ||
            lc.contains("ml") || lc.contains(" kg") || lc.contains(" l ") ||
            lc.contains("oz") || lc.contains("cup") || lc.contains("tsp") ||
            lc.contains("tbsp") || lc.contains(" tl") || lc.contains(" el") ||
            lc.contains("liter") || lc.contains("prise") || lc.contains("pinch")
        // Count-nouns that appear without a unit
        val hasCountNoun = lc.contains("zehe") || lc.contains("knoblauch") ||
            lc.contains("scheibe") || lc.contains("zweig") || lc.contains("stück") ||
            lc.contains("clove") || lc.contains("slice") || lc.contains("sprig")

        return hasDigit || hasUnit || hasCountNoun
    }

    fun parseIngredientLine(line: String): ParsedIngredient? {
        val clean = line.trimStart('*', '-', '\u2022', '\u00b7', ' ').trim()
        if (clean.isBlank() || clean.length < 2) return null

        val numRegex = Regex("""^(\d+(?:[.,/]\d+)?(?:\s+and\s+\d+/\d+)?)\s*""")
        val numMatch = numRegex.find(clean) ?: run {
            val lc = clean.lowercase()
            val amt = if (lc.contains("spray") || lc.contains("prise") || lc.contains("pinch")) 2f else 100f
            return ParsedIngredient(amt, clean.take(50))
        }

        val amount = parseNumber(numMatch.value.trim())
        val rest   = clean.removePrefix(numMatch.value).trim()

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
            val countKey = COUNT_WEIGHTS.keys.sortedByDescending { it.length }
                .firstOrNull { lc.contains(it) }
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

    /**
     * Simplifies an ingredient name for better database matching.
     * "600g Proteinpasta" -> ["proteinpasta", "protein pasta", "pasta"]
     * "veganes Hack (Erbse)" -> ["veganes hack erbse", "hack erbse", "hackfleisch"]
     */
    private fun simplifyForSearch(name: String): List<String> {
        val n = name.lowercase()
            .replace(Regex("""\(.*?\)"""), " ") // remove parentheses
            .replace(Regex("[^a-zäöüß0-9 ]"), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val queries = mutableListOf<String>()
        queries.add(n)

        // Remove common adjectives to find the base food
        val stripped = n
            .replace(Regex("""\b(veganes?|veganer?|vegan|fettarm|fettarme[rns]?|mager|light|frisch[er]*|gegart|gekocht|roh|gewürfelt[e]?|gehackt[e]?|getrocknet[e]?|optional|bio|low[- ]fat|high[- ]protein|protein)\b"""), "")
            .replace(Regex("""\s+"""), " ").trim()
        if (stripped != n && stripped.length >= 3) queries.add(stripped)

        // Try first meaningful word(s) if compound word
        val words = stripped.split(" ").filter { it.length >= 3 }
        if (words.size >= 2) queries.add(words.takeLast(1).joinToString(" ")) // last word often the actual food
        if (words.size >= 2) queries.add(words.take(2).joinToString(" "))

        return queries.distinct().filter { it.length >= 3 }
    }

    private fun searchOFF(query: String): FoodItem? {
        return runCatching {
            val searchQueries = simplifyForSearch(query)
            for (searchTerm in searchQueries) {
                val result = searchOFFSingle(searchTerm, query) ?: continue
                return result
            }
            null
        }.getOrNull()
    }

    private fun searchOFFSingle(searchTerm: String, originalName: String): FoodItem? {
        return runCatching {
            val encoded = java.net.URLEncoder.encode(searchTerm.take(50), "UTF-8")
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
                val name = p.optString("product_name", originalName).ifBlank { originalName }
                return FoodItem(
                    name     = name,
                    calories = kcal,
                    protein  = n.optDouble("proteins_100g", 0.0).toFloat(),
                    carbs    = n.optDouble("carbohydrates_100g", 0.0).toFloat(),
                    fat      = n.optDouble("fat_100g", 0.0).toFloat(),
                    source   = ch.nutrisnap.app.data.model.FoodSource.OPEN_FOOD_FACTS
                )
            }
            null
        }.getOrNull()
    }

    suspend fun analyze(recipe: Recipe): AnalysisResult {
        val lines = recipe.ingredients.lines()
            .map { it.trim() }
            .filter { isIngredientLine(it) }

        // ── Pass 1: local DB + OpenFoodFacts (parallel, per-ingredient) ───────────
        val firstPass = withContext(Dispatchers.IO) {
            coroutineScope {
                lines.map { line ->
                    async {
                        val parsed = parseIngredientLine(line)
                        if (parsed == null || parsed.name.isBlank() || parsed.name.length < 2) {
                            return@async IngredientResult(line, null, null)
                        }
                        val factor = parsed.amountG / 100f

                        // Try local DB with original name + simplified versions
                        val localSearchTerms = listOf(parsed.name) +
                            listOf(parsed.name.lowercase()
                                .replace(Regex("""\b(veganes?|veganer?|vegan|fettarm|fettarme[rns]?|mager|light|frisch[er]*|bio|protein|high[- ]protein|low[- ]fat)\b"""), "")
                                .replace(Regex("""\s+"""), " ").trim())
                        val local = localSearchTerms.firstNotNullOfOrNull { IngredientNutritionDatabase.lookup(it) }
                        if (local != null) {
                            return@async IngredientResult(
                                line     = line,
                                parsed   = parsed,
                                foodItem = FoodItem(
                                    name            = parsed.name,
                                    calories = local.calories,
                                    protein  = local.protein,
                                    carbs    = local.carbs,
                                    fat      = local.fat,
                                    fiber    = local.fiber,
                                    source   = ch.nutrisnap.app.data.model.FoodSource.OPEN_FOOD_FACTS
                                ),
                                calories = local.calories * factor,
                                protein  = local.protein  * factor,
                                carbs    = local.carbs    * factor,
                                fat      = local.fat      * factor,
                                matched  = true
                            )
                        }

                        val food = searchOFF(parsed.name)
                        if (food != null) {
                            IngredientResult(
                                line     = line,
                                parsed   = parsed,
                                foodItem = food,
                                calories = food.calories * factor,
                                protein  = food.protein  * factor,
                                carbs    = food.carbs    * factor,
                                fat      = food.fat      * factor,
                                matched  = true
                            )
                        } else {
                            IngredientResult(line, parsed, null)
                        }
                    }
                }.map { it.await() }
            }
        }

        // ── Pass 2: AI estimate for whatever is still unmatched ────────────────────
        val unmatched = firstPass.filter { !it.matched && it.parsed != null }
        val results: List<IngredientResult> = if (unmatched.isEmpty()) {
            firstPass
        } else {
            val apiKey = runCatching { BuildConfig.GROQ_API_KEY }.getOrElse { "" }
            val estimates = if (apiKey.isNotBlank()) {
                runCatching { estimateViaAi(unmatched.map { it.parsed!!.name }, apiKey) }.getOrNull()
            } else null

            if (estimates == null) {
                firstPass
            } else {
                firstPass.map { r ->
                    if (r.matched || r.parsed == null) return@map r
                    val est = estimates[r.parsed.name] ?: return@map r
                    val factor = r.parsed.amountG / 100f
                    r.copy(
                        foodItem = FoodItem(
                            name            = r.parsed.name,
                            calories = est.calories,
                            protein  = est.protein,
                            carbs    = est.carbs,
                            fat      = est.fat,
                            source   = ch.nutrisnap.app.data.model.FoodSource.OPEN_FOOD_FACTS
                        ),
                        calories  = est.calories * factor,
                        protein   = est.protein  * factor,
                        carbs     = est.carbs    * factor,
                        fat       = est.fat      * factor,
                        matched   = true,
                        estimated = true
                    )
                }
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
            totalCount         = results.size,
            estimatedCount     = results.count { it.estimated }
        )
    }

    // ── AI nutrition estimate ───────────────────────────────────────────────────

    private data class AiNutritionEntry(
        val calories: Float, val protein: Float, val carbs: Float, val fat: Float
    )

    /**
     * Asks Groq to estimate per-100g macros for a batch of ingredient names that
     * neither the local DB nor OpenFoodFacts could resolve. One request covers
     * the whole batch, returning a JSON object keyed by ingredient name so
     * results can be matched back up regardless of response order.
     */
    private fun estimateViaAi(names: List<String>, apiKey: String): Map<String, AiNutritionEntry>? {
        val distinctNames = names.distinct().take(25) // sane upper bound per recipe
        if (distinctNames.isEmpty()) return null

        val listText = distinctNames.joinToString("\n") { "- $it" }
        val systemPrompt = """
            You are a nutrition database. For each food/ingredient name given, return
            estimated nutrition values PER 100 GRAMS (raw/uncooked unless the name
            implies otherwise, e.g. "cooked rice"). Use standard reference values
            (USDA-style) — best estimate, not a real lookup, is fine.

            Respond with ONLY a JSON object, no markdown, no explanation, in this
            exact shape:
            {"items": [
              {"name": "<the exact name as given>", "calories": <kcal per 100g>, "protein": <g per 100g>, "carbs": <g per 100g>, "fat": <g per 100g>}
            ]}

            If a name is not a real food (e.g. "optional", "to taste", "garnish"),
            return zeros for all four values for that item — do not omit it.
        """.trimIndent()

        val payload = JSONObject().apply {
            put("model", "llama-3.1-8b-instant")
            put("temperature", 0.2)
            put("max_tokens", 1200)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", "Ingredients:\n$listText") })
            })
        }

        val req = Request.Builder()
            .url(GROQ_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val raw = aiClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string() ?: return null
        }

        val content = JSONObject(raw)
            .optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")
            ?: return null

        // Strip potential markdown code fences before parsing
        val jsonText = content.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        val items = JSONObject(jsonText).optJSONArray("items") ?: return null
        val map = mutableMapOf<String, AiNutritionEntry>()
        for (i in 0 until items.length()) {
            val obj  = items.getJSONObject(i)
            val name = obj.optString("name")
            if (name.isBlank()) continue
            map[name] = AiNutritionEntry(
                calories = obj.optDouble("calories", 0.0).toFloat(),
                protein  = obj.optDouble("protein", 0.0).toFloat(),
                carbs    = obj.optDouble("carbs", 0.0).toFloat(),
                fat      = obj.optDouble("fat", 0.0).toFloat()
            )
        }
        return map.ifEmpty { null }
    }
}
