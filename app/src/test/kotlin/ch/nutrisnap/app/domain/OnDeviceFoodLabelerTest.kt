package ch.nutrisnap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceFoodLabelerTest {

    @Test
    fun mapsAppleToApfel() {
        val c = OnDeviceFoodLabeler.resolveLabel("Apple", 0.9f)
        assertNotNull(c)
        assertEquals("Apfel", c!!.name)
        assertTrue(c.estimatedGrams in 100f..200f)
        assertEquals("mittel", c.confidence)
    }

    @Test
    fun unknownNonFoodLabelReturnsNull() {
        assertNull(OnDeviceFoodLabeler.resolveLabel("Person", 0.95f))
        assertNull(OnDeviceFoodLabeler.resolveLabel("Table", 0.8f))
        assertNull(OnDeviceFoodLabeler.resolveLabel("Car", 0.9f))
    }

    @Test
    fun genericFoodKeptWhenNoSpecificLabels() {
        val dish = OnDeviceFoodLabeler.buildDishFromLabels(
            listOf("Food" to 0.7f, "Table" to 0.9f)
        )
        assertEquals(1, dish.ingredients.size)
        assertEquals("Gericht", dish.ingredients.first().name)
    }

    @Test
    fun specificLabelsDropGenericFood() {
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
    fun substringMatchForFrenchFries() {
        val c = OnDeviceFoodLabeler.resolveLabel("French fries", 0.6f)
        assertNotNull(c)
        assertEquals("Pommes", c!!.name)
    }

    @Test
    fun shortKeysStillExactMatch() {
        val egg = OnDeviceFoodLabeler.resolveLabel("egg", 0.8f)
        assertNotNull(egg)
        assertEquals("Ei", egg!!.name)
        val tea = OnDeviceFoodLabeler.resolveLabel("tea", 0.8f)
        assertNotNull(tea)
        assertEquals("Tee", tea!!.name)
    }
}
