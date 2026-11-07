package com.glasslauncher.tv.data

import android.content.Context
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Backups to a WebDAV folder (Settings › System › Backup to WebDAV: Nextcloud, Synology, Koofr, pCloud…): the same
 * locked file as the weekly one in Downloads (Backup, under the backup code), so the service only ever has a locked file.
 * The last [KEEP] are kept there. Blocking: off the main thread.
 */
object WebDav {
    const val KEEP = 4
    private const val PREFIX = "Glassio Yahsun backup "
    private val NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")

    // MARK: - Pure

    fun fileName(at: Long, zone: ZoneId = ZoneId.systemDefault()) = PREFIX + NAME_TIME.withZone(zone).format(Instant.ofEpochMilli(at)) + ".json"

    fun folder(url: String) = url.trim().trimEnd('/') + "/"

    fun file(url: String, name: String) = folder(url) + URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private val HREF = Regex("<(?:[A-Za-z0-9_-]+:)?href>(.*?)</(?:[A-Za-z0-9_-]+:)?href>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** The backups in a PROPFIND answer, newest first. */
    fun names(xml: String): List<String> = HREF.findAll(xml).mapNotNull { m ->
        val last = m.groupValues[1].trim().trimEnd('/').substringAfterLast('/')
        runCatching { URLDecoder.decode(last.replace("%20", " "), "UTF-8") }.getOrNull()
    }.filter { it.startsWith(PREFIX) && it.endsWith(".json") }.distinct().sortedDescending().toList()

    /** The ones beyond the newest `keep`. */
    fun toDelete(names: List<String>, keep: Int) = names.sortedDescending().drop(keep)

    fun why(code: Int) = when (code) {
        401, 403 -> "WebDAV refused the username or password (an app password, for most services)"
        404, 409 -> "There's no folder at that address"
        507 -> "The WebDAV storage is full"
        else -> "WebDAV answered $code"
    }

    // MARK: - Talking to it

    class Conf(val url: String, val user: String, val pass: String) {
        val ready get() = url.startsWith("http://") || url.startsWith("https://")
    }

    fun conf(ctx: Context): Conf {
        val sp = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)
        return Conf(sp.getString("davUrl", "").orEmpty().trim(), sp.getString("davUser", "").orEmpty(), sp.getString("davPass", "").orEmpty())
    }

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
    }

    private fun request(c: Conf, url: String): Request.Builder {
        val b = Request.Builder().url(url).header("User-Agent", "GlassioYahsun")
        if (c.user.isNotEmpty() || c.pass.isNotEmpty()) b.header("Authorization", Credentials.basic(c.user, c.pass))
        return b
    }

    private fun call(r: Request): Pair<Int, ByteArray> = client.newCall(r).execute().use { it.code to (it.body?.bytes() ?: ByteArray(0)) }

    private val PROPFIND = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""

    /** The backups in the folder, newest first. */
    fun list(c: Conf): List<String> {
        val (code, body) = call(request(c, folder(c.url)).header("Depth", "1")
            .method("PROPFIND", PROPFIND.toRequestBody("application/xml; charset=utf-8".toMediaType())).build())
        check(code == 207 || code == 200) { why(code) }
        return names(String(body, Charsets.UTF_8))
    }

    /** The folder, made when it isn't there (its parent has to be). */
    private fun ensureFolder(c: Conf) {
        val (code, _) = call(request(c, folder(c.url)).header("Depth", "0")
            .method("PROPFIND", PROPFIND.toRequestBody("application/xml; charset=utf-8".toMediaType())).build())
        if (code == 207 || code == 200) return
        if (code != 404) error(why(code))
        val (made, _) = call(request(c, folder(c.url)).method("MKCOL", null).build())
        check(made in 200..299 || made == 405) { why(made) }
    }

    fun upload(c: Conf, name: String, bytes: ByteArray) {
        ensureFolder(c)
        val (code, _) = call(request(c, file(c.url, name)).put(bytes.toRequestBody("application/json".toMediaType())).build())
        check(code in 200..299) { why(code) }
    }

    fun download(c: Conf, name: String): ByteArray {
        val (code, body) = call(request(c, file(c.url, name)).get().build())
        check(code in 200..299) { why(code) }
        return body
    }

    fun delete(c: Conf, name: String) {
        val (code, _) = call(request(c, file(c.url, name)).delete().build())
        check(code in 200..299 || code == 404) { why(code) }
    }

    /**
     * The test intent's (--ez dav_test true): a small stand-in file through the same path as a backup (the folder made,
     * the file put, the listing read, the old ones beyond [KEEP] taken out), none of the TV's settings in it. Returns
     * what's in the folder after.
     */
    fun test(ctx: Context): List<String> {
        val c = conf(ctx)
        check(c.ready) { "No WebDAV folder set" }
        upload(c, fileName(System.currentTimeMillis()), """{"test":"Glassio WebDAV check, no settings in it"}""".toByteArray())
        toDelete(list(c), KEEP).forEach { delete(c, it) }
        return list(c)
    }

    /** A backup made now under `code`, put in the folder, the old ones beyond [KEEP] taken out. Returns its name. */
    fun backUp(ctx: Context, code: String): String {
        val c = conf(ctx)
        check(c.ready) { "Set the WebDAV folder first: Settings › System › Backup to WebDAV" }
        val name = fileName(System.currentTimeMillis())
        upload(c, name, Backup.make(ctx, code))
        runCatching { toDelete(list(c), KEEP).forEach { delete(c, it) } }.onFailure { Glog.w("WebDAV: old backups stay", it) }
        ctx.getSharedPreferences("glass", Context.MODE_PRIVATE).edit().putLong("davLast", System.currentTimeMillis()).apply()
        Glog.i("WebDAV: backup uploaded")
        return name
    }
}
