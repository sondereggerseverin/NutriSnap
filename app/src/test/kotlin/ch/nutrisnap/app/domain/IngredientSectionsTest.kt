package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.IngredientMatch
import ch.nutrisnap.app.domain.IngredientSections.EditSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientSectionsTest {

    private val text = """
        Sauce / Fleisch
        • 150 g Zwiebel
        • 1000 g Poulet Schenkelschnetzelfleisch
        Beilage
        • 600 g Erbsen
        • 600 g Jasminreis
        Sauce / Fleisch
        • 300 g Magerquark
    """.trimIndent()

    private fun m(raw: String, name: String, kcal: Float, g: Float, group: String? = null) =
        IngredientMatch(
            recipeId = 1, ingredientRaw = raw, ingredientName = name,
            amountGrams = g, matchedCalories = kcal, componentGroup = group
        )

    @Test
    fun `header rule`() {
        assertTrue(IngredientSections.isHeaderLine("Beilage"))
        assertTrue(IngredientSections.isHeaderLine("Sauce / Fleisch"))
        assertFalse(IngredientSections.isHeaderLine("• 600 g Reis"))
        assertFalse(IngredientSections.isHeaderLine("150 g Zwiebel"))
        assertFalse(IngredientSections.isHeaderLine("- Salz"))
    }

    @Test
    fun `duplicate headers are merged in first-seen order`() {
        val s = IngredientSections.parse(text)
        assertEquals(listOf("Sauce / Fleisch", "Beilage"), s.map { it.name })
        assertEquals(3, s[0].lines.size)
        assertEquals("300 g Magerquark", s[0].lines.last())
    }

    @Test
    fun `flat text becomes one default section and renders flat`() {
        val s = IngredientSections.parse("• 100 g Mehl\n• 2 Eier")
        assertEquals(listOf(IngredientSections.DEFAULT_SECTION), s.map { it.name })
        assertEquals("• 100 g Mehl\n• 2 Eier", IngredientSections.render(s))
    }

    @Test
    fun `empty text gives one empty default section`() {
        val s = IngredientSections.parse("")
        assertEquals(1, s.size)
        assertTrue(s[0].lines.isEmpty())
    }

    @Test
    fun `render parse roundtrip is stable`() {
        val s = IngredientSections.parse(text)
        val again = IngredientSections.parse(IngredientSections.render(s))
        assertEquals(s, again)
    }

    @Test
    fun `resolve assigns by exact line and sums per section`() {
        val matches = listOf(
            m("150 g Zwiebel", "Zwiebel", 42f, 150f),
            m("1000 g Poulet Schenkelschnetzelfleisch", "Poulet", 1380f, 1000f),
            m("600 g Erbsen", "Erbsen", 534f, 600f),
            m("600 g Jasminreis", "Jasminreis", 2100f, 600f),
            m("300 g Magerquark", "Magerquark", 189f, 300f)
        )
        val r = IngredientSections.resolve(text, matches)
        assertEquals(2, r.size)
        assertEquals(42f + 1380f + 189f, r[0].kcal, 0.01f)
        assertEquals(534f + 2100f, r[1].kcal, 0.01f)
        assertEquals(1450f, r[0].rawG, 0.01f)
        assertEquals(0, r.sumOf { it.unmatchedCount })
    }

    @Test
    fun `stale componentGroup never overrides the text`() {
        // Alt-Fehler: Erbsen/Reis hingen im Dialog an «sauce», im Text stehen sie unter Beilage.
        val matches = listOf(
            m("600 g Erbsen", "Erbsen", 534f, 600f, group = "sauce"),
            m("600 g Jasminreis", "Jasminreis", 2100f, 600f, group = "sauce")
        )
        val r = IngredientSections.resolve(text, matches)
        val beilage = r.first { it.name == "Beilage" }
        assertEquals(2634f, beilage.kcal, 0.01f)
    }

    @Test
    fun `legacy flat text builds sections from componentGroup`() {
        val flat = "• 600 g Reis\n• 400 g Poulet"
        val matches = listOf(
            m("600 g Reis", "Reis", 2100f, 600f, group = "side"),
            m("400 g Poulet", "Poulet", 552f, 400f, group = "sauce")
        )
        val r = IngredientSections.resolve(flat, matches)
        assertEquals(listOf("Beilage", "Sauce / Fleisch"), r.map { it.name })
        assertEquals(2100f, r[0].kcal, 0.01f)
    }

    @Test
    fun `match without text line is kept as extra line`() {
        val matches = listOf(m("50 g Cashewkerne", "Cashewkerne", 300f, 50f))
        val r = IngredientSections.resolve(text, matches)
        assertEquals(300f, r.sumOf { it.kcal.toDouble() }.toFloat(), 0.01f)
        assertTrue(r[0].lines.any { it.matchIndex == 0 })
    }

    @Test
    fun `deleted matches are ignored`() {
        val matches = listOf(m("150 g Zwiebel", "Zwiebel", 42f, 150f).copy(isDeleted = true))
        val r = IngredientSections.resolve(text, matches)
        assertEquals(0f, r.sumOf { it.kcal.toDouble() }.toFloat(), 0.01f)
    }

    @Test
    fun `identical lines get separate matches`() {
        val t = "Gericht A\n• 600 g Hähnchen\nGericht B\n• 600 g Hähnchen"
        val matches = listOf(
            m("600 g Hähnchen", "Hähnchen", 660f, 600f),
            m("600 g Hähnchen", "Hähnchen", 660f, 600f)
        )
        val r = IngredientSections.resolve(t, matches)
        assertEquals(660f, r[0].kcal, 0.01f)
        assertEquals(660f, r[1].kcal, 0.01f)
    }

    @Test
    fun `groupsByMatchIndex is null for a single section`() {
        val r = IngredientSections.resolve("• 100 g Mehl", listOf(m("100 g Mehl", "Mehl", 364f, 100f)))
        assertNull(IngredientSections.groupsByMatchIndex(r)[0])
        val r2 = IngredientSections.resolve(text, listOf(m("600 g Erbsen", "Erbsen", 1f, 600f)))
        assertEquals("Beilage", IngredientSections.groupsByMatchIndex(r2)[0])
    }

    @Test
    fun `move line between sections`() {
        val s = IngredientSections.parse(text)
        val moved = IngredientSections.moveLine(s, 0, 2, 1)
        assertEquals(2, moved[0].lines.size)
        assertEquals("300 g Magerquark", moved[1].lines.last())
        assertEquals(s, IngredientSections.moveLine(s, 0, 0, 0))
    }

    @Test
    fun `add rename delete section`() {
        val s = IngredientSections.parse(text)
        val added = IngredientSections.addSection(s, " Topping: ")
        assertEquals("Topping", added.last().name)
        assertEquals(added, IngredientSections.addSection(added, "topping"))

        val renamed = IngredientSections.renameSection(added, 2, "Beilage")
        assertEquals(2, renamed.size) // zusammengeführt
        assertEquals(2, renamed[1].lines.size)

        val deleted = IngredientSections.deleteSection(s, 1)
        assertEquals(1, deleted.size)
        assertEquals(5, deleted[0].lines.size)
        assertEquals(deleted, IngredientSections.deleteSection(deleted, 0)) // letzter bleibt
    }

    @Test
    fun `cook factor history and suggestion`() {
        assertEquals(0.5f, IngredientSections.factorOf(1000f, 500f)!!, 0.0001f)
        assertNull(IngredientSections.factorOf(0f, 500f))
        var h = emptyList<Float>()
        for (f in listOf(0.8f, 0.9f, 0.85f, 0.85f, 0.85f, 0.85f)) h = IngredientSections.addFactor(h, f)
        assertEquals(IngredientSections.MAX_FACTOR_HISTORY, h.size)
        assertEquals(2150, IngredientSections.suggestedCookedG(2500f, h)) // Ø der letzten 5 = 0.86
        assertNull(IngredientSections.suggestedCookedG(2500f, emptyList()))
        assertEquals(h, IngredientSections.parseFactorHistory(IngredientSections.encodeFactorHistory(h)))
        assertEquals(1800f, IngredientSections.netCookedG(2300f, 500f), 0.01f)
    }

    private val splitText = "Sauce / Fleisch\n• 150 g Zwiebel\nBeilage\n• 600 g Jasminreis"

    @Test
    fun `component per section carries nutrition from its own lines`() {
        val ms = listOf(m("150 g Zwiebel", "Zwiebel", 42f, 150f), m("600 g Jasminreis", "Jasminreis", 2100f, 600f))
        val sections = IngredientSections.resolve(splitText, ms)
        val c = IngredientSections.toComponent(7L, sections[1], ms, 1200f, listOf(2f), sortOrder = 1, id = 5L)
        assertEquals("Beilage", c.name)
        assertEquals(2100f, c.totalCalories, 0.01f)
        assertEquals(1200f, c.cookedWeightG, 0.01f)
        assertEquals(5L, c.id)
        assertEquals(listOf(2f), IngredientSections.parseFactorHistory(c.factorHistory))
    }

    @Test
    fun `refresh matches components by section name not by keywords`() {
        // „Reis“ steht bewusst in der Sauce: Name entscheidet, nicht das Schlüsselwort.
        val t = "Beilage\n• 150 g Zwiebel\nSauce / Fleisch\n• 600 g Jasminreis"
        val ms = listOf(m("150 g Zwiebel", "Zwiebel", 42f, 150f), m("600 g Jasminreis", "Jasminreis", 2100f, 600f))
        val old = listOf(
            ch.nutrisnap.app.data.model.RecipeComponent(1L, 1L, "Sauce / Fleisch", 500f, 1f),
            ch.nutrisnap.app.data.model.RecipeComponent(2L, 1L, "Beilage", 100f, 1f)
        )
        val fresh = IngredientSections.refreshComponents(t, old, ms)
        assertEquals(2100f, fresh[0].totalCalories, 0.01f)
        assertEquals(42f, fresh[1].totalCalories, 0.01f)
        assertEquals(500f, fresh[0].cookedWeightG, 0.01f)
    }

    @Test
    fun `find component ignores case and trailing colon`() {
        val comps = listOf(ch.nutrisnap.app.data.model.RecipeComponent(1L, 1L, "Beilage", 100f, 1f))
        assertEquals(1L, IngredientSections.findComponent(comps, "beilage:")?.id)
        assertNull(IngredientSections.findComponent(comps, "Sauce"))
    }
}
