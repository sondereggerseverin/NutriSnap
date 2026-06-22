package ch.nutrisnap.app.ui.navigation

/**
 * Navigation Patch — Recipe Ingredient Verification
 *
 * Füge folgende Route in deinen NavGraph ein (z.B. AppNavigation.kt):
 *
 * ```kotlin
 * composable(
 *     route = "recipe_verify/{recipeId}?title={title}&ingredients={ingredients}",
 *     arguments = listOf(
 *         navArgument("recipeId") { type = NavType.LongType },
 *         navArgument("title") { defaultValue = "" },
 *         navArgument("ingredients") { defaultValue = "" }
 *     )
 * ) { backStackEntry ->
 *     val recipeId = backStackEntry.arguments?.getLong("recipeId") ?: return@composable
 *     val title = backStackEntry.arguments?.getString("title") ?: ""
 *     val ingredients = backStackEntry.arguments?.getString("ingredients") ?: ""
 *     RecipeIngredientVerificationScreen(
 *         recipeId = recipeId,
 *         recipeTitle = title,
 *         ingredientsRaw = ingredients,
 *         onNavigateToBarcode = { matchId -> navController.navigate("barcode_scanner?matchId=$matchId") },
 *         onNavigateToSearch = { matchId, query -> navController.navigate("food_search?matchId=$matchId&query=$query") },
 *         onBack = { navController.popBackStack() }
 *     )
 * }
 * ```
 *
 * Aufruf aus RecipeDetailScreen — Button hinzufügen:
 *
 * ```kotlin
 * Button(onClick = {
 *     navController.navigate(
 *         "recipe_verify/${recipe.id}" +
 *         "?title=${Uri.encode(recipe.title)}" +
 *         "&ingredients=${Uri.encode(recipe.ingredients)}"
 *     )
 * }) {
 *     Icon(Icons.Default.QrCodeScanner, null)
 *     Spacer(Modifier.width(8.dp))
 *     Text("Zutaten verifizieren")
 * }
 * ```
 */
