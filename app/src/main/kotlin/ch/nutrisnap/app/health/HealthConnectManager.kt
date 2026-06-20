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

    /** Steps today */
    fun getTodaysSteps(): Flow<Long> = flow {
        val (start, end) = todayRange()
        val resp = client.readRecords(
            ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(start, end))
        )
        emit(resp.records.sumOf { it.count })
    }

    /**
     * Total burned calories today.
     * Samsung Health writes workout calories to TotalCaloriesBurnedRecord.
     * We take the max of Total vs Active to avoid double-counting.
     */
    fun getTodaysActiveCalories(): Flow<Double> = flow {
        val (start, end) = todayRange()

        val activeResp = client.readRecords(
            ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, TimeRangeFilter.between(start, end))
        )
        val activeCals = activeResp.records.sumOf { it.energy.inKilocalories }

        val totalResp = client.readRecords(
            ReadRecordsRequest(TotalCaloriesBurnedRecord::class, TimeRangeFilter.between(start, end))
        )
        val totalCals = totalResp.records.sumOf { it.energy.inKilocalories }

        // Samsung Health: totalCals includes BMR, so subtract BMR estimate (~1800/day → 75/h)
        // If total > active significantly, use total - BMR portion; otherwise use active
        val result = when {
            totalCals > activeCals * 1.5 -> {
                // Total likely includes BMR - estimate activity portion only
                val hoursElapsed = java.time.Duration.between(start, end).toMinutes() / 60.0
                val bmrPortion = hoursElapsed * 75.0  // ~1800 kcal/day BMR / 24h
                (totalCals - bmrPortion).coerceAtLeast(activeCals)
            }
            totalCals > 0 -> totalCals
            else -> activeCals
        }
        emit(result)
    }

    /** Latest weight reading from last 30 days */
    fun getLatestWeight(): Flow<Double?> = flow {
        val resp = client.readRecords(
            ReadRecordsRequest(
                WeightRecord::class,
                TimeRangeFilter.between(
                    Instant.now().minusSeconds(30L * 24 * 3600),
                    Instant.now()
                )
            )
        )
        emit(resp.records.lastOrNull()?.weight?.inKilograms)
    }

    /** Last night sleep: 18:00 yesterday → 12:00 today */
    fun getLastNightSleep(): Flow<Long> = flow {
        val from = LocalDate.now().minusDays(1).atTime(18, 0)
            .atZone(ZoneId.systemDefault()).toInstant()
        val to   = LocalDate.now().atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant()
        val resp = client.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, to))
        )
        emit(resp.records.sumOf {
            java.time.Duration.between(it.startTime, it.endTime).toMinutes()
        })
    }

    /** Average heart rate today */
    fun getTodaysAvgHeartRate(): Flow<Long?> = flow {
        val (start, end) = todayRange()
        val resp = client.readRecords(
            ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(start, end))
        )
        val all = resp.records.flatMap { it.samples }
        emit(if (all.isEmpty()) null else all.map { it.beatsPerMinute }.average().toLong())
    }

    /** Steps grouped by day for the last 7 days */
    fun getWeeklySteps(): Flow<Map<LocalDate, Long>> = flow {
        val weekStart = LocalDate.now().minusDays(6)
            .atStartOfDay(ZoneId.systemDefault()).toInstant()
        val resp = client.readRecords(
            ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(weekStart, Instant.now()))
        )
        emit(resp.records.groupBy {
            it.startTime.atZone(ZoneId.systemDefault()).toLocalDate()
        }.mapValues { (_, v) -> v.sumOf { it.count } })
    }

    private fun todayRange(): Pair<Instant, Instant> {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        return Pair(start, Instant.now())
    }
}
