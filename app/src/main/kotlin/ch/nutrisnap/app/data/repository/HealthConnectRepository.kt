package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.HealthConnectDao
import ch.nutrisnap.app.data.model.HealthConnectCache
import ch.nutrisnap.app.health.HealthConnectManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthConnectRepository @Inject constructor(
    private val manager: HealthConnectManager,
    private val dao: HealthConnectDao
) {
    /** Holt heute's Daten – erst aus Cache, dann von Health Connect */
    fun getTodayData(): Flow<HealthConnectCache?> =
        dao.getCacheForDate(LocalDate.now())

    fun getLast7Days(): Flow<List<HealthConnectCache>> =
        dao.getLast7Days()

    /**
     * Synchronisiert alle Health Connect Daten für heute.
     * Parallel-Abfrage für maximale Geschwindigkeit.
     */
    suspend fun syncToday(): Result<HealthConnectCache> = runCatching {
        coroutineScope {
            val steps = async { manager.getTodaysSteps().firstOrNull() ?: 0L }
            val calories = async { manager.getTodaysActiveCalories().firstOrNull() ?: 0.0 }
            val weight = async { manager.getLatestWeight().firstOrNull() }
            val sleep = async { manager.getLastNightSleep().firstOrNull() ?: 0L }
            val heartRate = async { manager.getTodaysAvgHeartRate().firstOrNull() }

            val cache = HealthConnectCache(
                date = LocalDate.now(),
                steps = steps.await(),
                activeCaloriesKcal = calories.await(),
                weightKg = weight.await(),
                sleepMinutes = sleep.await(),
                avgHeartRateBpm = heartRate.await(),
                lastUpdated = System.currentTimeMillis()
            )

            dao.insertOrUpdate(cache)
            // Alte Daten (>30 Tage) aufräumen
            dao.deleteOlderThan(LocalDate.now().minusDays(30))
            cache
        }
    }

    suspend fun hasPermissions(): Boolean = manager.hasAllPermissions()
}
