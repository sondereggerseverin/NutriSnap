package ch.nutrisnap.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeAiParserFormatTest {

    @Test
    fun `unescapeSocialText resolves tab and newline escapes`() {
        val raw = "Fuer das Haehnchen:\\n\\t•\\t650 g Haehnchen\\n\\t•\\t1 TL Salz"
        val out = RecipeAiParser.unescapeSocialText(raw)
        assertFalse(out.contains("\\t"))
        assertFalse(out.contains("\\n"))
        assertTrue(out.contains("650 g Haehnchen"))
        assertTrue(out.contains('\n') || out.lines().size >= 2)
    }

    @Test
    fun `formatIngredientText strips literal tab escapes and marketing line`() {
        // Simuliert Instagram-JSON mit \\t und Marketing-Intro
        val raw = """
            Gesund, proteinreich & super easy fuer 4 Portionen
            Fuer das Haehnchen:
            \t•\t650 g Haehnchen-Innenfilets
            \t•\t1 TL Salz
            \t•\t30 g Honig
            Fuer das Mac & Cheese:
            \t•\t300 ml fettarme Milch
            \t•\t250 g Nudeln
        """.trimIndent()

        val formatted = RecipeAiParser.formatIngredientText(raw)
        assertFalse("literal backslash-t must not appear", formatted.contains("\\t"))
        assertFalse("marketing intro must be filtered", formatted.contains("proteinreich"))
        assertTrue(formatted.contains("650 g Haehnchen") || formatted.contains("Haehnchen-Innenfilets"))
        assertTrue(formatted.contains("250 g Nudeln") || formatted.contains("Nudeln"))
        // Jede echte Zutat als Bullet
        assertTrue(formatted.lines().any { it.startsWith("•") && it.contains("650") })
    }

    @Test
    fun `isJunkIngredientLine flags marketing without quantity`() {
        assertTrue(
            RecipeAiParser.isJunkIngredientLine(
                "Gesund, proteinreich & super easy fuer 4 Portionen"
            )
        )
        assertFalse(
            RecipeAiParser.isJunkIngredientLine("650 g Haehnchen-Innenfilets")
        )
    }

    @Test
    fun `unescape is idempotent on clean text`() {
        val clean = "• 100 g Haferflocken\n• 1 TL Salz"
        assertEquals(clean, RecipeAiParser.unescapeSocialText(clean))
    }
}
