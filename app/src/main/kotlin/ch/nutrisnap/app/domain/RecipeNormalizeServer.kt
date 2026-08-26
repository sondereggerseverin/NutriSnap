package ch.nutrisnap.app.domain

import ch.nutrisnap.app.BuildConfig
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeCategory
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

        val servings = j.optInt("servings", 1).coerceIn(1, 24)
        val prep = j.optInt("prep_time_minutes")
            .takeIf { !j.isNull("prep_time_minutes") && it > 0 }

        // Beschreibung ohne Server-Nährwerte – Nutzer nutzt Verifizieren/Berechnen in der App
        val desc = j.optString("description", "").trim()
            .takeUnless { it.isBlank() || it.equals("null", true) }
            .orEmpty()

        // AMM-Pfad: Server-Ergebnis ist Wahrheit.
        // Kein formatIngredientText – der hat FR-Einheiten und Abschnitte zerstört.
        val cleanTitle = RecipeAiParser.cleanDishTitle(title, ingredients)
        val cleanIng = ingredients.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !RecipeAiParser.isJunkIngredientLine(it) }
            .map { line ->
                if (line.startsWith("•") || line.startsWith("-")) {
                    val body = line.trimStart('•', '-', '*', ' ')
                    "• ${normalizeCulinaryUnits(body)}"
                } else if (RecipeAiParser.isSectionHeaderLine(line)) {
                    line
                } else {
                    normalizeCulinaryUnits(line)
                }
            }
            .joinToString("\n")
            .ifBlank { ingredients }

        val cleanInstr = instructions.trim().ifBlank { "" }

        // Kategorie: Server meal_category → sonst Heuristik aus Titel/Zutaten
        val serverCat = j.optString("meal_category", "").trim().uppercase()
        val validCats = setOf(
            "BREAKFAST", "MAIN", "SIDE_SNACK", "DESSERT", "DRINK", "SAUCE", "OTHER"
        )
        val mealCat = when {
            serverCat in validCats && serverCat != "OTHER" -> serverCat
            else -> RecipeCategory.guess(cleanTitle, cleanIng, desc).name
        }

        return Recipe(
            title = cleanTitle,
            description = desc,
            ingredients = cleanIng,
            instructions = cleanInstr,
            servings = servings,
            prepTimeMinutes = prep,
            sourceUrl = sourceUrl ?: j.optString("sourceUrl").ifBlank { null },
            platform = platform,
            imageUrl = imageUrl ?: j.optString("imageUrl").ifBlank { null },
            tags = j.optString("tags", platform).take(200),
            mealCategory = mealCat
        )
    }
}
