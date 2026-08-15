package ch.nutrisnap.app.domain

import ch.nutrisnap.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Transkribiert Video-/Audio-URLs via Groq Whisper (Free-Tier).
 * Wird nur genutzt, wenn Toggle an und Caption zu schwach ist.
 */
object VideoTranscriptService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val TRANSCRIBE_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MAX_BYTES = 24 * 1024 * 1024 // Groq-Limit ~25 MB

    /**
     * @param mediaUrl direkte Play-URL (mp4/webm/mp3), nicht die Social-Share-URL
     * @return Transkript-Text oder null bei Fehler/leerem Ergebnis
     */
    suspend fun transcribe(mediaUrl: String, apiKey: String = BuildConfig.GROQ_API_KEY): String? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || mediaUrl.isBlank()) return@withContext null
            runCatching {
                val bytes = downloadBytes(mediaUrl) ?: return@runCatching null
                if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@runCatching null

                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        "audio.mp4",
                        bytes.toRequestBody("video/mp4".toMediaType())
                    )
                    .addFormDataPart("model", "whisper-large-v3")
                    .addFormDataPart("language", "de")
                    .addFormDataPart("response_format", "json")
                    .build()

                val req = Request.Builder()
                    .url(TRANSCRIBE_URL)
                    .header("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()

                client.newCall(req).execute().use { resp ->
                    val str = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@runCatching null
                    val text = JSONObject(str).optString("text").trim()
                    text.takeIf { it.length >= 30 }
                }
            }.getOrNull()
        }

    /**
     * Holt eine abspielbare Media-URL von TikTok (tikwm) oder null.
     */
    fun resolveTikTokMediaUrl(expandedUrl: String): String? {
        return runCatching {
            val apiUrl = "https://www.tikwm.com/api/?url=${java.net.URLEncoder.encode(expandedUrl, "UTF-8")}&hd=1"
            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "Mozilla/5.0 (compatible; NutriSnap/1.0)")
                .build()
            val raw = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            if (raw.isBlank()) return null
            // data.play / data.hdplay / data.wmplay
            val candidates = listOf("hdplay", "play", "wmplay", "music")
            for (key in candidates) {
                val v = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                    .find(raw)?.groupValues?.get(1)
                    ?.replace("\\u0026", "&")
                    ?.replace("\\/", "/")
                    ?.replace("\\\"", "\"")
                if (!v.isNullOrBlank() && v.startsWith("http")) return v
            }
            null
        }.getOrNull()
    }

    /**
     * Versucht eine IG-Video-URL über öffentliche Embed-/Mirror-Seiten.
     * Oft null – dann wird Transkript übersprungen.
     */
    fun resolveInstagramMediaUrl(shortcode: String): String? {
        val desktopUa =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val urls = listOf(
            "https://www.ddinstagram.com/reel/$shortcode",
            "https://www.ddinstagram.com/p/$shortcode",
            "https://www.instagram.com/p/$shortcode/embed/captioned/"
        )
        for (u in urls) {
            val html = runCatching {
                val req = Request.Builder().url(u)
                    .header("User-Agent", desktopUa)
                    .header("Accept", "text/html")
                    .build()
                client.newCall(req).execute().use { it.body?.string().orEmpty() }
            }.getOrNull().orEmpty()
            if (html.isBlank()) continue
            // video_url / contentUrl / <video src=
            val patterns = listOf(
                Regex(""""video_url"\s*:\s*"(https://[^"]+)""""),
                Regex(""""contentUrl"\s*:\s*"(https://[^"]+)""""),
                Regex("""<meta\s+property="og:video(?::secure_url)?"\s+content="(https://[^"]+)""""),
                Regex("""<video[^>]+src="(https://[^"]+)"""")
            )
            for (p in patterns) {
                val m = p.find(html)?.groupValues?.get(1)
                    ?.replace("\\u0026", "&")
                    ?.replace("\\/", "/")
                if (!m.isNullOrBlank()) return m
            }
        }
        return null
    }

    private fun downloadBytes(url: String): ByteArray? {
        val req = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "*/*")
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.bytes()
        }
    }

    /** Caption gilt als „schwach“, wenn Transkript helfen könnte. */
    fun isWeakCaption(caption: String?): Boolean {
        if (caption.isNullOrBlank()) return true
        val t = caption.trim()
        if (t.length < 80) return true
        val lc = t.lowercase()
        val hints = listOf(
            "zutaten", "ingredient", "rezept", "recipe", "anleitung",
            " tbsp", " tsp", " el ", " tl ", "gramm", " g ", " ml"
        )
        return hints.none { it in lc }
    }
}
