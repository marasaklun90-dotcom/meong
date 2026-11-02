package com.indo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import android.util.Base64
import java.net.URI
import java.net.URLEncoder

class Vidlink : ExtractorApi() {
    override var name = "Vidlink"
    override var mainUrl = "https://vidlink.pro"
    override val requiresReferer = false

    private val ua =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/137.0.0.0 Mobile Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""/movie/(\d+)""")
            .find(url)?.groupValues?.get(1) ?: return

        val encResponse = app.get(
            "https://enc-dec.app/api/enc-vidlink?text=$id"
        ).text

        val enc = JSONObject(encResponse).optString("result")
        if (enc.isBlank()) return

        val response = app.get(
            "https://vidlink.pro/api/b/movie/$enc?multiLang=0",
            headers = mapOf(
                "User-Agent" to ua,
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/",
                "X-Playback-Environment" to "dash-hevc"
            )
        ).text

        val stream = JSONObject(response)
            .optJSONObject("stream") ?: return

        val playlist = stream.optString("playlist")

        if (playlist.isNotBlank()) {
            val playlistHeaders =
                stream.optJSONObject("playlistHeaders")

            var cookie = ""

            if (playlistHeaders != null) {
                for (key in playlistHeaders.keys()) {
                    if (key.equals("Cookie", ignoreCase = true)) {
                        cookie = playlistHeaders.optString(key)
                        break
                    }
                }
            }

            if (cookie.isNotBlank()) {
                val parsed = URI(playlist)
                val origin = "${parsed.scheme}://${parsed.rawAuthority}"

                val encodedCookie = Base64.encodeToString(
                    cookie.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )

                fun encode(value: String): String =
                    URLEncoder.encode(value, "UTF-8")
                        .replace("+", "%20")

                val originalQuery = parsed.rawQuery
                    ?.takeIf { it.isNotBlank() }
                    ?.plus("&") ?: ""

                val dashUrl =
                    "https://surge.flooderrr.store/sacdn" +
                    parsed.rawPath +
                    "?" + originalQuery +
                    "host=" + encode(origin) +
                    "&sc=" + encode(encodedCookie)

                callback(
                    newExtractorLink(
                        name,
                        "$name DASH HEVC",
                        dashUrl,
                        type = ExtractorLinkType.DASH
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = mapOf(
                            "User-Agent" to ua,
                            "Referer" to "$mainUrl/"
                        )
                    }
                )
            }
        }

        val captions = stream.optJSONArray("captions")
        if (captions != null) {
            for (i in 0 until captions.length()) {
                val caption = captions.optJSONObject(i) ?: continue
                val subtitleUrl = caption.optString("url")

                if (subtitleUrl.startsWith("http")) {
                    subtitleCallback(
                        newSubtitleFile(
                            caption.optString("language", "Subtitle"),
                            subtitleUrl
                        )
                    )
                }
            }
        }
    }
}
