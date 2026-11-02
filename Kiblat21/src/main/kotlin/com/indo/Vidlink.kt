package com.indo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class Vidlink : ExtractorApi() {
    override var name = "Vidlink"
    override var mainUrl = "https://vidlink.pro"
    override val requiresReferer = false

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""/movie/(\d+)""").find(url)?.groupValues?.get(1) ?: return
        val enc = JSONObject(app.get("https://enc-dec.app/api/enc-vidlink?text=$id").text).optString("result")
        if (enc.isBlank()) return
        val res = app.get(
            "https://vidlink.pro/api/b/movie/$enc",
            headers = mapOf("User-Agent" to ua, "Origin" to "https://vidlink.pro", "Referer" to "https://vidlink.pro/")
        ).text
        val stream = JSONObject(res).optJSONObject("stream") ?: return
        val quals = stream.optJSONObject("qualities")
        if (quals != null) {
            for (k in quals.keys()) {
                val o = quals.optJSONObject(k) ?: continue
                val u = o.optString("url")
                if (u.isBlank()) continue
                val h = o.optJSONObject("headers")
                val hm = mutableMapOf<String, String>()
                if (h != null) for (hk in h.keys()) hm[hk] = h.optString(hk)
                callback(newExtractorLink(name, "$name ${k}p", u, type = ExtractorLinkType.VIDEO) {
                    this.quality = k.toIntOrNull() ?: 0
                    this.referer = "https://filmboom.top/"
                    this.headers = emptyMap()
                })
            }
        }
        val caps = stream.optJSONArray("captions")
        if (caps != null) for (i in 0 until caps.length()) {
            val c = caps.optJSONObject(i) ?: continue
            val cu = c.optString("url")
            if (cu.startsWith("http")) subtitleCallback(newSubtitleFile(c.optString("language", "Subtitle"), cu))
        }
    }
}
