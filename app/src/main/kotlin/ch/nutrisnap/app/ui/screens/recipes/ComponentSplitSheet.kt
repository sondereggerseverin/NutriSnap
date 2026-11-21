package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.nutrisnap.app.data.model.IngredientMatch
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeComponent
import ch.nutrisnap.app.domain.IngredientSections
import ch.nutrisnap.app.domain.IngredientSections.EditSection
import ch.nutrisnap.app.domain.IngredientSections.ResolvedSection
import ch.nutrisnap.app.ui.theme.NutriFont
import java.util.Locale
import kotlin.math.roundToInt

/** Marker für „neuen Abschnitt anlegen“ im Namensdialog (Umbenennen nutzt den Abschnittsindex). */
private const val DIALOG_ADD = -1

/**
 * Komponenten trennen: ein Abschnitt = eine Komponente.
 *
 * Ablauf: Abschnitte stammen aus dem Zutaten-Text (Master), Zutat antippen = verschieben,
 * pro Abschnitt nur das Kochgewicht prüfen/eintragen. Das Kochgewicht wird aus dem
 * Garfaktor früherer Messungen vorgeschlagen, damit jedes Mal derselbe Wert herauskommt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentSplitSheet(
    recipe: Recipe,
    matches: List<IngredientMatch>,
    initialComponents: List<RecipeComponent>,
    onSave: (ingredients: String, components: List<RecipeComponent>) -> Unit,
    onDismiss: () -> Unit
) {
    // Nach dem Start entscheidet nur noch der Text – Alt-Gruppen der Matches würden Abschnitte neu erzeugen.
    val plain = remember(matches) { matches.map { it.copy(componentGroup = null) } }
    var edit by remember {
        mutableStateOf(
            IngredientSections.resolve(recipe.ingredients, matches)
                .map { s -> EditSection(s.name, s.lines.map { it.text }) }
        )
    }
    var typed by remember { mutableStateOf(mapOf<String, String>()) }
    var nameDialog by remember { mutableStateOf<Int?>(null) }

    val resolved = remember(edit, plain) { IngredientSections.resolve(IngredientSections.render(edit), plain) }

    fun oldOf(name: String) = IngredientSections.findComponent(initialComponents, name)
    fun historyOf(name: String) = IngredientSections.parseFactorHistory(oldOf(name)?.factorHistory.orEmpty())

    /** Start-Kochgewicht: Garfaktor-Vorschlag, sonst früheres Gewicht, sonst Rohgewicht. */
    fun defaultG(sec: ResolvedSection): Int? =
        IngredientSections.suggestedCookedG(sec.rawG, historyOf(sec.name))
            ?: oldOf(sec.name)?.cookedWeightG?.takeIf { it > 0f }?.roundToInt()
            ?: sec.rawG.takeIf { it > 0f }?.roundToInt()

    fun typedG(name: String): Float? = typed[name]?.replace(',', '.')?.toFloatOrNull()?.takeIf { it > 0f }
    fun cookedOf(sec: ResolvedSection): Float =
        if (sec.name in typed) typedG(sec.name) ?: 0f else defaultG(sec)?.toFloat() ?: 0f

    val filled = resolved.filter { it.lines.isNotEmpty() }
    val canSave = filled.size >= 2 && filled.all { cookedOf(it) > 0f }

    fun save() {
        val text = IngredientSections.render(edit.filter { it.lines.isNotEmpty() })
        val final = IngredientSections.resolve(text, plain)
        val comps = final.mapIndexed { i, sec ->
            val old = oldOf(sec.name)
            val history = historyOf(sec.name)
            // Nur echte Eingaben sind Messungen; ein übernommener Vorschlag verändert den Garfaktor nicht.
            val measured = typedG(sec.name)?.let { IngredientSections.factorOf(sec.rawG, it) }
            IngredientSections.toComponent(
                recipeId = recipe.id,
                section = sec,
                matches = plain,
                cookedG = cookedOf(sec),
                factorHistory = if (measured != null) IngredientSections.addFactor(history, measured) else history,
                sortOrder = i,
                id = old?.id ?: 0L
            )
        }
        onSave(text, comps)
    }

    var allowSheetDismiss by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { newValue -> if (newValue == SheetValue.Hidden) allowSheetDismiss else true }
    )
    fun requestDismiss() {
        allowSheetDismiss = true
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = { requestDismiss() }, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Komponenten trennen",
                    fontWeight = FontWeight.Bold,
                    fontSize = NutriFont.TitleLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { requestDismiss() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Schliessen")
                }
            }
            Text(
                recipe.displayTitle(),
                fontSize = NutriFont.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Jeder Abschnitt wird einzeln abgewogen. Zutat antippen = in anderen Abschnitt verschieben.",
                fontSize = NutriFont.Small,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            resolved.forEachIndexed { si, sec ->
                SectionCard(
                    sec = sec,
                    otherNames = resolved.mapIndexedNotNull { i, s -> s.name.takeIf { i != si } },
                    cookedText = typed[sec.name] ?: defaultG(sec)?.toString().orEmpty(),
                    cookedG = cookedOf(sec),
                    history = historyOf(sec.name),
                    canDelete = resolved.size > 1,
                    onCookedChange = { typed = typed + (sec.name to it) },
                    onRename = { nameDialog = si },
                    onDelete = { edit = IngredientSections.deleteSection(edit, si) },
                    onMove = { lineIdx, targetName ->
                        val to = resolved.indexOfFirst { it.name == targetName }
                        if (to >= 0) edit = IngredientSections.moveLine(edit, si, lineIdx, to)
                    }
                )
            }

            TextButton(onClick = { nameDialog = DIALOG_ADD }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Abschnitt hinzufügen")
            }
            Spacer(Modifier.height(12.dp))

            Button(onClick = { save(); requestDismiss() }, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Trennung speichern")
            }
            if (!canSave) {
                Text(
                    if (filled.size < 2) "Mindestens zwei Abschnitte mit Zutaten nötig."
                    else "Jeder Abschnitt braucht ein Kochgewicht.",
                    fontSize = NutriFont.Small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (initialComponents.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { onSave(recipe.ingredients, emptyList()); requestDismiss() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Ohne Trennung – als Ganzes tracken") }
            }
            TextButton(onClick = { requestDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
        }
    }

    nameDialog?.let { idx ->
        NameDialog(
            title = if (idx == DIALOG_ADD) "Neuer Abschnitt" else "Abschnitt umbenennen",
            initial = if (idx == DIALOG_ADD) "" else resolved.getOrNull(idx)?.name.orEmpty(),
            onConfirm = { name ->
                if (idx == DIALOG_ADD) {
                    edit = IngredientSections.addSection(edit, name)
                } else {
                    val old = resolved.getOrNull(idx)?.name
                    edit = IngredientSections.renameSection(edit, idx, name)
                    if (old != null) typed[old]?.let { typed = typed - old + (name.trim() to it) }
                }
                nameDialog = null
            },
            onDismiss = { nameDialog = null }
        )
    }
}

@Composable
private fun SectionCard(
    sec: ResolvedSection,
    otherNames: List<String>,
    cookedText: String,
    cookedG: Float,
    history: List<Float>,
    canDelete: Boolean,
    onCookedChange: (String) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMove: (lineIdx: Int, targetName: String) -> Unit
) {
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sec.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = NutriFont.Subtitle,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRename, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Umbenennen")
                }
                if (canDelete) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Abschnitt entfernen",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Text(
                "Roh ${fmtNum(sec.rawG)} g · ${fmtNum(sec.kcal)} kcal",
                fontSize = NutriFont.Small,
                color = MaterialTheme.colorScheme.primary
            )
            if (sec.unmatchedCount > 0) {
                Text(
                    "${sec.unmatchedCount} Zeile(n) ohne Nährwerte",
                    fontSize = NutriFont.Small,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(6.dp))

            sec.lines.forEachIndexed { li, line ->
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    Text(
                        line.text,
                        fontSize = NutriFont.BodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = otherNames.isNotEmpty()) { menuOpen = true }
                            .padding(vertical = 8.dp)
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        otherNames.forEach { name ->
                            DropdownMenuItem(
                                text = { Text("Nach «$name»") },
                                onClick = { menuOpen = false; onMove(li, name) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = cookedText,
                onValueChange = onCookedChange,
                label = { Text("Kochgewicht (g)") },
                supportingText = {
                    val mean = IngredientSections.meanFactor(history)
                    val density = if (cookedG > 0f && sec.kcal > 0f) " · ${fmtNum(sec.kcal * 100f / cookedG)} kcal/100 g" else ""
                    Text(
                        if (mean != null) {
                            "Vorschlag aus ${history.size} Messung(en), Faktor ${"%.2f".format(Locale.ROOT, mean)}$density"
                        } else {
                            "Noch keine Messung: Rohgewicht als Start. Nach dem Kochen wiegen (ohne Topf)$density"
                        }
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
