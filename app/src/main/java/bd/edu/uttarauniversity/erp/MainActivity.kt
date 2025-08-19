package bd.edu.uttarauniversity.erp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import bd.edu.uttarauniversity.erp.ui.theme.UUTheme

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

sealed class WebViewState {
    object Loading : WebViewState()
    object Success : WebViewState()
    object Error : WebViewState()
    object PageNotFound : WebViewState()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ThemeManager(LocalContext.current.dataStore)

            UUTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewExample(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewExample(modifier: Modifier) {
    val mUrl = "https://erp.uttarauniversity.edu.bd/"
    var webView: WebView? by remember { mutableStateOf(null) }
    var webViewState by remember { mutableStateOf<WebViewState>(WebViewState.Loading) }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT

                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            webViewState = WebViewState.Loading
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (webViewState !is WebViewState.Error && webViewState !is WebViewState.PageNotFound) {
                                webViewState = WebViewState.Success
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                webViewState = WebViewState.Error
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: android.webkit.WebResourceResponse?
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            if (request?.isForMainFrame == true && errorResponse?.statusCode == 404) {
                                webViewState = WebViewState.PageNotFound
                            }
                        }
                    }
                    loadUrl(mUrl)
                }.also {
                    webView = it
                }
            })

        when (webViewState) {
            is WebViewState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            is WebViewState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "No Internet Connection")
                    Button(onClick = { webView?.reload() }) {
                        Text("Try Again")
                    }
                }
            }

            is WebViewState.PageNotFound -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "404 - Page Not Found")
                    Button(onClick = { webView?.loadUrl(mUrl) }) {
                        Text("Home")
                    }
                }
            }

            is WebViewState.Success -> {
                // WebView is visible and loaded
            }
        }
    }
}