package ch.nutrisnap.app.domain

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
}
