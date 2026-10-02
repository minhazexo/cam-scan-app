package com.camscan.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.camscan.app.domain.processor.DocumentDetector
import com.camscan.app.domain.processor.OpenCvVision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the OpenCV-backed capture path. These run on a real
 * device/emulator so the bundled native library (libopencv_java4.so) actually
 * loads and executes — the Robolectric JVM suite intentionally stays on the
 * pure-Kotlin fallback.
 */
@RunWith(AndroidJUnit4::class)
class OpenCvVisionInstrumentedTest {

    private fun renderScene(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(38, 40, 46))
        val paper = Paint().apply { color = Color.WHITE }
        canvas.drawRect(w * 0.18f, h * 0.14f, w * 0.82f, h * 0.86f, paper)
        val ink = Paint().apply { color = Color.rgb(35, 35, 35) }
        var y = h * 0.22f
        while (y < h * 0.80f) {
            canvas.drawRect(w * 0.26f, y, w * 0.74f, y + h * 0.02f, ink)
            y += h * 0.07f
        }
        return bmp
    }

    @Test
    fun nativeLibraryLoads() {
        assertTrue(
            "OpenCV native library must load on device",
            OpenCvVision.isAvailable()
        )
    }

    @Test
    fun edgeExtractionProducesBoundary() {
        assertTrue(OpenCvVision.isAvailable())
        val scene = renderScene(480, 640)
        val edges = OpenCvVision.edges(scene, blockSize = 35, c = 8.0)
        assertNotNull("OpenCV edge extraction returned null", edges)
        val canny = edges!!.canny
        assertEquals(480 * 640, canny.size)
        var count = 0
        for (b in canny) if (b.toInt() != 0) count++
        assertTrue("expected Canny boundary pixels, got $count", count > 50)
    }

    @Test
    fun adaptiveEdgesReturnedBothPolarities() {
        assertTrue(OpenCvVision.isAvailable())
        val scene = renderScene(480, 640)
        val edges = OpenCvVision.edges(scene, blockSize = 35, c = 8.0)
        assertNotNull(edges)
        assertEquals(2, edges!!.adaptive.size)
        edges.adaptive.forEach { map ->
            assertEquals(480 * 640, map.size)
        }
    }

    @Test
    fun warpQuadProducesRequestedSize() {
        assertTrue(OpenCvVision.isAvailable())
        val scene = renderScene(480, 640)
        val srcPts = doubleArrayOf(
            480 * 0.18, 640 * 0.14,
            480 * 0.82, 640 * 0.14,
            480 * 0.82, 640 * 0.86,
            480 * 0.18, 640 * 0.86
        )
        val warped = OpenCvVision.warpQuad(scene, srcPts, outW = 300, outH = 400)
        assertNotNull("OpenCV warp returned null", warped)
        assertEquals(300, warped!!.width)
        assertEquals(400, warped.height)
        // The warped image must contain the page content: mostly bright paper
        // with some dark text lines. Pure white would mean a mis-mapped warp;
        // mostly dark would mean we captured the table instead of the page.
        var bright = 0
        var dark = 0
        val total = 300 * 400
        for (y in 0 until 400) {
            for (x in 0 until 300) {
                val c = warped.getPixel(x, y)
                if (Color.red(c) > 180 && Color.green(c) > 180) bright++
                if (Color.red(c) < 90 && Color.green(c) < 90) dark++
            }
        }
        assertTrue("expected mostly paper, bright=$bright/$total", bright > total * 0.5)
        assertTrue("expected some dark text lines, dark=$dark/$total", dark > total * 0.01)
    }

    @Test
    fun capturePathUsesOpenCvEdgeMaps() {
        assertTrue(OpenCvVision.isAvailable())
        val scene = renderScene(480, 640)
        DocumentDetector.detectDocument(scene, fast = false)
        assertTrue(
            "full detection should have used OpenCV edges",
            DocumentDetector.lastStats.openCvUsed
        )
    }

    @Test
    fun livePathStaysOnKotlinFallback() {
        val scene = renderScene(240, 320)
        DocumentDetector.detectDocument(scene, fast = true)
        assertTrue(
            "fast/live detection must not depend on OpenCV",
            !DocumentDetector.lastStats.openCvUsed
        )
    }
}
