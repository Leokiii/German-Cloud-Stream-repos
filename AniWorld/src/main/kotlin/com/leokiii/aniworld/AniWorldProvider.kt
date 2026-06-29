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
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element

class AniWorldProvider : MainAPI() {
    // aniworld.site is a mirror of aniworld.to and shares the same structure.
    // If aniworld.to is ever unreachable, switch this to "https://aniworld.site".
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
        // Anime entries everywhere on the site are links to /anime/stream/{slug}
        val home = document.select("a[href*=/anime/stream/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href")
        // Only top-level anime pages, not season/episode sub-pages
        if (!href.contains("/anime/stream/")) return null
        val slug = href.substringAfter("/anime/stream/").trim('/')
        if (slug.isEmpty() || slug.contains("/")) return null

        val title = this.attr("title").removeSuffix(" Stream anschauen").ifBlank {
            this.selectFirst("h3, .seriesTitle, .title")?.text() ?: this.text()
        }.trim()
        if (title.isEmpty()) return null

        val posterUrl = fixPosterUrl(
            this.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        )

        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // aniworld exposes a JSON search endpoint
        val response = app.post(
            "$mainUrl/ajax/search",
            data = mapOf("keyword" to query),
            referer = "$mainUrl/search",
            headers = mapOf("x-requested-with" to "XMLHttpRequest")
        ).text

        val results = parseSearchJson(response)
        if (results.isNotEmpty()) return results

        // Fallback: scrape the HTML search page
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select("a[href*=/anime/stream/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private suspend fun parseSearchJson(json: String): List<SearchResponse> {
        // Response is a JSON array of objects: { "title": "...", "link": "/anime/stream/slug", ... }
        val items = tryParseJson<List<SearchItem>>(json) ?: return emptyList()
        return items.filter { it.link?.contains("/anime/stream/") == true }
            .mapNotNull { item ->
                val link = item.link ?: return@mapNotNull null
                // Only top-level series links
                val slug = link.substringAfter("/anime/stream/").trim('/')
                if (slug.isEmpty() || slug.contains("/")) return@mapNotNull null
                val title = item.title?.replace(Regex("<[^>]*>"), "")?.trim() ?: return@mapNotNull null
                newAnimeSearchResponse(title, fixUrl(link), TvType.Anime)
            }
            .distinctBy { it.url }
    }

    data class SearchItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("description") val description: String? = null,
    )

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.series-title h1 span")?.text()
            ?: document.selectFirst("h1 span")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: "AniWorld"

        val poster = fixPosterUrl(
            document.selectFirst("div.seriesCoverBox img")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        )

        val plot = document.selectFirst("p.seri_des")?.attr("data-full-description")
            ?: document.selectFirst("p.seri_des")?.text()

        val tags = document.select("div.genres li a").map { it.text() }

        val yearText = document.selectFirst("span[itemprop=startDate] a")?.text()
            ?: document.selectFirst("div.series-title small span")?.text()
        val year = Regex("(\\d{4})").find(yearText ?: "")?.groupValues?.get(1)?.toIntOrNull()

        // Collect every season link, then every episode within each season.
        val seasonLinks = document.select("div#stream > ul:first-child li a")
            .map { it.attr("href") }
            .filter { it.contains("/staffel-") || it.contains("/filme") }
            .map { fixUrl(it) }
            .distinct()
            .ifEmpty { listOf(url) }

        val episodes = mutableListOf<Episode>()
        for (seasonUrl in seasonLinks) {
            val seasonDoc = if (seasonUrl == url) document else app.get(seasonUrl).document
            val seasonNumber = Regex("staffel-(\\d+)")
                .find(seasonUrl)?.groupValues?.get(1)?.toIntOrNull()

            seasonDoc.select("table.seasonEpisodesList tbody tr").forEach { row ->
                val epLink = row.selectFirst("a[href*=/episode-]")?.attr("href")
                    ?: row.selectFirst("td a")?.attr("href")
                    ?: return@forEach
                val epNum = Regex("episode-(\\d+)")
                    .find(epLink)?.groupValues?.get(1)?.toIntOrNull()
                val epTitle = row.selectFirst("td.seasonEpisodeTitle a span")?.text()
                    ?: row.selectFirst("td.seasonEpisodeTitle")?.text()

                episodes.add(
                    newEpisode(fixUrl(epLink)) {
                        this.name = epTitle
                        this.season = seasonNumber
                        this.episode = epNum
                    }
                )
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

        // Each hoster is a list item that links to a /redirect/{id} which 302s to the real host.
        document.select("div.hosterSiteVideo ul li").forEach { li ->
            val redirect = li.selectFirst("a.watchEpisode")?.attr("href")
                ?: li.attr("data-link-target")
            if (redirect.isNullOrBlank()) return@forEach

            // Follow the redirect to resolve the actual hosting provider URL.
            val hostUrl = try {
                app.get(fixUrl(redirect), allowRedirects = false)
                    .headers["location"]
                    ?: app.get(fixUrl(redirect)).url
            } catch (e: Exception) {
                fixUrl(redirect)
            }

            if (loadExtractor(hostUrl, mainUrl, subtitleCallback, callback)) {
                found = true
            }
        }

        return found
    }
}
