package com.RowdyAvocado

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.apmapIndexed
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.Objects
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.system.measureTimeMillis

open class RabbitStream : MainAPI() {
    override var mainUrl = "https://sflix.to"
    override var name = "Sflix.to"

    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val usesWebView = true
    override val supportedTypes =
            setOf(
                    TvType.Movie,
                    TvType.TvSeries,
            )
    override val vpnStatus = VPNStatus.None

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get("$mainUrl/home").text
        val document = Jsoup.parse(html)

        val all = ArrayList<HomePageList>()

        val map =
                mapOf(
                        "Trending Movies" to "div#trending-movies",
                        "Trending TV Shows" to "div#trending-tv",
                )
        map.forEach {
            all.add(
                    HomePageList(
                            it.key,
                            document.select(it.value).select("div.flw-item").map { element ->
                                element.toSearchResult()
                            }
                    )
            )
        }

        document.select("section.block_area.block_area_home.section-id-02").forEach {
            val title = it.select("h2.cat-heading").text().trim()
            val elements = it.select("div.flw-item").map { element -> element.toSearchResult() }
            all.add(HomePageList(title, elements))
        }

        return HomePageResponse(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${query.replace(" ", "-")}"
        val html = app.get(url).text
        val document = Jsoup.parse(html)

        return document.select("div.flw-item").map {
            val title = it.select("h2.film-name").text()
            val href = fixUrl(it.select("a").attr("href"))
            val year = it.select("span.fdi-item").text().toIntOrNull()
            val image = it.select("img").attr("data-src")
            val isMovie = href.contains("/movie/")

            val metaInfo = it.select("div.fd-infor > span.fdi-item")
            // val rating = metaInfo[0].text()
            val quality = getQualityFromString(metaInfo.getOrNull(1)?.text())

            if (isMovie) {
                newMovieSearchResponse(name = title, url = href, type = TvType.Movie, fix = true) {
                    posterUrl = image
                    this.year = year
                    this.quality = quality
                }
            } else {
                newTvSeriesSearchResponse(
                        name = title,
                        url = href,
                        type = TvType.TvSeries,
                        fix = true
                ) {
                    posterUrl = image
                    // this.year = year
                    this.quality = quality
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val details = document.select("div.detail_page-watch")
        val img = details.select("img.film-poster-img")
        val posterUrl = img.attr("src")
        val title = img.attr("title") ?: throw ErrorLoadingException("No Title")

        /*
        val year = Regex("""[Rr]eleased:\s*(\d{4})""").find(
            document.select("div.elements").text()
        )?.groupValues?.get(1)?.toIntOrNull()
        val duration = Regex("""[Dd]uration:\s*(\d*)""").find(
            document.select("div.elements").text()
        )?.groupValues?.get(1)?.trim()?.plus(" min")*/
        var duration = document.selectFirst(".fs-item > .duration")?.text()?.trim()
        var year: Int? = null
        var tags: List<String>? = null
        var cast: List<String>? = null
        val youtubeTrailer = document.selectFirst("iframe#iframe-trailer")?.attr("data-src")
        val rating =
                document.selectFirst(".fs-item > .imdb")
                        ?.text()
                        ?.trim()
                        ?.removePrefix("IMDB:")
                        ?.toRatingInt()

        document.select("div.elements > .row > div > .row-line").forEach { element ->
            val type = element.select(".type").text() ?: return@forEach
            when {
                type.contains("Released") -> {
                    year =
                            Regex("\\d+")
                                    .find(element.ownText() ?: return@forEach)
                                    ?.groupValues
                                    ?.firstOrNull()
                                    ?.toIntOrNull()
                }
                type.contains("Genre") -> {
                    tags = element.select("a").mapNotNull { it.text() }
                }
                type.contains("Cast") -> {
                    cast = element.select("a").mapNotNull { it.text() }
                }
                type.contains("Duration") -> {
                    duration = duration ?: element.ownText().trim()
                }
            }
        }
        val plot = details.select("div.description").text().replace("Overview:", "").trim()

        val isMovie = url.contains("/movie/")

        // https://sflix.to/movie/free-never-say-never-again-hd-18317 -> 18317
        val idRegex = Regex(""".*-(\d+)""")
        val dataId = details.attr("data-id")
        val id =
                if (dataId.isNullOrEmpty())
                        idRegex.find(url)?.groupValues?.get(1)
                                ?: throw ErrorLoadingException("Unable to get id from '$url'")
                else dataId

        val recommendations =
                document.select("div.film_list-wrap > div.flw-item").mapNotNull { element ->
                    val titleHeader =
                            element.select("div.film-detail > .film-name > a")
                                    ?: return@mapNotNull null
                    val recUrl = fixUrlNull(titleHeader.attr("href")) ?: return@mapNotNull null
                    val recTitle = titleHeader.text() ?: return@mapNotNull null
                    val poster = element.select("div.film-poster > img").attr("data-src")
                    newMovieSearchResponse(
                            name = recTitle,
                            recUrl,
                            type =
                                    if (recUrl.contains("/movie/")) TvType.Movie
                                    else TvType.TvSeries,
                    ) { this.posterUrl = poster }
                }

        if (isMovie) {
            // Movies
            val episodesUrl = "$mainUrl/ajax/movie/episodes/$id"
            val episodes = app.get(episodesUrl).text

            // Supported streams, they're identical
            val sourceIds =
                    Jsoup.parse(episodes).select("a").mapNotNull { element ->
                        var sourceId = element.attr("data-id")
                        val serverName = element.select("span").text().trim()
                        if (sourceId.isNullOrEmpty()) sourceId = element.attr("data-linkid")

                        if (element.select("span").text().trim().isValidServer()) {
                            if (sourceId.isNullOrEmpty()) {
                                fixUrlNull(element.attr("href")) to serverName
                            } else {
                                "$url.$sourceId".replace("/movie/", "/watch-movie/") to serverName
                            }
                        } else {
                            null
                        }
                    }

            val comingSoon = sourceIds.isEmpty()

            return newMovieLoadResponse(title, url, TvType.Movie, sourceIds) {
                this.year = year
                this.posterUrl = posterUrl
                this.plot = plot
                addDuration(duration)
                addActors(cast)
                this.tags = tags
                this.recommendations = recommendations
                this.comingSoon = comingSoon
                addTrailer(youtubeTrailer)
                this.rating = rating
            }
        } else {
            val seasonsDocument = app.get("$mainUrl/ajax/v2/tv/seasons/$id").document
            val episodes = arrayListOf<Episode>()
            var seasonItems = seasonsDocument.select("div.dropdown-menu.dropdown-menu-model > a")
            if (seasonItems.isNullOrEmpty())
                    seasonItems = seasonsDocument.select("div.dropdown-menu > a.dropdown-item")
            seasonItems.apmapIndexed { season, element ->
                val seasonId = element.attr("data-id")
                if (seasonId.isNullOrBlank()) return@apmapIndexed

                var episode = 0
                val seasonEpisodes = app.get("$mainUrl/ajax/v2/season/episodes/$seasonId").document
                var seasonEpisodesItems =
                        seasonEpisodes.select("div.flw-item.film_single-item.episode-item.eps-item")
                if (seasonEpisodesItems.isNullOrEmpty()) {
                    seasonEpisodesItems = seasonEpisodes.select("ul > li > a")
                }
                seasonEpisodesItems.forEach {
                    val episodeImg = it.select("img")
                    val episodeTitle = episodeImg.attr("title") ?: it.ownText()
                    val episodePosterUrl = episodeImg.attr("src")
                    val episodeData = it.attr("data-id") ?: return@forEach

                    episode++

                    val episodeNum =
                            (it.select("div.episode-number").text() ?: episodeTitle).let { str ->
                                Regex("""\d+""")
                                        .find(str)
                                        ?.groupValues
                                        ?.firstOrNull()
                                        ?.toIntOrNull()
                            }
                                    ?: episode

                    episodes.add(
                            newEpisode(Pair(url, episodeData)) {
                                this.posterUrl = fixUrlNull(episodePosterUrl)
                                this.name = episodeTitle?.removePrefix("Episode $episodeNum: ")
                                this.season = season + 1
                                this.episode = episodeNum
                            }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                addDuration(duration)
                addActors(cast)
                this.tags = tags
                this.recommendations = recommendations
                addTrailer(youtubeTrailer)
                this.rating = rating
            }
        }
    }

    data class Tracks(
            @JsonProperty("file") val file: String?,
            @JsonProperty("label") val label: String?,
            @JsonProperty("kind") val kind: String?
    )

    data class Sources(
            @JsonProperty("file") val file: String?,
            @JsonProperty("type") val type: String?,
            @JsonProperty("label") val label: String?
    )

    data class SourceObject(
            @JsonProperty("sources") val sources: List<Sources?>? = null,
            @JsonProperty("sources_1") val sources1: List<Sources?>? = null,
            @JsonProperty("sources_2") val sources2: List<Sources?>? = null,
            @JsonProperty("sourcesBackup") val sourcesBackup: List<Sources?>? = null,
            @JsonProperty("tracks") val tracks: List<Tracks?>? = null
    )

    data class SourceObjectEncrypted(
            @JsonProperty("sources") val sources: String?,
            @JsonProperty("encrypted") val encrypted: Boolean?,
            @JsonProperty("sources_1") val sources1: String?,
            @JsonProperty("sources_2") val sources2: String?,
            @JsonProperty("sourcesBackup") val sourcesBackup: String?,
            @JsonProperty("tracks") val tracks: List<Tracks?>?
    )

    data class IframeJson(
            //        @JsonProperty("type") val type: String? = null,
            @JsonProperty("link") val link: String? = null,
    //        @JsonProperty("sources") val sources: ArrayList<String> = arrayListOf(),
    //        @JsonProperty("tracks") val tracks: ArrayList<String> = arrayListOf(),
    //        @JsonProperty("title") val title: String? = null
    )

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls =
                (tryParseJson<Pair<String, String>>(data)?.let { (prefix, server) ->
                            val episodesUrl = "$mainUrl/ajax/v2/episode/servers/$server"

                            // Supported streams, they're identical
                            app.get(episodesUrl).document.select("a").mapNotNull { element ->
                                val id = element.attr("data-id") ?: return@mapNotNull null
                                val serverName = element.select("span").text().trim()
                                if (element.select("span").text().trim().isValidServer()) {
                                    "$prefix.$id".replace("/tv/", "/watch-tv/") to serverName
                                } else {
                                    null
                                }
                            }
                        }
                                ?: tryParseJson<List<Pair<String?, String>>>(data))?.distinct()

        urls?.apmap { (url, serverName) ->
            suspendSafeApiCall {
                // Possible without token

                //                val response = app.get(url)
                //                val key =
                //
                // response.document.select("script[src*=https://www.google.com/recaptcha/api.js?render=]")
                //                        .attr("src").substringAfter("render=")
                //                val token = getCaptchaToken(mainUrl, key) ?:
                // return@suspendSafeApiCall

                val serverId = url?.substringAfterLast(".") ?: return@suspendSafeApiCall
                val iframeLink =
                        app.get("${this.mainUrl}/ajax/get_link/$serverId").parsed<IframeJson>().link
                                ?: return@suspendSafeApiCall

                // Some smarter ws11 or w10 selection might be required in the future.
                //                val extractorData =
                //
                // "https://ws11.rabbitstream.net/socket.io/?EIO=4&transport=polling"
                val res =
                        !loadExtractor(iframeLink, null, subtitleCallback) { extractorLink ->
                            callback.invoke(
                                    ExtractorLink(
                                            source = serverName,
                                            name = serverName,
                                            url = extractorLink.url,
                                            referer = extractorLink.referer,
                                            quality = extractorLink.quality,
                                            type = extractorLink.type,
                                            headers = extractorLink.headers,
                                            extractorData = extractorLink.extractorData
                                    )
                            )
                        }

                if (res) {
                    extractRabbitStream(
                            iframeLink,
                            subtitleCallback,
                            callback,
                            false,
                            decryptKey = getKey()
                    ) { it }
                }
            }
        }

        return !urls.isNullOrEmpty()
    }

    //    override suspend fun extractorVerifierJob(extractorData: String?) {
    //        runSflixExtractorVerifierJob(this, extractorData, "https://rabbitstream.net/")
    //    }

    private fun Element.toSearchResult(): SearchResponse {
        val inner = this.selectFirst("div.film-poster")
        val img = inner!!.select("img")
        val title = img.attr("title")
        val posterUrl = img.attr("data-src") ?: img.attr("src")
        val href = fixUrl(inner.select("a").attr("href"))
        val isMovie = href.contains("/movie/")
        val otherInfo =
                this.selectFirst("div.film-detail > div.fd-infor")?.select("span")?.toList()
                        ?: listOf()
        // var rating: Int? = null
        var year: Int? = null
        var quality: SearchQuality? = null
        when (otherInfo.size) {
            1 -> {
                year = otherInfo[0].text().trim().toIntOrNull()
            }
            2 -> {
                year = otherInfo[0].text().trim().toIntOrNull()
            }
            3 -> {
                // rating = otherInfo[0]?.text()?.toRatingInt()
                quality = getQualityFromString(otherInfo[1].text())
                year = otherInfo[2].text().trim().toIntOrNull()
            }
        }

        return if (isMovie) {
            newMovieSearchResponse(name = title, url = href, type = TvType.Movie, fix = true) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = quality
            }
        } else {
            newTvSeriesSearchResponse(
                    name = title,
                    url = href,
                    type = TvType.TvSeries,
                    fix = true
            ) {
                this.posterUrl = posterUrl
                // this.year = year
                this.quality = quality
            }
        }
    }

    companion object {
        data class PollingData(
                @JsonProperty("sid") val sid: String? = null,
                @JsonProperty("upgrades") val upgrades: ArrayList<String> = arrayListOf(),
                @JsonProperty("pingInterval") val pingInterval: Int? = null,
                @JsonProperty("pingTimeout") val pingTimeout: Int? = null
        )

        /*
        # python code to figure out the time offset based on code if necessary
        chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
        code = "Nxa_-bM"
        total = 0
        for i, char in enumerate(code[::-1]):
            index = chars.index(char)
            value = index * 64**i
            total += value
        print(f"total {total}")
        */
        private fun generateTimeStamp(): String {
            val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
            var code = ""
            var time = unixTimeMS
            while (time > 0) {
                code += chars[(time % (chars.length)).toInt()]
                time /= chars.length
            }
            return code.reversed()
        }

        suspend fun getKey(): String? {
            return app.get("https://e4.tvembed.cc/e4").text
        }

        /** Generates a session 1 Get request. */
        private suspend fun negotiateNewSid(baseUrl: String): PollingData? {
            // Tries multiple times
            for (i in 1..5) {
                val jsonText =
                        app.get("$baseUrl&t=${generateTimeStamp()}").text.replaceBefore("{", "")
                //            println("Negotiated sid $jsonText")
                parseJson<PollingData?>(jsonText)?.let {
                    return it
                }
                delay(1000L * i)
            }
            return null
        }

        /**
         * Generates a new session if the request fails
         * @return the data and if it is new.
         */
        private suspend fun getUpdatedData(
                response: NiceResponse,
                data: PollingData,
                baseUrl: String
        ): Pair<PollingData, Boolean> {
            if (!response.okhttpResponse.isSuccessful) {
                return negotiateNewSid(baseUrl)?.let { it to true } ?: (data to false)
            }
            return data to false
        }

        private suspend fun initPolling(
                extractorData: String,
                referer: String
        ): Pair<PollingData?, String?> {
            val headers =
                    mapOf(
                            "Referer" to referer // "https://rabbitstream.net/"
                    )

            val data = negotiateNewSid(extractorData) ?: return null to null
            app.post(
                    "$extractorData&t=${generateTimeStamp()}&sid=${data.sid}",
                    requestBody = "40".toRequestBody(),
                    headers = headers
            )

            // This makes the second get request work, and re-connect work.
            val reconnectSid =
                    parseJson<PollingData>(
                                    app.get(
                                                    "$extractorData&t=${generateTimeStamp()}&sid=${data.sid}",
                                                    headers = headers
                                            )
                                            //                    .also { println("First get
                                            // ${it.text}") }
                                            .text
                                            .replaceBefore("{", "")
                            )
                            .sid

            // This response is used in the post requests. Same contents in all it seems.
            // val authInt =
            //         app.get(
            //                         "$extractorData&t=${generateTimeStamp()}&sid=${data.sid}",
            //                         timeout = 60,
            //                         headers = headers
            //                 )
            //                 .text
            //                 // .also { println("Second get ${it}") }
            //                 // Dunno if it's actually generated like this, just guessing.
            //                 .toIntOrNull()
            //                 ?.plus(1)
            //                 ?: 3

            return data to reconnectSid
        }

        suspend fun runSflixExtractorVerifierJob(extractorData: String?, referer: String) {
            if (extractorData == null) return
            val headers =
                    mapOf(
                            "Referer" to referer // "https://rabbitstream.net/"
                    )

            lateinit var data: PollingData
            var reconnectSid = ""

            initPolling(extractorData, referer).also {
                data = it.first ?: throw RuntimeException("Data Null")
                reconnectSid = it.second ?: throw RuntimeException("ReconnectSid Null")
            }

            // Prevents them from fucking us over with doing a while(true){} loop
            val interval = maxOf(data.pingInterval?.toLong()?.plus(2000) ?: return, 10000L)
            var reconnect = false
            var newAuth = false

            while (true) {
                val authData =
                        when {
                            newAuth -> "40"
                            reconnect -> """42["_reconnect", "$reconnectSid"]"""
                            else -> "3"
                        }

                val url = "${extractorData}&t=${generateTimeStamp()}&sid=${data.sid}"

                getUpdatedData(
                                app.post(url, json = authData, headers = headers),
                                data,
                                extractorData
                        )
                        .also {
                            newAuth = it.second
                            data = it.first
                        }

                // .also { println("Sflix post job ${it.text}") }

                val time = measureTimeMillis {
                    // This acts as a timeout
                    val getResponse = app.get(url, timeout = interval / 1000, headers = headers)
                    //                    .also { println("Sflix get job ${it.text}") }
                    reconnect = getResponse.text.contains("sid")
                }
                // Always waits even if the get response is instant, to prevent a while true loop.
                if (time < interval - 4000) delay(4000)
            }
        }

        // Only scrape servers with these names
        fun String?.isValidServer(): Boolean {
            val list = listOf("upcloud", "vidcloud", "streamlare")
            return list.contains(this?.lowercase(Locale.ROOT))
        }

        // For re-use in Zoro
        private suspend fun Sources.toExtractorLink(
                caller: MainAPI,
                name: String,
                extractorData: String? = null,
        ): List<ExtractorLink>? {
            return this.file?.let { file ->
                // println("FILE::: $file")
                val isM3u8 =
                        URI(this.file).path.endsWith(".m3u8") ||
                                this.type.equals("hls", ignoreCase = true)
                return if (isM3u8) {
                    suspendSafeApiCall {
                        M3u8Helper()
                                .m3u8Generation(
                                        M3u8Helper.M3u8Stream(
                                                this.file,
                                                null,
                                                mapOf("Referer" to "https://mzzcloud.life/")
                                        ),
                                        false
                                )
                                .map { stream ->
                                    ExtractorLink(
                                            caller.name,
                                            "${caller.name} $name",
                                            stream.streamUrl,
                                            caller.mainUrl,
                                            getQualityFromName(stream.quality?.toString()),
                                            true,
                                            extractorData = extractorData
                                    )
                                }
                    }
                            .takeIf { !it.isNullOrEmpty() }
                            ?: listOf(
                                    // Fallback if m3u8 extractor fails
                                    ExtractorLink(
                                            caller.name,
                                            "${caller.name} $name",
                                            this.file,
                                            caller.mainUrl,
                                            getQualityFromName(this.label),
                                            isM3u8,
                                            extractorData = extractorData
                                    )
                            )
                } else {
                    listOf(
                            ExtractorLink(
                                    caller.name,
                                    caller.name,
                                    file,
                                    caller.mainUrl,
                                    getQualityFromName(this.label),
                                    false,
                                    extractorData = extractorData
                            )
                    )
                }
            }
        }

        private fun Tracks.toSubtitleFile(): SubtitleFile? {
            return this.file?.let { SubtitleFile(this.label ?: "Unknown", it) }
        }

        private fun md5(input: ByteArray): ByteArray {
            return MessageDigest.getInstance("MD5").digest(input)
        }

        private fun generateKey(salt: ByteArray, secret: ByteArray): ByteArray {
            var key = md5(secret + salt)
            var currentKey = key
            while (currentKey.size < 48) {
                key = md5(key + secret + salt)
                currentKey += key
            }
            return currentKey
        }

        private fun decryptSourceUrl(decryptionKey: ByteArray, sourceUrl: String): String {
            val cipherData = base64DecodeArray(sourceUrl)
            val encrypted = cipherData.copyOfRange(16, cipherData.size)
            val aesCBC = Cipher.getInstance("AES/CBC/PKCS5Padding")

            Objects.requireNonNull(aesCBC)
                    .init(
                            Cipher.DECRYPT_MODE,
                            SecretKeySpec(decryptionKey.copyOfRange(0, 32), "AES"),
                            IvParameterSpec(decryptionKey.copyOfRange(32, decryptionKey.size))
                    )
            val decryptedData = aesCBC!!.doFinal(encrypted)
            return String(decryptedData, StandardCharsets.UTF_8)
        }

        private inline fun <reified T> decryptMapped(input: String, key: String): T? {
            return tryParseJson(decrypt(input, key))
        }

        private fun decrypt(input: String, key: String): String {
            return decryptSourceUrl(
                    generateKey(base64DecodeArray(input).copyOfRange(8, 16), key.toByteArray()),
                    input
            )
        }

        suspend fun MainAPI.extractRabbitStream(
                url: String,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit,
                useSidAuthentication: Boolean,
                /** Used for extractorLink name, input: Source name */
                extractorData: String? = null,
                decryptKey: String? = null,
                nameTransformer: (String) -> String,
        ) = suspendSafeApiCall {
            // https://rapid-cloud.ru/embed-6/dcPOVRE57YOT?z= -> https://rapid-cloud.ru/embed-6
            val mainIframeUrl = url.substringBeforeLast("/")
            val mainIframeId =
                    url.substringAfterLast("/")
                            .substringBefore(
                                    "?"
                            ) // https://rapid-cloud.ru/embed-6/dcPOVRE57YOT?z= -> dcPOVRE57YOT
            //            val iframe = app.get(url, referer = mainUrl)
            //            val iframeKey =
            //
            // iframe.document.select("script[src*=https://www.google.com/recaptcha/api.js?render=]")
            //                    .attr("src").substringAfter("render=")
            //            val iframeToken = getCaptchaToken(url, iframeKey)
            //            val number =
            //                Regex("""recaptchaNumber =
            // '(.*?)'""").find(iframe.text)?.groupValues?.get(1)

            var sid: String? = null
            if (useSidAuthentication && extractorData != null) {
                negotiateNewSid(extractorData)?.also { pollingData ->
                    app.post(
                            "$extractorData&t=${generateTimeStamp()}&sid=${pollingData.sid}",
                            requestBody = "40".toRequestBody(),
                            timeout = 60
                    )
                    val text =
                            app.get(
                                            "$extractorData&t=${generateTimeStamp()}&sid=${pollingData.sid}",
                                            timeout = 60
                                    )
                                    .text
                                    .replaceBefore("{", "")

                    sid = parseJson<PollingData>(text).sid
                    ioSafe {
                        app.get("$extractorData&t=${generateTimeStamp()}&sid=${pollingData.sid}")
                    }
                }
            }
            val getSourcesUrl =
                    "${
                mainIframeUrl.replace(
                    "/embed",
                    "/ajax/embed"
                )
            }/getSources?id=$mainIframeId${sid?.let { "$&sId=$it" } ?: ""}"
            val response =
                    app.get(
                            getSourcesUrl,
                            referer = mainUrl,
                            headers =
                                    mapOf(
                                            "X-Requested-With" to "XMLHttpRequest",
                                            "Accept" to "*/*",
                                            "Accept-Language" to "en-US,en;q=0.5",
                                            "Connection" to "keep-alive",
                                            "TE" to "trailers"
                                    )
                    )

            val sourceObject =
                    if (decryptKey != null) {
                        val encryptedMap = response.parsedSafe<SourceObjectEncrypted>()
                        val sources = encryptedMap?.sources
                        if (sources == null || encryptedMap.encrypted == false) {
                            response.parsedSafe()
                        } else {
                            val decrypted = decryptMapped<List<Sources>>(sources, decryptKey)
                            SourceObject(sources = decrypted, tracks = encryptedMap.tracks)
                        }
                    } else {
                        response.parsedSafe()
                    }
                            ?: return@suspendSafeApiCall

            sourceObject.tracks?.forEach { track ->
                track?.toSubtitleFile()?.let { subtitleFile ->
                    subtitleCallback.invoke(subtitleFile)
                }
            }

            val list =
                    listOf(
                            sourceObject.sources to "source 1",
                            sourceObject.sources1 to "source 2",
                            sourceObject.sources2 to "source 3",
                            sourceObject.sourcesBackup to "source backup"
                    )

            list.forEach { subList ->
                subList.first?.forEach { source ->
                    source?.toExtractorLink(
                                    this,
                                    nameTransformer(subList.second),
                                    extractorData,
                            )
                            ?.forEach {
                                // Sets Zoro SID used for video loading
                                //                            (this as?
                                // ZoroProvider)?.sid?.set(it.url.hashCode(), sid)
                                callback(it)
                            }
                }
            }
        }
    }
}
