package com.lifelink.app.core.location

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Offline-safe approximate coordinate picker. It deliberately renders natively,
 * so it does not depend on WebView, a JavaScript CDN, map tiles, or an API key.
 */
@Composable
fun NativeLocationPicker(
    latitude: Double,
    longitude: Double,
    onLocationSelected: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var fraction by remember(latitude, longitude) { mutableStateOf(Offset(.5f, .5f)) }
    fun select(position: Offset, width: Float, height: Float) {
        val x = (position.x / max(width, 1f)).coerceIn(0f, 1f)
        val y = (position.y / max(height, 1f)).coerceIn(0f, 1f)
        fraction = Offset(x, y)
        val nextLatitude = (latitude + (.5f - y) * .10f).coerceIn(-90.0, 90.0)
        val nextLongitude = (longitude + (x - .5f) * .14f).coerceIn(-180.0, 180.0)
        onLocationSelected(nextLatitude, nextLongitude)
    }
    Box(
        modifier = modifier.fillMaxWidth().height(260.dp).background(Color(0xFFE7EFE9)),
        contentAlignment = Alignment.TopCenter
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
                .pointerInput(latitude, longitude) {
                    detectTapGestures { select(it, size.width.toFloat(), size.height.toFloat()) }
                }
                .pointerInput(latitude, longitude) {
                    detectDragGestures { change, _ ->
                        select(change.position, size.width.toFloat(), size.height.toFloat())
                    }
                }
        ) {
            val grid = 42.dp.toPx()
            var x = 0f
            while (x <= size.width) {
                drawLine(Color(0xFFC6D8CC), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += grid
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(Color(0xFFC6D8CC), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += grid
            }
            drawLine(Color.White.copy(alpha = .8f), Offset(0f, size.height * .36f), Offset(size.width, size.height * .62f), strokeWidth = 3.dp.toPx())
            drawLine(Color.White.copy(alpha = .8f), Offset(0f, size.height * .72f), Offset(size.width, size.height * .48f), strokeWidth = 3.dp.toPx())
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Color(0xFF567866), radius = 6.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
            val pin = Offset(fraction.x * size.width, fraction.y * size.height)
            drawCircle(Color.White, radius = 14.dp.toPx(), center = pin)
            drawCircle(Color(0xFFB71942), radius = 11.dp.toPx(), center = pin)
            drawCircle(Color.White, radius = 3.dp.toPx(), center = pin)
        }
        Text(
            "Tap or drag the pin to choose an approximate location",
            modifier = Modifier.padding(8.dp),
            color = Color(0xFF24352B),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
