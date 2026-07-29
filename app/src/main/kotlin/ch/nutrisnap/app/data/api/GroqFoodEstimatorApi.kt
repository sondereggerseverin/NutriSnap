package ch.nutrisnap.app.data.api

import android.util.Log
import ch.nutrisnap.app.BuildConfig
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import ch.nutrisnap.app.domain.GeminiService
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
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

    /**
     * Ruft Gemini und Groq PARALLEL auf (statt sequenziell mit Fallback via runBlocking)
     * und nimmt das erste erfolgreiche Ergebnis. Vorher wartete dieser Call bis zu Gemini's
     * vollem Timeout (~28s, s. GeminiService), bevor überhaupt erst Groq versucht wurde -
     * dieselbe 30s-Verzögerung, die in GroqVisionService/RecipeAiParser bereits behoben
     * wurde, hier aber noch nicht.
     */
    suspend fun estimate(query: String): FoodItem? = withContext(Dispatchers.IO) {
        val prompt = """
            Gib die durchschnittlichen Nährwerte pro 100g für das deutsche Lebensmittel
            "$query" zurück (rohe/übliche Zubereitungsform, keine bestimmte Marke).
            Antworte AUSSCHLIESSLICH mit einem JSON-Objekt, keine Erklärung, kein Markdown:
            {"name":"...", "calories":0.0, "protein":0.0, "carbs":0.0, "fat":0.0,
             "fiber":0.0, "sugar":0.0, "salt":0.0}
            Falls "$query" kein plausibles Lebensmittel ist, antworte mit {}
        """.trimIndent()

        if (!GeminiService.isAvailable()) {
            return@withContext runCatching { estimateViaGroq(prompt, query) }
                .onFailure { e -> Log.w(TAG, "Schätzung für \"$query\" fehlgeschlagen: ${e.message}") }
                .getOrNull()
        }

        coroutineScope {
            val geminiJob: Deferred<FoodItem?> = async {
                runCatching { estimateViaGemini(prompt, query) }.getOrNull()
            }
            val groqJob: Deferred<FoodItem?> = async {
                runCatching { estimateViaGroq(prompt, query) }
                    .onFailure { e -> Log.w(TAG, "Schätzung für \"$query\" fehlgeschlagen: ${e.message}") }
                    .getOrNull()
            }

            val (winnerJob, winnerResult) = select<Pair<Deferred<FoodItem?>, FoodItem?>> {
                geminiJob.onAwait { geminiJob to it }
                groqJob.onAwait { groqJob to it }
            }
            val loserJob = if (winnerJob === geminiJob) groqJob else geminiJob

            if (winnerResult != null) {
                loserJob.cancel()
                winnerResult
            } else {
                loserJob.await()
            }
        }
    }

    private suspend fun estimateViaGemini(prompt: String, query: String): FoodItem? {
        val geminiResult = GeminiService.generateText(prompt = prompt, temperature = 0.2, maxTokens = 300)
        if (geminiResult.isFailure) return null
        val content = geminiResult.getOrThrow()
            .trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val data = JSONObject(content)
        return if (data.has("calories")) buildFoodItem(data, query) else null
    }

    private fun estimateViaGroq(prompt: String, query: String): FoodItem? {
        val apiKey = BuildConfig.GROQ_API_KEY
        if (apiKey.isBlank()) return null

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
            return null
        }

        val content = JSONObject(bodyStr)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
            .trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        val data = JSONObject(content)
        if (!data.has("calories")) return null

        return buildFoodItem(data, query)
    }

    private fun buildFoodItem(data: JSONObject, query: String): FoodItem {
        fun g(key: String): Float? =
            if (data.has(key) && !data.isNull(key)) data.optDouble(key, Double.NaN).toFloat().takeIf { !it.isNaN() } else null
        return FoodItem(
            name = data.optString("name", query).ifBlank { query },
            brand = "KI-geschätzt",
            calories = g("calories"),
            protein  = g("protein"),
            carbs    = g("carbs"),
            fat      = g("fat"),
            fiber    = g("fiber"),
            sugar    = g("sugar"),
            salt     = g("salt"),
            servingSize = 100f,
            servingUnit = "g",
            source = FoodSource.MANUAL,
            completenessScore = 20 // niedrig gewichtet — nur Fallback, keine verifizierte Quelle
        )
    }
}
