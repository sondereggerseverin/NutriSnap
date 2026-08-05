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
    suspend fun parse(
        caption:  String,
        sourceUrl: String?,
        platform:  String,
        imageUrl:  String?,
        apiKey:    String
    ): Recipe = withContext(Dispatchers.IO) {
        val cleaned = cleanCaption(caption)
        val fallback = fallbackParse(cleaned, sourceUrl, platform, imageUrl)
        val aiResult = runCatching { callLlm(cleaned, apiKey) }.getOrNull()
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

        val ingredients = when {
            !isWeakIngredients(ai.ingredients) -> ai.ingredients
            !isWeakIngredients(fallback.ingredients) -> fallback.ingredients
            else -> ai.ingredients.ifBlank { fallback.ingredients }
        }

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
        // Normalize Instagram captions with no newlines but numbered steps/sections
        // e.g. "Zutaten:200g Mehl1 Ei..." → add newlines before numbers+units or section keywords
        if (!c.contains("\n") && c.length > 100) {
            // Before quantities like "200g", "1 EL", "2 TL", "1/2 cup"
            c = c.replace(Regex("(?<=[a-zäöüA-ZÄÖÜ,)])(?=\\d+[\\s,./]*(g|kg|ml|l|EL|TL|cup|tbsp|tsp|oz|lb|Stück|Stk|pcs|Scheiben|Zehe|Zweig|Prise)\\b)"), "\n")
            // Before section headers like "Für die Sauce", "Topping:", "Zubereitung:"
            c = c.replace(Regex("(?=(?:Für |For |Sauce|Dressing|Topping|Marinade|Zubereitung|Instructions?|Preparation|Steps?|Method):?)"), "\n")
            // Before numbered steps "1.", "2.", etc.
            c = c.replace(Regex("(?<=\\s)(?=[2-9]\\.|1[0-9]\\.)"), "\n")
        }
        return c.ifBlank { raw.trim() }
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
    private suspend fun callLlm(caption: String, apiKey: String): Recipe = coroutineScope {
        val userMessage = "Extract recipe from this caption:\n\n$caption"

        if (!GeminiService.isAvailable()) {
            return@coroutineScope callGroq(caption, apiKey)
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
            runCatching { callGroq(caption, apiKey) }
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

    private fun callGroq(caption: String, apiKey: String): Recipe {
        val userMessage = "Extract recipe from this caption:\n\n$caption"

        val body = JSONObject().apply {
            put("model", "llama-3.3-70b-versatile")
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
        }.trim()

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
                val end = (instrIdx ?: cleaned.length).coerceAtMost(cleaned.length)
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

        val instructions = instrIdx?.let { cleaned.substring(it).trim() } ?: ""

        return Recipe(
            title           = title,
            description     = cals?.let { "📊 Pro Portion: ${it.toInt()} kcal" } ?: "",
            ingredients     = ingredients.ifBlank { "Tippe ✏️ um Zutaten hinzuzufügen." },
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
