package ch.nutrisnap.app.health

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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zentraler Manager für alle Health Connect Operationen.
 * Kapselt den HealthConnectClient und stellt typsichere Methoden bereit.
 */
@Singleton
class HealthConnectManager @Inject constructor(
    private val healthConnectClient: HealthConnectClient
) {
    companion object {
        /** Alle benötigten Permissions auf einen Blick */
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
        )
    }

    /** Prüft welche Permissions bereits erteilt wurden */
    suspend fun checkPermissions(): Set<String> =
        healthConnectClient.permissionController.getGrantedPermissions()

    /** Gibt true zurück wenn alle nötigen Permissions vorhanden */
    suspend fun hasAllPermissions(): Boolean =
        checkPermissions().containsAll(REQUIRED_PERMISSIONS)

    /** Schritte für heute */
    fun getTodaysSteps(): Flow<Long> = flow {
        val (start, end) = todayRange()
        val response = healthConnectClient.readRecords(
            ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(start, end))
        )
        emit(response.records.sumOf { it.count })
    }

    /** Aktiv verbrannte Kalorien heute (kcal) */
    fun getTodaysActiveCalories(): Flow<Double> = flow {
        val (start, end) = todayRange()
        val response = healthConnectClient.readRecords(
            ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, TimeRangeFilter.between(start, end))
        )
        emit(response.records.sumOf { it.energy.inKilocalories })
    }

    /** Letztes bekanntes Gewicht (kg) */
    fun getLatestWeight(): Flow<Double?> = flow {
        val response = healthConnectClient.readRecords(
            ReadRecordsRequest(
                WeightRecord::class,
                TimeRangeFilter.between(
                    Instant.now().minusSeconds(30L * 24 * 3600), // letzte 30 Tage
                    Instant.now()
                )
            )
        )
        emit(response.records.lastOrNull()?.weight?.inKilograms)
    }

    /** Schlaf letzte Nacht (Minuten) */
    fun getLastNightSleep(): Flow<Long> = flow {
        val yesterdayStart = LocalDate.now().minusDays(1)
            .atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant()
        val todayNoon = LocalDate.now()
            .atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()

        val response = healthConnectClient.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(yesterdayStart, todayNoon))
        )
        val totalMinutes = response.records.sumOf { session ->
            java.time.Duration.between(session.startTime, session.endTime).toMinutes()
        }
        emit(totalMinutes)
    }

    /** Durchschnittlicher Ruhepuls heute (bpm) */
    fun getTodaysAvgHeartRate(): Flow<Long?> = flow {
        val (start, end) = todayRange()
        val response = healthConnectClient.readRecords(
            ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(start, end))
        )
        val allSamples = response.records.flatMap { it.samples }
        emit(if (allSamples.isEmpty()) null else allSamples.map { it.beatsPerMinute }.average().toLong())
    }

    /** Schritte der letzten 7 Tage (Map: Datum → Schritte) */
    fun getWeeklySteps(): Flow<Map<LocalDate, Long>> = flow {
        val weekStart = LocalDate.now().minusDays(6)
            .atStartOfDay(ZoneId.systemDefault()).toInstant()
        val now = Instant.now()

        val response = healthConnectClient.readRecords(
            ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(weekStart, now))
        )

        val grouped = response.records.groupBy {
            it.startTime.atZone(ZoneId.systemDefault()).toLocalDate()
        }.mapValues { entry -> entry.value.sumOf { it.count } }

        emit(grouped)
    }

    private fun todayRange(): Pair<Instant, Instant> {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        val end = Instant.now()
        return Pair(start, end)
    }
}
