package com.camscan.app.ui.corner

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camscan.app.ui.theme.OverlayGreen
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CornerAdjustScreen(
    viewModel: CornerAdjustViewModel,
    documentId: String?,
    imagePath: String,
    onNavigateBack: () -> Unit,
    onComplete: (String) -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(imagePath) {
        viewModel.loadImage(context, imagePath)
    }

    val bitmap by viewModel.originalBitmap.collectAsState()
    val corners by viewModel.corners.collectAsState()
    val activeDragPoint by viewModel.activeDragPoint.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()

    var activeCornerIndex by remember { mutableIntStateOf(-1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adjust Corners") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.resetCorners() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Corners")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.applyPerspectiveWarpAndSave(context, documentId, imagePath, onComplete)
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Default.Check, contentDescription = "Apply Crop")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val currentBitmap = bitmap
            if (currentBitmap != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val normX = offset.x / size.width
                                        val normY = offset.y / size.height
                                        val scaled = corners.scale(size.width.toFloat(), size.height.toFloat())

                                        val pts = listOf(scaled.topLeft, scaled.topRight, scaled.bottomRight, scaled.bottomLeft)
                                        var closestIdx = -1
                                        var minDist = Float.MAX_VALUE

                                        pts.forEachIndexed { idx, pt ->
                                            val dist = hypot(offset.x - pt.x, offset.y - pt.y)
                                            if (dist < minDist && dist < 120f) {
                                                minDist = dist
                                                closestIdx = idx
                                            }
                                        }
                                        activeCornerIndex = closestIdx
                                    },
                                    onDrag = { change, _ ->
                                        if (activeCornerIndex != -1) {
                                            change.consume()
                                            val normX = change.position.x / size.width
                                            val normY = change.position.y / size.height
                                            viewModel.updateCorner(activeCornerIndex, PointF(normX, normY))
                                        }
                                    },
                                    onDragEnd = {
                                        activeCornerIndex = -1
                                        viewModel.onDragEnd()
                                    }
                                )
                            }
                    ) {
                        val canvasW = size.width
                        val canvasH = size.height

                        // Draw image
                        drawImage(
                            image = currentBitmap.asImageBitmap(),
                            dstSize = androidx.compose.ui.unit.IntSize(canvasW.toInt(), canvasH.toInt())
                        )

                        // Draw Quad polygon
                        val scaled = corners.scale(canvasW, canvasH)
                        val path = Path().apply {
                            moveTo(scaled.topLeft.x, scaled.topLeft.y)
                            lineTo(scaled.topRight.x, scaled.topRight.y)
                            lineTo(scaled.bottomRight.x, scaled.bottomRight.y)
                            lineTo(scaled.bottomLeft.x, scaled.bottomLeft.y)
                            close()
                        }

                        drawPath(
                            path = path,
                            color = OverlayGreen.copy(alpha = 0.3f)
                        )
                        drawPath(
                            path = path,
                            color = OverlayGreen,
                            style = Stroke(width = 3.dp.toPx())
                        )

                        // Draw Corner handles
                        val pts = listOf(scaled.topLeft, scaled.topRight, scaled.bottomRight, scaled.bottomLeft)
                        pts.forEachIndexed { idx, pt ->
                            val isSelected = idx == activeCornerIndex
                            drawCircle(
                                color = if (isSelected) Color.Yellow else Color.White,
                                radius = if (isSelected) 12.dp.toPx() else 8.dp.toPx(),
                                center = androidx.compose.ui.geometry.Offset(pt.x, pt.y)
                            )
                            drawCircle(
                                color = OverlayGreen,
                                radius = if (isSelected) 8.dp.toPx() else 5.dp.toPx(),
                                center = androidx.compose.ui.geometry.Offset(pt.x, pt.y)
                            )
                        }
                    }

                    // Magnifier Loupe
                    activeDragPoint?.let { pt ->
                        MagnifierLoupe(
                            bitmap = currentBitmap,
                            normalizedPoint = pt,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(16.dp)
                        )
                    }
                }
            } else {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
