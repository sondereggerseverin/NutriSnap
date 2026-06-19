package ch.nutrisnap.app.health

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.PermissionController

/**
 * Permission-Handler für Health Connect.
 *
 * In MainActivity.kt so registrieren:
 *
 *   private lateinit var healthPermissionLauncher: ActivityResultLauncher<Set<String>>
 *
 *   override fun onCreate(savedInstanceState: Bundle?) {
 *       super.onCreate(savedInstanceState)
 *
 *       healthPermissionLauncher = registerForActivityResult(
 *           PermissionController.createRequestPermissionResultContract()
 *       ) { granted ->
 *           if (granted.containsAll(HealthConnectManager.REQUIRED_PERMISSIONS)) {
 *               // Alle Permissions erteilt
 *               healthConnectViewModel.onPermissionGranted()
 *           } else {
 *               // Teilweise oder keine Permissions
 *               // Zeige Erklärung warum es nötig ist
 *           }
 *       }
 *   }
 *
 *   // Aufrufen wenn "Verbinden" geklickt:
 *   fun requestHealthPermissions() {
 *       healthPermissionLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
 *   }
 */
object HealthConnectPermissionHandler
