package com.jatz.app.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A soft diagonal sheen for raised, non-photo surfaces (buttons, the
 * transport disc, card frames) — the glossy/plastic material read the
 * reference mockup has, where a flat fill colour alone looks matte and dead.
 * Never apply this over actual photos (album art): it's a highlight for
 * synthetic surfaces, not something a photo should have painted over it.
 *
 * `Offset.Infinite` is a real `Brush.linearGradient` sentinel meaning
 * "resolve to this element's own bottom-right corner at draw time" — the
 * gradient stays diagonal regardless of the element's actual size.
 */
fun glossyBrush(base: Color, highlightAlpha: Float = 0.14f): Brush = Brush.linearGradient(
    colors = listOf(Color.White.copy(alpha = highlightAlpha), base, base),
    start = Offset.Zero,
    end = Offset.Infinite,
)

/**
 * The two-shadow "soft UI" look from the reference mockup: a light shadow to
 * the top-left and a dark one to the bottom-right of every raised surface.
 *
 * Compose has no built-in dual offset shadow, so this leans on the classic
 * [android.graphics.Paint.setShadowLayer] trick (works on every API level,
 * unlike RenderEffect which needs 31+): draw the same rounded-rect shape
 * twice with a blurred, offset shadow each time, then let the caller's own
 * `.background()` paint the flat surface color on top, leaving just the
 * blurred halo visible around the edges.
 *
 * Usage:
 * `Modifier.neumorphic(cornerRadius = 20.dp).background(JatzSurface, RoundedCornerShape(20.dp))`
 * — the corner radius passed here and to `.background()`/`.clip()` should match.
 */
fun Modifier.neumorphic(
    cornerRadius: Dp = 20.dp,
    elevation: Dp = 6.dp,
    lightColor: Color = JatzShadowLight,
    darkColor: Color = JatzShadowDark,
): Modifier = this.drawBehind {
    val radiusPx = cornerRadius.toPx()
    val elevationPx = elevation.toPx()
    val shift = elevationPx * 0.7f

    drawIntoCanvas { canvas ->
        val paint = Paint()
        val frameworkPaint = paint.asFrameworkPaint()
        frameworkPaint.color = android.graphics.Color.TRANSPARENT

        // Dark shadow, offset toward the bottom-right.
        frameworkPaint.setShadowLayer(elevationPx, shift, shift, darkColor.copy(alpha = 0.55f).toArgb())
        canvas.drawRoundRect(0f, 0f, size.width, size.height, radiusPx, radiusPx, paint)

        // Light shadow, offset toward the top-left.
        frameworkPaint.setShadowLayer(elevationPx, -shift, -shift, lightColor.copy(alpha = 0.45f).toArgb())
        canvas.drawRoundRect(0f, 0f, size.width, size.height, radiusPx, radiusPx, paint)
    }
}

/** The inset/"pressed" counterpart — used for the seek bar track and the
 * player's central control ring, which read as carved-in rather than raised. */
fun Modifier.neumorphicInset(
    cornerRadius: Dp = 20.dp,
    elevation: Dp = 4.dp,
    lightColor: Color = JatzShadowLight,
    darkColor: Color = JatzShadowDark,
): Modifier = this.drawBehind {
    val radiusPx = cornerRadius.toPx()
    val elevationPx = elevation.toPx()
    val shift = elevationPx * 0.6f
    val inset = elevationPx

    drawIntoCanvas { canvas ->
        val paint = Paint()
        val frameworkPaint = paint.asFrameworkPaint()
        frameworkPaint.color = android.graphics.Color.TRANSPARENT

        // A slightly smaller, inverted-offset shadow pair reads as a carved
        // groove rather than a raised block — an approximation of an inset
        // shadow, which Paint.setShadowLayer cannot do directly.
        frameworkPaint.setShadowLayer(elevationPx, -shift, -shift, darkColor.copy(alpha = 0.5f).toArgb())
        canvas.drawRoundRect(inset, inset, size.width - inset, size.height - inset, radiusPx, radiusPx, paint)

        frameworkPaint.setShadowLayer(elevationPx, shift, shift, lightColor.copy(alpha = 0.35f).toArgb())
        canvas.drawRoundRect(inset, inset, size.width - inset, size.height - inset, radiusPx, radiusPx, paint)
    }
}
