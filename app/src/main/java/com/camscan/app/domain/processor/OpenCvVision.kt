package com.camscan.app.domain.processor

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min

/**
 * OpenCV-backed image operations for the full-quality CAPTURE path.
 *
 * Vision primitives (edge extraction, adaptive thresholding, perspective
 * warping) are delegated to OpenCV's native library when it is present, which
 * is faster and better tested than hand-rolled equivalents. Everything here is
 * *optional*:
 *
 *  - [isAvailable] performs a one-time, defensive native-library load. On the
 *    JVM / Robolectric the native library cannot load, so it returns false and
 *    every caller falls back to the pure-Kotlin engine. The regression suite
 *    therefore keeps exercising the Kotlin implementation.
 *  - Every public operation returns `null` on any failure (missing native lib,
 *    unexpected Mat state, OOM, …) so a broken OpenCV install can never break
 *    capture; the Kotlin path takes over.
 *
 * This class is the ONLY place in the app that references `org.opencv`.
 */
object OpenCvVision {

    private const val STATE_UNKNOWN = 0
    private const val STATE_AVAILABLE = 1
    private const val STATE_UNAVAILABLE = 2

    @Volatile
    private var state = STATE_UNKNOWN

    /** True once the bundled native library (libopencv_java4.so) is loaded. */
    fun isAvailable(): Boolean {
        if (state == STATE_AVAILABLE) return true
        if (state == STATE_UNAVAILABLE) return false
        synchronized(this) {
            if (state == STATE_UNKNOWN) {
                state = if (initNative()) STATE_AVAILABLE else STATE_UNAVAILABLE
            }
        }
        return state == STATE_AVAILABLE
    }

    private fun initNative(): Boolean {
        return try {
            // 4.9+ ships the native library inside the AAR; initLocal loads it
            // without the (retired) OpenCV Manager.
            try {
                OpenCVLoader.initLocal()
            } catch (t: Throwable) {
                System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
                true
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Edge maps produced by OpenCV for one image.
     *
     * @param canny strong Canny edges after a morphological close (values 0/1).
     * @param adaptive boundary maps from adaptive thresholding, bright and
     *   dark polarity (values 0/1 each).
     */
    class CvEdges(
        val canny: ByteArray,
        val adaptive: List<ByteArray>
    )

    /**
     * Runs OpenCV's capture-path edge extraction on [bitmap].
     *
     * @return null when OpenCV is unavailable or the operation fails.
     */
    fun edges(bitmap: Bitmap, blockSize: Int, c: Double): CvEdges? {
        if (!isAvailable()) return null
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        var rgba: Mat? = null
        var gray: Mat? = null
        var blurred: Mat? = null
        var edges: Mat? = null
        val adaptiveHolders = mutableListOf<Mat>()
        try {
            rgba = Mat()
            Utils.bitmapToMat(bitmap, rgba)
            if (rgba.empty()) return null

            gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

            blurred = Mat()
            val k = (blockSize.coerceAtLeast(3) or 1) // odd
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

            val w = blurred.cols()
            val h = blurred.rows()
            val size = w * h

            // Thresholds derived from the median brightness, same philosophy as
            // the Kotlin engine so results are comparable.
            val grayBytes = ByteArray(size)
            blurred.get(0, 0, grayBytes)
            val sorted = grayBytes.clone()
            Arrays.sort(sorted)
            val median = (sorted[size / 2].toInt() and 0xFF).toDouble()
            val lower = max(10.0, (1.0 - 0.33) * median * 0.5)
            val upper = min(255.0, (1.0 + 0.33) * median)

            edges = Mat()
            Imgproc.Canny(blurred, edges, lower, upper)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.morphologyEx(
                edges, edges, Imgproc.MORPH_CLOSE, kernel,
                Point(-1.0, -1.0), 2
            )
            val canny = matToBinary(edges, size)

            val adaptive = mutableListOf<ByteArray>()
            for (type in intArrayOf(Imgproc.THRESH_BINARY, Imgproc.THRESH_BINARY_INV)) {
                val bin = Mat()
                adaptiveHolders.add(bin)
                Imgproc.adaptiveThreshold(
                    blurred, bin, 255.0,
                    Imgproc.ADAPTIVE_THRESH_MEAN_C, type,
                    (blockSize.coerceAtLeast(3) or 1), c
                )
                val dil = Mat()
                val ero = Mat()
                adaptiveHolders.add(dil)
                adaptiveHolders.add(ero)
                val morphKernel =
                    Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                Imgproc.dilate(bin, dil, morphKernel)
                Imgproc.erode(bin, ero, morphKernel)
                val grad = Mat()
                adaptiveHolders.add(grad)
                Core.absdiff(dil, ero, grad)
                Imgproc.threshold(grad, grad, 0.5, 255.0, Imgproc.THRESH_BINARY)
                adaptive.add(matToBinary(grad, size))
            }

            return CvEdges(canny, adaptive)
        } catch (t: Throwable) {
            return null
        } finally {
            rgba?.release()
            gray?.release()
            blurred?.release()
            edges?.release()
            adaptiveHolders.forEach { it.release() }
        }
    }

    private fun matToBinary(src: Mat, size: Int): ByteArray {
        val raw = ByteArray(size)
        src.get(0, 0, raw)
        val out = ByteArray(size)
        for (i in 0 until size) if (raw[i].toInt() != 0) out[i] = 1
        return out
    }

    /**
     * Perspective-warp [srcBitmap] so the quad at [srcPts] (8 doubles, order
     * TL, TR, BR, BL in source pixels) fills an [outW] x [outH] bitmap.
     * Outside areas are filled white, matching the Kotlin warper.
     *
     * @return null when OpenCV is unavailable or the operation fails, so the
     *   caller can fall back to the DLT warper.
     */
    fun warpQuad(
        srcBitmap: Bitmap,
        srcPts: DoubleArray,
        outW: Int,
        outH: Int
    ): Bitmap? {
        if (!isAvailable()) return null
        if (srcPts.size != 8 || outW < 2 || outH < 2) return null
        var rgba: Mat? = null
        var dst: Mat? = null
        try {
            rgba = Mat()
            Utils.bitmapToMat(srcBitmap, rgba)
            if (rgba.empty()) return null

            val src = MatOfPoint2f(
                Point(srcPts[0], srcPts[1]),
                Point(srcPts[2], srcPts[3]),
                Point(srcPts[4], srcPts[5]),
                Point(srcPts[6], srcPts[7])
            )
            val dstPts = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(outW.toDouble(), 0.0),
                Point(outW.toDouble(), outH.toDouble()),
                Point(0.0, outH.toDouble())
            )
            val h = Imgproc.getPerspectiveTransform(src, dstPts)
            dst = Mat()
            Imgproc.warpPerspective(
                rgba, dst, h, Size(outW.toDouble(), outH.toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT,
                Scalar(255.0, 255.0, 255.0, 255.0)
            )
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dst, out)
            return out
        } catch (t: Throwable) {
            return null
        } finally {
            rgba?.release()
            dst?.release()
        }
    }
}
