package com.glasslauncher.tv.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sync with other TVs at home (Settings › System › Sync with other TVs): Live TV's favorites and profiles, followed teams
 * and shows, channel renames and numbers, reminders and where catch-up shows were left, kept the same on every paired
 * TV on the same Wi-Fi. Recordings stay on the TV that made them.
 *
 * Each TV keeps a snapshot of those things, each with when it last changed and on which TV ([Item]); syncing trades the
 * snapshots and keeps the newest of each (a deletion is a change too), then writes back what's different here. TVs pair
 * once with a code one shows and the other types (PBKDF2 of it, proofs both ways, a secret neither sends); after that every
 * message is signed with the pair's secret (HMAC-SHA256) and must be fresh. Found on the network by NSD
 * ("_glassiosync._tcp"). Runs in Home's process while the setting is on.
 */
object LanSync {
    // MARK: - Pure

    /** One thing's state: its value, when it last changed, on which TV, and whether it's gone. */
    data class Item(val v: String, val at: Long, val by: String, val del: Boolean = false)

    /** A deletion is remembered this long, so a TV that was off still hears of it. */
    const val FORGET_MS = 30L * 24 * 3600_000
    /** A signed message older (or newer) than this is refused: one sent again later isn't taken. */
    const val FRESH_MS = 10 * 60_000L

    /** The snapshot after looking at what's here now (`current`): the changed and the new marked now, the gone marked gone. */
    fun observe(prev: Map<String, Item>, current: Map<String, String>, now: Long, me: String): Map<String, Item> {
        val out = HashMap<String, Item>()
        for ((k, v) in current) {
            val p = prev[k]
            out[k] = if (p != null && !p.del && p.v == v) p else Item(v, now, me)
        }
        for ((k, p) in prev) if (k !in current) out[k] = if (p.del) p else Item("", now, me, true)
        return out
    }

    private fun newer(a: Item, b: Item) = a.at > b.at || a.at == b.at && (a.by > b.by || a.by == b.by && a.v > b.v)

    /** Both TVs' snapshots as one: each thing as the newest change made it. The same whichever TV merges. */
    fun merge(a: Map<String, Item>, b: Map<String, Item>): Map<String, Item> {
        val out = HashMap(a)
        for ((k, ib) in b) { val ia = out[k]; if (ia == null || newer(ib, ia)) out[k] = ib }
        return out
    }

    fun prune(m: Map<String, Item>, now: Long) = m.filter { !it.value.del || now - it.value.at <= FORGET_MS }

    /** What to write here so it's as `merged` says: a value, or null to take it out. */
    fun writes(here: Map<String, String>, merged: Map<String, Item>): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        for ((k, it) in merged) {
            if (it.del) { if (k in here) out[k] = null }
            else if (here[k] != it.v) out[k] = it.v
        }
        return out
    }

    private fun hmac(key: ByteArray, text: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(text.toByteArray(Charsets.UTF_8))

    private fun b64(b: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun unb64(s: String) = java.util.Base64.getUrlDecoder().decode(s)

    fun sign(secret: String, body: String) = b64(hmac(unb64(secret), body))

    fun verify(secret: String, body: String, sig: String) =
        runCatching { MessageDigest.isEqual(unb64(sign(secret, body)), unb64(sig)) }.getOrDefault(false)

    /** The key both TVs make from the code (and a salt the showing TV picked). */
    fun pairKey(code: String, salt: String): String {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return b64(f.generateSecret(PBEKeySpec(code.toCharArray(), unb64(salt), 120_000, 256)).encoded)
    }

    /** "I know the code": `role` A (the TV showing it) or B (the one typing it), over the other's nonce. */
    fun proof(key: String, role: String, nonce: String) = b64(hmac(unb64(key), "glassio-sync-proof|$role|$nonce"))

    /** The pair's secret, the same on both, never sent. */
    fun secret(key: String, nonceA: String, nonceB: String) = b64(hmac(unb64(key), "glassio-sync-secret|$nonceA|$nonceB"))

    private val rnd = SecureRandom()
    fun newCode() = String.format(java.util.Locale.ROOT, "%06d", rnd.nextInt(1_000_000))
    fun nonce(): String = ByteArray(18).also { rnd.nextBytes(it) }.let { b64(it) }

    // MARK: - This TV: its id, its pairs, its snapshot

    class Peer(val id: String, val name: String, val secret: String, var host: String, var port: Int)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)

    fun me(ctx: Context): String = sp(ctx).getString("syncId", null) ?: nonce().take(16).also { sp(ctx).edit().putString("syncId", it).apply() }

    fun myName(ctx: Context): String =
        runCatching { android.provider.Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL

    fun on(ctx: Context) = sp(ctx).getBoolean("syncOn", false)

    fun peers(ctx: Context): List<Peer> = runCatching {
        val a = JSONArray(sp(ctx).getString("syncPeers", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Peer(it.getString("id"), it.optString("name"), it.getString("secret"), it.optString("host"), it.optInt("port")) } }
    }.getOrDefault(emptyList())

    private fun savePeers(ctx: Context, list: List<Peer>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("secret", it.secret).put("host", it.host).put("port", it.port)) }
        sp(ctx).edit().putString("syncPeers", a.toString()).apply()
    }

    fun forget(ctx: Context, id: String) = savePeers(ctx, peers(ctx).filter { it.id != id })

    private fun stateFile(ctx: Context) = File(ctx.filesDir, "sync-state.json")

    private fun loadState(ctx: Context): Map<String, Item> = runCatching {
        val o = JSONObject(stateFile(ctx).readText())
        o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Item(it.optString("v"), it.optLong("at"), it.optString("by"), it.optBoolean("del")) } }
    }.getOrDefault(emptyMap())

    private fun json(m: Map<String, Item>) = JSONObject().apply {
        m.forEach { (k, it) -> put(k, JSONObject().put("v", it.v).put("at", it.at).put("by", it.by).put("del", it.del)) }
    }

    private fun items(o: JSONObject) = o.keys().asSequence().associateWith { k ->
        o.getJSONObject(k).let { Item(it.optString("v"), it.optLong("at"), it.optString("by"), it.optBoolean("del")) }
    }

    private fun saveState(ctx: Context, m: Map<String, Item>) { runCatching { stateFile(ctx).writeText(json(m).toString()) } }

    // MARK: - What syncs, read and written where Live TV keeps it

    private fun files(ctx: Context) = ctx.filesDir
    private fun profileFile(ctx: Context, id: String, name: String): File =
        if (id == com.glasslauncher.tv.live.LiveSyncStores.activeProfile(ctx)) File(files(ctx), name) else File(File(File(files(ctx), "profiles"), id), name)

    private fun text(f: File): String? = runCatching { if (f.isFile) f.readText().trim() else null }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Everything that syncs as it is here now, by key: profiles, fav:<profile>, resume:<profile>, follow, edits, remind:<key>. */
    fun read(ctx: Context): Map<String, String> {
        val out = HashMap<String, String>()
        val reg = runCatching { JSONObject(File(files(ctx), "live-profiles.json").readText()) }.getOrNull()
        val list = reg?.optJSONArray("list")
        if (list != null) out["profiles"] = list.toString()
        val ids = if (list == null || list.length() == 0) listOf("main") else (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("id") }
        for (id in ids) {
            text(profileFile(ctx, id, "live-favorites.json"))?.let { out["fav:$id"] = it }
            text(profileFile(ctx, id, "live-resume.json"))?.let { out["resume:$id"] = it }
        }
        text(File(files(ctx), "live-follow.json"))?.let { out["follow"] = it }
        text(File(files(ctx), "live-channel-edits.json"))?.let { out["edits"] = it }
        out.putAll(com.glasslauncher.tv.live.LiveSyncStores.reminders(ctx))
        return out
    }

    private fun put(f: File, v: String?) {
        if (v == null) { f.delete(); return }
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".sync")
        tmp.writeText(v)
        if (!tmp.renameTo(f)) { f.writeText(v); tmp.delete() }
    }

    private fun write(ctx: Context, key: String, v: String?) {
        when {
            key == "profiles" -> {
                if (v == null) return
                val f = File(files(ctx), "live-profiles.json")
                val reg = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
                val list = JSONArray(v)
                val ids = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("id") }
                reg.put("list", list)
                if (reg.optString("active", "main") !in ids && ids.isNotEmpty()) reg.put("active", ids[0])     // this one's profile went
                put(f, reg.toString())
            }
            key.startsWith("fav:") -> put(profileFile(ctx, key.removePrefix("fav:"), "live-favorites.json"), v)
            key.startsWith("resume:") -> put(profileFile(ctx, key.removePrefix("resume:"), "live-resume.json"), v)
            key == "follow" -> put(File(files(ctx), "live-follow.json"), v)
            key == "edits" -> put(File(files(ctx), "live-channel-edits.json"), v)
            key.startsWith("remind:") -> if (v == null) com.glasslauncher.tv.live.LiveSyncStores.cancelReminder(ctx, key.removePrefix("remind:"))
                else com.glasslauncher.tv.live.LiveSyncStores.putReminder(ctx, v)
        }
    }

    /** This TV's snapshot brought up to date, merged with another TV's, written back here; the result (to send back). */
    @Synchronized
    private fun mergeIn(ctx: Context, remote: Map<String, Item>): Map<String, Item> {
        val now = System.currentTimeMillis()
        val local = observe(loadState(ctx), read(ctx), now, me(ctx))
        val merged = prune(merge(local, remote), now)
        val w = writes(read(ctx), merged)
        w.forEach { (k, v) -> runCatching { write(ctx, k, v) }.onFailure { Glog.w("Sync: couldn't write $k", it) } }
        // What was written is read back as it is now (a reminder already over doesn't come back).
        val settled = if (w.isEmpty()) merged else prune(merge(merged, observe(merged, read(ctx), now, me(ctx))), now)
        saveState(ctx, settled)
        if (w.isNotEmpty()) {
            Glog.i("Sync: ${w.size} change(s) from another TV")
            com.glasslauncher.tv.live.LiveSyncStores.refreshLive(ctx)
        }
        return settled
    }

    /** This TV's own changes noticed (no other TV); true when anything changed since last time. */
    @Synchronized
    fun look(ctx: Context): Boolean {
        val before = loadState(ctx)
        val now = observe(before, read(ctx), System.currentTimeMillis(), me(ctx))
        if (now == before) return false
        saveState(ctx, now)
        return true
    }

    // MARK: - Talking to the other TVs

    private const val PORTS_FROM = 9097
    @Volatile private var server: ServerSocket? = null
    @Volatile private var port = 0
    @Volatile private var pairing: Triple<String, Long, String>? = null     // the code shown, until when, its salt
    @Volatile private var pending: JSONObject? = null                         // a pairing half done
    /** TVs with Glassio found on the network: id → (name, host, port). */
    val found = java.util.concurrent.ConcurrentHashMap<String, Triple<String, String, Int>>()

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

    private fun answer(c: Socket, code: Int, body: String, sig: String? = null) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "No"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
            (if (sig != null) "X-Glassio-Sig: $sig\r\n" else "") + "Connection: close\r\n\r\n"
        c.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    private fun handle(ctx: Context, c: Socket) {
        val input = c.getInputStream()
        val req = readLine(input) ?: return
        val path = req.split(' ').getOrNull(1)?.substringBefore('?') ?: return
        var len = 0
        var sig = ""
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':').trim()
            if (name == "content-length") len = value.toIntOrNull() ?: 0
            if (name == "x-glassio-sig") sig = value
        }
        if (len !in 0..(4 * 1024 * 1024)) return answer(c, 400, "{}")
        val body = ByteArray(len).also { var r = 0; while (r < len) { val n = input.read(it, r, len - r); if (n < 0) break; r += n } }.toString(Charsets.UTF_8)
        val j = runCatching { JSONObject(body) }.getOrNull() ?: return answer(c, 400, "{}")
        when (path) {
            "/pair/start" -> {
                val p = pairing
                if (p == null || System.currentTimeMillis() > p.second) return answer(c, 403, "{\"error\":\"Not pairing\"}")
                val key = pairKey(p.first, p.third)
                val nonceA = nonce()
                pending = JSONObject().put("id", j.optString("id")).put("name", j.optString("name")).put("key", key).put("nonceA", nonceA)
                    .put("nonceB", j.optString("nonce")).put("host", j.optString("host").ifBlank { c.inetAddress.hostAddress.orEmpty() }).put("port", j.optInt("port"))
                answer(c, 200, JSONObject().put("id", me(ctx)).put("name", myName(ctx)).put("nonce", nonceA).put("salt", p.third)
                    .put("proof", proof(key, "A", j.optString("nonce"))).put("port", port).toString())
            }
            "/pair/finish" -> {
                val pe = pending
                if (pe == null || pe.optString("id") != j.optString("id") || pairing == null) return answer(c, 403, "{\"error\":\"Not pairing\"}")
                val want = proof(pe.getString("key"), "B", pe.getString("nonceA"))
                if (!MessageDigest.isEqual(want.toByteArray(), j.optString("proof").toByteArray())) {
                    pending = null; pairing = null      // a wrong code ends this pairing: show a new one
                    return answer(c, 403, "{\"error\":\"Wrong code\"}")
                }
                val secret = secret(pe.getString("key"), pe.getString("nonceA"), pe.getString("nonceB"))
                savePeers(ctx, peers(ctx).filter { it.id != pe.getString("id") } +
                    Peer(pe.getString("id"), pe.optString("name"), secret, pe.optString("host"), pe.optInt("port")))
                pending = null; pairing = null
                Glog.i("Sync: paired with ${pe.optString("name")}")
                answer(c, 200, "{\"ok\":true}")
            }
            "/sync" -> {
                val peer = peers(ctx).firstOrNull { it.id == j.optString("from") } ?: return answer(c, 403, "{\"error\":\"Not paired\"}")
                if (!verify(peer.secret, body, sig) || Math.abs(System.currentTimeMillis() - j.optLong("sent")) > FRESH_MS) return answer(c, 403, "{\"error\":\"Not signed\"}")
                val merged = mergeIn(ctx, items(j.optJSONObject("items") ?: JSONObject()))
                val out = JSONObject().put("from", me(ctx)).put("sent", System.currentTimeMillis()).put("items", json(merged)).toString()
                answer(c, 200, out, sign(peer.secret, out))
            }
            else -> answer(c, 404, "{}")
        }
    }

    private fun post(host: String, port: Int, path: String, body: String, sig: String?): Pair<String, String> {
        val c = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 20000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        if (sig != null) c.setRequestProperty("X-Glassio-Sig", sig)
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null } ?: "The other TV answered $code" }
            return text to c.getHeaderField("X-Glassio-Sig").orEmpty()
        } finally { c.disconnect() }
    }

    /** This TV shows a code for two minutes: the other TV types it. */
    fun showCode(): String {
        val code = newCode()
        pairing = Triple(code, System.currentTimeMillis() + 2 * 60_000L, nonce())
        return code
    }

    /** Pairs with the TV at `host`:`port` showing `code`. Blocking. */
    fun pairWith(ctx: Context, host: String, port: Int, code: String): String {
        val nonceB = nonce()
        val (a, _) = post(host, port, "/pair/start", JSONObject().put("id", me(ctx)).put("name", myName(ctx)).put("nonce", nonceB).put("port", this.port).toString(), null)
        val r = JSONObject(a)
        val key = pairKey(code.filter { it.isDigit() }, r.getString("salt"))
        check(MessageDigest.isEqual(proof(key, "A", nonceB).toByteArray(), r.getString("proof").toByteArray())) { "Wrong code" }
        post(host, port, "/pair/finish", JSONObject().put("id", me(ctx)).put("proof", proof(key, "B", r.getString("nonce"))).toString(), null)
        savePeers(ctx, peers(ctx).filter { it.id != r.getString("id") } +
            Peer(r.getString("id"), r.optString("name"), secret(key, r.getString("nonce"), nonceB), host, r.optInt("port", port)))
        return r.optString("name")
    }

    /** Syncs with every paired TV that answers; how many did. Blocking. */
    fun syncNow(ctx: Context): Int {
        var done = 0
        for (p in peers(ctx)) {
            val at = found[p.id]
            val host = at?.second ?: p.host
            val port = at?.third ?: p.port
            runCatching {
                val local = mergeIn(ctx, emptyMap())
                val body = JSONObject().put("from", me(ctx)).put("sent", System.currentTimeMillis()).put("items", json(local)).toString()
                val (text, sig) = post(host, port, "/sync", body, sign(p.secret, body))
                check(verify(p.secret, text, sig)) { "The answer wasn't signed" }
                val r = JSONObject(text)
                check(Math.abs(System.currentTimeMillis() - r.optLong("sent")) <= FRESH_MS) { "The answer was old" }
                mergeIn(ctx, items(r.optJSONObject("items") ?: JSONObject()))
                if (host != p.host || port != p.port) savePeers(ctx, peers(ctx).map { if (it.id == p.id) Peer(it.id, it.name, it.secret, host, port) else it })
            }.fold({ done++ }, { Glog.w("Sync with ${p.name} didn't work", it) })
        }
        return done
    }

    // MARK: - Running (Home's process)

    @Volatile private var loop: Thread? = null
    private var nsdReg: NsdManager.RegistrationListener? = null
    private var nsdFind: NsdManager.DiscoveryListener? = null

    /** On or off as the setting is. */
    @Synchronized
    fun apply(ctx: Context) {
        val app = ctx.applicationContext
        if (!on(app)) { stop(app); return }
        if (server != null) return
        for (p in PORTS_FROM..PORTS_FROM + 2) {
            val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p), 8) } }.getOrNull() ?: continue
            server = s; port = p; break
        }
        val s = server ?: run { Glog.w("Sync: no free port"); return }
        Thread({
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                Thread({ runCatching { c.soTimeout = 20_000; handle(app, c) }; runCatching { c.close() } }, "sync-in").apply { isDaemon = true; start() }
            }
        }, "sync-server").apply { isDaemon = true; start() }
        nsd(app)
        loop = Thread({
            var last = 0L
            while (loop === Thread.currentThread()) {
                runCatching {
                    val changed = look(app)
                    if (peers(app).isNotEmpty() && (changed || System.currentTimeMillis() - last > 10 * 60_000L)) { syncNow(app); last = System.currentTimeMillis() }
                }
                try { Thread.sleep(60_000) } catch (_: InterruptedException) { break }
            }
        }, "sync-loop").apply { isDaemon = true; start() }
        Glog.i("Sync on, port $port")
    }

    @Synchronized
    private fun stop(ctx: Context) {
        loop?.interrupt(); loop = null
        runCatching { server?.close() }; server = null
        val m = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager
        nsdReg?.let { runCatching { m?.unregisterService(it) } }; nsdReg = null
        nsdFind?.let { runCatching { m?.stopServiceDiscovery(it) } }; nsdFind = null
        found.clear()
    }

    private fun nsd(ctx: Context) {
        val m = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply { serviceName = "Glassio " + me(ctx).take(6); serviceType = "_glassiosync._tcp"; port = this@LanSync.port; setAttribute("id", me(ctx)); setAttribute("name", myName(ctx)) }
        nsdReg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Glog.w("Sync: not on the network ($e)") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        runCatching { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdReg) }
        val queue = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()
        Thread({
            while (nsdFind != null || queue.isNotEmpty()) {
                val next = queue.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                val done = java.util.concurrent.CountDownLatch(1)
                runCatching {
                    @Suppress("DEPRECATION")
                    m.resolveService(next, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, e: Int) { done.countDown() }
                        override fun onServiceResolved(i: NsdServiceInfo) {
                            val id = i.attributes["id"]?.toString(Charsets.UTF_8)
                            @Suppress("DEPRECATION") val host = i.host?.hostAddress
                            if (id != null && id != me(ctx) && host != null) found[id] = Triple(i.attributes["name"]?.toString(Charsets.UTF_8) ?: i.serviceName, host, i.port)
                            done.countDown()
                        }
                    })
                }
                done.await(8, java.util.concurrent.TimeUnit.SECONDS)
            }
        }, "sync-resolve").apply { isDaemon = true; start() }
        nsdFind = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(i: NsdServiceInfo) { queue.offer(i) }
            override fun onServiceLost(i: NsdServiceInfo) {}
        }
        runCatching { m.discoverServices("_glassiosync._tcp", NsdManager.PROTOCOL_DNS_SD, nsdFind) }
    }
}
