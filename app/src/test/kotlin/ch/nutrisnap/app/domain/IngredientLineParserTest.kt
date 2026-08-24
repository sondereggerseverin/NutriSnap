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

    @Test
    fun `weetbix without unit becomes Stuck`() {
        val p = parseIngredientLine("2 weetbix")
        assertEquals("2", p.amount)
        assertEquals("Stück", p.unit)
        assertTrue(p.name.contains("weetbix", ignoreCase = true))
    }

    @Test
    fun `biscoff biscuit without unit becomes Stuck`() {
        val p = parseIngredientLine("1 whole biscoff biscuit")
        assertEquals("1", p.amount)
        assertEquals("Stück", p.unit)
        assertTrue(p.name.contains("biscoff", ignoreCase = true) || p.name.contains("biscuit", ignoreCase = true))
        assertTrue(!p.name.contains("whole", ignoreCase = true))
    }

    @Test
    fun `heaped teaspoon of cream cheese parses as TL`() {
        val p = parseIngredientLine("1 heaped teaspoon of cream cheese")
        assertEquals("1", p.amount)
        assertEquals("TL", p.unit)
        assertTrue(p.name.contains("cream", ignoreCase = true) || p.name.contains("cheese", ignoreCase = true))
        assertTrue(!p.name.contains("heaped", ignoreCase = true))
        assertTrue(!p.name.lowercase().trim().startsWith("of "))
    }

    @Test
    fun `tablespoons of greek yogurt parses as EL`() {
        val p = parseIngredientLine("2 tablespoons of Greek Yogurt")
        assertEquals("2", p.amount)
        assertEquals("EL", p.unit)
        assertTrue(p.name.contains("Greek", ignoreCase = true) || p.name.contains("Yogurt", ignoreCase = true))
    }

    @Test
    fun `crushed biscoff biscuit becomes Stuck`() {
        val p = parseIngredientLine("1 crushed biscoff biscuit")
        assertEquals("1", p.amount)
        assertEquals("Stück", p.unit)
    }

    @Test
    fun `bare heaped teaspoon without number becomes 1 TL`() {
        val p = parseIngredientLine("Heaped teaspoon of melted biscoff")
        assertEquals("1", p.amount)
        assertEquals("TL", p.unit)
        assertTrue(p.name.contains("biscoff", ignoreCase = true))
    }
}
