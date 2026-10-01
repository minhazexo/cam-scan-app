package com.camscan.app.ui.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Camera : Screen("camera?documentId={documentId}") {
        fun createRoute(documentId: String? = null) = if (documentId != null) "camera?documentId=$documentId" else "camera"
    }
    data object CornerAdjust : Screen("corner_adjust?documentId={documentId}&imagePath={imagePath}") {
        fun createRoute(documentId: String?, imagePath: String) =
            "corner_adjust?documentId=${documentId ?: ""}&imagePath=${java.net.URLEncoder.encode(imagePath, "UTF-8")}"
    }
    data object PageEditor : Screen("page_editor/{documentId}/{pageId}") {
        fun createRoute(documentId: String, pageId: String) = "page_editor/$documentId/$pageId"
    }
    data object DocumentDetail : Screen("document_detail/{documentId}") {
        fun createRoute(documentId: String) = "document_detail/$documentId"
    }
    data object OcrResult : Screen("ocr_result/{documentId}/{pageId}") {
        fun createRoute(documentId: String, pageId: String) = "ocr_result/$documentId/$pageId"
    }
    data object Settings : Screen("settings")
}
