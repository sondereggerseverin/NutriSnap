package ch.nutrisnap.app.ui.screens.diary

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.CustomFoodItem
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.ui.viewmodel.DiaryViewModel
import kotlinx.coroutines.flow.filterNotNull

/**
 * BottomSheet das die Makros eines verknuepften Rezepts oder
 * eigenen Lebensmittels anzeigt, wenn kein separater Detail-Screen
 * vorhanden ist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryLinkedDetailSheet(
    recipeId: Long? = null,
    customFoodId: Int? = null,
    onDismiss: () -> Unit,
    viewModel: DiaryViewModel = viewModel()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (recipeId != null) {
                val recipe by viewModel.getRecipeById(recipeId)
                    .collectAsState(initial = null)
                recipe?.let { RecipeDetailContent(it) }
                    ?: CircularProgressIndicator()
            } else if (customFoodId != null) {
                val food by viewModel.getCustomFoodById(customFoodId)
                    .collectAsState(initial = null)
                food?.let { CustomFoodDetailContent(it) }
                    ?: CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun RecipeDetailContent(recipe: Recipe) {
    Text(recipe.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    HorizontalDivider()
    MacroRow("Kalorien",      "${recipe.totalCalories?.toInt() ?: "–"} kcal")
    MacroRow("Protein",       "${recipe.proteinPerServing?.let { "%.1f g".format(it) } ?: "–"}")
    MacroRow("Kohlenhydrate", "${recipe.carbsPerServing?.let   { "%.1f g".format(it) } ?: "–"}")
    MacroRow("Fett",          "${recipe.fatPerServing?.let     { "%.1f g".format(it) } ?: "–"}")
    recipe.description.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CustomFoodDetailContent(food: CustomFoodItem) {
    Text(food.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    food.brand?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
    MacroRow("Kalorien (100g)",      "%.1f kcal".format(food.calories))
    MacroRow("Protein (100g)",       "%.1f g".format(food.protein))
    MacroRow("Kohlenhydrate (100g)", "%.1f g".format(food.carbs))
    MacroRow("Fett (100g)",          "%.1f g".format(food.fat))
    if (food.fiber > 0) MacroRow("Ballaststoffe (100g)", "%.1f g".format(food.fiber))
    if (food.sugar > 0) MacroRow("Zucker (100g)",        "%.1f g".format(food.sugar))
    if (food.salt  > 0) MacroRow("Salz (100g)",          "%.1f g".format(food.salt))
}

@Composable
private fun MacroRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
