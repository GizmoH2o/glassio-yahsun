package com.glasslauncher.tv.live;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live TV's channel logos, for Live TV (LiveView) and the home screen's Live TV row: downloaded once into the cache
 * (shared by both processes), decoded small, kept in memory per process (the most recent ones), two at a time.
 */
final class LiveLogos {
    private LiveLogos() {}

    private static final Map<String, Bitmap> MEMORY = new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 160;
        }
    };
    private static final Set<String> PENDING = new HashSet<>(), FAILED = new HashSet<>();
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);

    /** The logo, ready to draw, or null (loading: `loaded` runs on a background thread when it's there; or none). */
    static Bitmap get(Context c, String url, Runnable loaded) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return null;
        synchronized (MEMORY) {
            Bitmap b = MEMORY.get(url);
            if (b != null || FAILED.contains(url) || !PENDING.add(url)) return b;
        }
        final Context app = c.getApplicationContext();
        IO.execute(() -> {
            Bitmap b = null;
            try {
                b = load(app, url);
            } catch (IOException | RuntimeException | OutOfMemoryError ignored) {   // the channel's letters instead
            }
            boolean dark = b != null && dark(b);
            synchronized (MEMORY) {
                PENDING.remove(url);
                if (b != null) MEMORY.put(url, b); else FAILED.add(url);
                if (dark) DARK.add(b);
            }
            if (b != null && loaded != null) loaded.run();
        });
        return null;
    }

    /** Whether `url` couldn't be had (the channel's other logo, or its letters, instead). */
    static boolean failed(String url) {
        synchronized (MEMORY) {
            return url != null && FAILED.contains(url);
        }
    }

    /** Logos dark on a see-through background: shown on a light plate (they would vanish on the dark tiles). */
    private static final java.util.Set<Bitmap> DARK = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    static boolean isDark(Bitmap b) {
        synchronized (MEMORY) {
            return DARK.contains(b);
        }
    }

    /** Mostly see-through, and what's drawn is dark (sampled on a grid). */
    private static boolean dark(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), seen = 0, clear = 0;
        long lum = 0;
        for (int gy = 0; gy < 24; gy++) {
            for (int gx = 0; gx < 24; gx++) {
                int px = b.getPixel(Math.min(w - 1, gx * w / 24 + w / 48), Math.min(h - 1, gy * h / 24 + h / 48));
                if ((px >>> 24) < 128) {
                    clear++;
                    continue;
                }
                seen++;
                lum += (((px >> 16) & 255) * 3 + ((px >> 8) & 255) * 6 + (px & 255)) / 10;
            }
        }
        return seen > 8 && clear > 576 / 10 && lum / seen < 70;
    }

    private static Bitmap load(Context c, String url) throws IOException {
        File dir = new File(c.getCacheDir(), "live-logos"), f = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
        if (!f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File part = new File(f.getPath() + "." + android.os.Process.myPid() + ".part");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", Live.USER_AGENT);
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                int n, total = 0;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > 2_000_000) throw new IOException("logo too big");
                    out.write(buffer, 0, n);
                }
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw e;
            } finally {
                conn.disconnect();
            }
            if (!part.renameTo(f) && !f.exists()) throw new IOException("couldn't save the logo");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= 360 && bounds.outHeight / (opts.inSampleSize * 2) >= 200) opts.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
        if (b == null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            throw new IOException("unreadable logo");
        }
        return b;
    }
}
