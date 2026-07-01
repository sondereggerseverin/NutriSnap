package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.WeightEntry
import ch.nutrisnap.app.data.supabase.SupabaseSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.LocalDate

class WeightRepository(db: NutriDatabase) {
    private val dao = db.weightDao()
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun pushSafely(block: suspend () -> Unit) {
        syncScope.launch { runCatching { block() } }
    }

    fun getAll(): Flow<List<WeightEntry>> = dao.getAll()

    /** Last [days] days, ascending by date */
    fun getRecent(days: Int = 7): Flow<List<WeightEntry>> =
        dao.getSince(LocalDate.now().minusDays((days - 1).toLong()).toString())

    suspend fun getLatest(): WeightEntry? = dao.getLatest()

    suspend fun logWeight(date: LocalDate, kg: Float) {
        val entry = WeightEntry(dateStr = date.toString(), weightKg = kg)
        dao.upsert(entry)
        pushSafely { SupabaseSync.upsertWeight(entry) }
    }

    suspend fun delete(entry: WeightEntry) {
        dao.delete(entry)
        pushSafely { SupabaseSync.deleteWeight(entry.dateStr) }
    }
}
