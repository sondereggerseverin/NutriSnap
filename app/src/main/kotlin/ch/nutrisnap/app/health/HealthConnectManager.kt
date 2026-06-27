package ch.nutrisnap.app.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class HealthConnectStatus { AVAILABLE, NEEDS_UPDATE, NOT_AVAILABLE }

/**
 * Diagnostic data class for debugging what Samsung Health writes into Health Connect.
 */
data class CalorieDiagnostics(
    val activeCaloriesAggregate: Double,
    val activeCaloriesRecordCount: Int,
    val activeCaloriesRecordSum: Double,
    val totalCaloriesAggregate: Double,
    val exerciseSessionCount: Int,
    val exerciseSessionCaloriesSum: Double,
    val elapsedMinutes: Long
)

class HealthConnectManager(context: Context) {

    private val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context.applicationContext)
    }

    companion object {
        private const val TAG = "HealthConnect"

        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        )

        fun getStatus(context: Context): HealthConnectStatus = when (
            HealthConnectClient.getSdkStatus(context)
        ) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectStatus.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectStatus.NEEDS_UPDATE
            else -> HealthConnectStatus.NOT_AVAILABLE
        }

        fun isAvailable(context: Context): Boolean =
            getStatus(context) == HealthConnectStatus.AVAILABLE

        fun openPlayStore(context: Context) {
            val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata")
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    suspend fun checkPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    suspend fun hasAllPermissions(): Boolean =
        checkPermissions().containsAll(REQUIRED_PERMISSIONS)

    /** Steps for a specific day */
    suspend fun getStepsForDay(date: LocalDate): Long {
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val end   = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        return runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )[StepsRecord.COUNT_TOTAL] ?: 0L
        }.getOrDefault(0L)
    }

    /**
     * Reads all calorie-related data from Health Connect and logs everything
     * for debugging what Samsung Health actually writes into HC.
     * Check Logcat with tag "HealthConnect" after calling this.
     */
    suspend fun getDiagnosticsForToday(): CalorieDiagnostics {
        val date = LocalDate.now()
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val end = Instant.now()
        val elapsedMinutes = java.time.Duration.between(start, end).toMinutes()

        // 1. ActiveCaloriesBurnedRecord aggregate
        val activeAgg = runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
        }.getOrDefault(0.0)

        // 2. ActiveCaloriesBurnedRecord individual records
        val activeRecords = runCatching {
            client.readRecords(
                ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, TimeRangeFilter.between(start, end))
            ).records
        }.getOrDefault(emptyList())
        val activeRecordSum = activeRecords.sumOf { it.energy.inKilocalories }

        // 3. TotalCaloriesBurnedRecord aggregate
        val totalAgg = runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0
        }.getOrDefault(0.0)

        // 4. ExerciseSessionRecord individual records + their active calories
        val exerciseSessions = runCatching {
            client.readRecords(
                ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(start, end))
            ).records
        }.getOrDefault(emptyList())
        val exerciseCalSum = exerciseSessions.sumOf {
            it.exerciseRouteResult.let { _ -> 0.0 } // ExerciseSessionRecord has no direct calorie field
        }

        // Log everything
        Log.d(TAG, "=== CALORIE DIAGNOSTICS (${date}) ===")
        Log.d(TAG, "Elapsed time: ${elapsedMinutes} min")
        Log.d(TAG, "ActiveCaloriesBurned AGGREGATE: $activeAgg kcal")
        Log.d(TAG, "ActiveCaloriesBurned RECORDS: ${activeRecords.size} records, sum=$activeRecordSum kcal")
        activeRecords.forEachIndexed { i, r ->
            Log.d(TAG, "  ActiveRecord[$i]: ${r.energy.inKilocalories} kcal, ${r.startTime} -> ${r.endTime}, dataOrigin=${r.metadata.dataOrigin.packageName}")
        }
        Log.d(TAG, "TotalCaloriesBurned AGGREGATE: $totalAgg kcal")
        Log.d(TAG, "  -> TotalCal - BMR estimate (${elapsedMinutes} min * 1 kcal/min = ${elapsedMinutes} kcal) = ${(totalAgg - elapsedMinutes).coerceAtLeast(0.0)} kcal")
        Log.d(TAG, "ExerciseSessions: ${exerciseSessions.size} sessions")
        exerciseSessions.forEachIndexed { i, s ->
            Log.d(TAG, "  Session[$i]: type=${s.exerciseType}, ${s.startTime} -> ${s.endTime}, dataOrigin=${s.metadata.dataOrigin.packageName}")
        }
        Log.d(TAG, "=== END DIAGNOSTICS ===")

        return CalorieDiagnostics(
            activeCaloriesAggregate = activeAgg,
            activeCaloriesRecordCount = activeRecords.size,
            activeCaloriesRecordSum = activeRecordSum,
            totalCaloriesAggregate = totalAgg,
            exerciseSessionCount = exerciseSessions.size,
            exerciseSessionCaloriesSum = exerciseCalSum,
            elapsedMinutes = elapsedMinutes
        )
    }

    /**
     * Activity calories for a specific day.
     *
     * Samsung Health writes BOTH ActiveCaloriesBurnedRecord AND TotalCaloriesBurnedRecord.
     * Samsung Health's dashboard "Cal" value = TotalCaloriesBurnedRecord (which on Samsung
     * devices = activity-only calories, NOT including BMR).
     *
     * Strategy: take the MAXIMUM of Active and Total aggregates, because Samsung Health
     * sometimes writes its full activity cal into TotalCaloriesBurnedRecord and only a
     * subset into ActiveCaloriesBurnedRecord.
     */
    /**
     * Returns the activity/burned calories for a day — matching what Samsung Health shows.
     *
     * Samsung Galaxy devices write into Health Connect:
     *   - ActiveCaloriesBurnedRecord  = exercise/movement calories only (often underreported)
     *   - TotalCaloriesBurnedRecord   = on Samsung = TOTAL including BMR
     *
     * Samsung Health "Verbrannt" value = TotalCaloriesBurnedRecord on Galaxy devices.
     * We return TotalCaloriesBurnedRecord when available, falling back to ActiveCalories.
     */
    suspend fun getActiveCaloriesForDay(date: LocalDate): Double {
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val end   = if (date == LocalDate.now()) Instant.now()
                    else date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()

        return runCatching {
            val activeAgg = client.aggregate(
                AggregateRequest(
                    metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0

            val totalAgg = client.aggregate(
                AggregateRequest(
                    metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0

            Log.d(TAG, "getActiveCaloriesForDay($date): activeAgg=$activeAgg totalAgg=$totalAgg")

            // Samsung Health on Galaxy writes its full "Verbrannt" value into TotalCaloriesBurnedRecord.
            // If total > active significantly, use total (it IS the Samsung Health dashboard value).
            // If total is 0 or suspiciously low, fall back to active.
            when {
                totalAgg > activeAgg * 1.5 && totalAgg > 200.0 -> totalAgg   // Samsung Health total = "Verbrannt"
                activeAgg > 0.0 -> activeAgg
                totalAgg > 0.0  -> totalAgg
                else -> 0.0
            }
        }.getOrDefault(0.0)
    }

    /** Sleep for a specific night (18:00 day-1 → 12:00 day) */
    suspend fun getSleepForNight(date: LocalDate): Long {
        val from = date.minusDays(1).atTime(18, 0)
            .atZone(ZoneId.systemDefault()).toInstant()
        val to   = date.atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant()
        return runCatching {
            val resp = client.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, to))
            )
            resp.records.sumOf {
                java.time.Duration.between(it.startTime, it.endTime).toMinutes()
            }
        }.getOrDefault(0L)
    }

    /** Weight readings for a date range (newest per day) */
    suspend fun getWeightForRange(from: Instant, to: Instant): Map<LocalDate, Double> {
        return runCatching {
            val resp = client.readRecords(
                ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(from, to))
            )
            resp.records
                .groupBy { it.time.atZone(ZoneId.systemDefault()).toLocalDate() }
                .mapValues { (_, records) -> records.last().weight.inKilograms }
        }.getOrDefault(emptyMap())
    }

    fun getTodaysSteps(): Flow<Long> = flow {
        emit(getStepsForDay(LocalDate.now()))
    }

    fun getTodaysActiveCalories(): Flow<Double> = flow {
        emit(getActiveCaloriesForDay(LocalDate.now()))
    }

    fun getLatestWeight(): Flow<Double?> = flow {
        val result = runCatching {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    WeightRecord::class,
                    TimeRangeFilter.between(
                        Instant.now().minusSeconds(30L * 24 * 3600),
                        Instant.now()
                    )
                )
            )
            resp.records.lastOrNull()?.weight?.inKilograms
        }.getOrDefault(null)
        emit(result)
    }

    fun getLastNightSleep(): Flow<Long> = flow {
        emit(getSleepForNight(LocalDate.now()))
    }

    fun getTodaysAvgHeartRate(): Flow<Long?> = flow {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        val end   = Instant.now()
        val result = runCatching {
            val resp = client.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(start, end))
            )
            val all = resp.records.flatMap { it.samples }
            if (all.isEmpty()) null else all.map { it.beatsPerMinute }.average().toLong()
        }.getOrDefault(null)
        emit(result)
    }

    fun getWeeklySteps(): Flow<Map<LocalDate, Long>> = flow {
        val today = LocalDate.now()
        val map = mutableMapOf<LocalDate, Long>()
        for (i in 6 downTo 0) {
            val day = today.minusDays(i.toLong())
            map[day] = getStepsForDay(day)
        }
        emit(map)
    }

    /** Weekly active calories (last 7 days) */
    fun getWeeklyActiveCalories(): Flow<Map<LocalDate, Double>> = flow {
        val today = LocalDate.now()
        val map = mutableMapOf<LocalDate, Double>()
        for (i in 6 downTo 0) {
            val day = today.minusDays(i.toLong())
            map[day] = getActiveCaloriesForDay(day)
        }
        emit(map)
    }
}
