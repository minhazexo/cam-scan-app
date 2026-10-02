package com.camscan.app.domain.model

import android.graphics.PointF

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
 * The workflow action a detection result authorises.
 *
 * This is the *semantic* control surface. Callers that run unattended
 * (batch capture, gallery import, PDF import) must branch on this, not on
 * [DetectionConfidence], so that a merely-uncertain quad can never be
 * auto-processed by accident.
 */
enum class DetectionAction {
    /** Confident detection: the caller may run the strict pipeline unattended. */
    AUTO_PROCESS,

    /** A quad was found but is uncertain: the user must confirm/adjust it. */
    USER_CONFIRM,

    /** No trustworthy quad: the user must pick all four corners. */
    USER_SELECT_CORNERS;

    val requiresUser: Boolean get() = this != AUTO_PROCESS
}

/**
 * State of the live camera overlay.
 *
 * The overlay must NEVER draw a fake document rectangle: a fixed
 * 12%-88% inset was misleading because it implied a detection that did
 * not happen. A quad is drawn only for [DETECTED] / [CONFIRMATION_REQUIRED];
 * otherwise the UI shows a subtle "move closer" hint.
 */
enum class DetectionOverlayState {
    /** No trustworthy candidate: show guidance, do NOT draw corners. */
    NOT_DETECTED,

    /** A confident quad is tracked; draw it solid. */
    DETECTED,

    /** A candidate exists but is uncertain; draw it dashed / ask to confirm. */
    CONFIRMATION_REQUIRED
}

/**
 * Live (per-preview-frame) detection result handed to the camera overlay.
 *
 * @param state overlay state.
 * @param corners normalized quad, present only for [DetectionOverlayState.DETECTED]
 *   and [DetectionOverlayState.CONFIRMATION_REQUIRED].
 */
data class LiveDetection(
    val state: DetectionOverlayState,
    val corners: CornerPoints? = null,
    val confidence: DetectionConfidence = DetectionConfidence.LOW
) {
    /** True when the overlay should draw a document quad. */
    val hasQuad: Boolean
        get() = corners != null && state != DetectionOverlayState.NOT_DETECTED

    companion object {
        val NONE = LiveDetection(DetectionOverlayState.NOT_DETECTED, null, DetectionConfidence.LOW)

        /** Maps a full [DetectionResult] to overlay state. */
        fun from(result: DetectionResult): LiveDetection {
            val corners = result.corners
            return when {
                corners == null -> NONE
                result.confidence == DetectionConfidence.HIGH || result.digitalPage ->
                    LiveDetection(DetectionOverlayState.DETECTED, corners, result.confidence)
                else -> LiveDetection(DetectionOverlayState.CONFIRMATION_REQUIRED, corners, result.confidence)
            }
        }
    }
}

/**
 * Result of [com.camscan.app.domain.processor.DocumentDetector.detectDocument].
 *
 * @param corners normalized (0..1) quad, ordered TL/TR/BR/BL.
 * @param score 0..1 ranking score of the selected quad.
 * @param allCandidates every ranked candidate (best first), for debug UI.
 * @param reason human-readable explanation, especially for LOW.
 * @param digitalPage true when the input was *positively identified* as a
 *   genuine born-digital page (clean vector/text PDF page). Only then may the
 *   full page canvas legitimately act as the document quad.
 */
data class DetectionResult(
    val corners: CornerPoints?,
    val confidence: DetectionConfidence,
    val score: Float,
    val allCandidates: List<ScoredQuad> = emptyList(),
    val reason: String = "",
    val digitalPage: Boolean = false
) {
    /** Workflow action for this result. */
    val action: DetectionAction
        get() = when {
            corners == null -> DetectionAction.USER_SELECT_CORNERS
            digitalPage -> DetectionAction.AUTO_PROCESS
            confidence == DetectionConfidence.HIGH -> DetectionAction.AUTO_PROCESS
            confidence == DetectionConfidence.MEDIUM -> DetectionAction.USER_CONFIRM
            else -> DetectionAction.USER_SELECT_CORNERS
        }

    /** True only for LOW: the user must place all four corners by hand. */
    val needsManual: Boolean get() = action == DetectionAction.USER_SELECT_CORNERS

    /** True for MEDIUM: a quad exists but the user must confirm it. */
    val needsConfirmation: Boolean get() = action == DetectionAction.USER_CONFIRM

    /**
     * True when an unattended caller must NOT save a final scan. Correctness
     * beats always producing an image: these pages stay pending.
     */
    val blocksAutoProcessing: Boolean get() = action.requiresUser
}

/**
 * A quadrilateral candidate with its ranking score breakdown.
 * Points are in the detection-sample pixel space unless stated otherwise.
 */
data class ScoredQuad(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF,
    /** Final calibrated ranking score in 0..1 (higher = more document-like). */
    val score: Float,
    val areaScore: Float = 0f,
    val rectangularityScore: Float = 0f,
    val edgeSupportScore: Float = 0f,
    val angleScore: Float = 0f,
    val aspectScore: Float = 0f,
    val borderScore: Float = 0f,
    val polarityScore: Float = 0f,
    /** Weakest single-edge boundary polarity (0..1): a real page has a
     *  luminance step on EVERY side, a texture patch does not. */
    val polarityMinScore: Float = 0f,
    val parallelScore: Float = 0f,
    val contrastScore: Float = 0f,
    val textureScore: Float = 0f,
    val continuityScore: Float = 0f,
    val areaFraction: Float = 0f,
    /** Which discovery pass produced this candidate ([SOURCE_*]). */
    val source: Int = SOURCE_CONTOUR,
    /** Combined geometry evidence (rectangularity + angles + area). */
    val geometryScore: Float = 0f,
    /** Perspective/shape evidence (parallel edges + aspect plausibility). */
    val perspectiveScore: Float = 0f,
    /** Photometric evidence (polarity + contrast + texture). */
    val photometricScore: Float = 0f,
    /** Context evidence (frame margin / area plausibility). */
    val contextScore: Float = 0f
) {
    fun toCornerPoints(sampleW: Float, sampleH: Float): CornerPoints {
        return CornerPoints(
            PointF(topLeft.x / sampleW, topLeft.y / sampleH),
            PointF(topRight.x / sampleW, topRight.y / sampleH),
            PointF(bottomRight.x / sampleW, bottomRight.y / sampleH),
            PointF(bottomLeft.x / sampleW, bottomLeft.y / sampleH)
        )
    }

    /** Points as a list in TL/TR/BR/BL order. */
    fun toList(): List<PointF> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    companion object {
        /** Contour on a Canny edge map. */
        const val SOURCE_CONTOUR = 0
        /** Contour on an adaptive-threshold binary map. */
        const val SOURCE_ADAPTIVE = 1
        /** Lines intersected from a Hough transform. */
        const val SOURCE_HOUGH = 2
        /** Face/silhouette of a connected non-edge region (page body). */
        const val SOURCE_REGION = 3
    }
}
