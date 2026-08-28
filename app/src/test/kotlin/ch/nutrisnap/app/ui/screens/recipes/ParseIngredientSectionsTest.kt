package ch.nutrisnap.app.ui.screens.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseIngredientSectionsTest {

    @Test
    fun `bowl sections title case are recognized`() {
        val text = """
            Kartoffelpüree
            • 400 g Kartoffeln
            • 60 g Skyr
            • 40 ml Milch oder Kochwasser
            • Salz, Pfeffer, Muskat
            Hähnchen
            • 300 g Hähnchenbrust
            • 1 TL Paprikapulver
            Brokkoli/Bimi
            • 300 g Bimi oder Brokkoli
            Cremige würzige Protein-Sauce
            • 150 g Skyr
            • 50 g leichter Frischkäse
            Belag
            • 20 g Parmesan
            • frische Petersilie
        """.trimIndent()

        val sections = parseIngredientSections(text)
        assertEquals(
            listOf("Kartoffelpüree", "Hähnchen", "Brokkoli/Bimi", "Cremige würzige Protein-Sauce", "Belag"),
            sections.map { it.first }
        )
        assertEquals(4, sections[0].second.size)
        assertEquals(2, sections[1].second.size)
        assertEquals(1, sections[2].second.size)
        assertEquals(2, sections[3].second.size)
        assertEquals(2, sections[4].second.size)
    }

    @Test
    fun `all caps social headers still work`() {
        val text = """
            DOUGH
            • 200 g Mehl
            FILLING
            • 100 g Frischkäse
        """.trimIndent()
        val sections = parseIngredientSections(text)
        assertEquals(listOf("DOUGH", "FILLING"), sections.map { it.first })
    }

    @Test
    fun `seasoning lines are not headers`() {
        val text = """
            Sauce
            • 100 g Skyr
            Salz, Pfeffer
            • 1 TL Öl
        """.trimIndent()
        val sections = parseIngredientSections(text)
        // "Salz, Pfeffer" darf kein neuer Abschnitt sein
        assertTrue(sections.none { it.first.contains("Salz", ignoreCase = true) })
        assertTrue(sections.any { it.first.equals("Sauce", ignoreCase = true) })
    }
}
