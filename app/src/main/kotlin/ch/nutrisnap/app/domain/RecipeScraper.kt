package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeScrapeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Scrapes recipe data from a given URL.
 *
 * Strategy (same as "All My Meals" app reverse-engineered approach):
 *  1. Instagram/TikTok  → Instagram oEmbed API (public, no auth) → caption parsing
 *  2. Recipe websites   → schema.org/Recipe JSON-LD (structured data)
 *  3. Fallback          → og:tags + heuristic list extraction
 *
 * Key insight from "All My Meals": caption MUST contain ingredients + steps.
 * The app shows: "Für ein gutes Ergebnis sollten Zutaten und Zubereitung
 * in der Post-Beschreibung stehen." — they parse the caption text, not some
 * private API. We do the same, plus oEmbed for the thumbnail.
 */
class RecipeScraper {

    suspend fun scrape(rawUrl: String): RecipeScrapeResult = withContext(Dispatchers.IO) {
        runCatching {
            val url = expandUrl(rawUrl.trim())
            val platform = detectPlatform(url)

            val recipe = when (platform) {
                "instagram" -> scrapeInstagram(url)
                "tiktok"    -> scrapeTikTok(url)
                else        -> scrapeGenericWeb(url, platform)
            }
            RecipeScrapeResult(success = true, recipe = recipe)
        }.getOrElse { e ->
            RecipeScrapeResult(success = false, error = e.message ?: "Unbekannter Fehler")
        }
    }

    // ── URL expansion (handles t.co, bit.ly, vm.tiktok.com etc.) ─────────────

    private fun expandUrl(url: String): String {
        return try {
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            conn.connect()
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (!location.isNullOrBlank() && location.startsWith("http")) location else url
        } catch (e: Exception) {
            url
        }
    }

    // ── Platform detection ────────────────────────────────────────────────────

    private fun detectPlatform(url: String): String = when {
        "instagram.com" in url -> "instagram"
        "instagr.am" in url    -> "instagram"
        "tiktok.com" in url    -> "tiktok"
        "vm.tiktok.com" in url -> "tiktok"
        "youtube.com" in url   -> "youtube"
        "youtu.be" in url      -> "youtube"
        else                   -> "web"
    }

    // ── Instagram ─────────────────────────────────────────────────────────────
    //
    // Step 1: Instagram oEmbed API → gets caption + thumbnail (no login needed)
    //   https://graph.facebook.com/v18.0/instagram_oembed?url=...&fields=thumbnail_url,author_name,title
    //   BUT: requires App Token. Use the public endpoint instead:
    //   https://www.instagram.com/p/{shortcode}/?__a=1  → blocked
    //
    // Real approach: Jsoup on the Instagram post page reads og:description
    // which contains the full caption text. This is what "All My Meals" does too.
    // oEmbed (no-auth public): https://api.instagram.com/oembed/?url=...
    //   → returns: thumbnail_url, author_name, html (but NO caption in public oEmbed)
    //
    // Best combo: oEmbed for thumbnail/author + og:description for caption

    private fun scrapeInstagram(url: String): Recipe {
        // 1. Try oEmbed for thumbnail + author
        val oEmbed = fetchInstagramOEmbed(url)

        // 2. Fetch the page for the caption (og:description has the full caption)
        val doc = jsoupGet(url)
        val caption = doc.select("meta[property=og:description]").attr("content")
            .ifBlank { doc.select("meta[name=description]").attr("content") }
            .ifBlank { oEmbed?.get("title") ?: "" }

        val title = buildInstagramTitle(caption, oEmbed?.get("author_name"))
        val image = oEmbed?.get("thumbnail_url")
            ?: doc.select("meta[property=og:image]").attr("content").ifBlank { null }

        val (ingredients, instructions) = parseCaptionIntoSections(caption)

        return Recipe(
            title        = title,
            description  = caption.take(500),
            imageUrl     = image,
            sourceUrl    = url,
            platform     = "instagram",
            ingredients  = ingredients,
            instructions = instructions,
            tags         = "instagram,reel"
        )
    }

    /** Public Instagram oEmbed — returns thumbnail_url, author_name, title */
    private fun fetchInstagramOEmbed(postUrl: String): Map<String, String>? {
        return try {
            val encoded = java.net.URLEncoder.encode(postUrl, "UTF-8")
            val raw = fetch("https://api.instagram.com/oembed/?url=$encoded&omitscript=true")
            // manual JSON field extraction
            buildMap {
                listOf("thumbnail_url", "author_name", "title").forEach { key ->
                    val regex = Regex(""""$key"\s*:\s*"([^"]*?)"""")
                    regex.find(raw)?.groupValues?.get(1)?.let { put(key, it) }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── TikTok ────────────────────────────────────────────────────────────────
    // Same strategy: oEmbed for thumbnail, og:description for caption

    private fun scrapeTikTok(url: String): Recipe {
        val oEmbed = fetchTikTokOEmbed(url)
        val doc    = try { jsoupGet(url) } catch (e: Exception) { null }

        val caption = doc?.select("meta[property=og:description]")?.attr("content")
            ?.ifBlank { null }
            ?: oEmbed?.get("title")
            ?: ""

        val image = oEmbed?.get("thumbnail_url")
            ?: doc?.select("meta[property=og:image]")?.attr("content")?.ifBlank { null }

        val author = oEmbed?.get("author_name") ?: "TikTok"
        val title  = buildInstagramTitle(caption, author)
        val (ingredients, instructions) = parseCaptionIntoSections(caption)

        return Recipe(
            title        = title,
            description  = caption.take(500),
            imageUrl     = image,
            sourceUrl    = url,
            platform     = "tiktok",
            ingredients  = ingredients,
            instructions = instructions,
            tags         = "tiktok,reel"
        )
    }

    private fun fetchTikTokOEmbed(postUrl: String): Map<String, String>? {
        return try {
            val encoded = java.net.URLEncoder.encode(postUrl, "UTF-8")
            val raw = fetch("https://www.tiktok.com/oembed?url=$encoded")
            buildMap {
                listOf("thumbnail_url", "author_name", "title").forEach { key ->
                    Regex(""""$key"\s*:\s*"([^"]*?)"""").find(raw)?.groupValues?.get(1)?.let { put(key, it) }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── Generic web (schema.org JSON-LD) ─────────────────────────────────────

    private fun scrapeGenericWeb(url: String, platform: String): Recipe {
        val doc = jsoupGet(url)

        // Try schema.org/Recipe JSON-LD first
        val jsonLd = doc.select("script[type='application/ld+json']").firstOrNull {
            it.data().contains("Recipe", ignoreCase = false) ||
                    it.data().contains("recipe", ignoreCase = false)
        }?.data()

        if (jsonLd != null) {
            return parseJsonLdRecipe(jsonLd, url, platform, doc)
        }

        // Fallback: og-tags + heuristic list
        val title       = doc.select("meta[property=og:title]").attr("content").ifBlank { doc.title() }
        val description = doc.select("meta[property=og:description]").attr("content")
            .ifBlank { doc.select("meta[name=description]").attr("content") }
        val image       = doc.select("meta[property=og:image]").attr("content").ifBlank { null }
        val lists       = doc.select("ul li, ol li").take(25).joinToString("\n") { "• ${it.text().trim()}" }

        return Recipe(
            title        = cleanTitle(title),
            description  = description.take(400),
            imageUrl     = image,
            sourceUrl    = url,
            platform     = platform,
            ingredients  = lists.ifBlank { "Zutaten nicht gefunden — bitte manuell ergänzen." },
            instructions = "Bitte Anleitung manuell ergänzen.",
            tags         = platform
        )
    }

    // ── schema.org JSON-LD parser ─────────────────────────────────────────────

    private fun parseJsonLdRecipe(json: String, url: String, platform: String, doc: Document): Recipe {
        fun field(key: String): String? =
            Regex(""""$key"\s*:\s*"([^"]*?)"""").find(json)?.groupValues?.get(1)

        fun listField(key: String): List<String> {
            val arr = Regex(""""$key"\s*:\s*\[([^\]]*?)]""", RegexOption.DOT_MATCHES_ALL)
                .find(json)?.groupValues?.get(1) ?: return emptyList()
            return Regex(""""([^"]+)"""").findAll(arr).map { it.groupValues[1] }.toList()
        }

        fun intField(key: String): Int? = field(key)?.filter { it.isDigit() }?.toIntOrNull()

        val title      = field("name") ?: doc.title()
        val desc       = field("description") ?: ""
        val image      = field("image") ?: doc.select("meta[property=og:image]").attr("content").ifBlank { null }
        val servings   = intField("recipeYield") ?: 1
        val prepMin    = field("prepTime")?.let { parseDuration(it) }
        val cookMin    = field("cookTime")?.let { parseDuration(it) }
        val totalMin   = (prepMin ?: 0) + (cookMin ?: 0)
        val ingredients  = listField("recipeIngredient").joinToString("\n") { "• $it" }
        val instructions = listField("text")
            .ifEmpty { listField("recipeInstructions") }
            .mapIndexed { i, s -> "${i + 1}. $s" }
            .joinToString("\n")
        val keywords   = field("keywords") ?: ""

        return Recipe(
            title           = cleanTitle(title),
            description     = desc.take(400),
            imageUrl        = image,
            sourceUrl       = url,
            platform        = platform,
            ingredients     = ingredients.ifBlank { "Zutaten nicht gefunden." },
            instructions    = instructions.ifBlank { "Anleitung nicht gefunden." },
            servings        = servings,
            prepTimeMinutes = totalMin.takeIf { it > 0 },
            tags            = keywords.take(200)
        )
    }

    // ── Caption parser ────────────────────────────────────────────────────────
    // Splits Instagram/TikTok caption into ingredients + instructions.
    // "All My Meals" does the same — they parse the caption text.

    private fun parseCaptionIntoSections(caption: String): Pair<String, String> {
        if (caption.isBlank()) return ("Kein Text im Post gefunden." to "")

        val lower = caption.lowercase()

        // Common section keywords in DE/EN
        val instrKeywords = listOf(
            "zubereitung", "anleitung", "so geht", "zubereiten", "zubereiten:",
            "schritt", "preparation", "method", "instructions", "steps", "how to",
            "directions", "zubereitung:", "so wird", "und so geht"
        )
        val ingrKeywords = listOf(
            "zutaten", "zutaten:", "ingredients", "you need", "du brauchst",
            "das brauchst", "für", "for the recipe"
        )

        // Find first instruction keyword position
        val instrIdx = instrKeywords.firstNotNullOfOrNull { kw ->
            val i = lower.indexOf(kw)
            if (i > 5) i else null
        }

        // Find ingredient keyword position
        val ingrIdx = ingrKeywords.firstNotNullOfOrNull { kw ->
            val i = lower.indexOf(kw)
            if (i >= 0) i else null
        }

        return when {
            // Both sections found
            ingrIdx != null && instrIdx != null && instrIdx > ingrIdx -> {
                val ingr  = caption.substring(ingrIdx, instrIdx).trim()
                val instr = caption.substring(instrIdx).trim()
                ingr to instr
            }
            // Only instructions found
            instrIdx != null -> {
                val ingr  = caption.substring(0, instrIdx).trim()
                val instr = caption.substring(instrIdx).trim()
                ingr to instr
            }
            // Only ingredients found
            ingrIdx != null -> {
                caption.substring(ingrIdx).trim() to ""
            }
            // No structure — return everything as ingredients (user can edit)
            else -> caption to ""
        }
    }

    private fun buildInstagramTitle(caption: String, author: String?): String {
        // First non-empty, non-emoji line of caption as title
        val firstLine = caption.lines()
            .map { it.trim() }
            .firstOrNull { it.length > 4 && !it.all { c -> !c.isLetter() } }
            ?: caption.take(50)
        val base = firstLine.take(60).trimEnd()
        return if (author != null) "$base (@$author)" else base
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseDuration(iso: String): Int {
        val h = Regex("""(\d+)H""").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("""(\d+)M""").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return h * 60 + m
    }

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("""\s*[-|].*$"""), "").trim().ifBlank { "Rezept" }

    private fun jsoupGet(url: String): Document =
        Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
            .followRedirects(true)
            .timeout(14_000)
            .get()

    private fun fetch(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpsURLConnection
        conn.setRequestProperty("User-Agent", "NutriSnap/1.0 (Android)")
        conn.connectTimeout = 8_000
        conn.readTimeout    = 10_000
        return conn.inputStream.bufferedReader().readText()
    }
}
