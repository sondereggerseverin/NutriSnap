package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.CustomFoodDao
import ch.nutrisnap.app.data.model.CustomFoodItem
import kotlinx.coroutines.flow.Flow

class CustomFoodRepository(private val dao: CustomFoodDao) {
    fun getAll(): Flow<List<CustomFoodItem>> = dao.getAll()
    fun search(query: String): Flow<List<CustomFoodItem>> = dao.search(query)
    suspend fun insert(item: CustomFoodItem) = dao.insert(item)
    suspend fun update(item: CustomFoodItem) = dao.update(item)
    suspend fun delete(item: CustomFoodItem) = dao.delete(item)
}
