package com.leokiii.animebase

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
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class AnimeBaseProvider : MainAPI() {
    override var mainUrl = "https://anime-base.net"
    override var name = "Anime-Base"
    override val hasMainPage = true
    override var lang = "de"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/favorites" to "Beliebt",
        "$mainUrl/updates" to "Updates",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        // Both listing pages link out via /link/<slug> which maps to /anime/<slug>.
        val home = document.select("div.table-responsive > a, div.box-body > a")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawHref = this.attr("href")
        if (rawHref.isBlank()) return null
        val href = fixUrlNull(rawHref.replace("/link/", "/anime/")) ?: return null
        if (!href.contains("/anime/")) return null
        val img = this.selectFirst("div.thumbnail img, img")
        val title = this.attr("title").ifBlank {
            img?.attr("alt").orEmpty()
        }.ifBlank { this.text() }.trim()
        if (title.isEmpty()) return null
        val poster = fixUrlNull(img?.let { it.attr("data-src").ifBlank { it.attr("src") } })
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // The search form posts to /searching with a "search" field.
        val document = app.post(
            "$mainUrl/searching",
            data = mapOf("search" to query),
            referer = "$mainUrl/searching"
        ).document
        return document.select("div.col-lg-9 div.box-body > a, div.box-body > a")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1, div.box-header h3")?.text()?.trim()
            ?: "Anime-Base"
        val poster = fixUrlNull(
            document.selectFirst("div.thumbnail img, div.box-body img")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        )
        val plot = getInfo(document, "Beschreibung")
            ?: document.selectFirst("div.box-body p")?.text()
        val tags = document.select("strong:contains(Genre) + p > a").map { it.text() }
        val year = getInfo(document, "Erscheinungsjahr")
            ?.let { Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        // Episodes are grouped in panels; each panel header names the episode and the
        // hoster buttons inside carry the stream link in data-streamlink.
        val panels = document.select("div.tab-content > div > div.panel, div.panel")
        val episodes = panels.mapIndexedNotNull { index, panel ->
            val epName = panel.selectFirst("h3, div.panel-heading")?.text()?.trim()
            val streamLinks = panel.select("[data-streamlink]")
                .mapNotNull { it.attr("data-streamlink").takeIf { s -> s.isNotBlank() } }
            if (streamLinks.isEmpty()) return@mapIndexedNotNull null
            val number = epName?.let {
                Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
            } ?: (index + 1)
            // Pass the hoster links to loadLinks as a newline-joined payload.
            newEpisode(streamLinks.joinToString("\n")) {
                this.name = epName ?: "Episode $number"
                this.episode = number
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            addEpisodes(
                DubStatus.Subbed,
                episodes.ifEmpty { listOf(newEpisode(url) { this.episode = 1 }) }
            )
        }
    }

    private fun getInfo(element: Element, label: String): String? =
        element.selectFirst("strong:contains($label) + p")?.text()?.trim()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val seen = HashSet<String>()

        // data may be a newline-joined list of hoster links (from load), or a page URL.
        val links: List<String> = if (data.startsWith("http") && !data.contains("\n")) {
            // Single URL: could be a hoster directly or a page containing data-streamlink buttons.
            if (data.contains("/anime/")) {
                app.get(data).document.select("[data-streamlink]")
                    .mapNotNull { it.attr("data-streamlink").takeIf { s -> s.isNotBlank() } }
            } else {
                listOf(data)
            }
        } else {
            data.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        }

        for (raw in links) {
            val link = fixUrlNull(raw)?.trim() ?: continue
            if (!seen.add(link)) continue
            if (loadExtractor(link, mainUrl, subtitleCallback, callback)) {
                found = true
            }
        }
        return found
    }
}
