package ch.nutrisnap.app.data.api

import android.util.Log
import ch.nutrisnap.app.BuildConfig
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import ch.nutrisnap.app.domain.GeminiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Letzter Fallback für die Lebensmittelsuche: wenn OpenFoodFacts (nur Markenprodukte
 * mit Barcode), USDA (nur Englisch) und die Schweizer Nährwertdatenbank kein Ergebnis
 * liefern — was bei einfachen deutschen Grundnahrungsmitteln ("Apfel", "Reis", "Ei")
 * häufig vorkommt — schätzt das LLM die Standard-Nährwerte pro 100g.
 * Nutzt primär Gemini (besseres Free-Tier), Fallback auf Groq/Llama.
 * Ergebnis ist klar als Schätzung markiert (Marke "KI-geschätzt") und wird NICHT
 * in die lokale DB gecacht, da es keine verifizierte Quelle ist.
 */
object GroqFoodEstimatorApi {
    private const val TAG = "GroqFoodEstimatorApi"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun estimate(query: String): FoodItem? = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = """
                Gib die durchschnittlichen Nährwerte pro 100g für das deutsche Lebensmittel
                "$query" zurück (rohe/übliche Zubereitungsform, keine bestimmte Marke).
                Antworte AUSSCHLIESSLICH mit einem JSON-Objekt, keine Erklärung, kein Markdown:
                {"name":"...", "calories":0.0, "protein":0.0, "carbs":0.0, "fat":0.0,
                 "fiber":0.0, "sugar":0.0, "salt":0.0}
                Falls "$query" kein plausibles Lebensmittel ist, antworte mit {}
            """.trimIndent()

            // Primary: Gemini
            if (GeminiService.isAvailable()) {
                val geminiResult = runBlocking {
                    GeminiService.generateText(prompt = prompt, temperature = 0.2, maxTokens = 300)
                }
                if (geminiResult.isSuccess) {
                    val content = geminiResult.getOrThrow()
                        .trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    val data = JSONObject(content)
                    if (data.has("calories")) {
                        return@withContext buildFoodItem(data, query)
                    }
                }
            }

            // Fallback: Groq
            val apiKey = BuildConfig.GROQ_API_KEY
            if (apiKey.isBlank()) return@withContext null

            val requestJson = JSONObject().apply {
                put("model", "openai/gpt-oss-120b")
                put("temperature", 0.2)
                put("max_tokens", 300)
                put("reasoning_effort", "low")
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                })
            }.toString()

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestJson.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string()
            if (!response.isSuccessful || bodyStr == null) {
                Log.w(TAG, "Schätzung für \"$query\" fehlgeschlagen: HTTP ${response.code}")
                return@withContext null
            }

            val content = JSONObject(bodyStr)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
                .trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val data = JSONObject(content)
            if (!data.has("calories")) return@withContext null

            buildFoodItem(data, query)
        }.onFailure { e -> Log.w(TAG, "Schätzung für \"$query\" fehlgeschlagen: ${e.message}") }
            .getOrNull()
    }

    private fun buildFoodItem(data: JSONObject, query: String): FoodItem {
        return FoodItem(
            name = data.optString("name", query).ifBlank { query },
            brand = "KI-geschätzt",
            calories = data.optDouble("calories", 0.0).toFloat(),
            protein  = data.optDouble("protein", 0.0).toFloat(),
            carbs    = data.optDouble("carbs", 0.0).toFloat(),
            fat      = data.optDouble("fat", 0.0).toFloat(),
            fiber    = data.optDouble("fiber", 0.0).toFloat().takeIf { it > 0f },
            sugar    = data.optDouble("sugar", 0.0).toFloat().takeIf { it > 0f },
            salt     = data.optDouble("salt", 0.0).toFloat().takeIf { it > 0f },
            servingSize = 100f,
            servingUnit = "g",
            source = FoodSource.MANUAL,
            completenessScore = 20 // niedrig gewichtet — nur Fallback, keine verifizierte Quelle
        )
    }
}
