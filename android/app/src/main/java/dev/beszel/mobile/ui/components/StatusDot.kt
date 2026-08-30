package dev.beszel.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.beszel.mobile.ui.theme.rememberPulse

/**
 * Status indicator with a soft glow halo. Never color-only in usage: pair
 * with a text label or icon at the call site.
 */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    glow: Boolean = true,
    pulse: Boolean = false,
) {
    val haloAlpha = if (pulse) {
        rememberPulse(initialValue = 0.18f, targetValue = 0.5f, durationMillis = 1100, label = "status-pulse", restValue = 0.32f)
    } else {
        0.32f
    }
    Canvas(modifier.size(size * 2.6f)) {
        val radius = size.toPx() / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        if (glow) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = haloAlpha), Color.Transparent),
                    center = center,
                    radius = radius * 3.4f,
                ),
                radius = radius * 3.4f,
                center = center,
            )
        }
        drawCircle(color = color, radius = radius, center = center)
    }
}
