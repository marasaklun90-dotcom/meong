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
}
