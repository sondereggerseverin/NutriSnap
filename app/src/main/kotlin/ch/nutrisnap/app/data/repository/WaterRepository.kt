package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.WaterEntryDao
import ch.nutrisnap.app.data.model.WaterEntry
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class WaterRepository(private val dao: WaterEntryDao) {
    fun getEntriesForDate(date: LocalDate): Flow<List<WaterEntry>> = dao.getEntriesForDate(date)
    fun getTotalForDate(date: LocalDate): Flow<Int?> = dao.getTotalForDate(date)
    suspend fun addEntry(date: LocalDate, amountMl: Int) = dao.insert(WaterEntry(date = date, amountMl = amountMl))
    suspend fun deleteEntry(entry: WaterEntry) = dao.delete(entry)
}
