package ch.nutrisnap.app.data.repository

import android.content.Context
import android.util.Log
import ch.nutrisnap.app.data.db.HealthConnectDao
import ch.nutrisnap.app.data.model.HealthConnectCache
import ch.nutrisnap.app.health.HealthConnectManager
import ch.nutrisnap.app.health.SamsungHealthDataManager
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
    private val profileRepo: UserProfileRepository? = null,
    context: Context? = null
) {
    // Tier 0 source: reads active calories directly from Samsung Health, bypassing Health
    // Connect entirely. Only used when available/permitted; falls back to Health Connect
    // tiers (see HealthConnectManager.getActiveCaloriesForDay) otherwise. `context` is
    // optional so existing call sites that don't pass one keep working (Tier 0 simply
    // stays disabled for them, same as before this SDK was integrated).
    private val samsungHealthManager: SamsungHealthDataManager? =
        context?.let {
            runCatching { SamsungHealthDataManager(it.applicationContext) }
                .onFailure { e -> Log.w(TAG, "Samsung Health SDK init failed, disabling Tier 0", e) }
                .getOrNull()
        }

    private suspend fun samsungActiveCalories(date: LocalDate): Double? =
        runCatching { samsungHealthManager?.readActiveCalories(date) }
            .onFailure { Log.w(TAG, "Samsung Health Tier 0 calories read failed for $date", it) }
            .getOrNull()

    fun getTodayData(): Flow<HealthConnectCache?> = dao.getCacheForDate(LocalDate.now())
    fun getLast7Days(): Flow<List<HealthConnectCache>> = dao.getLast7Days()
    fun getLast30Days(): Flow<List<HealthConnectCache>> = dao.getLast30Days()

    /** Beliebiger Zeitraum aus dem lokalen Cache (Tages-/Wochen-/Monatsansicht, Kalender). */
    fun getRange(from: LocalDate, to: LocalDate): Flow<List<HealthConnectCache>> =
        dao.getRange(from, to)

    /**
     * Stellt sicher, dass [from]..[to] im Cache vorhanden ist, bevor die UI ihn liest.
     * Fragt Health Connect nur für Tage ab, die noch fehlen oder nur mit steps == 0
     * gecached sind (gleiche Heuristik wie [syncHistorical]). Braucht für Tage älter
     * als 30 Tage die PERMISSION_READ_HEALTH_DATA_HISTORY-Berechtigung — ohne sie
     * liefert Health Connect für diese Tage einfach nichts zurück (kein Crash).
     */
    suspend fun ensureRangeSynced(from: LocalDate, to: LocalDate): Result<Int> = runCatching {
        val today = LocalDate.now()
        val cappedTo = if (to.isAfter(today)) today else to
        if (from.isAfter(cappedTo)) return@runCatching 0

        val bmr = currentBmr()
        val cached = dao.getRangeOnce(from, cappedTo).associateBy { it.date }
        val missingDays = generateSequence(from) { d -> d.plusDays(1) }
            .takeWhile { !it.isAfter(cappedTo) }
            .filter { it == today || cached[it] == null || cached[it]!!.steps == 0L }
            .toList()
        if (missingDays.isEmpty()) return@runCatching 0

        val weightFrom = from.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val weightTo = cappedTo.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val weightMap = runCatching { manager.getWeightForRange(weightFrom, weightTo) }
            .getOrDefault(emptyMap())

        var synced = 0
        for (date in missingDays) {
            val steps = runCatching { manager.getStepsForDay(date) }.getOrDefault(0L)
            val calories = samsungActiveCalories(date)
                ?: runCatching { manager.getActiveCaloriesForDay(date, bmr) }.getOrDefault(null)
            val sleep = runCatching { manager.getSleepForNight(date) }.getOrDefault(0L)
            val weight = weightMap[date] ?: cached[date]?.weightKg

            dao.insertOrUpdate(
                HealthConnectCache(
                    date = date,
                    steps = steps,
                    activeCaloriesKcal = calories,
                    weightKg = weight,
                    sleepMinutes = sleep,
                    avgHeartRateBpm = cached[date]?.avgHeartRateBpm
                )
            )
            synced++
        }
        synced
    }

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
                samsungActiveCalories(LocalDate.now()) ?: runCatching {
                    manager.getActiveCaloriesForDay(LocalDate.now(), bmr)
                }.onFailure { Log.e(TAG, "syncToday: calories fetch failed", it) }
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
            // War vorher 30 Tage — der Cache ist jetzt auch die Datenquelle fuer die
            // Monats-/Kalenderansicht in der Analyse, daher deutlich laenger behalten.
            dao.deleteOlderThan(LocalDate.now().minusDays(400))
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
            val calories = samsungActiveCalories(date)
                ?: runCatching { manager.getActiveCaloriesForDay(date, bmr) }.getOrDefault(null)
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

    suspend fun hasSamsungHealthPermissions(): Boolean =
        samsungHealthManager?.hasPermissions() ?: false

    suspend fun requestSamsungHealthPermissions(activity: android.app.Activity): Boolean =
        samsungHealthManager?.requestPermissions(activity) ?: false

    fun isSamsungHealthSupported(): Boolean =
        samsungHealthManager != null && SamsungHealthDataManager.isSupported()
}
