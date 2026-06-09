package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeScrapeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL

/**
 * Scrapes recipe data from a given URL.
 * Supports:
 *  - Instagram posts (extracts caption / og:description)
 *  - Generic recipe sites with schema.org/Recipe JSON-LD
 *  - Fallback: best-effort extraction from page text
 */
class RecipeScraper {

    suspend fun scrape(rawUrl: String): RecipeScrapeResult = withContext(Dispatchers.IO) {
        runCatching {
            val url = rawUrl.trim()
            val platform = detectPlatform(url)

            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                .followRedirects(true)
                .timeout(12_000)
                .get()

            val recipe = when (platform) {
                "instagram" -> scrapeInstagram(doc, url)
                else        -> scrapeGeneric(doc, url, platform)
            }

            RecipeScrapeResult(success = true, recipe = recipe)
        }.getOrElse { e ->
            RecipeScrapeResult(success = false, error = e.message ?: "Unknown error")
        }
    }

    // ── Platform detection ───────────────────────────────────────────────────

    private fun detectPlatform(url: String): String = when {
        "instagram.com" in url -> "instagram"
        "tiktok.com"    in url -> "tiktok"
        "youtube.com"   in url -> "youtube"
        "youtu.be"      in url -> "youtube"
        else                   -> "web"
    }

    // ── Instagram ────────────────────────────────────────────────────────────
    // Instagram serves og:description with the post caption in meta tags.

    private fun scrapeInstagram(doc: Document, url: String): Recipe {
        val title   = doc.select("meta[property=og:title]").attr("content")
            .ifBlank { "Instagram Rezept" }
        val caption = doc.select("meta[property=og:description]").attr("content")
            .ifBlank { doc.select("meta[name=description]").attr("content") }
        val image   = doc.select("meta[property=og:image]").attr("content").ifBlank { null }

        val (ingredients, instructions) = parseCaption(caption)

        return Recipe(
            title        = cleanTitle(title),
            description  = caption.take(300),
            imageUrl     = image,
            sourceUrl    = url,
            platform     = "instagram",
            ingredients  = ingredients,
            instructions = instructions,
            tags         = "instagram"
        )
    }

    // ── Generic (schema.org JSON-LD) ─────────────────────────────────────────

    private fun scrapeGeneric(doc: Document, url: String, platform: String): Recipe {
        // 1. Try schema.org/Recipe JSON-LD
        val jsonLd = doc.select("script[type='application/ld+json']").firstOrNull {
            it.data().contains("\"@type\"") &&
                (it.data().contains("\"Recipe\"") || it.data().contains("recipe", ignoreCase = true))
        }?.data()

        if (jsonLd != null) {
            return parseJsonLdRecipe(jsonLd, url, platform, doc)
        }

        // 2. Fallback: og tags + heuristic text
        val title       = doc.select("meta[property=og:title]").attr("content")
            .ifBlank { doc.title() }
        val description = doc.select("meta[property=og:description]").attr("content")
            .ifBlank { doc.select("meta[name=description]").attr("content") }
        val image       = doc.select("meta[property=og:image]").attr("content").ifBlank { null }

        // Heuristic: look for <ul>/<ol> lists that might be ingredients / steps
        val lists = doc.select("ul li, ol li")
        val ingredients = lists.take(20).joinToString("\n") { "• ${it.text().trim()}" }

        return Recipe(
            title        = cleanTitle(title),
            description  = description.take(400),
            imageUrl     = image,
            sourceUrl    = url,
            platform     = platform,
            ingredients  = ingredients.ifBlank { "Zutaten nicht gefunden – bitte manuell ergänzen." },
            instructions = "Bitte Anleitung manuell ergänzen.",
            tags         = platform
        )
    }

    // ── JSON-LD parser (schema.org Recipe) ───────────────────────────────────

    private fun parseJsonLdRecipe(json: String, url: String, platform: String, doc: Document): Recipe {
        // Minimal hand-rolled parser to avoid a full JSON dependency in this layer.
        fun field(key: String): String? {
            val regex = Regex(""""$key"\s*:\s*"([^"]*?)"""")
            return regex.find(json)?.groupValues?.get(1)
        }
        fun listField(key: String): List<String> {
            val arrayRegex = Regex(""""$key"\s*:\s*\[([^\]]*?)]""", RegexOption.DOT_MATCHES_ALL)
            val arrayContent = arrayRegex.find(json)?.groupValues?.get(1) ?: return emptyList()
            return Regex(""""([^"]+)"""").findAll(arrayContent).map { it.groupValues[1] }.toList()
        }
        fun intField(key: String): Int? = field(key)?.filter { it.isDigit() }?.toIntOrNull()

        val title       = field("name") ?: doc.title()
        val description = field("description") ?: ""
        val image       = field("image")
            ?: doc.select("meta[property=og:image]").attr("content").ifBlank { null }
        val servings    = intField("recipeYield") ?: 1
        val prepTime    = field("prepTime")?.let { parseDuration(it) }
        val cookTime    = field("cookTime")?.let { parseDuration(it) }
        val totalMinutes= (prepTime ?: 0) + (cookTime ?: 0)

        val ingredients  = listField("recipeIngredient").joinToString("\n") { "• $it" }
        val instructions = listField("text")
            .ifEmpty { listField("recipeInstructions") }
            .mapIndexed { i, s -> "${i + 1}. $s" }
            .joinToString("\n")

        val keywords = field("keywords") ?: ""

        return Recipe(
            title        = cleanTitle(title),
            description  = description.take(400),
            imageUrl     = image,
            sourceUrl    = url,
            platform     = platform,
            ingredients  = ingredients.ifBlank { "Zutaten nicht gefunden." },
            instructions = instructions.ifBlank { "Anleitung nicht gefunden." },
            servings     = servings,
            prepTimeMinutes = totalMinutes.takeIf { it > 0 },
            tags         = keywords.take(200)
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Rough heuristic: split Instagram caption into ingredients / instructions. */
    private fun parseCaption(caption: String): Pair<String, String> {
        val lower = caption.lowercase()
        val splitKeywords = listOf("zubereitung", "anleitung", "so geht", "preparation", "method", "instructions", "steps")
        val splitIdx = splitKeywords.firstNotNullOfOrNull { kw ->
            val i = lower.indexOf(kw)
            if (i > 20) i else null
        }
        return if (splitIdx != null) {
            val ingredients  = caption.substring(0, splitIdx).trim()
            val instructions = caption.substring(splitIdx).trim()
            ingredients to instructions
        } else {
            caption to ""
        }
    }

    /** ISO 8601 duration PT15M → 15, PT1H30M → 90 */
    private fun parseDuration(iso: String): Int {
        val hours   = Regex("""(\d+)H""").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)M""").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return hours * 60 + minutes
    }

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("""\s*[-|].*$"""), "").trim().ifBlank { "Rezept" }
}
