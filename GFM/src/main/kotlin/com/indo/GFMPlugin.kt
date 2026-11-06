package com.indo

import android.app.AlertDialog
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class GFMPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(GFM())

        openSettings = { ctx ->
            val cookie = CookieManager.getInstance()
                .getCookie("https://gfymultimedx.online")

            val hasSession = cookie
                ?.split(";")
                ?.any { it.trim().startsWith("laravel-session=") } == true

            AlertDialog.Builder(ctx)
                .setTitle("Pengaturan GFM")
                .setMessage(
                    "Cookie sesi: " +
                    (if (hasSession) "Tersedia" else "Belum tersedia") +
                    "\nLogin email OTP melalui situs resmi GFM."
                )
                .setPositiveButton("Login OTP") { _, _ ->
                    val webView = WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        
// Blokir popup iklan GFM
settings.setSupportMultipleWindows(true)
settings.javaScriptCanOpenWindowsAutomatically = false

webChromeClient = object : android.webkit.WebChromeClient() {
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?
    ): Boolean = false
}

webViewClient = object : WebViewClient() {
    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: android.webkit.WebResourceRequest?
    ): Boolean {
        if (request == null || !request.isForMainFrame)
            return false

        val uri = request.url
        val host = uri.host?.lowercase().orEmpty()
        val allowed = host == "gfymultimedx.online" ||
            host.endsWith(".gfymultimedx.online")

        if (!allowed) return true

        val path = uri.path.orEmpty().lowercase()
        val query = uri.query.orEmpty().lowercase()

        val adPatterns = listOf(
            "/popunder", "/pop-up", "/popup",
            "/advert", "/ads/", "/redirect-ad"
        )

        return adPatterns.any {
            path.contains(it) || query.contains(it)
        }
    }
}

                    }

                    CookieManager.getInstance().setAcceptCookie(true)

                    val dialog = AlertDialog.Builder(ctx)
                        .setTitle("Login GFM")
                        .setView(webView)
                        .setNeutralButton("Kembali", null)
                        .setNegativeButton("Tutup", null)
                        .create()


                    val gfmAutoHandler = android.os.Handler(
                        android.os.Looper.getMainLooper()
                    )
                    var gfmRunning = true
                    val gfmAutoTask = object : Runnable {
                        override fun run() {
                            if (!gfmRunning) return
                            webView.evaluateJavascript(
                                """
                                (function() {
                                  if (location.hostname !== 'gfymultimedx.online')
                                    return;
                                  var text = document.body?.innerText.toLowerCase() || '';
                                  if (text.includes('verifikasi berhasil')) {
                                    var links = document.querySelectorAll('a,button');
                                    for (var link of links) {
                                      var label = (link.innerText || link.textContent || '')
                                        .trim().toLowerCase();
                                      if (label.includes('kembali ke beranda') &&
                                          link.getClientRects().length) {
                                        if (!link.dataset.gfmClicked) {
                                          link.dataset.gfmClicked = '1';
                                          link.click();
                                        }
                                        return;
                                      }
                                    }
                                    return;
                                  }
                                  var ready = text.includes('pemeriksaan siap');
                                  var targets = ready
                                    ? ['konfirmasi verifikasi']
                                    : ['lanjutkan verifikasi keamanan'];
                                  for (var b of document.querySelectorAll('button,a')) {
                                    var label = (b.innerText || '').trim().toLowerCase();
                                    if (targets.some(t => label.includes(t)) &&
                                        !b.disabled && b.getClientRects().length) {
                                      if (b.dataset.gfmClicked) return;
                                      b.dataset.gfmClicked = '1';
                                      b.click();
                                      return;
                                    }
                                  }
                                })();
                                """.trimIndent(), null
                            )
                            gfmAutoHandler.postDelayed(this, 2000)
                        }
                    }

                    dialog.setOnDismissListener {
                        gfmRunning = false
                        gfmAutoHandler.removeCallbacks(gfmAutoTask)
                        CookieManager.getInstance().flush()
                        webView.destroy()
                    }

                    dialog.show()
                    gfmAutoHandler.postDelayed(gfmAutoTask, 2000)
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
