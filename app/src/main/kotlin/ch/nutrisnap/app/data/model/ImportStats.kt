package ch.nutrisnap.app.data.model

data class ImportStats(
    val importedFoods: Int = 0,
    val skippedFoods: Int = 0,
    val importedRecipes: Int = 0,
    val skippedRecipes: Int = 0,
    val importedDiaryEntries: Int = 0,
    val skippedDiaryEntries: Int = 0,
    val backfilledIngredients: Int = 0,
    val unmatchedIngredients: Int = 0
) {
    val totalImported get() = importedFoods + importedRecipes + importedDiaryEntries
}
