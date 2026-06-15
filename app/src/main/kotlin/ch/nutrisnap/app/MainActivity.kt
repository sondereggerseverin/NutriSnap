package ch.nutrisnap.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ch.nutrisnap.app.ui.screens.analysis.AnalysisScreen
import ch.nutrisnap.app.ui.screens.diary.DiaryScreen
import ch.nutrisnap.app.ui.screens.home.HomeScreen
import ch.nutrisnap.app.ui.screens.recipes.RecipesScreen
import ch.nutrisnap.app.ui.screens.settings.SettingsScreen
import ch.nutrisnap.app.ui.theme.NutriSnapTheme

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home     : Screen("home",     "Start",     Icons.Default.Home)
    object Diary    : Screen("diary",    "Tagebuch",  Icons.Default.MenuBook)
    object Recipes  : Screen("recipes",  "Rezepte",   Icons.Default.RestaurantMenu)
    object Analysis : Screen("analysis", "Analyse",   Icons.Default.BarChart)
    object Settings : Screen("settings", "Mehr",      Icons.Default.Settings)
}

val bottomNavItems = listOf(Screen.Home, Screen.Diary, Screen.Recipes, Screen.Analysis, Screen.Settings)

class MainActivity : ComponentActivity() {

    private var sharedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedUrl = extractSharedUrl(intent)

        setContent {
            NutriSnapTheme {
                MainScaffold(sharedUrl = sharedUrl)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedUrl = extractSharedUrl(intent)
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            return intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.trim()
                ?.takeIf { it.startsWith("http") }
        }
        if (intent?.action == Intent.ACTION_VIEW) {
            return intent.dataString
        }
        return null
    }
}

@Composable
fun MainScaffold(sharedUrl: String?) {
    val navController = rememberNavController()
    val backEntry     by navController.currentBackStackEntryAsState()
    val currentRoute  = backEntry?.destination?.route

    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) {
            navController.navigate(Screen.Recipes.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState    = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        selected = currentRoute == screen.route,
                        onClick  = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        },
                        icon  = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = Screen.Home.route,
            modifier         = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(onNavigateToDiary = {
                    navController.navigate(Screen.Diary.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState    = true
                    }
                })
            }
            composable(Screen.Diary.route)    { DiaryScreen() }
            composable(Screen.Recipes.route)  { RecipesScreen(sharedUrl = sharedUrl) }
            composable(Screen.Analysis.route) { AnalysisScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
