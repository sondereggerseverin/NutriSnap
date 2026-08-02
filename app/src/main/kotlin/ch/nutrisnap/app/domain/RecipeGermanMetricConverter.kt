package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.Recipe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * Übersetzt Rezept-Zutaten und Zubereitung ins Deutsche und rechnet
 * imperial/US-Mengen (cups, tbsp, oz, °F …) in metrische Einheiten um.
 */
object RecipeGermanMetricConverter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Serializable
    data class ConvertedRecipe(
        val ingredients: String = "",
        val instructions: String = "",
        val title: String = ""
    )

    /**
     * Schnelle Offline-Umrechnung nur für Einheiten (kein Übersetzen).
     * Unterstützt Brüche wie 1/4, ½, 1 1/2.
     */
    fun convertUnitsToMetric(text: String): String {
        return try {
            text.lines().joinToString("\n") { line ->
                runCatching { convertLineToMetric(line) }.getOrDefault(line)
            }
        } catch (_: Exception) {
            text
        }
    }

    /**
     * Flüssigkeiten → ml, feste Zutaten → g (mit typischen Dichten).
     * Cups/tbsp/tsp werden nicht pauschal als Volumen belassen.
     */
    private fun convertLineToMetric(line: String): String {
        var r = line
        val unicodeFractions = mapOf(
            '¼' to "1/4", '½' to "1/2", '¾' to "3/4",
            '⅓' to "1/3", '⅔' to "2/3", '⅛' to "1/8",
            '⅜' to "3/8", '⅝' to "5/8", '⅞' to "7/8"
        )
        unicodeFractions.forEach { (u, a) -> r = r.replace(u.toString(), a) }

        fun parseAmount(raw: String): Double? {
            val s = raw.trim().replace(',', '.')
            val mixed = Regex("""^(\d+)\s+(\d+)/(\d+)$""").matchEntire(s)
            if (mixed != null) {
                val whole = mixed.groupValues[1].toDouble()
                val num = mixed.groupValues[2].toDouble()
                val den = mixed.groupValues[3].toDouble()
                if (den != 0.0) return whole + num / den
            }
            val frac = Regex("""^(\d+)/(\d+)$""").matchEntire(s)
            if (frac != null) {
                val num = frac.groupValues[1].toDouble()
                val den = frac.groupValues[2].toDouble()
                if (den != 0.0) return num / den
            }
            return s.toDoubleOrNull()
        }

        fun fmt(v: Double, unit: String): String {
            val rounded = when {
                v >= 100 -> v.toInt().toString()
                v >= 10 -> {
                    val t = (v * 10).toInt() / 10.0
                    if (t == t.toInt().toDouble()) t.toInt().toString() else "%.1f".format(t)
                }
                else -> {
                    val t = (v * 10).toInt() / 10.0
                    if (t == t.toInt().toDouble()) t.toInt().toString() else "%.1f".format(t)
                }
            }
            return "$rounded$unit"
        }

        // °F → °C zuerst
        r = Regex("""(\d+(?:[.,]\d+)?)\s*°\s*F\b""").replace(r) { mr ->
            val v = mr.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@replace mr.value
            "${((v - 32) * 5 / 9).toInt()}°C"
        }
        r = Regex("""(\d+(?:[.,]\d+)?)\s*degrees?\s*F(?:ahrenheit)?\b""", RegexOption.IGNORE_CASE).replace(r) { mr ->
            val v = mr.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@replace mr.value
            "${((v - 32) * 5 / 9).toInt()}°C"
        }

        // oz / lb immer Gewicht
        r = Regex("""(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)\s*(?:lbs?|pounds?)\b""", RegexOption.IGNORE_CASE).replace(r) { mr ->
            parseAmount(mr.groupValues[1])?.let { fmt(it * 453.6, " g") } ?: mr.value
        }
        r = Regex("""(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)\s*(?:fl\.?\s*oz)\b""", RegexOption.IGNORE_CASE).replace(r) { mr ->
            parseAmount(mr.groupValues[1])?.let { fmt(it * 29.57, " ml") } ?: mr.value
        }
        r = Regex("""(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)\s*(?:oz|ounces?)\b""", RegexOption.IGNORE_CASE).replace(r) { mr ->
            parseAmount(mr.groupValues[1])?.let { fmt(it * 28.35, " g") } ?: mr.value
        }

        // cup / tbsp / tsp: abhängig von der Zutat (Name nach der Einheit)
        val volPattern = Regex(
            """(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)\s*(cups?|tbsp|tbs|tablespoons?|tsp|teaspoons?)\b\s*(.*)""",
            RegexOption.IGNORE_CASE
        )
        r = volPattern.replace(r) { mr ->
            val amt = parseAmount(mr.groupValues[1]) ?: return@replace mr.value
            val unit = mr.groupValues[2].lowercase()
            val rest = mr.groupValues[3]
            val name = rest.lowercase()

            val isLiquid = LIQUID_KEYWORDS.any { name.contains(it) }
            val densityPerCup = densityGPerCup(name) // null = unbekannt

            when {
                unit.startsWith("cup") -> {
                    when {
                        isLiquid -> fmt(amt * 240, " ml") + (if (rest.isNotBlank()) " $rest" else "")
                        densityPerCup != null -> fmt(amt * densityPerCup, " g") + (if (rest.isNotBlank()) " $rest" else "")
                        else -> fmt(amt * 240, " g") + (if (rest.isNotBlank()) " $rest" else "") // Feststoffe default: g≈ml-Volumen
                    }
                }
                unit.startsWith("tbsp") || unit == "tbs" || unit.startsWith("table") -> {
                    when {
                        isLiquid -> fmt(amt * 15, " ml") + (if (rest.isNotBlank()) " $rest" else "")
                        densityPerCup != null -> fmt(amt * densityPerCup / 16.0, " g") + (if (rest.isNotBlank()) " $rest" else "")
                        else -> fmt(amt * 15, " g") + (if (rest.isNotBlank()) " $rest" else "")
                    }
                }
                else -> { // tsp
                    when {
                        isLiquid -> fmt(amt * 5, " ml") + (if (rest.isNotBlank()) " $rest" else "")
                        densityPerCup != null -> fmt(amt * densityPerCup / 48.0, " g") + (if (rest.isNotBlank()) " $rest" else "")
                        else -> fmt(amt * 5, " g") + (if (rest.isNotBlank()) " $rest" else "")
                    }
                }
            }
        }

        return r
    }

    /** g pro US-Cup für gängige feste Zutaten. */
    private fun densityGPerCup(nameLower: String): Double? {
        val rules = listOf(
            listOf("flour", "mehl") to 120.0,
            listOf("sugar", "zucker", "coconut sugar") to 200.0,
            listOf("powdered sugar", "icing", "confectioner", "puderzucker") to 120.0,
            listOf("butter", "butter") to 227.0,
            listOf("cottage cheese", "hüttenkäse", "huttenkase") to 225.0,
            listOf("greek yogurt", "joghurt", "yogurt", "yoghurt") to 245.0,
            listOf("honey", "honig") to 340.0,
            listOf("protein powder", "proteinpulver", "whey") to 120.0,
            listOf("cinnamon", "zimt") to 125.0,
            listOf("baking powder", "backpulver") to 220.0,
            listOf("baking soda", "natron") to 220.0,
            listOf("salt", "salz") to 290.0,
            listOf("cocoa", "kakao") to 85.0,
            listOf("oat", "hafer") to 90.0,
            listOf("rice", "reis") to 185.0,
            listOf("cheese", "käse", "kase") to 110.0
        )
        for ((keys, dens) in rules) {
            if (keys.any { nameLower.contains(it) }) return dens
        }
        return null
    }

    private val LIQUID_KEYWORDS = listOf(
        "milk", "milch", "water", "wasser", "oil", "öl", "ol ", " cream", "sahne",
        "broth", "brühe", "bruhe", "stock", "juice", "saft", "vinegar", "essig",
        "wine", "wein", "beer", "bier", "syrup", "sirup", "extract", "vanilleextrakt",
        "vanilla extract", "soy sauce", "sojasauce", "stock", "fond"
    )

    /**
     * KI: Zutaten + Zubereitung ins Deutsche übersetzen und metrisch umrechnen.
     * Titel optional mitübersetzen.
     */
    suspend fun convertWithAi(recipe: Recipe): Result<ConvertedRecipe> {
        val prompt = """
Du bist ein Schweizer/deutscher Koch-Assistent. Wandle das folgende Rezept ins Deutsche um und rechne alle Mengen in metrische Einheiten um.

Regeln:
- Zutatennamen auf Deutsch (z.B. cottage cheese → Hüttenkäse, greek yogurt → griechischer Joghurt, flour → Mehl).
- FESTE Zutaten immer in Gramm (g), NICHT in ml:
  Mehl ~120 g/cup, Zucker ~200 g/cup, Butter ~227 g/cup, Hüttenkäse ~225 g/cup,
  Honig ~340 g/cup, Zimt/Gewürze pro TL in g, Backpulver/Natron/Salz in g.
- FLÜSSIGE Zutaten in ml: Milch, Wasser, Öl, Extrakt, Saft, Brühe.
- oz → g, lb → g, °F → °C.
- Beispiel: "1 cup flour" → "120 g Mehl", "1/4 cup milk" → "60 ml Milch", "2 tbsp butter" → "28 g Butter".
- Zubereitungsschritte auf Deutsch, klar und nummeriert.
- Mengen realistisch runden (ganze g/ml).
- Gruppierungen (dough:, filling:) als deutsche Überschriften (Teig:, Füllung:, Glasur:).
- Erfinde keine Zutaten hinzu.

Originaltitel: ${recipe.title}
Zutaten:
${recipe.ingredients}

Zubereitung:
${recipe.instructions}

Antworte NUR mit JSON:
{
  "title": "Deutscher Titel",
  "ingredients": "Zeile pro Zutat...",
  "instructions": "1. ...\\n2. ..."
}
""".trimIndent()

        val raw = GeminiService.generateText(
            prompt = prompt,
            temperature = 0.2,
            maxTokens = 3000
        ).getOrElse { e ->
            // Fallback: offline nur Einheiten
            return Result.success(
                ConvertedRecipe(
                    title = recipe.title,
                    ingredients = convertUnitsToMetric(recipe.ingredients),
                    instructions = convertUnitsToMetric(recipe.instructions)
                )
            )
        }

        return runCatching {
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            // Robust: JSONObject falls kotlinx strict fails
            val obj = JSONObject(cleaned)
            ConvertedRecipe(
                title = obj.optString("title").ifBlank { recipe.title },
                ingredients = obj.optString("ingredients").ifBlank { convertUnitsToMetric(recipe.ingredients) },
                instructions = obj.optString("instructions").ifBlank { convertUnitsToMetric(recipe.instructions) }
            )
        }.recover {
            ConvertedRecipe(
                title = recipe.title,
                ingredients = convertUnitsToMetric(recipe.ingredients),
                instructions = convertUnitsToMetric(recipe.instructions)
            )
        }
    }
}
