package com.jatz.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import com.jatz.app.ui.theme.glossyBrush
import com.jatz.app.ui.theme.neumorphic
import com.jatz.app.ui.theme.neumorphicInset

// Fixed, compact sizes: cover + text + slider + the transport disc need to
// fit on a typical phone with no scrolling. The disc is deliberately large
// (matches the reference mockup's dominant click-wheel-style control), the
// cover deliberately smaller than a naive full-width hero image.
private val COVER_SIZE = 176.dp
private val DISC_SIZE = 244.dp

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

        // Sized to fit one screen on typical phones with no scrolling (see
        // COVER_SIZE/DISC_SIZE); verticalScroll stays only as a safety net
        // for unusually short screens or large font-scale settings.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // Extra bottom padding: without it the disc's own 8dp margin
                // was the only thing between it and the screen edge -- too
                // tight, and risks the disc's touch targets landing under a
                // gesture-nav bar on some devices.
                .padding(horizontal = 28.dp, vertical = 4.dp)
                .padding(bottom = 28.dp),
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
                    .padding(vertical = 14.dp)
                    .size(COVER_SIZE)
                    .clip(RoundedCornerShape(20.dp))
                    .background(glossyBrush(JatzSurfaceLow))
                    .neumorphic(cornerRadius = 20.dp, elevation = 8.dp),
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
                modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
                maxLines = 1,
            )

            val shownPosition = dragPositionMs ?: state.positionMs
            ThinSeekBar(
                positionMs = shownPosition,
                durationMs = state.durationMs,
                onDrag = { dragPositionMs = it },
                onSeekFinished = {
                    dragPositionMs?.let { playerController.seekTo(it) }
                    dragPositionMs = null
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatMs(shownPosition), style = JatzType.caption, color = JatzTextDim)
                Text("-" + formatMs((state.durationMs - shownPosition).coerceAtLeast(0)),
                    style = JatzType.caption, color = JatzTextDim)
            }

            if (state.isLoading) {
                Text(
                    text = state.loadingLabel ?: "Carico…",
                    style = JatzType.caption,
                    color = JatzText,
                    modifier = Modifier.padding(top = 6.dp),
                    maxLines = 2,
                )
            }
            state.error?.let {
                Text(text = it, style = JatzType.caption, color = JatzText,
                    modifier = Modifier.padding(top = 6.dp), maxLines = 3)
            }

            // The reference mockup's disc is NOT a raised card with a big
            // filled play button inside it: it's a large, barely-embossed
            // dark disc with a smaller SUNKEN decorative circle in its
            // center, and four small icons at the four cardinal points --
            // repeat/loop at top (replacing shuffle), previous at left, next
            // at right, play/pause at bottom, all the same subtle visual
            // weight. Nothing here is a big white accent button.
            Box(
                modifier = Modifier
                    .padding(top = 14.dp, bottom = 8.dp)
                    .size(DISC_SIZE)
                    .neumorphic(cornerRadius = DISC_SIZE / 2, elevation = 3.dp)
                    .clip(CircleShape)
                    .background(JatzSurface),
                contentAlignment = Alignment.Center,
            ) {
                // Decorative sunken center -- not interactive, just the
                // click-wheel's visual signature. Roughly a third of the
                // disc's diameter, not half -- the icons sit in the wide
                // ring around it, not crowded against a huge center hole.
                Box(
                    modifier = Modifier
                        .size(DISC_SIZE * 0.34f)
                        .clip(CircleShape)
                        .background(JatzSurfaceLow)
                        .neumorphicInset(cornerRadius = DISC_SIZE * 0.17f, elevation = 4.dp),
                )

                IconButton(
                    onClick = { playerController.cycleRepeat() },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
                ) {
                    Icon(
                        imageVector = if (state.repeat == RepeatCycle.TRACK) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = "Loop",
                        tint = if (state.repeat != RepeatCycle.OFF) JatzAccent else JatzTextDim,
                    )
                }
                IconButton(
                    onClick = { playerController.previous() },
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp),
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Precedente", tint = JatzText)
                }
                IconButton(
                    onClick = { playerController.next() },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp),
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Successivo", tint = JatzText)
                }
                IconButton(
                    onClick = { playerController.togglePlayPause() },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pausa",
                        tint = JatzText,
                    )
                }
            }
        }
    }
}

/**
 * A thin progress line with a small dot marker, matching the reference
 * mockup, instead of Material3's default thick track + large thumb.
 *
 * Rather than hand-rolling drag/tap gesture detection (real but easy-to-get-
 * subtly-wrong code that can't be tested on-device from here), this layers a
 * real Material3 [Slider] — fully functional, its interaction already proven
 * — made completely invisible, under a purely decorative [Canvas] that draws
 * the thin line/dot from the exact same value. The Canvas never touches
 * pointer input, so there is no custom gesture code to get wrong.
 */
@Composable
private fun ThinSeekBar(
    positionMs: Long,
    durationMs: Long,
    onDrag: (Long) -> Unit,
    onSeekFinished: () -> Unit,
) {
    val duration = durationMs.coerceAtLeast(1L)
    val progress = (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    // Taller than the visible line itself: gives the real (invisible) Slider
    // underneath a comfortable touch target without affecting how thin the
    // drawn line looks, since the Canvas centers the line within this box
    // regardless of its height.
    Box(modifier = Modifier.fillMaxWidth().height(40.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val midY = size.height / 2f
            val strokeWidth = 3.dp.toPx()
            drawLine(
                color = JatzSurfaceLow,
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            val activeX = size.width * progress
            if (activeX > 0f) {
                drawLine(
                    color = JatzText,
                    start = Offset(0f, midY),
                    end = Offset(activeX, midY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
            // The seek marker itself: a small dark glossy bead (not a flat
            // light dot) -- a soft drop shadow, a dark base, and a small
            // specular glint offset toward the top-left, echoing the same
            // glossy-material read as the buttons elsewhere on this screen.
            val dotRadius = 7.dp.toPx()
            val dotCenter = Offset(activeX, midY)
            drawCircle(
                color = Color.Black.copy(alpha = 0.35f),
                radius = dotRadius,
                center = dotCenter + Offset(1.dp.toPx(), 1.5.dp.toPx()),
            )
            drawCircle(color = JatzSurfaceLow, radius = dotRadius, center = dotCenter)
            drawCircle(
                color = Color.White.copy(alpha = 0.55f),
                radius = dotRadius * 0.35f,
                center = dotCenter + Offset(-dotRadius * 0.35f, -dotRadius * 0.35f),
            )
        }
        Slider(
            value = positionMs.toFloat(),
            onValueChange = { onDrag(it.toLong()) },
            onValueChangeFinished = onSeekFinished,
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier.fillMaxSize(),
            colors = SliderDefaults.colors(
                thumbColor = Color.Transparent,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
        )
    }
}
