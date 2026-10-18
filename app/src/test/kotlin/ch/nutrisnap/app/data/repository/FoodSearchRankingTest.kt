package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodSearchRankingTest {

    private fun food(
        name: String, kcal: Float?, p: Float?, c: Float?, f: Float?,
        brand: String? = null, barcode: String? = null
    ) = FoodItem(
        name = name, brand = brand, barcode = barcode,
        calories = kcal, protein = p, carbs = c, fat = f,
        source = FoodSource.OPEN_FOOD_FACTS
    )

    private val kakaoResults = listOf(
        food("Milchgetränk mit fettarmer Kakaomasse", 27f, 1f, 5f, 3f),
        food("Nesquik Kakaopulver", 383f, 5f, 78f, 3f, brand = "Nestlé", barcode = "7613000000001"),
        food("Kakaopulver", 367f, 20f, 9f, 21f),
        food("Trink Kakao ohne Zucker", 64f, 4f, 82f, 2f),
        food("Kakao schwach entölt - Bio", 387f, 22f, 12f, 21f),
        food("Caotina Noir 500g Kakaohaltiges Getränkepulver", null, null, null, null)
    )

    @Test
    fun kakao_genericPowderComesFirst() {
        val cleaned = FoodSearchRepository.cleanSearchResults(kakaoResults)
        val sorted = cleaned.sortedWith(FoodSearchRepository.relevanceComparator("kakao"))
        assertEquals("Kakaopulver", sorted.first().name)
    }

    @Test
    fun cleanSearchResults_dropsEmptyAndInconsistentEntries() {
        val cleaned = FoodSearchRepository.cleanSearchResults(kakaoResults).map { it.name }
        assertFalse(cleaned.any { it.startsWith("Caotina") })
        assertFalse("Trink Kakao ohne Zucker" in cleaned)
        assertTrue("Kakaopulver" in cleaned)
    }

    @Test
    fun cleanSearchResults_keepsInconsistentWhenNoAlternatives() {
        // Süssstoff: 0 kcal, aber 100 g "Kohlenhydrate" (Polyole) – darf nicht verschwinden.
        val sweetener = listOf(food("Erythrit", 0f, 0f, 100f, 0f))
        assertEquals(1, FoodSearchRepository.cleanSearchResults(sweetener).size)
    }
}
