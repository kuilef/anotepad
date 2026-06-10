package com.anotepad.storecaptures

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.anotepad.MainActivity
import com.anotepad.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.ScreenshotCallback
import tools.fastlane.screengrab.UiAutomatorScreenshotStrategy
import tools.fastlane.screengrab.locale.LocaleTestRule
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern
import kotlin.math.roundToInt

private const val APP_PACKAGE = "com.anotepad"
private const val SCREEN_WAIT_MS = 5_000L
private const val PICKER_WAIT_MS = 12_000L
private const val APP_RETURN_WAIT_MS = 15_000L
private const val FILE_LIST_SETTLE_MS = 1_000L
private const val DEFAULT_TARGET_FOLDER_NAME = "anotepad"
private const val ABOUT_FILE_NAME = "01_local_notes.txt"
private const val LOG_TAG = "StoreScreenshots"

private val DOCUMENTS_UI_PACKAGE_PATTERN = Pattern.compile("com\\.(google\\.)?android\\.documentsui")
private val DOCUMENTS_UI_TITLE_RES_PATTERN =
    Pattern.compile("(android:id/title|com\\.(google\\.)?android\\.documentsui:id/title)")
private val DOCUMENTS_UI_ACTION_SELECT_RES_PATTERN =
    Pattern.compile("com\\.(google\\.)?android\\.documentsui:id/action_menu_select")
private val DOCUMENTS_UI_ROOTS_LIST_RES_PATTERN =
    Pattern.compile("com\\.(google\\.)?android\\.documentsui:id/roots_list")
private val DOCUMENTS_UI_SCROLLABLE_LIST_RES_PATTERN =
    Pattern.compile("com\\.(google\\.)?android\\.documentsui:id/(dir_list|list|recycler_view)")
private val DOCUMENTS_UI_NAVIGATION_DESC_PATTERN =
    Pattern.compile("(show roots|open navigation drawer|show navigation drawer)", Pattern.CASE_INSENSITIVE)
private val LOCAL_STORAGE_ROOT_TEXT_PATTERN = Pattern.compile(
    "(internal storage|phone|tablet|this device|pixel|oneplus|storage|sd ?card|sdcard|my files|" +
        "moto|samsung|galaxy|xiaomi|redmi|внутрен|памят|телефон|устройство)",
    Pattern.CASE_INSENSITIVE
)
private val NON_LOCAL_STORAGE_ROOT_TEXT_PATTERN = Pattern.compile(
    "(recent|recents|images|photos|videos|audio|downloads|google drive|drive|cloud|" +
        "изображ|фото|видео|аудио|загруз|диск)",
    Pattern.CASE_INSENSITIVE
)

private val USE_THIS_FOLDER_TEXT_PATTERN = Pattern.compile(
    "(use[\\s\\u00A0]+this[\\s\\u00A0]+folder|" +
        "select|choose|" +
        "использовать[\\s\\u00A0]+эту[\\s\\u00A0]+папку|" +
        "diesen[\\s\\u00A0]+ordner[\\s\\u00A0]+verwenden|" +
        "utiliser[\\s\\u00A0]+ce[\\s\\u00A0]+dossier|" +
        "usar[\\s\\u00A0]+esta[\\s\\u00A0]+carpeta)",
    Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
)

private val CONFIRM_FOLDER_ACCESS_TEXT_PATTERN = Pattern.compile(
    "(allow|ok|разрешить|ок|zulassen|autoriser|permitir|aceptar)",
    Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
)

@RunWith(AndroidJUnit4::class)
class PlayStoreScreenshotTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Rule
    @JvmField
    val localeTestRule = LocaleTestRule()

    @Test
    fun captureLocalizedStoreScreenshots() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val args = InstrumentationRegistry.getArguments()
        val screenshotLocale = args.getString("testLocale")
            ?: args.getString("locale")
            ?: Screengrab.getLocale()
        val targetFolderName = args.getString("targetFolderName")
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TARGET_FOLDER_NAME

        Log.d(LOG_TAG, "Starting screenshot capture. locale=$screenshotLocale targetFolderName=$targetFolderName")
        Screengrab.setLocale(screenshotLocale)
        val screenshotStrategy = UiAutomatorScreenshotStrategy()
        val screenshotCallback = ExternalFilesScreenshotCallback(targetContext, screenshotLocale)

        wakeDevice(device)
        device.waitForIdle()
        selectExistingWorkFolderIfRequested(device, targetContext, targetFolderName)
        waitForFolderContents(device, targetContext)
        dismissToolbarOnboardingIfVisible(device, targetContext)
        wakeDevice(device)
        Screengrab.screenshot("01_home_files", screenshotStrategy, screenshotCallback)

        wakeDevice(device)
        openFeedForScreenshot(device, targetContext)
        wakeDevice(device)
        Screengrab.screenshot("02_feed", screenshotStrategy, screenshotCallback)

        wakeDevice(device)
        openFileForScreenshot(device, targetContext, ABOUT_FILE_NAME)
        wakeDevice(device)
        Screengrab.screenshot("03_about_anotepad", screenshotStrategy, screenshotCallback)

        wakeDevice(device)
        openSettings(device, targetContext)
        wakeDevice(device)
        Screengrab.screenshot("04_settings", screenshotStrategy, screenshotCallback)
    }
}

private fun wakeDevice(device: UiDevice) {
    if (!device.isScreenOn) {
        device.wakeUp()
    }
    if (!device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), SCREEN_WAIT_MS)) {
        launchApp(device)
        device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), SCREEN_WAIT_MS)
    }
    device.waitForIdle()
}

private fun launchApp(device: UiDevice) {
    Log.d(LOG_TAG, "Launching $APP_PACKAGE from test")
    device.executeShellCommand("monkey -p $APP_PACKAGE -c android.intent.category.LAUNCHER 1")
}

private fun selectExistingWorkFolderIfRequested(
    device: UiDevice,
    context: Context,
    targetFolderName: String
) {
    device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), SCREEN_WAIT_MS)
    device.waitForIdle()

    if (!isFolderSelectionPromptVisible(device, context)) return

    openSystemFolderPickerFromCurrentPrompt(device, context)
    selectExistingFolderInPicker(device, context, targetFolderName)

    if (!device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), APP_RETURN_WAIT_MS)) {
        throw IllegalStateException("The app did not return after selecting the folder. currentPackage=${device.currentPackageName}")
    }
    device.waitForIdle()
    device.wait(Until.gone(By.text(context.getString(R.string.label_no_folder))), SCREEN_WAIT_MS)
    device.wait(Until.gone(By.text(context.getString(R.string.label_folder_unavailable_title))), SCREEN_WAIT_MS)
}

private fun openSystemFolderPickerFromCurrentPrompt(device: UiDevice, context: Context) {
    val pickFolderLabel = context.getString(R.string.action_pick_folder)
    val continueLabel = context.getString(R.string.action_continue)
    val deadline = System.currentTimeMillis() + PICKER_WAIT_MS
    var appRelaunched = false

    while (System.currentTimeMillis() < deadline) {
        if (isDocumentsUiVisible(device)) return

        val continueButton = findObjectByTextOrDescription(device, continueLabel)
        if (continueButton != null) {
            clickObjectOrClickableParent(continueButton)
            device.waitForIdle()
            Thread.sleep(300L)
            continue
        }

        val pickFolderButton = findObjectByTextOrDescription(device, pickFolderLabel)
        if (pickFolderButton != null) {
            clickObjectOrClickableParent(pickFolderButton)
            device.waitForIdle()
            Thread.sleep(500L)
            continue
        }

        val inApp = device.hasObject(By.pkg(APP_PACKAGE))
        if (!inApp && !appRelaunched) {
            Log.w(LOG_TAG, "Neither app nor DocumentsUI is visible; relaunching app. currentPackage=${device.currentPackageName}")
            launchApp(device)
            appRelaunched = true
            device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), SCREEN_WAIT_MS)
            Thread.sleep(500L)
            continue
        }

        Thread.sleep(250L)
    }

    throw IllegalStateException("System folder picker did not open. currentPackage=${device.currentPackageName}")
}

private fun waitForFolderContents(device: UiDevice, context: Context) {
    val searching = By.text(context.getString(R.string.label_searching))
    device.wait(Until.gone(searching), SCREEN_WAIT_MS)
    Thread.sleep(FILE_LIST_SETTLE_MS)
    device.waitForIdle()
}

private fun dismissToolbarOnboardingIfVisible(device: UiDevice, context: Context) {
    val titleSelector = By.text(context.getString(R.string.label_toolbar_onboarding_title))
    val skipLabel = context.getString(R.string.action_skip)

    repeat(3) {
        if (!device.hasObject(titleSelector) && !device.hasObject(By.text(skipLabel))) return

        val skipButton = findObjectByTextOrDescription(device, skipLabel)
        if (skipButton != null) {
            clickObjectOrClickableParent(skipButton)
        } else {
            device.pressBack()
        }
        device.waitForIdle()
        device.wait(Until.gone(titleSelector), SCREEN_WAIT_MS)
    }
}

private fun openFeedForScreenshot(device: UiDevice, context: Context) {
    wakeDevice(device)
    dismissToolbarOnboardingIfVisible(device, context)

    val showFeedLabel = context.getString(R.string.action_toggle_feed)
    val showListLabel = context.getString(R.string.action_toggle_list)

    if (device.hasObject(By.pkg(APP_PACKAGE).desc(showListLabel))) {
        waitForFolderContents(device, context)
        return
    }

    val showFeedButton = device.findObject(By.pkg(APP_PACKAGE).desc(showFeedLabel))
        ?: throw IllegalStateException("Unable to find the feed view button.")
    clickObjectOrClickableParent(showFeedButton)
    device.waitForIdle()
    waitForFolderContents(device, context)
}

private fun openFileForScreenshot(device: UiDevice, context: Context, fileName: String) {
    dismissToolbarOnboardingIfVisible(device, context)
    ensureListView(device, context)

    val fileRow = findVisibleTextWithScroll(device, fileName)
        ?: throw IllegalStateException("Unable to find $fileName in the current folder.")
    clickObjectOrClickableParent(fileRow)

    if (!device.wait(Until.hasObject(By.pkg(APP_PACKAGE).desc(context.getString(R.string.action_save))), SCREEN_WAIT_MS) ||
        !device.wait(Until.hasObject(By.text(fileName)), SCREEN_WAIT_MS)
    ) {
        throw IllegalStateException("$fileName did not open.")
    }
    device.wait(Until.gone(By.text(context.getString(R.string.label_loading))), SCREEN_WAIT_MS)
    device.waitForIdle()
}

private fun ensureListView(device: UiDevice, context: Context) {
    val toggleListLabel = context.getString(R.string.action_toggle_list)
    val toggleListButton = device.findObject(By.pkg(APP_PACKAGE).desc(toggleListLabel)) ?: return

    clickObjectOrClickableParent(toggleListButton)
    device.waitForIdle()
    Thread.sleep(FILE_LIST_SETTLE_MS)
}

private fun findVisibleTextWithScroll(device: UiDevice, text: String): UiObject2? {
    device.findObject(By.text(text))?.let { return it }

    val scrollable = device.findObject(By.scrollable(true)) ?: return null
    repeat(3) {
        if (!scrollable.scroll(Direction.UP, 0.8f)) return@repeat
        device.waitForIdle()
        device.findObject(By.text(text))?.let { return it }
    }

    repeat(12) {
        if (!scrollable.scroll(Direction.DOWN, 0.8f)) return null
        device.waitForIdle()
        device.findObject(By.text(text))?.let { return it }
    }
    return null
}

private fun isFolderSelectionPromptVisible(device: UiDevice, context: Context): Boolean {
    val noFolder = By.text(context.getString(R.string.label_no_folder))
    val folderUnavailable = By.text(context.getString(R.string.label_folder_unavailable_title))
    val pickFolder = By.text(context.getString(R.string.action_pick_folder))
    return device.hasObject(noFolder) ||
        device.hasObject(folderUnavailable) ||
        device.hasObject(pickFolder) ||
        device.wait(Until.hasObject(noFolder), 1_500L) ||
        device.wait(Until.hasObject(folderUnavailable), 1_500L)
}

private fun findObjectByTextOrDescription(device: UiDevice, text: String): UiObject2? {
    return device.findObject(By.text(text)) ?: device.findObject(By.desc(text))
}

private fun selectExistingFolderInPicker(
    device: UiDevice,
    context: Context,
    targetFolderName: String
) {
    if (!isDocumentsUiVisible(device)) {
        throw IllegalStateException("Folder picker is not visible. currentPackage=${device.currentPackageName}")
    }

    if (!isCurrentPickerFolder(device, targetFolderName) && !openTargetFolderIfVisible(device, targetFolderName)) {
        openDocumentsRootDrawer(device, context)
        if (!selectLocalStorageRoot(device)) {
            throw IllegalStateException("Unable to find the local storage root in the system folder picker.")
        }
        scrollPickerListToTop(device)
        if (!isCurrentPickerFolder(device, targetFolderName) && !openTargetFolderIfVisible(device, targetFolderName)) {
            throw IllegalStateException("Unable to find existing /emulated/0/$targetFolderName in the system folder picker.")
        }
    }

    if (!clickUseThisFolder(device, context)) {
        throw IllegalStateException("Unable to confirm the selected $targetFolderName folder.")
    }

    confirmFolderAccessIfAsked(device)
}

private fun isCurrentPickerFolder(device: UiDevice, targetFolderName: String): Boolean {
    return device.hasObject(By.text(exactTextPattern(targetFolderName))) &&
        findUseThisFolderButton(device) != null
}

private fun isDocumentsUiVisible(device: UiDevice): Boolean {
    return device.hasObject(By.pkg(DOCUMENTS_UI_PACKAGE_PATTERN))
}

private fun openTargetFolderIfVisible(device: UiDevice, targetFolderName: String): Boolean {
    scrollPickerListToTop(device)
    repeat(12) {
        findTargetFolder(device, targetFolderName)?.let { folder ->
            clickObjectOrClickableParent(folder)
            device.waitForIdle()
            Thread.sleep(500L)
            return true
        }

        val scrollable = findPickerScrollable(device) ?: return false
        if (!scrollable.scroll(Direction.DOWN, 0.8f)) return false
        device.waitForIdle()
    }
    return false
}

private fun findTargetFolder(device: UiDevice, targetFolderName: String): UiObject2? {
    val exactNamePattern = exactTextPattern(targetFolderName)
    return device.findObject(By.res(DOCUMENTS_UI_TITLE_RES_PATTERN).text(exactNamePattern))
        ?: device.findObject(By.text(exactNamePattern))
}

private fun exactTextPattern(text: String): Pattern {
    return Pattern.compile("^${Pattern.quote(text)}$", Pattern.CASE_INSENSITIVE)
}

private fun findPickerScrollable(device: UiDevice): UiObject2? {
    return device.findObject(By.res(DOCUMENTS_UI_SCROLLABLE_LIST_RES_PATTERN))
        ?: device.findObject(By.scrollable(true))
}

private fun scrollPickerListToTop(device: UiDevice) {
    val scrollable = findPickerScrollable(device) ?: return
    repeat(8) {
        if (!scrollable.scroll(Direction.UP, 0.8f)) return
        device.waitForIdle()
    }
}

private fun openDocumentsRootDrawer(device: UiDevice, context: Context) {
    if (device.hasObject(By.res(DOCUMENTS_UI_ROOTS_LIST_RES_PATTERN))) return

    val drawerButton = device.findObject(By.desc(DOCUMENTS_UI_NAVIGATION_DESC_PATTERN))
    if (drawerButton != null) {
        clickObjectOrClickableParent(drawerButton)
    } else {
        val density = context.resources.displayMetrics.density
        device.click((32 * density).roundToInt(), (32 * density).roundToInt())
    }
    device.wait(Until.hasObject(By.res(DOCUMENTS_UI_ROOTS_LIST_RES_PATTERN)), SCREEN_WAIT_MS)
    device.waitForIdle()
}

private fun selectLocalStorageRoot(device: UiDevice): Boolean {
    val root = device.findObject(By.res(DOCUMENTS_UI_TITLE_RES_PATTERN).text(LOCAL_STORAGE_ROOT_TEXT_PATTERN))
        ?: device.findObject(By.text(LOCAL_STORAGE_ROOT_TEXT_PATTERN))
        ?: findFallbackLocalStorageRoot(device)
        ?: return false

    clickObjectOrClickableParent(root)
    device.waitForIdle()
    Thread.sleep(500L)
    return true
}

private fun findFallbackLocalStorageRoot(device: UiDevice): UiObject2? {
    val rootsList = device.findObject(By.res(DOCUMENTS_UI_ROOTS_LIST_RES_PATTERN)) ?: return null
    val titleNodes = collectDescendants(rootsList).filter { node ->
        val text = node.text
        val resourceName = node.resourceName
        !text.isNullOrBlank() &&
            resourceName != null &&
            DOCUMENTS_UI_TITLE_RES_PATTERN.matcher(resourceName).matches()
    }

    return titleNodes.lastOrNull { node ->
        !NON_LOCAL_STORAGE_ROOT_TEXT_PATTERN.matcher(node.text).find()
    }
}

private fun collectDescendants(root: UiObject2): List<UiObject2> {
    val result = mutableListOf<UiObject2>()

    fun visit(node: UiObject2) {
        result += node
        node.children.forEach(::visit)
    }

    visit(root)
    return result
}

private fun clickUseThisFolder(device: UiDevice, context: Context): Boolean {
    val selectButton = findUseThisFolderButton(device)
    if (selectButton != null) {
        clickObjectOrClickableParent(selectButton)
    } else {
        val density = context.resources.displayMetrics.density
        device.click(
            device.displayWidth - (56 * density).roundToInt(),
            (32 * density).roundToInt()
        )
    }
    device.waitForIdle()
    Thread.sleep(500L)
    return true
}

private fun findUseThisFolderButton(device: UiDevice): UiObject2? {
    return device.findObject(By.res(DOCUMENTS_UI_ACTION_SELECT_RES_PATTERN))
        ?: device.findObject(By.text(USE_THIS_FOLDER_TEXT_PATTERN))
        ?: device.findObject(By.desc(USE_THIS_FOLDER_TEXT_PATTERN))
        ?: device.findObject(By.res("android:id/button1"))
}

private fun confirmFolderAccessIfAsked(device: UiDevice) {
    val deadline = System.currentTimeMillis() + PICKER_WAIT_MS
    while (System.currentTimeMillis() < deadline) {
        val allowButton = device.findObject(By.res("android:id/button1"))
            ?: device.findObject(By.text(CONFIRM_FOLDER_ACCESS_TEXT_PATTERN))
            ?: device.findObject(By.desc(CONFIRM_FOLDER_ACCESS_TEXT_PATTERN))

        if (allowButton != null) {
            clickObjectOrClickableParent(allowButton)
            device.waitForIdle()
            return
        }

        if (!isDocumentsUiVisible(device)) return
        Thread.sleep(250L)
    }
}

private fun clickObjectOrClickableParent(target: UiObject2) {
    var current: UiObject2? = target
    while (current != null) {
        if (current.isClickable) {
            current.click()
            return
        }
        current = current.parent
    }
    target.click()
}

private fun openSettings(device: UiDevice, context: Context) {
    val settingsLabel = context.getString(R.string.action_settings)

    returnToBrowserScreen(device, context)
    dismissToolbarOnboardingIfVisible(device, context)

    val settingsButton = device.findObject(By.pkg(APP_PACKAGE).desc(settingsLabel))
        ?: throw IllegalStateException("Unable to find the app Settings button on the main screen.")
    clickObjectOrClickableParent(settingsButton)
    device.waitForIdle()

    if (!device.wait(Until.hasObject(By.text(context.getString(R.string.label_settings_title))), SCREEN_WAIT_MS) ||
        !device.wait(Until.hasObject(By.text(context.getString(R.string.label_main_folder))), SCREEN_WAIT_MS)
    ) {
        throw IllegalStateException("Settings screen did not open.")
    }
    device.waitForIdle()
}

private fun returnToBrowserScreen(device: UiDevice, context: Context) {
    val backLabel = context.getString(R.string.action_back)
    val settingsLabel = context.getString(R.string.action_settings)
    val settingsButton = By.pkg(APP_PACKAGE).desc(settingsLabel)

    if (device.hasObject(settingsButton)) return

    repeat(3) {
        val backButton = device.findObject(By.pkg(APP_PACKAGE).desc(backLabel))
        if (backButton != null) {
            clickObjectOrClickableParent(backButton)
        } else {
            device.pressBack()
        }
        device.waitForIdle()
        if (device.wait(Until.hasObject(settingsButton), SCREEN_WAIT_MS)) return
    }

    throw IllegalStateException("Unable to return to the main screen before opening Settings.")
}

private class ExternalFilesScreenshotCallback(
    private val context: Context,
    private val locale: String
) : ScreenshotCallback {
    override fun screenshotCaptured(screenshotName: String, screenshot: Bitmap) {
        val screenshotsDir = File(
            File(context.getExternalFilesDir("screengrab"), locale),
            "images/screenshots"
        )
        if (!screenshotsDir.exists() && !screenshotsDir.mkdirs()) {
            throw IllegalStateException("Unable to create screenshot directory: ${screenshotsDir.absolutePath}")
        }

        val screenshotFile = File(screenshotsDir, "$screenshotName.png")
        try {
            BufferedOutputStream(FileOutputStream(screenshotFile)).use { output ->
                screenshot.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
            }
            Log.d("Screengrab", "Captured screenshot \"${screenshotFile.name}\"")
        } finally {
            screenshot.recycle()
        }
    }

    companion object {
        private const val PNG_QUALITY = 100
    }
}
