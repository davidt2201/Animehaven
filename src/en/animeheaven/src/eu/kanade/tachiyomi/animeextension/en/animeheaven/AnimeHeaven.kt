package eu.kanade.tachiyomi.animeextension.en.animeheaven

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import keiyoushi.annotation.Source
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

@Source
abstract class AnimeHeaven : AnimeHttpSource() {
    override val supportsLatest = true
    override val disableRelatedAnimesBySearch = true

    private val metadataSemaphore = Semaphore(3)
    private val safetyCache = ConcurrentHashMap<String, Boolean>()

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/popular.php?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        return AnimesPage(parseAnimeCards(doc), hasNextPage(doc, 1))
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage =
        fetchAndFilter(popularAnimeRequest(page), page)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/new.php?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        return AnimesPage(parseAnimeCards(doc), hasNextPage(doc, 1))
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage =
        fetchAndFilter(latestUpdatesRequest(page), page)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return popularAnimeRequest(page)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/fastsearch.php?xhr=1&s=$encoded", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        return AnimesPage(parseAnimeCards(doc), false)
    }

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (query.isBlank()) return getPopularAnime(page)
        val response = client.newCall(searchAnimeRequest(page, query, filters)).await()
        if (!response.isSuccessful) {
            val status = response.code
            response.close()
            throw IOException("AnimeHeaven search failed: HTTP $status")
        }
        val doc = response.useAsJsoup()
        return AnimesPage(filterVisible(parseAnimeCards(doc)), false)
    }

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET(toAbsoluteUrl(anime.url), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.useAsJsoup()
        val path = response.request.url.encodedPath +
            response.request.url.encodedQuery?.let { "?$it" }.orEmpty()

        if (!doc.isAllowed()) {
            safetyCache[path] = false
            throw IOException("This title is unavailable under the source content filter.")
        }
        safetyCache[path] = true

        val title = doc.selectFirst("h1, .anime-title, #anime-title")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.title().substringBefore(" Anime").trim()

        return SAnime.create().apply {
            setUrlWithoutDomain(path)
            this.title = title
            thumbnail_url = doc.selectFirst("img.coverimg, img.anime-poster, img[class*=poster]")
                ?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") }.ifBlank { it.attr("src") } }
            description = doc.selectFirst("div.infodes.c, .infodes")?.text()?.trim()
            genre = tagLabels(doc).joinToString()
        }
    }

    override fun seasonListParse(response: Response): List<SAnime> =
        throw UnsupportedOperationException("AnimeHeaven does not expose seasons.")

    override fun episodeListParse(response: Response): List<SEpisode> =
        throw UnsupportedOperationException("Episode lists are read from the title page.")

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = fetchSafeAnimeDocument(anime.url)
        return doc.select("a:has(.watch2), a:has(div.watch2)")
            .mapNotNull { episodeFromElement(it, anime.url) }
            .distinctBy { it.url }
            .reversed()
    }

    private fun episodeFromElement(element: Element, animePath: String): SEpisode? {
        val episodeId = element.id().ifBlank { element.selectFirst(".watch2")?.id().orEmpty() }
        val number = element.selectFirst(".watch2")?.text()?.trim()?.toFloatOrNull() ?: return null
        if (episodeId.isBlank()) return null

        val encodedAnimePath = URLEncoder.encode(animePath, "UTF-8")
        return SEpisode.create().apply {
            setUrlWithoutDomain("/episode?anime=$encodedAnimePath&id=$episodeId")
            episode_number = number
            name = "Episode $number"
        }
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val episodeUrl = (baseUrl + episode.url).toHttpUrl()
        val animePath = episodeUrl.queryParameter("anime")
            ?: throw IOException("Missing title reference for this episode")
        val episodeId = episodeUrl.queryParameter("id")
            ?: throw IOException("Missing episode identifier")

        // Re-check before resolving any video source, including saved/direct episode links.
        fetchSafeAnimeDocument(animePath)

        val gateHeaders = headers.newBuilder()
            .set("Referer", toAbsoluteUrl(animePath))
            .add("Cookie", "key=$episodeId")
            .build()
        val response = client.newCall(GET("$baseUrl/gate.php", gateHeaders)).await()
        if (!response.isSuccessful) {
            val status = response.code
            response.close()
            throw IOException("AnimeHeaven video page failed: HTTP $status")
        }

        val doc = response.useAsJsoup()
        val videos = doc.select("video source, source").mapNotNull { element ->
            val url = element.absUrl("src").ifBlank { element.attr("src") }
            if (url.isBlank()) return@mapNotNull null
            Video(
                videoUrl = url,
                videoTitle = element.attr("title").ifBlank { "AnimeHeaven server" },
                headers = gateHeaders,
            )
        }
        return videos.toHosterList()
    }

    override fun hosterListParse(response: Response): List<Hoster> =
        throw UnsupportedOperationException("Hosters are resolved directly.")

    override suspend fun getVideoList(hoster: Hoster): List<Video> =
        hoster.videoList.orEmpty()

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> =
        throw UnsupportedOperationException("Videos are attached to their hoster.")

    override fun relatedAnimeListParse(response: Response): List<SAnime> = emptyList()

    private suspend fun fetchAndFilter(request: Request, page: Int): AnimesPage {
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            val status = response.code
            response.close()
            throw IOException("AnimeHeaven catalogue failed: HTTP $status")
        }
        val doc = response.useAsJsoup()
        return AnimesPage(filterVisible(parseAnimeCards(doc)), hasNextPage(doc, page))
    }

    private suspend fun filterVisible(entries: List<SAnime>): List<SAnime> = coroutineScope {
        entries.map { anime ->
            async { if (isAnimeSafe(anime.url)) anime else null }
        }.awaitAll().filterNotNull()
    }

    private suspend fun isAnimeSafe(path: String): Boolean {
        safetyCache[path]?.let { return it }
        return metadataSemaphore.withPermit {
            safetyCache[path]?.let { return@withPermit it }
            try {
                val response = client.newCall(GET(toAbsoluteUrl(path), headers)).await()
                if (!response.isSuccessful) {
                    response.close()
                    return@withPermit false
                }
                val allowed = response.useAsJsoup().isAllowed()
                safetyCache[path] = allowed
                allowed
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Unknown metadata or network errors fail closed.
                false
            }
        }
    }

    private suspend fun fetchSafeAnimeDocument(path: String): Document {
        if (safetyCache[path] == false) {
            throw IOException("This title is unavailable under the source content filter.")
        }
        val response = client.newCall(GET(toAbsoluteUrl(path), headers)).await()
        if (!response.isSuccessful) {
            val status = response.code
            response.close()
            throw IOException("AnimeHeaven title page failed: HTTP $status")
        }
        val doc = response.useAsJsoup()
        if (!doc.isAllowed()) {
            safetyCache[path] = false
            throw IOException("This title is unavailable under the source content filter.")
        }
        safetyCache[path] = true
        return doc
    }

    private fun Document.isAllowed(): Boolean {
        val tags = tagLabels(this)
        val ratings = select("[data-rating], [class*=content-rating], [class*=age-rating], meta[name*=rating]")
            .mapNotNull { element ->
                element.attr("data-rating").takeIf { it.isNotBlank() }
                    ?: element.attr("content").takeIf { it.isNotBlank() }
                    ?: element.text().takeIf { it.isNotBlank() }
            }

        // Without a site-provided classification, do not show or resolve the title.
        if (tags.isEmpty() && ratings.isEmpty()) return false
        return !ContentFilter.isExplicit(tags, ratings)
    }

    private fun tagLabels(doc: Document): List<String> =
        doc.select("a[href*=tags.php]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun parseAnimeCards(doc: Document): List<SAnime> =
        doc.select("a.ac, a:has(img.coverimg), a[href*=anime.php]")
            .mapNotNull { element ->
                val href = element.absUrl("href").ifBlank { element.attr("href") }
                if (!href.contains("anime.php", ignoreCase = true)) return@mapNotNull null

                val image = element.selectFirst("img.coverimg, img")
                val title = element.selectFirst(".fastname, .anime-title, .title, h2, h3")
                    ?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: image?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                    ?: element.text().trim()
                if (title.isBlank()) return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    this.title = title
                    thumbnail_url = image?.let {
                        it.absUrl("src").ifBlank { it.absUrl("data-src") }.ifBlank { it.attr("src") }
                    }
                }
            }.distinctBy { it.url }

    private fun hasNextPage(doc: Document, page: Int): Boolean =
        doc.select("a[rel=next], a.next, .pagination a").any { link ->
            link.attr("rel").equals("next", true) ||
                link.hasClass("next") ||
                link.text().trim().equals("next", true) ||
                link.text().trim() == (page + 1).toString()
        }

    private fun toAbsoluteUrl(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path
        else "$baseUrl/${path.trimStart('/')}"
}
