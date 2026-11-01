package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONArray
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KiblatFilm21 : MainAPI() {

    override var mainUrl = "https://kiblatfilm21.com"
    override var name = "KiblatFilm21"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/137.0.0.0 Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Beranda"
    )

    private fun absoluteUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun getPoster(element: Element): String? {
        val img = element.selectFirst("img") ?: return null
        val poster = img.attr("src")
            .ifBlank { img.attr("data-src") }
            .ifBlank { img.attr("data-lazy-src") }

        return poster.takeIf { it.isNotBlank() }?.let { absoluteUrl(it) }
    }

    private fun toSearchResponse(element: Element): SearchResponse? {
        val anchor = if (element.tagName() == "a") {
            element
        } else {
            element.selectFirst("a[href]") ?: return null
        }

        val href = anchor.attr("href")
        if (href.isBlank()) return null

        val url = absoluteUrl(href)
        val title = anchor.selectFirst("h2, h3, h4, .title")?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: anchor.selectFirst("img")?.attr("alt")
                ?.trim()
                ?.ifBlank { null }
            ?: anchor.text().trim().ifBlank { null }
            ?: return null

        val poster = getPoster(anchor)

        return if (url.contains("/series/")) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) {
            mainUrl
        } else {
            "$mainUrl/page/$page"
        }

        val doc = app.get(url, headers = headers).document
        val items = doc.select("a.content-card")
            .mapNotNull { toSearchResponse(it) }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/search?q=$encoded"

        val doc = app.get(url, headers = headers).document

        return doc.select("a.content-card")
            .mapNotNull { toSearchResponse(it) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("—")
                ?.trim()
            ?: "KiblatFilm21"

        val poster = doc.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[name=twitter:image]")
                ?.attr("content")
                ?.ifBlank { null }

        val plot = doc.selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")
                ?.attr("content")
                ?.ifBlank { null }

        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(doc.title())
            ?.value
            ?.toIntOrNull()

        val tags = doc.select("a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (url.contains("/series/")) {
            val episodes = doc.select(
                "a[href*='/season/'][href*='/episode/']"
            ).mapNotNull { anchor ->

                val href = anchor.attr("href")
                if (href.isBlank()) return@mapNotNull null

                val episodeUrl = absoluteUrl(href)

                val match = Regex(
                    """/season/(\d+)/episode/(\d+)"""
                ).find(episodeUrl) ?: return@mapNotNull null

                val season = match.groupValues[1].toIntOrNull() ?: 1
                val episode = match.groupValues[2].toIntOrNull() ?: 1

                val episodeTitle = anchor.selectFirst("h3, h4")
                    ?.text()
                    ?.trim()
                    ?.ifBlank { null }
                    ?: anchor.text()
                        .replace(Regex("""S\d+E\d+"""), "")
                        .trim()
                        .ifBlank { "Episode $episode" }

                val episodePoster = getPoster(anchor)

                newEpisode(episodeUrl) {
                    this.name = episodeTitle
                    this.season = season
                    this.episode = episode
                    this.posterUrl = episodePoster
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        val watchUrl = if (url.endsWith("/watch")) {
            url
        } else {
            "${url.trimEnd('/')}/watch"
        }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            watchUrl
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    private fun extractEmbeds(html: String): List<Pair<String, String>> {
        return try {
            val pattern = Regex(
                """\\?"embeds\\?"\s*:\s*(\[[\s\S]*?\])"""
            )

            val raw = pattern.find(html)?.groupValues?.get(1)
                ?: return emptyList()

            val normalized = raw
                .replace("\\\"", "\"")
                .replace("\\u0026", "&")
                .replace("\\/", "/")

            val array = JSONArray(normalized)
            val result = mutableListOf<Pair<String, String>>()

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val server = item.optString("server")
                    .ifBlank { "Server ${i + 1}" }
                val playerUrl = item.optString("url")

                if (playerUrl.startsWith("http")) {
                    result.add(server to playerUrl)
                }
            }

            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val pageHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "$mainUrl/"
        )

        val html = try {
            app.get(data, headers = pageHeaders).text
        } catch (_: Exception) {
            return false
        }

        val embeds = extractEmbeds(html)
        if (embeds.isEmpty()) return false

        var found = false

        for ((server, playerUrl) in embeds) {
            try {
                loadExtractor(
                    playerUrl,
                    data,
                    subtitleCallback,
                    callback
                )
                found = true
            } catch (_: Exception) {
                // Lewati server yang gagal.
            }
        }

        return found
    }
}
