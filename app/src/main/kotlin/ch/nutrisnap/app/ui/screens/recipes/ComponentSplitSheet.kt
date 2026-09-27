package ch.nutrisnap.app.ui.screens.recipes

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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.model.IngredientMatch
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeComponent
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer

private data class SplitPart(
    val key: String,
    val name: String,
    val weightText: String
)

/** Mappt Abschnitts-/Gruppennamen auf side/sauce oder behält den Key. */
internal fun normalizeGroupKey(raw: String?): String? {
    val g = raw?.trim().orEmpty()
    if (g.isEmpty()) return null
    if (g == "side" || g == "sauce") return g
    val n = g.lowercase()
    return when {
        listOf(
            "beilage", "side", "stampf", "mash", "mais", "sweetcorn", "bohne", "bean",
            "reis", "kartoffel", "potato", "süsskartoffel", "suesskartoffel", "quinoa"
        ).any { it in n } -> "side"
        listOf(
            "sauce", "fleisch", "meat", "hähnchen", "huhn", "chicken", "poulet",
            "marinade", "honig", "honey", "dressing"
        ).any { it in n } -> "sauce"
        else -> g
    }
}

internal fun defaultPartKey(
    m: IngredientMatch,
    sections: List<Pair<String, List<String>>> = emptyList()
): String {
    normalizeGroupKey(m.componentGroup)?.let { normalized ->
        if (sections.isEmpty()) {
            if (normalized == "side" || normalized == "sauce") return normalized
            return normalized
        }
        sections.firstOrNull { it.first.equals(m.componentGroup, true) }?.let { return it.first }
        sections.firstOrNull { normalizeGroupKey(it.first) == normalized }?.let { return it.first }
    }
    val text = "${m.ingredientRaw} ${m.ingredientName} ${m.matchedFoodName.orEmpty()}".lowercase()
    val sideKeys = listOf(
        "reis", "kartoffel", "süsskartoffel", "suesskartoffel", "potato", "nudel", "pasta",
        "quinoa", "couscous", "bulgur", "mais", "sweetcorn", "erbse", "bohne", "bean", "beilage"
    )
    val sauceKeys = listOf(
        "hack", "fleisch", "hähnchen", "huhn", "chicken", "poulet", "rind", "schwein",
        "sauce", "soße", "sosse", "tomat", "senf", "zwiebel"
    )
    val n = text
    if (sections.isNotEmpty()) {
        // Beste Übereinstimmung über Abschnittsname
        sections.firstOrNull { (name, lines) ->
            lines.any { line ->
                val core = line.lowercase().filter { it.isLetter() }
                val ing = n.filter { it.isLetter() }
                core.length >= 4 && (ing.contains(core.take(8)) || core.contains(ing.take(8)))
            } || sideKeys.any { it in name.lowercase() } && sideKeys.any { it in n }
        }?.let { return it.first }
        return sections.last().first
    }
    return if (sideKeys.any { it in n }) "side" else "sauce"
}

internal fun displayNameForKey(key: String): String = when (key.lowercase()) {
    "side" -> "Beilage"
    "sauce" -> "Sauce / Fleisch"
    "main" -> "Hauptteil"
    else -> key
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentSplitSheet(
    recipe: Recipe,
    matches: List<IngredientMatch>,
    initialComponents: List<RecipeComponent>,
    onSave: (components: List<RecipeComponent>, matches: List<IngredientMatch>) -> Unit,
    onDismiss: () -> Unit
) {
    val ingredientSections = remember(recipe.id, recipe.ingredients) {
        parseIngredientSections(recipe.ingredients)
    }

    // Matches oder synthetische aus Abschnitten
    val workingMatches = remember(matches, ingredientSections, recipe.id) {
        if (matches.isNotEmpty()) matches.filter { !it.isDeleted }
        else if (ingredientSections.size >= 2) {
            ingredientSections.flatMap { (sectionName, lines) ->
                lines.map { line ->
                    val grams = RecipeNutritionAnalyzer.parseIngredientLine(line)?.amountG ?: 0f
                    IngredientMatch(
                        recipeId = recipe.id,
                        ingredientRaw = line,
                        ingredientName = line.trimStart('•', '-', ' ').trim(),
                        amountGrams = grams,
                        componentGroup = sectionName
                    )
                }
            }
        } else emptyList()
    }

    fun weightSuggestion(name: String, sectionLines: List<String> = emptyList()): String {
        val match = initialComponents.firstOrNull {
            it.name.equals(name, true) ||
                normalizeGroupKey(it.name) == normalizeGroupKey(name)
        }
        match?.cookedWeightG?.takeIf { it > 0f }?.toInt()?.toString()?.let { return it }
        val sumG = sectionLines.mapNotNull {
            RecipeNutritionAnalyzer.parseIngredientLine(it)?.amountG
        }.sum().takeIf { it > 0f }
        return sumG?.toInt()?.toString() ?: ""
    }

    // Teile initialisieren: Abschnitte > gespeicherte Komponenten > Default Beilage/Sauce
    var parts by remember {
        mutableStateOf(
            when {
                ingredientSections.size >= 2 -> ingredientSections.map { (name, lines) ->
                    SplitPart(key = name, name = name, weightText = weightSuggestion(name, lines))
                }
                initialComponents.isNotEmpty() -> {
                    // Deduplizieren nach normalisiertem Namen
                    initialComponents
                        .groupBy { normalizeGroupKey(it.name) ?: it.name.trim().lowercase() }
                        .map { (_, group) -> group.maxByOrNull { it.cookedWeightG } ?: group.last() }
                        .mapIndexed { i, c ->
                            val key = normalizeGroupKey(c.name) ?: c.name.ifBlank { "teil$i" }
                            SplitPart(
                                key = key,
                                name = c.name.ifBlank { displayNameForKey(key) },
                                weightText = c.cookedWeightG.takeIf { it > 0f }?.toInt()?.toString() ?: ""
                            )
                        }
                }
                else -> listOf(
                    SplitPart("side", "Beilage", ""),
                    SplitPart("sauce", "Sauce / Fleisch", "")
                )
            }
        )
    }

    fun clampToPart(raw: String?, m: IngredientMatch): String {
        val keys = parts.map { it.key }
        if (keys.isEmpty()) return raw ?: "sauce"
        if (raw != null && raw in keys) return raw
        val norm = normalizeGroupKey(raw)
        if (norm != null) {
            keys.firstOrNull { it == norm }?.let { return it }
            keys.firstOrNull { normalizeGroupKey(it) == norm }?.let { return it }
            // Display-Name Match
            keys.firstOrNull { displayNameForKey(it).equals(raw, true) }?.let { return it }
        }
        val d = defaultPartKey(m, ingredientSections)
        if (d in keys) return d
        keys.firstOrNull { normalizeGroupKey(it) == d }?.let { return it }
        return keys.first()
    }

    fun buildGroups(): Map<Int, String> {
        if (workingMatches.isEmpty()) return emptyMap()
        if (ingredientSections.size >= 2) {
            val assigned = assignMatchesToSections(workingMatches, ingredientSections)
            return workingMatches.mapIndexed { i, m ->
                i to clampToPart(assigned[i] ?: m.componentGroup, m)
            }.toMap()
        }
        return workingMatches.mapIndexed { i, m ->
            i to clampToPart(m.componentGroup, m)
        }.toMap()
    }

    var groups by remember { mutableStateOf(buildGroups()) }
    LaunchedEffect(workingMatches, ingredientSections, parts.map { it.key }.joinToString()) {
        if (workingMatches.isEmpty()) return@LaunchedEffect
        // Nur fehlende / ungültige Keys neu setzen, bestehende manuelle Zuordnung behalten
        val validKeys = parts.map { it.key }.toSet()
        val current = groups
        val rebuilt = buildGroups()
        groups = workingMatches.indices.associateWith { i ->
            val existing = current[i]
            if (existing != null && existing in validKeys) existing
            else rebuilt[i] ?: validKeys.first()
        }
    }

    fun sumKcal(key: String): Float =
        workingMatches.filterIndexed { i, _ -> groups[i] == key }
            .sumOf { (it.matchedCalories ?: 0f).toDouble() }.toFloat()

    fun sumProt(key: String): Float =
        workingMatches.filterIndexed { i, _ -> groups[i] == key }
            .sumOf { (it.matchedProtein ?: 0f).toDouble() }.toFloat()

    fun sumCarbs(key: String): Float =
        workingMatches.filterIndexed { i, _ -> groups[i] == key }
            .sumOf { (it.matchedCarbs ?: 0f).toDouble() }.toFloat()

    fun sumFat(key: String): Float =
        workingMatches.filterIndexed { i, _ -> groups[i] == key }
            .sumOf { (it.matchedFat ?: 0f).toDouble() }.toFloat()

    var allowSheetDismiss by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { newValue ->
            if (newValue == SheetValue.Hidden) allowSheetDismiss else true
        }
    )
    fun requestDismiss() {
        allowSheetDismiss = true
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { requestDismiss() },
        sheetState = sheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Komponenten trennen",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { requestDismiss() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Schliessen")
                }
            }
            Text(
                recipe.displayTitle(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Abschnitte anlegen, Zutaten zuordnen, Kochgewicht eintragen. Fertig.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            // ── 1. Komponenten (Name + Kochgewicht) ──────────────────────────
            Text("Abschnitte", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))

            parts.forEachIndexed { index, part ->
                val kcal = sumKcal(part.key)
                Card(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = part.name,
                                onValueChange = { v ->
                                    // Key bleibt stabil – nur Anzeigename ändert sich
                                    parts = parts.toMutableList().also {
                                        it[index] = part.copy(name = v)
                                    }
                                },
                                label = { Text("Name") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            if (parts.size > 1) {
                                IconButton(
                                    onClick = {
                                        val removedKey = part.key
                                        val fallback = parts.first { it.key != removedKey }.key
                                        groups = groups.mapValues { (_, g) ->
                                            if (g == removedKey) fallback else g
                                        }
                                        parts = parts.filterIndexed { i, _ -> i != index }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Entfernen",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = part.weightText,
                            onValueChange = { v ->
                                parts = parts.toMutableList().also {
                                    it[index] = part.copy(weightText = v)
                                }
                            },
                            label = { Text("Kochgewicht (g)") },
                            placeholder = { Text("Nach dem Kochen, ohne Topf") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (kcal > 0f) {
                            Text(
                                "${fmtNum(kcal)} kcal aus zugeordneten Zutaten",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            TextButton(
                onClick = {
                    val n = parts.size + 1
                    val key = "teil$n"
                    parts = parts + SplitPart(key, "Teil $n", "")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Abschnitt hinzufügen")
            }

            Spacer(Modifier.height(20.dp))

            // ── 2. Zutaten zuordnen (ein Dropdown pro Zutat) ─────────────────
            Text("Zutaten zuordnen", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tippe auf den Abschnitt-Namen, um die Zutat umzuzuordnen.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (workingMatches.isEmpty()) {
                Text(
                    "Keine Zutaten vorhanden. Zuerst im Verify-Sheet matchen oder Abschnitte im Zutaten-Text anlegen.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                workingMatches.forEachIndexed { mi, m ->
                    val currentKey = groups[mi] ?: parts.firstOrNull()?.key.orEmpty()
                    val currentName = parts.firstOrNull { it.key == currentKey }?.name
                        ?: displayNameForKey(currentKey)

                    var menuOpen by remember { mutableStateOf(false) }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.ingredientRaw.ifBlank { m.ingredientName },
                                fontSize = 14.sp
                            )
                            val kcal = m.matchedCalories ?: 0f
                            if (kcal > 0f) {
                                Text(
                                    "${fmtNum(kcal)} kcal",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Box {
                            AssistChip(
                                onClick = { menuOpen = true },
                                label = {
                                    Text(currentName, fontSize = 12.sp, maxLines = 1)
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                parts.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.name) },
                                        onClick = {
                                            groups = groups + (mi to p.key)
                                            menuOpen = false
                                        },
                                        trailingIcon = {
                                            if (p.key == currentKey) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (mi < workingMatches.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    val withWeights = parts.mapIndexedNotNull { i, part ->
                        val w = part.weightText.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
                            ?: return@mapIndexedNotNull null
                        Triple(i, part, w)
                    }
                    if (withWeights.isEmpty()) return@Button

                    val weightSum = withWeights.sumOf { it.third.toDouble() }.toFloat().coerceAtLeast(1f)
                    val serv = recipe.servings.coerceAtLeast(1).toFloat()
                    val recipeKcal = recipe.totalCalories ?: 0f
                    val recipeProt = (recipe.proteinPerServing ?: 0f) * serv
                    val recipeCarbs = (recipe.carbsPerServing ?: 0f) * serv
                    val recipeFat = (recipe.fatPerServing ?: 0f) * serv

                    val comps = withWeights.map { (i, part, w) ->
                        val kcalM = sumKcal(part.key)
                        val protM = sumProt(part.key)
                        val carbsM = sumCarbs(part.key)
                        val fatM = sumFat(part.key)
                        val frac = w / weightSum
                        RecipeComponent(
                            recipeId = recipe.id,
                            name = part.name.trim().ifBlank { displayNameForKey(part.key) },
                            cookedWeightG = w,
                            totalCalories = if (kcalM > 0f) kcalM else recipeKcal * frac,
                            proteinG = if (protM > 0f) protM else recipeProt * frac,
                            carbsG = if (carbsM > 0f) carbsM else recipeCarbs * frac,
                            fatG = if (fatM > 0f) fatM else recipeFat * frac,
                            sortOrder = i
                        )
                    }
                    // Dedup nach Name
                    val deduped = comps
                        .groupBy { it.name.trim().lowercase() }
                        .map { (_, g) -> g.maxByOrNull { it.cookedWeightG } ?: g.last() }

                    val updatedMatches = workingMatches.mapIndexed { i, m ->
                        m.copy(componentGroup = groups[i] ?: parts.firstOrNull()?.key ?: "sauce")
                    }
                    onSave(deduped, updatedMatches)
                    requestDismiss()
                },
                enabled = parts.any {
                    it.weightText.replace(',', '.').toFloatOrNull()?.let { w -> w > 0f } == true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Trennung speichern")
            }
            TextButton(onClick = { requestDismiss() }, modifier = Modifier.fillMaxWidth()) {
                Text("Abbrechen")
            }
        }
    }
}
