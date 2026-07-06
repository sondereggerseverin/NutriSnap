package ch.nutrisnap.app.ui.screens.scan

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Bündelt die drei bisher getrennten Scan-Einstiege (Barcode, Foto-Kalorienschätzung,
// Nährwerttabelle) an einer Stelle, statt sie über Settings + Diary-Sheet verteilt zu haben.
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
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            }
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ScanOptionCard(
                icon = Icons.Default.QrCodeScanner,
                title = "Barcode scannen",
                subtitle = "Verpacktes Produkt per Barcode suchen und ins Tagebuch eintragen",
                onClick = onBarcode
            )
            ScanOptionCard(
                icon = Icons.Default.PhotoCamera,
                title = "Essen fotografieren",
                subtitle = "KI schätzt Kalorien & Makros anhand eines Fotos",
                onClick = onPhotoEstimate
            )
            ScanOptionCard(
                icon = Icons.Default.CameraAlt,
                title = "Nährwerttabelle fotografieren",
                subtitle = "Werte von der Verpackung automatisch auslesen",
                onClick = onLabelPhoto
            )
        }
    }
}

@Composable
private fun ScanOptionCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
