package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document

class Donghub : MainAPI() {
    override var mainUrl = "https://donghive.vip"
    override var name = "Donghub"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.TvSeries)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Popular Today",
        "$mainUrl/" to "Latest Release",
    )

    private fun parseItems(doc: Document, selector: String): List<SearchResponse> {
        return doc.select(selector).mapNotNull { item ->
            val a = item.selectFirst(".bsx > a") ?: return@mapNotNull null
            val href = a.attr("href")
            val poster = item.selectFirst(".limit img")?.attr("src")?.ifBlank { null }
            val type = item.selectFirst(".eggtype")?.text()?.trim()
                ?: item.selectFirst(".typez")?.text()?.trim()
            val tvType = when (type) {
                "Movie" -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            val title = item.selectFirst(".eggtitle")?.text()?.trim()?.ifBlank { null }
                ?: item.selectFirst(".tt")?.ownText()?.trim()?.ifBlank { null }
                ?: item.selectFirst(".tt h2")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null

            val epText = item.selectFirst(".eggepisode")?.text()?.trim()
                ?: item.selectFirst(".bt .epx")?.text()?.trim()
            val epNum = Regex("""(\d+)""").find(epText ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()

            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                this.posterHeaders = defaultHeaders
                addSub(epNum)
            }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == "Popular Today") {
            val doc = app.get(mainUrl, headers = defaultHeaders).document
            val items = parseItems(doc, ".listupd.popularslider article.bs")
            return newHomePageResponse(request.name, items)
        }

        val url = if (page > 1) "$mainUrl/page/$page/" else mainUrl
        val doc = app.get(url, headers = defaultHeaders).document
        val items = parseItems(doc, ".listupd.normal article.bs")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = defaultHeaders).document
        return parseItems(doc, "div.listupd article.bs")
    }

    private fun episodeToSeriesUrl(url: String): String? {
        val slug = url.trimEnd('/').substringAfterLast("/")
        val seriesSlug = Regex("-episode-\\d+.*$", RegexOption.IGNORE_CASE).replace(slug, "")
        if (seriesSlug == slug || seriesSlug.isBlank()) return null
        return "$mainUrl/$seriesSlug/"
    }

    private suspend fun resolveSeriesUrl(url: String): String {
        if (!url.contains("-episode-", ignoreCase = true)) return url
        try {
            val doc = app.get(url, headers = defaultHeaders).document
            val breadcrumbLink = doc.select(".ts-breadcrumb a[itemprop=item], .breadcrumb a")
                .map { it.attr("href") }
                .firstOrNull { it.isNotBlank() && !it.equals("$mainUrl/", ignoreCase = true) && !it.equals(url, ignoreCase = true) }
            if (!breadcrumbLink.isNullOrBlank()) return breadcrumbLink
        } catch (_: Exception) {}
        return episodeToSeriesUrl(url) ?: url
    }

    override suspend fun load(url: String): LoadResponse {
        val animeUrl = resolveSeriesUrl(url)
        val doc = app.get(animeUrl, headers = defaultHeaders).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst(".infolimit h2")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst(".single-info .thumb img")?.attr("src")
            ?: doc.selectFirst(".thumb img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val synopsis = doc.select(".desc p, .entry-content p").text().trim().ifBlank { null }
        val tags = doc.select(".genxed a").mapNotNull { it.text().trim().ifBlank { null } }

        val slug = animeUrl.trimEnd('/').substringAfterLast("/")
        val catRaw = app.get("$mainUrl/wp-json/wp/v2/categories?slug=$slug&per_page=1&_fields=id", headers = defaultHeaders).text
        val catList = tryParseJson<List<Map<String, Any?>>>(catRaw)
        val categoryId = catList?.firstOrNull()?.get("id")?.toString()

        val episodes = mutableListOf<Episode>()
        if (categoryId != null) {
            var apiPage = 1
            while (true) {
                val postRaw = app.get(
                    "$mainUrl/wp-json/wp/v2/posts?categories=$categoryId&per_page=100&page=$apiPage&_fields=id,title,link",
                    headers = defaultHeaders
                ).text
                val posts = tryParseJson<List<Map<String, Any?>>>(postRaw) ?: break
                if (posts.isEmpty()) break
                posts.forEach { post ->
                    val epHref = post["link"]?.toString() ?: return@forEach
                    val titleObj = post["title"] as? Map<*, *>
                    val epTitle = titleObj?.get("rendered")?.toString()?.ifBlank { null } ?: return@forEach
                    val epNum = Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    episodes.add(newEpisode(epHref) {
                        this.name = epTitle
                        this.episode = epNum
                        this.posterUrl = poster
                    })
                }
                if (posts.size < 100) break
                apiPage++
            }
        }

        if (episodes.isEmpty()) {
            doc.select(".eplister ul li a").forEach { a ->
                val epHref = a.attr("href").ifBlank { return@forEach }
                val epTitle = a.selectFirst(".epl-title")?.text()?.trim() ?: a.text().trim()
                val epNum = a.selectFirst(".epl-num")?.text()?.toIntOrNull()
                    ?: Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                episodes.add(newEpisode(epHref) {
                    this.name = epTitle
                    this.episode = epNum
                    this.posterUrl = poster
                })
            }
        }

        if (episodes.isEmpty() && url.contains("-episode-", ignoreCase = true)) {
            episodes.add(newEpisode(url) {
                this.name = title
                this.episode = Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                this.posterUrl = poster
            })
        }

        episodes.sortBy { it.episode }

        val seasonDataList = mutableListOf<SeasonData>()
        if (episodes.size > 10) {
            val chunks = episodes.chunked(10)
            chunks.forEachIndexed { index, chunk ->
                val seasonNum = index + 1
                val firstEp = chunk.first().episode ?: (index * 10 + 1)
                val lastEp = chunk.last().episode ?: ((index + 1) * 10)
                val sName = "Episode $firstEp - $lastEp"
                seasonDataList.add(SeasonData(seasonNum, sName))
                chunk.forEach { ep ->
                    ep.season = seasonNum
                }
            }
        }

        if (episodes.isNotEmpty()) {
            return newAnimeLoadResponse(title, animeUrl, TvType.Anime) {
                this.engName = title
                this.posterUrl = poster
                this.posterHeaders = defaultHeaders
                if (seasonDataList.isNotEmpty()) {
                    this.seasonNames = seasonDataList
                }
                addEpisodes(DubStatus.Subbed, episodes)
                this.plot = synopsis
                this.tags = tags
            }
        }

        return newMovieLoadResponse(title, animeUrl, TvType.Anime, animeUrl) {
            this.posterUrl = poster
            this.posterHeaders = defaultHeaders
            this.plot = synopsis
            this.tags = tags
        }
    }

    private suspend fun extractDailymotion(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val metaText = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to "https://geo.dailymotion.com/"
                )
            ).text

            val m3u8Url = Regex(""""type":\s*"application/x-mpegURL",\s*"url":\s*"([^"]+)"""").find(metaText)?.groupValues?.getOrNull(1)
                ?: Regex(""""url":\s*"([^"]+\.m3u8[^"]*)"""").find(metaText)?.groupValues?.getOrNull(1)
                ?: return

            val links = M3u8Helper.generateM3u8(
                source = "Dailymotion",
                streamUrl = m3u8Url,
                referer = "https://www.dailymotion.com/",
                headers = mapOf("Referer" to "https://www.dailymotion.com/")
            )
            if (links.isNotEmpty()) {
                links.forEach { callback(it) }
            } else {
                callback(
                    newExtractorLink("Dailymotion", "Dailymotion", m3u8Url) {
                        this.referer = "https://www.dailymotion.com/"
                        this.type = ExtractorLinkType.M3U8
                    }
                )
            }
        } catch (_: Exception) { }
    }

    private suspend fun extractDTube(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // DTube accepts UUID video IDs and exposes an HLS master manifest.
            val streamUrl = "https://nas1.d.tube/videos/$videoId/master.m3u8"
            val headers = mapOf("Referer" to "https://play.d.tube/")

            val response = app.get(
                streamUrl,
                headers = headers,
            )

            if (!response.text.trimStart().startsWith("#EXTM3U")) return

            val links = M3u8Helper.generateM3u8(
                source = "DTube",
                streamUrl = streamUrl,
                referer = "https://play.d.tube/",
                headers = headers
            )

            if (links.isNotEmpty()) {
                links.forEach { callback(it) }
            } else {
                callback(newExtractorLink("DTube", "DTube", streamUrl) {
                    this.referer = "https://play.d.tube/"
                    this.type = ExtractorLinkType.M3U8
                    this.headers = headers
                })
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun extractVidhide(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to "$mainUrl/"
                )
            ).text

            val packed = Regex(
                """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)\s*\{[\s\S]*?\}\s*\(\s*'((?:[^'\\]|\\.)*+)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']+)'\s*\.split\s*\(\s*'\|'\s*\)\s*\)""",
                RegexOption.IGNORE_CASE
            ).find(html) ?: return

            val encoded = packed.groupValues[1]
            val radix = packed.groupValues[2].toIntOrNull() ?: return
            val count = packed.groupValues[3].toIntOrNull() ?: return
            val dictionary = packed.groupValues[4].split("|")
            var decoded = encoded

            for (i in (count - 1) downTo 0) {
                val word = dictionary.getOrNull(i)
                if (!word.isNullOrEmpty()) {
                    decoded = decoded.replace(
                        Regex("\\b" + Regex.escape(i.toString(radix)) + "\\b"),
                        word
                    )
                }
            }

            val m3u8Url = Regex(
                """https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"""
            ).find(decoded)?.value ?: return

            val streamUrl = m3u8Url
                .replace("&amp;", "&")
                .replace("\\/", "/")

            val links = try {
                M3u8Helper.generateM3u8(
                    source = "VidHide",
                    streamUrl = streamUrl,
                    referer = url,
                    headers = mapOf("Referer" to url)
                )
            } catch (_: Exception) {
                emptyList()
            }

            if (links.isNotEmpty()) {
                links.forEach { callback(it) }
            } else {
                callback(newExtractorLink("VidHide", "VidHide", streamUrl) {
                    this.referer = url
                    this.type = ExtractorLinkType.M3U8
                })
            }
        } catch (_: Exception) {
        }
    }


    private suspend fun extractOdysee(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to defaultHeaders["User-Agent"].orEmpty(),
                "Referer" to "https://odysee.com/"
            )

            val html = app.get(url, headers = headers).text
            val streamUrl = Regex(
                "\"contentUrl\"\\s*:\\s*\"([^\"]+)\""
            ).find(html)?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")
                ?: return

            if (!streamUrl.startsWith("https://player.odycdn.com/")) return

            callback(newExtractorLink("Odysee", "Odysee", streamUrl) {
                this.referer = "https://odysee.com/"
                this.type = ExtractorLinkType.VIDEO
                this.headers = headers
            })
        } catch (_: Exception) {
            // Kegagalan Odysee tidak menghentikan mirror lainnya.
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = defaultHeaders).document
        val seenDm = mutableSetOf<String>()
        val seenUrls = mutableSetOf<String>()

        suspend fun handleUrl(rawUrl: String) {
            val url = when {
                rawUrl.startsWith("//") -> "https:$rawUrl"
                rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
                else -> return
            }

            val dmId = Regex(
                """(?:dailymotion\.com/(?:video/|player/[^?]+\.html\?video=)|dai\.ly/|geo\.dailymotion\.com/player/[^?]+\.html\?video=)([a-zA-Z0-9]+)"""
            ).find(url)?.groupValues?.getOrNull(1)

            if (dmId != null) {
                if (seenDm.add(dmId)) extractDailymotion(dmId, callback)
                return
            }

            if (url.contains("play.d.tube") || url.contains("d.tube/watch/")) {
                val dtubeId = Regex("""[?&]v=([0-9a-fA-F-]{36})""")
                    .find(url)?.groupValues?.getOrNull(1)
                    ?: Regex("""d\.tube/watch/([0-9a-fA-F-]{36})""")
                        .find(url)?.groupValues?.getOrNull(1)

                if (dtubeId != null) extractDTube(dtubeId, callback)
                return
            }

            if (!seenUrls.add(url)) return

            if (url.contains("odysee.com", ignoreCase = true)) {
                extractOdysee(url, callback)
            } else if (url.contains("morencius.com") || url.contains("minochinos.com")) {
                extractVidhide(url, callback)
            } else {
                loadExtractor(url, data, subtitleCallback, callback)
            }
        }

        doc.select("#pembed iframe[src], #embed_holder iframe[src], .player-embed iframe[src]")
            .forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.isNotBlank()) handleUrl(src)
            }

        doc.select("select.mirror option").forEach { option ->
            val encoded = option.attr("value").trim()
            if (encoded.isBlank()) return@forEach

            val decoded = try {
                if (encoded.startsWith("http://") || encoded.startsWith("https://") ||
                    encoded.startsWith("//")) {
                    encoded
                } else {
                    String(
                        android.util.Base64.decode(encoded, android.util.Base64.DEFAULT),
                        Charsets.UTF_8
                    ).trim()
                }
            } catch (_: Exception) {
                return@forEach
            }

            val iframeSrc = Regex(
                """<iframe\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(decoded)?.groupValues?.getOrNull(1)

            val directUrl = iframeSrc
                ?: Regex("""https?://[^\s"'<>]+""").find(decoded)?.value

            if (!directUrl.isNullOrBlank()) handleUrl(directUrl)
        }

        return true
    }
}
