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
import androidx.health.connect.client.records.MealType as HcMealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import ch.nutrisnap.app.data.model.MealType
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

    private val originFilter = setOf(DataOrigin(DATA_ORIGIN_PACKAGE))

    private fun Record.isFromGarmin(): Boolean =
        metadata.dataOrigin.packageName == DATA_ORIGIN_PACKAGE

    companion object {
        private const val TAG = "HealthConnectManager"

        // Einzige Datenquelle für Uhr-Daten (Schritte, Aktiv-kcal, Schlaf, Puls):
        // Garmin Connect. Andere Apps/Uhren in Health Connect (z. B. Samsung Health
        // mit einer fremden Uhr) werden bewusst ignoriert.
        private const val DATA_ORIGIN_PACKAGE = "com.garmin.android.apps.connectmobile"

        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        )

        // Ohne diese Permission liefert Health Connect nur die letzten 30 Tage zurück.
        // Bewusst NICHT in REQUIRED_PERMISSIONS gebündelt, sondern separat im Analyse-Tab
        // angefragt (nur wenn der Nutzer per Kalender/Monat weiter als 30 Tage zurückgeht),
        // damit der normale "Health Connect verbinden"-Flow nicht davon abhängt.
        val HISTORY_PERMISSION = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

        /** Optional: Tagebuch → NutritionRecord. */
        val WRITE_NUTRITION_PERMISSION = HealthPermission.getWritePermission(NutritionRecord::class)

        /**
         * Permissions, die der "Verbinden"-Dialog anfragt: Lesen (Pflicht-Set) + Nutrition-Schreiben.
         * [hasAllPermissions] bleibt auf [REQUIRED_PERMISSIONS], damit bestehende Nutzer ohne
         * Write-Grant nicht als "getrennt" gelten.
         */
        val REQUESTABLE_PERMISSIONS: Set<String> = REQUIRED_PERMISSIONS + WRITE_NUTRITION_PERMISSION

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

    /** True, wenn Health Connect auch Daten älter als 30 Tage zurückgeben darf. */
    suspend fun hasHistoryPermission(): Boolean =
        checkPermissions().contains(HISTORY_PERMISSION)

    /** True, wenn NutriSnap NutritionRecords schreiben darf. */
    suspend fun hasWriteNutritionPermission(): Boolean =
        checkPermissions().contains(WRITE_NUTRITION_PERMISSION)

    /**
     * Schreibt einen Mahlzeiten-Eintrag als [NutritionRecord] nach Health Connect.
     * Best-effort: fehlende Permission oder API-Fehler → Result.failure, ohne Exception
     * an den Aufrufer durchzureichen (Tagebuch-Speichern darf nicht scheitern).
     *
     * Werte in g bzw. kcal; 0/negativ → Feld weglassen (HC akzeptiert null).
     * Zeitraum: 15 Minuten um [at] (HC verlangt start < end).
     */
    suspend fun writeNutritionEntry(
        name: String,
        mealType: MealType,
        energyKcal: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
        fiberG: Double = 0.0,
        sugarG: Double = 0.0,
        saturatedFatG: Double = 0.0,
        sodiumG: Double = 0.0,
        at: Instant = Instant.now()
    ): Result<Unit> = runCatching {
        if (!hasWriteNutritionPermission()) {
            error("Keine WRITE_NUTRITION-Berechtigung")
        }
        val zone = ZoneId.systemDefault()
        val offset = zone.rules.getOffset(at)
        val end = at.plusSeconds(15 * 60)
        val endOffset = zone.rules.getOffset(end)

        fun massOrNull(g: Double): Mass? =
            if (g.isFinite() && g > 0.0) Mass.grams(g) else null

        val record = NutritionRecord(
            startTime = at,
            startZoneOffset = offset,
            endTime = end,
            endZoneOffset = endOffset,
            metadata = Metadata.manualEntry(),
            name = name.take(100).ifBlank { null },
            energy = if (energyKcal.isFinite() && energyKcal > 0.0) Energy.kilocalories(energyKcal) else null,
            protein = massOrNull(proteinG),
            totalCarbohydrate = massOrNull(carbsG),
            totalFat = massOrNull(fatG),
            dietaryFiber = massOrNull(fiberG),
            sugar = massOrNull(sugarG),
            saturatedFat = massOrNull(saturatedFatG),
            sodium = massOrNull(sodiumG),
            mealType = when (mealType) {
                MealType.BREAKFAST -> HcMealType.MEAL_TYPE_BREAKFAST
                MealType.LUNCH -> HcMealType.MEAL_TYPE_LUNCH
                MealType.DINNER -> HcMealType.MEAL_TYPE_DINNER
                MealType.SNACK -> HcMealType.MEAL_TYPE_SNACK
            }
        )
        client.insertRecords(listOf(record))
        Unit
    }.onFailure {
        Log.w(TAG, "writeNutritionEntry failed for \"$name\": ${it.message}")
    }

    /** Steps for a specific day */
    suspend fun getStepsForDay(date: LocalDate): Long {
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val end   = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        return runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    dataOriginFilter = originFilter
                )
            )[StepsRecord.COUNT_TOTAL] ?: 0L
        }.onFailure {
            Log.e(TAG, "getStepsForDay failed for $date", it)
        }.getOrDefault(0L)
    }

    /**
     * Aktiv-kcal (nur Bewegung, ohne Grundumsatz) eines Tages aus Garmin-Records.
     * Zuordnung strikt nach startTime-Kalendertag — so landet eine Radfahrt, die um
     * 22:00 startet, am Starttag und nicht anteilig am Folgetag.
     * null = Garmin hat für den Tag nichts geschrieben (z. B. noch nicht synchronisiert);
     * bewusst unterschieden von 0.0 = "wirklich 0 kcal".
     */
    suspend fun getActiveCaloriesForDay(date: LocalDate): Double? {
        val zone = ZoneId.systemDefault()
        val windowStart = date.minusDays(1).atStartOfDay(zone).toInstant()
        val windowEnd = if (date == LocalDate.now()) Instant.now()
                        else date.plusDays(2).atStartOfDay(zone).toInstant()
        return runCatching {
            val records = client.readRecords(
                ReadRecordsRequest(
                    ActiveCaloriesBurnedRecord::class,
                    TimeRangeFilter.between(windowStart, windowEnd)
                )
            ).records.filter {
                it.isFromGarmin() && it.startTime.atZone(zone).toLocalDate() == date
            }
            if (records.isEmpty()) null else records.sumOf { it.energy.inKilocalories }
        }.onFailure {
            Log.e(TAG, "getActiveCaloriesForDay failed for $date", it)
        }.getOrNull()
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
            resp.records.filter { it.isFromGarmin() }.sumOf {
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
     * AggregateRequest dedupliziert überlappende Records innerhalb der Garmin-Quelle.
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
            val all = resp.records.filter { it.isFromGarmin() }.flatMap { it.samples }
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
