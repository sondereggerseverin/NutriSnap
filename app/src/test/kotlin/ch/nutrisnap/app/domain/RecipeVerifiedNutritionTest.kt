package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeVerifiedNutritionTest {

    @Test
    fun `applies macros and replaces old macro line in description`() {
        val recipe = Recipe(
            title = "Test",
            description = "Lecker\n\n📊 Pro Portion: 100 kcal · 10g Protein · 10g Kohlenhydrate · 5g Fett (verifiziert)",
            servings = 2
        )
        val updated = RecipeVerifiedNutrition.applyToRecipe(
            recipe = recipe,
            kcalPerServ = 250f,
            protPerServ = 30f,
            carbsPerServ = 20f,
            fatPerServ = 8f
        )
        assertEquals(500f, updated.totalCalories)
        assertEquals(30f, updated.proteinPerServing)
        assertTrue(updated.description.contains("250 kcal"))
        assertTrue(updated.description.contains("Lecker"))
        assertEquals(1, updated.description.lines().count { it.trim().startsWith("📊") })
    }
}
