package com.glasslauncher.tv.live;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * A channel's resolution badge (4K, 2K, FHD, HD or SD; LiveMerge.badge) as Live TV draws it: on a line after the
 * name and country tag, or at a corner of its own. 4K and 2K gold, FHD white, the rest dark. Drawn on the main thread
 * only (its paints are shared).
 */
final class LiveBadge {
    private LiveBadge() {}

    private static final Paint TEXT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF BOX = new RectF();
    private static final int DARK = 0xD91C1C1E;
    private static boolean read, on;
    private static long readAt;

    static {
        TEXT.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    /** `ch`'s badge, "" when it has none or Resolution badges is off (the setting read again every 2 s). */
    static String of(Context c, Xtream.Channel ch) {
        long now = android.os.SystemClock.uptimeMillis();
        if (!read || now - readAt > 2000) {
            on = LiveOptions.resolutionBadges(c);
            read = true;
            readAt = now;
        }
        return on && ch != null ? LiveMerge.badge(c, ch) : "";
    }

    /** How wide the badge is on a line drawn in `text` (0: none), with the gap before it. */
    static float width(String t, Paint text, float k) {
        if (t == null || t.isEmpty()) return 0;
        size(text.getTextSize() * 0.6f);
        return TEXT.measureText(t) + height(text, k) * 0.6f + 12 * k;
    }

    /** The badge on the line of `text` (the country tag's height), starting at x on baseline y; returns where it ends. */
    static float drawInline(Canvas c, String t, float x, float baseline, Paint text, float k) {
        if (t == null || t.isEmpty()) return x;
        size(text.getTextSize() * 0.6f);
        float ph = height(text, k), tw = TEXT.measureText(t), mid = baseline - Ui.halfCap(text);
        BOX.set(x, mid - ph / 2, x + tw + ph * 0.6f, mid + ph / 2);
        paint(c, t, tw, 1, k);
        return BOX.right;
    }

    /** The badge with its top right corner at (right, top), as tall as a tag on a line of `text`; returns its left. */
    static float drawTopRight(Canvas c, String t, float right, float top, Paint text, float k) {
        if (t == null || t.isEmpty()) return right;
        size(text.getTextSize() * 0.6f);
        float ph = height(text, k), tw = TEXT.measureText(t);
        BOX.set(right - tw - ph * 0.6f, top, right, top + ph);
        paint(c, t, tw, 1, k);
        return BOX.left;
    }

    /** The badge at the top right of a logo's `box` (big enough logos only), as the guide's logos had it. */
    static void drawCorner(Canvas c, String t, RectF box, float alpha, float k) {
        if (t == null || t.isEmpty() || box.height() < 44 * k || box.width() < 70 * k) return;
        size(Math.max(13 * k, Math.min(17 * k, box.height() * 0.2f)));
        float tw = TEXT.measureText(t), ph = TEXT.getTextSize() * 1.25f, pw = tw + ph * 0.6f, inset = 4 * k;
        BOX.set(box.right - inset - pw, box.top + inset, box.right - inset, box.top + inset + ph);
        paint(c, t, tw, alpha, k);
    }

    private static void size(float px) {
        TEXT.setTextSize(px);
    }

    private static float height(Paint text, float k) {
        return Math.max(text.getTextSize() * 0.9f, 20 * k);
    }

    private static void paint(Canvas c, String t, float tw, float alpha, float k) {
        int bg = "4K".equals(t) || "2K".equals(t) ? 0xFFE8B84A : "FHD".equals(t) ? 0xFFF5F5F7 : DARK;
        FILL.setColor(Ui.alpha(bg, alpha));
        c.drawRoundRect(BOX, 5 * k, 5 * k, FILL);
        TEXT.setColor(Ui.alpha(bg == DARK ? 0xFFF5F5F7 : 0xFF1C1C1E, alpha));
        c.drawText(t, BOX.centerX() - tw / 2, BOX.centerY() + Ui.halfCap(TEXT), TEXT);
    }
}
