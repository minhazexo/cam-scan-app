package com.camscan.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import com.camscan.app.domain.model.CornerPoints
import com.camscan.app.domain.model.DetectionAction
import com.camscan.app.domain.model.DetectionConfidence
import com.camscan.app.domain.model.DetectionOverlayState
import com.camscan.app.domain.model.DetectionResult
import com.camscan.app.domain.model.LiveDetection
import com.camscan.app.domain.processor.DocumentDetector
import com.camscan.app.domain.processor.QuadValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Regression suite for the reworked multi-pass auto detector.
 *
 * Synthetic scenes are rendered with `setPixels` (real pixels under
 * Robolectric) — a smooth background gradient plus grain, with a paper quad
 * and text bars inside. They cover rotation, perspective, coverage, false
 * positives, deduplication and the live overlay contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoDetectionRegressionTest {

    // ------------------------------------------------------------------
    // Scene rendering
    // ------------------------------------------------------------------

    private fun renderScene(
        w: Int,
        h: Int,
        quad: List<PointF>?,
        tableBright: Boolean = false,
        textRows: Int = 12,
        seed: Long = 42L
    ): Bitmap {
        val rnd = Random(seed)
        val pixels = IntArray(w * h)
        val dark = if (tableBright) 205 else 50
        val light = if (tableBright) 245 else 100
        for (y in 0 until h) {
            for (x in 0 until w) {
                val t = (x.toFloat() / w) * 0.6f + (y.toFloat() / h) * 0.4f
                val base = dark + ((light - dark) * t).toInt()
                val grain = rnd.nextInt(9) - 4
                val v = (base + grain).coerceIn(0, 255)
                pixels[y * w + x] = Color.rgb(v, v, v)
            }
        }
        if (quad != null) {
            val paper = if (tableBright) Color.rgb(248, 248, 250) else Color.WHITE
            fillQuad(pixels, w, h, quad, paper)
            // Fake text bars clipped to the paper interior.
            val ys = quad.map { it.y }
            val minY = ys.min()
            val maxY = ys.max()
            val span = (maxY - minY).coerceAtLeast(1f)
            val ink = Color.rgb(40, 40, 40)
            for (r in 0 until textRows) {
                val y = (minY + span * (0.12f + 0.76f * r / textRows)).toInt()
                if (y !in 0 until h) continue
                val xs = scanX(quad, y.toFloat()) ?: continue
                val inset = (xs.second - xs.first) * 0.10f
                val x0 = (xs.first + inset).toInt()
                val x1 = (xs.second - inset).toInt()
                if (x1 <= x0) continue
                for (yy in y until min(y + 5, h)) {
                    for (x in x0 until min(x1, w)) {
                        pixels[yy * w + x] = ink
                    }
                }
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun fillQuad(pixels: IntArray, w: Int, h: Int, quad: List<PointF>, color: Int) {
        val ys = quad.map { it.y }
        val minY = max(0, ys.min().toInt())
        val maxY = min(h, ys.max().toInt())
        for (y in minY until maxY) {
            val xs = scanX(quad, y.toFloat()) ?: continue
            val x0 = max(0, xs.first.toInt())
            val x1 = min(w, xs.second.toInt())
            for (x in x0 until x1) pixels[y * w + x] = color
        }
    }

    private fun scanX(quad: List<PointF>, y: Float): Pair<Float, Float>? {
        val xs = mutableListOf<Float>()
        for (i in quad.indices) {
            val a = quad[i]
            val b = quad[(i + 1) % quad.size]
            if ((a.y <= y && b.y > y) || (b.y <= y && a.y > y)) {
                val t = (y - a.y) / (b.y - a.y)
                xs.add(a.x + t * (b.x - a.x))
            }
        }
        if (xs.size < 2) return null
        xs.sort()
        return Pair(xs.first(), xs.last())
    }

    private fun rect(w: Int, h: Int, fx0: Float, fy0: Float, fx1: Float, fy1: Float) = listOf(
        PointF(w * fx0, h * fy0),
        PointF(w * fx1, h * fy0),
        PointF(w * fx1, h * fy1),
        PointF(w * fx0, h * fy1)
    )

    private fun rotatedRect(w: Int, h: Int, hw: Float, hh: Float, deg: Double): List<PointF> {
        val cx = w / 2f
        val cy = h / 2f
        val a = Math.toRadians(deg)
        fun rot(px: Float, py: Float): PointF {
            val dx = px - cx
            val dy = py - cy
            return PointF(cx + dx * cos(a).toFloat() - dy * sin(a).toFloat(),
                cy + dx * sin(a).toFloat() + dy * cos(a).toFloat())
        }
        return listOf(rot(cx - hw, cy - hh), rot(cx + hw, cy - hh), rot(cx + hw, cy + hh), rot(cx - hw, cy + hh))
    }

    private fun quadError(a: CornerPoints, expected: List<PointF>, w: Float, h: Float): Float {
        val pts = a.toList()
        var worst = 0f
        for (i in 0 until 4) {
            worst = max(worst, max(abs(pts[i].x - expected[i].x / w), abs(pts[i].y - expected[i].y / h)))
        }
        return worst
    }

    private fun areaFraction(corners: CornerPoints, w: Int, h: Int): Float =
        abs(QuadValidator.polygonArea(corners.scale(w.toFloat(), h.toFloat()).toList())) / (w * h)

    // ------------------------------------------------------------------
    // Rotation
    // ------------------------------------------------------------------

    @Test
    fun rotation10_detected() {
        val w = 600
        val h = 800
        val expected = rotatedRect(w, h, w * 0.30f, h * 0.32f, 10.0)
        val photo = renderScene(w, h, expected)
        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertNotNull("10deg doc must be detected: ${result.reason}", result.corners)
        val err = quadError(result.corners!!, expected, w.toFloat(), h.toFloat())
        assertTrue("10deg corners off (err=$err)", err < 0.10f)
        photo.recycle()
    }

    @Test
    fun rotation20_detected() {
        val w = 600
        val h = 800
        val expected = rotatedRect(w, h, w * 0.29f, h * 0.30f, 20.0)
        val photo = renderScene(w, h, expected)
        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertNotNull("20deg doc must be detected: ${result.reason}", result.corners)
        val err = quadError(result.corners!!, expected, w.toFloat(), h.toFloat())
        assertTrue("20deg corners off (err=$err)", err < 0.12f)
        photo.recycle()
    }

    // ------------------------------------------------------------------
    // Perspective
    // ------------------------------------------------------------------

    @Test
    fun strongPerspective_detected() {
        val w = 640
        val h = 800
        val expected = listOf(
            PointF(w * 0.30f, h * 0.16f),
            PointF(w * 0.78f, h * 0.09f),
            PointF(w * 0.70f, h * 0.91f),
            PointF(w * 0.16f, h * 0.83f)
        )
        val photo = renderScene(w, h, expected)
        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertNotNull("perspective doc must be detected: ${result.reason}", result.corners)
        val err = quadError(result.corners!!, expected, w.toFloat(), h.toFloat())
        assertTrue("perspective corners off (err=$err)", err < 0.12f)
        photo.recycle()
    }

    // ------------------------------------------------------------------
    // Coverage: 50% and 70% documents
    // ------------------------------------------------------------------

    @Test
    fun document50Percent_onlyDocumentExtracted() {
        val w = 640
        val h = 800
        val expected = rect(w, h, 0.25f, 0.25f, 0.75f, 0.75f)
        val photo = renderScene(w, h, expected)
        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertNotNull("50% doc must be detected: ${result.reason}", result.corners)
        val corners = result.corners!!
        val err = quadError(corners, expected, w.toFloat(), h.toFloat())
        assertTrue("50% corners off (err=$err)", err < 0.12f)
        assertTrue("background leaked at 50% (area=${areaFraction(corners, w, h)})",
            areaFraction(corners, w, h) < 0.42f)
        photo.recycle()
    }

    @Test
    fun document70Percent_onlyDocumentExtracted() {
        val w = 640
        val h = 800
        val expected = rect(w, h, 0.15f, 0.15f, 0.85f, 0.85f)
        val photo = renderScene(w, h, expected)
        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertNotNull("70% doc must be detected: ${result.reason}", result.corners)
        val corners = result.corners!!
        val err = quadError(corners, expected, w.toFloat(), h.toFloat())
        assertTrue("70% corners off (err=$err)", err < 0.10f)
        assertTrue("background leaked at 70% (area=${areaFraction(corners, w, h)})",
            areaFraction(corners, w, h) < 0.66f)
        photo.recycle()
    }

    // ------------------------------------------------------------------
    // Background / no document
    // ------------------------------------------------------------------

    @Test
    fun backgroundOnly_demandsManualCorners() {
        val photo = renderScene(560, 720, null)
        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertTrue("background-only must go manual (${result.confidence}: ${result.reason})", result.needsManual)
        assertNull("no corners may be invented", result.corners)
        photo.recycle()
    }

    @Test
    fun fullFrameTexturedPhoto_notAutoProcessed() {
        // Whole frame is "wall" texture (no paper). A full-frame photo quad is
        // the camera, never the document.
        val bmp = renderScene(640, 800, null)
        val result = DocumentDetector.detectDocument(bmp, fast = false)
        assertFalse("full-frame texture must not be a digital page", result.digitalPage)
        assertTrue("full-frame texture must not auto-process", result.blocksAutoProcessing)
        bmp.recycle()
    }

    // ------------------------------------------------------------------
    // Low contrast: weak / ambiguous stays confirmation
    // ------------------------------------------------------------------

    @Test
    fun lowContrastDocument_requiresConfirmationNotAuto() {
        val w = 600
        val h = 800
        val expected = rect(w, h, 0.2f, 0.2f, 0.8f, 0.8f)
        val photo = renderScene(w, h, expected, tableBright = true)
        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertTrue(
            "low-contrast page must not auto-process (${result.confidence}: ${result.reason})",
            result.blocksAutoProcessing
        )
        photo.recycle()
    }

    // ------------------------------------------------------------------
    // Deduplication
    // ------------------------------------------------------------------

    @Test
    fun candidatesAreDeduplicated() {
        val w = 600
        val h = 800
        val photo = renderScene(w, h, rect(w, h, 0.18f, 0.14f, 0.82f, 0.86f))
        val result = DocumentDetector.detectDocument(photo, fast = false)
        val candidates = result.allCandidates
        val diag = kotlin.math.hypot(w.toDouble(), h.toDouble()).toFloat()
        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                val d = QuadValidator.maxCornerDistance(
                    candidates[i].toList(), candidates[j].toList(), w.toFloat(), h.toFloat()
                )
                assertTrue(
                    "near-duplicate candidates survived dedup (dist=${d * diag}px)",
                    d >= 0.02f
                )
            }
        }
        photo.recycle()
    }

    // ------------------------------------------------------------------
    // Live overlay contract (Phase 10/12)
    // ------------------------------------------------------------------

    @Test
    fun detectCorners_returnsNullWhenNothingDetected() {
        val bmp = Bitmap.createBitmap(400, 500, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        // No fake 12%-88% rectangle may be fabricated.
        assertNull("no fixed fake quad may be returned", DocumentDetector.detectCorners(bmp))
        val live = DocumentDetector.detectLive(bmp)
        assertEquals(DetectionOverlayState.NOT_DETECTED, live.state)
        assertFalse(live.hasQuad)
        bmp.recycle()
    }

    @Test
    fun detectLive_reportsDetectedQuadForRealDocument() {
        val w = 600
        val h = 800
        val photo = renderScene(w, h, rect(w, h, 0.2f, 0.15f, 0.8f, 0.85f))
        val live = DocumentDetector.detectLive(photo)
        assertEquals(DetectionOverlayState.DETECTED, live.state)
        assertTrue("detected live quad must be drawable", live.hasQuad)
        assertNotNull(live.corners)
        photo.recycle()
    }

    @Test
    fun overlayMapping_mediumRequiresConfirmation() {
        val medium = DetectionResult(
            corners = CornerPoints(
                PointF(0.1f, 0.1f), PointF(0.9f, 0.1f),
                PointF(0.9f, 0.9f), PointF(0.1f, 0.9f)
            ),
            confidence = DetectionConfidence.MEDIUM,
            score = 0.75f
        )
        val live = LiveDetection.from(medium)
        assertEquals(DetectionOverlayState.CONFIRMATION_REQUIRED, live.state)
        assertTrue(live.hasQuad)

        val lowResult = DetectionResult(null, DetectionConfidence.LOW, 0f, emptyList(), "none")
        val none = LiveDetection.from(lowResult)
        assertEquals(DetectionOverlayState.NOT_DETECTED, none.state)
        assertFalse(none.hasQuad)
        assertEquals(DetectionAction.USER_SELECT_CORNERS, lowResult.action)
    }

    // ------------------------------------------------------------------
    // Digital page stays detected as its own canvas
    // ------------------------------------------------------------------

    @Test
    fun digitalPage_identifiedAsDocument() {
        val w = 620
        val h = 877
        val pixels = IntArray(w * h) { Color.WHITE }
        val rnd = Random(3L)
        val ink = Color.rgb(30, 30, 30)
        var y = (h * 0.10f).toInt()
        while (y < h * 0.90f) {
            val words = 3 + rnd.nextInt(4)
            var x = (w * 0.12f).toInt()
            repeat(words) {
                val wlen = (w * (0.06f + rnd.nextFloat() * 0.09f)).toInt()
                for (yy in y until min(y + 3, h)) {
                    for (xx in x until min(x + wlen, w)) {
                        if (xx in 0 until w) pixels[yy * w + xx] = ink
                    }
                }
                x += wlen + (w * 0.02f).toInt()
            }
            y += (h * 0.035f).toInt()
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        val result = DocumentDetector.detectDocument(bmp, fast = false)
        assertTrue("born-digital page must be identified (${result.reason})", result.digitalPage)
        assertEquals(DetectionAction.AUTO_PROCESS, result.action)
        bmp.recycle()
    }

    // ------------------------------------------------------------------
    // Shadows, receipts, notebooks, busy backgrounds
    // ------------------------------------------------------------------

    private fun f2(v: Float) = "%.2f".format(v)

    /** Soft diagonal shadow band over the whole photo (uneven lighting). */
    private fun addShadow(photo: Bitmap, w: Int, h: Int, strength: Float = 0.35f) {
        val px = IntArray(w * h)
        photo.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val d = abs((x - w * 0.28f) - y * 0.22f) / (w * 0.22f)
                if (d < 1f) {
                    val f = 1f - strength * (1f - d)
                    val c = px[y * w + x]
                    px[y * w + x] = Color.rgb(
                        (Color.red(c) * f).toInt().coerceIn(0, 255),
                        (Color.green(c) * f).toInt().coerceIn(0, 255),
                        (Color.blue(c) * f).toInt().coerceIn(0, 255)
                    )
                }
            }
        }
        photo.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** Ruled notebook lines (blue) plus a red margin line across the page. */
    private fun addRuledLines(photo: Bitmap, w: Int, h: Int, quad: List<PointF>) {
        val px = IntArray(w * h)
        photo.getPixels(px, 0, w, 0, 0, w, h)
        val ys = quad.map { it.y }
        val minY = ys.min()
        val maxY = ys.max()
        val span = (maxY - minY).coerceAtLeast(1f)
        val rule = Color.rgb(150, 170, 210)
        val margin = Color.rgb(205, 80, 80)
        var y = minY + span * 0.10f
        while (y < maxY - span * 0.06f) {
            val xs = scanX(quad, y)
            if (xs != null) {
                val x0 = (xs.first + (xs.second - xs.first) * 0.08f).toInt()
                val x1 = (xs.second - (xs.second - xs.first) * 0.06f).toInt()
                val yy = y.toInt()
                if (yy in 0 until h) {
                    for (x in max(0, x0) until min(x1, w)) px[yy * w + x] = rule
                    val mx = (xs.first + (xs.second - xs.first) * 0.14f).toInt()
                    if (mx in 0 until w) {
                        for (yyy in yy until min(yy + 2, h)) px[yyy * w + mx] = margin
                    }
                }
            }
            y += span * 0.055f
        }
        photo.setPixels(px, 0, w, 0, 0, w, h)
    }

    /**
     * Checkered, high-frequency "busy" background (wood grain / patterned
     * desk) with an optional paper page on top.
     */
    private fun renderBusyScene(w: Int, h: Int, quad: List<PointF>?, seed: Long = 11L): Bitmap {
        val rnd = Random(seed)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val check = ((x / 9) + (y / 7)) % 2
                val base = if (check == 0) 95 else 175
                val grain = rnd.nextInt(70) - 35
                val v = (base + grain).coerceIn(0, 255)
                val b = (v + if (check == 0) 12 else 0).coerceIn(0, 255)
                px[y * w + x] = Color.rgb(v, v, b)
            }
        }
        if (quad != null) {
            fillQuad(px, w, h, quad, Color.rgb(250, 250, 250))
            val ys = quad.map { it.y }
            val minY = ys.min()
            val maxY = ys.max()
            val span = (maxY - minY).coerceAtLeast(1f)
            val ink = Color.rgb(40, 40, 40)
            for (r in 0 until 16) {
                val y = (minY + span * (0.10f + 0.80f * r / 16f)).toInt()
                if (y !in 0 until h) continue
                val xs = scanX(quad, y.toFloat()) ?: continue
                val inset = (xs.second - xs.first) * 0.12f
                val x0 = (xs.first + inset).toInt()
                val x1 = (xs.second - inset).toInt()
                if (x1 <= x0) continue
                for (yy in y until min(y + 5, h)) {
                    for (x in max(0, x0) until min(x1, w)) px[yy * w + x] = ink
                }
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    @Test
    fun shadowAcrossPage_stillDetected() {
        val w = 640
        val h = 820
        val expected = rect(w, h, 0.18f, 0.14f, 0.82f, 0.86f)
        val photo = renderScene(w, h, expected)
        addShadow(photo, w, h)
        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertNotNull("shadowed page must be detected: ${result.reason}", result.corners)
        val err = quadError(result.corners!!, expected, w.toFloat(), h.toFloat())
        assertTrue("shadow shifted the corners (err=$err)", err < 0.12f)
        assertFalse("shadowed page must not be mistaken for a digital page", result.digitalPage)
        photo.recycle()
    }

    @Test
    fun receiptTallNarrow_detected() {
        val w = 600
        val h = 900
        val expected = rect(w, h, 0.34f, 0.08f, 0.66f, 0.92f)
        val photo = renderScene(w, h, expected, textRows = 26)
        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertNotNull("tall receipt must be detected: ${result.reason}", result.corners)
        val corners = result.corners!!
        val err = quadError(corners, expected, w.toFloat(), h.toFloat())
        assertTrue("receipt corners off (err=$err)", err < 0.12f)
        val area = areaFraction(corners, w, h)
        assertTrue("receipt area implausible (area=$area)", area in 0.20f..0.42f)
        photo.recycle()
    }

    @Test
    fun notebookRuledPage_detected() {
        val w = 640
        val h = 820
        val expected = rect(w, h, 0.14f, 0.12f, 0.86f, 0.88f)
        val photo = renderScene(w, h, expected, textRows = 4)
        addRuledLines(photo, w, h, expected)
        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertNotNull("ruled notebook page must be detected: ${result.reason}", result.corners)
        val err = quadError(result.corners!!, expected, w.toFloat(), h.toFloat())
        assertTrue("notebook corners off (err=$err)", err < 0.10f)
        photo.recycle()
    }

    @Test
    fun busyBackground_documentStillDetected() {
        val w = 700
        val h = 900
        val expected = rect(w, h, 0.20f, 0.16f, 0.80f, 0.84f)
        val photo = renderBusyScene(w, h, expected)
        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertNotNull("busy background must not hide the page: ${result.reason}", result.corners)
        val corners = result.corners!!
        val err = quadError(corners, expected, w.toFloat(), h.toFloat())
        assertTrue("busy-background corners off (err=$err)", err < 0.14f)
        val area = areaFraction(corners, w, h)
        assertTrue("background leaked into the page (area=${f2(area)})", area < 0.72f)
        photo.recycle()
    }

    @Test
    fun busyBackgroundOnly_demandsManual() {
        val photo = renderBusyScene(700, 900, null)
        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertFalse("textured background is not a digital page", result.digitalPage)
        assertTrue(
            "busy background must not auto-process (${result.confidence}: ${result.reason})",
            result.blocksAutoProcessing
        )
        photo.recycle()
    }
}
