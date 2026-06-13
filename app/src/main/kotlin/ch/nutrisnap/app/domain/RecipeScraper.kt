package ch.nutrisnap.app.domain

import android.content.Context
import ch.nutrisnap.app.BuildConfig
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeScrapeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

class RecipeScraper(private val context: Context) {

    class InstagramBlockedException(url: String) :
        Exception("INSTAGRAM_BLOCKED:$url")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Cache-Control", "no-cache")
                .build()
            chain.proceed(req)
        }
        .build()

    suspend fun scrape(rawUrl: String): RecipeScrapeResult = withContext(Dispatchers.IO) {
        runCatching {
            val url      = rawUrl.trim()
            val platform = detectPlatform(url)
            val recipe   = when (platform) {
                "instagram" -> scrapeInstagram(url)
                "tiktok"    -> scrapeTikTok(url)
                else        -> scrapeWeb(url, platform)
            }
            RecipeScrapeResult(success = true, recipe = recipe)
        }.getOrElse { e ->
            when (e) {
                is InstagramBlockedException ->
                    RecipeScrapeResult(success = false, error = e.message, instagramBlocked = true)
                else ->
                    RecipeScrapeResult(success = false, error = "Fehler: ${e.message}")
            }
        }
    }

    private fun detectPlatform(url: String) = when {
        "instagram.com" in url || "instagr.am" in url -> "instagram"
        "tiktok.com"    in url                        -> "tiktok"
        "youtube.com"   in url || "youtu.be" in url   -> "youtube"
        else                                          -> "web"
    }

    // ── INSTAGRAM ──────────────────────────────────────────────────────────────

    private suspend fun scrapeInstagram(url: String): Recipe {
        val shortcode = extractInstagramShortcode(url)

        val oEmbed    = runCatching { fetchOEmbed("https://api.instagram.com/oembed/?url=${encode(url)}&omitscript=true") }.getOrNull()
        val thumbnail = oEmbed?.get("thumbnail_url")
        val author    = oEmbed?.get("author_name")

        var caption = ""

        // Strategy 1: WebView (full JS render, may share IG login cookies)
        caption = runCatching { InstagramWebViewScraper.extractCaption(context, url) ?: "" }.getOrElse { "" }

        // Strategy 2: imginn
        if (shortcode != null && caption.isBlank()) {
            caption = runCatching {
                val doc = jsoupGet("https://imginn.com/p/$shortcode/")
                doc.select(".desc, .photo-desc, [class*=desc], [class*=caption]").text()
                    .ifBlank { doc.select("meta[property=og:description]").attr("content") }
            }.getOrElse { "" }
        }

        // Strategy 3: picuki
        if (shortcode != null && caption.isBlank()) {
            caption = runCatching {
                val doc = jsoupGet("https://www.picuki.com/media/$shortcode")
                doc.select(".photo-description, .description, [class*=caption], [class*=desc]").text()
                    .ifBlank { doc.select("meta[property=og:description]").attr("content") }
                    .ifBlank { doc.select("meta[name=description]").attr("content") }
            }.getOrElse { "" }
        }

        // Strategy 4: imgsed
        if (shortcode != null && caption.isBlank()) {
            caption = runCatching {
                val doc = jsoupGet("https://www.imgsed.com/p/$shortcode")
                doc.select("meta[property=og:description]").attr("content")
                    .ifBlank { doc.select("meta[name=description]").attr("content") }
            }.getOrElse { "" }
        }

        // Strategy 5: ddinstagram
        if (caption.isBlank()) {
            caption = runCatching {
                val ddUrl = url.replace("www.instagram.com", "www.ddinstagram.com")
                               .replace("instagram.com", "ddinstagram.com")
                val doc = jsoupGet(ddUrl)
                doc.select("meta[property=og:description]").attr("content")
                    .ifBlank { doc.select("meta[name=description]").attr("content") }
            }.getOrElse { "" }
        }

        // Strategy 6: Direct
        if (caption.isBlank()) {
            caption = runCatching {
                val doc = jsoupGet(url)
                doc.select("meta[property=og:description]").attr("content")
                    .ifBlank { doc.select("meta[name=description]").attr("content") }
            }.getOrElse { "" }
        }

        if (caption.isBlank()) throw InstagramBlockedException(url)

        // ── AI parsing ────────────────────────────────────────────────────────
        val apiKey = runCatching { BuildConfig.GROQ_API_KEY }.getOrElse { "" }
        val parsed = if (apiKey.isNotBlank()) {
            RecipeAiParser.parse(
                caption   = caption,
                sourceUrl = url,
                platform  = "instagram",
                imageUrl  = thumbnail,
                apiKey    = apiKey
            )
        } else {
            RecipeAiParser.fallbackParse(caption, url, "instagram", thumbnail)
        }

        // Merge: always keep the scraped thumbnail and source URL
        return parsed.copy(
            imageUrl  = thumbnail ?: parsed.imageUrl,
            sourceUrl = url,
            platform  = "instagram",
            tags      = listOfNotNull(
                parsed.tags.ifBlank { null },
                author?.let { "@$it" }
            ).joinToString(",").take(200)
        )
    }

    private fun extractInstagramShortcode(url: String): String? =
        Regex("/(?:p|reel|tv)/([A-Za-z0-9_-]+)/?").find(url)?.groupValues?.get(1)

    // ── TIKTOK ─────────────────────────────────────────────────────────────────

    private suspend fun scrapeTikTok(url: String): Recipe {
        val expandedUrl = runCatching {
            if ("vm.tiktok.com" in url || "vt.tiktok.com" in url) {
                client.newCall(Request.Builder().url(url).head().build())
                    .execute().use { it.request.url.toString() }
            } else url
        }.getOrElse { url }

        val oEmbed      = runCatching { fetchOEmbed("https://www.tiktok.com/oembed?url=${encode(expandedUrl)}") }.getOrNull()
        val thumbnail   = oEmbed?.get("thumbnail_url")
        val author      = oEmbed?.get("author_name")
        val oEmbedTitle = oEmbed?.get("title") ?: ""

        val caption = runCatching {
            val doc = jsoupGet(expandedUrl)
            doc.select("meta[property=og:description]").attr("content")
                .ifBlank { doc.select("meta[name=description]").attr("content") }
        }.getOrElse { oEmbedTitle }

        val text   = caption.ifBlank { oEmbedTitle }
        val apiKey = runCatching { BuildConfig.GROQ_API_KEY }.getOrElse { "" }
        val parsed = if (apiKey.isNotBlank() && text.isNotBlank()) {
            RecipeAiParser.parse(text, url, "tiktok", thumbnail, apiKey)
        } else {
            RecipeAiParser.fallbackParse(text, url, "tiktok", thumbnail)
        }

        return parsed.copy(
            imageUrl  = thumbnail ?: parsed.imageUrl,
            sourceUrl = url,
            platform  = "tiktok",
            tags      = listOfNotNull(parsed.tags.ifBlank { null }, author?.let { "@$it" })
                .joinToString(",").take(200)
        )
    }

    // ── GENERIC WEB ────────────────────────────────────────────────────────────

    private fun scrapeWeb(url: String, platform: String): Recipe {
        val doc = jsoupGet(url)
        val jsonLd = doc.select("script[type='application/ld+json']")
            .firstOrNull { "Recipe" in it.data() || "recipe" in it.data() }?.data()
        if (jsonLd != null) return parseJsonLd(jsonLd, url, platform, doc)

        val title = doc.select("meta[property=og:title]").attr("content").ifBlank { doc.title() }
        val desc  = doc.select("meta[property=og:description]").attr("content")
        val image = doc.select("meta[property=og:image]").attr("content").ifBlank { null }
        val lists = doc.select("ul li, ol li").take(25)
            .joinToString("\n") { "• ${it.text().trim()}" }

        return Recipe(
            title        = cleanTitle(title),
            description  = desc.take(400),
            imageUrl     = image,
            sourceUrl    = url,
            platform     = platform,
            ingredients  = lists.ifBlank { "Zutaten nicht gefunden." },
            instructions = "Anleitung nicht gefunden.",
            tags         = platform
        )
    }

    // ── JSON-LD ────────────────────────────────────────────────────────────────

    private fun parseJsonLd(json: String, url: String, platform: String, doc: Document): Recipe {
        fun field(key: String) = Regex(""""$key"\s*:\s*"([^"]*?)"""").find(json)?.groupValues?.get(1)
        fun listField(key: String): List<String> {
            val arr = Regex(""""$key"\s*:\s*\[([^\]]*?)]""", RegexOption.DOT_MATCHES_ALL)
                .find(json)?.groupValues?.get(1) ?: return emptyList()
            return Regex(""""([^"]+)"""").findAll(arr).map { it.groupValues[1] }.toList()
        }
        fun parseDur(iso: String) =
            (Regex("""(\d+)H""").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0) * 60 +
            (Regex("""(\d+)M""").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0)

        return Recipe(
            title           = cleanTitle(field("name") ?: doc.title()),
            description     = (field("description") ?: "").take(400),
            imageUrl        = field("image") ?: doc.select("meta[property=og:image]").attr("content").ifBlank { null },
            sourceUrl       = url,
            platform        = platform,
            ingredients     = listField("recipeIngredient").joinToString("\n") { "• $it" }.ifBlank { "Nicht gefunden." },
            instructions    = listField("text").ifEmpty { listField("recipeInstructions") }
                .mapIndexed { i, s -> "${i+1}. $s" }.joinToString("\n").ifBlank { "Nicht gefunden." },
            servings        = field("recipeYield")?.filter { it.isDigit() }?.toIntOrNull() ?: 1,
            prepTimeMinutes = field("prepTime")?.let { parseDur(it) }?.takeIf { it > 0 },
            tags            = (field("keywords") ?: "").take(200)
        )
    }

    // ── oEmbed ─────────────────────────────────────────────────────────────────

    private fun fetchOEmbed(url: String): Map<String, String> {
        val raw = fetchString(url)
        return buildMap {
            listOf("thumbnail_url", "author_name", "title", "html").forEach { key ->
                Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(raw)
                    ?.groupValues?.get(1)?.let { put(key, it.replace("\\u0026", "&").replace("\\/", "/")) }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun cleanTitle(raw: String) =
        raw.replace(Regex("""\s*[-|].*$"""), "").trim().ifBlank { "Rezept" }

    private fun encode(url: String) = java.net.URLEncoder.encode(url, "UTF-8")

    private fun jsoupGet(url: String): Document =
        Jsoup.parse(fetchString(url), url)

    private fun fetchString(url: String): String {
        val req = Request.Builder().url(url).build()
        return client.newCall(req).execute().use { resp ->
            resp.body?.string() ?: throw Exception("Leere Antwort von $url")
        }
    }
}
