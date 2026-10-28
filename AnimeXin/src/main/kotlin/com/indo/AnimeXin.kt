package com.indo

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLEncoder

class AnimeXin : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "AnimeXin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val headers = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Popular Today",
        "$mainUrl/" to "Latest Release"
    )

    private fun parseItems(
        doc: Document,
        selector: String = "article.bs"
    ): List<SearchResponse> {
        return doc.select(selector).mapNotNull { item ->
            val link = item.selectFirst(".bsx > a[href], a.tip[href], a[href]")
                ?.attr("href")?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val image = item.selectFirst(".limit img, .bsx img, img")
            val poster = (
                image?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                    ?: image?.attr("src")?.takeIf { it.isNotBlank() }
                )?.let { fixUrl(it) }

            val rawTitle = item.selectFirst(".tt")?.ownText()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: item.selectFirst(".eggtitle")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: item.selectFirst(".tt h2")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: image?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val title = rawTitle
                .replace(Regex("""\s+Episode\s+\d+.*$""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { rawTitle }

            val typeText = item.selectFirst(".typez")?.text().orEmpty()
            val tvType = if (typeText.contains("Movie", true)) {
                TvType.AnimeMovie
            } else {
                TvType.Anime
            }

            val episodeText = item.selectFirst(".epx, .eggepisode, .epl-num")
                ?.text().orEmpty()
            val episodeNumber = Regex("""\d+""")
                .find(episodeText)?.value?.toIntOrNull()

            newAnimeSearchResponse(title, fixUrl(link), tvType) {
                this.posterUrl = poster
                this.posterHeaders = headers
                addSub(episodeNumber)
            }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val pageUrl = if (page > 1) "$mainUrl/page/$page/" else "$mainUrl/"
        val doc = app.get(pageUrl, headers = headers).document

        val items = if (request.name == "Popular Today") {
            parseItems(doc, ".listupd.popularslider article.bs")
        } else {
            parseItems(doc, ".listupd.normal article.bs")
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encodedQuery", headers = headers).document
        return parseItems(doc, ".listupd article.bs")
    }

    override suspend fun load(url: String): LoadResponse {
        val requestedUrl = fixUrl(url)
        val requestedDoc = app.get(requestedUrl, headers = headers).document

        val breadcrumbSeries = requestedDoc
            .selectFirst(".ts-breadcrumb [itemprop=itemListElement]:nth-child(2) a")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }

        val seriesUrl = breadcrumbSeries
            ?.takeIf { it.trimEnd('/') != requestedUrl.trimEnd('/') }
            ?: requestedUrl

        val doc = if (seriesUrl != requestedUrl) {
            app.get(seriesUrl, headers = headers).document
        } else {
            requestedDoc
        }

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".info h1, .thumbook h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Judul AnimeXin tidak ditemukan")

        val poster = (
            doc.selectFirst(".thumb img, .thumbook img, .bigcover img")
                ?.let { img ->
                    img.attr("data-src").ifBlank {
                        img.attr("src")
                    }
                }?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?.takeIf { it.isNotBlank() }
            )?.let { fixUrl(it) }

        val synopsis = doc.select(".desc p, .entry-content p, .synp p")
            .text().trim().ifBlank { null }

        val tags = doc.select(".genxed a, a[href*=/genres/]")
            .mapNotNull { it.text().trim().ifBlank { null } }
            .distinct()

        val statusText = doc.select(".spe span")
            .firstOrNull { it.text().contains("Status", true) }
            ?.text().orEmpty()

        val status = when {
            statusText.contains("Ongoing", true) -> ShowStatus.Ongoing
            statusText.contains("Completed", true) -> ShowStatus.Completed
            else -> null
        }

        val releasedText = doc.select(".spe span")
            .firstOrNull {
                it.text().contains("Released", true) ||
                    it.text().contains("Tanggal rilis", true)
            }?.text().orEmpty()

        val year = Regex("""\b(19\d{2}|20\d{2})\b""")
            .find(releasedText)?.value?.toIntOrNull()

        val typeText = doc.select(".spe span")
            .firstOrNull { it.text().contains("Type", true) }
            ?.text().orEmpty()

        val tvType = if (typeText.contains("Movie", true)) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        val episodeElements = doc.select(
            ".eplister ul li a, .episodelist ul li a"
        )

        val episodes = episodeElements.mapNotNull { element ->
            val episodeUrl = element.attr("href")
                .takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
                ?: return@mapNotNull null

            val episodeTitle = element.selectFirst(".epl-title, .playinfo h3")
                ?.text()?.trim()?.ifBlank { null }

            val episodeText = element.selectFirst(".epl-num")
                ?.text()?.trim().orEmpty()

            val episodeNumber = Regex("""\d+""")
                .find(episodeText.ifBlank { episodeTitle.orEmpty() })
                ?.value?.toIntOrNull()

            newEpisode(episodeUrl) {
                this.name = episodeTitle
                    ?: episodeNumber?.let { "Episode $it" }
                    ?: "Episode"
                this.episode = episodeNumber
            }
        }.distinctBy { it.data }.sortedWith(
            compareBy(nullsLast()) { it.episode }
        )

        val finalEpisodes = if (episodes.isNotEmpty()) {
            episodes
        } else if (seriesUrl != requestedUrl) {
            listOf(
                newEpisode(requestedUrl) {
                    this.name = requestedDoc.selectFirst("h1.entry-title")
                        ?.text()?.trim() ?: "Episode"
                    this.episode = Regex("""Episode\s*(\d+)""")
                        .find(this.name.orEmpty())
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
            )
        } else {
            emptyList()
        }

        return newAnimeLoadResponse(title, seriesUrl, tvType) {
            engName = title
            posterUrl = poster
            posterHeaders = headers
            addEpisodes(DubStatus.Subbed, finalEpisodes)
            showStatus = status
            this.year = year
            plot = synopsis
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = fixUrl(data)
        val doc = app.get(episodeUrl, headers = headers).document

        // Simpan URL beserta nama server dan bahasa dari website.
        val links = linkedMapOf<String, String>()

        doc.select("select.mirror option").forEach { option ->
            val label = option.text().trim()
            val value = option.attr("value").trim()

            if (value.isBlank() ||
                label.isBlank() ||
                label.contains("comment", ignoreCase = true) ||
                value == "post" ||
                value == "all_comment"
            ) return@forEach

            try {
                val iframeUrl = if (
                    value.startsWith("http://") ||
                    value.startsWith("https://") ||
                    value.startsWith("//")
                ) {
                    value
                } else {
                    val decoded = String(
                        Base64.decode(value, Base64.DEFAULT),
                        Charsets.UTF_8
                    )

                    Regex(
                        """<iframe\b[^>]*\bsrc=["']([^"']+)["']""",
                        RegexOption.IGNORE_CASE
                    ).find(decoded)?.groupValues?.getOrNull(1)
                        ?: Regex("""https?://[^\s"'<>]+""")
                            .find(decoded)?.value
                }

                iframeUrl?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { rawUrl ->
                                val normalizedUrl = if (rawUrl.startsWith("//")) {
                                    "https:$rawUrl"
                                } else {
                                    fixUrl(rawUrl)
                                }
                                links[normalizedUrl] = label
                            }
            } catch (_: Exception) {
                // Abaikan opsi yang gagal didekode.
            }
        }

        // Tambahkan iframe aktif jika tidak ditemukan di daftar server.
        doc.select(
            "#pembed iframe[src], .player-embed iframe[src], .mobius iframe[src]"
        ).forEach { iframe ->
            iframe.attr("src").trim()
                .takeIf { it.isNotBlank() }
                ?.let { rawUrl ->
                    val normalizedUrl = if (rawUrl.startsWith("//")) {
                        "https:$rawUrl"
                    } else {
                        fixUrl(rawUrl)
                    }
                    links.putIfAbsent(normalizedUrl, "Default Player")
                }
        }

        links.forEach { (url, serverLabel) ->
            try {
                loadExtractor(url, episodeUrl, subtitleCallback) { extracted ->
                    val labeled = ExtractorLink(
                        extracted.source,
                        "$serverLabel - ${extracted.name}",
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
            } catch (_: Exception) {
                // Kegagalan satu server tidak menghentikan server lainnya.
            }
        }

        return true
    }
}
