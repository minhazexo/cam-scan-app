package com.camscan.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["documentId"])]
)
data class PageEntity(
    @PrimaryKey
    val id: String,
    val documentId: String,
    val pageIndex: Int,
    val originalImagePath: String,
    val processedImagePath: String,
    val filterMode: String,
    val rotationDegrees: Int,
    val cropCornersJson: String?,
    val ocrText: String?
)
