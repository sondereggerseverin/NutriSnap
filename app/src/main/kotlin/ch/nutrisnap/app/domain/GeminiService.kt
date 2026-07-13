package ch.nutrisnap.app.domain

import android.util.Log
import ch.nutrisnap.app.BuildConfig
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
 * Wrapper für Google Gemini API (v1beta).
 * Gemini Flash hat ein grosszügigeres Free-Tier als Groq:
 *   - 15 RPM, 1'500 RPD (vs. Groq 30 RPM)
 *   - 1M Context Fenster (vs. Groq 131K)
 *   - Native Vision-Support
 *
 * Wird als Primary Provider für Rezept-Extraktion und Vision eingesetzt.
 * Fallback auf Groq wenn Gemini-Key fehlt oder Request fehlschlägt.
 */
object GeminiService {
    private const val TAG = "GeminiService"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    private const val TEXT_MODEL = "gemini-2.5-flash"
    private const val VISION_MODEL = "gemini-2.5-flash"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Prüft ob ein Gemini API Key konfiguriert ist. */
    fun isAvailable(): Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank()

    /**
     * Text-basierter LLM-Call (kein Vision).
     * Liefert den bereinigten Text der Antwort oder einen Fehler.
     */
    suspend fun generateText(
        prompt: String,
        systemPrompt: String? = null,
        temperature: Double = 0.2,
        maxTokens: Int = 2000
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext Result.failure(Exception("Kein GEMINI_API_KEY konfiguriert"))

        try {
            val contents = JSONArray()

            if (systemPrompt != null) {
                // Gemini: system instruction als erstes user/nachricht-pair
                contents.put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemPrompt) })
                    })
                })
                contents.put(JSONObject().apply {
                    put("role", "model")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", "Verstanden. Ich werde die Anweisungen befolgen.") })
                    })
                })
            }

            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", prompt) })
                })
            })

            val requestBody = JSONObject().apply {
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("temperature", temperature)
                    put("maxOutputTokens", maxTokens)
                })
            }.toString()

            val apiKey = BuildConfig.GEMINI_API_KEY
            val url = "$BASE_URL/models/$TEXT_MODEL:generateContent?key=$apiKey"

            val request = Request.Builder()
                .url(url)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: return@withContext Result.failure(Exception("Leere Gemini-Antwort"))

            if (!response.isSuccessful) {
                Log.w(TAG, "Gemini API Fehler ${response.code}: $bodyStr")
                return@withContext Result.failure(Exception("Gemini API Fehler ${response.code}: $bodyStr"))
            }

            val text = extractText(bodyStr)
            if (text.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Leere Gemini-Antwort"))
            }

            Result.success(text.trim())
        } catch (e: Exception) {
            Log.w(TAG, "Gemini text call fehlgeschlagen: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Vision-basierter LLM-Call mit einem Base64-JPEG Bild.
     */
    suspend fun generateVision(
        prompt: String,
        base64Jpeg: String,
        temperature: Double = 0.3,
        maxTokens: Int = 1000
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext Result.failure(Exception("Kein GEMINI_API_KEY konfiguriert"))

        try {
            val contents = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64Jpeg)
                            })
                        })
                    })
                })
            }

            val requestBody = JSONObject().apply {
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("temperature", temperature)
                    put("maxOutputTokens", maxTokens)
                })
            }.toString()

            val apiKey = BuildConfig.GEMINI_API_KEY
            val url = "$BASE_URL/models/$VISION_MODEL:generateContent?key=$apiKey"

            val request = Request.Builder()
                .url(url)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: return@withContext Result.failure(Exception("Leere Gemini-Antwort"))

            if (!response.isSuccessful) {
                Log.w(TAG, "Gemini Vision API Fehler ${response.code}: $bodyStr")
                return@withContext Result.failure(Exception("Gemini Vision API Fehler ${response.code}: $bodyStr"))
            }

            val text = extractText(bodyStr)
            if (text.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Leere Gemini-Vision-Antwort"))
            }

            Result.success(text.trim())
        } catch (e: Exception) {
            Log.w(TAG, "Gemini vision call fehlgeschlagen: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Extrahiert den Text aus einer Gemini API-Antwort.
     */
    private fun extractText(responseBody: String): String? {
        return try {
            val root = JSONObject(responseBody)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            if (parts.length() == 0) return null
            parts.getJSONObject(0).optString("text", null)
        } catch (e: Exception) {
            Log.w(TAG, "Gemini Response-Parsing fehlgeschlagen: ${e.message}")
            null
        }
    }
}
