package com.leokiii.aniworld

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AniWorldProvider : MainAPI() {
    private val jsonMapper = ObjectMapper()

    override var mainUrl = "https://aniworld.to"
    override var name = "AniWorld"
    override val hasMainPage = true
    override var lang = "de"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/beliebte-animes" to "Beliebte Animes",
        "$mainUrl/neu" to "Neu",
        "$mainUrl/animes-alphabet" to "Alle Animes",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("a[href*=/anime/stream/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href")
        if (!href.contains("/anime/stream/")) return null
        val slug = href.substringAfter("/anime/stream/").trim('/')
        if (slug.isEmpty() || slug.contains("/")) return null

        val title = this.attr("title").removeSuffix(" Stream anschauen").ifBlank {
            this.selectFirst("h3, .seriesTitle, .title")?.text() ?: this.text()
        }.trim()
        if (title.isEmpty()) return null

        val posterUrl = posterFromElement(this)


        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val clean = url.trim()
        if (clean.isEmpty() || clean.startsWith("data:image", ignoreCase = true)) return null
        return when {
            clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$mainUrl$clean"
            else -> "$mainUrl/$clean"
        }
    }

    private fun posterFromElement(element: Element): String? {
        val image = element.selectFirst("img") ?: return null
        val raw = listOf(
            "data-src", "data-lazy-src", "data-original", "data-original-src",
            "data-image", "data-poster", "data-thumb", "srcset", "src"
        ).asSequence()
            .map { image.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:image", ignoreCase = true) }
            ?: return null
        return fixPosterUrl(raw.substringBefore(",").trim().substringBefore(" ").trim())
    }

    override suspend fun search(query: String) = run {
        val response = app.post(
            "$mainUrl/ajax/search",
            data = mapOf("keyword" to query),
            referer = "$mainUrl/search",
            headers = mapOf("x-requested-with" to "XMLHttpRequest")
        ).text

        val results = parseSearchJson(response)
        if (results.isNotEmpty()) return@run results

        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/search?q=$encoded").document
        document.select("a[href*=/anime/stream/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun parseSearchJson(json: String) = run {
        val root = try {
            jsonMapper.readTree(json)
        } catch (_: Exception) {
            null
        }
        if (root == null || !root.isArray) {
            emptyList()
        } else {
            root.mapNotNull { item ->
                val link = item.path("link").asText("")
                if (!link.contains("/anime/stream/")) return@mapNotNull null

                val slug = link.substringAfter("/anime/stream/").trim('/')
                if (slug.isEmpty() || slug.contains("/")) return@mapNotNull null

                val title = Jsoup.parse(item.path("title").asText("")).text().trim()
                if (title.isEmpty()) return@mapNotNull null

                val poster = sequenceOf("image", "poster", "image_url", "cover")
                    .map { item.path(it).asText("") }
                    .firstOrNull { it.isNotBlank() }
                    ?.let { fixPosterUrl(it) }

                newAnimeSearchResponse(title, fixUrl(link), TvType.Anime) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.series-title h1 span")?.text()
            ?: document.selectFirst("h1 span")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: "AniWorld"

        val poster = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")?.let { fixPosterUrl(it) }
            ?: document.selectFirst("div.seriesCoverBox img, .seriesCoverBox img, .series-cover img")
                ?.let { posterFromElement(it.parent() ?: it) }

        val plot = document.selectFirst("p.seri_des")?.attr("data-full-description")
            ?: document.selectFirst("p.seri_des")?.text()

        val tags = document.select("div.genres li a").map { it.text() }

        val yearText = document.selectFirst("span[itemprop=startDate] a")?.text()
            ?: document.selectFirst("div.series-title small span")?.text()
        val year = Regex("(\\d{4})").find(yearText ?: "")?.groupValues?.get(1)?.toIntOrNull()

        val seasonLinks = document.select("div#stream ul:first-child li a")
            .map { it.attr("href") }
            .filter { it.contains("/staffel-") || it.contains("/filme") }
            .map { fixUrl(it) }
            .distinct()
            .ifEmpty { listOf(url) }

        val episodes = seasonLinks.flatMap { seasonUrl ->
            val seasonDoc = if (seasonUrl == url) document else app.get(seasonUrl).document
            val seasonNumber = Regex("staffel-(\\d+)")
                .find(seasonUrl)?.groupValues?.get(1)?.toIntOrNull()

            seasonDoc.select("table.seasonEpisodesList tbody tr").mapNotNull { row ->
                val epLink = row.selectFirst("a[href*=/episode-]")?.attr("href")
                    ?: row.selectFirst("td a")?.attr("href")
                    ?: return@mapNotNull null
                val epNum = Regex("episode-(\\d+)")
                    .find(epLink)?.groupValues?.get(1)?.toIntOrNull()
                val epTitle = row.selectFirst("td.seasonEpisodeTitle a span")?.text()
                    ?: row.selectFirst("td.seasonEpisodeTitle")?.text()

                newEpisode(fixUrl(epLink)) {
                    this.name = epTitle
                    this.season = seasonNumber
                    this.episode = epNum
                }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        document.select("div.hosterSiteVideo ul li").forEach { li ->
            val redirect = li.selectFirst("a.watchEpisode")?.attr("href")
                ?: li.attr("data-link-target")
            if (redirect.isNullOrBlank()) return@forEach

            val hostUrl = try {
                app.get(fixUrl(redirect), allowRedirects = false)
                    .headers["location"]
                    ?: app.get(fixUrl(redirect)).url
            } catch (_: Exception) {
                fixUrl(redirect)
            }

            if (loadExtractor(hostUrl, mainUrl, subtitleCallback, callback)) {
                found = true
            }
        }

        return found
    }
}
