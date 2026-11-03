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
import kotlinx.coroutines.async
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

        val mirrors = listOf(
            "Dcloud" to "dcloud",
            "Tcloud" to "tik",
            "Hindi" to "v6:Hindi",
            "English" to "v6:English",
            "Acme" to "acme"
        )

        kotlinx.coroutines.supervisorScope {
            val requests = mirrors.map { (label, mirrorId) ->
                label to async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(5000L) {
                            val encodedId = java.net.URLEncoder.encode(
                                mirrorId, "UTF-8"
                            )
                            val response = app.get(
                                "https://core.vidzee.wtf/streams/$path?s=$encodedId&e=1",
                                headers = mapOf(
                                    "Referer" to "$mainUrl/",
                                    "Origin" to mainUrl
                                )
                            ).text
                            JSONObject(response).optString("c")
                                .takeIf { it.isNotBlank() }
                        }
                    } catch (e: Exception) {
                        println("VIDZEE $label API gagal: ${e.message}")
                        null
                    }
                }
            }

            for ((label, request) in requests) {
                val encrypted = request.await() ?: continue

                try {
                    val decrypted = withTimeoutOrNull(12000L) {
                        decrypt(encrypted, url)
                    } ?: continue

                    val stream = JSONObject(decrypted)
                    val streamUrl = stream.optString("url")
                    if (!streamUrl.startsWith("https://")) continue

                    val streamHeaders = mutableMapOf<String, String>()
                    stream.optJSONObject("headers")?.let { headers ->
                        for (key in headers.keys()) {
                            streamHeaders[key] = headers.optString(key)
                        }
                    }

                    val displayName = "Vidzee $label"

                    val qualityLinks = try {
                        com.lagradost.cloudstream3.utils.M3u8Helper.generateM3u8(
                            source = displayName,
                            streamUrl = streamUrl,
                            referer = "$mainUrl/",
                            headers = streamHeaders
                        )
                    } catch (e: Exception) {
                        println("VIDZEE $label resolusi gagal: ${e.message}")
                        emptyList()
                    }

                    if (qualityLinks.isNotEmpty()) {
                        qualityLinks.forEach { link ->
                            callback(
                                newExtractorLink(
                                    name,
                                    "$displayName ${link.quality}p",
                                    link.url,
                                    link.type
                                ) {
                                    this.referer = link.referer
                                    this.quality = link.quality
                                    this.headers = streamHeaders
                                }
                            )
                        }
                    } else {
                        callback(
                            newExtractorLink(
                                name,
                                displayName,
                                streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$mainUrl/"
                                this.headers = streamHeaders
                            }
                        )
                    }
                    println("VIDZEE $label berhasil")
                } catch (e: Exception) {
                    println("VIDZEE $label gagal: ${e.message}")
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun decrypt(encrypted: String, embedUrl: String): String? =
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
                                        document.title = "VIDZEE_RESULT:" +
                                            encodeURIComponent(JSON.stringify(data));
                                    } catch (e) {
                                        document.title = "VIDZEE_ERROR";
                                    }
                                })()
                            """.trimIndent()

                            view.evaluateJavascript(js, null)
                        }
                    }
                }

                webView.loadUrl(embedUrl)
            }
        }
}
