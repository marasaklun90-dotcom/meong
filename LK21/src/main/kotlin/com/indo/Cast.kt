package com.indo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class Cast : ExtractorApi() {
    override val name = "CAST"
    override val mainUrl = "https://videonode.de"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val match = Regex(
            """/iframe3/cast/([^/?#]+)""",
            RegexOption.IGNORE_CASE
        ).find(url) ?: return

        val id = match.groupValues[1]

        val response = app.post(
            "$mainUrl/api.php",
            headers = mapOf(
                "Referer" to url,
                "Origin" to mainUrl
            ),
            data = mapOf(
                "host" to "cast",
                "id" to id
            )
        ).text

        val embedUrl = runCatching {
            JSONObject(response).optString("embedUrl")
        }.getOrNull().orEmpty()

        if (embedUrl.isBlank()) return

        // Hanya proses tautan media langsung.
        // Halaman embed MFW09 memerlukan akses playback tersendiri.
        val path = embedUrl.substringBefore("?")

        val type = when {
            path.endsWith(".m3u8", true) ->
                ExtractorLinkType.M3U8
            path.endsWith(".mp4", true) ->
                ExtractorLinkType.VIDEO
            else -> return
        }

        callback(
            newExtractorLink(
                name,
                name,
                embedUrl,
                type = type
            ) {
                this.referer = referer ?: mainUrl
            }
        )
    }
}
