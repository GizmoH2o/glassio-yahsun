package com.glasslauncher.tv.data

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Report a problem (the phone page): a text file of what's useful to look into something, without anyone's accounts:
 * the version and TV, the settings that aren't private, the last crash and Glassio's recent log, with tokens, links,
 * addresses and e-mails taken out.
 */
object ProblemReport {
    /** Settings never shown, whatever they hold. */
    private val PRIVATE = Regex("token|pass|pin|secret|auth|key|code|webhook|url|link|mail|user|city|ha[A-Z]|cal|bg(Image|Video)|aerial|entities", RegexOption.IGNORE_CASE)

    internal fun privateSetting(name: String) = PRIVATE.containsMatchIn(name)

    /** Takes out what could identify someone or sign in as them from a line of log. Pure. */
    internal fun scrub(text: String): String = text
        .replace(Regex("""eyJ[\w-]{10,}(\.[\w-]+)*"""), "[token]")
        .replace(Regex("""(?i)(token|key|password|auth|secret|t)=[^\s&"']+"""), "$1=[hidden]")
        .replace(Regex("""(?i)bearer\s+\S+"""), "Bearer [hidden]")
        .replace(Regex("""https?://[^\s"')]+""")) { m -> runCatching { java.net.URI(m.value).host }.getOrNull()?.let { "https://$it/…" } ?: "[link]" }
        .replace(Regex("""[\w.+-]+@[\w-]+\.[\w.]+"""), "[e-mail]")
        .replace(Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b"""), "[address]")

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
        appendLine("── Last crash")
        appendLine(File(ctx.filesDir, "last-crash.txt").takeIf { it.exists() }?.readText()?.let { scrub(it).take(6000) } ?: "None")
        appendLine()
        appendLine("── Recent log")
        appendLine(runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "500", "--pid=" + android.os.Process.myPid()))
            scrub(p.inputStream.bufferedReader().use { it.readText() })
        }.getOrDefault("Couldn't be read"))
    }
}
