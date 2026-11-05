package com.indo

import android.app.AlertDialog
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PusatFilm21Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PusatFilm21())

        openSettings = { ctx ->
            AlertDialog.Builder(ctx)
                .setTitle("Pengaturan PusatFilm21")
                .setMessage(
                    "Status cookie: " +
                    (if (CookieManager.getInstance()
                            .getCookie("https://new1.pusatfilm21info.com")
                            .isNullOrBlank()
                    ) "Belum tersedia" else "Tersedia") +
                    "\\nLogin menggunakan akun Google melalui situs resmi PusatFilm21."
                )
                .setPositiveButton("Login Google") { _, _ ->
                    val webView = WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                    }

                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(webView, true)
                    }

                    val loginDialog = AlertDialog.Builder(ctx)
                        .setTitle("Login PusatFilm21")
                        .setView(webView)
                        .setNegativeButton("Tutup", null)
                        .create()

                    loginDialog.setOnDismissListener {
                        CookieManager.getInstance().flush()
                        webView.destroy()
                    }

                    webView.loadUrl("https://new1.pusatfilm21info.com/auth/login")
                    loginDialog.show()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }
}
