package ch.nutrisnap.app.health

import android.content.Context
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

class HealthConnectManager(context: Context) {

    private val client: HealthConnectClient =
        HealthConnectClient.getOrCreate(context.applicationContext)

    companion object {
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
        )

        /** Check if Health Connect is available on this device */
        fun isAvailable(context: Context): Boolean =
            HealthConnectClient.getSdkStatus(context) ==
                HealthConnectClient.SDK_AVAILABLE
    }

    suspend fun checkPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    suspend fun hasAllPermissions(): Boolean =
        checkPermissions().containsAll(REQUIRED_PERMISSIONS)

    fun getTodaysSteps(): Flow<Long> = flow {
        val (start, end) = todayRange()
        val resp = client.readRecords(
            ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(start, end))
        )
        emit(resp.records.sumOf { it.count })
    }

    fun getTodaysActiveCalories(): Flow<Double> = flow {
        val (start, end) = todayRange()
        val resp = client.readRecords(
            ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, TimeRangeFilter.between(start, end))
        )
        emit(resp.records.sumOf { it.energy.inKilocalories })
    }

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

    fun getLastNightSleep(): Flow<Long> = flow {
        val yesterdayEvening = LocalDate.now().minusDays(1)
            .atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant()
        val todayNoon = LocalDate.now()
            .atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
        val resp = client.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(yesterdayEvening, todayNoon))
        )
        emit(resp.records.sumOf { java.time.Duration.between(it.startTime, it.endTime).toMinutes() })
    }

    fun getTodaysAvgHeartRate(): Flow<Long?> = flow {
        val (start, end) = todayRange()
        val resp = client.readRecords(
            ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(start, end))
        )
        val all = resp.records.flatMap { it.samples }
        emit(if (all.isEmpty()) null else all.map { it.beatsPerMinute }.average().toLong())
    }

    fun getWeeklySteps(): Flow<Map<LocalDate, Long>> = flow {
        val weekStart = LocalDate.now().minusDays(6)
            .atStartOfDay(ZoneId.systemDefault()).toInstant()
        val resp = client.readRecords(
            ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(weekStart, Instant.now()))
        )
        val grouped = resp.records.groupBy {
            it.startTime.atZone(ZoneId.systemDefault()).toLocalDate()
        }.mapValues { e -> e.value.sumOf { it.count } }
        emit(grouped)
    }

    private fun todayRange(): Pair<Instant, Instant> {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        return Pair(start, Instant.now())
    }
}
