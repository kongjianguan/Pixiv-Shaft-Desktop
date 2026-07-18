package ceui.pixiv.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import ceui.pixiv.platform.TrackpadGestureBridge
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    onToggleFullscreen: (() -> Unit)? = null
) {
    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember { mutableStateOf(IntSize.Zero) }

    // Clamp a single axis offset so the scaled image edge never floats past the viewport edge.
    // When scaled > viewport: offset in [viewport-scaled, 0] (one edge pinned, other extends).
    // When scaled <= viewport: offset in [0, viewport-scaled] (image stays fully inside).
    fun clampAxis(off: Float, viewport: Float, image: Float, s: Float): Float {
        val scaled = image * s
        return if (scaled > viewport) off.coerceIn(viewport - scaled, 0f)
        else off.coerceIn(0f, viewport - scaled)
    }

    suspend fun clampAndApply(newScale: Float) {
        val vw = viewportSize.width.toFloat()
        val vh = viewportSize.height.toFloat()
        val iw = imageSize.width.toFloat().coerceAtLeast(1f)
        val ih = imageSize.height.toFloat().coerceAtLeast(1f)
        val ox = clampAxis(offsetX.value, vw, iw, newScale)
        val oy = clampAxis(offsetY.value, vh, ih, newScale)
        offsetX.snapTo(ox)
        offsetY.snapTo(oy)
    }

    // Apply a scale change anchored at (ax, ay) in viewport coords, then clamp.
    suspend fun zoomTo(newScale: Float, ax: Float, ay: Float) {
        val oldScale = scale.value
        scale.snapTo(newScale)
        if (newScale <= 1f) {
            offsetX.snapTo(0f)
            offsetY.snapTo(0f)
        } else {
            val ratio = newScale / oldScale
            val rawX = ax - (ax - offsetX.value) * ratio
            val rawY = ay - (ay - offsetY.value) * ratio
            offsetX.snapTo(rawX)
            offsetY.snapTo(rawY)
            clampAndApply(newScale)
        }
    }

    LaunchedEffect(active) {
        if (!active) {
            scale.snapTo(1f)
            offsetX.snapTo(0f)
            offsetY.snapTo(0f)
        }
    }

    // Stable per-instance token so the bridge only lets this view clear its own handler.
    val handlerToken = remember { Any() }
    DisposableEffect(active) {
        if (active) {
            TrackpadGestureBridge.install()
            val handler: (Double) -> Unit = { delta ->
                val oldScale = scale.value
                val factor = (1.0f + delta.toFloat()).coerceIn(0.5f, 2.0f)
                val newScale = (oldScale * factor).coerceIn(0.5f, 5f)
                val vw = viewportSize.width.toFloat()
                val vh = viewportSize.height.toFloat()
                scope.launch {
                    try {
                        zoomTo(newScale, vw / 2f, vh / 2f)
                    } catch (e: CancellationException) { throw e }
                }
            }
            TrackpadGestureBridge.setMagnifyHandler(handlerToken, handler)
            onDispose { TrackpadGestureBridge.setMagnifyHandler(handlerToken, null) }
        } else {
            onDispose {}
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { viewportSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        onToggleFullscreen?.invoke()
                    }
                )
            }
            .pointerInput(active) {
                if (active) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        scope.launch {
                            try {
                                val oldScale = scale.value
                                val newScale = (oldScale * zoom).coerceIn(0.5f, 5f)
                                if (newScale > 1f) {
                                    zoomTo(newScale, centroid.x, centroid.y)
                                    offsetX.snapTo(offsetX.value + pan.x)
                                    offsetY.snapTo(offsetY.value + pan.y)
                                    clampAndApply(newScale)
                                } else {
                                    scale.snapTo(newScale)
                                    offsetX.snapTo(0f)
                                    offsetY.snapTo(0f)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            }
                        }
                    }
                }
            }
            .pointerInput(active) {
                if (active) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val delta = change.scrollDelta
                            if (delta == Offset.Zero) continue
                            val cursorPos = change.position

                            val useCtrl = event.keyboardModifiers.isCtrlPressed
                            scope.launch {
                                try {
                                    if (useCtrl) {
                                        val oldScale = scale.value
                                        val zoomFactor = 1f - delta.y * 0.02f
                                        val newScale = (oldScale * zoomFactor).coerceIn(0.5f, 5f)
                                        zoomTo(newScale, cursorPos.x, cursorPos.y)
                                    } else if (scale.value > 1f) {
                                        offsetX.snapTo(offsetX.value - delta.x * 30f)
                                        offsetY.snapTo(offsetY.value - delta.y * 30f)
                                        clampAndApply(scale.value)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                }
                            }
                        }
                    }
                }
            }
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { imageSize = it }
                .graphicsLayer(
                    scaleX = scale.value,
                    scaleY = scale.value,
                    translationX = offsetX.value,
                    translationY = offsetY.value,
                    transformOrigin = TransformOrigin(0f, 0f)
                ),
            contentScale = ContentScale.Fit
        )
    }
}
