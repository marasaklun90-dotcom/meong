package com.indo

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class Kiblat21 : MainAPI() {
    override var mainUrl = "https://kiblatfilm21.com"
    override var name = "Kiblat21"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Film Terbaru"
    )

    private fun toSearchResult(element: Element): SearchResponse? {
        val link = element.selectFirst("a[href*='/movie/'], a[href*='/tv/']") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = (
            element.selectFirst("h2, h3")?.text()
                ?: link.attr("title").ifBlank {
                    link.selectFirst("img")?.attr("alt").orEmpty()
                }
            ).trim()

        if (title.isBlank()) return null

        val poster = link.selectFirst("img")?.let {
            fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") })
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("article, .movie-item, .film-item, .item")
            .mapNotNull { toSearchResult(it) }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/search?q=$encoded", headers = headers).document

        return doc.select("article, .movie-item, .film-item, .item")
            .mapNotNull { toSearchResult(it) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Kiblat21"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val page = app.get(data, headers = headers)
            val doc = page.document

            val pattern = Regex(
                """https?://player\.vidzee\.wtf/embed/(movie|tv)/(\d+)(?:/(\d+)/(\d+))?"""
            )

            val iframeUrl = doc.select("iframe[src]")
                .map { it.attr("src") }
                .firstNotNullOfOrNull { src ->
                    pattern.find(src)?.value
                } ?: pattern.find(page.text)?.value
                  ?: pattern.find(data)?.value
                  ?: return false

            val match = pattern.find(iframeUrl) ?: return false
            val type = match.groupValues[1]
            val tmdbId = match.groupValues[2]

            val streamPath = if (type == "movie") {
                "/streams/movie/$tmdbId"
            } else {
                val season = match.groupValues[3].ifBlank { "1" }
                val episode = match.groupValues[4].ifBlank { "1" }
                "/streams/tv/$tmdbId/$season/$episode"
            }

            val apiUrl = "https://core.vidzee.wtf$streamPath?s=v6%3AEnglish"
            val response = app.get(
                apiUrl,
                headers = mapOf(
                    "Referer" to "https://player.vidzee.wtf/",
                    "User-Agent" to headers["User-Agent"].orEmpty()
                )
            )

            val json = JSONObject(response.text)
            val streamUrl = json.optString("url")
            if (streamUrl.isBlank()) return false

            val language = json.optString("language", "English")
            val streamHeaders = mutableMapOf(
                "Referer" to "https://player.vidzee.wtf/",
                "User-Agent" to headers["User-Agent"].orEmpty()
            )

            val extraHeaders = json.optJSONObject("headers")
            extraHeaders?.keys()?.forEach { key ->
                streamHeaders[key] = extraHeaders.optString(key)
            }

            callback(
                newExtractorLink(
                    "Vidzee",
                    "Vidzee $language",
                    streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.P720.value
                    this.referer = "https://player.vidzee.wtf/"
                    this.headers = streamHeaders
                }
            )
            true
        } catch (e: Exception) {
            println("Kiblat21 Vidzee error: ${e.message}")
            false
        }
    }
}
