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

    @Test
    fun rejectsGermanEngagementBait() {
        assertTrue(RecipeAiParser.isJunkIngredientLine(
            "Kommentiere „PIDE“ und ich schicke dir das Rezept zum speichern oder ausdrucken kostenlos zu."
        ))
        assertTrue(RecipeAiParser.isJunkIngredientLine(
            "40 g Kommentiere „PIDE“ und ich schicke dir das Rezept zum speichern oder ausdrucken kostenlos zu."
        ))
        assertTrue(RecipeAiParser.isJunkIngredientLine("Schreib PIDE in die Kommentare"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("Check deine DMs"))
        assertTrue(RecipeAiParser.isPromoIngredientNoise(
            "Kommentiere „PIDE“ und ich schicke dir das Rezept"
        ))
    }

    @Test
    fun germanPideStyleCaptionKeepsRealIngredientsDropsBait() {
        val raw = """
            Salami Käse Pide - 40g Protein
            ✅ 6 Portionen
            ✅ Pro Portion: 414 kcal, Fett 6,7g, KH 48,6g, P 40g
            💬 Kommentiere „PIDE“ und ich schicke dir das Rezept zum speichern oder ausdrucken kostenlos zu.
            📘 ZUTATEN Teig:
            🔹 380g Dinkelmehl Typ 630
            🔹 500g Magerquark
            🔹 1 Päckchen Backpulver
            📘 ZUTATEN Belag
            🔹 270g EatLean Käse gerieben
            🔹 90g Gratinkäse
            🔹 38g Geflügelsalami
            🧑‍🍳 ZUBEREITUNG:
            1. alle Zutaten für den Teig in einer Schüssel mit der Hand verkneten.
            2. in sechs gleich große Portionen aufteilen und länglich ausrollen
            3. Käse darauf verteilen und zu Schiffchen formen.
            4. Salami darauf geben
            5. im Backofen bei 200° für circa 15 Minuten backen.
            #highproteinrezepte #pide #proteinpide
        """.trimIndent()
        val out = RecipeAiParser.formatIngredientText(raw)
        val lower = out.lowercase()
        // Mindestens ein realer Zutatenblock muss übrig bleiben
        val hasDoughOrTopping =
            lower.contains("dinkelmehl") || out.contains("380") ||
                lower.contains("magerquark") || out.contains("500") ||
                lower.contains("backpulver") ||
                lower.contains("eatlean") || lower.contains("salami") ||
                out.contains("270") || out.contains("38")
        assertTrue("expected real ingredients in:\n$out", hasDoughOrTopping)
        assertFalse("bait leaked:\n$out", lower.contains("kommentiere") || lower.contains("schicke dir"))
        assertFalse("instruction leaked:\n$out", lower.contains("verkneten") || lower.contains("ausrollen"))
        assertFalse("hashtag leaked:\n$out", lower.contains("#highprotein") || lower.contains("#pide"))
    }


    @Test
    fun germanPideFallbackParseExtractsIngredientsAndSteps() {
        val caption = """
            Salami Käse Pide - 40g Protein
            ✅ 6 Portionen
            ✅ Pro Portion: 414 kcal, Fett 6,7g, KH 48,6g, P 40g
            💬 Kommentiere „PIDE“ und ich schicke dir das Rezept zum speichern oder ausdrucken kostenlos zu.
            📘 ZUTATEN Teig:
            🔹 380g Dinkelmehl Typ 630
            🔹 500g Magerquark
            🔹 1 Päckchen Backpulver
            📘 ZUTATEN Belag
            🔹 270g EatLean Käse gerieben
            🔹 90g Gratinkäse
            🔹 38g Geflügelsalami
            🧑‍🍳 ZUBEREITUNG:
            1. alle Zutaten für den Teig in einer Schüssel mit der Hand verkneten.
            2. in sechs gleich große Portionen aufteilen und länglich ausrollen
            3. Käse darauf verteilen und zu Schiffchen formen.
            4. Salami darauf geben
            5. im Backofen bei 200° für circa 15 Minuten backen.
            #highproteinrezepte #pide #proteinpide
        """.trimIndent()
        val recipe = RecipeAiParser.fallbackParse(caption, null, "instagram", null)
        val ing = recipe.ingredients.lowercase()
        assertTrue("expected Dinkelmehl/380 in:\n${recipe.ingredients}",
            ing.contains("dinkelmehl") || recipe.ingredients.contains("380"))
        assertTrue("expected Magerquark/500 in:\n${recipe.ingredients}",
            ing.contains("magerquark") || recipe.ingredients.contains("500"))
        assertTrue("expected Backpulver in:\n${recipe.ingredients}",
            ing.contains("backpulver"))
        assertFalse("bait in ingredients:\n${recipe.ingredients}",
            ing.contains("kommentiere") || ing.contains("schicke dir"))
        assertFalse("empty tip placeholder:\n${recipe.ingredients}",
            recipe.ingredients.startsWith("Tippe"))
        val instr = recipe.instructions.lowercase()
        assertFalse("bare ZUBEREITUNG header:\n${recipe.instructions}",
            instr.trim() == "zubereitung" || instr.trim() == "zubereitung:")
        assertFalse("orphan 'alle':\n${recipe.instructions}",
            instr.lines().any { it.trim() == "alle" })
        assertTrue("expected cooking steps:\n${recipe.instructions}",
            instr.contains("verkneten") || instr.contains("ausrollen") ||
                instr.contains("backen") || instr.contains("schiffchen"))
        assertFalse("quoted title: ${recipe.title}", recipe.title.startsWith("\""))
        assertTrue("title has Pide: ${recipe.title}",
            recipe.title.lowercase().contains("pide") || recipe.title.lowercase().contains("salami"))
        assertTrue("servings should be 6, was ${recipe.servings}", recipe.servings == 6)
    }


    @Test
    fun rejectsPromoTitles() {
        assertTrue(RecipeAiParser.isPromoTitle("Comment \"recipe\" & I'll DM you the full recipe!"))
        assertTrue(RecipeAiParser.isPromoTitle("Another Hailey recipe & it slaps!! Who knew she was a chef?"))
        assertTrue(RecipeAiParser.isPromoTitle("Kommentiere PIDE und ich schicke dir das Rezept"))
        assertFalse(RecipeAiParser.isPromoTitle("Hailey Bieber Protein Pizza"))
        assertFalse(RecipeAiParser.isPromoTitle("Hähnchen Alfredo Pasta"))
        assertFalse(RecipeAiParser.isPromoTitle("Salami Käse Pide"))
    }

    @Test
    fun dropsNumberedStepsFromIngredients() {
        val raw = """
            2 hartgekochte Eier
            150g Frischkäse
            180g Like Hähnchen
            1. Die Nudeln nach Packungsanleitung kochen
            2. Das Like Hähnchen nach Belieben würzen und anbraten
            3. Die gekochten Eier, Frischkäse, Parmesan mixen
        """.trimIndent()
        val out = RecipeAiParser.formatIngredientText(raw)
        val lower = out.lowercase()
        assertTrue(lower.contains("frischkäse") || out.contains("150"))
        assertTrue(lower.contains("hähnchen") || lower.contains("haehnchen") || out.contains("180"))
        assertFalse("step leaked:\n$out", lower.contains("packungsanleitung") || lower.contains("anbraten"))
    }


    @Test
    fun englishCupCaptionFallbackExtractsIngredients() {
        val caption = """
            Another Hailey recipe & it slaps!! Who knew she was a chef? This pizza is amazing!
            INGREDIENTS:
            The crust
            1/4 cup cottage cheese
            1/3 cup liquid egg whites
            1/3 cup all purpose flour
            1.5 tbsp coconut flour
            1 tsp baking powder
            Oregano
            The toppings
            1/3 cup marinara sauce
            1/2 cup mozzarella cheese
            1.) Preheat your oven to 350. Mix all crust ingredients in a bowl.
            2.) Bake for 10 minutes, peel the crust off the parchment.
            3.) Bake 10 more minutes until the cheese is bubbly.
            Save this one so you have it ready next time!
        """.trimIndent()
        val recipe = RecipeAiParser.fallbackParse(caption, null, "instagram", null)
        val ing = recipe.ingredients.lowercase()
        assertTrue("expected cottage/cup in:\n${recipe.ingredients}",
            ing.contains("cottage") || ing.contains("1/4") || ing.contains("cup"))
        assertTrue("expected flour in:\n${recipe.ingredients}",
            ing.contains("flour") || ing.contains("1/3"))
        assertFalse("promo title:\n${recipe.title}", RecipeAiParser.isPromoTitle(recipe.title))
        assertFalse("bait title:\n${recipe.title}",
            recipe.title.lowercase().contains("slaps") || recipe.title.lowercase().startsWith("another"))
        val instr = recipe.instructions.lowercase()
        assertTrue("expected bake/preheat in steps:\n${recipe.instructions}",
            instr.contains("bake") || instr.contains("preheat") || instr.contains("350"))
        assertFalse("steps in ingredients:\n${recipe.ingredients}",
            ing.contains("preheat") || ing.contains("bake for"))
    }

    @Test
    fun rejectsTikTokUiChrome() {
        assertTrue(RecipeAiParser.isSocialUiChromeLine("For You"))
        assertTrue(RecipeAiParser.isSocialUiChromeLine("Following"))
        assertTrue(RecipeAiParser.isSocialUiChromeLine("Community"))
        assertTrue(RecipeAiParser.isSocialUiChromeLine("230.7K"))
        assertTrue(RecipeAiParser.isSocialUiChromeLine("101.3K"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("For You"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("230.7K"))
        assertTrue(RecipeAiParser.isPromoIngredientNoise("Link in my profile"))
        assertFalse(RecipeAiParser.isSocialUiChromeLine("100 g Himbeeren"))
        assertFalse(RecipeAiParser.isJunkIngredientLine("100 g Himbeeren"))
    }

    @Test
    fun detectsSocialScreenshotOcr() {
        val ocr = """
            For You
            Following
            fitfoodieselma
            Healthy Overnight Oats
            100 g raspberries
            230.7K
            Link in my profile
        """.trimIndent()
        assertTrue(RecipeAiParser.looksLikeSocialScreenshotOcr(ocr))
        assertFalse(
            RecipeAiParser.looksLikeSocialScreenshotOcr(
                "100 g Haferflocken\n200 ml Milch\n1 EL Honig"
            )
        )
    }

    @Test
    fun rejectsInstructionFragmentsInIngredients() {
        assertTrue(RecipeAiParser.isJunkIngredientLine("1. Alle"))
        assertTrue(RecipeAiParser.isJunkIngredientLine("2. Alle"))
        assertTrue(
            RecipeAiParser.isJunkIngredientLine(
                "für den Raspberry Cookie Teig vermengen und am Ende die gehackten Himbeeren unterheben"
            )
        )
        assertFalse(RecipeAiParser.isJunkIngredientLine("Prise Salz"))
        assertFalse(RecipeAiParser.isJunkIngredientLine("Butter Vanille Aroma"))
        assertFalse(RecipeAiParser.isJunkIngredientLine("80g Erythrit Stevia Mix"))
    }

    @Test
    fun formatIngredientTextKeepsAromaAndSaltDropsSteps() {
        val raw = """
            Raspberry Cookie Teig:
            100g Becel Vital -30%
            50g Magerquark
            1 Ei
            Butter Vanille Aroma
            Prise Salz
            20g Gehackte gefriergetrocknete Himbeeren
            Cheesecake Teig:
            50g Frischkäse
            1. Alle Zutaten bis auf die Himbeeren für den Raspberry Cookie Teig vermengen
            2. Alle Zutaten für den Cheesecake Teig verrühren
        """.trimIndent()
        val out = RecipeAiParser.formatIngredientText(raw)
        val lower = out.lowercase()
        assertTrue("salt kept:\n$out", lower.contains("salz"))
        assertTrue("aroma kept:\n$out", lower.contains("aroma") || lower.contains("vanille"))
        assertFalse("no vermengen in ingredients:\n$out", lower.contains("vermengen"))
        assertFalse("no 1. alle:\n$out", Regex("""(?i)1\.?\s*alle""").containsMatchIn(out))
        assertTrue("cookie teig header:\n$out", lower.contains("cookie") || lower.contains("teig"))
    }


    @Test
    fun keepsHalfElAndNachWahl() {
        assertFalse(RecipeAiParser.isJunkIngredientLine("1/2 EL Chiasamen"))
        assertFalse(RecipeAiParser.isJunkIngredientLine("1-1,5 EL Agavendicksaft"))
        assertFalse(RecipeAiParser.isJunkIngredientLine("Milch nach Wahl"))
        assertFalse(RecipeAiParser.isJunkIngredientLine("Haselnussmus nach Bedarf"))
    }

    @Test
    fun cleansTagSeriesTitle() {
        val raw = "Tag 12/30: Rezepte unter 2€ - Bueno Overnight Oats"
        assertTrue(RecipeAiParser.isPromoTitle(raw))
        val cleaned = RecipeAiParser.cleanDishTitle(raw, "60g Hafermehl\nHaselnussmus")
        assertFalse("still promo: $cleaned", RecipeAiParser.isPromoTitle(cleaned))
        assertTrue("expected Bueno/Oats in $cleaned",
            cleaned.lowercase().contains("bueno") || cleaned.lowercase().contains("oats") ||
                cleaned.lowercase().contains("haselnuss"))
    }
}
