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
data class GeneratedRecipe(
    val title: String = "",
    val description: String = "",
    val ingredients: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    val servings: Int = 2,
    val prepTimeMinutes: Int = 30,
    val calories: Int = 0,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f
)

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

            val prompt = "Du bist ein erfahrener Ernaehrungsberater und Koch. " +
                "Erstelle ein realistisches Rezept fuer: $userInput. " +
                "WICHTIG: Berechne die Naehrwerte EXAKT und realistisch basierend auf den echten Zutatenmengen pro Portion. " +
                "Beispiel-Referenzwerte pro 100g: Huehnerbrust=165kcal/31gP, Parmesan=431kcal/38gP, " +
                "Ricotta=174kcal/11gP, Hackfleisch=250kcal/17gP, Pasta=350kcal/13gP, Reis=130kcal/3gP, Ei=155kcal/13gP. " +
                "Addiere die Kalorien aller Zutaten und teile durch die Portionszahl. " +
                "Antworte NUR mit JSON (kein Markdown, keine Erklaerungen): " +
                "{\"title\":\"Name\",\"description\":\"Beschreibung\"," +
                "\"ingredients\":[\"200g Beispiel\"],\"steps\":[\"Schritt 1\"]," +
                "\"servings\":4,\"prepTimeMinutes\":30,\"calories\":650," +
                "\"protein\":55.0,\"carbs\":45.0,\"fat\":25.0}"

            val requestJson = JSONObject().apply {
                put("model", model)
                put("temperature", 0.7)
                put("max_tokens", 1500)
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
