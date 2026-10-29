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

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Startseite",
        "$mainUrl/alle-serien" to "Alle Serien",
        "$mainUrl/alle-filme" to "Alle Filme",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val target = if (page > 1) {
            request.data + (if (request.data.contains("?")) "&" else "?") + "page=" + page
        } else {
            request.data
        }
        val document = app.get(target).document
        val home = document.select(".panel")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        val hasNext = document.select(".cyber-button[href*='page=']").any {
            val href = it.attr("href")
            href.isNotBlank() && !href.endsWith("page=$page")
        }
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst(".panel-header .panel-link[href]")
            ?: this.selectFirst("a[href*='/anime-serien/'], a[href*='/anime-filme/']")
            ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.contains("/anime-serien/") && !href.contains("/anime-filme/")) return null
        val title = anchor.attr("title").ifBlank { anchor.text() }.trim()
        if (title.isEmpty()) return null
        val image = this.selectFirst(".panel-header .content-box img, img")
        val poster = fixUrlNull(image?.let {
            it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("data-original") }
        })
        val type = if (href.contains("/anime-filme/")) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String) = run {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?q=$encoded").document
        document.select(".search-results .search-result-item, .search-result-item")
            .mapNotNull { item ->
                val anchor = if (item.tagName() == "a" && item.hasAttr("href")) {
                    item
                } else {
                    item.selectFirst("a[href*='/anime-serien/'], a[href*='/anime-filme/'], a[href]")
                } ?: return@mapNotNull null
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                if (!href.contains("/anime-serien/") && !href.contains("/anime-filme/")) return@mapNotNull null
                val title = item.selectFirst(".result-content h3, .result-content .result-title, .result-content .title")
                    ?.text()?.trim()
                    .takeUnless { it.isNullOrBlank() }
                    ?: anchor.attr("title").ifBlank { anchor.text() }.trim()
                if (title.isEmpty()) return@mapNotNull null
                val image = item.selectFirst(".result-image img, img")
                val poster = fixUrlNull(image?.let {
                    it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("data-original") }
                })
                val type = if (href.contains("/anime-filme/")) TvType.AnimeMovie else TvType.Anime
                newAnimeSearchResponse(title, href, type) {
                    this.posterUrl = poster
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst(".series-info .heading")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Anime-Stream"
        val poster = fixUrlNull(
            document.selectFirst(".series-cover img, .banner img")
                ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
        )
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
