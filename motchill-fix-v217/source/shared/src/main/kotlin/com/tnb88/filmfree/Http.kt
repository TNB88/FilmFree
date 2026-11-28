package com.tnb88.filmfree

import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay
import okhttp3.Interceptor
import java.io.IOException

object Http {
    fun retryable(code: Int, retryAfter: String?): Boolean =
        code in setOf(429, 500, 502, 503, 504) && (retryAfter.isNullOrBlank() || retryAfter.toLongOrNull()?.let { it in 0..3 } == true)
    suspend fun get(url: String, headers: Map<String,String> = emptyMap(), referer: String? = null,
        timeout: Int = 15, interceptor: Interceptor? = null): NiceResponse {
        for (attempt in 0..1) {
            try {
                val response = app.get(url, headers = headers, referer = referer, timeout = timeout.toLong(), interceptor = interceptor)
                if (attempt == 0 && retryable(response.code, response.headers["Retry-After"])) {
                    delay((response.headers["Retry-After"]?.toLongOrNull()?.times(1000) ?: 600).coerceAtLeast(300))
                    continue
                }
                return response
            } catch (error: IOException) {
                if (attempt == 1) throw error
                delay(600)
            }
        }
        error("HTTP retry exhausted")
    }
}
