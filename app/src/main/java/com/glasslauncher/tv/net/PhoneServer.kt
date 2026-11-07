package com.glasslauncher.tv.net

import com.glasslauncher.tv.GlassApp
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Tiny LAN-only web page ("Manage from phone"). It only runs while the settings page is open and every
 * request must carry the one-time token embedded in the QR code. Secrets (TMDB token) are write-only.
 */
class PhoneServer(private val app: GlassApp, private val token: String) {
    @Volatile private var running = false
    private var socket: ServerSocket? = null

    fun start(): Int? {
        for (port in 9090..9099) {
            try {
                val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port), 8) }
                socket = s; running = true
                Thread({ loop(s) }, "glass-phone-server").also { it.isDaemon = true; it.start() }
                return port
            } catch (_: Exception) { }
        }
        return null
    }

    fun stop() { running = false; runCatching { socket?.close() }; socket = null }

    private fun loop(s: ServerSocket) {
        while (running) {
            val c = try { s.accept() } catch (_: Exception) { break }
            try { c.soTimeout = 15000; handle(c) } catch (_: Exception) { } finally { runCatching { c.close() } }
        }
    }

    private fun readLine(i: InputStream): String? {
        val o = ByteArrayOutputStream()
        while (o.size() < 8192) {
            val b = i.read()
            if (b < 0) return if (o.size() == 0) null else o.toString("UTF-8")
            if (b == '\n'.code) return o.toString("UTF-8").trimEnd('\r')
            o.write(b)
        }
        return null
    }

    private fun query(q: String, key: String) = q.split('&').firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }.orEmpty()

    private fun handle(c: Socket) {
        val input = c.getInputStream()
        val req = readLine(input) ?: return
        val parts = req.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase(); val target = parts[1]
        var len = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("content-length", true)) len = line.substring(idx + 1).trim().toIntOrNull() ?: 0
        }
        val path = target.substringBefore('?'); val q = target.substringAfter('?', "")
        // A shared design's page (its QR code): no code needed, it only shows a design's colors and code.
        if (method == "GET" && path == "/design") { respond(c, 200, "text/html", com.glasslauncher.tv.live.LiveDesigns.page(query(q, "d"), query(q, "n"))); return }
        if (!MessageDigest.isEqual(query(q, "t").toByteArray(), token.toByteArray())) { respond(c, 403, "text/plain", "Forbidden"); return }
        if (method == "GET" && path == "/") { respond(c, 200, "text/html", PAGE); return }
        if (method == "GET" && path == "/api/state") { respond(c, 200, "application/json", state().toString()); return }
        // The calendar (Settings › System › Calendar on the phone page): the feed a phone subscribes to, or one show's file.
        if (method == "GET" && (path == "/api/live/calendar.ics" || path == "/api/live/event.ics")) {
            val ics = runCatching {
                check(app.settings.phoneCalendar) { "Calendar on the phone page is off: Settings › System" }
                val items = if (path == "/api/live/calendar.ics") com.glasslauncher.tv.live.LivePhone.calendar(app)
                else listOf(com.glasslauncher.tv.live.LivePhone.calendarItem(app, query(q, "id").toIntOrNull() ?: -1,
                    query(q, "start").toLongOrNull() ?: 0, query(q, "end").toLongOrNull() ?: 0, query(q, "title").take(200)))
                com.glasslauncher.tv.data.IcsWriter.calendar("Glassio", items, System.currentTimeMillis(), 15)
            }
            ics.fold({
                val file = if (path == "/api/live/event.ics") "Content-Disposition: attachment; filename=\"glassio-show.ics\"\r\n"
                else "Content-Disposition: inline; filename=\"glassio.ics\"\r\n"
                respond(c, 200, "text/calendar", it, file)
            }, { respond(c, 400, "text/plain", it.message ?: "Failed") })
            return
        }
        // Live TV (Settings › System › Live TV on the phone page): reading it, blocking (the guide).
        if (method == "GET" && path.startsWith("/api/live/")) {
            val body = runCatching {
                check(app.settings.phoneLive) { "Live TV on the phone page is off: Settings › System" }
                when (path) {
                    "/api/live/channels" -> com.glasslauncher.tv.live.LivePhone.channels(app, query(q, "q")).toString()
                    "/api/live/find" -> JSONObject().put("airings", com.glasslauncher.tv.live.LivePhone.find(app, query(q, "q"))).toString()
                    "/api/live/schedule" -> JSONObject().put("items", com.glasslauncher.tv.live.LivePhone.schedule(app)).toString()
                    "/api/live/recordings" -> JSONObject().put("items", com.glasslauncher.tv.live.LivePhone.recordings(app)).toString()
                    else -> error("Not found")
                }
            }
            respond(c, 200, "application/json", body.getOrElse { err(it.message ?: "Failed") })
            return
        }
        if (method != "POST") { respond(c, 404, "text/plain", "Not found"); return }
        if (path == "/api/bg/upload") {
            // Straight to the backgrounds folder, never held in memory (videos can be hundreds of MB).
            val msg = runCatching { bgUpload(query(q, "name"), query(q, "use") == "1", input, len) }
            respond(c, 200, "application/json", msg.fold({ JSONObject().put("ok", true).put("message", it).toString() }, { err(it.message ?: "Upload failed") }))
            return
        }
        if (path == "/api/backup/make" || path == "/api/backup/restore") { backup(c, path, input, len); return }
        val max = if (path == "/api/icon/upload") 8 * 1024 * 1024 else 64 * 1024
        if (len !in 0..max) { respond(c, 400, "application/json", err("Too large")); return }
        val body = ByteArray(len)
        var read = 0
        while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
        try {
            val msg = if (path == "/api/icon/upload") {
                icon(query(q, "pkg"), body.copyOf(read))
            } else {
                val j = runCatching { JSONObject(String(body, 0, read, Charsets.UTF_8)) }.getOrDefault(JSONObject())
                post(path, j)
            }
            respond(c, 200, "application/json", JSONObject().put("ok", true).put("message", msg).toString())
        } catch (e: Exception) {
            respond(c, 200, "application/json", err(e.message ?: "Failed"))
        }
    }

    /**
     * The backup (Backup): made here and handed to the phone (also saved in the TV's Downloads), or one from the phone
     * restored, then Glassio restarts. The code comes in the body, never in the address.
     */
    private fun backup(c: Socket, path: String, input: InputStream, len: Int) {
        if (len !in 1..(24 * 1024 * 1024)) { respond(c, 400, "application/json", err("Too large")); return }
        val body = ByteArray(len)
        var read = 0
        while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
        val j = runCatching { JSONObject(String(body, 0, read, Charsets.UTF_8)) }.getOrDefault(JSONObject())
        val code = j.optString("code")
        val b = com.glasslauncher.tv.data.Backup
        try {
            if (path == "/api/backup/make") {
                val file = b.make(app, code)
                runCatching { b.saveToDownloads(app, file) }
                respond(c, 200, "application/json", JSONObject().put("ok", true).put("message", "Backup made").put("name", b.NAME)
                    .put("file", String(file, Charsets.UTF_8)).toString())
            } else {
                val file = j.optString("file").toByteArray(Charsets.UTF_8)
                require(b.madeAt(file) > 0) { "That file isn't a Glassio Yahsun backup" }
                val r = b.restore(app, file, code)
                runCatching { b.incomingFile(app).apply { parentFile?.mkdirs() }.writeBytes(file) }
                respond(c, 200, "application/json", JSONObject().put("ok", true)
                    .put("message", "Restored ${r.settings} settings and ${r.files} files. Glassio restarts on the TV").toString())
                // After the answer is out: Glassio starts again with what was restored.
                Thread({ Thread.sleep(1500); android.os.Handler(android.os.Looper.getMainLooper()).post { b.restart(app) } }, "glass-restore").start()
            }
        } catch (e: Exception) {
            respond(c, 200, "application/json", err(e.message ?: "Failed"))
        }
    }

    private fun icon(pkg: String, bytes: ByteArray): String {
        require(pkg.isNotBlank()) { "Missing app" }
        app.icons.setFromBytes(pkg, bytes)
        return "Icon updated"
    }

    private val bg = com.glasslauncher.tv.ui.CustomBackground
    @Volatile private var downloading = ""

    /** A safe file name in the backgrounds folder, with an extension Glassio can show. */
    private fun bgFile(raw: String, fallbackExt: String = ""): java.io.File {
        var name = raw.substringAfterLast('/').substringBefore('?').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(80)
        var ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in bg.exts && ext !in bg.videoExts) { ext = fallbackExt; name = (name.ifBlank { "background" }.substringBeforeLast('.')) + "." + ext }
        require(ext in bg.exts || ext in bg.videoExts) { "Use a picture (jpg, png, webp) or a video (mp4, mkv, webm, mov)" }
        if (name.startsWith(".")) name = "background$name"
        return java.io.File(bg.folder(app), name)
    }

    private fun useFile(f: java.io.File) {
        if (f.extension.lowercase() in bg.videoExts) { app.settings.bgVideo = f.absolutePath; app.settings.bgMode = "video" }
        else { app.settings.bgImage = f.absolutePath; app.settings.bgMode = "image" }
    }

    private fun bgUpload(name: String, use: Boolean, input: InputStream, len: Int): String {
        val f = bgFile(name)
        val video = f.extension.lowercase() in bg.videoExts
        require(len > 0) { "Empty file" }
        require(len <= (if (video) 1500 else 40) * 1024 * 1024) { if (video) "Videos up to 1.5 GB" else "Pictures up to 40 MB" }
        val part = java.io.File(f.parentFile, f.name + ".part")
        var left = len.toLong()
        part.outputStream().buffered(256 * 1024).use { o ->
            val buf = ByteArray(256 * 1024)
            while (left > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; o.write(buf, 0, n); left -= n }
        }
        if (left > 0) { part.delete(); error("The upload stopped before the end") }
        f.delete(); check(part.renameTo(f)) { "Couldn't save the file" }
        if (use) useFile(f)
        return "Saved ${f.name} on the TV" + if (use) " and set as the background" else ""
    }

    /** Downloads a picture or video link into the backgrounds folder (in the background), then uses it. */
    private fun bgDownload(url: String, use: Boolean): String {
        require(url.startsWith("http")) { "The link must start with http" }
        check(downloading.isEmpty()) { "Already downloading $downloading" }
        val guess = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        val first = runCatching { bgFile(url) }.getOrNull()
        downloading = first?.name ?: "the link"
        Thread({
            try {
                var c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 30000; c.instanceFollowRedirects = true
                val type = c.contentType.orEmpty().lowercase()
                val ext = when { guess in bg.exts || guess in bg.videoExts -> guess; "png" in type -> "png"; "webp" in type -> "webp"; "image" in type -> "jpg"
                    "webm" in type -> "webm"; "matroska" in type -> "mkv"; "quicktime" in type -> "mov"; "video" in type -> "mp4"; else -> "" }
                val f = first ?: bgFile("background-" + System.currentTimeMillis() / 1000, ext)
                downloading = f.name
                val part = java.io.File(f.parentFile, f.name + ".part")
                check(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }
                c.inputStream.use { i -> part.outputStream().use { i.copyTo(it, 256 * 1024) } }
                f.delete(); part.renameTo(f)
                if (use) useFile(f)
                lastDownload = "Downloaded ${f.name}" + if (use) " and set as the background" else ""
            } catch (e: Exception) {
                lastDownload = "Download failed: ${e.message}"
            } finally { downloading = "" }
        }, "glass-bg-download").apply { isDaemon = true; start() }
        return "Downloading to the TV… (this page shows when it's done)"
    }
    @Volatile private var lastDownload = ""

    /** The remote's buttons, to the TV's own key codes (pressed through the TV's debugging connection, LocalShell). */
    private val KEYS = mapOf(
        "up" to "DPAD_UP", "down" to "DPAD_DOWN", "left" to "DPAD_LEFT", "right" to "DPAD_RIGHT", "ok" to "DPAD_CENTER",
        "back" to "BACK", "home" to "HOME", "menu" to "MENU", "playpause" to "MEDIA_PLAY_PAUSE", "rew" to "MEDIA_REWIND",
        "ff" to "MEDIA_FAST_FORWARD", "volup" to "VOLUME_UP", "voldown" to "VOLUME_DOWN", "mute" to "VOLUME_MUTE",
        "chup" to "CHANNEL_UP", "chdown" to "CHANNEL_DOWN", "info" to "INFO",
    )

    /** A command on the TV's shell; the TV asks once to allow USB debugging for Glassio (Always allow). */
    private fun shell(cmd: String) {
        com.glasslauncher.tv.data.LocalShell.run(app, cmd)
            .getOrElse { error("Allow TV access (ADB) for Glassio first: Settings › System › TV access") }
    }

    private fun post(path: String, j: JSONObject): String = when (path) {
        // The phone remote: a button, typed text, Live TV's search, an app opened or moved on Home.
        "/api/key" -> { shell("input keyevent KEYCODE_" + (KEYS[j.optString("key")] ?: error("Unknown button"))); "" }
        "/api/text" -> {
            // Only what the TV's keyboard types plainly (letters, digits, simple punctuation); a space as %s.
            // As typed: a space in front too (adding a word to what's there).
            val t = j.optString("text").take(200).filter { it.isLetterOrDigit() || it in " .,-_@:/?!'" }
            require(t.isNotBlank()) { "Nothing to type" }
            shell("input text '" + t.replace("'", "'\\''").replace(" ", "%s") + "'")
            "Typed"
        }
        // Live TV: watch a channel on the TV, set or cancel a reminder or a recording, play or delete a recording.
        "/api/live/watch", "/api/live/set", "/api/live/cancel", "/api/live/play", "/api/live/delete" -> {
            check(app.settings.phoneLive) { "Live TV on the phone page is off: Settings › System" }
            when (path) {
                "/api/live/watch" -> com.glasslauncher.tv.live.LivePhone.watch(app, j.optInt("id", -1))
                "/api/live/set" -> com.glasslauncher.tv.live.LivePhone.set(app, j.optBoolean("record"), j.optInt("id", -1),
                    j.optLong("start"), j.optLong("end"), j.optString("title").take(200))
                "/api/live/cancel" -> com.glasslauncher.tv.live.LivePhone.cancel(app, j.optString("key"))
                "/api/live/play" -> com.glasslauncher.tv.live.LivePhone.play(app, j.optString("id"))
                else -> com.glasslauncher.tv.live.LivePhone.delete(app, j.optString("id"))
            }
        }
        // A Live TV design by its code (shared from another TV, or a community one).
        "/api/design" -> com.glasslauncher.tv.live.LiveDesigns.apply(app, j.optString("code"))
        "/api/search" -> {
            val words = j.optString("q").trim().take(100)
            require(words.isNotEmpty()) { "Type what to look for" }
            app.startActivity(com.glasslauncher.tv.live.LiveHome.search(app, words))
            "Searching Live TV for “$words”"
        }
        "/api/app/open" -> {
            val e = app.apps.ordered(includeHidden = true).firstOrNull { it.pkg == j.optString("pkg") } ?: error("No such app")
            check(app.apps.open(app, e)) { "${e.label} can't be opened" }; "Opened ${e.label}"
        }
        // Home Assistant 2.0: tune {"channel": 622 | "Sky Sports News"}, pause, play, stop, notify {"title","message","seconds"}.
        "/api/ha/tune", "/api/ha/pause", "/api/ha/play", "/api/ha/stop", "/api/ha/notify" -> {
            check(app.settings.haControl) { "Control from Home Assistant is off: Settings › Nuvio, Stremio & Home Assistant" }
            when (path) {
                "/api/ha/tune" -> com.glasslauncher.tv.live.LivePhone.tune(app, j.optString("channel").take(80))
                "/api/ha/pause" -> { shell("input keyevent KEYCODE_MEDIA_PAUSE"); "Paused" }
                "/api/ha/play" -> { shell("input keyevent KEYCODE_MEDIA_PLAY"); "Playing" }
                "/api/ha/stop" -> { shell("input keyevent KEYCODE_MEDIA_STOP"); "Stopped" }
                else -> {
                    val title = j.optString("title").trim().take(80)
                    val message = j.optString("message").trim().take(300)
                    require(title.isNotEmpty() || message.isNotEmpty()) { "Send a title or a message" }
                    com.glasslauncher.tv.CameraActivity.notice(app, title, message, j.optInt("seconds", 8))
                    "Shown on the TV"
                }
            }
        }
        // Home Assistant: a camera over whatever is on (a doorbell). {"image": snapshot link, "title", "seconds"}.
        "/api/ha/camera" -> {
            val image = j.optString("image").trim()
            require(image.startsWith("http")) { "Send the camera's picture link as image" }
            com.glasslauncher.tv.CameraActivity.show(app, image, j.optString("title", "Doorbell"), j.optInt("seconds", 20))
            "Showing the camera"
        }
        "/api/app/move" -> { app.apps.move(j.optString("pkg"), if (j.optInt("dir") < 0) -1 else 1); "Moved" }
        "/api/bg/mode" -> {
            val m = j.optString("mode")
            require(m in listOf("carousel", "image", "video", "aerial")) { "Unknown mode" }
            require(m != "image" || app.settings.bgImage.isNotBlank()) { "Add a picture first" }
            require(m != "video" || app.settings.bgVideo.isNotBlank()) { "Add a video first" }
            app.settings.bgMode = m; "Background changed"
        }
        "/api/bg/link" -> {
            val u = j.optString("url").trim()
            require(u.startsWith("http")) { "The link must start with http" }
            if (j.optString("kind") == "video") { app.settings.bgVideo = u; app.settings.bgMode = "video"; "Video link set as the background" }
            else { app.settings.bgImage = u; app.settings.bgMode = "image"; "Picture link set as the background" }
        }
        "/api/bg/download" -> bgDownload(j.optString("url").trim(), j.optBoolean("use", true))
        "/api/bg/use" -> { val f = java.io.File(bg.folder(app), j.optString("name").substringAfterLast('/')); require(f.isFile) { "Not found" }; useFile(f); "Background changed" }
        "/api/bg/delete" -> {
            val f = java.io.File(bg.folder(app), j.optString("name").substringAfterLast('/')); require(f.isFile) { "Not found" }
            val st = app.settings
            if (st.bgImage == f.absolutePath) { st.bgImage = ""; if (st.bgMode == "image") st.bgMode = "carousel" }
            if (st.bgVideo == f.absolutePath) { st.bgVideo = ""; if (st.bgMode == "video") st.bgMode = "carousel" }
            f.delete(); "Deleted"
        }
        "/api/addon/add" -> runBlocking { app.catalogs.addAddon(j.optString("url")) }
        "/api/addon/remove" -> runBlocking { app.catalogs.removeAddon(j.optString("url")) }
        "/api/icon/url" -> { app.icons.setFromUrl(j.optString("pkg"), j.optString("url").trim()); "Icon updated" }
        "/api/icon/reset" -> { app.icons.reset(j.optString("pkg")); "Icon reset" }
        "/api/live/signin" -> com.glasslauncher.tv.live.LiveHome.signIn(app, j.optString("kind"), j.optString("server").trim(), j.optString("user").trim(), j.optString("pass").trim())
        "/api/tmdb" -> {
            val t = j.optString("token").trim()
            require(!com.glasslauncher.tv.data.Tmdb.looksLikeApiKey(t)) { "That's the API Key. Paste the API Read Access Token (the long one, starting eyJ...)" }
            if (t.isNotBlank()) require(com.glasslauncher.tv.data.Tmdb.verify(t) != false) { "TMDB refused this token. Copy the API Read Access Token again" }
            app.settings.tmdbToken = t; app.catalogs.clearEnrichment()
            if (t.isBlank()) "TMDB token removed" else "TMDB token saved and working"
        }
        // Backup to WebDAV: the folder, the username and the password (write-only), or a backup now with the weekly code.
        "/api/dav" -> {
            val url = j.optString("url").trim()
            require(url.isEmpty() || url.startsWith("http://") || url.startsWith("https://")) { "The folder's address starts with https://" }
            app.settings.davUrl = url
            app.settings.davUser = j.optString("user").trim()
            if (j.has("pass") && j.optString("pass").isNotEmpty()) app.settings.davPass = j.optString("pass")
            if (url.isEmpty()) { app.settings.davUser = ""; app.settings.davPass = "" }
            if (url.isEmpty()) "Backup to WebDAV off" else "WebDAV folder saved"
        }
        "/api/dav/now" -> {
            val code = app.settings.backupAutoCode
            require(code.isNotEmpty()) { "Turn on the weekly backup first (Settings › System): it has the code" }
            "Backed up: " + com.glasslauncher.tv.data.WebDav.backUp(app, code)
        }
        "/api/ha" -> { app.settings.haWebhook = j.optString("url"); app.settings.haOnLaunch = app.settings.haWebhook.startsWith("http"); "Home Assistant webhook saved" }
        "/api/cal/add" -> {
            val u = j.optString("url").trim()
            require(Regex("^(https?|webcals?)://.+", RegexOption.IGNORE_CASE).matches(u)) { "The link starts with https:// or webcal://" }
            app.settings.calLinks = app.settings.calLinks + u; "Calendar added: its next events show on Home"
        }
        "/api/cal/remove" -> {
            val l = app.settings.calLinks; val i = j.optInt("i", -1)
            require(i in l.indices) { "Not found" }
            app.settings.calLinks = l.filterIndexed { k, _ -> k != i }; "Calendar removed"
        }
        "/api/hastate" -> {
            val st = app.settings
            val url = j.optString("url").trim().trimEnd('/'); val token = j.optString("token").trim()
            require(url.isBlank() || url.startsWith("http")) { "The address starts with http:// or https://" }
            if (url.isNotBlank()) st.haUrl = url
            if (token.isNotBlank()) st.haToken = token
            // Checked straight away: a wrong address or token says so here, not as an empty row on the TV.
            val n = com.glasslauncher.tv.data.HomeWidgets.all(st.haBase, st.haToken).size
            "Home Assistant works: $n entities. Choose which show on Home in Settings › Home › Home Assistant readings"
        }
        "/api/report" -> com.glasslauncher.tv.data.ProblemReport.build(app, app.updater.current)
        "/api/aerial/add" -> {
            val u = j.optString("url").trim()
            require(u.startsWith("http")) { "The link must start with http" }
            app.settings.aerialUrls = (app.settings.aerialUrls + u).distinct(); "Video link added"
        }
        "/api/aerial/remove" -> { app.settings.aerialUrls = app.settings.aerialUrls - j.optString("url"); "Removed" }
        else -> error("Unknown action")
    }

    private fun state(): JSONObject {
        val addons = JSONArray()
        app.catalogs.addons.sorted().forEach { addons.put(JSONObject().put("url", it).put("name", app.catalogs.addonName(it)).put("host", runCatching { java.net.URI(it).host }.getOrNull() ?: "")) }
        app.catalogs.accountOnly().forEach { addons.put(JSONObject().put("url", it).put("name", app.catalogs.addonName(it)).put("host", runCatching { java.net.URI(it).host }.getOrNull() ?: "").put("account", true).put("from", app.catalogs.accountOf(it))) }
        val apps = JSONArray()
        app.apps.ordered(includeHidden = true).forEach {
            apps.put(JSONObject().put("pkg", it.pkg).put("label", it.label).put("custom", app.icons.fileFor(it.pkg).exists()))
        }
        val aerial = JSONArray(); app.settings.aerialUrls.forEach { aerial.put(it) }
        // The calendars by their site only: their links are secret addresses.
        val cals = JSONArray(); app.settings.calLinks.forEach { cals.put(runCatching { java.net.URI(it.replaceFirst(Regex("^webcals?://"), "https://")).host }.getOrNull() ?: "calendar") }
        val st = app.settings
        val files = JSONArray()
        (bg.files(app, false) + bg.files(app, true)).forEach { f ->
            files.put(JSONObject().put("name", f.name).put("video", f.extension.lowercase() in bg.videoExts).put("mb", (f.length() / 1024 / 1024).toInt())
                .put("used", f.absolutePath == st.bgImage || f.absolutePath == st.bgVideo))
        }
        fun shown(v: String) = if (v.startsWith("http")) v else v.substringAfterLast('/')
        val back = JSONObject().put("mode", st.bgMode).put("image", shown(st.bgImage)).put("video", shown(st.bgVideo)).put("files", files)
            .put("downloading", downloading).put("last", lastDownload)
        return JSONObject().put("addons", addons).put("bg", back).put("apps", apps).put("aerial", aerial)
            .put("tmdb", app.settings.tmdbToken.isNotBlank()).put("ha", app.settings.haWebhook.isNotBlank())
            .put("cals", cals).put("haBase", app.settings.haBase).put("haToken", app.settings.haToken.isNotBlank())
            .put("live", app.settings.phoneLive).put("calendar", app.settings.phoneCalendar)
            .put("dav", runCatching { java.net.URI(app.settings.davUrl).host }.getOrNull().orEmpty()).put("davUser", app.settings.davUser)
            .put("davPass", app.settings.davPass.isNotEmpty()).put("davLast", app.settings.davLast)
    }

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m).toString()

    private fun respond(c: Socket, code: Int, type: String, body: String, extra: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" + extra +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        val out = c.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8)); out.write(bytes); out.flush()
    }

    internal companion object {
        /** The phone page. Its script's strings in single quotes: no apostrophes in them (’ instead), or none of it runs. */
        const val PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Glassio</title><style>
body{margin:0;background:#0b0b0d;color:#fff;font:16px/1.5 -apple-system,Roboto,Segoe UI,sans-serif}
main{max-width:620px;margin:0 auto;padding:22px 16px 60px}h1{font-size:26px;margin:6px 0 0}h2{font-size:13px;letter-spacing:.12em;color:#b3b3b8;margin:30px 0 8px;text-transform:uppercase}
p{color:#b3b3b8;margin:4px 0 12px}input[type=text],input[type=password],textarea{width:100%;box-sizing:border-box;background:#1c1c1f;color:#fff;border:1px solid #2a2a2f;border-radius:12px;padding:12px;font:15px monospace}
button{background:#fff;color:#0b0b0d;border:0;border-radius:12px;padding:11px 16px;font-size:15px;font-weight:600;margin-top:8px}
button.s{background:#2a2a2f;color:#fff;padding:8px 12px;font-size:13px;margin:0 4px 0 0}button.d{background:#3a1f2b;color:#ff8f9b}
#msg{position:sticky;top:0;padding:10px 0;min-height:1.4em;font-weight:600}.ok{color:#6ee7b7}.bad{color:#ff8f9b}
.card{background:#1c1c1f;border-radius:14px;padding:12px 14px;margin-bottom:8px}.card b{display:block}.card small{color:#8a8a90;word-break:break-all}
.row{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}
.pad{display:grid;grid-template-columns:repeat(3,72px);gap:8px;justify-content:center;margin:6px 0 4px}.pad button{height:64px;margin:0;font-size:20px}.pad .okb{border-radius:50%}.tag{font-size:11px;background:#2a2150;color:#c8bdff;border-radius:6px;padding:1px 6px;margin-left:6px}
</style></head><body><main>
<h1>Glassio</h1><p>Manage your TV from here &middot; &#1573;&#1583;&#1575;&#1585;&#1577; &#1575;&#1604;&#1578;&#1604;&#1601;&#1586;&#1610;&#1608;&#1606; &#1605;&#1606; &#1607;&#1606;&#1575;</p>
<div id="msg"></div>
<h2>Remote</h2>
<div class="pad"><span></span><button onclick="k('up')">&#9650;</button><span></span>
<button onclick="k('left')">&#9664;</button><button class="okb" onclick="k('ok')">OK</button><button onclick="k('right')">&#9654;</button>
<span></span><button onclick="k('down')">&#9660;</button><span></span></div>
<div class="row"><button class="s" onclick="k('back')">Back</button><button class="s" onclick="k('home')">Home</button><button class="s" onclick="k('menu')">Menu</button><button class="s" onclick="k('info')">Info</button></div>
<div class="row"><button class="s" onclick="k('rew')">&#9194;</button><button class="s" onclick="k('playpause')">&#9199;</button><button class="s" onclick="k('ff')">&#9193;</button>
<button class="s" onclick="k('chup')">CH +</button><button class="s" onclick="k('chdown')">CH &minus;</button></div>
<div class="row"><button class="s" onclick="k('voldown')">Vol &minus;</button><button class="s" onclick="k('mute')">Mute</button><button class="s" onclick="k('volup')">Vol +</button></div>
<input type="text" id="tx" placeholder="Type into the TV (the box that's open there)" style="margin-top:12px"><button onclick="send('/api/text',{text:document.getElementById('tx').value},'tx')">Type on the TV</button>
<input type="text" id="sq" placeholder="Channel or show" style="margin-top:12px"><button onclick="send('/api/search',{q:v('sq')},'sq')">Search Live TV</button>
<div id="livebox" style="display:none"><h2>Live TV</h2><div class="row" id="ltabs"></div>
<div id="lchq" style="margin-top:10px"><input type="text" id="lc" placeholder="Any channel: its name or number"><button class="s" style="margin-top:8px" onclick="lCh()">Find channel</button></div>
<div id="lfind" style="display:none;margin-top:10px"><input type="text" id="lq" placeholder="A show or a team"><button onclick="lFind()">Find in the guide</button></div>
<div id="llist" style="margin-top:10px"></div>
<div id="calbox" style="display:none"><h2>Calendar</h2><p>What you set and the matches you follow, the next 7 days, in your phone’s calendar: it updates itself while you’re home. iPhone: Subscribe. Android: open the link with ICSx⁵ (free), which puts it in Google Calendar on the phone. Google Calendar on its own can’t reach the TV: use Add to calendar on a show instead.</p>
<div class="row"><a id="calsub" href="#"><button class="s">Subscribe</button></a><button class="s" onclick="var t=document.getElementById('calurl');t.select();try{document.execCommand('copy');say('Copied',true)}catch(e){}">Copy link</button></div>
<input type="text" id="calurl" readonly style="margin-top:8px"></div></div>
<h2>Add-ons</h2><div id="addons"></div>
<input type="text" id="au" placeholder="https://.../manifest.json  or  stremio://..."><button onclick="addAddon()">Add add-on</button>
<div style="display:none"><h2>Background</h2><p>What's behind the home screen. Your pictures and videos live in Glassio's backgrounds folder on the TV.</p>
<div class="row" id="bgm"></div><p id="bgnow"></p>
<input type="text" id="bi" placeholder="Picture link: https://.../photo.jpg"><div class="row"><button onclick="bgLink('image','bi')">Use picture link</button><button class="s" onclick="bgSave('bi')">Save to the TV</button></div>
<input type="text" id="bv" placeholder="Video link: https://.../video.mp4" style="margin-top:12px"><div class="row"><button onclick="bgLink('video','bv')">Use video link</button><button class="s" onclick="bgSave('bv')">Save to the TV</button></div>
<div class="row" style="margin-top:12px"><input type="file" id="bf" accept="image/*,video/*" style="display:none" onchange="bgUp()"><button onclick="document.getElementById('bf').click()">Upload a picture or video from this phone</button></div>
<p id="bgp"></p><div id="bgfiles"></div></div>
<h2>Apps</h2><p>Open one on the TV, move it up or down on Home, or give it an icon: an image link or one from this phone.</p><div id="apps"></div>
<h2>TMDB token (logos, synopsis, ratings)</h2><p>Paste the <b>API Read Access Token</b> (the long one, starting eyJ...), not the short API Key. themoviedb.org &rarr; Settings &rarr; API.</p><p id="tks"></p><input type="text" id="tk" placeholder="TMDB API Read Access Token"><button onclick="send('/api/tmdb',{token:v('tk')},'tk')">Save token</button>
<h2>Live TV sign-in</h2><p>Set up Live TV from here instead of typing with the remote. Xtream Codes: the server, your username and password. M3U: the playlist's link (and its guide's, if it has one). The details stay on your TV.</p><select id="lk" onchange="lkShow()"><option value="xtream">Xtream Codes</option><option value="m3u">M3U link</option><option value="mag">MAG portal</option></select><input type="text" id="ls" placeholder="Server (http://example.com:8080)"><input type="text" id="lu" placeholder="Username" autocomplete="off"><input type="password" id="lp" placeholder="Password" autocomplete="off"><button onclick="send('/api/live/signin',{kind:v('lk'),server:v('ls'),user:v('lu'),pass:v('lp')},'lp')">Sign in</button>
<h2>Home Assistant control</h2><p>Paste into Home Assistant's <b>configuration.yaml</b>, restart it, then use these in automations: <b>rest_command.glassio_tune</b> (channel: a number or a name), <b>glassio_notify</b> (title, message), <b>glassio_pause</b>, <b>glassio_play</b>. Keep it private: the address has the TV's code. Needs Phone remote on (Settings &rsaquo; System).</p>
<textarea id="hay" rows="12" readonly></textarea><button class="s" onclick="var t=document.getElementById('hay');t.select();try{document.execCommand('copy');say('Copied',true)}catch(e){}">Copy</button>
<h2>Home Assistant webhook</h2><input type="text" id="ha" placeholder="http://homeassistant.local:8123/api/webhook/your_id"><button onclick="send('/api/ha',{url:v('ha')},'ha')">Save webhook</button>
<h2>Calendars on Home</h2><p>An iCal link: in Google Calendar on a computer, the calendar's Settings &rarr; <b>Secret address in iCal format</b>. Its next events show in Home's "At a glance" row.</p><div id="cals"></div><input type="text" id="cl" placeholder="https://calendar.google.com/calendar/ical/.../basic.ics"><button onclick="send('/api/cal/add',{url:v('cl')},'cl')">Add calendar</button>
<h2>Home Assistant readings on Home</h2><p>Its address and a <b>long-lived access token</b> (your Home Assistant profile &rarr; Security &rarr; Long-lived access tokens). Then choose the readings on the TV: Settings &rsaquo; Home &rsaquo; Home Assistant readings.</p><p id="hab"></p><input type="text" id="hu" placeholder="http://homeassistant.local:8123"><input type="password" id="ht" placeholder="Long-lived access token" autocomplete="off" style="margin-top:8px"><button onclick="send('/api/hastate',{url:v('hu'),token:v('ht')},'ht')">Save and check</button>
<h2>Report a problem</h2><p>A text file to send with your report: the version, your TV, your settings and the recent log. Tokens, links, addresses and e-mails are taken out.</p><button onclick="rep()">Download the report</button>
<h2>Backup</h2><p>Everything Glassio has on this TV (settings, sign-ins, app icons, Live TV) in one file, locked with a code you choose: at least 6 characters. Keep the code safe: without it the file can't be opened.</p>
<input type="password" id="bc" placeholder="Code for the backup" autocomplete="new-password"><button onclick="bk()">Download a backup</button>
<p style="margin-top:14px">Restore one: Glassio's settings on the TV are replaced, then it restarts.</p>
<input type="file" id="bkf" accept=".json,application/json"><input type="password" id="rc" placeholder="Code of that backup" autocomplete="off" style="margin-top:8px"><button class="d" onclick="rs()">Restore on the TV</button>
<h2>Apply a design</h2><p>A Live TV design’s 8 digits, from another TV’s Share or a community design: Live TV shows it at once.</p>
<input type="text" id="dsg" placeholder="8 digits" inputmode="numeric"><button onclick="send('/api/design',{code:v('dsg')},'dsg')">Apply on the TV</button>
<h2>Backup to WebDAV</h2><p>Nextcloud, Synology, Koofr, pCloud and others: the weekly backup goes to this folder too, locked with your backup code. Use an <b>app password</b> from your service’s security settings. The last 4 are kept.</p><p id="davs"></p>
<input type="text" id="du" placeholder="https://cloud.example.com/remote.php/dav/files/you/Glassio"><input type="text" id="dn" placeholder="Username" style="margin-top:8px" autocomplete="off"><input type="password" id="dp" placeholder="App password" style="margin-top:8px" autocomplete="new-password">
<div class="row"><button onclick="send('/api/dav',{url:v('du'),user:v('dn'),pass:document.getElementById('dp').value},'dp')">Save</button><button class="s" onclick="send('/api/dav/now',{})">Back up now</button></div>
<h2>Screensaver video links</h2><div id="aerial"></div>
<input type="text" id="av" placeholder="https://.../video.mp4"><button onclick="send('/api/aerial/add',{url:v('av')},'av')">Add video</button>
</main><script>
var T=new URLSearchParams(location.search).get('t')||'';
function v(i){return document.getElementById(i).value.trim()}
function say(t,ok){var m=document.getElementById('msg');m.textContent=t;m.className=ok?'ok':'bad'}
function lkShow(){var k=v('lk');document.getElementById('ls').placeholder=k=='xtream'?'Server (http://example.com:8080)':k=='m3u'?'Playlist link':'Portal address';document.getElementById('lu').placeholder=k=='xtream'?'Username':k=='m3u'?'Guide link (optional)':'MAC address (optional)';document.getElementById('lp').style.display=k=='xtream'?'':'none'}
function api(p,b){return fetch(p+'?t='+encodeURIComponent(T),b?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)}:{}).then(function(r){return r.json()})}
function send(p,b,clear){say('...',true);api(p,b).then(function(r){say(r.ok?r.message:r.error,r.ok);if(r.ok){if(clear)document.getElementById(clear).value='';load()}}).catch(function(){say('Cannot reach the TV',false)})}
function bk(){var c=v('bc');if(c.length<6){say('The code needs at least 6 characters',false);return}say('Making the backup... (a few seconds)',true);
api('/api/backup/make',{code:c}).then(function(r){if(!r.ok){say(r.error,false);return}var a=document.createElement('a');a.href=URL.createObjectURL(new Blob([r.file],{type:'application/json'}));a.download=r.name;document.body.appendChild(a);a.click();a.remove();document.getElementById('bc').value='';say('Backup downloaded (also saved in the TV’s Downloads)',true)}).catch(function(){say('Cannot reach the TV',false)})}
function rs(){var f=document.getElementById('bkf').files[0],c=v('rc');if(!f){say('Choose the backup file first',false);return}if(!c){say('Type its code',false);return}
if(!confirm('Replace Glassio’s settings on the TV with this backup?'))return;say('Restoring...',true);var fr=new FileReader();
fr.onload=function(){api('/api/backup/restore',{code:c,file:fr.result}).then(function(r){say(r.ok?r.message:r.error,r.ok);if(r.ok)document.getElementById('rc').value=''}).catch(function(){say('Cannot reach the TV',false)})};fr.readAsText(f)}
function rep(){say('...',true);api('/api/report',{}).then(function(r){if(!r.ok){say(r.error,false);return}var a=document.createElement('a');a.href=URL.createObjectURL(new Blob([r.message],{type:'text/plain'}));a.download='glassio-report.txt';document.body.appendChild(a);a.click();a.remove();say('Report downloaded',true)}).catch(function(){say('Cannot reach the TV',false)})}
var LT='now',LTS=[['now','On now'],['find','Find'],['schedule','Your schedule'],['rec','Recordings']],liveOn=false;
function hm(t){return new Date(t).toLocaleTimeString([],{hour:'numeric',minute:'2-digit'})}
function dy(t){var d=new Date(t),n=new Date();if(d.toDateString()==n.toDateString())return 'Today';if(d.toDateString()==new Date(n.getTime()+864e5).toDateString())return 'Tomorrow';return d.toLocaleDateString([],{weekday:'short',day:'numeric',month:'short'})}
function chName(x){return (x.num>0?x.num+'  ':'')+x.name+(x.country?'  '+x.country:'')}
function get(p){return fetch(p+(p.indexOf('?')<0?'?':'&')+'t='+encodeURIComponent(T)).then(function(r){return r.json()})}
function lAct(p,b){say('...',true);api(p,b).then(function(r){say(r.ok?r.message:r.error,r.ok);if(r.ok&&LT!='now')lShow()}).catch(function(){say('Cannot reach the TV',false)})}
function btn(t,cls,f){var b=el('button',cls,t);b.onclick=f;return b}
function lTabs(){var r=document.getElementById('ltabs');r.textContent='';LTS.forEach(function(o){r.appendChild(btn(o[1],LT==o[0]?'':'s',function(){LT=o[0];lShow()}))});
document.getElementById('lfind').style.display=LT=='find'?'':'none';document.getElementById('lchq').style.display=LT=='now'?'':'none'}
function lShow(){lTabs();var L=document.getElementById('llist');L.textContent='';if(LT=='now')lCh();else if(LT=='find'){if(v('lq'))lFind()}
else if(LT=='schedule'){L.textContent='Reading...';get('/api/live/schedule').then(lSched)}else{L.textContent='Reading...';get('/api/live/recordings').then(lRecs)}}
var calOn=false;
function calB(x,p){return btn('Add to calendar','s',function(){location.href='/api/live/event.ics?t='+encodeURIComponent(T)+'&id='+x.id+'&start='+p.start+'&end='+p.end+'&title='+encodeURIComponent(p.title)})}
function setB(x,p,rec){return btn(rec?(p.record?'Recording':'Record'):(p.remind?'Reminder set':'Remind'),'s',function(){lAct('/api/live/set',{record:rec,id:x.id,start:p.start,end:p.end,title:p.title})})}
function lCh(){var L=document.getElementById('llist');L.textContent='Reading...';get('/api/live/channels?q='+encodeURIComponent(v('lc'))).then(function(d){L.textContent='';
if(d.error){L.textContent=d.error;return}if(!d.ready){L.textContent='Live TV is not signed in on the TV.';return}
if(!d.channels.length)L.textContent=v('lc')?'No channel like that.':'No favourite or recent channels yet: find one above.';
d.channels.forEach(function(x){var c=el('div','card');c.appendChild(el('b','',chName(x)));
if(x.now)c.appendChild(el('small','','Now: '+x.now.title+'  (until '+hm(x.now.end)+')'));
if(x.next){c.appendChild(el('br'));c.appendChild(el('small','',hm(x.next.start)+': '+x.next.title))}
var r=el('div','row');r.appendChild(btn('Watch on TV','s',function(){lAct('/api/live/watch',{id:x.id})}));
if(x.now)r.appendChild(btn('Record now','s',function(){lAct('/api/live/set',{record:true,id:x.id,start:x.now.start,end:x.now.end,title:x.now.title})}));
if(x.next){r.appendChild(btn('Remind me of next','s',function(){lAct('/api/live/set',{record:false,id:x.id,start:x.next.start,end:x.next.end,title:x.next.title})}));
r.appendChild(btn('Record next','s',function(){lAct('/api/live/set',{record:true,id:x.id,start:x.next.start,end:x.next.end,title:x.next.title})}))}
c.appendChild(r);L.appendChild(c)})}).catch(function(){L.textContent='Cannot reach the TV'})}
function lFind(){var L=document.getElementById('llist'),q=v('lq');if(!q)return;L.textContent='Looking in the guide...';get('/api/live/find?q='+encodeURIComponent(q)).then(function(d){L.textContent='';
if(d.error){L.textContent=d.error;return}if(!d.airings.length){L.textContent='Nothing in the next 7 days.';return}var now=Date.now();
d.airings.forEach(function(x){var c=el('div','card');c.appendChild(el('b','',x.title));c.appendChild(el('small','',dy(x.start)+' '+hm(x.start)+'  ·  '+chName(x)));
var r=el('div','row');if(x.start<=now)r.appendChild(btn('Watch on TV','s',function(){lAct('/api/live/watch',{id:x.id})}));
if(x.start>now)r.appendChild(setB(x,x,false));r.appendChild(setB(x,x,true));if(calOn)r.appendChild(calB(x,x));c.appendChild(r);L.appendChild(c)})}).catch(function(){L.textContent='Cannot reach the TV'})}
function lSched(d){var L=document.getElementById('llist');L.textContent='';if(d.error){L.textContent=d.error;return}if(!d.items.length){L.textContent='Nothing set.';return}
d.items.forEach(function(x){var c=el('div','card');var t=el('b','',x.title);t.appendChild(el('span','tag',x.kind=='record'?'recording':'reminder'));c.appendChild(t);
c.appendChild(el('small','',dy(x.start)+' '+hm(x.start)+'  ·  '+chName(x)));var r=el('div','row');
r.appendChild(btn('Cancel','s d',function(){lAct('/api/live/cancel',{key:x.key})}));
if(calOn){var k=x.key.split(':');r.appendChild(calB({id:parseInt(k[1],10)},x))}c.appendChild(r);L.appendChild(c)})}
function lRecs(d){var L=document.getElementById('llist');L.textContent='';if(d.error){L.textContent=d.error;return}if(!d.items.length){L.textContent='No recordings yet.';return}
d.items.forEach(function(x){var c=el('div','card');c.appendChild(el('b','',x.title));
c.appendChild(el('small','',dy(x.start)+' '+hm(x.start)+'  ·  '+chName(x)+'  ·  '+Math.round(x.size/1048576)+' MB'));var r=el('div','row');
r.appendChild(btn('Play on TV','s',function(){lAct('/api/live/play',{id:x.id})}));
r.appendChild(btn('Delete','s d',function(){if(confirm('Delete the recording of '+x.title+'? It can\'t be undone.'))lAct('/api/live/delete',{id:x.id})}));c.appendChild(r);L.appendChild(c)})}
function k(key){api('/api/key',{key:key}).then(function(r){if(!r.ok)say(r.error,false)}).catch(function(){say('Cannot reach the TV',false)})}
function addAddon(){var u=v('au');if(u)send('/api/addon/add',{url:u},'au')}
function el(t,c,x){var e=document.createElement(t);if(c)e.className=c;if(x!==undefined)e.textContent=x;return e}
function up(pkg,f){say('Uploading...',true);fetch('/api/icon/upload?pkg='+encodeURIComponent(pkg)+'&t='+encodeURIComponent(T),{method:'POST',body:f}).then(function(r){return r.json()}).then(function(r){say(r.ok?r.message:r.error,r.ok);load()}).catch(function(){say('Upload failed',false)})}
function bgLink(k,i){var u=v(i);if(u)send('/api/bg/link',{kind:k,url:u},i)}
function bgSave(i){var u=v(i);if(u)send('/api/bg/download',{url:u,use:true},i)}
function bgUp(){var f=document.getElementById('bf').files[0];if(!f)return;var x=new XMLHttpRequest(),p=document.getElementById('bgp');
x.open('POST','/api/bg/upload?use=1&name='+encodeURIComponent(f.name)+'&t='+encodeURIComponent(T));
x.upload.onprogress=function(e){if(e.lengthComputable)p.textContent='Uploading '+Math.round(e.loaded*100/e.total)+'%'};
x.onload=function(){p.textContent='';try{var r=JSON.parse(x.responseText);say(r.ok?r.message:r.error,r.ok)}catch(e){say('Upload failed',false)}document.getElementById('bf').value='';load()};
x.onerror=function(){p.textContent='';say('Upload failed',false)};say('Uploading...',true);x.send(f)}
var BGM=[['carousel','Title backdrops'],['image','My picture'],['video','My video'],['aerial','Screensaver']],poll=0;
function haYaml(){var b=location.origin+'/api/ha/',q='?t='+encodeURIComponent(T),h='    method: post\n    content_type: "application/json"\n';
return 'rest_command:\n  glassio_tune:\n    url: "'+b+'tune'+q+'"\n'+h+'    payload: \'{"channel": "{{ channel }}"}\'\n'
+'  glassio_notify:\n    url: "'+b+'notify'+q+'"\n'+h+'    payload: \'{"title": "{{ title }}", "message": "{{ message }}"}\'\n'
+'  glassio_pause:\n    url: "'+b+'pause'+q+'"\n'+h+'    payload: "{}"\n'
+'  glassio_play:\n    url: "'+b+'play'+q+'"\n'+h+'    payload: "{}"\n'}
function load(){document.getElementById('hay').value=haYaml();api('/api/state').then(function(d){
document.getElementById('livebox').style.display=d.live?'':'none';if(d.live&&!liveOn){liveOn=true;lShow()}
var ds=document.getElementById('davs');ds.textContent=d.dav?('Folder on '+d.dav+(d.davUser?' · '+d.davUser:'')+(d.davPass?' · password saved':'')+(d.davLast?' · last backup '+new Date(d.davLast).toLocaleString():'')):'Not set';
if(d.dav&&!v('du'))document.getElementById('du').placeholder='Saved (type the address again to change it)';if(d.davUser&&!v('dn'))document.getElementById('dn').value=d.davUser;
calOn=!!d.calendar;document.getElementById('calbox').style.display=calOn?'':'none';
if(calOn){var cu=location.host+'/api/live/calendar.ics?t='+encodeURIComponent(T);document.getElementById('calurl').value='webcal://'+cu;document.getElementById('calsub').href='webcal://'+cu}
var a=document.getElementById('addons');a.textContent='';d.addons.forEach(function(x){var c=el('div','card');var t=el('b','',x.name);if(x.account)t.appendChild(el('span','tag',(x.from||'Nuvio')+' account'));c.appendChild(t);c.appendChild(el('small','',x.host));if(!x.account){var r=el('div','row');var b=el('button','s d','Remove');b.onclick=function(){send('/api/addon/remove',{url:x.url})};r.appendChild(b);c.appendChild(r)}a.appendChild(c)});
var g=d.bg,m=document.getElementById('bgm');m.textContent='';BGM.forEach(function(o){var b=el('button',g.mode==o[0]?'':'s',o[1]);b.onclick=function(){send('/api/bg/mode',{mode:o[0]})};m.appendChild(b)});
document.getElementById('bgnow').textContent=(g.image?'Picture: '+g.image+'  ':'')+(g.video?'Video: '+g.video:'');
var bp=document.getElementById('bgp');if(g.downloading){bp.textContent='Downloading '+g.downloading+'...';clearTimeout(poll);poll=setTimeout(load,2000)}else if(g.last&&bp.textContent.indexOf('Downloading')==0){bp.textContent=g.last}
var bl=document.getElementById('bgfiles');bl.textContent='';g.files.forEach(function(f){var c=el('div','card');var t=el('b','',f.name);t.appendChild(el('span','tag',f.video?'video':'picture'));if(f.used)t.appendChild(el('span','tag','in use'));c.appendChild(t);c.appendChild(el('small','',f.mb+' MB'));var r=el('div','row');
var bu=el('button','s','Use');bu.onclick=function(){send('/api/bg/use',{name:f.name})};var bd=el('button','s d','Delete');bd.onclick=function(){if(confirm('Delete '+f.name+'?'))send('/api/bg/delete',{name:f.name})};r.appendChild(bu);r.appendChild(bd);c.appendChild(r);bl.appendChild(c)});
var p=document.getElementById('apps');p.textContent='';d.apps.forEach(function(x){var c=el('div','card');var t=el('b','',x.label);if(x.custom)t.appendChild(el('span','tag','custom'));c.appendChild(t);c.appendChild(el('small','',x.pkg));var r=el('div','row');
var bu=el('button','s','Link');bu.onclick=function(){var u=prompt('Image link');if(u)send('/api/icon/url',{pkg:x.pkg,url:u})};
var fi=el('input');fi.type='file';fi.accept='image/*';fi.style.display='none';fi.onchange=function(){if(fi.files[0])up(x.pkg,fi.files[0])};
var bf=el('button','s','Upload');bf.onclick=function(){fi.click()};
var br=el('button','s d','Reset');br.onclick=function(){send('/api/icon/reset',{pkg:x.pkg})};
var bo=el('button','s','Open');bo.onclick=function(){send('/api/app/open',{pkg:x.pkg})};
var bup=el('button','s','▲');bup.onclick=function(){send('/api/app/move',{pkg:x.pkg,dir:-1})};
var bdn=el('button','s','▼');bdn.onclick=function(){send('/api/app/move',{pkg:x.pkg,dir:1})};
r.appendChild(bo);r.appendChild(bup);r.appendChild(bdn);r.appendChild(bu);r.appendChild(bf);r.appendChild(fi);if(x.custom)r.appendChild(br);c.appendChild(r);p.appendChild(c)});
var ks=document.getElementById('tks');ks.textContent=d.tmdb?'\u2713 Set':'Not set';ks.className=d.tmdb?'ok':'';
document.getElementById('tk').placeholder=d.tmdb?'Token saved (type a new one to replace, empty to remove)':'TMDB read access token';
var hs=document.getElementById('ha');hs.previousElementSibling.innerHTML='Home Assistant webhook'+(d.ha?' <span class="ok">&#10003; Set</span>':'');
document.getElementById('ha').placeholder=d.ha?'Webhook saved (type a new one to replace)':'http://homeassistant.local:8123/api/webhook/your_id';
var cz=document.getElementById('cals');cz.textContent='';d.cals.forEach(function(h,i){var c=el('div','card');c.appendChild(el('b','',h));var r=el('div','row');var b=el('button','s d','Remove');b.onclick=function(){send('/api/cal/remove',{i:i})};r.appendChild(b);c.appendChild(r);cz.appendChild(c)});
var hb=document.getElementById('hab');hb.textContent=(d.haBase?'Address: '+d.haBase:'No address yet')+(d.haToken?' · token saved':'');document.getElementById('hu').placeholder=d.haBase||'http://homeassistant.local:8123';
var z=document.getElementById('aerial');z.textContent='';d.aerial.forEach(function(u){var c=el('div','card');c.appendChild(el('small','',u.replace(/^(https?:\/\/[^\/]+).*$/,'$1')));var r=el('div','row');var b=el('button','s d','Remove');b.onclick=function(){send('/api/aerial/remove',{url:u})};r.appendChild(b);c.appendChild(r);z.appendChild(c)})
}).catch(function(){say('Cannot reach the TV',false)})}
load();
</script></body></html>"""
    }
}
