package com.jatz.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jatz.app.data.LibraryStore
import com.jatz.app.data.model.DropDto
import com.jatz.app.ui.components.AlbumCard
import com.jatz.app.ui.theme.JatzDivider
import com.jatz.app.ui.theme.JatzText
import com.jatz.app.ui.theme.JatzTextDim
import com.jatz.app.ui.theme.JatzType

/**
 * Every record ever delivered, oldest drop last — the accumulation the whole
 * concept is built on. Grid of cover art grouped by drop date, Spotify-style.
 */
@Composable
fun LibraryScreen(navController: NavController) {
    val context = LocalContext.current
    var drops by remember { mutableStateOf<List<DropDto>>(emptyList()) }

    LaunchedEffect(Unit) {
        drops = LibraryStore.allDrops(context)
    }

    if (drops.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("La libreria si riempie giorno dopo giorno.", color = JatzTextDim)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "LIBRERIA",
                style = JatzType.screenTitle,
                color = JatzTextDim,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        drops.forEachIndexed { i, drop ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    // A thin divider between date groups (not before the
                    // first) -- with dozens of drops accumulating over time,
                    // the boundary between one day's delivery and the next
                    // needs to be scannable at a glance, not just a color hint.
                    if (i > 0) {
                        HorizontalDivider(
                            color = JatzDivider,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    Text(
                        text = drop.date,
                        style = JatzType.caption,
                        color = JatzText,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }
            }
            items(drop.albums, key = { it.id }) { album ->
                AlbumCard(
                    album = album,
                    modifier = Modifier.padding(6.dp),
                    onClick = { navController.navigate("album/${album.id}") },
                )
            }
        }
    }
}
