package ch.nutrisnap.app.domain

import android.content.Context
import ch.nutrisnap.app.BuildConfig
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeScrapeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RecipeScraper(private val context: Context) {

    class InstagramBlockedException(url: String) : Exception("INSTAGRAM_BLOCKED:$url")

    companion object {
        /** Caption-Cache pro normalisierter URL (Prozess-Lebensdauer). */
        private val captionCache = ConcurrentHashMap<String, String>()
        private fun cacheKey(url: String) = url.trim().lowercase().substringBefore("?").trimEnd('/')
    }

    private var progress: (String) -> Unit = {}

    private fun isGoodCaption(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.trim()
        if (t.length < 40) return false
        val lc = t.lowercase()
        val recipeHints = listOf(
            "zutaten", "ingredient", "rezept", "recipe", "anleitung", "instructions",
            " tbsp", " tsp", " el ", " tl ", "gramm", " ml", " cup"
        )
        return t.length >= 80 || recipeHints.any { it in lc }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7")
                // Wichtig: Accept-Encoding NICHT manuell setzen — sonst dekomprimiert
                // OkHttp die Antwort nicht und Mirror/Embed-HTML kommt als Müll an.
                .header("Cache-Control", "no-cache")
                .build()
            chain.proceed(req)
        }
        .build()

    suspend fun scrape(
        rawUrl: String,
        onProgress: (String) -> Unit = {}
    ): RecipeScrapeResult = withContext(Dispatchers.IO) {
        progress = onProgress
        runCatching {
            val url      = rawUrl.trim()
            val platform = detectPlatform(url)
            progress("Link erkennen…")
            val recipe   = when (platform) {
                "instagram" -> scrapeInstagram(url)
                "tiktok"    -> scrapeTikTok(url)
                else        -> {
                    progress("Seite laden…")
                    scrapeWeb(url, platform)
                }
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
        val key = cacheKey(url)

        progress("Metadaten laden…")
        val oEmbed = runCatching {
            fetchOEmbed("https://api.instagram.com/oembed/?url=${encode(url)}&omitscript=true")
        }.getOrNull()
        var thumbnail = oEmbed?.get("thumbnail_url")
        val author = oEmbed?.get("author_name")

        // Cache: gleiche URL nicht nochmal scrapen
        var caption = captionCache[key].orEmpty()
        if (isGoodCaption(caption)) {
            progress("Aus Cache…")
        } else {
            progress("Seite laden (parallel)…")
            caption = raceInstagramCaption(url, shortcode)
            if (isGoodCaption(caption)) captionCache[key] = caption
        }

        if (thumbnail.isNullOrBlank() && shortcode != null) {
            thumbnail = "https://www.instagram.com/p/$shortcode/media/?size=l"
        }

        // Letzter Fallback: oEmbed-Titel (manchmal Caption-Ausschnitt)
        if (!isGoodCaption(caption)) {
            val oEmbedTitle = oEmbed?.get("title")?.trim().orEmpty()
            if (isGoodCaption(oEmbedTitle)) caption = oEmbedTitle
        }

        if (!isGoodCaption(caption)) throw InstagramBlockedException(url)

        progress("Rezept extrahieren…")
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

    /**
     * Startet WebView + offizielles Embed + Mirror-Seiten parallel und nimmt die
     * erste brauchbare Caption. Gesamtdauer typisch unter ~18s.
     */
    private suspend fun raceInstagramCaption(url: String, shortcode: String?): String =
        coroutineScope {
            data class Cap(val text: String, val source: String)

            val desktopUa =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            val jobs = buildList {
                // 1) WebView mit Geräte-Cookies (beste Chance bei Login auf dem Gerät)
                add(async {
                    runCatching {
                        InstagramWebViewScraper.extractCaption(context, url)?.let { Cap(it, "webview") }
                    }.getOrNull()?.takeIf { isGoodCaption(it.text) }
                })
                if (shortcode != null) {
                    // 2) Offizielles IG-Embed (oft ohne Login für öffentliche Posts)
                    add(async {
                        runCatching {
                            fetchInstagramEmbedCaption(shortcode)?.let { Cap(it, "embed") }
                        }.getOrNull()?.takeIf { isGoodCaption(it.text) }
                    })
                    // 2b) WebView auf Embed-URL (weniger Login-Wall als /p/)
                    add(async {
                        runCatching {
                            val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
                            InstagramWebViewScraper.extractCaption(context, embedUrl)?.let { Cap(it, "webview-embed") }
                        }.getOrNull()?.takeIf { isGoodCaption(it.text) }
                    })
                    add(async {
                        runCatching {
                            val doc = jsoupGet("https://imginn.com/p/$shortcode/")
                            val t = doc.select(".desc, .photo-desc, [class*=desc], [class*=caption]").text()
                                .ifBlank { doc.select("meta[property=og:description]").attr("content") }
                            Cap(t, "imginn").takeIf { isGoodCaption(it.text) }
                        }.getOrNull()
                    })
                    add(async {
                        runCatching {
                            val doc = jsoupGet("https://www.picuki.com/media/$shortcode")
                            val t = doc.select(".photo-description, .description, [class*=caption], [class*=desc]").text()
                                .ifBlank { doc.select("meta[property=og:description]").attr("content") }
                                .ifBlank { doc.select("meta[name=description]").attr("content") }
                            Cap(t, "picuki").takeIf { isGoodCaption(it.text) }
                        }.getOrNull()
                    })
                    add(async {
                        runCatching {
                            val ezUrl = url.replace("www.instagram.com", "www.instagramez.com")
                                .replace("instagram.com", "instagramez.com")
                            val doc = jsoupGetWithUA(ezUrl, desktopUa)
                            val t = doc.select("meta[property=og:description]").attr("content")
                                .ifBlank { doc.select("meta[name=description]").attr("content") }
                            Cap(t, "instagramez").takeIf { isGoodCaption(it.text) }
                        }.getOrNull()
                    })
                    add(async {
                        runCatching {
                            val ddUrl = url.replace("www.instagram.com", "www.ddinstagram.com")
                                .replace("instagram.com", "ddinstagram.com")
                            val doc = jsoupGetWithUA(ddUrl, desktopUa)
                            val t = doc.select("meta[property=og:description]").attr("content")
                                .ifBlank { doc.select("meta[name=description]").attr("content") }
                                .ifBlank { doc.select("p, .caption, [class*=caption]").text() }
                            Cap(t, "ddinstagram").takeIf { isGoodCaption(it.text) }
                        }.getOrNull()
                    })
                    // 3) Worker-Mirror (oft noch erreichbar wenn Main-Domain down)
                    add(async {
                        runCatching {
                            val wUrl = "https://ddinstagram.com/p/$shortcode"
                            val doc = jsoupGetWithUA(wUrl, desktopUa)
                            val t = doc.select("meta[property=og:description]").attr("content")
                                .ifBlank { doc.select("meta[name=description]").attr("content") }
                            Cap(t, "ddinstagram-apex").takeIf { isGoodCaption(it.text) }
                        }.getOrNull()
                    })
                }
            }

            // Erste brauchbare Caption innerhalb 18s
            val winner = withTimeoutOrNull(18_000L) {
                val pending = jobs.toMutableList()
                while (pending.isNotEmpty()) {
                    val done = select {
                        pending.forEach { job ->
                            job.onAwait { result -> job to result }
                        }
                    }
                    pending.remove(done.first)
                    val cap = done.second
                    if (cap != null && isGoodCaption(cap.text)) return@withTimeoutOrNull cap.text
                }
                null
            }

            if (!winner.isNullOrBlank()) {
                jobs.forEach { it.cancel() }
                return@coroutineScope winner
            }

            val best = jobs.mapNotNull { d ->
                if (d.isCompleted) runCatching { d.getCompleted() }.getOrNull()?.text else null
            }.filter { it.isNotBlank() }.maxByOrNull { it.length }.orEmpty()
            jobs.forEach { it.cancel() }
            best
        }

    /**
     * Offizielle Embed-Seite: für öffentliche Posts oft ohne Login und mit Caption im HTML.
     */
    private fun fetchInstagramEmbedCaption(shortcode: String): String? {
        val urls = listOf(
            "https://www.instagram.com/p/$shortcode/embed/captioned/",
            "https://www.instagram.com/reel/$shortcode/embed/captioned/",
            "https://www.instagram.com/p/$shortcode/embed/"
        )
        for (embedUrl in urls) {
            val html = runCatching { fetchStringWithUA(
                embedUrl,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            ) }.getOrNull() ?: continue
            if (html.isBlank() || "login" in html.lowercase().take(2000) && "caption" !in html.lowercase()) continue
            val doc = Jsoup.parse(html, embedUrl)
            val candidates = listOf(
                doc.select(".Caption, .CaptionContent, [class*=Caption]").text(),
                doc.select("meta[property=og:description]").attr("content"),
                doc.select("meta[name=description]").attr("content"),
                // JSON im Embed-Script
                Regex(""""text"\s*:\s*"((?:[^"\\]|\\.){40,})"""").findAll(html)
                    .map { it.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"") }
                    .maxByOrNull { it.length }
                    .orEmpty()
            )
            val best = candidates.map { it.trim() }.filter { it.length >= 40 }.maxByOrNull { it.length }
            if (best != null && isGoodCaption(best)) return best
        }
        return null
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
        progress("Link auflösen…")
        val expandedUrl = runCatching {
            if ("vm.tiktok.com" in url || "vt.tiktok.com" in url) {
                val req = Request.Builder().url(url).head().build()
                client.newCall(req).execute().use { it.request.url.toString() }
            } else url
        }.getOrDefault(url)
        val key = cacheKey(expandedUrl)

        var caption: String? = captionCache[key]
        var thumbnail: String? = null
        var author: String? = null

        if (isGoodCaption(caption)) {
            progress("Aus Cache…")
        } else {
            progress("Seite laden (parallel)…")
            val raced = raceTikTokCaption(expandedUrl)
            caption = raced.first
            thumbnail = raced.second
            author = raced.third
            if (isGoodCaption(caption)) captionCache[key] = caption!!
        }

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

        progress("Rezept extrahieren…")
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

    /** Parallel: tikwm API, WebView, oEmbed, Jsoup — erste brauchbare Caption. */
    private suspend fun raceTikTokCaption(expandedUrl: String): Triple<String?, String?, String?> =
        coroutineScope {
            data class Pack(val caption: String?, val thumb: String?, val author: String?)

            val jobs = listOf(
                async {
                    runCatching {
                        val apiUrl = "https://www.tikwm.com/api/?url=${encode(expandedUrl)}&hd=1"
                        val raw = fetchString(apiUrl)
                        val title = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(raw)?.groupValues?.get(1)
                            ?.replace("\\n", "\n")?.replace("\\\"", "\"")
                        val cover = Regex(""""origin_cover"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(raw)?.groupValues?.get(1)
                            ?: Regex(""""cover"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(raw)?.groupValues?.get(1)
                        val auth = Regex(""""unique_id"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(raw)?.groupValues?.get(1)
                        val thumb = cover?.replace("\\u0026", "&")?.replace("\\/", "/")
                            ?.takeIf { "tiktokcdn.com" !in it }
                        Pack(title, thumb, auth).takeIf { isGoodCaption(it.caption) }
                    }.getOrNull()
                },
                async {
                    runCatching {
                        val r = TikTokWebViewScraper.extract(context, expandedUrl)
                        Pack(r.caption, null, r.author).takeIf { isGoodCaption(it.caption) }
                    }.getOrNull()
                },
                async {
                    runCatching {
                        val oEmbed = fetchOEmbed("https://www.tiktok.com/oembed?url=${encode(expandedUrl)}")
                        Pack(oEmbed["title"], oEmbed["thumbnail_url"], oEmbed["author_name"])
                            .takeIf { isGoodCaption(it.caption) || !it.caption.isNullOrBlank() }
                    }.getOrNull()
                },
                async {
                    runCatching {
                        val doc = jsoupGetWithUA(
                            expandedUrl,
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36"
                        )
                        val t = doc.select("meta[property=og:description]").attr("content")
                            .ifBlank { doc.select("meta[name=description]").attr("content") }
                        Pack(t, extractOgImage(doc), null).takeIf { isGoodCaption(it.caption) }
                    }.getOrNull()
                }
            )

            val winner = withTimeoutOrNull(14_000L) {
                val pending = jobs.toMutableList()
                while (pending.isNotEmpty()) {
                    val done = select {
                        pending.forEach { job -> job.onAwait { result -> job to result } }
                    }
                    pending.remove(done.first)
                    val p = done.second
                    if (p != null && isGoodCaption(p.caption)) return@withTimeoutOrNull p
                }
                null
            }

            if (winner != null) {
                jobs.forEach { it.cancel() }
                return@coroutineScope Triple(winner.caption, winner.thumb, winner.author)
            }

            val any = jobs.mapNotNull { d ->
                if (d.isCompleted) runCatching { d.getCompleted() }.getOrNull() else null
            }.maxByOrNull { it.caption?.length ?: 0 }
            jobs.forEach { it.cancel() }
            Triple(any?.caption, any?.thumb, any?.author)
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
