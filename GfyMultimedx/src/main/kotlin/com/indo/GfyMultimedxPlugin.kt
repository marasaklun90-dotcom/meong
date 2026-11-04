package com.indo

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
        registerMainAPI(GfyMultimedx())

        openSettings = { ctx ->
            val cookie = CookieManager.getInstance()
                .getCookie("https://gfymultimedx.online")

            val hasSession = cookie
                ?.split(";")
                ?.any { it.trim().startsWith("laravel-session=") } == true

            AlertDialog.Builder(ctx)
                .setTitle("Pengaturan GFYMultimedx")
                .setMessage(
                    "Cookie sesi: " +
                    (if (hasSession) "Tersedia" else "Belum tersedia") +
                    "\nLogin email OTP melalui situs resmi GFYMultimedx."
                )
                .setPositiveButton("Login OTP") { _, _ ->
                    val webView = WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                    }

                    CookieManager.getInstance().setAcceptCookie(true)

                    val dialog = AlertDialog.Builder(ctx)
                        .setTitle("Login GFYMultimedx")
                        .setView(webView)
                        .setNeutralButton("Kembali", null)
                        .setNegativeButton("Tutup", null)
                        .create()

                    dialog.setOnDismissListener {
                        CookieManager.getInstance().flush()
                        webView.destroy()
                    }

                    dialog.show()
                    webView.isFocusable = true
                    webView.isFocusableInTouchMode = true
                    dialog.window?.clearFlags(
                        android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    )
                    dialog.window?.setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    )
                    webView.requestFocus()

                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                        setTextColor(android.graphics.Color.WHITE)
                        setOnClickListener {
                            if (webView.canGoBack()) webView.goBack()
                        }
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                        ?.setTextColor(android.graphics.Color.WHITE)

                    webView.loadUrl(
                        "https://gfymultimedx.online/akun/masuk"
                    )
                }
                .setNegativeButton("Batal", null)
                .show().also { d ->
                    d.getButton(AlertDialog.BUTTON_POSITIVE)
                        ?.setTextColor(android.graphics.Color.WHITE)
                    d.getButton(AlertDialog.BUTTON_NEGATIVE)
                        ?.setTextColor(android.graphics.Color.WHITE)
                }
        }
    }
}
