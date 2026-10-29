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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

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

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Neu hinzugefügt",
        "$mainUrl/page/2/" to "Weitere Anime",
        "$mainUrl/page/3/" to "Mehr entdecken",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // WordPress can use different markup on the front page and archive pages.
        // Respect the selected archive and advance its /page/N/ URL when CloudStream
        // requests additional pages, instead of returning the same cards repeatedly.
        val baseUrl = request.data.trimEnd('/')
        val pagePattern = Regex("(/page/)(\\d+)$")
        val target = if (page <= 1) {
            request.data
        } else {
            val match = pagePattern.find(baseUrl)
            if (match != null) {
                val nextNumber = (match.groupValues[2].toIntOrNull() ?: 1) + page - 1
                baseUrl.substringBeforeLast("/page/") + "/page/$nextNumber/"
            } else {
                "$baseUrl/page/$page/"
            }
        }
        val document = app.get(target).document
        val home = document.select(
            "div.video-item, div.item-thumbnail, article, .post, .anime-item, .anime-card, .item"
        ).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        val hasNext = document.select(
            "a.next, a.next.page-numbers, .nav-links a.next, a[rel=next]"
        ).isNotEmpty()
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(
            "div.item-thumbnail a[href], .post-thumbnail a[href], a.video-item[href], " +
                "a[href*='/anime/'], a[href*='/series/'], a[href]"
        ) ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (href == mainUrl || href.contains("/page/") || href.contains("/category/") ||
            href.contains("/tag/") || href.contains("/author/")) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val title = sequenceOf(
            anchor.attr("title"),
            selectFirst("h2, h3, h4, .entry-title, .post-title, .title")?.text().orEmpty(),
            image?.attr("alt").orEmpty(),
            anchor.text()
        ).map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val poster = image?.let { posterFromImage(it) }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    private fun posterFromImage(image: Element): String? {
        val raw = listOf(
            "data-lazy-src", "data-src", "data-original", "data-original-src",
            "data-image", "data-poster", "data-thumb", "srcset", "src"
        ).asSequence()
            .map { image.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:image", ignoreCase = true) }
            ?: return null
        return fixUrlNull(raw.substringBefore(",").trim().substringBefore(" ").trim())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // WordPress search endpoint.
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document
        return document.select("div.item-thumbnail a[href], div.video-item, article, .post, .anime-item, .anime-card")
            .mapNotNull { el ->
                if (el.tagName() == "a") {
                    val href = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                    val title = el.attr("title").ifBlank {
                        el.selectFirst("img")?.attr("alt").orEmpty()
                    }.trim()
                    if (title.isEmpty()) return@mapNotNull null
                    val poster = el.selectFirst("img")?.let { posterFromImage(it) }
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

        val poster = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")?.let { fixUrlNull(it) }
            ?: document.selectFirst("div.single-thumbnail img, div.post-thumbnail img, article img, .featured-image img")
                ?.let { posterFromImage(it) }

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
