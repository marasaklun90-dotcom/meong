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

    private fun sanitizeAbyssPayload(enc: String): String? {
        return try {
            val rawBytes = android.util.Base64.decode(enc, android.util.Base64.DEFAULT)
            val rawJsonStr = String(rawBytes, Charsets.ISO_8859_1)
            val json = JSONObject(rawJsonStr)
            val userId = json.optString("user_id")
            val slug = json.optString("slug")
            val md5Id = json.optString("md5_id")
            val mediaStr = json.optString("media")
            if (userId.isBlank() || slug.isBlank() || md5Id.isBlank() || mediaStr.isBlank()) return null

            val keyStr = "$userId:$slug:$md5Id"
            val md5 = java.security.MessageDigest.getInstance("MD5").digest(keyStr.toByteArray(Charsets.UTF_8))
            val md5Hex = md5.joinToString("") { "%02x".format(it) }
            val keyBytes = md5Hex.toByteArray(Charsets.UTF_8)
            val ivBytes = keyBytes.copyOfRange(0, 16)

            val mediaBytes = ByteArray(mediaStr.length) { mediaStr[it].code.toByte() }
            val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
            val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
            val ivSpec = javax.crypto.spec.IvParameterSpec(ivBytes)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(mediaBytes)
            val decryptedJsonStr = String(decryptedBytes, Charsets.UTF_8)

            val mediaObj = JSONObject(decryptedJsonStr)
            val mp4 = mediaObj.optJSONObject("mp4") ?: return null
            val sources = mp4.optJSONArray("sources") ?: return null
            val newSources = org.json.JSONArray()
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                if (src.has("sub") && src.optString("sub").isNotBlank()) {
                    newSources.put(src)
                }
            }
            mp4.put("sources", newSources)

            val newMediaStr = mediaObj.toString()
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val reEncryptedBytes = cipher.doFinal(newMediaStr.toByteArray(Charsets.UTF_8))
            val reEncryptedBin = StringBuilder(reEncryptedBytes.size)
            for (b in reEncryptedBytes) {
                reEncryptedBin.append((b.toInt() and 0xFF).toChar())
            }
            json.put("media", reEncryptedBin.toString())
            val finalJsonBytes = json.toString().toByteArray(Charsets.ISO_8859_1)
            android.util.Base64.encodeToString(finalJsonBytes, android.util.Base64.NO_WRAP)
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

    private suspend fun extractHydrax(
        embedUrl: String,
        playerUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val areq = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Referer" to playerUrl
                )
            ).text

            val enc = Regex("""const\s+datas\s*=\s*"([^"]*)"""").find(areq)?.groupValues?.getOrNull(1) ?: return false

            extractAbyssSubtitles(enc, subtitleCallback)

            val jsonPayload = JSONObject().put("text", enc).toString()
            var decRes = app.post(
                "https://enc-dec.app/api/dec-abyss",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Content-Type" to "application/json",
                    "Origin" to "https://playhydrax.com",
                    "Referer" to "https://playhydrax.com/"
                ),
                json = JsonAsString(jsonPayload)
            ).text

            var decJson = JSONObject(decRes)
            var result = decJson.optJSONObject("result")
            if (result == null || decJson.optInt("status") != 200) {
                val sanitizedEnc = sanitizeAbyssPayload(enc)
                if (sanitizedEnc != null) {
                    val sanitizedPayload = JSONObject().put("text", sanitizedEnc).toString()
                    decRes = app.post(
                        "https://enc-dec.app/api/dec-abyss",
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                            "Content-Type" to "application/json",
                            "Origin" to "https://playhydrax.com",
                            "Referer" to "https://playhydrax.com/"
                        ),
                        json = JsonAsString(sanitizedPayload)
                    ).text
                    decJson = JSONObject(decRes)
                    result = decJson.optJSONObject("result")
                }
            }

            if (result == null) return false
            val sources = result.optJSONArray("sources") ?: return false

            val parsedSources = mutableListOf<Pair<ExtractorLink, Int>>()
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
                    else -> Qualities.Unknown.value
                }
                val link = newExtractorLink(
                    "Hydrax",
                    "Hydrax $type",
                    srcUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = q
                    this.referer = "https://abyssplayer.com/"
                    this.headers = mapOf(
                        "Referer" to "https://abyssplayer.com/"
                    )
                }
                parsedSources.add(Pair(link, q))
            }

            var found = false
            parsedSources.sortedByDescending { it.second }.forEach { (link, _) ->
                callback(link)
                found = true
            }
            return found
        } catch (_: Exception) {
            return false
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
        } catch (_: Exception) {
            return false
        }

        val embeds = extractEmbeds(html)
        if (embeds.isEmpty()) return false

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
                            if (extractHydrax(
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
                    loadExtractor(
                        playerUrl, data,
                        subtitleCallback, callback
                    )
                    found = true
                }
            } catch (_: Exception) {
                // Lewati server yang gagal.
            }
        }

        return found
    }
}
