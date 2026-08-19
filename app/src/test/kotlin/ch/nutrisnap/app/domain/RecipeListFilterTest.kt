package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.ui.screens.recipes.RecipeSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeListFilterTest {

    private fun recipe(title: String, platform: String? = "web", ingredients: String = "") =
        Recipe(
            id = title.hashCode().toLong(),
            title = title,
            ingredients = ingredients,
            platform = platform,
            savedAt = title.length.toLong()
        )

    @Test
    fun `platform filter uses web default`() {
        val list = listOf(recipe("A", null), recipe("B", "instagram"))
        val out = RecipeListFilter.filterAndSort(
            recipes = list,
            platformFilter = "web",
            categoryFilter = null,
            needles = emptyList(),
            sort = RecipeSort.NAME
        )
        assertEquals(1, out.size)
        assertEquals("A", out[0].title)
    }

    @Test
    fun `needles require all ingredients`() {
        val list = listOf(
            recipe("Bowl", ingredients = "Reis Hähnchen"),
            recipe("Salat", ingredients = "Salat Tomate")
        )
        val out = RecipeListFilter.filterAndSort(
            recipes = list,
            platformFilter = null,
            categoryFilter = null,
            needles = listOf("reis", "hähnchen"),
            sort = RecipeSort.NEWEST
        )
        assertEquals(1, out.size)
        assertTrue(out[0].title == "Bowl")
    }
}
