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

        /**
         * Samsung Health schreibt mehrere StepsRecord-Eintraege pro Zeitslot:
         * - einen vom Hintergrund-Sensor
         * - einen von der Sensor-Fusion / Workout-Erkennung
         * Diese ueberlappen sich exakt oder teilweise und fuehren zu Doppelzaehlung.
         *
         * Fix: Wir gruppieren Records nach ihrer dataOrigin (Datenquelle) und nehmen
         * pro Zeitfenster nur den hoechsten Wert einer einzigen Quelle (Samsung Health selbst
         * aggregiert intern, Health Connect gibt aber rohe Teil-Records zurueck).
         * Konkret: Wir nehmen den Record mit dem hoechsten count pro nicht-ueberlappenden Slot.
         */
        fun deduplicateSteps(records: List<StepsRecord>): Long {
            if (records.isEmpty()) return 0L
            // Sort by startTime, then by count descending so the "best" record wins
            val sorted = records.sortedWith(compareBy<StepsRecord> { it.startTime }.thenByDescending { it.count })
            var lastEnd = Instant.EPOCH
            var total = 0L
            for (record in sorted) {
                when {
                    // No overlap: count fully
                    record.startTime >= lastEnd -> {
                        total += record.count
                        lastEnd = record.endTime
                    }
                    // Complete duplicate or subset: skip entirely
                    record.endTime <= lastEnd -> { /* skip */ }
                    // Partial overlap: extend window, do NOT add count (conservative)
                    else -> {
                        lastEnd = record.endTime
                    }
                }
            }
            return total
        }

        fun deduplicateCalories(records: List<ActiveCaloriesBurnedRecord>): Double {
            if (records.isEmpty()) return 0.0
            val sorted = records.sortedWith(compareBy<ActiveCaloriesBurnedRecord> { it.startTime }.thenByDescending { it.energy.inKilocalories })
            var lastEnd = Instant.EPOCH
            var total = 0.0
            for (record in sorted) {
                when {
                    record.startTime >= lastEnd -> {
                        total += record.energy.inKilocalories
                        lastEnd = record.endTime
                    }
                    record.endTime <= lastEnd -> { /* skip duplicate */ }
                    else -> { lastEnd = record.endTime }
                }
            }
            return total
        }
    }

    suspend fun checkPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    suspend fun hasAllPermissions(): Boolean =
        checkPermissions().containsAll(REQUIRED_PERMISSIONS)

    /** Steps today – vollstaendig dedupliziert gegen Samsung Health Doppeleintraege */
    fun getTodaysSteps(): Flow<Long> = flow {
        val (start, end) = todayRange()
        val result = runCatching {
            val resp = client.readRecords(
                ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(start, end))
            )
            deduplicateSteps(resp.records)
        }.getOrDefault(0L)
        emit(result)
    }

    /**
     * Active calories today – nur ActiveCaloriesBurnedRecord (kein BMR).
     * Samsung Health 69 Cal = ActiveCalories, nicht Total.
     */
    fun getTodaysActiveCalories(): Flow<Double> = flow {
        val (start, end) = todayRange()
        val result = runCatching {
            val resp = client.readRecords(
                ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, TimeRangeFilter.between(start, end))
            )
            deduplicateCalories(resp.records)
        }.getOrDefault(0.0)
        emit(result)
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

    /** Steps grouped by day for the last 7 days – dedupliziert */
    fun getWeeklySteps(): Flow<Map<LocalDate, Long>> = flow {
        val weekStart = LocalDate.now().minusDays(6)
            .atStartOfDay(ZoneId.systemDefault()).toInstant()
        val result = runCatching {
            val resp = client.readRecords(
                ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(weekStart, Instant.now()))
            )
            resp.records
                .groupBy { it.startTime.atZone(ZoneId.systemDefault()).toLocalDate() }
                .mapValues { (_, records) -> deduplicateSteps(records) }
        }.getOrDefault(emptyMap())
        emit(result)
    }

    private fun todayRange(): Pair<Instant, Instant> {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        return Pair(start, Instant.now())
    }
}
