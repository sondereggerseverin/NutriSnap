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
        var r = text
        // Unicode-Brüche → ASCII
        val unicodeFractions = mapOf(
            '¼' to "1/4", '½' to "1/2", '¾' to "3/4",
            '⅓' to "1/3", '⅔' to "2/3", '⅛' to "1/8",
            '⅜' to "3/8", '⅝' to "5/8", '⅞' to "7/8"
        )
        unicodeFractions.forEach { (u, a) -> r = r.replace(u.toString(), a) }

        // Gemischte Zahlen: "1 1/2 cup" → 1.5
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
                v >= 10 -> ((v * 10).toInt() / 10.0).let {
                    if (it == it.toInt().toDouble()) it.toInt().toString() else "%.1f".format(it)
                }
                else -> {
                    val t = (v * 10).toInt() / 10.0
                    if (t == t.toInt().toDouble()) t.toInt().toString() else "%.1f".format(t)
                }
            }
            return "$rounded$unit"
        }

        data class Rule(val pattern: Regex, val convert: (Double) -> String)
        val rules = listOf(
            Rule(Regex("""(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)\s*(?:cups?|Cups?)\b""")) { v -> fmt(v * 240, " ml") },
            Rule(Regex("""(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)\s*(?:tbsp|Tbsp|tablespoons?)\b""")) { v -> fmt(v * 15, " ml") },
            Rule(Regex("""(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)\s*(?:tsp|Tsp|teaspoons?)\b""")) { v -> fmt(v * 5, " ml") },
            Rule(Regex("""(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)\s*(?:fl\.?\s*oz)\b""", RegexOption.IGNORE_CASE)) { v -> fmt(v * 29.57, " ml") },
            Rule(Regex("""(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)\s*(?:oz|ounces?)\b""", RegexOption.IGNORE_CASE)) { v -> fmt(v * 28.35, " g") },
            Rule(Regex("""(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)\s*(?:lbs?|pounds?)\b""", RegexOption.IGNORE_CASE)) { v -> fmt(v * 453.6, " g") },
            Rule(Regex("""(\d+(?:[.,]\d+)?)\s*°\s*F\b""")) { v -> "${((v - 32) * 5 / 9).toInt()}°C" },
            Rule(Regex("""(\d+(?:[.,]\d+)?)\s*degrees?\s*F(?:ahrenheit)?\b""", RegexOption.IGNORE_CASE)) { v -> "${((v - 32) * 5 / 9).toInt()}°C" }
        )

        rules.forEach { rule ->
            r = rule.pattern.replace(r) { mr ->
                parseAmount(mr.groupValues[1])?.let { rule.convert(it) } ?: mr.value
            }
        }
        return r
    }

    /**
     * KI: Zutaten + Zubereitung ins Deutsche übersetzen und metrisch umrechnen.
     * Titel optional mitübersetzen.
     */
    suspend fun convertWithAi(recipe: Recipe): Result<ConvertedRecipe> {
        val prompt = """
Du bist ein Schweizer/deutscher Koch-Assistent. Wandle das folgende Rezept ins Deutsche um und rechne alle Mengen in metrische Einheiten um.

Regeln:
- Zutatennamen auf Deutsch (z.B. cottage cheese → Hüttenkäse, greek yogurt → griechischer Joghurt, all-purpose flour → Weissmehl).
- cups → ml (1 cup = 240 ml), tbsp → ml (15 ml), tsp → ml (5 ml), oz → g, lb → g.
- °F → °C.
- Brüche als Dezimalzahl oder gängige Mengenangaben (z.B. 1/4 cup → 60 ml).
- Zubereitungsschritte auf Deutsch, klar und nummeriert.
- Mengen realistisch runden (ganze ml/g wo sinnvoll).
- Gruppierungen (dough:, filling:) als deutsche Überschriften behalten (Teig:, Füllung:, Glasur:).
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
