package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.CommonActivity
import android.app.AlertDialog
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONObject
import org.jsoup.nodes.Element

class GfyMultimedx : MainAPI() {
    override var mainUrl = "https://gfymultimedx.online"
    override var name = "GfyMultimedx"
    override val hasMainPage = true
    override val loadLinksTimeoutMs: Long = 300_000L

    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private fun getAuthHeaders(): Map<String, String> {
        val cookie = try {
            android.webkit.CookieManager.getInstance()
                .getCookie(mainUrl)
        } catch (_: Exception) {
            null
        }

        return if (!cookie.isNullOrBlank()) {
            headers + mapOf("Cookie" to cookie)
        } else {
            headers
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/film" to "Film Terbaru",
        "$mainUrl/serial" to "Serial TV",
        "$mainUrl/anime" to "Anime",
        "$mainUrl/genre/action" to "Action",
        "$mainUrl/genre/horror" to "Horor",
        "$mainUrl/genre/drama" to "Drama",
        "$mainUrl/genre/comedy" to "Komedi",
        "$mainUrl/genre/thriller" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("a.web-media-link, a[href*='/film/']").mapNotNull { toSearchResponse(it) }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/cari?q=$encoded"
        val doc = app.get(url, headers = headers).document
        return doc.select("a.web-media-link, a[href*='/film/']").mapNotNull { toSearchResponse(it) }
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("""^Poster\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^[▶★\s0-9.]+"""), "")
            .replace(Regex("""(?:Film|Serial|Anime)$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun toSearchResponse(element: Element): SearchResponse? {
        val rawHref = element.attr("href").trim()
        if (rawHref.isBlank() || rawHref.startsWith("#")) return null
        if (!rawHref.contains("/film/")) return null

        val href = fixUrlNull(rawHref) ?: return null

        val rawTitle = element.attr("data-web-title").ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: element.text().trim()
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null

        val poster = element.attr("data-web-backdrop").ifBlank { null }
            ?: element.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }

        val meta = element.attr("data-web-meta")
        val score = Regex("""★\s*([0-9.]+)""").find(meta)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(meta)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tvType = when {
            meta.contains("Serial", ignoreCase = true) -> TvType.TvSeries
            meta.contains("Anime", ignoreCase = true) -> TvType.Anime
            else -> TvType.Movie
        }

        return when (tvType) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
            TvType.Anime -> newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
            else -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val reqHeaders = getAuthHeaders()
        val doc = app.get(url, headers = reqHeaders).document

        if (doc.select("form[data-traffic-verification]").isNotEmpty() ||
            doc.title().contains("Akun", ignoreCase = true) ||
            doc.location().contains("/akun/masuk")
        ) {
            throw ErrorLoadingException("GfyMultimedx sekarang mewajibkan login akun (Email OTP) oleh pemilik web untuk membuka film/episode.")
        }

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "GfyMultimedx"
        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("video[poster]")?.attr("poster")
            ?: doc.selectFirst(".web-media-poster img, img")?.attr("src")

        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: doc.selectFirst(".synopsis, p")?.text()?.trim()

        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(doc.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tags = doc.select("a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val trailerUrl = doc.selectFirst("[data-web-trailer]")?.attr("data-web-trailer")?.ifBlank { null }?.let {
            if (it.startsWith("http")) it else "https://www.youtube.com/watch?v=$it"
        }

        val epLinks = doc.select("a[href*='/season-'][href*='/episode-']")
        val isSeries = epLinks.isNotEmpty() || url.contains("/serial")

        return if (isSeries) {
            val episodes = epLinks.mapIndexedNotNull { index, a ->
                val epUrl = fixUrlNull(a.attr("href")) ?: return@mapIndexedNotNull null
                val epText = a.text().trim().replace(Regex("""\s+"""), " ")

                val sMatch = Regex("""season-(\d+)""", RegexOption.IGNORE_CASE).find(epUrl)
                    ?: Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(epText)
                val eMatch = Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE).find(epUrl)
                    ?: Regex("""E(\d+)""", RegexOption.IGNORE_CASE).find(epText)

                val sNum = sMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val eNum = eMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (index + 1)
                val epName = epText.ifBlank { "S${sNum}E$eNum" }

                newEpisode(epUrl) {
                    this.name = epName
                    this.season = sNum
                    this.episode = eNum
                }
            }.ifEmpty {
                listOf(
                    newEpisode(url) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }

            val isAnime = tags.any { it.contains("anime", ignoreCase = true) } || url.contains("/anime")
            val tvType = if (isAnime) TvType.Anime else TvType.TvSeries

            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }


    private suspend fun extractPixeldrain(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = try {
            java.net.URI(url)
        } catch (_: Exception) {
            return false
        }

        if (parsed.host != "pixeldrain.com") return false

        val parts = parsed.path.trim('/').split("/")
        if (parts.size < 2) return false

        if (parts[0] == "u") {
            val id = parts[1]
            if (!id.matches(Regex("[A-Za-z0-9]{6,64}"))) return false

            callback(
                newExtractorLink(
                    "Pixeldrain",
                    "Pixeldrain",
                    "https://pixeldrain.com/api/file/$id",
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://pixeldrain.com/"
                }
            )
            return true
        }

        if (parts[0] != "d") return false

        val id = parts[1]
        if (!id.matches(Regex("[A-Za-z0-9]{6,64}"))) return false

        val api = "https://pixeldrain.com/api/filesystem/$id"
        val json = JSONObject(app.get(api).text)
        val children = json.optJSONArray("children") ?: return false

        var found = false

        for (i in 0 until children.length()) {
            val item = children.optJSONObject(i) ?: continue
            if (item.optString("type") != "file") continue

            val path = item.optString("path")
            if (path.isBlank()) continue

            val encoded = path.split("/").joinToString("/") {
                java.net.URLEncoder.encode(it, "UTF-8")
                    .replace("+", "%20")
            }

            val fileUrl = "https://pixeldrain.com/api/filesystem$encoded"
            val lower = path.lowercase()

            if (lower.endsWith(".mp4") || lower.endsWith(".mkv")) {
                callback(
                    newExtractorLink(
                        "Pixeldrain",
                        "Pixeldrain",
                        fileUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://pixeldrain.com/"
                        this.quality = Qualities.P1080.value
                    }
                )
                found = true
            } else if (lower.endsWith(".srt") || lower.endsWith(".vtt")) {
                subtitleCallback(newSubtitleFile("Indonesian", fileUrl))
            }
        }

        return found
    }


    private suspend fun openPlaybackWebView(pageUrl: String): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val activity = CommonActivity.activity
                if (activity == null || activity.isFinishing) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                val webView = WebView(activity)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                var dialog: AlertDialog? = null
                var completed = false

                fun finish(url: String?) {
                    if (completed) return
                    completed = true
                    dialog?.dismiss()
                    webView.destroy()
                    if (continuation.isActive) continuation.resume(url)
                }

                // GFY_SMART_ADBLOCK: hanya blokir host iklan yang diketahui.
                val blockedAdHosts = setOf(
                    "pyppo.com",
                    "adsterra.com",
                    "highrevenuegate.com",
                    "highrevenueformat.com"
                )

                fun isBlockedAd(url: String): Boolean {
                    val host = try {
                        android.net.Uri.parse(url).host?.lowercase().orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    return blockedAdHosts.any {
                        host == it || host.endsWith(".$it")
                    }
                }

                webView.settings.javaScriptCanOpenWindowsAutomatically = false
                webView.settings.setSupportMultipleWindows(false)


                // GFY_AUTO_PIXELDRAIN: periksa URL yang sudah tersedia.
                fun checkPixeldrain() {
                    if (completed) return
                    webView.evaluateJavascript(
                        """(function() {
                            const urls = Array.from(
                                document.querySelectorAll('iframe[src], a[href]')
                            ).map(e => e.src || e.href);
                            return urls.find(u =>
                                /^https:\\/\\/pixeldrain\\.com\\/(d|u)\\//i.test(u)
                            ) || '';
                        })();"""
                    ) { result ->
                        val url = try {
                            org.json.JSONTokener(result).nextValue() as? String
                        } catch (_: Exception) {
                            null
                        }
                        if (!url.isNullOrBlank() && !completed) {
                            finish(url)
                        }
                    }
                }


                // GFY_AUTO_SKIP_V9: klik tombol Skip resmi saat aktif.
                val skipHandler = android.os.Handler(
                    android.os.Looper.getMainLooper()
                )

                val skipTask = object : Runnable {
                    override fun run() {
                        if (completed || !continuation.isActive) return

                        checkPixeldrain()

                        webView.evaluateJavascript(
                            """(function() {
                                const elements = Array.from(
                                    document.querySelectorAll(
                                        'button, a, [role="button"]'
                                    )
                                );
                                const button = elements.find(el => {
                                    const text = (
                                        el.innerText || el.textContent || ''
                                    ).trim().toLowerCase();
                                    const visible = !!(
                                        el.getClientRects().length
                                    );
                                    const disabled =
                                        el.disabled ||
                                        el.getAttribute('aria-disabled') === 'true';
                                    return visible && !disabled &&
                                        /^(skip ad|skip ads|lewati iklan|skip iklan|lewati)$/.test(text);
                                });
                                if (button) {
                                    button.click();
                                    return 'clicked';
                                }
                                return 'waiting';
                            })();""",
                            null
                        )

                        skipHandler.postDelayed(this, 1500L)
                    }
                }

                continuation.invokeOnCancellation {
                    skipHandler.removeCallbacks(skipTask)
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        checkPixeldrain()
                    }


                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString().orEmpty()
                        return isBlockedAd(url) || (!url.startsWith("https://") &&
                               !url.startsWith("http://"))
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val url = request?.url?.toString().orEmpty()
                        if (url.startsWith("https://pixeldrain.com/d/") ||
                            url.startsWith("https://pixeldrain.com/u/")) {
                            activity.runOnUiThread { finish(url) }
                        }
                        if (isBlockedAd(url)) {
                            return android.webkit.WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                java.io.ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                dialog = AlertDialog.Builder(activity)
                    .setTitle("GFYMultimedx - Verifikasi Playback")
                    .setView(webView)
                    .setNeutralButton("Kembali", null)
                    .setNegativeButton("Batal") { _, _ -> finish(null) }
                    .create()

                dialog?.setOnCancelListener { finish(null) }
                continuation.invokeOnCancellation {
                    activity.runOnUiThread {
                        if (!completed) {
                            completed = true
                            dialog?.dismiss()
                            webView.destroy()
                        }
                    }
                }

                dialog?.show()
                dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        webView.loadUrl(pageUrl)
                    }
                }
                skipHandler.postDelayed(skipTask, 1500L)
                webView.loadUrl(pageUrl)
            }
        }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val reqHeaders = getAuthHeaders()
        val pageResp = app.get(data, headers = reqHeaders)
        val doc = pageResp.document
        android.util.Log.d(
            "GFY_DEBUG",
            "loadLinks: loginRedirect=${pageResp.url.contains("/akun/masuk")}, " +
            "deferred=${doc.select("video[data-gfy-deferred-source]").size}, " +
            "iframe=${doc.select("iframe[src]").size}, " +
            "video=${doc.select("video").size}, " +
            "playButtons=${doc.select("button, a").count { it.text().contains("putar", true) || it.text().contains("play", true) }}"
        )

        if (doc.select("form[data-traffic-verification]").isNotEmpty() ||
            doc.title().contains("Akun", ignoreCase = true) ||
            pageResp.url.contains("/akun/masuk")
        ) {
            throw ErrorLoadingException("GfyMultimedx sekarang mewajibkan login akun (Email OTP) oleh pemilik web untuk memutar video.")
        }

        // 1. Extract subtitles from <track>
        doc.select("track[kind='subtitles'], track[src]").forEach { track ->
            val subUrl = fixUrlNull(track.attr("src")) ?: return@forEach
            val rawLang = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Indonesia" }
            val label = when (rawLang.lowercase()) {
                "id", "ind", "indonesia", "indonesian" -> "Indonesian"
                "en", "eng", "english" -> "English"
                else -> rawLang
            }
            subtitleCallback(newSubtitleFile(label, subUrl))
        }

        var foundStream = false

        // 2. Extract video from data-gfy-deferred-source
        val video = doc.selectFirst("video[data-gfy-deferred-source]")
        val deferredSource = video?.attr("data-gfy-deferred-source")

        if (!deferredSource.isNullOrBlank()) {
            try {
                val endpoint = fixUrl(deferredSource)
                val csrf = video?.attr("data-gfy-deferred-csrf").orEmpty()
                val postResp = app.post(
                    endpoint,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "X-Requested-With" to "XMLHttpRequest",
                        "X-CSRF-TOKEN" to csrf,
                        "Referer" to data,
                        "Origin" to mainUrl
                    ),
                    cookies = pageResp.cookies
                ).text

                val json = JSONObject(postResp)
                val streamUrl = json.optString("url")
                if (json.optString("type") == "pixeldrain-viewer") {
                    if (extractPixeldrain(streamUrl, subtitleCallback, callback)) {
                        foundStream = true
                    }
                }
                val isHls = json.optString("type") == "hls" || streamUrl.contains(".m3u8")

                if (!foundStream && json.optString("type") != "pixeldrain-viewer" && streamUrl.startsWith("http")) {
                    if (isHls) {
                        try {
                            val m3u8Links = M3u8Helper.generateM3u8(this.name, streamUrl, mainUrl)
                            if (m3u8Links.isNotEmpty()) {
                                m3u8Links.forEach { callback(it) }
                                foundStream = true
                            } else {
                                callback(
                                    newExtractorLink(
                                        this.name,
                                        this.name,
                                        streamUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = mainUrl
                                    }
                                )
                                foundStream = true
                            }
                        } catch (_: Exception) {
                            callback(
                                newExtractorLink(
                                    this.name,
                                    this.name,
                                    streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = mainUrl
                                }
                            )
                            foundStream = true
                        }
                    } else {
                        callback(
                            newExtractorLink(
                                this.name,
                                this.name,
                                streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.P1080.value
                            }
                        )
                        foundStream = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3. Fallback: check for iframes
        doc.select("iframe.external-embed, .player iframe, iframe[src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            if (!src.contains("quge5") && !src.contains("adsterra") && !src.contains("highrevenueformat")) {
                loadExtractor(src, data, subtitleCallback, callback)
                foundStream = true
            }
        }

        if (!foundStream) {
            val webPixeldrainUrl = openPlaybackWebView(data)
            if (!webPixeldrainUrl.isNullOrBlank()) {
                foundStream = extractPixeldrain(
                    webPixeldrainUrl,
                    subtitleCallback,
                    callback
                )
            }
        }

        if (!foundStream) {
            throw ErrorLoadingException(
                "GFY DEBUG: " +
                "login=${pageResp.url.contains("/akun/masuk")}, " +
                "deferred=${doc.select("video[data-gfy-deferred-source]").size}, " +
                "iframe=${doc.select("iframe[src]").size}, " +
                "video=${doc.select("video").size}, " +
                "pixeldrain=${doc.html().contains("pixeldrain", true)}"
            )
        }
        return foundStream
    }
}
