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
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Generiert ein KI-Bild für ein Rezept über die ZenMux Image-API
 * (OpenAI-kompatibler Endpoint, Modell "gpt-image-2").
 * Speichert das Ergebnis lokal unter /files/recipe_images/ und gibt einen
 * "file://"-URI zurück, der wie eine normale Bild-URL in Coil verwendet werden kann.
 */
class ZenMuxImageService(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun generateRecipeImage(title: String, description: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val apiKey = BuildConfig.ZENMUX_API_KEY
                if (apiKey.isBlank()) {
                    return@withContext Result.failure(
                        Exception("Kein ZENMUX_API_KEY konfiguriert")
                    )
                }

                val prompt = buildString {
                    append("Professionelles, appetitliches Food-Fotografie-Bild von: $title.")
                    if (description.isNotBlank()) append(" $description.")
                    append(
                        " Draufsicht, natürliches Licht, auf einem Teller angerichtet, " +
                            "realistischer Food-Blog-Stil, keine Personen, kein Text im Bild."
                    )
                }

                val requestJson = JSONObject().apply {
                    put("model", "gpt-image-2")
                    put("prompt", prompt)
                    put("n", 1)
                    put("size", "1024x1024")
                }.toString()

                val request = Request.Builder()
                    .url("https://zenmux.ai/api/v1/images/generations")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(requestJson.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val bodyStr = response.body?.string()
                    ?: return@withContext Result.failure(Exception("Leere Antwort von ZenMux"))
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("ZenMux Fehler ${response.code}: $bodyStr"))
                }

                val root = JSONObject(bodyStr)
                val b64 = root.getJSONArray("data").getJSONObject(0).getString("b64_json")
                val bytes = Base64.decode(b64, Base64.DEFAULT)

                val dir = File(context.filesDir, "recipe_images").apply { mkdirs() }
                val file = File(dir, "${UUID.randomUUID()}.png")
                file.writeBytes(bytes)

                Result.success(file.toURI().toString())
            } catch (e: Exception) {
                Log.e("ZenMuxImage", "Bildgenerierung fehlgeschlagen: ${e.message}", e)
                Result.failure(e)
            }
        }
}
