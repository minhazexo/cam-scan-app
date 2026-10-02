package com.camscan.app.data.repository

import com.camscan.app.data.local.dao.DocumentDao
import com.camscan.app.data.local.dao.PageDao
import com.camscan.app.data.local.entity.DocumentEntity
import com.camscan.app.data.local.entity.PageEntity
import com.camscan.app.data.storage.StorageManager
import com.camscan.app.domain.model.CornerPoints
import com.camscan.app.domain.model.DocumentModel
import com.camscan.app.domain.model.FilterMode
import com.camscan.app.domain.model.PageModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

class DocumentRepository(
    private val documentDao: DocumentDao,
    private val pageDao: PageDao,
    private val storageManager: StorageManager
) {

    fun getAllDocuments(): Flow<List<DocumentModel>> {
        return documentDao.getAllDocuments().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    fun searchDocuments(query: String): Flow<List<DocumentModel>> {
        return documentDao.searchDocuments(query).map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    suspend fun getDocumentById(id: String): DocumentModel? {
        return withContext(Dispatchers.IO) {
            documentDao.getDocumentById(id)?.toDomainModel()
        }
    }

    fun getPagesForDocumentFlow(documentId: String): Flow<List<PageModel>> {
        return pageDao.getPagesForDocumentFlow(documentId).map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    suspend fun getPagesForDocument(documentId: String): List<PageModel> {
        return withContext(Dispatchers.IO) {
            pageDao.getPagesForDocument(documentId).map { it.toDomainModel() }
        }
    }

    suspend fun createDocument(
        title: String,
        pages: List<Pair<String, String>> // Pair<originalPath, processedPath>
    ): DocumentModel {
        return withContext(Dispatchers.IO) {
            val docId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val pageEntities = pages.mapIndexed { index, pair ->
                PageEntity(
                    id = UUID.randomUUID().toString(),
                    documentId = docId,
                    pageIndex = index,
                    originalImagePath = pair.first,
                    processedImagePath = pair.second,
                    filterMode = FilterMode.AUTO.name,
                    rotationDegrees = 0,
                    cropCornersJson = cornerPointsToJson(CornerPoints.defaultNormalized()),
                    ocrText = null,
                    // A "page" whose processed image is just a copy of the
                    // original means detection failed; flag it for the user.
                    needsManualCorrection = if (pair.first == pair.second) 1 else 0
                )
            }

            val thumbnail = pages.firstOrNull()?.second
            val docEntity = DocumentEntity(
                id = docId,
                title = title.ifBlank { "Doc_${now}" },
                createdAt = now,
                updatedAt = now,
                pageCount = pages.size,
                thumbnailPath = thumbnail
            )

            documentDao.insertDocument(docEntity)
            pageDao.insertPages(pageEntities)

            docEntity.toDomainModel()
        }
    }

    suspend fun addPageToDocument(
        documentId: String,
        originalPath: String,
        processedPath: String
    ): PageModel {
        return withContext(Dispatchers.IO) {
            val pages = pageDao.getPagesForDocument(documentId)
            val newIndex = pages.size
            val pageEntity = PageEntity(
                id = UUID.randomUUID().toString(),
                documentId = documentId,
                pageIndex = newIndex,
                originalImagePath = originalPath,
                processedImagePath = processedPath,
                filterMode = FilterMode.AUTO.name,
                rotationDegrees = 0,
                cropCornersJson = cornerPointsToJson(CornerPoints.defaultNormalized()),
                ocrText = null,
                needsManualCorrection = if (originalPath == processedPath) 1 else 0
            )
            pageDao.insertPage(pageEntity)

            val doc = documentDao.getDocumentById(documentId)
            if (doc != null) {
                val updated = doc.copy(
                    pageCount = newIndex + 1,
                    updatedAt = System.currentTimeMillis(),
                    thumbnailPath = doc.thumbnailPath ?: processedPath
                )
                documentDao.updateDocument(updated)
            }

            pageEntity.toDomainModel()
        }
    }

    suspend fun updatePage(page: PageModel) {
        withContext(Dispatchers.IO) {
            val entity = PageEntity(
                id = page.id,
                documentId = page.documentId,
                pageIndex = page.pageIndex,
                originalImagePath = page.originalImagePath,
                processedImagePath = page.processedImagePath,
                filterMode = page.filterMode.name,
                rotationDegrees = page.rotationDegrees,
                cropCornersJson = cornerPointsToJson(page.cropCorners),
                ocrText = page.ocrText,
                needsManualCorrection = if (page.needsManualCorrection) 1 else 0
            )
            pageDao.updatePage(entity)

            val doc = documentDao.getDocumentById(page.documentId)
            if (doc != null) {
                documentDao.updateDocument(doc.copy(updatedAt = System.currentTimeMillis()))
            }
        }
    }

    suspend fun reorderPages(documentId: String, updatedPages: List<PageModel>) {
        withContext(Dispatchers.IO) {
            val entities = updatedPages.mapIndexed { index, page ->
                PageEntity(
                    id = page.id,
                    documentId = page.documentId,
                    pageIndex = index,
                    originalImagePath = page.originalImagePath,
                    processedImagePath = page.processedImagePath,
                    filterMode = page.filterMode.name,
                    rotationDegrees = page.rotationDegrees,
                    cropCornersJson = cornerPointsToJson(page.cropCorners),
                    ocrText = page.ocrText,
                    needsManualCorrection = if (page.needsManualCorrection) 1 else 0
                )
            }
            pageDao.insertPages(entities)

            val firstThumb = updatedPages.firstOrNull()?.processedImagePath
            val doc = documentDao.getDocumentById(documentId)
            if (doc != null) {
                documentDao.updateDocument(
                    doc.copy(
                        pageCount = updatedPages.size,
                        updatedAt = System.currentTimeMillis(),
                        thumbnailPath = firstThumb
                    )
                )
            }
        }
    }

    suspend fun deletePage(page: PageModel) {
        withContext(Dispatchers.IO) {
            pageDao.deletePage(page.id)
            storageManager.deleteFile(page.originalImagePath)
            storageManager.deleteFile(page.processedImagePath)

            val remainingPages = pageDao.getPagesForDocument(page.documentId)
            if (remainingPages.isEmpty()) {
                deleteDocument(page.documentId)
            } else {
                // reindex
                val reindexed = remainingPages.mapIndexed { index, entity ->
                    entity.copy(pageIndex = index)
                }
                pageDao.insertPages(reindexed)

                val doc = documentDao.getDocumentById(page.documentId)
                if (doc != null) {
                    documentDao.updateDocument(
                        doc.copy(
                            pageCount = remainingPages.size,
                            updatedAt = System.currentTimeMillis(),
                            thumbnailPath = remainingPages.first().processedImagePath
                        )
                    )
                }
            }
        }
    }

    suspend fun updateDocumentTitle(documentId: String, newTitle: String) {
        withContext(Dispatchers.IO) {
            val doc = documentDao.getDocumentById(documentId)
            if (doc != null) {
                documentDao.updateDocument(
                    doc.copy(
                        title = newTitle,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    suspend fun deleteDocument(documentId: String) {
        withContext(Dispatchers.IO) {
            val pages = pageDao.getPagesForDocument(documentId)
            pages.forEach { page ->
                storageManager.deleteFile(page.originalImagePath)
                storageManager.deleteFile(page.processedImagePath)
            }
            documentDao.deleteDocument(documentId)
        }
    }

    private fun DocumentEntity.toDomainModel() = DocumentModel(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        pageCount = pageCount,
        thumbnailPath = thumbnailPath
    )

    private fun PageEntity.toDomainModel() = PageModel(
        id = id,
        documentId = documentId,
        pageIndex = pageIndex,
        originalImagePath = originalImagePath,
        processedImagePath = processedImagePath,
        filterMode = FilterMode.fromString(filterMode),
        rotationDegrees = rotationDegrees,
        cropCorners = jsonToCornerPoints(cropCornersJson),
        ocrText = ocrText,
        needsManualCorrection = needsManualCorrection == 1
    )

    /** Marks the given pages (by original path) as awaiting corner correction. */
    suspend fun markPagesPending(documentId: String, originalPaths: List<String>) {
        if (originalPaths.isEmpty()) return
        withContext(Dispatchers.IO) {
            pageDao.markPagesPending(documentId, originalPaths)
        }
    }

    /**
     * Applies a manual corner fix. Returns true when an existing page was
     * updated (rather than a new page being appended).
     */
    suspend fun applyCorners(originalPath: String, processedPath: String, corners: CornerPoints): Boolean {
        return withContext(Dispatchers.IO) {
            pageDao.applyCorners(
                originalPath,
                processedPath,
                cornerPointsToJson(corners)
            ) > 0
        }
    }

    companion object {
        fun cornerPointsToJson(points: CornerPoints): String {
            val json = JSONObject()
            json.put("tlX", points.topLeft.x)
            json.put("tlY", points.topLeft.y)
            json.put("trX", points.topRight.x)
            json.put("trY", points.topRight.y)
            json.put("brX", points.bottomRight.x)
            json.put("brY", points.bottomRight.y)
            json.put("blX", points.bottomLeft.x)
            json.put("blY", points.bottomLeft.y)
            return json.toString()
        }

        fun jsonToCornerPoints(jsonString: String?): CornerPoints {
            if (jsonString.isNullOrBlank()) return CornerPoints.defaultNormalized()
            return try {
                val json = JSONObject(jsonString)
                CornerPoints(
                    android.graphics.PointF(json.getDouble("tlX").toFloat(), json.getDouble("tlY").toFloat()),
                    android.graphics.PointF(json.getDouble("trX").toFloat(), json.getDouble("trY").toFloat()),
                    android.graphics.PointF(json.getDouble("brX").toFloat(), json.getDouble("brY").toFloat()),
                    android.graphics.PointF(json.getDouble("blX").toFloat(), json.getDouble("blY").toFloat())
                )
            } catch (e: Exception) {
                CornerPoints.defaultNormalized()
            }
        }
    }
}
