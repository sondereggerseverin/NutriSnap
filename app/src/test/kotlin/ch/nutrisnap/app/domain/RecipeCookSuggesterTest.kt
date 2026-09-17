package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeCookSuggesterTest {

    private fun recipe(
        id: Long = 1L,
        title: String = "Test",
        timesCooked: Int = 0,
        lastCookedAt: Long? = null,
        isFavorite: Boolean = false,
        cookRating: Int = 0,
        prepTimeMinutes: Int? = null,
        tags: String = "",
        mealCategory: String = RecipeCategory.MAIN.name,
        totalCalories: Float? = 500f,
        proteinPerServing: Float? = 30f,
        servings: Int = 1
    ) = Recipe(
        id = id,
        title = title,
        timesCooked = timesCooked,
        lastCookedAt = lastCookedAt,
        isFavorite = isFavorite,
        cookRating = cookRating,
        prepTimeMinutes = prepTimeMinutes,
        tags = tags,
        mealCategory = mealCategory,
        totalCalories = totalCalories,
        proteinPerServing = proteinPerServing,
        servings = servings
    )

    @Test
    fun `never cooked ranks above recently cooked`() {
        val now = System.currentTimeMillis()
        val never = recipe(id = 1, title = "Neu", timesCooked = 0)
        val recent = recipe(
            id = 2,
            title = "Gestern",
            timesCooked = 3,
            lastCookedAt = now - 1 * 86_400_000L
        )
        val list = RecipeCookSuggester.suggest(
            recipes = listOf(never, recent),
            mode = CookSuggestMode.SMART,
            maxResults = 2,
            seed = 42L,
            hourOfDay = 19
        )
        assertEquals("Neu", list.first().recipe.title)
        assertTrue(list.first().reasons.any { it.contains("nie", ignoreCase = true) })
    }

    @Test
    fun `favorites mode only returns favorites`() {
        val fav = recipe(id = 1, title = "Fav", isFavorite = true)
        val other = recipe(id = 2, title = "Other", isFavorite = false)
        val list = RecipeCookSuggester.suggest(
            recipes = listOf(fav, other),
            mode = CookSuggestMode.FAVORITES,
            maxResults = 5,
            seed = 1L
        )
        assertEquals(1, list.size)
        assertEquals("Fav", list.first().recipe.title)
    }

    @Test
    fun `quick mode filters by prep time`() {
        val quick = recipe(id = 1, title = "Schnell", prepTimeMinutes = 20)
        val slow = recipe(id = 2, title = "Langsam", prepTimeMinutes = 90)
        val list = RecipeCookSuggester.suggest(
            recipes = listOf(quick, slow),
            mode = CookSuggestMode.QUICK,
            maxResults = 5,
            seed = 1L
        )
        assertEquals(1, list.size)
        assertEquals("Schnell", list.first().recipe.title)
    }

    @Test
    fun `high protein mode prefers protein rich`() {
        val high = recipe(
            id = 1,
            title = "High P",
            proteinPerServing = 40f,
            totalCalories = 400f,
            tags = "HIGH_PROTEIN"
        )
        val low = recipe(
            id = 2,
            title = "Low P",
            proteinPerServing = 5f,
            totalCalories = 500f
        )
        val list = RecipeCookSuggester.suggest(
            recipes = listOf(high, low),
            mode = CookSuggestMode.HIGH_PROTEIN,
            maxResults = 5,
            seed = 1L
        )
        assertTrue(list.any { it.recipe.title == "High P" })
        assertFalse(list.any { it.recipe.title == "Low P" })
    }

    @Test
    fun `preferred category filters`() {
        val breakfast = recipe(
            id = 1,
            title = "Oats",
            mealCategory = RecipeCategory.BREAKFAST.name
        )
        val main = recipe(
            id = 2,
            title = "Pasta",
            mealCategory = RecipeCategory.MAIN.name
        )
        val list = RecipeCookSuggester.suggest(
            recipes = listOf(breakfast, main),
            preferredCategory = RecipeCategory.BREAKFAST,
            maxResults = 5,
            seed = 1L
        )
        assertEquals(1, list.size)
        assertEquals("Oats", list.first().recipe.title)
    }

    @Test
    fun `macro fit boosts matching protein`() {
        val match = recipe(
            id = 1,
            title = "Protein Bowl",
            proteinPerServing = 35f,
            totalCalories = 450f
        )
        val mismatch = recipe(
            id = 2,
            title = "Huge Meal",
            proteinPerServing = 10f,
            totalCalories = 900f
        )
        val remaining = MacroRemaining(kcal = 500f, protein = 40f, carbs = 50f, fat = 20f)
        val list = RecipeCookSuggester.suggest(
            recipes = listOf(match, mismatch),
            remaining = remaining,
            mode = CookSuggestMode.SMART,
            maxResults = 2,
            seed = 99L,
            hourOfDay = 19
        )
        assertEquals("Protein Bowl", list.first().recipe.title)
    }

    @Test
    fun `recommendScore is deterministic`() {
        val r = recipe(id = 7, title = "X", isFavorite = true, timesCooked = 0)
        val a = RecipeCookSuggester.recommendScore(r, hourOfDay = 12)
        val b = RecipeCookSuggester.recommendScore(r, hourOfDay = 12)
        assertEquals(a, b, 0.001f)
    }

    @Test
    fun `empty list returns empty`() {
        assertTrue(
            RecipeCookSuggester.suggest(emptyList(), maxResults = 5).isEmpty()
        )
    }

    @Test
    fun `preferred categories for morning include breakfast`() {
        val cats = RecipeCookSuggester.preferredCategoriesForHour(8)
        assertTrue(cats.contains(RecipeCategory.BREAKFAST))
    }
}
