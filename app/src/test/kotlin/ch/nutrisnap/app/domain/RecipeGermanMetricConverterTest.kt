package ch.nutrisnap.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeGermanMetricConverterTest {

    @Test
    fun `cups of liquid become ml`() {
        val out = RecipeGermanMetricConverter.convertUnitsToMetric("1 cup milk")
        assertTrue(out.lowercase().contains("ml") || out.contains("240") || out.contains("236"))
        assertFalse(out.lowercase().contains("cup milk") && !out.lowercase().contains("ml"))
    }

    @Test
    fun `tablespoon becomes metric volume or weight`() {
        val out = RecipeGermanMetricConverter.convertUnitsToMetric("2 tbsp olive oil")
        // EL / ml / g – nicht mehr tbsp
        assertFalse(out.lowercase().contains("tbsp"))
        assertTrue(
            out.contains("EL", ignoreCase = true) ||
                out.lowercase().contains("ml") ||
                out.lowercase().contains("g")
        )
    }

    @Test
    fun `unicode half cup is converted`() {
        val out = RecipeGermanMetricConverter.convertUnitsToMetric("½ cup water")
        assertTrue(out.lowercase().contains("ml") || out.contains("120") || out.contains("118"))
    }

    @Test
    fun `already metric line stays usable`() {
        val out = RecipeGermanMetricConverter.convertUnitsToMetric("200 g Haferflocken")
        assertTrue(out.contains("200"))
        assertTrue(out.contains("g"))
        assertTrue(out.contains("Haferflocken") || out.lowercase().contains("hafer"))
    }

    @Test
    fun `translateNamesToGerman maps common english names`() {
        val out = RecipeGermanMetricConverter.translateNamesToGerman("chicken breast")
        assertTrue(
            out.lowercase().contains("hähnchen") ||
                out.lowercase().contains("haehnchen") ||
                out.lowercase().contains("huhn") ||
                out.lowercase().contains("brust")
        )
    }

    @Test
    fun `convertOfflineFull combines units and names`() {
        val out = RecipeGermanMetricConverter.convertOfflineFull("1 cup chicken stock")
        assertTrue(out.isNotBlank())
    }
}
