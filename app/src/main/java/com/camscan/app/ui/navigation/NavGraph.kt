package com.camscan.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.camscan.app.CamScanApplication
import com.camscan.app.ui.camera.CameraScanScreen
import com.camscan.app.ui.camera.CameraScanViewModel
import com.camscan.app.ui.corner.CornerAdjustScreen
import com.camscan.app.ui.corner.CornerAdjustViewModel
import com.camscan.app.ui.document.DocumentDetailScreen
import com.camscan.app.ui.document.DocumentDetailViewModel
import com.camscan.app.ui.editor.PageEditorScreen
import com.camscan.app.ui.editor.PageEditorViewModel
import com.camscan.app.ui.home.HomeScreen
import com.camscan.app.ui.home.HomeViewModel
import com.camscan.app.ui.settings.SettingsScreen
import com.camscan.app.ui.settings.SettingsViewModel
import java.net.URLDecoder

@Composable
fun NavGraph(
    navController: NavHostController,
    application: CamScanApplication
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = HomeViewModel.Factory(application.repository)
            )
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToCamera = {
                    navController.navigate(Screen.Camera.createRoute())
                },
                onNavigateToDocumentDetail = { docId ->
                    navController.navigate(Screen.DocumentDetail.createRoute(docId))
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(
            route = Screen.Camera.route,
            arguments = listOf(
                navArgument("documentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val docId = backStackEntry.arguments?.getString("documentId")
            val cameraViewModel: CameraScanViewModel = viewModel(
                factory = CameraScanViewModel.Factory(application.repository)
            )
            CameraScanScreen(
                viewModel = cameraViewModel,
                documentId = docId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToCornerAdjust = { targetDocId, imagePath ->
                    navController.navigate(Screen.CornerAdjust.createRoute(targetDocId, imagePath)) {
                        popUpTo(Screen.Camera.route) { inclusive = true }
                    }
                },
                onNavigateToDocumentDetail = { targetDocId ->
                    navController.navigate(Screen.DocumentDetail.createRoute(targetDocId)) {
                        popUpTo(Screen.Camera.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.CornerAdjust.route,
            arguments = listOf(
                navArgument("documentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("imagePath") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val docId = backStackEntry.arguments?.getString("documentId")
            val encodedPath = backStackEntry.arguments?.getString("imagePath") ?: ""
            val imagePath = URLDecoder.decode(encodedPath, "UTF-8")

            val cornerViewModel: CornerAdjustViewModel = viewModel(
                factory = CornerAdjustViewModel.Factory(application.repository)
            )
            CornerAdjustScreen(
                viewModel = cornerViewModel,
                documentId = docId,
                imagePath = imagePath,
                onNavigateBack = { navController.popBackStack() },
                onComplete = { targetDocId ->
                    navController.navigate(Screen.DocumentDetail.createRoute(targetDocId)) {
                        popUpTo(Screen.Home.route)
                    }
                }
            )
        }

        composable(
            route = Screen.PageEditor.route,
            arguments = listOf(
                navArgument("documentId") { type = NavType.StringType },
                navArgument("pageId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val docId = backStackEntry.arguments?.getString("documentId") ?: ""
            val pageId = backStackEntry.arguments?.getString("pageId") ?: ""

            val editorViewModel: PageEditorViewModel = viewModel(
                factory = PageEditorViewModel.Factory(application.repository)
            )
            PageEditorScreen(
                viewModel = editorViewModel,
                documentId = docId,
                pageId = pageId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToCornerAdjust = { dId, imgPath ->
                    navController.navigate(Screen.CornerAdjust.createRoute(dId, imgPath))
                },
                onNavigateToOcrResult = { _, _ -> }
            )
        }

        composable(
            route = Screen.DocumentDetail.route,
            arguments = listOf(
                navArgument("documentId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val docId = backStackEntry.arguments?.getString("documentId") ?: ""

            val detailViewModel: DocumentDetailViewModel = viewModel(
                factory = DocumentDetailViewModel.Factory(application.repository)
            )
            DocumentDetailScreen(
                viewModel = detailViewModel,
                documentId = docId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToCamera = { targetDocId ->
                    navController.navigate(Screen.Camera.createRoute(targetDocId))
                },
                onNavigateToPageEditor = { dId, pageId ->
                    navController.navigate(Screen.PageEditor.createRoute(dId, pageId))
                },
                onNavigateToCornerAdjust = { dId, imgPath ->
                    navController.navigate(Screen.CornerAdjust.createRoute(dId, imgPath))
                }
            )
        }

        composable(Screen.Settings.route) {
            val settingsViewModel: SettingsViewModel = viewModel()
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
