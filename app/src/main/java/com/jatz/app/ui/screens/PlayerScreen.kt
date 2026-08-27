package com.jatz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.jatz.app.playback.PlayerController
import com.jatz.app.playback.RepeatCycle
import com.jatz.app.ui.formatMs
import com.jatz.app.ui.theme.JatzAccent
import com.jatz.app.ui.theme.JatzSurface
import com.jatz.app.ui.theme.JatzSurfaceLow
import com.jatz.app.ui.theme.JatzText
import com.jatz.app.ui.theme.JatzTextDim
import com.jatz.app.ui.theme.JatzType
import com.jatz.app.ui.theme.neumorphic

@Composable
fun PlayerScreen(navController: NavController, playerController: PlayerController) {
    val state by playerController.state.collectAsState()
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }

    val album = state.album
    val track = state.currentTrack

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Filled.ExpandMore, contentDescription = "Chiudi", tint = JatzText)
            }
        }

        if (album == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nessun disco in riproduzione.", color = JatzTextDim)
            }
            return@Column
        }

        // Scrollable: cover + text + slider + the 220dp transport disc can
        // together exceed a smaller phone's usable height once the bottom
        // nav/mini-player has already taken its share, so nothing here
        // relies on everything fitting one unscrolled screen.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Text(
            text = album.title.uppercase(),
            style = JatzType.screenTitle,
            color = JatzTextDim,
            modifier = Modifier.padding(top = 8.dp),
        )

        AsyncImage(
            model = album.coverUrl,
            contentDescription = album.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(JatzSurfaceLow)
                .neumorphic(cornerRadius = 20.dp, elevation = 8.dp),
        )

        Text(
            text = track?.title ?: "—",
            style = JatzType.albumTitle,
            color = JatzText,
            textAlign = TextAlign.Center,
        )
        Text(
            text = track?.artist?.ifBlank { album.artist } ?: album.artist,
            style = JatzType.albumArtist,
            color = JatzTextDim,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        val shownPosition = dragPositionMs ?: state.positionMs
        Slider(
            value = shownPosition.toFloat(),
            onValueChange = { dragPositionMs = it.toLong() },
            onValueChangeFinished = {
                dragPositionMs?.let { playerController.seekTo(it) }
                dragPositionMs = null
            },
            valueRange = 0f..state.durationMs.coerceAtLeast(1L).toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = JatzAccent,
                activeTrackColor = JatzAccent,
                inactiveTrackColor = JatzSurfaceLow,
            ),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(shownPosition), style = JatzType.caption, color = JatzTextDim)
            Text("-" + formatMs((state.durationMs - shownPosition).coerceAtLeast(0)),
                style = JatzType.caption, color = JatzTextDim)
        }

        if (state.isLoading) {
            Text(
                text = state.loadingLabel ?: "Carico…",
                style = JatzType.caption,
                color = JatzAccent,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        state.error?.let {
            Text(text = it, style = JatzType.caption, color = JatzAccent, modifier = Modifier.padding(top = 12.dp))
        }

        // Small satellite icons above the transport disc, mirroring the
        // mockup's single shuffle icon centered above the click-wheel-style
        // control cluster (repeat mirrors it on the other side).
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { playerController.toggleShuffle() }) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (state.shuffle) JatzAccent else JatzTextDim,
                )
            }
            IconButton(onClick = { playerController.cycleRepeat() }) {
                Icon(
                    imageVector = if (state.repeat == RepeatCycle.TRACK) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = "Repeat",
                    tint = if (state.repeat != RepeatCycle.OFF) JatzAccent else JatzTextDim,
                )
            }
        }

        // One big embossed disc holding prev/play-pause/next together, like
        // the mockup's click-wheel-style transport cluster, instead of five
        // separate buttons spread across a plain row.
        Box(
            modifier = Modifier
                .padding(top = 12.dp)
                .size(220.dp)
                .neumorphic(cornerRadius = 110.dp, elevation = 10.dp)
                .clip(CircleShape)
                .background(JatzSurface),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { playerController.previous() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Precedente", tint = JatzText,
                        modifier = Modifier.size(34.dp))
                }
                IconButton(
                    onClick = { playerController.togglePlayPause() },
                    modifier = Modifier
                        .size(76.dp)
                        .neumorphic(cornerRadius = 38.dp, elevation = 6.dp)
                        .clip(CircleShape)
                        .background(JatzAccent),
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pausa",
                        tint = JatzSurface,
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = { playerController.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Successivo", tint = JatzText,
                        modifier = Modifier.size(34.dp))
                }
            }
        }
        }
    }
}
