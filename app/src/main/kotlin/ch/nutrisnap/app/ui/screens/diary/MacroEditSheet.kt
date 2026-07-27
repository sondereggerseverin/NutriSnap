package ch.nutrisnap.app.ui.screens.diary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.MacroField
import ch.nutrisnap.app.data.model.valueOf
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriSpacing

/** Schrittweite fuer die Plus/Minus-Buttons, abhaengig vom Feld (Kalorien in
 *  groesseren Schritten als Gramm-Makros). */
private fun MacroField.step(): Float = if (this == MacroField.CALORIES) 10f else 1f

private fun MacroField.color(): Color = when (this) {
    MacroField.CALORIES -> MacroColors.calories
    MacroField.PROTEIN  -> MacroColors.protein
    MacroField.CARBS    -> MacroColors.carbs
    MacroField.FAT      -> MacroColors.fat
    MacroField.FIBER    -> MacroColors.fiber
}

/**
 * Fokussierter Edit-Screen (Bottom-Sheet) fuer die direkte Makro-Korrektur ohne
 * Zutaten-Umweg: `edit-calories` / `edit-protein` / `edit-carbs` / `edit-fat` /
 * `edit-fiber` sind alle dieselbe Komponente, parametrisiert über [field] - eigene
 * Screens pro Feld wären reine Codeduplikation.
 *
 * Zutaten des Eintrags bleiben beim Speichern unangetastet; nur der Endwert von
 * [field] wird überschrieben und der Eintrag als `isGloballyOverridden` markiert.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroEditSheet(
    entry: DiaryEntry,
    field: MacroField,
    onSave: (Float) -> Unit,
    onRemoveOverride: () -> Unit,
    onDismiss: () -> Unit
) {
    val color = field.color()
    val step = field.step()
    var valueText by remember {
        mutableStateOf(entry.valueOf(field).let { if (it == it.toInt().toFloat()) it.toInt().toString() else "%.1f".format(it) })
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = NutriSpacing.lg)
                .navigationBarsPadding()
                .padding(bottom = NutriSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "${field.label} anpassen",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.align(Alignment.Start)
            )
            Text(
                entry.foodName,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(NutriSpacing.xl))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NutriSpacing.md)) {
                FilledTonalIconButton(onClick = {
                    val v = (valueText.toFloatOrNull() ?: 0f) - step
                    valueText = v.coerceAtLeast(0f).let { if (it == it.toInt().toFloat()) it.toInt().toString() else "%.1f".format(it) }
                }) { Icon(Icons.Default.Remove, "Weniger") }

                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center, color = color),
                    suffix = { Text(field.unit, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier
                        .width(160.dp)
                        .focusRequester(focusRequester)
                )

                FilledTonalIconButton(onClick = {
                    val v = (valueText.toFloatOrNull() ?: 0f) + step
                    valueText = v.let { if (it == it.toInt().toFloat()) it.toInt().toString() else "%.1f".format(it) }
                }) { Icon(Icons.Default.Add, "Mehr") }
            }

            Spacer(Modifier.height(NutriSpacing.md))
            Text(
                "Gesamtwert manuell gesetzt – Zutaten wurden nicht verändert",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(NutriSpacing.xl))

            Button(
                onClick = { valueText.toFloatOrNull()?.let { if (it >= 0f) onSave(it) } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Speichern") }

            if (entry.isGloballyOverridden) {
                Spacer(Modifier.height(NutriSpacing.sm))
                TextButton(onClick = onRemoveOverride, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(NutriSpacing.xs))
                    Text("Override entfernen (automatisch berechnete Werte)")
                }
            }
        }
    }
}

/** Kleines "manuell"-Badge für global überschriebene Werte, analog zum bestehenden
 *  Scan/Suche-Badge in der Zutaten-Verifizierung (IngredientVerifySheet). */
@Composable
fun ManualOverrideBadge(modifier: Modifier = Modifier) {
    Text(
        "manuell",
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = modifier
    )
}
