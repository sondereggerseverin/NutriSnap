package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.ui.screens.recipegen.RecipeGeneratorScreen
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing

private enum class RecipeTab(
    val label: String,
    val icon: ImageVector
) {
    SAVED("Rezepte", Icons.Default.RestaurantMenu),
    FREEZER("Gefrierer", Icons.Default.AcUnit),
    AI("KI-Koch", Icons.Default.AutoAwesome)
}

/** Fasst Rezepte, Gefrierer und KI-Koch unter einem Bottom-Nav-Eintrag zusammen. */
@Composable
fun RecipesHubScreen(
    sharedUrl: String?,
    sharedBatchUrls: List<String> = emptyList(),
    sharedRecipeJson: String? = null
) {
    var tab by remember { mutableStateOf(RecipeTab.SAVED) }

    Column(Modifier.fillMaxSize()) {
        RecipeHubSegmentedControl(
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.xs)
        )
        when (tab) {
            RecipeTab.SAVED -> RecipesScreen(
                sharedUrl = sharedUrl,
                sharedBatchUrls = sharedBatchUrls,
                sharedRecipeJson = sharedRecipeJson
            )
            RecipeTab.FREEZER -> FreezerScreen()
            RecipeTab.AI -> RecipeGeneratorScreen()
        }
    }
}

@Composable
private fun RecipeHubSegmentedControl(
    selected: RecipeTab,
    onSelect: (RecipeTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val trackShape = RoundedCornerShape(NutriRadius.lg)

    Row(
        modifier = modifier
            .height(44.dp)
            .clip(trackShape)
            .background(scheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        RecipeTab.entries.forEach { item ->
            val isSelected = item == selected
            val bg by animateColorAsState(
                targetValue = if (isSelected) scheme.primary else scheme.surfaceVariant.copy(alpha = 0f),
                animationSpec = tween(180),
                label = "tabBg"
            )
            val content by animateColorAsState(
                targetValue = if (isSelected) scheme.onPrimary else scheme.onSurfaceVariant,
                animationSpec = tween(180),
                label = "tabContent"
            )
            val interaction = remember { MutableInteractionSource() }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(NutriRadius.md))
                    .background(bg)
                    .clickable(
                        interactionSource = interaction,
                        indication = ripple(bounded = true),
                        onClick = { onSelect(item) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = content
                    )
                    Text(
                        text = item.label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = content,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
