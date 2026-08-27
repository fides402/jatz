package com.jatz.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.jatz.app.ui.theme.JatzAccent
import com.jatz.app.ui.theme.JatzSurface
import com.jatz.app.ui.theme.JatzTextDim

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("today", "Oggi", Icons.Filled.Today),
    Tab("library", "Libreria", Icons.Filled.LibraryMusic),
    Tab("loved", "Loved", Icons.Filled.Favorite),
)

@Composable
fun BottomNavBar(navController: NavController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    NavigationBar(containerColor = JatzSurface) {
        tabs.forEach { tab ->
            val selected = currentRoute?.hierarchy?.any { it.route == tab.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = JatzAccent,
                    selectedTextColor = JatzAccent,
                    unselectedIconColor = JatzTextDim,
                    unselectedTextColor = JatzTextDim,
                    indicatorColor = JatzSurface,
                ),
            )
        }
    }
}
