package ch.nutrisnap.app


import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.health.HealthConnectManager
import ch.nutrisnap.app.service.NotificationHelper
import ch.nutrisnap.app.service.NotificationScheduler
import ch.nutrisnap.app.ui.components.OfflineBanner
import ch.nutrisnap.app.ui.components.SyncStatusBanner
import ch.nutrisnap.app.ui.screens.HealthConnectScreen
import ch.nutrisnap.app.ui.screens.analysis.AnalysisScreen
import ch.nutrisnap.app.ui.screens.auth.AuthViewModel
import ch.nutrisnap.app.ui.screens.auth.LoginScreen
import ch.nutrisnap.app.ui.screens.customfood.CreateCustomFoodScreen
import ch.nutrisnap.app.ui.screens.customfood.CustomFoodListScreen
import ch.nutrisnap.app.ui.screens.deficiency.DeficiencyTrendScreen
import ch.nutrisnap.app.ui.screens.diary.DiaryScreen
import ch.nutrisnap.app.ui.screens.export.ExportScreen
import ch.nutrisnap.app.ui.screens.insights.InsightsScreen
import ch.nutrisnap.app.ui.screens.chat.DataChatScreen
import ch.nutrisnap.app.ui.screens.home.HomeScreen
import ch.nutrisnap.app.ui.screens.mealtemplate.MealTemplateScreen
import ch.nutrisnap.app.ui.screens.recipes.RecipesHubScreen
import ch.nutrisnap.app.ui.screens.scan.FoodScanScreen
import ch.nutrisnap.app.ui.screens.scan.NutritionLabelScanScreen
import ch.nutrisnap.app.ui.screens.scan.ScanChooserScreen
import ch.nutrisnap.app.ui.screens.security.BiometricLockScreen
import ch.nutrisnap.app.ui.screens.settings.CrashLogScreen
import ch.nutrisnap.app.ui.screens.settings.KEY_BIOMETRIC_LOCK
import ch.nutrisnap.app.ui.screens.settings.NotificationSettingsScreen
import ch.nutrisnap.app.ui.screens.settings.MealOrderScreen
import ch.nutrisnap.app.ui.screens.settings.SettingsScreen
import ch.nutrisnap.app.ui.screens.settings.YazioImportScreen
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.screens.stats.WeeklyStatsScreen
import ch.nutrisnap.app.ui.screens.stats.WeeklyStatsViewModel
import ch.nutrisnap.app.ui.theme.NutriSnapTheme
import ch.nutrisnap.app.ui.viewmodel.HealthConnectViewModel
import ch.nutrisnap.app.utils.NetworkMonitor
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home      : Screen("home",       "Start",    Icons.Default.Home)
    object Diary     : Screen("diary",      "Tagebuch", Icons.Default.MenuBook)
    object Recipes   : Screen("recipes",    "Rezepte",  Icons.Default.RestaurantMenu)
    object Analysis  : Screen("analysis",   "Analyse",  Icons.Default.BarChart)
    object Settings  : Screen("settings",   "Mehr",     Icons.Default.Settings)
}

// "KI-Koch" ist als zweiter Tab in RecipesHubScreen (Screen.Recipes) untergebracht,
// dadurch nur noch 5 statt 6 Bottom-Nav-Items (Material-Empfehlung: max. 5).
val bottomNavItems = listOf(
    Screen.Home, Screen.Diary, Screen.Recipes, Screen.Analysis, Screen.Settings
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var sharedUrl: String? = null
    private var sharedBatchUrls: List<String> = emptyList()
    private var sharedRecipeJson: String? = null

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) NotificationScheduler.scheduleAll(this) }

    private val healthConnectPermLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        // Verbunden = Lesen ok; WRITE_NUTRITION ist optional und darf den Callback nicht blockieren.
        if (granted.containsAll(HealthConnectManager.REQUIRED_PERMISSIONS)) {
            healthConnectViewModel?.onPermissionGranted()
        }
    }

    private var healthConnectViewModel: HealthConnectViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedRecipeJson = extractSharedRecipeJson(intent)
        if (sharedRecipeJson == null) {
            val extractedUrls = extractSharedUrls(intent)
            if (extractedUrls.size > 1) sharedBatchUrls = extractedUrls else sharedUrl = extractedUrls.firstOrNull()
        }
        NotificationHelper.createChannels(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationScheduler.scheduleAll(this)
        }

        setContent {
            NutriSnapTheme {
                val authVm: AuthViewModel = viewModel()
                // ─── DEV: Anmeldung deaktivieren ──────────────────────────────────────────
                // true  = Anmeldung aktiv (normal)
                // false = Anmeldung übersprungen (dev/debug)
                val AUTH_ENABLED = true

                if (!AUTH_ENABLED) {
                    // Skip auth entirely — go straight to main content
                    val networkMonitor2 = remember { NetworkMonitor(this) }
                    val isOnline2 by networkMonitor2.isOnline.collectAsStateWithLifecycle(initialValue = true)
                    val hcVm2: HealthConnectViewModel = viewModel()
                    LaunchedEffect(Unit) { healthConnectViewModel = hcVm2 }
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            OfflineBanner(isOnline = isOnline2)
                            MainScaffold(
                                sharedUrl = sharedUrl,
                                sharedBatchUrls = sharedBatchUrls,
                                sharedRecipeJson = sharedRecipeJson,
                                hcVm = hcVm2,
                                onRequestHealthPermission = {
                                    healthConnectPermLauncher.launch(HealthConnectManager.REQUESTABLE_PERMISSIONS)
                                }
                            )
                        }
                        // Overlay: nimmt keinen Layout-Platz, schwebt über dem Content
                        SyncStatusBanner()
                    }
                    return@NutriSnapTheme
                }

                val isLoggedIn by authVm.isLoggedIn.collectAsStateWithLifecycle()

                when (isLoggedIn) {
                    null  -> Box(modifier = Modifier.fillMaxSize())
                    false -> LoginScreen(onLoggedIn = { authVm.onLoggedIn() })
                    true  -> {
                        // Pull remote (web-created) rows on first composition AND every time
                        // the app comes back to the foreground. Previously this only ran once
                        // per login (LaunchedEffect(Unit)), so entries added on the web app
                        // never showed up on the phone unless you logged out/in again.
                        val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                        DisposableEffect(lifecycleOwner) {
                            val db = ch.nutrisnap.app.data.db.NutriDatabase.getInstance(this@MainActivity)
                            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                    (lifecycleOwner.lifecycleScope).launch {
                                        runCatching { ch.nutrisnap.app.data.supabase.SyncManager.syncAll(db) }
                                    }
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                        }
                        val networkMonitor = remember { NetworkMonitor(this) }
                        val isOnline by networkMonitor.isOnline.collectAsStateWithLifecycle(initialValue = true)
                        val biometricEnabled by notifDataStore.data
                            .map { it[KEY_BIOMETRIC_LOCK] ?: false }.collectAsStateWithLifecycle(initialValue = false)
                        var isUnlocked by remember { mutableStateOf(true) }

                        val hcVm: HealthConnectViewModel = viewModel()
                        LaunchedEffect(Unit) { healthConnectViewModel = hcVm }
                        LaunchedEffect(biometricEnabled) { if (biometricEnabled) isUnlocked = false }

                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                OfflineBanner(isOnline = isOnline)
                                if (!isUnlocked) {
                                    BiometricLockScreen(onUnlocked = { isUnlocked = true })
                                } else {
                                    MainScaffold(
                                        sharedUrl = sharedUrl,
                                        sharedBatchUrls = sharedBatchUrls,
                                        sharedRecipeJson = sharedRecipeJson,
                                        hcVm = hcVm,
                                        onRequestHealthPermission = {
                                            healthConnectPermLauncher.launch(
                                                HealthConnectManager.REQUESTABLE_PERMISSIONS
                                            )
                                        }
                                    )
                                }
                            }
                            // Overlay: nimmt keinen Layout-Platz, schwebt über dem Content
                            SyncStatusBanner()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val recipeJson = extractSharedRecipeJson(intent)
        if (recipeJson != null) {
            sharedRecipeJson = recipeJson
            sharedUrl = null; sharedBatchUrls = emptyList()
            return
        }
        sharedRecipeJson = null
        val extractedUrls = extractSharedUrls(intent)
        if (extractedUrls.size > 1) { sharedBatchUrls = extractedUrls; sharedUrl = null }
        else { sharedUrl = extractedUrls.firstOrNull(); sharedBatchUrls = emptyList() }
    }

    /**
     * Erkennt ein von Claude (Chat) geteiltes Rezept-JSON (Text markieren -> Teilen, oder
     * Teilen einer heruntergeladenen .json-Datei). Gibt null zurueck fuer alles andere
     * (z.B. normale Insta/TikTok/Web-Links), damit die bestehende Link-Erkennung unveraendert
     * weiterlaeuft.
     */
    private fun extractSharedRecipeJson(intent: Intent?): String? {
        if (intent == null) return null
        val text = when {
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
            intent.action == Intent.ACTION_SEND && intent.type == "application/json" -> {
                val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                uri?.let { u -> runCatching {
                    contentResolver.openInputStream(u)?.use { it.reader().readText() }
                }.getOrNull() }
            }
            else -> null
        } ?: return null
        return text.takeIf { ch.nutrisnap.app.domain.RecipeJsonImport.tryParse(it) != null }
    }

    /**
     * Erkennt einen oder mehrere geteilte Links.
     * - ACTION_SEND (text/plain): extrahiert ALLE URLs aus dem Text, nicht nur eine am Anfang —
     *   deckt den Fall ab, dass mehrere Insta/TikTok-Links in einer Notiz zusammen geteilt werden.
     * - ACTION_SEND_MULTIPLE (text/plain): manche Apps hängen mehrere Texte als
     *   EXTRA_TEXT-ArrayList an; falls nicht vorhanden, wird der einzelne EXTRA_TEXT genutzt.
     * - ACTION_VIEW: einzelner Deep-Link.
     */
    private fun extractSharedUrls(intent: Intent?): List<String> {
        if (intent == null) return emptyList()
        return when {
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
                ch.nutrisnap.app.domain.UrlExtractor.extractAll(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "")
            intent.action == Intent.ACTION_SEND_MULTIPLE && intent.type == "text/plain" -> {
                val texts = intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
                    ?.joinToString("\n") { it.toString() }
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                ch.nutrisnap.app.domain.UrlExtractor.extractAll(texts)
            }
            intent.action == Intent.ACTION_VIEW -> listOfNotNull(intent.dataString)
            else -> emptyList()
        }
    }
}

// ── Navigations-Transitions ────────────────────────────────────────────────────
// Tabs (Bottom-Nav): sanftes Ueberblenden statt hartem Schnitt.
// Gestapelte Screens (Settings-Unterseiten etc.): seitliches Hinein-/Hinausschieben,
// vermittelt Vorwaerts-/Zurueck-Navigation analog zur System-Navigation.
private val tabEnter = fadeIn(tween(220))
private val tabExit  = fadeOut(tween(180))
private val pushEnter = slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(280))
private val pushExit  = slideOutHorizontally(tween(280)) { -it / 4 } + fadeOut(tween(200))
private val popEnter  = slideInHorizontally(tween(280)) { -it / 4 } + fadeIn(tween(280))
private val popExit   = slideOutHorizontally(tween(280)) { it / 4 } + fadeOut(tween(200))

@Composable
fun MainScaffold(
    sharedUrl: String?,
    sharedBatchUrls: List<String> = emptyList(),
    sharedRecipeJson: String? = null,
    hcVm: HealthConnectViewModel,
    onRequestHealthPermission: () -> Unit
) {
    val navController = rememberNavController()
    val backEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backEntry?.destination?.route

    LaunchedEffect(sharedUrl, sharedBatchUrls, sharedRecipeJson) {
        if (!sharedUrl.isNullOrBlank() || sharedBatchUrls.isNotEmpty() || !sharedRecipeJson.isNullOrBlank()) {
            navController.navigate(Screen.Recipes.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true; restoreState = true
            }
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val navPrefs by context.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val renameNavLabel = navPrefs?.get(ch.nutrisnap.app.ui.theme.KEY_TOGGLE_NAV_LABEL_RENAME) ?: false

    Scaffold(bottomBar = {
        // Schwebende Nav-Leiste: Ecken bewusst eckiger (16dp statt 28dp), damit
        // rechte Labels („Einstellung“) nicht in der Rundung verschwinden.
        val navShape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 10.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 14.dp, shape = navShape, clip = false),
                shape = navShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 3.dp
            ) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier.height(68.dp)
                ) {
                    bottomNavItems.forEach { screen ->
                        val label = when {
                            screen is Screen.Settings && renameNavLabel -> "Einstellung"
                            else -> screen.label
                        }
                        NavigationBarItem(
                            selected = currentRoute == screen.route ||
                                currentRoute?.startsWith("${screen.route}?") == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true; restoreState = true
                                }
                            },
                            icon = { Icon(screen.icon, contentDescription = label) },
                            label = {
                                Text(
                                    label,
                                    maxLines = 1,
                                    fontSize = 11.sp,
                                    softWrap = false
                                )
                            }
                        )
                    }
                }
            }
        }
    }) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(
                Screen.Home.route,
                enterTransition = { tabEnter }, exitTransition = { tabExit },
                popEnterTransition = { tabEnter }, popExitTransition = { tabExit }
            ) {
                HomeScreen(
                    hcVm = hcVm,
                    onNavigateToDiary = { meal, autoOpenAdd ->
                        // open=true: restoreState aus, sonst bleibt das Add-Sheet zu
                        // (alte Compose-Instanz mit showAddSheet=false).
                        val route = if (meal != null) "diary?meal=${meal.name}&open=$autoOpenAdd"
                                    else "diary?open=$autoOpenAdd"
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = !autoOpenAdd
                        }
                    },
                    onNavigateToHealth = { navController.navigate("health") },
                    onNavigateToFoodScan = { navController.navigate("food_scan") },
                    onNavigateToBarcode = { navController.navigate("diary?open=true&scan=true") },
                    onNavigateToLabelScan = { navController.navigate("nutrition_label_scan") },
                    onNavigateToCustomFoods = { navController.navigate("custom_foods") },
                    onNavigateToMealTemplates = { navController.navigate("meal_templates") }
                )
            }
            composable(
                route = "diary?meal={meal}&open={open}&scan={scan}",
                arguments = listOf(
                    navArgument("meal") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("open") { type = NavType.BoolType; defaultValue = false },
                    navArgument("scan") { type = NavType.BoolType; defaultValue = false }
                ),
                enterTransition = { tabEnter }, exitTransition = { tabExit },
                popEnterTransition = { tabEnter }, popExitTransition = { tabExit }
            ) { backStackEntry ->
                val mealArg = backStackEntry.arguments?.getString("meal")?.let { runCatching { MealType.valueOf(it) }.getOrNull() }
                val openArg = backStackEntry.arguments?.getBoolean("open") ?: false
                val scanArg = backStackEntry.arguments?.getBoolean("scan") ?: false
                DiaryScreen(
                    initialMeal = mealArg,
                    autoOpenAdd = openArg,
                    autoOpenScanner = scanArg,
                    onNavigateToPhotoScan = { meal ->
                        val route = if (meal != null) "food_scan?meal=${meal.name}" else "food_scan"
                        navController.navigate(route)
                    }
                )
            }
            composable(
                Screen.Recipes.route,
                enterTransition = { tabEnter }, exitTransition = { tabExit },
                popEnterTransition = { tabEnter }, popExitTransition = { tabExit }
            ) { RecipesHubScreen(sharedUrl = sharedUrl, sharedBatchUrls = sharedBatchUrls, sharedRecipeJson = sharedRecipeJson) }
            composable(
                Screen.Analysis.route,
                enterTransition = { tabEnter }, exitTransition = { tabExit },
                popEnterTransition = { tabEnter }, popExitTransition = { tabExit }
            ) {
                AnalysisScreen(
                    onNavigateToInsights = { navController.navigate("insights") },
                    onNavigateToChat      = { navController.navigate("chat") },
                    onNavigateToDeficiencyTrend = { navController.navigate("deficiency_trend") }
                )
            }
            composable(
                Screen.Settings.route,
                enterTransition = { tabEnter }, exitTransition = { tabExit },
                popEnterTransition = { tabEnter }, popExitTransition = { tabExit }
            ) {
                SettingsScreen(
                    onNavigateToNotifSettings = { navController.navigate("notif_settings") },
                    onNavigateToStats         = { navController.navigate("stats") },
                    onNavigateToExport        = { navController.navigate("export") },
                    onNavigateToCustomFoods   = { navController.navigate("custom_foods") },
                    onNavigateToMealTemplates = { navController.navigate("meal_templates") },
                    onNavigateToYazioImport   = { navController.navigate("yazio_import") },
                    onNavigateToScan          = { navController.navigate("scan_chooser") },
                    onNavigateToMealOrder     = { navController.navigate("meal_order") },
                    onNavigateToShoppingList  = { navController.navigate("shopping_list") },
                    onNavigateToSupplements   = { navController.navigate("supplements") },
                    onNavigateToCrashLog      = { navController.navigate("crash_log") }
                )
            }
            composable(
                "meal_order",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                MealOrderScreen(onBack = { navController.popBackStack() })
            }
            composable(
                "scan_chooser",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                ScanChooserScreen(
                    onBarcode       = { navController.navigate("diary?open=true&scan=true") },
                    onPhotoEstimate = { navController.navigate("food_scan") },
                    onLabelPhoto    = { navController.navigate("nutrition_label_scan") },
                    onBack          = { navController.popBackStack() }
                )
            }
            composable(
                "stats",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                val vm: WeeklyStatsViewModel = viewModel()
                WeeklyStatsScreen(viewModel = vm)
            }
            composable(
                "export",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                ExportScreen(onBack = { navController.popBackStack() })
            }
            composable(
                "insights",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                InsightsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                "deficiency_trend",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                DeficiencyTrendScreen(onBack = { navController.popBackStack() })
            }
            composable(
                "chat",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                DataChatScreen(onBack = { navController.popBackStack() })
            }
            composable(
                "health",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                HealthConnectScreen(
                    viewModel = hcVm,
                    onRequestPermission = onRequestHealthPermission,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                "notif_settings",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                NotificationSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                "custom_foods",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                CustomFoodListScreen(
                    onBack = { navController.popBackStack() },
                    onAdd = { navController.navigate("custom_food_create") },
                    onEdit = { id -> navController.navigate("custom_food_edit/$id") }
                )
            }
            composable(
                "custom_food_create",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                CreateCustomFoodScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = "custom_food_edit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.IntType }),
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) { backStackEntry ->
                val idArg = backStackEntry.arguments?.getInt("id")
                CreateCustomFoodScreen(
                    onBack = { navController.popBackStack() },
                    editId = idArg
                )
            }
            composable(
                "meal_templates",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                MealTemplateScreen(
                    onBack = { navController.popBackStack() },
                    onTemplateSelected = { navController.popBackStack() }
                )
            }
            composable(
                "yazio_import",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                YazioImportScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = "food_scan?meal={meal}",
                arguments = listOf(
                    navArgument("meal") { type = NavType.StringType; nullable = true; defaultValue = null }
                ),
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) { backStackEntry ->
                val mealArg = backStackEntry.arguments?.getString("meal")
                    ?.let { runCatching { MealType.valueOf(it) }.getOrNull() }
                FoodScanScreen(
                    onNavigateBack = { navController.popBackStack() },
                    initialMeal = mealArg
                )
            }
            // Alias ohne Query, damit bestehende navigate("food_scan") Calls weiter funktionieren
            composable(
                "food_scan",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                FoodScanScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(
                "nutrition_label_scan?barcode={barcode}",
                arguments = listOf(
                    androidx.navigation.navArgument("barcode") {
                        type = androidx.navigation.NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) { entry ->
                val bc = entry.arguments?.getString("barcode")
                NutritionLabelScanScreen(
                    onNavigateBack = { navController.popBackStack() },
                    barcode = bc
                )
            }
            // Alias ohne Query (Deep-Links / alte Aufrufe)
            composable(
                "nutrition_label_scan",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                NutritionLabelScanScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(
                "shopping_list",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                ch.nutrisnap.app.ui.screens.shopping.ShoppingListScreen(onBack = { navController.popBackStack() })
            }
            composable(
                "supplements",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                ch.nutrisnap.app.ui.screens.supplements.SupplementsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(
                "crash_log",
                enterTransition = { pushEnter }, exitTransition = { pushExit },
                popEnterTransition = { popEnter }, popExitTransition = { popExit }
            ) {
                CrashLogScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

