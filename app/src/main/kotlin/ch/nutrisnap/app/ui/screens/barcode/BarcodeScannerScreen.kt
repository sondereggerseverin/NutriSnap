package ch.nutrisnap.app.ui.screens.barcode

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import ch.nutrisnap.app.domain.BarcodeStabilityGate
import ch.nutrisnap.app.domain.BarcodeValidator
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn

/**
 * NEU: Barcode-Scanner Screen mit ML Kit.
 *
 * SETUP:
 *  1. In build.gradle.kts:
 *       implementation("com.google.mlkit:barcode-scanning:17.3.0")
 *       implementation("androidx.camera:camera-camera2:1.3.4")
 *       implementation("androidx.camera:camera-lifecycle:1.3.4")
 *       implementation("androidx.camera:camera-view:1.3.4")
 *
 *  2. In AndroidManifest.xml:
 *       <uses-permission android:name="android.permission.CAMERA" />
 *       <uses-feature android:name="android.hardware.camera" android:required="false" />
 *
 *  3. Navigation-Eintrag in MainActivity.kt:
 *       composable("barcode") {
 *           BarcodeScannerScreen(
 *               onBarcodeDetected = { barcode -> navController.navigate("food_detail/$barcode") },
 *               onNavigateBack = { navController.popBackStack() }
 *           )
 *       }
 *
 * VERWENDUNG: Im FoodSearchScreen einen FAB oder Button "Barcode scannen" hinzufügen,
 * der zu "barcode" navigiert.
 */
@Composable
fun BarcodeScannerScreen(
    onBarcodeDetected: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(true) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }

    // Permission-Check
    LaunchedEffect(Unit) {
        hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    if (!hasPermission) {
        CameraPermissionRequest(onPermissionGranted = { hasPermission = true })
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Kamera-Vorschau
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onCameraReady = { camera = it },
            onBarcodeDetected = { barcode ->
                if (isScanning) {
                    isScanning = false // einmalig scannen, dann pausieren
                    onBarcodeDetected(barcode)
                }
            }
        )

        // Overlay: Scan-Rahmen
        ScanOverlay(modifier = Modifier.fillMaxSize())

        // Info-Text
        Text(
            text = "Barcode im Bild halten · Antippen zum Fokussieren",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
        )

        // Taschenlampe (nur wenn die Kamera einen Blitz hat) – hilft bei dunklen Küchen/Autos
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            IconButton(
                onClick = {
                    torchOn = !torchOn
                    camera?.cameraControl?.enableTorch(torchOn)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = if (torchOn) "Taschenlampe aus" else "Taschenlampe an",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        // Zurück-Button
        IconButton(
            onClick = onNavigateBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Zurück",
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier = Modifier,
    onCameraReady: (Camera) -> Unit = {},
    onBarcodeDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    // Nur Lebensmittel-Codes (EAN-13/EAN-8/UPC-A). Ohne Filter werden auch Code 128/ITF/Codabar
    // erkannt und liefern auf gewölbten Flaschen Fehllesungen (z. B. "104812").
    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A
                )
                .build()
        )
    }
    val stabilityGate = remember { BarcodeStabilityGate(required = 2) }
    var boundProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // Kamera, Scanner und Executor beim Verlassen freigeben. Der Screen wird auch inline in
    // Sheets genutzt; ohne unbind blieb die Kamera an den Activity-Lifecycle gebunden.
    DisposableEffect(Unit) {
        onDispose {
            boundProvider?.unbindAll()
            barcodeScanner.close()
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // Standard-Analyse läuft nur in ~640x480: zu grob für Barcodes auf gewölbten Flaschen.
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(executor) { imageProxy ->
                            processImageProxy(barcodeScanner, stabilityGate, imageProxy, onBarcodeDetected)
                        }
                    }

                boundProvider = cameraProvider
                try {
                    cameraProvider.unbindAll()
                    val cam = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                    onCameraReady(cam)

                    // Tap-to-Focus: gewölbte Flaschen und Nahdistanz brauchen oft manuellen Fokus
                    previewView.setOnTouchListener { v, event ->
                        if (event.action == android.view.MotionEvent.ACTION_UP) {
                            val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                            cam.cameraControl.startFocusAndMetering(
                                FocusMeteringAction.Builder(point).build()
                            )
                            v.performClick()
                        }
                        true
                    }
                } catch (e: Exception) {
                    // Kamera nicht verfügbar
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = modifier
    )
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processImageProxy(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    gate: BarcodeStabilityGate,
    imageProxy: ImageProxy,
    onBarcodeDetected: (String) -> Unit
) {
    val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            // Erster Treffer mit gültiger Länge + Prüfziffer, der in 2 Frames in Folge identisch ist
            barcodes.asSequence()
                .mapNotNull { it.rawValue }
                .firstOrNull { BarcodeValidator.isValidFoodBarcode(it) }
                ?.let { gate.offer(it) }
                ?.let { onBarcodeDetected(it) }
        }
        .addOnCompleteListener { imageProxy.close() }
}

@Composable
private fun ScanOverlay(modifier: Modifier = Modifier) {
    // Einfacher Scan-Rahmen – kann mit Canvas weiter gestaltet werden
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(250.dp)
                .align(Alignment.Center)
                // Rahmen-Styling hier ergänzen
        )
    }
}

@Composable
private fun CameraPermissionRequest(onPermissionGranted: () -> Unit) {
    val context = LocalContext.current
    // true = System-Dialog wurde schon mal gezeigt und abgelehnt (shouldShowRequestPermissionRationale
    // wird dann false, weil "nicht mehr fragen" aktiv ist) -> direkt zu den Einstellungen leiten.
    var permanentlyDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onPermissionGranted()
        } else {
            val activity = context as? android.app.Activity
            val canAskAgain = activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ?: true
            permanentlyDenied = !canAskAgain
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Kamera-Zugriff wird benötigt, um Barcodes zu scannen.")
        Spacer(Modifier.height(16.dp))
        if (permanentlyDenied) {
            Text(
                "Der Zugriff wurde dauerhaft abgelehnt. Bitte in den App-Einstellungen aktivieren.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null)
                )
                context.startActivity(intent)
            }) {
                Text("Zu den Einstellungen")
            }
        } else {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Zugriff erlauben")
            }
        }
    }
}
