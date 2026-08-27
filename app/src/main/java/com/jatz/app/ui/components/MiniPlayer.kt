package com.jatz.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jatz.app.playback.PlayerController
import com.jatz.app.playback.PlayerUiState
import com.jatz.app.ui.theme.JatzAccent
import com.jatz.app.ui.theme.JatzSurface
import com.jatz.app.ui.theme.JatzText
import com.jatz.app.ui.theme.JatzTextDim
import com.jatz.app.ui.theme.JatzType
import com.jatz.app.ui.theme.glossyBrush
import com.jatz.app.ui.theme.neumorphic

@Composable
fun MiniPlayer(state: PlayerUiState, controller: PlayerController, onOpen: () -> Unit) {
    val album = state.album ?: return
    val track = state.currentTrack

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // More room below than above: it sits directly on top of the
            // bottom nav bar, and the earlier symmetric 6dp read as no gap
            // at all against that adjacent surface.
            .padding(horizontal = 12.dp, top = 6.dp, bottom = 12.dp)
            .neumorphic(cornerRadius = 16.dp, elevation = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(glossyBrush(JatzSurface))
            .clickable(onClick = onOpen)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = album.coverUrl,
            contentDescription = album.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(
                text = track?.title ?: album.title,
                style = JatzType.trackTitle,
                color = JatzText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The loading label and error are surfaced here, not just on the
            // full Player screen: tapping play never used to navigate there,
            // so a stuck resolution or a failure looked like "nothing
            // happens" with zero visible feedback anywhere in the app.
            Text(
                text = state.error ?: state.loadingLabel ?: (track?.artist?.ifBlank { album.artist } ?: album.artist),
                style = JatzType.caption,
                color = if (state.error != null) JatzAccent else JatzTextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp).padding(end = 8.dp),
                color = JatzAccent,
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(
                onClick = { controller.togglePlayPause() },
                // Extra end margin: IconButton's own 48dp touch bounds sat
                // flush against the pill's rounded corner with only the
                // row's uniform 8dp padding around it -- looked pinned to
                // the edge rather than centered with room to breathe.
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = JatzText,
                )
            }
        }
    }
}
