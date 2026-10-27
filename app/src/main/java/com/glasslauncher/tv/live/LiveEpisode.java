package com.glasslauncher.tv.live;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A program's episode, read from the guide's episode line ("S05 E08 Terror Tales of the Park III", "S5E8 - …",
 * "Season 5 Episode 8: …", "5x08 …", "Ep. 12 …"): its season and number, shown as a small glass chip ("S5 · E8"),
 * then the episode's own title. A line without numbers is the title alone.
 */
final class LiveEpisode {
    /** -1 when the line doesn't say. */
    final int season, episode;
    /** The episode's own title, "" when the line is only its numbers. */
    final String title;

    private LiveEpisode(int season, int episode, String title) {
        this.season = season;
        this.episode = episode;
        this.title = title;
    }

    private static final Pattern NUMBERED = Pattern.compile(
            "(?iu)^\\s*(?:(?:s|season|t|temporada|staffel)\\s*\\.?\\s*(\\d{1,4})\\s*[,.:/\\-–]?\\s*)?"
                    + "(?:e|ep|episode|episódio|episodio|folge)\\s*\\.?\\s*(\\d{1,5})(?!\\d)");
    private static final Pattern CROSS = Pattern.compile("^\\s*(\\d{1,2})x(\\d{1,3})(?!\\d)");
    private static final Pattern LEAD = Pattern.compile("^[\\s:·|,.\\-–—]+");

    /** The episode in `line` (a program's sub), null when there's none. */
    static LiveEpisode of(String line) {
        if (line == null || line.trim().isEmpty()) return null;
        String l = line.trim();
        Matcher m = NUMBERED.matcher(l);
        int season = -1, episode = -1;
        int end = 0;
        if (m.find()) {
            if (m.group(1) != null) season = Integer.parseInt(m.group(1));
            episode = Integer.parseInt(m.group(2));
            end = m.end();
        } else if ((m = CROSS.matcher(l)).find()) {
            season = Integer.parseInt(m.group(1));
            episode = Integer.parseInt(m.group(2));
            end = m.end();
        }
        String title = LEAD.matcher(l.substring(end)).replaceFirst("").trim();
        if (episode < 0 && title.isEmpty()) return null;
        return new LiveEpisode(season, episode, title);
    }

    private static final LiveEpisode NONE = new LiveEpisode(-1, -1, "");
    private static final java.util.Map<String, LiveEpisode> SEEN = new java.util.HashMap<>();

    /** As `of`, remembered: the guide draws every cell's line each frame (drawing, on the main thread). */
    static LiveEpisode cached(String line) {
        if (line == null || line.isEmpty()) return null;
        LiveEpisode e = SEEN.get(line);
        if (e == null) {
            if (SEEN.size() > 2000) SEEN.clear();
            e = of(line);
            SEEN.put(line, e == null ? NONE : e);
        }
        return e == NONE ? null : e;
    }

    /** The chip's words: "S5 · E8", "E12", "" without numbers. */
    String label() {
        if (episode < 0) return "";
        return season >= 0 ? String.format(Locale.ROOT, "S%d · E%d", season, episode) : String.format(Locale.ROOT, "E%d", episode);
    }

    /** `about` without its first line when that line is the episode's (the full guide puts it there too). */
    static String aboutWithout(String about, String line) {
        if (about == null) return "";
        if (line == null || line.isEmpty() || !about.startsWith(line)) return about;
        String rest = about.substring(line.length());
        if (!rest.isEmpty() && rest.charAt(0) != '\n') return about;
        return rest.trim();
    }

    /** The chip's pens, made as they're first needed (the reading is checked in tests, without Android). */
    private static Paint chipText, chip;
    private static RectF box;

    /**
     * The chip and the title on the line at `baseline` from `x`, within `room` (the title cut short, never the chip),
     * in `text`'s size and color (on a light focus, the chip goes dark as the lettering does). Returns where it ends.
     */
    static float draw(Canvas c, LiveEpisode e, float x, float baseline, Paint text, float room, float k) {
        float end = x;
        String label = e.label();
        if (!label.isEmpty()) {
            if (chip == null) {
                chipText = new Paint(Paint.ANTI_ALIAS_FLAG);
                chip = new Paint(Paint.ANTI_ALIAS_FLAG);
                box = new RectF();
            }
            chipText.setTypeface(Ui.semibold);
            chipText.setTextSize(text.getTextSize() * 0.74f);
            chipText.setLetterSpacing(0.06f);
            int color = text.getColor();
            boolean onLight = ((color >> 16 & 255) * 3 + (color >> 8 & 255) * 6 + (color & 255)) / 10 < 128;
            chipText.setColor(onLight ? color | 0xFF000000 : 0xF2FFFFFF);
            float h = Math.max(text.getTextSize() * 1.05f, 18 * k), tw = chipText.measureText(label), w = tw + h * 0.9f;
            if (w > room) return x;
            float mid = baseline - Ui.halfCap(text);
            box.set(x, mid - h / 2, x + w, mid + h / 2);
            chip.setColor(onLight ? 0x24000000 : 0x2EFFFFFF);
            c.drawRoundRect(box, h * 0.32f, h * 0.32f, chip);
            c.drawText(label, x + (w - tw) / 2, mid + Ui.halfCap(chipText), chipText);
            end = x + w + (e.title.isEmpty() ? 0 : 12 * k);
        }
        if (!e.title.isEmpty()) {
            float left = room - (end - x);
            if (left < 20 * k) return end;
            String t = ellipsize(e.title, text, left);
            c.drawText(t, end, baseline, text);
            end += text.measureText(t);
        }
        return end;
    }

    private static String ellipsize(String s, Paint p, float room) {
        if (p.measureText(s) <= room) return s;
        int n = p.breakText(s, true, Math.max(0, room - p.measureText("…")), null);
        return n <= 0 ? "" : s.substring(0, n).trim() + "…";
    }
}
