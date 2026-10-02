package com.camscan.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.camscan.app.data.local.entity.PageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PageDao {
    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY pageIndex ASC")
    fun getPagesForDocumentFlow(documentId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY pageIndex ASC")
    suspend fun getPagesForDocument(documentId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun getPageById(id: String): PageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPage(page: PageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<PageEntity>)

    @Update
    suspend fun updatePage(page: PageEntity)

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun deletePage(id: String)

    @Query("DELETE FROM pages WHERE documentId = :documentId")
    suspend fun deletePagesForDocument(documentId: String)

    /** Marks the listed pages (matched by original image path) as pending. */
    @Query("UPDATE pages SET needsManualCorrection = 1 WHERE documentId = :docId AND originalImagePath IN (:origPaths)")
    suspend fun markPagesPending(docId: String, origPaths: List<String>)

    /** Applies a manual corner fix to an existing page and clears the flag. */
    @Query("UPDATE pages SET processedImagePath = :procPath, cropCornersJson = :corners, needsManualCorrection = 0 WHERE originalImagePath = :origPath")
    suspend fun applyCorners(origPath: String, procPath: String, corners: String): Int
}
