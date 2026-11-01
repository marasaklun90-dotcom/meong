package com.indo

import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
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
        val link = if (element.tagName() == "a") element
            else element.selectFirst("a.content-card[href*='/movie/'], a.content-card[href*='/series/']")
                ?: return null

        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = link.selectFirst("img")?.attr("alt")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null

        val poster = link.selectFirst("img")?.let {
            fixUrlNull(
                it.attr("src").ifBlank {
                    it.attr("data-src")
                }
            )
        }

        return if (href.contains("/series/")) {
            newTvSeriesSearchResponse(title, href) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(mainUrl, headers = headers).document

        val section = doc.select(".cv-row").firstOrNull {
            it.selectFirst(".content-row-header__title")?.text()
                ?.equals(request.name, ignoreCase = true) == true
        }

        val items = (section?.select("a.content-card")
            ?: doc.select(".cv-row a.content-card"))
            .mapNotNull { toSearchResult(it) }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/search?q=$encoded", headers = headers).document

        return doc.select(
            "a.content-card[href*='/movie/'], a.content-card[href*='/series/']"
        ).mapNotNull { toSearchResult(it) }
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
        val watchUrl = if (data.trimEnd('/').endsWith("/watch")) data
                       else "${data.trimEnd('/')}/watch"
        val html = app.get(watchUrl, headers = headers).text
            .replace("\\\"", "\"").replace("\\/", "/")
        val embeds = Regex(""""server":"([^"]+)","url":"([^"]+)","blocked":(true|false)""")
            .findAll(html)
            .filter { it.groupValues[3] == "false" }
            .map { it.groupValues[1] to it.groupValues[2] }
            .distinctBy { it.second }
            .toList()
        println("KIBLAT21: server ditemukan = ${embeds.size}")
        if (embeds.isEmpty()) return false
        embeds.amap { (server, embedUrl) ->
            try {
                val target = if (embedUrl.contains("2embed.cc")) {
                    val src = app.get(embedUrl, referer = watchUrl).document
                        .selectFirst("iframe#iframesrc")?.attr("data-src")
                    val id = src?.let {
                        Regex("""[?&]id=([A-Za-z0-9]+)""").find(it)?.groupValues?.get(1)
                    }
                    if (id != null) "https://2vcdn.skin/e/$id" else embedUrl
                } else embedUrl
                val ref = if (target.contains("2vcdn.skin"))
                    "https://streamsrcs.2embed.cc/" else watchUrl
                val ok = loadExtractor(target, ref, subtitleCallback, callback)
                if (!ok) {
                    val m3u8 = app.get(
                        embedUrl,
                        referer = watchUrl,
                        interceptor = WebViewResolver(Regex("""\.m3u8"""))
                    ).url
                    if (m3u8.contains(".m3u8")) {
                        callback(
                            newExtractorLink(server, server, m3u8, ExtractorLinkType.M3U8) {
                                this.referer = embedUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                println("KIBLAT21: $server gagal: ${e.message}")
            }
        }
        return true
    }
}
