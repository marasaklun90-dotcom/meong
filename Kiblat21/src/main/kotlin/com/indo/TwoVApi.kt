package com.indo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder

open class TwoVApi(
    private val mirrorName: String,
    private val mirrorUrl: String
) : ExtractorApi() {

    override var name = mirrorName
    override var mainUrl = mirrorUrl
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(
                url,
                referer = referer ?: "https://www.2embed.cc/"
            )

            val html = response.text

            val base = Regex(
                """<base[^>]+href=["']([^"']+)""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.get(1) ?: "/"

            val apiBase = when {
                base.startsWith("http") -> base
                base.startsWith("/") -> "$mainUrl$base"
                else -> "$mainUrl/$base"
            }.trimEnd('/')

            val qText = Regex(
                """var\s+Q\s*=\s*(\{.*?\});""",
                RegexOption.DOT_MATCHES_ALL
            ).find(html)?.groupValues?.get(1) ?: return

            val q = JSONObject(qText)
            val token = q.optString("t")
            val servers = q.optJSONObject("ssr")
                ?.optJSONArray("servers") ?: return

            if (token.isBlank()) return

            val encode: (String) -> String = {
                URLEncoder.encode(it, "UTF-8")
            }

            var count = 0

            for (i in 0 until minOf(servers.length(), 14)) {
                val server = servers.optJSONObject(i) ?: continue
                val ref = server.optString("ref")
                val serverName = server.optString("name")

                if (ref.isBlank()) continue

                try {
                    val apiUrl = "$apiBase/api.php?a=play" +
                        "&ref=${encode(ref)}" +
                        "&t=${encode(token)}"

                    val result = app.get(
                        apiUrl,
                        referer = url,
                        headers = mapOf(
                            "Accept" to "application/json, text/plain, */*"
                        )
                    )

                    val data = JSONObject(result.text)
                    val rawVideoUrl = data.optString("url")
                    val type = data.optString("type", "hls")

                    val videoUrl = when {
                        rawVideoUrl.startsWith("https://") ||
                        rawVideoUrl.startsWith("http://") -> rawVideoUrl

                        rawVideoUrl.startsWith("//") ->
                            "https:$rawVideoUrl"

                        rawVideoUrl.startsWith("/") ->
                            "$mainUrl$rawVideoUrl"

                        else ->
                            continue
                    }

                    val linkType = when {
                        type.equals("mp4", true) ->
                            ExtractorLinkType.VIDEO
                        else ->
                            ExtractorLinkType.M3U8
                    }

                    val label = serverName
                        .replace(Regex("""^Server\s+""", RegexOption.IGNORE_CASE), "")
                        .ifBlank { "Server ${i + 1}" }

                    callback(
                        newExtractorLink(
                            name,
                            "$name $label",
                            videoUrl,
                            linkType
                        ) {
                            this.referer = "$mainUrl/"
                            this.headers = mapOf(
                                "Referer" to "$mainUrl/",
                                "Origin" to mainUrl
                            )
                        }
                    )

                    count++

                } catch (e: Exception) {
                    println("$name server $i gagal: ${e.message}")
                }
            }

            println("$name: $count sumber ditemukan")

        } catch (e: Exception) {
            println("$name gagal: ${e.message}")
        }
    }
}

class TwoVVsrc : TwoVApi(
    "2Embed Vsrc",
    "https://vidsrc.buzz"
)

class TwoVVidem : TwoVApi(
    "2Embed Videm",
    "https://videm.xyz"
)
