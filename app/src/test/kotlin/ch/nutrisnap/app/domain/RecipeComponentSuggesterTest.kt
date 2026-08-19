package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.IngredientMatch
import ch.nutrisnap.app.data.model.MatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeComponentSuggesterTest {

    private fun match(raw: String, name: String, cal: Float = 100f) = IngredientMatch(
        recipeId = 1L,
        ingredientRaw = raw,
        ingredientName = name,
        amountGrams = 100f,
        matchedCalories = cal,
        matchedProtein = 10f,
        matchedCarbs = 10f,
        matchedFat = 5f,
        matchSource = MatchSource.DATABASE
    )

    @Test
    fun `rice is side`() {
        assertTrue(RecipeComponentSuggester.isSide("200 g Basmati Reis"))
    }

    @Test
    fun `chicken is sauce group`() {
        assertTrue(RecipeComponentSuggester.isSauce("Poulet braten"))
    }

    @Test
    fun `suggest groups side and sauce`() {
        val list = listOf(
            match("200 g Reis", "Reis", 200f),
            match("300 g Poulet", "Poulet", 300f)
        )
        val comps = RecipeComponentSuggester.suggestFromMatches(1L, list)
        assertEquals(2, comps.size)
        assertTrue(comps.any { it.name.contains("Beilage") })
        assertTrue(comps.any { it.name.contains("Sauce") || it.name.contains("Fleisch") })
    }
}
