package com.jatz.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jatz.app.data.model.TrackDto
import com.jatz.app.ui.theme.JatzAccent
import com.jatz.app.ui.theme.JatzText
import com.jatz.app.ui.theme.JatzTextDim
import com.jatz.app.ui.theme.JatzType

@Composable
fun TrackRow(
    index: Int,
    track: TrackDto,
    isPlaying: Boolean,
    isLoved: Boolean,
    onClick: () -> Unit,
    onToggleLoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$index",
            style = JatzType.caption,
            color = if (isPlaying) JatzAccent else JatzTextDim,
            modifier = Modifier.width(24.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = JatzType.trackTitle,
                color = if (isPlaying) JatzAccent else JatzText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.artist.isNotBlank()) {
                Text(
                    text = track.artist,
                    style = JatzType.caption,
                    color = JatzTextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (track.duration.isNotBlank()) {
            Text(text = track.duration, style = JatzType.caption, color = JatzTextDim)
        }
        IconButton(onClick = onToggleLoved) {
            Icon(
                imageVector = if (isLoved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = "Loved",
                tint = if (isLoved) JatzAccent else JatzTextDim,
            )
        }
    }
}
