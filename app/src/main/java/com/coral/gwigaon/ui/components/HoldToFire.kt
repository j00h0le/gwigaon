package com.coral.gwigaon.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Press and hold for [holdMs] to fire once: [progress] fills 0 → 1 while held and snaps back to 0 on release, so a
 * brush of the screen never fires. [onFire] is a State so the latest callback runs without restarting the gesture.
 */
fun Modifier.holdToFire(
    progress: Animatable<Float, AnimationVector1D>,
    holdMs: Long,
    scope: CoroutineScope,
    onFire: State<() -> Unit>,
): Modifier = pointerInput(holdMs) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val fill = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(holdMs.toInt(), easing = LinearEasing))
        }
        var released = false
        withTimeoutOrNull(holdMs) {
            waitForUpOrCancellation()
            released = true
        }
        if (!released) {
            onFire.value()
            waitForUpOrCancellation() // one hold fires once, however long it lasts
        }
        fill.cancel()
        scope.launch { progress.snapTo(0f) }
    }
}
