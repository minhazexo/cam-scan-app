package com.camscan.app.domain.model

import android.graphics.PointF

data class CornerPoints(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF
) {
    fun toList(): List<PointF> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun isNormalized(): Boolean {
        return topLeft.x in 0f..1f && topLeft.y in 0f..1f &&
                bottomRight.x in 0f..1f && bottomRight.y in 0f..1f
    }

    fun scale(width: Float, height: Float): CornerPoints {
        return CornerPoints(
            PointF(topLeft.x * width, topLeft.y * height),
            PointF(topRight.x * width, topRight.y * height),
            PointF(bottomRight.x * width, bottomRight.y * height),
            PointF(bottomLeft.x * width, bottomLeft.y * height)
        )
    }

    fun normalize(width: Float, height: Float): CornerPoints {
        if (width <= 0f || height <= 0f) return defaultNormalized()
        return CornerPoints(
            PointF(topLeft.x / width, topLeft.y / height),
            PointF(topRight.x / width, topRight.y / height),
            PointF(bottomRight.x / width, bottomRight.y / height),
            PointF(bottomLeft.x / width, bottomLeft.y / height)
        )
    }

    companion object {
        fun defaultNormalized(): CornerPoints {
            return CornerPoints(
                PointF(0.05f, 0.05f),
                PointF(0.95f, 0.05f),
                PointF(0.95f, 0.95f),
                PointF(0.05f, 0.95f)
            )
        }
    }
}
