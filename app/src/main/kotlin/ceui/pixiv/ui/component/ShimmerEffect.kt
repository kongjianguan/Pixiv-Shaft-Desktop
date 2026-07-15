package ceui.pixiv.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

private val shimmerBase   = Color(0xFFE0E0E0)
private val shimmerMid    = Color(0xFFECECEC)
private val shimmerShine = Color(0xFFF5F5F5)

@Composable
fun rememberShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )
    // A ~1000px-wide gradient sweeps from left to right.
    // Base color on both edges, bright in the middle => highlight sweeps across.
    return Brush.linearGradient(
        colors = listOf(shimmerBase, shimmerMid, shimmerShine, shimmerMid, shimmerBase),
        start = Offset(x = translateAnim - 500f, y = 0f),
        end   = Offset(x = translateAnim + 500f, y = 0f),
    )
}

/** Draws a shimmer animation as the background of the composable. */
@Composable
fun Modifier.shimmer(shape: Shape = RoundedCornerShape(8.dp)): Modifier {
    val brush = rememberShimmerBrush()
    return background(brush, shape)
}

/** A shimmering placeholder box (skeleton card). */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp)
) {
    Box(
        modifier = modifier.shimmer(shape)
    )
}
