package com.tingyiting.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.tingyiting.ui.bookshelf.BookshelfScreen
import com.tingyiting.ui.browser.BrowserScreen
import com.tingyiting.ui.player.PlayerScreen
import com.tingyiting.ui.server.ServerConfigScreen

@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.ServerConfig.route
    ) {
        composable(Screen.ServerConfig.route) {
            ServerConfigScreen(
                onConfigured = {
                    navController.navigate(Screen.Bookshelf.route) {
                        popUpTo(Screen.ServerConfig.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Bookshelf.route) {
            BookshelfScreen(
                onNavigateToBrowser = {
                    navController.navigate(Screen.Browser.route)
                },
                onNavigateToPlayer = { bookId ->
                    navController.navigate(Screen.Player.createRoute(bookId))
                }
            )
        }

        composable(Screen.Browser.route) {
            BrowserScreen(
                onNavigateToBookshelf = {
                    navController.popBackStack()
                },
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
        ) {
            PlayerScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
