package com.kuilef.anotepad.drive

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kuilef.anotepad.BuildConfig
import com.kuilef.anotepad.R
import com.kuilef.anotepad.sync.DriveAuthManager
import com.kuilef.anotepad.ui.theme.ANotepadTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DrivePickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DRIVE_PICKER_API_KEY.isBlank() || BuildConfig.DRIVE_PICKER_APP_ID.isBlank()) {
            finishWithError(getString(R.string.error_drive_picker_missing_config))
            return
        }

        setContent {
            ANotepadTheme {
                var accessToken by remember { mutableStateOf<String?>(null) }
                var errorMessage by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    val token = withContext(Dispatchers.IO) {
                        DriveAuthManager(applicationContext).getAccessToken()
                    }
                    if (token.isNullOrBlank()) {
                        errorMessage = getString(R.string.error_drive_picker_auth)
                        finishWithError(errorMessage ?: getString(R.string.error_drive_picker_auth))
                    } else {
                        accessToken = token
                    }
                }

                if (accessToken == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = errorMessage ?: getString(R.string.label_loading),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    DrivePickerWebView(
                        accessToken = accessToken.orEmpty(),
                        apiKey = BuildConfig.DRIVE_PICKER_API_KEY,
                        appId = BuildConfig.DRIVE_PICKER_APP_ID,
                        onPicked = { id, name -> finishWithSelection(id, name) },
                        onCancel = { finishCanceled() },
                        onError = { message -> finishWithError(message) }
                    )
                }
            }
        }
    }

    override fun onBackPressed() {
        finishCanceled()
    }

    private fun finishWithSelection(id: String, name: String?) {
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_DRIVE_ID, id)
                putExtra(EXTRA_DRIVE_NAME, name)
            }
        )
        finish()
    }

    private fun finishCanceled() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun finishWithError(message: String?) {
        setResult(
            Activity.RESULT_CANCELED,
            Intent().apply { putExtra(EXTRA_ERROR, message ?: "") }
        )
        finish()
    }

    companion object {
        const val EXTRA_DRIVE_ID = "drive_picker_id"
        const val EXTRA_DRIVE_NAME = "drive_picker_name"
        const val EXTRA_ERROR = "drive_picker_error"
    }
}

@Composable
private fun DrivePickerWebView(
    accessToken: String,
    apiKey: String,
    appId: String,
    onPicked: (String, String?) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit
) {
    val html = remember(accessToken, apiKey, appId) {
        buildPickerHtml(accessToken, apiKey, appId)
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                addJavascriptInterface(PickerBridge(onPicked, onCancel, onError), "Android")
                loadDataWithBaseURL("https://localhost", html, "text/html", "utf-8", null)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL("https://localhost", html, "text/html", "utf-8", null)
        }
    )
}

private class PickerBridge(
    private val onPicked: (String, String?) -> Unit,
    private val onCancel: () -> Unit,
    private val onError: (String) -> Unit
) {
    @JavascriptInterface
    fun onPicked(id: String?, name: String?) {
        if (id.isNullOrBlank()) {
            onError("Drive picker returned empty id")
            return
        }
        onPicked(id, name)
    }

    @JavascriptInterface
    fun onCancel() {
        onCancel()
    }

    @JavascriptInterface
    fun onError(message: String?) {
        onError(message ?: "Drive picker error")
    }
}

private fun buildPickerHtml(accessToken: String, apiKey: String, appId: String): String {
    val tokenJs = JSONObject.quote(accessToken)
    val apiKeyJs = JSONObject.quote(apiKey)
    val appIdJs = JSONObject.quote(appId)
    return """
        <!doctype html>
        <html>
          <head>
            <meta name="viewport" content="width=device-width, initial-scale=1" />
            <script type="text/javascript" src="https://apis.google.com/js/api.js"></script>
            <script type="text/javascript">
              const OAUTH_TOKEN = $tokenJs;
              const API_KEY = $apiKeyJs;
              const APP_ID = $appIdJs;

              function onApiLoad() {
                gapi.load('picker', {'callback': createPicker});
              }

              function createPicker() {
                const view = new google.picker.DocsView(google.picker.ViewId.FOLDERS)
                  .setIncludeFolders(true)
                  .setSelectFolderEnabled(true);

                const picker = new google.picker.PickerBuilder()
                  .setOAuthToken(OAUTH_TOKEN)
                  .setDeveloperKey(API_KEY)
                  .setAppId(APP_ID)
                  .addView(view)
                  .setCallback(pickerCallback)
                  .build();
                picker.setVisible(true);
              }

              function pickerCallback(data) {
                if (data.action === google.picker.Action.PICKED) {
                  const doc = data.docs && data.docs.length ? data.docs[0] : null;
                  const id = doc && (doc.id || doc[google.picker.Document.ID]);
                  const name = doc && (doc.name || doc[google.picker.Document.NAME]);
                  if (id) {
                    Android.onPicked(id, name || "");
                  } else {
                    Android.onError("No folder id returned");
                  }
                } else if (data.action === google.picker.Action.CANCEL) {
                  Android.onCancel();
                }
              }

              window.onerror = function(message) {
                Android.onError(String(message));
                return false;
              };
            </script>
          </head>
          <body onload="onApiLoad()"></body>
        </html>
    """.trimIndent()
}
