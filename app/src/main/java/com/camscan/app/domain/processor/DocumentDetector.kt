package com.camscan.app.domain.processor

import android.graphics.Bitmap
import android.graphics.PointF
import com.camscan.app.domain.model.CornerPoints
import com.camscan.app.domain.model.DetectionConfidence
import com.camscan.app.domain.model.DetectionResult
import com.camscan.app.domain.model.LiveDetection
import com.camscan.app.domain.model.ScoredQuad
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Robust, dependency-free document boundary detector.
 *
 * The engine is a two-stage, multi-pass detector:
 *
 *  STAGE 1 (high recall) — candidate DISCOVERY. Several independent edge
 *  representations are generated so a real page is found even under weak
 *  contrast, shadow, rotation and perspective:
 *    PASS A  Canny at several sensitivities (thresholds derived from
 *            gradient statistics, not brightness alone).
 *    PASS B  Adaptive threshold (mean + gaussian, both polarities) which
 *            finds page boundaries in low-contrast / white-on-white scenes.
 *    PASS C  Hough line intersections, which recover corners when the border
 *            contour is broken.
 *
 *  STAGE 2 (high precision) — candidate VALIDATION. Every candidate is
 *  scored on independent signals (geometry, strong-edge support, perspective,
 *  photometric contrast, context), then near-duplicates are merged (required
 *  before any runner-up margin is meaningful), then a calibrated confidence
 *  decides HIGH / MEDIUM / LOW.
 *
 * Absolute rules preserved:
 *  - The image border (0,0)-(W,H) is the CAMERA FRAME and is never returned
 *    for a photograph unless the input is positively identified as a
 *    born-digital page ([isDigitalPage]).
 *  - No fixed "fake" inset quad is ever returned. LOW carries corners==null
 *    so callers route to the manual editor.
 *  - No single photometric signal is a mandatory gate: polarity / contrast /
 *    texture contribute to the score, they do not veto it.
 */
object DocumentDetector {

    /** Outcome counters from the most recent [detectDocument] call (debug). */
    @Volatile
    var lastStats: DetectionStats = DetectionStats()
        private set

    /**
     * Rich diagnostics for the development debug screen and for tuning
     * (Phase 17). All coordinates are in detection-sample pixel space.
     */
    data class DetectionStats(
        var inputW: Int = 0,
        var inputH: Int = 0,
        var sampleW: Int = 0,
        var sampleH: Int = 0,
        var edgePixels: Int = 0,
        var cannyPasses: Int = 0,
        var adaptivePasses: Int = 0,
        var components: Int = 0,
        var bigComponents: Int = 0,
        var approxFour: Int = 0,
        var validatedQuads: Int = 0,
        var candidateCount: Int = 0,
        var uniqueCandidateCount: Int = 0,
        var houghLines: Int = 0,
        var houghQuads: Int = 0,
        var selectedScore: Float = 0f,
        var secondScore: Float = 0f,
        var calibratedScore: Float = 0f,
        var confidence: String = "LOW",
        var edgeSupport: Float = 0f,
        var polarity: Float = 0f,
        var contrast: Float = 0f,
        var aspect: Float = 0f,
        var rectangularity: Float = 0f,
        var selectedCorners: List<PointF> = emptyList(),
        var houghLineDesc: List<String> = emptyList(),
        var openCvUsed: Boolean = false
    )

    /** Detection work resolution for the live (per-frame) fast path. */
    private const val SAMPLE_LIVE = 480

    /** Detection work resolution for the full (capture) path. */
    private const val SAMPLE_FULL = 640

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Full detection with confidence. This is the ONLY entry point the
     * processing pipeline uses to obtain automatic corners.
     *
     * @param fast true for the live preview fast path (lower resolution,
     *   fewer passes). The capture path must pass false for the full
     *   multi-pass detector.
     */
    fun detectDocument(bitmap: Bitmap, fast: Boolean = false): DetectionResult {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 50 || height < 50) {
            lastStats = DetectionStats(inputW = width, inputH = height)
            return DetectionResult(null, DetectionConfidence.LOW, 0f, emptyList(), "image too small")
        }

        val maxDim = if (fast) SAMPLE_LIVE else SAMPLE_FULL
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
            val (gx, gy, mag) = sobel(blurred, sampleW, sampleH)

            val stats = DetectionStats(
                inputW = width,
                inputH = height,
                sampleW = sampleW,
                sampleH = sampleH
            )

            // A born-digital page can be identified independently of the
            // candidate search; it is used as a fallback so a clean page whose
            // text-block contours produce only weak quads is still recognised.
            val isDigital = isDigitalPage(gray, sampleW, sampleH)
            // A page-like surface that fills the frame (dark/low-contrast page
            // photos) has no page-vs-background boundary to find, so it is
            // recognised independently and used as its own document quad.
            val isFlat = isFlatPage(gray, sampleW, sampleH)

            // Capture path (fast=false) prefers OpenCV for edge extraction.
            // It is optional: on JVM/Robolectric (and any device where the
            // native library fails to load) this returns null and the Kotlin
            // passes below run exactly as before.
            val cvEdges = if (!fast) {
                try {
                    OpenCvVision.edges(small, blockSize = 35, c = 8.0)
                } catch (t: Throwable) {
                    null
                }
            } else null

            val candidates = discoverCandidates(blurred, gx, gy, mag, sampleW, sampleH, fast, stats, cvEdges)

            // STAGE 2: merge near-identical candidates (different passes /
            // epsilon values / Hough intersections) BEFORE ranking so the
            // runner-up is a genuinely different quadrilateral.
            val deduped = deduplicate(candidates, sampleW, sampleH)
            stats.uniqueCandidateCount = deduped.size

            val ranked = deduped
                .filter { it.areaFraction in 0.04f..0.96f }
                .sortedByDescending { it.score }

            if (ranked.isEmpty()) {
                // No candidate survived. A white page on a white table
                // produces no edge evidence; inventing a generic inset would
                // silently include the background. Only a POSITIVELY
                // identified digital page may act as its own document quad.
                if (isDigital) {
                    val page = CornerPoints(
                        PointF(0f, 0f), PointF(1f, 0f),
                        PointF(1f, 1f), PointF(0f, 1f)
                    )
                    stats.confidence = "HIGH"
                    lastStats = stats
                    return DetectionResult(
                        page, DetectionConfidence.HIGH, 0.6f, emptyList(),
                        "digital page identified; page canvas used as document",
                        digitalPage = true
                    )
                }
                if (isFlat) {
                    return flatPageResult(stats, emptyList(), 0.6f)
                }
                stats.confidence = "LOW"
                lastStats = stats
                return DetectionResult(
                    null, DetectionConfidence.LOW, 0f, emptyList(),
                    "no quadrilateral found; manual corners required"
                )
            }

            // Camera-frame exclusion: drop full-frame / near-frame quads with
            // weak support. A photograph's frame is the camera, not the page.
            val viable = ranked.filterNot { q ->
                val cp = q.toList()
                (QuadValidator.isFullFrame(QuadValidator.orderPoints(cp), sampleW.toFloat(), sampleH.toFloat()) ||
                    QuadValidator.isNearFrame(QuadValidator.orderPoints(cp), sampleW.toFloat(), sampleH.toFloat())) &&
                    !q.hasStrongEdgeEvidence
            }

            val pool = if (viable.isNotEmpty()) viable else {
                val first = ranked.first()
                val cp = QuadValidator.orderPoints(first.toList())
                val nearFrame = QuadValidator.isFullFrame(cp, sampleW.toFloat(), sampleH.toFloat()) ||
                    QuadValidator.isNearFrame(cp, sampleW.toFloat(), sampleH.toFloat())
                if (nearFrame && isDigital) {
                    stats.confidence = "HIGH"
                    lastStats = stats
                    return DetectionResult(
                        cp, DetectionConfidence.HIGH, first.score, ranked,
                        "digital page identified; page canvas used as document", digitalPage = true
                    )
                }
                if (nearFrame && isUniformBorder(gray, sampleW, sampleH)) {
                    // The page fills the frame, but for a photograph this is
                    // unconfirmed: never auto-process, ask the user.
                    stats.confidence = "MEDIUM"
                    lastStats = stats
                    return DetectionResult(
                        cp, DetectionConfidence.MEDIUM, first.score, ranked,
                        "page fills the frame; confirm the corners", digitalPage = false
                    )
                }
                if (isFlat) {
                    return flatPageResult(stats, ranked, first.score)
                }
                stats.confidence = "LOW"
                lastStats = stats
                return DetectionResult(
                    null, DetectionConfidence.LOW, first.score, ranked,
                    "only the camera frame was found; manual corners required"
                )
            }

            val best = pool.first()
            val pixelCorners = best.toCornerPoints(sampleW.toFloat(), sampleH.toFloat())
                .scale(sampleW.toFloat(), sampleH.toFloat())
            val corners = clampToImage(pixelCorners, sampleW.toFloat(), sampleH.toFloat())

            val validation = QuadValidator.validate(corners, sampleW.toFloat(), sampleH.toFloat())
            if (!validation.valid) {
                stats.confidence = "LOW"
                lastStats = stats
                return DetectionResult(null, DetectionConfidence.LOW, best.score, pool, "invalid quad (${validation.reason}); manual corners required")
            }

            val normalized = corners.normalize(sampleW.toFloat(), sampleH.toFloat())
            var confidence = rateConfidence(best, pool)

            // A quad that fills (or nearly fills) the camera frame is the
            // camera, not the page. Even with strong edge evidence it must
            // never be auto-processed for a photograph: demote HIGH to
            // MEDIUM so the user confirms. A born-digital page is exempt.
            val ordered = QuadValidator.orderPoints(corners.toList())
            val nearFrame = QuadValidator.isFullFrame(ordered, sampleW.toFloat(), sampleH.toFloat()) ||
                QuadValidator.isNearFrame(ordered, sampleW.toFloat(), sampleH.toFloat())
            if (nearFrame && !isDigital && confidence == DetectionConfidence.HIGH) {
                confidence = DetectionConfidence.MEDIUM
            }

            stats.selectedScore = best.score
            stats.secondScore = pool.getOrNull(1)?.score ?: 0f
            stats.calibratedScore = best.score
            stats.confidence = confidence.name
            stats.edgeSupport = best.edgeSupportScore
            stats.polarity = best.polarityScore
            stats.contrast = best.contrastScore
            stats.aspect = best.aspectScore
            stats.rectangularity = best.rectangularityScore
            stats.selectedCorners = normalized.toList()
            lastStats = stats

            if (confidence == DetectionConfidence.LOW) {
                // No trustworthy quad. A positively identified digital page is
                // still legitimate (its canvas IS the document); anything else
                // goes to the manual corner editor.
                if (isDigital) {
                    val page = CornerPoints(
                        PointF(0f, 0f), PointF(1f, 0f),
                        PointF(1f, 1f), PointF(0f, 1f)
                    )
                    return DetectionResult(
                        page, DetectionConfidence.HIGH, 0.6f, pool,
                        "digital page identified; page canvas used as document",
                        digitalPage = true
                    )
                }
                if (isFlat) {
                    return flatPageResult(stats, pool, best.score)
                }
                return DetectionResult(
                    null, DetectionConfidence.LOW, best.score, pool,
                    "weak detection (score ${"%.2f".format(best.score)}); manual corners required"
                )
            }

            val reason = if (confidence == DetectionConfidence.HIGH) {
                "document detected (score ${"%.2f".format(best.score)})"
            } else {
                "uncertain detection (score ${"%.2f".format(best.score)}); confirm corners"
            }
            return DetectionResult(normalized, confidence, best.score, pool, reason)
        } finally {
            if (small != bitmap) small.recycle()
        }
    }

    /**
     * Live-detection entry point used by the camera preview. Returns a
     * [LiveDetection] which can represent NOT_DETECTED, so the overlay never
     * draws a fake document rectangle.
     */
    fun detectLive(bitmap: Bitmap): LiveDetection {
        val result = try {
            detectDocument(bitmap, fast = true)
        } catch (e: Exception) {
            null
        }
        return if (result == null) LiveDetection.NONE else LiveDetection.from(result)
    }

    /**
     * Legacy shim for callers that only need points.
     *
     * Returns the best quad, or NULL when nothing trustworthy was found.
     * It no longer fabricates a fixed 12%-88% rectangle (Phase 10), because
     * that was indistinguishable from a real detection in the UI.
     */
    fun detectCorners(bitmap: Bitmap): CornerPoints? {
        val result = try {
            detectDocument(bitmap, fast = true)
        } catch (e: Exception) {
            null
        }
        return result?.corners
    }

    fun orderPoints(pts: List<PointF>): CornerPoints = QuadValidator.orderPoints(pts)

    // ------------------------------------------------------------------
    // Stage 1: multi-pass candidate discovery
    // ------------------------------------------------------------------

    /**
     * Runs every discovery pass and returns the union of scored candidates.
     * Candidates carry the [ScoredQuad] components used by [rateConfidence].
     */
    private fun discoverCandidates(
        gray: FloatArray,
        gx: FloatArray,
        gy: FloatArray,
        mag: FloatArray,
        w: Int,
        h: Int,
        fast: Boolean,
        stats: DetectionStats,
        cvEdges: OpenCvVision.CvEdges?
    ): List<ScoredQuad> {
        val out = mutableListOf<ScoredQuad>()

        // ---- PASS A: Canny at several sensitivities ---------------------
        val cannyEdgeMaps = mutableListOf<ByteArray>()
        // OpenCV's Canny (capture path only) is the primary, conservative map:
        // it feeds scoring, Hough and contour discovery as cannyEdgeMaps[0].
        if (cvEdges != null && cvEdges.canny.size == w * h) {
            cannyEdgeMaps.add(cvEdges.canny)
            stats.cannyPasses++
            stats.openCvUsed = true
        }
        val cannyThresholds = cannyThresholds(gray, mag, fast)
        for ((low, high) in cannyThresholds) {
            val edges = canny(gx, gy, mag, w, h, low, high)
            cannyEdgeMaps.add(edges)
            stats.cannyPasses++
        }
        // The scoring edge map must stay conservative: the sensitive Canny
        // pass is used for DISCOVERY only. If it were merged in here, dense
        // background texture would support any quad and edge support would
        // stop being evidence. The primary (brightness-median) pass is the
        // proven, reliable boundary map used for scoring and Hough.
        val strongEdges = union(listOf(cannyEdgeMaps.first()))
        stats.edgePixels = countNonZero(strongEdges)
        // Scoring tolerance: candidate corners are a few pixels away from the
        // exact boundary (contour/hull quantisation, mask offsets). Edge
        // support must tolerate that, otherwise a correct quad scores as if it
        // had no edges at all. Radius ~4px on a 480-640px detection image.
        val strongDilated = dilateN(strongEdges, w, h, 4)

        for (edges in cannyEdgeMaps) {
            val closed = closeGaps(edges, w, h)
            out += contourCandidates(
                componentEdges = closed,
                scoreEdges = strongDilated,
                combinedEdges = strongDilated,
                gray = gray,
                w = w, h = h,
                source = ScoredQuad.SOURCE_CONTOUR,
                stats = stats
            )
        }

        // ---- PASS B: adaptive threshold (mean + gaussian) ---------------
        val adaptiveEdgeMaps = mutableListOf<ByteArray>()
        // OpenCV adaptive boundaries (capture path only) are extra discovery
        // sources; they are intentionally NOT the scoring edge map.
        if (cvEdges != null) {
            for (a in cvEdges.adaptive) {
                if (a.size == w * h) {
                    adaptiveEdgeMaps.add(a)
                    stats.adaptivePasses++
                    stats.openCvUsed = true
                }
            }
        }
        val blockMean = if (fast) 31 else 41
        val blockGauss = if (fast) 25 else 35
        val adaptiveSpecs = mutableListOf<Triple<FloatArray, Int, Float>>()
        adaptiveSpecs += Triple(localMeanIntegral(gray, w, h, blockMean), blockMean, 10f)
        if (!fast) {
            adaptiveSpecs += Triple(localMeanGaussian(gray, w, h, blockGauss), blockGauss, 8f)
        }
        for ((mean, _, c) in adaptiveSpecs) {
            for (bright in booleanArrayOf(true, false)) {
                val binary = adaptiveBinary(gray, mean, c, bright, w, h)
                val boundary = morphGradient(binary, w, h)
                adaptiveEdgeMaps.add(boundary)
                stats.adaptivePasses++
            }
        }
        // Continuity bonus map: primary Canny + adaptive boundaries (the
        // sensitive Canny pass is intentionally excluded so continuity stays
        // meaningful).
        val combinedEdges = dilateN(union(listOf(cannyEdgeMaps.first()) + adaptiveEdgeMaps), w, h, 4)

        for (boundary in adaptiveEdgeMaps) {
            val closed = closeGaps(boundary, w, h)
            out += contourCandidates(
                componentEdges = closed,
                scoreEdges = strongDilated,
                combinedEdges = combinedEdges,
                gray = gray,
                w = w, h = h,
                source = ScoredQuad.SOURCE_ADAPTIVE,
                stats = stats
            )
        }

        // ---- PASS C: Hough lines (full resolution only) -----------------
        if (!fast) {
            out += houghCandidates(
                edges = strongEdges,
                magnitudes = mag,
                scoreEdges = strongDilated,
                combinedEdges = combinedEdges,
                gray = gray,
                w = w, h = h,
                stats = stats
            )
        }

        // ---- PASS D: region silhouettes ---------------------------------
        // On a textured background the Canny CONTOUR pass welds the page
        // boundary into the background edge lattice, so the page quad is lost.
        // This pass instead segments the image into connected NON-EDGE regions
        // (4-connectivity, so a 1px edge line truly separates regions) and
        // takes the silhouette of each large region. A page body is one large
        // region whose outline is the page rectangle regardless of what the
        // background looks like.
        // A region silhouette's perimeter sits just OUTSIDE the dilated
        // segmentation band, so it needs a slightly wider support map to be
        // recognised as edge-backed rather than a free-floating shape.
        val regionSupport = dilateN(strongEdges, w, h, 7)
        out += regionCandidates(
            edgeMap = strongDilated,
            scoreEdges = regionSupport,
            combinedEdges = regionSupport,
            gray = gray,
            w = w, h = h,
            stats = stats
        )

        stats.candidateCount = out.size
        return out
    }

    /**
     * Candidate quads from connected non-edge regions ("faces"). Recovers a
     * page silhouette on busy/textured backgrounds where the edge-contour pass
     * fuses the page border with the background texture.
     */
    private fun regionCandidates(
        edgeMap: ByteArray,
        scoreEdges: ByteArray,
        combinedEdges: ByteArray,
        gray: FloatArray,
        w: Int,
        h: Int,
        stats: DetectionStats?
    ): List<ScoredQuad> {
        val n = w * h
        val inRegion = ByteArray(n)
        for (i in 0 until n) inRegion[i] = if (edgeMap[i] == 0.toByte()) 1 else 0
        val label = IntArray(n) { -1 }
        val out = mutableListOf<ScoredQuad>()
        val imgArea = w.toFloat() * h
        val minArea = (imgArea * 0.05f).toInt()
        var comp = 0
        for (seed in 0 until n) {
            if (inRegion[seed] == 0.toByte() || label[seed] != -1) continue
            val boundary = ArrayList<PointF>(256)
            val stack = ArrayDeque<Int>()
            stack.add(seed)
            label[seed] = comp
            var area = 0
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                area++
                val x = i % w
                val y = i / w
                var isBoundary = false
                // 4-connectivity: a 1px edge line must separate two regions.
                for (dir in 0 until 4) {
                    val nx = x + (if (dir == 0) -1 else if (dir == 1) 1 else 0)
                    val ny = y + (if (dir == 2) -1 else if (dir == 3) 1 else 0)
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                        isBoundary = true
                        continue
                    }
                    val ni = ny * w + nx
                    if (inRegion[ni] == 0.toByte()) {
                        isBoundary = true
                        continue
                    }
                    if (label[ni] == -1) {
                        label[ni] = comp
                        stack.add(ni)
                    }
                }
                if (isBoundary) boundary.add(PointF(x.toFloat(), y.toFloat()))
            }
            comp++
            stats?.components = (stats?.components ?: 0) + 1
            if (area < minArea || boundary.size < 40) continue
            stats?.bigComponents = (stats?.bigComponents ?: 0) + 1
            val hull = try {
                QuadValidator.convexHull(subsampleForHull(boundary))
            } catch (e: Exception) {
                continue
            }
            if (hull.size < 4) continue
            val hullArea = abs(QuadValidator.polygonArea(hull))
            if (hullArea < imgArea * 0.04f) continue
            val peri = QuadValidator.perimeter(hull)
            for (epsF in floatArrayOf(0.008f, 0.013f, 0.019f, 0.027f, 0.040f)) {
                val approx = QuadValidator.approxPolyDP(hull, (epsF * peri).coerceAtLeast(2f))
                if (approx.size != 4) continue
                stats?.approxFour = (stats?.approxFour ?: 0) + 1
                val ordered = QuadValidator.orderPoints(approx)
                val quad = ordered.toList()
                val qArea = abs(QuadValidator.polygonArea(quad))
                if (qArea < imgArea * 0.03f || qArea > imgArea * 0.985f) continue
                if (!QuadValidator.validate(ordered, w.toFloat(), h.toFloat()).valid) continue
                stats?.validatedQuads = (stats?.validatedQuads ?: 0) + 1
                out.add(
                    scoreQuad(
                        quad, hullArea, scoreEdges, combinedEdges,
                        gray, w, h, ScoredQuad.SOURCE_REGION
                    )
                )
                break
            }
        }
        return out
    }

    /**
     * Thresholds for the Canny passes. Combines brightness-median thresholds
     * (stable on clean pages) with gradient-statistics thresholds (adapt to
     * real photographs) so weak-but-real boundaries are not lost.
     */
    private fun cannyThresholds(gray: FloatArray, mag: FloatArray, fast: Boolean): List<Pair<Float, Float>> {
        val median = medianOf(gray)
        val baseHigh = ((1f + 0.33f) * median).toInt().coerceIn(40, 160).toFloat()
        val baseLow = ((1f - 0.33f) * median * 0.5f).toInt().coerceIn(15, 80).toFloat()

        val p = gradientPercentiles(mag, w = 0, h = 0)
        val sensitiveHigh = p.p50.coerceIn(25f, 220f)
        val sensitiveLow = (p.p50 * 0.40f).coerceIn(10f, 120f)

        val passes = mutableListOf(baseLow to baseHigh)
        passes += sensitiveLow to sensitiveHigh
        if (!fast) {
            val moderateHigh = p.p75.coerceIn(40f, 260f)
            val moderateLow = (p.p75 * 0.50f).coerceIn(15f, 160f)
            passes += moderateLow to moderateHigh
        }
        return passes
    }

    // ------------------------------------------------------------------
    // Stage 1 primitives: grayscale / blur / gradients / edges
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

    private data class Gradients(val gx: FloatArray, val gy: FloatArray, val mag: FloatArray)

    private fun sobel(gray: FloatArray, w: Int, h: Int): Gradients {
        val gx = FloatArray(w * h)
        val gy = FloatArray(w * h)
        val mag = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val sx = -gray[i - w - 1] - 2 * gray[i - 1] - gray[i + w - 1] +
                    gray[i - w + 1] + 2 * gray[i + 1] + gray[i + w + 1]
                val sy = -gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1] +
                    gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1]
                gx[i] = sx
                gy[i] = sy
                mag[i] = sqrt(sx * sx + sy * sy)
            }
        }
        return Gradients(gx, gy, mag)
    }

    private data class GradientPercentiles(val p50: Float, val p75: Float, val p90: Float)

    /** Percentiles of the NON-ZERO gradient magnitudes. */
    private fun gradientPercentiles(mag: FloatArray, w: Int, h: Int): GradientPercentiles {
        val nonzero = ArrayList<Float>(mag.size / 4)
        for (v in mag) if (v > 1f) nonzero.add(v)
        if (nonzero.isEmpty()) return GradientPercentiles(0f, 0f, 0f)
        nonzero.sort()
        fun at(f: Float): Float = nonzero[(f * (nonzero.size - 1)).toInt().coerceIn(0, nonzero.size - 1)]
        return GradientPercentiles(at(0.50f), at(0.75f), at(0.90f))
    }

    private fun canny(gx: FloatArray, gy: FloatArray, mag: FloatArray, w: Int, h: Int, low: Float, high: Float): ByteArray {
        val dir = ByteArray(w * h)
        for (i in mag.indices) {
            if (mag[i] < low) continue
            val ang = Math.toDegrees(atan2(gy[i].toDouble(), gx[i].toDouble()))
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
                if (m < low) continue
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
        return out
    }

    // ------------------------------------------------------------------
    // Adaptive-threshold primitives (Pass B)
    // ------------------------------------------------------------------

    private fun localMeanIntegral(gray: FloatArray, w: Int, h: Int, blockSize: Int): FloatArray {
        val iw = w + 1
        val integral = DoubleArray(iw * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0.0
            val rowBase = (y + 1) * iw
            val prevBase = y * iw
            for (x in 0 until w) {
                rowSum += gray[y * w + x]
                integral[rowBase + x + 1] = integral[prevBase + x + 1] + rowSum
            }
        }
        val r = blockSize / 2
        val mean = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w, x + r + 1)
                val sum = integral[y1 * iw + x1] - integral[y0 * iw + x1] -
                    integral[y1 * iw + x0] + integral[y0 * iw + x0]
                val count = ((x1 - x0) * (y1 - y0)).coerceAtLeast(1)
                mean[y * w + x] = (sum / count).toFloat()
            }
        }
        return mean
    }

    /** Gaussian-ish local mean via two box passes (triangle kernel). */
    private fun localMeanGaussian(gray: FloatArray, w: Int, h: Int, blockSize: Int): FloatArray {
        val r = (blockSize / 2).coerceAtLeast(1)
        var cur = gray
        repeat(2) {
            cur = boxBlur(cur, w, h, r)
        }
        return cur
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            var sum = 0f
            var count = 0
            for (x in -r..r) {
                val xx = x.coerceIn(0, w - 1)
                sum += src[y * w + xx]
                count++
            }
            for (x in 0 until w) {
                tmp[y * w + x] = sum / count
                val add = (x + r + 1).coerceAtMost(w - 1)
                val rem = (x - r).coerceAtLeast(0)
                sum += src[y * w + add] - src[y * w + rem]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            var count = 0
            for (y in -r..r) {
                val yy = y.coerceIn(0, h - 1)
                sum += tmp[yy * w + x]
                count++
            }
            for (y in 0 until h) {
                out[y * w + x] = sum / count
                val add = (y + r + 1).coerceAtMost(h - 1)
                val rem = (y - r).coerceAtLeast(0)
                sum += tmp[add * w + x] - tmp[rem * w + x]
            }
        }
        return out
    }

    /**
     * Binary mask where a pixel is brighter (bright=true) or darker
     * (bright=false) than its local mean by more than [c].
     */
    private fun adaptiveBinary(gray: FloatArray, mean: FloatArray, c: Float, bright: Boolean, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (i in out.indices) {
            val v = gray[i]
            val m = mean[i]
            out[i] = if (bright) {
                if (v > m + c) 1 else 0
            } else {
                if (v < m - c) 1 else 0
            }
        }
        return out
    }

    /** Boundary of a binary region: dilate AND NOT erode. */
    private fun morphGradient(binary: ByteArray, w: Int, h: Int): ByteArray {
        val d = dilate3x3(binary, w, h)
        val e = erode3x3(binary, w, h)
        val out = ByteArray(binary.size)
        for (i in out.indices) {
            out[i] = if (d[i] != 0.toByte() && e[i] == 0.toByte()) 1 else 0
        }
        return out
    }

    // ------------------------------------------------------------------
    // Morphology helpers
    // ------------------------------------------------------------------

    /** Repeated 3x3 dilation: expands the support map by [n] pixels. */
    private fun dilateN(src: ByteArray, w: Int, h: Int, n: Int): ByteArray {
        var cur = src
        repeat(n) { cur = dilate3x3(cur, w, h) }
        return cur
    }

    private fun union(maps: List<ByteArray>): ByteArray {
        if (maps.isEmpty()) return ByteArray(0)
        val out = ByteArray(maps[0].size)
        for (map in maps) {
            for (i in out.indices) if (map[i] != 0.toByte()) out[i] = 1
        }
        return out
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
     * Morphological CLOSE to bridge small gaps. Correct form is
     * dilate -> erode (a true close), applied twice so gaps of roughly 5px
     * are bridged with zero net growth (a net dilation would pull text and
     * shadows into the page border and inflate the quad).
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
    // Stage 1: connected components -> hull -> poly approx candidates
    // ------------------------------------------------------------------

    private fun contourCandidates(
        componentEdges: ByteArray,
        scoreEdges: ByteArray,
        combinedEdges: ByteArray,
        gray: FloatArray,
        w: Int,
        h: Int,
        source: Int,
        stats: DetectionStats?
    ): List<ScoredQuad> {
        val labels = IntArray(w * h) { -1 }
        var compCount = 0
        val out = mutableListOf<ScoredQuad>()
        val imgArea = w.toFloat() * h

        for (seed in componentEdges.indices) {
            if (componentEdges[seed] == 0.toByte() || labels[seed] != -1) continue
            val pts = mutableListOf<PointF>()
            val stack = ArrayDeque<Int>()
            stack.add(seed)
            labels[seed] = compCount
            val guard = w * h
            var count = 0
            while (stack.isNotEmpty() && count < guard) {
                count++
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
                            if (componentEdges[ni] != 0.toByte() && labels[ni] == -1) {
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

            val hullInput = subsampleForHull(pts)
            val hull = try {
                QuadValidator.convexHull(hullInput)
            } catch (e: Exception) {
                continue
            }
            if (hull.size < 4) continue
            val hullArea = abs(QuadValidator.polygonArea(hull))
            if (hullArea < imgArea * 0.02f) continue
            val peri = QuadValidator.perimeter(hull)

            // Fine epsilon ladder: small epsilons keep exact corners on clean
            // pages; large epsilons smooth bumps where objects touch the page.
            for (epsF in floatArrayOf(0.008f, 0.013f, 0.019f, 0.027f, 0.040f, 0.058f, 0.080f, 0.11f)) {
                val approx = QuadValidator.approxPolyDP(hull, (epsF * peri).coerceAtLeast(2f))
                if (approx.size != 4) continue
                stats?.approxFour = (stats?.approxFour ?: 0) + 1
                val ordered = QuadValidator.orderPoints(approx)
                val quad = ordered.toList()
                val qArea = abs(QuadValidator.polygonArea(quad))
                if (qArea < imgArea * 0.02f || qArea > imgArea * 0.985f) continue
                val v = QuadValidator.validate(ordered, w.toFloat(), h.toFloat())
                if (!v.valid) continue
                stats?.validatedQuads = (stats?.validatedQuads ?: 0) + 1
                val scored = scoreQuad(quad, hullArea, scoreEdges, combinedEdges, gray, w, h, source)
                out.add(scored)
                break // one quad per component to avoid duplicates
            }
        }
        return out
    }

    /**
     * Reduces a large component point cloud to a hull-friendly sample while
     * guaranteeing the extreme corners survive (so the hull is exact).
     */
    private fun subsampleForHull(pts: List<PointF>): List<PointF> {
        val cap = 4000
        if (pts.size <= cap) return pts
        val result = ArrayList<PointF>(cap + 4)
        val stride = max(1, pts.size / cap)
        var i = 0
        while (i < pts.size) {
            result.add(pts[i])
            i += stride
        }
        var minSum = Float.MAX_VALUE
        var maxSum = -Float.MAX_VALUE
        var minDiff = Float.MAX_VALUE
        var maxDiff = -Float.MAX_VALUE
        var pMinSum = pts[0]
        var pMaxSum = pts[0]
        var pMinDiff = pts[0]
        var pMaxDiff = pts[0]
        for (p in pts) {
            val s = p.x + p.y
            val d = p.x - p.y
            if (s < minSum) { minSum = s; pMinSum = p }
            if (s > maxSum) { maxSum = s; pMaxSum = p }
            if (d < minDiff) { minDiff = d; pMinDiff = p }
            if (d > maxDiff) { maxDiff = d; pMaxDiff = p }
        }
        result += pMinSum
        result += pMaxSum
        result += pMinDiff
        result += pMaxDiff
        return result
    }

    // ------------------------------------------------------------------
    // Stage 1: Hough line intersections (handles broken/tilted borders)
    // ------------------------------------------------------------------

    private data class HoughLine(val rho: Float, val thetaDeg: Float, val votes: Float)

    private fun houghCandidates(
        edges: ByteArray,
        magnitudes: FloatArray,
        scoreEdges: ByteArray,
        combinedEdges: ByteArray,
        gray: FloatArray,
        w: Int,
        h: Int,
        stats: DetectionStats?
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
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    if (edges[idx] == 0.toByte()) continue
                    edgeCount++
                    val weight = magnitudes[idx].coerceAtLeast(1f)
                    for (t in 0 until numTheta) {
                        val rho = x * cosT[t] + y * sinT[t]
                        val rIdx = ((rho + maxRho) / rhoStep).toInt().coerceIn(0, numRho - 1)
                        acc[rIdx * numTheta + t] += weight
                    }
                }
            }
            if (edgeCount < 200) return out

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
                            val tt = t + dt
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

            fun isHorizontal(l: HoughLine): Boolean = l.thetaDeg < 28 || l.thetaDeg > 152
            fun isVertical(l: HoughLine): Boolean = l.thetaDeg in 62f..118f
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
                            val p1 = intersect(horizontals[i], verticals[k]) ?: continue
                            val p2 = intersect(horizontals[i], verticals[m]) ?: continue
                            val p3 = intersect(horizontals[j], verticals[k]) ?: continue
                            val p4 = intersect(horizontals[j], verticals[m]) ?: continue
                            val all = listOf(p1, p2, p3, p4)
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
                            val scored = scoreQuad(quad, qArea * 0.92f, scoreEdges, combinedEdges, gray, w, h, ScoredQuad.SOURCE_HOUGH)
                            found.add(scored)
                        }
                    }
                }
            }
            found.sortByDescending { it.score }
            val kept = found.take(40)
            out.addAll(kept)
            stats?.houghQuads = (stats?.houghQuads ?: 0) + kept.size
        } catch (e: Exception) {
            // Hough is best-effort; the contour passes already ran.
        }
        return out
    }

    // ------------------------------------------------------------------
    // Stage 2: candidate scoring + deduplication + confidence
    // ------------------------------------------------------------------

    private fun scoreQuad(
        quad: List<PointF>,
        hullArea: Float,
        scoreEdges: ByteArray,
        combinedEdges: ByteArray,
        gray: FloatArray,
        w: Int,
        h: Int,
        source: Int
    ): ScoredQuad {
        val imgArea = w.toFloat() * h
        val qArea = abs(QuadValidator.polygonArea(quad))
        val areaFrac = (qArea / imgArea).coerceIn(0f, 1f)

        val areaScore = when {
            areaFrac < 0.04f -> (areaFrac / 0.04f).coerceIn(0f, 1f) * 0.5f
            areaFrac <= 0.22f -> 0.5f + 0.5f * ((areaFrac - 0.04f) / 0.18f)
            areaFrac <= 0.85f -> 1f
            areaFrac <= 0.94f -> 1f - (areaFrac - 0.85f) / 0.09f * 0.35f
            else -> (0.65f - (areaFrac - 0.94f) / 0.06f * 0.65f).coerceAtLeast(0f)
        }

        val rectangularity = if (qArea > 1f) {
            (min(hullArea, qArea) / max(hullArea, qArea)).coerceIn(0f, 1f)
        } else 0f

        // Per-edge (support, polarity). The MINIMUM over the four edges is a
        // strong discriminator: a table / text-block / Hough sub-quad uses an
        // internal line as one of its sides, so that side has no paper-vs-scene
        // step while the other three look perfect. A real page has a step on
        // every side.
        val metrics = QuadValidator.edgeMetrics(quad, scoreEdges, gray, w, h)
        val edgeSupport = if (metrics.isEmpty()) 0f else metrics.map { it.first }.average().toFloat()
        val combinedSupport = QuadValidator.edgeSupport(quad, combinedEdges, w, h)
        val polarityAvg = if (metrics.isEmpty()) 0f else metrics.map { it.second }.average().toFloat()
        val polarityMin = metrics.minOfOrNull { it.second } ?: 0f

        var angleDev = 0f
        for (i in 0 until 4) {
            val ang = interiorAngle(quad[i], quad[(i + 1) % 4], quad[(i + 2) % 4])
            angleDev += abs(90.0 - ang).toFloat()
        }
        angleDev /= 4f
        val angleScore = (1f - angleDev / 35f).coerceIn(0f, 1f)

        val (qw, qh) = QuadValidator.quadDims(quad)
        val portraitAr = (min(qw, qh) / max(qw, qh).coerceAtLeast(1f)).coerceIn(0.01f, 1f)
        val distA4 = min(abs(portraitAr - 0.707f), abs(portraitAr - 0.65f))
        val aspectScore = (0.45f + 0.55f * (1f - distA4 / 0.45f).coerceIn(0f, 1f))

        val m = 0.02f * min(w, h)
        var touched = 0
        if (quad.any { it.x < m }) touched++
        if (quad.any { it.x > w - m }) touched++
        if (quad.any { it.y < m }) touched++
        if (quad.any { it.y > h - m }) touched++
        var borderScore = 1f - touched * 0.22f
        if (touched >= 3 && edgeSupport > 0.7f) borderScore = 0.6f
        borderScore = borderScore.coerceIn(0f, 1f)

        val polarity = polarityAvg
        val parallel = parallelEdgeConsistency(quad)
        val contrast = insideOutsideContrast(quad, gray, w, h)
        val texture = textureDifference(quad, gray, w, h)
        val continuity = (edgeSupport * 0.5f + combinedSupport * 0.5f).coerceIn(0f, 1f)
        val perspPlaus = QuadValidator.perspectivePlausibility(quad)

        // Calibrated component model (Phase 7). No single signal is a gate:
        // each contributes to a weighted total, so a real document with weak
        // brightness contrast can still score highly on geometry + edges.
        val geometry = (0.45f * rectangularity + 0.35f * angleScore + 0.20f * areaScore).coerceIn(0f, 1f)
        val edge = (0.65f * edgeSupport + 0.35f * continuity).coerceIn(0f, 1f)
        val perspective = (0.50f * parallel + 0.30f * aspectScore + 0.20f * perspPlaus).coerceIn(0f, 1f)
        // Photometric: average polarity plus the weakest edge, so no single
        // fabricated side can be hidden by three real ones. The weakest-edge
        // term dominates deliberately: a texture/grid patch can have Canny
        // support on aligned squares yet no consistent page-vs-scene step.
        val photometric = (0.25f * polarityAvg + 0.45f * polarityMin +
            0.20f * contrast + 0.10f * texture).coerceIn(0f, 1f)
        val context = borderScore

        // Photometric evidence carries real weight (0.18) so a photometrically
        // incoherent texture patch cannot outrank a genuine page purely on
        // strong edges + right angles.
        val total = (0.38f * geometry + 0.24f * edge + 0.15f * perspective +
            0.18f * photometric + 0.05f * context).coerceIn(0f, 1f)

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
            polarityMinScore = polarityMin,
            parallelScore = parallel,
            contrastScore = contrast,
            textureScore = texture,
            continuityScore = continuity,
            areaFraction = areaFrac,
            source = source,
            geometryScore = geometry,
            perspectiveScore = perspective,
            photometricScore = photometric,
            contextScore = context
        )
    }

    /**
     * Merges candidates that describe the SAME quadrilateral (from different
     * passes, epsilon values or Hough intersections) keeping the highest
     * scoring one. REQUIRED before computing any runner-up margin: otherwise
     * the runner-up is simply a copy of the winner and the true document can
     * never reach HIGH.
     */
    private fun deduplicate(candidates: List<ScoredQuad>, w: Int, h: Int): List<ScoredQuad> {
        if (candidates.size <= 1) return candidates
        val sorted = candidates.sortedByDescending { it.score }
        val kept = ArrayList<ScoredQuad>()
        for (c in sorted) {
            val cp = c.toList()
            var duplicate = false
            for (k in kept) {
                val kp = k.toList()
                if (QuadValidator.maxCornerDistance(cp, kp, w.toFloat(), h.toFloat()) < 0.025f ||
                    QuadValidator.iou(cp, kp) > 0.85f
                ) {
                    duplicate = true
                    break
                }
            }
            if (!duplicate) kept.add(c)
        }
        return kept
    }

    /**
     * Calibrated confidence. HIGH means "the evidence strongly indicates this
     * quadrilateral is the page", NOT "every signal succeeded".
     *
     * `calibrated = 0.40*geometry + 0.30*edge + 0.15*perspective + 0.10*photo + 0.05*context`
     * is already stored in [ScoredQuad.score]. HIGH additionally needs real
     * (Canny-corroborated) edge evidence and plausible geometry; a quad that
     * only an adaptive threshold can see stays MEDIUM and asks the user.
     */
    private fun rateConfidence(best: ScoredQuad, pool: List<ScoredQuad>): DetectionConfidence {
        val calibrated = best.score
        val inArea = best.areaFraction in 0.05f..0.95f

        // A quad covering essentially the whole frame is the camera frame,
        // not a page; never auto-accept (Phase 9).
        if (!inArea && best.areaFraction > 0.93f) {
            return if (calibrated >= 0.60f) DetectionConfidence.MEDIUM else DetectionConfidence.LOW
        }
        if (!inArea) return DetectionConfidence.LOW

        return when {
            calibrated >= 0.80f &&
                best.edgeSupportScore >= 0.35f &&
                best.geometryScore >= 0.55f &&
                best.polarityMinScore >= 0.30f -> DetectionConfidence.HIGH

            calibrated >= 0.72f && best.edgeSupportScore >= 0.25f -> DetectionConfidence.MEDIUM

            else -> DetectionConfidence.LOW
        }
    }

    /** True when the quad has strong Canny-corroborated edge support. */
    private val ScoredQuad.hasStrongEdgeEvidence: Boolean
        get() = edgeSupportScore >= 0.75f

    // ------------------------------------------------------------------
    // Scoring helpers
    // ------------------------------------------------------------------

    private fun parallelEdgeConsistency(quad: List<PointF>): Float {
        fun angle(e0: Int): Double {
            val a = quad[e0]
            val b = quad[(e0 + 1) % 4]
            return atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
        }
        fun delta(a: Double, b: Double): Double {
            var d = abs(a - b)
            while (d > Math.PI / 2) d = Math.PI - d
            return d
        }
        val d1 = delta(angle(0), angle(2))
        val d2 = delta(angle(1), angle(3))
        val mean = ((d1 + d2) / 2.0).toFloat()
        return (1f - mean / 0.45f).coerceIn(0f, 1f)
    }

    private fun insideOutsideContrast(quad: List<PointF>, gray: FloatArray, w: Int, h: Int): Float {
        if (gray.size != w * h) return 0f
        var sum = 0f
        var n = 0
        val d = 9f
        for (e in 0 until 4) {
            val a = quad[e]
            val b = quad[(e + 1) % 4]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val len = hypot(ex.toDouble(), ey.toDouble()).toFloat()
            if (len < 1e-6f) continue
            var nx = -ey / len
            var ny = ex / len
            val mx = (a.x + b.x) / 2f
            val my = (a.y + b.y) / 2f
            val cx = (quad[0].x + quad[1].x + quad[2].x + quad[3].x) / 4f
            val cy = (quad[0].y + quad[1].y + quad[2].y + quad[3].y) / 4f
            if ((mx + nx - cx) * (mx - cx) + (my + ny - cy) * (my - cy) < 0) {
                nx = -nx
                ny = -ny
            }
            val steps = 20
            for (s in 0 until steps) {
                val t = s.toFloat() / (steps - 1)
                val sx = a.x + ex * t
                val sy = a.y + ey * t
                val ix = (sx - nx * d).toInt()
                val iy = (sy - ny * d).toInt()
                val ox = (sx + nx * d).toInt()
                val oy = (sy + ny * d).toInt()
                if (ix !in 0 until w || iy !in 0 until h || ox !in 0 until w || oy !in 0 until h) continue
                val diff = abs(gray[iy * w + ix] - gray[oy * w + ox])
                sum += min(diff, 60f)
                n++
            }
        }
        if (n == 0) return 0f
        return (sum / n / 45f).coerceIn(0f, 1f)
    }

    private fun textureDifference(quad: List<PointF>, gray: FloatArray, w: Int, h: Int): Float {
        if (gray.size != w * h) return 0f
        fun energy(x: Int, y: Int): Float {
            if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) return 0f
            val i = y * w + x
            val gx = gray[i + 1] - gray[i - 1]
            val gy = gray[i + w] - gray[i - w]
            return hypot(gx.toDouble(), gy.toDouble()).toFloat()
        }
        var inSum = 0f
        var outSum = 0f
        var n = 0
        val cx = (quad[0].x + quad[1].x + quad[2].x + quad[3].x) / 4f
        val cy = (quad[0].y + quad[1].y + quad[2].y + quad[3].y) / 4f
        for (e in 0 until 4) {
            val a = quad[e]
            val b = quad[(e + 1) % 4]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val len = hypot(ex.toDouble(), ey.toDouble()).toFloat()
            if (len < 1e-6f) continue
            var nx = -ey / len
            var ny = ex / len
            val mx = (a.x + b.x) / 2f
            val my = (a.y + b.y) / 2f
            if ((mx + nx - cx) * (mx - cx) + (my + ny - cy) * (my - cy) < 0) {
                nx = -nx
                ny = -ny
            }
            val steps = 20
            for (s in 0 until steps) {
                val t = s.toFloat() / (steps - 1)
                val sx = a.x + ex * t
                val sy = a.y + ey * t
                val ix = (sx - nx * 5f).toInt()
                val iy = (sy - ny * 5f).toInt()
                val ox = (sx + nx * 14f).toInt()
                val oy = (sy + ny * 14f).toInt()
                if (ix !in 0 until w || iy !in 0 until h) continue
                inSum += energy(ix, iy)
                n++
                if (ox in 0 until w && oy in 0 until h) outSum += energy(ox, oy)
            }
        }
        if (n == 0 || outSum <= 1e-3f) return 0f
        val ratio = inSum / (outSum / n)
        return ((1f - ratio) / 0.6f).coerceIn(0f, 1f)
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

    // ------------------------------------------------------------------
    // Digital-page identification / uniform border
    // ------------------------------------------------------------------

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

    /**
     * Positively identifies a born-digital page: a white canvas carrying only
     * sparse, hard-edged, axis-aligned ink, with no photographic scene.
     * Deliberately strict. When in doubt this returns false.
     */
    fun isDigitalPage(gray: FloatArray, w: Int, h: Int): Boolean {
        if (w < 32 || h < 32) return false
        if (medianOf(gray) < 232f) return false
        if (isUniformBorder(gray, w, h).not()) return false

        var ink = 0
        var total = 0
        var rowTransitionH = 0
        var colTransitionV = 0
        var prevRowDark = false
        var prevColDark = false
        val step = 2
        var y = 0
        val colDark = BooleanArray(h)
        while (y < h) {
            var rowDark = false
            var x = 0
            while (x < w) {
                val v = gray[y * w + x]
                if (v < 160f) {
                    ink++
                    rowDark = true
                    colDark[y] = true
                }
                total++
                x += step
            }
            if (rowDark != prevRowDark) rowTransitionH++
            prevRowDark = rowDark
            y += step
        }
        var x2 = 0
        while (x2 < w) {
            var colHasInk = false
            var yy = 0
            while (yy < h) {
                if (colDark[yy]) colHasInk = true
                yy += step
            }
            if (colHasInk != prevColDark) colTransitionV++
            prevColDark = colHasInk
            x2 += step
        }
        if (total == 0 || ink == 0) return false

        val inkRatio = ink.toDouble() / total
        if (inkRatio > 0.22) return false

        val rows = h / step
        if (rowTransitionH < 6 || rowTransitionH > rows * 0.75) return false
        if (colTransitionV > rowTransitionH * 3) return false

        return true
    }

    /**
     * True when the whole frame is a page-like surface carrying text (a "flat
     * page" photo) rather than a scene. Used as a fallback so a dark,
     * low-contrast photo of a page that FILLS THE FRAME can still be scanned
     * automatically. It deliberately does NOT depend on absolute brightness,
     * so underexposed page photos qualify.
     *
     * Conservative on purpose: requires many scattered small ink strokes over a
     * wide area with paper dominating; a scene, a wall or a logo fails.
     */
    private fun isFlatPage(gray: FloatArray, w: Int, h: Int): Boolean {
        if (w < 48 || h < 48) return false
        val n = w * h
        val mean = localMeanIntegral(gray, w, h, 31)
        // Margin comfortably above typical sensor grain (~10) so speckle is
        // not mistaken for text, but far below real page text contrast.
        val ink = ByteArray(n)
        var inkCount = 0
        for (i in 0 until n) {
            if (gray[i] < mean[i] - 15f) {
                ink[i] = 1
                inkCount++
            }
        }
        val inkRatio = inkCount.toDouble() / n
        // A page has some ink but paper dominates; a dark blob or a photo fails.
        if (inkRatio < 0.03 || inkRatio > 0.40) return false

        // Text => many SMALL ink strokes; a scene/logo/photo => few large blobs.
        val label = IntArray(n) { -1 }
        var small = 0
        var large = 0
        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1
        var comp = 0
        for (seed in 0 until n) {
            if (ink[seed] == 0.toByte() || label[seed] != -1) continue
            val stack = ArrayDeque<Int>()
            stack.add(seed)
            label[seed] = comp
            var size = 0
            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                size++
                val x = idx % w
                val y = idx / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h) {
                            val ni = ny * w + nx
                            if (ink[ni] != 0.toByte() && label[ni] == -1) {
                                label[ni] = comp
                                stack.add(ni)
                            }
                        }
                    }
                }
            }
            comp++
            // Ignore 1-3px specks (sensor grain): real text strokes are larger.
            when {
                size < 4 -> {}
                size <= 400 -> small++
                else -> large++
            }
        }
        if (small < 30 || large > small) return false
        // Ink must be spread across the frame (a page of text), not one corner.
        val spanX = (maxX - minX).toFloat() / w
        val spanY = (maxY - minY).toFloat() / h
        return spanX >= 0.65f && spanY >= 0.65f
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private fun clampToImage(c: CornerPoints, w: Float, h: Float): CornerPoints {
        fun cl(p: PointF) = PointF(p.x.coerceIn(0f, w - 1f), p.y.coerceIn(0f, h - 1f))
        return CornerPoints(cl(c.topLeft), cl(c.topRight), cl(c.bottomRight), cl(c.bottomLeft))
    }

    /**
     * Result for a recognised flat page photo: the full frame (minus a hairline
     * inset so the quad passes area validation) is the document. HIGH so it
     * auto-processes; [DetectionResult.flatPage] records why.
     */
    private fun flatPageResult(
        stats: DetectionStats,
        pool: List<ScoredQuad>,
        score: Float
    ): DetectionResult {
        val page = CornerPoints(
            PointF(0.01f, 0.01f), PointF(0.99f, 0.01f),
            PointF(0.99f, 0.99f), PointF(0.01f, 0.99f)
        )
        stats.confidence = "HIGH"
        stats.selectedScore = score
        stats.selectedCorners = page.toList()
        lastStats = stats
        return DetectionResult(
            page, DetectionConfidence.HIGH, score, pool,
            "flat page photo detected; full frame used as the document",
            flatPage = true
        )
    }

    // ------------------------------------------------------------------
    // Debug previews — same code path as detection
    // ------------------------------------------------------------------

    /**
     * Explains the score of an explicit quad (normalized corners) against this
     * bitmap: per-edge (support, polarity), component scores and validity.
     */
    fun explainQuad(bitmap: Bitmap, corners: CornerPoints): String {
        return try {
            val maxDim = SAMPLE_FULL
            val scale = min(1f, maxDim.toFloat() / max(bitmap.width, bitmap.height))
            val sw = max(80, (bitmap.width * scale).toInt())
            val sh = max(80, (bitmap.height * scale).toInt())
            val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
            try {
                val pixels = IntArray(sw * sh)
                small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
                val gray = toGrayscale(pixels)
                val blurred = gaussianBlur5x5(gray, sw, sh)
                val (gx, gy, mag) = sobel(blurred, sw, sh)
                val median = medianOf(blurred)
                val high = ((1f + 0.33f) * median).toInt().coerceIn(40, 160).toFloat()
                val low = ((1f - 0.33f) * median * 0.5f).toInt().coerceIn(15, 80).toFloat()
                val edges = canny(gx, gy, mag, sw, sh, low, high)
                val dilated = dilate3x3(dilate3x3(edges, sw, sh), sw, sh)
                val px = corners.scale(sw.toFloat(), sh.toFloat())
                val quad = QuadValidator.orderPoints(px.toList()).toList()
                val metrics = QuadValidator.edgeMetrics(quad, dilated, blurred, sw, sh)
                val scored = scoreQuad(quad, abs(QuadValidator.polygonArea(quad)), dilated, dilated, blurred, sw, sh, ScoredQuad.SOURCE_CONTOUR)
                val v = QuadValidator.validate(px, sw.toFloat(), sh.toFloat())
                val sb = StringBuilder()
                sb.append("valid=${v.valid}(${v.reason}) ")
                metrics.forEachIndexed { i, m ->
                    sb.append("e$i sup=${"%.2f".format(m.first)} pol=${"%.2f".format(m.second)} ")
                }
                sb.append("total=${"%.3f".format(scored.score)} geom=${"%.2f".format(scored.geometryScore)} edge=${"%.2f".format(scored.edgeSupportScore)} pol=${"%.2f".format(scored.polarityScore)}")
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
        val maxDim = SAMPLE_LIVE
        val scale = min(1f, maxDim.toFloat() / max(bitmap.width, bitmap.height))
        val sw = max(80, (bitmap.width * scale).toInt())
        val sh = max(80, (bitmap.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        try {
            val pixels = IntArray(sw * sh)
            small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            val gray = toGrayscale(pixels)
            val blurred = gaussianBlur5x5(gray, sw, sh)
            val (gx, gy, mag) = sobel(blurred, sw, sh)
            val median = medianOf(blurred)
            val high = ((1f + 0.33f) * median).toInt().coerceIn(40, 160).toFloat()
            val low = ((1f - 0.33f) * median * 0.5f).toInt().coerceIn(15, 80).toFloat()
            val edges = canny(gx, gy, mag, sw, sh, low, high)
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
        // Candidate points live in detection-sample space, which is the input
        // uniformly scaled to SAMPLE_FULL — same aspect as the image.
        val sampleScale = min(1f, SAMPLE_FULL.toFloat() / max(bitmap.width, bitmap.height))
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
