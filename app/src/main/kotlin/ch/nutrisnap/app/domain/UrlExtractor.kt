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
            .map { it.value.trimEnd('.', ',', ')', ']', '!', '?') }
            .distinct()
            .toList()
}
