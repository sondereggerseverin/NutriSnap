package ch.nutrisnap.app.ui.screens.recipes

/**
 * Teilt Social-/Caption-Text in Zutaten- und Anleitungsblock.
 * Pure Funktion – testbar ohne ViewModel.
 */
object RecipeCaptionParser {
    fun parseCaption(caption: String): Pair<String, String> {
        val lower = caption.lowercase()
        val instrKw = listOf("zubereitung","anleitung","so geht","preparation","method","instructions","steps","how to","zubereiten:")
        val ingrKw  = listOf("zutaten","zutaten:","ingredients","du brauchst","das brauchst","you need","für das rezept")
        val instrIdx = instrKw.firstNotNullOfOrNull { kw -> lower.indexOf(kw).takeIf { it > 5 } }
        val ingrIdx  = ingrKw.firstNotNullOfOrNull  { kw -> lower.indexOf(kw).takeIf { it >= 0 } }
        return when {
            ingrIdx != null && instrIdx != null && instrIdx > ingrIdx ->
                caption.substring(ingrIdx, instrIdx).trim() to caption.substring(instrIdx).trim()
            instrIdx != null -> caption.substring(0, instrIdx).trim() to caption.substring(instrIdx).trim()
            ingrIdx != null  -> caption.substring(ingrIdx).trim() to ""
            else             -> caption to ""
        }
    }
}
