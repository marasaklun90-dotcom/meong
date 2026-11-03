package com.indo

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import java.net.URLDecoder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

class Vidzee(private val context: Context) : ExtractorApi() {
    override var name = "Vidzee"
    override var mainUrl = "https://player.vidzee.wtf"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val movie = Regex("""/embed/movie/(\d+)""").find(url)
        val tv = Regex("""/embed/tv/(\d+)/(\d+)/(\d+)""").find(url)

        val path = when {
            movie != null -> "movie/${movie.groupValues[1]}"
            tv != null -> "tv/${tv.groupValues[1]}/${tv.groupValues[2]}/${tv.groupValues[3]}"
            else -> return
        }

        val response = app.get(
            "https://core.vidzee.wtf/streams/$path?s=dcloud&e=1",
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl
            )
        ).text

        val encrypted = JSONObject(response).optString("c")
        if (encrypted.isBlank()) return

        val result = withTimeoutOrNull(20000L) {
            decrypt(encrypted)
        } ?: return

        val stream = JSONObject(result)
        val streamUrl = stream.optString("url")
        if (!streamUrl.startsWith("https://")) return

        val streamHeaders = mutableMapOf<String, String>()
        stream.optJSONObject("headers")?.let { headers ->
            for (key in headers.keys()) {
                streamHeaders[key] = headers.optString(key)
            }
        }

        callback(
            newExtractorLink(
                name,
                "$name Dcloud",
                streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.headers = streamHeaders
            }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun decrypt(encrypted: String): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context.applicationContext)
                var finished = false
                var decryptStarted = false

                fun finish(value: String?) {
                    if (finished) return
                    finished = true
                    if (continuation.isActive) continuation.resume(value)
                    webView.stopLoading()
                    webView.destroy()
                }

                continuation.invokeOnCancellation {
                    webView.post {
                        if (!finished) finish(null)
                    }
                }

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView, title: String?) {
                        super.onReceivedTitle(view, title)
                        if (finished || title == null) return

                        if (title.startsWith("VIDZEE_RESULT:")) {
                            try {
                                val decoded = URLDecoder.decode(
                                    title.removePrefix("VIDZEE_RESULT:"),
                                    "UTF-8"
                                )
                                finish(if (decoded == "null") null else decoded)
                            } catch (_: Exception) {
                                finish(null)
                            }
                        } else if (title == "VIDZEE_ERROR") {
                            finish(null)
                        }
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        if (finished || decryptStarted) return
                        if (!loadedUrl.startsWith(mainUrl)) {
                            finish(null)
                            return
                        }

                        decryptStarted = true
                        view.evaluateJavascript(VidzeeCrypto.script) {
                            val payload = JSONObject.quote(encrypted)
                            val js = """
                                (async function() {
                                    try {
                                        const data = await window.vidzeeDecrypt($payload);
                                        return data ? JSON.stringify(data) : null;
                                    } catch (e) {
                                        return null;
                                    }
                                })()
                            """.trimIndent()

                            view.evaluateJavascript(js, null)
                        }
                    }
                }

                webView.loadUrl("$mainUrl/embed/tv/259702/1/1")
            }
        }
}
