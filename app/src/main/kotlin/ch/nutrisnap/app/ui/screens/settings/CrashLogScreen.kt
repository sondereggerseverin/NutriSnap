package ch.nutrisnap.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.utils.CrashLogger
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var content by remember { mutableStateOf(CrashLogger.read(context)) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Absturzprotokoll") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (content.isNotBlank()) {
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(content))
                            scope.launch { snackbar.showSnackbar("Kopiert") }
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Kopieren")
                        }
                        IconButton(onClick = {
                            CrashLogger.clear(context)
                            content = ""
                        }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Löschen")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (content.isBlank()) {
                Text(
                    "Kein Absturz/ANR seit dem letzten Löschen aufgezeichnet.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Neuester Eintrag zuerst (CRASH / ANR / ERROR). Zum Melden kopieren und einfügen.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                SelectionContainer(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(content, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
