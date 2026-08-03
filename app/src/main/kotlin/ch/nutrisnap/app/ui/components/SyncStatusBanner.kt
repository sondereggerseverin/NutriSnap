package ch.nutrisnap.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.supabase.SyncState
import ch.nutrisnap.app.data.supabase.SyncStatusHolder
import kotlinx.coroutines.delay

/**
 * Banner nur bei laufender Voll-Sync (pushAll/pullAll) oder Fehler.
 * Einzel-Pushes setzen den Status nicht mehr → kein Dauer-"Synchronisiert…".
 * Hängt der Status >45s, wird er automatisch zurückgesetzt.
 */
@Composable
fun SyncStatusBanner() {
    val status by SyncStatusHolder.status.collectAsState()

    // Stuck-Guard: alle 10s prüfen
    LaunchedEffect(status.state, status.activeOps) {
        if (status.state == SyncState.SYNCING) {
            while (true) {
                delay(10_000)
                SyncStatusHolder.clearStaleSyncing(45)
            }
        }
    }

    // ERROR nach 8s ausblenden (zurück zu IDLE)
    LaunchedEffect(status.state) {
        if (status.state == SyncState.ERROR) {
            delay(8_000)
            SyncStatusHolder.clearStaleSyncing(0) // force clear via helper if ERROR
            // Explizit: activeOps 0 + IDLE when error aged
        }
    }

    AnimatedVisibility(
        visible = status.state == SyncState.SYNCING || status.state == SyncState.ERROR,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut()
    ) {
        val isError = status.state == SyncState.ERROR
        val bgColor = if (isError) Color(0xFFB00020) else Color(0xFF1E6091)
        val label = if (isError) {
            "Sync fehlgeschlagen" + (status.lastError?.let { ": ${it.take(60)}" } ?: "")
        } else {
            "Synchronisiert…"
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .padding(vertical = 6.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isError) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}
