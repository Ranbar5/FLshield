package com.example.applocker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

@Composable
fun PatternLockView(
    modifier: Modifier = Modifier,
    expectedPattern: String,
    onSuccess: () -> Unit,
    onError: () -> Unit = {}
) {
    var connectedDots by remember { mutableStateOf<List<Int>>(emptyList()) }
    var currentTouchPosition by remember { mutableStateOf<Offset?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(isError) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (isError) {
                            isError = false
                            connectedDots = emptyList()
                        }
                        val dot = getDotAtOffset(offset, size.width.toFloat(), size.height.toFloat(), 40.dp.toPx())
                        if (dot != null) {
                            connectedDots = listOf(dot)
                        }
                        currentTouchPosition = offset
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val newPos = change.position
                        currentTouchPosition = newPos
                        val dot = getDotAtOffset(newPos, size.width.toFloat(), size.height.toFloat(), 40.dp.toPx())
                        if (dot != null && !connectedDots.contains(dot)) {
                            connectedDots = connectedDots + dot
                        }
                    },
                    onDragEnd = {
                        val patternString = connectedDots.joinToString("") { it.toString() }
                        if (patternString.isNotEmpty()) {
                            if (patternString == expectedPattern) {
                                onSuccess()
                            } else {
                                isError = true
                                onError()
                                scope.launch {
                                    delay(1000)
                                    isError = false
                                    connectedDots = emptyList()
                                }
                            }
                        }
                        currentTouchPosition = null
                    },
                    onDragCancel = {
                        connectedDots = emptyList()
                        currentTouchPosition = null
                        isError = false
                    }
                )
            }
    ) {
        val width = size.width
        val height = size.height

        // Colors
        val normalDotColor = Color(0xFF475569)
        val activeColor = if (isError) Color(0xFFEF4444) else Color(0xFF6366F1)
        val activeDotBgColor = activeColor.copy(alpha = 0.2f)

        // Calculate dot center positions (centered in 3x3 grid)
        val dotCenters = List(9) { i ->
            Offset(
                x = (i % 3 + 1) * (width / 4f),
                y = (i / 3 + 1) * (height / 4f)
            )
        }

        // Draw connections
        if (connectedDots.size > 1) {
            for (i in 0 until connectedDots.size - 1) {
                val start = dotCenters[connectedDots[i]]
                val end = dotCenters[connectedDots[i + 1]]
                drawLine(
                    color = activeColor,
                    start = start,
                    end = end,
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        // Draw line to current touch
        if (connectedDots.isNotEmpty() && currentTouchPosition != null && !isError) {
            val lastDotPos = dotCenters[connectedDots.last()]
            drawLine(
                color = activeColor,
                start = lastDotPos,
                end = currentTouchPosition!!,
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Draw the 9 dots
        for (i in 0..8) {
            val center = dotCenters[i]
            val isConnected = connectedDots.contains(i)

            if (isConnected) {
                // Outer ring
                drawCircle(
                    color = activeDotBgColor,
                    radius = 28.dp.toPx(),
                    center = center
                )
                // Outer ring stroke
                drawCircle(
                    color = activeColor,
                    radius = 28.dp.toPx(),
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
                // Inner dot
                drawCircle(
                    color = activeColor,
                    radius = 8.dp.toPx(),
                    center = center
                )
            } else {
                // Normal small dot
                drawCircle(
                    color = normalDotColor,
                    radius = 6.dp.toPx(),
                    center = center
                )
            }
        }
    }
}

private fun getDotAtOffset(offset: Offset, width: Float, height: Float, thresholdPx: Float): Int? {
    for (i in 0..8) {
        val centerX = (i % 3 + 1) * (width / 4f)
        val centerY = (i / 3 + 1) * (height / 4f)
        val dx = offset.x - centerX
        val dy = offset.y - centerY
        if (sqrt(dx * dx + dy * dy) < thresholdPx) {
            return i
        }
    }
    return null
}
