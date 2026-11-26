package com.streamflixreborn.streamflix.fragments.player

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.R

/** Hosts the provider's interactive player when the viewer explicitly selects it. */
class VidSrcPlayerActivity : Activity() {
    private var browser: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.data
        if (!resources.getBoolean(R.bool.allow_vidsrc_player) || page?.scheme != "https" ||
            page.authority != "vidsrc.sh" || page.path !in setOf("/embed/movie", "/embed/tv")) {
            finish()
            return
        }
        val view = WebView(this)
        browser = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(true)
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                request.url.scheme !in setOf("https", "about") || (request.isForMainFrame && request.url != page)
        }
        view.webChromeClient = WebChromeClient()
        setContentView(view)
        view.requestFocus()
        view.loadUrl(page.toString())
    }

    override fun onPause() {
        browser?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        browser?.onResume()
    }

    override fun onDestroy() {
        browser?.let {
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        browser = null
        super.onDestroy()
    }
}
