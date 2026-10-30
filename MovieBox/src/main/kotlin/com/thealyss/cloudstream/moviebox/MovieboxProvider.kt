package com.moviebox

import android.net.Uri
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MovieboxProvider : MainAPI() {
    override var mainUrl = "https://officialmoviebox.com"
    private val mainAPIUrl = "https://h5-api.aoneroom.com"
    private val secondAPIUrl = "https://filmboom.top"
    private val mobileAPIUrl = "https://api3.aoneroom.com"
    override val instantLinkLoading = true
    override var name = "MovieBox"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    private val mapper = jacksonObjectMapper()
    private val secretKeyDefault = base64Decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==")
    private val secretKeyAlt = base64Decode("WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ==")
    private val random = SecureRandom()
    private val deviceId = generateDeviceId()

    private val brandModels = mapOf(
        "Samsung" to listOf("SM-S918B", "SM-A528B", "SM-M336B"),
        "Xiaomi" to listOf("2201117TI", "M2012K11AI", "Redmi Note 11"),
        "OnePlus" to listOf("LE2111", "CPH2449", "IN2023"),
        "Google" to listOf("Pixel 6", "Pixel 7", "Pixel 8"),
        "Realme" to listOf("RMX3085", "RMX3360", "RMX3551")
    )

    // Comprehensive Permanent 18+ / Adult / NSFW / BL Blacklist
    private val nsfwBlacklist = listOf(
        // Direct Pornography & 18+ Erotica
        "hentai", "jav", "uncensored", "r18", "adult", "porn", "xxx", "xvideos", "pornhub",
        "erotica", "erotic", "nsfw", "18+", "nudity", "ecchi", "sex", "sexy", "sexuality",
        "sexual", "stripper", "fetish", "smut", "anal", "fuck", "fucking", "gay", "lesbian",
        "blowjob", "creampie", "handjob", "milf", "boobs", "tits", "pussy", "dick", "cock",
        "vagina", "cum", "ejaculat", "orgasm", "masturbat", "threesome", "gangbang", "bdsm",
        "hardcore", "softcore", "nude", "naked", "topless", "bottomless", "horny", "slut",
        "whore", "incest", "camgirl", "cam4", "onlyfans", "brazzers", "redtube", "xhamster",
        "youporn", "xrated", "x-rated", "yaoi", "yuri", "doujin", "doujinshi", "hentaivn",
        "hanime", "deepthroat", "squirting", "orgy", "swinger", "dominatrix", "dildo",
        "vibrator", "voyeur", "nympho", "playboy", "penthouse", "hustler",

        // Boys Love / Girls Love & Related Themes
        "boys love", "boys' love", "boy's love", "boyslove", "boylove", "bl story",
        "bl series", "bl drama", "girls love", "girls' love", "girlslove", "girllove",
        "gl series", "gl drama", "danmei", "fujoshi",

        // Vivamax & Pinoy 18+ Erotic Movies
        "vivamax", "viva max", "bomba", "bold movie", "hubad", "pantaxa", "palitan",
        "scorpio nights", "silip", "haliparot", "tag-init",

        // Indonesian / Malay 18+ terms
        "bokep", "film panas", "cerita panas", "ngewe", "mesum", "porno", "cabul",
        "bugil", "telanjang", "toket", "pepek", "kontol", "memek", "itil", "tetek",
        "sange", "colmek", "coli",

        // Korean / Japanese Erotic keywords
        "young sister in law", "sister in law", "friend's mother", "female boarding house",
        "delicious delivery", "secret tutor"
    )

    // Sports / Wrestling / Football Blacklist
    private val sportsBlacklist = listOf(
        "wrestling", "wrestler", "wwe", "aew", "smackdown", "raw", "royal rumble", "wrestlemania",
        "football", "soccer", "fifa", "uefa", "premier league", "champions league", "la liga",
        "serie a", "bundesliga", "nfl", "super bowl"
    )

    // Regional Dubbing Blacklist (filters out duplicate Hindi/Tamil/Telugu audio dubs)
    private val dubBlacklist = listOf(
        "(hindi)", "[hindi]", "hindi dub", "(tamil)", "[tamil]", "tamil dub",
        "(telugu)", "[telugu]", "telugu dub", "(kannada)", "[kannada]",
        "(malayalam)", "[malayalam]", "(bhojpuri)", "[bhojpuri]",
        "(punjabi)", "[punjabi]", "(dual audio)", "[dual audio]"
    )

    private val nsfwRegexList = nsfwBlacklist.map { keyword ->
        val trimmed = keyword.trim()
        if (Regex("""^[a-zA-Z0-9]+$""").matches(trimmed)) {
            Regex("""(?i)\b${Regex.escape(trimmed)}\b""")
        } else {
            Regex("""(?i)(?:^|\W)${Regex.escape(trimmed)}(?:$|\W)""")
        }
    }

    private val sportsRegexList = sportsBlacklist.map { keyword ->
        val trimmed = keyword.trim()
        if (Regex("""^[a-zA-Z0-9]+$""").matches(trimmed)) {
            Regex("""(?i)\b${Regex.escape(trimmed)}\b""")
        } else {
            Regex("""(?i)(?:^|\W)${Regex.escape(trimmed)}(?:$|\W)""")
        }
    }

    private fun isNsfw(
        title: String? = null,
        description: String? = null,
        tags: List<String>? = null,
        isAdult: Boolean? = null,
        classify: String? = null
    ): Boolean {
        if (isAdult == true) return true

        val combinedText = buildString {
            if (!title.isNullOrBlank()) append(title).append(" ")
            if (!description.isNullOrBlank()) append(description).append(" ")
            if (!classify.isNullOrBlank()) append(classify).append(" ")
            if (!tags.isNullOrEmpty()) append(tags.joinToString(" ")).append(" ")
        }

        if (combinedText.isBlank()) return false

        return nsfwRegexList.any { regex ->
            regex.containsMatchIn(combinedText)
        }
    }

    private fun isExcludedCategory(title: String? = null, tags: List<String>? = null): Boolean {
        if (!title.isNullOrBlank()) {
            if (sportsRegexList.any { it.containsMatchIn(title) }) return true
        }
        if (!tags.isNullOrEmpty()) {
            val combinedTags = tags.joinToString(" ")
            if (sportsRegexList.any { it.containsMatchIn(combinedTags) }) return true
        }
        return false
    }

    private fun isAnimeOrAnimation(subjectType: Int?, tags: List<String>?): Boolean {
        if (subjectType == 1006) return true
        if (!tags.isNullOrEmpty() && tags.any { it.equals("Anime", ignoreCase = true) || it.equals("Animation", ignoreCase = true) }) return true
        return false
    }

    private fun isDubbedTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        val lower = title.lowercase(Locale.ROOT)
        return dubBlacklist.any { lower.contains(it) }
    }

    private fun md5(input: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) }

    private fun generateXClientToken(timestamp: Long = System.currentTimeMillis()): String {
        val ts = timestamp.toString()
        val reversed = ts.reversed()
        val hash = md5(reversed.toByteArray(Charsets.UTF_8))
        return "$ts,$hash"
    }

    private fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun randomBrandModel(): Pair<String, String> {
        val brand = brandModels.keys.random()
        val model = brandModels[brand]?.random() ?: "SM-S918B"
        return brand to model
    }

    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long
    ): String {
        val uri = Uri.parse(url)
        val path = uri.path ?: ""
        val query = if (!uri.queryParameterNames.isNullOrEmpty()) {
            uri.queryParameterNames.sorted().joinToString("&") { key ->
                uri.getQueryParameters(key).joinToString("&") { "$key=$it" }
            }
        } else ""
        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null) {
            val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
            md5(trimmed)
        } else ""
        val bodyLength = bodyBytes?.size?.toString() ?: ""
        return "${method.uppercase()}\n${accept ?: ""}\n${contentType ?: ""}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
    }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String? = null,
        useAltKey: Boolean = false,
        timestamp: Long = System.currentTimeMillis()
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val secret = if (useAltKey) secretKeyAlt else secretKeyDefault
        val secretBytes = base64DecodeArray(secret)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val signatureB64 = base64Encode(signature)
        return "$timestamp|2|$signatureB64"
    }

    private fun decodeJwtExpiry(token: String): Long {
        return try {
            val payload = token.split(".").getOrNull(1) ?: return 0L
            val it = payload.replace("-", "+").replace("_", "/")
            val padded = it + "=".repeat((4 - (it.length % 4)) % 4)
            val json = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            JSONObject(json).optLong("exp", 0L)
        } catch (e: Exception) {
            0L
        }
    }

    private fun isTokenValid(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val exp = decodeJwtExpiry(token)
        return exp > (System.currentTimeMillis() / 1000) + 3600
    }

    private var cachedBearerToken: String? = null

    private suspend fun getCachedToken(): String {
        if (isTokenValid(cachedBearerToken)) return cachedBearerToken!!

        val url = "$mobileAPIUrl/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"
        val (brand, model) = randomBrandModel()
        val xClientToken = generateXClientToken()
        val xTrSignature = generateXTrSignature("GET", "application/json", "application/json", url)
        val headers = mapOf(
            "user-agent" to "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; $brand; Build/TQ3A.230901.001; Cronet/145.0.7582.0)",
            "accept" to "application/json",
            "content-type" to "application/json",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.oneroom","version_name":"3.0.13.0325.03","version_code":50020088,"os":"android","os_version":"13","device_id":"$deviceId","install_store":"ps","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"Asia/Calcutta","sp_code":""}""",
            "x-client-status" to "0"
        )
        return try {
            val res = app.get(url, headers = headers)
            val xUser = res.headers["x-user"]
            if (!xUser.isNullOrBlank()) {
                val token = mapper.readTree(xUser).get("token")?.asText()
                if (!token.isNullOrBlank()) {
                    cachedBearerToken = token
                    return token
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    // Curated Main Page without Anime or Dubbed duplicates
    override val mainPage: List<MainPageData> = mainPageOf(
        "872031290915189720" to "Trending Now",
        "4380734070238626200" to "K-Drama",
        "7736026911486755336" to "Western TV",
        "8624142774394406504" to "Most Popular C-Drama",
        "5848753831881965888" to "Indonesian Horror Stories",
        "1,Latest" to "Movie Latest",
        "1,Latest,Animation" to "Animation",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val home = mutableListOf<SearchResponse>()
        if (!request.data.contains(",")) {
            val url = "$mainAPIUrl/wefeed-h5api-bff/ranking-list/content?id=${request.data}&page=$page&perPage=12"
            val index = app.get(url).parsedSafe<Media>()?.data?.subjectList?.mapNotNull { item ->
                val tags = item.genre?.split(",")?.map { it.trim() }
                if (isNsfw(title = item.title, description = item.description, tags = tags) || isAnimeOrAnimation(item.subjectType, tags) || isDubbedTitle(item.title) || isExcludedCategory(item.title, tags)) null
                else item.toSearchResponse(this)
            } ?: emptyList()

            home.addAll(index)
        } else {
            val params = request.data.split(",")
            val body = mapOf(
                "channelId" to params.first(),
                "page" to page,
                "perPage" to "28",
                "sort" to params[1]
            ).plus(if (params.size > 2) mapOf("genre" to params[2]) else emptyMap()).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

            val index = app.post("$mainAPIUrl/wefeed-h5api-bff/subject/filter", requestBody = body)
                .parsedSafe<Media>()?.data?.items?.mapNotNull { item ->
                    val tags = item.genre?.split(",")?.map { it.trim() }
                    if (isNsfw(title = item.title, description = item.description, tags = tags) || (params.size < 3 && isAnimeOrAnimation(item.subjectType, tags)) || isDubbedTitle(item.title) || isExcludedCategory(item.title, tags)) null
                    else item.toSearchResponse(this)
                } ?: emptyList()

            home.addAll(index)
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (isNsfw(title = query) || isExcludedCategory(title = query)) return emptyList()

        val url = "$mobileAPIUrl/wefeed-mobile-bff/subject-api/search/v2"
        val jsonBody = """{"page": 1, "perPage": 20, "keyword": "$query"}"""
        val xClientToken = generateXClientToken()
        val xTrSignature = generateXTrSignature(
            "POST",
            "application/json",
            "application/json; charset=utf-8",
            url,
            jsonBody
        )
        val token = getCachedToken()
        val (brand, model) = randomBrandModel()
        val headers = mapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "content-type" to "application/json",
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"$brand $model","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
            "x-client-status" to "0",
            "Authorization" to "Bearer $token"
        )

        return try {
            val requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
            val res = app.post(url, headers = headers, requestBody = requestBody)

            val xUser = res.headers["x-user"]
            if (!xUser.isNullOrBlank()) {
                val tokenFromHeader = mapper.readTree(xUser).get("token")?.asText()
                if (!tokenFromHeader.isNullOrBlank()) {
                    cachedBearerToken = tokenFromHeader
                }
            }

            val root = mapper.readTree(res.text)
            val results = mutableListOf<SearchResponse>()
            val searchResults = root.get("data")?.get("results")
            searchResults?.forEach { resultItem ->
                resultItem.get("subjects")?.forEach { subjectItem ->
                    val title = subjectItem.get("title")?.asText() ?: return@forEach
                    val desc = subjectItem.get("description")?.asText() ?: subjectItem.get("overview")?.asText()
                    val classify = subjectItem.get("classify")?.asText()
                    val id = subjectItem.get("subjectId")?.asText() ?: return@forEach
                    val cover = subjectItem.get("cover")?.get("url")?.asText()
                    val subjectType = subjectItem.get("subjectType")?.asInt() ?: 1
                    val isAdult = subjectItem.get("isAdult")?.asBoolean() ?: false

                    val tags = mutableListOf<String>()
                    subjectItem.get("genres")?.forEach { tags.add(it.asText()) }
                    subjectItem.get("tags")?.forEach { tags.add(it.asText()) }
                    subjectItem.get("genre")?.asText()?.split(",")?.map { it.trim() }?.let { tags.addAll(it) }

                    // Comprehensive permanent 18+, Anime, BL, Sports & Regional Dub removal from MovieBox
                    if (isNsfw(title = title, description = desc, tags = tags, isAdult = isAdult, classify = classify) || isAnimeOrAnimation(subjectType, tags) || isDubbedTitle(title) || isExcludedCategory(title, tags)) return@forEach

                    val tvType = if (subjectType == 2) TvType.TvSeries else TvType.Movie
                    results.add(
                        newMovieSearchResponse(title, id, tvType) {
                            this.posterUrl = cover
                        }
                    )
                }
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = if (url.contains("subjectId=")) {
            Regex("""subjectId=([^&]+)""").find(url)?.groupValues?.get(1) ?: url.substringAfterLast("/")
        } else {
            url.substringAfterLast("/")
        }

        val document = app.get("$secondAPIUrl/wefeed-h5-bff/web/subject/detail?subjectId=$id")
            .parsedSafe<MediaDetail>()?.data
        val subject = document?.subject
        val title = subject?.title ?: ""
        val poster = subject?.cover?.url
        val tags = subject?.genre?.split(",")?.map { it.trim() }
        val description = subject?.description

        // Strict filter on load
        if (isNsfw(title = title, tags = tags)) {
            throw ErrorLoadingException("Content filtered by SafeSearch (18+ / Adult content)")
        }
        if (isExcludedCategory(title = title, tags = tags)) {
            throw ErrorLoadingException("Content filtered (Sports / Wrestling / Football)")
        }

        val year = subject?.releaseDate?.substringBefore("-")?.toIntOrNull()
        val tvType = if (subject?.subjectType == 2) TvType.TvSeries else TvType.Movie
        val trailer = subject?.trailer?.videoAddress?.url
        val rating = subject?.imdbRatingValue?.toIntOrNull()
        val actors = document?.stars?.mapNotNull { cast ->
            ActorData(
                Actor(
                    cast.name ?: return@mapNotNull null,
                    cast.avatarUrl
                ),
                roleString = cast.character
            )
        }?.distinctBy { it.actor }

        val recommendations =
            app.get("$secondAPIUrl/wefeed-h5-bff/web/subject/detail-rec?subjectId=$id&page=1&perPage=12")
                .parsedSafe<Media>()?.data?.items?.mapNotNull { item ->
                    val itemTags = item.genre?.split(",")?.map { it.trim() }
                    if (isNsfw(title = item.title, description = item.description, tags = itemTags) || isAnimeOrAnimation(item.subjectType, itemTags) || isDubbedTitle(item.title) || isExcludedCategory(item.title, itemTags)) null
                    else item.toSearchResponse(this)
                }

        return if (tvType == TvType.TvSeries) {
            val episode = document?.resource?.seasons?.map { seasons ->
                (if (seasons.allEp.isNullOrEmpty()) (1..seasons.maxEp!!) else seasons.allEp.split(",")
                    .map { it.toInt() })
                    .map { episode ->
                        newEpisode(
                            LoadData(
                                id,
                                seasons.se,
                                episode,
                                subject?.detailPath
                            ).toJson()
                        ) {
                            this.season = seasons.se
                            this.episode = episode
                        }
                    }
            }?.flatten() ?: emptyList()
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episode) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer, addRaw = true)
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LoadData(id, detailPath = subject?.detailPath).toJson()
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer, addRaw = true)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val media = parseJson<LoadData>(data)
        val referer = "$secondAPIUrl/spa/videoPlayPage/movies/${media.detailPath}?id=${media.id}&type=/movie/detail&lang=en"

        val streams = app.get(
            "$secondAPIUrl/wefeed-h5-bff/web/subject/play?subjectId=${media.id}&se=${media.season ?: 0}&ep=${media.episode ?: 0}",
            referer = referer
        ).parsedSafe<Media>()?.data?.streams

        streams?.reversed()?.distinctBy { it.url }?.forEach { source ->
            val sourceUrl = source.url ?: return@forEach
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    sourceUrl,
                    INFER_TYPE
                ) {
                    this.referer = "$secondAPIUrl/"
                    this.quality = getQualityFromName(source.resolutions)
                }
            )
        }

        val id = streams?.firstOrNull()?.id
        val format = streams?.firstOrNull()?.format

        if (id != null && format != null) {
            app.get(
                "$secondAPIUrl/wefeed-h5-bff/web/subject/caption?format=$format&id=$id&subjectId=${media.id}",
                referer = referer
            ).parsedSafe<Media>()?.data?.captions?.forEach { subtitle ->
                val subUrl = subtitle.url ?: return@forEach
                subtitleCallback.invoke(
                    newSubtitleFile(
                        subtitle.lanName ?: "",
                        subUrl
                    )
                )
            }
        }

        return true
    }

    data class LoadData(
        val id: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val detailPath: String? = null,
    )

    data class Media(
        @JsonProperty("data") val data: Data? = null,
    ) {
        data class Data(
            @JsonProperty("subjectList") val subjectList: ArrayList<Items>? = arrayListOf(),
            @JsonProperty("items") val items: ArrayList<Items>? = arrayListOf(),
            @JsonProperty("streams") val streams: ArrayList<Streams>? = arrayListOf(),
            @JsonProperty("captions") val captions: ArrayList<Captions>? = arrayListOf(),
        ) {
            data class Streams(
                @JsonProperty("id") val id: String? = null,
                @JsonProperty("format") val format: String? = null,
                @JsonProperty("url") val url: String? = null,
                @JsonProperty("resolutions") val resolutions: String? = null,
            )

            data class Captions(
                @JsonProperty("lan") val lan: String? = null,
                @JsonProperty("lanName") val lanName: String? = null,
                @JsonProperty("url") val url: String? = null,
            )
        }
    }

    data class MediaDetail(
        @JsonProperty("data") val data: Data? = null,
    ) {
        data class Data(
            @JsonProperty("subject") val subject: Items? = null,
            @JsonProperty("stars") val stars: ArrayList<Stars>? = arrayListOf(),
            @JsonProperty("resource") val resource: Resource? = null,
        ) {
            data class Stars(
                @JsonProperty("name") val name: String? = null,
                @JsonProperty("character") val character: String? = null,
                @JsonProperty("avatarUrl") val avatarUrl: String? = null,
            )

            data class Resource(
                @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
            ) {
                data class Seasons(
                    @JsonProperty("se") val se: Int? = null,
                    @JsonProperty("maxEp") val maxEp: Int? = null,
                    @JsonProperty("allEp") val allEp: String? = null,
                )
            }
        }
    }

    data class Items(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("subjectType") val subjectType: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("duration") val duration: Long? = null,
        @JsonProperty("genre") val genre: String? = null,
        @JsonProperty("cover") val cover: Cover? = null,
        @JsonProperty("imdbRatingValue") val imdbRatingValue: String? = null,
        @JsonProperty("countryName") val countryName: String? = null,
        @JsonProperty("trailer") val trailer: Trailer? = null,
        @JsonProperty("detailPath") val detailPath: String? = null,
    ) {
        fun toSearchResponse(provider: MovieboxProvider): SearchResponse {
            return provider.newMovieSearchResponse(
                title ?: "",
                subjectId ?: "",
                if (subjectType == 1) TvType.Movie else TvType.TvSeries,
                false
            ) {
                this.posterUrl = cover?.url
            }
        }

        data class Cover(
            @JsonProperty("url") val url: String? = null,
        )

        data class Trailer(
            @JsonProperty("videoAddress") val videoAddress: VideoAddress? = null,
        ) {
            data class VideoAddress(
                @JsonProperty("url") val url: String? = null,
            )
        }
    }
}
