package com.camscan.app.domain.model

/**
 * Confidence level for automatic document detection.
 *
 * HIGH   -> automatic scan may proceed.
 * MEDIUM -> show detected corners for user confirmation.
 * LOW    -> automatic detection failed; manual 4-corner selection is required.
 *
 * There is intentionally NO "use full photo" state. LOW confidence must
 * always route to manual corner selection.
 */
enum class DetectionConfidence {
    HIGH,
    MEDIUM,
    LOW
}

/**
 * Result of [com.camscan.app.domain.processor.DocumentDetector.detectDocument].
 *
 * @param corners normalized (0..1) quad, ordered TL/TR/BR/BL.
 * @param score 0..1 ranking score of the selected quad.
 * @param allCandidates every ranked candidate (best first), for debug UI.
 * @param reason human-readable explanation, especially for LOW.
 */
data class DetectionResult(
    val corners: CornerPoints?,
    val confidence: DetectionConfidence,
    val score: Float,
    val allCandidates: List<ScoredQuad> = emptyList(),
    val reason: String = ""
) {
    val needsManual: Boolean get() = confidence == DetectionConfidence.LOW || corners == null
    val needsConfirmation: Boolean get() = confidence == DetectionConfidence.MEDIUM
}

/**
 * A quadrilateral candidate with its ranking score breakdown.
 * Points are in the detection-sample pixel space unless stated otherwise.
 */
data class ScoredQuad(
    val topLeft: android.graphics.PointF,
    val topRight: android.graphics.PointF,
    val bottomRight: android.graphics.PointF,
    val bottomLeft: android.graphics.PointF,
    val score: Float,
    val areaScore: Float = 0f,
    val rectangularityScore: Float = 0f,
    val edgeSupportScore: Float = 0f,
    val angleScore: Float = 0f,
    val aspectScore: Float = 0f,
    val borderScore: Float = 0f,
    val polarityScore: Float = 0f,
    val areaFraction: Float = 0f
) {
    fun toCornerPoints(sampleW: Float, sampleH: Float): CornerPoints {
        return CornerPoints(
            android.graphics.PointF(topLeft.x / sampleW, topLeft.y / sampleH),
            android.graphics.PointF(topRight.x / sampleW, topRight.y / sampleH),
            android.graphics.PointF(bottomRight.x / sampleW, bottomRight.y / sampleH),
            android.graphics.PointF(bottomLeft.x / sampleW, bottomLeft.y / sampleH)
        )
    }
}
