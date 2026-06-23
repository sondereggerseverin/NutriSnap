package ch.nutrisnap.app.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
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

    /**
     * Steps today – deduplicated.
     *
     * Samsung Health can write multiple overlapping StepsRecord entries
     * (e.g. background + workout). We deduplicate by sorting records and
     * only counting non-overlapping intervals to avoid double-counting.
     */
    fun getTodaysSteps(): Flow<Long> = flow {
        val (start, end) = todayRange()
        val result = runCatching {
            val resp = client.readRecords(
                ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(start, end))
            )
            // Sort by start time, skip records that start before the previous one ended
            var lastEnd = start
            var total = 0L
            resp.records
                .sortedBy { it.startTime }
                .forEach { record ->
                    if (record.startTime >= lastEnd) {
                        total += record.count
                        lastEnd = record.endTime
                    } else if (record.endTime > lastEnd) {
                        // Partial overlap: only count the non-overlapping tail
                        // We can't split step count proportionally without timestamps,
                        // so we skip partial overlaps conservatively.
                        lastEnd = record.endTime
                    }
                }
            total
        }.getOrDefault(0L)
        emit(result)
    }

    /**
     * Active (workout) calories today.
     *
     * Strategy:
     * 1. Prefer ActiveCaloriesBurnedRecord – these are pure activity kcal, no BMR.
     * 2. If active kcal == 0 and TotalCaloriesBurnedRecord exists, use it as fallback
     *    but subtract an estimated BMR share so we don't inflate the number.
     *
     * Samsung Health writes TotalCaloriesBurnedRecord which includes BMR (~1800 kcal/day).
     * We avoid the BMR-subtraction heuristic that caused wrong numbers before.
     * Instead we simply trust ActiveCaloriesBurnedRecord as the primary source.
     */
    fun getTodaysActiveCalories(): Flow<Double> = flow {
        val (start, end) = todayRange()

        val activeCals = runCatching {
            val resp = client.readRecords(
                ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, TimeRangeFilter.between(start, end))
            )
            // Deduplicate overlapping records the same way as steps
            var lastEnd = start
            var total = 0.0
            resp.records
                .sortedBy { it.startTime }
                .forEach { record ->
                    if (record.startTime >= lastEnd) {
                        total += record.energy.inKilocalories
                        lastEnd = record.endTime
                    } else if (record.endTime > lastEnd) {
                        lastEnd = record.endTime
                    }
                }
            total
        }.getOrDefault(0.0)

        emit(activeCals)
    }

    /** Latest weight reading from last 30 days */
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

    /** Last night sleep: 18:00 yesterday → 12:00 today */
    fun getLastNightSleep(): Flow<Long> = flow {
        val from = LocalDate.now().minusDays(1).atTime(18, 0)
            .atZone(ZoneId.systemDefault()).toInstant()
        val to   = LocalDate.now().atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant()
        val result = runCatching {
            val resp = client.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, to))
            )
            resp.records.sumOf {
                java.time.Duration.between(it.startTime, it.endTime).toMinutes()
            }
        }.getOrDefault(0L)
        emit(result)
    }

    /** Average heart rate today */
    fun getTodaysAvgHeartRate(): Flow<Long?> = flow {
        val (start, end) = todayRange()
        val result = runCatching {
            val resp = client.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(start, end))
            )
            val all = resp.records.flatMap { it.samples }
            if (all.isEmpty()) null else all.map { it.beatsPerMinute }.average().toLong()
        }.getOrDefault(null)
        emit(result)
    }

    /** Steps grouped by day for the last 7 days (deduplicated per day) */
    fun getWeeklySteps(): Flow<Map<LocalDate, Long>> = flow {
        val weekStart = LocalDate.now().minusDays(6)
            .atStartOfDay(ZoneId.systemDefault()).toInstant()
        val result = runCatching {
            val resp = client.readRecords(
                ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(weekStart, Instant.now()))
            )
            // Group by day, then deduplicate within each day
            val byDay = resp.records.groupBy {
                it.startTime.atZone(ZoneId.systemDefault()).toLocalDate()
            }
            byDay.mapValues { (_, records) ->
                var lastEnd = Instant.EPOCH
                var total = 0L
                records.sortedBy { it.startTime }.forEach { r ->
                    if (r.startTime >= lastEnd) {
                        total += r.count
                        lastEnd = r.endTime
                    } else if (r.endTime > lastEnd) {
                        lastEnd = r.endTime
                    }
                }
                total
            }
        }.getOrDefault(emptyMap())
        emit(result)
    }

    private fun todayRange(): Pair<Instant, Instant> {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        return Pair(start, Instant.now())
    }
}
