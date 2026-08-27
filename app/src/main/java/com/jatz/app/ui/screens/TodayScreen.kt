package com.jatz.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jatz.app.data.LibraryStore
import com.jatz.app.data.model.DropDto
import com.jatz.app.data.model.eraEnum
import com.jatz.app.data.model.Era
import com.jatz.app.ui.components.AlbumCard
import com.jatz.app.ui.theme.JatzAccent
import com.jatz.app.ui.theme.JatzText
import com.jatz.app.ui.theme.JatzTextDim
import com.jatz.app.ui.theme.JatzType

/** The core concept, screen 1: today's 5 records — 3 vintage, 2 modern — and nothing else. */
@Composable
fun TodayScreen(navController: NavController) {
    val context = LocalContext.current
    var drop by remember { mutableStateOf<DropDto?>(null) }

    LaunchedEffect(Unit) {
        drop = LibraryStore.latestDrop(context)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "JATZ", style = JatzType.screenTitle, color = JatzTextDim)
        Text(
            text = drop?.date ?: "—",
            style = JatzType.albumTitle,
            color = JatzText,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        val albums = drop?.albums.orEmpty()
        if (albums.isEmpty()) {
            Text("Nessun disco ancora. Il primo drop arriva entro le 8:00.", color = JatzTextDim)
            return@Column
        }

        val vintage = albums.filter { it.eraEnum() == Era.VINTAGE }
        val modern = albums.filter { it.eraEnum() == Era.MODERN }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Column {
                    SectionLabel("VINTAGE · 1968–1983")
                    AlbumShelf(vintage, navController)
                }
            }
            item {
                Column {
                    SectionLabel("MODERNO · dal 2018")
                    AlbumShelf(modern, navController)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = JatzType.caption,
        color = JatzAccent,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * A plain (non-lazy) row of album tiles. The count here is always small (3
 * vintage + 2 modern, occasionally fewer on a thin curation night — see
 * curate.py), so a LazyVerticalGrid would be the wrong tool: nesting a lazy,
 * unbounded-height grid inside a LazyColumn item crashes at runtime. The big
 * scrollable grid lives in LibraryScreen instead, where it belongs.
 */
@Composable
private fun AlbumShelf(albums: List<com.jatz.app.data.model.AlbumDto>, navController: NavController) {
    if (albums.isEmpty()) {
        Text("Nessun disco per questa fascia stanotte.", color = JatzTextDim, style = JatzType.caption)
        return
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        albums.forEach { album ->
            AlbumCard(
                album = album,
                modifier = Modifier
                    .weight(1f)
                    .padding(6.dp),
                onClick = { navController.navigate("album/${album.id}") },
            )
        }
    }
}
