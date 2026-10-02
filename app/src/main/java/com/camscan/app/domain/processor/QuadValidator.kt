package com.camscan.app.domain.processor

import android.graphics.PointF
import com.camscan.app.domain.model.CornerPoints
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Geometric validation for document quadrilaterals.
 *
 * Centralises every validity rule so detector, warper and UI share
 * identical acceptance criteria. In particular this enforces the
 * "exclude image border / camera frame" rule.
 */
object QuadValidator {

    data class ValidationResult(val valid: Boolean, val reason: String = "")

    /** Minimum quad area as a fraction of the image area. */
    const val MIN_AREA_FRACTION = 0.04f

    /** Maximum quad area; anything larger is treated as the camera frame. */
    const val MAX_AREA_FRACTION = 0.97f

    /**
     * If the quad covers more than this fraction AND touches all four image
     * borders, it is rejected as the camera frame unless edge support is
     * exceptionally strong (handled by the caller via [isFullFrame]).
     */
    const val FULL_FRAME_FRACTION = 0.94f

    private const val MIN_ANGLE_DEG = 55.0
    private const val MAX_ANGLE_DEG = 125.0

    /** Accepts portrait/landscape page shapes, rejects extreme slivers. */
    private const val MIN_ASPECT = 0.35f
    private const val MAX_ASPECT = 2.9f

    fun orderPoints(pts: List<PointF>): CornerPoints {
        if (pts.size != 4) return CornerPoints.defaultNormalized()
        // Robust ordering: sort by angle around centroid, then rotate so
        // index 0 is the top-left (minimum x+y).
        val cx = pts.map { it.x }.average().toFloat()
        val cy = pts.map { it.y }.average().toFloat()
        val byAngle = pts.sortedBy { atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
        // byAngle is clockwise starting from -pi (left side). Find TL.
        var tlIdx = 0
        var best = Float.MAX_VALUE
        byAngle.forEachIndexed { i, p ->
            val s = p.x + p.y
            if (s < best) {
                best = s
                tlIdx = i
            }
        }
        // Reorder so TL is first, keeping clockwise order. The angle sort
        // above is counter/clockwise depending on y direction (y grows down,
        // so atan2 order is clockwise visually). Ensure TL,TR,BR,BL.
        val ordered = (0 until 4).map { byAngle[(tlIdx + it) % 4] }
        // Verify orientation: ordered[1] should be to the right of ordered[0].
        // If ordered[1] is below ordered[0] instead, reverse winding.
        val a = ordered[0]
        val b = ordered[1]
        val c = ordered[2]
        val cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        val fixed = if (cross < 0) {
            listOf(ordered[0], ordered[3], ordered[2], ordered[1])
        } else {
            ordered
        }
        return CornerPoints(fixed[0], fixed[1], fixed[2], fixed[3])
    }

    fun validate(corners: CornerPoints, imageW: Float, imageH: Float): ValidationResult {
        if (imageW <= 0f || imageH <= 0f) return ValidationResult(false, "invalid image size")
        val pts = corners.toList()
        if (pts.any { it.x.isNaN() || it.y.isNaN() }) {
            return ValidationResult(false, "NaN corner")
        }
        // Points must be inside (or marginally outside due to 1% guard) image.
        for (p in pts) {
            if (p.x < -imageW * 0.05f || p.x > imageW * 1.05f ||
                p.y < -imageH * 0.05f || p.y > imageH * 1.05f
            ) {
                return ValidationResult(false, "corner outside image")
            }
        }
        // No duplicate / collapsed points.
        for (i in 0 until 4) {
            val d = hypot(
                (pts[i].x - pts[(i + 1) % 4].x).toDouble(),
                (pts[i].y - pts[(i + 1) % 4].y).toDouble()
            )
            if (d < min(imageW, imageH) * 0.05) {
                return ValidationResult(false, "collapsed edge")
            }
        }
        // Convex + non-crossing: consistent winding sign.
        val signs = (0 until 4).map { i ->
            val p0 = pts[i]
            val p1 = pts[(i + 1) % 4]
            val p2 = pts[(i + 2) % 4]
            cross(p0, p1, p2)
        }
        if (!(signs.all { it > 0 } || signs.all { it < 0 })) {
            return ValidationResult(false, "non-convex or self-intersecting")
        }
        // Interior angles within tolerance.
        for (i in 0 until 4) {
            val ang = interiorAngleDeg(pts[i], pts[(i + 1) % 4], pts[(i + 2) % 4])
            if (ang < MIN_ANGLE_DEG || ang > MAX_ANGLE_DEG) {
                return ValidationResult(false, "bad angle %.1f".format(ang))
            }
        }
        val area = abs(polygonArea(pts)).toDouble()
        val imgArea = imageW.toDouble() * imageH.toDouble()
        val frac = area / imgArea
        if (frac < MIN_AREA_FRACTION) return ValidationResult(false, "area too small")
        if (frac > MAX_AREA_FRACTION) return ValidationResult(false, "area covers full frame")

        val (wA, hA) = quadDims(pts)
        if (wA <= 0 || hA <= 0) return ValidationResult(false, "degenerate dims")
        // Aspect check on portrait-normalised ratio instead: allow wide range.
        val portraitAspect = min(wA, hA) / max(wA, hA)
        if (portraitAspect < MIN_ASPECT || 1f / portraitAspect > MAX_ASPECT) {
            return ValidationResult(false, "implausible aspect")
        }
        return ValidationResult(true)
    }

    /**
     * True when the quad is essentially the image boundary (camera frame).
     * Such quads must NEVER be auto-accepted (Absolute Rule #3).
     */
    fun isFullFrame(corners: CornerPoints, imageW: Float, imageH: Float): Boolean {
        val pts = corners.toList()
        val area = abs(polygonArea(pts)) / (imageW * imageH)
        if (area < FULL_FRAME_FRACTION) return false
        val margin = 0.035f
        val touchesLeft = pts.any { it.x < imageW * margin }
        val touchesRight = pts.any { it.x > imageW * (1f - margin) }
        val touchesTop = pts.any { it.y < imageH * margin }
        val touchesBottom = pts.any { it.y > imageH * (1f - margin) }
        // Full frame only if it spans nearly the whole sensor on both axes.
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        val spansX = (maxX - minX) > imageW * 0.94f
        val spansY = (maxY - minY) > imageH * 0.94f
        return touchesLeft && touchesRight && touchesTop && touchesBottom && spansX && spansY
    }

    /** Normalise a normalized [CornerPoints] into pixel space. */
    private fun denorm(c: CornerPoints, w: Float, h: Float): CornerPoints {
        return if (c.isNormalized()) c.scale(w, h) else c
    }

    /**
     * True when a large quad is essentially the camera frame: it covers more
     * than 90% of the image and either (a) any corner is within 2px of an image
     * edge (frame-touching perspective overshoot), or (b) it spans more than 97%
     * of the image on both axes (nearly full frame with only hairline margins).
     *
     * The existing [isFullFrame] only flags quads touching ALL FOUR borders with
     * >= 94% area; this catches near-full quads that touch fewer sides or have
     * thin margins, which would otherwise slip through [validate] and leak frame
     * background into the scan.
     */
    fun isNearFrame(corners: CornerPoints, imageW: Float, imageH: Float): Boolean {
        val cp = denorm(corners, imageW, imageH)
        val area = abs(polygonArea(cp.toList())) / (imageW * imageH)
        if (area <= 0.90f) return false
        fun touchX(p: PointF) = p.x < 2f || p.x > imageW - 2f
        fun touchY(p: PointF) = p.y < 2f || p.y > imageH - 2f
        if (cp.toList().any { touchX(it) || touchY(it) }) return true
        val xs = cp.toList().map { it.x }; val ys = cp.toList().map { it.y }
        val spanX = (xs.maxOfOrNull { it }!! - xs.minOfOrNull { it }!!) / imageW
        val spanY = (ys.maxOfOrNull { it }!! - ys.minOfOrNull { it }!!) / imageH
        return spanX > 0.97f && spanY > 0.97f
    }

    fun polygonArea(pts: List<PointF>): Float {
        var a = 0f
        for (i in pts.indices) {
            val p = pts[i]
            val q = pts[(i + 1) % pts.size]
            a += p.x * q.y - q.x * p.y
        }
        return a / 2f
    }

    fun quadDims(pts: List<PointF>): Pair<Float, Float> {
        val topW = hypot((pts[1].x - pts[0].x).toDouble(), (pts[1].y - pts[0].y).toDouble()).toFloat()
        val botW = hypot((pts[2].x - pts[3].x).toDouble(), (pts[2].y - pts[3].y).toDouble()).toFloat()
        val leftH = hypot((pts[3].x - pts[0].x).toDouble(), (pts[3].y - pts[0].y).toDouble()).toFloat()
        val rightH = hypot((pts[2].x - pts[1].x).toDouble(), (pts[2].y - pts[1].y).toDouble()).toFloat()
        return Pair(max(topW, botW), max(leftH, rightH))
    }

    private fun cross(a: PointF, b: PointF, c: PointF): Float {
        return (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
    }

    private fun interiorAngleDeg(a: PointF, b: PointF, c: PointF): Double {
        val v1x = (a.x - b.x).toDouble()
        val v1y = (a.y - b.y).toDouble()
        val v2x = (c.x - b.x).toDouble()
        val v2y = (c.y - b.y).toDouble()
        val dot = v1x * v2x + v1y * v2y
        val n1 = hypot(v1x, v1y)
        val n2 = hypot(v2x, v2y)
        if (n1 < 1e-9 || n2 < 1e-9) return 0.0
        val cosA = (dot / (n1 * n2)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(cosA))
    }

    /** Douglas-Peucker polyline simplification. */
    fun approxPolyDP(points: List<PointF>, epsilon: Float): List<PointF> {
        if (points.size < 3) return points
        // Close the ring for perimeter computation.
        val closed = if (points.first() == points.last()) points else points + points.first()
        return douglasPeucker(closed, epsilon).let {
            // Drop duplicated closing point.
            if (it.size > 1 && it.first() == it.last()) it.dropLast(1) else it
        }
    }

    private fun douglasPeucker(pts: List<PointF>, eps: Float): List<PointF> {
        if (pts.size <= 2) return pts
        var maxDist = 0f
        var index = 0
        val start = pts.first()
        val end = pts.last()
        for (i in 1 until pts.size - 1) {
            val d = perpendicularDistance(pts[i], start, end)
            if (d > maxDist) {
                maxDist = d
                index = i
            }
        }
        return if (maxDist > eps) {
            val left = douglasPeucker(pts.subList(0, index + 1), eps)
            val right = douglasPeucker(pts.subList(index, pts.size), eps)
            left.dropLast(1) + right
        } else {
            listOf(start, end)
        }
    }

    private fun perpendicularDistance(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < 1e-9f) return hypot((p.x - a.x).toDouble(), (p.y - a.y).toDouble()).toFloat()
        return abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / len
    }

    /** Monotone-chain convex hull. Returns hull in CCW order without dup end. */
    fun convexHull(points: List<PointF>): List<PointF> {
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y })).distinct()
        if (sorted.size <= 3) return sorted
        fun crossO(o: PointF, a: PointF, b: PointF): Float {
            return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        }
        val lower = mutableListOf<PointF>()
        for (p in sorted) {
            while (lower.size >= 2 && crossO(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }
        val upper = mutableListOf<PointF>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && crossO(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    fun perimeter(pts: List<PointF>): Float {
        var p = 0f
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            p += hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
        }
        return p
    }

    /** Fraction of quad perimeter supported by edge pixels (dilated edge map). */
    fun edgeSupport(
        quad: List<PointF>,
        dilatedEdges: ByteArray,
        w: Int,
        h: Int,
        samplesPerEdge: Int = 60
    ): Float {
        var hit = 0
        var total = 0
        for (e in 0 until 4) {
            val a = quad[e]
            val b = quad[(e + 1) % 4]
            for (s in 0 until samplesPerEdge) {
                val t = s.toFloat() / (samplesPerEdge - 1)
                val x = (a.x + (b.x - a.x) * t).toInt().coerceIn(0, w - 1)
                val y = (a.y + (b.y - a.y) * t).toInt().coerceIn(0, h - 1)
                total++
                if (dilatedEdges[y * w + x] != 0.toByte()) hit++
            }
        }
        return if (total == 0) 0f else hit.toFloat() / total
    }

    /**
     * Per-edge (support, polarity) pairs in TL->TR->BR->BL->TL order.
     * Used by scoring and by the debug screen.
     */
    fun edgeMetrics(
        quad: List<PointF>,
        dilatedEdges: ByteArray,
        gray: FloatArray,
        w: Int,
        h: Int,
        samplesPerEdge: Int = 48
    ): List<Pair<Float, Float>> {
        val out = mutableListOf<Pair<Float, Float>>()
        if (gray.size != w * h) return List(4) { Pair(0f, 0f) }
        val cx = (quad[0].x + quad[1].x + quad[2].x + quad[3].x) / 4f
        val cy = (quad[0].y + quad[1].y + quad[2].y + quad[3].y) / 4f
        // Regional offset: far enough to clear text strokes and blur halos so
        // only a true paper-vs-scene step counts (text lines fake ±4px).
        val d = 10f
        val step = 15f
        for (e in 0 until 4) {
            val a = quad[e]
            val b = quad[(e + 1) % 4]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val len = hypot(ex.toDouble(), ey.toDouble()).toFloat()
            if (len < 1e-6f) {
                out.add(Pair(0f, 0f))
                continue
            }
            var nx = -ey / len
            var ny = ex / len
            val mx = (a.x + b.x) / 2f
            val my = (a.y + b.y) / 2f
            if ((mx + nx - cx) * (mx - cx) + (my + ny - cy) * (my - cy) < 0) {
                nx = -nx
                ny = -ny
            }
            var hit = 0
            var pos = 0
            var neg = 0
            var total = 0
            for (s in 0 until samplesPerEdge) {
                val t = s.toFloat() / (samplesPerEdge - 1)
                val sx = a.x + ex * t
                val sy = a.y + ey * t
                val px = sx.toInt().coerceIn(0, w - 1)
                val py = sy.toInt().coerceIn(0, h - 1)
                total++
                if (dilatedEdges[py * w + px] != 0.toByte()) hit++
                val ix = (sx - nx * d).toInt()
                val iy = (sy - ny * d).toInt()
                val ox = (sx + nx * d).toInt()
                val oy = (sy + ny * d).toInt()
                if (ix !in 0 until w || iy !in 0 until h || ox !in 0 until w || oy !in 0 until h) continue
                val diff = gray[iy * w + ix] - gray[oy * w + ox]
                if (diff > step) pos++ else if (diff < -step) neg++
            }
            val support = if (total == 0) 0f else hit.toFloat() / total
            val polarity = if (total == 0) 0f else max(pos, neg).toFloat() / total
            out.add(Pair(support, polarity))
        }
        return out
    }

    /**
     * Boundary polarity: a REAL page edge separates paper from scene, so
     * brightness sampled just inside vs just outside the edge differs
     * consistently along the whole edge. Interior text lines, texture and
     * random quad hallucinations have ~zero straddling difference.
     *
     * Returns 0..1 averaged over the four edges.
     */
    fun boundaryPolarity(
        quad: List<PointF>,
        gray: FloatArray,
        w: Int,
        h: Int,
        samplesPerEdge: Int = 48
    ): Float {
        if (gray.size != w * h) return 0f
        // Use an empty edge map: support is irrelevant here, only polarity.
        val perEdge = edgeMetrics(quad, ByteArray(w * h), gray, w, h, samplesPerEdge)
        if (perEdge.isEmpty()) return 0f
        return perEdge.map { it.second }.average().toFloat().coerceIn(0f, 1f)
    }
}
