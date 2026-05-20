package com.magizhchi.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.magizhchi.mobile.data.TokenStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint @InstallIn(SingletonComponent::class)
interface TokenStoreEntryPoint { fun tokenStore(): TokenStore }

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val store = remember {
        EntryPointAccessors.fromApplication(ctx, TokenStoreEntryPoint::class.java).tokenStore()
    }

    val startDest = if (store.token.isNullOrBlank()) "login" else "home"

    NavHost(nav, startDestination = startDest) {
        composable("login") {
            LoginScreen(onLoggedIn = {
                nav.navigate("home") { popUpTo("login") { inclusive = true } }
            })
        }
        composable("home") {
            HomeScreen(
                onOpenList = { table -> nav.navigate("list/$table") },
                onLogout   = { store.clear(); nav.navigate("login") { popUpTo(0) } }
            )
        }
        composable("list/{table}", arguments = listOf(navArgument("table") { type = NavType.StringType })) {
            ListScreen(
                table = it.arguments?.getString("table")!!,
                onRow = { table, pk -> nav.navigate("detail/$table/$pk") }
            )
        }
        composable("detail/{table}/{pk}",
            arguments = listOf(
                navArgument("table") { type = NavType.StringType },
                navArgument("pk")    { type = NavType.StringType }
            )
        ) {
            DetailScreen(
                table = it.arguments?.getString("table")!!,
                rowPk = it.arguments?.getString("pk")!!
            )
        }
    }
}
