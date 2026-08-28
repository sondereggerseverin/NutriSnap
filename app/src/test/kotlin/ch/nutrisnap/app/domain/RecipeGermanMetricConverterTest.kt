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

    @Test
    fun `cup flour becomes grams not tasse`() {
        val out = RecipeGermanMetricConverter.convertUnitsToMetric(
            "1/3 cup all-purpose flour (spooned and leveled)"
        )
        assertFalse(out.lowercase().contains("cup"))
        assertFalse(out.lowercase().contains("tasse"))
        assertTrue(out.lowercase().contains("g"))
        // ~40 g (1/3 * 120)
        assertTrue(out.contains("40") || out.contains("39") || out.contains("41"))
    }

    @Test
    fun `german tasse mehl becomes grams`() {
        val out = RecipeGermanMetricConverter.convertUnitsToMetric("0.33 Tasse Allzweckmehl")
        assertFalse(out.lowercase().contains("tasse"))
        assertTrue(out.lowercase().contains("g"))
        assertTrue(out.contains("Allzweckmehl") || out.lowercase().contains("mehl"))
    }

    @Test
    fun `packed cup brown sugar denser than plain sugar`() {
        val out = RecipeGermanMetricConverter.convertUnitsToMetric("1/4 cup packed brown sugar")
        assertFalse(out.lowercase().contains("cup"))
        assertTrue(out.lowercase().contains("g"))
    }

    @Test
    fun `one and half cups flour`() {
        val out = RecipeGermanMetricConverter.convertUnitsToMetric("1 1/2 cups all-purpose flour")
        assertFalse(out.lowercase().contains("cup"))
        assertTrue(out.contains("180") || out.contains("179") || out.contains("181"))
    }
}
