package ch.nutrisnap.app.ui.screens.recipes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.InventoryItem
import ch.nutrisnap.app.data.repository.InventoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InventoryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = InventoryRepository(NutriDatabase.getInstance(app))

    val items: StateFlow<List<InventoryItem>> = repo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun upsert(
        id: Long = 0,
        name: String,
        quantity: Float,
        unit: String,
        notes: String = "",
        location: String = "",
        barcode: String? = null,
        foodItemId: Long? = null
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val existing = if (id > 0) repo.getById(id) else null
            val item = InventoryItem(
                id = id,
                name = name.trim(),
                quantity = quantity.coerceAtLeast(0f),
                unit = unit.ifBlank { "Stk" },
                notes = notes.trim(),
                barcode = barcode?.ifBlank { null },
                foodItemId = foodItemId,
                location = location.trim(),
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            repo.upsert(item)
        }
    }

    fun delete(item: InventoryItem) {
        viewModelScope.launch { repo.delete(item) }
    }

    fun updateQuantity(id: Long, quantity: Float) {
        viewModelScope.launch { repo.updateQuantity(id, quantity.coerceAtLeast(0f)) }
    }
}
