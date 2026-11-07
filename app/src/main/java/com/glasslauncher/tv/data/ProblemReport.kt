package com.glasslauncher.tv.data

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Report a problem (the phone page, or saved to the TV's Downloads): a text file of what's useful to look into
 * something, without anyone's accounts: the version and TV, the settings that aren't private, Live TV's guide,
 * connection and trouble, the last crash and the recent log of all of Glassio's processes, with tokens, links, addresses
 * and e-mails taken out.
 */
object ProblemReport {
    private const val NAME = "Glassio-problem-report.txt"

    /** Settings never shown, whatever they hold. */
    private val PRIVATE = Regex("token|pass|pin|secret|auth|key|code|webhook|url|link|mail|user|city|ha[A-Z]|cal|bg(Image|Video)|aerial|entities|hidden|saved|profile|name|title", RegexOption.IGNORE_CASE)

    internal fun privateSetting(name: String) = PRIVATE.containsMatchIn(name)

    /** Whether one of Live TV's settings belongs in a report: not its per-channel ones, nor anything private. Pure. */
    internal fun liveSetting(name: String) = ':' !in name && !privateSetting(name)

    /** The recent log. No process named: an app reads its own lines, and Live TV has a process of its own. */
    internal fun logCommand() = arrayOf("logcat", "-d", "-t", "1500")

    /** Takes out what could identify someone or sign in as them from a line of log. Pure. */
    internal fun scrub(text: String): String = text
        .replace(Regex("""eyJ[\w-]{10,}(\.[\w-]+)*"""), "[token]")
        .replace(Regex("""(?i)(token|key|password|auth|secret|t)=[^\s&"']+"""), "$1=[hidden]")
        .replace(Regex("""(?i)bearer\s+\S+"""), "Bearer [hidden]")
        .replace(Regex("""https?://[^\s"')]+""")) { m -> runCatching { java.net.URI(m.value).host }.getOrNull()?.let { "https://$it/…" } ?: "[link]" }
        .replace(Regex("""[\w.+-]+@[\w-]+\.[\w.]+"""), "[e-mail]")
        .replace(Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b"""), "[address]")

    /** The report in the TV's Downloads, in place of the last one. Blocking. */
    fun saveToDownloads(ctx: Context, version: String) {
        val r = ctx.contentResolver
        val bytes = build(ctx, version).toByteArray()
        if (Build.VERSION.SDK_INT >= 29) {
            r.delete(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " = ?", arrayOf(NAME))
            val v = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, NAME)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            }
            val uri = r.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("No Downloads folder")
            (r.openOutputStream(uri) ?: error("Couldn't write to Downloads")).use { it.write(bytes) }
        } else {
            @Suppress("DEPRECATION")
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), NAME).writeBytes(bytes)
        }
    }

    fun build(ctx: Context, version: String): String = buildString {
        appendLine("Glassio Yahsun $version")
        appendLine("TV: ${Build.BRAND} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Web view: " + (runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.versionName }.getOrNull() ?: "unknown"))
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        appendLine("Memory: ${mem.availMem / 1048576} MB free of ${mem.totalMem / 1048576} MB")
        appendLine()
        appendLine("── Settings (private ones left out)")
        ctx.getSharedPreferences("glass", Context.MODE_PRIVATE).all.toSortedMap().forEach { (k, v) ->
            if (!privateSetting(k)) appendLine("$k = ${scrub(v.toString()).take(120)}")
        }
        appendLine()
        appendLine("── Live TV")
        appendLine(runCatching { scrub(com.glasslauncher.tv.live.LiveReport.text(ctx)) }.getOrDefault("Couldn't be read"))
        ctx.getSharedPreferences("live-options", Context.MODE_PRIVATE).all.toSortedMap().forEach { (k, v) ->
            if (liveSetting(k)) appendLine("$k = ${scrub(v.toString()).take(120)}")
        }
        appendLine()
        appendLine("── Live TV's log")
        appendLine(File(ctx.filesDir, "live-log.txt").takeIf { it.exists() }?.readText()?.let { scrub(it).takeLast(40_000) } ?: "None yet")
        appendLine()
        appendLine("── Last crash")
        appendLine(File(ctx.filesDir, "last-crash.txt").takeIf { it.exists() }?.readText()?.let { scrub(it).take(6000) } ?: "None")
        appendLine()
        appendLine("── Recent log")
        appendLine(runCatching {
            val p = Runtime.getRuntime().exec(logCommand())
            scrub(p.inputStream.bufferedReader().use { it.readText() })
        }.getOrDefault("Couldn't be read"))
    }
}
