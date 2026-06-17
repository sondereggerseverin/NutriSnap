package ch.nutrisnap.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OfflineBanner(isOnline: Boolean) {
    AnimatedVisibility(
        visible = !isOnline,
        enter = slideInVertically() + fadeIn(),
        exit  = slideOutVertically() + fadeOut()
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().background(Color(0xFFB00020)).padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Kein Internet - Offline-Modus aktiv",
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium
            )
        }
    }
}
