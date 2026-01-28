package com.anotepad

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import com.anotepad.ui.BrowserScreen
import com.anotepad.ui.EditorScreen
import com.anotepad.ui.SearchScreen
import com.anotepad.ui.SettingsScreen
import com.anotepad.ui.TemplatesScreen
import kotlinx.coroutines.launch

private const val ROUTE_BROWSER = "browser"
private const val ROUTE_EDITOR = "editor"
private const val ROUTE_SEARCH = "search"
private const val ROUTE_TEMPLATES = "templates"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun AppNav(deps: AppDependencies) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val factory = remember { AppViewModelFactory(deps) }
    val scope = rememberCoroutineScope()

    val pickDirectoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            scope.launch {
                deps.preferencesRepository.setRootTreeUri(uri)
            }
        }
    }

    NavHost(navController = navController, startDestination = ROUTE_BROWSER) {
        composable(ROUTE_BROWSER) {
            val viewModel: com.anotepad.ui.BrowserViewModel = viewModel(factory = factory)
            BrowserScreen(
                viewModel = viewModel,
                onPickDirectory = { pickDirectoryLauncher.launch(null) },
                onOpenFile = { fileUri, dirUri ->
                    navController.navigate("$ROUTE_EDITOR?file=${encodeUri(fileUri)}&dir=${encodeUri(dirUri)}")
                },
                onNewFile = { dirUri, extension ->
                    navController.navigate("$ROUTE_EDITOR?dir=${encodeUri(dirUri)}&ext=$extension")
                },
                onSearch = { dirUri ->
                    navController.navigate("$ROUTE_SEARCH?dir=${encodeUri(dirUri)}")
                },
                onSettings = { navController.navigate(ROUTE_SETTINGS) }
            )
        }
        composable(
            route = "$ROUTE_EDITOR?file={file}&dir={dir}&ext={ext}",
            arguments = listOf(
                navArgument("file") { type = NavType.StringType; nullable = true },
                navArgument("dir") { type = NavType.StringType; nullable = true },
                navArgument("ext") { type = NavType.StringType; nullable = true }
            )
        ) { backStackEntry ->
            val fileArg = backStackEntry.arguments?.getString("file")
            val dirArg = backStackEntry.arguments?.getString("dir")
            val extArg = backStackEntry.arguments?.getString("ext")
            val viewModel: com.anotepad.ui.EditorViewModel = viewModel(factory = factory)
            val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
            val templateText = savedStateHandle?.get<String>("template")

            LaunchedEffect(fileArg, dirArg, extArg) {
                viewModel.load(
                    fileUri = fileArg?.takeIf { it.isNotBlank() }?.let { Uri.parse(Uri.decode(it)) },
                    dirUri = dirArg?.takeIf { it.isNotBlank() }?.let { Uri.parse(Uri.decode(it)) },
                    newFileExtension = extArg ?: "txt"
                )
            }
            LaunchedEffect(templateText) {
                if (!templateText.isNullOrBlank()) {
                    viewModel.queueTemplate(templateText)
                    savedStateHandle?.remove<String>("template")
                }
            }

            EditorScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenTemplates = {
                    navController.navigate("$ROUTE_TEMPLATES?mode=pick")
                }
            )
        }
        composable(
            route = "$ROUTE_SEARCH?dir={dir}",
            arguments = listOf(navArgument("dir") { type = NavType.StringType; nullable = true })
        ) { backStackEntry ->
            val dirArg = backStackEntry.arguments?.getString("dir")
            val viewModel: com.anotepad.ui.SearchViewModel = viewModel(factory = factory)
            LaunchedEffect(dirArg) {
                viewModel.setBaseDir(dirArg?.takeIf { it.isNotBlank() }?.let { Uri.parse(Uri.decode(it)) })
            }
            SearchScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenResult = { fileUri, dirUri ->
                    navController.navigate("$ROUTE_EDITOR?file=${encodeUri(fileUri)}&dir=${encodeUri(dirUri)}")
                }
            )
        }
        composable(
            route = "$ROUTE_TEMPLATES?mode={mode}",
            arguments = listOf(navArgument("mode") { type = NavType.StringType; nullable = true })
        ) { backStackEntry ->
            val mode = backStackEntry.arguments?.getString("mode") ?: "manage"
            val viewModel: com.anotepad.ui.TemplatesViewModel = viewModel(factory = factory)
            TemplatesScreen(
                viewModel = viewModel,
                pickMode = mode == "pick",
                onBack = { navController.popBackStack() },
                onTemplatePicked = { templateText ->
                    navController.previousBackStackEntry?.savedStateHandle?.set("template", templateText)
                    navController.popBackStack()
                }
            )
        }
        composable(ROUTE_SETTINGS) {
            val viewModel: com.anotepad.ui.SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}

private fun encodeUri(uri: Uri?): String {
    return Uri.encode(uri?.toString() ?: "")
}
