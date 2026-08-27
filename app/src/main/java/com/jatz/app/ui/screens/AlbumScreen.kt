package com.jatz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.jatz.app.data.LibraryStore
import com.jatz.app.data.model.AlbumDto
import com.jatz.app.playback.PlayerController
import com.jatz.app.ui.components.TrackRow
import com.jatz.app.ui.theme.JatzAccent
import com.jatz.app.ui.theme.JatzSurface
import com.jatz.app.ui.theme.JatzSurfaceLow
import com.jatz.app.ui.theme.JatzText
import com.jatz.app.ui.theme.JatzTextDim
import com.jatz.app.ui.theme.JatzType
import com.jatz.app.ui.theme.neumorphic
import kotlinx.coroutines.launch

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

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Indietro", tint = JatzText)
            }
            Text(
                text = a.artist.uppercase(),
                style = JatzType.screenTitle,
                color = JatzTextDim,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        AsyncImage(
            model = a.coverUrl,
            contentDescription = a.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(JatzSurfaceLow),
        )

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(a.title, style = JatzType.albumTitle, color = JatzText)
            Text("${a.artist} · ${a.year}", style = JatzType.albumArtist, color = JatzTextDim)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleAction(icon = Icons.Filled.Shuffle, contentDescription = "Shuffle disco") {
                if (!playerState.shuffle) playerController.toggleShuffle()
                playerController.playAlbum(a, 0)
            }
            IconButton(
                onClick = { playerController.playAlbum(a, 0) },
                modifier = Modifier
                    .size(64.dp)
                    .neumorphic(cornerRadius = 32.dp, elevation = 6.dp)
                    .clip(CircleShape)
                    .background(JatzAccent),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = JatzSurface,
                    modifier = Modifier.size(32.dp))
            }
            CircleAction(icon = Icons.Filled.Shuffle, contentDescription = "Placeholder", alpha = 0f) {}
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
                    onClick = {
                        playerController.playAlbum(a, a.tracks.indexOf(track))
                    },
                    onToggleLoved = {
                        scope.launch {
                            LibraryStore.toggleLoved(context, a.id, track.position)
                            lovedPositions = LibraryStore.lovedKeys(context)
                        }
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
    alpha: Float = 1f,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .neumorphic(cornerRadius = 24.dp, elevation = 5.dp)
            .clip(CircleShape)
            .background(JatzSurface),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = JatzText.copy(alpha = alpha))
    }
}
