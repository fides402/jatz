package com.jatz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

// Fixed, compact sizes rather than fillMaxWidth/large defaults: the goal is
// for cover + text + slider + transport disc to all fit on a typical phone
// screen with no scrolling needed, not just to look good on a tall preview.
private val COVER_SIZE = 190.dp
private val DISC_SIZE = 168.dp

@Composable
fun PlayerScreen(navController: NavController, playerController: PlayerController) {
    val state by playerController.state.collectAsState()
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }

    val album = state.album
    val track = state.currentTrack

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
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

        // Everything below is sized to fit one screen on typical phones
        // without scrolling (see COVER_SIZE/DISC_SIZE). verticalScroll stays
        // as a safety net only for unusually short screens/large font
        // scales, where it silently kicks in instead of clipping content.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = album.title.uppercase(),
                style = JatzType.screenTitle,
                color = JatzTextDim,
            )

            AsyncImage(
                model = album.coverUrl,
                contentDescription = album.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(COVER_SIZE)
                    .clip(RoundedCornerShape(18.dp))
                    .background(JatzSurfaceLow)
                    .neumorphic(cornerRadius = 18.dp, elevation = 8.dp),
            )

            Text(
                text = track?.title ?: "—",
                style = JatzType.albumTitle,
                color = JatzText,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text(
                text = track?.artist?.ifBlank { album.artist } ?: album.artist,
                style = JatzType.albumArtist,
                color = JatzTextDim,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                maxLines = 1,
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
                    modifier = Modifier.padding(top = 6.dp),
                    maxLines = 2,
                )
            }
            state.error?.let {
                Text(text = it, style = JatzType.caption, color = JatzAccent,
                    modifier = Modifier.padding(top = 6.dp), maxLines = 3)
            }

            // Small satellite icons above the transport disc, mirroring the
            // mockup's single shuffle icon centered above the click-wheel-style
            // control cluster (repeat mirrors it on the other side).
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
                    .padding(top = 4.dp, bottom = 8.dp)
                    .size(DISC_SIZE)
                    .neumorphic(cornerRadius = DISC_SIZE / 2, elevation = 10.dp)
                    .clip(CircleShape)
                    .background(JatzSurface),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { playerController.previous() }, modifier = Modifier.size(46.dp)) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Precedente", tint = JatzText,
                            modifier = Modifier.size(28.dp))
                    }
                    IconButton(
                        onClick = { playerController.togglePlayPause() },
                        modifier = Modifier
                            .size(64.dp)
                            .neumorphic(cornerRadius = 32.dp, elevation = 6.dp)
                            .clip(CircleShape)
                            .background(JatzAccent),
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play/Pausa",
                            tint = JatzSurface,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    IconButton(onClick = { playerController.next() }, modifier = Modifier.size(46.dp)) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Successivo", tint = JatzText,
                            modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}
