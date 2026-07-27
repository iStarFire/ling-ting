package com.tingyiting.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.tingyiting.ui.components.CoverArtwork
import com.tingyiting.ui.player.PlayerScreen
import com.tingyiting.ui.server.ServerConfigScreen

/**
 * 播放页状态持有者：记录当前打开的播放页 bookId，null 表示播放页未打开。
 * 播放页作为全屏覆盖层从底部滑出，覆盖在 NavHost 上方，不与根 Scaffold 交互，
 * 彻底消除导航条显隐带来的布局跳变闪烁。
 *
 * visible 独立于 bookId：关闭时先将 visible 置 false 触发退出动画，
 * 动画播完后（onExitComplete）再清 bookId，避免 AnimatedVisibility 直接
 * 从组合树移除导致退出动画无法播放。
 */
class PlayerOverlayState {
    var bookId by mutableStateOf<Long?>(null)
        private set
    var visible by mutableStateOf(false)
        private set

    fun open(bookId: Long) {
        this.bookId = bookId
        this.visible = true
    }

    fun close() {
        this.visible = false
    }

    /** 由 AnimatedVisibility 退出动画完成后调用，清理 bookId。 */
    fun onExited() {
        bookId = null
    }
}

@Composable
fun rememberPlayerOverlayState(): PlayerOverlayState = remember { PlayerOverlayState() }

@Composable
fun AppNavigation(
    navController: NavHostController,
    playbackViewModel: NavigationPlaybackViewModel = hiltViewModel(),
    playerOverlay: PlayerOverlayState = rememberPlayerOverlayState()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val playbackInfo by playbackViewModel.playbackInfo.collectAsStateWithLifecycle()
    val coverUrl by playbackViewModel.coverUrl.collectAsStateWithLifecycle()
    val lastPlayedBook by playbackViewModel.lastPlayedBook.collectAsStateWithLifecycle()
    // 仅在顶层页（书架 / 账号）显示底部导航，浏览/播放/编辑子页隐藏
    val showBottomBar = currentRoute == Screen.Bookshelf.route || currentRoute == Screen.Accounts.route

    // 解决底部按钮与 PlayerScreen 圆形播放按钮的高度一致问题：
    // 任何时候都展示有效内容（有当前播放则用播放信息，否则用最近播放过的专辑，仍无则占位），
    // 让两个页面切换时大小稳定、不会跳动。
    val miniShortcutInfo = playbackInfo ?: lastPlayedBook?.let { book ->
        PlaybackInfo(
            bookId = book.id,
            isPlaying = false,
            currentPosition = book.position,
            duration = book.duration,
            currentTrackIndex = 0,
            trackTitle = book.title,
            trackCount = 1
        )
    }
    val miniShortcutCoverUrl = coverUrl ?: lastPlayedBook?.coverUrl

    // 路由到播放页：不经过 NavHost，直接打开覆盖层
    val onNavigateToPlayer: (Long) -> Unit = { bookId -> playerOverlay.open(bookId) }

    Box(modifier = Modifier.fillMaxSize()) {
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
                            playbackInfo = miniShortcutInfo,
                            coverUrl = miniShortcutCoverUrl,
                            onClick = {
                                val info = miniShortcutInfo
                                if (info != null) {
                                    // 有当前播放：先恢复播放再跳转；上次播放：跳转后自动载入
                                    if (playbackInfo != null) playbackViewModel.resumeIfPaused()
                                    playerOverlay.open(info.bookId)
                                }
                            }
                        )
                        NavigationBarItem(
                            selected = currentRoute == Screen.Accounts.route,
                            onClick = { navigateTopLevel(navController, Screen.Accounts.route) },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = { Text("设置") }
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
                        onNavigateToBrowser = { navController.navigate(Screen.Browser.createRoute()) },
                        onNavigateToReimport = { bookId, path ->
                            navController.navigate(Screen.Browser.createReimportRoute(bookId, path))
                        },
                        onNavigateToPlayer = onNavigateToPlayer,
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

                composable(
                    route = Screen.Browser.route,
                    arguments = listOf(
                        navArgument("reimportBookId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("reimportPath") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) {
                    val reimportBookId = it.arguments?.getString("reimportBookId")?.toLongOrNull()
                    val reimportPath = it.arguments?.getString("reimportPath")
                    BrowserScreen(
                        onNavigateToBookshelf = { navController.popBackStack() },
                        onNavigateToPlayer = { bookId ->
                            // 浏览器导入完成后跳到播放页：先回到书架，再打开覆盖层
                            navController.popBackStack(Screen.Bookshelf.route, false)
                            playerOverlay.open(bookId)
                        },
                        reimportBookId = reimportBookId,
                        reimportPath = reimportPath
                    )
                }
            }
        }

        // 播放页覆盖层：从底部以抽屉动画滑出，覆盖在 NavHost/Scaffold 之上。
        // Scaffold 完全不受影响 → 没有 bottomBar 显隐 → 没有闪烁。
        // bookId 在退出动画播完后才清空，保证 AnimatedVisibility 有内容可动画。
        val openBookId = playerOverlay.bookId
        if (openBookId != null) {
            BackHandler(enabled = true) { playerOverlay.close() }
            androidx.compose.animation.AnimatedVisibility(
                visible = playerOverlay.visible,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(durationMillis = 600, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(durationMillis = 500, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                )
            ) {
                PlayerScreen(
                    bookId = openBookId,
                    onNavigateBack = { playerOverlay.close() }
                )
            }
            // 退出动画播完后清理 bookId
            LaunchedEffect(playerOverlay.visible) {
                if (!playerOverlay.visible) {
                    delay(500)
                    playerOverlay.onExited()
                }
            }
        }
    }
}

@Composable
private fun RowScope.PlayerShortcutItem(
    playbackInfo: PlaybackInfo?,
    coverUrl: String?,
    onClick: () -> Unit
) {
    val enabled = playbackInfo != null
    val progress = if (enabled && playbackInfo!!.duration > 0) {
        playbackInfo.currentPosition.toFloat() / playbackInfo.duration
    } else 0f
    NavigationBarItem(
        selected = false,
        enabled = enabled,
        onClick = onClick,
        icon = {
            PlayerShortcutIcon(
                title = playbackInfo?.trackTitle.orEmpty(),
                coverUrl = coverUrl.orEmpty(),
                isPlaying = playbackInfo?.isPlaying == true,
                enabled = enabled,
                progress = progress
            )
        },
        label = null
    )
}

/**
 * 底部导航中间的播放快捷入口：
 * - 有当前播放：圆形封面，播放中缓慢自转，外圈展示播放进度
 * - 无播放：占位播放图标
 * - 视觉上比侧边两项更紧凑，整体高度更小
 */
@Composable
private fun PlayerShortcutIcon(
    title: String,
    coverUrl: String,
    isPlaying: Boolean,
    enabled: Boolean,
    progress: Float
) {
    val transition = rememberInfiniteTransition(label = "player-rot")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // 尺寸与播放页主播放按钮 (FilledIconButton.size(80.dp)) 一致，
    // 避免在书架页与播放页之间切换时按钮视觉上跳动。
    Box(
        modifier = Modifier.size(SHORTCUT_ICON_SIZE),
        contentAlignment = Alignment.Center
    ) {
        // 外圈进度环
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 4.dp,
            color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            strokeCap = StrokeCap.Round
        )
        // 封面（圆形，播放中旋转）
        Box(
            modifier = Modifier
                .padding(6.dp)
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .graphicsLayer {
                    rotationZ = if (isPlaying) rotation else 0f
                },
            contentAlignment = Alignment.Center
        ) {
            if (enabled && coverUrl.isNotBlank()) {
                CoverArtwork(
                    title = title,
                    coverUrl = coverUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 28.dp,
                    fallbackFontSize = 16.sp
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private val SHORTCUT_ICON_SIZE = 80.dp

private fun navigateTopLevel(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}