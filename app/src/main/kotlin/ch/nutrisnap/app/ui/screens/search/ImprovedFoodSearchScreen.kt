package ch.nutrisnap.app.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import ch.nutrisnap.app.data.repository.FoodSearchRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed class SearchState {
    object Idle : SearchState()
    object Loading : SearchState()
    data class Results(val items: List<FoodItem>) : SearchState()
    data class Error(val message: String) : SearchState()
}

class ImprovedFoodSearchViewModel(
    private val searchRepository: FoodSearchRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    val recentFoods: StateFlow<List<FoodItem>> = searchRepository.getRecentFoods()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val frequentFoods: StateFlow<List<FoodItem>> = searchRepository.getFrequentFoods()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            _query.debounce(300L).distinctUntilChanged().collect { q ->
                if (q.length >= 2) performSearch(q) else _searchState.value = SearchState.Idle
            }
        }
    }

    fun onQueryChange(newQuery: String) { _query.value = newQuery }
    fun onClear() { _query.value = ""; _searchState.value = SearchState.Idle }

    fun searchByBarcode(barcode: String) {
        viewModelScope.launch {
            _searchState.value = SearchState.Loading
            val result = searchRepository.searchByBarcode(barcode)
            _searchState.value = if (result != null) SearchState.Results(listOf(result))
            else SearchState.Error("Produkt nicht gefunden (Barcode: $barcode)")
        }
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searchState.value = SearchState.Loading
            val results = runCatching { searchRepository.search(query) }.getOrDefault(emptyList())
            _searchState.value = if (results.isEmpty()) SearchState.Error("Keine Ergebnisse fuer \"$query\"")
            else SearchState.Results(results)
        }
    }
}

@Composable
fun ImprovedFoodSearchScreen(
    viewModel: ImprovedFoodSearchViewModel,
    onFoodSelected: (FoodItem) -> Unit,
    onOpenBarcode: () -> Unit
) {
    val query by viewModel.query.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val recentFoods by viewModel.recentFoods.collectAsState()
    val frequentFoods by viewModel.frequentFoods.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Lebensmittel suchen...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = viewModel::onClear) {
                        Icon(Icons.Default.Clear, contentDescription = "Loeschen")
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onOpenBarcode, modifier = Modifier.size(56.dp)) {
                Text("📷", style = MaterialTheme.typography.headlineSmall)
            }
        }

        when (val state = searchState) {
            is SearchState.Idle -> IdleSearchContent(recentFoods, frequentFoods, onFoodSelected)
            is SearchState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is SearchState.Results -> SearchResultsList(state.items, query, onFoodSelected)
            is SearchState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("😕", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = {}) { Text("Manuell erfassen") }
                }
            }
        }
    }
}

@Composable
private fun IdleSearchContent(recentFoods: List<FoodItem>, frequentFoods: List<FoodItem>, onFoodSelected: (FoodItem) -> Unit) {
    LazyColumn {
        if (recentFoods.isNotEmpty()) {
            item { SectionHeader("Zuletzt gegessen") }
            items(recentFoods.take(5)) { food -> FoodListItem(food, onClick = { onFoodSelected(food) }) }
        }
        if (frequentFoods.isNotEmpty()) {
            item { SectionHeader("Haeufig genutzt") }
            items(frequentFoods.take(5)) { food -> FoodListItem(food, onClick = { onFoodSelected(food) }) }
        }
    }
}

@Composable
private fun SearchResultsList(items: List<FoodItem>, query: String, onFoodSelected: (FoodItem) -> Unit) {
    LazyColumn {
        item {
            Text("${items.size} Ergebnisse fuer \"$query\"",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(items) { food -> FoodListItem(food, onClick = { onFoodSelected(food) }) }
    }
}

@Composable
private fun FoodListItem(food: FoodItem, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(food.name, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Text("${food.calories.toInt()} kcal | P: ${food.protein.toInt()}g | K: ${food.carbs.toInt()}g | F: ${food.fat.toInt()}g",
                style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Text(
                text = when (food.source) {
                    FoodSource.USDA -> "USDA"
                    FoodSource.OPEN_FOOD_FACTS -> "OFF"
                    FoodSource.NUTRITIONIX -> "NTX"
                    FoodSource.MANUAL -> "✏️"
                    FoodSource.SWISS_FSVO -> "🇨🇭"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
    HorizontalDivider(thickness = 0.5.dp)
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}
