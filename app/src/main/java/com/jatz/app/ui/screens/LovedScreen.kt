package com.jatz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jatz.app.data.LibraryStore
import com.jatz.app.data.model.AlbumDto
import com.jatz.app.data.model.TrackDto
import com.jatz.app.playback.PlayerController
import com.jatz.app.ui.theme.JatzAccent
import com.jatz.app.ui.theme.JatzSurfaceLow
import com.jatz.app.ui.theme.JatzText
import com.jatz.app.ui.theme.JatzTextDim
import com.jatz.app.ui.theme.JatzType

/** LOVED TRACKS — the app's equivalent of Spotify's "Brani che mi piacciono". */
@Composable
fun LovedScreen(playerController: PlayerController) {
    val context = LocalContext.current
    var loved by remember { mutableStateOf<List<Pair<AlbumDto, TrackDto>>>(emptyList()) }

    LaunchedEffect(Unit) {
        loved = LibraryStore.lovedTracks(context)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "LOVED TRACKS", style = JatzType.screenTitle, color = JatzTextDim)
        Text(
            text = "${loved.size} brani",
            style = JatzType.albumTitle,
            color = JatzText,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        if (loved.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text("Metti \"mi piace\" a un brano per vederlo qui.", color = JatzTextDim)
            }
            return@Column
        }

        LazyColumn {
            items(loved, key = { (album, track) -> "${album.id}#${track.position}" }) { (album, track) ->
                LovedRow(
                    album = album,
                    track = track,
                    onClick = {
                        val startIndex = album.tracks.indexOfFirst { it.position == track.position }
                            .coerceAtLeast(0)
                        playerController.playAlbum(album, startIndex)
                    },
                )
            }
        }
    }
}

@Composable
private fun LovedRow(album: AlbumDto, track: TrackDto, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = album.coverUrl,
            contentDescription = album.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(JatzSurfaceLow),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(track.title, style = JatzType.trackTitle, color = JatzText,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${track.artist.ifBlank { album.artist }} · ${album.title}",
                style = JatzType.caption, color = JatzTextDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Filled.Favorite, contentDescription = null, tint = JatzAccent)
    }
}
