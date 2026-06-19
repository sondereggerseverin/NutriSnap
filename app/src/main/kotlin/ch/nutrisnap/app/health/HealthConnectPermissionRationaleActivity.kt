package ch.nutrisnap.app.health

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle

/**
 * Required by Health Connect: shown when the user taps "Privacy Policy" inside
 * the Health Connect permission dialog. At minimum it must exist and be exported;
 * here we display a short explanation and then finish.
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
