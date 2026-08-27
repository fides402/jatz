package com.jatz.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jatz.app.playback.PlayerController
import com.jatz.app.ui.components.BottomNavBar
import com.jatz.app.ui.components.MiniPlayer
import com.jatz.app.ui.screens.AlbumScreen
import com.jatz.app.ui.screens.LibraryScreen
import com.jatz.app.ui.screens.LovedScreen
import com.jatz.app.ui.screens.PlayerScreen
import com.jatz.app.ui.screens.TodayScreen
import com.jatz.app.ui.theme.JatzBackground
import com.jatz.app.ui.theme.JatzTheme

/** The whole app, hung off the single [PlayerController] instance from JatzApp (the Application). */
@Composable
fun JatzApp(playerController: PlayerController) {
    JatzTheme {
        val navController = rememberNavController()
        val playerState by playerController.state.collectAsState()

        Scaffold(
            containerColor = JatzBackground,
            bottomBar = {
                Column {
                    if (playerState.album != null) {
                        MiniPlayer(
                            state = playerState,
                            controller = playerController,
                            onOpen = { navController.navigate("player") },
                        )
                    }
                    BottomNavBar(navController)
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "today",
                modifier = Modifier.padding(padding),
            ) {
                composable("today") { TodayScreen(navController) }
                composable("library") { LibraryScreen(navController) }
                composable("loved") { LovedScreen(playerController) }
                composable(
                    route = "album/{albumId}",
                    arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val albumId = backStackEntry.arguments?.getString("albumId") ?: return@composable
                    AlbumScreen(albumId, navController, playerController)
                }
                composable("player") { PlayerScreen(navController, playerController) }
            }
        }
    }
}
