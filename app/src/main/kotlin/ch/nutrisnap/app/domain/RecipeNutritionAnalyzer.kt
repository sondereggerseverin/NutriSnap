package ch.nutrisnap.app.domain

import ch.nutrisnap.app.BuildConfig
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.repository.GlobalIngredientDictionary
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
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
 *  2. Feature 2: globales Zutaten-Wörterbuch (frühere Treffer aus 1./3., app-weit gecacht)
 *  3. OpenFoodFacts search (covers specific/branded products)
 *  4. AI estimate via Groq (covers anything neither source has — spice
 *     blends, regional ingredients, prepared products, typos, etc.)
 *
 * The AI step is a single batched call for ALL still-unmatched ingredients
 * (not one call per ingredient), so a 14-ingredient recipe with 3 unknown
 * items costs exactly one extra request, not three.
 */
object RecipeNutritionAnalyzer {

    // Feature 2: per initGlobalDictionary() von der Application aus gesetzt (siehe
    // NutriSnapApplication.onCreate). Bleibt null, falls das nie aufgerufen wird — die
    // Analyse funktioniert dann exakt wie vorher, nur ohne den Cache-Vorteil.
    private var globalDictionary: GlobalIngredientDictionary? = null
    fun initGlobalDictionary(dictionary: GlobalIngredientDictionary) { globalDictionary = dictionary }

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
        val estimated: Boolean = false,
        /** Mikronaehrstoffe für diese Zutat, bereits auf die tatsächliche Menge
         *  skaliert (nicht pro 100g). Nur Werte, die die Quelle geliefert hat. */
        val micros: Map<String, Float> = emptyMap()
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
        val estimatedCount:     Int = 0,
        /** Summe aller Mikronaehrstoffe über alle Zutaten (absolut, nicht pro Portion). */
        val totalMicros:        Map<String, Float> = emptyMap(),
        /** False, wenn mindestens eine gematchte Zutat keine Ballaststoff-Angabe hatte
         *  (totalMicros["fiber"] ist dann eine Unterschätzung, nicht 0). */
        val fiberComplete:      Boolean = true
    )

    /** Alle nicht-null Mikronaehrstoffe eines FoodItem (pro 100g), skaliert auf [factor]. */
    private fun FoodItem.scaledMicros(factor: Float): Map<String, Float> = buildMap {
        fiber?.let { put("fiber", it * factor) }
        sugar?.let { put("sugar", it * factor) }
        saturatedFat?.let { put("saturatedFat", it * factor) }
        monoFat?.let { put("monoFat", it * factor) }
        polyFat?.let { put("polyFat", it * factor) }
        transFat?.let { put("transFat", it * factor) }
        salt?.let { put("salt", it * factor) }
        sodium?.let { put("sodium", it * factor) }
        alcohol?.let { put("alcohol", it * factor) }
        cholesterol?.let { put("cholesterol", it * factor) }
        water?.let { put("water", it * factor) }
        vitaminA?.let { put("vitaminA", it * factor) }
        vitaminB1?.let { put("vitaminB1", it * factor) }
        vitaminB2?.let { put("vitaminB2", it * factor) }
        vitaminB3?.let { put("vitaminB3", it * factor) }
        vitaminB5?.let { put("vitaminB5", it * factor) }
        vitaminB6?.let { put("vitaminB6", it * factor) }
        vitaminB7?.let { put("vitaminB7", it * factor) }
        vitaminB11?.let { put("vitaminB11", it * factor) }
        vitaminB12?.let { put("vitaminB12", it * factor) }
        vitaminC?.let { put("vitaminC", it * factor) }
        vitaminD?.let { put("vitaminD", it * factor) }
        vitaminE?.let { put("vitaminE", it * factor) }
        vitaminK?.let { put("vitaminK", it * factor) }
        potassium?.let { put("potassium", it * factor) }
        calcium?.let { put("calcium", it * factor) }
        iron?.let { put("iron", it * factor) }
        magnesium?.let { put("magnesium", it * factor) }
        zinc?.let { put("zinc", it * factor) }
        phosphorus?.let { put("phosphorus", it * factor) }
        copper?.let { put("copper", it * factor) }
        manganese?.let { put("manganese", it * factor) }
        fluoride?.let { put("fluoride", it * factor) }
        iodine?.let { put("iodine", it * factor) }
        selenium?.let { put("selenium", it * factor) }
        chromium?.let { put("chromium", it * factor) }
        molybdenum?.let { put("molybdenum", it * factor) }
        chloride?.let { put("chloride", it * factor) }
        choline?.let { put("choline", it * factor) }
        arsenic?.let { put("arsenic", it * factor) }
        boron?.let { put("boron", it * factor) }
        cobalt?.let { put("cobalt", it * factor) }
        rubidium?.let { put("rubidium", it * factor) }
        silicon?.let { put("silicon", it * factor) }
        sulfur?.let { put("sulfur", it * factor) }
        tin?.let { put("tin", it * factor) }
        vanadium?.let { put("vanadium", it * factor) }
    }

    private fun sumMicros(maps: List<Map<String, Float>>): Map<String, Float> =
        maps.fold(emptyMap()) { acc, m ->
            (acc.keys + m.keys).associateWith { (acc[it] ?: 0f) + (m[it] ?: 0f) }
        }

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
        "kilogram" to 1000f, "kilogramm" to 1000f,
        "gram" to 1f, "gramm" to 1f, "grams" to 1f,
        "lb" to 453.6f, "lbs" to 453.6f, "oz" to 28.35f,
        "l" to 1000f, "ml" to 1f, "dl" to 100f,
        "liter" to 1000f, "litre" to 1000f,
        "milliliter" to 1f, "millilitre" to 1f,
        "cups" to 240f, "cup" to 240f,
        "tbsp" to 15f, "tbs" to 15f, "el" to 15f,
        "tablespoon" to 15f, "tablespoons" to 15f, "esslöffel" to 15f,
        "tsp" to 5f, "tl" to 5f,
        "teaspoon" to 5f, "teaspoons" to 5f, "teelöffel" to 5f
    )

    // Units that describe a *count* of items rather than a weight/volume.
    // Used as a fallback when the exact item isn't in COUNT_WEIGHTS below —
    // intentionally a much smaller guess than the old blanket "* 100" fallback,
    // since "10 portion Raffaello" should land around 200g, not 1000g.
    private val GENERIC_PIECE_UNITS = setOf(
        "portion", "portionen", "stück", "stueck", "piece", "pieces", "scoop", "scoops",
        "packung", "pack", "pkg", "dose", "bund", "stange", "zehe", "scheibe", "scheiben"
    )

    private val COUNT_WEIGHTS = mapOf(
        "eggs" to 55f, "egg" to 55f, "ei" to 55f, "eier" to 55f,
        "onion" to 110f, "zwiebel" to 110f, "zwiebeln" to 110f,
        "garlic" to 3f, "knoblauch" to 3f, "clove" to 3f, "cloves" to 3f,
        "lime" to 60f, "lemon" to 80f, "zitrone" to 80f,
        "tomato" to 120f, "tomate" to 120f,
        "potato" to 150f, "kartoffel" to 150f,
        "avocado" to 150f, "banana" to 120f, "banane" to 120f,
        "scharlotte" to 80f, "schalotte" to 80f, "shallot" to 40f, "shallots" to 40f,
        "knoblauchzehe" to 3f, "knoblauchzehen" to 3f,
        // Hähnchenbrust: typisch ~150–200 g roh pro Stück
        "chicken breast" to 180f, "chicken breasts" to 180f,
        "hähnchenbrust" to 180f, "haehnchenbrust" to 180f,
        "hühnerbrust" to 180f, "brustfilet" to 180f,
        "breast" to 180f, "breasts" to 180f,
        "thigh" to 120f, "thighs" to 120f, "schenkel" to 120f,
        "stange" to 200f, "porree" to 200f, "lauch" to 200f,
        "packung" to 150f, "pack" to 150f, "dose" to 200f,
        "bund" to 50f, "scheibe" to 25f, "scheiben" to 25f
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
            "fett:", "protein:", "kohlenhydrate:", "for the", "für die", "für den", "für das",
            "für hähnchen", "für haehnchen", "für sauce", "für soße", "für sosse",
            "sauce:", "dressing:", "topping:", "marinade:", "das rezept",
            "ingredients", "zutaten", "served with", "serviert mit")
        if (skipPrefixes.any { lc.startsWith(it) }) return false

        // Filter pure macro lines: "292 kcal", "6g Fett", "11g KH", "47g Protein", "Fett: 56g"
        val isMacroLine = Regex("""^\d+\s*(?:kcal|kalorien|calories)${'$'}""").matches(s.trim()) ||
            Regex("""^\d+\s*g\s*(?:fett|fat|protein|eiweiss|eiweißs?|kh|kohlenhydrate?|carbs?)${'$'}""", RegexOption.IGNORE_CASE).matches(s.trim()) ||
            Regex("""^(?:fett|protein|kh|kohlenhydrate?|eiweiss|calories?|kcal)\s*[:=]\s*\d""", RegexOption.IGNORE_CASE).matches(s.trim()) ||
            Regex("""^\d+\s*(?:kcal|kalorien)""").matches(s.trim()) && s.length < 15
        if (isMacroLine) return false

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

    /**
     * Summiert die Gramm-Mengen aller Zutatenzeilen direkt aus dem rohen
     * Zutatentext — ohne DB-Abgleich oder AI-Aufruf. Wird genutzt, um eine
     * Gramm-Menge pro Portion sofort anzubieten (z.B. im "Ins Tagebuch"-Dialog),
     * auch bevor der Nutzer "Neu berechnen" ausgeführt hat.
     */
    fun estimateTotalGrams(ingredientsText: String): Float =
        ingredientsText.lines()
            .filter { isIngredientLine(it) }
            .mapNotNull { parseIngredientLine(it)?.amountG }
            .sum()

    private const val FRACTION_CHARS = "¼½¾⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"
    private val UNICODE_FRACTION_VALUES = mapOf(
        '¼' to 0.25f, '½' to 0.5f, '¾' to 0.75f,
        '⅓' to 0.33f, '⅔' to 0.67f,
        '⅕' to 0.2f, '⅖' to 0.4f, '⅗' to 0.6f, '⅘' to 0.8f,
        '⅙' to 0.17f, '⅚' to 0.83f,
        '⅛' to 0.13f, '⅜' to 0.38f, '⅝' to 0.63f, '⅞' to 0.88f
    )

    fun parseIngredientLine(line: String): ParsedIngredient? {
        var clean = line.trimStart('*', '-', '\u2022', '\u00b7', ' ').trim()
        if (clean.isBlank() || clean.length < 2) return null
        // Abschnittspräfix abschneiden: "Für die Sauce: 1 Schalotte" → "1 Schalotte"
        clean = clean.replace(
            Regex(
                """(?i)^(für\s+(die\s+|den\s+|das\s+)?|for\s+(the\s+)?)""" +
                    """(hähnchen|haehnchen|chicken|sauce|soße|sosse|marinade|dressing|""" +
                    """topping|teig|base|füllung|fuellung|beilage)""" +
                    """\s*[:：\-]\s*"""
            ),
            ""
        ).trim()
        if (clean.isBlank() || clean.length < 2) return null

        // "150-200 ml Wasser" / "150 – 200 ml" → Mittelwert + Einheit
        val rangeMatch = Regex(
            """^(\d+(?:[.,]\d+)?)\s*[-–—]\s*(\d+(?:[.,]\d+)?)\s*(g|kg|ml|l|el|tl|tbsp|tsp|cup|cups)?\b\s*(.*)$""",
            RegexOption.IGNORE_CASE
        ).find(clean)
        if (rangeMatch != null) {
            val a = rangeMatch.groupValues[1].replace(',', '.').toFloatOrNull() ?: 0f
            val b = rangeMatch.groupValues[2].replace(',', '.').toFloatOrNull() ?: 0f
            val avg = if (a > 0f && b > 0f) (a + b) / 2f else maxOf(a, b)
            val unit = rangeMatch.groupValues[3].lowercase()
            val name = rangeMatch.groupValues[4].trim().ifBlank { clean }.take(50)
            val mult = if (unit.isNotBlank()) (UNIT_TO_G[unit] ?: 1f) else 1f
            // Ohne Einheit und avg >= 20 → bereits Gramm/ml
            val amountG = if (unit.isBlank() && avg >= 20f) avg else avg * mult
            return ParsedIngredient(amountG.coerceAtLeast(1f), name)
        }

        // Menge am Anfang: "300 g Tagliatelle", "1 TL Salz", "½ cup flour"
        val numRegex = Regex(
            "^((?:\\d+\\s+)?[$FRACTION_CHARS]|\\d+(?:[.,/]\\d+)?(?:\\s+and\\s+\\d+/\\d+)?)\\s*"
        )
        val numMatch = numRegex.find(clean)
        if (numMatch == null) {
            // Menge am Ende: "Hähnchenbrust 350 g"
            val trailing = Regex(
                """^(.*?)\s+((?:\d+\s+)?[$FRACTION_CHARS]|\d+(?:[.,/]\d+)?)\s*(g|kg|ml|l|el|tl|tbsp|tsp)\b\s*$""",
                RegexOption.IGNORE_CASE
            ).find(clean)
            if (trailing != null) {
                val foodName = trailing.groupValues[1].trim()
                val amt = parseNumber(trailing.groupValues[2].trim())
                val unit = trailing.groupValues[3].lowercase()
                val mult = UNIT_TO_G[unit] ?: 1f
                return ParsedIngredient((amt * mult).coerceAtLeast(1f), foodName.take(50))
            }
            val lc = clean.lowercase()
            // Gewürze/Pulver ohne Menge: Prise (~2 g), nicht 50 g (sonst explodieren Makros)
            val amt = when {
                lc.contains("spray") || lc.contains("prise") || lc.contains("pinch") -> 2f
                Regex(
                    """\b(powder|pulver|gewürz|seasoning|paprika|cumin|oregano|cinnamon|zimt|""" +
                        """chili|curry|pfeffer|pepper|salt|salz|garlic powder|knoblauchpulver|""" +
                        """onion powder|mustard powder|cayenne|turmeric|kurkuma)\b"""
                ).containsMatchIn(lc) -> 3f
                else -> 50f
            }
            return ParsedIngredient(amt, clean.take(50))
        }

        val amount = parseNumber(numMatch.value.trim())
        val rest = clean.removePrefix(numMatch.value).trim()
        val restLc = rest.lowercase()

        // Einheit am Anfang von rest: "g Tagliatelle", "ml Milch"
        val unitMatch = UNIT_TO_G.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (unit, _) ->
                restLc == unit || restLc.startsWith("$unit ") || restLc.startsWith("$unit,") ||
                    restLc.startsWith("$unit.")
            }
        if (unitMatch != null) {
            val amountG = amount * unitMatch.value
            val foodName = rest.substring(unitMatch.key.length).trim().trimStart(',', '.').trim()
                .replace(Regex("""\s*(,|;).*"""), "").take(50)
            return ParsedIngredient(amountG.coerceAtLeast(1f), foodName.ifBlank { rest }.take(50))
        }

        // Einheit irgendwo früh im Rest (nach optionalem Müll): " - 200 ml Wasser" sollte
        // oben als Range greifen; hier z.B. "ml Wasser" falls amount schon abgetrennt
        val embeddedUnit = Regex(
            """^\s*(g|kg|ml|l|el|tl|tbsp|tsp|cup|cups)\b\s*(.*)$""",
            RegexOption.IGNORE_CASE
        ).find(rest)
        if (embeddedUnit != null) {
            val unit = embeddedUnit.groupValues[1].lowercase()
            val mult = UNIT_TO_G[unit] ?: 1f
            val foodName = embeddedUnit.groupValues[2].trim().take(50)
            return ParsedIngredient((amount * mult).coerceAtLeast(1f), foodName.ifBlank { rest }.take(50))
        }

        // Zähl-Einheiten: "1 Stange Porree", "2 Packung Frischkäse"
        val countKey = COUNT_WEIGHTS.keys.sortedByDescending { it.length }
            .firstOrNull { restLc.contains(it) }
        if (countKey != null) {
            val gramWeight = amount * (COUNT_WEIGHTS[countKey] ?: 100f)
            return ParsedIngredient(gramWeight.coerceAtLeast(1f), rest.take(50))
        }
        if (GENERIC_PIECE_UNITS.any { restLc.startsWith(it) || restLc.contains(" $it") }) {
            return ParsedIngredient((amount * 50f).coerceAtLeast(1f), rest.take(50))
        }

        // WICHTIG: kein amount*100 mehr!
        // "300 Tagliatelle" ohne "g" → 300 g annehmen wenn amount >= 20
        // "2 Eier" ohne Treffer → bescheidene Schätzung
        val gramWeight = when {
            amount >= 20f -> amount          // klar Gramm/ml ohne Einheit
            amount >= 5f -> amount * 20f     // z.B. "10 Nüsse" grob
            else -> amount * 50f             // 1–4 Stück ohne bekannte Bezeichnung
        }
        return ParsedIngredient(gramWeight.coerceAtLeast(1f), rest.take(50))
    }

    private fun parseNumber(s: String): Float {
        val clean = s.trim()
        val fractionChar = clean.lastOrNull { it in FRACTION_CHARS }
        if (fractionChar != null) {
            val wholePart = clean.dropLast(1).trim().replace(',', '.').toFloatOrNull() ?: 0f
            return wholePart + (UNICODE_FRACTION_VALUES[fractionChar] ?: 0f)
        }
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
            // Lokale Referenz (DE/CH-typische Werte) — OFF-Treffer, die stark abweichen, verwerfen
            val localRef = IngredientNutritionDatabase.lookup(originalName)
                ?: IngredientNutritionDatabase.lookup(searchTerm)

            val encoded = java.net.URLEncoder.encode(searchTerm.take(50), "UTF-8")
            val url = "https://world.openfoodfacts.org/cgi/search.pl?search_terms=$encoded" +
                "&search_simple=1&action=process&json=1&page_size=8" +
                "&fields=product_name,brands,nutriments,countries_tags"
            val req = Request.Builder().url(url).header("User-Agent", "NutriSnap/1.0 (Android)").build()
            val body = client.newCall(req).execute().use { it.body?.string() ?: return null }
            val products = JSONObject(body).optJSONArray("products") ?: return null
            for (i in 0 until products.length()) {
                val p    = products.getJSONObject(i)
                val n    = p.optJSONObject("nutriments") ?: continue
                val kcal = (n.optDouble("energy-kcal_100g", -1.0).toFloat()
                    .takeIf { it > 0 } ?: n.optDouble("energy_kcal_100g", -1.0).toFloat()
                    .takeIf { it > 0 }) ?: continue
                // Ausreißer-Check: z.B. Mais 433 kcal/100g statt ~86 → verwerfen
                if (localRef != null && localRef.calories > 0f) {
                    val ratio = kcal / localRef.calories
                    if (ratio > 2.2f || ratio < 0.4f) continue
                }
                val name = p.optString("product_name", originalName).ifBlank { originalName }
                fun g(key: String): Float? =
                    if (n.has(key) && !n.isNull(key)) n.optDouble(key, Double.NaN).toFloat().takeIf { !it.isNaN() } else null
                return FoodItem(
                    name     = name,
                    calories = kcal,
                    protein  = g("proteins_100g"),
                    carbs    = g("carbohydrates_100g"),
                    fat      = g("fat_100g"),
                    fiber    = g("fiber_100g") ?: g("fibers_100g"),
                    source   = ch.nutrisnap.app.data.model.FoodSource.OPEN_FOOD_FACTS
                )
            }
            // Kein plausibler OFF-Treffer → lokale Referenz als FoodItem nutzen
            if (localRef != null) {
                return FoodItem(
                    name     = originalName,
                    calories = localRef.calories,
                    protein  = localRef.protein,
                    carbs    = localRef.carbs,
                    fat      = localRef.fat,
                    fiber    = localRef.fiber.takeIf { it > 0f },
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
        return analyzeIngredientLines(lines, recipe.servings.coerceAtLeast(1))
    }

    /**
     * Baut ein [AnalysisResult] direkt aus bereits verifizierten [ch.nutrisnap.app.data.model.IngredientMatch]-
     * Einträgen zusammen — OHNE erneute DB-/OpenFoodFacts-Suche. Für "Einsehen" (readOnly) und "Verify",
     * damit ein Rezept mit bereits bestätigten Zutaten nicht bei jedem Öffnen neu (und ggf. mit
     * abweichenden Treffern) analysiert wird. Gibt null zurück, wenn keine verwertbaren Matches vorhanden
     * sind — dann muss der Aufrufer auf [analyze] zurückfallen.
     */
    fun fromStoredMatches(
        recipe: Recipe,
        matches: List<ch.nutrisnap.app.data.model.IngredientMatch>
    ): AnalysisResult? {
        val active = matches.filter { !it.isDeleted }
        if (active.isEmpty()) return null

        val ingredientResults = active.map { m ->
            val amountG = m.manualAmountG ?: m.amountGrams
            val matched = m.matchedFoodItemId != null ||
                m.matchSource != ch.nutrisnap.app.data.model.MatchSource.UNMATCHED
            val foodItem = if (matched && m.matchedFoodName != null && amountG > 0f) {
                FoodItem(
                    name     = m.matchedFoodName,
                    calories = (m.matchedCalories ?: 0f) / amountG * 100f,
                    protein  = (m.matchedProtein ?: 0f) / amountG * 100f,
                    carbs    = (m.matchedCarbs ?: 0f) / amountG * 100f,
                    fat      = (m.matchedFat ?: 0f) / amountG * 100f,
                    source   = ch.nutrisnap.app.data.model.FoodSource.OPEN_FOOD_FACTS
                )
            } else null
            IngredientResult(
                line     = m.ingredientRaw.ifBlank { m.ingredientName },
                parsed   = ParsedIngredient(amountG = amountG, name = m.ingredientName),
                foodItem = foodItem,
                calories = m.matchedCalories ?: 0f,
                protein  = m.matchedProtein ?: 0f,
                carbs    = m.matchedCarbs ?: 0f,
                fat      = m.matchedFat ?: 0f,
                matched  = matched,
                micros   = m.manualFiberG?.let { mapOf("fiber" to it) } ?: emptyMap()
            )
        }

        val servDiv = recipe.servings.coerceAtLeast(1)
        val totalCalories = ingredientResults.sumOf { it.calories.toDouble() }.toFloat()
        val totalProtein  = ingredientResults.sumOf { it.protein.toDouble() }.toFloat()
        val totalCarbs    = ingredientResults.sumOf { it.carbs.toDouble() }.toFloat()
        val totalFat      = ingredientResults.sumOf { it.fat.toDouble() }.toFloat()
        val totalFiber    = ingredientResults.sumOf { (it.micros["fiber"] ?: 0f).toDouble() }.toFloat()

        return AnalysisResult(
            ingredients        = ingredientResults,
            totalCalories      = totalCalories,
            totalProtein       = totalProtein,
            totalCarbs         = totalCarbs,
            totalFat           = totalFat,
            caloriesPerServing = totalCalories / servDiv,
            proteinPerServing  = totalProtein / servDiv,
            carbsPerServing    = totalCarbs / servDiv,
            fatPerServing      = totalFat / servDiv,
            matchedCount       = ingredientResults.count { it.matched },
            totalCount         = ingredientResults.size,
            estimatedCount     = 0,
            totalMicros        = if (totalFiber > 0f) mapOf("fiber" to totalFiber) else emptyMap(),
            // Nur Ballaststoffe aus manuellen Angaben verfügbar -> nie "vollständig".
            fiberComplete      = false
        )
    }

    /**
     * Wie [analyze], aber für Zutatenzeilen, die nicht aus einem [Recipe] stammen
     * (z.B. von der mehrstufigen KI-Foto-Analyse produzierte "150g Reis"-Zeilen).
     * [servings] = 1, da eine fotografierte Mahlzeit bereits die gesamte Portion ist.
     */
    /**
     * @param allowNetwork wenn false: nur lokale DB + Wörterbuch-Cache, kein OFF und keine KI
     *        (für On-Device-/Offline-Food-Scan, Phase C).
     */
    suspend fun analyzeIngredientLines(
        lines: List<String>,
        servings: Int = 1,
        allowNetwork: Boolean = true
    ): AnalysisResult {
        // ── Pass 1: local DB + optional OpenFoodFacts (parallel, per-ingredient) ─
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
                            val localFood = FoodItem(
                                name            = parsed.name,
                                calories = local.calories,
                                protein  = local.protein,
                                carbs    = local.carbs,
                                fat      = local.fat,
                                fiber    = local.fiber,
                                source   = ch.nutrisnap.app.data.model.FoodSource.OPEN_FOOD_FACTS
                            )
                            return@async IngredientResult(
                                line     = line,
                                parsed   = parsed,
                                foodItem = localFood,
                                calories = local.calories * factor,
                                protein  = local.protein  * factor,
                                carbs    = local.carbs    * factor,
                                fat      = local.fat      * factor,
                                matched  = true,
                                micros   = localFood.scaledMicros(factor)
                            )
                        }

                        // Feature 2: bereits einmal aufgelöste Zutat (egal ob damals lokale DB
                        // oder OFF) -> direkt aus dem Cache, ohne erneute OFF-Netzwerkanfrage.
                        // Cache-Miss -> normale OFF-Suche (nur wenn allowNetwork), neuer Treffer
                        // wird für's nächste Mal im globalen Wörterbuch abgelegt.
                        val cachedFood = globalDictionary?.lookup(parsed.name)?.let { cached ->
                            FoodItem(
                                name     = cached.offProductName,
                                calories = cached.kcalPer100g.toFloat(),
                                protein  = cached.proteinPer100g.toFloat(),
                                carbs    = cached.carbsPer100g.toFloat(),
                                fat      = cached.fatPer100g.toFloat(),
                                source   = ch.nutrisnap.app.data.model.FoodSource.OPEN_FOOD_FACTS
                            )
                        }
                        val food = cachedFood ?: if (allowNetwork) {
                            searchOFF(parsed.name)?.also { off ->
                                globalDictionary?.save(
                                    originalName    = parsed.name,
                                    offProductId    = "",
                                    offProductName  = off.name,
                                    kcalPer100g     = (off.calories ?: 0f).toDouble(),
                                    proteinPer100g  = (off.protein  ?: 0f).toDouble(),
                                    carbsPer100g    = (off.carbs    ?: 0f).toDouble(),
                                    fatPer100g      = (off.fat      ?: 0f).toDouble()
                                )
                            }
                        } else null
                        if (food != null) {
                            IngredientResult(
                                line     = line,
                                parsed   = parsed,
                                foodItem = food,
                                // Unbekannte Werte fliessen als 0 in die Rezept-Summe ein (analog zum
                                // fiberComplete-Flag unten waere eine explizite "unvollstaendig"-Markierung
                                // pro Makro denkbar, aber ausserhalb des DB-Scopes dieser Aenderung).
                                calories = (food.calories ?: 0f) * factor,
                                protein  = (food.protein  ?: 0f) * factor,
                                carbs    = (food.carbs    ?: 0f) * factor,
                                fat      = (food.fat      ?: 0f) * factor,
                                matched  = true,
                                micros   = food.scaledMicros(factor)
                            )
                        } else {
                            IngredientResult(line, parsed, null)
                        }
                    }
                }.map { it.await() }
            }
        }

        // ── Pass 2: AI estimate for whatever is still unmatched (nur online) ───────
        val unmatched = firstPass.filter { !it.matched && it.parsed != null }
        val results: List<IngredientResult> = if (unmatched.isEmpty() || !allowNetwork) {
            firstPass
        } else {
            val apiKey = runCatching { BuildConfig.GROQ_API_KEY }.getOrElse { "" }
            val estimates = if (apiKey.isNotBlank()) {
                // War bisher NICHT auf Dispatchers.IO gewrappt -> blockierende
                // Netzwerkcalls liefen auf dem Aufrufer-Dispatcher (i.d.R. Main aus
                // viewModelScope.launch) -> NetworkOnMainThreadException-Risiko.
                withContext(Dispatchers.IO) {
                    runCatching { estimateViaAi(unmatched.map { it.parsed!!.name }, apiKey) }.getOrNull()
                }
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

        val servingsF = servings.coerceAtLeast(1).toFloat()
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
            caloriesPerServing = totCal  / servingsF,
            proteinPerServing  = totProt / servingsF,
            carbsPerServing    = totCarb / servingsF,
            fatPerServing      = totFat  / servingsF,
            matchedCount       = results.count { it.matched },
            totalCount         = results.size,
            estimatedCount     = results.count { it.estimated },
            totalMicros        = sumMicros(results.map { it.micros }),
            fiberComplete      = results.filter { it.matched }
                .let { matched -> matched.isNotEmpty() && matched.all { it.micros.containsKey("fiber") } }
        )
    }

    // ── AI nutrition estimate ───────────────────────────────────────────────────

    private data class AiNutritionEntry(
        val calories: Float, val protein: Float, val carbs: Float, val fat: Float
    )

    /**
     * Asks LLM to estimate per-100g macros for a batch of ingredient names that
     * neither the local DB nor OpenFoodFacts could resolve. One request covers
     * the whole batch, returning a JSON object keyed by ingredient name so
     * results can be matched back up regardless of response order.
     * Uses Gemini (primary) with Groq fallback.
     */
    private suspend fun estimateViaAi(names: List<String>, apiKey: String): Map<String, AiNutritionEntry>? = coroutineScope {
        val distinctNames = names.distinct().take(25) // sane upper bound per recipe
        if (distinctNames.isEmpty()) return@coroutineScope null

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

        val userMessage = "Ingredients:\n$listText"

        fun callGroq(): Map<String, AiNutritionEntry>? {
            val payload = JSONObject().apply {
                put("model", "llama-3.1-8b-instant")
                put("temperature", 0.2)
                put("max_tokens", 1200)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
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
            return parseAiResponse(content)
        }

        if (!GeminiService.isAvailable()) {
            return@coroutineScope callGroq()
        }

        // Parallel-Race statt sequentiellem Fallback: vermeidet worst-case
        // Latenz von "Gemini-Timeout + Groq-Call".
        val geminiJob: Deferred<Map<String, AiNutritionEntry>?> = async {
            val result = GeminiService.generateText(
                prompt = userMessage,
                systemPrompt = systemPrompt,
                temperature = 0.2,
                maxTokens = 1200
            )
            if (result.isSuccess) parseAiResponse(result.getOrThrow()) else null
        }
        val groqJob: Deferred<Map<String, AiNutritionEntry>?> = async { runCatching { callGroq() }.getOrNull() }

        val (winnerJob, winnerResult) = select<Pair<Deferred<Map<String, AiNutritionEntry>?>, Map<String, AiNutritionEntry>?>> {
            geminiJob.onAwait { geminiJob to it }
            groqJob.onAwait { groqJob to it }
        }
        val loserJob = if (winnerJob === geminiJob) groqJob else geminiJob

        if (winnerResult != null) {
            loserJob.cancel()
            winnerResult
        } else {
            loserJob.await()
        }
    }

    private fun parseAiResponse(content: String): Map<String, AiNutritionEntry>? {
        val jsonText = content.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        val items = try { JSONObject(jsonText).optJSONArray("items") } catch (_: Exception) { null } ?: return null
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
