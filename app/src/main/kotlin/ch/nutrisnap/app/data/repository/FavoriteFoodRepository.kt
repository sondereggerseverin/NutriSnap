package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.favoriteKey
import ch.nutrisnap.app.data.model.toFavoriteEntity
import ch.nutrisnap.app.data.model.toFoodItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FavoriteFoodRepository(db: NutriDatabase) {
    private val dao = db.favoriteFoodDao()

    fun getAll(): Flow<List<FoodItem>> =
        dao.getAll().map { list -> list.map { it.toFoodItem() } }

    fun isFavorite(food: FoodItem): Flow<Boolean> = dao.isFavoriteFlow(food.favoriteKey())

    /** Returns the new favorite state (true = now favorited) */
    suspend fun toggle(food: FoodItem): Boolean {
        val key = food.favoriteKey()
        return if (dao.getByKey(key) != null) {
            dao.deleteByKey(key)
            false
        } else {
            dao.insert(food.toFavoriteEntity())
            true
        }
    }
}
