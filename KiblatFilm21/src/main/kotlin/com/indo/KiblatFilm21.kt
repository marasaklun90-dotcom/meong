package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONArray
import org.json.JSONObject
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
        val doc = app.get(mainUrl, headers = headers).document

        val categories = listOf(
            "Film Indonesia",
            "Film Terbaru",
            "Serial TV Terbaru",
            "Anime",
            "Drama Korea",
            "Drama China",
            "Horor"
        )

        val homePageLists = categories.mapNotNull { category ->
            val section = doc.select("section.cv-row").firstOrNull {
                it.selectFirst(".content-row-header__title")
                    ?.text()
                    ?.trim() == category
            } ?: return@mapNotNull null

            val items = section.select("a.content-card")
                .mapNotNull { toSearchResponse(it) }
                .distinctBy { it.url }

            if (items.isEmpty()) {
                null
            } else {
                HomePageList(category, items)
            }
        }

        return newHomePageResponse(homePageLists)
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
            val decoded = html
                .replace("\\\\\"", "\"")
                .replace("\\\"", "\"")
                .replace("\\u0026", "&")
                .replace("\\\\/", "/")
                .replace("\\/", "/")

            val pattern = Regex(
                """"embeds"\s*:\s*(\[[\s\S]*?\])"""
            )

            val raw = pattern.find(decoded)?.groupValues?.get(1)
                ?: return emptyList()

            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val server = item.optString("server").ifBlank { "Server ${i + 1}" }
                val url = item.optString("url")
                if (url.startsWith("http")) server to url else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    
private fun decryptAbyssPayload(enc: String): JSONObject? {
    return try {
        val raw = android.util.Base64.decode(enc, android.util.Base64.DEFAULT)
        val outer = JSONObject(String(raw, Charsets.ISO_8859_1))

        val userId = outer.optString("user_id")
        val slug = outer.optString("slug")
        val md5Id = outer.optString("md5_id")
        val media = outer.optString("media")

        if (userId.isBlank() || slug.isBlank() ||
            md5Id.isBlank() || media.isBlank()) return null

        fun md5(value: String): ByteArray =
            java.security.MessageDigest.getInstance("MD5")
                .digest(value.toByteArray(Charsets.UTF_8))

        val keyHex = md5("$userId:$slug:$md5Id")
            .joinToString("") { "%02x".format(it) }
        val key = keyHex.toByteArray(Charsets.UTF_8)
        val iv = key.copyOfRange(0, 16)

        val encrypted = ByteArray(media.length) {
            media[it].code.toByte()
        }

        val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(iv)
        )

        JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }
}

private fun createAbyssToken(path: String, size: String): String? {
    return try {
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(size.toByteArray(Charsets.UTF_8))
        val keyHex = md5.joinToString("") { "%02x".format(it) }
        val key = keyHex.toByteArray(Charsets.UTF_8)
        val iv = key.copyOfRange(0, 16)

        val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(iv)
        )

        val encrypted = cipher.doFinal(path.toByteArray(Charsets.UTF_8))
        val first = android.util.Base64.encodeToString(
            encrypted,
            android.util.Base64.NO_WRAP
        ).trimEnd('=')

        android.util.Base64.encodeToString(
            first.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        ).trimEnd('=')
    } catch (_: Exception) {
        null
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

    
private suspend fun extractAbyss(
    embedUrl: String,
    playerUrl: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    return try {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Referer" to playerUrl
        )

        val html = app.get(embedUrl, headers = headers).text
        val enc = Regex("""const\s+datas\s*=\s*"([^"]*)"""")
            .find(html)?.groupValues?.getOrNull(1) ?: return false

        extractAbyssSubtitles(enc, subtitleCallback)

        val outerBytes = android.util.Base64.decode(
            enc, android.util.Base64.DEFAULT
        )
        val outer = JSONObject(String(outerBytes, Charsets.ISO_8859_1))
        val md5Id = outer.optString("md5_id")
        val userId = outer.optString("user_id")

        val media = decryptAbyssPayload(enc) ?: return false
        val mp4 = media.optJSONObject("mp4") ?: return false
        val sources = mp4.optJSONArray("sources") ?: return false
        val domains = mp4.optJSONArray("domains") ?: return false

        var found = false

        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            if (!src.optBoolean("status", true)) continue

            val size = src.optString("size")
            val resId = src.optString("res_id")
            val sub = src.optString("sub")
            val file = src.optString("file")

            val url = if (file.startsWith("http")) {
                file
            } else {
                if (size.isBlank() || resId.isBlank()) continue

                var domain: String? = null
                for (j in 0 until domains.length()) {
                    val candidate = domains.optString(j)
                    if (candidate.contains(sub)) {
                        domain = candidate
                        break
                    }
                }

                val host = domain ?: continue
                val path = "/mp4/$md5Id/$resId/$size/$userId"
                val token = createAbyssToken(path, size) ?: continue
                val cleanHost = host.removePrefix("//").trimEnd('/')
                "https://$cleanHost/storage/$size/$token"
            }

            val label = src.optString("label")
                .ifBlank { src.optString("type") }
                .ifBlank { size }

            val quality = when {
                label.contains("1080") -> Qualities.P1080.value
                label.contains("720") -> Qualities.P720.value
                label.contains("480") -> Qualities.P480.value
                label.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            callback(
                newExtractorLink(
                    "Abyss",
                    "Abyss $label",
                    url,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.referer = "https://abyssplayer.com/"
                    this.headers = mapOf(
                        "Referer" to "https://abyssplayer.com/",
                        "User-Agent" to headers["User-Agent"].orEmpty()
                    )
                }
            )
            found = true
        }

        found
    } catch (_: Exception) {
        false
    }
}

override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val reqHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "$mainUrl/"
        )

        val html = try {
            app.get(data, headers = reqHeaders).text
        } catch (e: Exception) {
            callback(
                newExtractorLink(
                    name, "DEBUG get gagal: ${e.javaClass.simpleName} ${e.message?.take(60)}",
                    "https://debug.invalid/", ExtractorLinkType.VIDEO
                ) {}
            )
            return true
        }

        val embeds = extractEmbeds(html)
        if (embeds.isEmpty()) {
            callback(
                newExtractorLink(
                    name, "DEBUG embeds=0 html=${html.length} data=${data.takeLast(25)}",
                    "https://debug.invalid/", ExtractorLinkType.VIDEO
                ) {}
            )
            return true
        }

        var found = false

        for ((server, rawUrl) in embeds) {
            val playerUrl = absoluteUrl(rawUrl)

            try {
                val match = Regex("""/iframe3/([^/]+)/([^/?&]+)""")
                    .find(playerUrl)

                if (match != null) {
                    val host = match.groupValues[1]
                    val id = match.groupValues[2]

                    val postRes = app.post(
                        "https://videonode.de/api.php",
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "Referer" to playerUrl,
                            "Origin" to "https://videonode.de"
                        ),
                        data = mapOf("host" to host, "id" to id)
                    ).text

                    val embedUrl = JSONObject(postRes).optString("embedUrl")

                    if (embedUrl.isNotBlank()) {
                        if (
                            host.equals("hydrax", true) ||
                            embedUrl.contains("abyssplayer", true) ||
                            embedUrl.contains("playhydrax", true)
                        ) {
                            if (extractAbyss(
                                embedUrl, playerUrl,
                                subtitleCallback, callback
                            )) found = true
                        } else {
                            loadExtractor(
                                embedUrl, playerUrl,
                                subtitleCallback, callback
                            )
                            found = true
                        }
                    }
                } else {
                    if (playerUrl.contains("abyssplayer", true) ||
                        playerUrl.contains("playhydrax", true)
                    ) {
                        if (extractAbyss(playerUrl, data, subtitleCallback, callback)) found = true
                    } else {
                        var got = false
                        loadExtractor(playerUrl, data, subtitleCallback) { link ->
                            got = true
                            callback(link)
                        }
                        if (!got) {
                            val resp = app.get(
                                playerUrl,
                                referer = "$mainUrl/",
                                interceptor = com.lagradost.cloudstream3.network.WebViewResolver(
                                    Regex("""\.(m3u8|mp4)""")
                                )
                            )
                            val u = resp.url
                            if (u.contains(".m3u8") || u.contains(".mp4")) {
                                callback(
                                    newExtractorLink(
                                        server, server, u,
                                        if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) { this.referer = playerUrl }
                                )
                                got = true
                            }
                        }
                        if (got) found = true
                    }
                }
            } catch (e: Exception) {
                callback(
                    newExtractorLink(
                        name, "DEBUG $server: ${e.javaClass.simpleName} ${e.message?.take(50)}",
                        "https://debug.invalid/", ExtractorLinkType.VIDEO
                    ) {}
                )
                found = true
            }
        }

        if (!found) {
            callback(
                newExtractorLink(
                    name, "DEBUG semua server kosong (embeds=${embeds.size})",
                    "https://debug.invalid/", ExtractorLinkType.VIDEO
                ) {}
            )
            found = true
        }
        return found
    }
}
