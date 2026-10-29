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

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Startseite – alle Bereiche",
        "$mainUrl/latest-uploads/" to "Zuletzt hinzugefügt",
        "$mainUrl/home-3/" to "Weitere Updates",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
        val hasNext = document.select("a.next, a.next.page-numbers, .nav-links a.next, a[rel=next]").isNotEmpty()
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(
            "div.item-thumbnail a[href], .post-thumbnail a[href], a.video-item[href], " +
                "a[href*='/anime/'], a[href*='/series/'], a[href]"
        ) ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (href == mainUrl || href == "$mainUrl/" || href.contains("/page/") ||
            href.contains("/category/") || href.contains("/tag/") || href.contains("/author/") ||
            href.contains("/wp-content/")) return null
        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val title = sequenceOf(
            anchor.attr("title"),
            selectFirst("h2, h3, h4, .entry-title, .post-title, .title")?.text().orEmpty(),
            image?.attr("alt").orEmpty(),
            anchor.text()
        ).map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val poster = image?.let { posterFromImage(it) }
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
    }

    private fun posterFromImage(image: Element): String? {
        val raw = listOf(
            "data-lazy-src", "data-src", "data-original", "data-original-src",
            "data-image", "data-poster", "data-thumb", "srcset", "src"
        ).asSequence().map { image.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:image", ignoreCase = true) }
            ?: return null
        return fixUrlNull(raw.substringBefore(",").trim().substringBefore(" ").trim())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document
        return document.select("div.item-thumbnail a[href], div.video-item, article, .post, .anime-item, .anime-card, .item")
            .mapNotNull { el ->
                if (el.tagName() == "a") {
                    val href = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                    if (href == mainUrl || href == "$mainUrl/" || href.contains("/category/") ||
                        href.contains("/tag/") || href.contains("/author/")) return@mapNotNull null
                    val title = el.attr("title").ifBlank { el.selectFirst("img")?.attr("alt").orEmpty() }
                        .ifBlank { el.text() }.trim()
                    if (title.isEmpty()) return@mapNotNull null
                    val poster = el.selectFirst("img")?.let { posterFromImage(it) }
                    newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
                } else el.toSearchResult()
            }.distinctBy { it.url }
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

        // The tab links are mirror choices (for example ?link=0..3) for this
        // post, not separate episodes. Keep the page URL as episode data so
        // loadLinks can inspect every mirror, including mirrors other than link=0.
        val tabLinks = document.select("div[id^=multi_link_tab] a[href], div.tab-content .tab-pane a[href]")
        val episodeNumber = tabLinks.asSequence()
            .map { it.text().trim() }
            .mapNotNull { label ->
                Regex("(?:ep(?:isode)?|folge)\\s*\\.?\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(label)?.groupValues?.get(1)?.toIntOrNull()
            }
            .firstOrNull() ?: 1
        val episodes = listOf(newEpisode(url) {
            this.episode = episodeNumber
            this.name = "Folge $episodeNumber"
        })

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            val dubStatus = if (title.contains("dub", ignoreCase = true)) {
                DubStatus.Dubbed
            } else {
                DubStatus.Subbed
            }
            addEpisodes(dubStatus, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = fixUrlNull(data) ?: data
        val document = try {
            app.get(pageUrl, referer = mainUrl).document
        } catch (_: Exception) {
            return false
        }
        var found = false
        val seen = HashSet<String>()

        fun cleanUrl(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.isEmpty() || trimmed == "#") return null
            return fixUrlNull(trimmed)
        }

        // Some mirrors are embedded immediately; others only appear after loading
        // their ?link=N page. Inspect both paths rather than assuming link=0 works.
        val directCandidates = document.select(
            "#player-embed a[href], #player-embed iframe[src], div.tab-pane iframe[src]"
        ).mapNotNull { element ->
            cleanUrl(element.attr("abs:src").ifBlank { element.attr("abs:href") })
        }.distinct()

        for (candidate in directCandidates) {
            if (!seen.add(candidate)) continue
            try {
                if (loadExtractor(candidate, pageUrl, subtitleCallback, callback)) found = true
            } catch (_: Exception) {
                // Continue so a dead mirror does not block the remaining choices.
            }
        }

        val mirrorPages = document.select(
            "div.tab-content div.tab-pane a[href], div[id^=multi_link_tab] a[href]"
        ).mapNotNull { cleanUrl(it.attr("abs:href")) }.distinct()

        for (mirrorPage in mirrorPages) {
            try {
                val mirrorDocument = app.get(mirrorPage, referer = pageUrl).document
                val candidates = mirrorDocument.select(
                    "#player-embed a[href], #player-embed iframe[src], div.tab-pane iframe[src]"
                ).mapNotNull { element ->
                    cleanUrl(element.attr("abs:src").ifBlank { element.attr("abs:href") })
                }.distinct()

                for (candidate in candidates) {
                    if (!seen.add(candidate)) continue
                    try {
                        if (loadExtractor(candidate, mirrorPage, subtitleCallback, callback)) found = true
                    } catch (_: Exception) {
                        // Keep trying the other hoster tabs.
                    }
                }
            } catch (_: Exception) {
                // One unavailable mirror should not fail the whole episode.
            }
        }

        return found
    }
}
