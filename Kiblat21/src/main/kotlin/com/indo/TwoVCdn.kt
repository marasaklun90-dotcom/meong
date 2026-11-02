package com.indo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack

class TwoVCdn : ExtractorApi() {
    override var name = "2Embed"
    override var mainUrl = "https://2vcdn.skin"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(
            url,
            referer = "https://streamsrcs.2embed.cc/",
            headers = mapOf("User-Agent" to "Mozilla/5.0")
        ).text
        val packed = Regex(
            """(?s)eval\s*\(\s*function\s*\(.*?</script>"""
        ).find(res)?.value
            ?.substringBefore("</script>")
            ?: ""

        val script = if (packed.isNotBlank()) {
            try {
                getAndUnpack(packed)
            } catch (e: Exception) {
                println("KIBLAT21 2Embed: unpack gagal: ${e.message}")
                res
            }
        } else {
            res
        }
        val m3u8 = Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""")
            .find(script)?.value
            ?: Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""").find(res)?.value
            ?: run {
                println("KIBLAT21 2Embed: playlist tidak ditemukan")
                return
            }
        println("KIBLAT21 2Embed: playlist ditemukan")
        callback(
            newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                this.referer = "https://2vcdn.skin/"
                this.headers = mapOf("Origin" to "https://2vcdn.skin")
            }
        )
    }
}
