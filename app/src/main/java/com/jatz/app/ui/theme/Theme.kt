package com.jatz.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// JATZ is dark-only by design, matching the mockup — there is no light
// variant to fall back to, so the scheme below is used regardless of the
// system theme setting.
private val JatzColorScheme = darkColorScheme(
    primary = JatzAccent,
    onPrimary = JatzBackground,
    background = JatzBackground,
    onBackground = JatzText,
    surface = JatzSurface,
    onSurface = JatzText,
    surfaceVariant = JatzSurfaceLow,
    onSurfaceVariant = JatzTextDim,
    outline = JatzDivider,
)

object JatzType {
    val screenTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp)
    val albumTitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val albumArtist = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
    val trackTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal)
    val caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
}

@Composable
fun JatzTheme(content: @Composable () -> Unit) {
    // isSystemInDarkTheme() read intentionally, even though unused today: it
    // documents that a future light variant would branch here, matching how
    // every other theme in the codebase is structured.
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = JatzColorScheme,
        content = content,
    )
}
