package ch.nutrisnap.app.ui.screens.diary

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.*
import ch.nutrisnap.app.data.model.favoriteKey
import ch.nutrisnap.app.ui.components.EmptyState
import ch.nutrisnap.app.ui.components.MicronutrientTable
import ch.nutrisnap.app.ui.components.NutritionFactsProgress
import ch.nutrisnap.app.ui.components.SectionHeader
import ch.nutrisnap.app.domain.EntryPlausibilityChecker
import ch.nutrisnap.app.domain.FoodPortionPresets
import ch.nutrisnap.app.domain.EverydayServingSizes
import ch.nutrisnap.app.ui.screens.barcode.BarcodeScannerScreen
import ch.nutrisnap.app.ui.screens.scan.PhotoCaptureScreen
import android.graphics.Bitmap
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import androidx.compose.ui.graphics.Color
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formatiert eine Rezept-Portionsmenge (amountGrams speichert bei Rezepten den
 *  Portionsfaktor, nicht Gramm) als "1 Portion", "2 Portionen", "0.5 Portionen" etc. */
private fun formatPortionAmount(amount: Float): String {
    val text = if (amount == amount.toInt().toFloat()) amount.toInt().toString() else "%.1f".format(amount)
    return "$text Portion${if (amount == 1f) "" else "en"}"
}

/** Anzeige für Rezept-/Manual-Einträge: Gramm wenn gram-getrackt, sonst Portionen. */
private fun recipeAmountLabel(entry: DiaryEntry): String {
    if (entry.isGramTrackedRecipe) {
        return "${entry.recipeGrams!!.toInt()} g"
    }
    // Legacy: amountGrams fälschlich als Gramm statt Portionsfaktor gespeichert
    if (entry.isRecipeEntry && entry.amountGrams >= 20f && entry.recipeGrams == null) {
        return "${entry.amountGrams.toInt()} g"
    }
    val portions = when {
        entry.recipeGrams != null && entry.recipeGrams > 0f && entry.recipeGrams < 10f ->
            entry.recipeGrams
        entry.amountGrams > 0f -> entry.amountGrams
        else -> 1f
    }
    return formatPortionAmount(portions)
}

/** true wenn Menge eher Portion/Rezept als echte Gramm-Angabe ist. */
private fun looksLikePortionEntry(entry: DiaryEntry): Boolean = entry.isPortionTracked

private fun defaultMealForNow(): MealType = when (LocalTime.now().hour) {
    in 5..10  -> MealType.BREAKFAST
    in 11..14 -> MealType.LUNCH
    in 17..21 -> MealType.DINNER
    else      -> MealType.SNACK
}


/** Parst Mengeneingaben wie "100", "100g", "100 g", "1,5", "100ml". */
private fun parseGramsInput(text: String): Float? {
    val cleaned = text.trim()
        .replace(',', '.')
        .replace(Regex("""(?i)\s*(g|gramm|grams?|ml|milliliter)\s*$"""), "")
        .trim()
    return cleaned.toFloatOrNull()?.takeIf { it > 0f }
}

/** Kompakte Tagesübersicht: eine Zeile Kalorien + dünner Balken + Makro-Mini-Stats.
 *  Ersetzt die frühere, deutlich höhere MacroBar-Karte auf dem Tagebuch-Screen. */
@Composable
private fun CompactDayOverview(
    calories: Float,
    goal: Float,
    protein: Float,
    carbs: Float,
    fat: Float,
    modifier: Modifier = Modifier
) {
    val progress  = (calories / goal.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val remaining = goal - calories

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.md)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${calories.toInt()}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        " / ${goal.toInt()} kcal",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp, start = 2.dp)
                    )
                }
                Text(
                    if (remaining >= 0) "${remaining.toInt()} kcal übrig" else "${-remaining.toInt()} kcal über",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (remaining >= 0) MacroColors.calories else MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))

            LinearProgressIndicator(
                progress   = { progress },
                modifier   = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color      = if (progress < 1f) MacroColors.calories else MaterialTheme.colorScheme.error,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap  = androidx.compose.ui.graphics.StrokeCap.Round
            )

            Spacer(Modifier.height(NutriSpacing.sm))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MiniMacroStat("Protein", protein, MacroColors.protein)
                MiniMacroStat("Kohlenh.", carbs, MacroColors.carbs)
                MiniMacroStat("Fett", fat, MacroColors.fat)
            }
        }
    }
}

@Composable
private fun MiniMacroStat(label: String, grams: Float, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "${grams.toInt()}g",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(3.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun DiaryScreen(
    vm: DiaryViewModel = viewModel(),
    initialMeal: MealType? = null,
    autoOpenAdd: Boolean = false,
    autoOpenScanner: Boolean = false,
    onNavigateToPhotoScan: (MealType?) -> Unit = {}
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showAddSheet by remember { mutableStateOf(autoOpenAdd || autoOpenScanner) }
    // BUG-FIX: showAddSheet wurde bisher nur beim Erstellen der Compose-Instanz aus
    // autoOpenAdd/autoOpenScanner initialisiert. Da NavHost Tab-Instanzen bei
    // Tab-Wechseln wiederverwendet (restoreState/saveState), blieb bei erneutem
    // Aufruf mit autoOpenAdd=true die alte Instanz (samt showAddSheet=false) aktiv
    // und das Sheet oeffnete sich nicht mehr — deshalb wich MainActivity bislang auf
    // einen "frischen Push" ohne popUpTo aus, was den Back-Stack aufblähte und dazu
    // fuehrte, dass man von Tagebuch nicht mehr sauber zu Start zurückkam.
    LaunchedEffect(autoOpenAdd, autoOpenScanner, initialMeal) {
        if (autoOpenAdd || autoOpenScanner) showAddSheet = true
    }
    var editEntry    by remember { mutableStateOf<DiaryEntry?>(null) }
    var detailEntry  by remember { mutableStateOf<DiaryEntry?>(null) }
    var scheduleEntry by remember { mutableStateOf<DiaryEntry?>(null) }
    var scheduleMeal by remember { mutableStateOf<MealType?>(null) }
    // Direkte Makro-Korrektur (globale Ebene): welches Feld gerade im MacroEditSheet
    // bearbeitet wird. Der zugehörige Eintrag wird bei jeder Rekomposition frisch aus
    // state.entries geholt, damit der Wert nach dem Speichern sofort aktuell ist.
    var macroEditField by remember { mutableStateOf<MacroField?>(null) }
    var expandedNutrition by remember { mutableStateOf<MealType?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val mealPrefs by context.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val mealOrder = remember(mealPrefs) { parseMealOrder(mealPrefs?.get(ch.nutrisnap.app.ui.theme.KEY_MEAL_ORDER)) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val quickAddFavorites by vm.favorites.collectAsStateWithLifecycle()
    val autopilotTemplates by vm.autopilotTemplates.collectAsStateWithLifecycle()
    val recipesVm: ch.nutrisnap.app.ui.screens.recipes.RecipesViewModel = viewModel()
    val recipesState by recipesVm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // BUG-FIX: Bisher liess sich das Add-Sheet nur ueber den "+" auf der
        // Startseite (Meal-Quick-Add -> Navigation mit autoOpenAdd=true) oeffnen.
        // Wer bereits im Tagebuch war, hatte keine Moeglichkeit, ohne Umweg ueber
        // "Start" einen Eintrag zu erfassen.
        floatingActionButton = {
            val consolidateFab = mealPrefs?.get(ch.nutrisnap.app.ui.theme.KEY_TOGGLE_DIARY_FAB_CONSOLIDATION) ?: false
            if (consolidateFab) {
                FloatingActionButton(onClick = { showAddSheet = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Eintrag hinzufügen")
                }
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    SmallFloatingActionButton(
                        onClick = {
                            val meal = initialMeal ?: defaultMealForNow()
                            onNavigateToPhotoScan(meal)
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "Essen fotografieren")
                    }
                    Spacer(Modifier.height(12.dp))
                    FloatingActionButton(onClick = { showAddSheet = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Eintrag hinzufügen")
                    }
                }
            }
        }
    ) { padding ->
        val window = ch.nutrisnap.app.ui.rememberWindowInfo()
        ch.nutrisnap.app.ui.AdaptiveContent(
            modifier = Modifier.padding(padding),
            window = window
        ) {
        val isWeekday = remember(state.selectedDate) {
            val dow = state.selectedDate.dayOfWeek
            dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY
        }
        val autopilotSuggestions = remember(isWeekday, autopilotTemplates, state.entries) {
            if (!isWeekday || autopilotTemplates.isEmpty()) emptyList()
            else {
                val presentMeals = state.entries.map { it.mealType }.toSet()
                autopilotTemplates.filter { it.mealType !in presentMeals }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = if (window.isTablet) 12.dp else 0.dp,
                end = if (window.isTablet) 12.dp else 0.dp,
                bottom = 100.dp
            )
        ) {
            item { DateNavigator(state.selectedDate, vm::prevDay, vm::nextDay, vm::setDate) }
            item {
                val largerYesterday = mealPrefs?.get(ch.nutrisnap.app.ui.theme.KEY_TOGGLE_TOUCH_YESTERDAY_BTN) ?: true
                if (largerYesterday) {
                    FilledTonalButton(
                        onClick = {
                            vm.copyYesterday { n ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (n > 0) "$n Einträge von gestern übernommen"
                                        else "Gestern war leer – nichts zu kopieren"
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .padding(horizontal = NutriSpacing.lg)
                            .heightIn(min = 40.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Gestern übernehmen", fontSize = 13.sp)
                    }
                } else {
                    TextButton(
                        onClick = {
                            vm.copyYesterday { n ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (n > 0) "$n Einträge von gestern übernommen"
                                        else "Gestern war leer – nichts zu kopieren"
                                    )
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = NutriSpacing.lg)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Gestern übernehmen", fontSize = 13.sp)
                    }
                }
            }
            item {
                CompactDayOverview(
                    calories = state.totalCalories,
                    goal = state.calorieGoal,
                    protein  = state.totalProtein,
                    carbs = state.totalCarbs,
                    fat      = state.totalFat,
                    modifier = Modifier.padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.xs)
                )
            }
            if (autopilotSuggestions.isNotEmpty()) {
                item(key = "autopilot_banner") {
                    AutopilotBanner(
                        templates = autopilotSuggestions,
                        onApply = { t ->
                            vm.applyAutopilotTemplate(t) { n ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (n > 0) "${t.name}: $n Einträge übernommen"
                                        else "${t.name} hat noch keine Lebensmittel"
                                    )
                                }
                            }
                        }
                    )
                }
            }
            if (state.entries.isEmpty()) {
                item {
                    EmptyState(
                        icon = {
                            Icon(
                                Icons.Default.MenuBook, null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        message = "Noch keine Einträge",
                        sub     = "Tippe auf + um Mahlzeiten zu erfassen"
                    )
                }
            } else {
                val grouped = state.entries.groupBy { it.mealType }
                mealOrder.forEach { meal ->
                    val mealEntries = grouped[meal] ?: return@forEach
                    val mealKcal = mealEntries.sumOf { it.calories.toInt() }
                    item {
                        SectionHeader(
                            title  = meal.label(),
                            action = {
                                val largerHeader = mealPrefs?.get(ch.nutrisnap.app.ui.theme.KEY_TOGGLE_TOUCH_MEAL_HEADER) ?: true
                                val headerBtnSize = if (largerHeader) 40.dp else 28.dp
                                val headerIconSize = if (largerHeader) 22.dp else 18.dp
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val splitMap = ch.nutrisnap.app.data.model.parseMealSplit(
                                        mealPrefs?.get(ch.nutrisnap.app.ui.theme.KEY_MEAL_SPLIT)
                                    )
                                    val mealTarget = ch.nutrisnap.app.data.model.mealKcalTarget(
                                        state.calorieGoal.toInt(), meal, splitMap
                                    )
                                    Text(
                                        if (mealTarget > 0) "$mealKcal / $mealTarget kcal" else "$mealKcal kcal",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    IconButton(
                                        onClick = { scheduleMeal = meal },
                                        modifier = Modifier.size(headerBtnSize)
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Mahlzeit auf mehrere Tage kopieren",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(headerIconSize)
                                        )
                                    }
                                    IconButton(
                                        onClick = { expandedNutrition = if (expandedNutrition == meal) null else meal },
                                        modifier = Modifier.size(headerBtnSize)
                                    ) {
                                        Icon(
                                            if (expandedNutrition == meal) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = "Nährwerte anzeigen",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(if (largerHeader) 24.dp else 20.dp)
                                        )
                                    }
                                }
                            }
                        )
                    }
                    if (expandedNutrition == meal) {
                        item(key = "meal_nutrition_${meal.name}") {
                            NutritionFactsProgress(
                                calories = mealEntries.sumOf { it.calories.toDouble() }.toFloat(),
                                caloriesGoal = state.calorieGoal,
                                carbs    = mealEntries.sumOf { it.carbs.toDouble() }.toFloat(),
                                carbsGoal = state.carbsGoal,
                                protein  = mealEntries.sumOf { it.protein.toDouble() }.toFloat(),
                                proteinGoal = state.proteinGoal,
                                fat      = mealEntries.sumOf { it.fat.toDouble() }.toFloat(),
                                fatGoal  = state.fatGoal,
                                modifier = Modifier.padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.xs)
                            )
                        }
                    }
                    item(key = "meal_group_${meal.name}") {
                        ReorderableMealEntries(
                            entries   = mealEntries,
                            onEdit    = { detailEntry = it; vm.loadEntryDetail(it) },
                            onDelete  = { entry ->
                                vm.deleteEntry(entry)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "\"${entry.foodName}\" gelöscht",
                                        actionLabel = "Rückgängig",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) vm.restoreEntry(entry)
                                }
                            },
                            onSchedule = { scheduleEntry = it },
                            onReorder = { ids -> vm.reorderEntries(ids) }
                        )
                    }
                }
            }
            // Schnellzugriffe zum Hinzufügen stehen bewusst unter dem Tagebuch: wer die
            // App öffnet, will primär sehen was schon erfasst ist, nicht erst an
            // Vorschlägen vorbeiscrollen.
            if (quickAddFavorites.isNotEmpty()) {
                item {
                    QuickAddBar(
                        favorites = quickAddFavorites,
                        onQuickAdd = { food ->
                            val grams = FoodPortionPresets.forFood(food).firstOrNull()?.grams
                                ?: food.servingSize.takeIf { it > 0f } ?: 100f
                            val meal = defaultMealForNow()
                            vm.quickAddFavorite(food, grams, meal) { entry ->
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "\"${food.name}\" (${grams.toInt()} g) hinzugefügt",
                                        actionLabel = "Rückgängig",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) vm.deleteEntry(entry)
                                }
                            }
                        }
                    )
                }
            }
            if (recipesState.recipes.isNotEmpty()) {
                item {
                    RecipeQuickAddBar(
                        recipes = recipesState.recipes,
                        onQuickAdd = { recipe ->
                            val meal = defaultMealForNow()
                            vm.addRecipeAsMeal(recipe, 1f, meal)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "\"${recipe.title}\" hinzugefügt",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    )
                }
            }
        }
        } // AdaptiveContent
    }

    if (showAddSheet) AddFoodSheet(
        vm = vm,
        initialMeal = initialMeal,
        autoOpenScanner = autoOpenScanner,
        onNavigateToPhotoScan = {
            showAddSheet = false
            onNavigateToPhotoScan(initialMeal ?: defaultMealForNow())
        },
        onDismiss = { showAddSheet = false }
    )

    detailEntry?.let { entry ->
        // Immer den aktuellen Eintrag aus dem State nehmen (nicht die evtl. veraltete
        // Closure-Referenz), damit ein Makro-Override sofort sichtbar wird.
        val liveEntry = state.entries.firstOrNull { it.id == entry.id } ?: entry
        val foodItem by vm.entryDetailFood.collectAsStateWithLifecycle()
        val detailRecipe by vm.entryDetailRecipe.collectAsStateWithLifecycle()
        EntryDetailSheet(
            entry     = liveEntry,
            foodItem  = foodItem,
            recipe    = detailRecipe,
            onEdit    = { editEntry = liveEntry; detailEntry = null; vm.clearEntryDetail() },
            onEditMacro = { field -> macroEditField = field },
            onDismiss = { detailEntry = null; vm.clearEntryDetail() }
        )
        macroEditField?.let { field ->
            MacroEditSheet(
                entry = liveEntry,
                field = field,
                onSave = { newValue -> vm.setGlobalMacroOverride(liveEntry, field, newValue); macroEditField = null },
                onRemoveOverride = { vm.clearGlobalOverride(liveEntry); macroEditField = null },
                onDismiss = { macroEditField = null }
            )
        }
    }

    scheduleEntry?.let { entry ->
        EntryScheduleSheet(
            entry = entry,
            currentDate = state.selectedDate,
            onDismiss = { scheduleEntry = null },
            onMove = { date, meal ->
                vm.moveEntry(entry, date, meal) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "Verschoben auf ${date.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM."))} · ${meal.label()}"
                        )
                    }
                }
                scheduleEntry = null
            },
            onCopyDays = { days, start, meal, includeStart ->
                vm.copyEntryToDays(entry, days, start, meal, includeStart) { n ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (n > 0) "$n× „${entry.foodName}“ kopiert" else "Nichts kopiert"
                        )
                    }
                }
                scheduleEntry = null
            }
        )
    }

    scheduleMeal?.let { meal ->
        MealCopySheet(
            meal = meal,
            sourceDate = state.selectedDate,
            onDismiss = { scheduleMeal = null },
            onConfirm = { days ->
                vm.copyMealToDays(meal, state.selectedDate, days, includeStart = false) { n ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (n > 0) "$n Einträge auf $days Tage verteilt" else "Keine Einträge"
                        )
                    }
                }
                scheduleMeal = null
            }
        )
    }

    editEntry?.let { entry ->
        EditEntryDialog(
            entry    = entry,
            onSave   = { newAmount -> vm.updateEntryAmount(entry, newAmount); editEntry = null },
            onDelete = { vm.deleteEntry(entry); editEntry = null },
            onDismiss = { editEntry = null }
        )
    }
}

@Composable
private fun EditEntryDialog(
    entry: DiaryEntry,
    onSave: (Float) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    // Gramm-Tracking nur bei echten Rezept-Gramm (≥10); sonst Portionen für Rezept/Manual.
    val isGramTracked = entry.isGramTrackedRecipe
    val isPortionUnit = entry.isPortionTracked && !isGramTracked
    // "baseValue" ist die Menge in der Einheit, in der amountText editiert wird.
    val baseValue = when {
        isGramTracked -> entry.recipeGrams!!
        entry.amountGrams > 0f -> entry.amountGrams
        else -> 1f // Manual ohne Menge = 1 Portion Basis
    }
    var amountText by remember { mutableStateOf(
        when {
            isGramTracked -> entry.recipeGrams!!.toInt().toString()
            entry.amountGrams > 0f && isPortionUnit ->
                if (entry.amountGrams == entry.amountGrams.toInt().toFloat()) entry.amountGrams.toInt().toString()
                else entry.amountGrams.toString()
            isPortionUnit -> "1" // Manual: relative Portionsbasis
            else -> entry.amountGrams.toInt().toString()
        }
    ) }
    val unit = if (isGramTracked) "g" else if (isPortionUnit) "Port." else "g"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                entry.foodName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NutriSpacing.md)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Menge ($unit)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val amount = parseGramsInput(amountText) ?: 0f
                if (amount > 0f && baseValue > 0f) {
                    val factor = amount / baseValue
                    Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.md)) {
                        Text(
                            "${(entry.calories * factor).toInt()} kcal",
                            fontWeight = FontWeight.SemiBold,
                            color = MacroColors.calories
                        )
                        Text("P ${(entry.protein * factor).toInt()}g", fontSize = 12.sp, color = MacroColors.protein)
                        Text("K ${(entry.carbs * factor).toInt()}g", fontSize = 12.sp, color = MacroColors.carbs)
                        Text("F ${(entry.fat * factor).toInt()}g", fontSize = 12.sp, color = MacroColors.fat)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val v = parseGramsInput(amountText)
                if (v != null && v > 0f) {
                    // Bei Gramm-Erfassung: eingegebene Gramm → äquivalenter Portionsfaktor
                    // (updateEntryAmount skaliert immer über amountGrams).
                    val toSave = if (isGramTracked && entry.recipeGrams != null && entry.recipeGrams > 0f) {
                        v / entry.recipeGrams * entry.amountGrams
                    } else {
                        v
                    }
                    onSave(toSave)
                }
            }) { Text("Speichern") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
                TextButton(onClick = onDelete) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDetailSheet(
    entry: DiaryEntry,
    foodItem: ch.nutrisnap.app.data.model.FoodItem?,
    recipe: ch.nutrisnap.app.data.model.Recipe? = null,
    onEdit: () -> Unit,
    onEditMacro: (MacroField) -> Unit,
    onDismiss: () -> Unit
) {
    val factor = entry.amountGrams / 100f
    val micros = remember(foodItem, entry.amountGrams) {
        buildMap<String, Float> {
            if (foodItem == null) {
                // Kein verknüpftes FoodItem (z.B. Rezept- oder manueller Eintrag) —
                // auf die am DiaryEntry selbst gespeicherten Werte zurückfallen.
                if (entry.fiber > 0f) put("fiber", entry.fiber)
                if (entry.sugar > 0f) put("sugar", entry.sugar)
                if (entry.saturatedFat > 0f) put("saturatedFat", entry.saturatedFat)
                if (entry.salt > 0f) put("salt", entry.salt)
                if (entry.sodium > 0f) put("sodium", entry.sodium)
            }
            foodItem?.let { f ->
                f.fiber?.let { put("fiber", it * factor) }
                f.sugar?.let { put("sugar", it * factor) }
                f.saturatedFat?.let { put("saturatedFat", it * factor) }
                f.monoFat?.let { put("monoFat", it * factor) }
                f.polyFat?.let { put("polyFat", it * factor) }
                f.transFat?.let { put("transFat", it * factor) }
                f.salt?.let { put("salt", it * factor) }
                f.sodium?.let { put("sodium", it * factor) }
                f.alcohol?.let { put("alcohol", it * factor) }
                f.cholesterol?.let { put("cholesterol", it * factor) }
                f.water?.let { put("water", it * factor) }
                f.vitaminA?.let { put("vitaminA", it * factor) }
                f.vitaminB1?.let { put("vitaminB1", it * factor) }
                f.vitaminB2?.let { put("vitaminB2", it * factor) }
                f.vitaminB3?.let { put("vitaminB3", it * factor) }
                f.vitaminB5?.let { put("vitaminB5", it * factor) }
                f.vitaminB6?.let { put("vitaminB6", it * factor) }
                f.vitaminB7?.let { put("vitaminB7", it * factor) }
                f.vitaminB11?.let { put("vitaminB11", it * factor) }
                f.vitaminB12?.let { put("vitaminB12", it * factor) }
                f.vitaminC?.let { put("vitaminC", it * factor) }
                f.vitaminD?.let { put("vitaminD", it * factor) }
                f.vitaminE?.let { put("vitaminE", it * factor) }
                f.vitaminK?.let { put("vitaminK", it * factor) }
                f.potassium?.let { put("potassium", it * factor) }
                f.calcium?.let { put("calcium", it * factor) }
                f.iron?.let { put("iron", it * factor) }
                f.magnesium?.let { put("magnesium", it * factor) }
                f.zinc?.let { put("zinc", it * factor) }
                f.phosphorus?.let { put("phosphorus", it * factor) }
                f.copper?.let { put("copper", it * factor) }
                f.manganese?.let { put("manganese", it * factor) }
                f.fluoride?.let { put("fluoride", it * factor) }
                f.iodine?.let { put("iodine", it * factor) }
                f.selenium?.let { put("selenium", it * factor) }
                f.chromium?.let { put("chromium", it * factor) }
                f.molybdenum?.let { put("molybdenum", it * factor) }
                f.chloride?.let { put("chloride", it * factor) }
                f.choline?.let { put("choline", it * factor) }
                f.arsenic?.let { put("arsenic", it * factor) }
                f.boron?.let { put("boron", it * factor) }
                f.cobalt?.let { put("cobalt", it * factor) }
                f.rubidium?.let { put("rubidium", it * factor) }
                f.silicon?.let { put("silicon", it * factor) }
                f.sulfur?.let { put("sulfur", it * factor) }
                f.tin?.let { put("tin", it * factor) }
                f.vanadium?.let { put("vanadium", it * factor) }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = NutriSpacing.lg)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = NutriSpacing.lg)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    entry.foodName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "Menge bearbeiten")
                }
            }
            val showPortionLabel = entry.isPortionTracked || entry.isRecipeEntry
            Text(
                if (showPortionLabel) recipeAmountLabel(entry)
                else "${entry.amountGrams.toInt()} g",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (entry.isGloballyOverridden) {
                Spacer(Modifier.height(NutriSpacing.xs))
                Text(
                    "Gesamtwert manuell gesetzt – Zutaten wurden nicht verändert",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(Modifier.height(NutriSpacing.md))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                EntryMacroItem("Kalorien", "${entry.calories.toInt()}", "kcal", MacroColors.calories, entry.isGloballyOverridden) { onEditMacro(MacroField.CALORIES) }
                EntryMacroItem("Protein", "${entry.protein.toInt()}", "g", MacroColors.protein, entry.isGloballyOverridden) { onEditMacro(MacroField.PROTEIN) }
                EntryMacroItem("Kohlenhy.", "${entry.carbs.toInt()}", "g", MacroColors.carbs, entry.isGloballyOverridden) { onEditMacro(MacroField.CARBS) }
                EntryMacroItem("Fett", "${entry.fat.toInt()}", "g", MacroColors.fat, entry.isGloballyOverridden) { onEditMacro(MacroField.FAT) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Lange drücken zum direkten Anpassen eines Werts",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = NutriSpacing.xs)
                )
                TextButton(onClick = { onEditMacro(MacroField.FIBER) }) {
                    Text("Ballaststoffe", fontSize = 11.sp)
                }
            }
            if (micros.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = NutriSpacing.md))
                MicronutrientTable(micros, ratio = 1f)
            } else if (entry.isFoodEntry) {
                Spacer(Modifier.height(NutriSpacing.md))
                Text(
                    "Keine Mikronährstoffe für diesen Eintrag verfügbar.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Zutaten bei Rezept-Einträgen (skaliert auf getrackte Menge)
            if (entry.isRecipeEntry) {
                HorizontalDivider(Modifier.padding(vertical = NutriSpacing.md))
                EntryRecipeIngredientsSection(entry = entry, recipe = recipe)
            }
        }
    }
}

/**
 * Skaliert eine Zutatenzeile proportional zum getrackten Portionsfaktor.
 * (Gleiche Heuristik wie im Kochmodus: fuehrende Mengenangabe * Faktor.)
 */
private val entryIngredientQtyRegex =
    Regex("""^\s*(\d+/\d+|\d+(?:[.,]\d+)?)(\s*)(.*)$""", RegexOption.DOT_MATCHES_ALL)

private fun scaleEntryIngredientLine(line: String, factor: Double): String {
    val match = entryIngredientQtyRegex.find(line) ?: return line
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
    val rounded = kotlin.math.round(scaled * 100) / 100.0
    val formatted = if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString().trimEnd('0').trimEnd('.').replace(".", ",")
    }
    return "$formatted$spacer$rest"
}

@Composable
private fun EntryRecipeIngredientsSection(
    entry: DiaryEntry,
    recipe: ch.nutrisnap.app.data.model.Recipe?
) {
    var expanded by remember { mutableStateOf(true) }
    val lines = remember(recipe?.id, recipe?.ingredients) {
        recipe?.ingredients
            ?.lineSequence()
            ?.map {
                it.trim()
                    .removePrefix("•").removePrefix("-").removePrefix("*")
                    .removePrefix("·").trim()
            }
            ?.filter { it.isNotBlank() }
            ?.toList()
            .orEmpty()
    }
    // amountGrams = Portionsfaktor relativ zu 1 Rezept-Portion;
    // ingredients-Text bezieht sich auf das ganze Rezept (servings Portionen).
    val scaleFactor = remember(entry.amountGrams, recipe?.servings) {
        val servings = (recipe?.servings ?: 1).coerceAtLeast(1).toDouble()
        (entry.amountGrams.toDouble() / servings).coerceAtLeast(0.01)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Zutaten", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                when {
                    recipe == null -> "Rezept nicht mehr in der Bibliothek"
                    entry.isGramTrackedRecipe ->
                        "für ${entry.recipeGrams!!.toInt()} g (×${"%.2f".format(scaleFactor).trimEnd('0').trimEnd('.')})"
                    else ->
                        "für ${formatPortionAmount(entry.amountGrams)} (×${"%.2f".format(scaleFactor).trimEnd('0').trimEnd('.')})"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Zuklappen" else "Aufklappen",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (expanded) {
        Spacer(Modifier.height(NutriSpacing.sm))
        if (recipe == null) {
            Text(
                "Das Original-Rezept wurde gelöscht – Zutaten nicht verfügbar.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (lines.isEmpty()) {
            Text(
                "Keine Zutaten hinterlegt.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            lines.forEach { line ->
                val display = scaleEntryIngredientLine(line, scaleFactor)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        "•",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp, top = 1.dp)
                    )
                    Text(
                        display,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            // Makro-Zusammenfassung der getrackten Menge (bereits im Header, hier als Kontext)
            Spacer(Modifier.height(NutriSpacing.sm))
            Text(
                "${entry.calories.toInt()} kcal · P ${entry.protein.toInt()} · K ${entry.carbs.toInt()} · F ${entry.fat.toInt()}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EntryMacroItem(
    label: String,
    value: String,
    unit: String,
    color: Color,
    isOverridden: Boolean = false,
    onLongPress: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (onLongPress != null) {
            Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)
        } else Modifier
    ) {
        Text(
            value,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = color
        )
        Text(unit, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isOverridden) ManualOverrideBadge()
    }
}

@Composable
private fun RecipeQuickAddBar(recipes: List<Recipe>, onQuickAdd: (Recipe) -> Unit) {
    Column(Modifier.padding(top = NutriSpacing.xs, bottom = NutriSpacing.xs)) {
        Text(
            "\uD83C\uDF73 Rezepte",
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = NutriSpacing.lg)
                .padding(bottom = NutriSpacing.sm)
        )
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = NutriSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)
        ) {
            recipes.take(10).forEach { recipe ->
                Column(
                    Modifier
                        .clip(RoundedCornerShape(NutriRadius.md))
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                        .clickable { onQuickAdd(recipe) }
                        .padding(horizontal = NutriSpacing.md, vertical = NutriSpacing.sm)
                        .widthIn(max = 110.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        recipe.title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    recipe.totalCalories?.let { total ->
                        val per = (total / recipe.servings.coerceAtLeast(1)).toInt()
                        Text(
                            "$per kcal/Port.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickAddBar(favorites: List<FoodItem>, onQuickAdd: (FoodItem) -> Unit) {
    Column(Modifier.padding(top = NutriSpacing.xs, bottom = NutriSpacing.xs)) {
        Text(
            "\u26A1 Schnell hinzufügen",
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = NutriSpacing.lg)
                .padding(bottom = NutriSpacing.sm)
        )
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = NutriSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)
        ) {
            favorites.take(10).forEach { food ->
                Column(
                    Modifier
                        .clip(RoundedCornerShape(NutriRadius.md))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        .clickable { onQuickAdd(food) }
                        .padding(horizontal = NutriSpacing.md, vertical = NutriSpacing.sm)
                        .widthIn(max = 110.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        food.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "${food.calories?.toInt() ?: "–"} kcal/100g",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateNavigator(
    date: LocalDate,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPick: (LocalDate) -> Unit = {}
) {
    var showPicker by remember { mutableStateOf(false) }
    val zone = java.time.ZoneId.systemDefault()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.sm, vertical = NutriSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        IconButton(onClick = { onPick(date.minusDays(7)) }) {
            Icon(Icons.Default.KeyboardDoubleArrowLeft, "−7 Tage")
        }
        IconButton(onClick = onPrev) {
            Icon(Icons.Default.ChevronLeft, "Vorheriger Tag")
        }
        val label = when (date) {
            LocalDate.now()              -> "Heute"
            LocalDate.now().minusDays(1) -> "Gestern"
            LocalDate.now().plusDays(1)  -> "Morgen"
            else -> date.format(DateTimeFormatter.ofPattern("EEE, dd. MMM", Locale.GERMAN))
        }
        TextButton(onClick = { showPicker = true }) {
            Icon(
                Icons.Default.CalendarMonth,
                contentDescription = "Kalender",
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        // Planung bis 30 Tage in die Zukunft erlauben
        val maxFuture = LocalDate.now().plusDays(30)
        IconButton(onClick = onNext, enabled = date.isBefore(maxFuture)) {
            Icon(Icons.Default.ChevronRight, "Nächster Tag")
        }
        IconButton(
            onClick = { onPick(minOf(date.plusDays(7), maxFuture)) },
            enabled = date.isBefore(maxFuture)
        ) {
            Icon(Icons.Default.KeyboardDoubleArrowRight, "+7 Tage")
        }
    }

    if (showPicker) {
        val initialMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = java.time.Instant.ofEpochMilli(utcTimeMillis)
                        .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    val max = LocalDate.now().plusDays(30)
                    val min = LocalDate.now().minusYears(2)
                    return !d.isAfter(max) && !d.isBefore(min)
                }
            }
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        val picked = java.time.Instant.ofEpochMilli(ms)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        onPick(picked)
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Abbrechen") }
            }
        ) {
            DatePicker(state = pickerState, title = {
                Text(
                    "Tag wählen",
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp)
                )
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiaryEntryRow(
    entry: DiaryEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSchedule: () -> Unit = {},
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier? = null
) {
    var showConfirm by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs by context.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val largerDiaryIcons = prefs?.get(ch.nutrisnap.app.ui.theme.KEY_TOGGLE_TOUCH_DIARY_ICONS) ?: true
    val diaryIconBtnSize = if (largerDiaryIcons) 40.dp else 32.dp

    val isRecipeEntry = looksLikePortionEntry(entry)
    val amountLabel   = if (isRecipeEntry) recipeAmountLabel(entry)
                         else "${entry.amountGrams.toInt()} g"

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) showConfirm = true
            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.lg, vertical = 3.dp),
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(NutriRadius.md))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = NutriSpacing.xl),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.DeleteOutline, "Löschen",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) {
        Card(
            Modifier
                .fillMaxWidth()
                .animateContentSize()
                .clickable { onEdit() },
            shape  = RoundedCornerShape(NutriRadius.md),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(0.5.dp)
        ) {
            Row(
                Modifier.padding(horizontal = NutriSpacing.md, vertical = NutriSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.foodName,
                        fontWeight = FontWeight.Medium,
                        fontSize   = 14.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        amountLabel,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(NutriSpacing.sm))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${entry.calories.toInt()} kcal",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MacroColors.calories
                    )
                    Text(
                        "P ${entry.protein.toInt()}  K ${entry.carbs.toInt()}  F ${entry.fat.toInt()}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(diaryIconBtnSize)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Mehr",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Menge bearbeiten") },
                            onClick = { showMenu = false; onEdit() },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Verschieben / kopieren…") },
                            onClick = { showMenu = false; onSchedule() },
                            leadingIcon = { Icon(Icons.Default.Event, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Löschen") },
                            onClick = { showMenu = false; showConfirm = true },
                            leadingIcon = {
                                Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                            }
                        )
                    }
                }
                if (dragHandleModifier != null) {
                    Icon(
                        Icons.Default.DragHandle, "Reihenfolge ändern",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .padding(start = NutriSpacing.xs)
                            .size(20.dp)
                            .then(dragHandleModifier)
                    )
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title   = { Text("Eintrag löschen?") },
            text    = { Text(entry.foodName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showConfirm = false }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Abbrechen") }
            }
        )
    }

    LaunchedEffect(showConfirm) {
        if (!showConfirm) dismissState.reset()
    }
}

private const val DIARY_ROW_HEIGHT_DP = 62

@Composable
private fun ReorderableMealEntries(
    entries: List<DiaryEntry>,
    onEdit: (DiaryEntry) -> Unit,
    onDelete: (DiaryEntry) -> Unit,
    onSchedule: (DiaryEntry) -> Unit = {},
    onReorder: (List<Long>) -> Unit
) {
    var items by remember(entries.map { it.id }) { mutableStateOf(entries) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { DIARY_ROW_HEIGHT_DP.dp.toPx() }

    Column {
        items.forEach { entry ->
            val isDragging = entry.id == draggingId
            DiaryEntryRow(
                entry    = entry,
                onEdit   = { onEdit(entry) },
                onDelete = { onDelete(entry) },
                onSchedule = { onSchedule(entry) },
                modifier = Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f },
                dragHandleModifier = Modifier.pointerInput(entry.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { draggingId = entry.id; dragOffset = 0f },
                        onDragEnd   = {
                            draggingId = null
                            dragOffset = 0f
                            onReorder(items.map { it.id })
                        },
                        onDragCancel = { draggingId = null; dragOffset = 0f },
                        onDrag = { change, delta ->
                            change.consume()
                            dragOffset += delta.y
                            val fromIndex = items.indexOfFirst { it.id == entry.id }
                            val toIndex = (fromIndex + (dragOffset / rowHeightPx).roundToInt())
                                .coerceIn(0, items.lastIndex)
                            if (toIndex != fromIndex) {
                                items = items.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                                dragOffset -= (toIndex - fromIndex) * rowHeightPx
                            }
                        }
                    )
                }
            )
        }
    }
}

enum class AddFoodTab { SEARCH, AI, MANUAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodSheet(
    vm: DiaryViewModel,
    initialMeal: MealType? = null,
    autoOpenScanner: Boolean = false,
    onNavigateToPhotoScan: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var activeTab    by remember { mutableStateOf(AddFoodTab.SEARCH) }
    var showScanner  by remember { mutableStateOf(autoOpenScanner) }
    var barcodeStatus by remember { mutableStateOf("") }

    var pendingUnknownBarcode by remember { mutableStateOf<String?>(null) }
    var labelCaptureStep by remember { mutableStateOf(0) } // 0=none, 1=first photo, 2=optional second
    var firstLabelBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isSavingProduct by remember { mutableStateOf(false) }

    if (showScanner) {
        BarcodeScannerScreen(
            onBarcodeDetected = { barcode ->
                showScanner = false
                barcodeStatus = "Suche Barcode $barcode..."
                vm.searchBarcode(barcode) { food ->
                    if (food != null) {
                        activeTab = AddFoodTab.SEARCH
                        barcodeStatus = ""
                        vm.setBarcodeResult(food)
                    } else {
                        barcodeStatus = "Barcode $barcode nicht gefunden"
                        pendingUnknownBarcode = barcode
                    }
                }
            },
            onNavigateBack = { showScanner = false }
        )
        return
    }

    // Unbekanntes Produkt: Nährwerttabelle fotografieren (1–2 Fotos)
    if (labelCaptureStep > 0 && pendingUnknownBarcode != null) {
        PhotoCaptureScreen(
            title = if (labelCaptureStep == 1) "Nährwerttabelle fotografieren" else "Zweites Foto (optional)",
            instructions = if (labelCaptureStep == 1)
                "Barcode $pendingUnknownBarcode – Nährwerttabelle (pro 100g) scharf fotografieren"
            else
                "Falls Name/Werte auf der anderen Seite: jetzt fotografieren, oder zurück",
            onPhotoCaptured = { bitmap ->
                if (labelCaptureStep == 1) {
                    firstLabelBitmap = bitmap
                    labelCaptureStep = 2
                } else {
                    isSavingProduct = true
                    val meal = initialMeal ?: defaultMealForNow()
                    vm.captureUnknownProduct(
                        barcode = pendingUnknownBarcode!!,
                        labelBitmap = firstLabelBitmap!!,
                        secondBitmap = bitmap,
                        meal = meal,
                        amountGrams = 100f
                    ) { food ->
                        isSavingProduct = false
                        labelCaptureStep = 0
                        firstLabelBitmap = null
                        pendingUnknownBarcode = null
                        barcodeStatus = if (food != null)
                            "„${food.name}“ gespeichert & eingetragen"
                        else
                            "Etikett konnte nicht gelesen werden"
                    }
                }
            },
            onNavigateBack = {
                if (labelCaptureStep == 2 && firstLabelBitmap != null) {
                    // Ohne zweites Foto speichern
                    isSavingProduct = true
                    val meal = initialMeal ?: defaultMealForNow()
                    vm.captureUnknownProduct(
                        barcode = pendingUnknownBarcode!!,
                        labelBitmap = firstLabelBitmap!!,
                        secondBitmap = null,
                        meal = meal,
                        amountGrams = 100f
                    ) { food ->
                        isSavingProduct = false
                        labelCaptureStep = 0
                        firstLabelBitmap = null
                        pendingUnknownBarcode = null
                        barcodeStatus = if (food != null)
                            "„${food.name}“ gespeichert & eingetragen"
                        else
                            "Etikett konnte nicht gelesen werden"
                    }
                } else {
                    labelCaptureStep = 0
                    firstLabelBitmap = null
                }
            }
        )
        return
    }

    val density = LocalDensity.current
    // rememberUpdatedState: confirmValueChange-Lambda hält sonst stale imeVisible
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val imeVisibleState = rememberUpdatedState(imeVisible)
    // Tastatur offen: Sheet nicht per Gesture schließen (sonst Crashes/Dismiss beim Öffnen)
    val addSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { newValue ->
            if (imeVisibleState.value && newValue == SheetValue.Hidden) false else true
        }
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = addSheetState,
        // Insets selbst handhaben (imePadding unten), sonst doppelte/kämpfende Resize-Logik
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NutriSpacing.lg)
                .padding(bottom = NutriSpacing.xl)
        ) {
            Text(
                "Eintrag hinzufügen",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = NutriSpacing.sm)
            )
            // Nachträglich tracken: Tag wählen (nutzt dieselbe Datums-Navigation wie das Tagebuch)
            val diaryDate by vm.uiState.collectAsStateWithLifecycle()
            val activeDate = diaryDate.selectedDate
            Text("Tag", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = NutriSpacing.md)
            ) {
                val today = java.time.LocalDate.now()
                listOf(
                    today to "Heute",
                    today.plusDays(1) to "Morgen",
                    today.plusDays(2) to "+2 Tage",
                    today.minusDays(1) to "Gestern"
                ).forEach { (d, label) ->
                    FilterChip(
                        selected = activeDate == d,
                        onClick = { vm.setDate(d) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
            if (activeDate != java.time.LocalDate.now()) {
                Text(
                    "Wird für ${activeDate.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))} gespeichert",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = NutriSpacing.sm)
                )
            }

            // Direkte Einstiege: Foto ist der kürzeste Weg zum Tracken per Kamera
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = NutriSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)
            ) {
                OutlinedButton(
                    onClick = onNavigateToPhotoScan,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(NutriSpacing.xs))
                    Text("Foto")
                }
                OutlinedButton(
                    onClick = { showScanner = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.QrCodeScanner, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(NutriSpacing.xs))
                    Text("Barcode")
                }
            }

            ScrollableTabRow(
                selectedTabIndex = activeTab.ordinal,
                edgePadding = 0.dp
            ) {
                Tab(
                    selected = activeTab == AddFoodTab.SEARCH,
                    onClick  = { activeTab = AddFoodTab.SEARCH },
                    text     = { Text("Suche") },
                    icon     = { Icon(Icons.Default.Search, null, Modifier.size(16.dp)) }
                )
                Tab(
                    selected = activeTab == AddFoodTab.AI,
                    onClick  = { activeTab = AddFoodTab.AI },
                    text     = { Text("KI schätzen") },
                    icon     = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)) }
                )
                Tab(
                    selected = activeTab == AddFoodTab.MANUAL,
                    onClick  = { activeTab = AddFoodTab.MANUAL },
                    text     = { Text("Manuell") },
                    icon     = { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) }
                )
            }

            Spacer(Modifier.height(NutriSpacing.md))

            if (pendingUnknownBarcode != null && labelCaptureStep == 0) {
                Card(
                    Modifier.fillMaxWidth().padding(bottom = NutriSpacing.sm),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Barcode $pendingUnknownBarcode nicht in der Datenbank",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            "Nährwerttabelle fotografieren – Produkt wird gespeichert und ist nächstes Mal per Barcode findbar.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { labelCaptureStep = 1 },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Etikett fotografieren")
                        }
                        TextButton(onClick = { pendingUnknownBarcode = null; barcodeStatus = "" }) {
                            Text("Abbrechen")
                        }
                    }
                }
            }
            if (isSavingProduct) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Produkt wird gespeichert…", fontSize = 13.sp)
                }
            }

            when (activeTab) {
                AddFoodTab.SEARCH -> SearchTab(
                    vm = vm,
                    initialMeal = initialMeal,
                    barcodeStatus = barcodeStatus,
                    onOpenScanner = { showScanner = true },
                    onSwitchToAi = { activeTab = AddFoodTab.AI },
                    onDismiss = onDismiss
                )
                AddFoodTab.AI -> AiEstimateTab(
                    vm = vm,
                    initialMeal = initialMeal,
                    onDismiss = onDismiss
                )
                AddFoodTab.MANUAL -> ManualEntryTab(
                    initialMeal = initialMeal,
                    onSave = { name, kcal, protein, carbs, fat, meal ->
                        vm.addManualEntry(name, kcal, protein, carbs, fat, meal)
                        onDismiss()
                    }
                )
            }

            Spacer(Modifier.height(NutriSpacing.xxxl))
        }
    }
}

@Composable
private fun SearchTab(
    vm: DiaryViewModel,
    initialMeal: MealType? = null,
    barcodeStatus: String,
    onOpenScanner: () -> Unit,
    onSwitchToAi: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var query        by remember { mutableStateOf("") }
    var selectedFood by remember { mutableStateOf<FoodItem?>(null) }
    var amountText   by remember { mutableStateOf("100") }
    var selectedMeal by remember { mutableStateOf(initialMeal ?: MealType.LUNCH) }
    var portionWarning by remember { mutableStateOf<String?>(null) }

    val results   by vm.searchResults.collectAsStateWithLifecycle()
    val searching by vm.isSearching.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val barcodeResult by vm.barcodeResult.collectAsStateWithLifecycle()
    val favoriteKeys = remember(favorites) { favorites.map { it.favoriteKey() }.toSet() }

    LaunchedEffect(barcodeResult) {
        barcodeResult?.let { selectedFood = it; vm.clearBarcodeResult() }
    }
    var rememberedGrams by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(selectedFood) {
        val food = selectedFood ?: return@LaunchedEffect
        rememberedGrams = null
        val last = vm.getLastAmount(food)
        if (last != null && last > 0f) {
            rememberedGrams = last
            amountText = if (last == last.toInt().toFloat()) last.toInt().toString() else "%.0f".format(last)
        }
    }

    if (selectedFood == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; vm.searchFood(it) },
                label = { Text("Suchen\u2026") },
                leadingIcon  = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(20.dp)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md)
            )
            IconButton(
                onClick = onOpenScanner,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .size(56.dp)
            ) {
                Icon(
                    Icons.Default.QrCodeScanner, "Barcode",
                    Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (barcodeStatus.isNotBlank()) {
            Text(
                barcodeStatus,
                fontSize = 13.sp,
                color = if ("nicht gefunden" in barcodeStatus)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = NutriSpacing.xs)
            )
        }

        if (query.isBlank() && favorites.isNotEmpty()) {
            Text(
                "\u2B50 Favoriten",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = NutriSpacing.md, bottom = NutriSpacing.xs)
            )
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(favorites, key = { it.favoriteKey() }) { food ->
                    FoodResultRow(
                        food     = food,
                        onClick  = { selectedFood = food },
                        leading  = {
                            Icon(
                                Icons.Default.Star, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }
        }

        if (results.isEmpty() && query.length > 1 && !searching) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = NutriSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Keine Treffer in der Datenbank",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onSwitchToAi,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Mit KI schätzen")
                }
            }
        }

        LazyColumn(Modifier.heightIn(max = 300.dp)) {
            items(results) { food ->
                val isFav = food.favoriteKey() in favoriteKeys
                FoodResultRow(
                    food     = food,
                    onClick  = { selectedFood = food },
                    trailingAction = {
                        IconButton(
                            onClick = { vm.toggleFavorite(food) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                null,
                                tint = if (isFav) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    } else {
        val food  = selectedFood!!
        val isFav = food.favoriteKey() in favoriteKeys
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    food.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                food.brand?.let {
                    Text(
                        it,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = { vm.toggleFavorite(food) }) {
                Icon(
                    if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null,
                    tint = if (isFav) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(NutriSpacing.md))
        val presets = remember(food) { ch.nutrisnap.app.domain.FoodPortionPresets.forFood(food) }
        val hasRemembered = rememberedGrams != null && rememberedGrams!! > 0f
        if (hasRemembered || presets.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                if (hasRemembered) {
                    val g = rememberedGrams!!
                    FilterChip(
                        selected = amountText.toFloatOrNull() == g,
                        onClick = {
                            amountText = if (g == g.toInt().toFloat()) g.toInt().toString() else "%.0f".format(g)
                        },
                        label = {
                            Text(
                                "Standard ${if (g == g.toInt().toFloat()) g.toInt() else "%.0f".format(g)} g",
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.History, null, Modifier.size(16.dp))
                        }
                    )
                }
                presets.forEach { preset ->
                    FilterChip(
                        selected = amountText.toFloatOrNull() == preset.grams,
                        onClick  = { amountText = preset.grams.toInt().toString() },
                        label    = { Text(preset.label, fontSize = 12.sp) }
                    )
                }
            }
            Spacer(Modifier.height(NutriSpacing.sm))
        }
        var showServingGuide by remember { mutableStateOf(false) }
        val grams = parseGramsInput(amountText) ?: 0f
        fun confirmAdd(andClose: Boolean) {
            if (grams <= 0f) return
            val warning = EntryPlausibilityChecker.checkPortion(grams)
            if (warning != null) {
                portionWarning = warning
            } else {
                vm.addEntryWithMemory(food, grams, selectedMeal)
                if (andClose) onDismiss()
                else {
                    selectedFood = null
                    amountText = "100"
                    query = ""
                    vm.searchFood("")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Menge (g)") },
                placeholder = { Text("z.B. 100 oder 100g") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { confirmAdd(andClose = false) }),
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md),
                trailingIcon = {
                    IconButton(onClick = { showServingGuide = true }) {
                        Icon(Icons.Default.Straighten, "Alltagseinheiten anzeigen")
                    }
                },
                supportingText = {
                    if (amountText.isNotBlank() && parseGramsInput(amountText) == null)
                        Text("Ungültige Menge – z.B. 100 oder 100g", color = MaterialTheme.colorScheme.error)
                },
                isError = amountText.isNotBlank() && parseGramsInput(amountText) == null
            )
            MealPicker(selected = selectedMeal) { selectedMeal = it }
        }
        if (showServingGuide) {
            ServingSizeGuideDialog(
                food = food,
                onSelect = { g -> amountText = g.toInt().toString(); showServingGuide = false },
                onDismiss = { showServingGuide = false }
            )
        }
        if (grams > 0) {
            Spacer(Modifier.height(NutriSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.lg)) {
                Text(
                    "${((food.calories ?: 0f) * grams / 100f).toInt()} kcal",
                    fontWeight = FontWeight.SemiBold,
                    color = MacroColors.calories
                )
                Text("P ${((food.protein ?: 0f) * grams / 100f).toInt()}g", fontSize = 13.sp, color = MacroColors.protein)
                Text("K ${((food.carbs ?: 0f) * grams / 100f).toInt()}g", fontSize = 13.sp, color = MacroColors.carbs)
                Text("F ${((food.fat ?: 0f) * grams / 100f).toInt()}g", fontSize = 13.sp, color = MacroColors.fat)
            }
        }
        Spacer(Modifier.height(NutriSpacing.lg))
        // Primär: + weiteres Lebensmittel (Sheet bleibt offen)
        Button(
            onClick = { confirmAdd(andClose = false) },
            modifier = Modifier.fillMaxWidth(),
            enabled = grams > 0
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Hinzufügen & weiteres")
        }
        Spacer(Modifier.height(NutriSpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
            OutlinedButton(
                onClick = { selectedFood = null },
                modifier = Modifier.weight(1f)
            ) {
                Text("Zurück")
            }
            OutlinedButton(
                onClick = { confirmAdd(andClose = true) },
                modifier = Modifier.weight(1f),
                enabled = grams > 0
            ) {
                Text("Hinzufügen & fertig")
            }
        }
        portionWarning?.let { warning ->
            AlertDialog(
                onDismissRequest = { portionWarning = null },
                title   = { Text("Menge prüfen") },
                text    = { Text(warning) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.addEntryWithMemory(food, grams, selectedMeal)
                        portionWarning = null
                        selectedFood = null
                        amountText = "100"
                        query = ""
                    }) { Text("Trotzdem + weiteres") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        vm.addEntryWithMemory(food, grams, selectedMeal)
                        portionWarning = null
                        onDismiss()
                    }) { Text("Trotzdem & fertig") }
                }
            )
        }
    }
}

/**
 * KI-Schätzung wie bei Rezept-Zutaten: zuerst lokale Referenz-DB,
 * sonst Groq/Gemini. Ergebnis pro 100g, Menge + Mahlzeit wählbar.
 */
@Composable
private fun AiEstimateTab(
    vm: DiaryViewModel,
    initialMeal: MealType? = null,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<FoodItem?>(null) }
    var sourceLabel by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var amountText by remember { mutableStateOf("100") }
    var selectedMeal by remember { mutableStateOf(initialMeal ?: MealType.LUNCH) }
    val scope = rememberCoroutineScope()

    fun runEstimate(name: String) {
        val q = name.trim()
        if (q.length < 2) return
        scope.launch {
            isLoading = true
            errorMsg = null
            result = null
            // 1) Lokale Referenz (USDA-nah) – nichts erfinden
            val local = ch.nutrisnap.app.domain.IngredientNutritionDatabase.lookup(q)
            if (local != null) {
                result = FoodItem(
                    name = q,
                    brand = "Referenzdatenbank",
                    calories = local.calories,
                    protein = local.protein,
                    carbs = local.carbs,
                    fat = local.fat,
                    fiber = local.fiber,
                    servingSize = 100f,
                    servingUnit = "g",
                    source = FoodSource.MANUAL,
                    completenessScore = 80
                )
                sourceLabel = "Lokale Referenz (USDA-nah)"
                isLoading = false
                return@launch
            }
            // 2) KI-Schätzung
            val estimated = runCatching {
                ch.nutrisnap.app.data.api.GroqFoodEstimatorApi.estimate(q)
            }.getOrNull()
            if (estimated != null) {
                result = estimated
                sourceLabel = "KI-Schätzung (nicht verifiziert)"
            } else {
                errorMsg = "Keine Schätzung möglich – probiere Suche oder Manuell."
            }
            isLoading = false
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NutriSpacing.md)
    ) {
        Text(
            "Name eingeben – zuerst Referenzwerte, sonst KI pro 100g",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Lebensmittel (z.B. Hähnchenbrust, Reis)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(NutriRadius.md),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(
                onSearch = { runEstimate(query) }
            ),
            trailingIcon = {
                IconButton(
                    onClick = { runEstimate(query) },
                    enabled = query.trim().length >= 2 && !isLoading
                ) {
                    if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.AutoAwesome, "Schätzen")
                }
            }
        )
        Button(
            onClick = { runEstimate(query) },
            enabled = query.trim().length >= 2 && !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (isLoading) "Schätze…" else "Mit KI schätzen")
        }

        errorMsg?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        result?.let { food ->
            Card(
                shape = RoundedCornerShape(NutriRadius.md),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(food.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        sourceLabel,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${food.calories?.toInt() ?: "–"} kcal", fontWeight = FontWeight.Bold, color = MacroColors.calories)
                        Text("P ${food.protein?.let { "%.1f".format(it) } ?: "–"} g", fontSize = 13.sp, color = MacroColors.protein)
                        Text("K ${food.carbs?.let { "%.1f".format(it) } ?: "–"} g", fontSize = 13.sp, color = MacroColors.carbs)
                        Text("F ${food.fat?.let { "%.1f".format(it) } ?: "–"} g", fontSize = 13.sp, color = MacroColors.fat)
                    }
                    Text("pro 100 g", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                    label = { Text("Menge g") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(NutriRadius.md)
                )
                MealPicker(selected = selectedMeal) { selectedMeal = it }
            }

            val grams = amountText.replace(',', '.').toFloatOrNull() ?: 0f
            val factor = grams / 100f
            if (grams > 0f) {
                val kcal = (food.calories ?: 0f) * factor
                val p = (food.protein ?: 0f) * factor
                val k = (food.carbs ?: 0f) * factor
                val f = (food.fat ?: 0f) * factor
                Text(
                    "→ ${kcal.toInt()} kcal · P ${p.toInt()}g · K ${k.toInt()}g · F ${f.toInt()}g",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Button(
                onClick = {
                    if (grams > 0f) {
                        vm.addEntryWithMemory(food, grams, selectedMeal)
                        onDismiss()
                    }
                },
                enabled = grams > 0f,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Ins Tagebuch")
            }
        }
    }
}

@Composable
private fun ManualEntryTab(
    initialMeal: MealType? = null,
    onSave: (name: String, kcal: Float, protein: Float, carbs: Float, fat: Float, meal: MealType) -> Unit
) {
    var name         by remember { mutableStateOf("") }
    var kcalText     by remember { mutableStateOf("") }
    var proteinText  by remember { mutableStateOf("") }
    var carbsText    by remember { mutableStateOf("") }
    var fatText      by remember { mutableStateOf("") }
    var selectedMeal by remember { mutableStateOf(initialMeal ?: MealType.LUNCH) }
    var manualWarning by remember { mutableStateOf<String?>(null) }

    val kcal    = kcalText.toFloatOrNull() ?: 0f
    val protein = proteinText.toFloatOrNull() ?: 0f
    val carbs   = carbsText.toFloatOrNull() ?: 0f
    val fat     = fatText.toFloatOrNull() ?: 0f
    val isValid = name.isNotBlank() && kcal > 0f

    Column(verticalArrangement = Arrangement.spacedBy(NutriSpacing.md)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Name (z.B. Müesli mit Früchten)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(NutriRadius.md)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
            OutlinedTextField(
                value = kcalText, onValueChange = { kcalText = it },
                label = { Text("kcal *") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md)
            )
            MealPicker(selected = selectedMeal) { selectedMeal = it }
        }

        Text(
            "Makros (optional)",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
            OutlinedTextField(
                value = proteinText, onValueChange = { proteinText = it },
                label = { Text("Protein g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md)
            )
            OutlinedTextField(
                value = carbsText, onValueChange = { carbsText = it },
                label = { Text("Kohlenhydrate g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md)
            )
            OutlinedTextField(
                value = fatText, onValueChange = { fatText = it },
                label = { Text("Fett g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md)
            )
        }

        if (isValid) {
            Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.lg)) {
                Text("${kcal.toInt()} kcal", fontWeight = FontWeight.SemiBold, color = MacroColors.calories)
                if (protein > 0) Text("P ${protein.toInt()}g", fontSize = 12.sp, color = MacroColors.protein)
                if (carbs > 0)   Text("K ${carbs.toInt()}g", fontSize = 12.sp, color = MacroColors.carbs)
                if (fat > 0)     Text("F ${fat.toInt()}g", fontSize = 12.sp, color = MacroColors.fat)
            }
        }

        Button(
            onClick  = {
                if (isValid) {
                    val warning = EntryPlausibilityChecker.checkManualEntry(kcal, protein, carbs, fat)
                    if (warning != null) manualWarning = warning
                    else onSave(name, kcal, protein, carbs, fat, selectedMeal)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled  = isValid
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(NutriSpacing.sm))
            Text("Manuell hinzufügen")
        }
        manualWarning?.let { warning ->
            AlertDialog(
                onDismissRequest = { manualWarning = null },
                title   = { Text("Werte prüfen") },
                text    = { Text(warning) },
                confirmButton = {
                    TextButton(onClick = {
                        onSave(name, kcal, protein, carbs, fat, selectedMeal)
                        manualWarning = null
                    }) { Text("Trotzdem speichern") }
                },
                dismissButton = {
                    TextButton(onClick = { manualWarning = null }) { Text("Anpassen") }
                }
            )
        }
    }
}

/**
 * Visueller Portionsgrössen-Guide: zeigt ~80 Alltagseinheiten (Tasse, Handvoll, Scheibe, ...)
 * mit Emoji-Referenzbild, Objektname und dem für [food] berechneten Kalorienwert, als
 * Alternative zur reinen Gramm-Eingabe. Ergänzt [FoodPortionPresets] um allgemeine,
 * lebensmittelunabhängige Masse.
 */
@Composable
private fun ServingSizeGuideDialog(
    food: FoodItem,
    onSelect: (grams: Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wie viel ungefähr?") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(EverydayServingSizes.ALL) { unit ->
                    val kcal = (food.calories ?: 0f) * unit.grams / 100f
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(unit.grams) }
                            .padding(vertical = NutriSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(unit.emoji, fontSize = 22.sp)
                            Spacer(Modifier.width(NutriSpacing.sm))
                            Column {
                                Text(unit.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "${unit.grams.toInt()} g",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            "${kcal.toInt()} kcal",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MacroColors.calories
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schliessen") }
        }
    )
}

@Composable
private fun FoodResultRow(
    food: FoodItem,
    onClick: () -> Unit,
    leading: @Composable () -> Unit = {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(NutriRadius.sm))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                food.name.firstOrNull()?.uppercase() ?: "?",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    },
    trailingAction: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = NutriSpacing.sm, horizontal = NutriSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(Modifier.width(NutriSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                food.name,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                food.brand ?: "${food.calories?.toInt() ?: "–"} kcal/100g",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(NutriSpacing.sm))
        Text(
            "${food.calories?.toInt() ?: "–"} kcal",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MacroColors.calories
        )
        trailingAction?.invoke()
    }
}

@Composable
private fun MealPicker(selected: MealType, onSelect: (MealType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected.label())
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MealType.values().forEach { meal ->
                DropdownMenuItem(
                    text    = { Text(meal.label()) },
                    onClick = { onSelect(meal); expanded = false }
                )
            }
        }
    }
}

private fun MealType.label() = when (this) {
    MealType.BREAKFAST -> "Frühstück"
    MealType.LUNCH     -> "Mittagessen"
    MealType.DINNER    -> "Abendessen"
    MealType.SNACK     -> "Snack"
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryScheduleSheet(
    entry: DiaryEntry,
    currentDate: java.time.LocalDate,
    onDismiss: () -> Unit,
    onMove: (java.time.LocalDate, MealType) -> Unit,
    onCopyDays: (dayCount: Int, start: java.time.LocalDate, meal: MealType?, includeStart: Boolean) -> Unit
) {
    // move = verschieben | copy = 1× kopieren | mealprep = N Tage (gleiche Portionen)
    var mode by remember { mutableStateOf("move") }
    val today = java.time.LocalDate.now()
    var selectedDate by remember {
        mutableStateOf(
            runCatching { java.time.LocalDate.parse(entry.dateStr) }.getOrDefault(currentDate)
        )
    }
    var selectedMeal by remember { mutableStateOf(entry.mealType) }
    var dayCount by remember { mutableIntStateOf(5) }

    fun applyQuick(date: java.time.LocalDate, meal: MealType) {
        when (mode) {
            "move" -> onMove(date, meal)
            "copy" -> onCopyDays(1, date, meal, true)
            else -> onCopyDays(dayCount, date, meal, true) // mealprep
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 24.dp)
        ) {
            Text("Verschieben / kopieren", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(entry.foodName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == "move", onClick = { mode = "move" }, label = { Text("Verschieben") })
                FilterChip(selected = mode == "copy", onClick = { mode = "copy" }, label = { Text("Kopieren") })
                FilterChip(selected = mode == "mealprep", onClick = { mode = "mealprep" }, label = { Text("Meal-Prep") })
            }
            Text(
                when (mode) {
                    "move" -> "Eintrag an einen anderen Tag/Mahlzeit verschieben"
                    "copy" -> "Einmalig an einen anderen Tag/Mahlzeit kopieren"
                    else -> "Gleiche Portion auf mehrere Tage verteilen (z. B. 5 Portionen vorgekocht)"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(12.dp))
            Text("Schnell", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { applyQuick(today.minusDays(1), MealType.DINNER) },
                    modifier = Modifier.weight(1f)
                ) { Text("Gestern Abend", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { applyQuick(today.minusDays(1), MealType.LUNCH) },
                    modifier = Modifier.weight(1f)
                ) { Text("Gestern Mittag", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { applyQuick(today.minusDays(2), MealType.DINNER) },
                    modifier = Modifier.weight(1f)
                ) { Text("Vorgestern Abend", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { applyQuick(today.minusDays(2), MealType.LUNCH) },
                    modifier = Modifier.weight(1f)
                ) { Text("Vorgestern Mittag", fontSize = 12.sp) }
            }

            Spacer(Modifier.height(12.dp))
            Text("Tag", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    today.minusDays(2) to "Vorgestern",
                    today.minusDays(1) to "Gestern",
                    today to "Heute",
                    today.plusDays(1) to "Morgen"
                ).forEach { (d, label) ->
                    FilterChip(
                        selected = selectedDate == d,
                        onClick = { selectedDate = d },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Mahlzeit", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MealType.entries.forEach { m ->
                    FilterChip(
                        selected = selectedMeal == m,
                        onClick = { selectedMeal = m },
                        label = { Text(m.label(), fontSize = 11.sp) }
                    )
                }
            }

            if (mode == "mealprep") {
                Spacer(Modifier.height(12.dp))
                Text("Anzahl Tage (Meal-Prep)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf(5, 7).forEach { n ->
                        FilterChip(
                            selected = dayCount == n,
                            onClick = { dayCount = n },
                            label = { Text("$n Tage") }
                        )
                    }
                    IconButton(onClick = { dayCount = (dayCount - 1).coerceAtLeast(2) }) {
                        Icon(Icons.Default.Remove, null)
                    }
                    Text("$dayCount", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    IconButton(onClick = { dayCount = (dayCount + 1).coerceAtMost(14) }) {
                        Icon(Icons.Default.Add, null)
                    }
                }
                Text(
                    "Kopiert ab ${selectedDate.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM."))} auf $dayCount Tage · ${selectedMeal.label()}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    when (mode) {
                        "move" -> onMove(selectedDate, selectedMeal)
                        "copy" -> onCopyDays(1, selectedDate, selectedMeal, true)
                        else -> onCopyDays(dayCount, selectedDate, selectedMeal, true)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when (mode) {
                        "move" -> "Verschieben"
                        "copy" -> "Kopieren"
                        else -> "Auf $dayCount Tage kopieren"
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealCopySheet(
    meal: MealType,
    sourceDate: java.time.LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (dayCount: Int) -> Unit
) {
    var dayCount by remember { mutableIntStateOf(5) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 24.dp)
        ) {
            Text("Mahlzeit kopieren", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "${meal.label()} · ab ${sourceDate.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM."))} auf Folgetage",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(5, 7).forEach { n ->
                    FilterChip(selected = dayCount == n, onClick = { dayCount = n }, label = { Text("$n Tage") })
                }
                IconButton(onClick = { dayCount = (dayCount - 1).coerceAtLeast(2) }) {
                    Icon(Icons.Default.Remove, null)
                }
                Text("$dayCount", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                IconButton(onClick = { dayCount = (dayCount + 1).coerceAtMost(14) }) {
                    Icon(Icons.Default.Add, null)
                }
            }
            Text(
                "Alle Einträge dieser Mahlzeit werden auf die nächsten ${dayCount - 1} Tage kopiert (ohne heute nochmal).",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onConfirm(dayCount) }, modifier = Modifier.fillMaxWidth()) {
                Text("Auf $dayCount Tage kopieren")
            }
        }
    }
}

@Composable
private fun AutopilotBanner(
    templates: List<ch.nutrisnap.app.data.model.MealTemplate>,
    onApply: (ch.nutrisnap.app.data.model.MealTemplate) -> Unit
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.sm),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        )
    ) {
        Column(Modifier.padding(NutriSpacing.md)) {
            Text("Wochen-Autopilot", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                "Mo–Fr · Vorlagen für leere Mahlzeiten",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            templates.forEach { t ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text(t.mealType.label(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = { onApply(t) }) {
                        Text("Übernehmen", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
