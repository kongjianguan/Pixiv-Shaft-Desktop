package ceui.pixiv.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onToggleFullscreen: (() -> Unit)? = null
) {
    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var ctrlPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.key == Key.CtrlLeft || keyEvent.key == Key.CtrlRight) {
                    ctrlPressed = keyEvent.type == KeyEventType.KeyDown
                }
                false
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        onToggleFullscreen?.invoke()
                    },
                    onDoubleTap = {
                        val target = if (scale.value < 1.5f) 2f else 1f
                        scope.launch {
                            try {
                                scale.animateTo(target)
                                if (target == 1f) {
                                    offsetX.animateTo(0f)
                                    offsetY.animateTo(0f)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scope.launch {
                        try {
                            scale.snapTo((scale.value * zoom).coerceIn(0.5f, 5f))
                            if (scale.value > 1f) {
                                offsetX.snapTo(offsetX.value + pan.x)
                                offsetY.snapTo(offsetY.value + pan.y)
                            } else {
                                offsetX.snapTo(0f)
                                offsetY.snapTo(0f)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val delta = change.scrollDelta
                        if (delta == Offset.Zero) continue

                        val useCtrl = ctrlPressed
                        scope.launch {
                            try {
                                if (useCtrl) {
                                    // Ctrl+scroll = zoom (macOS trackpad pinch sends this)
                                    val zoomFactor = 1f - delta.y * 0.02f
                                    val newScale = (scale.value * zoomFactor).coerceIn(0.5f, 5f)
                                    scale.snapTo(newScale)
                                    if (newScale <= 1f) {
                                        offsetX.snapTo(0f)
                                        offsetY.snapTo(0f)
                                    }
                                } else if (scale.value > 1f) {
                                    // Trackpad two-finger scroll = pan (when zoomed in)
                                    offsetX.snapTo(offsetX.value + delta.x * 30f)
                                    offsetY.snapTo(offsetY.value + delta.y * 30f)
                                }
                            } catch (e: CancellationException) {
                                throw e
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
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offsetX.value
                    translationY = offsetY.value
                },
            contentScale = ContentScale.Fit
        )
    }
}
