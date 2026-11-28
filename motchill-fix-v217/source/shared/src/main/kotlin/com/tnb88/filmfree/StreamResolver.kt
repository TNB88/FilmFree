package com.tnb88.filmfree

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import okio.ByteString.Companion.decodeBase64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.lagradost.cloudstream3.network.CloudflareKiller

object StreamResolver {
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    val headers = mapOf("User-Agent" to UA)
    private val cloudflare by lazy { CloudflareKiller() }
    fun direct(url: String) = Regex("(?i)\\.(m3u8|mp4)(?:[?#]|$)").containsMatchIn(url)
    fun sourceArray(document: Document, variable: String): List<String> {
        val script = document.select("script").joinToString("\n") { it.data() }
        val value = Regex("(?:var|let|const)\\s+${Regex.escape(variable)}\\s*=\\s*(\\[[^;]*?])\\s*;",
            RegexOption.DOT_MATCHES_ALL).find(script)?.groupValues?.get(1) ?: return emptyList()
        return runCatching { Protocol.array(Protocol.json(value)).map { it.asText() } }.getOrDefault(emptyList())
    }

    private suspend fun emit(source: MediaSource, label: String, url: String,
        referer: String, callback: (ExtractorLink) -> Unit) {
        val origin = runCatching { URI(referer).let { "${it.scheme}://${it.rawAuthority}" } }.getOrDefault("")
        callback(newExtractorLink(label, "$label - ${source.name}", url,
            if (url.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
            this.referer = referer
            headers = StreamResolver.headers + source.headers + if (origin.isNotBlank()) mapOf("Origin" to origin) else emptyMap()
            quality = Qualities.Unknown.value
        })
    }

    suspend fun resolve(source: MediaSource, label: String,
        subtitle: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, depth: Int = 0): Boolean {
        require(depth <= 3) { "Nguồn phát chuyển hướng quá nhiều lần" }
        val url = source.link
        val requestHeaders = headers + source.headers
        val playerInterceptor = if (runCatching { URI(url).host?.endsWith(".streamc.xyz") == true }.getOrDefault(false)) cloudflare else null
        require(url.startsWith("http://") || url.startsWith("https://")) { "Link phát không hợp lệ" }
        if (direct(url)) { emit(source, label, url, source.referer, callback); return true }
        val response = Http.get(url, headers = requestHeaders, referer = source.referer, timeout = 15, interceptor = playerInterceptor)
        require(response.code == 200) { "Máy chủ phát trả HTTP ${response.code}" }
        if (response.text.trimStart().startsWith("#EXTM3U")) {
            emit(source, label, response.url, source.referer, callback); return true
        }
        val document = response.document
        var found = false
        val directSources = sourceArray(document, "all_sources") +
            document.select("video[src], video source[src]").map { it.attr("src") } +
            document.select("[data-link]").map { it.attr("data-link") }.filter(::direct)
        directSources.distinct().forEach { raw ->
            val link = Protocol.absolute(raw, response.url)
            if (direct(link)) { emit(source, label, link, response.url, callback); found = true }
        }
        sourceArray(document, "all_subtitles").filter(String::isNotBlank).forEach {
            subtitle(newSubtitleFile("Tiếng Việt", Protocol.absolute(it, response.url)))
        }
        document.select("track[src]").forEach {
            subtitle(newSubtitleFile(it.attr("label").ifBlank { it.attr("srclang") }.ifBlank { "Phụ đề" },
                Protocol.absolute(it.attr("src"), response.url)))
        }
        if (found) return true

        val embedScript = document.select("script").firstOrNull { it.data().contains("window.__EMBED_DATA__") }?.data()
        if (embedScript != null) {
            val payload = embedScript.substringAfter("window.__EMBED_DATA__").substringAfter('=')
            val config = Protocol.json(payload)
            val token = Protocol.text(config, "embedToken")
            val siteId = Protocol.text(config, "siteId")
            val sourceId = Protocol.text(config, "videoSourceId")
            require(token.isNotBlank() && siteId.matches(Regex("\\d+")) && sourceId.matches(Regex("\\d+"))) {
                "MotChill chưa cấp thông tin phát hợp lệ"
            }
            // Native-HLS endpoint used by the site's own player. Tokens are provided by the server.
            val host = URI(response.url).let { "${it.scheme}://${it.rawAuthority}" }
            for (tier in listOf("cdn", "m3u8", "b2")) {
                if (!config.path("availability").path("has_$tier").asBoolean(false)) continue
                val stream = "$host/embed/$siteId/$sourceId/stream?tier=$tier&native=1&embed_token=${Protocol.encoded(token)}"
                val playlist = Http.get(stream, headers = requestHeaders, referer = response.url, timeout = 15)
                if (playlist.code == 200 && playlist.text.trimStart().startsWith("#EXTM3U")) {
                    emit(source, label, stream, response.url, callback)
                    return true
                }
            }
            throw ErrorLoadingException("Máy chủ MotChill chưa trả playlist phát được")
        }

        val bootstrap = document.selectFirst("#stream-bootstrap")?.data().orEmpty()
        if (bootstrap.isNotBlank()) {
            var config = Protocol.json(bootstrap)
            val api = Protocol.absolute(Protocol.text(config, "api").ifBlank { response.url }, response.url)
            // This is the site's native-HLS bootstrap request, not a fabricated token.
            // Access-denied/challenge responses are never treated as successful playback.
            if (!config.path("preissued").isObject) {
                val origin = URI(response.url).let { "${it.scheme}://${it.rawAuthority}" }
                val frameOrigin = runCatching { URI(source.referer).let { "${it.scheme}://${it.rawAuthority}" } }.getOrDefault("")
                val body = Protocol.mapper.writeValueAsString(mapOf(
                    "action" to "bootstrap", "referrer" to source.referer,
                    "frame_origins" to listOf(frameOrigin).filter(String::isNotBlank),
                    "request_grant" to true, "playlist_format" to "hls", "pretty_url" to true,
                    "path_chunks" to true, "bootstrap_format" to "json"))
                val result = app.post(api, headers = requestHeaders + mapOf("Content-Type" to "application/json", "Origin" to origin),
                    referer = response.url, requestBody = body.toRequestBody("application/json".toMediaType()), timeout = 15, interceptor = playerInterceptor)
                require(result.code == 200) { "Máy chủ phát trả HTTP ${result.code}" }
                config = Protocol.json(result.text)
            }
            val playlist = Protocol.text(config.path("preissued"), "playlist")
            require(!config.path("turnstileEnabled").asBoolean(false)) { "Nguồn này yêu cầu xác minh trên trình phát gốc" }
            require(playlist.isNotBlank()) { "Máy chủ chưa cấp link HLS cho phim này" }
            emit(source, label, Protocol.absolute(playlist, api), response.url, callback)
            return true
        }

        // Legacy, non-DRM player configuration supplied inline by Nguồn C.
        val encoded = document.selectFirst("#player[data-obf]")?.attr("data-obf").orEmpty()
        if (encoded.isNotBlank()) {
            val config = Protocol.json(encoded.decodeBase64()?.utf8() ?: throw ErrorLoadingException("Cấu hình player không hợp lệ"))
            val raw = Protocol.text(config, "sUb")
            if (raw.isNotBlank()) {
                // Legacy encrypted manifests require the old host extractor; do not emit an unusable URL.
                var emitted = false
                loadExtractor(url, source.referer, subtitle) { emitted = true; callback(it) }
                if (emitted) return true
            }
        }
        val frames = document.select("#player iframe[src], .player iframe[src], iframe#iframeStream, iframe[allowfullscreen], iframe[src*=embed], iframe[src*=player]").map {
            Protocol.absolute(it.attr("src").ifBlank { it.attr("data-src") }, response.url)
        }.filter { it.isNotBlank() && it != url }.distinct()
        for (frame in frames) {
            if (resolve(source.copy(link = frame, referer = response.url), label, subtitle, callback, depth + 1)) found = true
        }
        if (found) return true
        var emitted = false
        loadExtractor(url, source.referer, subtitle) { emitted = true; callback(it) }
        return emitted
    }
}
