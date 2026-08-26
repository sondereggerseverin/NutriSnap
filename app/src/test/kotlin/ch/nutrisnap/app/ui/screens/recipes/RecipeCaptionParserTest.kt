package ch.nutrisnap.app.ui.screens.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // formatIngredientText normalisiert zu Bullet-Zeilen
        assertTrue(
            "expected tomaten in ingredients, got: $ing",
            ing.contains("Tomaten", ignoreCase = true) || ing.contains("lecker", ignoreCase = true)
        )
        assertEquals("", instr)
    }

    @Test
    fun `DEBUG bueno overnight oats mit topping block`() {
        val caption = """
            Bueno Overnight Oats 😍 der perfekte Frühstücks-Snack!
            .
            Zutaten:
            60g Hafermehl
            250ml Milch
            1 EL Chiasamen
            1 TL Agavendicksaft
            Topping: Haselnussmus, Schokolade (+ etwas Kokosöl)
            .
            Zubereitung:
            1. Hafermehl mit Milch und Chiasamen vermischen.
            2. Über Nacht im Kühlschrank ziehen lassen.
            3. Mit Topping servieren.
        """.trimIndent()
        val (ing, instr) = RecipeCaptionParser.parseCaption(caption)
        println("=== INGREDIENTS ===\n$ing")
        println("=== INSTRUCTIONS ===\n$instr")
        assertTrue("Chiasamen fehlt: $ing", ing.contains("Chiasamen"))
        assertTrue("Agavendicksaft fehlt: $ing", ing.contains("Agavendicksaft"))
        assertTrue("Haselnussmus fehlt: $ing", ing.contains("Haselnussmus"))
        assertTrue("Schokolade fehlt: $ing", ing.contains("Schokolade"))
        assertTrue("Kokosöl fehlt: $ing", ing.contains("Kokosöl") || ing.contains("Kokosoel"))
    }

    @Test
    fun `promo ingredients with star does not steal the ingredients block`() {
        val caption = """
            These Oreo oats are SO scrummy
            ✨recipe✨
            50g of oat flour*
            30g protein powder*
            The ingredients with a * are from @prozis (code FITFOODIEJULES will give you a big discount + gifts!)
            Preheat oven to 180 mix all
        """.trimIndent()
        val (ing, instr) = RecipeCaptionParser.parseCaption(caption)
        assertTrue("real ingredients kept", ing.contains("50g") || ing.contains("oat flour"))
        assertFalse(
            "promo must not start the block alone",
            ing.trim().lowercase().startsWith("the ingredients with")
        )
        assertTrue(
            "preheat should land in instructions or after ingredients",
            instr.lowercase().contains("preheat") || ing.lowercase().contains("preheat")
        )
    }
}
