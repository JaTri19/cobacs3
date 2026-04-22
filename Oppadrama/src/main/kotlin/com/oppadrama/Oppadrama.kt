package com.oppadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.util.Base64

class Oppadrama : MainAPI() {
    override var mainUrl = "http://45.11.57.16"
    override var name = "Oppadrama"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/series/?type=Movie&order=update" to "Film Terupdate",
        "$mainUrl/series/?country%5B%5D=japan&type=Movie&order=update" to "Film Jepang",
        "$mainUrl/series/?country%5B%5D=thailand&status=&type=Movie&order=update" to "Film Thailand",
        "$mainUrl/series/?country%5B%5D=united-states&status=&type=Movie&order=update" to "Film Barat",
        "$mainUrl/series/?country%5B%5D=south-korea&status=&type=Movie&order=update" to "Film Korea",
        "$mainUrl/series/?country%5B%5D=south-korea&status=&type=Drama&order=update" to "Series Korea",
        "$mainUrl/series/?country%5B%5D=japan&type=Drama&order=update" to "Series Jepang",
        "$mainUrl/series/?country%5B%5D=usa&type=Drama&order=update" to "Series Barat"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + "&page=$page").document
        val home = document.select("article.bs").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2[itemprop=headline]")?.text()?.trim() 
            ?: this.selectFirst(".tt")?.text()?.trim() 
            ?: return null
        val href = this.selectFirst("a[itemprop=url]")?.attr("href") ?: this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img[itemprop=image]")?.attr("src") ?: this.selectFirst("img")?.attr("src")
        val isMovie = href.contains("movie") || this.selectFirst(".typez")?.text()?.contains("Movie") == true
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.bs").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // If this is an episode page, find the series link and load that instead
        val seriesLink = document.selectFirst(".nvs a, .ts-breadcrumb li:nth-last-child(2) a")?.attr("href")
        if (seriesLink != null && seriesLink.contains("/series/")) {
            return load(seriesLink)
        }

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("img[itemprop=image]")?.attr("src") ?: document.selectFirst(".thumb img")?.attr("src")
        val tags = document.select(".genxed a").map { it.text() }
        val description = document.selectFirst(".entry-content p")?.text()?.trim()
        val year = document.select(".tsinfo .info-content").firstOrNull { it.previousElementSibling()?.text()?.contains("Year") == true }?.text()?.toIntOrNull()
        val isMovie = document.selectFirst(".tsinfo .type")?.text()?.contains("Movie") == true || url.contains("movie")

        val recommendations = document.select("div.bixbox .bs").mapNotNull {
            it.toSearchResult()
        }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            val episodes = document.select("div.eplister ul li").mapNotNull {
                val epHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epTitle = it.selectFirst(".epl-num")?.text() ?: it.selectFirst(".epl-title")?.text() ?: "Episode"
                newEpisode(epHref) {
                    this.name = epTitle
                }
            }.reversed()

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        
        // Find iframe directly if present
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        // Find servers encoded in Base64
        val servers = document.select(".mobius select option, .server_list li, .mirrors select option")
        servers.forEach { server ->
            val b64 = server.attr("value").takeIf { it.isNotBlank() } ?: server.attr("data-src")
            if (b64.isNotBlank() && b64.length > 20) {
                try {
                    val decoded = String(Base64.getDecoder().decode(b64))
                    val iframeSrc = Jsoup.parse(decoded).select("iframe").attr("src")
                    if (iframeSrc.startsWith("http")) {
                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Ignore decode errors
                }
            }
        }
        return true
    }
}
