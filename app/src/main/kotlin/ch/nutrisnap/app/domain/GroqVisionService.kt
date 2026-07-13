package ch.nutrisnap.app.domain

import android.graphics.Bitmap
import android.util.Base64
import ch.nutrisnap.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@Serializable
data class FoodScanResult(
    val foodName: String = "",
    val estimatedGrams: Float = 0f,
    val calories: Float = 0f,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f,
    val confidence: String = "mittel"
)

@Serializable
data class FridgeScanResult(
    val ingredients: List<String> = emptyList()
)

@Serializable
data class NutritionLabelResult(
    val caloriesPer100g: Float = 0f,
    val proteinPer100g: Float = 0f,
    val carbsPer100g: Float = 0f,
    val fatPer100g: Float = 0f,
    val fiberPer100g: Float = 0f
)

/**
 * Nutzt Groq's multimodales Vision-Modell um Fotos zu analysieren.
 * Gleicher kostenloser Groq-Tier wie GroqRecipeGeneratorService, gleicher API-Key
 * (BuildConfig.GROQ_API_KEY, via GitHub Actions Secret injiziert).
 */
class GroqVisionService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    companion object {
        // Aktuelles Groq Vision-Modell (Stand 2026, siehe console.groq.com/docs/vision).
        // Falls Groq dieses Modell dereinst deprecated: hier zentral austauschen.
        private const val VISION_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct"
        private const val MAX_DIMENSION = 1024 // Px – haelt Base64-Payload unter Groq's 4MB-Limit
    }

    /** Komprimiert ein Foto auf eine fuer die API geeignete Groesse und kodiert es als Base64-JPEG. */
    fun bitmapToBase64Jpeg(bitmap: Bitmap, quality: Int = 70): String {
        val scaled = scaleDown(bitmap, MAX_DIMENSION)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
    }

    /** Schaetzt Lebensmittel + Kalorien/Makros aus einem Foto einer Mahlzeit. */
    suspend fun analyzeFoodPhoto(base64Jpeg: String): Result<FoodScanResult> = withContext(Dispatchers.IO) {
        val prompt = """
Du bist ein erfahrener Ernährungsberater. Analysiere das Foto eines Gerichts/einer Mahlzeit.
Identifiziere was zu sehen ist, schätze die Portionsgrösse in Gramm und berechne realistische Nährwerte.

Antworte NUR mit folgendem JSON (kein Markdown, keine Erklärungen):
{
  "foodName": "Bezeichnung des Gerichts",
  "estimatedGrams": 350,
  "calories": 520,
  "protein": 28.0,
  "carbs": 55.0,
  "fat": 18.0,
  "confidence": "hoch"
}

confidence ist "hoch", "mittel" oder "niedrig" je nachdem wie sicher die Schätzung ist.
Alle Werte (calories/protein/carbs/fat) beziehen sich auf die GESAMTE geschätzte Portion, nicht auf 100g.
""".trimIndent()
        callVisionRaw(prompt, base64Jpeg).mapCatching { json.decodeFromString<FoodScanResult>(it) }
    }

    /** Erkennt vorhandene Zutaten auf einem Foto (z.B. offener Kühlschrank/Vorratsschrank). */
    suspend fun analyzeFridgePhoto(base64Jpeg: String): Result<FridgeScanResult> = withContext(Dispatchers.IO) {
        val prompt = """
Du siehst ein Foto von einem Kühlschrank, Vorratsschrank oder einer Ansammlung von Lebensmitteln.
Identifiziere ALLE klar erkennbaren Lebensmittel/Zutaten auf dem Foto. Sei konkret (z.B. "Rüebli" statt "Gemüse",
"Naturejoghurt" statt "Milchprodukt"), aber erfinde nichts, was nicht wirklich zu sehen ist.
Ignoriere nicht-essbare Dinge.

Antworte NUR mit folgendem JSON (kein Markdown, keine Erklärungen):
{
  "ingredients": ["Rüebli", "Naturejoghurt", "Eier", "Zwiebeln"]
}
""".trimIndent()
        callVisionRaw(prompt, base64Jpeg).mapCatching { json.decodeFromString<FridgeScanResult>(it) }
    }

    /** Liest eine fotografierte Nährwerttabelle aus und gibt die Werte pro 100g zurück. */
    suspend fun analyzeNutritionLabel(base64Jpeg: String): Result<NutritionLabelResult> = withContext(Dispatchers.IO) {
        val prompt = """
Auf dem Foto ist eine Nährwerttabelle (von einer Lebensmittelverpackung) zu sehen.
Lies die Werte PRO 100g/100ml aus der Tabelle ab. Falls die Tabelle nur Werte pro Portion zeigt
und die Portionsgrösse erkennbar ist, rechne korrekt auf 100g um.

Antworte NUR mit folgendem JSON (kein Markdown, keine Erklärungen):
{
  "caloriesPer100g": 250,
  "proteinPer100g": 12.0,
  "carbsPer100g": 30.0,
  "fatPer100g": 8.0,
  "fiberPer100g": 3.0
}
""".trimIndent()
        callVisionRaw(prompt, base64Jpeg).mapCatching { json.decodeFromString<NutritionLabelResult>(it) }
    }

    private fun callVisionRaw(prompt: String, base64Jpeg: String): Result<String> {
        // Primary: Gemini (besseres Free-Tier,1M Context)
        if (GeminiService.isAvailable()) {
            val geminiResult = GeminiService.generateVision(
                prompt = prompt,
                base64Jpeg = base64Jpeg,
                temperature = 0.3,
                maxTokens = 1000
            )
            if (geminiResult.isSuccess) return geminiResult
            // Fallback to Groq if Gemini fails
        }

        // Fallback: Groq
        return callGroqVision(prompt, base64Jpeg)
    }

    private fun callGroqVision(prompt: String, base64Jpeg: String): Result<String> {
        return try {
            val apiKey = BuildConfig.GROQ_API_KEY
            if (apiKey.isBlank()) return Result.failure(Exception(
                "Kein GROQ_API_KEY in local.properties konfiguriert"
            ))

            val content = JSONArray().apply {
                put(JSONObject().apply { put("type", "text"); put("text", prompt) })
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$base64Jpeg")
                    })
                })
            }
            val requestJson = JSONObject().apply {
                put("model", VISION_MODEL)
                put("temperature", 0.3)
                put("max_completion_tokens", 1000)
                put("response_format", JSONObject().apply { put("type", "json_object") })
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", content)
                    })
                })
            }.toString()

            val requestBody = requestJson.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: return Result.failure(Exception("Leere Antwort"))
            if (!response.isSuccessful) return Result.failure(Exception("API Fehler ${response.code}: $bodyStr"))

            val root = JSONObject(bodyStr)
            val text = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            Result.success(cleaned)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
