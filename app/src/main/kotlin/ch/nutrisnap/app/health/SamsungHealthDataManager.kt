package ch.nutrisnap.app.health

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.AggregateRequest
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import java.time.LocalDate

private const val TAG = "SamsungHealthDataMgr"

/**
 * Wraps the Samsung Health Data SDK (com.samsung.android.sdk.health.data), which talks
 * directly to the Samsung Health app instead of going through Health Connect.
 *
 * Why this exists: Samsung Health reliably writes StepsRecord to Health Connect but very
 * often never writes ActiveCaloriesBurnedRecord or TotalCaloriesBurnedRecord at all — a
 * known Samsung Health / Health Connect gap that no amount of fallback-tier logic on the
 * Health Connect side can fix (see HealthConnectManager tiers 1-4). Reading active calories
 * directly from Samsung Health via this SDK sidesteps Health Connect entirely.
 *
 * Requires Android 10+ (API 29) and, currently, Samsung Health Data SDK "Developer Mode"
 * enabled on-device (Samsung Health app -> Labs/Advanced -> Developer Mode). Production
 * distribution would require formal Samsung Partner registration.
 *
 * API surface below was verified directly against the shipped AAR bytecode
 * (samsung-health-data-api-1.1.0.aar), not from documentation/memory, since the SDK is
 * partner-only and not fully mirrored in public docs:
 *  - HealthDataStore.aggregateData(request) is the actual suspend method name (not "aggregate")
 *  - DataType.StepsType.TOTAL is the steps aggregate operation (not "COUNT")
 *  - DataType.ActivitySummaryType.TOTAL_ACTIVE_CALORIES_BURNED yields a Float, not Long
 *  - LocalTimeFilter.of() takes java.time.LocalDateTime, not Instant
 *  - AggregateOperation.requestBuilder must be cast to AggregateRequest.LocalTimeBuilder<T>
 *    to call setLocalTimeFilter(); the base Builder interface only exposes build()
 */
class SamsungHealthDataManager(private val context: Context) {

    private var store: HealthDataStore? = null

    companion object {
        val REQUIRED_PERMISSIONS: Set<Permission> = setOf(
            Permission.of(DataType.ActivitySummaryType(), AccessType.READ),
            Permission.of(DataType.StepsType(), AccessType.READ)
        )

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    /** Lazily creates the HealthDataStore. Returns null if unsupported or unreachable. */
    private suspend fun getStore(): HealthDataStore? {
        if (!isSupported()) return null
        store?.let { return it }
        return runCatching { HealthDataService.getStore(context) }
            .onFailure { Log.e(TAG, "getStore failed", it) }
            .getOrNull()
            ?.also { store = it }
    }

    /** Whether both ActivitySummaryType and StepsType read permissions are granted. */
    suspend fun hasPermissions(): Boolean {
        val s = getStore() ?: return false
        return runCatching {
            s.getGrantedPermissions(REQUIRED_PERMISSIONS).containsAll(REQUIRED_PERMISSIONS)
        }.onFailure { Log.e(TAG, "hasPermissions check failed", it) }
            .getOrDefault(false)
    }

    /**
     * Requests permissions via Samsung Health's own consent dialog. Must be called with a
     * foreground Activity. Returns true only if all required permissions ended up granted.
     */
    suspend fun requestPermissions(activity: Activity): Boolean {
        val s = getStore() ?: return false
        return try {
            s.requestPermissions(REQUIRED_PERMISSIONS, activity).containsAll(REQUIRED_PERMISSIONS)
        } catch (e: ResolvablePlatformException) {
            // e.g. Samsung Health app needs an update or the user needs to accept its own
            // data-processing consent first. If resolvable, kick off Samsung's own resolution UI.
            Log.w(TAG, "requestPermissions: resolvable issue", e)
            if (e.hasResolution) runCatching { e.resolve(activity) }
            false
        } catch (e: HealthDataException) {
            Log.e(TAG, "requestPermissions failed", e)
            false
        }
    }

    /** Active calories burned for [date] read directly from Samsung Health, or null. */
    suspend fun readActiveCalories(date: LocalDate): Double? {
        val s = getStore() ?: return null
        if (!hasPermissions()) return null
        return runCatching {
            val start = date.atStartOfDay()
            val end = date.plusDays(1).atStartOfDay()
            @Suppress("UNCHECKED_CAST")
            val builder = DataType.ActivitySummaryType.TOTAL_ACTIVE_CALORIES_BURNED
                .requestBuilder as AggregateRequest.LocalTimeBuilder<Float>
            val request = builder
                .setLocalTimeFilter(LocalTimeFilter.of(start, end))
                .build()
            val response = s.aggregateData(request)
            response.dataList.firstOrNull()?.value?.toDouble()
        }.onFailure {
            Log.e(TAG, "readActiveCalories failed for $date", it)
        }.getOrNull()
    }

    /** Step count for [date] read directly from Samsung Health, or null. */
    suspend fun readSteps(date: LocalDate): Long? {
        val s = getStore() ?: return null
        if (!hasPermissions()) return null
        return runCatching {
            val start = date.atStartOfDay()
            val end = date.plusDays(1).atStartOfDay()
            @Suppress("UNCHECKED_CAST")
            val builder = DataType.StepsType.TOTAL
                .requestBuilder as AggregateRequest.LocalTimeBuilder<Long>
            val request = builder
                .setLocalTimeFilter(LocalTimeFilter.of(start, end))
                .build()
            val response = s.aggregateData(request)
            response.dataList.firstOrNull()?.value
        }.onFailure {
            Log.e(TAG, "readSteps failed for $date", it)
        }.getOrNull()
    }
}
