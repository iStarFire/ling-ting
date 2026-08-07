package com.lingting.ui.navigation

sealed class Screen(val route: String) {
    data object Bookshelf : Screen("bookshelf")
    data object Accounts : Screen("accounts")
    data object ServerConfig : Screen("server_config")
    data object Browser : Screen("browser?reimportBookId={reimportBookId}&reimportPath={reimportPath}") {
        fun createRoute() = "browser"
        fun createReimportRoute(bookId: Long, path: String): String {
            val encoded = java.net.URLEncoder.encode(path, "UTF-8")
            return "browser?reimportBookId=$bookId&reimportPath=$encoded"
        }
    }
    data object Player : Screen("player/{bookId}") {
        fun createRoute(bookId: Long) = "player/$bookId"
    }
}
