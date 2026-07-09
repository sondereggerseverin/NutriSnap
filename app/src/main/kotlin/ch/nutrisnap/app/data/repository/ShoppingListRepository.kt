package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.ShoppingListItem
import kotlinx.coroutines.flow.Flow

class ShoppingListRepository(db: NutriDatabase) {
    private val dao = db.shoppingListDao()

    fun observeAll(): Flow<List<ShoppingListItem>> = dao.getAll()

    suspend fun add(name: String, amount: Float? = null, unit: String? = null, recipeTitle: String? = null) =
        dao.insert(ShoppingListItem(name = name.trim(), amount = amount, unit = unit, recipeTitle = recipeTitle))

    suspend fun addAll(items: List<ShoppingListItem>) = dao.insertAll(items)

    suspend fun toggle(item: ShoppingListItem) = dao.update(item.copy(checked = !item.checked))

    suspend fun delete(item: ShoppingListItem) = dao.delete(item)

    suspend fun clearChecked() = dao.deleteChecked()

    suspend fun clearAll() = dao.deleteAll()
}
