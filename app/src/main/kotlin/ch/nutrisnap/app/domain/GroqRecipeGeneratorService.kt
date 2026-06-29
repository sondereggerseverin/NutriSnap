package ch.nutrisnap.app.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ch.nutrisnap.app.BuildConfig
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@Serializable
data class RecipeIngredient(
    val name: String = "",
    val amount: String = "",   // z.B. "200g", "2 Stück"
    val calories: Int = 0,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f
)

@Serializable
data class GeneratedRecipe(
    val title: String = "",
    val description: String = "",
    val ingredients: List<String> = emptyList(),          // legacy, kept for history compat
    val structuredIngredients: List<RecipeIngredient> = emptyList(),
    val steps: List<String> = emptyList(),
    val servings: Int = 2,
    val prepTimeMinutes: Int = 30,
    val calories: Int = 0,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f
) {
    /** Returns structured ingredients if available, otherwise wraps legacy strings */
    fun effectiveIngredients(): List<RecipeIngredient> =
        if (structuredIngredients.isNotEmpty()) structuredIngredients
        else ingredients.map { RecipeIngredient(name = it, amount = "") }
}

class GroqRecipeGeneratorService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    suspend fun generateRecipe(userInput: String): Result<GeneratedRecipe> = withContext(Dispatchers.IO) {
        tryProvider(
            url = "https://api.groq.com/openai/v1/chat/completions",
            apiKey = BuildConfig.GROQ_API_KEY,
            model = "llama-3.3-70b-versatile",
            userInput = userInput
        )
    }

    private fun tryProvider(
        url: String,
        apiKey: String,
        model: String,
        userInput: String
    ): Result<GeneratedRecipe> {
        return try {
            if (apiKey.isBlank()) return Result.failure(Exception(
                "Kein GROQ_API_KEY in local.properties konfiguriert"
            ))

            val prompt = """
Du bist ein erfahrener Ernaehrungsberater und Koch.
Erstelle ein realistisches Rezept fuer: $userInput

Berechne die Naehrwerte EXAKT basierend auf echten Zutatenmengen pro Portion.
Referenzwerte pro 100g: Huehnerbrust=165kcal/31gP, Parmesan=431kcal/38gP,
Ricotta=174kcal/11gP, Hackfleisch=250kcal/17gP, Pasta=350kcal/13gP,
Reis=130kcal/3gP, Ei=155kcal/13gP, Butter=717kcal/1gP.

Antworte NUR mit folgendem JSON (kein Markdown, keine Erklaerungen):
{
  "title": "Rezeptname",
  "description": "Kurze Beschreibung",
  "structuredIngredients": [
    {"name": "Huehnerbrust", "amount": "200g", "calories": 330, "protein": 62.0, "carbs": 0.0, "fat": 7.0},
    {"name": "Pasta", "amount": "250g", "calories": 875, "protein": 32.5, "carbs": 175.0, "fat": 5.0}
  ],
  "ingredients": ["200g Huehnerbrust", "250g Pasta"],
  "steps": ["Schritt 1", "Schritt 2"],
  "servings": 4,
  "prepTimeMinutes": 30,
  "calories": 650,
  "protein": 55.0,
  "carbs": 45.0,
  "fat": 25.0
}

Wichtig: calories/protein/carbs/fat auf Toplevel sind die Werte PRO PORTION.
Die Werte in structuredIngredients sind GESAMT fuer die gesamte Zutatenmenge.
""".trimIndent()

            val requestJson = JSONObject().apply {
                put("model", model)
                put("temperature", 0.7)
                put("max_tokens", 2000)
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }.toString()

            val requestBody = requestJson.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: return Result.failure(Exception("Leere Antwort"))
            if (!response.isSuccessful) return Result.failure(Exception("API Fehler ${response.code}: $bodyStr"))

            val root = JSONObject(bodyStr)
            val content = root
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val cleaned = content.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val recipe = json.decodeFromString<GeneratedRecipe>(cleaned)
            Result.success(recipe)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
