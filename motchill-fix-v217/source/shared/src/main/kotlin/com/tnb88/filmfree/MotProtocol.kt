package com.tnb88.filmfree

import com.fasterxml.jackson.databind.JsonNode
import java.net.URI

object MotProtocol {
    const val DEFAULT_DOMAIN = "https://motchill.win"
    // A remote domain must serve the same hub API, not merely use the MotChill name.
    fun candidates(configured: String): List<String> =
        listOf(configured, DEFAULT_DOMAIN, "https://motchill.show").distinct()

    fun apiOrigin(finalUrl: String): String {
        val uri = URI(finalUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawPath.startsWith("/api/hub/")) { "Tên miền chuyển hướng không còn là API MotChill" }
        return "${uri.scheme}://${uri.rawAuthority}"
    }

    fun items(root: JsonNode): List<JsonNode> {
        require(root.isObject && root.path("items").isArray) { "MotChill không trả danh sách phim hợp lệ" }
        return Protocol.array(root.path("items"))
    }
    fun hasNext(root: JsonNode, requested: Int): Boolean {
        val current = root.path("page").asInt(1)
        val size = root.path("page_size").asInt(36)
        val total = root.path("total").asInt(0)
        return items(root).isNotEmpty() && current == requested && size > 0 && current.toLong() * size < total
    }
    fun episodes(root: JsonNode): List<JsonNode> {
        require(root.isArray) { "MotChill không trả danh sách tập hợp lệ" }
        return Protocol.array(root).filter { Protocol.text(it, "slug").isNotBlank() }
            .sortedBy { it.path("order_index").asInt(Int.MAX_VALUE) }
    }
    fun movieId(node: JsonNode): String = Protocol.text(node, "raw_movie_id", "movieId", "id")
        .takeIf { it.matches(Regex("\\d+")) }.orEmpty()
    fun saved(movieSlug: String, episode: JsonNode, movieId: String = ""): String =
        Protocol.mapper.writeValueAsString(mapOf("movieSlug" to movieSlug,
            "episodeSlug" to Protocol.text(episode, "slug"), "movieId" to movieId))
    fun sources(episode: JsonNode, referer: String): List<MediaSource> =
        Protocol.array(episode.path("sources")).mapNotNull { source ->
            val link = Protocol.text(source, "play_url", "embed_url")
            if (link.isBlank()) null else MediaSource(Protocol.text(source, "language_name").ifBlank { "MotChill" }, link, referer)
        }.distinctBy { it.link }
}
