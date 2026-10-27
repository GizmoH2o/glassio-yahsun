package com.glasslauncher.tv.data

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Glassio Yahsun's backup: every setting of Glassio and Live TV, sign-ins included (Nuvio, Trakt, TMDB, Live TV's
 * playlists and their logins), the app icons you picked, Live TV's favorites, Following, profiles and reminders.
 * Not the recordings, nor anything that's only a copy of what's online (channel lists over [FILE_MAX], the guide).
 *
 * One file, "Glassio Yahsun backup.json": a plain header, then everything gzipped and locked with AES-256-GCM under a
 * key made from the code you choose (PBKDF2, [ITERATIONS] rounds). Without the code the file says nothing. It goes to
 * the TV's Downloads (which an uninstall leaves alone) and through the phone page to and from your phone.
 */
object Backup {
    const val NAME = "Glassio Yahsun backup.json"
    const val MIN_CODE = 6
    private const val FORMAT = "glassio-yahsun-backup"
    private const val ITERATIONS = BackupCrypto.ITERATIONS
    /** One file bigger than this stays out (a provider's whole channel list: Live TV reads it again); all together. */
    private const val FILE_MAX = 4L * 1024 * 1024
    private const val TOTAL_MAX = 16L * 1024 * 1024
    /**
     * Never in a backup: recordings (big, and the TV's own), what's only for now (the channel on, the last start, the
     * small window's handover, a crash), Android's own startup-speed markers (they belong to this install), half-written files,
     * and what's only a copy of what's online (Live TV's logo pictures, its logo and episode look-ups).
     */
    private val SKIP_DIRS = setOf("recordings", "drop", "live-logos")
    private val SKIP_FILES = setOf("mini_live.json", "last-crash.txt", "live-watching.txt", "live-last-boot", "profileInstalled",
        "live-public-matches.json", "live-tmdb-episodes.json")

    class Restored(val settings: Int, val files: Int)

    /** A backup older than this, or none at all: Settings suggests making one. */
    const val REMIND_AFTER_DAYS = 30
    private const val NOTE = "glass_backup"

    /** When the last backup was made on this TV (0: never), for Settings' reminder. */
    fun lastMade(ctx: Context): Long = ctx.getSharedPreferences(NOTE, Context.MODE_PRIVATE).getLong("made", 0L)

    private fun noteMade(ctx: Context, at: Long) {
        ctx.getSharedPreferences(NOTE, Context.MODE_PRIVATE).edit().putLong("made", at).apply()
    }

    /** Settings' line under Back up settings: when the last one was, or a nudge when there's none or it's old. */
    fun reminder(ctx: Context, now: Long = System.currentTimeMillis()): String {
        val at = lastMade(ctx)
        if (at <= 0L) return "No backup yet: make one, so a reset or a new TV takes a minute"
        val days = ((now - at) / 86_400_000L).toInt()
        val day = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(at))
        return if (days >= REMIND_AFTER_DAYS) "Last backup $day, $days days ago: time for a new one"
        else "Last backup $day. Locked with a code; saved in Downloads, and to your phone from Manage from phone"
    }

    /** The backup locked with [code]. Blocking (a second or two: the key is slow to make on purpose). */
    fun make(ctx: Context, code: String): ByteArray {
        require(code.length >= MIN_CODE) { "The code needs at least $MIN_CODE characters" }
        val prefs = JSONObject()
        for (name in prefNames(ctx)) {
            val values = JSONObject()
            for ((k, v) in ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                if (v == null) continue
                val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is Set<*> -> "s"; else -> "t" }
                values.put(k, JSONObject().put("t", t).put("v", if (v is Set<*>) JSONArray(v) else v))
            }
            prefs.put(name, values)
        }
        val files = JSONObject()
        var total = 0L
        for ((rel, f) in backedUpFiles(ctx)) {
            if (total + f.length() > TOTAL_MAX) continue
            total += f.length()
            files.put(rel, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        }
        val payload = JSONObject().put("prefs", prefs).put("files", files).toString().toByteArray(Charsets.UTF_8)
        val sealed = BackupCrypto.seal(payload, code, ITERATIONS)
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        val made = System.currentTimeMillis()
        noteMade(ctx, made)
        return JSONObject().put("format", FORMAT).put("version", 1).put("app", version).put("made", made)
            .put("kdf", "PBKDF2WithHmacSHA256").put("iterations", ITERATIONS)
            .put("salt", b64(sealed.salt)).put("iv", b64(sealed.iv)).put("data", b64(sealed.data))
            .toString().toByteArray(Charsets.UTF_8)
    }

    /** When the file was made (0: not a backup). */
    fun madeAt(bytes: ByteArray): Long = runCatching { header(bytes).optLong("made") }.getOrDefault(0L)

    /**
     * Puts the backup in place of what's here now. Live TV's processes stop first (they'd write their old settings
     * back); the caller restarts Glassio right after ([restart]). Blocking.
     */
    fun restore(ctx: Context, bytes: ByteArray, code: String): Restored {
        val h = header(bytes)
        val sealed = BackupCrypto.Sealed(unb64(h.getString("salt")), unb64(h.getString("iv")), unb64(h.getString("data")))
        val opened = try {
            BackupCrypto.open(sealed, code, h.optInt("iterations", ITERATIONS))
        } catch (e: BackupCrypto.WrongCode) {
            error("Wrong code")
        }
        val payload = JSONObject(opened.toString(Charsets.UTF_8))
        stopOtherProcesses(ctx)
        var settings = 0
        val prefs = payload.optJSONObject("prefs") ?: JSONObject()
        for (name in prefs.keys()) {
            if (!safeName(name)) continue
            val values = prefs.getJSONObject(name)
            val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (k in values.keys()) {
                val o = values.getJSONObject(k)
                when (o.optString("t")) {
                    "b" -> e.putBoolean(k, o.optBoolean("v"))
                    "i" -> e.putInt(k, o.optInt("v"))
                    "l" -> e.putLong(k, o.optLong("v"))
                    "f" -> e.putFloat(k, o.optDouble("v").toFloat())
                    "s" -> e.putStringSet(k, o.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet())
                    else -> e.putString(k, o.optString("v"))
                }
                settings++
            }
            e.commit()
        }
        // The files: what's here now in the same places goes (another account's login, say), then the backup's.
        for ((_, f) in backedUpFiles(ctx)) f.delete()
        val files = payload.optJSONObject("files") ?: JSONObject()
        var n = 0
        val root = ctx.filesDir.canonicalFile
        for (rel in files.keys()) {
            val f = File(root, rel).canonicalFile
            if (!f.path.startsWith(root.path + File.separator)) continue      // only inside the app's own folder
            f.parentFile?.mkdirs()
            f.writeBytes(unb64(files.getString(rel)))
            n++
        }
        return Restored(settings, n)
    }

    /** Glassio again with what was restored: a new process (this one still holds the old settings in memory). */
    fun restart(ctx: Context) {
        com.glasslauncher.tv.RestartActivity.restart(ctx)
    }

    /** The backup in the TV's Downloads (replacing the last one Glassio saved there). */
    fun saveToDownloads(ctx: Context, bytes: ByteArray) {
        val r = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            ownInDownloads(ctx)?.let { r.delete(it, null, null) }
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            }
            val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("No Downloads folder")
            (r.openOutputStream(uri) ?: error("Couldn't write to Downloads")).use { it.write(bytes) }
        } else {
            @Suppress("DEPRECATION")
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), NAME).writeBytes(bytes)
        }
    }

    /**
     * A backup on the TV, newest first: one sent from the phone page (the app's backup folder), the one Glassio saved
     * in Downloads, else (after a reinstall Android hides that one from the new install) read through the TV's own
     * adb (TV access). Null when there's none. Blocking.
     */
    fun findOnTv(ctx: Context): ByteArray? {
        incomingFile(ctx).takeIf { it.isFile }?.let { return it.readBytes() }
        if (Build.VERSION.SDK_INT >= 29) ownInDownloads(ctx)?.let { uri ->
            runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()?.let { return it }
        }
        val shell = LocalShell.run(ctx, "base64 -w 0 '/sdcard/Download/$NAME' 2>/dev/null || true").getOrNull()?.trim().orEmpty()
        if (shell.isNotEmpty()) runCatching { Base64.decode(shell, Base64.DEFAULT) }.getOrNull()?.takeIf { madeAt(it) > 0 }?.let { return it }
        return null
    }

    /** Where the phone page puts a backup it receives (the app's own folder, so no permission is asked). */
    fun incomingFile(ctx: Context): File = File(ctx.getExternalFilesDir("backup") ?: File(ctx.filesDir, "backup-in"), NAME)

    private fun ownInDownloads(ctx: Context): android.net.Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(NAME), "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
            if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
        }
        return null
    }

    /** The app's preference files, but the libraries' own. */
    private fun prefNames(ctx: Context): List<String> =
        File(ctx.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.name.endsWith(".xml") }.map { it.name.removeSuffix(".xml") }
            .filter { safeName(it) && !it.startsWith("androidx.") && !it.startsWith("com.google.") && !it.startsWith("WebView") }

    private fun safeName(n: String) = n.isNotBlank() && !n.contains('/') && !n.contains("..")

    /** Every file under the app's folder a backup keeps (by its path in there), small enough. */
    private fun backedUpFiles(ctx: Context): List<Pair<String, File>> {
        val root = ctx.filesDir
        val out = ArrayList<Pair<String, File>>()
        fun walk(dir: File, rel: String) {
            for (f in dir.listFiles().orEmpty()) {
                val path = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                when {
                    f.isDirectory -> if (rel.isEmpty() && f.name in SKIP_DIRS) Unit else walk(f, path)
                    f.name in SKIP_FILES || f.name.startsWith("profileinstaller") || f.name.endsWith(".tmp") || f.name.endsWith(".part") -> Unit
                    f.length() > FILE_MAX -> Unit
                    else -> out += path to f
                }
            }
        }
        walk(root, "")
        return out
    }

    /** Live TV and its recordings run in their own processes: stopped, so they don't write their old settings back. */
    private fun stopOtherProcesses(ctx: Context) {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return
        val me = android.os.Process.myPid()
        am.runningAppProcesses.orEmpty().filter { it.pid != me && it.processName.startsWith(ctx.packageName + ":") }
            .forEach { android.os.Process.killProcess(it.pid) }
    }

    private fun header(bytes: ByteArray): JSONObject {
        val h = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
        require(h != null && h.optString("format") == FORMAT) { "Not a Glassio Yahsun backup" }
        return h
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.DEFAULT)
}
