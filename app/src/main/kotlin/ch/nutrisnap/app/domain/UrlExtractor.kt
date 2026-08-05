package ch.nutrisnap.app.domain

/**
 * Extrahiert eine oder mehrere URLs aus Freitext (z.B. eingefügte Notiz mit
 * mehreren Insta/TikTok-Links, oder ein per Android-Share-Sheet geteilter Text).
 * Wird sowohl vom Share-Intent-Handling (MainActivity) als auch vom
 * Batch-Import-Screen (BatchImportSheet) genutzt.
 */
object UrlExtractor {
    private val URL_REGEX = Regex("""https?://[^\s"'<>]+""")

    fun extractAll(text: String): List<String> =
        URL_REGEX.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '!', '"', '\'') }
            .map { normalizeInstagramUrl(it) }
            .distinct()
            .toList()

    /**
     * Instagram-Collection-Links aus DevTools sind oft `/p/CODE` obwohl es ein
     * Reel ist; Query-Parameter (`igsh`, `img_index`) stören oEmbed nicht, aber
     * der Medientyp (`p` vs `reel`) schon. Shortcode behalten, Tracking-Params
     * entfernen — der Scraper probiert danach beide Pfad-Varianten.
     */
    private fun normalizeInstagramUrl(url: String): String {
        if ("instagram.com" !in url.lowercase() && "instagr.am" !in url.lowercase()) return url
        val shortcode = Regex("/(?:p|reel|tv)/([A-Za-z0-9_-]+)/?")
            .find(url)?.groupValues?.get(1) ?: return url.substringBefore("?").trimEnd('/')
        val kind = when {
            "/reel/" in url.lowercase() -> "reel"
            "/tv/" in url.lowercase() -> "tv"
            else -> "p"
        }
        return "https://www.instagram.com/$kind/$shortcode/"
    }
}
