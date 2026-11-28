package com.tnb88.filmfree

import com.lagradost.cloudstream3.app
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RemoteDomain(private val file: String, private val fallback: String, private val repo: String = "FilmFree") {
    private val lock = Mutex()
    @Volatile private var cached = fallback
    @Volatile private var until = 0L
    @Volatile private var rejected = ""
    @Volatile private var rejectedUntil = 0L
    fun reject(value: String) { rejected = value; rejectedUntil = System.currentTimeMillis() + 5 * 60 * 1000L }
    suspend fun resolve(): String = lock.withLock {
        if (System.currentTimeMillis() >= until) {
            try {
                val response = app.get("https://raw.githubusercontent.com/TNB88/$repo/main/$file", timeout = 8)
                if (response.code == 200) response.text.lineSequence().map(String::trim).firstOrNull { value ->
                    runCatching { URI(value).let {
                        it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null &&
                            it.query == null && it.fragment == null && it.path.trim('/').isBlank()
                    } }.getOrDefault(false)
                }?.let { cached = it.trimEnd('/') }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Cached/configured fallback remains usable offline. */ }
            until = System.currentTimeMillis() + 5 * 60 * 1000L
        }
        if (cached == rejected && System.currentTimeMillis() < rejectedUntil) fallback else cached
    }
}
