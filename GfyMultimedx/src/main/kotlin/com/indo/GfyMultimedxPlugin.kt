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
                        .setNegativeButton("Tutup", null)
                        .create()

                    dialog.setOnDismissListener {
                        CookieManager.getInstance().flush()
                        webView.destroy()
                    }

                    dialog.show()
                    webView.loadUrl(
                        "https://gfymultimedx.online/akun/masuk"
                    )
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }
}
