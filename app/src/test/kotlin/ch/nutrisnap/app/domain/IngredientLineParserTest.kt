package ch.nutrisnap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientLineParserTest {

    @Test
    fun `parse grams and name`() {
        val p = parseIngredientLine("650 g Haehnchen-Innenfilets")
        assertEquals("650", p.amount)
        assertEquals("g", p.unit)
        assertTrue(p.name.contains("Haehnchen") || p.name.contains("Innen"))
    }

    @Test
    fun `parse teaspoon`() {
        val p = parseIngredientLine("1 TL Salz")
        assertEquals("1", p.amount)
        assertTrue(p.unit.equals("TL", ignoreCase = true) || p.unit.isNotBlank())
        assertTrue(p.name.contains("Salz"))
    }

    @Test
    fun `join roundtrip keeps amount unit name`() {
        val joined = joinIngredientLine(ParsedIngredient("100", "g", "Haferflocken"))
        assertTrue(joined.contains("100"))
        assertTrue(joined.contains("g"))
        assertTrue(joined.contains("Haferflocken"))
    }

    @Test
    fun `normalize coverage ignores spaces and units formatting`() {
        val a = normalizeForCoverageMatch("200g Haferflocken")
        val b = normalizeForCoverageMatch("200 g Haferflocken")
        assertEquals(a, b)
    }
}
