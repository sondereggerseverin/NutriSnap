package ch.nutrisnap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeImageTextExtractorTest {

    @Test
    fun looksLikeRecipe_withQtyLines() {
        val text = """
            High Protein Pasta Salad
            200g Pasta
            150g Hähnchenbrust
            1 EL Olivenöl
            Salz
        """.trimIndent()
        assertTrue(RecipeImageTextExtractor.looksLikeRecipeText(text))
    }

    @Test
    fun looksLikeRecipe_withKeywordAndOneQty() {
        val text = """
            Zutaten für 2 Portionen
            300 g Reis
            Zwiebel
            Knoblauch
            Zubereitung folgt
        """.trimIndent()
        assertTrue(RecipeImageTextExtractor.looksLikeRecipeText(text))
    }

    @Test
    fun rejectsTooShort() {
        assertFalse(RecipeImageTextExtractor.looksLikeRecipeText("Hallo Welt"))
    }

    @Test
    fun rejectsPromoWithoutQty() {
        val text = """
            Save this recipe for later
            Link in bio
            Comment RECIPE for the full list
            Follow for more meal prep ideas
        """.trimIndent()
        assertFalse(RecipeImageTextExtractor.looksLikeRecipeText(text))
    }

    @Test
    fun ingredientQualityScore_prefersQtyLines() {
        val weak = "Salz\nPfeffer\nÖl"
        val strong = "200g Pasta\n150g Hähnchen\n1 EL Öl\n2 TL Salz"
        assertTrue(
            RecipeImageTextExtractor.ingredientQualityScore(strong) >
                RecipeImageTextExtractor.ingredientQualityScore(weak)
        )
        assertTrue(RecipeImageTextExtractor.ingredientQualityScore(strong) >= RecipeImageTextExtractor.STRONG_INGREDIENT_SCORE)
    }

    @Test
    fun mergeBest_picksStrongerIngredientsAndLongerInstructions() {
        val ocr = RecipeFromImageResult(
            title = "Pasta",
            ingredients = "200g Pasta\n150g Hähnchen\n1 EL Öl",
            instructions = "1. Kochen"
        )
        val vision = RecipeFromImageResult(
            title = "High Protein Pasta",
            ingredients = "Pasta\nHähnchen",
            instructions = "1. Wasser kochen\n2. Pasta 8 Min\n3. Hähnchen anbraten"
        )
        val merged = RecipeImageTextExtractor.mergeBest(ocr, vision)
        assertTrue(merged.ingredients.contains("200g"))
        assertTrue(merged.instructions.contains("anbraten"))
        assertEquals("High Protein Pasta", merged.title)
    }
}
