package ch.nutrisnap.app.domain

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryPlausibilityCheckerTest {

    @Test
    fun `portion under limit is ok`() {
        assertNull(EntryPlausibilityChecker.checkPortion(200f))
        assertNull(EntryPlausibilityChecker.checkPortion(1500f))
    }

    @Test
    fun `portion above limit warns`() {
        val msg = EntryPlausibilityChecker.checkPortion(1501f)
        assertNotNull(msg)
        assertTrue(msg!!.contains("1501"))
    }

    @Test
    fun `manual entry with consistent macros is ok`() {
        // 20g P + 30g C + 10g F = 80+120+90 = 290 kcal
        assertNull(EntryPlausibilityChecker.checkManualEntry(290f, 20f, 30f, 10f))
    }

    @Test
    fun `manual entry with large macro deviation warns`() {
        val msg = EntryPlausibilityChecker.checkManualEntry(100f, 50f, 50f, 50f)
        assertNotNull(msg)
        assertTrue(msg!!.contains("Makros") || msg.contains("kcal"))
    }

    @Test
    fun `manual entry with extreme kcal warns`() {
        val msg = EntryPlausibilityChecker.checkManualEntry(3500f, 0f, 0f, 0f)
        assertNotNull(msg)
        assertTrue(msg!!.contains("3500"))
    }
}
