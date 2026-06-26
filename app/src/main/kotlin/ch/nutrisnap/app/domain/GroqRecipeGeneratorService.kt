package ch.nutrisnap.app.domain

import ch.nutrisnap.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

/**
 * AI recipe generator with multi-provider support:
 *  1. GLM-5.2 via Zenmux (free tier, 1M context) — primary
 *  2. Groq llama3-8b (free tier) — fallback
 *
 * Configure in local.properties:
 *   ZENMUX_API_KEY=your_key   (get free key at zenmux.ai)
 *   GROQ_API_KEY=your_key     (get free key at console.groq.com)
 */
class GroqRecipeGeneratorService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    suspend fun generateRecipe(userInput: String): Result<GeneratedRecipe> = withContext(Dispatchers.IO) {
        val zenmuxKey = BuildConfig.ZENMUX_API_KEY
        val groqKey = BuildConfig.GROQ_API_KEY

        return@withContext when {
            zenmuxKey.isNotBlank() -> tryProvider(
                url = "https://zenmux.ai/api/v1/chat/completions",
                apiKey = zenmuxKey,
                model = "z-ai/glm-5.2-free",
                userInput = userInput
            )
            groqKey.isNotBlank() -> tryProvider(
                url = "https://api.groq.com/openai/v1/chat/completions",
                apiKey = groqKey,
                model = "llama3-8b-8192",
                userInput = userInput
            )
            else -> Result.failure(Exception(
                "Kein API-Key konfiguriert. Hol dir einen kostenlosen Key:\n" +
                "• GLM-5.2: zenmux.ai → ZENMUX_API_KEY\n" +
                "• Groq: console.groq.com → GROQ_API_KEY"
            ))
        }
    }

    private fun tryProvider(
        url: String,
        apiKey: String,
        model: String,
        userInput: String
    ): Result<GeneratedRecipe> {
        return try {
            val prompt = "Du bist ein Koch. Erstelle ein Rezept fuer: $userInput. " +
                "Antworte NUR mit JSON (kein Markdown): " +
                "{\"title\":\"Name\",\"description\":\"Beschreibung\"," +
                "\"ingredients\":[\"200g Beispiel\"],\"steps\":[\"Schritt 1\"]," +
                "\"servings\":2,\"prepTimeMinutes\":25,\"calories\":400," +
                "\"protein\":30.0,\"carbs\":40.0,\"fat\":10.0}"

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
