package ch.nutrisnap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodPortionPresetsIngredientTest {

    @Test
    fun `Proteinpulver in Scoops zu je 30 g`() {
        val p = FoodPortionPresets.forIngredient("Schokoladen-Proteinpulver")
        assertEquals(listOf(30f, 60f, 90f), p.map { it.grams })
        assertEquals("1 Scoop", p.first().label)
    }

    @Test
    fun `Brot Scheiben und Oel Loeffel`() {
        assertEquals(listOf(40f, 80f, 120f), FoodPortionPresets.forIngredient("Vollkornbrot").map { it.grams })
        assertEquals(14f, FoodPortionPresets.forIngredient("Olivenöl").first { it.label == "1 EL" }.grams)
    }

    @Test
    fun `Ei nur als ganzes Wort`() {
        assertEquals("1 Ei", FoodPortionPresets.forIngredient("Eier").first().label)
        assertEquals("1 Ei", FoodPortionPresets.forIngredient("Ei, gekocht").first().label)
        assertTrue(FoodPortionPresets.forIngredient("Reis, gekocht").isEmpty())
        assertTrue(FoodPortionPresets.forIngredient("Weizenmehl").isEmpty())
    }

    @Test
    fun `Obst bekommt Klein Mittel Gross, Unbekanntes nichts`() {
        assertEquals(listOf("Klein", "Mittel", "Gross"), FoodPortionPresets.forIngredient("Apfel").map { it.label })
        assertTrue(FoodPortionPresets.forIngredient("Xylit").isEmpty())
    }
}
