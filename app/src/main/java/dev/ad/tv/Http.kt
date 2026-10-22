package dev.ad.tv

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Http {
    /** MET Norway and Nominatim both reject requests without an identifying User-Agent. */
    const val USER_AGENT = "TVHome/${BuildConfig.VERSION_NAME} (+https://github.com/ad/tv)"

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = open(url, headers)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for ${url.substringBefore('?')}")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    fun getBytes(url: String): ByteArray {
        val c = open(url, emptyMap())
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** Tries each base in turn and returns the first successful response. */
    fun getFirst(bases: List<String>, path: String, headers: Map<String, String> = emptyMap()): String {
        var last: Exception? = null
        for (base in bases) {
            try {
                return get(base.trimEnd('/') + path, headers)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("no hosts")
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, image/*;q=0.9, */*;q=0.5")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
}
