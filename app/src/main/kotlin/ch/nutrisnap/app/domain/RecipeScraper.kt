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
        val thumbnail = oEmbed?.get("thumbnail_url")
        val author    = oEmbed?.get("author_name")
        var caption   = ""

        caption = runCatching { InstagramWebViewScraper.extractCaption(context, url) ?: "" }.getOrElse { "" }

        if (shortcode != null && caption.isBlank()) {
            caption = runCatching {
                jsoupGet("https://imginn.com/p/$shortcode/")
                    .let { it.select(".desc, .photo-desc, [class*=desc], [class*=caption]").text()
                        .ifBlank { it.select("meta[property=og:description]").attr("content") } }
            }.getOrElse { "" }
        }
        if (shortcode != null && caption.isBlank()) {
            caption = runCatching {
                jsoupGet("https://www.picuki.com/media/$shortcode")
                    .let { it.select(".photo-description, .description, [class*=caption], [class*=desc]").text()
                        .ifBlank { it.select("meta[property=og:description]").attr("content") }
                        .ifBlank { it.select("meta[name=description]").attr("content") } }
            }.getOrElse { "" }
        }
        if (shortcode != null && caption.isBlank()) {
            caption = runCatching {
                jsoupGet("https://www.imgsed.com/p/$shortcode")
                    .let { it.select("meta[property=og:description]").attr("content")
                        .ifBlank { it.select("meta[name=description]").attr("content") } }
            }.getOrElse { "" }
        }
        if (caption.isBlank()) {
            caption = runCatching {
                val ddUrl = url.replace("www.instagram.com", "www.ddinstagram.com").replace("instagram.com", "ddinstagram.com")
                jsoupGet(ddUrl).let { it.select("meta[property=og:description]").attr("content")
                    .ifBlank { it.select("meta[name=description]").attr("content") } }
            }.getOrElse { "" }
        }
        if (caption.isBlank()) {
            caption = runCatching {
                jsoupGet(url).let { it.select("meta[property=og:description]").attr("content")
                    .ifBlank { it.select("meta[name=description]").attr("content") } }
            }.getOrElse { "" }
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

    private fun extractInstagramShortcode(url: String): String? =
        Regex("/(?:p|reel|tv)/([A-Za-z0-9_-]+)/?").find(url)?.groupValues?.get(1)

    // ── TIKTOK ─────────────────────────────────────────────────────────────────
    // Strategy 1: tikwm.com API — free, no auth, returns title + cover + author
    // Strategy 2: WebView (renders JS, can extract description from DOM)
    // Strategy 3: oEmbed title as last resort

    private suspend fun scrapeTikTok(url: String): Recipe {
        // Expand vm.tiktok.com short links first
        val expandedUrl = runCatching {
            if ("vm.tiktok.com" in url || "vt.tiktok.com" in url) {
                client.newCall(Request.Builder().url(url).get().build())
                    .execute().use { it.request.url.toString() }
            } else url
        }.getOrElse { url }

        // Strategy 1: tikwm.com API (most reliable for TikTok content)
        var caption   = ""
        var thumbnail: String? = null
        var author:    String? = null

        runCatching {
            val apiUrl = "https://www.tikwm.com/api/?url=${encode(expandedUrl)}"
            val raw    = fetchString(apiUrl)
            val j      = org.json.JSONObject(raw).optJSONObject("data")
            if (j != null) {
                caption   = j.optString("title", "").trim()
                thumbnail = j.optString("cover", "").ifBlank { null }
                author    = j.optJSONObject("author")?.optString("nickname")
            }
        }

        // Strategy 2: WebView (full JS render)
        if (caption.isBlank()) {
            caption = runCatching {
                InstagramWebViewScraper.extractCaption(context, expandedUrl) ?: ""
            }.getOrElse { "" }
        }

        // Strategy 3: og:description via Jsoup
        if (caption.isBlank()) {
            caption = runCatching {
                jsoupGet(expandedUrl).let {
                    it.select("meta[property=og:description]").attr("content")
                        .ifBlank { it.select("meta[name=description]").attr("content") }
                }
            }.getOrElse { "" }
        }

        // Strategy 4: oEmbed
        if (caption.isBlank() || thumbnail == null) {
            val oEmbed = runCatching { fetchOEmbed("https://www.tiktok.com/oembed?url=${encode(expandedUrl)}") }.getOrNull()
            if (caption.isBlank()) caption   = oEmbed?.get("title") ?: ""
            if (thumbnail == null) thumbnail = oEmbed?.get("thumbnail_url")
            if (author    == null) author    = oEmbed?.get("author_name")
        }

        if (caption.isBlank()) {
            return Recipe(
                title     = "TikTok Rezept",
                sourceUrl = url,
                platform  = "tiktok",
                imageUrl  = thumbnail,
                tags      = author?.let { "@$it" } ?: "tiktok"
            )
        }

        val apiKey = runCatching { BuildConfig.GROQ_API_KEY }.getOrElse { "" }
        val parsed = if (apiKey.isNotBlank()) {
            RecipeAiParser.parse(caption, url, "tiktok", thumbnail, apiKey)
        } else {
            RecipeAiParser.fallbackParse(caption, url, "tiktok", thumbnail)
        }
        return parsed.copy(
            imageUrl  = thumbnail ?: parsed.imageUrl,
            sourceUrl = url,
            platform  = "tiktok",
            tags      = listOfNotNull(parsed.tags.ifBlank { null }, author?.let { "@$it" }).joinToString(",").take(200)
        )
    }

    // ── GENERIC WEB ────────────────────────────────────────────────────────────
    // FIX: try multiple JSON-LD blocks; also handle arrays and nested @graph

    private fun scrapeWeb(url: String, platform: String): Recipe {
        val doc = jsoupGet(url)

        // Try all JSON-LD script blocks, not just the first
        val jsonLdBlocks = doc.select("script[type='application/ld+json']").map { it.data() }

        // Look for Recipe in any block, including @graph arrays
        for (raw in jsonLdBlocks) {
            val recipeJson = extractRecipeFromJsonLd(raw) ?: continue
            return parseJsonLd(recipeJson, url, platform, doc)
        }

        val title = doc.select("meta[property=og:title]").attr("content").ifBlank { doc.title() }
        val desc  = doc.select("meta[property=og:description]").attr("content")
        val image = doc.select("meta[property=og:image]").attr("content").ifBlank { null }
        // Be more generous: also grab h2/h3 sections + lists
        val lists = doc.select("ul li, ol li").take(30)
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

    /** Extract a Recipe JSON object from a JSON-LD block (handles @graph arrays) */
    private fun extractRecipeFromJsonLd(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val obj = org.json.JSONObject(raw)
            when {
                // Direct Recipe object
                obj.optString("@type").contains("Recipe", ignoreCase = true) -> raw
                // @graph array
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
            // Maybe it's a JSON array at top level
            try {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length())
                    .map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString("@type").contains("Recipe", ignoreCase = true) }
                    ?.toString()
            } catch (_: Exception) { null }
        }
    }

    // ── JSON-LD parser ─────────────────────────────────────────────────────────

    private fun parseJsonLd(json: String, url: String, platform: String, doc: Document): Recipe {
        fun field(key: String) = Regex(""""$key"\s*:\s*"([^"]*?)"""").find(json)?.groupValues?.get(1)
        fun listField(key: String): List<String> {
            // Handle both array-of-strings and array-of-objects with "text" field
            val arr = Regex(""""$key"\s*:\s*\[([^\]]*?)]""", RegexOption.DOT_MATCHES_ALL)
                .find(json)?.groupValues?.get(1) ?: return emptyList()
            // Try plain strings first
            val strings = Regex(""""([^"]+)"""").findAll(arr).map { it.groupValues[1] }.toList()
            if (strings.isNotEmpty()) return strings
            // Try objects with "text" property
            return Regex(""""text"\s*:\s*"([^"]+)"""").findAll(arr).map { it.groupValues[1] }.toList()
        }
        fun parseDur(iso: String) =
            (Regex("""(\d+)H""").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0) * 60 +
            (Regex("""(\d+)M""").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0)

        // recipeYield can be string like "4 Portionen" or just "4"
        val yieldRaw = field("recipeYield") ?: ""
        val servings = Regex("""\d+""").find(yieldRaw)?.value?.toIntOrNull() ?: 1

        val ingredients = listField("recipeIngredient")
        val instructions = listField("recipeInstructions").ifEmpty { listField("text") }

        return Recipe(
            title           = cleanTitle(field("name") ?: doc.title()),
            description     = (field("description") ?: "").take(400),
            imageUrl        = field("image") ?: doc.select("meta[property=og:image]").attr("content").ifBlank { null },
            sourceUrl       = url,
            platform        = platform,
            ingredients     = ingredients.joinToString("\n") { "• $it" }.ifBlank { "Nicht gefunden." },
            instructions    = instructions.mapIndexed { i, s -> "${i+1}. $s" }.joinToString("\n").ifBlank { "" },
            servings        = servings,
            prepTimeMinutes = field("prepTime")?.let { parseDur(it) }?.takeIf { it > 0 },
            tags            = (field("keywords") ?: platform).take(200),
            totalCalories   = null  // Web recipes rarely have this in JSON-LD
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

    private fun jsoupGet(url: String): Document = Jsoup.parse(fetchString(url), url)

    private fun fetchString(url: String): String {
        val req = Request.Builder().url(url).build()
        return client.newCall(req).execute().use { resp ->
            resp.body?.string() ?: throw Exception("Leere Antwort von $url")
        }
    }
}
