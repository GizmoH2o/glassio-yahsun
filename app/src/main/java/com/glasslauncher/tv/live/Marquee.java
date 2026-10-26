package com.glasslauncher.tv.live;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;

/**
 * A line too long for its room scrolls across it, as a ticker (Apple TV's focused titles): still for a moment, then
 * gliding left and coming round again, instead of ending in "…". One per place on screen (it remembers when its text
 * came in). `moving` says a frame is wanted (the view resets it before drawing and asks for the next frame after).
 */
final class Marquee {
    private static final long HOLD = 1500;
    private static final float SPEED = 70, GAP = 90;     // px per second and between the two copies, at 1920 wide
    static boolean moving;

    private String text = "";
    private long since;

    /** Draws `s` at x on `baseline` within `room` (left aligned); returns how wide it drew (at most `room`). */
    float draw(Canvas c, String s, float x, float baseline, float room, Paint p, float k) {
        if (s == null) s = "";
        float tw = p.measureText(s);
        if (tw <= room) {
            text = s;
            c.drawText(s, x, baseline, p);
            return tw;
        }
        long up = SystemClock.uptimeMillis();
        if (!s.equals(text)) {
            text = s;
            since = up;
        }
        float cycle = tw + GAP * k, run = Math.max(0, up - since - HOLD) / 1000f * SPEED * k;
        if (run >= cycle) {     // round once: still again for a moment, from the start
            since = up;
            run = 0;
        }
        float off = run;
        c.save();
        c.clipRect(x, baseline - p.getTextSize() * 1.3f, x + room, baseline + p.getTextSize() * 0.5f);
        c.drawText(s, x - off, baseline, p);
        if (off > 0) c.drawText(s, x - off + cycle, baseline, p);
        c.restore();
        moving = true;
        return room;
    }
}
