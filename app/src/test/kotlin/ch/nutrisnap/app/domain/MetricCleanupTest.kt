package ch.nutrisnap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricCleanupTest {

    @Test
    fun stripsDualOzParenthesis() {
        val out = RecipeGermanMetricConverter.cleanupMetricLine(
            "1361 g (48 oz) Hähnchen tenderloins"
        )
        assertFalse(out.contains("("))
        assertFalse(out.contains("oz"))
        assertTrue(out.contains("1361") || out.contains("Hähnchen"))
    }

    @Test
    fun stripsDoubleMetricInParens() {
        val out = RecipeGermanMetricConverter.cleanupMetricLine("1361 g (1360 g) Hähnchen")
        assertEquals("1361 g Hähnchen", out.replace(Regex("""\s+"""), " ").trim())
    }

    @Test
    fun stripsTbspDual() {
        val out = RecipeGermanMetricConverter.cleanupMetricLine("15 g (1 Tbsp) Öl")
        assertFalse(out.contains("("))
        assertFalse(out.contains("Tbsp"))
        assertTrue(out.contains("15") && out.contains("Öl"))
    }

    @Test
    fun mergesBrokenSplitLines() {
        val out = RecipeGermanMetricConverter.cleanupMetricText(
            "1361 g (\n1360 g) Hähnchen tenderloins\n15 g (\n15 ml ) Öl"
        )
        assertFalse(out.contains("("))
        assertFalse(out.contains(")"))
        val lines = out.lines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 2)
        assertTrue(lines.any { it.contains("Hähnchen") })
        assertTrue(lines.any { it.contains("Öl") })
    }

    @Test
    fun convertUnitsThenCleanupOz() {
        val out = RecipeGermanMetricConverter.convertUnitsToMetric(
            "48 oz chicken tenderloins"
        )
        assertFalse(out.lowercase().contains("oz"))
        assertTrue(out.contains("g"))
    }
}
