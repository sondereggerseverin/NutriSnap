package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.MatchSource

@Composable
internal fun ComponentSectionHeader(
    title: String,
    subtitle: String,
    weightText: String,
    onWeightChange: (String) -> Unit,
    groupKcal: Float
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (groupKcal > 0f) {
            Text(
                "${safeInt(groupKcal)} kcal aus Zutaten",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(6.dp))
        val parsed = weightText.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
        OutlinedTextField(
            value = weightText,
            onValueChange = onWeightChange,
            label = { Text("Kochgewicht (g)") },
            supportingText = {
                Text(
                    if (parsed != null)
                        "Eingetragen: ${parsed.toInt()} g · wird automatisch gespeichert"
                    else
                        "Gesamtgewicht nach dem Kochen (ohne Topf) – wird automatisch gespeichert"
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── Single ingredient row ─────────────────────────────────────────────────────

@Composable
internal fun IngredientVerifyRow(
    state: IngredientVerifyState,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    autoFocusFiberEdit: Boolean,
    onFiberEditConsumed: () -> Unit,
    onScan: () -> Unit,
    onDelete: () -> Unit,
    onManualFiberSaved: (Float) -> Unit,
    onAmountSaved: (Float) -> Unit,
    componentGroup: String? = null,
    onMoveComponent: (() -> Unit)? = null,
    availableGroups: List<String> = listOf("side", "sauce"),
    readOnly: Boolean = false
) {
    val isOverride = state.override != null
    val isMatched  = state.isVerified
    val showActions = expanded
    val fiberValue = state.effectiveMicros["fiber"]
    val isManualFiber = state.manualFiber != null
    var editingFiber by remember { mutableStateOf(false) }
    var fiberInput by remember { mutableStateOf(fiberValue?.let { "%.1f".format(it) } ?: "") }
    val fiberFocusRequester = remember { FocusRequester() }
    var editingAmount by remember { mutableStateOf(false) }
    // Keyed by recipe line + effective amount so the field always follows the recipe
    // (previously remember {} kept a stale 300g when the line said 350g).
    var amountInput by remember(state.result.line, state.effectiveAmountG) {
        mutableStateOf("%.0f".format(state.effectiveAmountG))
    }
    val amountFocusRequester = remember { FocusRequester() }

    fun parseAmountInput(text: String): Float? {
        val cleaned = text.trim()
            .replace(',', '.')
            .replace(Regex("""(?i)\s*(g|gramm|grams?|ml)\s*$"""), "")
            .trim()
        return cleaned.toFloatOrNull()?.takeIf { it > 0f }
    }

    fun saveAmount() {
        parseAmountInput(amountInput)?.let { onAmountSaved(it) }
        editingAmount = false
    }

    LaunchedEffect(autoFocusFiberEdit) {
        if (autoFocusFiberEdit) {
            editingFiber = true
            fiberFocusRequester.requestFocus()
            onFiberEditConsumed()
        }
    }

    fun saveFiber() {
        fiberInput.replace(',', '.').toFloatOrNull()?.let { onManualFiberSaved(it) }
        editingFiber = false
    }

    val parts = formatVerifyLineParts(state)
    val matchName = state.effectiveFood?.name?.trim().orEmpty()
    // Bei manuellem Override ist der Hauptname bereits der Produktname →
    // Unterzeile nur zeigen, wenn sie sich vom Hauptnamen unterscheidet.
    val showMatchSub = when {
        !isMatched && !isOverride -> true
        isOverride -> matchName.isNotBlank() && !matchName.equals(parts.name, ignoreCase = true)
        matchName.isNotBlank() && !matchName.equals(parts.name, ignoreCase = true) -> true
        else -> false
    }
    val matchLabel = when {
        isOverride -> state.override?.name?.takeIf { it.isNotBlank() }?.let { "✓ $it" } ?: "✓ manuell"
        isMatched  -> "✓ $matchName"
        else       -> "Nicht gefunden"
    }

    Column(Modifier.fillMaxWidth()) {
        // Tabellarische Hauptzeile: Status | Menge | Name | kcal | Aktionen
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isOverride -> Color(0xFF1565C0).copy(alpha = 0.15f)
                            isMatched  -> Color(0xFF2E7D32).copy(alpha = 0.12f)
                            else       -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        isOverride -> Icons.Default.QrCodeScanner
                        isMatched  -> Icons.Default.Check
                        else       -> Icons.Default.QuestionMark
                    },
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = when {
                        isOverride -> Color(0xFF1565C0)
                        isMatched  -> Color(0xFF2E7D32)
                        else       -> MaterialTheme.colorScheme.error
                    }
                )
            }

            // Menge (feste Spalte)
            Text(
                parts.amountLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(56.dp),
                maxLines = 1
            )

            // Name + optional Match-Unterzeile
            Column(Modifier.weight(1f)) {
                Text(
                    text = parts.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2
                )
                if (showMatchSub) {
                    Text(
                        text = matchLabel,
                        fontSize = 11.sp,
                        color = when {
                            isOverride -> Color(0xFF1565C0)
                            isMatched  -> Color(0xFF2E7D32)
                            else       -> MaterialTheme.colorScheme.error
                        },
                        maxLines = 1
                    )
                }
            }

            // kcal
            Text(
                if (state.effectiveCalories > 0f) "${safeInt(state.effectiveCalories)}" else "–",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(36.dp),
                maxLines = 1
            )
            Text(
                "kcal",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!readOnly) {
                IconButton(onClick = onScan, Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        "Produkt ändern",
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Icon(
                if (showActions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }


        // Schnell umhängen: nächste Gruppe in availableGroups
        if (onMoveComponent != null && componentGroup != null && availableGroups.size >= 2) {
            val curIdx = availableGroups.indexOf(componentGroup).let { if (it < 0) 0 else it }
            val nextLabel = availableGroups[(curIdx + 1) % availableGroups.size].let { k ->
                when (k) {
                    "side" -> "Beilage"
                    "sauce" -> "Sauce / Fleisch"
                    else -> k
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 60.dp, end = 16.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                AssistChip(
                    onClick = onMoveComponent,
                    label = {
                        Text("→ $nextLabel", fontSize = 11.sp)
                    },
                    modifier = Modifier.height(28.dp)
                )
            }
        }

        // Detail- & Action-Bereich — shown when expanded
        if (showActions) {
            // Kein nested verticalScroll in LazyColumn-Item (Crash-Ursache)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Menge — immer aus dem Rezept (parsed), editierbar mit +/- und Freitext ("100g")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Menge: ${"%.0f".format(state.effectiveAmountG)} g",
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                        if (state.amountOverride != null &&
                            kotlin.math.abs(state.amountOverride - state.originalAmountG) > 0.5f
                        ) {
                            Text(
                                "Rezept: ${"%.0f".format(state.originalAmountG)} g",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // − 10g
                    IconButton(
                        onClick = {
                            val next = (state.effectiveAmountG - 10f).coerceAtLeast(1f)
                            onAmountSaved(next)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Remove, "−10 g", Modifier.size(18.dp))
                    }
                    // + 10g
                    IconButton(
                        onClick = {
                            onAmountSaved(state.effectiveAmountG + 10f)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Add, "+10 g", Modifier.size(18.dp))
                    }
                    // Freitext (100 / 100g)
                    IconButton(onClick = {
                        amountInput = "%.0f".format(state.effectiveAmountG)
                        editingAmount = true
                    }) {
                        Icon(Icons.Default.Edit, "Menge tippen", Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (editingAmount) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)) {
                        OutlinedTextField(
                            value = amountInput,
                            onValueChange = { amountInput = it },
                            label = { Text("Menge (g)", fontSize = 11.sp) },
                            placeholder = { Text("z.B. 350 oder 350g") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f).focusRequester(amountFocusRequester),
                            shape = RoundedCornerShape(10.dp),
                            isError = amountInput.isNotBlank() && parseAmountInput(amountInput) == null
                        )
                        IconButton(onClick = { saveAmount() }) {
                            Icon(Icons.Default.Check, "Speichern", tint = MaterialTheme.colorScheme.primary)
                        }
                        // Zurück auf Rezeptmenge
                        TextButton(onClick = {
                            onAmountSaved(state.originalAmountG)
                            amountInput = "%.0f".format(state.originalAmountG)
                            editingAmount = false
                        }) {
                            Text("Rezept", fontSize = 11.sp)
                        }
                    }
                    LaunchedEffect(editingAmount) {
                        if (editingAmount) amountFocusRequester.requestFocus()
                    }
                }

                // Makro-Details — gleiche Quelle wie die Kalorien-Anzeige oben (effectiveXxx)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("P ${fmtMacro(state.effectiveProtein)} g", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("·", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("K ${fmtMacro(state.effectiveCarbs)} g", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("·", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("F ${fmtMacro(state.effectiveFat)} g", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Ballaststoffe — hervorgehoben, ggf. mit manueller Eingabe
                if (editingFiber) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = fiberInput,
                            onValueChange = { fiberInput = it },
                            label = { Text("Ballaststoffe (g)", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f).focusRequester(fiberFocusRequester),
                            shape = RoundedCornerShape(10.dp)
                        )
                        IconButton(onClick = { saveFiber() }) {
                            Icon(Icons.Default.Check, "Speichern", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else if (fiberValue != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Ballaststoffe: ${"%.1f".format(fiberValue)} g",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        if (isManualFiber) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.Edit, "manuell überschrieben", Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(" manuell", fontSize = 10.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Text(
                        "Ballaststoffe: – (fehlen)",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        "✎ Ballaststoffe manuell eintragen",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { editingFiber = true }
                            .padding(top = 2.dp)
                    )
                }

                // Optional: Zucker & Salz, falls vorhanden
                val sugar = state.effectiveMicros["sugar"]
                val salt = state.effectiveMicros["salt"]
                if (sugar != null || salt != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOfNotNull(
                            sugar?.let { "Zucker ${"%.1f".format(it)} g" },
                            salt?.let { "Salz ${"%.1f".format(it)} g" }
                        ).joinToString("   ·   "),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!readOnly) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Keep as-is (close actions)
                    OutlinedButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Check, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Belassen", fontSize = 12.sp)
                    }
                    // Delete
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Löschen", fontSize = 12.sp)
                    }
                }
                } // !readOnly
            }
        }
    }
}

@Composable
internal fun SmallScanButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.height(28.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Default.QrCodeScanner, null, Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSecondary)
            Text("Scannen", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondary)
        }
    }
}

@Composable
internal fun MacroChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.6f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

