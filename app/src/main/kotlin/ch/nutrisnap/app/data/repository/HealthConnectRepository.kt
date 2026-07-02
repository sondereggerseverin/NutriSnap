package ch.nutrisnap.app.data.repository

import android.util.Log
import ch.nutrisnap.app.data.db.HealthConnectDao
import ch.nutrisnap.app.data.model.HealthConnectCache
import ch.nutrisnap.app.health.HealthConnectManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "HealthConnectRepo"

class HealthConnectRepository(
    private val manager: HealthConnectManager,
    private val dao: HealthConnectDao,
    private val profileRepo: UserProfileRepository? = null
) {
    fun getTodayData(): Flow<HealthConnectCache?> = dao.getCacheForDate(LocalDate.now())
    fun getLast7Days(): Flow<List<HealthConnectCache>> = dao.getLast7Days()
    fun getLast30Days(): Flow<List<HealthConnectCache>> = dao.getLast30Days()

    // Used as a fallback: Samsung Health often writes TotalCaloriesBurnedRecord to
    // Health Connect but never writes ActiveCaloriesBurnedRecord. Subtracting a
    // prorated BMR from the total gives a usable "active calories" estimate.
    private suspend fun currentBmr(): Double? =
        runCatching { profileRepo?.get()?.firstOrNull()?.computedBmr() }.getOrNull()

    suspend fun syncToday(): Result<HealthConnectCache> = runCatching {
        coroutineScope {
            val bmr = currentBmr()
            val steps = async {
                runCatching { manager.getTodaysSteps().firstOrNull() ?: 0L }.getOrDefault(0L)
            }
            val calories = async {
                runCatching { manager.getActiveCaloriesForDay(LocalDate.now(), bmr) }
                    .onFailure { Log.e(TAG, "syncToday: calories fetch failed", it) }
                    .getOrDefault(null)
            }
            val weight = async {
                runCatching {
                    val today = LocalDate.now()
                    val start = today.atStartOfDay(ZoneId.systemDefault()).toInstant()
                    val end = java.time.Instant.now()
                    manager.getWeightForRange(start, end)[today]
                }.getOrDefault(null)
            }
            val sleep = async {
                runCatching { manager.getLastNightSleep().firstOrNull() ?: 0L }.getOrDefault(0L)
            }
            val heartRate = async {
                runCatching { manager.getTodaysAvgHeartRate().firstOrNull() }.getOrDefault(null)
            }

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

    /**
     * Syncs historical data for the last [days] days.
     * Skips days that are already in the cache (date exists in DB).
     * Also fills in weight readings from Health Connect for each day.
     */
    suspend fun syncHistorical(days: Int = 30): Result<Int> = runCatching {
        val today = LocalDate.now()
        val bmr = currentBmr()
        // Fetch all weight data in one API call for efficiency
        val weightFrom = today.minusDays(days.toLong())
            .atStartOfDay(ZoneId.systemDefault()).toInstant()
        val weightMap = runCatching {
            manager.getWeightForRange(weightFrom, Instant.now())
        }.getOrDefault(emptyMap())

        var synced = 0
        for (i in 1..days) {
            val date = today.minusDays(i.toLong())
            // Skip today (handled by syncToday) and days already cached with data
            val existing = dao.getCacheForDateOnce(date)
            if (existing != null && existing.steps > 0) continue

            val steps = runCatching { manager.getStepsForDay(date) }.getOrDefault(0L)
            val calories = runCatching { manager.getActiveCaloriesForDay(date, bmr) }.getOrDefault(null)
            val sleep = runCatching { manager.getSleepForNight(date) }.getOrDefault(0L)
            val weight = weightMap[date] ?: existing?.weightKg

            val cache = HealthConnectCache(
                date = date,
                steps = steps,
                activeCaloriesKcal = calories,
                weightKg = weight,
                sleepMinutes = sleep,
                avgHeartRateBpm = existing?.avgHeartRateBpm
            )
            dao.insertOrUpdate(cache)
            synced++
        }
        synced
    }

    suspend fun hasPermissions(): Boolean = manager.hasAllPermissions()
}
