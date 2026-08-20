package ch.nutrisnap.app.domain

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Einfache Zähler: wie oft der On-Device-Food-Scan-Fallback genutzt wurde.
 * Nur lokal in DataStore – kein Server, kein PII.
 */
object OnDeviceScanStats {

    private val KEY_ON_DEVICE_SCANS = intPreferencesKey("on_device_food_scan_count")
    private val KEY_ON_DEVICE_FAILS = intPreferencesKey("on_device_food_scan_fail_count")

    suspend fun recordSuccess(context: Context) {
        context.applicationContext.notifDataStore.edit { prefs ->
            val n = prefs[KEY_ON_DEVICE_SCANS] ?: 0
            prefs[KEY_ON_DEVICE_SCANS] = n + 1
        }
    }

    suspend fun recordFailure(context: Context) {
        context.applicationContext.notifDataStore.edit { prefs ->
            val n = prefs[KEY_ON_DEVICE_FAILS] ?: 0
            prefs[KEY_ON_DEVICE_FAILS] = n + 1
        }
    }

    suspend fun snapshot(context: Context): Snapshot {
        val data = context.applicationContext.notifDataStore.data.first()
        return Snapshot(
            successCount = data[KEY_ON_DEVICE_SCANS] ?: 0,
            failCount = data[KEY_ON_DEVICE_FAILS] ?: 0
        )
    }

    fun observe(context: Context) =
        context.applicationContext.notifDataStore.data.map { data ->
            Snapshot(
                successCount = data[KEY_ON_DEVICE_SCANS] ?: 0,
                failCount = data[KEY_ON_DEVICE_FAILS] ?: 0
            )
        }

    data class Snapshot(val successCount: Int, val failCount: Int) {
        val totalAttempts: Int get() = successCount + failCount
    }
}
