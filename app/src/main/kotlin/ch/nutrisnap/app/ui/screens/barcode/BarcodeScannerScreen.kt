package ch.nutrisnap.app.ui.screens.barcode

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
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
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

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
            text = "Halte die Kamera auf einen Barcode",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
        )

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
    onBarcodeDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember { BarcodeScanning.getClient() }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(executor) { imageProxy ->
                            processImageProxy(barcodeScanner, imageProxy, onBarcodeDetected)
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
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
    imageProxy: ImageProxy,
    onBarcodeDetected: (String) -> Unit
) {
    val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull { it.format != Barcode.FORMAT_UNKNOWN }
                ?.rawValue
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
