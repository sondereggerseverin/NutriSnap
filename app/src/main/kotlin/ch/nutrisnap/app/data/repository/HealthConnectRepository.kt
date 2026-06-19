package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.HealthConnectDao
import ch.nutrisnap.app.data.model.HealthConnectCache
import ch.nutrisnap.app.health.HealthConnectManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import java.time.LocalDate

class HealthConnectRepository(
    private val manager: HealthConnectManager,
    private val dao: HealthConnectDao
) {
    fun getTodayData(): Flow<HealthConnectCache?> = dao.getCacheForDate(LocalDate.now())
    fun getLast7Days(): Flow<List<HealthConnectCache>> = dao.getLast7Days()

    suspend fun syncToday(): Result<HealthConnectCache> = runCatching {
        coroutineScope {
            val steps     = async { manager.getTodaysSteps().firstOrNull() ?: 0L }
            val calories  = async { manager.getTodaysActiveCalories().firstOrNull() ?: 0.0 }
            val weight    = async { manager.getLatestWeight().firstOrNull() }
            val sleep     = async { manager.getLastNightSleep().firstOrNull() ?: 0L }
            val heartRate = async { manager.getTodaysAvgHeartRate().firstOrNull() }

            val cache = HealthConnectCache(
                date = LocalDate.now(),
                steps = steps.await(),
                activeCaloriesKcal = calories.await(),
                weightKg = weight.await(),
                sleepMinutes = sleep.await(),
                avgHeartRateBpm = heartRate.await()
            )
            dao.insertOrUpdate(cache)
            dao.deleteOlderThan(LocalDate.now().minusDays(30))
            cache
        }
    }

    suspend fun hasPermissions(): Boolean = manager.hasAllPermissions()
}
