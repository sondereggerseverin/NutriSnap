package ch.nutrisnap.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.supabase.SyncState
import ch.nutrisnap.app.data.supabase.SyncStatusHolder
import kotlinx.coroutines.delay

/**
 * Kompakter Status-Chip statt vollflächigem Balken: hängt oben rechts (unterhalb der
 * echten Statusleiste dank windowInsetsPadding), nimmt nur so viel Platz wie der Text
 * braucht und überlagert den Inhalt statt eine eigene volle Zeile mit Farbbruch zu
 * beanspruchen. Vorher lief der Balken ohne Inset-Padding hinter die System-Statusleiste
 * und verschmolz optisch mit ihr ("13:01 ... Synchronisiert... 5G ... 35%" in einer Zeile).
 *
 * Nur bei laufender Voll-Sync (pushAll/pullAll) oder Fehler sichtbar.
 * Einzel-Pushes setzen den Status nicht mehr → kein Dauer-"Synchronisiert…".
 * Hängt der Status >45s, wird er automatisch zurückgesetzt.
 */
@Composable
fun SyncStatusBanner() {
    val status by SyncStatusHolder.status.collectAsStateWithLifecycle()

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
            SyncStatusHolder.clearStaleSyncing(0)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 4.dp, end = 12.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        AnimatedVisibility(
            visible = status.state == SyncState.SYNCING || status.state == SyncState.ERROR,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f)
        ) {
            val isError = status.state == SyncState.ERROR
            val scheme = MaterialTheme.colorScheme
            val bg = if (isError) scheme.errorContainer else scheme.secondaryContainer
            val fg = if (isError) scheme.onErrorContainer else scheme.onSecondaryContainer
            val label = if (isError) {
                "Sync fehlgeschlagen" + (status.lastError?.let { ": ${it.take(40)}" } ?: "")
            } else {
                "Synchronisiert"
            }

            Row(
                modifier = Modifier
                    .shadow(4.dp, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isError) {
                    Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(12.dp), tint = fg)
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(11.dp),
                        strokeWidth = 1.6.dp,
                        color = fg
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    color = fg,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}
