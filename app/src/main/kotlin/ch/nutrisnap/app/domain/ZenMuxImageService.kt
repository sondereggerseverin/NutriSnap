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
 * 1. Grok Imagine (xAI) – beste Food-Qualität, wenn GROK_API_KEY gesetzt
 * 2. Gemini Image (Free-Tier ~250–500/Tag)
 * 3. Cloudflare Workers AI FLUX.1 [schnell]
 * 4. Pollinations Flux (kein Key)
 * 5. ZenMux (falls Key Rechte hat)
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

            // 1) Grok Imagine (xAI) – Primary, wenn Key vorhanden
            if (BuildConfig.GROK_API_KEY.isNotBlank()) {
                generateViaGrok(prompt).fold(
                    onSuccess = { return@withContext Result.success(it) },
                    onFailure = { errors += "Grok: ${it.message}" }
                )
            }

            // 2) Gemini Image – Free-Tier Fallback
            if (BuildConfig.GEMINI_API_KEY.isNotBlank()) {
                generateViaGemini(prompt).fold(
                    onSuccess = { return@withContext Result.success(it) },
                    onFailure = { errors += "Gemini: ${it.message}" }
                )
            }

            // 3) Cloudflare Workers AI – FLUX.1 schnell
            if (BuildConfig.CLOUDFLARE_ACCOUNT_ID.isNotBlank() &&
                BuildConfig.CLOUDFLARE_API_TOKEN.isNotBlank()
            ) {
                generateViaCloudflare(prompt).fold(
                    onSuccess = { return@withContext Result.success(it) },
                    onFailure = { errors += "CF: ${it.message}" }
                )
            }

            // 4) Pollinations (kein Key)
            generateViaPollinations(prompt).fold(
                onSuccess = { return@withContext Result.success(it) },
                onFailure = { errors += "Pollinations: ${it.message}" }
            )

            // 5) ZenMux
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
     * xAI Grok Imagine – OpenAI-kompatible Image API.
     * Modelle: grok-imagine-image (~$0.02), grok-imagine-image-quality (~$0.05–0.07).
     */
    private fun generateViaGrok(prompt: String): Result<String> {
        return try {
            val models = listOf("grok-imagine-image", "grok-imagine-image-quality")
            var lastErr: Exception? = null
            for (model in models) {
                val requestJson = JSONObject().apply {
                    put("model", model)
                    put("prompt", prompt)
                    put("n", 1)
                    put("response_format", "b64_json")
                }.toString()
                val request = Request.Builder()
                    .url("https://api.x.ai/v1/images/generations")
                    .addHeader("Authorization", "Bearer ${BuildConfig.GROK_API_KEY}")
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toRequestBody("application/json".toMediaType()))
                    .build()
                val response = client.newCall(request).execute()
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    lastErr = Exception("$model → ${response.code}: ${bodyStr.take(120)}")
                    Log.w("RecipeImage", "Grok $model fehlgeschlagen: ${response.code}")
                    continue
                }
                val data = JSONObject(bodyStr).optJSONArray("data")
                    ?: run {
                        lastErr = Exception("$model: kein data-Array")
                        continue
                    }
                if (data.length() == 0) {
                    lastErr = Exception("$model: leeres data")
                    continue
                }
                val data0 = data.getJSONObject(0)
                when {
                    data0.has("b64_json") && !data0.isNull("b64_json") -> {
                        val bytes = Base64.decode(data0.getString("b64_json"), Base64.DEFAULT)
                        if (bytes.size < 2000) {
                            lastErr = Exception("$model: Bild zu klein")
                            continue
                        }
                        Log.i("RecipeImage", "Grok Imagine OK ($model, ${bytes.size} B)")
                        return Result.success(saveBytes(bytes))
                    }
                    data0.has("url") && !data0.isNull("url") -> {
                        val imgUrl = data0.getString("url")
                        val imgBytes = client.newCall(Request.Builder().url(imgUrl).build())
                            .execute().body?.bytes()
                        if (imgBytes == null || imgBytes.size < 2000) {
                            lastErr = Exception("$model: URL-Download fehlgeschlagen")
                            continue
                        }
                        Log.i("RecipeImage", "Grok Imagine OK via URL ($model, ${imgBytes.size} B)")
                        return Result.success(saveBytes(imgBytes))
                    }
                    else -> lastErr = Exception("$model: weder b64_json noch url")
                }
            }
            Result.failure(lastErr ?: Exception("Grok Imagine fehlgeschlagen"))
        } catch (e: Exception) {
            Result.failure(e)
        }
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
     * Baut einen englischen Food-Photo-Prompt im Stil echter Handy-Fotos
     * (Zuhause / einfaches Restaurant) – nicht Food-Blog / Studio.
     */
    private fun buildFoodPrompt(title: String, description: String, ingredientsHint: String): String {
        val dish = title.trim().ifBlank { "homemade meal" }
        val lower = "$title $description $ingredientsHint".lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")

        // Was genau auf dem Teller liegen muss – zuerst Identität, dann Stil
        val whatOnPlate = when {
            "bami goreng" in lower || "bamigoreng" in lower || "mie goreng" in lower ||
                "miegoreng" in lower || "bamie" in lower ->
                "Indonesian bami goreng: dark brown fried egg noodles with chicken strips and shrimp, " +
                    "topped with a fried egg, cucumber slices and lime — NOT rice, NOT pasta spaghetti"
            "nasi goreng" in lower || "nasigoreng" in lower || "gebratener reis" in lower ->
                "Indonesian nasi goreng: dark brown fried rice with kecap manis, egg, chicken or shrimp — NOT noodles"
            "naan" in lower && !("butter chicken" in lower || "curry" in lower) ->
                "ONLY garlic naan bread on a simple plate, golden brown spots, soft and puffy — " +
                    "NO pasta, NO noodles, NO curry, NO sauce bowl"
            "butter chicken" in lower || "butterchicken" in lower || "murgh makhani" in lower ->
                "butter chicken: orange-red creamy tomato chicken curry in a bowl or skillet, " +
                    "optionally with plain rice OR naan on the side — NO spaghetti, NO pasta"
            "thunfisch" in lower || "tuna" in lower ->
                "tuna vegetable skillet: chunks of tuna with mixed vegetables in a light tomato or chili-lime sauce " +
                    "in a frying pan or on a plate — NO butter chicken, NO naan, NO pasta"
            "pad thai" in lower || "padthai" in lower ->
                "pad thai with flat rice noodles, not wheat pasta"
            "thai" in lower && ("gruen" in lower || "green" in lower) ->
                "Thai green curry with coconut milk, chicken, Thai eggplant, basil — green sauce, not orange"
            "thai" in lower && ("rot" in lower || "red" in lower) ->
                "Thai red curry with coconut milk, not Western stew"
            "hacktaetschli" in lower || "hacktätschli" in lower || "meatball" in lower || "frikadelle" in lower ->
                "Swiss-style pan-fried meatballs with roasted potato cubes and tomato-mozzarella salad on a dark plate"
            "lachs" in lower || "salmon" in lower ->
                "baked salmon fillet with roasted potatoes and broccoli, meal-prep style in a glass container or on a plate"
            "pfannkuchen" in lower || "pancake" in lower ->
                "stack of homemade pancakes with cooked cherries or berries on a white plate"
            "pasta" in lower || "spaghetti" in lower || "penne" in lower || "nudeln" in lower ->
                "home-cooked pasta with the sauce from the recipe, simple white plate"
            "pizza" in lower -> "homemade pizza on a plate, not studio shot"
            else -> "exactly the dish named \"$dish\", nothing else substituted"
        }

        val ings = ingredientsHint
            .lines()
            .map { it.replace(Regex("""^[\d.,/\s]+[a-zA-Zµ]*\s*"""), "").trim() }
            .filter { it.length in 2..40 }
            .take(5)
            .joinToString(", ")

        return buildString {
            // Identität zuerst – Modelle priorisieren oft den Anfang
            append("Phone photo of homemade food: $dish. ")
            append("$whatOnPlate. ")
            if (ings.isNotBlank()) append("Main ingredients visible: $ings. ")
            append(
                "Style like a real casual phone snapshot at home or a simple restaurant: " +
                    "plain white plate or everyday bowl on a wooden table or kitchen counter, " +
                    "imperfect homemade plating, sauce may drip a little, natural mixed indoor light, " +
                    "slightly messy but appetizing, realistic textures, muted true colors, " +
                    "shot from above or 45-degree angle, no professional studio setup. "
            )
            append(
                "CRITICAL: show ONLY this dish — do not invent pasta, naan, or other foods not in the recipe. " +
                    "No hands, no fingers, no people, no cutlery held in frame, no text, no watermark, " +
                    "no plastic CGI look, no glossy food-blog perfection, no oversaturated colors."
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
