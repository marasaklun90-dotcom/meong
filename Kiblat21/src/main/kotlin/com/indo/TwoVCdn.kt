package com.indo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getAndUnpack

class TwoVCdn : ExtractorApi() {
    override var name = "2VCDN"
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
            referer = referer ?: "https://streamsrcs.2embed.cc/",
            headers = mapOf("User-Agent" to "Mozilla/5.0")
        ).text
        val script = try { getAndUnpack(res) } catch (e: Exception) { res }
        val m3u8 = Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""")
            .find(script)?.value
            ?: Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""").find(res)?.value
            ?: return
        M3u8Helper.generateM3u8(name, m3u8, "https://2vcdn.skin/")
            .forEach(callback)
    }
}
