package com.tingyiting.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.tingyiting.playback.PlaybackInfo
import com.tingyiting.ui.accounts.AccountsScreen
import com.tingyiting.ui.bookshelf.BookshelfScreen
import com.tingyiting.ui.browser.BrowserScreen
import com.tingyiting.ui.components.BookCover
import com.tingyiting.ui.player.PlayerScreen
import com.tingyiting.ui.server.ServerConfigScreen

@Composable
fun AppNavigation(
    navController: NavHostController,
    playbackViewModel: NavigationPlaybackViewModel = hiltViewModel()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val playbackInfo by playbackViewModel.playbackInfo.collectAsStateWithLifecycle()
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
                    PlayerShortcutItem(
                        playbackInfo = playbackInfo,
                        onClick = {
                            playbackInfo?.let {
                                navController.navigate(Screen.Player.createRoute(it.bookId))
                            }
                        }
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

@Composable
private fun RowScope.PlayerShortcutItem(
    playbackInfo: PlaybackInfo?,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = false,
        enabled = playbackInfo != null,
        onClick = onClick,
        icon = {
            PlayerShortcutIcon(
                title = playbackInfo?.trackTitle.orEmpty(),
                statusIcon = when {
                    playbackInfo == null -> Icons.Filled.Headphones
                    playbackInfo.isPlaying -> Icons.Filled.Pause
                    else -> Icons.Filled.PlayArrow
                },
                enabled = playbackInfo != null
            )
        },
        label = { Text("播放") }
    )
}

@Composable
private fun PlayerShortcutIcon(
    title: String,
    statusIcon: ImageVector,
    enabled: Boolean
) {
    val borderColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = Modifier.size(width = 66.dp, height = 58.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(2.dp, borderColor),
            tonalElevation = if (enabled) 4.dp else 0.dp,
            shadowElevation = if (enabled) 6.dp else 0.dp
        ) {
            if (enabled) {
                BookCover(
                    title = title,
                    modifier = Modifier.size(50.dp),
                    cornerRadius = 10.dp,
                    fontSize = MaterialTheme.typography.titleLarge.fontSize
                )
            } else {
                Box(
                    modifier = Modifier.size(50.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.size(28.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.92f else 0.48f),
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
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
