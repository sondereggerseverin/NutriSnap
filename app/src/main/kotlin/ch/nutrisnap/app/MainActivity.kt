package ch.nutrisnap.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import ch.nutrisnap.app.ui.screens.HealthConnectScreen
import ch.nutrisnap.app.ui.screens.analysis.AnalysisScreen
import ch.nutrisnap.app.ui.screens.auth.AuthViewModel
import ch.nutrisnap.app.ui.screens.auth.LoginScreen
import ch.nutrisnap.app.ui.screens.customfood.CreateCustomFoodScreen
import ch.nutrisnap.app.ui.screens.diary.DiaryScreen
import ch.nutrisnap.app.ui.screens.export.ExportScreen
import ch.nutrisnap.app.ui.screens.home.HomeScreen
import ch.nutrisnap.app.ui.screens.mealtemplate.MealTemplateScreen
import ch.nutrisnap.app.ui.screens.recipes.RecipesHubScreen
import ch.nutrisnap.app.ui.screens.scan.FoodScanScreen
import ch.nutrisnap.app.ui.screens.scan.NutritionLabelScanScreen
import ch.nutrisnap.app.ui.screens.scan.ScanChooserScreen
import ch.nutrisnap.app.ui.screens.security.BiometricLockScreen
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

class MainActivity : ComponentActivity() {
    private var sharedUrl: String? = null
    private var sharedBatchUrls: List<String> = emptyList()

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) NotificationScheduler.scheduleAll(this) }

    private val healthConnectPermLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectManager.REQUIRED_PERMISSIONS)) {
            healthConnectViewModel?.onPermissionGranted()
        }
    }

    private var healthConnectViewModel: HealthConnectViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val extractedUrls = extractSharedUrls(intent)
        if (extractedUrls.size > 1) sharedBatchUrls = extractedUrls else sharedUrl = extractedUrls.firstOrNull()
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
                    val isOnline2 by networkMonitor2.isOnline.collectAsState(initial = true)
                    val hcVm2: HealthConnectViewModel = viewModel()
                    LaunchedEffect(Unit) { healthConnectViewModel = hcVm2 }
                    Column(modifier = Modifier.fillMaxSize()) {
                        OfflineBanner(isOnline = isOnline2)
                        MainScaffold(
                            sharedUrl = sharedUrl,
                            sharedBatchUrls = sharedBatchUrls,
                            hcVm = hcVm2,
                            onRequestHealthPermission = {
                                healthConnectPermLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
                            }
                        )
                    }
                    return@NutriSnapTheme
                }

                val isLoggedIn by authVm.isLoggedIn.collectAsState()

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
                                        runCatching { ch.nutrisnap.app.data.supabase.SyncManager.pullAll(db) }
                                    }
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                        }
                        val networkMonitor = remember { NetworkMonitor(this) }
                        val isOnline by networkMonitor.isOnline.collectAsState(initial = true)
                        val biometricEnabled by notifDataStore.data
                            .map { it[KEY_BIOMETRIC_LOCK] ?: false }.collectAsState(initial = false)
                        var isUnlocked by remember { mutableStateOf(true) }

                        val hcVm: HealthConnectViewModel = viewModel()
                        LaunchedEffect(Unit) { healthConnectViewModel = hcVm }
                        LaunchedEffect(biometricEnabled) { if (biometricEnabled) isUnlocked = false }

                        Column(modifier = Modifier.fillMaxSize()) {
                            OfflineBanner(isOnline = isOnline)
                            if (!isUnlocked) {
                                BiometricLockScreen(onUnlocked = { isUnlocked = true })
                            } else {
                                MainScaffold(
                                    sharedUrl = sharedUrl,
                                    sharedBatchUrls = sharedBatchUrls,
                                    hcVm = hcVm,
                                    onRequestHealthPermission = {
                                        healthConnectPermLauncher.launch(
                                            HealthConnectManager.REQUIRED_PERMISSIONS
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val extractedUrls = extractSharedUrls(intent)
        if (extractedUrls.size > 1) { sharedBatchUrls = extractedUrls; sharedUrl = null }
        else { sharedUrl = extractedUrls.firstOrNull(); sharedBatchUrls = emptyList() }
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

@Composable
fun MainScaffold(
    sharedUrl: String?,
    sharedBatchUrls: List<String> = emptyList(),
    hcVm: HealthConnectViewModel,
    onRequestHealthPermission: () -> Unit
) {
    val navController = rememberNavController()
    val backEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backEntry?.destination?.route

    LaunchedEffect(sharedUrl, sharedBatchUrls) {
        if (!sharedUrl.isNullOrBlank() || sharedBatchUrls.isNotEmpty()) {
            navController.navigate(Screen.Recipes.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true; restoreState = true
            }
        }
    }

    Scaffold(bottomBar = {
        NavigationBar {
            bottomNavItems.forEach { screen ->
                NavigationBarItem(
                    // "diary" hat optionale Query-Argumente (?meal=...&open=...), daher reicht ein
                    // Prefix-Vergleich statt exakter Gleichheit, sonst bleibt der Tab unselektiert.
                    selected = currentRoute == screen.route ||
                        currentRoute?.startsWith("${screen.route}?") == true,
                    onClick = {
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true; restoreState = true
                        }
                    },
                    icon = { Icon(screen.icon, contentDescription = screen.label) },
                    label = { Text(screen.label, maxLines = 1) }
                )
            }
        }
    }) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    hcVm = hcVm,
                    onNavigateToDiary = { meal, autoOpenAdd ->
                        val route = if (meal != null) "diary?meal=${meal.name}&open=$autoOpenAdd" else "diary?open=$autoOpenAdd"
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true; restoreState = true
                        }
                    },
                    onNavigateToHealth = { navController.navigate("health") },
                    onNavigateToFoodScan = { navController.navigate("scan_chooser") },
                    onNavigateToRecipeImport = {
                        navController.navigate(Screen.Recipes.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true; restoreState = true
                        }
                    }
                )
            }
            composable(
                route = "diary?meal={meal}&open={open}&scan={scan}",
                arguments = listOf(
                    navArgument("meal") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("open") { type = NavType.BoolType; defaultValue = false },
                    navArgument("scan") { type = NavType.BoolType; defaultValue = false }
                )
            ) { backStackEntry ->
                val mealArg = backStackEntry.arguments?.getString("meal")?.let { runCatching { MealType.valueOf(it) }.getOrNull() }
                val openArg = backStackEntry.arguments?.getBoolean("open") ?: false
                val scanArg = backStackEntry.arguments?.getBoolean("scan") ?: false
                DiaryScreen(initialMeal = mealArg, autoOpenAdd = openArg, autoOpenScanner = scanArg)
            }
            composable(Screen.Recipes.route)   { RecipesHubScreen(sharedUrl = sharedUrl, sharedBatchUrls = sharedBatchUrls) }
            composable(Screen.Analysis.route)  { AnalysisScreen() }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToNotifSettings = { navController.navigate("notif_settings") },
                    onNavigateToStats         = { navController.navigate("stats") },
                    onNavigateToExport        = { navController.navigate("export") },
                    onNavigateToCustomFoods   = { navController.navigate("custom_foods") },
                    onNavigateToMealTemplates = { navController.navigate("meal_templates") },
                    onNavigateToYazioImport   = { navController.navigate("yazio_import") },
                    onNavigateToScan          = { navController.navigate("scan_chooser") },
                    onNavigateToMealOrder     = { navController.navigate("meal_order") },
                    onNavigateToShoppingList  = { navController.navigate("shopping_list") }
                )
            }
            composable("meal_order") {
                MealOrderScreen(onBack = { navController.popBackStack() })
            }
            composable("scan_chooser") {
                ScanChooserScreen(
                    onBarcode       = { navController.navigate("diary?open=true&scan=true") },
                    onPhotoEstimate = { navController.navigate("food_scan") },
                    onLabelPhoto    = { navController.navigate("nutrition_label_scan") },
                    onBack          = { navController.popBackStack() }
                )
            }
            composable("stats") {
                val vm: WeeklyStatsViewModel = viewModel()
                WeeklyStatsScreen(viewModel = vm)
            }
            composable("export") {
                ExportScreen(onBack = { navController.popBackStack() })
            }
            composable("health") {
                HealthConnectScreen(
                    viewModel = hcVm,
                    onRequestPermission = onRequestHealthPermission,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("notif_settings") {
                NotificationSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("custom_foods") {
                CreateCustomFoodScreen(onBack = { navController.popBackStack() })
            }
            composable("meal_templates") {
                MealTemplateScreen(
                    onBack = { navController.popBackStack() },
                    onTemplateSelected = { navController.popBackStack() }
                )
            }
            composable("yazio_import") {
                YazioImportScreen(onBack = { navController.popBackStack() })
            }
            composable("food_scan") {
                FoodScanScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable("nutrition_label_scan") {
                NutritionLabelScanScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable("shopping_list") {
                ch.nutrisnap.app.ui.screens.shopping.ShoppingListScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
