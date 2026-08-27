package com.jatz.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jatz.app.data.model.AlbumDto
import com.jatz.app.ui.theme.JatzSurfaceLow
import com.jatz.app.ui.theme.JatzText
import com.jatz.app.ui.theme.JatzTextDim
import com.jatz.app.ui.theme.JatzType

/** One tile in the library grid — cover, title, artist, Spotify-style. */
@Composable
fun AlbumCard(album: AlbumDto, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = album.coverUrl,
            contentDescription = album.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(JatzSurfaceLow),
        )
        Column(modifier = Modifier.padding(top = 6.dp)) {
            Text(
                text = album.title,
                style = JatzType.trackTitle,
                color = JatzText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${album.artist} · ${album.year}",
                style = JatzType.caption,
                color = JatzTextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
