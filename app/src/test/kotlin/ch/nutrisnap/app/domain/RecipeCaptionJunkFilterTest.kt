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
        assertTrue(out.contains("potatoes") || out.contains("Kartoffel") || out.contains("Carisma"))
        assertTrue(out.contains("500") || out.contains("beef") || out.contains("Rind"))
        assertTrue(out.contains("320") || out.contains("yoghurt") || out.contains("Joghurt") || out.contains("Chobani"))
    }
}
