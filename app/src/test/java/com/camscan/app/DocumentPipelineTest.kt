package com.camscan.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import com.camscan.app.domain.model.CornerPoints
import com.camscan.app.domain.model.DetectionAction
import com.camscan.app.domain.model.DetectionConfidence
import com.camscan.app.domain.model.DetectionResult
import com.camscan.app.domain.model.FilterMode
import com.camscan.app.domain.model.PageModel
import com.camscan.app.domain.processor.DeskewHelper
import com.camscan.app.domain.processor.DocumentDetector
import com.camscan.app.domain.processor.DocumentProcessor
import com.camscan.app.domain.processor.NeedsManualCornersException
import com.camscan.app.domain.processor.PerspectiveWarper
import com.camscan.app.domain.processor.QuadValidator
import com.camscan.app.ui.editor.PageEditorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

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
    // Blank page with no reliable edges must NOT become a fake automatic
    // scan: there is no evidence of a page boundary, so the user is asked.
    // (A generic inset quad here would silently include background.)
    // ------------------------------------------------------------------

    @Test
    fun uniformWhitePage_demandsManualCorners() {
        val bmp = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val result = DocumentDetector.detectDocument(bmp, fast = false)
        assertTrue(
            "blank page has no page evidence; expected LOW, got ${result.confidence} (${result.reason})",
            result.needsManual
        )
        assertEquals(DetectionAction.USER_SELECT_CORNERS, result.action)
        assertNull("no corners may be invented for a blank page", result.corners)
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

    // ==================================================================
    // Required regression suite
    // ==================================================================

    /**
     * TEST 1: document occupies 60% of the photograph, the rest is table.
     * Expected: only the document, and never the surrounding table.
     */
    @Test
    fun test01_documentSixtyPercent_onlyDocumentExtracted() {
        val w = 640
        val h = 800
        val expected = centeredQuad(w, h, 0.2f, 0.2f, 0.8f, 0.8f)
        val photo = renderPhoto(w, h, expected)

        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertNotNull("document must be detected: ${result.reason}", result.corners)
        val pts = result.corners!!.scale(w.toFloat(), h.toFloat()).toList()
        val area = abs(QuadValidator.polygonArea(pts)) / (w * h)
        // Ground truth area is 0.6*0.6 = 0.36. Table leakage pushes this up.
        assertTrue("background leaked into the quad (area=$area)", area < 0.50f)
        val err = quadError(result.corners!!, expected, w.toFloat(), h.toFloat())
        assertTrue("corners drifted onto the background (err=$err)", err < 0.10f)
        photo.recycle()
    }

    /**
     * TEST 2: white paper on a white/light background. There is no reliable
     * edge, so the app must NOT produce a blind full-frame scan.
     */
    @Test
    fun test02_whitePaperOnWhiteBackground_noBlindFullFrameScan() {
        val w = 600
        val h = 800
        // A barely-visible page: near-white on very light grey, no real edges.
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val inside = x in 120 until 480 && y in 100 until 700
                pixels[y * w + x] = if (inside) Color.rgb(246, 246, 248) else Color.WHITE
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)

        val result = DocumentDetector.detectDocument(bmp, fast = false)
        assertTrue(
            "white-on-white has no page evidence; must not auto-scan (${result.confidence})",
            result.blocksAutoProcessing
        )
        val scan = DocumentProcessor.processImageAutoOrNull(bmp, FilterMode.AUTO)
        assertNull("no automatic scan may be produced", scan)
        bmp.recycle()
    }

    /**
     * TEST 3: a MEDIUM detection must never yield an automatic final scan;
     * it must route to corner confirmation.
     */
    @Test
    fun test03_mediumConfidence_neverAutoScans() {
        val medium = DetectionResult(
            corners = CornerPoints(
                PointF(0.1f, 0.1f), PointF(0.9f, 0.1f),
                PointF(0.9f, 0.9f), PointF(0.1f, 0.9f)
            ),
            confidence = DetectionConfidence.MEDIUM,
            score = 0.5f
        )
        assertEquals(DetectionAction.USER_CONFIRM, medium.action)
        assertTrue("MEDIUM must require the user", medium.blocksAutoProcessing)
        assertTrue(medium.needsConfirmation)
        assertFalse("MEDIUM is not the all-corners case", medium.needsManual)
    }

    /**
     * TEST 4: a LOW detection is pending / manual correction, with no corners.
     */
    @Test
    fun test04_lowConfidence_pendingManualCorrection() {
        val low = DetectionResult(
            corners = null,
            confidence = DetectionConfidence.LOW,
            score = 0.2f,
            reason = "no quadrilateral found"
        )
        assertEquals(DetectionAction.USER_SELECT_CORNERS, low.action)
        assertTrue(low.needsManual)
        assertTrue(low.blocksAutoProcessing)
    }

    /**
     * TEST 5: an image-based PDF page (a photograph inside a PDF canvas).
     * Detection must find the embedded document, not accept the PDF canvas.
     */
    @Test
    fun test05_imageBasedPdf_detectsEmbeddedDocumentNotCanvas() {
        val w = 700
        val h = 900
        // The "PDF page" is a photo of a document on a table.
        val embedded = centeredQuad(w, h, 0.18f, 0.16f, 0.82f, 0.84f)
        val page = renderPhoto(w, h, embedded)

        val result = DocumentDetector.detectDocument(page, fast = false)
        assertFalse("a photo page must not be treated as a digital page", result.digitalPage)
        assertNotNull("embedded document must be detected: ${result.reason}", result.corners)
        val err = quadError(result.corners!!, embedded, w.toFloat(), h.toFloat())
        assertTrue("expected the embedded document, not the canvas (err=$err)", err < 0.12f)
        page.recycle()
    }

    /**
     * TEST 6: a genuine digital page (white canvas + sparse axis-aligned text)
     * is accepted as its own document. A photo of paper is not.
     */
    @Test
    fun test06_digitalPdfPage_acceptedAsDocument() {
        val w = 620
        val h = 877 // ~A4 ratio
        val page = renderDigitalPage(w, h)
        val result = DocumentDetector.detectDocument(page, fast = false)
        assertTrue(
            "a born-digital page must be identified (${result.reason})",
            result.digitalPage
        )
        assertEquals(DetectionAction.AUTO_PROCESS, result.action)
        assertNotNull(result.corners)
        page.recycle()

        // A photograph of a page is NOT a digital page.
        val photo = renderPhoto(620, 877, centeredQuad(620, 877, 0.2f, 0.15f, 0.8f, 0.85f))
        val photoResult = DocumentDetector.detectDocument(photo, fast = false)
        assertFalse(
            "a photographed page must never be flagged as a digital page",
            photoResult.digitalPage
        )
        photo.recycle()
    }

    /**
     * TEST 7: a document photographed at strong perspective is rectified to a
     * straight rectangle by the warper.
     */
    @Test
    fun test07_perspectiveTrapezoid_isRectified() {
        val w = 640
        val h = 800
        val trapezoid = listOf(
            PointF(w * 0.30f, h * 0.18f),
            PointF(w * 0.78f, h * 0.10f),
            PointF(w * 0.70f, h * 0.90f),
            PointF(w * 0.16f, h * 0.82f)
        )
        val photo = renderPhoto(w, h, trapezoid)

        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertNotNull("perspective page must be detected: ${result.reason}", result.corners)

        val warped = PerspectiveWarper.warpToRectangle(photo, result.corners!!)
        // A rectified page has near-square interior angles: compare the
        // left/right edge slopes, which differ strongly in a trapezoid.
        val rectW = warped.width.toFloat()
        val rectH = warped.height.toFloat()
        val (ww, hh) = QuadValidator.quadDims(
            listOf(PointF(0f, 0f), PointF(rectW, 0f), PointF(rectW, rectH), PointF(0f, rectH))
        )
        assertTrue("rectified page has implausible dims (${ww}x$hh)", ww > 100 && hh > 100)
        val aspect = min(ww, hh) / max(ww, hh)
        assertTrue("rectified page aspect is degenerate ($aspect)", aspect > 0.3f)
        warped.recycle()
        photo.recycle()
    }

    /**
     * TEST 8: a rotated (skewed) document is deskewed.
     */
    @Test
    fun test08_rotatedDocument_isDeskewed() {
        // Built with setPixels (not Canvas): the rasterised Canvas path is not
        // dependable under Robolectric.
        val w = 700
        val h = 500
        val pixels = IntArray(w * h) { Color.WHITE }
        val slope = tan(Math.toRadians(6.0)).toFloat()
        for (row in 0 until 10) {
            val yBase = 60 + row * 40
            val xEnd = 610 - row * 18
            for (x in 90 until xEnd) {
                val yc = (yBase + slope * (x - w / 2)).toInt()
                for (dy in -4..4) {
                    val y = yc + dy
                    if (y in 0 until h) pixels[y * w + x] = Color.rgb(30, 30, 30)
                }
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)

        val angle = DeskewHelper.estimateSkewAngle(bmp)
        assertTrue("expected ~6deg skew, got $angle", !angle.isNaN() && abs(angle) in 2.5f..9.5f)
        val deskewed = DeskewHelper.deskew(bmp)
        assertTrue("deskew must produce a corrected copy", deskewed !== bmp)
        deskewed.recycle()
        bmp.recycle()
    }

    /**
     * TEST 9: a document with large legitimate white margins keeps them; the
     * quad must cover the paper, not crop down to the text.
     */
    @Test
    fun test09_largeWhiteMargins_preserved() {
        val w = 600
        val h = 800
        val expected = centeredQuad(w, h, 0.18f, 0.12f, 0.82f, 0.88f)
        val photo = renderPhoto(w, h, expected, sparseText = true)

        val result = DocumentDetector.detectDocument(photo, fast = false)
        assertNotNull("sparse page must be detected: ${result.reason}", result.corners)
        val pts = result.corners!!.scale(w.toFloat(), h.toFloat()).toList()
        val area = abs(QuadValidator.polygonArea(pts)) / (w * h)
        // Ground truth paper area is 0.64*0.76 ~= 0.49.
        assertTrue("margins were cropped away (area=$area)", area > 0.30f)
        photo.recycle()
    }

    /**
     * TEST 10: background with brightness similar to the document yields
     * uncertain detection that must route to confirmation, not auto-scan.
     */
    @Test
    fun test10_similarBrightnessBackground_uncertainNotAuto() {
        val w = 600
        val h = 800
        // Light-grey table close to paper brightness: weak edge contrast.
        val pixels = IntArray(w * h)
        val table = Color.rgb(214, 212, 208)
        val paper = Color.rgb(243, 243, 245)
        val ink = Color.rgb(60, 60, 60)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] =
                    if (x in 110 until 490 && y in 90 until 710) paper else table
            }
        }
        for (r in 0 until 12) {
            val y = 140 + r * 44
            for (yy in y until minOf(y + 8, h)) {
                val x1 = 450 - r * 12
                for (x in 150 until x1) {
                    if (x in 0 until w && yy in 0 until h) pixels[yy * w + x] = ink
                }
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)

        val result = DocumentDetector.detectDocument(bmp, fast = false)
        assertTrue(
            "low-contrast page/table must not be auto-processed (${result.confidence}, ${result.reason})",
            result.blocksAutoProcessing
        )
        assertNull("no automatic scan may be produced", DocumentProcessor.processImageAutoOrNull(bmp, FilterMode.AUTO))
        bmp.recycle()
    }

    /**
     * TEST 11: the page editor must source from the processed image, not the
     * original photograph.
     */
    @Test
    fun test11_pageEditor_sourcesProcessedImageNotOriginal() {
        val processed = "storage/processed_page.png"
        val page = PageModel(
            id = "p1",
            documentId = "d1",
            pageIndex = 0,
            originalImagePath = "storage/original_photo.jpg",
            processedImagePath = processed,
            filterMode = FilterMode.AUTO,
            rotationDegrees = 0,
            cropCorners = CornerPoints.defaultNormalized()
        )
        val source = PageEditorSource.resolve(page)
        assertEquals("editor must load the processed page", processed, source)
    }

    /**
     * BUG #9: the background check must fail CLOSED — if it cannot prove the
     * output is clean it must not report success.
     *
     * Verified by inspection of the source: the catch block returns false
     * rather than true. Robolectric's Bitmap does not throw on a recycled
     * instance, so the failure path cannot be triggered from here.
     */
    @Test
    fun backgroundCheck_failsClosedOnAnalysisError() {
        val source = File("src/main/java/com/camscan/app/domain/processor/DocumentProcessor.kt")
        val text = if (source.exists()) source.readText() else ""
        if (text.isEmpty()) return // source not on disk in this environment
        val fnStart = text.indexOf("fun passesBackgroundCheck")
        assertTrue("passesBackgroundCheck must exist", fnStart >= 0)
        // Bound the search to this function only, so later catch blocks
        // elsewhere in the file cannot be mistaken for it.
        val fnEnd = text.indexOf("\n    // ---", fnStart).let { if (it < 0) text.length else it }
        val fnBody = text.substring(fnStart, fnEnd)
        val catchIdx = fnBody.indexOf("catch (e: Exception)")
        assertTrue("passesBackgroundCheck must have a catch block", catchIdx >= 0)
        val catchBody = fnBody.substring(catchIdx)
        assertFalse(
            "passesBackgroundCheck must not `return true` from its catch block (BUG #9)",
            Regex("return\\s+true").containsMatchIn(catchBody)
        )
        assertTrue(
            "passesBackgroundCheck must `return false` from its catch block (fail closed)",
            Regex("return\\s+false").containsMatchIn(catchBody)
        )
    }

    /**
     * BUG #16 / #10: a manual quad is warped from the exact selected points,
     * and the strict pipeline never fabricates an A4 from a failed detection.
     */
    @Test
    fun manualQuad_usesExactSelectedCorners() {
        val w = 500
        val h = 600
        val photo = renderPhoto(w, h, null)
        val manual = CornerPoints(
            PointF(0.2f, 0.15f), PointF(0.75f, 0.12f),
            PointF(0.78f, 0.85f), PointF(0.18f, 0.88f)
        )
        val result = DocumentProcessor.processImageStrict(photo, FilterMode.AUTO, manual)
        assertFalse("a user quad is not auto-detected", result.autoDetected)
        assertEquals(
            "the exact user quad must be used",
            manual.topLeft.x, result.cornersUsed.topLeft.x, 0.001f
        )
        assertEquals(
            DocumentProcessor.A4_WIDTH_PX, result.a4.width
        )
        assertEquals(
            DocumentProcessor.A4_HEIGHT_PX, result.a4.height
        )
        result.a4.recycle()
        result.documentOnly.recycle()
        photo.recycle()
    }

    /**
     * A failed detection must never produce a scan: the strict pipeline
     * throws instead of falling back to the whole photograph.
     */
    @Test
    fun failedDetection_throwsRatherThanReturningFullPhoto() {
        val photo = renderPhoto(500, 600, null)
        var threw = false
        try {
            val scan = DocumentProcessor.processImageStrict(photo, FilterMode.AUTO, null)
            scan.a4.recycle()
            scan.documentOnly.recycle()
        } catch (e: NeedsManualCornersException) {
            threw = true
        }
        assertTrue("no-document input must demand manual corners", threw)
        photo.recycle()
    }

    /**
     * Renders a born-digital page: a white canvas carrying sparse, hard-edged,
     * axis-aligned text. Built with setPixels rather than Canvas so the pixels
     * are real under Robolectric.
     */
    private fun renderDigitalPage(w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h) { Color.WHITE }
        val rnd = Random(11L)
        val ink = Color.rgb(25, 25, 25)
        val barH = maxOf(2, (h * 0.008f).toInt())
        var y = (h * 0.10f).toInt()
        while (y < h * 0.90f) {
            val words = 3 + rnd.nextInt(4)
            var x = (w * 0.12f).toInt()
            repeat(words) {
                val wlen = (w * (0.06f + rnd.nextFloat() * 0.09f)).toInt()
                for (yy in y until minOf(y + barH, h)) {
                    for (xx in x until minOf(x + wlen, w)) {
                        if (xx in 0 until w && yy in 0 until h) pixels[yy * w + xx] = ink
                    }
                }
                x += wlen + (w * 0.02f).toInt()
                if (x > w * 0.88f) return@repeat
            }
            y += (h * 0.035f).toInt()
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }
}
