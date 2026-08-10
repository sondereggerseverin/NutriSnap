package ch.nutrisnap.app.ui.screens.recipes

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.db.RecipeCollectionDao
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeCollection
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class RecipeCollectionsViewModel(app: Application) : AndroidViewModel(app) {
    private val dao: RecipeCollectionDao =
        NutriDatabase.getInstance(app).recipeCollectionDao()

    val collections: StateFlow<List<RecipeCollection>> =
        dao.getAllCollections()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteRecipes: StateFlow<List<Recipe>> =
        dao.getFavoriteRecipes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun recipesInCollection(collectionId: Long): Flow<List<Recipe>> =
        dao.getRecipesByCollection(collectionId)

    fun createCollection(name: String, emoji: String) {
        viewModelScope.launch {
            dao.insertCollection(RecipeCollection(name = name.trim(), emoji = emoji))
        }
    }

    fun deleteCollection(collection: RecipeCollection) {
        viewModelScope.launch {
            // Rezepte aus der Sammlung lösen, dann Sammlung löschen
            dao.getRecipesByCollection(collection.id).first().forEach { recipe ->
                dao.assignToCollection(recipe.id, null)
            }
            dao.deleteCollection(collection)
        }
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
    viewModel: RecipeCollectionsViewModel = viewModel(),
    onOpenRecipe: (Recipe) -> Unit = {},
    onBack: () -> Unit
) {
    val collections by viewModel.collections.collectAsState()
    val favorites by viewModel.favoriteRecipes.collectAsState()
    var showNewCollectionDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newEmoji by remember { mutableStateOf("📁") }
    var openCollection by remember { mutableStateOf<RecipeCollection?>(null) }
    var showFavorites by remember { mutableStateOf(false) }

    val emojis = listOf("📁", "🍕", "🥗", "🍰", "🥩", "🍜", "🥤", "🌮", "🍱", "⭐", "🎄", "💪")

    when {
        showFavorites -> {
            CollectionRecipesScreen(
                title = "❤️ Favoriten",
                recipesFlow = viewModel.favoriteRecipes,
                onOpenRecipe = onOpenRecipe,
                onBack = { showFavorites = false },
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                emptyHint = "Noch keine Favoriten – tippe auf das Herz bei einem Rezept."
            )
            return
        }
        openCollection != null -> {
            val col = openCollection!!
            CollectionRecipesScreen(
                title = "${col.emoji} ${col.name}",
                recipesFlow = viewModel.recipesInCollection(col.id),
                onOpenRecipe = onOpenRecipe,
                onBack = { openCollection = null },
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                emptyHint = "Diese Sammlung ist noch leer. Weise Rezepte über das Menü zu."
            )
            return
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sammlungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { showNewCollectionDialog = true }) {
                        Icon(Icons.Default.Add, "Neue Sammlung")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showFavorites = true },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("❤️", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Favoriten",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${favorites.size} Rezept${if (favorites.size == 1) "" else "e"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }
            }

            if (collections.isNotEmpty()) {
                item {
                    Text(
                        "Meine Sammlungen",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            items(collections, key = { it.id }) { collection ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openCollection = collection }
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(collection.emoji, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            collection.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.deleteCollection(collection) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Löschen",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }
            }

            if (collections.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📂", style = MaterialTheme.typography.displayMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("Noch keine Sammlungen", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Tippe auf + um eine zu erstellen",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
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
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            viewModel.createCollection(newName, newEmoji)
                            newName = ""
                            newEmoji = "📁"
                            showNewCollectionDialog = false
                        }
                    }
                ) { Text("Erstellen") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showNewCollectionDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionRecipesScreen(
    title: String,
    recipesFlow: Flow<List<Recipe>>,
    onOpenRecipe: (Recipe) -> Unit,
    onBack: () -> Unit,
    onToggleFavorite: (Recipe) -> Unit,
    emptyHint: String
) {
    val recipes by recipesFlow.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        if (recipes.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    emptyHint,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(recipes, key = { it.id }) { recipe ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRecipe(recipe) }
                    ) {
                        Row(
                            Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    recipe.displayTitle(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                val kcal = recipe.totalCalories
                                if (kcal != null) {
                                    Text(
                                        "${kcal.toInt()} kcal",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = { onToggleFavorite(recipe) }) {
                                Icon(
                                    if (recipe.isFavorite) Icons.Default.Favorite
                                    else Icons.Default.FavoriteBorder,
                                    contentDescription = "Favorit",
                                    tint = if (recipe.isFavorite) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Dialog: Rezept einer Sammlung zuweisen oder entfernen. */
@Composable
fun AssignToCollectionDialog(
    recipe: Recipe,
    onDismiss: () -> Unit,
    viewModel: RecipeCollectionsViewModel = viewModel()
) {
    val collections by viewModel.collections.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sammlung wählen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    recipe.displayTitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                // Keine Sammlung
                ListItem(
                    headlineContent = { Text("Keine Sammlung") },
                    leadingContent = {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.clickable {
                        viewModel.assignToCollection(recipe.id, null)
                        onDismiss()
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (recipe.collectionId == null)
                            MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent
                    )
                )

                if (collections.isEmpty()) {
                    Text(
                        "Noch keine Sammlungen – erstelle eine unter dem Ordner-Icon.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                collections.forEach { col ->
                    ListItem(
                        headlineContent = { Text("${col.emoji} ${col.name}") },
                        leadingContent = {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = if (recipe.collectionId == col.id)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable {
                            viewModel.assignToCollection(recipe.id, col.id)
                            onDismiss()
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (recipe.collectionId == col.id)
                                MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        }
    )
}
