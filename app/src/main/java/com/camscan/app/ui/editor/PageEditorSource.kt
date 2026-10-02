package com.camscan.app.ui.editor

import com.camscan.app.domain.model.PageModel

/**
 * Chooses which image the page editor edits.
 *
 * Normal filter / brightness / contrast / rotation editing must operate on the
 * PROCESSED (rectified) page. Sourcing the raw camera photograph here would
 * pull the surrounding background straight back into an already-corrected
 * scan, and the enhancement pass would then re-render the table as if it were
 * paper.
 *
 * The original photograph stays available only through the corner editor for
 * recrop, reset, re-detection, manual correction and restoring the original.
 *
 * Extracted as an object so the rule is directly unit-testable.
 */
object PageEditorSource {

    /** The bitmap path the editor should load for [page]. */
    fun resolve(page: PageModel): String =
        if (page.processedImagePath.isNotBlank()) page.processedImagePath
        else page.originalImagePath
}
