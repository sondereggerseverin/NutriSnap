package ch.nutrisnap.app.ui.screens.recipes

import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import kotlin.math.round

/**
 * Extrahiert eine fuehrende Mengenangabe am Zeilenanfang (z.B. "200g", "2 ", "1,5 EL",
 * "1/2 Zwiebel") und skaliert sie um [factor]. Zeilen ohne erkennbare Zahl am Anfang
 * (z.B. "Salz nach Geschmack") werden unveraendert zurueckgegeben.
 */
private val leadingQuantityRegex = Regex("""^\s*(\d+/\d+|\d+(?:[.,]\d+)?)(\s*)(.*)$""", RegexOption.DOT_MATCHES_ALL)

private fun scaleIngredientLine(line: String, factor: Double): String {
    val match = leadingQuantityRegex.find(line) ?: return line
    val (numStr, spacer, rest) = match.destructured
    val value = if (numStr.contains("/")) {
        val parts = numStr.split("/")
        val n = parts[0].toDoubleOrNull() ?: return line
        val d = parts[1].toDoubleOrNull() ?: return line
        if (d == 0.0) return line
        n / d
    } else {
        numStr.replace(",", ".").toDoubleOrNull() ?: return line
    }
    val scaled = value * factor
    val rounded = round(scaled * 100) / 100.0
    val formatted = if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        // Zwei Nachkommastellen max, unnoetige Nullen weg, deutsches Komma statt Punkt
        rounded.toString().trimEnd('0').trimEnd('.').replace(".", ",")
    }
    return "$formatted$spacer$rest"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookingModeScreen(recipe: Recipe, onBack: () -> Unit) {

    // Wakelock: Bildschirm bleibt an waehrend des Kochens
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val steps = remember(recipe.instructions) {
        recipe.instructions
            .split("\n")
            .filter { it.isNotBlank() }
            .mapIndexed { i, step -> step.trim().removePrefix("${i + 1}.").trim() }
    }

    val ingredients = remember(recipe.ingredients) {
        recipe.ingredients.split("\n").filter { it.isNotBlank() }
    }

    var currentStep by remember { mutableStateOf(0) }
    var completedSteps by remember { mutableStateOf(setOf<Int>()) }
    var showIngredients by remember { mutableStateOf(false) }

    // Portionsskalierung: Basis ist recipe.servings (mind. 1, falls schlecht befuellt).
    val baseServings = remember(recipe.servings) { recipe.servings.coerceAtLeast(1) }
    var servings by remember(baseServings) { mutableStateOf(baseServings) }
    val scaledIngredients = remember(ingredients, servings, baseServings) {
        val factor = servings.toDouble() / baseServings.toDouble()
        ingredients.map { scaleIngredientLine(it, factor) }
    }

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

                    // Portionen-Stepper
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Portionen", style = MaterialTheme.typography.bodyLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedIconButton(
                                onClick = { if (servings > 1) servings-- },
                                enabled = servings > 1,
                                modifier = Modifier.size(40.dp)
                            ) { Icon(Icons.Default.Remove, "Weniger Portionen", modifier = Modifier.size(20.dp)) }
                            Text(
                                "$servings",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            OutlinedIconButton(onClick = { servings++ }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.Add, "Mehr Portionen", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    if (servings != baseServings) {
                        Text(
                            "Original: $baseServings Portion${if (baseServings == 1) "" else "en"} – Mengen unten angepasst",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    scaledIngredients.forEach { ingredient ->
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
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
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
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

