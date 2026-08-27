package com.jatz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.jatz.app.data.LibraryStore
import com.jatz.app.data.model.AlbumDto
import com.jatz.app.playback.PlayerController
import com.jatz.app.ui.components.TrackRow
import com.jatz.app.ui.theme.JatzSurface
import com.jatz.app.ui.theme.JatzSurfaceLow
import com.jatz.app.ui.theme.JatzText
import com.jatz.app.ui.theme.JatzTextDim
import com.jatz.app.ui.theme.JatzType
import com.jatz.app.ui.theme.neumorphic
import kotlinx.coroutines.launch

/**
 * Matches the mockup's right panel proportions: a SMALL square cover flanked
 * by two circular buttons (not a full-width cover), so the tracklist below —
 * the thing you actually scroll and tap — gets almost the entire screen
 * instead of a cramped sliver under an oversized hero image.
 */
@Composable
fun AlbumScreen(albumId: String, navController: NavController, playerController: PlayerController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var album by remember { mutableStateOf<AlbumDto?>(null) }
    var lovedPositions by remember { mutableStateOf<Set<String>>(emptySet()) }
    val playerState by playerController.state.collectAsState()

    LaunchedEffect(albumId) {
        album = LibraryStore.findAlbum(context, albumId)
        lovedPositions = LibraryStore.lovedKeys(context)
    }

    val a = album ?: return

    fun playFrom(index: Int) {
        playerController.playAlbum(a, index)
        navController.navigate("player")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Indietro", tint = JatzText)
            }
            Text(
                text = "${a.artist.uppercase()} • ${a.title.uppercase()}",
                style = JatzType.screenTitle,
                color = JatzTextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            // Decorative, matching the mockup's header — no menu exists yet.
            IconButton(onClick = {}) {
                Icon(Icons.Filled.MoreVert, contentDescription = null, tint = JatzTextDim)
            }
        }

        // Heart-cover-shuffle row, centered, with the title/artist block
        // below it rather than beside it — the mockup's actual proportions,
        // not a side-by-side layout invented to save vertical space (the
        // tracklist below already claims all the space this row doesn't use).
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleAction(icon = Icons.Filled.Shuffle, contentDescription = "Shuffle disco") {
                if (!playerState.shuffle) playerController.toggleShuffle()
                playFrom(0)
            }
            Spacer(modifier = Modifier.width(20.dp))
            AsyncImage(
                model = a.coverUrl,
                contentDescription = a.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(104.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(JatzSurfaceLow)
                    .neumorphic(cornerRadius = 14.dp, elevation = 6.dp)
                    .clickable { playFrom(0) },
            )
            Spacer(modifier = Modifier.width(20.dp))
            CircleAction(icon = Icons.Filled.Shuffle, contentDescription = "Shuffle disco") {
                if (!playerState.shuffle) playerController.toggleShuffle()
                playFrom(0)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(a.title, style = JatzType.albumTitle, color = JatzText, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text("${a.artist} · ${a.year}", style = JatzType.caption, color = JatzTextDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(a.tracks, key = { it.position }) { track ->
                val isCurrent = playerState.album?.id == a.id &&
                    playerState.currentTrack?.position == track.position
                val loved = "${a.id}#${track.position}" in lovedPositions
                TrackRow(
                    index = a.tracks.indexOf(track) + 1,
                    track = track,
                    isPlaying = isCurrent && playerState.isPlaying,
                    isLoved = loved,
                    onClick = { playFrom(a.tracks.indexOf(track)) },
                    onToggleLoved = {
                        scope.launch {
                            LibraryStore.toggleLoved(context, a.id, track.position)
                            lovedPositions = LibraryStore.lovedKeys(context)
                        }
                    },
                    // The mockup highlights the current track with a filled
                    // rounded-rect background rather than just text color.
                    modifier = if (isCurrent) {
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(JatzSurfaceLow)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun CircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .neumorphic(cornerRadius = 22.dp, elevation = 5.dp)
            .clip(CircleShape)
            .background(JatzSurface),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = JatzText)
    }
}
