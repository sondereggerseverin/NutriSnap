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

        fun isWeakIngredients(s: String) =
            s.isBlank() ||
                s.equals("Zutaten nicht gefunden.", true) ||
                s.startsWith("Tippe") ||
                s.length < 20

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

        val instructions = ai.instructions.trim()
            .takeUnless { it.isBlank() || it.equals("null", true) }
            ?: fallback.instructions

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
     * Strips the "X likes, Y comments - username on Date:" prefix that
     * Instagram/mirror sites prepend to og:description captions, and removes
     * surrounding quote marks. Safe to call on already-clean text (no-op).
     */
    fun cleanCaption(raw: String): String {
        val prefixRegex = Regex(
            """^[\d.,]+\s*(?:likes?|Likes?)\s*,?\s*[\d.,]*\s*(?:comments?|Comments?)?\s*-\s*\S+\s+on\s+[^:]+:\s*""",
            RegexOption.IGNORE_CASE
        )
        var c = prefixRegex.replace(raw.trim(), "").trim()
        // Strip surrounding straight or curly quotes left over from the caption
        c = c.removeSurrounding("\"").removeSurrounding("\u201c", "\u201d").trim()
        // Normalize TikTok/Instagram "*" ingredient separator → newlines
        if (c.contains("* ") && !c.contains("\n")) {
            c = c.replace(Regex("\\*(?=\\s*\\d|\\s*[¼½¾])"), "\n•")
        }
        // Immer Mengen/Abschnitte trennen – auch wenn schon einzelne Newlines da sind
        // (sonst bleibt "600g Hähnchen 15 ml Öl 1 Tsp Paprika" in einer Zeile)
        c = splitInlineIngredients(c)
        return c.ifBlank { raw.trim() }
    }

    /**
     * Zerlegt Caption-/Zutaten-Blöcke, in denen mehrere Zutaten in einer Zeile
     * kleben (typisch TikTok/Instagram), in saubere Zeilen mit Abschnitts-Headern.
     * Entfernt Hashtags. Idempotent auf bereits formatierten Listen.
     */
    fun formatIngredientText(raw: String): String {
        if (raw.isBlank()) return raw
        var t = raw.trim()
        // Hashtags am Ende entfernen
        t = t.replace(Regex("""(?:\s*#\w+)+[\s:]*$""", RegexOption.IGNORE_CASE), "").trim()
        t = t.replace(Regex("""#\w+"""), " ").trim()
        t = splitInlineIngredients(t)
        // Doppelte Leerzeilen kollabieren, Aufzählungszeichen vereinheitlichen
        return t.lines()
            .map { it.trim().trimStart('•', '-', '*', ' ').trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n") { line ->
                if (isSectionHeaderLine(line)) line.trimEnd(':').trim()
                else "• $line"
            }
    }

    /** True wenn der Text wie ein ungegliederter Caption-Klumpen aussieht. */
    fun looksMashed(ingredients: String): Boolean {
        val lines = ingredients.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return false
        val qtyHits = Regex(
            """\d+[.,]?\d*\s*(?:g|kg|ml|l|el|tl|tsp|tbsp|cup|oz)\b""",
            RegexOption.IGNORE_CASE
        ).findAll(ingredients).count()
        // Viele Mengen, wenige Zeilen → klar zerquetscht
        return qtyHits >= 4 && lines.size < qtyHits * 0.6
    }

    private fun isSectionHeaderLine(line: String): Boolean {
        val d = line.trim().trimStart('•', '-', '*', ' ').trim()
        if (d.length < 3) return false
        val lower = d.lowercase()
        if (lower.startsWith("für ") || lower.startsWith("for ") ||
            lower.startsWith("ingredients") || lower.startsWith("zutaten")
        ) return true
        // Kurzer Titel ohne Menge
        if (Regex("""\d+[.,]?\d*\s*(g|kg|ml|l|el|tl|tsp|tbsp)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(d)
        ) return false
        if (d.first().isDigit()) return false
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
                """(?<=\S)\s*(?=(?:Für\s+\p{L}|For the\s+\p{L}|Charred\s+\p{L}|Hot Honig\s+\p{L}|Hot Honey\s+\p{L}|Ingredients for\s+\d|Zutaten(?:\s+für)?\s+\p{L}))""",
                RegexOption.IGNORE_CASE
            ),
            "\n"
        )
        // Vor Mengen: 600g / 15 ml / 1 Tsp / 2 Tbsp / 76.5 g / 1/2 Limette
        val unit = """g|kg|ml|l|EL|TL|Tsp|Tbsp|tsp|tbsp|cup|cups|oz|lb|Stück|Stk|pcs|Prise|Tasse"""
        // Nach Buchstabe/Klammer → neue Menge mit Einheit
        c = c.replace(
            Regex(
                """(?<=[a-zäöüßA-ZÄÖÜ)])\s+(?=(\d+[.,]?\d*|\d+/\d+)\s*($unit)\b)""",
                RegexOption.IGNORE_CASE
            ),
            "\n"
        )
        // Nach Nicht-Ziffer vor Menge mit Einheit (Emoji etc.)
        c = c.replace(
            Regex(
                """(?<=[^\d\s.,/])\s+(?=(\d+[.,]?\d*|\d+/\d+)\s*($unit)\b)""",
                RegexOption.IGNORE_CASE
            ),
            "\n"
        )
        // Brüche ohne Einheit: "1/2 Limette", "1/2 rote Zwiebel"
        c = c.replace(
            Regex("""(?<=[^\d\s.,/])\s+(?=\d+/\d+\s+\p{L})"""),
            "\n"
        )
        // Newlines vor "Ingredients for N servings"
        c = c.replace(
            Regex("""(?i)(?<=\S)\s*(?=Ingredients\s+for\s+\d+)"""),
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
            line.length > 3 &&
            line.any { it.isLetter() } &&
            !line.startsWith("#") &&
            !Regex("""^\d+[.,]?\d*\s*[KkMm]?\s*(likes?|comments?|followers|views)""", RegexOption.IGNORE_CASE).containsMatchIn(line) &&
            !Regex("""^\d{4}-\d{2}-\d{2}""").containsMatchIn(line) &&
            !line.lowercase().startsWith("zutaten") &&
            !line.lowercase().startsWith("zubereitung")
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
  1. If caption contains a line that IS clearly a food/dish name (e.g. "High Protein Pasta Salad", "Butter Chicken Burritos"), use that
  2. If caption starts with descriptive text ("Wirklich ausgezeichnet...", "This is amazing..."), look for a dish name LATER in the caption near the ingredient list
  3. NEVER use: likes/comments counts, usernames, dates, hashtags, promotional text, generic phrases like "Check this out"
  4. If truly no dish name exists, construct one from the main ingredients (e.g. "Pasta Salat mit Thunfisch")
- servings: extract the number of PORTIONS/SERVINGS this recipe makes. Look for "Makes X", "Ergibt X", "für X Personen", "X Portionen". If the caption says "Per Burrito" or "Per Serving" that means 1 serving in the macros. Default to 1 if unclear, NOT a random number.
- ingredient_sections: group by section headers (e.g. "Marinade", "Sauce", "Topping"). Items separated by "-", "•", "*", or newlines. If no sections, use one section named "".
- CRITICAL: Each ingredient item must be ONE ingredient only (e.g. "200g Hähnchenbrust"), NOT a full sentence or instruction.
- calories_per_serving / protein_g / carbs_g / fat_g: extract PER SERVING values if mentioned, else null
- instructions: numbered steps only, no ingredient lists. null if not present.
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
                            append("• ${items.getString(k)}\n")
                        }
                    }
                    if (i < sectionsArr.length() - 1) append("\n")
                }
            }
        }.trim().let { formatIngredientText(it) }

        // optString liefert bei JSON-null den Literal-String "null" — daher safeString.
        val instructions = j.safeString("instructions")
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
            "method", "instructions", "directions", "steps", "how to make", "how to:")
        val ingrKw  = listOf("zutaten", "ingredients", "du brauchst", "you need",
            "what you need", "you will need", "ingredients:", "what you'll need")

        val instrIdx = instrKw.firstNotNullOfOrNull { lower.indexOf(it).takeIf { i -> i > 0 } }
        val ingrIdx  = ingrKw.firstNotNullOfOrNull  { lower.indexOf(it).takeIf { i -> i >= 0 } }

        val ingredientLineRegex = Regex(
            """^(?:[-•*]|\d+\s*(?:g|ml|l|kg|cup|cups|tbsp?|tsp?|oz|lb|St[üu]ck|stk\.?|EL|TL|Prise|Tasse|Zehe))|""" +
            """^\d+[.,]?\d*\s+\w""",
            RegexOption.IGNORE_CASE
        )

        val ingredients = when {
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
                val ingrLines = lines.filter { line ->
                    ingredientLineRegex.containsMatchIn(line) ||
                    line.startsWith("-") || line.startsWith("•") || line.startsWith("*")
                }
                if (ingrLines.size >= 2) {
                    ingrLines.joinToString("\n")
                } else {
                    val hashtagStart = lines.indexOfFirst { it.startsWith("#") }.takeIf { it > 0 }
                    val bodyLines = lines.drop(1).take(hashtagStart?.minus(1) ?: 30)
                    bodyLines.joinToString("\n").ifBlank { cleaned.take(1200) }
                }
            }
        }

        // Wenn Zubereitung vor Zutaten steht: nur bis zum Zutaten-Block
        val instructions = when {
            instrIdx == null -> ""
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
            instructions    = instructions,
            servings        = servings,
            totalCalories   = cals?.let { it * servings },
            sourceUrl       = sourceUrl,
            platform        = platform,
            imageUrl        = imageUrl,
            tags            = platform
        )
    }
}
