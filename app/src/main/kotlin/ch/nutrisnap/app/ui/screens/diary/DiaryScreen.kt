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
internal fun formatPortionAmount(amount: Float): String {
    val text = if (amount == amount.toInt().toFloat()) amount.toInt().toString() else "%.1f".format(amount)
    return "$text Portion${if (amount == 1f) "" else "en"}"
}

/** Anzeige für Rezept-/Manual-Einträge: Gramm wenn gram-getrackt, sonst Portionen. */
internal fun recipeAmountLabel(entry: DiaryEntry): String {
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
    val mealPatterns by vm.mealPatterns.collectAsStateWithLifecycle()
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
        val openPatternMeals = remember(mealPatterns, state.entries) {
            val present = state.entries.map { it.mealType }.toSet()
            mealPatterns.filter { it.mealType !in present }
        }
        val compactDiary = mealPrefs?.get(ch.nutrisnap.app.ui.theme.KEY_TOGGLE_DIARY_LAYOUT_COMPACT) ?: false
        val copyYesterdayAction: () -> Unit = {
            vm.copyYesterday { n ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (n > 0) "$n Einträge von gestern übernommen"
                        else "Gestern war leer – nichts zu kopieren"
                    )
                }
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
            item {
                DateNavigator(
                    state.selectedDate, vm::prevDay, vm::nextDay, vm::setDate,
                    onCopyYesterday = if (compactDiary) copyYesterdayAction else null
                )
            }
            if (!compactDiary) {
            item {
                val largerYesterday = mealPrefs?.get(ch.nutrisnap.app.ui.theme.KEY_TOGGLE_TOUCH_YESTERDAY_BTN) ?: true
                if (largerYesterday) {
                    FilledTonalButton(
                        onClick = copyYesterdayAction,
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
                        onClick = copyYesterdayAction,
                        modifier = Modifier.padding(horizontal = NutriSpacing.lg)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Gestern übernehmen", fontSize = 13.sp)
                    }
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
            // 1-Tap-Relog für erkannte wiederkehrende Mahlzeiten
            if (openPatternMeals.isNotEmpty()) {
                item(key = "meal_pattern_banner") {
                    MealPatternBanner(
                        patterns = openPatternMeals,
                        onApply = { p ->
                            vm.applyMealPattern(p) { n ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (n > 0) "${p.label}: $n Einträge übernommen"
                                        else "Lebensmittel nicht gefunden"
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
                                        onClick = {
                                            val defaultName = "${meal.label()} Vorlage"
                                            vm.saveMealAsTemplate(meal, defaultName) { id ->
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        if (id != null) "Als Vorlage gespeichert"
                                                        else "Keine Einträge – nichts gespeichert"
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(headerBtnSize)
                                    ) {
                                        Icon(
                                            Icons.Default.BookmarkAdd,
                                            contentDescription = "Als Vorlage speichern",
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
    onPick: (LocalDate) -> Unit = {},
    onCopyYesterday: (() -> Unit)? = null
) {
    var showPicker by remember { mutableStateOf(false) }
    val zone = java.time.ZoneId.systemDefault()

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.sm, vertical = NutriSpacing.xs)
    ) {
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically
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
        // Design-Toggle #17 "Diary kompakter": "Gestern übernehmen" als Icon-Button
        // im Navigator statt eigener volle Breite einnehmender Zeile darunter.
        if (onCopyYesterday != null) {
            IconButton(
                onClick = onCopyYesterday,
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Gestern übernehmen", modifier = Modifier.size(20.dp))
            }
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
