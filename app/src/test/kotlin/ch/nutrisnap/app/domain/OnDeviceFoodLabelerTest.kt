package ch.nutrisnap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceFoodLabelerTest {

    @Test
    fun `maps apple to Apfel`() {
        val c = OnDeviceFoodLabeler.resolveLabel("Apple", 0.9f)
        assertNotNull(c)
        assertEquals("Apfel", c!!.name)
        assertTrue(c.estimatedGrams in 100f..200f)
        assertEquals("mittel", c.confidence)
    }

    @Test
    fun `unknown non-food label returns null`() {
        assertNull(OnDeviceFoodLabeler.resolveLabel("Person", 0.95f))
        assertNull(OnDeviceFoodLabeler.resolveLabel("Table", 0.8f))
    }

    @Test
    fun `generic food kept when no specific labels`() {
        val dish = OnDeviceFoodLabeler.buildDishFromLabels(
            listOf("Food" to 0.7f, "Table" to 0.9f)
        )
        assertEquals(1, dish.ingredients.size)
        assertEquals("Gericht", dish.ingredients.first().name)
    }

    @Test
    fun `specific labels drop generic food`() {
        val dish = OnDeviceFoodLabeler.buildDishFromLabels(
            listOf(
                "Food" to 0.95f,
                "Banana" to 0.8f,
                "Yogurt" to 0.7f
            )
        )
        assertEquals(2, dish.ingredients.size)
        assertFalse(dish.ingredients.any { it.name == "Gericht" })
        assertTrue(dish.ingredients.any { it.name == "Banane" })
        assertTrue(dish.ingredients.any { it.name == "Joghurt" })
    }

    @Test
    fun `substring match for french fries`() {
        val c = OnDeviceFoodLabeler.resolveLabel("French fries", 0.6f)
        assertNotNull(c)
        assertEquals("Pommes", c!!.name)
    }
}
