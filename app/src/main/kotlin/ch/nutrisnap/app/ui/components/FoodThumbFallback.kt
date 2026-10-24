package ch.nutrisnap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.utils.FoodEmoji

/**
 * Einheitlicher Bild-Fallback für Lebensmittel ohne Foto (Suche, Hinzufügen, Vorlagen):
 * passendes Emoji zum Namen, sonst der Anfangsbuchstabe. Gleiche Optik wie im Tagebuch.
 */
@Composable
fun FoodThumbFallback(
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp)
) {
    val emoji = remember(name) { FoodEmoji.forName(name) }
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (emoji != null) {
            Text(emoji, fontSize = (size.value * 0.55f).sp)
        } else {
            Text(
                name.firstOrNull()?.uppercase() ?: "?",
                fontSize = (size.value * 0.33f).sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
