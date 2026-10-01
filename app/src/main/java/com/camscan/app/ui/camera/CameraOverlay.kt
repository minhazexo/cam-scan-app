package com.camscan.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.camscan.app.domain.model.CornerPoints
import com.camscan.app.ui.theme.OverlayGreen

@Composable
fun CameraOverlay(
    corners: CornerPoints,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        val scaled = corners.scale(width, height)

        val path = Path().apply {
            moveTo(scaled.topLeft.x, scaled.topLeft.y)
            lineTo(scaled.topRight.x, scaled.topRight.y)
            lineTo(scaled.bottomRight.x, scaled.bottomRight.y)
            lineTo(scaled.bottomLeft.x, scaled.bottomLeft.y)
            close()
        }

        // Draw translucent green quad fill
        drawPath(
            path = path,
            color = OverlayGreen.copy(alpha = 0.25f)
        )

        // Draw bright green stroke border
        drawPath(
            path = path,
            color = OverlayGreen,
            style = Stroke(width = 3.dp.toPx())
        )

        // Draw 4 corner pins
        val cornerList = listOf(scaled.topLeft, scaled.topRight, scaled.bottomRight, scaled.bottomLeft)
        cornerList.forEach { pt ->
            drawCircle(
                color = Color.White,
                radius = 8.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(pt.x, pt.y)
            )
            drawCircle(
                color = OverlayGreen,
                radius = 6.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(pt.x, pt.y)
            )
        }
    }
}
