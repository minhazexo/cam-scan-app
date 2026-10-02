package com.camscan.app.domain.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.camscan.app.domain.model.CornerPoints
import com.camscan.app.domain.model.DetectionConfidence
import com.camscan.app.domain.model.DetectionResult
import com.camscan.app.domain.model.FilterMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Raised when automatic detection cannot produce a trustworthy quad.
 * Callers MUST route to the manual 4-corner editor; they must NOT fall
 * back to the full photograph (Absolute Rule #3).
 */
class NeedsManualCornersException(
    val detection: DetectionResult,
    message: String = detection.reason.ifBlank { "Document detection failed; manual corners required." }
) : Exception(message)

/**
 * Output of the strict scan pipeline.
 *
 * [documentOnly] is the rectified intermediate (pre-A4) for debug display.
 * [a4] is the final page. [cornersUsed] are the normalized corners the
 * output was built from; [autoDetected] tells whether they came from the
 * detector (true) or the user (false).
 */
data class ScanResult(
    val a4: Bitmap,
    val documentOnly: Bitmap,
    val cornersUsed: CornerPoints,
    val autoDetected: Boolean,
    val confidence: DetectionConfidence,
    val detectionScore: Float,
    val needsConfirmation: Boolean,
    val deskewAngleApplied: Float,
    val dewarped: Boolean
)

object DocumentProcessor {

    // Standard A4 at ~300 DPI.
    const val A4_WIDTH_PX = 2480
    const val A4_HEIGHT_PX = 3508

    /** Upper bound on enlargement when fitting an extraction onto A4. */
    const val MAX_UPSCALE = 2.5f

    /**
     * Enlargement cap applied when the extraction is already reasonably large
     * (>= [MIN_FILL_SOURCE_PX] on its short side). A normal phone photo is
     * then scaled up to fill the sheet, matching how scanners output a full
     * page, instead of being letterboxed into the middle of the A4 canvas.
     */
    const val MAX_UPSCALE_FILL = 6f

    /** Short-side size at or above which filling the sheet is safe. */
    const val MIN_FILL_SOURCE_PX = 400

    // ------------------------------------------------------------------
    // Strict pipeline (the only path used for final output)
    // ------------------------------------------------------------------

    /**
     * INPUT -> DETECTION -> 4 CORNERS -> VALIDATION -> PERSPECTIVE ->
     * DOCUMENT-ONLY -> DESKEW -> DEWARP? -> ENHANCE -> A4 -> FINAL CHECK.
     *
     * @param corners caller-supplied quad (manual editor). Null = auto-detect.
     * @param allowUnconfirmed unattended callers (batch, gallery, PDF) pass
     *   true to *also* accept a MEDIUM quad. Interactive confirmation flows
     *   leave it false so an unconfirmed quad is never auto-scanned.
     * @throws NeedsManualCornersException when auto detection is not
     *   HIGH (or MEDIUM when [allowUnconfirmed] is false), or the auto result
     *   fails validation / background check. Never returns a full-photo scan.
     */
    fun processImageStrict(
        bitmap: Bitmap,
        filterMode: FilterMode = FilterMode.AUTO,
        corners: CornerPoints? = null,
        allowUnconfirmed: Boolean = false
    ): ScanResult {
        val fromUser = corners != null
        val detection: DetectionResult?
        val quad: CornerPoints

        if (fromUser) {
            detection = null
            quad = corners!!
            val v = QuadValidator.validate(
                if (quad.isNormalized()) quad.scale(bitmap.width.toFloat(), bitmap.height.toFloat()) else quad,
                bitmap.width.toFloat(), bitmap.height.toFloat()
            )
            if (!v.valid) {
                throw NeedsManualCornersException(
                    DetectionResult(null, DetectionConfidence.LOW, 0f, emptyList(), "Invalid manual quad: ${v.reason}"),
                    "Invalid corners (${v.reason}); adjust the four points."
                )
            }
            // Manual quads are trusted even if they span the frame: the user
            // explicitly selected them (only valid document source #2).
        } else {
            val result = DocumentDetector.detectDocument(bitmap, fast = false)
            detection = result
            if (result.corners == null) {
                throw NeedsManualCornersException(result)
            }
            // An uncertain quad is never a final scan. Unattended callers get
            // the exception so the page stays pending; interactive callers can
            // re-run with allowUnconfirmed once the user confirms the corners.
            if (result.blocksAutoProcessing && !allowUnconfirmed) {
                throw NeedsManualCornersException(
                    result,
                    if (result.needsConfirmation) {
                        "Detected corners are uncertain. Confirm or adjust them to finish this page."
                    } else {
                        result.reason.ifBlank { "Document detection failed; manual corners required." }
                    }
                )
            }
            quad = result.corners
        }

        // HARD EXTRACTION: from here on only the rectified bitmap exists.
        // The original photograph is never referenced again in this function.
        val rectified = PerspectiveWarper.warpToRectangle(bitmap, quad)

        try {
            // FINAL BACKGROUND CHECK on the rectified document (auto path).
            // A user-confirmed quad is trusted; an auto quad that still shows
            // surrounding scene is rejected back to manual correction.
            if (!fromUser && !passesBackgroundCheck(rectified)) {
                throw NeedsManualCornersException(
                    detection!!,
                    "Output still contains background; confirm or adjust corners."
                )
            }

            val skewEstimate = DeskewHelper.estimateSkewAngle(rectified)
            val deskewed = DeskewHelper.deskew(rectified)
            val deskewApplied = deskewed !== rectified

            val dewarped = DewarpHelper.maybeDewarp(deskewed)
            val didDewarp = dewarped !== deskewed

            val enhanced = DocumentEnhancer.enhance(dewarped, filterMode)
            val a4 = formatToA4Canvas(enhanced)

            // Keep a small document-only copy for debug display; recycle big
            // intermediates aggressively to bound memory.
            val docPreview = Bitmap.createScaledBitmap(
                enhanced,
                480.coerceAtMost(enhanced.width),
                (480f * enhanced.height / enhanced.width).toInt().coerceAtLeast(1).coerceAtMost(640),
                true
            )

            if (enhanced !== dewarped && enhanced !== docPreview) enhanced.recycle()
            if (dewarped !== deskewed && dewarped !== docPreview) dewarped.recycle()
            if (deskewed !== rectified && deskewed !== docPreview) deskewed.recycle()
            rectified.recycle()

            val confidence = detection?.confidence ?: DetectionConfidence.HIGH
            return ScanResult(
                a4 = a4,
                documentOnly = docPreview,
                cornersUsed = quad,
                autoDetected = !fromUser,
                confidence = confidence,
                detectionScore = detection?.score ?: 1f,
                needsConfirmation = detection?.needsConfirmation ?: false,
                deskewAngleApplied = if (deskewApplied && !skewEstimate.isNaN()) skewEstimate else 0f,
                dewarped = didDewarp
            )
        } catch (e: NeedsManualCornersException) {
            rectified.recycle()
            throw e
        } catch (e: InvalidQuadException) {
            rectified.recycle()
            throw NeedsManualCornersException(
                detection ?: DetectionResult(null, DetectionConfidence.LOW, 0f, emptyList(), e.message ?: "invalid quad"),
                e.message ?: "Invalid quad."
            )
        } catch (e: Exception) {
            rectified.recycle()
            throw e
        }
    }

    /**
     * Legacy entry point. Behaves like [processImageStrict] but returns only
     * the A4 bitmap. Throws [NeedsManualCornersException] instead of ever
     * returning a full-photo result. All callers must catch it and route to
     * the manual corner editor.
     */
    fun processImage(
        bitmap: Bitmap,
        filterMode: FilterMode = FilterMode.AUTO,
        corners: CornerPoints? = null
    ): Bitmap {
        val result = processImageStrict(bitmap, filterMode, corners)
        result.documentOnly.recycle()
        return result.a4
    }

    /**
     * Strict pipeline for unattended callers (batch / gallery / PDF).
     * Returns a finished scan ONLY for a HIGH-confidence detection.
     * MEDIUM and LOW both return null so the caller keeps the original
     * pending user confirmation / manual corner correction.
     */
    fun processImageAutoOrNull(
        bitmap: Bitmap,
        filterMode: FilterMode = FilterMode.AUTO
    ): ScanResult? {
        return try {
            processImageStrict(bitmap, filterMode, null, allowUnconfirmed = false)
        } catch (e: NeedsManualCornersException) {
            null
        }
    }

    // ------------------------------------------------------------------
    // A4 composition (document-only input)
    // ------------------------------------------------------------------

    /**
     * Places ONLY the rectified document onto a standardized A4 canvas,
     * proportionally, without stretch/squeeze. Never pass the original
     * photograph here.
     *
     * Implemented with explicit bilinear resampling (no Canvas) so output is
     * bit-exact on every device and in unit tests.
     */
    fun formatToA4Canvas(sourceBitmap: Bitmap): Bitmap {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height
        require(srcW > 0 && srcH > 0) { "empty document image" }

        // Choose orientation from the document itself.
        val portrait = srcH >= srcW
        val canvasW = if (portrait) A4_WIDTH_PX else A4_HEIGHT_PX
        val canvasH = if (portrait) A4_HEIGHT_PX else A4_WIDTH_PX

        val maxW = canvasW * 0.94f
        val maxH = canvasH * 0.94f
        // Enlargement cap. A TINY extraction blown up to full A4 is pure
        // interpolation noise, so it stays capped; a normal-size photo is
        // allowed to fill the sheet (scanner-like output) instead of being
        // letterboxed with wide white margins.
        val cap = if (min(srcW, srcH) >= MIN_FILL_SOURCE_PX) MAX_UPSCALE_FILL else MAX_UPSCALE
        val scale = min(
            min(maxW / srcW.toFloat(), maxH / srcH.toFloat()),
            cap
        )
        val scaledW = (srcW * scale).toInt().coerceAtLeast(1)
        val scaledH = (srcH * scale).toInt().coerceAtLeast(1)
        val left = (canvasW - scaledW) / 2
        val top = (canvasH - scaledH) / 2

        val srcPixels = IntArray(srcW * srcH)
        sourceBitmap.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
        val out = IntArray(canvasW * canvasH) { Color.WHITE }

        // Precompute source column maps for speed.
        val colX0 = IntArray(scaledW)
        val colFx = FloatArray(scaledW)
        for (dx in 0 until scaledW) {
            val sx = dx.toFloat() * srcW / scaledW
            val x0 = sx.toInt().coerceIn(0, srcW - 2)
            colX0[dx] = x0
            colFx[dx] = (sx - x0).coerceIn(0f, 1f)
        }

        for (dy in 0 until scaledH) {
            val sy = dy.toFloat() * srcH / scaledH
            val y0 = sy.toInt().coerceIn(0, srcH - 2)
            val fy = (sy - y0).coerceIn(0f, 1f)
            val rowTop = y0 * srcW
            val rowBot = (y0 + 1) * srcW
            val outRow = (top + dy) * canvasW + left
            for (dx in 0 until scaledW) {
                val x0 = colX0[dx]
                val fx = colFx[dx]
                val c00 = srcPixels[rowTop + x0]
                val c10 = srcPixels[rowTop + x0 + 1]
                val c01 = srcPixels[rowBot + x0]
                val c11 = srcPixels[rowBot + x0 + 1]
                out[outRow + dx] = bilerp(c00, c10, c01, c11, fx, fy)
            }
        }

        val a4Bitmap = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        a4Bitmap.setPixels(out, 0, canvasW, 0, 0, canvasW, canvasH)
        return a4Bitmap
    }

    private fun bilerp(c00: Int, c10: Int, c01: Int, c11: Int, fx: Float, fy: Float): Int {
        fun ch(c: Int, shift: Int): Float = ((c shr shift) and 0xFF).toFloat()
        val invFx = 1f - fx
        val invFy = 1f - fy
        val a = (ch(c00, 24) * invFx + ch(c10, 24) * fx) * invFy +
            (ch(c01, 24) * invFx + ch(c11, 24) * fx) * fy
        val r = (ch(c00, 16) * invFx + ch(c10, 16) * fx) * invFy +
            (ch(c01, 16) * invFx + ch(c11, 16) * fx) * fy
        val g = (ch(c00, 8) * invFx + ch(c10, 8) * fx) * invFy +
            (ch(c01, 8) * invFx + ch(c11, 8) * fx) * fy
        val b = (ch(c00, 0) * invFx + ch(c10, 0) * fx) * invFy +
            (ch(c01, 0) * invFx + ch(c11, 0) * fx) * fy
        // Source photos are opaque; force opaque output so no platform
        // alpha-premultiplication quirks can darken the page.
        @Suppress("UNUSED_VARIABLE")
        val alpha = a
        return (255 shl 24) or
            (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or
            b.toInt().coerceIn(0, 255)
    }

    // ------------------------------------------------------------------
    // Final background check (Step 18)
    // ------------------------------------------------------------------

    /**
     * Inspects the borders of the RECTIFIED image for leftover external
     * background (table/wall/floor texture creeping in through a bad quad).
     * Returns false when the scan looks invalid and must go back to corner
     * correction. Legitimate paper margins (flat white) pass.
     */
    fun passesBackgroundCheck(rectified: Bitmap): Boolean {
        try {
            val w = rectified.width
            val h = rectified.height
            if (w < 60 || h < 60) return false
            val pixels = IntArray(w * h)
            rectified.getPixels(pixels, 0, w, 0, 0, w, h)

            data class Strip(val mean: Double, val std: Double, val sat: Double, val edgeDensity: Double)

            fun stripStats(x0: Int, y0: Int, x1: Int, y1: Int): Strip {
                var n = 0L
                var sum = 0.0
                var sumSq = 0.0
                var satSum = 0.0
                var edgeCount = 0L
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        val c = pixels[y * w + x]
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val lum = 0.299 * r + 0.587 * g + 0.114 * b
                        sum += lum
                        sumSq += lum * lum
                        satSum += (maxOf(r, g, b) - minOf(r, g, b)).toDouble()
                        // Local contrast: horizontal gradient magnitude.
                        if (x + 2 < x1) {
                            val c2 = pixels[y * w + x + 2]
                            val lum2 = 0.299 * ((c2 shr 16) and 0xFF) +
                                0.587 * ((c2 shr 8) and 0xFF) + 0.114 * (c2 and 0xFF)
                            if (abs(lum - lum2) > 22) edgeCount++
                        }
                        n++
                        x += 3
                    }
                    y += 3
                }
                if (n == 0L) return Strip(255.0, 0.0, 0.0, 0.0)
                val mean = sum / n
                val variance = sumSq / n - mean * mean
                return Strip(
                    mean,
                    sqrt(maxOf(0.0, variance)),
                    satSum / n,
                    edgeCount.toDouble() / n
                )
            }

            val edge = (min(w, h) * 0.03f).toInt().coerceAtLeast(4)
            val inner = (min(w, h) * 0.30f).toInt()

            val top = stripStats(0, 0, w, edge)
            val bottom = stripStats(0, h - edge, w, h)
            val left = stripStats(0, 0, edge, h)
            val right = stripStats(w - edge, 0, w, h)
            val center = stripStats(w / 2 - inner / 2, h / 2 - inner / 2, w / 2 + inner / 2, h / 2 + inner / 2)

            val borders = listOf(top, bottom, left, right)
            var suspicious = 0
            for (s in borders) {
                // Darkness vs page centre.
                val darker = center.mean - s.mean > 28
                // Texture: variance far above the paper centre.
                val textured = s.std > center.std * 2.2 + 12
                // Colourfulness: real scene (wood, fabric, screen) is saturated.
                val colorful = s.sat > center.sat + 22 && s.sat > 30
                // Edge density: scene detail, unlike blank paper margins.
                val busy = s.edgeDensity > center.edgeDensity + 0.18
                if ((darker && textured) || (darker && colorful) ||
                    (textured && colorful) || (busy && textured)
                ) {
                    suspicious++
                }
            }
            // Two or more bad sides -> invalid scan.
            if (suspicious >= 2) return false
            // Any near-black strip is almost certainly background, not paper.
            if (borders.any { it.mean < 45 }) return false
            return true
        } catch (e: Exception) {
            // Fail CLOSED. If we cannot prove the output is clean, we must not
            // save it as a final scan.
            return false
        }
    }

    // ------------------------------------------------------------------
    // Debug support (Step 19)
    // ------------------------------------------------------------------

    /**
     * Debug stage preview for the development screen. All previews derive
     * from the same detection used by the pipeline.
     */
    data class ScanDebugInfo(
        val edgePreview: Bitmap?,
        val candidatesPreview: Bitmap?,
        val selectedPreview: Bitmap?,
        val perspectivePreview: Bitmap?,
        val deskewedPreview: Bitmap?,
        val dewarpedPreview: Bitmap?,
        val enhancedPreview: Bitmap?,
        val detection: DetectionResult?,
        val stats: DocumentDetector.DetectionStats? = null
    )

    fun buildDebugInfo(bitmap: Bitmap, detection: DetectionResult? = null): ScanDebugInfo {
        val result = detection ?: try {
            DocumentDetector.detectDocument(bitmap, fast = false)
        } catch (e: Exception) {
            null
        }
        val edges = try {
            DocumentDetector.renderEdgePreview(bitmap)
        } catch (e: Exception) {
            null
        }
        val cands = try {
            if (result != null) DocumentDetector.renderCandidatesPreview(bitmap, result) else null
        } catch (e: Exception) {
            null
        }
        val sel = try {
            if (result?.corners != null) renderSelectedPreview(bitmap, result.corners) else null
        } catch (e: Exception) {
            null
        }
        // Downstream stage previews from the detected quad (small copies).
        var perspective: Bitmap? = null
        var deskewed: Bitmap? = null
        var dewarped: Bitmap? = null
        var enhanced: Bitmap? = null
        try {
            val quad = result?.corners
            if (quad != null) {
                val warped = PerspectiveWarper.warpToRectangle(bitmap, quad)
                perspective = downscaleForDebug(warped)
                val ds = DeskewHelper.deskew(warped)
                if (ds !== warped) warped.recycle()
                deskewed = downscaleForDebug(ds)
                val dw = DewarpHelper.maybeDewarp(ds)
                if (dw !== ds) ds.recycle()
                dewarped = downscaleForDebug(dw)
                val en = DocumentEnhancer.enhance(dw, FilterMode.AUTO)
                if (en !== dw) dw.recycle()
                enhanced = downscaleForDebug(en)
                if (en !== enhanced) en.recycle()
            }
        } catch (e: Exception) {
            // Debug only; ignore stage failures.
        }
        return ScanDebugInfo(
            edges, cands, sel, perspective, deskewed, dewarped, enhanced, result,
            DocumentDetector.lastStats
        )
    }

    private fun downscaleForDebug(bitmap: Bitmap): Bitmap {
        val maxDim = 480
        val scale = min(1f, maxDim.toFloat() / max(bitmap.width, bitmap.height))
        if (scale >= 1f) return bitmap
        val sw = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val sh = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, sw, sh, true)
    }

    private fun renderSelectedPreview(bitmap: Bitmap, corners: CornerPoints): Bitmap {
        val maxDim = 720
        val scale = min(1f, maxDim.toFloat() / max(bitmap.width, bitmap.height))
        val sw = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val sh = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val preview = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        val out = preview.copy(Bitmap.Config.ARGB_8888, true)
        if (out != preview) preview.recycle()
        val canvas = Canvas(out)
        val scaled = corners.scale(sw.toFloat(), sh.toFloat())
        val path = android.graphics.Path().apply {
            moveTo(scaled.topLeft.x, scaled.topLeft.y)
            lineTo(scaled.topRight.x, scaled.topRight.y)
            lineTo(scaled.bottomRight.x, scaled.bottomRight.y)
            lineTo(scaled.bottomLeft.x, scaled.bottomLeft.y)
            close()
        }
        val fill = Paint().apply {
            color = Color.argb(70, 0, 230, 120)
            style = Paint.Style.FILL
        }
        val stroke = Paint().apply {
            color = Color.rgb(0, 210, 110)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        val dot = Paint().apply { color = Color.YELLOW; style = Paint.Style.FILL }
        val dotR = 10f
        for (p in scaled.toList()) canvas.drawCircle(p.x, p.y, dotR, dot)
        // Label corners TL/TR/BR/BL.
        val text = Paint().apply {
            color = Color.BLACK
            textSize = 30f
            isFakeBoldText = true
        }
        val labels = listOf("TL", "TR", "BR", "BL")
        scaled.toList().forEachIndexed { i, p ->
            canvas.drawText(labels[i], p.x + 12f, p.y - 12f, text)
        }
        return out
    }
}
