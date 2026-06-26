package ch.nutrisnap.app.domain

import ch.nutrisnap.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    suspend fun generateRecipe(userInput: String): Result<GeneratedRecipe> = withContext(Dispatchers.IO) {
        try {
            val apiKey = BuildConfig.GROQ_API_KEY
            if (apiKey.isBlank()) return@withContext Result.failure(Exception("Kein GROQ API-Key konfiguriert"))

            val escapedInput = userInput.replace("\\", "\\\\").replace("\"", "\\\"")
            val prompt = """Du bist ein Ernährungsberater und Koch. Erstelle ein Rezept basierend auf dieser Anfrage: "$escapedInput". Antworte NUR mit einem JSON-Objekt ohne Markdown-Backticks: {"title":"Rezeptname","description":"Kurze Beschreibung","ingredients":["200g Hähnchenbrust"],"steps":["Schritt 1..."],"servings":2,"prepTimeMinutes":25,"calories":450,"protein":38.5,"carbs":42.0,"fat":12.0}"""

            val bodyJson = """{"model":"llama3-8b-8192","messages":[{"role":"user","content":${Json.encodeToString(kotlinx.serialization.serializer<String>(), prompt)}}],"temperature":0.7,"max_tokens":1500}"""

            val requestBody = bodyJson.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: return@withContext Result.failure(Exception("Leere Antwort"))
            if (!response.isSuccessful) return@withContext Result.failure(Exception("API Fehler ${response.code}"))

            // Parse Groq response
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val content = root["choices"]?.jsonArray?.get(0)
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.content
                ?: return@withContext Result.failure(Exception("Konnte Antwort nicht parsen"))

            val cleaned = content.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val recipe = json.decodeFromString<GeneratedRecipe>(cleaned)
            Result.success(recipe)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
