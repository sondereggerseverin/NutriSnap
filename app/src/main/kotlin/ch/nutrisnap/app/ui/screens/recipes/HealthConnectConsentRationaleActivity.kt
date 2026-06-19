package ch.nutrisnap.app.ui.screens.recipes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.nutrisnap.app.ui.theme.NutriSnapTheme

class HealthConnectConsentRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NutriSnapTheme { RationaleScreen { finish() } } }
    }
}

@Composable
private fun RationaleScreen(onClose: () -> Unit) {
    Scaffold { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🥗", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(16.dp))
            Text("Warum braucht NutriSnap Health Connect?", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text("NutriSnap liest Schritte, Kalorien, Gewicht, Schlaf und Herzfrequenz – nur lokal, keine Daten verlassen dein Gerät.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(32.dp))
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Verstanden") }
        }
    }
}
