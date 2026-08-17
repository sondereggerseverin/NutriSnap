package ch.nutrisnap.app.ui.screens.scan

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.theme.CropperDefaults
import ch.nutrisnap.app.ui.theme.KEY_TOGGLE_CROPPER_THEME_COLOR
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import java.util.concurrent.Executors

/**
 * Capture-Screen für ein Foto (Kamera oder Galerie).
 *
 * [enableCrop] nur für Rezept-Fotos true setzen. Nährwerttabellen und
 * Rezept-Extraktion (OCR) laufen ohne Zuschneiden – der Cropper versteckt
 * sonst oft den Weiter-Button und stört die Texterkennung.
 */
@Composable
fun PhotoCaptureScreen(
    title: String,
    instructions: String,
    onPhotoCaptured: (Bitmap) -> Unit,
    onNavigateBack: () -> Unit,
    enableCrop: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var captureHint by remember { mutableStateOf<String?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var cameraInfo by remember { mutableStateOf<CameraInfo?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    val prefs by context.notifDataStore.data.collectAsState(initial = null)
    val useThemeCropper = prefs?.get(KEY_TOGGLE_CROPPER_THEME_COLOR) ?: true
    val themePrimary = MaterialTheme.colorScheme.primary

    fun decodeBitmap(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    } catch (_: Exception) {
        null
    }

    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (!result.isSuccessful) {
            isCapturing = false
            return@rememberLauncherForActivityResult
        }
        val croppedUri = result.uriContent
        isCapturing = false
        if (croppedUri != null) {
            decodeBitmap(croppedUri)?.let { onPhotoCaptured(it) }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (enableCrop) {
            isCapturing = true
            cropLauncher.launch(
                CropImageContractOptions(
                    uri = uri,
                    cropImageOptions = CropperDefaults.options(
                        title = "Foto zuschneiden",
                        useTheme = useThemeCropper,
                        themePrimary = themePrimary
                    )
                )
            )
        } else {
            decodeBitmap(uri)?.let { onPhotoCaptured(it) }
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
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    // Scharfes Foto statt schnellstes (unscharfes) Frame
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(92)
                        .build()
                    imageCapture = capture
                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture
                        )
                        cameraControl = camera.cameraControl
                        cameraInfo = camera.cameraInfo
                        // Einmal zentral fokussieren, sobald die Kamera läuft
                        previewView.post {
                            focusCenter(previewView, camera.cameraControl)
                        }
                    } catch (_: Exception) { /* Kamera nicht verfügbar */ }
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
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text(instructions, color = Color.White, fontSize = 13.sp)
            }
        }

        IconButton(
            onClick = onNavigateBack,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück", tint = Color.White)
        }

        if (isCapturing) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(
                    captureHint ?: "Foto wird aufgenommen…",
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }

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

        FloatingActionButton(
            onClick = {
                val capture = imageCapture ?: return@FloatingActionButton
                if (isCapturing) return@FloatingActionButton
                isCapturing = true
                captureHint = "Fokussiere…"

                fun doTakePicture() {
                    captureHint = "Foto…"
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
                                bitmap = Bitmap.createBitmap(
                                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                                )
                            }
                            mainExecutor.execute {
                                isCapturing = false
                                captureHint = null
                                bitmap?.let { onPhotoCaptured(it) }
                            }
                        }
                        override fun onError(exc: ImageCaptureException) {
                            mainExecutor.execute {
                                isCapturing = false
                                captureHint = null
                            }
                        }
                    })
                }

                // Kurz AF, dann Foto – Analyse läuft danach auf dem Bild (kein Halten)
                val ctrl = cameraControl
                if (ctrl != null) {
                    val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(2, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val future = runCatching { ctrl.startFocusAndMetering(action) }.getOrNull()
                    if (future != null) {
                        // Max. ~1.2 s auf Fokus warten, dann immer auslösen
                        val focused = java.util.concurrent.atomic.AtomicBoolean(false)
                        future.addListener({
                            if (focused.compareAndSet(false, true)) doTakePicture()
                        }, mainExecutor)
                        mainExecutor.execute {
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (focused.compareAndSet(false, true)) doTakePicture()
                            }, 1200L)
                        }
                    } else {
                        doTakePicture()
                    }
                } else {
                    doTakePicture()
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
        ) {
            Icon(Icons.Default.Camera, contentDescription = "Foto aufnehmen")
        }
    }
}

/** Einmaliger AF auf Bildmitte (nach Kamera-Start). */
private fun focusCenter(previewView: PreviewView, control: CameraControl) {
    val factory = previewView.meteringPointFactory
    val point = factory.createPoint(previewView.width / 2f, previewView.height / 2f)
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
        .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    runCatching { control.startFocusAndMetering(action) }
}
