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
import ch.nutrisnap.app.ui.screens.diary.DiaryScreen
import ch.nutrisnap.app.ui.screens.home.HomeScreen
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
    object Home     : Screen("home",     "Start",      Icons.Default.Home)
    object Diary    : Screen("diary",    "Tagebuch",   Icons.Default.MenuBook)
    object Recipes  : Screen("recipes",  "Rezepte",    Icons.Default.RestaurantMenu)
    object Analysis : Screen("analysis", "Analyse",    Icons.Default.BarChart)
    object Health   : Screen("health",   "Gesundheit", Icons.Default.FavoriteBorder)
    object Settings : Screen("settings", "Mehr",       Icons.Default.Settings)
}

val bottomNavItems = listOf(
    Screen.Home, Screen.Diary, Screen.Recipes,
    Screen.Analysis, Screen.Health, Screen.Settings
)

class MainActivity : ComponentActivity() {
    private var sharedUrl: String? = null

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) NotificationScheduler.scheduleAll(this) }

    // Health Connect permission launcher
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
                val networkMonitor = remember { NetworkMonitor(this) }
                val isOnline by networkMonitor.isOnline.collectAsState(initial = true)
                val biometricEnabled by notifDataStore.data
                    .map { it[KEY_BIOMETRIC_LOCK] ?: false }.collectAsState(initial = false)
                var isUnlocked by remember { mutableStateOf(true) }

                // Hold reference to HealthConnectViewModel so permission callback can reach it
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
                    label = { Text(screen.label) }
                )
            }
        }
    }) { innerPadding ->
        NavHost(navController = navController, startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)) {
            composable(Screen.Home.route) {
                HomeScreen(onNavigateToDiary = {
                    navController.navigate(Screen.Diary.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true; restoreState = true
                    }
                })
            }
            composable(Screen.Diary.route)    { DiaryScreen() }
            composable(Screen.Recipes.route)  { RecipesScreen(sharedUrl = sharedUrl) }
            composable(Screen.Analysis.route) { AnalysisScreen() }
            composable(Screen.Health.route) {
                HealthConnectScreen(
                    viewModel = hcVm,
                    onRequestPermission = onRequestHealthPermission
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(onNavigateToNotifSettings = { navController.navigate("notif_settings") })
            }
            composable("notif_settings") {
                NotificationSettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
