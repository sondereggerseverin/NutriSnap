package ch.nutrisnap.app.ui.screens.security

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import ch.nutrisnap.app.security.BiometricHelper

@Composable
fun BiometricLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!BiometricHelper.isBiometricAvailable(context)) { onUnlocked(); return@LaunchedEffect }
        triggerBiometric(context, onUnlocked) { error -> errorMessage = error }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Gesperrt", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text("NutriSnap", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Bitte authentifiziere dich um fortzufahren",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
        }
        Button(
            onClick = { triggerBiometric(context, onUnlocked) { e -> errorMessage = e } },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("Fingerabdruck / PIN verwenden") }
    }
}

private fun triggerBiometric(context: android.content.Context, onSuccess: () -> Unit, onError: (String) -> Unit) {
    val activity = context as? FragmentActivity ?: return
    BiometricHelper.authenticate(activity = activity, onSuccess = onSuccess, onError = onError)
}
