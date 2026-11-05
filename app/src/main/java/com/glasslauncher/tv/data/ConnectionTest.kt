package com.glasslauncher.tv.data

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/** Settings' Test the connection: is it the internet or the provider? */
object ConnectionTest {
    /** [internetMs], [providerMs]: how long each took; -1 no answer; provider 0: nothing to ask. */
    class Result(val internetMs: Long, val mbps: Float, val providerMs: Long, val verdict: String)

    private const val CHECK = "https://connectivitycheck.gstatic.com/generate_204"
    private const val DOWNLOAD = "https://speed.cloudflare.com/__down?bytes=10000000"

    /** What the answers mean. Pure. */
    fun verdict(internetMs: Long, mbps: Float, providerMs: Long): String = when {
        internetMs < 0 -> "No internet"
        providerMs < 0 -> "Your provider isn't answering"
        mbps in 0.01f..8f -> "Internet slow for HD"
        else -> "Internet and provider fine"
    }

    /** Background thread. */
    fun run(c: Context): Result {
        val internet = runCatching {
            val t = System.currentTimeMillis()
            val h = URL(CHECK).openConnection() as HttpURLConnection
            h.connectTimeout = 6000; h.readTimeout = 6000
            h.responseCode
            h.disconnect()
            System.currentTimeMillis() - t
        }.getOrDefault(-1L)
        val mbps = if (internet < 0) 0f else runCatching {
            val h = URL(DOWNLOAD).openConnection() as HttpURLConnection
            h.connectTimeout = 6000; h.readTimeout = 10000
            val t = System.currentTimeMillis()
            var bytes = 0L
            h.inputStream.use { i ->
                val buf = ByteArray(64 * 1024)
                while (System.currentTimeMillis() - t < 8000) {
                    val n = i.read(buf)
                    if (n <= 0) break
                    bytes += n
                }
            }
            h.disconnect()
            bytes * 8f / 1000f / (System.currentTimeMillis() - t).coerceAtLeast(1)
        }.getOrDefault(0f)
        val provider = runCatching { com.glasslauncher.tv.live.LiveHome.pingProvider(c) }.getOrDefault(-1L)
        return Result(internet, mbps, provider, verdict(internet, mbps, provider))
    }
}
