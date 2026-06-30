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

    class InstagramBlockedException(url: String) : Exception("INSTAGRAM_BLOCKED:$url")

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
        "tiktok.com" in url                           -> "tiktok"
        "youtube.com" in url || "youtu.be" in url     -> "youtube"
        else                                          -> "web"
    }

    // ── INSTAGRAM ──────────────────────────────────────────────────────────────

    private suspend fun scrapeInstagram(url: String): Recipe {
        val shortcode = extractInstagramShortcode(url)
        val oEmbed    = runCatching { fetchOEmbed("https://api.instagram.com/oembed/?url=${encode(url)}&omitscript=true") }.getOrNull()
        var thumbnail = oEmbed?.get("thumbnail_url")
        val author    = oEmbed?.get("author_name")
        var caption   = ""

        caption = runCatching { InstagramWebViewScraper.extractCaption(context, url) ?: "" }.getOrElse { "" }

        if (shortcode != null && (caption.isBlank() || thumbnail.isNullOrBlank())) {
            runCatching {
                val doc = jsoupGet("https://imginn.com/p/$shortcode/")
                if (caption.isBlank()) {
                    caption = doc.select(".desc, .photo-desc, [class*=desc], [class*=caption]").text()
                        .ifBlank { doc.select("meta[property=og:description]").attr("content") }
                }
                if (thumbnail.isNullOrBlank()) thumbnail = extractOgImage(doc)
            }
        }
        if (shortcode != null && (caption.isBlank() || thumbnail.isNullOrBlank())) {
            runCatching {
                val doc = jsoupGet("https://www.picuki.com/media/$shortcode")
                if (caption.isBlank()) {
                    caption = doc.select(".photo-description, .description, [class*=caption], [class*=desc]").text()
                        .ifBlank { doc.select("meta[property=og:description]").attr("content") }
                        .ifBlank { doc.select("meta[name=description]").attr("content") }
                }
                if (thumbnail.isNullOrBlank()) thumbnail = extractOgImage(doc)
            }
        }
        if (shortcode != null && (caption.isBlank() || thumbnail.isNullOrBlank())) {
            runCatching {
                val doc = jsoupGet("https://www.imgsed.com/p/$shortcode")
                if (caption.isBlank()) {
                    caption = doc.select("meta[property=og:description]").attr("content")
                        .ifBlank { doc.select("meta[name=description]").attr("content") }
                }
                if (thumbnail.isNullOrBlank()) thumbnail = extractOgImage(doc)
            }
        }
        // instagramez.com — active mirror as of mid-2026
        if (caption.isBlank() || thumbnail.isNullOrBlank()) {
            runCatching {
                val ezUrl = url.replace("www.instagram.com", "www.instagramez.com").replace("instagram.com", "instagramez.com")
                val doc = jsoupGetWithUA(ezUrl,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                if (caption.isBlank()) {
                    caption = doc.select("meta[property=og:description]").attr("content")
                        .ifBlank { doc.select("meta[name=description]").attr("content") }
                }
                if (thumbnail.isNullOrBlank()) thumbnail = extractOgImage(doc)
            }
        }
        // ddinstagram.com — keep as additional fallback
        if (caption.isBlank() || thumbnail.isNullOrBlank()) {
            runCatching {
                val ddUrl = url.replace("www.instagram.com", "www.ddinstagram.com").replace("instagram.com", "ddinstagram.com")
                val doc = jsoupGet(ddUrl)
                if (caption.isBlank()) {
                    caption = doc.select("meta[property=og:description]").attr("content")
                        .ifBlank { doc.select("meta[name=description]").attr("content") }
                }
                if (thumbnail.isNullOrBlank()) thumbnail = extractOgImage(doc)
            }
        }
        if (caption.isBlank() || thumbnail.isNullOrBlank()) {
            runCatching {
                val doc = jsoupGet(url)
                if (caption.isBlank()) {
                    caption = doc.select("meta[property=og:description]").attr("content")
                        .ifBlank { doc.select("meta[name=description]").attr("content") }
                }
                if (thumbnail.isNullOrBlank()) thumbnail = extractOgImage(doc)
            }
        }

        // Last resort thumbnail: Instagram's public post thumbnail endpoint
        if (thumbnail.isNullOrBlank() && shortcode != null) {
            thumbnail = "https://www.instagram.com/p/$shortcode/media/?size=l"
        }

        if (caption.isBlank()) throw InstagramBlockedException(url)

        val apiKey = runCatching { BuildConfig.GROQ_API_KEY }.getOrElse { "" }
        val parsed = if (apiKey.isNotBlank()) {
            RecipeAiParser.parse(caption, url, "instagram", thumbnail, apiKey)
        } else {
            RecipeAiParser.fallbackParse(caption, url, "instagram", thumbnail)
        }
        return parsed.copy(
            imageUrl  = thumbnail ?: parsed.imageUrl,
            sourceUrl = url,
            platform  = "instagram",
            tags      = listOfNotNull(parsed.tags.ifBlank { null }, author?.let { "@$it" }).joinToString(",").take(200)
        )
    }

    private fun extractOgImage(doc: Document): String? {
        val candidates = listOf(
            doc.select("meta[property=og:image]").attr("content"),
            doc.select("meta[property=og:image:secure_url]").attr("content"),
            doc.select("meta[name=twitter:image]").attr("content")
        )
        return candidates.firstOrNull { it.isNotBlank() && "default" !in it.lowercase() && "logo" !in it.lowercase() }
            ?.let { if (it.startsWith("http")) it else null }
    }

    private fun extractInstagramShortcode(url: String): String? =
        Regex("/(?:p|reel|tv)/([A-Za-z0-9_-]+)/?").find(url)?.groupValues?.get(1)

    // ── TIKTOK ─────────────────────────────────────────────────────────────────
    // Strategy order (most reliable first):
    //  1. tikwm.com API  — free, no auth, returns title + cover + author
    //  2. snaptik / musicaldown mirror sites (og:description)
    //  3. Dedicated TikTok WebView scraper (desktop UA + TikTok DOM selectors)
    //  4. Generic og:description via Jsoup
    //  5. oEmbed title (lowest quality, no body text)

    private suspend fun scrapeTikTok(url: String): Recipe {
        // Expand vm.tiktok.com / vt.tiktok.com short links
        val expandedUrl = runCatching {
            if ("vm.tiktok.com" in url || "vt.tiktok.com" in url) {
                client.newCall(Request.Builder().url(url).get().build())
                    .execute().use { it.request.url.toString() }
            } else url
        }.getOrElse { url }

        var caption:   String? = null
        var thumbnail: String? = null
        var author:    String? = null

        // ── 1. tikwm.com API ─────────────────────────────────────────────────
        runCatching {
            // tikwm accepts both full URLs and short links; try both
            for (tryUrl in listOf(expandedUrl, url).distinct()) {
                val apiUrl = "https://www.tikwm.com/api/?url=${encode(tryUrl)}"
                val raw = fetchStringWithUA(apiUrl,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                val root = org.json.JSONObject(raw)
                val code = root.optInt("code", -1)
                val j    = root.optJSONObject("data")
                if (code == 0 && j != null) {
                    val t = j.optString("title", "").ifBlank { j.optString("desc", "") }.trim()
                    // Always extract thumbnail + author BEFORE break so they're not skipped
                    // Prefer origin_cover (stable) over cover (signed CDN URL that expires quickly)
                    if (thumbnail == null) {
                        val originCover = j.optString("origin_cover", "").ifBlank { null }
                        val cover       = j.optString("cover", "").ifBlank { null }
                        // tiktokcdn.com URLs are auth-signed and often fail in AsyncImage → skip them
                        thumbnail = when {
                            originCover != null && "tiktokcdn.com" !in originCover -> originCover
                            cover != null && "tiktokcdn.com" !in cover             -> cover
                            originCover != null -> originCover  // fallback: use even if CDN (better than nothing)
                            else -> cover
                        }
                    }
                    if (author == null) author = j.optJSONObject("author")?.optString("nickname")
                    if (t.isNotBlank()) { caption = t; break }
                }
            }
        }

        // ── 2. Mirror sites with og:description ──────────────────────────────
        if (caption.isNullOrBlank() || thumbnail == null) {
            // snaptik — often has full descriptions in og:description
            runCatching {
                val doc = jsoupGetWithUA("https://snaptik.app/en?url=${encode(expandedUrl)}",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                if (caption.isNullOrBlank()) {
                    caption = doc.select("meta[property=og:description]").attr("content")
                        .ifBlank { doc.select(".video-title, .description, [class*=title]").text() }
                        .ifBlank { null }
                }
                if (thumbnail == null) thumbnail = extractOgImage(doc)
            }
        }

        if (caption.isNullOrBlank() || thumbnail == null) {
            // tikmate.online — another free mirror
            runCatching {
                val doc = jsoupGetWithUA("https://tikmate.online/?url=${encode(expandedUrl)}",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                if (caption.isNullOrBlank()) {
                    caption = doc.select("meta[property=og:description]").attr("content")
                        .ifBlank { doc.select(".video-info, .caption, h2").text() }
                        .ifBlank { null }
                }
                if (thumbnail == null) thumbnail = extractOgImage(doc)
            }
        }

        // ── 3. Dedicated TikTok WebView scraper ──────────────────────────────
        if (caption.isNullOrBlank()) {
            val webViewResult = runCatching {
                TikTokWebViewScraper.extract(context, expandedUrl)
            }.getOrNull()
            if (webViewResult != null) {
                if (caption.isNullOrBlank()) caption = webViewResult.caption
                if (author == null) author = webViewResult.author
            }
        }

        // ── 4. Jsoup og:description ───────────────────────────────────────────
        if (caption.isNullOrBlank() || thumbnail == null) {
            runCatching {
                val doc = jsoupGetWithUA(expandedUrl,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                if (caption.isNullOrBlank()) {
                    caption = doc.select("meta[property=og:description]").attr("content")
                        .ifBlank { doc.select("meta[name=description]").attr("content") }
                        .ifBlank { null }
                }
                if (thumbnail == null) thumbnail = extractOgImage(doc)
            }
        }

        // ── 5. oEmbed (title only, no body) ──────────────────────────────────
        if (caption.isNullOrBlank() || thumbnail == null) {
            val oEmbed = runCatching {
                fetchOEmbed("https://www.tiktok.com/oembed?url=${encode(expandedUrl)}")
            }.getOrNull()
            if (caption.isNullOrBlank()) caption = oEmbed?.get("title")
            if (thumbnail == null) thumbnail = oEmbed?.get("thumbnail_url")
            if (author == null) author = oEmbed?.get("author_name")
        }

        // Nothing worked → placeholder with edit hint
        if (caption.isNullOrBlank()) {
            return Recipe(
                title        = "TikTok Rezept",
                description  = "Caption konnte nicht geladen werden. Tippe auf ✏️ und füge die Zutaten manuell ein.",
                sourceUrl    = url,
                platform     = "tiktok",
                imageUrl     = thumbnail,
                ingredients  = "",
                tags         = author?.let { "@$it" } ?: "tiktok"
            )
        }

        val apiKey = runCatching { BuildConfig.GROQ_API_KEY }.getOrElse { "" }
        val parsed = if (apiKey.isNotBlank()) {
            RecipeAiParser.parse(caption!!, url, "tiktok", thumbnail, apiKey)
        } else {
            RecipeAiParser.fallbackParse(caption!!, url, "tiktok", thumbnail)
        }
        return parsed.copy(
            imageUrl  = thumbnail ?: parsed.imageUrl,
            sourceUrl = url,
            platform  = "tiktok",
            tags      = listOfNotNull(parsed.tags.ifBlank { null }, author?.let { "@$it" }).joinToString(",").take(200)
        )
    }

    // ── GENERIC WEB ────────────────────────────────────────────────────────────

    private fun scrapeWeb(url: String, platform: String): Recipe {
        val doc = jsoupGet(url)

        val jsonLdBlocks = doc.select("script[type='application/ld+json']").map { it.data() }
        for (raw in jsonLdBlocks) {
            val recipeJson = extractRecipeFromJsonLd(raw) ?: continue
            return parseJsonLd(recipeJson, url, platform, doc)
        }

        val title = doc.select("meta[property=og:title]").attr("content").ifBlank { doc.title() }
        val desc  = doc.select("meta[property=og:description]").attr("content")
        val image = doc.select("meta[property=og:image]").attr("content").ifBlank { null }
        val lists = doc.select("ul li, ol li").take(30)
            .joinToString("\n") { "• ${it.text().trim()}" }

        return Recipe(
            title        = cleanTitle(title, url),
            description  = desc.take(400),
            imageUrl     = image,
            sourceUrl    = url,
            platform     = platform,
            ingredients  = lists.ifBlank { "Zutaten nicht gefunden." },
            instructions = "Anleitung nicht gefunden.",
            tags         = platform
        )
    }

    private fun extractRecipeFromJsonLd(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val obj = org.json.JSONObject(raw)
            when {
                obj.optString("@type").contains("Recipe", ignoreCase = true) -> raw
                obj.has("@graph") -> {
                    val graph = obj.getJSONArray("@graph")
                    (0 until graph.length())
                        .map { graph.getJSONObject(it) }
                        .firstOrNull { it.optString("@type").contains("Recipe", ignoreCase = true) }
                        ?.toString()
                }
                else -> null
            }
        } catch (_: Exception) {
            try {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length())
                    .map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString("@type").contains("Recipe", ignoreCase = true) }
                    ?.toString()
            } catch (_: Exception) { null }
        }
    }

    private fun parseJsonLd(json: String, url: String, platform: String, doc: Document): Recipe {
        fun field(key: String) = Regex(""""$key"\s*:\s*"([^"]*?)"""").find(json)?.groupValues?.get(1)
        fun listField(key: String): List<String> {
            val arr = Regex(""""$key"\s*:\s*\[([^\]]*?)]""", RegexOption.DOT_MATCHES_ALL)
                .find(json)?.groupValues?.get(1) ?: return emptyList()
            val strings = Regex(""""([^"]+)"""").findAll(arr).map { it.groupValues[1] }.toList()
            if (strings.isNotEmpty()) return strings
            return Regex(""""text"\s*:\s*"([^"]+)"""").findAll(arr).map { it.groupValues[1] }.toList()
        }
        fun parseDur(iso: String) =
            (Regex("""(\d+)H""").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0) * 60 +
            (Regex("""(\d+)M""").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0)

        val yieldRaw = field("recipeYield") ?: ""
        val servings = Regex("""\d+""").find(yieldRaw)?.value?.toIntOrNull() ?: 1
        val ingredients  = listField("recipeIngredient")
        val instructions = listField("recipeInstructions").ifEmpty { listField("text") }

        return Recipe(
            title           = cleanTitle(field("name") ?: doc.title(), url),
            description     = (field("description") ?: "").take(400),
            imageUrl        = field("image") ?: doc.select("meta[property=og:image]").attr("content").ifBlank { null },
            sourceUrl       = url,
            platform        = platform,
            ingredients     = ingredients.joinToString("\n") { "• $it" }.ifBlank { "Nicht gefunden." },
            instructions    = instructions.mapIndexed { i, s -> "${i+1}. $s" }.joinToString("\n").ifBlank { "" },
            servings        = servings,
            prepTimeMinutes = field("prepTime")?.let { parseDur(it) }?.takeIf { it > 0 },
            tags            = (field("keywords") ?: platform).take(200),
            totalCalories   = null
        )
    }

    private fun fetchOEmbed(url: String): Map<String, String> {
        val raw = fetchString(url)
        return buildMap {
            listOf("thumbnail_url", "author_name", "title", "html").forEach { key ->
                Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(raw)
                    ?.groupValues?.get(1)?.let { put(key, it.replace("\\u0026", "&").replace("\\/", "/")) }
            }
        }
    }

    private fun cleanTitle(raw: String, sourceUrl: String? = null): String {
        val cleaned = raw.replace(Regex("""\s*[-|].*$"""), "").trim()
        if (cleaned.isNotBlank()) return cleaned
        val host = sourceUrl?.let { runCatching { java.net.URI(it).host?.removePrefix("www.") }.getOrNull() }
        return if (!host.isNullOrBlank()) "Rezept von $host" else "Rezept"
    }

    private fun encode(url: String) = java.net.URLEncoder.encode(url, "UTF-8")

    private fun jsoupGet(url: String): Document = Jsoup.parse(fetchString(url), url)

    private fun jsoupGetWithUA(url: String, ua: String): Document {
        val raw = fetchStringWithUA(url, ua)
        return Jsoup.parse(raw, url)
    }

    private fun fetchString(url: String): String {
        val req = Request.Builder().url(url).build()
        return client.newCall(req).execute().use { resp ->
            resp.body?.string() ?: throw Exception("Leere Antwort von $url")
        }
    }

    private fun fetchStringWithUA(url: String, ua: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        return client.newCall(req).execute().use { resp ->
            resp.body?.string() ?: throw Exception("Leere Antwort von $url")
        }
    }
}
