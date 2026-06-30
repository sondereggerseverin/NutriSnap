package ch.nutrisnap.app.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
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
        private const val TAG = "HealthConnectManager"

        // Samsung Health's package id - used to filter out other contributing apps
        // (e.g. Google Fit, other trackers) that may inflate or duplicate values.
        private const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"

        // Sanity cap: nobody legitimately burns more "active" kcal than this in a day.
        // Used only as a last-resort guard on the unfiltered aggregate fallback.
        private const val ACTIVE_CALORIES_SANITY_CAP_KCAL = 3000.0

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
        }.onFailure {
            Log.e(TAG, "getStepsForDay failed for $date", it)
        }.getOrDefault(0L)
    }

    /**
     * Activity calories for a specific day (active/movement only, no BMR).
     *
     * Three-tier fallback, in order of trustworthiness:
     *  1. Sum individual ActiveCaloriesBurnedRecord entries written by Samsung Health
     *     (filtered by dataOrigin). Most granular and reliable when present.
     *  2. Aggregate ActiveCaloriesBurnedRecord but restricted to Samsung Health's
     *     dataOrigin via AggregateRequest's dataOriginFilter. Cheaper, same source.
     *  3. Unfiltered aggregate across all sources, capped at a sanity ceiling, so a
     *     misbehaving/duplicate source can't blow up the displayed number. This is
     *     the old behaviour and is only used if Samsung Health has no data at all
     *     (e.g. permission not granted yet, or sync hasn't happened).
     */
    suspend fun getActiveCaloriesForDay(date: LocalDate): Double? {
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val end   = if (date == LocalDate.now()) Instant.now()
                    else date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val range = TimeRangeFilter.between(start, end)

        // Tier 1: sum raw Samsung-origin records.
        val samsungRecordsSum = runCatching {
            val resp = client.readRecords(
                ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, range)
            )
            val samsungRecords = resp.records.filter {
                it.metadata.dataOrigin.packageName == SAMSUNG_HEALTH_PACKAGE
            }
            if (samsungRecords.isEmpty()) null
            else samsungRecords.sumOf { it.energy.inKilocalories }
        }.onFailure {
            Log.e(TAG, "getActiveCaloriesForDay: Tier 1 (Samsung records) failed for $date", it)
        }.getOrNull()

        if (samsungRecordsSum != null && samsungRecordsSum > 0.0) {
            return samsungRecordsSum
        }

        // Tier 2: Samsung-filtered aggregate.
        val samsungAggregate = runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                    timeRangeFilter = range,
                    dataOriginFilter = setOf(DataOrigin(SAMSUNG_HEALTH_PACKAGE))
                )
            )[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
        }.onFailure {
            Log.e(TAG, "getActiveCaloriesForDay: Tier 2 (Samsung aggregate) failed for $date", it)
        }.getOrNull()

        if (samsungAggregate != null && samsungAggregate > 0.0) {
            return samsungAggregate
        }

        // Tier 3: unfiltered aggregate, capped as a last resort.
        // Kept nullable here on purpose: a missing key means Health Connect has
        // no record at all for this day (e.g. Samsung Health hasn't synced yet),
        // which is different from a genuine "burned 0 kcal" reading.
        val unfilteredAggregate = runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                    timeRangeFilter = range
                )
            )[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
        }.onFailure {
            Log.e(TAG, "getActiveCaloriesForDay: Tier 3 (unfiltered aggregate) failed for $date", it)
        }.getOrNull()

        if (unfilteredAggregate == null) {
            Log.w(TAG, "getActiveCaloriesForDay: all 3 tiers returned no data for $date " +
                "(check Health Connect permissions / Samsung Health sync)")
            return null
        }

        return unfilteredAggregate.coerceAtMost(ACTIVE_CALORIES_SANITY_CAP_KCAL)
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
            // Group by date, take last reading per day
            resp.records
                .groupBy { it.time.atZone(ZoneId.systemDefault()).toLocalDate() }
                .mapValues { (_, records) -> records.last().weight.inKilograms }
        }.getOrDefault(emptyMap())
    }

    /**
     * Steps today.
     * AggregateRequest deduplicates Samsung Health overlapping records internally.
     */
    fun getTodaysSteps(): Flow<Long> = flow {
        emit(getStepsForDay(LocalDate.now()))
    }

    /**
     * Activity calories heute.
     */
    fun getTodaysActiveCalories(): Flow<Double?> = flow {
        emit(getActiveCaloriesForDay(LocalDate.now()))
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
        emit(getSleepForNight(LocalDate.now()))
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

    /**
     * Steps per day for the last 7 days.
     */
    fun getWeeklySteps(): Flow<Map<LocalDate, Long>> = flow {
        val today = LocalDate.now()
        val map = mutableMapOf<LocalDate, Long>()
        for (i in 6 downTo 0) {
            val day = today.minusDays(i.toLong())
            map[day] = getStepsForDay(day)
        }
        emit(map)
    }

    private fun todayRange(): Pair<Instant, Instant> {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        return Pair(start, Instant.now())
    }
}
