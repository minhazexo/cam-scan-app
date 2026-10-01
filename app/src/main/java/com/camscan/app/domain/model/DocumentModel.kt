package com.camscan.app.domain.model

data class DocumentModel(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int,
    val thumbnailPath: String?
)
