package ch.nutrisnap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyMicronutrientHelperTest {

    @Test
    fun `fehlender Nährstoff zaehlt als keine Daten statt 0 Prozent`() {
        val result = buildPriorityStatuses(listOf("iron"), emptyMap()).single()

        assertFalse(result.hasData)
        assertNull(result.pctOfGoal)
        assertEquals(PriorityNutrientLevel.UNKNOWN, result.level)
    }

    @Test
    fun `explizit gemessene Null bleibt kritisch`() {
        val result = buildPriorityStatuses(listOf("iron"), mapOf("iron" to 0f)).single()

        assertTrue(result.hasData)
        assertEquals(0, result.pctOfGoal)
        assertEquals(PriorityNutrientLevel.CRITICAL, result.level)
    }

    @Test
    fun `Eisen 7 mg ist 50 Prozent und niedrig`() {
        // Referenzwert Eisen 14 mg -> 0.007 g = 50 %
        val result = buildPriorityStatuses(listOf("iron"), mapOf("iron" to 0.007f)).single()

        assertTrue(result.hasData)
        assertEquals(50, result.pctOfGoal)
        assertEquals(PriorityNutrientLevel.LOW, result.level)
        assertEquals(7f, result.amountDisplay, 0.01f)   // Anzeige in mg
    }

    @Test
    fun `gemischte Liste markiert nur fehlende Nährstoffe als ohne Daten`() {
        val result = buildPriorityStatuses(
            listOf("fiber", "vitaminD", "magnesium"),
            mapOf("fiber" to 10f)
        ).associateBy { it.key }

        assertTrue(result.getValue("fiber").hasData)
        assertFalse(result.getValue("vitaminD").hasData)
        assertFalse(result.getValue("magnesium").hasData)
    }
}
