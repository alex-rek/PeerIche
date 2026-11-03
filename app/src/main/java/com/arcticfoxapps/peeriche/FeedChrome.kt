package com.arcticfoxapps.peeriche

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable internal fun FeedPanel(visible: Boolean, top: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible,
        enter = slideInVertically(tween(240)) { if (top) -it else it } + expandVertically(tween(240), expandFrom = if (top) Alignment.Top else Alignment.Bottom),
        exit = slideOutVertically(tween(240)) { if (top) -it else it } + shrinkVertically(tween(240), shrinkTowards = if (top) Alignment.Top else Alignment.Bottom)) {
        content()
    }
}

@Composable internal fun rememberFeedScrollConnection(enabled: Boolean, state: LazyGridState,
    onVisibility: (Boolean) -> Unit): NestedScrollConnection {
    val callback by rememberUpdatedState(onVisibility)
    val threshold = with(LocalDensity.current) { 20.dp.toPx() }
    return remember(enabled, state, threshold) {
        object : NestedScrollConnection {
            private var directionDistance = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!enabled || source != NestedScrollSource.UserInput) return Offset.Zero
                val delta = consumed.y
                if (!state.canScrollBackward && available.y >= 0f) {
                    directionDistance = 0f
                    callback(true)
                } else if (delta != 0f) {
                    if (delta * directionDistance < 0) directionDistance = 0f
                    directionDistance += delta
                    if (directionDistance <= -threshold) { callback(false); directionDistance = 0f }
                    else if (directionDistance >= threshold) { callback(true); directionDistance = 0f }
                }
                // Observe only: the grid and pull-to-refresh consume the gesture themselves.
                return Offset.Zero
            }
        }
    }
}
