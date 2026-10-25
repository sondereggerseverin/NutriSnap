package ch.nutrisnap.app.data.repository

import java.text.Normalizer

/**
 * Schlüssel zum Vergleichen von Zutatennamen, damit Schreibvarianten desselben Lebensmittels
 * auf denselben Standard zeigen:
 *  «Flohsamenschalen (Psyllium)» = «Flohsamenschalen» = «flohsamenschalen, psyllium»
 *  «Geriebener Käse» = «Käse gerieben» = «Geraspelter Käse».
 * Ignoriert werden Klammern, Satzzeichen, Gross-/Kleinschreibung, Umlaute/Akzente,
 * Wortreihenfolge und reine Zubereitungs-/Füllwörter (roh, frisch, gerieben, grob …).
 * Bedeutungstragende Wörter (light, vegan, Fettstufen wie «7 %») bleiben erhalten.
 */
object IngredientKey {
    private val parens = Regex("""\([^()]*\)""")
    private val nonAlnum = Regex("""[^a-z0-9]+""")
    private val accents = Regex("""\p{M}+""")
    private val filler = Regex(
        """^(in|aus|von|roh\w*|bio|grob\w*|fein\w*|frisch\w*|zerbroesel\w*|gehackt\w*|""" +
            """gerieben\w*|geraspelt\w*|geschnitten\w*|gemahlen\w*|gekocht\w*)$"""
    )

    fun of(raw: String): String {
        var s = raw.lowercase()
        while (parens.containsMatchIn(s)) s = parens.replace(s, " ")
        s = s.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(accents, "")
        val tokens = nonAlnum.split(s).filter { it.isNotBlank() && !filler.matches(it) }
        return tokens.sorted().joinToString(" ")
    }
}
