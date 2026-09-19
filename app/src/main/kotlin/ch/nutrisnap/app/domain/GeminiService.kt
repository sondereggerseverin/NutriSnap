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
    private const val TEXT_MODEL = "gemini-3.6-flash"
    private const val VISION_MODEL = "gemini-3.6-flash"
    /** Nach 429/Quota: Gemini für diese Dauer überspringen (Groq übernimmt). */
    private const val QUOTA_COOLDOWN_MS = 60_000L

    // Kurze Timeouts: Gemini soll bei Problemen schnell an Groq (Parallel-Race)
    // abgeben statt die UI 30s+ blockieren zu lassen.
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var quotaBlockedUntilMs: Long = 0L

    /** Prüft ob ein Gemini API Key konfiguriert ist. */
    fun isAvailable(): Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank()

    /** true wenn Key da ist und kein frisches Quota-Cooldown aktiv ist. */
    fun isUsable(): Boolean =
        isAvailable() && System.currentTimeMillis() >= quotaBlockedUntilMs

    private fun markQuotaExhausted(retryAfterSeconds: Double? = null) {
        val waitMs = ((retryAfterSeconds ?: 60.0).coerceIn(15.0, 300.0) * 1000).toLong()
        quotaBlockedUntilMs = System.currentTimeMillis() + waitMs.coerceAtLeast(QUOTA_COOLDOWN_MS)
        Log.w(TAG, "Gemini Quota erschöpft – Pause ${waitMs / 1000}s, Fallback auf Groq")
    }

    /** Nutzerwfreundliche Meldung statt rohem API-JSON. */
    private fun friendlyError(code: Int, body: String): String {
        if (code == 429 || body.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
            body.contains("quota", ignoreCase = true)
        ) {
            val retrySec = Regex("""retry in ([\d.]+)s""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            markQuotaExhausted(retrySec)
            return "KI-Kontingent kurz erschöpft – bitte in etwa ${
                (retrySec ?: 60.0).toInt().coerceAtLeast(15)
            } s erneut versuchen (oder es läuft automatisch über den Fallback)."
        }
        if (code == 401 || code == 403) return "KI-Zugang ungültig – API-Key prüfen."
        if (code in 500..599) return "KI-Dienst vorübergehend nicht erreichbar."
        return "KI-Anfrage fehlgeschlagen (Code $code)."
    }

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
        if (!isUsable()) {
            return@withContext Result.failure(Exception("Gemini Quota-Pause aktiv – Fallback nutzen"))
        }

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
                Log.w(TAG, "Gemini API Fehler ${response.code}: ${bodyStr.take(200)}")
                return@withContext Result.failure(Exception(friendlyError(response.code, bodyStr)))
            }

            val text = extractText(bodyStr)
            if (text.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Leere Gemini-Antwort"))
            }

            Result.success(text.trim())
        } catch (e: Exception) {
            Log.w(TAG, "Gemini text call fehlgeschlagen: ${e.message}")
            Result.failure(Exception(e.message?.take(120) ?: "Gemini-Anfrage fehlgeschlagen"))
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
    ): Result<String> = generateVision(
        prompt = prompt,
        base64Jpegs = listOf(base64Jpeg),
        temperature = temperature,
        maxTokens = maxTokens
    )

    /**
     * Vision-Call mit einem oder mehreren JPEGs (z.B. mehrere Social-Screenshots).
     */
    suspend fun generateVision(
        prompt: String,
        base64Jpegs: List<String>,
        temperature: Double = 0.3,
        maxTokens: Int = 1000
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext Result.failure(Exception("Kein GEMINI_API_KEY konfiguriert"))
        if (!isUsable()) {
            return@withContext Result.failure(Exception("Gemini Quota-Pause aktiv – Fallback nutzen"))
        }
        val images = base64Jpegs.filter { it.isNotBlank() }.take(4)
        if (images.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Keine Bilder"))
        }

        try {
            val contents = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                        images.forEach { b64 ->
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "image/jpeg")
                                    put("data", b64)
                                })
                            })
                        }
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
                Log.w(TAG, "Gemini Vision API Fehler ${response.code}: ${bodyStr.take(200)}")
                return@withContext Result.failure(Exception(friendlyError(response.code, bodyStr)))
            }

            val text = extractText(bodyStr)
            if (text.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Leere Gemini-Vision-Antwort"))
            }

            Result.success(text.trim())
        } catch (e: Exception) {
            Log.w(TAG, "Gemini vision call fehlgeschlagen: ${e.message}")
            Result.failure(Exception(e.message?.take(120) ?: "Gemini-Vision fehlgeschlagen"))
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
