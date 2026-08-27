package com.jatz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jatz.app.data.ExportManager
import com.jatz.app.data.LibraryStore
import com.jatz.app.data.model.AlbumDto
import com.jatz.app.data.model.DropDto
import com.jatz.app.ui.components.AlbumCard
import com.jatz.app.ui.theme.JatzDivider
import com.jatz.app.ui.theme.JatzSurfaceLow
import com.jatz.app.ui.theme.JatzText
import com.jatz.app.ui.theme.JatzTextDim
import com.jatz.app.ui.theme.JatzType
import kotlinx.coroutines.launch

/**
 * Every record ever delivered, oldest drop last — the accumulation the whole
 * concept is built on. Grid of cover art grouped by drop date, Spotify-style,
 * with a search box (the library is a scroll-forever list within a few
 * weeks) and a one-tap export (this is the only copy anywhere — no server,
 * no account — so a way off the phone matters).
 */
@Composable
fun LibraryScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var drops by remember { mutableStateOf<List<DropDto>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        drops = LibraryStore.allDrops(context)
    }

    if (drops.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("La libreria si riempie giorno dopo giorno.", color = JatzTextDim)
        }
        return
    }

    val filtered: List<AlbumDto> = remember(query, drops) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) emptyList()
        else drops.flatMap { it.albums }
            .filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "LIBRERIA",
                style = JatzType.screenTitle,
                color = JatzTextDim,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                scope.launch {
                    val file = ExportManager.buildExportFile(context)
                    ExportManager.share(context, file)
                }
            }) {
                Icon(Icons.Filled.Share, contentDescription = "Esporta libreria", tint = JatzTextDim)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(JatzSurfaceLow)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = JatzTextDim)
            Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp)) {
                if (query.isEmpty()) {
                    Text("Cerca per titolo o artista", style = JatzType.trackTitle, color = JatzTextDim)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    textStyle = JatzType.trackTitle.copy(color = JatzText),
                    cursorBrush = SolidColor(JatzText),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancella ricerca", tint = JatzTextDim)
                }
            }
        }

        if (query.isNotBlank()) {
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nessun disco trovato per “$query”.", color = JatzTextDim)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    items(filtered, key = { it.id }) { album ->
                        AlbumCard(
                            album = album,
                            modifier = Modifier.padding(6.dp),
                            onClick = { navController.navigate("album/${album.id}") },
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
            ) {
                drops.forEachIndexed { i, drop ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            // A thin divider between date groups (not before
                            // the first) -- with dozens of drops accumulating
                            // over time, the boundary between one day's
                            // delivery and the next needs to be scannable at
                            // a glance, not just a color hint.
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
    }
}
