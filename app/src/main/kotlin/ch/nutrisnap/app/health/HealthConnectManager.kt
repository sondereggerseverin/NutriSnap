package ch.nutrisnap.app.health

import android.content.Context
import android.content.Intent
import android.net.Uri
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

class HealthConnectManager(context: Context) {

    private val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context.applicationContext)
    }

    companion object {
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
     * Activity calories for a specific day.
     *
     * Strategy:
     * 1. Try ActiveCaloriesBurnedRecord aggregate first (most accurate, Samsung Health writes this).
     * 2. If that returns 0, fall back to reading individual ExerciseSessionRecords and summing
     *    their active calories — Samsung Health sometimes only writes session records.
     * 3. No BMR subtraction — we only want active/exercise calories, not total energy.
     */
    suspend fun getActiveCaloriesForDay(date: LocalDate): Double {
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val end   = if (date == LocalDate.now()) Instant.now()
                    else date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()

        return runCatching {
            // 1. Aggregate active calories
            val aggregated = client.aggregate(
                AggregateRequest(
                    metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0

            if (aggregated > 0.0) return@runCatching aggregated

            // 2. Fallback: sum individual ActiveCaloriesBurnedRecords (Samsung Health quirk)
            val records = client.readRecords(
                ReadRecordsRequest(
                    ActiveCaloriesBurnedRecord::class,
                    TimeRangeFilter.between(start, end)
                )
            ).records

            if (records.isEmpty()) return@runCatching 0.0

            // Deduplicate overlapping Samsung Health records:
            // Sort by startTime, skip records fully contained within a previous record's range.
            val sorted = records.sortedWith(compareBy({ it.startTime }, { -it.energy.inKilocalories }))
            var lastEnd = Instant.MIN
            var total = 0.0
            for (record in sorted) {
                if (record.startTime >= lastEnd) {
                    total += record.energy.inKilocalories
                    if (record.endTime > lastEnd) lastEnd = record.endTime
                }
            }
            total
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
