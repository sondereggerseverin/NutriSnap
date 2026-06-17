package ch.nutrisnap.app.ui.screens.recipes

import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.model.Recipe
import androidx.activity.compose.LocalActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookingModeScreen(recipe: Recipe, onBack: () -> Unit) {

    // Wakelock: Bildschirm bleibt an waehrend des Kochens
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val steps = remember(recipe.instructions) {
        recipe.instructions
            .split("
")
            .filter { it.isNotBlank() }
            .mapIndexed { i, step -> step.trim().removePrefix("${i + 1}.").trim() }
    }

    val ingredients = remember(recipe.ingredients) {
        recipe.ingredients.split("
").filter { it.isNotBlank() }
    }

    var currentStep by remember { mutableStateOf(0) }
    var completedSteps by remember { mutableStateOf(setOf<Int>()) }
    var showIngredients by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kochmodus", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.Close, "Beenden") } },
                actions = {
                    IconButton(onClick = { showIngredients = !showIngredients }) {
                        Icon(
                            if (showIngredients) Icons.Default.ListAlt else Icons.Default.ShoppingCart,
                            "Zutaten"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // Fortschrittsbalken
            LinearProgressIndicator(
                progress = { if (steps.isEmpty()) 0f else (currentStep + 1).toFloat() / steps.size },
                modifier = Modifier.fillMaxWidth().height(4.dp)
            )

            if (showIngredients) {
                // Zutaten-Ansicht
                Column(
                    modifier = Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text("Zutaten", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    ingredients.forEach { ingredient ->
                        Row(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text("• ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(ingredient, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            } else if (steps.isEmpty()) {
                // Keine Schritte
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("😕", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Keine Zubereitungsschritte gefunden",
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else {
                // Kochschritte
                Column(
                    modifier = Modifier.weight(1f).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Schritt ${currentStep + 1} von ${steps.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(24.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = steps.getOrElse(currentStep) { "" },
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 20.sp,
                            modifier = Modifier.padding(24.dp),
                            lineHeight = 32.sp
                        )
                    }
                    Spacer(Modifier.height(24.dp))

                    // Schritt als erledigt markieren
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = currentStep in completedSteps,
                            onCheckedChange = { checked ->
                                completedSteps = if (checked)
                                    completedSteps + currentStep
                                else
                                    completedSteps - currentStep
                            }
                        )
                        Text(
                            "Erledigt",
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (currentStep in completedSteps)
                                TextDecoration.LineThrough else TextDecoration.None
                        )
                    }
                }

                // Navigation
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = { if (currentStep > 0) currentStep-- },
                        enabled = currentStep > 0,
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Zurueck")
                    }
                    Spacer(Modifier.width(16.dp))
                    if (currentStep < steps.size - 1) {
                        Button(
                            onClick = { currentStep++ },
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) {
                            Text("Weiter")
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.ArrowForward, null)
                        }
                    } else {
                        Button(
                            onClick = onBack,
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            Text("Fertig!")
                            Spacer(Modifier.width(8.dp))
                            Text("🎉")
                        }
                    }
                }
            }
        }
    }
}
