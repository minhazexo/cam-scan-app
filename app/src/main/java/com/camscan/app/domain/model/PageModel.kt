package com.camscan.app.domain.model

data class PageModel(
    val id: String,
    val documentId: String,
    val pageIndex: Int,
    val originalImagePath: String,
    val processedImagePath: String,
    val filterMode: FilterMode,
    val rotationDegrees: Int,
    val cropCorners: CornerPoints,
    val ocrText: String? = null
)
