package com.indo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

class TwoVCdn : ExtractorApi() {
    override var name = "2Embed Swish"
    override var mainUrl = "https://2vcdn.skin"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(
            url,
            referer = "https://streamsrcs.2embed.cc/",
            headers = mapOf("User-Agent" to "Mozilla/5.0")
        ).text

        val packed = Regex(
            """(?s)eval\s*\(\s*function\s*\(.*?</script>"""
        ).find(html)?.value?.substringBefore("</script>")

        val script = if (packed != null) {
            try {
                getAndUnpack(packed)
            } catch (e: Exception) {
                println("2Embed Swish unpack gagal: ${e.message}")
                return
            }
        } else {
            html
        }

        val links = mutableMapOf<String, String>()

        for (key in listOf("hls4", "hls3", "hls2")) {
            val raw = Regex(
                """"$key"\s*:\s*"([^"]+)""""
            ).find(script)?.groupValues?.get(1) ?: continue

            val resolved = when {
                raw.startsWith("https://") || raw.startsWith("http://") -> raw
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("/") -> "$mainUrl$raw"
                else -> continue
            }

            links[key] = resolved.replace("\\/", "/")
        }

        val selected = listOf("hls4", "hls3", "hls2")
            .firstNotNullOfOrNull { links[it] }

        if (selected == null) {
            println("2Embed Swish: sumber HLS tidak ditemukan")
            return
        }

        println("2Embed Swish: sumber HLS ditemukan")

        callback(
            newExtractorLink(
                name,
                name,
                selected,
                ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/"
                )
            }
        )
    }
}
