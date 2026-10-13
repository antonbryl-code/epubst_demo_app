package com.example.epubstdemo

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * Shows a compiled document in a WebView; see [DocumentHtml] for the page layout.
 * [topPadding] leaves room above the document for a toolbar laid over the view, and [onScroll]
 * gets each vertical scroll step in pixels (positive when scrolling down).
 */
@Composable
fun DocumentView(
    document: TypstDocument,
    modifier: Modifier = Modifier,
    topPadding: Dp = 0.dp,
    onScroll: (Int) -> Unit = {},
) {
    val dark = isSystemInDarkTheme()
    // With the device-width viewport, 1 CSS px = 1 dp.
    val html = remember(document, dark, topPadding) {
        DocumentHtml.build(document, dark, topPadding.value.roundToInt())
    }
    AndroidView(
        factory = ::DocumentWebView,
        update = {
            it.onScroll = onScroll
            it.show(html)
        },
        onRelease = { it.destroy() },
        modifier = modifier,
    )
}

/**
 * A WebView locked down for untrusted content: no JavaScript, file, content or network access,
 * and links open outside the app. Keeps the relative scroll position across documents, so a
 * recompile at a new width (e.g. on rotation) stays at about the same place.
 */
@SuppressLint("ViewConstructor")
private class DocumentWebView(context: Context) : WebView(context) {
    private var shownHtml: String? = null
    var onScroll: (Int) -> Unit = {}

    /** Where [show] restores the scroll position to; that scroll isn't reported to [onScroll]. */
    private var restoringTo: Int? = null

    init {
        settings.apply {
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            useWideViewPort = true // honor the page's viewport meta tag
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100 // SVG text doesn't scale with it anyway; keep the layout as compiled
        }
        setBackgroundColor(Color.TRANSPARENT) // the HTML paints the background (see DocumentHtml)
        webViewClient = LinkOpeningClient()
    }

    fun show(html: String) {
        if (html == shownHtml) return
        val fraction = if (contentHeight > 0) scrollY / (contentHeight * resources.displayMetrics.density) else 0f
        shownHtml = html
        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        // Fires once the new content is drawn, i.e. when its height is known.
        postVisualStateCallback(0, object : VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                val y = (fraction * contentHeight * resources.displayMetrics.density).toInt()
                if (y != scrollY) restoringTo = y
                scrollTo(0, y)
            }
        })
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (t == restoringTo) {
            restoringTo = null
        } else if (t != oldt) {
            onScroll(t - oldt)
        }
    }

    private class LinkOpeningClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.scheme in setOf("http", "https", "mailto", "tel")) {
                try {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                    // Nothing on the device handles it; ignore the tap.
                }
            }
            return true // never navigate away from the document
        }
    }
}
