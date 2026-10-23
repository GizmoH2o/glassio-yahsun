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
        if (!MessageDigest.isEqual(query(q, "t").toByteArray(), token.toByteArray())) { respond(c, 403, "text/plain", "Forbidden"); return }
        if (method == "GET" && path == "/") { respond(c, 200, "text/html", PAGE); return }
        if (method == "GET" && path == "/api/state") { respond(c, 200, "application/json", state().toString()); return }
        if (method != "POST") { respond(c, 404, "text/plain", "Not found"); return }
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

    private fun icon(pkg: String, bytes: ByteArray): String {
        require(pkg.isNotBlank()) { "Missing app" }
        app.icons.setFromBytes(pkg, bytes)
        return "Icon updated"
    }

    private fun post(path: String, j: JSONObject): String = when (path) {
        "/api/addon/add" -> runBlocking { app.catalogs.addAddon(j.optString("url")) }
        "/api/addon/remove" -> runBlocking { app.catalogs.removeAddon(j.optString("url")) }
        "/api/icon/url" -> { app.icons.setFromUrl(j.optString("pkg"), j.optString("url").trim()); "Icon updated" }
        "/api/icon/reset" -> { app.icons.reset(j.optString("pkg")); "Icon reset" }
        "/api/tmdb" -> {
            app.settings.tmdbToken = j.optString("token"); app.catalogs.clearEnrichment()
            if (app.settings.tmdbToken.isBlank()) "TMDB token removed" else "TMDB token saved"
        }
        "/api/ha" -> { app.settings.haWebhook = j.optString("url"); app.settings.haOnLaunch = app.settings.haWebhook.startsWith("http"); "Home Assistant webhook saved" }
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
        app.catalogs.addons.forEach { addons.put(JSONObject().put("url", it).put("name", app.catalogs.addonName(it)).put("host", runCatching { java.net.URI(it).host }.getOrNull() ?: "")) }
        val apps = JSONArray()
        app.apps.ordered(includeHidden = true).forEach {
            apps.put(JSONObject().put("pkg", it.pkg).put("label", it.label).put("custom", app.icons.fileFor(it.pkg).exists()))
        }
        val aerial = JSONArray(); app.settings.aerialUrls.forEach { aerial.put(it) }
        return JSONObject().put("addons", addons).put("apps", apps).put("aerial", aerial)
            .put("tmdb", app.settings.tmdbToken.isNotBlank()).put("ha", app.settings.haWebhook.isNotBlank())
    }

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m).toString()

    private fun respond(c: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        val out = c.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8)); out.write(bytes); out.flush()
    }

    private companion object {
        const val PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Glassio</title><style>
body{margin:0;background:#0b0d16;color:#eef1fb;font:16px/1.5 -apple-system,Roboto,Segoe UI,sans-serif}
main{max-width:620px;margin:0 auto;padding:22px 16px 60px}h1{font-size:26px;margin:6px 0 0}h2{font-size:13px;letter-spacing:.12em;color:#9d8cff;margin:30px 0 8px;text-transform:uppercase}
p{color:#98a2bd;margin:4px 0 12px}input[type=text],textarea{width:100%;box-sizing:border-box;background:#141827;color:#eef1fb;border:1px solid #232a40;border-radius:12px;padding:12px;font:15px monospace}
button{background:#7b61ff;color:#fff;border:0;border-radius:12px;padding:11px 16px;font-size:15px;font-weight:600;margin-top:8px}
button.s{background:#232a40;color:#eef1fb;padding:8px 12px;font-size:13px;margin:0 4px 0 0}button.d{background:#3a1f2b;color:#ff8f9b}
#msg{position:sticky;top:0;padding:10px 0;min-height:1.4em;font-weight:600}.ok{color:#6ee7b7}.bad{color:#ff8f9b}
.card{background:#141827;border-radius:14px;padding:12px 14px;margin-bottom:8px}.card b{display:block}.card small{color:#6b7794;word-break:break-all}
.row{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}.tag{font-size:11px;background:#2a2150;color:#c8bdff;border-radius:6px;padding:1px 6px;margin-left:6px}
</style></head><body><main>
<h1>Glassio</h1><p>Manage your TV from here &middot; &#1573;&#1583;&#1575;&#1585;&#1577; &#1575;&#1604;&#1578;&#1604;&#1601;&#1586;&#1610;&#1608;&#1606; &#1605;&#1606; &#1607;&#1606;&#1575;</p>
<div id="msg"></div>
<h2>Add-ons</h2><div id="addons"></div>
<input type="text" id="au" placeholder="https://.../manifest.json  or  stremio://..."><button onclick="addAddon()">Add add-on</button>
<h2>App icons</h2><p>Pick an app, then paste an image link or upload an image from this phone.</p><div id="apps"></div>
<h2>TMDB token (logos, synopsis, ratings)</h2><input type="text" id="tk" placeholder="TMDB read access token"><button onclick="send('/api/tmdb',{token:v('tk')},'tk')">Save token</button>
<h2>Home Assistant webhook</h2><input type="text" id="ha" placeholder="http://homeassistant.local:8123/api/webhook/your_id"><button onclick="send('/api/ha',{url:v('ha')},'ha')">Save webhook</button>
<h2>Screensaver video links</h2><div id="aerial"></div>
<input type="text" id="av" placeholder="https://.../video.mp4"><button onclick="send('/api/aerial/add',{url:v('av')},'av')">Add video</button>
</main><script>
var T=new URLSearchParams(location.search).get('t')||'';
function v(i){return document.getElementById(i).value.trim()}
function say(t,ok){var m=document.getElementById('msg');m.textContent=t;m.className=ok?'ok':'bad'}
function api(p,b){return fetch(p+'?t='+encodeURIComponent(T),b?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)}:{}).then(function(r){return r.json()})}
function send(p,b,clear){say('...',true);api(p,b).then(function(r){say(r.ok?r.message:r.error,r.ok);if(r.ok){if(clear)document.getElementById(clear).value='';load()}}).catch(function(){say('Cannot reach the TV',false)})}
function addAddon(){var u=v('au');if(u)send('/api/addon/add',{url:u},'au')}
function el(t,c,x){var e=document.createElement(t);if(c)e.className=c;if(x!==undefined)e.textContent=x;return e}
function up(pkg,f){say('Uploading...',true);fetch('/api/icon/upload?pkg='+encodeURIComponent(pkg)+'&t='+encodeURIComponent(T),{method:'POST',body:f}).then(function(r){return r.json()}).then(function(r){say(r.ok?r.message:r.error,r.ok);load()}).catch(function(){say('Upload failed',false)})}
function load(){api('/api/state').then(function(d){
var a=document.getElementById('addons');a.textContent='';d.addons.forEach(function(x){var c=el('div','card');c.appendChild(el('b','',x.name));c.appendChild(el('small','',x.host));var r=el('div','row');var b=el('button','s d','Remove');b.onclick=function(){send('/api/addon/remove',{url:x.url})};r.appendChild(b);c.appendChild(r);a.appendChild(c)});
var p=document.getElementById('apps');p.textContent='';d.apps.forEach(function(x){var c=el('div','card');var t=el('b','',x.label);if(x.custom)t.appendChild(el('span','tag','custom'));c.appendChild(t);c.appendChild(el('small','',x.pkg));var r=el('div','row');
var bu=el('button','s','Link');bu.onclick=function(){var u=prompt('Image link');if(u)send('/api/icon/url',{pkg:x.pkg,url:u})};
var fi=el('input');fi.type='file';fi.accept='image/*';fi.style.display='none';fi.onchange=function(){if(fi.files[0])up(x.pkg,fi.files[0])};
var bf=el('button','s','Upload');bf.onclick=function(){fi.click()};
var br=el('button','s d','Reset');br.onclick=function(){send('/api/icon/reset',{pkg:x.pkg})};
r.appendChild(bu);r.appendChild(bf);r.appendChild(fi);if(x.custom)r.appendChild(br);c.appendChild(r);p.appendChild(c)});
document.getElementById('tk').placeholder=d.tmdb?'Token saved (type a new one to replace, empty to remove)':'TMDB read access token';
document.getElementById('ha').placeholder=d.ha?'Webhook saved (type a new one to replace)':'http://homeassistant.local:8123/api/webhook/your_id';
var z=document.getElementById('aerial');z.textContent='';d.aerial.forEach(function(u){var c=el('div','card');c.appendChild(el('small','',u.replace(/^(https?:\/\/[^\/]+).*$/,'$1')));var r=el('div','row');var b=el('button','s d','Remove');b.onclick=function(){send('/api/aerial/remove',{url:u})};r.appendChild(b);c.appendChild(r);z.appendChild(c)})
}).catch(function(){say('Cannot reach the TV',false)})}
load();
</script></body></html>"""
    }
}
