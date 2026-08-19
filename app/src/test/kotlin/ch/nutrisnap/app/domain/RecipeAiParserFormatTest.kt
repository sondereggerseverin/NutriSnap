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

    @Test
    fun `isPromoIngredientNoise flags affiliate code lines`() {
        assertTrue(
            RecipeAiParser.isPromoIngredientNoise(
                "The ingredients with a * are from @prozis (code FITFOODIEJULES will give you a big discount + gifts!)"
            )
        )
        assertTrue(
            RecipeAiParser.isPromoIngredientNoise(
                "INGREDIENTS WITH A * ARE FROM (CODE FITFOODIEJULES WILL GIVE YOU A BIG DISCOUNT + GIFTS!)"
            )
        )
        assertFalse(
            RecipeAiParser.isPromoIngredientNoise("50g of oat flour*")
        )
    }

    @Test
    fun `fallbackParse keeps real ingredients despite promo ingredients sentence`() {
        val caption = """
            The rumours are true, you can eat Oreos every day and still reach your health goals
            I might be tooting my own horn, but I have made oats in just about every way possible
            These Oreo oats are SO scrummy, and definitely one to try

            ✨recipe✨
            50g of oat flour* (or oats blended to flour)
            30g of vegan vanilla or cookies and cream protein powder*
            5g of cocoa powder*
            1 Oreo (I used a sugar free one)
            1 small square of dark chocolate*
            170ml of milk or water
            1/2 tsp baking powder

            The ingredients with a * are from @prozis (code FITFOODIEJULES will give you a big discount + gifts!)

            Preheat oven to 180° (350F) mix all ingredients (except cocoa powder) to form a batter. Then crush half the Oreo and mix into the batter
            Split half the batter into a separate bowl, and mix in the cocoa powder so you have two separate batters.
            In an oven safe bowl/ramekin, pour the Oreo batter and chocolate batter into separate sides of the bowl so you get a chocolate side and an Oreo side.
            Place the dark chocolate in the centre, and the other half of the Oreo on top. Bake for 20-25 minutes and enjoy!
        """.trimIndent()

        val recipe = RecipeAiParser.fallbackParse(
            caption = caption,
            sourceUrl = "https://www.instagram.com/p/example/",
            platform = "instagram",
            imageUrl = null
        )

        val ingLower = recipe.ingredients.lowercase()
        assertTrue("oat flour must be kept", ingLower.contains("oat flour") || ingLower.contains("hafer"))
        assertTrue("cocoa must be kept", ingLower.contains("cocoa") || ingLower.contains("kakao"))
        assertTrue("milk must be kept", ingLower.contains("milk") || ingLower.contains("milch") || ingLower.contains("170"))
        assertFalse("promo code must not appear in ingredients", ingLower.contains("fitfoodiejules"))
        assertFalse("discount promo must not appear", ingLower.contains("discount"))
        assertFalse("preheat must not be in ingredients", ingLower.contains("preheat"))

        val instrLower = recipe.instructions.lowercase()
        assertTrue(
            "instructions should mention preheat or bake",
            instrLower.contains("preheat") || instrLower.contains("bake") || instrLower.contains("vorheizen")
        )
    }
}
