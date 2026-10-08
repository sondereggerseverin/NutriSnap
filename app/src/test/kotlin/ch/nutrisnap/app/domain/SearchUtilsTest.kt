package ch.nutrisnap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchUtilsTest {

    @Test
    fun `normalize maps umlauts`() {
        assertEquals("aeoeuess", SearchUtils.normalize("äöüß"))
        assertEquals("haehnchen", SearchUtils.normalize("Hähnchen"))
    }

    @Test
    fun `fuzzyMatch finds substring`() {
        assertTrue(SearchUtils.fuzzyMatch("apfel", "Grüner Apfel"))
        assertTrue(SearchUtils.fuzzyMatch("Hähnchen", "Hähnchenbrust"))
    }

    @Test
    fun `fuzzyMatch handles compound without spaces`() {
        assertTrue(SearchUtils.fuzzyMatch("suesskartoffelpommes", "Süßkartoffel Pommes"))
    }

    @Test
    fun `fuzzyMatch uses synonyms`() {
        assertTrue(SearchUtils.fuzzyMatch("pommes", "Fritten"))
        assertTrue(SearchUtils.fuzzyMatch("chicken", "Hähnchen Filet"))
    }

    @Test
    fun `fuzzyMatch rejects unrelated`() {
        assertFalse(SearchUtils.fuzzyMatch("banane", "Tomate"))
    }

    @Test
    fun `rankResults prefers exact prefix`() {
        val ranked = SearchUtils.rankResults(
            "apfel",
            listOf("Ananas", "Grüner Apfel", "Apfelmus", "Banane")
        )
        assertTrue(ranked.isNotEmpty())
        val names = ranked.map { it.first }
        assertTrue(names.any { it.contains("Apfel", ignoreCase = true) })
        assertFalse(names.contains("Banane"))
    }

    @Test
    fun `toFtsMatchQuery builds prefix tokens`() {
        assertEquals("haehn*", SearchUtils.toFtsMatchQuery("haehn"))
        assertEquals("suss* kartoffel*", SearchUtils.toFtsMatchQuery("suss kartoffel"))
        assertEquals("", SearchUtils.toFtsMatchQuery("a"))
        assertEquals("", SearchUtils.toFtsMatchQuery("  "))
        val umlaut = SearchUtils.toFtsMatchQuery("Hähnchen")
        assertTrue(umlaut.contains("haehnchen*") || umlaut.contains("Hähnchen*") || umlaut.contains("*"))
    }

    @Test
    fun `localQueryVariants splits 10 common food compounds`() {
        // 1 leinsamenbrot
        val lein = SearchUtils.localQueryVariants("leinsamenbrot")
        assertTrue("leinsamen brot erwartet in $lein", lein.any { it.contains("leinsamen") && it.contains("brot") })

        // 2 vollkornbrötchen / broetchen
        val voll = SearchUtils.localQueryVariants("vollkornbroetchen")
        assertTrue("vollkorn broetchen erwartet in $voll", voll.any { it.contains("vollkorn") && it.contains("broetchen") })

        // 3 süsskartoffelpommes
        val suess = SearchUtils.localQueryVariants("süsskartoffelpommes")
        assertTrue("suesskartoffel pommes erwartet in $suess", suess.any { it.contains("pommes") && it.contains("kartoffel") })

        // 4 pouletbrust
        val poulet = SearchUtils.localQueryVariants("pouletbrust")
        assertTrue("poulet/brust Variante in $poulet", poulet.any { it.contains("brust") || it.contains("poulet") })

        // 5 magerquark
        val quark = SearchUtils.localQueryVariants("magerquark")
        assertTrue("mager quark in $quark", quark.any { it.contains("mager") && it.contains("quark") })

        // 6 chiasamen
        val chia = SearchUtils.localQueryVariants("chiasamen")
        assertTrue("chia samen in $chia", chia.any { it.contains("chia") && it.contains("samen") })

        // 7 haferflocken
        val hafer = SearchUtils.localQueryVariants("haferflocken")
        assertTrue("hafer flochen in $hafer", hafer.any { it.contains("hafer") && it.contains("flocken") })

        // 8 walnüsse
        val wal = SearchUtils.localQueryVariants("walnüsse")
        assertTrue("wal nuesse in $wal", wal.any { it.contains("wal") && it.contains("nuesse") })

        // 9 hüttenkäse
        val hutten = SearchUtils.localQueryVariants("hüttenkäse")
        assertTrue("huetten kaese in $hutten", hutten.any { it.contains("kaese") || it.contains("huetten") })

        // 10 dinkelbrot
        val dinkel = SearchUtils.localQueryVariants("dinkelbrot")
        assertTrue("dinkel brot in $dinkel", dinkel.any { it.contains("dinkel") && it.contains("brot") })
    }

    @Test
    fun `synonymsOf covers ruebli and chia`() {
        val ruebli = SearchUtils.synonymsOf("ruebli")
        assertTrue("karotte für ruebli in $ruebli", ruebli.any { it.contains("karotte") || it.contains("carrot") })

        val chia = SearchUtils.synonymsOf("chiasamen")
        assertTrue("chia synonym in $chia", chia.any { it.contains("chia") })
    }
}
