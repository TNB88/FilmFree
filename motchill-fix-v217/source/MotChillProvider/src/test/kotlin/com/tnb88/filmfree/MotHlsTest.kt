package com.tnb88.filmfree

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MotHlsTest {
    @Test fun relativeSegmentUsesPlaylistDirectoryNotAnExtraPathComponent() {
        assertEquals("https://cdn.test/path/seg.ts?key=1",
            MotHls.firstMedia("#EXTM3U\n#EXTINF:6,\nseg.ts?key=1", "https://cdn.test/path/master.m3u8?token=x"))
    }
    @Test fun masterPlaylistAndByteRangeSegmentAreChecked() = runBlocking {
        val calls = mutableListOf<Pair<String, Boolean>>()
        MotHls.verify(MotHls.Reply(200,"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\n720/index.m3u8"),
            "https://cdn.test/root/master.m3u8") { url, range ->
            calls += url to range
            if (range) MotHls.Reply(206, contentType="video/mp2t")
            else MotHls.Reply(200,"#EXTM3U\n#EXTINF:6,\nfirst.ts")
        }
        assertEquals(listOf("https://cdn.test/root/720/index.m3u8" to false,
            "https://cdn.test/root/720/first.ts" to true), calls)
    }
    @Test fun brokenSegmentIsRejectedEvenWhenPlaylistIs200() = runBlocking {
        for (reply in listOf(MotHls.Reply(403), MotHls.Reply(200, contentType="text/html"),
            MotHls.Reply(206, contentType="image/png"))) {
            try {
                MotHls.verify(MotHls.Reply(200,"#EXTM3U\n#EXTINF:6,\nfirst.ts"), "https://cdn.test/a.m3u8") { _, _ -> reply }
                fail("Broken CDN must not be emitted")
            } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun movieIdSurvivesSavedEpisodeWithoutExpiringUrls() {
        val ep = Protocol.json("""{"slug":"tap-1","sources":[{"embed_url":"https://test/?sig=expired"}]}""")
        val saved = MotProtocol.saved("movie", ep, "1234")
        assertEquals("1234", MotProtocol.movieId(Protocol.json(saved)))
        assertFalse(saved.contains("sig="))
        assertEquals("1234", MotProtocol.movieId(Protocol.json("""{"id":1234}""")))
        assertEquals("", MotProtocol.movieId(Protocol.json("""{"movieId":"../../bad"}""")))
    }
}
