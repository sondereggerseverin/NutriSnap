package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.nutrisnap.app.ui.screens.recipegen.RecipeGeneratorScreen

private enum class RecipeTab { SAVED, AI }

// Fasst "Rezepte" und "KI-Koch" unter einem Bottom-Nav-Eintrag zusammen.
// Grund: 6 Bottom-Nav-Items sind zu viele (Material-Empfehlung: max. 5) und
// beide Screens drehen sich um dasselbe Thema (Rezepte).
@Composable
fun RecipesHubScreen(sharedUrl: String?, sharedBatchUrls: List<String> = emptyList()) {
    var tab by remember { mutableStateOf(RecipeTab.SAVED) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab.ordinal) {
            Tab(
                selected = tab == RecipeTab.SAVED,
                onClick = { tab = RecipeTab.SAVED },
                text = { Text("Rezepte") },
                icon = { Icon(Icons.Default.RestaurantMenu, null, Modifier.size(18.dp)) }
            )
            Tab(
                selected = tab == RecipeTab.AI,
                onClick = { tab = RecipeTab.AI },
                text = { Text("KI-Koch") },
                icon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) }
            )
        }
        when (tab) {
            RecipeTab.SAVED -> RecipesScreen(sharedUrl = sharedUrl, sharedBatchUrls = sharedBatchUrls)
            RecipeTab.AI    -> RecipeGeneratorScreen()
        }
    }
}
