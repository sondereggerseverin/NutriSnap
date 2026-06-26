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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
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
import ch.nutrisnap.app.ui.screens.home.HomeScreen
import ch.nutrisnap.app.ui.screens.mealtemplate.MealTemplateScreen
import ch.nutrisnap.app.ui.screens.recipegen.RecipeGeneratorScreen
import ch.nutrisnap.app.ui.screens.recipes.RecipesScreen
import ch.nutrisnap.app.ui.screens.security.BiometricLockScreen
import ch.nutrisnap.app.ui.screens.settings.KEY_BIOMETRIC_LOCK
import ch.nutrisnap.app.ui.screens.settings.NotificationSettingsScreen
import ch.nutrisnap.app.ui.screens.settings.SettingsScreen
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.theme.NutriSnapTheme
import ch.nutrisnap.app.ui.viewmodel.HealthConnectViewModel
import ch.nutrisnap.app.utils.NetworkMonitor
import kotlinx.coroutines.flow.map

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home      : Screen("home",       "Start",    Icons.Default.Home)
    object Diary     : Screen("diary",      "Tagebuch", Icons.Default.MenuBook)
    object Recipes   : Screen("recipes",    "Rezepte",  Icons.Default.RestaurantMenu)
    object AiRecipes : Screen("ai_recipes", "KI-Koch",  Icons.Default.AutoAwesome)
    object Analysis  : Screen("analysis",   "Analyse",  Icons.Default.BarChart)
    object Settings  : Screen("settings",   "Mehr",     Icons.Default.Settings)
}

val bottomNavItems = listOf(
    Screen.Home, Screen.Diary, Screen.Recipes,
    Screen.AiRecipes, Screen.Analysis, Screen.Settings
)

class MainActivity : ComponentActivity() {
    private var sharedUrl: String? = null

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
        sharedUrl = extractSharedUrl(intent)
        NotificationHelper.createChannels(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationScheduler.scheduleAll(this)
        }

        setContent {
            NutriSnapTheme {
                val authVm: AuthViewModel = viewModel()
                val isLoggedIn by authVm.isLoggedIn.collectAsState()

                when (isLoggedIn) {
                    null  -> Box(modifier = Modifier.fillMaxSize())
                    false -> LoginScreen(onLoggedIn = { authVm.onLoggedIn() })
                    true  -> {
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
        sharedUrl = extractSharedUrl(intent)
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain")
            return intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.startsWith("http") }
        if (intent?.action == Intent.ACTION_VIEW) return intent.dataString
        return null
    }
}

@Composable
fun MainScaffold(
    sharedUrl: String?,
    hcVm: HealthConnectViewModel,
    onRequestHealthPermission: () -> Unit
) {
    val navController = rememberNavController()
    val backEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backEntry?.destination?.route

    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) {
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
                    selected = currentRoute == screen.route,
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
                    onNavigateToDiary = {
                        navController.navigate(Screen.Diary.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true; restoreState = true
                        }
                    },
                    onNavigateToHealth = { navController.navigate("health") }
                )
            }
            composable(Screen.Diary.route)     { DiaryScreen() }
            composable(Screen.Recipes.route)   { RecipesScreen(sharedUrl = sharedUrl) }
            composable(Screen.AiRecipes.route) { RecipeGeneratorScreen() }
            composable(Screen.Analysis.route)  { AnalysisScreen() }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToNotifSettings = { navController.navigate("notif_settings") }
                )
            }
            composable("health") {
                HealthConnectScreen(
                    viewModel = hcVm,
                    onRequestPermission = onRequestHealthPermission
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
        }
    }
}
