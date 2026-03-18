package com.m3mo.vault.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.m3mo.vault.di.ServiceLocator
import com.m3mo.vault.feature.add.AddMemoryScreen
import com.m3mo.vault.feature.add.AddMemoryViewModel
import com.m3mo.vault.feature.home.HomeScreen
import com.m3mo.vault.feature.home.HomeViewModel
import com.m3mo.vault.util.viewModelFactory

/**
 * Navigation routes
 */
object Routes {
    const val HOME = "home"
    const val ADD = "add"
    const val ADD_EDIT = "add/{memoryId}"
    const val SEARCH = "search"       // Batch 8b
    const val REVIEW = "review"       // Batch 8b
    const val SETTINGS = "settings"   // Batch 8b

    fun addEdit(memoryId: Long) = "add/$memoryId"
}

/**
 * Bottom navigation items
 */
enum class BottomNavItem(
    val route: String,
    val icon: ImageVector,
    val label: String
) {
    HOME(Routes.HOME, Icons.Default.Home, "Home"),
    SEARCH(Routes.SEARCH, Icons.Default.Search, "Search"),
    REVIEW(Routes.REVIEW, Icons.Default.Star, "Review"),
    SETTINGS(Routes.SETTINGS, Icons.Default.Settings, "Settings")
}

/**
 * M3moNavGraph - Main navigation host with bottom bar.
 */
@Composable
fun M3moNavGraph() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Hide bottom bar on Add screen
    val showBottomBar = currentDestination?.route?.startsWith("add") != true

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    BottomNavItem.entries.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentDestination?.hierarchy?.any {
                                it.route == item.route
                            } == true,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding)
        ) {
            // ===== Home =====
            composable(Routes.HOME) { backStackEntry ->
                val vm: HomeViewModel = viewModel(
                    factory = viewModelFactory {
                        HomeViewModel(
                            memoryRepository = ServiceLocator.memoryRepository,
                            deleteMemoryUseCase = ServiceLocator.deleteMemoryUseCase,
                            eventBus = ServiceLocator.eventBus,
                            logger = ServiceLocator.logger
                        )
                    }
                )

                // Refresh every time this screen becomes visible (including back navigation)
                val lifecycleState = backStackEntry.lifecycle.currentState
                LaunchedEffect(lifecycleState) {
                    if (lifecycleState == Lifecycle.State.RESUMED) {
                        vm.loadMemories()
                    }
                }

                HomeScreen(
                    viewModel = vm,
                    onNavigateToAdd = { navController.navigate(Routes.ADD) },
                    onNavigateToEdit = { id -> navController.navigate(Routes.addEdit(id)) },
                    onNavigateToSearch = { navController.navigate(Routes.SEARCH) }
                )
            }

            // ===== Add (new) =====
            composable(Routes.ADD) {
                val vm: AddMemoryViewModel = viewModel(
                    factory = viewModelFactory {
                        AddMemoryViewModel(
                            createMemoryUseCase = ServiceLocator.createMemoryUseCase,
                            updateMemoryUseCase = ServiceLocator.updateMemoryUseCase,
                            memoryRepository = ServiceLocator.memoryRepository,
                            idGenerator = ServiceLocator.idGenerator,
                            logger = ServiceLocator.logger
                        )
                    }
                )
                AddMemoryScreen(
                    viewModel = vm,
                    editMemoryId = null,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // ===== Add (edit existing) =====
            composable(
                route = Routes.ADD_EDIT,
                arguments = listOf(navArgument("memoryId") { type = NavType.LongType })
            ) { backStackEntry ->
                val memoryId = backStackEntry.arguments?.getLong("memoryId") ?: return@composable
                val vm: AddMemoryViewModel = viewModel(
                    factory = viewModelFactory {
                        AddMemoryViewModel(
                            createMemoryUseCase = ServiceLocator.createMemoryUseCase,
                            updateMemoryUseCase = ServiceLocator.updateMemoryUseCase,
                            memoryRepository = ServiceLocator.memoryRepository,
                            idGenerator = ServiceLocator.idGenerator,
                            logger = ServiceLocator.logger
                        )
                    }
                )
                AddMemoryScreen(
                    viewModel = vm,
                    editMemoryId = memoryId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // ===== Batch 8b Placeholders =====
            composable(Routes.SEARCH) {
                PlaceholderScreen(title = "Search", subtitle = "Coming in Batch 8b")
            }
            composable(Routes.REVIEW) {
                PlaceholderScreen(title = "Weekly Review", subtitle = "Coming in Batch 8b")
            }
            composable(Routes.SETTINGS) {
                PlaceholderScreen(title = "Settings", subtitle = "Coming in Batch 8b")
            }
        }
    }
}

/**
 * Temporary placeholder for Batch 8b screens.
 */
@Composable
private fun PlaceholderScreen(title: String, subtitle: String) {
    com.m3mo.vault.ui.component.EmptyState(
        title = title,
        subtitle = subtitle
    )
}