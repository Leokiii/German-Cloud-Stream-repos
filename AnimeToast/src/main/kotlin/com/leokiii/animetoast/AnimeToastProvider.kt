package com.leokiii.animetoast

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class AnimeToastProvider : MainAPI() {
    override var mainUrl = "https://www.animetoast.cc"
    override var name = "AnimeToast"
    override val hasMainPage = true
    override var lang = "de"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // AnimeToast is a WordPress theme; the front page lists recent series as video-item cards.
        val document = app.get(mainUrl).document
        val home = document.select("div.video-item, div.item-thumbnail")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse("Aktuelle Animes", home, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("div.item-thumbnail a")
            ?: this.selectFirst("a[href]")
            ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = anchor.attr("title").ifBlank {
            this.selectFirst("img")?.attr("alt").orEmpty()
        }.ifBlank { anchor.text() }.trim()
        if (title.isEmpty()) return null
        val poster = fixUrlNull(this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        })
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // WordPress search endpoint.
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.item-thumbnail a[href], div.video-item")
            .mapNotNull { el ->
                if (el.tagName() == "a") {
                    val href = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                    val title = el.attr("title").ifBlank {
                        el.selectFirst("img")?.attr("alt").orEmpty()
                    }.trim()
                    if (title.isEmpty()) return@mapNotNull null
                    val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))
                    newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
                } else {
                    el.toSearchResult()
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.light-title")?.text()
            ?: document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: "AnimeToast"

        val poster = fixUrlNull(
            document.selectFirst("div.single-thumbnail img, div.post-thumbnail img, article img")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        )

        val plot = document.selectFirst("div.entry-content p, div.post-content p")?.text()
        val tags = document.select("a[rel=tag], a[rel=\"category tag\"]").map { it.text() }.distinct()

        // Each mirror group is a tab (multi_link_tab0, multi_link_tab1, ...) containing
        // per-episode links. We collect distinct episode links across the first tab group.
        val episodeLinks = LinkedHashSet<String>()
        document.select("div[id^=multi_link_tab] div.tab-pane a[href], div.tab-content div.tab-pane a[href]")
            .forEach { a ->
                fixUrlNull(a.attr("href"))?.let { episodeLinks.add(it) }
            }

        val episodes = if (episodeLinks.isNotEmpty()) {
            episodeLinks.mapIndexed { index, link ->
                newEpisode(link) {
                    this.episode = index + 1
                }
            }
        } else {
            // Movie / single-page case: use the page itself as the only episode.
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

        // The embedded player block exposes the real hoster either as an <a> or <iframe>.
        val candidates = mutableListOf<String>()
        document.select("#player-embed a[href]").forEach { candidates.add(it.attr("abs:href")) }
        document.select("#player-embed iframe[src]").forEach { candidates.add(it.attr("abs:src")) }
        document.select("div.tab-pane iframe[src]").forEach { candidates.add(it.attr("abs:src")) }

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
