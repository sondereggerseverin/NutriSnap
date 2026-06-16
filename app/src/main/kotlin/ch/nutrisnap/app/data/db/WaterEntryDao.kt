package ch.nutrisnap.app.data.db

import androidx.room.*
import ch.nutrisnap.app.data.model.WaterEntry
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface WaterEntryDao {

    @Query("SELECT * FROM water_entries WHERE date = :date ORDER BY timestamp DESC")
    fun getEntriesForDate(date: LocalDate): Flow<List<WaterEntry>>

    @Query("SELECT SUM(amountMl) FROM water_entries WHERE date = :date")
    fun getTotalForDate(date: LocalDate): Flow<Int?>

    @Insert
    suspend fun insert(entry: WaterEntry)

    @Delete
    suspend fun delete(entry: WaterEntry)
}
