package ch.nutrisnap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionSanityTest {

    @Test
    fun `Honig mit kJ als kcal wird umgerechnet`() {
        val fixed = NutritionSanity.fixKcal(1284f, 0.4f, 75f, 0f)
        assertNotNull(fixed)
        assertEquals(306.9f, fixed!!, 0.2f)
    }

    @Test
    fun `plausibler Wert bleibt unveraendert`() {
        assertEquals(250f, NutritionSanity.fixKcal(250f, 10f, 30f, 8f)!!, 0.0001f)
    }

    @Test
    fun `negativ und NaN werden verworfen`() {
        assertNull(NutritionSanity.fixKcal(-1f, null, null, null))
        assertNull(NutritionSanity.fixKcal(Float.NaN, null, null, null))
    }

    @Test
    fun `ueber 905 kcal ohne Makros wird als kJ interpretiert, wenn danach plausibel`() {
        assertEquals(286.8f, NutritionSanity.fixKcal(1200f, null, null, null)!!, 0.2f)
    }

    @Test
    fun `ueber 905 kcal bleibt unplausibel auch als kJ und wird verworfen`() {
        assertNull(NutritionSanity.fixKcal(5000f, null, null, null))
    }

    @Test
    fun `ueber 905 kcal bei Null-Makros wird verworfen`() {
        assertNull(NutritionSanity.fixKcal(1284f, 0f, 0f, 0f))
    }

    @Test
    fun `Wert im Bereich aber passend zu Makros nur als kJ wird umgerechnet`() {
        // Makros ~185 kcal, angegebene 800 kcal passen nicht, 800 kJ = 191 kcal schon
        assertEquals(191.2f, NutritionSanity.fixKcal(800f, 10f, 25f, 5f)!!, 0.2f)
    }

    @Test
    fun `Makro-Widerspruch wird erkannt`() {
        assertTrue(NutritionSanity.isMacroInconsistent(64f, 0f, 82f, 0f))
    }

    @Test
    fun `konsistente Makros und fehlende Makros sind kein Widerspruch`() {
        assertFalse(NutritionSanity.isMacroInconsistent(250f, 10f, 30f, 8f))
        assertFalse(NutritionSanity.isMacroInconsistent(500f, null, 30f, 8f))
    }

    @Test
    fun `fast kalorienfreie Lebensmittel sind kein Widerspruch`() {
        assertFalse(NutritionSanity.isMacroInconsistent(10f, 1f, 0.5f, 0f))
    }
}
