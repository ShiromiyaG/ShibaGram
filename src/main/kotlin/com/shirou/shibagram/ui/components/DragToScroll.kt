package com.shirou.shibagram.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Lets the mouse drag a lazy list/grid like a touch screen.
 * Scrolls synchronously via dispatchRawDelta (the old code launched one coroutine per drag event).
 */
@Composable
fun Modifier.dragToScroll(state: ScrollableState, horizontal: Boolean): Modifier = draggable(
    state = rememberDraggableState { delta -> state.dispatchRawDelta(-delta) },
    orientation = if (horizontal) Orientation.Horizontal else Orientation.Vertical
)
