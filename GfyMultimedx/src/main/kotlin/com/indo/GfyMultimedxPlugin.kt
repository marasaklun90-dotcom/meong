package com.indo

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class GfyMultimedxPlugin : Plugin() {
    override fun load(context: Context) {
        openSettings = { ctx ->
            val activity = ctx as? Activity
                ?: com.lagradost.cloudstream3.CommonActivity.activity

            activity?.runOnUiThread {
                val webView = WebView(activity)
                webView.isFocusable = true
                webView.isFocusableInTouchMode = true
                webView.requestFocus()
                activity.window.setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return false
                        return uri.scheme !in listOf("http", "https")
                    }
                }
                webView.loadUrl("https://gfymultimedx.online/akun/masuk")

                AlertDialog.Builder(activity)
                    .setTitle("Login GFYMultimedX")
                    .setView(webView)
                    .setPositiveButton("Selesai") { _, _ ->
                        CookieManager.getInstance().flush()
                        webView.destroy()
                    }
                    .setNegativeButton("Batal") { _, _ ->
                        webView.destroy()
                    }
                    .show()
            }
        }

        registerMainAPI(GfyMultimedx())
    }
}
