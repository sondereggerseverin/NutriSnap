package ch.nutrisnap.app.ui.screens.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeCaptionParserTest {

    @Test
    fun `splits zutaten and zubereitung`() {
        val caption = """
            Mega Bowl
            Zutaten:
            200 g Reis
            100 g Hähnchen
            Zubereitung:
            Reis kochen und anbraten.
        """.trimIndent()
        val (ing, instr) = RecipeCaptionParser.parseCaption(caption)
        assertTrue(ing.lowercase().contains("zutaten") || ing.contains("Reis"))
        assertTrue(instr.lowercase().contains("zubereitung") || instr.contains("kochen"))
    }

    @Test
    fun `without sections returns full caption as ingredients`() {
        val (ing, instr) = RecipeCaptionParser.parseCaption("Einfach lecker mit Tomaten")
        assertEquals("Einfach lecker mit Tomaten", ing)
        assertEquals("", instr)
    }
}
