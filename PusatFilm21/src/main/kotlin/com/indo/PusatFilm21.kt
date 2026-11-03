package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.nicehttp.JsonAsString
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
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



    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

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
        val pageText = app.get(data, headers = headers).text

        val unescaped = pageText.replace("\\\"", "\"").replace("\\/", "/")

        val activeShare = Regex("""["']embedUrl["']:\s*["']https?://kotakajaib\.me/(?:embed|file)/([a-zA-Z0-9_-]+)""").find(unescaped)?.groupValues?.getOrNull(1)

        val shares = if (!activeShare.isNullOrBlank()) {
            listOf(activeShare)
        } else {
            Regex("""kotakajaib\.me/(?:embed|file)/([a-zA-Z0-9_-]+)""").findAll(unescaped)
                .map { it.groupValues[1] }
                .distinct()
                .take(2)
                .toList()
        }

        for (share in shares) {
            extractKotakAjaib(share, data, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun extractKotakAjaib(
        share: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val embedUrl = "https://kotakajaib.me/embed/$share"
            val embedDoc = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Referer" to refererUrl
                )
            ).document

            val kp = embedDoc.selectFirst("#kp") ?: return
            val token = kp.attr("data-token").ifBlank { null } ?: return
            val providers = kp.select("button[data-p]").map { it.attr("data-p") }.filter { it.isNotBlank() }
                .ifEmpty { listOf("hydrax", "gdriveplayer") }

            for (provider in providers) {
                try {
                    val encodedToken = URLEncoder.encode(token, "UTF-8")
                    val sourceUrl = "https://kotakajaib.me/embed/$share/source/$provider?t=$encodedToken"
                    val res = app.get(
                        sourceUrl,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                            "Referer" to embedUrl,
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    ).text

                    val resJson = JSONObject(res)
                    var targetUrl = resJson.optString("u").trim()
                    if (targetUrl.isBlank()) continue
                    if (targetUrl.startsWith("//")) {
                        targetUrl = "https:$targetUrl"
                    }

                    if (targetUrl.contains("playhydrax.com") || targetUrl.contains("abyssplayer.com")) {
                        extractAbyss(targetUrl, embedUrl, subtitleCallback, callback)
                    } else {
                        loadExtractor(targetUrl, embedUrl, subtitleCallback, callback)
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun extractAbyss(
        embedUrl: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val areq = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Referer" to refererUrl
                )
            ).text

            val enc = Regex("""const\s+datas\s*=\s*"([^"]*)"""").find(areq)?.groupValues?.getOrNull(1) ?: return false

            extractAbyssSubtitles(enc, subtitleCallback)

            val jsonPayload = JSONObject().put("text", enc).toString()
            val decRes = app.post(
                "https://enc-dec.app/api/dec-abyss",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Content-Type" to "application/json",
                    "Origin" to "https://playhydrax.com",
                    "Referer" to "https://playhydrax.com/"
                ),
                json = JsonAsString(jsonPayload)
            ).text

            val decJson = JSONObject(decRes)
            val result = decJson.optJSONObject("result") ?: return false
            val sources = result.optJSONArray("sources") ?: return false

            for (i in 0 until sources.length()) {
                val srcObj = sources.optJSONObject(i) ?: continue
                if (!srcObj.optBoolean("status", true)) continue
                val srcUrl = srcObj.optString("url")
                if (srcUrl.isBlank() || !srcUrl.startsWith("http")) continue
                val type = srcObj.optString("type")
                val q = when {
                    type.contains("1080") -> Qualities.P1080.value
                    type.contains("720") -> Qualities.P720.value
                    type.contains("480") -> Qualities.P480.value
                    type.contains("360") -> Qualities.P360.value
                    type.equals("Origin", true) -> Qualities.P1080.value
                    else -> Qualities.Unknown.value
                }
                callback(
                    newExtractorLink(
                        "HydraX",
                        "HydraX $type",
                        srcUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = q
                        this.referer = "https://abyssplayer.com/"
                        this.headers = mapOf("Referer" to "https://abyssplayer.com/")
                    }
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractAbyssSubtitles(enc: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val rawBytes = android.util.Base64.decode(enc, android.util.Base64.DEFAULT)
            val rawJsonStr = String(rawBytes, Charsets.ISO_8859_1)
            val json = JSONObject(rawJsonStr)
            val md5Id = json.optString("md5_id")
            if (md5Id.isBlank()) return

            val subs = json.optJSONObject("config")?.optJSONArray("subtitles") ?: return
            for (i in 0 until subs.length()) {
                val subObj = subs.optJSONObject(i) ?: continue
                val subSlug = subObj.optString("slug")
                if (subSlug.isBlank()) continue
                val subType = subObj.optString("type", "srt").ifBlank { "srt" }
                val rawLang = subObj.optString("lang", "id").ifBlank { "id" }
                val langLabel = when (rawLang.lowercase()) {
                    "id", "ind", "indonesian", "indonesia" -> "Indonesian"
                    "en", "eng", "english" -> "English"
                    "ms", "may", "malay" -> "Malay"
                    else -> rawLang
                }
                val subUrl = "https://cdn.iamcdn.net/subtitle/$md5Id/$subSlug.$subType"
                subtitleCallback(newSubtitleFile(langLabel, subUrl))
            }
        } catch (_: Exception) {
        }
    }
}
