package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.Supplement
import kotlinx.coroutines.flow.Flow

class SupplementRepository(db: NutriDatabase) {
    private val dao = db.supplementDao()

    fun observeAll(): Flow<List<Supplement>> = dao.getAll()
    suspend fun getById(id: Int): Supplement? = dao.getById(id)
    suspend fun isEmpty(): Boolean = dao.count() == 0
    suspend fun seedIfEmpty(seed: List<Supplement>) {
        if (isEmpty()) dao.insertAll(seed)
    }
    suspend fun add(item: Supplement) { dao.insert(item) }
    suspend fun update(item: Supplement) { dao.update(item) }
    suspend fun delete(item: Supplement) { dao.delete(item) }
}
