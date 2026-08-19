package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.model.IngredientMatch
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeComponent
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriSpacing
import java.time.LocalDate


private data class SplitPart(
    val key: String,
    val name: String,
    val weightText: String
)

/** Mappt beliebige componentGroup/Abschnittsnamen auf side/sauce oder behält den Key. */
private fun normalizeGroupKey(raw: String?): String? {
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
        else -> g // eigener Abschnitt (z. B. "Sweet potato mash")
    }
}

private fun defaultPartKey(
    m: IngredientMatch,
    sections: List<Pair<String, List<String>>> = emptyList()
): String {
    // 1) Bereits gesetzte componentGroup (nach Normalisierung)
    normalizeGroupKey(m.componentGroup)?.let { normalized ->
        if (sections.isEmpty()) {
            // Binary-Modus: nur side/sauce erlauben
            if (normalized == "side" || normalized == "sauce") return normalized
            // Custom-Key nur behalten wenn Abschnitte existieren
        } else {
            // Abschnitts-Modus: exact match auf Section-Name, sonst normalisiert
            sections.firstOrNull { it.first.equals(m.componentGroup, true) }?.let { return it.first }
            sections.firstOrNull { it.first.equals(normalized, true) }?.let { return it.first }
            if (normalized == "side" || normalized == "sauce") {
                sections.firstOrNull { (name, _) ->
                    normalizeGroupKey(name) == normalized
                }?.let { return it.first }
            }
        }
    }
    // 2) Abschnitte aus Zutaten-Text
    matchToSectionKey(m, sections)?.let { return it }
    val n = "${m.ingredientRaw} ${m.ingredientName} ${m.matchedFoodName.orEmpty()}".lowercase()
    val sideKeys = listOf(
        "reis", "basmati", "erbse", "erbsen", "peas", "kartoffel", "nudel", "pasta",
        "quinoa", "couscous", "bulgur", "beilage", "hafer", "flocken", "sweet potato",
        "süsskartoffel", "suesskartoffel", "süßkartoffel", "mais", "zuckermais",
        "sweetcorn", "bohne", "bean", "milch", "butter", "milk", "zwiebel", "onion",
        "stampf", "mash"
    )
    if (sections.isNotEmpty()) {
        return if (sideKeys.any { it in n }) {
            sections.firstOrNull { (name, _) ->
                normalizeGroupKey(name) == "side" ||
                    name.lowercase().let { s ->
                        listOf("stampf", "mash", "beilage", "kartoffel", "mais", "bohne", "potato").any { it in s }
                    }
            }?.first ?: sections.first().first
        } else {
            sections.firstOrNull { (name, _) ->
                normalizeGroupKey(name) == "sauce" ||
                    name.lowercase().let { s ->
                        listOf("sauce", "fleisch", "hähnchen", "huhn", "chicken", "honig", "marinade").any { it in s }
                    }
            }?.first ?: sections.last().first
        }
    }
    return if (sideKeys.any { it in n }) "side" else "sauce"
}

private fun displayNameForKey(key: String): String = when (key) {
    "side" -> "Beilage"
    "sauce" -> "Sauce / Fleisch"
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
    // Abschnitte aus Zutaten-Text (z.B. "Für die Sauce", "Charred Zuckermais & Beans")
    val ingredientSections = remember(recipe.id, recipe.ingredients) {
        parseIngredientSections(recipe.ingredients)
    }

    // Ohne Verify: Zutatenzeilen aus Abschnitten als Matches synthetisieren
    // → Abschnitte werden automatisch zu Komponenten, kein manuelles Zuordnen.
    val workingMatches = remember(matches, ingredientSections, recipe.id) {
        if (matches.isNotEmpty()) matches
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

    val groupsFromMatches = remember(workingMatches) {
        workingMatches.mapNotNull { normalizeGroupKey(it.componentGroup) }.distinct()
    }

    // Teile: 1) Zutaten-Abschnitte 2) Match-componentGroups 3) gespeicherte Komponenten 4) Beilage+Sauce
    var parts by remember {
        mutableStateOf(
            run {
                fun weightFor(name: String, sectionLines: List<String> = emptyList()): String {
                    val match = initialComponents.firstOrNull {
                        it.name.equals(name, true) ||
                            (name.contains("sauce", true) && it.name.contains("sauce", true)) ||
                            (name.contains("beilage", true) && it.name.contains("beilage", true)) ||
                            (normalizeGroupKey(it.name) == normalizeGroupKey(name))
                    }
                    match?.cookedWeightG?.takeIf { it > 0f }?.toInt()?.toString()?.let { return it }
                    // Vorschlag: Summe der Roh-Gramm aus dem Abschnitt
                    val sumG = sectionLines.mapNotNull {
                        RecipeNutritionAnalyzer.parseIngredientLine(it)?.amountG
                    }.sum().takeIf { it > 0f }
                    return sumG?.toInt()?.toString() ?: ""
                }
                when {
                    ingredientSections.size >= 2 -> ingredientSections.map { (name, lines) ->
                        SplitPart(key = name, name = name, weightText = weightFor(name, lines))
                    }
                    groupsFromMatches.size >= 2 -> groupsFromMatches.map { key ->
                        SplitPart(
                            key = key,
                            name = displayNameForKey(key),
                            weightText = weightFor(displayNameForKey(key))
                        )
                    }
                    initialComponents.isNotEmpty() -> initialComponents.mapIndexed { i, c ->
                        val key = normalizeGroupKey(c.name) ?: when {
                            c.name.contains("beilage", true) -> "side"
                            c.name.contains("sauce", true) || c.name.contains("fleisch", true) -> "sauce"
                            else -> c.name.ifBlank { "teil$i" }
                        }
                        SplitPart(
                            key = key,
                            name = c.name.ifBlank { displayNameForKey(key) },
                            weightText = c.cookedWeightG.takeIf { it > 0f }?.toInt()?.toString() ?: ""
                        )
                    }
                    else -> listOf(
                        SplitPart("side", "Beilage", ""),
                        SplitPart("sauce", "Sauce / Fleisch", "")
                    )
                }
            }
        )
    }

    // Index-basiert: bei doppelten Zutatenzeilen (Rezept x2) sonst Kollisionen.
    // HARTE REGEL: jede Zutat landet in einem existierenden Part – nie «Nicht zugeordnet».
    fun clampToPart(raw: String?, m: IngredientMatch): String {
        val partKeySet = parts.map { it.key }.toSet()
        if (partKeySet.isEmpty()) return raw ?: "sauce"
        if (raw != null && raw in partKeySet) return raw
        val norm = normalizeGroupKey(raw)
        if (norm != null) {
            partKeySet.firstOrNull { it == norm }?.let { return it }
            partKeySet.firstOrNull { normalizeGroupKey(it) == norm }?.let { return it }
        }
        val d = defaultPartKey(m, ingredientSections)
        if (d in partKeySet) return d
        partKeySet.firstOrNull { normalizeGroupKey(it) == d }?.let { return it }
        partKeySet.firstOrNull { normalizeGroupKey(it) == "side" && defaultPartKey(m, emptyList()) == "side" }
            ?.let { return it }
        return partKeySet.first()
    }

    fun buildGroups(): Map<Int, String> {
        if (workingMatches.isEmpty()) return emptyMap()
        // Direkt aus Abschnitts-Text: componentGroup == Section-Name
        if (ingredientSections.size >= 2) {
            val byExact = workingMatches.mapIndexed { i, m ->
                val cg = m.componentGroup?.trim()
                if (cg != null && parts.any { it.key == cg }) i to cg
                else null
            }
            if (byExact.all { it != null }) return byExact.mapNotNull { it }.toMap()
            val assigned = assignMatchesToSections(workingMatches, ingredientSections)
            return workingMatches.mapIndexed { i, m ->
                i to clampToPart(assigned[i] ?: m.componentGroup, m)
            }.toMap()
        }
        return workingMatches.mapIndexed { i, m ->
            val fromMatch = normalizeGroupKey(m.componentGroup)
            val key = when {
                fromMatch != null && fromMatch in parts.map { it.key }.toSet() -> fromMatch
                fromMatch == "side" && parts.any { normalizeGroupKey(it.key) == "side" } ->
                    parts.first { normalizeGroupKey(it.key) == "side" }.key
                fromMatch == "sauce" && parts.any { normalizeGroupKey(it.key) == "sauce" } ->
                    parts.first { normalizeGroupKey(it.key) == "sauce" }.key
                else -> defaultPartKey(m, emptyList())
            }
            i to clampToPart(key, m)
        }.toMap()
    }
    var groups by remember { mutableStateOf(buildGroups()) }
    LaunchedEffect(workingMatches, ingredientSections, parts.map { it.key }) {
        if (workingMatches.isEmpty()) return@LaunchedEffect
        groups = buildGroups()
    }

    fun sumFor(key: String): Triple<Float, Float, Float> {
        val list = workingMatches.filterIndexed { i, _ -> groups[i] == key }
        val kcal = list.sumOf { (it.matchedCalories ?: 0f).toDouble() }.toFloat()
        val prot = list.sumOf { (it.matchedProtein ?: 0f).toDouble() }.toFloat()
        val carbs = list.sumOf { (it.matchedCarbs ?: 0f).toDouble() }.toFloat()
        return Triple(kcal, prot, carbs)
    }
    fun fatFor(key: String): Float =
        workingMatches.filterIndexed { i, _ -> groups[i] == key }
            .sumOf { (it.matchedFat ?: 0f).toDouble() }.toFloat()

    // Swipe-to-dismiss aus: Scrollen soll das Sheet nicht schliessen (nur X / Abbrechen).
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
            Spacer(Modifier.height(8.dp))

            if (workingMatches.isEmpty()) {
                Text(
                    "Keine Zutaten gefunden. Rezept-Text prüfen oder «Verify» ausführen.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { requestDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Schliessen") }
                return@Column
            }

            if (ingredientSections.size >= 2 && matches.isEmpty()) {
                Text(
                    "Abschnitte aus dem Rezept übernommen – Kochgewichte prüfen und speichern.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
            }

            // Status-Banner für bereits gesetzte Gewichte
            val setParts = parts.filter {
                it.weightText.replace(',', '.').toFloatOrNull()?.let { w -> w > 0f } == true
            }
            if (setParts.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Bereits getrennt: " + setParts.joinToString(" · ") {
                            "${it.name} ${it.weightText} g"
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            parts.forEachIndexed { index, part ->
                val (kcal, prot, carbs) = sumFor(part.key)
                val fat = fatFor(part.key)
                val partMatches = workingMatches.mapIndexed { i, m -> i to m }.filter { groups[it.first] == part.key }

                Text(part.name, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        kcal > 0f -> "${fmtNum(kcal)} kcal aus Zutaten"
                        partMatches.isNotEmpty() -> "${partMatches.size} Zutaten aus Abschnitt"
                        else -> "Keine Zutaten zugeordnet"
                    },
                    fontSize = 12.sp,
                    color = if (kcal > 0f) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = part.weightText,
                    onValueChange = { v ->
                        parts = parts.toMutableList().also {
                            it[index] = part.copy(weightText = v)
                        }
                    },
                    label = { Text("Kochgewicht ${part.name} (g)") },
                    placeholder = { Text("Nach dem Kochen, ohne Topf") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
                if (part.weightText.replace(',', '.').toFloatOrNull()?.let { it > 0f } == true && kcal > 0f) {
                    val w = part.weightText.replace(',', '.').toFloatOrNull() ?: 0f
                    if (w > 0f) {
                        Text(
                            "Eingetragen: ${w.toInt()} g · ${fmtNum(kcal / w * 100f)} kcal/100g",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                partMatches.forEach { (mi, m) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.ingredientRaw, fontSize = 13.sp)
                            Text(
                                "${fmtNum(m.matchedCalories ?: 0f)} kcal",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Zum nächsten Teil verschieben
                        val nextIdx = (index + 1) % parts.size
                        if (parts.size > 1) {
                            AssistChip(
                                onClick = {
                                    groups = groups + (mi to parts[nextIdx].key)
                                },
                                label = {
                                    Text("→ ${parts[nextIdx].name}", fontSize = 11.sp)
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Orphans werden in buildGroups/clampToPart bereits einem Part zugewiesen –
            // kein «Nicht zugeordnet»-Block mehr.

            TextButton(
                onClick = {
                    val n = parts.size + 1
                    val key = "teil$n"
                    parts = parts + SplitPart(key, "Teil $n", "")
                }
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Komponente hinzufügen")
            }

            if (parts.size > 2) {
                TextButton(
                    onClick = {
                        val lastKey = parts.last().key
                        // Zutaten der letzten Gruppe in die vorletzte schieben
                        val prevKey = parts[parts.size - 2].key
                        groups = groups.mapValues { (_, v) -> if (v == lastKey) prevKey else v }
                        parts = parts.dropLast(1)
                    }
                ) {
                    Text("Letzte Komponente entfernen")
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val withWeights = parts.mapIndexedNotNull { i, part ->
                        val w = part.weightText.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
                            ?: return@mapIndexedNotNull null
                        Triple(i, part, w)
                    }
                    val weightSum = withWeights.sumOf { it.third.toDouble() }.toFloat().coerceAtLeast(1f)
                    val serv = recipe.servings.coerceAtLeast(1).toFloat()
                    val recipeKcal = recipe.totalCalories ?: 0f
                    val recipeProt = (recipe.proteinPerServing ?: 0f) * serv
                    val recipeCarbs = (recipe.carbsPerServing ?: 0f) * serv
                    val recipeFat = (recipe.fatPerServing ?: 0f) * serv
                    val comps = withWeights.map { (i, part, w) ->
                        val (kcalM, protM, carbsM) = sumFor(part.key)
                        val fatM = fatFor(part.key)
                        // Ohne Verify: anteilig aus Rezept-Total nach Kochgewicht
                        val frac = w / weightSum
                        val kcal = if (kcalM > 0f) kcalM else recipeKcal * frac
                        val prot = if (protM > 0f) protM else recipeProt * frac
                        val carbs = if (carbsM > 0f) carbsM else recipeCarbs * frac
                        val fat = if (fatM > 0f) fatM else recipeFat * frac
                        RecipeComponent(
                            recipeId = recipe.id,
                            name = part.name.ifBlank { displayNameForKey(part.key) },
                            cookedWeightG = w,
                            totalCalories = kcal,
                            proteinG = prot,
                            carbsG = carbs,
                            fatG = fat,
                            sortOrder = i
                        )
                    }
                    val updatedMatches = workingMatches.mapIndexed { i, m ->
                        m.copy(componentGroup = groups[i] ?: parts.firstOrNull()?.key ?: "sauce")
                    }
                    if (comps.isNotEmpty()) onSave(comps, updatedMatches)
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
            TextButton(onClick = { requestDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
        }
    }
}
