package com.glasslauncher.tv.data

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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
    private const val ITERATIONS = 150_000
    /** One file bigger than this stays out (a provider's whole channel list: Live TV reads it again); all together. */
    private const val FILE_MAX = 4L * 1024 * 1024
    private const val TOTAL_MAX = 16L * 1024 * 1024
    /**
     * Never in a backup: recordings (big, and the TV's own), what's only for now (the channel on, the last start, the
     * small window's handover, a crash), Android's own startup-speed markers (they belong to this install), half-written files.
     */
    private val SKIP_DIRS = setOf("recordings", "drop")
    private val SKIP_FILES = setOf("mini_live.json", "last-crash.txt", "live-watching.txt", "live-last-boot", "profileInstalled")

    class Restored(val settings: Int, val files: Int)

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
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(payload) } }.toByteArray()
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(code, salt, ITERATIONS), GCMParameterSpec(128, iv)) }
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        return JSONObject().put("format", FORMAT).put("version", 1).put("app", version).put("made", System.currentTimeMillis())
            .put("kdf", "PBKDF2WithHmacSHA256").put("iterations", ITERATIONS)
            .put("salt", b64(salt)).put("iv", b64(iv)).put("data", b64(c.doFinal(gz)))
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
        val gz = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(code, unb64(h.getString("salt")), h.optInt("iterations", ITERATIONS)), GCMParameterSpec(128, unb64(h.getString("iv"))))
            }.doFinal(unb64(h.getString("data")))
        } catch (e: AEADBadTagException) {
            error("Wrong code")
        }
        val payload = JSONObject(GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }.toString(Charsets.UTF_8))
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

    private fun key(code: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(code.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.DEFAULT)
}
