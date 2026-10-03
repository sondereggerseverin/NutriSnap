package ch.nutrisnap.app.ui.components

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Mikrofon-Button → System-Sprachdialog → erkannten Text an [onResult].
 * Nutzer kann den Text danach noch editieren, bevor die KI läuft.
 */
@Composable
fun VoiceInputButton(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prompt: String = "Was hast du gegessen?"
) {
    val context = LocalContext.current

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val matches = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            .orEmpty()
        val text = matches.firstOrNull()?.trim().orEmpty()
        if (text.isNotEmpty()) onResult(text)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchSpeech(context, prompt, speechLauncher::launch)
        } else {
            Toast.makeText(
                context,
                "Mikrofon-Berechtigung nötig für Sprach-Eingabe",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    IconButton(
        onClick = {
            val hasPerm = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                launchSpeech(context, prompt, speechLauncher::launch)
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        enabled = enabled,
        modifier = modifier
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = "Sprechen",
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

private fun launchSpeech(
    context: android.content.Context,
    prompt: String,
    start: (Intent) -> Unit
) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.GERMAN.toString())
        putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }
    val canHandle = intent.resolveActivity(context.packageManager) != null
    if (!canHandle) {
        Toast.makeText(
            context,
            "Keine Spracherkennung auf diesem Gerät verfügbar",
            Toast.LENGTH_SHORT
        ).show()
        return
    }
    runCatching { start(intent) }.onFailure {
        Toast.makeText(context, "Spracherkennung konnte nicht gestartet werden", Toast.LENGTH_SHORT)
            .show()
    }
}
