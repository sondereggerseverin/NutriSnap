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
 * 1. Gemini Image (beste Food-Qualität, Free-Tier ~250–500/Tag)
 * 2. Cloudflare Workers AI FLUX.1 [schnell] (~170/Tag free)
 * 3. Pollinations Flux (kein Key)
 * 4. ZenMux (falls Key Rechte hat)
 */
class ZenMuxImageService(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun generateRecipeImage(
        title: String,
        description: String,
        ingredientsHint: String = ""
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val prompt = buildFoodPrompt(title, description, ingredientsHint)

            val errors = mutableListOf<String>()

            // 1) Gemini Image – beste Food-Foto-Qualität (Free-Tier ausreichend)
            if (BuildConfig.GEMINI_API_KEY.isNotBlank()) {
                generateViaGemini(prompt).fold(
                    onSuccess = { return@withContext Result.success(it) },
                    onFailure = { errors += "Gemini: ${it.message}" }
                )
            }

            // 2) Cloudflare Workers AI – FLUX.1 schnell (zuverlässiger Free-Fallback)
            if (BuildConfig.CLOUDFLARE_ACCOUNT_ID.isNotBlank() &&
                BuildConfig.CLOUDFLARE_API_TOKEN.isNotBlank()
            ) {
                generateViaCloudflare(prompt).fold(
                    onSuccess = { return@withContext Result.success(it) },
                    onFailure = { errors += "CF: ${it.message}" }
                )
            }

            // 3) Pollinations (kein Key)
            generateViaPollinations(prompt).fold(
                onSuccess = { return@withContext Result.success(it) },
                onFailure = { errors += "Pollinations: ${it.message}" }
            )

            // 4) ZenMux
            if (BuildConfig.ZENMUX_API_KEY.isNotBlank()) {
                generateViaZenMuxOpenAi(prompt).fold(
                    onSuccess = { return@withContext Result.success(it) },
                    onFailure = { errors += "ZenMux: ${it.message}" }
                )
            }

            val msg = when {
                errors.isEmpty() -> "Kein Bild-API-Key konfiguriert"
                else -> errors.joinToString(" · ").take(160)
            }
            Log.e("RecipeImage", "Bildgenerierung fehlgeschlagen: $msg")
            Result.failure(Exception(msg))
        }


    /**
     * Cloudflare Workers AI – @cf/black-forest-labs/flux-1-schnell
     * Free tier: ~10'000 Neurons/Tag ≈ 170 Bilder.
     */
    private fun generateViaCloudflare(prompt: String): Result<String> {
        return try {
            val accountId = BuildConfig.CLOUDFLARE_ACCOUNT_ID
            val token = BuildConfig.CLOUDFLARE_API_TOKEN
            val model = "@cf/black-forest-labs/flux-1-schnell"
            val url =
                "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$model"
            val seed = (prompt.hashCode().toLong() and 0x7fffffffL)
            val body = JSONObject().apply {
                put("prompt", prompt.take(2048))
                put("seed", seed)
                put("num_steps", 6)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return Result.failure(Exception("${response.code}: ${bodyStr.take(100)}"))
            }
            val root = JSONObject(bodyStr)
            if (root.has("errors") && root.optJSONArray("errors")?.length() ?: 0 > 0) {
                val err = root.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
                return Result.failure(Exception(err ?: bodyStr.take(100)))
            }
            val result = root.optJSONObject("result") ?: root
            val b64 = result.optString("image")
            if (b64.isBlank()) {
                return Result.failure(Exception("keine image-Daten in CF-Antwort"))
            }
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            if (bytes.size < 1000) {
                return Result.failure(Exception("Bild zu klein (${bytes.size} B)"))
            }
            Result.success(saveBytes(bytes))
        } catch (e: Exception) {
            Result.failure(e)
        }
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


    /**
     * Baut einen englischen Food-Photo-Prompt – Modelle (Gemini/Flux) reagieren
     * darauf deutlich besser als auf deutsche Fliesstexte.
     */
    private fun buildFoodPrompt(title: String, description: String, ingredientsHint: String): String {
        val dish = title.trim().ifBlank { "homemade meal" }
        val lower = "$title $description $ingredientsHint".lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")

        val styleHint = when {
            // Indonesisch – Bami/Mie = Nudeln, Nasi = Reis
            "bami goreng" in lower || "bamigoreng" in lower || "mie goreng" in lower ||
                "miegoreng" in lower || "bamie" in lower ->
                "authentic Indonesian bami goreng (fried noodles), dark glossy kecap manis coated " +
                    "wheat noodles, chicken strips, shrimp, bean sprouts, fried egg on top, " +
                    "fresh cucumber and lime on the side, crispy shallots, chili slices — " +
                    "not rice, not yellow plain noodles"
            "nasi goreng" in lower || "nasigoreng" in lower || "gebratener reis" in lower ->
                "authentic Indonesian nasi goreng, dark brown wok-fried rice with caramelized " +
                    "kecap manis, scrambled egg, chicken or shrimp, green onions, cucumber slices, " +
                    "fried shallots — rich brown color, not plain yellow rice, not pale"
            "pad thai" in lower || "padthai" in lower ->
                "authentic Thai pad thai, flat rice noodles, tamarind sauce, peanuts, bean sprouts, lime"
            "thai" in lower && ("rot" in lower || "red" in lower) ->
                "authentic Thai red curry (gaeng phed) with rich red coconut milk sauce, " +
                    "chicken pieces, Thai basil, red chilies — not orange soup, not Western stew"
            "thai" in lower && ("gruen" in lower || "green" in lower) ->
                "authentic Thai green curry (gaeng khiao wan) with green coconut curry sauce"
            "thai" in lower -> "authentic Thai restaurant dish, coconut curry style"
            "curry" in lower -> "authentic restaurant curry, thick sauce, not thin soup"
            "bowl" in lower || "poke" in lower || "buddha" in lower ->
                "composed grain bowl, neatly arranged colorful toppings, fresh and vibrant"
            "pasta" in lower || "spaghetti" in lower || "penne" in lower || "tagliatelle" in lower ->
                "restaurant-style Italian pasta, glossy sauce coating the noodles, fresh herbs"
            "pizza" in lower -> "artisan pizza, blistered crust, melted cheese, overhead shot"
            "burger" in lower || "hamburger" in lower ->
                "juicy gourmet burger, stacked high, sesame bun, melted cheese, side of fries"
            "sushi" in lower || "sashimi" in lower || "maki" in lower ->
                "elegant Japanese sushi platter, fresh fish, wasabi, pickled ginger, dark slate plate"
            "steak" in lower || "rind" in lower || "beef" in lower ->
                "perfectly seared steak, medium rare, resting on plate with herb butter"
            "suppe" in lower || "soup" in lower || "eintopf" in lower ->
                "hearty restaurant soup in a deep bowl, steam rising, fresh garnish on top"
            else -> "authentic restaurant presentation of this exact dish, appetizing and detailed"
        }

        val ings = ingredientsHint
            .lines()
            .map { it.replace(Regex("""^[\d.,/\s]+[a-zA-Zµ]*\s*"""), "").trim() }
            .filter { it.length in 2..40 }
            .take(6)
            .joinToString(", ")

        return buildString {
            append("Natural handheld food photo of: $dish, shot on a real camera, not AI-generated looking. ")
            append("$styleHint. ")
            if (ings.isNotBlank()) append("Key visible ingredients: $ings. ")
            append(
                "Single bowl or plate on a worn wooden table, slight natural asymmetry, " +
                    "soft window light from the side, realistic shadows, gentle depth of field, " +
                    "true-to-life colors, visible food texture (sauce gloss, noodle strands, char), " +
                    "slight steam optional, casual home-cook or neighborhood restaurant plating. "
            )
            append(
                "Must match the named dish exactly. " +
                    "Avoid: plastic skin, waxy surfaces, oversaturated neon colors, perfect symmetry, " +
                    "CGI look, stock-photo sterility, people, hands, cutlery in foreground, text, watermark, logo, " +
                    "peas-and-corn cafeteria style."
            )
        }
    }

    /** Kostenloser Fallback ohne API-Key (Flux via Pollinations). */
    private fun generateViaPollinations(prompt: String): Result<String> {
        return try {
            // Seed aus Prompt → ähnliche Gerichte wirken konsistenter
            val seed = (prompt.hashCode().toLong() and 0x7fffffffL)
            val encoded = java.net.URLEncoder.encode(prompt.take(500), Charsets.UTF_8.name())
            val url =
                "https://image.pollinations.ai/prompt/$encoded" +
                    "?width=1024&height=1024&nologo=true&enhance=true" +
                    "&model=flux&seed=$seed&safe=false"
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
            if (bytes.size < 2000) {
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
