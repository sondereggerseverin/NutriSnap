package ch.nutrisnap.app.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import ch.nutrisnap.app.utils.ImageDecodeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * Reiner Compose-Zuschneider – **ohne** canhub CropImageView.
 *
 * canhub in AndroidView blockierte den Main-Thread in applyImageMatrix/onLayout.
 * Hier: Decode + Crop + Encode auf IO, UI nur Compose Canvas + Gesten.
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
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var isRotating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // Crop relativ zur Bildfläche (0..1)
    var cropL by remember { mutableFloatStateOf(0.08f) }
    var cropT by remember { mutableFloatStateOf(0.08f) }
    var cropR by remember { mutableFloatStateOf(0.92f) }
    var cropB by remember { mutableFloatStateOf(0.92f) }

    LaunchedEffect(imageUri) {
        isLoading = true
        errorText = null
        val bmp = withContext(Dispatchers.IO) {
            ImageDecodeUtils.decodeUri(context, imageUri, maxEdgePx = 1024, preferRgb565 = true)
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
            sourceBitmap?.let { b -> runCatching { if (!b.isRecycled) b.recycle() } }
            sourceBitmap = null
        }
    }

    fun rotatePreview() {
        val src = sourceBitmap ?: return
        if (isRotating || isSaving) return
        isRotating = true
        scope.launch {
            val rotated = withContext(Dispatchers.IO) {
                runCatching {
                    val m = Matrix().apply { postRotate(90f) }
                    Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                }.getOrNull()
            }
            if (rotated != null) {
                val old = sourceBitmap
                sourceBitmap = rotated
                if (old != null && old !== rotated) {
                    runCatching { if (!old.isRecycled) old.recycle() }
                }
                // Nach 90°-Drehung Crop zurücksetzen
                cropL = 0.08f; cropT = 0.08f; cropR = 0.92f; cropB = 0.92f
            }
            isRotating = false
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
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel, enabled = !isSaving) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Abbrechen", tint = Color.White)
            }
            Text(
                title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { rotatePreview() },
                enabled = !isSaving && !isRotating && sourceBitmap != null
            ) {
                Icon(Icons.Default.RotateRight, "Drehen", tint = Color.White)
            }
        }

        errorText?.let { msg ->
            Text(
                msg,
                color = Color(0xFFFFAB91),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            val bmp = sourceBitmap
            if (bmp != null && !isLoading) {
                CropCanvas(
                    bitmap = bmp,
                    cropL = cropL,
                    cropT = cropT,
                    cropR = cropR,
                    cropB = cropB,
                    onCropChange = { l, t, r, b ->
                        cropL = l; cropT = t; cropR = r; cropB = b
                    },
                    modifier = Modifier.fillMaxSize()
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

            if (isSaving || isRotating) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (isRotating) "Drehen…" else "Speichern…",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        Text(
            "Ecken ziehen · Fläche verschieben",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 4.dp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
                    val src = sourceBitmap ?: return@Button
                    if (isSaving) return@Button
                    isSaving = true
                    errorText = null
                    val l = cropL; val t = cropT; val r = cropR; val b = cropB
                    scope.launch {
                        val out = withContext(Dispatchers.IO) {
                            runCatching {
                                val cropped = cropNormalized(src, l, t, r, b)
                                val outFile = File(context.cacheDir, "crop_${System.currentTimeMillis()}.jpg")
                                FileOutputStream(outFile).use { fos ->
                                    cropped.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                                }
                                if (cropped !== src) {
                                    runCatching { if (!cropped.isRecycled) cropped.recycle() }
                                }
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    outFile
                                )
                            }.getOrNull()
                        }
                        if (out != null) {
                            onCropped(out)
                        } else {
                            errorText = "Zuschneiden fehlgeschlagen – Original wird verwendet"
                            onCropped(imageUri)
                        }
                        isSaving = false
                    }
                },
                enabled = !isSaving && !isRotating && sourceBitmap != null,
                modifier = Modifier.weight(1.4f)
            ) {
                Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Speichern", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CropCanvas(
    bitmap: Bitmap,
    cropL: Float,
    cropT: Float,
    cropR: Float,
    cropB: Float,
    onCropChange: (Float, Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val handlePx = with(density) { 28.dp.toPx() }
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val boxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val srcW = bitmap.width.toFloat()
        val srcH = bitmap.height.toFloat()
        val scale = min(boxW / srcW, boxH / srcH)
        val drawW = srcW * scale
        val drawH = srcH * scale
        val offsetX = (boxW - drawW) / 2f
        val offsetY = (boxH - drawH) / 2f

        fun normToPx(l: Float, t: Float, r: Float, b: Float): Rect =
            Rect(
                offsetX + l * drawW,
                offsetY + t * drawH,
                offsetX + r * drawW,
                offsetY + b * drawH
            )

        fun clampCrop(l: Float, t: Float, r: Float, b: Float): FloatArray {
            var nl = l.coerceIn(0f, 0.95f)
            var nt = t.coerceIn(0f, 0.95f)
            var nr = r.coerceIn(0.05f, 1f)
            var nb = b.coerceIn(0.05f, 1f)
            if (nr - nl < 0.08f) nr = (nl + 0.08f).coerceAtMost(1f)
            if (nb - nt < 0.08f) nb = (nt + 0.08f).coerceAtMost(1f)
            return floatArrayOf(nl, nt, nr, nb)
        }

        var activeHandle by remember { mutableIntStateOf(-1) }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(drawW, drawH, offsetX, offsetY, bitmap.width, bitmap.height) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val rect = normToPx(cropL, cropT, cropR, cropB)
                            val corners = listOf(
                                Offset(rect.left, rect.top),
                                Offset(rect.right, rect.top),
                                Offset(rect.left, rect.bottom),
                                Offset(rect.right, rect.bottom)
                            )
                            activeHandle = corners.indexOfFirst { c ->
                                (pos - c).getDistance() <= handlePx
                            }
                            if (activeHandle < 0 && rect.contains(pos)) activeHandle = 4
                        },
                        onDragEnd = { activeHandle = -1 },
                        onDragCancel = { activeHandle = -1 },
                        onDrag = { change, drag ->
                            change.consume()
                            if (activeHandle < 0 || drawW <= 0f || drawH <= 0f) return@detectDragGestures
                            val dx = drag.x / drawW
                            val dy = drag.y / drawH
                            when (activeHandle) {
                                0 -> {
                                    val c = clampCrop(cropL + dx, cropT + dy, cropR, cropB)
                                    onCropChange(c[0], c[1], c[2], c[3])
                                }
                                1 -> {
                                    val c = clampCrop(cropL, cropT + dy, cropR + dx, cropB)
                                    onCropChange(c[0], c[1], c[2], c[3])
                                }
                                2 -> {
                                    val c = clampCrop(cropL + dx, cropT, cropR, cropB + dy)
                                    onCropChange(c[0], c[1], c[2], c[3])
                                }
                                3 -> {
                                    val c = clampCrop(cropL, cropT, cropR + dx, cropB + dy)
                                    onCropChange(c[0], c[1], c[2], c[3])
                                }
                                4 -> {
                                    var nl = cropL + dx
                                    var nt = cropT + dy
                                    var nr = cropR + dx
                                    var nb = cropB + dy
                                    val w = nr - nl
                                    val h = nb - nt
                                    if (nl < 0f) { nl = 0f; nr = w }
                                    if (nt < 0f) { nt = 0f; nb = h }
                                    if (nr > 1f) { nr = 1f; nl = 1f - w }
                                    if (nb > 1f) { nb = 1f; nt = 1f - h }
                                    onCropChange(nl, nt, nr, nb)
                                }
                            }
                        }
                    )
                }
        ) {
            // Bild
            drawImage(
                image = imageBitmap,
                dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
                dstSize = IntSize(drawW.toInt().coerceAtLeast(1), drawH.toInt().coerceAtLeast(1))
            )

            val rect = normToPx(cropL, cropT, cropR, cropB)
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, Offset.Zero, Size(size.width, rect.top.coerceAtLeast(0f)))
            drawRect(
                dim,
                Offset(0f, rect.bottom),
                Size(size.width, (size.height - rect.bottom).coerceAtLeast(0f))
            )
            drawRect(dim, Offset(0f, rect.top), Size(rect.left.coerceAtLeast(0f), rect.height))
            drawRect(
                dim,
                Offset(rect.right, rect.top),
                Size((size.width - rect.right).coerceAtLeast(0f), rect.height)
            )
            drawRect(
                Color.White,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
                style = Stroke(width = 2.dp.toPx())
            )
            val hs = 10.dp.toPx()
            listOf(
                Offset(rect.left, rect.top),
                Offset(rect.right, rect.top),
                Offset(rect.left, rect.bottom),
                Offset(rect.right, rect.bottom)
            ).forEach { c ->
                drawCircle(Color.White, radius = hs, center = c)
            }
        }
    }
}

private fun cropNormalized(src: Bitmap, l: Float, t: Float, r: Float, b: Float): Bitmap {
    val left = (l * src.width).toInt().coerceIn(0, src.width - 1)
    val top = (t * src.height).toInt().coerceIn(0, src.height - 1)
    val right = (r * src.width).toInt().coerceIn(left + 1, src.width)
    val bottom = (b * src.height).toInt().coerceIn(top + 1, src.height)
    return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
}
