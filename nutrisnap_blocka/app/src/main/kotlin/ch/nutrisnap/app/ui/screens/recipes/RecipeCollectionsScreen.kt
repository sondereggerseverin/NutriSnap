package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.RecipeCollectionDao
import ch.nutrisnap.app.data.model.DietTag
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeCollection
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class RecipeCollectionsViewModel(
    private val dao: RecipeCollectionDao
) : ViewModel() {

    val collections: StateFlow<List<RecipeCollection>> =
        dao.getAllCollections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteRecipes: StateFlow<List<Recipe>> =
        dao.getFavoriteRecipes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createCollection(name: String, emoji: String) {
        viewModelScope.launch { dao.insertCollection(RecipeCollection(name = name, emoji = emoji)) }
    }

    fun deleteCollection(collection: RecipeCollection) {
        viewModelScope.launch { dao.deleteCollection(collection) }
    }

    fun toggleFavorite(recipe: Recipe) {
        viewModelScope.launch { dao.setFavorite(recipe.id, !recipe.isFavorite) }
    }

    fun assignToCollection(recipeId: Long, collectionId: Long?) {
        viewModelScope.launch { dao.assignToCollection(recipeId, collectionId) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeCollectionsScreen(
    viewModel: RecipeCollectionsViewModel,
    onOpenCollection: (RecipeCollection) -> Unit,
    onBack: () -> Unit
) {
    val collections by viewModel.collections.collectAsState()
    val favorites by viewModel.favoriteRecipes.collectAsState()
    var showNewCollectionDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newEmoji by remember { mutableStateOf("📁") }

    val emojis = listOf("📁", "🍕", "🥗", "🍰", "🥩", "🍜", "🥤", "🌮", "🍱", "⭐")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sammlungen") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Zurueck") } },
                actions = {
                    IconButton(onClick = { showNewCollectionDialog = true }) {
                        Icon(Icons.Default.Add, "Neue Sammlung")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // Favoriten
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { /* open favorites */ },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("❤️", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Favoriten", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${favorites.size} Rezepte", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }
            }

            // Eigene Sammlungen
            if (collections.isNotEmpty()) {
                item { Text("Meine Sammlungen", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
            }

            items(collections) { collection ->
                Card(modifier = Modifier.fillMaxWidth().clickable { onOpenCollection(collection) }) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(collection.emoji, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(12.dp))
                        Text(collection.name, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.deleteCollection(collection) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Loeschen",
                                tint = MaterialTheme.colorScheme.error)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }
            }

            if (collections.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📂", style = MaterialTheme.typography.displayMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("Noch keine Sammlungen", style = MaterialTheme.typography.bodyLarge)
                            Text("Tippe auf + um eine zu erstellen",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (showNewCollectionDialog) {
        AlertDialog(
            onDismissRequest = { showNewCollectionDialog = false },
            title = { Text("Neue Sammlung") },
            text = {
                Column {
                    OutlinedTextField(value = newName, onValueChange = { newName = it },
                        label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text("Emoji:", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(emojis) { emoji ->
                            FilterChip(
                                selected = newEmoji == emoji,
                                onClick = { newEmoji = emoji },
                                label = { Text(emoji) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newName.isNotBlank()) {
                        viewModel.createCollection(newName, newEmoji)
                        newName = ""; showNewCollectionDialog = false
                    }
                }) { Text("Erstellen") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showNewCollectionDialog = false }) { Text("Abbrechen") }
            }
        )
    }
}
