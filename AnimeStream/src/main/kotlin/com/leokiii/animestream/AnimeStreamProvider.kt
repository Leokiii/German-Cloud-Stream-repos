package com.leokiii.animestream

import com.lagradost.cloudstream3.DubStatus
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
import java.net.URLEncoder

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

    // The current homepage catalog is served from /backup. The two catalog pages
    // provide a much larger searchable/browsable list, with page= pagination.
    override val mainPage = mainPageOf(
        "$mainUrl/backup" to "Neu auf Anime-Stream",
        "$mainUrl/alle-serien" to "Alle Anime-Serien",
        "$mainUrl/alle-filme" to "Alle Anime-Filme",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val isCatalogPage = request.data.contains("/alle-serien") || request.data.contains("/alle-filme")
        val baseTarget = request.data.substringBefore("#")
        val target = if (page > 1) {
            baseTarget + (if (baseTarget.contains("?")) "&" else "?") + "page=" + page
        } else {
            baseTarget
        }
        val document = app.get(target).document

        // Parse actual title links rather than requiring the old .panel markup.
        val home = document.select("a[href*='/anime-serien/'], a[href*='/anime-filme/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val nextPage = page + 1
        val hasNext = isCatalogPage && document.select(
            "a[href*='page=$nextPage'], a[rel=next], .pagination a.next, a.next.page-numbers"
        ).isNotEmpty()
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (tagName() == "a" && hasAttr("href")) this else
            selectFirst("a[href*='/anime-serien/'], a[href*='/anime-filme/']") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.contains("/anime-serien/") && !href.contains("/anime-filme/")) return null

        val card = anchor.closest("article, li, .card, .anime-card, .series-card, .anime-item, .item, .video-item, .panel")
            ?: anchor.parent() ?: anchor
        val image = card.selectFirst("img") ?: anchor.selectFirst("img")
        val title = sequenceOf(
            anchor.attr("title"),
            anchor.attr("aria-label"),
            card.selectFirst("h2, h3, h4, .title, .anime-title, .series-title")?.text().orEmpty(),
            image?.attr("alt").orEmpty(),
            anchor.text()
        ).map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val poster = image?.let { posterFromImage(it) }
        val type = if (href.contains("/anime-filme/")) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    private fun posterFromImage(image: Element): String? {
        val raw = listOf(
            "data-lazy-src", "data-src", "data-original", "data-original-src",
            "data-image", "data-poster", "data-thumb", "data-srcset", "srcset", "src"
        ).asSequence()
            .map { image.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:image", ignoreCase = true) }
            ?: return null
        val url = raw.substringBefore(",").trim().substringBefore(" ").trim()
        return fixUrlNull(url)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val targets = listOf(
            "$mainUrl/?q=$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/suche?q=$encoded"
        )
        val matchingPaths = "a[href*='/anime-serien/'], a[href*='/anime-filme/']"
        for (target in targets) {
            val document = try {
                app.get(target).document
            } catch (_: Exception) {
                continue
            }
            val scoped = document.select(
                ".search-results $matchingPaths, .search-result $matchingPaths, " +
                    ".search-result-item $matchingPaths, .result-item $matchingPaths, " +
                    ".search-results a[href], .search-result-item a[href]"
            )
            val candidates = if (scoped.isNotEmpty()) scoped else {
                document.select(matchingPaths).filter { anchor ->
                    anchor.attr("title").ifBlank { anchor.text() }
                        .contains(query, ignoreCase = true)
                }
            }
            val results = candidates.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst(".series-info .heading")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Anime-Stream"
        val poster = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrlNull(it) }
            ?: document.selectFirst(".series-cover img, .banner img, .anime-cover img, .poster img, .cover img, main img")
                ?.let { posterFromImage(it) }
        val plot = document.selectFirst(".description-container .description")?.text()
        val tags = document.select(".series-meta .genres .genre-tag, .main-genre")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val year = document.selectFirst(".series-meta .year")?.text()
            ?.let { Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val episodeLinks = document.select(".episode-panel .episode-link[href]")
            .mapNotNull { anchor ->
                val link = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val panel = anchor.closest(".episode-panel") ?: anchor
                val label = panel.selectFirst(".episode-number")?.text()
                    ?.ifBlank { anchor.text() }
                    ?: anchor.text()
                val episodeNumber = Regex("(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull()
                val seasonText = anchor.closest(".season-block")
                    ?.selectFirst(".section-header")?.text().orEmpty()
                val seasonNumber = Regex("STAFFEL\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(seasonText)?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(link) {
                    this.name = label.trim().ifBlank { "Folge " + (episodeNumber ?: 1) }
                    this.episode = episodeNumber
                    this.season = seasonNumber
                }
            }

        val episodes = if (episodeLinks.isNotEmpty()) {
            episodeLinks
        } else {
            listOf(newEpisode(url) { this.episode = 1 })
        }

        val type = if (url.contains("/anime-filme/")) TvType.AnimeMovie else TvType.Anime
        return newAnimeLoadResponse(title, url, type) {
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
        var found = false
        val response = try {
            app.get(data, referer = mainUrl)
        } catch (_: Exception) {
            null
        }

        val resolvedUrl = response?.url ?: fixUrlNull(data) ?: data
        if (loadExtractor(resolvedUrl, mainUrl, subtitleCallback, callback)) {
            found = true
        }

        val document = response?.document ?: try {
            app.get(resolvedUrl, referer = mainUrl).document
        } catch (_: Exception) {
            return found
        }

        val candidates = document.select("iframe[src], video source[src], a[href*='player'], .hoster a[href]")
            .map { it.attr("abs:src").ifBlank { it.attr("abs:href") } }
            .filter { it.isNotBlank() }
            .distinct()

        for (link in candidates) {
            if (loadExtractor(link, resolvedUrl, subtitleCallback, callback)) {
                found = true
            }
        }
        return found
    }
}
