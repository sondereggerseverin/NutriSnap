package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.InventoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryMatcherTest {

    private fun inv(id: Long, name: String, qty: Float, unit: String) =
        InventoryItem(id = id, name = name, quantity = qty, unit = unit)

    @Test fun namenMitPluralUndZusatzwort() {
        assertTrue(InventoryMatcher.namesMatch("Zwiebel", "Rote Zwiebeln"))
        assertTrue(InventoryMatcher.namesMatch("Milch", "Milch 3.5%"))
    }

    @Test fun kaeseIstNichtFrischkaese() {
        assertFalse(InventoryMatcher.namesMatch("Käse", "Frischkäse"))
    }

    @Test fun gramVorratWirdAbgebucht() {
        val r = InventoryMatcher.match(listOf("200 g Mehl"), listOf(inv(1, "Mehl", 1000f, "g")))
        assertEquals(1, r.size)
        assertTrue(r[0].measurable)
        assertEquals(800f, r[0].newQty, 0.01f)
    }

    @Test fun kiloVorratWirdUmgerechnet() {
        val r = InventoryMatcher.match(listOf("500 g Mehl"), listOf(inv(1, "Mehl", 1f, "kg")))
        assertEquals(0.5f, r[0].newQty, 0.001f)
    }

    @Test fun nieUnterNull() {
        val r = InventoryMatcher.match(listOf("200 g Mehl"), listOf(inv(1, "Mehl", 100f, "g")))
        assertEquals(0f, r[0].newQty, 0.001f)
        assertTrue(r[0].fullyUsed)
    }

    @Test fun stueckBeiStueckAngabe() {
        val r = InventoryMatcher.match(listOf("2 Eier"), listOf(inv(1, "Eier", 6f, "Stk")))
        assertEquals(1, r.size)
        assertEquals(4f, r[0].newQty, 0.001f)
    }

    @Test fun packungenNurAlsVorschlagNichtSicher() {
        val r = InventoryMatcher.match(listOf("1 Dose Tomaten"), listOf(inv(1, "Tomaten", 3f, "Dose")))
        assertEquals(1, r.size)
        assertFalse(r[0].measurable)
        assertEquals(2f, r[0].newQty, 0.001f)
    }

    @Test fun mehrereZeilenSummierenSich() {
        val r = InventoryMatcher.match(
            listOf("200 g Mehl", "100 g Mehl"),
            listOf(inv(1, "Mehl", 1000f, "g"))
        )
        assertEquals(700f, r[0].newQty, 0.01f)
    }

    @Test fun keinTrefferKeineVorschlaege() {
        assertTrue(InventoryMatcher.match(listOf("200 g Mehl"), listOf(inv(1, "Reis", 500f, "g"))).isEmpty())
        assertTrue(InventoryMatcher.match(listOf("200 g Mehl"), emptyList()).isEmpty())
    }
}
