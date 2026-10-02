package com.camscan.app.domain.processor

import android.graphics.Bitmap
import android.graphics.PointF
import com.camscan.app.domain.model.CornerPoints
import com.camscan.app.domain.model.DetectionConfidence
import com.camscan.app.domain.model.DetectionResult
import com.camscan.app.domain.model.ScoredQuad
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Robust document boundary detector.
 *
 * Pipeline (all dependency-free, pure Kotlin):
 *  grayscale -> Gaussian blur -> Canny edges -> morphology ->
 *  connected-component contours -> convex hull -> Douglas-Peucker
 *  polygon approximation -> Hough line intersections ->
 *  multi-candidate scoring -> quadrilateral validation ->
 *  camera-frame exclusion -> confidence rating.
 *
 * Absolute rules enforced here:
 *  - The image border (0,0)-(W,H) is treated as the CAMERA FRAME and is
 *    never returned unless the border region is provably uniform (clean
 *    digital page / flatbed-like input) which counts as the required
 *    "very strong evidence".
 *  - LOW confidence returns corners == null so callers MUST route to the
 *    manual 4-corner editor. There is no full-photo fallback.
 */
object DocumentDetector {

    /** Outcome counters from the most recent [detectDocument] call (debug). */
    @Volatile
    var lastStats: DetectionStats = DetectionStats()
        private set

    data class DetectionStats(
        var edgePixels: Int = 0,
        var components: Int = 0,
        var bigComponents: Int = 0,
        var approxFour: Int = 0,
        var validatedQuads: Int = 0,
        var houghLines: Int = 0,
        var houghQuads: Int = 0,
        var houghLineDesc: List<String> = emptyList()
    )

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Full detection with confidence. This is the ONLY entry point the
     * processing pipeline uses to obtain automatic corners.
     */
    fun detectDocument(bitmap: Bitmap, fast: Boolean = false): DetectionResult {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 50 || height < 50) {
            return DetectionResult(null, DetectionConfidence.LOW, 0f, emptyList(), "image too small")
        }

        val maxDim = if (fast) 320 else 600
        val scale = min(1f, maxDim.toFloat() / max(width, height))
        val sampleW = max(80, (width * scale).toInt())
        val sampleH = max(80, (height * scale).toInt())

        val small = if (sampleW != width || sampleH != height) {
            Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, true)
        } else bitmap

        try {
            val pixels = IntArray(sampleW * sampleH)
            small.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)

            val gray = toGrayscale(pixels)
            val blurred = gaussianBlur5x5(gray, sampleW, sampleH)

            // Adaptive Canny thresholds from median brightness.
            val median = medianOf(blurred)
            val sigma = 0.33f
            var high = ((1f + sigma) * median).toInt().coerceIn(40, 160)
            var low = ((1f - sigma) * median * 0.5f).toInt().coerceIn(15, 80)
            if (fast) {
                high = high.coerceAtMost(120)
            }

            var edges = canny(blurred, sampleW, sampleH, low.toFloat(), high.toFloat()).first
            if (countNonZero(edges) < sampleW * sampleH * 0.002f) {
                // Retry with more sensitive thresholds once.
                edges = canny(blurred, sampleW, sampleH, (low * 0.5f).coerceAtLeast(10f), (high * 0.7f).coerceAtLeast(30f)).first
            }

            val dilated = dilate3x3(edges, sampleW, sampleH)
            // Wide support mask (effective 5x5): Hough quantisation shifts
            // lines by a few px; support must tolerate that while polarity
            // (regional, below) provides the precision.
            val dilatedWide = dilate3x3(dilated, sampleW, sampleH)
            val closed = closeGaps(edges, sampleW, sampleH)

            val stats = DetectionStats(edgePixels = countNonZero(edges))
            // Gradient magnitudes weight the Hough votes so strong page steps
            // outvote weak texture lines even when texture is dense.
            val magnitudes = FloatArray(sampleW * sampleH)
            gradientMagnitude(blurred, sampleW, sampleH, magnitudes)
            val candidates = mutableListOf<ScoredQuad>()
            candidates += contourCandidates(closed, dilatedWide, blurred, sampleW, sampleH, stats)
            if (!fast) {
                candidates += houghCandidates(edges, magnitudes, dilatedWide, blurred, sampleW, sampleH, stats)
            }
            lastStats = stats

            val ranked = candidates
                .filter { it.areaFraction in 0.02f..0.985f }
                .sortedByDescending { it.score }
                .take(12)

            if (ranked.isEmpty()) {
                // Clean-page exception: uniform border means the sensor frame
                // plausibly IS the page (digital PDF render / flatbed input).
                val uniform = isUniformBorder(gray, sampleW, sampleH)
                if (uniform) {
                    val inset = CornerPoints(
                        PointF(0.015f, 0.015f), PointF(0.985f, 0.015f),
                        PointF(0.985f, 0.985f), PointF(0.015f, 0.985f)
                    )
                    return DetectionResult(
                        inset, DetectionConfidence.MEDIUM, 0.5f, emptyList(),
                        "uniform page background; confirm corners"
                    )
                }
                return DetectionResult(null, DetectionConfidence.LOW, 0f, emptyList(), "no quadrilateral found; manual corners required")
            }

            // Camera-frame exclusion: drop full-frame / near-frame quads with
            // weak support. Near-frame candidates (>= 90% of the image touching
            // the frame or spanning > 97% on both axes) are rejected unless they
            // have exceptionally strong edge evidence.
            val viable = ranked.filterNot { q ->
                val cp = q.toCornerPoints(sampleW.toFloat(), sampleH.toFloat())
                    .let { denorm(it, sampleW.toFloat(), sampleH.toFloat()) }
                (QuadValidator.isFullFrame(cp, sampleW.toFloat(), sampleH.toFloat()) ||
                    QuadValidator.isNearFrame(cp, sampleW.toFloat(), sampleH.toFloat())) &&
                    q.edgeSupportScore < 0.75f
            }

            val pool = if (viable.isNotEmpty()) viable else {
                // Only full-frame / near-frame candidates existed without strong
                // support. If the border strips are provably uniform light (clean
                // digital page / flatbed-like input) the frame is likely the real
                // page — accept it with a confirmation hint.
                val cp = ranked.first().toCornerPoints(sampleW.toFloat(), sampleH.toFloat())
                    .let { denorm(it, sampleW.toFloat(), sampleH.toFloat()) }
                if (isUniformBorder(gray, sampleW, sampleH) &&
                    (QuadValidator.isFullFrame(cp, sampleW.toFloat(), sampleH.toFloat()) ||
                        QuadValidator.isNearFrame(cp, sampleW.toFloat(), sampleH.toFloat()))) {
                    return DetectionResult(cp, DetectionConfidence.MEDIUM, ranked.first().score, ranked,
                        "near/full frame on uniform background; confirm corners")
                }
                return DetectionResult(
                    null, DetectionConfidence.LOW, ranked.first().score, ranked,
                    "only the camera frame was found; manual corners required"
                )
            }

            val best = pool.first()
            var corners = denorm(best.toCornerPoints(sampleW.toFloat(), sampleH.toFloat()), sampleW.toFloat(), sampleH.toFloat())
            // Clamp inside sample bounds.
            corners = clampToImage(corners, sampleW.toFloat(), sampleH.toFloat())

            val validation = QuadValidator.validate(corners, sampleW.toFloat(), sampleH.toFloat())
            if (!validation.valid) {
                return DetectionResult(null, DetectionConfidence.LOW, best.score, ranked, "invalid quad (${validation.reason}); manual corners required")
            }

            val normalized = corners.normalize(sampleW.toFloat(), sampleH.toFloat())
            val confidence = rateConfidence(best, pool)
            val reason = when (confidence) {
                DetectionConfidence.HIGH -> "document detected (score ${"%.2f".format(best.score)})"
                DetectionConfidence.MEDIUM -> "uncertain detection (score ${"%.2f".format(best.score)}); confirm corners"
                DetectionConfidence.LOW -> "weak detection (score ${"%.2f".format(best.score)}); manual corners required"
            }
            return if (confidence == DetectionConfidence.LOW) {
                DetectionResult(null, confidence, best.score, ranked, reason)
            } else {
                DetectionResult(normalized, confidence, best.score, ranked, reason)
            }
        } finally {
            if (small != bitmap) small.recycle()
        }
    }

    /**
     * Legacy shim for live overlay / callers that only need points.
     * Returns the best-effort quad or a visibly-inset default (never raw
     * full-frame) so the overlay never implies "whole photo is the page".
     */
    fun detectCorners(bitmap: Bitmap): CornerPoints {
        val result = try {
            detectDocument(bitmap, fast = true)
        } catch (e: Exception) {
            null
        }
        if (result != null && result.corners != null) return result.corners
        // Inset default: clearly inside the frame, signalling "unconfirmed".
        return CornerPoints(
            PointF(0.12f, 0.12f), PointF(0.88f, 0.12f),
            PointF(0.88f, 0.88f), PointF(0.12f, 0.88f)
        )
    }

    fun orderPoints(pts: List<PointF>): CornerPoints = QuadValidator.orderPoints(pts)

    // ------------------------------------------------------------------
    // Stage 1: grayscale + blur
    // ------------------------------------------------------------------

    private fun toGrayscale(pixels: IntArray): FloatArray {
        val out = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            out[i] = (0.299f * r + 0.587f * g + 0.114f * b)
        }
        return out
    }

    private fun gaussianBlur5x5(src: FloatArray, w: Int, h: Int): FloatArray {
        // Separable 5x5 gaussian [1 4 6 4 1]/16.
        val k = floatArrayOf(1f, 4f, 6f, 4f, 1f)
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                var wn = 0f
                for (i in -2..2) {
                    val xx = (x + i).coerceIn(0, w - 1)
                    s += src[y * w + xx] * k[i + 2]
                    wn += k[i + 2]
                }
                tmp[y * w + x] = s / wn
            }
        }
        val dst = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                var wn = 0f
                for (i in -2..2) {
                    val yy = (y + i).coerceIn(0, h - 1)
                    s += tmp[yy * w + x] * k[i + 2]
                    wn += k[i + 2]
                }
                dst[y * w + x] = s / wn
            }
        }
        return dst
    }

    private fun medianOf(a: FloatArray): Float {
        val copy = a.clone()
        copy.sort()
        return copy[copy.size / 2]
    }

    // ------------------------------------------------------------------
    // Stage 2: Canny edge detection
    // ------------------------------------------------------------------

    private fun canny(gray: FloatArray, w: Int, h: Int, low: Float, high: Float): Pair<ByteArray, FloatArray> {
        val gx = FloatArray(w * h)
        val gy = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                gx[i] = (-gray[i - w - 1] - 2 * gray[i - 1] - gray[i + w - 1] +
                    gray[i - w + 1] + 2 * gray[i + 1] + gray[i + w + 1])
                gy[i] = (-gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1] +
                    gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1])
            }
        }
        val mag = FloatArray(w * h)
        val dir = ByteArray(w * h) // 0,1,2,3 -> 0,45,90,135
        for (i in mag.indices) {
            mag[i] = sqrt(gx[i] * gx[i] + gy[i] * gy[i])
            val ang = Math.toDegrees(kotlin.math.atan2(gy[i].toDouble(), gx[i].toDouble()))
            val a = ((ang + 180) % 180)
            dir[i] = when {
                a < 22.5 || a >= 157.5 -> 0
                a < 67.5 -> 1
                a < 112.5 -> 2
                else -> 3
            }.toByte()
        }
        // Non-maximum suppression.
        val nms = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val m = mag[i]
                val (n1, n2) = when (dir[i].toInt()) {
                    0 -> Pair(mag[i - 1], mag[i + 1])
                    1 -> Pair(mag[i - w + 1], mag[i + w - 1])
                    2 -> Pair(mag[i - w], mag[i + w])
                    else -> Pair(mag[i - w - 1], mag[i + w + 1])
                }
                nms[i] = if (m >= n1 && m >= n2) m else 0f
            }
        }
        // Double threshold + hysteresis.
        val STRONG: Byte = 2
        val WEAK: Byte = 1
        val state = ByteArray(w * h)
        val stack = ArrayDeque<Int>()
        for (i in nms.indices) {
            when {
                nms[i] >= high -> {
                    state[i] = STRONG
                    stack.add(i)
                }
                nms[i] >= low -> state[i] = WEAK
            }
        }
        val out = ByteArray(w * h)
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            out[i] = 1.toByte()
            val x = i % w
            val y = i / w
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) {
                        val ni = ny * w + nx
                        if (state[ni] == WEAK) {
                            state[ni] = STRONG
                            stack.add(ni)
                        }
                    }
                }
            }
        }
        return Pair(out, mag)
    }

    /** Raw Sobel gradient magnitude (for magnitude-weighted Hough voting). */
    private fun gradientMagnitude(gray: FloatArray, w: Int, h: Int, out: FloatArray) {
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = (-gray[i - w - 1] - 2 * gray[i - 1] - gray[i + w - 1] +
                    gray[i - w + 1] + 2 * gray[i + 1] + gray[i + w + 1])
                val gy = (-gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1] +
                    gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1])
                out[i] = sqrt(gx * gx + gy * gy)
            }
        }
    }

    private fun countNonZero(a: ByteArray): Int {
        var c = 0
        for (b in a) if (b != 0.toByte()) c++
        return c
    }

    private fun dilate3x3(src: ByteArray, w: Int, h: Int): ByteArray {
        val dst = ByteArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v: Byte = 0
                loop@ for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h && src[ny * w + nx] != 0.toByte()) {
                            v = 1
                            break@loop
                        }
                    }
                }
                dst[y * w + x] = v
            }
        }
        return dst
    }

    /**
     * Morphological CLOSE on the thin edge map to bridge small gaps.
     *
     * Correct form is dilate -> erode (a true close), applied twice so gaps of
     * roughly 5px are bridged. The previous erode(dilate(dilate(x))) was a net
     * dilation that merged text and shadows into the page border and pushed
     * the convex hull outward, inflating the document quad.
     */
    private fun closeGaps(src: ByteArray, w: Int, h: Int): ByteArray {
        var cur = dilate3x3(dilate3x3(src, w, h), w, h)
        cur = erode3x3(cur, w, h)
        cur = dilate3x3(dilate3x3(cur, w, h), w, h)
        return erode3x3(cur, w, h)
    }

    private fun erode3x3(src: ByteArray, w: Int, h: Int): ByteArray {
        val dst = ByteArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v: Byte = 1
                loop@ for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (!(nx in 0 until w && ny in 0 until h && src[ny * w + nx] != 0.toByte())) {
                            v = 0
                            break@loop
                        }
                    }
                }
                dst[y * w + x] = v
            }
        }
        return dst
    }

    // ------------------------------------------------------------------
    // Stage 3: connected components -> hull -> poly approx candidates
    // ------------------------------------------------------------------

    private fun contourCandidates(
        edges: ByteArray, dilated: ByteArray, gray: FloatArray, w: Int, h: Int, stats: DetectionStats?
    ): List<ScoredQuad> {
        val labels = IntArray(w * h) { -1 }
        var compCount = 0
        val out = mutableListOf<ScoredQuad>()
        val imgArea = w.toFloat() * h

        for (seed in edges.indices) {
            if (edges[seed] == 0.toByte() || labels[seed] != -1) continue
            // BFS this component (cap size to bound work).
            val pts = mutableListOf<PointF>()
            val stack = ArrayDeque<Int>()
            stack.add(seed)
            labels[seed] = compCount
            var guard = 0
            while (stack.isNotEmpty() && guard < 60000) {
                guard++
                val i = stack.removeLast()
                val x = i % w
                val y = i / w
                pts.add(PointF(x.toFloat(), y.toFloat()))
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h) {
                            val ni = ny * w + nx
                            if (edges[ni] != 0.toByte() && labels[ni] == -1) {
                                labels[ni] = compCount
                                stack.add(ni)
                            }
                        }
                    }
                }
            }
            compCount++
            stats?.components = (stats?.components ?: 0) + 1
            if (pts.size < 120) continue
            stats?.bigComponents = (stats?.bigComponents ?: 0) + 1

            // Bounding pre-filter: must cover a plausible page extent.
            var minX = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var minY = Float.MAX_VALUE
            var maxY = Float.MIN_VALUE
            for (p in pts) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
            val bbArea = (maxX - minX) * (maxY - minY)
            if (bbArea < imgArea * 0.02f) continue

            val hull = try {
                QuadValidator.convexHull(pts)
            } catch (e: Exception) {
                continue
            }
            if (hull.size < 4) continue
            val hullArea = abs(QuadValidator.polygonArea(hull))
            if (hullArea < imgArea * 0.02f) continue
            val peri = QuadValidator.perimeter(hull)

            // Try a fine epsilon ladder for the approximation: small epsilons
            // keep exact corners on clean pages, large epsilons smooth over
            // bumps where objects touch the page boundary.
            for (epsF in floatArrayOf(0.008f, 0.013f, 0.019f, 0.027f, 0.040f, 0.058f, 0.080f)) {
                val approx = QuadValidator.approxPolyDP(hull, (epsF * peri).coerceAtLeast(2f))
                if (approx.size != 4) continue
                stats?.approxFour = (stats?.approxFour ?: 0) + 1
                val ordered = QuadValidator.orderPoints(approx)
                val quad = ordered.toList()
                val qArea = abs(QuadValidator.polygonArea(quad))
                if (qArea < imgArea * 0.02f || qArea > imgArea * 0.985f) continue
                val v = QuadValidator.validate(denorm(ordered, 1f, 1f).let { ordered }, w.toFloat(), h.toFloat())
                if (!v.valid) continue
                stats?.validatedQuads = (stats?.validatedQuads ?: 0) + 1
                val scored = scoreQuad(quad, hullArea, dilated, gray, w, h)
                out.add(scored)
                break // one quad per component to avoid duplicates
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Stage 4: Hough line intersections (handles broken/tilted borders)
    // ------------------------------------------------------------------

    private data class HoughLine(val rho: Float, val thetaDeg: Float, val votes: Float)

    private fun houghCandidates(
        edges: ByteArray, magnitudes: FloatArray, dilated: ByteArray, gray: FloatArray, w: Int, h: Int, stats: DetectionStats?
    ): List<ScoredQuad> {
        val out = mutableListOf<ScoredQuad>()
        try {
            val thetaStep = 2 // degrees
            val numTheta = 180 / thetaStep
            val maxRho = hypot(w.toDouble(), h.toDouble()).toFloat()
            val rhoStep = 2f
            val numRho = (2 * maxRho / rhoStep).toInt().coerceAtLeast(1)
            val acc = FloatArray(numRho * numTheta)
            val cosT = FloatArray(numTheta)
            val sinT = FloatArray(numTheta)
            for (t in 0 until numTheta) {
                val rad = Math.toRadians((t * thetaStep).toDouble())
                cosT[t] = kotlin.math.cos(rad).toFloat()
                sinT[t] = kotlin.math.sin(rad).toFloat()
            }
            var edgeCount = 0
            for (y in 0 until h step 1) {
                for (x in 0 until w step 1) {
                    val idx = y * w + x
                    if (edges[idx] == 0.toByte()) continue
                    edgeCount++
                    // Magnitude-weighted vote: strong page steps outvote
                    // weak texture lines even when texture is dense.
                    val weight = magnitudes[idx].coerceAtLeast(1f)
                    for (t in 0 until numTheta) {
                        val rho = x * cosT[t] + y * sinT[t]
                        val rIdx = ((rho + maxRho) / rhoStep).toInt().coerceIn(0, numRho - 1)
                        acc[rIdx * numTheta + t] += weight
                    }
                }
            }
            if (edgeCount < 200) return out

            // Peak picking with local NMS.
            data class Peak(val r: Int, val t: Int, val v: Float)
            val peaks = mutableListOf<Peak>()
            var maxV = 0f
            for (v in acc) if (v > maxV) maxV = v
            val voteFloor = max(maxV * 0.30f, 8000f)
            for (r in 1 until numRho - 1) {
                for (t in 0 until numTheta) {
                    val v = acc[r * numTheta + t]
                    if (v < voteFloor) continue
                    var isMax = true
                    loop@ for (dr in -2..2) {
                        for (dt in -1..1) {
                            var tt = t + dt
                            if (tt < 0 || tt >= numTheta) continue
                            val vv = acc[(r + dr) * numTheta + tt]
                            if (vv > v) {
                                isMax = false
                                break@loop
                            }
                        }
                    }
                    if (isMax) peaks.add(Peak(r, t, v))
                }
            }
            // Orientation diversity: cap lines per 10-degree theta band so a
            // single texture direction cannot monopolise the line set.
            peaks.sortByDescending { it.v }
            val perBand = mutableMapOf<Int, Int>()
            val diverse = mutableListOf<Peak>()
            for (p in peaks) {
                val band = (p.t * thetaStep) / 10
                val used = perBand[band] ?: 0
                if (used < 5) {
                    diverse.add(p)
                    perBand[band] = used + 1
                }
                if (diverse.size >= 48) break
            }
            val lines = diverse.map {
                HoughLine(
                    rho = it.r * rhoStep - maxRho,
                    thetaDeg = (it.t * thetaStep).toFloat(),
                    votes = it.v
                )
            }
            stats?.houghLines = lines.size
            stats?.houghLineDesc = lines.take(14).map {
                "rho=${it.rho.toInt()} th=${it.thetaDeg.toInt()} v=${it.votes.toInt()}"
            }
            if (lines.size < 4) return out

            fun isHorizontal(l: HoughLine): Boolean {
                val t = l.thetaDeg
                return t < 28 || t > 152
            }
            fun isVertical(l: HoughLine): Boolean {
                val t = l.thetaDeg
                return t in 62f..118f
            }
            val horizontals = lines.filter(::isHorizontal).take(10)
            val verticals = lines.filter(::isVertical).take(10)
            if (horizontals.size < 2 || verticals.size < 2) return out

            fun intersect(a: HoughLine, b: HoughLine): PointF? {
                val t1 = Math.toRadians(a.thetaDeg.toDouble())
                val t2 = Math.toRadians(b.thetaDeg.toDouble())
                val c1 = kotlin.math.cos(t1)
                val s1 = kotlin.math.sin(t1)
                val c2 = kotlin.math.cos(t2)
                val s2 = kotlin.math.sin(t2)
                val det = c1 * s2 - s1 * c2
                if (abs(det) < 1e-6) return null
                val x = (a.rho * s2 - s1 * b.rho) / det
                val y = (c1 * b.rho - a.rho * c2) / det
                return PointF(x.toFloat(), y.toFloat())
            }

            val imgArea = w.toFloat() * h
            val found = mutableListOf<ScoredQuad>()
            for (i in horizontals.indices) {
                for (j in i + 1 until horizontals.size) {
                    for (k in verticals.indices) {
                        for (m in k + 1 until verticals.size) {
                            // Order: top/bottom unknown; compute all four and order.
                            val p1 = intersect(horizontals[i], verticals[k]) ?: continue
                            val p2 = intersect(horizontals[i], verticals[m]) ?: continue
                            val p3 = intersect(horizontals[j], verticals[k]) ?: continue
                            val p4 = intersect(horizontals[j], verticals[m]) ?: continue
                            val all = listOf(p1, p2, p3, p4)
                            // Must lie (mostly) inside the image.
                            var inside = 0
                            for (p in all) {
                                if (p.x in -w * 0.05f..w * 1.05f && p.y in -h * 0.05f..h * 1.05f) inside++
                            }
                            if (inside < 4) continue
                            val ordered = QuadValidator.orderPoints(all)
                            val quad = ordered.toList()
                            val qArea = abs(QuadValidator.polygonArea(quad))
                            if (qArea < imgArea * 0.03f || qArea > imgArea * 0.985f) continue
                            if (!QuadValidator.validate(ordered, w.toFloat(), h.toFloat()).valid) continue
                            val scored = scoreQuad(quad, qArea * 0.92f, dilated, gray, w, h)
                            found.add(scored)
                        }
                    }
                }
            }
            // Keep the strongest combos across the whole set so the true
            // page is never cut off by an early iteration cap.
            found.sortByDescending { it.score }
            val kept = found.take(40)
            out.addAll(kept)
            stats?.houghQuads = (stats?.houghQuads ?: 0) + kept.size
        } catch (e: Exception) {
            // Hough is best-effort; contour path already ran.
        }
        return out
    }

    // ------------------------------------------------------------------
    // Stage 5: scoring + confidence
    // ------------------------------------------------------------------

    private fun scoreQuad(
        quad: List<PointF>, hullArea: Float, dilated: ByteArray, gray: FloatArray, w: Int, h: Int
    ): ScoredQuad {
        val imgArea = w.toFloat() * h
        val qArea = abs(QuadValidator.polygonArea(quad))
        val areaFrac = (qArea / imgArea).coerceIn(0f, 1f)

        val areaScore = when {
            areaFrac < 0.04f -> (areaFrac / 0.04f).coerceIn(0f, 1f) * 0.4f
            areaFrac <= 0.22f -> 0.4f + 0.6f * ((areaFrac - 0.04f) / 0.18f)
            areaFrac <= 0.85f -> 1f
            areaFrac <= 0.94f -> 1f - (areaFrac - 0.85f) / 0.09f * 0.35f
            else -> (0.65f - (areaFrac - 0.94f) / 0.06f * 0.65f).coerceAtLeast(0f)
        }

        val rectangularity = if (qArea > 1f) {
            (min(hullArea, qArea) / max(hullArea, qArea)).coerceIn(0f, 1f)
        } else 0f

        val edgeSupport = QuadValidator.edgeSupport(quad, dilated, w, h)

        // Interior angles vs 90 deg.
        var angleDev = 0f
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            val c = quad[(i + 2) % 4]
            val ang = interiorAngle(a, b, c)
            angleDev += abs(90.0 - ang).toFloat()
        }
        angleDev /= 4f
        val angleScore = (1f - angleDev / 35f).coerceIn(0f, 1f)

        // Aspect: prefer page-like ratios but do not kill other docs.
        val (qw, qh) = QuadValidator.quadDims(quad)
        val portraitAr = (min(qw, qh) / max(qw, qh).coerceAtLeast(1f)).coerceIn(0.01f, 1f)
        val distA4 = min(abs(portraitAr - 0.707f), abs(portraitAr - 0.65f))
        val aspectScore = (0.35f + 0.65f * (1f - distA4 / 0.45f).coerceIn(0f, 1f))

        // Border margin: penalise sides hugging the sensor edge.
        val m = 0.02f * min(w, h)
        var touched = 0
        if (quad.any { it.x < m }) touched++
        if (quad.any { it.x > w - m }) touched++
        if (quad.any { it.y < m }) touched++
        if (quad.any { it.y > h - m }) touched++
        var borderScore = 1f - touched * 0.22f
        if (touched >= 3 && edgeSupport > 0.7f) borderScore = 0.6f // strong evidence override
        borderScore = borderScore.coerceIn(0f, 1f)

        // Photometric boundary evidence: paper-vs-scene step straddling the
        // edge. Text lines and texture quads score ~0 here.
        val polarity = QuadValidator.boundaryPolarity(quad, gray, w, h)

        val total = (0.16f * areaScore + 0.10f * rectangularity + 0.24f * edgeSupport +
            0.12f * angleScore + 0.08f * aspectScore + 0.08f * borderScore +
            0.22f * polarity)
            .coerceIn(0f, 1f)

        return ScoredQuad(
            quad[0], quad[1], quad[2], quad[3],
            score = total,
            areaScore = areaScore,
            rectangularityScore = rectangularity,
            edgeSupportScore = edgeSupport,
            angleScore = angleScore,
            aspectScore = aspectScore,
            borderScore = borderScore,
            polarityScore = polarity,
            areaFraction = areaFrac
        )
    }

    private fun interiorAngle(a: PointF, b: PointF, c: PointF): Double {
        val v1x = (a.x - b.x).toDouble()
        val v1y = (a.y - b.y).toDouble()
        val v2x = (c.x - b.x).toDouble()
        val v2y = (c.y - b.y).toDouble()
        val dot = v1x * v2x + v1y * v2y
        val n1 = hypot(v1x, v1y)
        val n2 = hypot(v2x, v2y)
        if (n1 < 1e-9 || n2 < 1e-9) return 0.0
        return Math.toDegrees(kotlin.math.acos((dot / (n1 * n2)).coerceIn(-1.0, 1.0)))
    }

    private fun rateConfidence(best: ScoredQuad, pool: List<ScoredQuad>): DetectionConfidence {
        val second = pool.getOrNull(1)?.score ?: 0f
        val margin = best.score - second
        val inArea = best.areaFraction in 0.06f..0.94f
        // Edge support is the primary geometric evidence that a quad is a
        // REAL page boundary rather than a texture hallucination; polarity
        // is the primary photometric evidence (paper-vs-scene step).
        // Quads weak on either can never be HIGH or MEDIUM.
        if (best.edgeSupportScore < 0.30f) return DetectionConfidence.LOW
        if (best.polarityScore < 0.35f) return DetectionConfidence.LOW
        return when {
            best.score >= 0.62f && best.edgeSupportScore >= 0.45f && inArea && best.borderScore > 0.3f -> DetectionConfidence.HIGH
            best.score >= 0.55f && best.edgeSupportScore >= 0.40f && inArea && margin > -0.05f -> DetectionConfidence.HIGH
            best.score >= 0.38f && best.areaFraction in 0.04f..0.97f -> DetectionConfidence.MEDIUM
            else -> DetectionConfidence.LOW
        }
    }

    /**
     * True when the outer border strips are flat white: evidence the input
     * is already a clean page (digital PDF / flatbed) rather than a photo
     * with surrounding scene.
     */
    fun isUniformBorder(gray: FloatArray, w: Int, h: Int): Boolean {
        val strip = (min(w, h) * 0.04f).toInt().coerceAtLeast(4)
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        fun add(x: Int, y: Int) {
            val v = gray[y * w + x].toDouble()
            sum += v
            sumSq += v * v
            n++
        }
        var x = 0
        while (x < w) {
            for (y in 0 until strip) {
                add(x, y)
                add(x, h - 1 - y)
            }
            x += 2
        }
        var y = 0
        while (y < h) {
            for (xx in 0 until strip) {
                add(xx, y)
                add(w - 1 - xx, y)
            }
            y += 2
        }
        if (n == 0) return false
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        val std = sqrt(max(0.0, variance))
        return mean > 225 && std < 16
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private fun denorm(c: CornerPoints, w: Float, h: Float): CornerPoints {
        // CornerPoints from ScoredQuad.toCornerPoints are normalized; this
        // converts back to pixel space. Detect by range.
        return if (c.isNormalized()) c.scale(w, h) else c
    }

    private fun clampToImage(c: CornerPoints, w: Float, h: Float): CornerPoints {
        fun cl(p: PointF) = PointF(p.x.coerceIn(0f, w - 1f), p.y.coerceIn(0f, h - 1f))
        return CornerPoints(cl(c.topLeft), cl(c.topRight), cl(c.bottomRight), cl(c.bottomLeft))
    }

    // ------------------------------------------------------------------
    // Debug previews (Step 19) — same code path as detection
    // ------------------------------------------------------------------

    /**
     * Explains the score of an explicit quad (normalized corners) against this
     * bitmap: per-edge (support, polarity), component scores and validity.
     * Used by tests and the debug screen.
     */
    fun explainQuad(bitmap: Bitmap, corners: CornerPoints): String {
        return try {
            val maxDim = 600
            val scale = min(1f, maxDim.toFloat() / max(bitmap.width, bitmap.height))
            val sw = max(80, (bitmap.width * scale).toInt())
            val sh = max(80, (bitmap.height * scale).toInt())
            val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
            try {
                val pixels = IntArray(sw * sh)
                small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
                val gray = toGrayscale(pixels)
                val blurred = gaussianBlur5x5(gray, sw, sh)
                val median = medianOf(blurred)
                val high = ((1f + 0.33f) * median).toInt().coerceIn(40, 160).toFloat()
                val low = ((1f - 0.33f) * median * 0.5f).toInt().coerceIn(15, 80).toFloat()
                val edges = canny(blurred, sw, sh, low, high).first
                val dilated = dilate3x3(dilate3x3(edges, sw, sh), sw, sh)
                val px = corners.scale(sw.toFloat(), sh.toFloat())
                val quad = QuadValidator.orderPoints(px.toList()).toList()
                val metrics = QuadValidator.edgeMetrics(quad, dilated, blurred, sw, sh)
                val scored = scoreQuad(quad, abs(QuadValidator.polygonArea(quad)), dilated, blurred, sw, sh)
                val v = QuadValidator.validate(px, sw.toFloat(), sh.toFloat())
                val sb = StringBuilder()
                sb.append("valid=${v.valid}(${v.reason}) ")
                metrics.forEachIndexed { i, m ->
                    sb.append("e$i sup=${"%.2f".format(m.first)} pol=${"%.2f".format(m.second)} ")
                }
                sb.append("total=${"%.3f".format(scored.score)} area=${"%.2f".format(scored.areaScore)} edge=${"%.2f".format(scored.edgeSupportScore)} pol=${"%.2f".format(scored.polarityScore)}")
                sb.toString()
            } finally {
                small.recycle()
            }
        } catch (e: Exception) {
            "explain failed: ${e.message}"
        }
    }

    /** White-on-black Canny edge image used by the debug screen. */
    fun renderEdgePreview(bitmap: Bitmap): Bitmap {
        val maxDim = 480
        val scale = min(1f, maxDim.toFloat() / max(bitmap.width, bitmap.height))
        val sw = max(80, (bitmap.width * scale).toInt())
        val sh = max(80, (bitmap.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        try {
            val pixels = IntArray(sw * sh)
            small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            val gray = toGrayscale(pixels)
            val blurred = gaussianBlur5x5(gray, sw, sh)
            val median = medianOf(blurred)
            val high = ((1f + 0.33f) * median).toInt().coerceIn(40, 160).toFloat()
            val low = ((1f - 0.33f) * median * 0.5f).toInt().coerceIn(15, 80).toFloat()
            val edges = canny(blurred, sw, sh, low, high).first
            val out = IntArray(sw * sh)
            for (i in out.indices) {
                out[i] = if (edges[i] != 0.toByte()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }
            val result = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            result.setPixels(out, 0, sw, 0, 0, sw, sh)
            return result
        } finally {
            small.recycle()
        }
    }

    /**
     * Original image with every candidate quad (red, alpha by rank) and the
     * selected quad (green) drawn. Corners of the selection are marked.
     */
    fun renderCandidatesPreview(bitmap: Bitmap, result: DetectionResult): Bitmap {
        val maxDim = 720
        val scale = min(1f, maxDim.toFloat() / max(bitmap.width, bitmap.height))
        val sw = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val sh = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val preview = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        val out = preview.copy(Bitmap.Config.ARGB_8888, true)
        if (out != preview) preview.recycle()
        val canvas = android.graphics.Canvas(out)
        // Candidate points live in detection-sample space, which is the
        // input uniformly scaled to maxDim 600 — same aspect as the image.
        val sampleScale = min(1f, 600f / max(bitmap.width, bitmap.height))
        val sampleW = max(80, (bitmap.width * sampleScale).toInt()).toFloat()
        val sampleH = max(80, (bitmap.height * sampleScale).toInt()).toFloat()
        result.allCandidates.forEachIndexed { idx, q ->
            val alpha = (200 - idx * 22).coerceAtLeast(60)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(alpha, 255, 60, 60)
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f
            }
            val path = android.graphics.Path().apply {
                moveTo(q.topLeft.x / sampleW * sw, q.topLeft.y / sampleH * sh)
                lineTo(q.topRight.x / sampleW * sw, q.topRight.y / sampleH * sh)
                lineTo(q.bottomRight.x / sampleW * sw, q.bottomRight.y / sampleH * sh)
                lineTo(q.bottomLeft.x / sampleW * sw, q.bottomLeft.y / sampleH * sh)
                close()
            }
            canvas.drawPath(path, paint)
        }
        // Draw the selected quad exactly (normalized -> preview space).
        result.corners?.let { corners ->
            val scaled = corners.scale(sw.toFloat(), sh.toFloat())
            val path = android.graphics.Path().apply {
                moveTo(scaled.topLeft.x, scaled.topLeft.y)
                lineTo(scaled.topRight.x, scaled.topRight.y)
                lineTo(scaled.bottomRight.x, scaled.bottomRight.y)
                lineTo(scaled.bottomLeft.x, scaled.bottomLeft.y)
                close()
            }
            val fill = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(60, 0, 230, 120)
                style = android.graphics.Paint.Style.FILL
            }
            val stroke = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(0, 210, 110)
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 5f
            }
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
            val dot = android.graphics.Paint().apply {
                color = android.graphics.Color.YELLOW
                style = android.graphics.Paint.Style.FILL
            }
            for (p in scaled.toList()) canvas.drawCircle(p.x, p.y, 11f, dot)
        }
        return out
    }
}
