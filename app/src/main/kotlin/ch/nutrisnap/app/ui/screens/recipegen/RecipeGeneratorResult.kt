package ch.nutrisnap.app.ui.screens.recipegen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ch.nutrisnap.app.data.model.GeneratedRecipeEntity
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.domain.CookingMethod
import ch.nutrisnap.app.domain.DayPlan
import ch.nutrisnap.app.domain.GeneratedRecipe
import ch.nutrisnap.app.domain.PlannedMeal
import ch.nutrisnap.app.domain.RecipeIngredient
import ch.nutrisnap.app.domain.WorkoutTiming
import kotlin.math.roundToInt

// ── Rezept-Karte ───────────────────────────────────────────────────────────────

@Composable
internal fun RecipeResultCard(
    recipe: GeneratedRecipe,
    imageUrl: String? = null,
    imageError: String? = null,
    isSavingImage: Boolean = false,
    onAddToDiary: () -> Unit,
    onSaveAsRecipe: () -> Unit,
    onUpdate: (GeneratedRecipe) -> Unit,
    onRemoveIngredient: (Int) -> Unit,
    onUpdateIngredient: (Int, RecipeIngredient) -> Unit,
    cookingMethod: CookingMethod = CookingMethod.STOVETOP,
    applianceModel: String = "",
    isAdaptingMethod: Boolean = false,
    onAdaptToMethod: (CookingMethod) -> Unit = {}
) {
    var checkedIngredients by remember(recipe) { mutableStateOf(setOf<Int>()) }
    var isEditing by remember { mutableStateOf(false) }

    // Edit-Felder (nur aktiv im Edit-Modus)
    var titleText    by remember(recipe, isEditing) { mutableStateOf(recipe.title) }
    var caloriesText by remember(recipe, isEditing) { mutableStateOf(recipe.calories.roundToInt().toString()) }
    var proteinText  by remember(recipe, isEditing) { mutableStateOf(recipe.protein.toInt().toString()) }
    var carbsText    by remember(recipe, isEditing) { mutableStateOf(recipe.carbs.toInt().toString()) }
    var fatText      by remember(recipe, isEditing) { mutableStateOf(recipe.fat.toInt().toString()) }
    var servingsText by remember(recipe, isEditing) { mutableStateOf(recipe.servings.toString()) }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column {
            // Kompakteres Hero-Bild (Swissmilk: Fokus ohne Bildschirm zu füllen)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                when {
                    !imageUrl.isNullOrBlank() -> {
                        AsyncImage(
                            model = run {
                                val u = imageUrl!!
                                if (u.startsWith("file://") || (u.startsWith("/") && !u.startsWith("http"))) {
                                    val f = java.io.File(u.removePrefix("file://"))
                                    if (f.exists()) f else u
                                } else u
                            },
                            contentDescription = recipe.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    isSavingImage -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.height(6.dp))
                            Text("Bild wird erzeugt…", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(12.dp)) {
                            Icon(
                                Icons.Default.Restaurant,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            if (!imageError.isNullOrBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Kein Bild: $imageError",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        if (isEditing) {
                            OutlinedTextField(
                                value = titleText, onValueChange = { titleText = it },
                                label = { Text("Titel") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                recipe.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (recipe.description.isNotBlank()) {
                                Text(
                                    recipe.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            if (isEditing) {
                                onUpdate(
                                    recipe.copy(
                                        title    = titleText.ifBlank { recipe.title },
                                        calories = caloriesText.toFloatOrNull() ?: recipe.calories,
                                        protein  = proteinText.toFloatOrNull()  ?: recipe.protein,
                                        carbs    = carbsText.toFloatOrNull()    ?: recipe.carbs,
                                        fat      = fatText.toFloatOrNull()      ?: recipe.fat,
                                        servings = servingsText.toIntOrNull()   ?: recipe.servings
                                    )
                                )
                            }
                            isEditing = !isEditing
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                            if (isEditing) "Speichern" else "Bearbeiten",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (isEditing) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EditMacroField("kcal", caloriesText) { caloriesText = it }
                        EditMacroField("P g", proteinText) { proteinText = it }
                        EditMacroField("K g", carbsText) { carbsText = it }
                        EditMacroField("F g", fatText) { fatText = it }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = servingsText, onValueChange = { servingsText = it },
                        label = { Text("Portionen") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(120.dp)
                    )
                } else {
                    // Swissmilk-Meta: Zeit + Portionen in einer Zeile
                    Text(
                        "${recipe.prepTimeMinutes} Min.  ·  ${recipe.servings} Port.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    // Eine Nährwert-Zeile statt vier Chips
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(
                            "1 Portion: ${recipe.calories.roundToInt()} kcal, " +
                                "${recipe.fat.toInt()} g Fett, " +
                                "${recipe.carbs.toInt()} g KH, " +
                                "${recipe.protein.toInt()} g Eiweiss",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                    // Geräte-Umschalter kompakter (weniger Höhe)
                    Spacer(Modifier.height(6.dp))
                    RecipeMethodAdaptRow(
                        current = cookingMethod,
                        applianceModel = applianceModel,
                        isAdapting = isAdaptingMethod,
                        onAdapt = onAdaptToMethod
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 10.dp))

                Text("Zutaten", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                val ingredients = recipe.effectiveIngredients()
                if (isEditing) {
                    ingredients.forEachIndexed { i, ing ->
                        EditableIngredientRow(
                            ingredient = ing,
                            onChange = { onUpdateIngredient(i, it) },
                            onDelete = { onRemoveIngredient(i) }
                        )
                    }
                    TextButton(onClick = {
                        onUpdateIngredient(ingredients.size, RecipeIngredient(name = "Neue Zutat"))
                    }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Zutat hinzufügen")
                    }
                } else {
                    ingredients.forEachIndexed { i, ing ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = i in checkedIngredients,
                                onCheckedChange = { checked ->
                                    checkedIngredients =
                                        if (checked) checkedIngredients + i else checkedIngredients - i
                                },
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (ing.amount.isNotBlank()) "${ing.amount}  ${ing.name}" else ing.name,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.weight(1f),
                                color = if (i in checkedIngredients)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface
                            )
                            if (ing.calories > 0) {
                                Text(
                                    "${ing.calories.roundToInt()}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 10.dp))

                Text("Zubereitung", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                recipe.steps.forEachIndexed { i, step ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(22.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "${i + 1}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(step, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onSaveAsRecipe,
                        enabled = !isSavingImage,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isSavingImage) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Bild…")
                        } else {
                            Icon(Icons.Default.MenuBook, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Als Rezept")
                        }
                    }
                    Button(onClick = onAddToDiary, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.BookmarkAdd, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tagebuch")
                    }
                }
            }
        }
    }
}

@Composable
internal fun RowScope.EditMacroField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(1f)
    )
}

@Composable
internal fun EditableIngredientRow(
    ingredient: RecipeIngredient,
    onChange: (RecipeIngredient) -> Unit,
    onDelete: () -> Unit
) {
    var name   by remember(ingredient) { mutableStateOf(ingredient.name) }
    var amount by remember(ingredient) { mutableStateOf(ingredient.amount) }
    var kcal   by remember(ingredient) { mutableStateOf(if (ingredient.calories > 0) ingredient.calories.roundToInt().toString() else "") }

    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it; onChange(ingredient.copy(name = name, amount = it, calories = kcal.toFloatOrNull() ?: ingredient.calories)) },
            label = { Text("Menge") }, singleLine = true,
            modifier = Modifier.width(80.dp)
        )
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; onChange(ingredient.copy(name = it, amount = amount, calories = kcal.toFloatOrNull() ?: ingredient.calories)) },
            label = { Text("Zutat") }, singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(
            value = kcal,
            onValueChange = { kcal = it; onChange(ingredient.copy(name = name, amount = amount, calories = it.toFloatOrNull() ?: ingredient.calories)) },
            label = { Text("kcal") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(72.dp)
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Close, "Entfernen", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        }
    }
}

// ── Tagebuch-Sheet ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToDiarySheet(
    recipe: GeneratedRecipe,
    onDismiss: () -> Unit,
    onConfirm: (servings: Float, mealType: MealType, date: java.time.LocalDate) -> Unit
) {
    // recipe.calories/protein/… sind pro Portion (siehe Generator-Prompt + reconcileNutrition)
    var servingsText by remember { mutableStateOf("1") }
    var mealType by remember { mutableStateOf(MealType.LUNCH) }
    var selectedDate by remember { mutableStateOf(java.time.LocalDate.now()) }

    val servings = servingsText.replace(',', '.').toFloatOrNull()?.coerceAtLeast(0.1f) ?: 1f
    val estKcal = recipe.calories * servings

    val mealLabels = mapOf(
        MealType.BREAKFAST to "☀️ Frühstück",
        MealType.LUNCH     to "🌤️ Mittagessen",
        MealType.DINNER    to "🌙 Abendessen",
        MealType.SNACK     to "🍎 Snack"
    )

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 24.dp)
        ) {
            Text("Zum Tagebuch hinzufügen", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                recipe.title,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
            )

            Text("Portionen", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalIconButton(
                    onClick = {
                        val next = ((servings - 0.5f) * 10).toInt() / 10f
                        if (next >= 0.5f) servingsText = formatServing(next)
                    },
                    enabled = servings > 0.5f
                ) { Icon(Icons.Default.Remove, null) }
                OutlinedTextField(
                    value = servingsText,
                    onValueChange = { raw ->
                        servingsText = raw.filter { it.isDigit() || it == '.' || it == ',' }
                    },
                    modifier = Modifier.width(88.dp),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                )
                FilledTonalIconButton(
                    onClick = {
                        val next = ((servings + 0.5f) * 10).toInt() / 10f
                        if (next <= 20f) servingsText = formatServing(next)
                    }
                ) { Icon(Icons.Default.Add, null) }
                Text(
                    "= ${estKcal.toInt()} kcal",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "1 Portion ≈ ${recipe.calories.toInt()} kcal · ±0,5 mit den Buttons",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(16.dp))

            Text("Mahlzeit", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                mealLabels.entries.take(2).forEach { (type, label) ->
                    FilterChip(
                        selected = mealType == type,
                        onClick = { mealType = type },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                mealLabels.entries.drop(2).forEach { (type, label) ->
                    FilterChip(
                        selected = mealType == type,
                        onClick = { mealType = type },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("Tag", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val today = java.time.LocalDate.now()
                listOf(
                    today to "Heute",
                    today.minusDays(1) to "Gestern",
                    today.minusDays(2) to "Vorgestern"
                ).forEach { (d, label) ->
                    FilterChip(
                        selected = selectedDate == d,
                        onClick = { selectedDate = d },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { onConfirm(servings, mealType, selectedDate) },
                modifier = Modifier.fillMaxWidth(),
                enabled = servings > 0f
            ) {
                Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Eintragen")
            }
        }
    }
}

/** Formatierung für Portions-Textfeld (1 / 1.5 / 2 …). */
internal fun formatServing(v: Float): String =
    if (v == v.toLong().toFloat()) v.toLong().toString()
    else ((v * 10).toInt() / 10f).toString()

@Composable
internal fun MacroChip(text: String, color: Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
