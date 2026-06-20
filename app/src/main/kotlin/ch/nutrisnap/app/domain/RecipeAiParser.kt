package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.Recipe
import kotlinx.coroutines.Dispatchers
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
        val cleaned  = cleanCaption(caption)
        val aiResult = runCatching { callGroq(cleaned, apiKey) }.getOrNull()
        aiResult ?: fallbackParse(cleaned, sourceUrl, platform, imageUrl)
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
        // Normalize TikTok's inline "*" ingredient separator → newlines so AI can parse it
        // e.g. "Makes 1½ cups* 1¼ cups Greek yogurt* 2 garlic cloves*..." → each on its own line
        if (c.contains("* ") && !c.contains("\n")) {
            c = c.replace(Regex("\\*(?=\\s*\\d|\\s*[¼½¾])"), "\n•")
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

    // ── Groq call ──────────────────────────────────────────────────────────────

    private fun callGroq(caption: String, apiKey: String): Recipe {
        val systemPrompt = """
You are a recipe extraction assistant. Extract structured recipe data from social media captions.
Respond ONLY with valid JSON matching this exact schema — no markdown, no explanation:
{
  "title": "Clean recipe name without hashtags/metrics (string)",
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
- title: DISH NAME ONLY - NEVER include likes/comments/follower counts/dates/usernames/handles. The caption often starts with "X likes, Y comments - username on Date: \"ACTUAL RECIPE NAME\"". Extract only ACTUAL RECIPE NAME. If quoted in caption use the quoted text. If section headers exist use the first one. Last resort: first meaningful ingredient section name
- ingredient_sections: group ingredients by their section headers (e.g. "Marinade", "Sauce"). If no sections exist, use one section named ""
- calories_per_serving / protein_g / carbs_g / fat_g: extract if mentioned, else null
- instructions: extract if present, else null
- tags: comma-separated, max 5, lowercase
- All numeric fields must be numbers (not strings), null if unknown
- IMPORTANT: Some captions (especially TikTok) list ingredients separated by "*" with no newlines, e.g. "* 1 cup flour* 2 eggs* ...". Split these into individual items.
- IMPORTANT: Ignore promotional text like "Comment X for...", "DM me for...", "Link in bio", hashtags — these are NOT recipe content.
        """.trimIndent()

        val userMessage = "Extract recipe from this caption:\n\n$caption"

        val body = JSONObject().apply {
            put("model", "llama-3.1-8b-instant")
            put("max_tokens", 1200)
            put("temperature", 0.1)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
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

        return parseGroqJson(JSONObject(content))
    }

    private fun parseGroqJson(j: JSONObject): Recipe {
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

        val instructions = j.optString("instructions", "").trim()
        val servings = j.optInt("servings", 1).coerceAtLeast(1)
        val cals = if (j.isNull("calories_per_serving")) null
                   else j.optDouble("calories_per_serving").toFloat().takeIf { it > 0 }
        val totalCals = cals?.let { it * servings }

        // Build description with macros if available
        val baseDesc = j.optString("description", "").trim()
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

        return Recipe(
            title              = j.optString("title", "Rezept").trim().ifBlank { "Rezept" },
            description        = description,
            ingredients        = ingredients.ifBlank { "Zutaten nicht gefunden." },
            instructions       = instructions.ifBlank { "" },
            servings           = servings,
            totalCalories      = totalCals,
            proteinPerServing  = proteinG,
            carbsPerServing    = carbsG,
            fatPerServing      = fatG,
            prepTimeMinutes    = prepTime,
            tags               = j.optString("tags", "").take(200)
        )
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
