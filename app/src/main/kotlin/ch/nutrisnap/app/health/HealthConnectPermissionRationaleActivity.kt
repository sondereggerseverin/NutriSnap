package ch.nutrisnap.app.health

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle

/**
 * Required by Health Connect SDK.
 * Must handle action: androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE
 * Also targeted by the activity-alias for VIEW_PERMISSION_USAGE (Android 14+).
 */
class HealthConnectPermissionRationaleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AlertDialog.Builder(this)
            .setTitle("Health Connect – Datenschutz")
            .setMessage(
                "NutriSnap liest Schritte, Kalorien, Gewicht, Schlaf und Herzfrequenz " +
                "aus Health Connect, um dein Ernährungs- und Aktivitätstagebuch " +
                "automatisch zu ergänzen. Die Daten verlassen dein Gerät nicht."
            )
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
