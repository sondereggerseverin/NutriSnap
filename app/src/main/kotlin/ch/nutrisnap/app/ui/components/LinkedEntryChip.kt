package ch.nutrisnap.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Kleiner Chip der in einer DiaryEntryCard erscheint wenn der Eintrag mit
 * einem Rezept oder einem eigenen Lebensmittel verknuepft ist.
 *
 * @param recipeId         ID des verknuepften Rezepts (oder null)
 * @param customFoodId     ID des verknuepften custom_food (oder null)
 * @param onOpenRecipe     Callback wenn der Nutzer auf "Rezept" tippt
 * @param onOpenCustomFood Callback wenn der Nutzer auf "Lebensmittel" tippt
 */
@Composable
fun LinkedEntryChip(
    recipeId: Long?,
    customFoodId: Int?,
    onOpenRecipe: ((Long) -> Unit)? = null,
    onOpenCustomFood: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (recipeId != null && onOpenRecipe != null) {
            AssistChip(
                onClick = { onOpenRecipe(recipeId) },
                label   = { Text("Rezept ansehen", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }
            )
        }
        if (customFoodId != null && onOpenCustomFood != null) {
            AssistChip(
                onClick = { onOpenCustomFood(customFoodId) },
                label   = { Text("Lebensmittel", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Fastfood,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }
            )
        }
    }
}
