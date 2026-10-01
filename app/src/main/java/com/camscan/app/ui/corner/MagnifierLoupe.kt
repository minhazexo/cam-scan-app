package com.camscan.app.ui.corner

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.camscan.app.ui.theme.OverlayGreen

@Composable
fun MagnifierLoupe(
    bitmap: Bitmap,
    normalizedPoint: PointF,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(100.dp)
            .clip(CircleShape)
            .background(Color.Black)
            .border(2.dp, OverlayGreen, CircleShape)
    ) {
        val imageBitmap = bitmap.asImageBitmap()

        Canvas(modifier = Modifier.size(100.dp)) {
            val srcX = (normalizedPoint.x * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val srcY = (normalizedPoint.y * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)

            val cropRadius = 30
            val srcLeft = (srcX - cropRadius).coerceIn(0, bitmap.width)
            val srcTop = (srcY - cropRadius).coerceIn(0, bitmap.height)
            val srcRight = (srcX + cropRadius).coerceIn(0, bitmap.width)
            val srcBottom = (srcY + cropRadius).coerceIn(0, bitmap.height)

            drawImage(
                image = imageBitmap,
                srcOffset = IntOffset(srcLeft, srcTop),
                srcSize = IntSize((srcRight - srcLeft).coerceAtLeast(1), (srcBottom - srcTop).coerceAtLeast(1)),
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )

            // Crosshair
            drawLine(
                color = OverlayGreen,
                start = androidx.compose.ui.geometry.Offset(size.width / 2f, 0f),
                end = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = OverlayGreen,
                start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
                end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}
