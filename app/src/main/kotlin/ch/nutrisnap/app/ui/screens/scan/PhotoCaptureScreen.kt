package ch.nutrisnap.app.ui.screens.scan

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Wiederverwendbarer Capture-Screen fuer EIN Foto (im Gegensatz zum
 * BarcodeScannerScreen, der laufend live analysiert). Wird sowohl fuer den
 * Essens-Scan (Kalorienschaetzung) als auch fuer das Fotografieren von
 * Naehrwerttabellen verwendet. Bietet sowohl Kamera-Aufnahme als auch
 * Auswahl eines bestehenden Bilds aus der Galerie.
 */
@Composable
fun PhotoCaptureScreen(
    title: String,
    instructions: String,
    onPhotoCaptured: (Bitmap) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            isCapturing = true
            val bitmap = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                    android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
            } catch (e: Exception) {
                null
            }
            isCapturing = false
            bitmap?.let { onPhotoCaptured(it) }
        }
    }

    LaunchedEffect(Unit) {
        hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    if (!hasPermission) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Kamera-Zugriff wird benötigt, um Fotos aufzunehmen.")
            Spacer(Modifier.height(16.dp))
            Text("Du kannst aber auch ein Foto aus der Galerie wählen:", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Icon(Icons.Default.PhotoLibrary, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Aus Galerie wählen")
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onNavigateBack) { Text("Zurück") }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    imageCapture = capture
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture
                        )
                    } catch (e: Exception) { /* Kamera nicht verfuegbar */ }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            Column {
                Text(title, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text(instructions, color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp)
            }
        }

        IconButton(
            onClick = onNavigateBack,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück", tint = MaterialTheme.colorScheme.onPrimary)
        }

        if (isCapturing) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        // Galerie-Button (unten links)
        FloatingActionButton(
            onClick = {
                if (isCapturing) return@FloatingActionButton
                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 40.dp),
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = "Aus Galerie wählen")
        }

        // Kamera-Auslöser (unten mittig)
        FloatingActionButton(
            onClick = {
                val capture = imageCapture ?: return@FloatingActionButton
                if (isCapturing) return@FloatingActionButton
                isCapturing = true
                capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val rotation = image.imageInfo.rotationDegrees
                        image.close()
                        if (bitmap != null && rotation != 0) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        }
                        isCapturing = false
                        bitmap?.let { onPhotoCaptured(it) }
                    }
                    override fun onError(exc: ImageCaptureException) {
                        isCapturing = false
                    }
                })
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
        ) {
            Icon(Icons.Default.Camera, contentDescription = "Foto aufnehmen")
        }
    }
}
