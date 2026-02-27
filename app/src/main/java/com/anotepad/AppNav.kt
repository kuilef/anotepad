package com.anotepad

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
import com.anotepad.ui.SyncScreen
import com.anotepad.ui.TemplatesScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val ROUTE_BROWSER = "browser"
private const val ROUTE_EDITOR = "editor"
private const val ROUTE_SEARCH = "search"
private const val ROUTE_TEMPLATES = "templates"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_SYNC = "sync"
private const val RESULT_EDITED_ORIGINAL_URI = "edited_original_uri"
private const val RESULT_EDITED_CURRENT_URI = "edited_current_uri"
private const val RESULT_EDITED_DIR_URI = "edited_dir_uri"

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
                applyRootSelection(deps, uri)
            }
        }
    }

    NavHost(navController = navController, startDestination = ROUTE_BROWSER) {
        composable(ROUTE_BROWSER) {
            val viewModel: com.anotepad.ui.BrowserViewModel = viewModel(factory = factory)
            val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
            val editedFlow = savedStateHandle?.getStateFlow<String?>(RESULT_EDITED_CURRENT_URI, null)
            LaunchedEffect(editedFlow) {
                val handle = savedStateHandle ?: return@LaunchedEffect
                editedFlow?.collectLatest { currentUriValue ->
                    if (currentUriValue.isNullOrBlank()) return@collectLatest
                    val originalValue = handle.get<String>(RESULT_EDITED_ORIGINAL_URI)
                    val dirValue = handle.get<String>(RESULT_EDITED_DIR_URI)
                    viewModel.applyEditorUpdate(
                        originalUri = parseNavUriArg(originalValue),
                        currentUri = parseNavUriArg(currentUriValue),
                        dirUri = parseNavUriArg(dirValue)
                    )
                    handle.remove<String>(RESULT_EDITED_ORIGINAL_URI)
                    handle.remove<String>(RESULT_EDITED_CURRENT_URI)
                    handle.remove<String>(RESULT_EDITED_DIR_URI)
                }
            }
            BrowserScreen(
                viewModel = viewModel,
                onPickDirectory = { pickDirectoryLauncher.launch(buildInitialFolderUri()) },
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
                    fileUri = parseNavUriArg(fileArg),
                    dirUri = parseNavUriArg(dirArg),
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
                onBack = back@{ result ->
                    if (navController.currentDestination?.route?.startsWith(ROUTE_EDITOR) != true) {
                        return@back
                    }
                    result?.let {
                        navController.previousBackStackEntry?.savedStateHandle?.set(
                            RESULT_EDITED_ORIGINAL_URI,
                            it.originalUri?.toString()
                        )
                        navController.previousBackStackEntry?.savedStateHandle?.set(
                            RESULT_EDITED_CURRENT_URI,
                            it.currentUri?.toString()
                        )
                        navController.previousBackStackEntry?.savedStateHandle?.set(
                            RESULT_EDITED_DIR_URI,
                            it.dirUri?.toString()
                        )
                    }
                    navController.popBackStack()
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
                viewModel.setBaseDir(parseNavUriArg(dirArg))
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
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenSync = { navController.navigate(ROUTE_SYNC) },
                onOpenTemplates = { navController.navigate("$ROUTE_TEMPLATES?mode=manage") },
                onPickDirectory = { pickDirectoryLauncher.launch(buildInitialFolderUri()) },
                fileRepository = deps.fileRepository
            )
        }
        composable(ROUTE_SYNC) {
            val viewModel: com.anotepad.ui.SyncViewModel = viewModel(factory = factory)
            SyncScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}

private fun encodeUri(uri: Uri?): String {
    return Uri.encode(uri?.toString() ?: "")
}

private fun parseNavUriArg(value: String?): Uri? {
    val normalized = value?.takeIf { it.isNotBlank() } ?: return null
    return Uri.parse(normalized)
}

private const val RECOMMENDED_FOLDER_NAME = "Anotepad"
private const val RECOMMENDED_PARENT_DIR = "Documents"
private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

private fun buildInitialFolderUri(): Uri? {
    val docId = "primary:$RECOMMENDED_PARENT_DIR/$RECOMMENDED_FOLDER_NAME"
    return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
}

private suspend fun applyRootSelection(deps: AppDependencies, pickedUri: Uri) {
    val currentRoot = deps.preferencesRepository.preferencesFlow.first().rootTreeUri
    val rootString = pickedUri.toString()
    val driveFolderName = resolveDriveFolderName(deps.fileRepository, pickedUri)
    deps.preferencesRepository.setRootTreeUri(pickedUri)
    if (!driveFolderName.isNullOrBlank()) {
        deps.preferencesRepository.setDriveSyncFolderName(driveFolderName)
    }
    if (rootString != currentRoot) {
        deps.syncRepository.resetForNewLocalRoot(driveFolderName)
    }
}

private fun resolveDriveFolderName(
    fileRepository: com.anotepad.file.FileRepository,
    pickedUri: Uri
): String {
    val displayName = fileRepository.getTreeDisplayName(pickedUri)
    if (!displayName.isNullOrBlank()) return displayName
    val displayPath = fileRepository.getTreeDisplayPath(pickedUri)
    return displayPath.substringAfterLast('/').ifBlank { RECOMMENDED_FOLDER_NAME }
}
