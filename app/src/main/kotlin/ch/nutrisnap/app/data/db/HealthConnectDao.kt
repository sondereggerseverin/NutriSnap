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

    @Query("SELECT * FROM health_connect_cache WHERE date = :date")
    suspend fun getCacheForDateOnce(date: LocalDate): HealthConnectCache?

    @Query("DELETE FROM health_connect_cache WHERE date < :cutoff")
    suspend fun deleteOlderThan(cutoff: LocalDate)
}
