package com.tnb88.filmfree

import java.net.URI

object MotHls {
    data class Reply(val code: Int, val text: String = "", val contentType: String = "", val url: String = "")

    fun firstMedia(playlist: String, base: String): String {
        require(playlist.trimStart().startsWith("#EXTM3U")) { "Không phải playlist HLS" }
        val raw = playlist.lineSequence().map(String::trim)
            .firstOrNull { it.isNotBlank() && !it.startsWith('#') }
            ?: error("Playlist chưa có video")
        val uri = URI(base).resolve(raw)
        require(uri.scheme in listOf("https", "http") && uri.userInfo == null) { "URL video không hợp lệ" }
        return uri.toString()
    }

    // A 200 playlist alone is not proof the CDN can deliver its video segments.
    suspend fun verify(initial: Reply, initialUrl: String, fetch: suspend (String, Boolean) -> Reply) {
        var reply = initial
        var url = initialUrl
        repeat(3) {
            require(reply.code == 200) { "Playlist trả HTTP ${reply.code}" }
            val next = firstMedia(reply.text, reply.url.ifBlank { url })
            if (!reply.text.contains("#EXT-X-STREAM-INF")) {
                val media = fetch(next, true)
                require(media.code in listOf(200, 206) &&
                    !media.contentType.contains("text/html", true) &&
                    !media.contentType.startsWith("image/", true) &&
                    !media.contentType.contains("json", true)) { "CDN video trả HTTP ${media.code}" }
                return
            }
            url = next
            reply = fetch(url, false)
        }
        error("Playlist chuyển hướng quá nhiều tầng")
    }
}
