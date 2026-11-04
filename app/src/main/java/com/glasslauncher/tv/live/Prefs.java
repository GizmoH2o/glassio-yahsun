package com.glasslauncher.tv.live;

import android.content.Context;

/**
 * What Live TV reads from Glassio's own settings ("glass" preferences, written by the home screen). Read in every
 * process: Live TV's (:live) sees the value as it was when that process started.
 */
final class Prefs {
    private Prefs() {}

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        Ui.applyLanguage();
        Spring.still = new java.io.File(app.getFilesDir(), "ui_still").exists();
    }

    /** Live TV reads as Glassio does (Settings › Look & motion). "auto": the TV's own language. */
    static String language() {
        try {
            String v = new String(java.nio.file.Files.readAllBytes(new java.io.File(app.getFilesDir(), "ui_language").toPath())).trim();
            return v.isEmpty() ? "auto" : v;
        } catch (Exception e) {
            return "auto";
        }
    }

    /** The TMDB token typed in Glassio's settings (Settings → Catalogs), for the shows' pictures and pages. */
    static String tmdbKey(Context c) {
        Context ctx = c != null ? c.getApplicationContext() : app;
        if (ctx == null) return "";
        String v = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE).getString("tmdbToken", "");
        return v == null ? "" : v.trim();
    }
}
