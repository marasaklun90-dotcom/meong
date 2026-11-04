package com.indo

import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getAndUnpack
import kotlinx.coroutines.withTimeoutOrNull
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
        
        "$mainUrl" to "Lagi Tren Sekarang",
        "$mainUrl" to "Film Indonesia",
        "$mainUrl" to "Film Terbaru",
        "$mainUrl" to "Serial TV Terbaru",
        "$mainUrl" to "Anime",
        "$mainUrl" to "Drama Korea",
        "$mainUrl" to "Drama China",
        "$mainUrl" to "Horor"
    )

    private fun toSearchResult(element: Element): SearchResponse? {
        val link = if (element.tagName() == "a") element
            else element.selectFirst(
                "a.content-card[href], a.sorot-kartu[href]"
            ) ?: return null

        val href = fixUrlNull(link.attr("href")) ?: return null

        if (!href.contains("/movie/") &&
            !href.contains("/series/") &&
            !href.contains("/drama-china/")) return null

        val posterImage = link.select("img").firstOrNull {
            it.attr("alt").isNotBlank()
        } ?: link.selectFirst("img")

        val title = posterImage?.attr("alt")?.trim()
            ?.replace(
                Regex("""\s*[—–-]\s*drama China sub Indo$""", RegexOption.IGNORE_CASE),
                ""
            )
            ?.takeIf { it.isNotBlank() }
            ?: link.selectFirst("h3")?.text()?.trim()
            ?: link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null

        val poster = posterImage?.let {
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
        val categoryPath = when (request.name.lowercase()) {
            "film indonesia" -> "/country/id"
            "film terbaru" -> "/movie"
            "serial tv terbaru" -> "/series"
            "anime" -> "/anime"
            "drama korea" -> "/country/kr"
            "drama china" -> "/drama-china"
            "horor" -> "/genre/horror"
            else -> null
        }

        if (categoryPath != null) {
            val query = if (
                categoryPath == "/movie" ||
                categoryPath == "/series"
            ) {
                "?sort=popularity.desc&page=$page"
            } else {
                "?page=$page"
            }

            val doc = app.get(
                "$mainUrl$categoryPath$query",
                headers = headers
            ).document

            val items = doc.select("a.content-card[href]")
                .mapNotNull { toSearchResult(it) }
                .distinctBy { it.url }

            return newHomePageResponse(
                request.name,
                items
            )
        }

        if (page > 1) {
            return newHomePageResponse(
                request.name,
                emptyList()
            )
        }

        val doc = app.get(mainUrl, headers = headers).document

        val section = doc.select("section.cv-row").firstOrNull {
            it.selectFirst(".content-row-header__title")
                ?.text()?.trim()
                ?.equals(request.name, ignoreCase = true) == true
        }

        val items = section?.select("a.content-card[href]")
            ?.mapNotNull { toSearchResult(it) }
            ?: emptyList()

        return newHomePageResponse(
            request.name,
            items.distinctBy { it.url }
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/search?q=$encoded", headers = headers).document

        return doc.select(
            "a.content-card[href*='/movie/'], a.content-card[href*='/series/'], a.content-card[href*='/drama-china/']"
        ).mapNotNull { toSearchResult(it) }
            .distinctBy { it.url }
    }


    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Kiblat21"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")


        // Drama China episodes
        if (url.contains("/drama-china/")) {
            val basePath = url.substringBefore("?")
                .trimEnd('/')
                .replace(Regex("/\\d+$"), "")

            val episodeRegex = Regex(
                "^" + Regex.escape(basePath) + "/(\\d+)/?$"
            )

            val episodes = doc.select("a[href*='/drama-china/']")
                .mapNotNull { element ->
                    val href = fixUrl(element.attr("href"))
                    val number = episodeRegex.matchEntire(href)
                        ?.groupValues?.get(1)?.toIntOrNull()
                        ?: return@mapNotNull null
                    number to href
                }
                .distinctBy { it.first }
                .sortedBy { it.first }
                .map { (number, href) ->
                    newEpisode(href) {
                        this.name = "Episode $number"
                        this.episode = number
                        this.season = 1
                    }
                }

            return newTvSeriesLoadResponse(
                title, url, TvType.TvSeries, episodes
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        if (url.contains("/series/")) {
            val episodeRegex = Regex(
                """/season/(\d+)/episode/(\d+)"""
            )

            val episodes = doc.select("a[href*='/season/'][href*='/episode/']")
                .mapNotNull { element ->
                    val href = fixUrl(element.attr("href"))
                    val match = episodeRegex.find(href) ?: return@mapNotNull null

                    val seasonNumber = match.groupValues[1].toIntOrNull()
                        ?: return@mapNotNull null
                    val episodeNumber = match.groupValues[2].toIntOrNull()
                        ?: return@mapNotNull null

                    Triple(seasonNumber, episodeNumber, href)
                }
                .distinctBy { it.first to it.second }
                .sortedWith(
                    compareBy<Triple<Int, Int, String>> { it.first }
                        .thenBy { it.second }
                )
                .map { (seasonNumber, episodeNumber, href) ->
                    newEpisode(href) {
                        this.name = "Episode $episodeNumber"
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

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
        val watchUrl = if (
            data.contains("/series/") &&
            data.contains("/episode/")
        ) {
            data.trimEnd('/')
        } else if (data.trimEnd('/').endsWith("/watch")) {
            data
        } else {
            "${data.trimEnd('/')}/watch"
        }
        val rawHtml = app.get(
            watchUrl,
            headers = mapOf("User-Agent" to "Mozilla/5.0")
        ).text

        val html = rawHtml
            .replace("\\\\\"", "\"")
            .replace("\\\"", "\"")
            .replace("\\/", "/")

        val embeds = Regex(
            """"server"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+)"\s*,\s*"blocked"\s*:\s*(true|false)"""
        ).findAll(html)
            .filter { it.groupValues[3] == "false" }
            .map { it.groupValues[1] to it.groupValues[2] }
            .distinctBy { it.second }
            .toList()

        println("KIBLAT21: ditemukan ${embeds.size} embed")
        embeds.forEach { (server, url) ->
            println("KIBLAT21 EMBED: $server -> $url")
        }

        val total = java.util.concurrent.atomic.AtomicInteger(0)
        embeds.amap { (server, embedUrl) ->
            val found = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
            val cb: (ExtractorLink) -> Unit = { found.add(it) }
            try {
                val isVidzee = embedUrl.contains("vidzee", ignoreCase = true)
                withTimeoutOrNull(if (isVidzee) 40000L else 25000L) {
                    val target = resolve2embed(embedUrl, watchUrl)

                    if (embedUrl.contains("2embed.cc", ignoreCase = true)) {
                        val tmdbId = Regex(
                            """(?:/embed/|[?&]tmdb=)([0-9]+)"""
                        ).find(embedUrl)?.groupValues?.get(1)

                        if (tmdbId != null) {
                            val mirrors = listOf(
                                "https://vidsrc.buzz/embed/movie/$tmdbId",
                                "https://videm.xyz/embed/movie/$tmdbId"
                            )

                            mirrors.amap { mirror ->
                                try {
                                    withTimeoutOrNull(20000L) {
                                        loadExtractor(
                                            mirror,
                                            embedUrl,
                                            subtitleCallback,
                                            cb
                                        )
                                    }
                                } catch (e: Exception) {
                                    println(
                                        "KIBLAT21 2Embed mirror gagal: ${e.message}"
                                    )
                                }
                            }
                        }
                    }

                    if (target.contains("abyssplayer.com")) {
                        Abyss().getUrl(target, watchUrl, subtitleCallback, cb)
                    } else {
                        loadExtractor(target, watchUrl, subtitleCallback, cb)
                    }
                    if (found.isEmpty() && !target.contains("vidzee", ignoreCase = true)) {
                        withTimeoutOrNull(7000L) { generic(server, target, watchUrl, cb) }
                    }
                    if (found.isEmpty() && !target.contains("vidzee", ignoreCase = true)) {
                        withTimeoutOrNull(8000L) { webview(server, embedUrl, watchUrl, cb) }
                    }
                }
            } catch (e: Exception) {
                println("KIBLAT21: $server gagal: ${e.message}")
            }
            if (found.isNotEmpty()) {
                found.toList().forEach { l ->
                    if (l.name.trim().matches(Regex("""SERVER\s*2""", RegexOption.IGNORE_CASE))) {
                        return@forEach
                    }
                    val label = when {
                        l.name.startsWith("Vidzee ", ignoreCase = true) -> l.name
                        l.name.startsWith(server) -> l.name
                        else -> "$server - ${l.name}"
                    }
                    val cleanLabel = label
                        .replace(
                            Regex("""^SERVER\s*\d+\s*[-–:]?\s*""", RegexOption.IGNORE_CASE),
                            ""
                        )
                        .replace(
                            Regex("""(?i)\b(\d{3,4}p)\s+\1\b"""),
                            "$1"
                        )
                        .trim()
                        .ifBlank { l.source.ifBlank { "Mirror" } }
                    if (cleanLabel.equals("SERVER 2", ignoreCase = true) ||
                        (server.equals("SERVER 2", ignoreCase = true) &&
                         l.name.equals("SERVER 2", ignoreCase = true))) {
                        return@forEach
                    }
                    callback(newExtractorLink(l.source, cleanLabel, l.url, l.type) {
                        this.referer = l.referer
                        this.quality = l.quality
                        this.headers = l.headers
                    })
                    total.incrementAndGet()
                }
            }
        }
        return total.get() > 0
    }

    private suspend fun resolve2embed(u: String, ref: String): String {
        if (!u.contains("2embed.cc")) return u
        val src = app.get(u, referer = ref).document
            .selectFirst("iframe#iframesrc")?.attr("data-src") ?: return u
        val id = Regex("""[?&]id=([A-Za-z0-9]+)""").find(src)?.groupValues?.get(1) ?: return u
        return "https://2vcdn.skin/e/$id"
    }

    private suspend fun emit(name: String, link: String, ref: String, cb: (ExtractorLink) -> Unit) {
        if (link.contains(".mp4")) {
            cb(newExtractorLink(name, name, link) {
                this.referer = ref
                this.quality = Qualities.Unknown.value
            })
        } else {
            cb(newExtractorLink(name, name, link, ExtractorLinkType.M3U8) {
                this.referer = ref
            })
        }
    }

    private suspend fun generic(name: String, url: String, ref: String, cb: (ExtractorLink) -> Unit) {
        val res = app.get(url, referer = ref, headers = mapOf("User-Agent" to "Mozilla/5.0")).text
        val body = try { getAndUnpack(res) } catch (e: Exception) { res }
        val re = Regex("""https?:[^"'\\\s]+(\.m3u8|\.mp4|/master\.txt)[^"'\\\s]*""")
        val link = re.find(body)?.value ?: re.find(res)?.value ?: return
        emit(name, link, url, cb)
    }

    private suspend fun webview(name: String, url: String, ref: String, cb: (ExtractorLink) -> Unit) {
        val re = Regex("""\.m3u8|\.mp4|master\.txt""")
        val r = app.get(url, referer = ref, interceptor = WebViewResolver(re)).url
        if (re.containsMatchIn(r)) emit(name, r, url, cb)
    }
}
