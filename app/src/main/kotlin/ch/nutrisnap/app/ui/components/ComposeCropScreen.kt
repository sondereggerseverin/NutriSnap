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
import androidx.compose.runtime.LaunchedEffect
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
import ch.nutrisnap.app.utils.ImageDecodeUtils
import com.canhub.cropper.CropImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream

/**
 * Zuschneide-Screen mit **immer sichtbarem Speichern-Button**.
 *
 * Wichtig gegen ANR/OOM:
 * - Bild wird zuerst auf max. 1600px skaliert (IO-Thread)
 * - In die CropImageView per [CropImageView.setImageBitmap] (kein erneutes Full-Decode)
 * - Speichern: synchrone Crop-API nur auf begrenztem Bitmap, Encode auf IO
 * - Timeout + Fallback auf Original, falls etwas hängen bleibt
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
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // Einmalig skaliert laden – nie das 12-MP-Original in die View
    LaunchedEffect(imageUri) {
        isLoading = true
        errorText = null
        val bmp = withContext(Dispatchers.IO) {
            ImageDecodeUtils.decodeUri(context, imageUri, maxEdgePx = 1600)
        }
        if (bmp == null) {
            errorText = "Bild konnte nicht geladen werden"
            isLoading = false
            return@LaunchedEffect
        }
        sourceBitmap = bmp
        isLoading = false
    }

    DisposableEffect(Unit) {
        onDispose {
            cropView = null
            sourceBitmap?.let { b -> runCatching { if (!b.isRecycled) b.recycle() } }
            sourceBitmap = null
        }
    }

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
                enabled = !isSaving && sourceBitmap != null
            ) {
                Icon(Icons.Default.RotateRight, contentDescription = "Drehen", tint = Color.White)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val bmp = sourceBitmap
            if (bmp != null && !isLoading) {
                AndroidView(
                    factory = { ctx ->
                        CropImageView(ctx).apply {
                            guidelines = CropImageView.Guidelines.ON
                            isAutoZoomEnabled = true
                            // Bitmap bereits skaliert – kein setImageUriAsync (lädt sonst nochmal)
                            setImageBitmap(bmp)
                            cropView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        cropView = view
                    }
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text("Bild wird vorbereitet…", color = Color.White, fontSize = 14.sp)
                    }
                }
            }

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
                        Text("Speichern…", color = Color.White, fontSize = 14.sp)
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
                    val view = cropView
                    if (view == null || isSaving) return@Button
                    isSaving = true
                    errorText = null
                    scope.launch {
                        val out = withTimeoutOrNull(12_000L) {
                            runCatching {
                                // Begrenztes Bitmap – getCroppedImage auf schon kleinem Source
                                val cropped = withContext(Dispatchers.Main.immediate) {
                                    view.getCroppedImage(1280, 1280) ?: view.getCroppedImage()
                                } ?: return@runCatching null
                                withContext(Dispatchers.IO) {
                                    val outFile = File(
                                        context.cacheDir,
                                        "crop_${System.currentTimeMillis()}.jpg"
                                    )
                                    FileOutputStream(outFile).use { fos ->
                                        cropped.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                                    }
                                    runCatching { if (!cropped.isRecycled) cropped.recycle() }
                                    FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        outFile
                                    )
                                }
                            }.getOrNull()
                        }
                        if (out != null) {
                            onCropped(out)
                        } else {
                            // Timeout/Fehler: Original (bereits skaliert, falls Cache) übernehmen
                            errorText = "Zuschneiden dauerte zu lange – Original wird verwendet"
                            onCropped(imageUri)
                        }
                        isSaving = false
                    }
                },
                enabled = !isSaving && sourceBitmap != null,
                modifier = Modifier.weight(1.4f)
            ) {
                Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Speichern", fontWeight = FontWeight.Bold)
            }
        }
    }
}
