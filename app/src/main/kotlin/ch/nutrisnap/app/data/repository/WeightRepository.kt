package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.WeightEntry
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class WeightRepository(db: NutriDatabase) {
    private val dao = db.weightDao()

    fun getAll(): Flow<List<WeightEntry>> = dao.getAll()

    /** Last [days] days, ascending by date */
    fun getRecent(days: Int = 7): Flow<List<WeightEntry>> =
        dao.getSince(LocalDate.now().minusDays((days - 1).toLong()).toString())

    suspend fun getLatest(): WeightEntry? = dao.getLatest()

    suspend fun logWeight(date: LocalDate, kg: Float) {
        dao.upsert(WeightEntry(dateStr = date.toString(), weightKg = kg))
    }

    suspend fun delete(entry: WeightEntry) = dao.delete(entry)
}
