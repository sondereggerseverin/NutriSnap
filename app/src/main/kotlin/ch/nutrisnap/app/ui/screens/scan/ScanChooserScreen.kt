package ch.nutrisnap.app.ui.screens.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanChooserScreen(
    onBarcode: () -> Unit,
    onPhotoEstimate: () -> Unit,
    onLabelPhoto: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Scannen") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                }
            }
        )
    }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(NutriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(NutriSpacing.md)
        ) {
            ScanOptionCard(
                icon = Icons.Default.QrCodeScanner,
                title = "Barcode scannen",
                subtitle = "Verpacktes Produkt per Barcode suchen und ins Tagebuch eintragen",
                color = MacroColors.protein,
                onClick = onBarcode
            )
            ScanOptionCard(
                icon = Icons.Default.PhotoCamera,
                title = "Essen fotografieren",
                subtitle = "KI schätzt Kalorien & Makros anhand eines Fotos",
                color = MacroColors.calories,
                onClick = onPhotoEstimate
            )
            ScanOptionCard(
                icon = Icons.Default.CameraAlt,
                title = "Nährwerttabelle fotografieren",
                subtitle = "Werte von der Verpackung automatisch auslesen",
                color = MacroColors.carbs,
                onClick = onLabelPhoto
            )
        }
    }
}

@Composable
private fun ScanOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            Modifier.padding(NutriSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(NutriRadius.md))
                    .background(color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, Modifier.size(24.dp), tint = color)
            }
            Spacer(Modifier.width(NutriSpacing.lg))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
