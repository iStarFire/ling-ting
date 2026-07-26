package com.tingyiting.ui.navigation

sealed class Screen(val route: String) {
    data object Bookshelf : Screen("bookshelf")
    data object Accounts : Screen("accounts")
    data object ServerConfig : Screen("server_config")
    data object Browser : Screen("browser")
    data object Player : Screen("player/{bookId}") {
        fun createRoute(bookId: Long) = "player/$bookId"
    }
}
