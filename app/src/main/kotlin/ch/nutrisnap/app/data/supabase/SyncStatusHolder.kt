package ch.nutrisnap.app.data.supabase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/**
 * Globaler, beobachtbarer Sync-Status.
 * Banner zeigt nur SYNCING (volle Push/Pull-Runden) und ERROR.
 * Einzel-Pushes aktualisieren den Status nicht mehr (siehe pushSafely).
 */
enum class SyncState { IDLE, SYNCING, SUCCESS, ERROR }

data class SyncStatus(
    val state: SyncState = SyncState.IDLE,
    val lastSuccessAt: Instant? = null,
    val lastError: String? = null,
    val activeOps: Int = 0,
    val syncingSince: Instant? = null
)

object SyncStatusHolder {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    fun opStarted() {
        _status.update {
            val ops = it.activeOps + 1
            it.copy(
                state = SyncState.SYNCING,
                activeOps = ops,
                syncingSince = it.syncingSince ?: Instant.now()
            )
        }
    }

    fun opSucceeded() {
        _status.update {
            val remaining = (it.activeOps - 1).coerceAtLeast(0)
            it.copy(
                state = if (remaining == 0) SyncState.SUCCESS else SyncState.SYNCING,
                activeOps = remaining,
                lastSuccessAt = Instant.now(),
                lastError = if (remaining == 0) null else it.lastError,
                syncingSince = if (remaining == 0) null else it.syncingSince
            )
        }
    }

    fun opFailed(message: String?) {
        _status.update {
            val remaining = (it.activeOps - 1).coerceAtLeast(0)
            it.copy(
                state = if (remaining == 0) SyncState.ERROR else SyncState.SYNCING,
                activeOps = remaining,
                lastError = message ?: "Unbekannter Fehler",
                syncingSince = if (remaining == 0) null else it.syncingSince
            )
        }
    }

    /** SYNCING/ERROR zurücksetzen. maxSeconds=0 erzwingt sofortiges Clear. */
    fun clearStaleSyncing(maxSeconds: Long = 45) {
        _status.update {
            if (maxSeconds == 0L) {
                return@update it.copy(state = SyncState.IDLE, activeOps = 0, syncingSince = null, lastError = null)
            }
            if (it.state != SyncState.SYNCING) return@update it
            val since = it.syncingSince ?: return@update it
            val age = Instant.now().epochSecond - since.epochSecond
            if (age >= maxSeconds) {
                it.copy(state = SyncState.IDLE, activeOps = 0, syncingSince = null)
            } else it
        }
    }
}
