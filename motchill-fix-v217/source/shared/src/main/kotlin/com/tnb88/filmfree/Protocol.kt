package com.tnb88.filmfree

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

data class MediaSource(val name: String, val link: String, val referer: String = "", val headers: Map<String, String> = emptyMap())
data class FilmEpisode(val name: String, val number: Int?, val sources: List<MediaSource>)

/** Pure protocol code: shared by the plugins and JVM regression tests. */
object Protocol {
    val mapper = ObjectMapper()
    fun json(text: String): JsonNode = mapper.readTree(text)
    fun text(node: JsonNode?, vararg keys: String): String = keys.firstNotNullOfOrNull {
        node?.get(it)?.takeUnless { value -> value.isNull || value.isContainerNode }?.asText()?.trim()
            ?.takeIf(String::isNotBlank)
    }.orEmpty()
    fun array(node: JsonNode?): List<JsonNode> = node?.takeIf { it.isArray }?.toList().orEmpty()
    fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun absolute(raw: String, base: String): String = runCatching {
        val value = raw.trim().replace("&amp;", "&")
        if (value.isBlank()) "" else URI(base.trimEnd('/') + "/").resolve(value).toString()
            .takeIf { URI(it).scheme in listOf("https", "http") }.orEmpty()
    }.getOrDefault("")

    fun apiRoot(raw: String, sourceC: Boolean): String? = runCatching {
        val uri = URI(raw.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        var path = uri.path.orEmpty().trimEnd('/')
        path = if (sourceC) path.replace(Regex("/api(?:/films?|/films?/.*)?$"), "")
            else path.replace(Regex("/v1/api(?:/.*)?$"), "")
        require(path.isEmpty())
        "https://${uri.rawAuthority}"
    }.getOrNull()

    fun items(root: JsonNode): List<JsonNode> {
        require(root.isObject && root.path("status").asText() !in listOf("false", "error")) {
            "Máy chủ không trả dữ liệu phim hợp lệ"
        }
        val list = root.get("items") ?: root.path("data").get("items")
        require(list != null && list.isArray) { "Máy chủ trả trang web thay vì danh sách API" }
        return array(list)
    }

    fun hasNext(root: JsonNode, requested: Int, count: Int): Boolean {
        val page = root.get("paginate") ?: root.get("pagination") ?: root.path("data").path("params").path("pagination")
        val current = text(page, "current_page", "currentPage").toIntOrNull()
        val total = text(page, "total_page", "totalPages").toIntOrNull()
        return count > 0 && (current == null || current == requested) && (total == null || requested < total)
    }

    fun slug(raw: String): String {
        val value = raw.trim()
        if (value.startsWith("{")) {
            val node = json(value)
            return slug(text(node, "slug", "url", "link", "data"))
        }
        return runCatching { URI(value).path }.getOrNull().orEmpty().ifBlank { value }
            .substringBefore('?').trim('/').substringAfterLast('/').removeSuffix(".html")
            .also { require(it.matches(Regex("[\\p{L}\\p{N}_-]+"))) { "Đường dẫn phim không hợp lệ" } }
    }

    fun image(item: JsonNode, root: JsonNode, sourceC: Boolean, base: String, poster: Boolean = true): String {
        val raw = if (poster) text(item, "poster_url_webp", "poster_url", "thumb_url_webp", "thumb_url")
            else text(item, "thumb_url_webp", "thumb_url", "poster_url_webp", "poster_url")
        if (raw.isBlank()) return ""
        if (raw.startsWith("https://") || raw.startsWith("http://")) return raw
        if (raw.startsWith("//")) return "https:$raw"
        val cdn = text(root.path("data"), "APP_DOMAIN_CDN_IMAGE")
            .ifBlank { text(root, "APP_DOMAIN_CDN_IMAGE") }.ifBlank { if (sourceC) base else "https://phimimg.com" }
        val path = if (!sourceC && !raw.contains('/')) "/uploads/movies/$raw" else raw
        return absolute(path, cdn)
    }

    fun series(item: JsonNode, episodes: List<FilmEpisode> = emptyList()): Boolean {
        val type = text(item, "type")
        if (type == "single") return false
        val total = text(item, "total_episodes", "episode_total").toIntOrNull()
        if (type in listOf("series", "tvshows") || total?.let { it > 1 } == true || episodes.size > 1) return true
        if (total == 1) return false
        val current = text(item, "current_episode", "episode_current")
        return current.contains(Regex("(?i)tập\\s*\\d+")) ||
            Regex("/(\\d+)").find(current)?.groupValues?.get(1)?.toIntOrNull()?.let { it > 1 } == true
    }

    fun episodes(movie: JsonNode, root: JsonNode): List<FilmEpisode> {
        val servers = array(root.get("episodes")).ifEmpty { array(movie.get("episodes")) }
        val groups = linkedMapOf<String, Pair<String, MutableList<MediaSource>>>()
        servers.forEach { server ->
            val serverName = text(server, "server_name").ifBlank { "Nguồn phát" }
            array(server.get("server_data") ?: server.get("items")).forEach { ep ->
                val title = text(ep, "name", "slug").ifBlank { "Full" }
                val num = Regex("\\d+").find(title)?.value?.toIntOrNull()
                val key = num?.toString() ?: title.lowercase(Locale.ROOT)
                val source = text(ep, "link_m3u8", "m3u8", "link_mp4", "link_embed", "embed")
                if (source.isNotBlank()) {
                    groups.getOrPut(key) { title to mutableListOf() }.second
                        .add(MediaSource(serverName, source))
                }
            }
        }
        return groups.map { (key, pair) -> FilmEpisode(pair.first, key.toIntOrNull(), pair.second.distinctBy { it.link }) }
            .sortedWith(compareBy({ it.number ?: Int.MAX_VALUE }, { it.name }))
    }

    fun savedSources(data: String): List<MediaSource> {
        if (data.startsWith("http")) return listOf(MediaSource("Nguồn phát", data))
        val root = json(data)
        return array(root).mapNotNull {
            val link = text(it, "link", "url", "data", "embed")
            if (link.isBlank()) null else MediaSource(text(it, "name", "server").ifBlank { "Nguồn phát" }, link, text(it, "referer"))
        }
    }
}
