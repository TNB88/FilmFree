package com.anhdaden

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import okio.ByteString.Companion.decodeBase64
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

object YanPlayerData {
    fun absolute(raw: String, base: String): String = runCatching {
        if (raw.isBlank()) return@runCatching ""
        val uri = URI(base).resolve(raw.trim())
        if (uri.scheme in listOf("https", "http") && uri.host != null && uri.rawUserInfo == null) uri.toString() else ""
    }.getOrDefault("")

    fun servers(doc: Document): List<Pair<String, String>> = doc.select("#list_sv a[data-src]").mapNotNull {
        val url = absolute(it.attr("data-src"), doc.location())
        if (url.isBlank()) null else it.text().trim().ifBlank { "Nguồn" } to url
    }.distinctBy { it.second }

    fun nativePlaylist(html: String, base: String): String {
        val encoded = Jsoup.parse(html, base).selectFirst("#player[data-obf]")?.attr("data-obf") ?: return ""
        val payload = encoded.decodeBase64()?.utf8() ?: return ""
        val root = runCatching { ObjectMapper().readTree(payload) }.getOrNull() ?: return ""
        // pU is the native, plain-HLS URL supplied by the player. sU is not a
        // plain playlist and must not be emitted as a native video URL.
        return absolute(root.path("pU").asText(""), base)
    }

    fun quality(label: String): Int = when {
        label.contains("4K", true) || label.contains("2160") -> 2160
        label.contains("1080") -> 1080
        label.contains("720") -> 720
        else -> Qualities.Unknown.value
    }
}

object YanLinkFix {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    @JvmStatic
    suspend fun load(data: String, domain: String, callback: (ExtractorLink) -> Unit,
        subtitles: (SubtitleFile) -> Unit): Boolean {
        val target = YanPlayerData.absolute(data, domain.trimEnd('/') + "/")
        require(target.isNotBlank()) { "YanHH3D: địa chỉ tập phim không hợp lệ" }
        val page = app.get(target, headers = mapOf("User-Agent" to UA), referer = domain + "/", timeout = 15)
        require(page.code == 200) { "YanHH3D: trang tập phim trả HTTP ${page.code}" }
        val doc = Jsoup.parse(page.text, page.url)
        var found = false
        for ((label, source) in YanPlayerData.servers(doc)) {
            try {
                val response = app.get(source, headers = mapOf("User-Agent" to UA), referer = page.url, timeout = 12)
                if (response.code != 200) continue
                val playlist = if (response.text.trimStart().startsWith("#EXTM3U")) response.url
                    else YanPlayerData.nativePlaylist(response.text, response.url)
                if (playlist.isBlank()) continue
                val check = if (playlist == response.url) response else app.get(playlist,
                    headers = mapOf("User-Agent" to UA), referer = response.url, timeout = 12)
                if (check.code != 200 || !check.text.trimStart().startsWith("#EXTM3U")) continue
                callback(newExtractorLink("YanHH3D", "YanHH3D $label", check.url, ExtractorLinkType.M3U8) {
                    referer = response.url
                    quality = YanPlayerData.quality(label)
                    headers = mapOf("User-Agent" to UA)
                })
                found = true
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* A broken mirror must not hide the other sources. */ }
        }
        if (found) return true
        // Preserve the existing extractor for older host types.
        invokeSource(data, domain, callback, subtitles)
        return true
    }
}
