package com.tnb88.filmfree

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MotDomainTest {
    @Test fun configuredDomainIsPreferredAndFallbackIsIndependent() {
        assertEquals(listOf("https://new.test", "https://motchill.win", "https://motchill.show"),
            MotProtocol.candidates("https://new.test"))
        assertEquals(2, MotProtocol.candidates("https://motchill.win").size)
    }
    @Test fun apiRedirectSetsCanonicalOrigin() {
        assertEquals("https://new.test", MotProtocol.apiOrigin("https://new.test/api/hub/movies?page=1"))
        assertEquals("https://new.test:8443", MotProtocol.apiOrigin("https://new.test:8443/api/hub/movies/a"))
    }
    @Test fun redirectsToHomeOrUnsafeUrlsAreNotApiSuccess() {
        for (url in listOf("https://new.test/", "http://new.test/api/hub/movies", "https://user:pass@new.test/api/hub/movies")) {
            try { MotProtocol.apiOrigin(url); fail("Must reject $url") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun currentWinListAndDetailMatchHubContract() {
        val dir = File(requireNotNull(System.getProperty("fixtures")))
        val root = Protocol.json(File(dir, "motWinList.json").readText())
        val items = MotProtocol.items(root)
        assertEquals(2, items.size)
        assertTrue(MotProtocol.hasNext(root, 1))
        val movie = Protocol.json(File(dir, "motWinDetail.json").readText())
        assertEquals("32885", Protocol.text(movie, "raw_movie_id"))
        assertEquals("phong-sinh-thuy-khoi", Protocol.text(movie, "slug"))
    }
}
