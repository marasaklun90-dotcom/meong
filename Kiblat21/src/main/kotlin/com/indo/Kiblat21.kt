package com.indo

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
        return try {
            val watchUrl = if (data.trimEnd('/').endsWith("/watch")) {
                data
            } else {
                "${data.trimEnd('/')}/watch"
            }

            val html = app.get(watchUrl, headers = headers).text

            val embedPattern = Regex(
                """(?s)["\\]server["\\]\s*:\s*["\\]([^"\\]+)["\\].{0,500}?["\\]url["\\]\s*:\s*["\\]([^"\\]+)["\\]"""
            )

            val embeds = embedPattern.findAll(html)
                .mapNotNull { match ->
                    val server = match.groupValues[1]
                    val url = match.groupValues[2]
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        server to url
                    } else {
                        null
                    }
                }
                .distinctBy { it.second }
                .toList()

            println("KIBLAT21: Watch HTML length = ${html.length}")
            println("KIBLAT21: Jumlah server ditemukan = ${embeds.size}")

            if (embeds.isEmpty()) {
                println("KIBLAT21: Embed tidak ditemukan. Periksa akses halaman watch.")
                return false
            }

            var foundAny = false

            for ((server, embedUrl) in embeds) {
                try {
                    println("KIBLAT21: Mencoba $server -> $embedUrl")

                    val loaded = loadExtractor(
                        embedUrl,
                        watchUrl,
                        subtitleCallback
                    ) { extracted ->
                        val labeled = ExtractorLink(
                            extracted.source,
                            "$server - ${extracted.name}",
                            extracted.url,
                            extracted.referer,
                            extracted.quality,
                            extracted.headers,
                            extracted.extractorData,
                            extracted.type,
                            extracted.audioTracks
                        )
                        callback(labeled)
                    }

                    if (loaded) {
                        foundAny = true
                    }
                } catch (e: Exception) {
                    println("KIBLAT21: $server gagal: ${e.message}")
                }
            }

            foundAny
        } catch (e: Exception) {
            println("KIBLAT21 loadLinks error: ${e.message}")
            false
        }
    }

}
