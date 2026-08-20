package ch.nutrisnap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeFromImageNormalizeTest {

    private val vision = GroqVisionService()

    @Test
    fun `imperial ingredients become metric`() {
        val raw = RecipeFromImageResult(
            title = "Pancakes",
            ingredients = "1 cup milk\n2 tbsp sugar",
            instructions = "1. Mix\n2. Cook",
            servings = 0
        )
        val out = vision.normalizeExtractedRecipe(raw)
        assertEquals(1, out.servings)
        assertFalse(out.ingredients.lowercase().contains("cup milk") && !out.ingredients.lowercase().contains("ml"))
        assertTrue(
            out.ingredients.lowercase().contains("ml") ||
                out.ingredients.lowercase().contains("g") ||
                out.ingredients.contains("EL", ignoreCase = true)
        )
    }

    @Test
    fun `blank title falls back to first ingredient line`() {
        val raw = RecipeFromImageResult(
            title = "  ",
            ingredients = "200 g Haferflocken\n100 ml Milch",
            instructions = "1. Mischen"
        )
        val out = vision.normalizeExtractedRecipe(raw)
        assertTrue(out.title.startsWith("Rezept:"))
        assertTrue(out.title.contains("Haferflocken") || out.title.contains("200"))
    }

    @Test
    fun `nonsense nutrients are dropped`() {
        val raw = RecipeFromImageResult(
            title = "Test",
            ingredients = "1 Ei",
            caloriesPerServing = -5f,
            proteinPerServing = 50_000f,
            carbsPerServing = 12f
        )
        val out = vision.normalizeExtractedRecipe(raw)
        assertNull(out.caloriesPerServing)
        assertNull(out.proteinPerServing)
        assertEquals(12f, out.carbsPerServing)
    }

    @Test
    fun `excessive blank lines are collapsed`() {
        val raw = RecipeFromImageResult(
            title = "Soup",
            ingredients = "1 L Wasser\n\n\n\n2 g Salz",
            instructions = "1. Kochen\n\n\n2. Würzen"
        )
        val out = vision.normalizeExtractedRecipe(raw)
        assertFalse(out.ingredients.contains("\n\n\n"))
        assertFalse(out.instructions.contains("\n\n\n"))
    }
}
