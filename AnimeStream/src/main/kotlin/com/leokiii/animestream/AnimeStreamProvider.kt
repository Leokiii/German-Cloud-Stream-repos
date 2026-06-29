package com.leokiii.animestream

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

class AnimeStreamProvider : MainAPI() {
    override var mainUrl = "https://anime-stream.to"
    override var name = "Anime-Stream"
    override val hasMainPage = true
    override var lang = "de"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/series/page/" to "Serien",
        "$mainUrl/movies/page/" to "Filme",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("${request.data}$page/").document
        val home = document.select("div.movies-list div.ml-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        // The theme paginates with a "next" list item after the active page.
        val hasNext = document.selectFirst("li.active ~ li") != null
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val img = this.selectFirst("a img")
        val title = (img?.attr("alt").orEmpty()).ifBlank { anchor.attr("title") }
            .ifBlank { anchor.text() }.trim()
        if (title.isEmpty()) return null
        val poster = fixUrlNull(img?.let { it.attr("data-original").ifBlank { it.attr("src") } })
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.movies-list div.ml-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val img = document.selectFirst("div.thumb img")
        val title = (img?.attr("alt").orEmpty())
            .ifBlank { document.selectFirst("h1")?.text().orEmpty() }
            .ifBlank { "Anime-Stream" }.trim()
        val poster = fixUrlNull(img?.attr("src"))
        val plot = document.selectFirst("div.desc p.f-desc, div.desc")?.text()
        val tags = document.select("div.mvici-left p a[rel=\"category tag\"]").map { it.text() }

        // Episode anchors live in the les-content list.
        val episodeEls = document.select("div.les-content a[href]")
        val episodes: List<Episode> = if (episodeEls.isNotEmpty()) {
            episodeEls.mapIndexed { index, a ->
                val href = fixUrlNull(a.attr("href"))!!
                newEpisode(href) {
                    this.name = a.text().ifBlank { "Episode ${index + 1}" }
                    this.episode = index + 1
                }
            }
        } else {
            listOf(newEpisode(url) { this.episode = 1 })
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
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
        val seen = HashSet<String>()

        val candidates = mutableListOf<String>()
        document.select("a.lnk-lnk[href]").forEach { candidates.add(it.attr("abs:href")) }
        document.select("div.les-content iframe[src], #player iframe[src], iframe[src]")
            .forEach { candidates.add(it.attr("abs:src")) }

        for (raw in candidates) {
            val link = raw.trim()
            if (link.isEmpty() || !seen.add(link)) continue
            if (loadExtractor(link, mainUrl, subtitleCallback, callback)) {
                found = true
            }
        }
        return found
    }
}
