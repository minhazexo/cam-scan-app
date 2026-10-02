package com.camscan.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camscan.app.domain.model.DetectionOverlayState
import com.camscan.app.domain.model.LiveDetection
import com.camscan.app.ui.theme.OverlayGreen

private val OverlayAmber = Color(0xFFFFB300)

/**
 * Live document overlay.
 *
 * The quad is drawn ONLY when a real candidate exists ([LiveDetection.hasQuad]).
 * For [DetectionOverlayState.CONFIRMATION_REQUIRED] the outline is dashed and
 * amber, signalling "found but confirm". When nothing is detected a subtle
 * "Move closer to document" hint is shown instead of a fabricated rectangle.
 */
@Composable
fun CameraOverlay(
    detection: LiveDetection,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val corners = detection.corners
        if (corners != null && detection.state != DetectionOverlayState.NOT_DETECTED) {
            val confirmed = detection.state == DetectionOverlayState.DETECTED
            val accent = if (confirmed) OverlayGreen else OverlayAmber
            Canvas(modifier = Modifier.fillMaxSize()) {
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

                // Translucent fill.
                drawPath(
                    path = path,
                    color = accent.copy(alpha = 0.20f)
                )

                // Solid for a confident detection, dashed for confirmation.
                val stroke = if (confirmed) {
                    Stroke(width = 3.dp.toPx())
                } else {
                    Stroke(
                        width = 3.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f), 0f)
                    )
                }
                drawPath(path = path, color = accent, style = stroke)

                // Corner pins only once the quad is confident.
                if (confirmed) {
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
        }

        if (detection.state == DetectionOverlayState.NOT_DETECTED) {
            Text(
                text = "Move closer to document",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .offset(y = 140.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}
