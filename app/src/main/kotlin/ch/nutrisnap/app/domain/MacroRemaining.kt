package ch.nutrisnap.app.domain

/**
 * Offene Tagesmakros (Ziel − bereits getrackt).
 * Wird von RecipeCookSuggester, RecipeListFilter und Rezept-Sheets genutzt.
 */
data class MacroRemaining(
    val kcal: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float
) {
    val hasMeaningfulGap: Boolean
        get() = kcal >= 80f || protein >= 8f
}
