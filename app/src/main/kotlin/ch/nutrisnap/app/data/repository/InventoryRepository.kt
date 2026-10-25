package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.InventoryItem
import kotlinx.coroutines.flow.Flow

class InventoryRepository(db: NutriDatabase) {
    private val dao = db.inventoryDao()

    fun getAll(): Flow<List<InventoryItem>> = dao.getAll()

    suspend fun getAllOnce(): List<InventoryItem> = dao.getAllOnce()

    suspend fun getById(id: Long): InventoryItem? = dao.getById(id)

    suspend fun getByBarcode(barcode: String): InventoryItem? = dao.getByBarcode(barcode)

    suspend fun upsert(item: InventoryItem): Long = dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(item: InventoryItem) = dao.delete(item)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun updateQuantity(id: Long, quantity: Float) =
        dao.updateQuantity(id, quantity, System.currentTimeMillis())
}
