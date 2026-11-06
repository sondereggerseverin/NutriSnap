package ch.nutrisnap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodSearchTextTest {

    @Test
    fun foldEntferntUmlaute() {
        assertEquals("grosse apfel", FoodSearchText.fold("Größe Äpfel"))
        assertEquals("ruebli", FoodSearchText.fold("Rüebli"))
    }

    @Test
    fun matchQuery_praefixUndAscii() {
        assertEquals("ruebli*", FoodSearchText.matchQueries("Rüebli").first())
        assertEquals("ruebli*", FoodSearchText.matchQueries("ruebli").first())
        assertEquals("apfel* bio*", FoodSearchText.matchQueries("Äpfel Bio").first())
    }

    @Test
    fun matchQuery_digraphVarianteFuerAepfelUndMuesli() {
        assertEquals(listOf("aepfel*", "apfel*"), FoodSearchText.matchQueries("aepfel"))
        assertEquals(listOf("muesli*", "musli*"), FoodSearchText.matchQueries("Müesli"))
    }

    @Test
    fun matchQuery_sonderzeichenUndLeereEingabe() {
        assertTrue(FoodSearchText.matchQueries("  ").isEmpty())
        assertTrue(FoodSearchText.matchQueries("\"*()").isEmpty())
        assertEquals("ab* cd*", FoodSearchText.matchQueries("ab'(cd)").first())
    }

    @Test
    fun matchQuery_einzelbuchstabenWerdenIgnoriert() {
        assertEquals("brot*", FoodSearchText.matchQueries("a brot").first())
    }
}
