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
        // Strenger Prompt: nur Standard-Referenzwerte (USDA/BFS), keine Marken,
        // Atwater-Plausibilität, typische Bereiche — gegen Halluzinationen wie
        // "Mais 400 kcal / 20g Ballaststoffe".
        val prompt = """
            Du bist eine Nährwert-Referenz. Gib NUR Standardwerte pro 100g (roh/üblich,
            ungesalzen, keine Marke) für "$query" zurück — wie USDA FoodData Central
            oder Schweizer Nährwertdatenbank.

            Regeln (strikt):
            - Nur bekannte, realistische Durchschnittswerte. Nichts erfinden.
            - calories muss ungefähr 4*protein + 4*carbs + 9*fat (±15%) entsprechen.
            - Typische Bereiche: Gemüse 10–80 kcal, Obst 30–90, Getreide/Reis roh
              300–370, gekocht 90–130, Mais/Maiskörner ~86, Kakaopulver ~228,
              Fleisch 100–300, Öle ~884. Ballaststoffe bei Gemüse/Getreide meist 1–10g,
              nicht 20g bei Mais.
            - Unbekannt oder unsicher → antworte mit {}

            Beispiele korrekter Werte:
            Mais (Körner): {"name":"Mais","calories":86,"protein":3.3,"carbs":19,"fat":1.2,"fiber":2.7,"sugar":3.2,"salt":0.02}
            Apfel: {"name":"Apfel","calories":52,"protein":0.3,"carbs":14,"fat":0.2,"fiber":2.4,"sugar":10,"salt":0}
            Kakaopulver: {"name":"Kakaopulver","calories":228,"protein":20,"carbs":58,"fat":14,"fiber":33,"sugar":1.8,"salt":0.05}

            Antworte AUSSCHLIESSLICH mit einem JSON-Objekt, kein Markdown, keine Erklärung:
            {"name":"...","calories":0.0,"protein":0.0,"carbs":0.0,"fat":0.0,"fiber":0.0,"sugar":0.0,"salt":0.0}
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

    private fun buildFoodItem(data: JSONObject, query: String): FoodItem? {
        fun g(key: String): Float? =
            if (data.has(key) && !data.isNull(key)) data.optDouble(key, Double.NaN).toFloat().takeIf { !it.isNaN() && it >= 0f } else null
        val calories = g("calories") ?: return null
        val protein = g("protein") ?: 0f
        val carbs = g("carbs") ?: 0f
        val fat = g("fat") ?: 0f
        // Atwater-Plausibilität: kcal ≈ 4P+4K+9F (±25%, etwas Spielraum für Alkohol/Rundung)
        val atwater = 4f * protein + 4f * carbs + 9f * fat
        if (atwater > 5f) {
            val ratio = calories / atwater
            if (ratio < 0.7f || ratio > 1.35f) {
                Log.w(TAG, "Verwerfe unplausible Schätzung für \"$query\": $calories kcal vs Atwater $atwater")
                return null
            }
        }
        // Absurde Ausreißer (z.B. 400 kcal bei Mais-artigem Low-Fat-Food) abfangen
        if (calories > 950f || calories < 0f) return null
        val fiber = g("fiber")
        if (fiber != null && fiber > 50f) return null // z.B. 20g bei Mais wäre falsch, 50+ absurd

        return FoodItem(
            name = data.optString("name", query).ifBlank { query },
            brand = "KI-geschätzt",
            calories = calories,
            protein  = protein,
            carbs    = carbs,
            fat      = fat,
            fiber    = fiber,
            sugar    = g("sugar"),
            salt     = g("salt"),
            servingSize = 100f,
            servingUnit = "g",
            source = FoodSource.MANUAL,
            completenessScore = 20 // niedrig gewichtet — nur Fallback, keine verifizierte Quelle
        )
    }
}
