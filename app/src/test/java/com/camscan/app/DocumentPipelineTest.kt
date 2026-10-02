package com.camscan.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import com.camscan.app.domain.model.CornerPoints
import com.camscan.app.domain.model.DetectionConfidence
import com.camscan.app.domain.model.FilterMode
import com.camscan.app.domain.processor.DocumentDetector
import com.camscan.app.domain.processor.DocumentProcessor
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
import kotlin.math.max
import kotlin.math.min

/**
 * Regression tests for the document-scanning pipeline (Step 20).
 *
 * Synthetic "photographs" are rendered programmatically: a textured
 * background (table/wall) with a paper quad inside, plus fake text lines.
 * They exercise the REAL pipeline — detection, validation, perspective
 * warp, deskew, A4 composition — with no full-photo fallback allowed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentPipelineTest {

    // ------------------------------------------------------------------
    // Synthetic photo rendering
    // ------------------------------------------------------------------

    private fun renderPhoto(
        w: Int,
        h: Int,
        quad: List<PointF>?, // null = no document (background only)
        seed: Long = 7L,
        sparseText: Boolean = false
    ): Bitmap {
        val rnd = Random(seed)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        // Realistic textured background: smooth organic blotches + grain
        // (value noise), no regular lines that could fake page edges.
        val gw = 20
        val gh = 20
        val grid = Array(gh + 1) { FloatArray(gw + 1) { rnd.nextFloat() } }
        fun smooth(t: Float): Float = t * t * (3f - 2f * t)
        fun blotch(x: Int, y: Int): Float {
            val fx = (x.toFloat() / w * gw).coerceIn(0f, gw - 0.001f)
            val fy = (y.toFloat() / h * gh).coerceIn(0f, gh - 0.001f)
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val tx = smooth(fx - x0)
            val ty = smooth(fy - y0)
            val a = grid[y0][x0]
            val b = grid[y0][x0 + 1]
            val c = grid[y0 + 1][x0]
            val d = grid[y0 + 1][x0 + 1]
            return a + (b - a) * tx + (c - a) * ty + (a - b - c + d) * tx * ty
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val n = blotch(x, y)
                val grain = rnd.nextInt(21) - 10
                val r = (62 + n * 48 + grain).toInt().coerceIn(0, 255)
                val g = (48 + n * 40 + grain).toInt().coerceIn(0, 255)
                val b = (38 + n * 32 + grain).toInt().coerceIn(0, 255)
                pixels[y * w + x] = Color.rgb(r, g, b)
            }
        }
        // A few "surrounding objects".
        repeat(6) {
            val cx = rnd.nextInt(w)
            val cy = rnd.nextInt(h)
            val rad = 20 + rnd.nextInt(50)
            for (y in max(0, cy - rad) until min(h, cy + rad)) {
                for (x in max(0, cx - rad) until min(w, cx + rad)) {
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy < rad * rad) {
                        pixels[y * w + x] = Color.rgb(30, 30, 34)
                    }
                }
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)

        if (quad != null) {
            val canvas = Canvas(bmp)
            // Paper fill via scanline rasterisation of the quad.
            var minY = h
            var maxY = 0
            for (p in quad) {
                minY = min(minY, p.y.toInt())
                maxY = max(maxY, p.y.toInt())
            }
            for (y in max(0, minY) until min(h, maxY)) {
                val xs = mutableListOf<Float>()
                for (i in quad.indices) {
                    val a = quad[i]
                    val b = quad[(i + 1) % quad.size]
                    if ((a.y <= y && b.y > y) || (b.y <= y && a.y > y)) {
                        val t = (y - a.y) / (b.y - a.y)
                        xs.add(a.x + t * (b.x - a.x))
                    }
                }
                if (xs.size >= 2) {
                    xs.sort()
                    val paint = Paint().apply { color = Color.WHITE }
                    canvas.drawRect(xs.first(), y.toFloat(), xs.last(), (y + 1).toFloat(), paint)
                }
            }
            // Fake text lines clipped to the quad interior.
            val linePaint = Paint().apply { color = Color.rgb(45, 45, 45) }
            val rows = if (sparseText) 4 else 14
            val span = (maxY - minY).toFloat()
            for (r in 0 until rows) {
                val y = (minY + span * (0.12f + 0.76f * r / rows)).toInt()
                if (y !in 0 until h) continue
                val xs = mutableListOf<Float>()
                for (i in quad.indices) {
                    val a = quad[i]
                    val b = quad[(i + 1) % quad.size]
                    if ((a.y <= y && b.y > y) || (b.y <= y && a.y > y)) {
                        val t = (y - a.y) / (b.y - a.y)
                        xs.add(a.x + t * (b.x - a.x))
                    }
                }
                if (xs.size >= 2) {
                    xs.sort()
                    val inset = (xs.last() - xs.first()) * 0.1f
                    val x0 = xs.first() + inset
                    val x1 = xs.last() - inset - rnd.nextFloat() * (xs.last() - xs.first()) * 0.25f
                    if (x1 > x0 + 10) {
                        canvas.drawRect(x0, y.toFloat(), x1, (y + 5).toFloat(), linePaint)
                    }
                }
            }
            // Printed inner border (legitimate page furniture that must survive).
            val borderPaint = Paint().apply {
                color = Color.rgb(120, 120, 120)
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            val path = Path().apply {
                moveTo(quad[0].x, quad[0].y)
                lineTo(quad[1].x, quad[1].y)
                lineTo(quad[2].x, quad[2].y)
                lineTo(quad[3].x, quad[3].y)
                close()
            }
            canvas.drawPath(path, borderPaint)
        }
        return bmp
    }

    private fun centeredQuad(w: Int, h: Int, fx0: Float, fy0: Float, fx1: Float, fy1: Float): List<PointF> {
        return listOf(
            PointF(w * fx0, h * fy0),
            PointF(w * fx1, h * fy0),
            PointF(w * fx1, h * fy1),
            PointF(w * fx0, h * fy1)
        )
    }

    /** Max per-corner normalized error between detected quad and ground truth. */
    private fun quadError(a: CornerPoints, expected: List<PointF>, w: Float, h: Float): Float {
        val pts = a.toList()
        var worst = 0f
        for (i in 0 until 4) {
            val dx = abs(pts[i].x - expected[i].x / w)
            val dy = abs(pts[i].y - expected[i].y / h)
            worst = max(worst, max(dx, dy))
        }
        return worst
    }

    // ------------------------------------------------------------------
    // CASE 1: document = 60% of photo -> only document
    // ------------------------------------------------------------------

    @Test
    fun case1_centeredDocument_isolatedFromBackground() {
        val w = 600
        val h = 800
        val expected = centeredQuad(w, h, 0.2f, 0.15f, 0.8f, 0.85f)
        val photo = renderPhoto(w, h, expected)

        val result = DocumentDetector.detectDocument(photo, fast = false)

        assertNotNull("detection must succeed, got: ${result.reason}", result.corners)
        assertTrue("must not be LOW, got ${result.confidence}: ${result.reason}", result.confidence != DetectionConfidence.LOW)
        val err = quadError(result.corners!!, expected, w.toFloat(), h.toFloat())
        assertTrue("corners too far from paper (err=$err)", err < 0.07f)

        // Strict pipeline output must be A4 and paper-only.
        val scan = DocumentProcessor.processImageStrict(photo, FilterMode.ORIGINAL, result.corners)
        assertEquals(2480, scan.a4.width)
        assertEquals(3508, scan.a4.height)
        // Centre of the A4 must be paper white, not table texture.
        val cx = scan.a4.getPixel(scan.a4.width / 2, scan.a4.height / 2)
        val lum = 0.299 * Color.red(cx) + 0.587 * Color.green(cx) + 0.114 * Color.blue(cx)
        assertTrue("A4 centre is not paper (lum=$lum)", lum > 150)
        scan.a4.recycle()
        scan.documentOnly.recycle()
        photo.recycle()
    }

    // ------------------------------------------------------------------
    // CASE 2: tilted document -> straight output
    // ------------------------------------------------------------------

    @Test
    fun case2_tiltedDocument_detected() {
        val w = 600
        val h = 800
        // 15-degree rotated rectangle about the centre.
        val cx = w / 2f
        val cy = h / 2f
        val hw = w * 0.28f
        val hh = h * 0.32f
        val ang = Math.toRadians(15.0)
        val cosA = kotlin.math.cos(ang).toFloat()
        val sinA = kotlin.math.sin(ang).toFloat()
        fun rot(px: Float, py: Float): PointF {
            val dx = px - cx
            val dy = py - cy
            return PointF(cx + dx * cosA - dy * sinA, cy + dx * sinA + dy * cosA)
        }
        val expected = listOf(
            rot(cx - hw, cy - hh), rot(cx + hw, cy - hh),
            rot(cx + hw, cy + hh), rot(cx - hw, cy + hh)
        )
        val photo = renderPhoto(w, h, expected)

        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertNotNull("tilted doc must be detected: ${result.reason}", result.corners)
        assertTrue(result.confidence != DetectionConfidence.LOW)
        val err = quadError(result.corners!!, expected, w.toFloat(), h.toFloat())
        assertTrue("tilted corners off (err=$err)", err < 0.09f)
        photo.recycle()
    }

    // ------------------------------------------------------------------
    // CASE 3: perspective trapezoid -> corners found
    // ------------------------------------------------------------------

    @Test
    fun case3_perspectiveTrapezoid_detected() {
        val w = 600
        val h = 800
        val expected = listOf(
            PointF(w * 0.26f, h * 0.14f),
            PointF(w * 0.80f, h * 0.22f),
            PointF(w * 0.72f, h * 0.90f),
            PointF(w * 0.18f, h * 0.82f)
        )
        val photo = renderPhoto(w, h, expected)

        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertNotNull("perspective doc must be detected: ${result.reason}", result.corners)
        assertTrue(result.confidence != DetectionConfidence.LOW)
        val err = quadError(result.corners!!, expected, w.toFloat(), h.toFloat())
        assertTrue("perspective corners off (err=$err)", err < 0.10f)
        photo.recycle()
    }

    // ------------------------------------------------------------------
    // CASE 4: large empty margins preserved (no text-area crop)
    // ------------------------------------------------------------------

    @Test
    fun case4_emptyMargins_preserved() {
        val w = 600
        val h = 800
        val expected = centeredQuad(w, h, 0.18f, 0.12f, 0.82f, 0.88f)
        val photo = renderPhoto(w, h, expected, sparseText = true)

        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertNotNull("sparse page must be detected: ${result.reason}", result.corners)
        // The found quad must cover the PAPER (~0.64*0.76=0.49), not the text.
        val pts = result.corners!!.scale(w.toFloat(), h.toFloat()).toList()
        val area = abs(QuadValidator.polygonArea(pts)) / (w * h)
        assertTrue("quad cropped to text instead of paper (area=$area)", area > 0.30f)
        photo.recycle()
    }

    // ------------------------------------------------------------------
    // CASE 6: small handwritten note on table -> only note
    // ------------------------------------------------------------------

    @Test
    fun case6_smallNote_onlyNoteExtracted() {
        val w = 700
        val h = 700
        val expected = centeredQuad(w, h, 0.28f, 0.30f, 0.72f, 0.70f)
        val photo = renderPhoto(w, h, expected)

        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertNotNull("note must be detected: ${result.reason}", result.corners)
        assertTrue(result.confidence != DetectionConfidence.LOW)
        val pts = result.corners!!.scale(w.toFloat(), h.toFloat()).toList()
        val area = abs(QuadValidator.polygonArea(pts)) / (w * h)
        assertTrue("leaked background into quad (area=$area)", area < 0.45f)
        val err = quadError(result.corners!!, expected, w.toFloat(), h.toFloat())
        assertTrue("note corners off (err=$err)", err < 0.09f)
        photo.recycle()
    }

    // ------------------------------------------------------------------
    // CASE 8: detection failure -> manual required, never full photo
    // ------------------------------------------------------------------

    @Test
    fun case8_noDocument_demandsManual() {
        val photo = renderPhoto(500, 600, null)
        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertTrue("expected LOW, got ${result.confidence} (${result.reason})", result.needsManual)
        assertNull("LOW must carry no corners", result.corners)
        photo.recycle()
    }

    // ------------------------------------------------------------------
    // Clean digital page (uniform background) is the documented exception
    // ------------------------------------------------------------------

    @Test
    fun uniformWhitePage_acceptedWithConfirmation() {
        val bmp = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val result = DocumentDetector.detectDocument(bmp, fast = false)
        assertTrue("uniform page should not be LOW", result.confidence != DetectionConfidence.LOW)
        assertNotNull(result.corners)
        bmp.recycle()
    }

    // ------------------------------------------------------------------
    // Validator rules
    // ------------------------------------------------------------------

    @Test
    fun validator_rejectsFullFrameAndCrossedQuads() {
        val w = 600f
        val h = 800f
        val full = CornerPoints(
            PointF(0f, 0f), PointF(w, 0f), PointF(w, h), PointF(0f, h)
        )
        assertFalse("full frame must be invalid", QuadValidator.validate(full, w, h).valid)
        assertTrue("full frame must be flagged", QuadValidator.isFullFrame(full, w, h))

        val crossed = CornerPoints(
            PointF(100f, 100f), PointF(500f, 700f), PointF(500f, 100f), PointF(100f, 700f)
        )
        assertFalse("crossed quad must be invalid", QuadValidator.validate(crossed, w, h).valid)

        val good = CornerPoints(
            PointF(120f, 120f), PointF(480f, 120f), PointF(480f, 680f), PointF(120f, 680f)
        )
        assertTrue("good quad must be valid", QuadValidator.validate(good, w, h).valid)
    }

    @Test
    fun deskew_estimatesSkewAngle() {
        // White page with dark text-like bars tilted by ~6 degrees.
        val w = 400
        val h = 500
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h) { android.graphics.Color.WHITE }
        val slope = kotlin.math.tan(Math.toRadians(6.0)).toFloat()
        for (row in 0 until 10) {
            val yBase = 40 + row * 42
            for (x in 20 until w - 20) {
                val yc = (yBase + slope * (x - w / 2)).toInt()
                for (dy in -2..2) {
                    val y = yc + dy
                    if (y in 0 until h) pixels[y * w + x] = android.graphics.Color.rgb(40, 40, 40)
                }
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        val angle = com.camscan.app.domain.processor.DeskewHelper.estimateSkewAngle(bmp)
        assertTrue("expected ~6deg skew, got $angle", !angle.isNaN() && kotlin.math.abs(angle) in 2.5f..9.5f)
        bmp.recycle()
    }

    @Test
    fun orderPoints_putsTopLeftFirst() {        val tl = PointF(100f, 120f)
        val tr = PointF(500f, 100f)
        val br = PointF(520f, 700f)
        val bl = PointF(80f, 680f)
        val ordered = QuadValidator.orderPoints(listOf(br, tl, bl, tr))
        assertEquals(tl.x, ordered.topLeft.x, 1f)
        assertEquals(tl.y, ordered.topLeft.y, 1f)
        assertEquals(tr.x, ordered.topRight.x, 1f)
        assertEquals(br.x, ordered.bottomRight.x, 1f)
        assertEquals(bl.x, ordered.bottomLeft.x, 1f)
    }
}
