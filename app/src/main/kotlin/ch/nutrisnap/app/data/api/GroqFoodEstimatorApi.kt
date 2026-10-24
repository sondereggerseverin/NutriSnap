package ch.nutrisnap.app.data.api

import android.util.Log
import ch.nutrisnap.app.BuildConfig
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import ch.nutrisnap.app.data.model.MealType
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
     * Heuristik: freier Text sieht aus wie ein Gericht (nicht Einzelzutat).
     * Beispiele: «Tagliatelle mit Poulet Stroganoff», «Pasta al pesto», «Burger mit Pommes».
     */
    fun looksLikeDish(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.length < 8) return false
        val words = q.split(Regex("""\s+""")).filter { it.length > 1 }
        if (words.size < 2) return false
        val dishHints = listOf(
            "mit ", " and ", " & ", " al ", " alla ", " au ", " à ",
            "pasta", "tagliatelle", "spaghetti", "penne", "risotto", "pizza",
            "burger", "bowl", "salat", "suppe", "curry", "stir fry", "pfanne",
            "stroganoff", "gulasch", "ragout", "lasagne", "couscous", "wrap",
            "sandwich", "teller", "menü", "menu", "gericht"
        )
        return dishHints.any { it in q }
    }

    /**
     * Schätzt Nährwerte für ein komplettes Gericht pro typischer Portion (nicht pro 100 g).
     * servingSize wird auf die geschätzte Portionsgrösse in g gesetzt.
     */
    suspend fun estimateDish(query: String): FoodItem? = withContext(Dispatchers.IO) {
        val prompt = """
            Du schätzt die Nährwerte einer typischen Restaurant-/Heimportion des Gerichts "$query".
            Nicht pro 100g, sondern für EINE realistische Portion (Teller, wie serviert).

            Regeln:
            - calories, protein, carbs, fat, fiber, sugar, salt für die ganze Portion
            - servingGrams: geschätztes Gesamtgewicht der Portion in Gramm (z.B. 350–500)
            - calories muss ungefähr 4*protein + 4*carbs + 9*fat (±20%) entsprechen
            - Realistische Restaurant-Portionen: Pasta-Gericht oft 500–900 kcal, Salat 200–450, Burger 600–900
            - Unbekannt → {}

            Antworte NUR mit JSON, kein Markdown:
            {"name":"...","calories":0.0,"protein":0.0,"carbs":0.0,"fat":0.0,"fiber":0.0,"sugar":0.0,"salt":0.0,"servingGrams":400}
        """.trimIndent()

        val item = if (!GeminiService.isUsable()) {
            runCatching { estimateViaGroq(prompt, query) }.getOrNull()
        } else {
            coroutineScope {
                val geminiJob: Deferred<FoodItem?> = async {
                    runCatching { estimateViaGemini(prompt, query) }.getOrNull()
                }
                val groqJob: Deferred<FoodItem?> = async {
                    runCatching { estimateViaGroq(prompt, query) }.getOrNull()
                }
                select<FoodItem?> {
                    geminiJob.onAwait { it }
                    groqJob.onAwait { it }
                } ?: geminiJob.await() ?: groqJob.await()
            }
        } ?: return@withContext null

        // Dish-Schätzung: servingSize = Portionsgewicht (Default 400 g)
        item.copy(
            brand = "KI-Gerichtsschätzung",
            servingSize = item.servingSize.takeIf { it > 100f } ?: 400f,
            completenessScore = 25
        )
    }

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

        if (!GeminiService.isUsable()) {
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

        val servingGrams = g("servingGrams")?.takeIf { it in 50f..1500f }
        // Dish-Prompt liefert absolute Portionswerte → auf pro 100 g umrechnen,
        // damit der Rest der App (amount/100 * per100) korrekt bleibt.
        // servingSize merkt die empfohlene Portionsgrösse für die UI.
        val per100 = if (servingGrams != null && servingGrams > 0f) 100f / servingGrams else 1f
        return FoodItem(
            name = data.optString("name", query).ifBlank { query },
            brand = if (servingGrams != null) "KI-Gerichtsschätzung" else "KI-geschätzt",
            calories = if (servingGrams != null) calories * per100 else calories,
            protein  = if (servingGrams != null) protein * per100 else protein,
            carbs    = if (servingGrams != null) carbs * per100 else carbs,
            fat      = if (servingGrams != null) fat * per100 else fat,
            fiber    = fiber?.let { if (servingGrams != null) it * per100 else it },
            sugar    = g("sugar")?.let { if (servingGrams != null) it * per100 else it },
            salt     = g("salt")?.let { if (servingGrams != null) it * per100 else it },
            servingSize = servingGrams ?: 100f,
            servingUnit = "g",
            source = FoodSource.MANUAL,
            completenessScore = 20 // niedrig gewichtet — nur Fallback, keine verifizierte Quelle
        )
    }

    /**
     * Parst freie Mahlzeiten-Beschreibungen in mehrere trackbare Positionen.
     * Beispiel: «2 Eier, 30 g Haferflocken und einen Latte zum Frühstück».
     * Werte sind **absolut** für die genannte Menge (nicht pro 100 g).
     */
    data class MealParseItem(
        val name: String,
        val grams: Float,
        val calories: Float,
        val protein: Float,
        val carbs: Float,
        val fat: Float,
        val fiber: Float = 0f
    )

    data class MealParseResult(
        val items: List<MealParseItem>,
        val mealType: MealType? = null
    )

    fun looksLikeMultiItemMeal(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.length < 6) return false
        val multiHints = listOf(",", " und ", " & ", " plus ", " mit ", " dazu ")
        val hasSeparator = multiHints.any { it in q }
        val hasAmount = Regex("""\d+\s*(g|gramm|ml|stk|stück|ei|eier|scheibe|scheiben)?""").containsMatchIn(q)
        val mealWords = listOf("frühstück", "mittag", "abend", "snack", "breakfast", "lunch", "dinner")
        val hasMeal = mealWords.any { it in q }
        return (hasSeparator && (hasAmount || q.split(Regex("""\s+""")).size >= 4)) ||
            (hasAmount && hasMeal) ||
            (hasSeparator && hasMeal)
    }

    suspend fun parseMealDescription(query: String): MealParseResult? = withContext(Dispatchers.IO) {
        val prompt = """
            Zerlege die Mahlzeit-Beschreibung in einzelne Lebensmittel mit geschätzten Nährwerten.
            Text: "$query"

            Regeln:
            - Jede Position: name, grams (geschätztes Gewicht), calories/protein/carbs/fat/fiber für GENAU diese Menge (absolut, nicht pro 100g)
            - calories ≈ 4*protein + 4*carbs + 9*fat (±25%)
            - Mengen aus dem Text übernehmen (z.B. «2 Eier» ≈ 100–120 g, «30 g Hafer»)
            - Ohne Mengenangabe realistische Portionsgrösse schätzen
            - mealType nur wenn klar: BREAKFAST, LUNCH, DINNER, SNACK – sonst null
            - Maximal 8 Positionen; Getränke mitzählen
            - Unverständlich → {"items":[]}

            NUR JSON, kein Markdown:
            {"mealType":"BREAKFAST","items":[{"name":"Ei","grams":100,"calories":155,"protein":13,"carbs":1.1,"fat":11,"fiber":0}]}
        """.trimIndent()

        val raw = if (!GeminiService.isUsable()) {
            runCatching { parseMealViaGroq(prompt) }.getOrNull()
        } else {
            coroutineScope {
                val geminiJob = async { runCatching { parseMealViaGemini(prompt) }.getOrNull() }
                val groqJob = async { runCatching { parseMealViaGroq(prompt) }.getOrNull() }
                select {
                    geminiJob.onAwait { it }
                    groqJob.onAwait { it }
                } ?: geminiJob.await() ?: groqJob.await()
            }
        } ?: return@withContext null

        if (raw.items.isEmpty()) return@withContext null
        raw
    }

    private suspend fun parseMealViaGemini(prompt: String): MealParseResult? {
        val result = GeminiService.generateText(prompt = prompt, temperature = 0.2, maxTokens = 800)
        if (result.isFailure) return null
        val content = result.getOrThrow()
            .trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return parseMealJson(content)
    }

    private fun parseMealViaGroq(prompt: String): MealParseResult? {
        val apiKey = BuildConfig.GROQ_API_KEY
        if (apiKey.isBlank()) return null
        val requestJson = JSONObject().apply {
            put("model", "openai/gpt-oss-120b")
            put("temperature", 0.2)
            put("max_tokens", 800)
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
            Log.w(TAG, "parseMeal HTTP ${response.code}")
            return null
        }
        val content = JSONObject(bodyStr)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            ?.removePrefix("```json")?.removePrefix("```")?.removeSuffix("```")?.trim()
            ?: return null
        return parseMealJson(content)
    }

    private fun parseMealJson(content: String): MealParseResult? {
        return runCatching {
            val root = JSONObject(content)
            val arr = root.optJSONArray("items") ?: return null
            val items = mutableListOf<MealParseItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").trim()
                if (name.isBlank()) continue
                val grams = o.optDouble("grams", 0.0).toFloat().takeIf { it >= 1f } ?: continue
                val cal = o.optDouble("calories", 0.0).toFloat()
                if (cal <= 0f || cal > 2500f) continue
                val protein = o.optDouble("protein", 0.0).toFloat().coerceAtLeast(0f)
                val carbs = o.optDouble("carbs", 0.0).toFloat().coerceAtLeast(0f)
                val fat = o.optDouble("fat", 0.0).toFloat().coerceAtLeast(0f)
                val fiber = o.optDouble("fiber", 0.0).toFloat().coerceAtLeast(0f)
                items.add(
                    MealParseItem(
                        name = name,
                        grams = grams,
                        calories = cal,
                        protein = protein,
                        carbs = carbs,
                        fat = fat,
                        fiber = fiber
                    )
                )
            }
            if (items.isEmpty()) return null
            val mealStr = root.optString("mealType", "").uppercase()
            val meal = when (mealStr) {
                "BREAKFAST" -> MealType.BREAKFAST
                "LUNCH" -> MealType.LUNCH
                "DINNER" -> MealType.DINNER
                "SNACK" -> MealType.SNACK
                else -> null
            }
            MealParseResult(items = items, mealType = meal)
        }.onFailure { Log.w(TAG, "parseMealJson: ${it.message}") }.getOrNull()
    }
}
