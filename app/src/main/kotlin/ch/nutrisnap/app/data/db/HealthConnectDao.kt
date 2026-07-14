package ch.nutrisnap.app.data.db

import androidx.room.*
import ch.nutrisnap.app.data.model.HealthConnectCache
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface HealthConnectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(cache: HealthConnectCache)

    @Query("SELECT * FROM health_connect_cache WHERE date = :date")
    fun getCacheForDate(date: LocalDate): Flow<HealthConnectCache?>

    @Query("SELECT * FROM health_connect_cache ORDER BY date DESC LIMIT 7")
    fun getLast7Days(): Flow<List<HealthConnectCache>>

    @Query("SELECT * FROM health_connect_cache ORDER BY date DESC LIMIT 30")
    fun getLast30Days(): Flow<List<HealthConnectCache>>

    @Query("SELECT * FROM health_connect_cache WHERE date = :date")
    suspend fun getCacheForDateOnce(date: LocalDate): HealthConnectCache?

    // Bounded range query for Tages-/Wochen-/Monatsansicht in der Analyse — deckt
    // beliebige Zeiträume ab, nicht nur "letzte N Tage".
    @Query("SELECT * FROM health_connect_cache WHERE date BETWEEN :from AND :to ORDER BY date ASC")
    fun getRange(from: LocalDate, to: LocalDate): Flow<List<HealthConnectCache>>

    @Query("SELECT * FROM health_connect_cache WHERE date BETWEEN :from AND :to ORDER BY date ASC")
    suspend fun getRangeOnce(from: LocalDate, to: LocalDate): List<HealthConnectCache>

    @Query("DELETE FROM health_connect_cache WHERE date < :cutoff")
    suspend fun deleteOlderThan(cutoff: LocalDate)
}
