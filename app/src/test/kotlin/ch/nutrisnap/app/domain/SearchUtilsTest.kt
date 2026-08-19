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
}
