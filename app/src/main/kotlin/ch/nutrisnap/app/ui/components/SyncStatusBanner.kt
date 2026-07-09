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

/**
 * Zeigt den globalen Sync-Status (SyncStatusHolder) als schmalen Banner an --
 * analog zu OfflineBanner, aber fuer "synct gerade" / "Sync fehlgeschlagen".
 *
 * Bewusst NICHT bei SUCCESS/IDLE sichtbar: ein staendig aufblitzendes
 * "Synchronisiert ✓" bei jeder kleinen Diary-Aenderung waere mehr Rauschen
 * als Nutzen. Der Banner meldet sich nur wenn etwas gerade laeuft (SYNCING)
 * oder schiefgegangen ist (ERROR) -- also genau dann, wenn der Nutzer vorher
 * keine Chance hatte zu wissen, was passiert.
 */
@Composable
fun SyncStatusBanner() {
    val status by SyncStatusHolder.status.collectAsState()

    AnimatedVisibility(
        visible = status.state == SyncState.SYNCING || status.state == SyncState.ERROR,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut()
    ) {
        val isError = status.state == SyncState.ERROR
        val bgColor = if (isError) Color(0xFFB00020) else Color(0xFF1E6091)
        val label = if (isError) {
            "Sync fehlgeschlagen" + (status.lastError?.let { ": $it" } ?: "")
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
