package com.anhdaden

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import okio.ByteString.Companion.encodeUtf8

class YanPlayerDataTest {
    @Test fun resolvesServerUrlsAndSkipsInvalidSchemes() {
        val doc = Jsoup.parse("""<div id='list_sv'><a data-src='//cdn.example/a.m3u8'>1080</a>
            <a data-src='/mirror'>4K</a><a data-src='javascript:alert(1)'>bad</a>
            <a data-src='//cdn.example/a.m3u8'>duplicate</a></div>""", "https://site.example/show/episode")
        assertEquals(listOf("https://cdn.example/a.m3u8", "https://site.example/mirror"), YanPlayerData.servers(doc).map { it.second })
    }
    @Test fun choosesNativePlainPlaylistNotEncryptedEndpoint() {
        val config = """{"pU":"/stream-plain?t=server-token","sU":"/encrypted-stream"}""".encodeUtf8().base64()
        assertEquals("https://cdn.example/stream-plain?t=server-token",
            YanPlayerData.nativePlaylist("<div id='player' data-obf='$config'></div>", "https://cdn.example/player"))
    }
    @Test fun rejectsMalformedOrMissingNativeConfig() {
        for (value in listOf("not base64", "{}".encodeUtf8().base64(), """{"pU":"javascript:alert(1)"}""".encodeUtf8().base64()))
            assertEquals("", YanPlayerData.nativePlaylist("<div id='player' data-obf='$value'></div>", "https://cdn.example/"))
        assertEquals("", YanPlayerData.nativePlaylist("<html></html>", "https://cdn.example/"))
    }
    private data class Reply(val code: Int, val url: String, val bytes: ByteArray) {
        val text get() = bytes.toString(Charsets.UTF_8)
    }
    private fun get(url: String, referer: String, range: Boolean = false): Reply {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000; conn.readTimeout = 12000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.setRequestProperty("Referer", referer)
        if (range) conn.setRequestProperty("Range", "bytes=0-1023")
        return try {
            val code = conn.responseCode
            val bytes = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readNBytes(if (range) 1024 else 2_000_000) } ?: byteArrayOf()
            Reply(code, conn.url.toString(), bytes)
        } finally { conn.disconnect() }
    }
    @Test fun liveTwoEpisodesHaveNativePlaylistAndVideoData() {
        assumeTrue(System.getProperty("live") == "true")
        for (show in listOf("tien-nghich", "gia-thien")) {
            val detail = get("https://yanhh3d.baby/$show", "https://yanhh3d.baby/")
            assertEquals(200, detail.code)
            val doc = Jsoup.parse(detail.text, detail.url)
            val episode = doc.select("a[href]").map { YanPlayerData.absolute(it.attr("href"), detail.url) }
                .first { URI(it.ifBlank { "https://invalid/" }).path.startsWith("/$show/tap-") }
            val page = get(episode, detail.url)
            val servers = YanPlayerData.servers(Jsoup.parse(page.text, page.url))
            assertTrue(servers.size >= 2)
            var ready = false
            for ((_, source) in servers) {
                try {
                    val player = get(source, page.url)
                    val url = YanPlayerData.nativePlaylist(player.text, player.url)
                    if (url.isBlank()) continue
                    val playlist = get(url, player.url)
                    if (playlist.code != 200 || !playlist.text.startsWith("#EXTM3U")) continue
                    val segment = playlist.text.lineSequence().map(String::trim).first { it.isNotBlank() && !it.startsWith('#') }
                    val result = get(URI(playlist.url).resolve(segment).toString(), player.url, true)
                    if (result.code in listOf(200,206) && result.bytes.isNotEmpty()) { ready = true; break }
                } catch (_: Exception) { }
            }
            assertTrue("Native video segment for $show", ready)
        }
    }
}
