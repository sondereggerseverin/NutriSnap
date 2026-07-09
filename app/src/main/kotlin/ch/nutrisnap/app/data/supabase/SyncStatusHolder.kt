package ch.nutrisnap.app.data.supabase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/**
 * Globaler, beobachtbarer Sync-Status.
 *
 * Vorher lief Push (Repositories.pushSafely) und Pull (SyncManager.pullAll)
 * komplett unsichtbar im Hintergrund -- ein fehlgeschlagener Sync (offline,
 * RLS-Policy, etc.) war fuer den Nutzer nicht von "alles ok" zu unterscheiden.
 *
 * Dieses Objekt wird von beiden Seiten (Push + Pull) aktualisiert und kann von
 * der UI per StateFlow beobachtet werden, um z.B. einen "Synchronisiert..."-
 * Banner oder eine Fehlermeldung anzuzeigen.
 */
enum class SyncState { IDLE, SYNCING, SUCCESS, ERROR }

data class SyncStatus(
    val state: SyncState = SyncState.IDLE,
    val lastSuccessAt: Instant? = null,
    val lastError: String? = null,
    // Zaehlt laufende Push/Pull-Operationen. Mehrere Repos koennen gleichzeitig
    // pushen (z.B. Diary + Recipe kurz nacheinander) -- "Syncing" soll erst
    // verschwinden wenn WIRKLICH nichts mehr laeuft, nicht nach der ersten
    // abgeschlossenen von mehreren parallelen Operationen.
    val activeOps: Int = 0
)

object SyncStatusHolder {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    fun opStarted() {
        _status.update { it.copy(state = SyncState.SYNCING, activeOps = it.activeOps + 1) }
    }

    fun opSucceeded() {
        _status.update {
            val remaining = (it.activeOps - 1).coerceAtLeast(0)
            it.copy(
                state = if (remaining == 0) SyncState.SUCCESS else SyncState.SYNCING,
                activeOps = remaining,
                lastSuccessAt = Instant.now(),
                lastError = if (remaining == 0) null else it.lastError
            )
        }
    }

    fun opFailed(message: String?) {
        _status.update {
            val remaining = (it.activeOps - 1).coerceAtLeast(0)
            it.copy(
                state = if (remaining == 0) SyncState.ERROR else SyncState.SYNCING,
                activeOps = remaining,
                lastError = message ?: "Unbekannter Fehler"
            )
        }
    }
}
