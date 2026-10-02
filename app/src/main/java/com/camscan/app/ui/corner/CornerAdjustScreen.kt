package com.camscan.app.ui.corner

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camscan.app.domain.model.DetectionConfidence
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
    val detection by viewModel.detection.collectAsState()
    val isDetecting by viewModel.isDetecting.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val showDebug by viewModel.showDebug.collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()

    var activeCornerIndex by remember { mutableIntStateOf(-1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adjust Corners") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.toggleDebug() },
                        enabled = bitmap != null
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = "Debug stages")
                    }
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
                    Icon(Icons.Default.Check, contentDescription = "Confirm crop")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black)
        ) {
            // Confidence banner (Step 17): HIGH auto, MEDIUM confirm, LOW manual.
            DetectionBanner(
                detection = detection,
                isDetecting = isDetecting
            )

            errorMessage?.let { msg ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = msg,
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (showDebug && debugInfo != null) {
                    DebugStagesView(debugInfo = debugInfo)
                } else {
                    val currentBitmap = bitmap
                    if (currentBitmap != null) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectDragGestures(
                                            onDragStart = { offset ->
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

                            if (isDetecting) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 20.dp, vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("Detecting document…", color = Color.White, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    } else {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Action bar: AUTO / RESET / ROTATE / CANCEL / CONFIRM
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CornerActionButton(
                    label = "AUTO",
                    icon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Color.White) },
                    onClick = { viewModel.autoDetect() },
                    enabled = !isDetecting && !isProcessing && bitmap != null
                )
                CornerActionButton(
                    label = "RESET",
                    icon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White) },
                    onClick = { viewModel.resetCorners() },
                    enabled = !isProcessing
                )
                CornerActionButton(
                    label = "ROTATE",
                    icon = { Icon(Icons.Default.RotateRight, contentDescription = null, tint = Color.White) },
                    onClick = { viewModel.rotateImage90() },
                    enabled = !isDetecting && !isProcessing && bitmap != null
                )
                CornerActionButton(
                    label = "CANCEL",
                    icon = { Icon(Icons.Default.Close, contentDescription = null, tint = Color.White) },
                    onClick = onNavigateBack,
                    enabled = !isProcessing
                )
            }
        }
    }
}

@Composable
private fun CornerActionButton(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            icon()
        }
        Text(
            text = label,
            color = if (enabled) Color.White else Color.Gray,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun DetectionBanner(
    detection: com.camscan.app.domain.model.DetectionResult?,
    isDetecting: Boolean
) {
    if (isDetecting) return
    val result = detection ?: return
    val (text, color) = when (result.confidence) {
        DetectionConfidence.HIGH ->
            "Auto-detected document (score ${"%.2f".format(result.score)}). Drag corners to refine, then confirm." to Color(0xFF1B5E20)
        DetectionConfidence.MEDIUM ->
            "Uncertain detection — please confirm the corners before scanning." to Color(0xFFE65100)
        DetectionConfidence.LOW ->
            "Auto detection failed — drag all 4 corners onto the page manually." to Color(0xFFB71C1C)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun DebugStagesView(
    debugInfo: com.camscan.app.domain.processor.DocumentProcessor.ScanDebugInfo?
) {
    if (debugInfo == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No debug data yet.", color = Color.White)
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val det = debugInfo.detection
        if (det != null) {
            Text(
                text = "confidence=${det.confidence} score=${"%.2f".format(det.score)} " +
                    "candidates=${det.allCandidates.size} reason=${det.reason}",
                color = Color.White,
                fontSize = 12.sp
            )
        }
        debugInfo.stats?.let { stats ->
            DebugStageCard(title = "0. Detector diagnostics") {
                DetectorDiagnostics(stats = stats)
            }
        }
        DebugStageCard(title = "1. Original → see editor view") { }
        debugInfo.edgePreview?.let {
            DebugStageCard(title = "2. Edge image (Canny)") { StageImage(bitmap = it) }
        }
        debugInfo.candidatesPreview?.let {
            DebugStageCard(title = "3-4. Contours / candidate quads (red) + contenders") { StageImage(bitmap = it) }
        }
        debugInfo.selectedPreview?.let {
            DebugStageCard(title = "5. Selected quadrilateral (TL/TR/BR/BL)") { StageImage(bitmap = it) }
        }
        debugInfo.perspectivePreview?.let {
            DebugStageCard(title = "6. Perspective result (document-only)") { StageImage(bitmap = it) }
        }
        debugInfo.deskewedPreview?.let {
            DebugStageCard(title = "7. Deskew result") { StageImage(bitmap = it) }
        }
        debugInfo.dewarpedPreview?.let {
            DebugStageCard(title = "8. Dewarp result (unchanged when page is flat)") { StageImage(bitmap = it) }
        }
        debugInfo.enhancedPreview?.let {
            DebugStageCard(title = "9. Enhanced result") { StageImage(bitmap = it) }
        }
        Text(
            text = "10. Final A4 is produced on CONFIRM from the exact quad above (2480×3508 portrait).",
            color = Color.LightGray,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun DetectorDiagnostics(stats: com.camscan.app.domain.processor.DocumentDetector.DetectionStats) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        DiagRow("OpenCV", if (stats.openCvUsed) "yes (native capture path)" else "no (Kotlin fallback)")
        DiagRow("Input", "${stats.inputW}×${stats.inputH}")
        DiagRow("Sample", "${stats.sampleW}×${stats.sampleH}")
        DiagRow("Edge pixels", stats.edgePixels.toString())
        DiagRow("Canny / adaptive", "${stats.cannyPasses} / ${stats.adaptivePasses}")
        DiagRow("Components", "${stats.components} (big ${stats.bigComponents})")
        DiagRow("approx4 / valid", "${stats.approxFour} / ${stats.validatedQuads}")
        DiagRow("Candidates", "${stats.candidateCount} → ${stats.uniqueCandidateCount} unique")
        DiagRow("Hough", "${stats.houghLines} lines / ${stats.houghQuads} quads")
        DiagRow(
            "Scores",
            "sel=${"%.3f".format(stats.selectedScore)} " +
                "2nd=${"%.3f".format(stats.secondScore)} " +
                "cal=${"%.3f".format(stats.calibratedScore)}"
        )
        DiagRow("Confidence", "${stats.confidence} (score ${"%.3f".format(stats.selectedScore)})")
        DiagRow("edge / polarity", "${"%.2f".format(stats.edgeSupport)} / ${"%.2f".format(stats.polarity)}")
        DiagRow("contrast / aspect", "${"%.2f".format(stats.contrast)} / ${"%.2f".format(stats.aspect)}")
        DiagRow("rectangularity", "%.2f".format(stats.rectangularity))
        if (stats.selectedCorners.isNotEmpty()) {
            DiagRow(
                "Selected corners",
                stats.selectedCorners.joinToString("  ") {
                    "(${"%.2f".format(it.x)},${"%.2f".format(it.y)})"
                }
            )
        }
        if (stats.houghLineDesc.isNotEmpty()) {
            DiagRow("Hough lines", stats.houghLineDesc.take(8).joinToString("; "))
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = Color.LightGray,
            fontSize = 11.sp,
            modifier = Modifier.width(130.dp)
        )
        Text(text = value, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun DebugStageCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(text = title, color = Color.White, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun StageImage(bitmap: android.graphics.Bitmap) {
    androidx.compose.foundation.Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        contentScale = ContentScale.Fit
    )
}
