package bd.edu.uttarauniversity.erp

import android.annotation.SuppressLint
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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import bd.edu.uttarauniversity.erp.ui.theme.UUTheme

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
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = MaterialTheme.colorScheme.background

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
                            if (isDarkTheme) {
                                val hexColor =
                                    String.format("#%06X", (0xFFFFFF and backgroundColor.toArgb()))
                                val css = """
                                    body {
                                        background-color: $hexColor !important;
                                    }
                                    #header, .sidebar, .sidebar-nav, .nav-item, .nav-link, .main, .tab-content {
                                        background-color: $hexColor !important;
                                        color: #FFFFFF !important;
                                    }
                                    .table>thead {
                                        background-color: black !important;
                                    }
                                    table tbody tr td, table tfoot tr td {
                                        color: #FFFFFF !important;
                                    }
                                    table thead tr td, table tfoot tr td {
                                        background-color: #343a40 !important;
                                        color: #FFFFFF !important;
                                    }
                                    table tbody .table-secondary td {
                                        background: #343a40 !important;
                                    }
                                    
                                    .tab-content, .card, .card-header, .card-body, .dropdown-menu, .modal-content, .select2-dropdown{
                                        background: #212529 !important;
                                    }
                                    .dropdown-item, .nav-link, .dropdown-header, .dropdown-header h6 {
                                        color: #FFFFFF !important;
                                    }
                                """
                                view?.evaluateJavascript(
                                    """
                                    const style = document.createElement('style');
                                    style.type = 'text/css';
                                    style.appendChild(document.createTextNode(`$css`));
                                    document.head.appendChild(style);
                                """, null
                                )
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