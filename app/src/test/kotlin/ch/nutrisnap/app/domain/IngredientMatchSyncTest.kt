package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.IngredientMatch
import ch.nutrisnap.app.data.model.MatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientMatchSyncTest {

    private fun match(id: Long, raw: String, name: String) = IngredientMatch(
        id = id,
        recipeId = 1L,
        ingredientRaw = raw,
        ingredientName = name,
        amountGrams = 100f,
        matchSource = MatchSource.DATABASE
    )

    @Test
    fun `core strips quantity and bullets`() {
        assertEquals("haferflocken", IngredientMatchSync.core("• 200 g Haferflocken"))
        assertEquals("reis", IngredientMatchSync.core("- 100g Reis"))
    }

    @Test
    fun `soft-deletes missing ingredients`() {
        val existing = listOf(
            match(1, "200 g Reis", "Reis"),
            match(2, "100 g Poulet", "Poulet")
        )
        val ids = IngredientMatchSync.matchIdsToSoftDelete(existing, "200 g Reis\n50 g Zwiebel")
        assertEquals(listOf(2L), ids)
    }

    @Test
    fun `keeps matches still present`() {
        val existing = listOf(match(1, "200 g Reis", "Reis"))
        val ids = IngredientMatchSync.matchIdsToSoftDelete(existing, "Reis gekocht")
        assertTrue(ids.isEmpty())
    }
}
