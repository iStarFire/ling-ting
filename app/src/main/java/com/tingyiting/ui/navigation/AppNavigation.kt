package com.tingyiting.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.tingyiting.ui.accounts.AccountsScreen
import com.tingyiting.ui.bookshelf.BookshelfScreen
import com.tingyiting.ui.browser.BrowserScreen
import com.tingyiting.ui.player.PlayerScreen
import com.tingyiting.ui.server.ServerConfigScreen

@Composable
fun AppNavigation(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    // 仅在顶层页（书架 / 账号）显示底部导航，浏览/播放/编辑子页隐藏
    val showBottomBar = currentRoute == Screen.Bookshelf.route || currentRoute == Screen.Accounts.route

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Screen.Bookshelf.route,
                        onClick = { navigateTopLevel(navController, Screen.Bookshelf.route) },
                        icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                        label = { Text("书架") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.Accounts.route,
                        onClick = { navigateTopLevel(navController, Screen.Accounts.route) },
                        icon = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                        label = { Text("账号") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Bookshelf.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Bookshelf.route) {
                BookshelfScreen(
                    onNavigateToBrowser = { navController.navigate(Screen.Browser.route) },
                    onNavigateToPlayer = { bookId ->
                        navController.navigate(Screen.Player.createRoute(bookId))
                    },
                    onNavigateToAccounts = { navController.navigate(Screen.Accounts.route) },
                    onNavigateToWebDav = { navController.navigate(Screen.ServerConfig.route) }
                )
            }

            composable(Screen.Accounts.route) {
                AccountsScreen(
                    onNavigateToWebDav = { navController.navigate(Screen.ServerConfig.route) }
                )
            }

            composable(Screen.ServerConfig.route) {
                ServerConfigScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Browser.route) {
                BrowserScreen(
                    onNavigateToBookshelf = { navController.popBackStack() },
                    onNavigateToPlayer = { bookId ->
                        navController.navigate(Screen.Player.createRoute(bookId)) {
                            popUpTo(Screen.Bookshelf.route)
                        }
                    }
                )
            }

            composable(
                route = Screen.Player.route,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType })
            ) { backStackEntry ->
                val bookId = backStackEntry.arguments?.getLong("bookId") ?: 0L
                PlayerScreen(
                    bookId = bookId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}

private fun navigateTopLevel(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
