package ch.nutrisnap.app.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.canhub.cropper.CropImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Eigener Zuschneide-Screen mit **immer sichtbarem** Speichern-Button unten.
 *
 * Ersetzt die deprecated CropImageActivity (Toolbar unter Edge-to-Edge unsichtbar).
 * Crop/Encode laufen im Hintergrund – große Kamera-Fotos sollen die UI nicht einfrieren.
 */
@Composable
fun ComposeCropScreen(
    imageUri: Uri,
    title: String = "Foto zuschneiden",
    onCropped: (Uri) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cropView by remember { mutableStateOf<CropImageView?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel, enabled = !isSaving) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Abbrechen",
                    tint = Color.White
                )
            }
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { runCatching { cropView?.rotateImage(90) } },
                enabled = !isSaving
            ) {
                Icon(
                    Icons.Default.RotateRight,
                    contentDescription = "Drehen",
                    tint = Color.White
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AndroidView(
                factory = { ctx ->
                    CropImageView(ctx).apply {
                        guidelines = CropImageView.Guidelines.ON
                        isAutoZoomEnabled = true
                        runCatching { setImageUriAsync(imageUri) }
                        cropView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view -> cropView = view }
            )

            if (isSaving) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text("Zuschneiden…", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }

        errorText?.let { msg ->
            Text(
                text = msg,
                color = Color(0xFFFF8A80),
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onCancel,
                enabled = !isSaving,
                modifier = Modifier.weight(1f)
            ) {
                Text("Abbrechen")
            }
            Button(
                onClick = {
                    val view = cropView ?: return@Button
                    if (isSaving) return@Button
                    isSaving = true
                    errorText = null
                    scope.launch {
                        val resultUri = runCatching {
                            // View-Zugriff auf Main; Encode im Hintergrund
                            val bitmap = withContext(Dispatchers.Main.immediate) {
                                // Begrenzte Ausgabegröße → weniger RAM, weniger OOM
                                view.getCroppedImage(1600, 1600) ?: view.getCroppedImage()
                            } ?: return@runCatching null

                            withContext(Dispatchers.IO) {
                                val outFile = File(
                                    context.cacheDir,
                                    "crop_${System.currentTimeMillis()}.jpg"
                                )
                                FileOutputStream(outFile).use { fos ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                                }
                                runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    outFile
                                )
                            }
                        }.getOrElse { t ->
                            errorText = t.message ?: "Fehler beim Speichern"
                            null
                        }
                        if (resultUri != null) {
                            onCropped(resultUri)
                        } else {
                            // Fallback: Original ohne Crop, App soll nicht stecken bleiben
                            onCropped(imageUri)
                        }
                        isSaving = false
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.weight(1.4f)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Speichern", fontWeight = FontWeight.Bold)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { cropView = null }
    }
}
