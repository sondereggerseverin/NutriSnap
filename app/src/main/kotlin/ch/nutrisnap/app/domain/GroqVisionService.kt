package ch.nutrisnap.app.domain

import android.graphics.Bitmap
import android.util.Base64
import ch.nutrisnap.app.BuildConfig
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.select
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
data class FridgeScanResult(
    val ingredients: List<String> = emptyList()
)

/** Einzelne, vom Foto separierte Zutat eines Gerichts (vor DB-Abgleich). */
@Serializable
data class DishIngredientCandidate(
    val name: String = "",
    val estimatedGrams: Float = 0f,
    /** "hoch", "mittel" oder "niedrig" — Sicherheit der Erkennung DIESER Zutat. */
    val confidence: String = "mittel"
)

/** Ergebnis der mehrstufigen Foto-Analyse: Gericht zerlegt in einzelne Zutaten. */
@Serializable
data class DishScanResult(
    val dishName: String = "",
    val ingredients: List<DishIngredientCandidate> = emptyList()
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
        private const val VISION_MODEL = "qwen/qwen3.6-27b"
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

    /**
     * Zerlegt ein Foto eines Gerichts in seine einzelnen sichtbaren Zutaten (statt eines
     * pauschalen Gesamteintrags). Robust gegenüber Tellergerichten/Bowls mit mehreren,
     * leicht überlappenden Komponenten. Deckt gleichzeitig die Stufen "Zutaten erkennen"
     * und "Zutaten trennen" ab, da beides ein einzelner Vision-Call ist.
     */
    suspend fun analyzeDishIngredients(base64Jpeg: String): Result<DishScanResult> = withContext(Dispatchers.IO) {
        val prompt = """
Du bist ein erfahrener Schweizer Ernährungsberater und Food-Vision-Experte. Analysiere das Foto eines Gerichts und zerlege es in seine einzelnen sichtbaren Bestandteile. Schätze die Portionsgrösse jeder Zutat in Gramm.

WICHTIGE SCHWEIZER PRIORITÄTEN (strikt beachten):

1. Bei cremigen, breiigen, klumpigen oder feinkörnigen Massen (grau, mauve, violett, beige, bräunlich):
   - Birchermüesli / Bircher / Overnight Oats / Müesli mit Joghurt hat HÖCHSTE Priorität.
   - Viele Birchermüesli (besonders mit Beeren, Zwetschgen, Heidelbeeren oder dunklem Joghurt) sind grau-violett oder mauve und können homogen/klumpig wirken – das ist NORMAL und kein Ausschlusskriterium.
   - Fleischsalat, Fleischpastete, Pâté oder Wurstsalat erst in Betracht ziehen, wenn klar sichtbare Fleisch-/Wurststreifen, Mayonnaise-Glanz und typische Randen-/Peperoni-Stückchen vorhanden sind UND keine Hafer-/Getreide-Textur erkennbar ist.
   - Farbe allein entscheidet NIE gegen Birchermüesli.

2. Bei dunklem Brot + rotem/glänzendem Aufstrich:
   - Typisch: Ruchbrot / Vollkornbrot / Parapan + Konfitüre / Marmelade.
   - Nicht automatisch als "Brot mit Fleischwurst" o.ä. interpretieren.

3. Bei dünnen, dunkelroten, trocken-faserigen Fleischscheiben:
   - Bevorzuge Trockenfleisch / Bündnerfleisch / Bündnerfleisch-ähnlich vor normalem Rohschinken.

4. Spiesse mit orangen Würfeln + roten Beeren + grüner Kugel = Melone + Erdbeere + Traube (klassisch).

Antworte NUR mit folgendem JSON (kein Markdown, keine Erklärungen):
{
  "dishName": "Kurze Gesamtbezeichnung des Gerichts (z.B. Schweizer Picknick-Platte)",
  "ingredients": [
    {"name": "Birchermüesli", "estimatedGrams": 120, "confidence": "mittel"},
    {"name": "Vollkornbrot mit Konfitüre", "estimatedGrams": 60, "confidence": "hoch"},
    {"name": "Trockenfleisch", "estimatedGrams": 40, "confidence": "hoch"},
    {"name": "Melone", "estimatedGrams": 100, "confidence": "hoch"},
    {"name": "Erdbeeren", "estimatedGrams": 30, "confidence": "hoch"}
  ]
}

confidence ist "hoch", "mittel" oder "niedrig". Erfinde keine Zutaten, die nicht sichtbar sind. Liste jede Zutat nur einmal.
""".trimIndent()
        // Hoeheres Token-Limit als Standard-1000: bei vielen kleinen Zutaten (Bowls, Mezze-Teller)
        // braucht die JSON-Antwort mit einem Eintrag pro Zutat mehr Platz als eine einzelne Schaetzung.
        callVisionRaw(prompt, base64Jpeg, maxTokens = 2000).mapCatching { json.decodeFromString<DishScanResult>(it) }
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

    /**
     * Ruft Gemini (primary) und Groq (fallback) PARALLEL auf und nimmt das erste
     * erfolgreiche Ergebnis. Vorher liefen beide Calls sequenziell (Gemini bis zu
     * 28s Timeout, danach erst Groq) — das war die Ursache der ~30s-Verzögerung.
     * Jetzt läuft Groq bereits mit, während Gemini noch wartet; schlägt einer der
     * beiden fehl, wird auf das Ergebnis des anderen gewartet statt neu zu starten.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun callVisionRaw(prompt: String, base64Jpeg: String, maxTokens: Int = 1000): Result<String> = coroutineScope {
        val groqDeferred: Deferred<Result<String>> = async(Dispatchers.IO) { callGroqVision(prompt, base64Jpeg, maxTokens) }

        if (!GeminiService.isAvailable()) return@coroutineScope groqDeferred.await()

        val geminiDeferred: Deferred<Result<String>> = async(Dispatchers.IO) {
            GeminiService.generateVision(prompt = prompt, base64Jpeg = base64Jpeg, temperature = 0.3, maxTokens = maxTokens)
        }

        val result = select<Result<String>> {
            geminiDeferred.onAwait { r -> if (r.isSuccess) r else groqDeferred.await() }
            groqDeferred.onAwait { r -> if (r.isSuccess) r else geminiDeferred.await() }
        }
        geminiDeferred.cancel()
        groqDeferred.cancel()
        result
    }

    private fun callGroqVision(prompt: String, base64Jpeg: String, maxTokens: Int = 1000): Result<String> {
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
                put("max_completion_tokens", maxTokens)
                put("reasoning_effort", "none")
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
