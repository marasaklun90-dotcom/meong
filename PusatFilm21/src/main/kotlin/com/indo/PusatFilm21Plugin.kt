package com.indo

import android.app.AlertDialog
import android.graphics.Color
import android.view.WindowManager
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
            val settingsDialog = AlertDialog.Builder(ctx)
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
                    val loginUrl = "https://new1.pusatfilm21info.com/auth/login"
                    val webView = WebView(ctx).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
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
                        .setNeutralButton("Kembali", null)
                        .setNegativeButton("Tutup", null)
                        .create()

                    loginDialog.setOnDismissListener {
                        CookieManager.getInstance().flush()
                        webView.destroy()
                    }

                    loginDialog.show()
                    loginDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                        setTextColor(Color.WHITE)
                        setOnClickListener {
                            if (webView.canGoBack()) webView.goBack()
                            else webView.loadUrl(loginUrl)
                        }
                    }
                    loginDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                        ?.setTextColor(Color.WHITE)
                    loginDialog.window?.clearFlags(
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    )
                    loginDialog.window?.setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    )
                    webView.requestFocus()
                    webView.loadUrl(loginUrl)
                }
                .setNegativeButton("Batal", null)
                .show()
            settingsDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(Color.WHITE)
            settingsDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                ?.setTextColor(Color.WHITE)
        }
    }
}
