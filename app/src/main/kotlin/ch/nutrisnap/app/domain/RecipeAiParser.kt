package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.Recipe
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * Uses the Groq API (llama-3.1-8b-instant, free tier) to parse a raw
 * Instagram/TikTok caption into a structured Recipe.
 *
 * Why Groq: free, fast (~1s), no new dependency (OkHttp already in project).
 * API key is stored in BuildConfig.GROQ_API_KEY via gradle secrets.
 *
 * Prompt strategy: strict JSON-only output with a well-defined schema.
 * Falls back gracefully to regex-parsed result if AI fails.
 */
object RecipeAiParser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"

    /**
     * Parse a raw social-media caption into a structured Recipe.
     *
     * @param caption   Raw text from Instagram/TikTok caption
     * @param sourceUrl Original URL (for Recipe.sourceUrl)
     * @param platform  "instagram" | "tiktok" | "web"
     * @param imageUrl  Thumbnail from oEmbed (kept as-is)
     * @param apiKey    Groq API key from BuildConfig
     */
    /**
     * @param fastModel true → Groq llama-3.1-8b-instant (schneller, etwas weniger präzise).
     *                  false → llama-3.3-70b-versatile (Default).
     */
    suspend fun parse(
        caption:  String,
        sourceUrl: String?,
        platform:  String,
        imageUrl:  String?,
        apiKey:    String,
        fastModel: Boolean = false
    ): Recipe = withContext(Dispatchers.IO) {
        val cleaned = cleanCaption(caption)
        val fallback = fallbackParse(cleaned, sourceUrl, platform, imageUrl)
        val aiResult = runCatching { callLlm(cleaned, apiKey, fastModel) }.getOrNull()
        // AI oft mit title=null / leeren Zutaten → mit Regex-Fallback mergen
        mergeWithFallback(aiResult, fallback, cleaned)
    }

    /**
     * Nimmt brauchbare AI-Felder, füllt Lücken aus dem Regex-Fallback
     * (Titel, Zutaten, kcal). Verhindert generische „Rezept“-Karten ohne Inhalt.
     */
    private fun mergeWithFallback(ai: Recipe?, fallback: Recipe, cleanedCaption: String): Recipe {
        if (ai == null) return fallback

        fun isPlaceholderTitle(t: String) =
            t.isBlank() ||
                t.equals("null", true) ||
                t.equals("undefined", true) ||
                t.equals("Rezept", true) ||
                t.equals("Instagram Rezept", true) ||
                t.equals("TikTok Rezept", true)

        fun isWeakIngredients(s: String): Boolean {
            if (s.isBlank() || s.equals("Zutaten nicht gefunden.", true) ||
                s.startsWith("Tippe") || s.length < 20
            ) return true
            val lines = s.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) return true
            // Viele Junk-Zeilen (Makros/Schritte) → schwach, Fallback nutzen
            val junk = lines.count { isJunkIngredientLine(it) }
            if (junk >= 2 && junk >= lines.size / 2) return true
            // Keine echte Mengenangabe → schwach
            val hasQty = lines.any {
                Regex("""\d+[.,]?\d*\s*(g|kg|ml|l|el|tl|tsp|tbsp)\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(it)
            }
            return !hasQty
        }

        val titleFromCaption = extractTitle(cleanedCaption, fallback = "")
        val title = when {
            !isPlaceholderTitle(ai.title) -> ai.title.trim()
            !isPlaceholderTitle(fallback.title) -> fallback.title.trim()
            titleFromCaption.isNotBlank() -> titleFromCaption
            else -> "Rezept"
        }

        val ingredientsRaw = when {
            !isWeakIngredients(ai.ingredients) -> ai.ingredients
            !isWeakIngredients(fallback.ingredients) -> fallback.ingredients
            else -> ai.ingredients.ifBlank { fallback.ingredients }
        }
        // Caption-Klumpen in saubere Zeilen + Abschnitte zerlegen
        val ingredients = formatIngredientText(ingredientsRaw)

        val instructions = formatInstructionsText(
            ai.instructions.trim()
                .takeUnless { it.isBlank() || it.equals("null", true) }
                ?: fallback.instructions
        )

        val description = ai.description.trim()
            .takeUnless { it.isBlank() || it.equals("null", true) }
            ?: fallback.description

        return ai.copy(
            title = title,
            description = description,
            ingredients = ingredients,
            instructions = instructions,
            servings = ai.servings.coerceAtLeast(1).takeIf { it > 0 } ?: fallback.servings,
            totalCalories = ai.totalCalories?.takeIf { it > 0f } ?: fallback.totalCalories,
            proteinPerServing = ai.proteinPerServing?.takeIf { it > 0f } ?: fallback.proteinPerServing,
            carbsPerServing = ai.carbsPerServing?.takeIf { it > 0f } ?: fallback.carbsPerServing,
            fatPerServing = ai.fatPerServing?.takeIf { it > 0f } ?: fallback.fatPerServing,
            prepTimeMinutes = ai.prepTimeMinutes ?: fallback.prepTimeMinutes,
            sourceUrl = ai.sourceUrl ?: fallback.sourceUrl,
            platform = ai.platform ?: fallback.platform,
            imageUrl = ai.imageUrl ?: fallback.imageUrl,
            tags = ai.tags.ifBlank { fallback.tags }
        )
    }

    /**
     * JSON-/Social-Escapes auflösen (Instagram/TikTok-Captions).
     * Ohne das landen wörtliche "\t" / "\n" / "\u00e4" in den Zutatenzeilen.
     * Idempotent auf bereits unescaped Text.
     */
    fun unescapeSocialText(raw: String): String {
        if (raw.isEmpty()) return raw
        if ('\\' !in raw) {
            return raw.replace("\r\n", "\n").replace('\r', '\n')
        }
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val n = raw[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append(' '); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\'' -> { sb.append('\''); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'u', 'U' -> {
                        val end = (i + 6).coerceAtMost(raw.length)
                        val hex = raw.substring(i + 2, end)
                        if (hex.length == 4 && hex.all { it in "0123456789abcdefABCDEF" }) {
                            sb.append(hex.toInt(16).toChar())
                            i += 6
                        } else {
                            sb.append(c); i++
                        }
                    }
                    else -> { sb.append(c); sb.append(n); i += 2 }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[ \\t]{2,}"), " ")
    }

    /**
     * Strips the "X likes, Y comments - username on Date:" prefix that
     * Instagram/mirror sites prepend to og:description captions, and removes
     * surrounding quote marks. Safe to call on already-clean text (no-op).
     */
    fun cleanCaption(raw: String): String {
        val prefixRegex = Regex(
            """^[\d.,]+\s*(?:likes?|Likes?)\s*,?\s*[\d.,]*\s*(?:comments?|Comments?)?\s*-\s*\S+\s+on\s+[^:]+:\s*""",
            RegexOption.IGNORE_CASE
        )
        var c = unescapeSocialText(raw.trim())
        c = prefixRegex.replace(c, "").trim()
        // Strip surrounding straight or curly quotes left over from the caption
        c = c.removeSurrounding("\"").removeSurrounding("\u201c", "\u201d").trim()
        // Normalize TikTok/Instagram "*" ingredient separator → newlines
        if (c.contains("* ") && !c.contains("\n")) {
            c = c.replace(Regex("\\*(?=\\s*\\d|\\s*[¼½¾])"), "\n•")
        }
        // Immer Mengen/Abschnitte trennen – auch wenn schon einzelne Newlines da sind
        // (sonst bleibt "600g Hähnchen 15 ml Öl 1 Tsp Paprika" in einer Zeile)
        c = splitInlineIngredients(c)
        return c.ifBlank { unescapeSocialText(raw.trim()) }
    }

    /**
     * Zerlegt Caption-/Zutaten-Blöcke, in denen mehrere Zutaten in einer Zeile
     * kleben (typisch TikTok/Instagram), in saubere Zeilen mit Abschnitts-Headern.
     * Entfernt Hashtags, Makro-Zeilen und Zubereitungsschritte.
     * Idempotent auf bereits formatierten Listen.
     */
    fun formatIngredientText(raw: String): String {
        if (raw.isBlank()) return raw
        var t = unescapeSocialText(raw.trim())
        // Hashtags entfernen
        t = t.replace(Regex("""(?:\s*#\w+)+[\s:]*$""", RegexOption.IGNORE_CASE), "").trim()
        t = t.replace(Regex("""#\w+"""), " ").trim()
        // Inline-Abschnitte hart trennen (TikTok-Klumpen ohne Newlines)
        t = t.replace(
            Regex("""(?i)(?<=\S)\s*(?=Zubereitung\b|Method\b|Instructions\b|Nährwerte\b|Naehrwerte\b)"""),
            "\n"
        )
        // Promo-Zeilen mit Pfeil vor dem Split isolieren
        t = t.replace(
            Regex("""(?i)\s*[→➤]\s*(?=Aktuell|Höchster|Hoechster|mit Code)"""),
            "\n"
        )
        t = splitInlineIngredients(t)
        // Ab erstem echten Methodenschritt abschneiden (falls Zutaten+Schritte gemischt)
        t = cutOffInstructions(t)
        // Zeilen, die noch mehrere Mengen tragen, nochmal aufsplitten
        val expanded = t.lines().flatMap { expandMultiIngredientLine(it) }
        return expanded
            .map { it.trim().trimStart('•', '-', '*', ' ').trim() }
            .flatMap { splitHeaderFromFirstItem(it) }
            .filter { it.isNotBlank() && !isJunkIngredientLine(it) }
            .map { cleanIngredientLineNoise(it) }
            .map { RecipeGermanMetricConverter.cleanupMetricLine(it) }
            .filter { it.isNotBlank() }
            .joinToString("\n") { line ->
                if (isSectionHeaderLine(line)) line.trimEnd(':').trim()
                else "• $line"
            }
    }

    /**
     * "For the chicken: 2 chicken breasts" → Header + eigene Zutatenzeile.
     * Verhindert, dass die erste Zutat im Abschnittsnamen landet.
     */
    private fun splitHeaderFromFirstItem(line: String): List<String> {
        val d = line.trim()
        // "For the chicken: 2 chicken breasts" / "Für die Sauce: 1 Schalotte …"
        val m = Regex(
            """^(For the\s+[^:]{2,40}|Für\s+(?:die|den|das)\s+[^:]{2,40}|Served with|Dazu|Beilage)\s*:\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(d) ?: return listOf(d)
        val header = m.groupValues[1].trim()
        val rest = m.groupValues[2].trim()
        if (rest.isBlank()) return listOf(header)
        // Rest kann noch mehrere Zutaten enthalten
        return listOf(header) + expandMultiIngredientLine(rest)
    }

    /**
     * Eine Zeile mit mehreren Mengen (z.B. "1 tsp oil 1 tsp paprika 1 tsp oregano")
     * in Einzelzutaten zerlegen.
     */
    private fun expandMultiIngredientLine(line: String): List<String> {
        val d = line.trim().trimStart('•', '-', '*', ' ').trim()
        if (d.isBlank() || isSectionHeaderLine(d)) return listOf(d)
        // "20 g X • 90 ml Y • 1 Portion Z" → Einzelzeilen (TikTok/Caption-Klumpen)
        if (d.count { it == '•' } >= 1 &&
            Regex("""\d+[.,]?\d*\s*(g|kg|ml|l|EL|TL)\b""", RegexOption.IGNORE_CASE).findAll(d).count() >= 2
        ) {
            val parts = d.split('•').map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size >= 2) return parts.flatMap { expandMultiIngredientLine(it) }
        }
        // Mengen in Klammern (z.B. "1361 g (48 oz)") NICHT als zweite Zutat zählen
        val masked = d.replace(Regex("""\([^)]*\)"""), " ")
        // Schon sauber: nur eine Mengenangabe
        val unit = """g|kg|ml|l|EL|TL|Tsp|Tbsp|tsp|tbsp|cup|cups|oz|lb|Stück|Stk|pcs|Prise|Tasse|clove|cloves|Portion|portion"""
        val qtyPattern = Regex(
            """(?i)(\d+[.,]?\d*|\d+/\d+|¼|½|¾|⅓|⅔)\s*($unit)\b"""
        )
        val hits = qtyPattern.findAll(masked).toList()
        if (hits.size <= 1) {
            // Zählzutaten ohne Einheit: "2 chicken breasts" / "2 garlic cloves"
            val countHits = Regex(
                """(?i)(?<=^|\s)(\d+)\s+(chicken|hähnchen|haehnchen|breast|breasts|egg|eggs|ei|eier|onion|zwiebel|shallot|schalotte|clove|cloves|zehe|tomato|tomate|potato|kartoffel)\b"""
            ).findAll(masked).toList()
            if (countHits.size <= 1) return listOf(d)
        }
        // An jeder Mengen-Position splitten (ab dem 2. Treffer) – Positionen aus masked,
        // Substrings aus Original (Klammern bleiben in der jeweiligen Zutat)
        val splitPoints = qtyPattern.findAll(masked).map { it.range.first }.toList()
        if (splitPoints.size <= 1) return listOf(d)
        val parts = mutableListOf<String>()
        for (i in splitPoints.indices) {
            val start = splitPoints[i]
            val end = splitPoints.getOrNull(i + 1) ?: d.length
            val part = d.substring(start, end).trim().trimEnd(',', ';')
            if (part.isNotBlank()) parts += part
        }
        // Text vor der ersten Menge (selten) an erste Zutat hängen oder verwerfen
        if (splitPoints.first() > 0) {
            val prefix = d.substring(0, splitPoints.first()).trim()
            if (prefix.isNotBlank() && prefix.length < 40 && !isSectionHeaderLine(prefix)) {
                // Präfix ist eher Rauschen (z.B. "and") — weglassen
            }
        }
        return parts.ifEmpty { listOf(d) }
    }

    /** Makros, Methodenschritte, Promo, Meta, Hashtags — keine Zutaten. */
    fun isJunkIngredientLine(line: String): Boolean {
        // Emoji/Symbole am Anfang entfernen (z.B. "🥣 Ingredients – Makes 3")
        val d = line.trim()
            .trimStart('•', '-', '*', ' ')
            .replace(Regex("""^[\p{So}\p{Cn}\p{Sk}]+"""), "")
            .trim()
        if (d.isBlank()) return true
        val lower = d.lowercase()
            .replace('–', '-')
            .replace('—', '-')
        // Meta: "Ingredients", "Ingredients – Makes 3", "Zutaten für 4"
        if (Regex(
                """^(ingredients?|zutaten)\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(d)
        ) {
            // Echte Zutat wie "Ingredients for salsa: 2 tomatoes" behalten nur mit klarer Menge+Food
            val onlyMeta = !Regex(
                """\d+[.,]?\d*\s*(g|ml|kg|l|tsp|tbsp|oz|cup)s?\s+\p{L}{3,}""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(d)
            if (onlyMeta) return true
        }
        // "Makes 3" / "Serves 4" / "Portions: 10" allein
        if (Regex(
                """^(makes?|serves?|servings?|portionen?|portions?)\s*:?\s*\d+\s*$""",
                RegexOption.IGNORE_CASE
            ).matches(lower)
        ) return true
        // Caption-Footer / Makro-Blöcke (nie Zutaten)
        if (lower.startsWith("entire recipe") || lower.startsWith("approx macros") ||
            lower.startsWith("macros per") || lower.startsWith("per serving") ||
            lower.startsWith("adjust serving") || lower.startsWith("recipe by") ||
            lower.startsWith("meal prep") || lower.startsWith("store frozen") ||
            lower.startsWith("full recipe") || lower.startsWith("quick lesson") ||
            lower.startsWith("big batch") || lower.startsWith("episode ") ||
            lower.startsWith("want meals") || lower.startsWith("comment ") ||
            lower.startsWith("the original creator") || lower.startsWith("mine ended") ||
            lower.startsWith("big ups") || lower.startsWith("if that sounds") ||
            lower.startsWith("this is exactly") || lower.startsWith("using lean") ||
            lower == "macros" || lower.startsWith("protein bowl")
        ) return true
        // Reine Hashtag-Zeile
        if (d.trim().startsWith("#") || Regex("""^(#\w+\s*)+$""").matches(d.trim())) return true
        // Marketing-/Subtitle ohne Mengenangabe (Caption-Intro, nicht Zutat)
        val hasQuantity = Regex(
            """(?i)((\d+[.,]?\d*|½|¼|¾|⅓|⅔)\s*(g|kg|ml|l|tl|el|tsp|tbsp|cup|cups|oz|lb|stück|stk|prise|bund|dose|pack|scheibe|scheiben|packet|packets)\b|(½|¼|¾|⅓|⅔)\s+\p{L})"""
        ).containsMatchIn(d)
        if (!hasQuantity && !isSectionHeaderLine(d)) {
            val marketingHits = listOf(
                "proteinreich", "gesund", "super easy", "meal prep", "perfekt zum",
                "für die woche", "zum mitnehmen", "high protein", "low calorie",
                "easy für", "easy fuer", "mac & cheese meal", "das perfekte",
                "fuer 4 portionen", "für 4 portionen", "animal style", "elite macros",
                "fat loss", "loaded fries"
            ).count { lower.contains(it) }
            if (marketingHits >= 1 && d.length > 20) return true
        }
        // Makro-Zusammenfassungen: "265 kcals", "47g Protein", "83g Carbs", "6190 Calories"
        // Immer Junk – auch wenn "47g" wie eine Mengenangabe aussieht
        if (Regex(
                """^[~≈]?\s*\d+[.,]?\d*\s*(kcals?|calories?|kcal)\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(d)
        ) return true
        if (Regex(
                """^[~≈]?\s*\d+[.,]?\d*\s*g?\s*(protein|eiwei[sß]|carbs?|kohlenhydrate?|fat|fett|ballaststoffe?|calories?|kcals?)\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(d)
        ) return true
        if (Regex("""^\d+[.,]?\d*\s*g\s*[|/:]\s*[pcf]\b""", RegexOption.IGNORE_CASE).containsMatchIn(d)) return true
        if (Regex("""^\d+[.,]?\d*\s*g\s*per\s+(cup|serving|portion)""", RegexOption.IGNORE_CASE).containsMatchIn(d)) return true
        if (Regex("""^[pcf]\s*:\s*$""", RegexOption.IGNORE_CASE).containsMatchIn(d)) return true
        // "10 servings = 620 Cals, 47g Protein" – Serving-Tabelle, keine Zutat
        if (Regex(
                """^\d+\s*servings?\s*=\s*\d+""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(d)
        ) return true
        // DE Makro-Zeilen mit Pipe/Suffix: "41 g Eiweiß |", "6 g Fett Anzeige"
        if (Regex(
                """(?i)^\d+[.,]?\d*\s*g\s+(eiwei[sß]|protein|kohlenhydrate?|carbs?|fett|fat)\b"""
            ).containsMatchIn(d)
        ) return true
        if (lower.startsWith("nährwerte") || lower.startsWith("naehrwerte") ||
            lower.startsWith("nährwert") || lower.startsWith("naehrwert") ||
            lower.contains("nährwerte gesamt") || lower.contains("naehrwerte gesamt")
        ) return true
        if (Regex("""(?i)\d+\s*kcal\s*[|·]\s*\d+\s*g\s*(eiwei|protein)""").containsMatchIn(d)) return true
        if (lower == "anzeige" || lower.endsWith(" anzeige") || lower.contains("fett anzeige")) return true
        // Prosa / Zubereitungsschritte
        if (lower.startsWith("firstly") || lower.startsWith("first,") ||
            lower.startsWith("next,") || lower.startsWith("next ") ||
            lower.startsWith("then ") || lower.startsWith("then,") ||
            lower.startsWith("in the meantime") || lower.startsWith("meanwhile") ||
            lower.startsWith("serve up") || lower.startsWith("serve with") ||
            lower.startsWith("enjoy") || lower.startsWith("once ") ||
            lower.startsWith("after ") || lower.startsWith("while ") ||
            lower.startsWith("chop the") || lower.startsWith("slice the") ||
            lower.startsWith("coat ") || lower.startsWith("preheat") ||
            lower.startsWith("remove from") || lower.startsWith("to the liquid") ||
            lower.startsWith("anschließend") || lower.startsWith("anschliessend") ||
            lower.startsWith("danach ") || lower.startsWith("nun ") ||
            lower.startsWith("verführe") || lower.startsWith("verrühr") ||
            lower.startsWith("die masse") || lower.startsWith("den cheesecake") ||
            lower.startsWith("für ca.") || lower.startsWith("fuer ca.") ||
            lower.startsWith("für jeweils") || lower.startsWith("fuer jeweils") ||
            lower.startsWith("für mindestens") || lower.startsWith("fuer mindestens")
        ) return true
        // Nummerierte Zubereitungsschritte
        if (Regex("""^\d+[.)]?\s+\p{L}""").containsMatchIn(d) &&
            Regex(
                """\b(mix|add|stir|pour|bake|cook|heat|divide|refrigerate|spoon|blend|whisk|fold|spread|save|method|season|fry|simmer|coat|chop|slice|preheat|remove|cover|place|""" +
                    """verrühren|verruehren|vermischen|geben|garen|auskühlen|auskuehlen|stellen|erwärmen|erwaermen|verteilen|schneiden|zerbrechen|mikrowelle|pausieren)\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(d)
        ) return true
        if (lower == "method" || lower.startsWith("method ") || lower == "zubereitung" ||
            lower.startsWith("zubereitung") ||
            lower.startsWith("instructions") || lower.startsWith("directions")
        ) return true
        // Lange Sätze mit mehreren Kochverben → Anleitung, keine Zutat
        if (d.length > 90 &&
            Regex(
                """\b(mix|stir|cook|bake|fry|simmer|season|until|minutes|mins|verrühren|verruehren|garen|mikrowelle|auskühlen|auskuehlen|verteilen|erwärmen|erwaermen|pausieren)\b""",
                RegexOption.IGNORE_CASE
            ).findAll(d).count() >= 2
        ) return true
        // Social / Promo (Affiliate-Codes, Rabatt-Hinweise)
        if (isPromoIngredientNoise(d)) return true
        // Reine Hashtag-/Code-Zeilen
        if (d.startsWith("@") && d.length < 40) return true
        if (!d.any { it.isLetter() }) return true
        // Orphan-Klammernreste wie "20g)" von "(about 20g)"
        if (Regex("""^\d+[.,]?\d*\s*g\s*\)?\s*$""", RegexOption.IGNORE_CASE).matches(d)) return true
        return false
    }

    /**
     * Affiliate-/Promo-Zeilen, die oft „ingredients“ enthalten und den
     * Keyword-Split kaputt machen (z. B. fitfoodiejules / Prozis-Code).
     */
    fun isPromoIngredientNoise(line: String): Boolean {
        val lower = line.trim().lowercase()
        if (lower.isBlank()) return false
        if (lower.startsWith("save this") || lower.startsWith("comment ") ||
            lower.startsWith("link in bio") || lower.startsWith("dm me")
        ) return true
        if (lower.contains("all prozis") || lower.contains("products linked")) return true
        if (lower.contains("will give you") && (lower.contains("discount") || lower.contains("code"))) return true
        if (lower.contains("ingredients with a *") || lower.contains("ingredients with a*")) return true
        if (lower.contains("are from @") && lower.contains("code")) return true
        if (lower.contains("big discount") || lower.contains("discount + gifts")) return true
        // DE TikTok-Promo: "Aktuell -25 % mit Code: VICCES", "Höchster Rabatt mit Code:"
        if ("mit code" in lower) return true
        if ("höchster rabatt" in lower || "hoechster rabatt" in lower) return true
        if (Regex("""(?i)aktuell\s*-?\s*\d+\s*%""").containsMatchIn(lower)) return true
        if (Regex("""(?i)\bcode\s*:\s*[a-z0-9_]+""").containsMatchIn(lower) &&
            !Regex("""(?i)^\d+[.,]?\d*\s*(g|ml)\b""").containsMatchIn(lower)
        ) return true
        // "code XYZ will give you…" ohne echte Menge
        if (Regex("""\bcode\s+[a-z0-9_]+\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) &&
            !Regex("""^\d+\s*(g|ml|tsp|tbsp)\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        ) return true
        return false
    }

    /** @prozis, doppelte (80 g)-Klammern o.ä. aus Zutatenzeilen entfernen. */
    private fun cleanIngredientLineNoise(line: String): String {
        var s = line
        s = s.replace(Regex("""@\w+"""), "").trim()
        // Promo-Anhängsel abschneiden: "15 g ESN Whey → Aktuell -25 % mit Code: VICCES"
        s = s.replace(
            Regex(
                """(?i)\s*[→➤‣]?\s*(aktuell\s*-?\s*\d+\s*%.*|höchster\s+rabatt.*|hoechster\s+rabatt.*|mit\s+code\s*:.*)$"""
            ),
            ""
        ).trim()
        s = s.replace(
            Regex("""(?i)\s*[→➤]?\s*.{0,12}code\s*:\s*[a-z0-9_]+\s*.*$"""),
            ""
        ).trim()
        s = s.replace(Regex("""(?i)\s+anzeige\s*$"""), "").trim()
        // "40g Haferflocken Mehl (80 g )" → erste Menge behalten, Klammer-Menge weg
        if (Regex("""^\d+[.,]?\d*\s*g\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)) {
            s = s.replace(Regex("""\s*\(\s*\d+[.,]?\d*\s*g\s*\)\s*$""", RegexOption.IGNORE_CASE), " ").trim()
        }
        s = s.replace(Regex("""\s{2,}"""), " ").trim()
        return s.trimEnd(':', ',', ';', ' ', '•')
    }

    /** Schneidet ab erstem klaren Methodenschritt / Makro-Block / Footer ab. */
    private fun cutOffInstructions(text: String): String {
        val lines = text.lines()
        val cut = lines.indexOfFirst { line ->
            val d = line.trim().trimStart('•', '-', '*', ' ').trim()
            val lower = d.lowercase()
            // Method / Anleitung
            lower == "method" || lower == "zubereitung" || lower.startsWith("zubereitung") ||
                lower == "instructions" ||
                lower == "directions" || lower == "preparation" ||
                lower.startsWith("nährwerte") || lower.startsWith("naehrwerte") ||
                lower.startsWith("firstly") || lower.startsWith("first,") ||
                lower.startsWith("next,") || lower.startsWith("in the meantime") ||
                lower.startsWith("meanwhile") ||
                (lower.startsWith("then ") && d.length > 40) ||
                // Makros / Serving-Hinweise (Caption-Footer) – keine Zutaten
                lower.startsWith("entire recipe macros") ||
                lower.startsWith("approx macros") ||
                lower.startsWith("macros per") ||
                lower.startsWith("per serving") ||
                lower.startsWith("adjust serving") ||
                lower.startsWith("recipe by") ||
                lower.startsWith("meal prep container") ||
                lower.startsWith("store frozen") ||
                lower.startsWith("full recipe below") ||
                lower.startsWith("quick lesson") ||
                lower == "macros" ||
                // Nummerierte Zubereitungsschritte
                (Regex("""^\d+[.)]\s+""").containsMatchIn(d) &&
                    Regex(
                        """\b(mix|add|stir|pour|bake|cook|heat|divide|refrigerate|spoon|blend|season|fry|simmer|coat|chop|slice|preheat|remove|cover|place|verrühren|verruehren|geben|garen|auskühlen|auskuehlen|stellen|erwärmen|erwaermen|verteilen|schneiden|mikrowelle|pausieren)\b""",
                        RegexOption.IGNORE_CASE
                    ).containsMatchIn(d)) ||
                // Langer Prosa-Absatz mit mehreren Kochverben
                (d.length > 100 &&
                    Regex(
                        """\b(mix|stir|cook|fry|simmer|season|until|minutes)\b""",
                        RegexOption.IGNORE_CASE
                    ).findAll(d).count() >= 2)
        }.takeIf { it > 0 } ?: return text
        return lines.take(cut).joinToString("\n")
    }

    /** Bereinigt Zubereitungstext: Hashtags, Promo, reine Meta-Zeilen raus. */
    fun formatInstructionsText(raw: String): String {
        if (raw.isBlank()) return raw
        return raw.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                    !line.startsWith("#") &&
                    !line.lowercase().startsWith("save this") &&
                    !line.lowercase().contains("products linked") &&
                    !line.lowercase().contains("link in bio") &&
                    !(line.startsWith("@") && line.length < 40)
            }
            .joinToString("\n")
            .trim()
    }

    /** True wenn der Text zerquetscht ist ODER viele Junk-Zeilen (Makros/Schritte) enthält. */
    fun looksMashed(ingredients: String): Boolean {
        val lines = ingredients.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return false
        val qtyHits = Regex(
            """\d+[.,]?\d*\s*(?:g|kg|ml|l|el|tl|tsp|tbsp|cup|oz)\b""",
            RegexOption.IGNORE_CASE
        ).findAll(ingredients).count()
        // Viele Mengen, wenige Zeilen → klar zerquetscht
        if (qtyHits >= 4 && lines.size < qtyHits * 0.6) return true
        // Makros / Methodenschritte in der Zutatenliste
        val junk = lines.count { isJunkIngredientLine(it) }
        return junk >= 2
    }

    private fun isSectionHeaderLine(line: String): Boolean {
        val d = line.trim().trimStart('•', '-', '*', ' ').trim()
        if (d.length < 3) return false
        val lower = d.lowercase().trimEnd(':').trim()
        // Explizite Abschnitts-Marker (EN + DE + typische Back-/Koch-Abschnitte)
        if (lower.startsWith("für ") || lower.startsWith("for the ") || lower.startsWith("for ") ||
            lower.startsWith("served with") || lower.startsWith("dazu") ||
            lower.startsWith("beilage") || lower.startsWith("sauce") ||
            lower.startsWith("marinade") || lower.startsWith("topping") ||
            lower.startsWith("dressing") || lower.startsWith("garnish") ||
            lower.startsWith("option to add") || lower.startsWith("optional") ||
            lower == "dough" || lower == "teig" ||
            lower == "filling" || lower == "füllung" || lower == "fuellung" ||
            lower == "base" || lower == "boden" ||
            lower == "frosting" || lower == "glasur" || lower == "icing" ||
            lower == "syrup" || lower == "sirup" ||
            lower == "batter" || lower == "teigmasse" ||
            lower == "crust" || lower == "boden" ||
            lower == "streusel" || lower == "glaze" ||
            lower.endsWith(" filling") || lower.endsWith(" füllung") || lower.endsWith(" fuellung") ||
            lower.endsWith(" frosting") || lower.endsWith(" glasur") ||
            lower.endsWith(" syrup") || lower.endsWith(" sirup") ||
            lower.endsWith(" dough") || lower.endsWith(" teig") ||
            lower.endsWith(" sauce") || lower.endsWith(" marinade") ||
            lower.endsWith(" topping") || lower.endsWith(" belag")
        ) {
            // "For the chicken: 2 breasts" ist KEIN reiner Header — splitHeaderFromFirstItem
            // kümmert sich darum. Hier true, damit die Zeile als Header-Kandidat gilt.
            return true
        }
        // "Ingredients (serves 2)" / "Zutaten für 4" = Meta, kein Abschnittsname für Split
        if (lower.startsWith("ingredients") || lower.startsWith("zutaten")) return true
        // Kurzer Titel ohne Menge
        if (Regex("""\d+[.,]?\d*\s*(g|kg|ml|l|el|tl|tsp|tbsp|cup|oz)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(d)
        ) return false
        if (d.first().isDigit()) return false
        // Reine GROSSBUCHSTABEN-Zeile ohne Menge = klassischer Social-Caption-Header
        // (DOUGH, CINNAMON COFFEE FILLING, COFFEE SYRUP, …)
        val lettersOnly = d.filter { it.isLetter() || it.isWhitespace() || it == '-' || it == '&' }
        if (lettersOnly.isNotBlank() && lettersOnly == lettersOnly.uppercase() &&
            lettersOnly.replace(" ", "").length in 3..40 &&
            !Regex("""\d""").containsMatchIn(d)
        ) return true
        // Emoji + kurzer Name (z.B. "Charred Zuckermais & beans 🫘")
        val withoutEmoji = d.replace(Regex("""[\p{So}\p{Cn}]"""), "").trim()
        return withoutEmoji.length in 3..48 &&
            !withoutEmoji.contains(Regex("""\d+\s*(g|ml)""", RegexOption.IGNORE_CASE))
    }

    /**
     * Fügt Newlines vor Mengen und Abschnitts-Markern ein.
     */
    private fun splitInlineIngredients(input: String): String {
        var c = input
        // Abschnitts-Marker (Wortanfang, damit "for" in "Ingredients for" nicht splitet)
        c = c.replace(
            Regex(
                """(?<=\S)\s*(?=(?:Für\s+(?:die|den|das)\s+\p{L}|Für\s+\p{L}|For the\s+\p{L}|Served with|Dazu\b|Beilage\b|Charred\s+\p{L}|Hot Honig\s+\p{L}|Hot Honey\s+\p{L}|Ingredients for\s+\d|Zutaten(?:\s+für)?\s+\p{L}))""",
                RegexOption.IGNORE_CASE
            ),
            "\n"
        )
        // "For the chicken:" / "Für die Sauce:" am Zeilenanfang oder nach Text — eigene Zeile
        c = c.replace(
            Regex(
                """(?i)(?<=\S)\s*(?=(?:For the\s+[^:\n]{2,30}|Für\s+(?:die|den|das)\s+[^:\n]{2,30})\s*:)"""
            ),
            "\n"
        )
        // Vor Mengen: 600g / 15 ml / 1 Tsp / 2 Tbsp / 76.5 g / 1/2 Limette
        // Nicht splitten bei "(about 20g)" / "(optional)" — negative Lookbehind auf "("
        val unit = """g|kg|ml|l|EL|TL|Tsp|Tbsp|tsp|tbsp|cup|cups|oz|lb|Stück|Stk|pcs|Prise|Tasse|clove|cloves|Portion|portion"""
        // Nach Buchstabe → neue Menge mit Einheit (nicht nach "about"/"ca." in Klammern)
        c = c.replace(
            Regex(
                """(?<=[a-zäöüßA-ZÄÖÜ)])\s+(?=(?!about\b|ca\.?\b|approx)(\d+[.,]?\d*|\d+/\d+|¼|½|¾)\s*($unit)\b)""",
                RegexOption.IGNORE_CASE
            ),
            "\n"
        )
        // Nach Nicht-Ziffer vor Menge mit Einheit (Emoji etc.), nicht in Klammern
        c = c.replace(
            Regex(
                """(?<=[^\d\s.,/(])\s+(?=(\d+[.,]?\d*|\d+/\d+|¼|½|¾)\s*($unit)\b)""",
                RegexOption.IGNORE_CASE
            ),
            "\n"
        )
        // Brüche ohne Einheit: "1/2 Limette", "1/2 rote Zwiebel"
        c = c.replace(
            Regex("""(?<=[^\d\s.,/])\s+(?=\d+/\d+\s+\p{L})"""),
            "\n"
        )
        // Zählzutaten: "2 chicken breasts", "2 garlic cloves"
        c = c.replace(
            Regex(
                """(?<=[a-zäöüßA-ZÄÖÜ)])\s+(?=(\d+)\s+(?:chicken|hähnchen|haehnchen|breast|breasts|egg|eggs|ei|eier|onion|zwiebel|shallot|schalotte|garlic|knoblauch|clove|cloves|tomato|tomate)\b)""",
                RegexOption.IGNORE_CASE
            ),
            "\n"
        )
        // Newlines vor "Ingredients for N servings" / "Ingredients (serves 2)"
        c = c.replace(
            Regex("""(?i)(?<=\S)\s*(?=Ingredients\s*(\(|for\s+\d|serves?))"""),
            "\n"
        )
        // Prosa-Start: "Firstly," / "Next," → eigene Zeile (cutOffInstructions greift danach)
        c = c.replace(
            Regex("""(?i)(?<=\S)\s*(?=(?:Firstly|First,|Next,|In the meantime|Meanwhile|Then season|Then add|Then fry)\b)"""),
            "\n"
        )
        return c
    }

    /**
     * Extracts a clean recipe title (dish name) from a raw caption, stripping
     * the Instagram metadata prefix and skipping hashtag/metric/date lines.
     */
    fun extractTitle(caption: String, fallback: String = "Rezept"): String {
        val cleaned = cleanCaption(caption)
        val lines   = cleaned.lines().map { it.trim() }.filter { it.isNotBlank() }
        return lines.firstOrNull { line ->
            val lower = line.lowercase()
            line.length > 3 &&
            line.any { it.isLetter() } &&
            !line.startsWith("#") &&
            !Regex("""^\d+[.,]?\d*\s*[KkMm]?\s*(likes?|comments?|followers|views)""", RegexOption.IGNORE_CASE).containsMatchIn(line) &&
            !Regex("""^\d{4}-\d{2}-\d{2}""").containsMatchIn(line) &&
            !lower.startsWith("zutaten") &&
            !lower.startsWith("ingredients") &&
            !lower.startsWith("zubereitung") &&
            !lower.startsWith("for the ") &&
            !lower.startsWith("für ") &&
            !lower.startsWith("method") &&
            !lower.startsWith("instructions") &&
            !lower.startsWith("served with")
        }?.take(80) ?: fallback
    }

    // ── LLM call ──────────────────────────────────────────────────────────────

    private val recipeSystemPrompt = """
You are a recipe extraction assistant. Extract structured recipe data from social media captions.
Respond ONLY with valid JSON matching this exact schema — no markdown, no explanation:
{
  "title": "Clean recipe dish name (string)",
  "description": "1-2 sentence description of the dish (string)",
  "servings": 4,
  "calories_per_serving": 548,
  "protein_g": 51,
  "carbs_g": 52,
  "fat_g": 17,
  "prep_time_minutes": null,
  "ingredient_sections": [
    {
      "section_name": "Chipotle Chicken Marinade",
      "items": ["1200g Raw Boneless Chicken Thighs", "2.5 Tsp Salt"]
    }
  ],
  "instructions": "Step-by-step instructions as a single string. Use \\n between steps.",
  "tags": "meal-prep,chicken,high-protein"
}
Rules:
- title: Extract the DISH NAME ONLY. Rules in priority order:
  1. If caption contains a line that IS clearly a food/dish name (e.g. "High Protein Pasta Salad", "Butter Chicken Burritos", "Marry Me Chicken"), use that
  2. If caption starts with descriptive text ("Wirklich ausgezeichnet...", "This is amazing..."), look for a dish name LATER in the caption near the ingredient list
  3. NEVER use: likes/comments counts, usernames, dates, hashtags, promotional text, "Ingredients (serves N)", "For the chicken", generic phrases like "Check this out"
  4. If truly no dish name exists, construct one from the main ingredients (e.g. "Pasta Salat mit Thunfisch")
- servings: extract the number of PORTIONS/SERVINGS this recipe makes. Look for "Makes X", "Ergibt X", "serves X", "für X Personen", "X Portionen". If the caption says "Per Burrito" or "Per Serving" that means 1 serving in the macros. Default to 1 if unclear, NOT a random number.
- ingredient_sections: group by section headers exactly as written (e.g. "For the chicken", "For the sauce", "Served with", "Marinade", "Topping", "DOUGH", "CINNAMON COFFEE FILLING", "COFFEE SYRUP", "FROSTING"). ALL-CAPS lines without quantities are section headers — never put them inside items. Items separated by "-", "•", "*", or newlines. If no sections, use one section named "".
- CRITICAL: Each ingredient item must be ONE ingredient only (e.g. "2 chicken breasts", "1 tsp olive oil", "150ml chicken stock") — NEVER merge multiple ingredients into one string.
- CRITICAL: Section headers must NOT include the first ingredient. Wrong: "For the chicken: 2 chicken breasts". Right: section_name="For the chicken", items=["2 chicken breasts", ...].
- CRITICAL ingredient format: always "quantity unit name" or "quantity name" for countable items. Examples: "2 Weetbix", "75 ml milk", "2 tbsp Greek yogurt", "1 tsp cream cheese", "1 Biscoff biscuit". NEVER invent grams for countable items (Weetbix, biscuits, cookies, eggs, onions). NEVER leave filler words in the name ("of", "heaped", "level", "whole", "crushed", "melted") — put modifiers only if essential, prefer clean names.
- Prefer original spoon units (tsp/tbsp) over guessed gram conversions for small amounts.
- Items may be plain strings OR objects {"quantity":"2","unit":"tbsp","name":"Greek yogurt"} — prefer clean string form "2 tbsp Greek yogurt".
- "Option to add X" / "Optional: X" → own section_name (e.g. "Optional") with the optional ingredient as item, not merged into another section.
- NEVER put into ingredient items: cooking instructions ("Firstly, season…", "Next, fry…", "In the meantime…"), macro summaries ("265 kcals", "39g | P"), numbered method steps ("1. Mix…"), hashtags, @mentions, "Method", "Zubereitung", promo ("Save this", "link in bio"), "Ingredients (serves 2)".
- "Served with: Mashed potato / broccoli" → own section "Served with" with those items — they are sides, not instructions.
- Prefer lines that look like "40g oat flour", "1 tsp oregano", "2 chicken breasts" over any surrounding caption noise.
- calories_per_serving / protein_g / carbs_g / fat_g: extract PER SERVING values if mentioned, else null
- instructions: cooking steps only (Firstly… / Next… / numbered). No ingredient lists, no hashtags, no promo lines. null if not present.
- tags: comma-separated, max 5, lowercase
- All numeric fields must be numbers (not strings), null if unknown
- Ignore: "Comment X for...", "DM me for...", "Link in bio", hashtags, storage/heating tips unless they are actual steps
    """.trimIndent()

    /**
     * Ruft Gemini und Groq PARALLEL auf (statt sequentiell mit Fallback) und
     * nimmt das erste erfolgreiche Ergebnis. Schlägt der zuerst antwortende
     * Provider fehl, wird auf den anderen gewartet statt einen weiteren
     * sequentiellen Request zu starten. Vermeidet die worst-case Latenz von
     * "Gemini-Timeout + Groq-Call" (~30s+) zugunsten von max(Gemini, Groq).
     */
    private suspend fun callLlm(caption: String, apiKey: String, fastModel: Boolean = false): Recipe = coroutineScope {
        val userMessage = "Extract recipe from this caption:\n\n$caption"

        if (!GeminiService.isAvailable()) {
            return@coroutineScope callGroq(caption, apiKey, fastModel)
        }

        val geminiJob: Deferred<Result<Recipe>> = async {
            runCatching {
                val response = GeminiService.generateText(
                    prompt = userMessage,
                    systemPrompt = recipeSystemPrompt,
                    temperature = 0.1,
                    maxTokens = 2000
                )
                val content = response.getOrThrow()
                    .trim()
                    .removePrefix("```json").removePrefix("```").removeSuffix("```")
                    .trim()
                parseLlmJson(JSONObject(content))
            }
        }
        val groqJob: Deferred<Result<Recipe>> = async {
            runCatching { callGroq(caption, apiKey, fastModel) }
        }

        val (winnerJob, winnerResult) = select<Pair<Deferred<Result<Recipe>>, Result<Recipe>>> {
            geminiJob.onAwait { geminiJob to it }
            groqJob.onAwait { groqJob to it }
        }
        val loserJob = if (winnerJob === geminiJob) groqJob else geminiJob

        if (winnerResult.isSuccess) {
            loserJob.cancel()
            winnerResult.getOrThrow()
        } else {
            // Zuerst antwortender Provider ist fehlgeschlagen — auf den anderen warten
            // statt sofort aufzugeben.
            loserJob.await().getOrThrow()
        }
    }

    private fun callGroq(caption: String, apiKey: String, fastModel: Boolean = false): Recipe {
        val userMessage = "Extract recipe from this caption:\n\n$caption"
        // 8B Instant: deutlich niedrigere Latenz auf Groq Free-Tier; 70B: bessere Struktur.
        val modelId = if (fastModel) "llama-3.1-8b-instant" else "llama-3.3-70b-versatile"

        val body = JSONObject().apply {
            put("model", modelId)
            put("max_tokens", 2000)
            put("temperature", 0.1)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", recipeSystemPrompt) })
                put(JSONObject().apply { put("role", "user");   put("content", userMessage) })
            })
        }.toString()

        val req = Request.Builder()
            .url(GROQ_URL)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()

        val responseText = client.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: throw Exception("Empty Groq response")
            if (!resp.isSuccessful) throw Exception("Groq error ${resp.code}: $bodyStr")
            bodyStr
        }

        val content = JSONObject(responseText)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```")
            .trim()

        return parseLlmJson(JSONObject(content))
    }

    /**
     * Items können String ("2 tbsp Greek yogurt") oder Objekt
     * {"quantity":"2","unit":"tbsp","name":"Greek yogurt"} sein.
     */
    private fun formatIngredientItem(raw: Any?): String {
        if (raw == null || raw == JSONObject.NULL) return ""
        if (raw is String) return raw.trim()
        if (raw is JSONObject) {
            val qty = raw.optString("quantity", raw.optString("amount", "")).trim()
            val unit = raw.optString("unit", "").trim()
            val name = raw.optString("name", raw.optString("ingredient", "")).trim()
            if (name.isBlank() && qty.isBlank()) return ""
            return listOf(qty, unit, name).filter { it.isNotBlank() }.joinToString(" ")
        }
        return raw.toString().trim()
    }

    private fun parseLlmJson(j: JSONObject): Recipe {
        // Build ingredients string from sections
        val sectionsArr = j.optJSONArray("ingredient_sections")
        val ingredients = buildString {
            if (sectionsArr != null) {
                for (i in 0 until sectionsArr.length()) {
                    val section = sectionsArr.getJSONObject(i)
                    val sectionName = section.optString("section_name", "")
                    if (sectionName.isNotBlank()) {
                        append("$sectionName\n")
                    }
                    val items = section.optJSONArray("items")
                    if (items != null) {
                        for (k in 0 until items.length()) {
                            val itemLine = formatIngredientItem(items.opt(k))
                            if (itemLine.isNotBlank()) append("• $itemLine\n")
                        }
                    }
                    if (i < sectionsArr.length() - 1) append("\n")
                }
            }
        }.trim().let { formatIngredientText(it) }

        // optString liefert bei JSON-null den Literal-String "null" — daher safeString.
        val instructions = formatInstructionsText(j.safeString("instructions"))
        val servings = j.optInt("servings", 1).coerceAtLeast(1)
        val cals = if (j.isNull("calories_per_serving")) null
                   else j.optDouble("calories_per_serving").toFloat().takeIf { it > 0 }
        val totalCals = cals?.let { it * servings }

        // Build description with macros if available
        val baseDesc = j.safeString("description")
        val macroLine = buildString {
            cals?.let { append("${it.toInt()} kcal") }
            val p = if (j.isNull("protein_g")) null else j.optDouble("protein_g").toFloat().takeIf { it > 0 }
            val c = if (j.isNull("carbs_g"))   null else j.optDouble("carbs_g").toFloat().takeIf { it > 0 }
            val f = if (j.isNull("fat_g"))     null else j.optDouble("fat_g").toFloat().takeIf { it > 0 }
            if (p != null) append(" · ${p.toInt()}g Protein")
            if (c != null) append(" · ${c.toInt()}g Kohlenhydrate")
            if (f != null) append(" · ${f.toInt()}g Fett")
        }.trim()

        val description = when {
            baseDesc.isNotBlank() && macroLine.isNotBlank() -> "$baseDesc\n\n📊 Pro Portion: $macroLine"
            macroLine.isNotBlank() -> "📊 Pro Portion: $macroLine"
            else -> baseDesc
        }

        val prepTime = if (j.isNull("prep_time_minutes")) null
                       else j.optInt("prep_time_minutes", 0).takeIf { it > 0 }

        val proteinG = if (j.isNull("protein_g")) null else j.optDouble("protein_g").toFloat().takeIf { it > 0 }
        val carbsG   = if (j.isNull("carbs_g"))   null else j.optDouble("carbs_g").toFloat().takeIf { it > 0 }
        val fatG     = if (j.isNull("fat_g"))      null else j.optDouble("fat_g").toFloat().takeIf { it > 0 }

        val rawTitle = j.safeString("title", "Rezept")
        return Recipe(
            title              = rawTitle.ifBlank { "Rezept" },
            description        = description,
            ingredients        = ingredients.ifBlank { "Zutaten nicht gefunden." },
            instructions       = instructions,
            servings           = servings,
            totalCalories      = totalCals,
            proteinPerServing  = proteinG,
            carbsPerServing    = carbsG,
            fatPerServing      = fatG,
            prepTimeMinutes    = prepTime,
            tags               = j.safeString("tags").take(200)
        )
    }

    /**
     * Android [JSONObject.optString] gibt bei JSON-null den **String** `"null"` zurück.
     * Diese Hilfsfunktion behandelt null / "null" / "undefined" als fehlend.
     */
    private fun JSONObject.safeString(key: String, default: String = ""): String {
        if (!has(key) || isNull(key)) return default
        val v = optString(key, default).trim()
        return if (v.isEmpty() ||
            v.equals("null", ignoreCase = true) ||
            v.equals("undefined", ignoreCase = true)
        ) default else v
    }

    // ── Fallback (no AI) ───────────────────────────────────────────────────────

    fun fallbackParse(
        caption:   String,
        sourceUrl: String?,
        platform:  String,
        imageUrl:  String?
    ): Recipe {
        val cleaned = cleanCaption(caption)
        val lower   = cleaned.lowercase()
        val lines   = cleaned.lines().map { it.trim() }.filter { it.isNotBlank() }

        val title = extractTitle(cleaned,
            fallback = if (platform == "tiktok") "TikTok Rezept" else "Instagram Rezept")

        val servings = Regex("""(?:makes?|für|ergibt|serves?|portionen?|servings?)\s*(\d+)""",
            RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val cals = Regex("""(\d{3,4})\s*(?:cal|kcal|calories)""", RegexOption.IGNORE_CASE)
            .find(cleaned)?.groupValues?.get(1)?.toFloatOrNull()

        val instrKw = listOf("zubereitung", "anleitung", "zubereiten", "preparation",
            "method", "instructions", "directions", "steps", "how to make", "how to:",
            "firstly", "first,", "next,", "in the meantime", "meanwhile",
            "preheat oven", "preheat the oven", "vorheizen")
        // Nur echte Abschnitts-Header, nicht Promo-Sätze wie
        // "The ingredients with a * are from @prozis (code …)"
        val ingrHeaderRegexes = listOf(
            Regex("""(?m)^(?:✨\s*)?recipe(?:\s*✨)?\s*$""", RegexOption.IGNORE_CASE),
            Regex("""(?m)^(?:zutaten|ingredients)\s*:?\s*$""", RegexOption.IGNORE_CASE),
            Regex("""(?m)^(?:zutaten|ingredients)\s*\([^)]*serves?""", RegexOption.IGNORE_CASE),
            Regex("""(?m)^(?:zutaten|ingredients)\s*\([^)]*portion""", RegexOption.IGNORE_CASE),
            Regex("""(?m)^(?:du brauchst|you need|what you need|you will need|what you'll need)\s*:?\s*$""", RegexOption.IGNORE_CASE),
            Regex("""(?m)^(?:zutaten|ingredients)\s*:""", RegexOption.IGNORE_CASE)
        )

        fun findSectionIndex(keywords: List<String>): Int? =
            keywords.firstNotNullOfOrNull { kw ->
                lower.indexOf(kw).takeIf { i -> i > 0 }
            }

        fun findIngredientHeaderIndex(): Int? {
            for (re in ingrHeaderRegexes) {
                val m = re.find(cleaned) ?: continue
                return m.range.first
            }
            // Fallback: "ingredients"/"zutaten" nur wenn am Zeilenanfang und
            // die Zeile kurz ist (Header), nicht mitten in einem Promo-Satz.
            for ((idx, line) in lines.withIndex()) {
                val t = line.trim().lowercase()
                if (t.length > 60) continue
                if (t.startsWith("ingredients") || t.startsWith("zutaten") ||
                    t == "recipe" || t.startsWith("✨recipe") || t.endsWith("recipe✨")
                ) {
                    // Promo-Sätze rausfiltern
                    if (t.contains("with a *") || t.contains("from @") ||
                        t.contains("discount") || t.contains("code ") ||
                        t.contains("prozis") || t.contains("will give you")
                    ) continue
                    // Index im cleaned-String: Position der Zeile
                    var pos = 0
                    for (j in 0 until idx) {
                        pos = cleaned.indexOf(lines[j], pos).let { if (it < 0) pos else it + lines[j].length }
                    }
                    val found = cleaned.indexOf(line, pos).takeIf { it >= 0 } ?: continue
                    return found
                }
            }
            return null
        }

        val instrIdx = findSectionIndex(instrKw)
        val ingrIdx  = findIngredientHeaderIndex()

        val ingredientLineRegex = Regex(
            """^(?:[-•*]|\d+\s*(?:g|ml|l|kg|cup|cups|tbsp?|tsp?|oz|lb|St[üu]ck|stk\.?|EL|TL|Prise|Tasse|Zehe))|""" +
            """^\d+[.,]?\d*\s+\w""",
            RegexOption.IGNORE_CASE
        )

        // Mengen-Zeilen + Abschnitts-Header (DOUGH / Füllung / …) in Original-Reihenfolge.
        // Früher nur qty-Zeilen → Header ohne Zahl (DOUGH, FROSTING) gingen verloren.
        fun isQtyIngredientLine(line: String): Boolean =
            ingredientLineRegex.containsMatchIn(line) ||
                line.startsWith("-") || line.startsWith("•") ||
                (line.startsWith("*") && Regex("""\d""").containsMatchIn(line))

        val qtyIngrLines = lines.filter { line ->
            isQtyIngredientLine(line) && !isJunkIngredientLine(line) && !isPromoIngredientNoise(line)
        }

        /** Mengen + Section-Header zwischen erster und letzter Zutat (inkl. Header davor). */
        fun linesWithSectionHeaders(qtyLines: List<String>): String {
            if (qtyLines.isEmpty()) return ""
            val qtyTrim = qtyLines.map { it.trim() }.toSet()
            val indices = lines.mapIndexedNotNull { i, line ->
                if (line.trim() in qtyTrim) i else null
            }
            if (indices.isEmpty()) return qtyLines.joinToString("\n")
            var start = indices.first()
            // Header direkt vor dem Block mitnehmen (DOUGH vor 240 g …)
            while (start > 0 && isSectionHeaderLine(lines[start - 1]) &&
                !isJunkIngredientLine(lines[start - 1]) &&
                !isPromoIngredientNoise(lines[start - 1])
            ) {
                start--
            }
            val end = indices.last()
            return lines.subList(start, end + 1)
                .filter { line ->
                    val t = line.trim()
                    if (t.isEmpty()) return@filter false
                    if (isJunkIngredientLine(t) || isPromoIngredientNoise(t)) return@filter false
                    isQtyIngredientLine(t) || isSectionHeaderLine(t)
                }
                .joinToString("\n")
        }

        val ingredients = when {
            qtyIngrLines.size >= 2 -> linesWithSectionHeaders(qtyIngrLines)
            ingrIdx != null -> {
                // Instruktionen können VOR den Zutaten stehen (begin > end) —
                // dann bis Textende nehmen, nicht bis zum früheren Instr-Index.
                val end = when {
                    instrIdx != null && instrIdx > ingrIdx -> instrIdx
                    else -> cleaned.length
                }.coerceIn(ingrIdx, cleaned.length)
                cleaned.substring(ingrIdx, end).trim()
            }
            else -> {
                val hashtagStart = lines.indexOfFirst { it.startsWith("#") }.takeIf { it > 0 }
                val bodyLines = lines.drop(1).take(hashtagStart?.minus(1) ?: 30)
                bodyLines.joinToString("\n").ifBlank { cleaned.take(1200) }
            }
        }

        // Wenn Zubereitung vor Zutaten steht: nur bis zum Zutaten-Block
        val instructions = when {
            instrIdx == null -> {
                // Ohne Keyword: ab erster klarer Koch-Anweisung (Preheat / Bake / …)
                val stepStart = lines.indexOfFirst { line ->
                    val l = line.lowercase().trim()
                    l.startsWith("preheat") || l.startsWith("vorheizen") ||
                        l.startsWith("bake ") || l.startsWith("mix all") ||
                        (l.startsWith("place ") && l.length > 40) ||
                        (l.startsWith("split ") && l.length > 40)
                }.takeIf { it >= 0 }
                if (stepStart != null) lines.drop(stepStart).joinToString("\n") else ""
            }
            ingrIdx != null && instrIdx < ingrIdx ->
                cleaned.substring(instrIdx, ingrIdx).trim()
            else ->
                cleaned.substring(instrIdx).trim()
        }

        return Recipe(
            title           = title,
            description     = cals?.let { "📊 Pro Portion: ${it.toInt()} kcal" } ?: "",
            ingredients     = formatIngredientText(ingredients)
                .ifBlank { "Tippe ✏️ um Zutaten hinzuzufügen." },
            instructions    = formatInstructionsText(instructions),
            servings        = servings,
            totalCalories   = cals?.let { it * servings },
            sourceUrl       = sourceUrl,
            platform        = platform,
            imageUrl        = imageUrl,
            tags            = platform
        )
    }
}
