package ch.nutrisnap.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeCaptionJunkFilterTest {

    @Test
    fun rejectsIngredientsMakesHeader() {
        assertTrue(RecipeAiParser.isJunkIngredientLine("Ingredients – Makes 3"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("Ingredients - Makes 3"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("🥣 Ingredients – Makes 3"))
    }

    @Test
    fun rejectsMacroAndFooterLines() {
        assertTrue(RecipeAiParser.isJunkIngredientLine("ENTIRE RECIPE MACROS"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("6190 Calories"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("471g Protein"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("10 servings = 620 Cals, 47g Protein"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("Recipe by: @stealth_health_life"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("#stealthhealth #mealprep"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("Adjust serving size based on your needs:"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("Per Serving:"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("Approx Macros Per Serve"))
    }

    @Test
    fun keepsRealIngredients() {
        assertFalse(RecipeAiParser.isJunkIngredientLine("500g lean beef mince"))
        assertFalse(RecipeAiParser.isJunkIngredientLine("1361g (48 oz) chicken tenderloins"))
        assertFalse(RecipeAiParser.isJunkIngredientLine("2 packets taco seasoning"))
        assertFalse(RecipeAiParser.isJunkIngredientLine("320g Chobani Greek yoghurt"))
        assertFalse(RecipeAiParser.isJunkIngredientLine("4 large Carisma potatoes"))
    }

    @Test
    fun formatIngredientTextDropsJunkKeepsFood() {
        val raw = """
            Want meals like this?
            🥣 Ingredients – Makes 3
            4 large Carisma potatoes
            500g lean beef mince
            1 onion
            Salt + pepper
            Burger Sauce
            320g Chobani Greek yoghurt
            80g light mayo
            Garlic powder
            Method
            Chop the potatoes into fries
            Approx Macros Per Serve
            ~550 Calories
            #mealprep #highprotein
        """.trimIndent()
        val out = RecipeAiParser.formatIngredientText(raw)
        val lower = out.lowercase()
        assertFalse(lower.contains("makes 3"))
        assertFalse(lower.contains("550"))
        assertFalse(lower.contains("#mealprep"))
        assertFalse(lower.contains("chop the potatoes"))
        assertTrue(
            "expected potatoes/beef/yoghurt in:\n$out",
            out.contains("potatoes", ignoreCase = true) ||
                out.contains("Carisma", ignoreCase = true) ||
                out.contains("beef", ignoreCase = true) ||
                out.contains("500")
        )
        assertTrue(
            "expected yoghurt/mayo amount in:\n$out",
            out.contains("320") || out.contains("yoghurt", ignoreCase = true) ||
                out.contains("Chobani", ignoreCase = true) || out.contains("mayo", ignoreCase = true)
        )
    }
}

    @Test
    fun rejectsGermanMacroLines() {
        assertTrue(RecipeAiParser.isJunkIngredientLine("41 g Eiweiß |"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("23 g Kohlenhydrate |"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("6 g Fett Anzeige"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("6 g Fett"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("Nährwerte gesamt"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("320 kcal | 41 g Eiweiß | 23 g Kohlenhydrate | 6 g Fett"))
    }

    @Test
    fun rejectsDePromoCodeLines() {
        assertTrue(RecipeAiParser.isPromoIngredientNoise("Aktuell -25 % mit Code: VICCES"))
        assertTrue(RecipeAiParser.isPromoIngredientNoise("Höchster Rabatt mit Code: VICCES"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("→ Aktuell -25 % mit Code: VICCES"))
    }

    @Test
    fun rejectsDeInstructionLines() {
        assertTrue(
            RecipeAiParser.isJunkIngredientLine(
                "1 Skyr, Designer Whey, Puddingpulver, Wasser und Flavor Powder zu einer glatten Cheesecake-Masse verrühren."
            )
        )
        assertTrue(
            RecipeAiParser.isJunkIngredientLine(
                "3 Den Cheesecake 4× für jeweils 1 Minute 30 Sekunden bei 450 Watt in der Mikrowelle garen."
            )
        )
        assertTrue(RecipeAiParser.isJunkIngredientLine("Zubereitung"))
    }

    @Test
    fun hanutaCaption_keepsOnlyRealIngredients() {
        val raw = """
            HIGH PROTEIN HANUTA CHEESECAKE
            Cheesecake-Masse
            210 g Skyr Natur
            15 g ESN Designer Whey Vanilla Ice Cream
            Aktuell -25 % mit Code: VICCES
            20 g Vanillepuddingpulver
            90 ml Wasser
            1 Portion ESN Designer Flavor Powder White Chocolate
            Höchster Rabatt mit Code: VICCES
            Topping
            60 g High Protein Schokopudding
            1 Hanuta Mini
            Zubereitung
            1 Skyr, Designer Whey, Puddingpulver, Wasser und Flavor Powder zu einer glatten Cheesecake-Masse verrühren.
            2 Die Masse in eine mikrowellengeeignete Form geben.
            3 Den Cheesecake 4× für jeweils 1 Minute 30 Sekunden bei 450 Watt in der Mikrowelle garen.
            Nährwerte gesamt
            320 kcal | 41 g Eiweiß | 23 g Kohlenhydrate | 6 g Fett
            41 g Eiweiß |
            23 g Kohlenhydrate |
            6 g Fett Anzeige
            #esnvicces #highprotein
        """.trimIndent()
        val out = RecipeAiParser.formatIngredientText(raw)
        val lower = out.lowercase()
        // echte Zutaten
        assertTrue("expected Skyr in:\n$out", lower.contains("skyr"))
        assertTrue("expected 210 in:\n$out", out.contains("210"))
        assertTrue("expected Vanillepudding or 20 g in:\n$out", lower.contains("vanille") || out.contains("20"))
        assertTrue("expected Wasser/90 in:\n$out", lower.contains("wasser") || out.contains("90"))
        assertTrue("expected Hanuta or Schoko in:\n$out", lower.contains("hanuta") || lower.contains("schoko"))
        // junk raus
        assertFalse("promo leaked:\n$out", lower.contains("vicces") || lower.contains("rabatt") || lower.contains("aktuell"))
        assertFalse("macro leaked:\n$out", lower.contains("eiweiß") || lower.contains("eiweiss") || lower.contains("kohlenhydrate"))
        assertFalse("kcal leaked:\n$out", lower.contains("320") && lower.contains("kcal"))
        assertFalse("instruction leaked:\n$out", lower.contains("verrühren") || lower.contains("mikrowelle"))
        assertFalse("hashtag leaked:\n$out", lower.contains("#esn") || lower.contains("highprotein"))
        assertFalse("anzeige leaked:\n$out", lower.contains("anzeige"))
    }

    @Test
    fun promoTailStrippedFromIngredientLine() {
        val out = RecipeAiParser.formatIngredientText(
            "15 g ESN Designer Whey Vanilla Ice Cream → Aktuell -25 % mit Code: VICCES"
        )
        val lower = out.lowercase()
        assertTrue(lower.contains("esn") || lower.contains("whey") || out.contains("15"))
        assertFalse(lower.contains("vicces"))
        assertFalse(lower.contains("aktuell"))
        assertFalse(lower.contains("code"))
    }
