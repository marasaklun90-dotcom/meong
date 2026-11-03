package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

class PusatFilm21 : MainAPI() {
    override var mainUrl = "https://new1.pusatfilm21info.com"
    override var name = "PusatFilm21"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)


    private val homeCategories = listOf(
        "Sedang Trending",
        "Netflix Originals",
        "Film Indonesia",
        "Drama Korea",
        "Film Baru Ditambahkan",
        "Serial TV Baru Ditambahkan"
    )

    override val mainPage = mainPageOf(
        *homeCategories.map { mainUrl to it }.toTypedArray()
    )

    private fun categoryItems(
        doc: org.jsoup.nodes.Document,
        category: String
    ): List<SearchResponse> {
        val heading = doc.select("h2").firstOrNull {
            it.text().trim() == category
        } ?: return emptyList()

        var container: org.jsoup.nodes.Element = heading
        var links = emptyList<org.jsoup.nodes.Element>()

        for (level in 1..4) {
            val parent = container.parent() ?: break
            container = parent

            val found = container.select(
                "a[href^='/movie/'], a[href^='/series/']"
            ).filter { it.selectFirst("img") != null }

            if (found.isNotEmpty()) {
                links = found
                break
            }
        }

        val seen = mutableSetOf<String>()

        return links.mapNotNull { a ->
            val href = a.attr("href")
            if (!seen.add(href)) return@mapNotNull null

            val img = a.selectFirst("img") ?: return@mapNotNull null
            val title = img.attr("alt").trim()
                .ifBlank { a.text().trim() }

            if (title.isBlank()) return@mapNotNull null

            val poster = img.attr("src")
                .ifBlank { img.attr("data-src") }

            val type = if (href.startsWith("/series/")) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }

            newMovieSearchResponse(title, "$mainUrl$href", type) {
                this.posterUrl = poster.ifBlank { null }
            }
        }
    }

    private fun parseMovies(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val seen = mutableSetOf<String>()

        return doc.select("a[href^='/movie/'], a[href^='/tv/'], a[href^='/series/']")
            .mapNotNull { a ->
                val href = a.attr("href")
                if (!seen.add(href)) return@mapNotNull null

                val img = a.selectFirst("img") ?: return@mapNotNull null
                val title = img.attr("alt").trim()
                    .ifBlank { a.text().trim() }

                if (title.isBlank()) return@mapNotNull null

                val poster = img.attr("src").ifBlank {
                    img.attr("data-src")
                }

                val type = if (href.startsWith("/series/")) {
                    TvType.TvSeries
                } else {
                    TvType.Movie
                }

                newMovieSearchResponse(title, "$mainUrl$href", type) {
                    this.posterUrl = poster.ifBlank { null }
                }
            }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(mainUrl).document
        val items = categoryItems(doc, request.name)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/search?q=$encoded").document
        return parseMovies(doc)
    }


    private fun parseSeriesEpisodes(
        doc: org.jsoup.nodes.Document,
        seriesUrl: String
    ): List<Episode> {
        val slug = seriesUrl.substringAfter("/series/").substringBefore("/")
        val episodes = mutableListOf<Episode>()

        for (script in doc.select("script")) {
            val raw = script.data()
            if (!raw.contains("self.__next_f.push")) continue

            val chunks = Regex("""self\.__next_f\.push\((\[.*?\])\)""")
                .findAll(raw)

            for (chunk in chunks) {
                try {
                    val flight = JSONArray(chunk.groupValues[1])
                    val content = flight.optString(1)
                    val key = "\"seasons\":"
                    val start = content.indexOf(key)
                    if (start < 0) continue

                    val arrayStart = start + key.length
                    if (content.getOrNull(arrayStart) != '[') continue

                    var depth = 0
                    var inString = false
                    var escaped = false
                    var end = -1

                    for (i in arrayStart until content.length) {
                        val c = content[i]
                        if (escaped) {
                            escaped = false
                            continue
                        }
                        if (c == '\\' && inString) {
                            escaped = true
                            continue
                        }
                        if (c == '"') {
                            inString = !inString
                            continue
                        }
                        if (!inString) {
                            if (c == '[') depth++
                            if (c == ']') {
                                depth--
                                if (depth == 0) {
                                    end = i + 1
                                    break
                                }
                            }
                        }
                    }

                    if (end < 0) continue

                    val seasons = JSONArray(content.substring(arrayStart, end))

                    for (i in 0 until seasons.length()) {
                        val season = seasons.optJSONObject(i) ?: continue
                        val seasonNum = season.optInt("number", 0)
                        if (seasonNum <= 0) continue

                        val eps = season.optJSONArray("episodes") ?: continue

                        for (j in 0 until eps.length()) {
                            val ep = eps.optJSONObject(j) ?: continue
                            val epNum = ep.optInt("number", 0)
                            if (epNum <= 0) continue

                            val epName = ep.optString("name")
                                .ifBlank { "Episode $epNum" }

                            val epUrl =
                                "$mainUrl/series/$slug/season/$seasonNum/episode/$epNum"

                            episodes.add(newEpisode(epUrl) {
                                this.name = epName
                                this.season = seasonNum
                                this.episode = epNum
                            })
                        }
                    }

                    if (episodes.isNotEmpty()) {
                        return episodes.distinctBy { it.data }
                    }
                } catch (_: Exception) {
                }
            }
        }

        return episodes
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "PusatFilm21"

        val title = rawTitle
            .replace(Regex("^Nonton\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+Sub Indo.*$", RegexOption.IGNORE_CASE), "")
            .trim()

        val poster = doc.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.ifBlank { null }
            ?: doc.selectFirst("img[alt]")?.attr("src")

        val plot = doc.selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.ifBlank { null }

        val year = Regex("""(?:19|20)\d{2}""")
            .find(url.substringAfterLast("/"))
            ?.value?.toIntOrNull()

        if (url.contains("/series/")) {
            val episodes = parseSeriesEpisodes(doc, url)
            return newTvSeriesLoadResponse(
                title, url, TvType.TvSeries, episodes
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tahap berikutnya: integrasi sumber video yang kompatibel.
        return false
    }
}
