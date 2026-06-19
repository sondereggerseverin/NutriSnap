package ch.nutrisnap.app.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.nutrisnap.app.ui.theme.NutriSnapTheme

/**
 * Required by Health Connect: shown when user taps "Why does this app need these permissions?"
 */
class HealthConnectPermissionRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NutriSnapTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Warum braucht NutriSnap diese Berechtigungen?",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "NutriSnap liest deine Schritte, verbrauchte Kalorien, " +
                            "Gewicht, Schlaf und Herzfrequenz aus Samsung Health / Health Connect, " +
                            "um dein tägliches Kalorienziel automatisch anzupassen. " +
                            "Deine Daten verlassen nie das Gerät.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { finish() }) { Text("Verstanden") }
                    }
                }
            }
        }
    }
}
