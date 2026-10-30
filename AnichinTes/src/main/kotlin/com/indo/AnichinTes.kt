package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class AnichinTes : MainAPI() {
    override var mainUrl = "https://anichin.com.co"
    override var name = "AnichinTes"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl).document

        val sections = document.select("div.bixbox").mapNotNull { box ->
            val heading = box.selectFirst(
                ".releases h1, .releases h2, .releases h3"
            )?.text()?.trim() ?: return@mapNotNull null

            val items = box.select("div.listupd article.bs").mapNotNull { item ->
                val a = item.selectFirst(".bsx > a")
                    ?: return@mapNotNull null

                val title = a.selectFirst("h2[itemprop=headline]")
                    ?.text()?.trim()?.ifBlank { null }
                    ?: a.attr("title").trim().ifBlank { null }
                    ?: return@mapNotNull null

                val poster = a.selectFirst("img")?.let {
                    it.attr("src").ifBlank { it.attr("data-src") }
                }

                val type = if (a.selectFirst(".typez.Movie") != null) {
                    TvType.AnimeMovie
                } else {
                    TvType.Anime
                }

                newAnimeSearchResponse(title, a.attr("href"), type) {
                    this.posterUrl = poster
                }
            }

            if (items.isEmpty()) null else HomePageList(heading, items)
        }

        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document

        return document.select("div.listupd article.bs").mapNotNull { item ->
            val a = item.selectFirst(".bsx > a")
                ?: return@mapNotNull null

            val title = a.selectFirst("h2[itemprop=headline]")
                ?.text()?.trim()?.ifBlank { null }
                ?: a.attr("title").trim().ifBlank { null }
                ?: return@mapNotNull null

            val poster = a.selectFirst("img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }

            val type = if (a.selectFirst(".typez.Movie") != null) {
                TvType.AnimeMovie
            } else {
                TvType.Anime
            }

            newAnimeSearchResponse(title, a.attr("href"), type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var pageUrl = url
        var document = app.get(pageUrl).document

        // Jika URL yang diterima adalah halaman episode,
        // cari tautan menuju halaman serial.
        if (document.selectFirst(".infox h1.entry-title") == null) {
            val seriesTitle = document.selectFirst(
                "h2[itemprop=partOfSeries]"
            )?.text()?.trim()
                ?: throw Exception("Judul serial tidak ditemukan")

            val seriesUrl = document.select("a[href*=/series/]")
                .firstOrNull {
                    it.text().trim().equals(seriesTitle, true)
                }?.attr("href")
                ?: throw Exception("Tautan halaman serial tidak ditemukan")

            pageUrl = seriesUrl
            document = app.get(pageUrl).document
        }

        val title = document.selectFirst(".infox h1.entry-title")
            ?.text()?.trim()
            ?: throw Exception("Judul anime tidak ditemukan")

        val poster = document.selectFirst(".thumbook .thumb img")?.let {
            it.attr("data-src")
                .ifBlank { it.attr("data-lazy-src") }
                .ifBlank { it.attr("src") }
                .takeIf { image -> image.startsWith("http") }
        }

        val description = document.selectFirst(".entry-content .mindesc")
            ?.text()?.trim()

        val genres = document.select(".info-content .genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val statusText = document.select(".info-content .spe span")
            .firstOrNull { it.text().contains("Status:", true) }
            ?.text()?.substringAfter(":")?.trim()

        val status = when {
            statusText?.contains("ongoing", true) == true -> ShowStatus.Ongoing
            statusText?.contains("completed", true) == true -> ShowStatus.Completed
            else -> null
        }

        val episodes = document.select(".eplister ul li").mapNotNull { item ->
            val a = item.selectFirst("a") ?: return@mapNotNull null
            val episodeTitle = item.selectFirst(".epl-title")
                ?.text()?.trim() ?: return@mapNotNull null
            val episodeNumber = item.selectFirst(".epl-num")
                ?.text()?.trim()?.toIntOrNull()

            newEpisode(a.attr("href")) {
                name = episodeTitle
                episode = episodeNumber
            }
        }

        return newAnimeLoadResponse(title, pageUrl, TvType.Anime) {
            posterUrl = poster
            plot = description
            tags = genres
            showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCastingLink: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val servers = document.select("select.mirror option")
            .mapNotNull { option ->
                option.attr("value").trim().takeIf {
                    it.startsWith("http")
                }
            }

        if (servers.isEmpty()) {
            throw Exception("Server video tidak ditemukan")
        }

        servers.forEach { serverUrl ->
            val serverPage = app.get(
                serverUrl,
                referer = data
            ).document

            val iframe = serverPage.selectFirst(
                "#embed_holder iframe, .player-embed iframe, iframe"
            )?.let {
                it.attr("data-litespeed-src")
                    .ifBlank { it.attr("src") }
                    .trim()
            }

            if (!iframe.isNullOrBlank() &&
                iframe != "about:blank" &&
                iframe.startsWith("http")
            ) {
                loadExtractor(
                    iframe,
                    serverUrl,
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }

}
