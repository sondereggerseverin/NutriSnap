package ch.nutrisnap.app.domain

import android.content.Context
import android.util.Base64
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
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Generiert Rezeptbilder. Reihenfolge:
 * 1. Gemini 2.5 Flash Image (GEMINI_API_KEY) – zuverlässig, schon im Projekt
 * 2. ZenMux OpenAI-kompatibel (gpt-image-2) falls Key vorhanden
 * 3. ZenMux free Gemini-Image über Vertex-kompatiblen Endpoint
 */
class ZenMuxImageService(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun generateRecipeImage(title: String, description: String): Result<String> =
        withContext(Dispatchers.IO) {
            val prompt = buildString {
                append("Professionelles, appetitliches Food-Fotografie-Bild von: $title.")
                if (description.isNotBlank()) append(" $description.")
                append(
                    " Draufsicht, natürliches Licht, auf einem Teller angerichtet, " +
                        "realistischer Food-Blog-Stil, keine Personen, kein Text im Bild."
                )
            }

            val errors = mutableListOf<String>()

            // 1) Gemini native image (bevorzugt – Key ist schon aktiv)
            if (BuildConfig.GEMINI_API_KEY.isNotBlank()) {
                generateViaGemini(prompt).fold(
                    onSuccess = { return@withContext Result.success(it) },
                    onFailure = { errors += "Gemini: ${it.message}" }
                )
            }

            // 2) ZenMux OpenAI Images
            if (BuildConfig.ZENMUX_API_KEY.isNotBlank()) {
                generateViaZenMuxOpenAi(prompt).fold(
                    onSuccess = { return@withContext Result.success(it) },
                    onFailure = { errors += "ZenMux-OA: ${it.message}" }
                )
                // 3) ZenMux free Gemini image model
                generateViaZenMuxGemini(prompt).fold(
                    onSuccess = { return@withContext Result.success(it) },
                    onFailure = { errors += "ZenMux-Gem: ${it.message}" }
                )
            }

            // 4) Pollinations (kostenlos, kein Key) – immer als letzter Fallback
            generateViaPollinations(prompt).fold(
                onSuccess = { return@withContext Result.success(it) },
                onFailure = { errors += "Pollinations: ${it.message}" }
            )

            val msg = when {
                errors.isEmpty() -> "Kein Bild-API-Key konfiguriert"
                else -> errors.joinToString(" · ").take(160)
            }
            Log.e("RecipeImage", "Bildgenerierung fehlgeschlagen: $msg")
            Result.failure(Exception(msg))
        }

    private fun generateViaGemini(prompt: String): Result<String> {
        return try {
            val apiKey = BuildConfig.GEMINI_API_KEY
            // Aktuelle Nano-Banana / Gemini-Image-Modelle (Stand 2026)
            val models = listOf(
                "gemini-2.5-flash-image",
                "gemini-3.1-flash-image",
                "gemini-3.1-flash-image-preview"
            )
            // responseModalities Varianten – Docs sind inkonsistent
            val modalityVariants = listOf(
                JSONArray().put("Image"),
                JSONArray().put("TEXT").put("IMAGE"),
                JSONArray().put("IMAGE")
            )
            var lastErr: Exception? = null
            for (model in models) {
                for (modalities in modalityVariants) {
                    val url =
                        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                    val body = JSONObject().apply {
                        put("contents", JSONArray().put(JSONObject().apply {
                            put("parts", JSONArray().put(JSONObject().apply { put("text", prompt) }))
                        }))
                        put("generationConfig", JSONObject().apply {
                            put("responseModalities", modalities)
                        })
                    }.toString()

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("x-goog-api-key", apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                    val response = client.newCall(request).execute()
                    val bodyStr = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        lastErr = Exception("$model → ${response.code}")
                        // 404 = Modell existiert nicht → nächstes Modell, nicht nächste Modalität
                        if (response.code == 404) break
                        continue
                    }
                    val bytes = extractGeminiImageBytes(bodyStr)
                    if (bytes != null) return Result.success(saveBytes(bytes))
                    lastErr = Exception("$model: keine Bilddaten (${bodyStr.take(80)})")
                }
            }
            Result.failure(lastErr ?: Exception("Gemini Bild fehlgeschlagen"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractGeminiImageBytes(bodyStr: String): ByteArray? {
        val root = JSONObject(bodyStr)
        val candidates = root.optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val parts = candidates.getJSONObject(i)
                .optJSONObject("content")
                ?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.getJSONObject(j)
                val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                if (inline != null) {
                    val b64 = inline.optString("data")
                    if (b64.isNotBlank()) return Base64.decode(b64, Base64.DEFAULT)
                }
            }
        }
        return null
    }

    private fun generateViaZenMuxOpenAi(prompt: String): Result<String> {
        return try {
            val requestJson = JSONObject().apply {
                put("model", "gpt-image-2")
                put("prompt", prompt)
                put("n", 1)
                put("size", "1024x1024")
            }.toString()
            val request = Request.Builder()
                .url("https://zenmux.ai/api/v1/images/generations")
                .addHeader("Authorization", "Bearer ${BuildConfig.ZENMUX_API_KEY}")
                .post(requestJson.toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return Result.failure(Exception("${response.code}: ${bodyStr.take(100)}"))
            }
            val data0 = JSONObject(bodyStr).getJSONArray("data").getJSONObject(0)
            when {
                data0.has("b64_json") && !data0.isNull("b64_json") -> {
                    val bytes = Base64.decode(data0.getString("b64_json"), Base64.DEFAULT)
                    Result.success(saveBytes(bytes))
                }
                data0.has("url") && !data0.isNull("url") -> {
                    val imgUrl = data0.getString("url")
                    val imgBytes = client.newCall(Request.Builder().url(imgUrl).build())
                        .execute().body?.bytes()
                        ?: return Result.failure(Exception("Bild-URL leer"))
                    Result.success(saveBytes(imgBytes))
                }
                else -> Result.failure(Exception("weder b64_json noch url"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun generateViaZenMuxGemini(prompt: String): Result<String> {
        return try {
            // Free Gemini image via ZenMux Gemini-compatible endpoint
            val models = listOf(
                "google/gemini-2.5-flash-image-free",
                "google/gemini-2.5-flash-image"
            )
            var lastErr: Exception? = null
            for (model in models) {
                val url = "https://zenmux.ai/api/v1beta/models/${model}:generateContent"
                val body = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().apply { put("text", prompt) }))
                    }))
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
                    })
                }.toString()
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer ${BuildConfig.ZENMUX_API_KEY}")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                val response = client.newCall(request).execute()
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    lastErr = Exception("$model → ${response.code}")
                    continue
                }
                val bytes = extractGeminiImageBytes(bodyStr)
                if (bytes != null) return Result.success(saveBytes(bytes))
                lastErr = Exception("$model: keine Bilddaten")
            }
            Result.failure(lastErr ?: Exception("ZenMux Gemini Bild fehlgeschlagen"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    /** Kostenloser Fallback ohne API-Key (Flux via Pollinations). */
    private fun generateViaPollinations(prompt: String): Result<String> {
        return try {
            // Kurzer, englischer Food-Prompt für bessere Bildqualität
            val short = prompt
                .replace(Regex("""(?i)Professionelles, appetitliches Food-Fotografie-Bild von:\s*"""), "")
                .replace(Regex("""Draufsicht.*"""), "")
                .trim()
                .take(200)
            val encoded = java.net.URLEncoder.encode(
                "professional food photography, plated dish, natural light, top view, appetizing, no text, no watermark: $short",
                Charsets.UTF_8.name()
            )
            val url = "https://image.pollinations.ai/prompt/$encoded?width=1024&height=1024&nologo=true&enhance=true"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "NutriSnap/1.1")
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return Result.failure(Exception("${response.code}"))
            }
            val bytes = response.body?.bytes()
                ?: return Result.failure(Exception("leerer Body"))
            if (bytes.size < 1000) {
                return Result.failure(Exception("Antwort zu klein (${bytes.size} B)"))
            }
            Result.success(saveBytes(bytes))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun saveBytes(bytes: ByteArray): String {
        val dir = File(context.filesDir, "recipe_images").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.png")
        file.writeBytes(bytes)
        return "file://${file.absolutePath}"
    }
}
