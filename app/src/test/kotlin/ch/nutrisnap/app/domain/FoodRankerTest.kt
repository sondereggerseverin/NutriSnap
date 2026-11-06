package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodRankerTest {

    private fun food(
        name: String, brand: String? = null, barcode: String? = null,
        kcal: Float? = 100f, popularity: Int = 0, timesUsed: Int = 0,
        source: FoodSource = FoodSource.OPEN_FOOD_FACTS
    ) = FoodItem(
        name = name, brand = brand, barcode = barcode,
        calories = kcal, protein = 5f, carbs = 10f, fat = 2f,
        source = source, popularity = popularity, timesUsed = timesUsed
    )

    private fun names(items: List<FoodItem>, q: String) = FoodRanker.rank(items, q).map { it.name }

    @Test
    fun exakterNameSchlaegtBeginntMit() {
        val r = names(listOf(food("Apfelringe getrocknet"), food("Apfel")), "apfel")
        assertEquals("Apfel", r.first())
    }

    @Test
    fun kakao_genericPowderBeatsDrinksAndBrands() {
        val items = listOf(
            food("Milchgetränk mit fettarmer Kakaomasse", kcal = 27f),
            food("Nesquik Kakaopulver", brand = "Nestlé", barcode = "7613000000001"),
            food("Kakaopulver"),
            food("Kakao schwach entölt - Bio")
        )
        assertEquals("Kakaopulver", names(items, "kakao").first())
    }

    @Test
    fun umlautUndAsciiSchreibweiseFindenDasselbe() {
        val items = listOf(food("Karottensalat"), food("Rüebli"))
        assertEquals("Rüebli", names(items, "ruebli").first())
        assertEquals("Rüebli", names(items, "Rüebli").first())
    }

    @Test
    fun beiGleichemTextEntscheidetPopularitaet() {
        val items = listOf(
            food("Joghurt Nature", brand = "NoName", barcode = "1", popularity = 10),
            food("Joghurt Nature", brand = "Migros", barcode = "2", popularity = 90)
        )
        assertEquals("Migros", FoodRanker.rank(items, "joghurt").first().brand)
    }

    @Test
    fun eigeneNutzungHebtTrefferAn() {
        val items = listOf(
            food("Haferflocken Bio", brand = "A", barcode = "1"),
            food("Haferflocken Bio", brand = "B", barcode = "2", timesUsed = 12)
        )
        assertEquals("B", FoodRanker.rank(items, "haferflocken").first().brand)
    }

    @Test
    fun kompositumZusammengeschriebenFindetGetrenntenNamen() {
        val items = listOf(food("Weissbrot"), food("Leinsamen Brot"))
        assertEquals("Leinsamen Brot", names(items, "leinsamenbrot").first())
    }

    @Test
    fun eintraegeOhneNaehrwerteUndKiSchaetzungenRutschenNachUnten() {
        val items = listOf(
            food("Quark", kcal = null).copy(protein = null, carbs = null, fat = null),
            food("Quark", brand = "KI-geschätzt"),
            food("Quark Magerstufe")
        )
        val r = FoodRanker.rank(items, "quark")
        assertEquals("Quark Magerstufe", r.first().name)
        assertTrue(r.last().calories == null)
    }

    @Test
    fun generischVorMarkeBeiGleichemText() {
        val items = listOf(
            food("Reis", brand = "Uncle Bens", barcode = "9"),
            food("Reis")
        )
        assertTrue(FoodRanker.rank(items, "reis").first().brand == null)
    }
}
