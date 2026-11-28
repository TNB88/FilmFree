package com.anhdaden

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.tnb88.filmfree.*
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup

class MotChillProvider : MainAPI() {
    override var mainUrl = MotProtocol.DEFAULT_DOMAIN
    override var name = "MotChill"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val mainPage = mainPageOf(
        "" to "Phim Mới", "movie_type=phim-le" to "Phim Lẻ", "movie_type=phim-bo" to "Phim Bộ",
        "movie_type=phim-chieu-rap" to "Phim Chiếu Rạp", "genre=hoat-hinh" to "Hoạt Hình")
    private val domain = RemoteDomain("domainmotchill.txt", MotProtocol.DEFAULT_DOMAIN)
    private val catalog = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, JsonNode>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonNode>?) = size > 256
    })

    private suspend fun api(path: String): JsonNode {
        var problem = "Không kết nối được API"
        for (base in MotProtocol.candidates(domain.resolve())) {
            try {
                // Do not multiply retries across two aliases of the same server.
                val response = app.get("$base/api/hub/$path", headers = StreamResolver.headers, timeout = 10)
                require(response.code == 200) { "API trả HTTP ${response.code}" }
                val root = Protocol.json(response.text)
                when {
                    path.startsWith("movies?") || path.startsWith("search?") -> MotProtocol.items(root)
                    path.endsWith("/episodes") -> MotProtocol.episodes(root)
                    else -> require(MotProtocol.movieId(root).isNotBlank()) { "API thiếu mã phim" }
                }
                // Follow the final API origin after a domain migration. Otherwise
                // cards and player Referer still point at the retired domain.
                mainUrl = MotProtocol.apiOrigin(response.url)
                return root
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                domain.reject(base)
                problem = "API ${path.substringBefore('?')}: ${error.message ?: "không phản hồi"}"
            }
        }
        throw ErrorLoadingException("MotChill: $problem")
    }

    private fun result(item: JsonNode): SearchResponse? {
        val title = Protocol.text(item, "title", "name")
        val slug = Protocol.text(item, "slug")
        if (title.isBlank() || slug.isBlank()) return null
        if (MotProtocol.movieId(item).isNotBlank()) catalog[slug] = item
        val poster = Protocol.text(item, "poster_url", "poster_url_b2", "thumb_url").ifBlank { null }
        val year = Protocol.text(item, "year").toIntOrNull()
        return if (Protocol.series(item)) newTvSeriesSearchResponse(title, "$mainUrl/phim/$slug", TvType.TvSeries) {
            posterUrl = poster; this.year = year
            episodes = Protocol.text(item, "episode_current").toIntOrNull()
        } else newMovieSearchResponse(title, "$mainUrl/phim/$slug", TvType.Movie) {
            posterUrl = poster; this.year = year
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val root = api("movies?${request.data}&page=${page.coerceAtLeast(1)}&page_size=36&sort=newest")
        val items = MotProtocol.items(root).mapNotNull(::result).distinctBy { it.url }
        return newHomePageResponse(request.name, items, MotProtocol.hasNext(root, page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val root = api("search?q=${Protocol.encoded(query.trim())}&page=1&page_size=40")
        return MotProtocol.items(root).mapNotNull(::result).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = Protocol.slug(url)
        // The catalog already supplies the numeric ID and full display metadata.
        // Avoid a redundant detail request to an intermittently timing-out route.
        val movie = catalog[slug] ?: api("movies/${Protocol.encoded(slug)}").also { catalog[slug] = it }
        val id = MotProtocol.movieId(movie)
        require(id.matches(Regex("\\d+"))) { "MotChill thiếu mã phim" }
        val root = api("movies/$id/episodes")
        val episodes = MotProtocol.episodes(root)
        val title = Protocol.text(movie, "title")
        require(title.isNotBlank()) { "MotChill thiếu tên phim" }
        val canonical = "$mainUrl/phim/$slug"
        val poster = Protocol.text(movie, "poster_url", "poster_url_b2", "thumb_url").ifBlank { null }
        val response = if (Protocol.series(movie) || episodes.size > 1) {
            newTvSeriesLoadResponse(title, canonical, TvType.TvSeries, episodes.map { ep ->
                newEpisode(MotProtocol.saved(slug, ep, id)) {
                    name = Protocol.text(ep, "episode_label", "slug")
                    episode = Protocol.text(ep, "order_index").toIntOrNull()
                    posterUrl = poster
                }
            }) {
                showStatus = if (Protocol.text(movie, "status") == "completed") ShowStatus.Completed else ShowStatus.Ongoing
            }
        } else newMovieLoadResponse(title, canonical, TvType.Movie,
            episodes.firstOrNull()?.let { MotProtocol.saved(slug, it, id) } ?: "{}")
        response.posterUrl = poster
        response.backgroundPosterUrl = Protocol.text(movie, "backdrop_url", "thumb_url").ifBlank { null }
        response.plot = Jsoup.parse(Protocol.text(movie, "description")).text()
        response.year = Protocol.text(movie, "year").toIntOrNull()
        response.duration = Regex("\\d+").find(Protocol.text(movie, "duration"))?.value?.toIntOrNull()
        response.tags = Protocol.array(movie.path("genres")).map { Protocol.text(it, "name") }.filter(String::isNotBlank)
        response.actors = Protocol.array(movie.path("actors")).mapNotNull {
            Protocol.text(it, "name").takeIf(String::isNotBlank)?.let { name -> ActorData(Actor(name)) }
        }
        return response
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val saved = runCatching { Protocol.json(data) }.getOrNull()
        val sources = if (saved != null && saved.isObject && saved.has("movieSlug")) {
            val slug = Protocol.slug(Protocol.text(saved, "movieSlug"))
            val id = MotProtocol.movieId(saved).ifBlank {
                MotProtocol.movieId(catalog[slug] ?: api("movies/${Protocol.encoded(slug)}"))
            }
            require(id.matches(Regex("\\d+"))) { "MotChill thiếu mã phim" }
            val fresh = MotProtocol.episodes(api("movies/$id/episodes"))
                .firstOrNull { Protocol.text(it, "slug") == Protocol.text(saved, "episodeSlug") }
                ?: throw ErrorLoadingException("Tập phim đã đổi; hãy mở lại danh sách tập")
            MotProtocol.sources(fresh, "$mainUrl/xem-phim/$slug")
        } else Protocol.savedSources(data)
        var found = false
        var error: Exception? = null
        for (source in sources) {
            try { if (StreamResolver.resolve(source, name, subtitleCallback, callback)) found = true }
            catch (cancel: CancellationException) { throw cancel }
            catch (problem: Exception) { error = problem }
        }
        if (!found) throw ErrorLoadingException(error?.message ?: "MotChill chưa có nguồn phát cho tập này")
        return true
    }
}
