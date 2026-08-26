package ch.nutrisnap.app.domain

import ch.nutrisnap.app.BuildConfig
import ch.nutrisnap.app.data.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AMM-ähnlicher Server-Import über Supabase Edge Function `recipe-normalize`.
 *
 * Flow:
 *   1) [importFromUrl] – nur Link → Server holt Caption + strukturiert (schnell)
 *   2) [normalize] – Client-Caption → Server strukturiert (Fallback)
 *
 * Deploy: docs/recipe-normalize-server.md
 * Secret: supabase secrets set GROQ_API_KEY=...
 */
object RecipeNormalizeServer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(28, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean =
        BuildConfig.SUPABASE_URL.isNotBlank() &&
            BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    /**
     * AMM-Pfad: nur URL schicken. Server fetched Caption und liefert Rezept.
     */
    suspend fun importFromUrl(
        sourceUrl: String,
        platform: String,
        imageUrl: String? = null
    ): Recipe? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        if (sourceUrl.isBlank()) return@withContext null
        postNormalize(
            caption = null,
            sourceUrl = sourceUrl,
            platform = platform,
            imageUrl = imageUrl
        )
    }

    /**
     * Caption schon vorhanden → nur normalisieren.
     */
    suspend fun normalize(
        caption: String,
        sourceUrl: String?,
        platform: String,
        imageUrl: String?
    ): Recipe? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        if (caption.trim().length < 20) return@withContext null
        postNormalize(
            caption = caption,
            sourceUrl = sourceUrl,
            platform = platform,
            imageUrl = imageUrl
        )
    }

    private fun postNormalize(
        caption: String?,
        sourceUrl: String?,
        platform: String,
        imageUrl: String?
    ): Recipe? = runCatching {
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val endpoint = "$base/functions/v1/recipe-normalize"

        val body = JSONObject().apply {
            put("platform", platform)
            if (!caption.isNullOrBlank()) put("caption", caption.take(12000))
            if (!sourceUrl.isNullOrBlank()) put("sourceUrl", sourceUrl)
            if (!imageUrl.isNullOrBlank()) put("imageUrl", imageUrl)
        }.toString()

        val req = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Content-Type", "application/json")
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return@runCatching null
            parseResponse(text, sourceUrl, platform, imageUrl)
        }
    }.getOrNull()

    private fun parseResponse(
        text: String,
        sourceUrl: String?,
        platform: String,
        imageUrl: String?
    ): Recipe? {
        val j = JSONObject(text)
        if (j.has("error")) return null

        val title = j.optString("title", "").trim()
            .takeIf { it.isNotBlank() && !it.equals("null", true) }
            ?: return null

        val ingredients = j.optString("ingredients", "").trim()
        val instructions = j.optString("instructions", "").trim()
        if (ingredients.length < 8 && instructions.length < 8) return null

        val servings = j.optInt("servings", 1).coerceAtLeast(1)
        val cals = j.optDouble("calories_per_serving")
            .takeIf { !j.isNull("calories_per_serving") && it > 0 }?.toFloat()
        val protein = j.optDouble("protein_g")
            .takeIf { !j.isNull("protein_g") && it > 0 }?.toFloat()
        val carbs = j.optDouble("carbs_g")
            .takeIf { !j.isNull("carbs_g") && it > 0 }?.toFloat()
        val fat = j.optDouble("fat_g")
            .takeIf { !j.isNull("fat_g") && it > 0 }?.toFloat()
        val prep = j.optInt("prep_time_minutes")
            .takeIf { !j.isNull("prep_time_minutes") && it > 0 }

        val desc = buildString {
            val d = j.optString("description", "").trim()
            if (d.isNotBlank() && !d.equals("null", true)) append(d)
            if (cals != null) {
                if (isNotEmpty()) append("\n\n")
                append("📊 Pro Portion: ${cals.toInt()} kcal")
                protein?.let { append(" · ${it.toInt()}g Protein") }
                carbs?.let { append(" · ${it.toInt()}g Kohlenhydrate") }
                fat?.let { append(" · ${it.toInt()}g Fett") }
            }
        }

        val cleanTitle = RecipeAiParser.cleanDishTitle(title, ingredients)
        // Server-Zutaten nur leicht säubern – formatIngredientText darf nichts Wichtiges killen
        val cleanedServerIng = ingredients.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !RecipeAiParser.isJunkIngredientLine(it) }
            .joinToString("\n")
        val formatted = RecipeAiParser.formatIngredientText(ingredients)
        // Wenn Formatter zu aggressiv war (Zeilen verloren), Server-Rohfassung behalten
        val serverLines = cleanedServerIng.lines().count { it.isNotBlank() }
        val formattedLines = formatted.lines().count { it.isNotBlank() }
        val cleanIng = when {
            formattedLines >= serverLines && formatted.length >= 8 -> formatted
            cleanedServerIng.length >= 8 -> cleanedServerIng
            else -> ingredients
        }
        val cleanInstr = RecipeAiParser.formatInstructionsText(instructions)
            .ifBlank { instructions }

        return Recipe(
            title = cleanTitle,
            description = desc,
            ingredients = cleanIng,
            instructions = cleanInstr,
            servings = servings,
            totalCalories = cals?.let { it * servings },
            proteinPerServing = protein,
            carbsPerServing = carbs,
            fatPerServing = fat,
            prepTimeMinutes = prep,
            sourceUrl = sourceUrl ?: j.optString("sourceUrl").ifBlank { null },
            platform = platform,
            imageUrl = imageUrl ?: j.optString("imageUrl").ifBlank { null },
            tags = j.optString("tags", platform).take(200)
        )
    }
}
