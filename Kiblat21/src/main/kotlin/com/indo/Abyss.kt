package com.indo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import org.json.JSONObject

class Abyss : ExtractorApi() {
    override var name = "HydraX"
    override var mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, headers = mapOf("User-Agent" to ua, "Referer" to (referer ?: "https://kiblatfilm21.com/"))).text
        val enc = Regex("""const\s+datas\s*=\s*"([^"]*)"""").find(html)?.groupValues?.getOrNull(1) ?: return
        subs(enc, subtitleCallback)
        val payload = JSONObject().put("text", enc).toString()
        val dec = app.post(
            "https://enc-dec.app/api/dec-abyss",
            headers = mapOf(
                "User-Agent" to ua,
                "Content-Type" to "application/json",
                "Origin" to "https://playhydrax.com",
                "Referer" to "https://playhydrax.com/"
            ),
            json = JsonAsString(payload)
        ).text
        val sources = JSONObject(dec).optJSONObject("result")?.optJSONArray("sources") ?: return
        for (i in 0 until sources.length()) {
            val o = sources.optJSONObject(i) ?: continue
            if (!o.optBoolean("status", true)) continue
            val u = o.optString("url")
            if (u.isBlank() || !u.startsWith("http")) continue
            val type = o.optString("type")
            val q = when {
                type.contains("1080") -> Qualities.P1080.value
                type.contains("720") -> Qualities.P720.value
                type.contains("480") -> Qualities.P480.value
                type.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
            callback(
                newExtractorLink(name, "$name $type", u, type = ExtractorLinkType.VIDEO) {
                    this.quality = q
                    this.referer = "https://abyssplayer.com/"
                    this.headers = mapOf("Referer" to "https://abyssplayer.com/")
                }
            )
        }
    }

    private suspend fun subs(enc: String, cb: (SubtitleFile) -> Unit) {
        try {
            val raw = String(android.util.Base64.decode(enc, android.util.Base64.DEFAULT), Charsets.ISO_8859_1)
            val json = JSONObject(raw)
            val md5Id = json.optString("md5_id")
            if (md5Id.isBlank()) return
            val arr = json.optJSONObject("config")?.optJSONArray("subtitles") ?: return
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val slug = s.optString("slug")
                if (slug.isBlank()) continue
                val t = s.optString("type", "srt").ifBlank { "srt" }
                val lang = when (s.optString("lang", "id").lowercase()) {
                    "id", "ind", "indonesian", "indonesia" -> "Indonesian"
                    "en", "eng", "english" -> "English"
                    "ms", "may", "malay" -> "Malay"
                    else -> s.optString("lang", "id")
                }
                cb(newSubtitleFile(lang, "https://cdn.iamcdn.net/subtitle/$md5Id/$slug.$t"))
            }
        } catch (_: Exception) {
        }
    }
}
