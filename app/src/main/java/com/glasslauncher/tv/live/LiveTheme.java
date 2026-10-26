package com.glasslauncher.tv.live;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.ListView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Live TV's theme: its style (the design language: Glassio's own, or one like Apple TV's, YouTube TV's, NOW's, Sky's,
 * Sling's or Hulu's: the background, the tiles, the focus and the text on it, how round things are) and, in Glassio's
 * style, the color of the focus and the highlights. LIVE, the "now" line and recording stay red whatever the theme.
 * In a file ("color,style"), not the preferences: Live TV sets it, the main process (the reminder card) reads it too.
 */
final class LiveTheme {
    private LiveTheme() {}

    /** Each theme: the tile (dark enough for white text on it) and its text color (on the dark background). */
    private static final int[] TILES = {0xFF8C1D18, 0xFF6B5314, 0xFF1F4E9C, 0xFF1E6B3A, 0xFF5B2A86, 0xFF4A4A4F, 0xFFF2F2F7};
    private static final int[] TEXTS = {0xFFFF6B5E, 0xFFF2C14E, 0xFF6FA8FF, 0xFF5FD38A, 0xFFC08CFF, 0xFFE5E5EA, 0xFFFFFFFF};
    static final int COUNT = TILES.length;

    /** Glassio's focus: white, as on the home screen. */
    static final int WHITE = 6;

    static int tile = TILES[WHITE], text = TEXTS[WHITE];
    private static int current = WHITE;

    /** The styles: each one's background (top and bottom of a gradient), tiles, focus and the text on the focus. */
    static final int ZERO = 0, APPLE = 1, YOUTUBE = 2, NOW = 3, SKY = 4, SLING = 5, HULU = 6, CUSTOM = 7, STYLES = 8;

    /**
     * Custom's choices: its background (the BGS below), corners (ROUNDS) and the style whose layout and lettering it
     * borrows; its focus is the focus color picked.
     */
    private static int customBg, customRound = 1, customLayout = ZERO;
    /** Each background: top and bottom of its gradient, the guide's programs on now, later and over. */
    private static final int[][] BGS = {
            {0xFF0B0B0D, 0xFF0B0B0D, 0xFF2E2E31, 0xFF242427, 0xFF161618},       // graphite
            {0xFF0F2244, 0xFF03060F, 0xFF1D3359, 0xFF152745, 0xFF0C182E},       // midnight blue
            {0xFF261440, 0xFF07040C, 0xFF33254D, 0xFF261C3B, 0xFF171126},       // deep purple
            {0xFF0E2A1E, 0xFF030806, 0xFF1F3A2E, 0xFF172C23, 0xFF0E1C16},       // forest
            {0xFF3A0F18, 0xFF0A0305, 0xFF4A2229, 0xFF381A20, 0xFF231014},       // burgundy
            {0xFF000000, 0xFF000000, 0xFF242424, 0xFF1A1A1A, 0xFF101010}};      // black
    static final int BG_COUNT = BGS.length;
    private static final float[] ROUNDS = {0.45f, 1, 1.8f};
    static final int ROUND_COUNT = ROUNDS.length;

    /** For the pickers' pictures: a focus color, a background, a corner roundness. */
    static int tileOf(int i) {
        return TILES[Math.max(0, Math.min(COUNT - 1, i))];
    }

    static float roundOf(int i) {
        return ROUNDS[Math.max(0, Math.min(ROUND_COUNT - 1, i))];
    }

    /** Background `i` in `r`: its gradient and three rows of the guide's programs in its colors. */
    static void drawBgSample(android.graphics.Canvas c, android.graphics.RectF r, int i, android.graphics.Paint p) {
        int[] b = BGS[Math.max(0, Math.min(BG_COUNT - 1, i))];
        p.setShader(new android.graphics.LinearGradient(0, r.top, 0, r.bottom, b[0], b[1], android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(r, p);
        p.setShader(null);
        float u = r.width() / 20, h = r.height() / 7;
        for (int row = 0; row < 3; row++) {
            float y = r.top + h * (2 + row * 1.5f);
            p.setColor(b[2]);
            c.drawRoundRect(r.left + u, y, r.left + u * 8, y + h, u / 2, u / 2, p);
            p.setColor(b[3]);
            c.drawRoundRect(r.left + u * 8.5f, y, r.left + u * 14, y + h, u / 2, u / 2, p);
            p.setColor(b[4]);
            c.drawRoundRect(r.left + u * 14.5f, y, r.right - u, y + h, u / 2, u / 2, p);
        }
    }

    static String bgName(int i) {
        switch (i) {
            case 1: return Ui.tr("Azul-noite", "Midnight blue");
            case 2: return Ui.tr("Roxo profundo", "Deep purple");
            case 3: return Ui.tr("Floresta", "Forest");
            case 4: return Ui.tr("Vinho", "Burgundy");
            case 5: return Ui.tr("Preto", "Black");
            default: return Ui.tr("Grafite", "Graphite");
        }
    }

    static String roundName(int i) {
        return i == 0 ? Ui.tr("Retos", "Square") : i == 2 ? Ui.tr("Bem redondos", "Very round") : Ui.tr("Redondos", "Rounded");
    }

    static int customBg() {
        return customBg;
    }

    static int customRound() {
        return customRound;
    }

    static int customLayout() {
        return customLayout;
    }

    /** Theme as it first was: Glassio, white, the same all day, Custom's defaults. */
    static void resetAll(Context c) {
        style = ZERO;
        night = morning = late = -1;
        customBg = 0;
        customRound = 1;
        customLayout = ZERO;
        tick(c);
        use(WHITE);
        save(c);
    }

    static void setCustom(Context c, int bgIndex, int roundIndex, int layout) {
        customBg = Math.max(0, Math.min(BG_COUNT - 1, bgIndex));
        customRound = Math.max(0, Math.min(ROUND_COUNT - 1, roundIndex));
        customLayout = layout < 0 || layout >= CUSTOM ? ZERO : layout;
        use(current);
        save(c);
    }

    /** The style whose layout and lettering show now (Custom's: the one it borrows). */
    static int layoutStyle() {
        int s = effective();
        return s == CUSTOM ? customLayout : s;
    }

    /** Whether the focus color picked applies (Glassio's and Custom's; the others bring their own). */
    static boolean ownColor() {
        int s = effective();
        return s == ZERO || s == CUSTOM;
    }
    private static int style;
    /**
     * Styles by the time of day (-1: the one before it): the morning's (sunrise to noon), the night's (sunset to
     * sunrise) and the late night's (midnight to sunrise, else the night's). `period` is the part of the day now.
     */
    private static int night = -1, morning = -1, late = -1;
    static final int DAY = 0, MORNING = 1, EVENING = 2, LATE = 3;
    private static int period = DAY;
    /** The page's background (a gradient from bgTop down to bg; the same color: flat). */
    static int bg = 0xFF0B0B0D, bgTop = 0xFF0B0B0D;
    /** Tiles and cards at rest; the guide's programs on now, later and over; the focus' text and icons. */
    static int surface = 0x1FFFFFFF, cellNow = 0xFF2E2E31, cellLater = 0xFF242427, cellPast = 0xFF161618, ink = 0xFFF5F5F7;
    /** A thin light edge on tiles (glass), 0: none. */
    static int rim;
    /** How round the corners are (1: Glassio's). */
    static float round = 1;

    /** A card's solid color (sheets, the PIN pad, dialogs): the style's surface over its background, opaque. */
    static int panel = 0xFF1C1C1F;
    /**
     * The guide's side panels: the rail of icons, the categories' column, a card (the show's), a dark pane (the
     * schedule, the digits). Glassio's greys; the other styles take them from their background.
     */
    static int railColor = 0xFF141416, column = 0xFA0E0E10, card = 0xFF1C1C1F, pane = 0xFF0B0B0D;

    /** An info tag's color (10+, 1080p, Dolby): Glassio's faint white, the other styles' accent, faint. */
    static int chip() {
        return effective() == ZERO ? 0x33FFFFFF : 0x47000000 | (text & 0x00FFFFFF);
    }

    /** A menu's or a dialog's card: Glassio's dark glass in its own style (as its home screen's dialogs), else [panel]. */
    static int sheet() {
        return effective() == ZERO ? 0xF0181820 : panel;
    }

    /** That card's light edge (0: none): Glassio's a little brighter than a tile's, as its dialogs have. */
    static int sheetRim() {
        return effective() == ZERO ? 0x38FFFFFF : rim;
    }

    /** A card's corners: the style's roundness, a little less at the extremes (big cards look odd fully round). */
    static float panelRound(float r) {
        return r * Math.max(0.5f, Math.min(1.2f, round));
    }

    static int style() {
        return style;
    }

    /** The style on screen now: the night one after sunset, else the chosen one (or, drawing a preview, that one). */
    static int effective() {
        if (preview >= 0) return preview;
        if (test >= 0) return test;
        return styleAt(period);
    }

    /** A style shown for a check from the computer (TestHook), -1: none; never saved. */
    private static int test = -1;

    static void test(int s) {
        test = s < -1 || s >= STYLES ? -1 : s;
        use(current);
    }

    /** The style shown in part `p` of the day. */
    static int styleAt(int p) {
        if (p == LATE && late >= 0) return late;
        if ((p == LATE || p == EVENING) && night >= 0) return night;
        if (p == MORNING && morning >= 0) return morning;
        return style;
    }

    static int morningStyle() {
        return morning;
    }

    static int lateStyle() {
        return late;
    }

    static void setMorning(Context c, int s) {
        morning = s < -1 || s >= STYLES ? -1 : s;
        tick(c);
        use(current);
        save(c);
    }

    static void setLate(Context c, int s) {
        late = s < -1 || s >= STYLES ? -1 : s;
        tick(c);
        use(current);
        save(c);
    }

    /**
     * A style to share: four digits, Custom's background, corners, layout and focus color ("1246"); typed on another
     * TV, it makes the same Custom style there.
     */
    static String code() {
        return "" + customBg + customRound + customLayout + current;
    }

    /** Custom from a code; false when it isn't one. */
    static boolean useCode(Context c, String code) {
        if (code == null || code.length() != 4) return false;
        int b = code.charAt(0) - '0', r = code.charAt(1) - '0', l = code.charAt(2) - '0', f = code.charAt(3) - '0';
        if (b < 0 || b >= BG_COUNT || r < 0 || r >= ROUND_COUNT || l < 0 || l >= CUSTOM || f < 0 || f >= COUNT) return false;
        customBg = b;
        customRound = r;
        customLayout = l;
        style = CUSTOM;
        use(f);
        save(c);
        return true;
    }

    private static int preview = -1;

    /** Draws as style `s` would (Settings' miniature of the style being changed), then back to the one on screen. */
    static void asStyle(int s, Runnable draw) {
        preview = s;
        use(current);
        LiveView.applyLayout();
        try {
            draw.run();
        } finally {
            preview = -1;
            use(current);
            LiveView.applyLayout();
        }
    }

    static int nightStyle() {
        return night;
    }

    static void setNight(Context c, int s) {
        night = s < -1 || s >= STYLES ? -1 : s;
        tick(c);
        use(current);
        save(c);
    }

    /**
     * Whether it just turned night or day (sunset and sunrise at the city set, else 6:00 and 18:00) and the style
     * changed with it: the caller draws everything again.
     */
    static boolean tick(Context c) {
        int before = effective();
        if (night < 0 && morning < 0 && late < 0) {
            period = DAY;
        } else {
            // Glassio's weather has no sunrise and sunset: 6:00 and 18:00.
            long now = System.currentTimeMillis(), t = timeOfDay(now);
            long rise = 6 * 60 * 60_000L, set = 18 * 60 * 60_000L;
            period = t < rise ? LATE : t >= set ? EVENING : t < 12 * 60 * 60_000L ? MORNING : DAY;
        }
        if (effective() == before) return false;
        use(current);
        return true;
    }

    /** Time of day (ms since local midnight) of an instant. */
    private static long timeOfDay(long at) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(at);
        return ((c.get(java.util.Calendar.HOUR_OF_DAY) * 60L + c.get(java.util.Calendar.MINUTE)) * 60 + c.get(java.util.Calendar.SECOND)) * 1000L;
    }

    /**
     * The lettering of the style on screen (Live TV's process only: these are Ui's faces): YouTube TV's Roboto,
     * Sling's narrower Roboto, NOW's, Sky's and Hulu's a weight bolder, Glassio's and Apple TV's Inter as it is.
     */
    static void applyFonts(Context c) {
        switch (layoutStyle()) {
            case YOUTUBE: Ui.restyle(c, "sans-serif", 0); break;
            case SLING: Ui.restyle(c, "sans-serif-condensed", 0); break;
            case NOW:
            case SKY:
            case HULU: Ui.restyle(c, null, 100); break;
            default: Ui.restyle(c, null, 0); break;
        }
    }

    static String styleName(int i) {
        switch (i) {
            case APPLE: return "Apple TV";
            case YOUTUBE: return "YouTube TV";
            case NOW: return "NOW";
            case SKY: return "Sky";
            case SLING: return "Sling";
            case HULU: return "Hulu";
            case CUSTOM: return Ui.tr("Personalizado", "Custom");
            case -1: return Ui.tr("Desligado", "Off");
            default: return "Glassio";
        }
    }

    /** What a style looks like, in a few words (the setting's line). */
    static String styleAbout(int i) {
        switch (i) {
            case APPLE: return Ui.tr("Vidro claro sobre azul profundo, foco branco, cantos bem redondos.",
                    "Clear glass over deep blue, a white focus, very round corners.");
            case YOUTUBE: return Ui.tr("Quase preto, foco branco, detalhes vermelhos.", "Near black, a white focus, red details.");
            case NOW: return Ui.tr("Preto e roxo, foco verde-água.", "Black and purple, a teal focus.");
            case SKY: return Ui.tr("O azul do Sky Q, foco azul vivo, cantos retos.", "Sky Q's blue, a bright blue focus, square corners.");
            case SLING: return Ui.tr("Azul-marinho, foco amarelo.", "Navy, a yellow focus.");
            case HULU: return Ui.tr("Preto, foco verde.", "Black, a green focus.");
            case CUSTOM: return Ui.tr("O seu: fundo, cor do foco, cantos e layout nas linhas abaixo.",
                    "Yours: background, focus color, corners and layout in the rows below.");
            default: return Ui.tr("O do Glassio: vidro claro sobre grafite, o foco na cor escolhida (branco).",
                    "Glassio's own: clear glass over graphite, the focus in the color you pick (white).");
        }
    }

    /** The page's background, at `alpha` (pages fading in). */
    static void background(android.graphics.Canvas c, int w, int h, float alpha) {
        if (bgTop == bg) {
            c.drawColor(Ui.alpha(bg, alpha));
            return;
        }
        if (bgPaint == null || bgH != h || bgFor != effective()) {
            bgPaint = new android.graphics.Paint();
            bgPaint.setShader(new android.graphics.LinearGradient(0, 0, 0, h, bgTop, bg, android.graphics.Shader.TileMode.CLAMP));
            bgH = h;
            bgFor = effective();
        }
        bgPaint.setAlpha(Math.round(255 * Math.max(0, Math.min(1, alpha))));
        c.drawRect(0, 0, w, h, bgPaint);
    }

    private static android.graphics.Paint bgPaint;
    private static int bgH, bgFor = -1;

    private static final android.graphics.Paint RIM = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    static {
        RIM.setStyle(android.graphics.Paint.Style.STROKE);
    }

    /** A tile at rest: its light edge, `width` thick, when the style has one (glass). */
    static void rim(android.graphics.Canvas c, android.graphics.RectF r, float radius, float width) {
        if (rim == 0) return;
        RIM.setStrokeWidth(width);
        drawRim(c, r, radius, RIM);
    }

    /** A tile's light edge, when the style has one (glass). */
    static void drawRim(android.graphics.Canvas c, android.graphics.RectF r, float radius, android.graphics.Paint stroke) {
        if (rim == 0) return;
        stroke.setShader(null);
        stroke.setColor(rim);
        c.drawRoundRect(r, radius, radius, stroke);
    }

    /** The text on a tile: white at rest, the focus' ink as the focus covers it (`f`, 0 to 1). */
    static int onTile(int idle, float f) {
        if (f <= 0) return idle;
        if (f >= 1) return ink;
        int a = idle, b = ink;
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg2 = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg2 - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }

    static String name(int i) {
        switch (i) {
            case 1: return Ui.tr("Dourado", "Gold");
            case 2: return Ui.tr("Azul", "Blue");
            case 3: return Ui.tr("Verde", "Green");
            case 4: return Ui.tr("Roxo", "Purple");
            case 5: return Ui.tr("Grafite", "Graphite");
            case 6: return Ui.tr("Branco", "White");
            default: return Ui.tr("Vermelho", "Red");
        }
    }

    static int current() {
        return current;
    }

    /** Reads the theme in use (each process, as it starts). */
    static void load(Context c) {
        int i = WHITE, s = ZERO;
        File f = file(c);
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                String[] parts = Xtream.read(in).trim().split(",");
                i = Integer.parseInt(parts[0].trim());
                if (parts.length > 1) s = Integer.parseInt(parts[1].trim());
                night = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : -1;
                morning = parts.length > 7 ? Integer.parseInt(parts[6].trim()) : -1;
                late = parts.length > 7 ? Integer.parseInt(parts[7].trim()) : -1;
                if (parts.length > 5) {
                    customBg = Math.max(0, Math.min(BG_COUNT - 1, Integer.parseInt(parts[3].trim())));
                    customRound = Math.max(0, Math.min(ROUND_COUNT - 1, Integer.parseInt(parts[4].trim())));
                    int l = Integer.parseInt(parts[5].trim());
                    customLayout = l < 0 || l >= CUSTOM ? ZERO : l;
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        style = s < 0 || s >= STYLES ? ZERO : s;
        if (night < -1 || night >= STYLES) night = -1;
        if (morning < -1 || morning >= STYLES) morning = -1;
        if (late < -1 || late >= STYLES) late = -1;
        tick(c);
        use(i);
    }

    static void set(Context c, int i) {
        use(i);
        save(c);
    }

    static void setStyle(Context c, int s) {
        style = s < 0 || s >= STYLES ? ZERO : s;
        use(current);
        save(c);
    }

    private static void save(Context c) {
        try {
            Xtream.write(file(c), current + "," + style + "," + night + "," + customBg + "," + customRound + "," + customLayout
                    + "," + morning + "," + late);
        } catch (IOException e) {
            AppLog.w("live: couldn't save the theme");
        }
    }

    private static void use(int i) {
        current = i < 0 || i >= COUNT ? 0 : i;
        tile = TILES[current];
        text = TEXTS[current];
        // Glassio's own; the others set everything, their focus too (the color picked stays for Glassio's).
        bg = bgTop = 0xFF0B0B0D;
        surface = 0x1FFFFFFF;
        cellNow = 0xFF2E2E31;
        cellLater = 0xFF242427;
        cellPast = 0xFF161618;
        ink = 0xFFF5F5F7;
        rim = 0;
        round = 1;
        // A light focus (white) takes dark text on it.
        int lum = ((tile >> 16) & 255) * 3 + ((tile >> 8) & 255) * 6 + (tile & 255);
        if (lum > 1700) ink = 0xFF1C1C1E;
        switch (effective()) {
            case ZERO:      // Glassio: clear glass tiles with a light edge over graphite, round like the home screen's
                bgTop = 0xFF2B2B30;
                bg = 0xFF0E0E11;
                surface = 0x24FFFFFF;
                cellNow = 0x33FFFFFF;
                cellLater = 0x1FFFFFFF;
                cellPast = 0x0FFFFFFF;
                rim = 0x2EFFFFFF;
                round = 1.5f;
                break;
            case CUSTOM: {
                int[] b = BGS[customBg];
                bgTop = b[0];
                bg = b[1];
                cellNow = b[2];
                cellLater = b[3];
                cellPast = b[4];
                round = ROUNDS[customRound];
                break;
            }
            case APPLE:     // Liquid Glass: light glass tiles with a bright edge over deep blue, the focus white
                bgTop = 0xFF1C2742;
                bg = 0xFF06070B;
                surface = 0x24FFFFFF;
                cellNow = 0x33FFFFFF;
                cellLater = 0x1FFFFFFF;
                cellPast = 0x0FFFFFFF;
                rim = 0x2EFFFFFF;
                tile = 0xF2F5F5F7;
                ink = 0xFF1C1C1E;
                text = 0xFF64D2FF;
                round = 1.8f;
                break;
            case YOUTUBE:   // flat near-black, the focus white, red
                bg = bgTop = 0xFF0F0F0F;
                surface = 0x1AFFFFFF;
                cellNow = 0xFF2A2A2A;
                cellLater = 0xFF212121;
                cellPast = 0xFF161616;
                tile = 0xFFF1F1F1;
                ink = 0xFF0F0F0F;
                text = 0xFFFF4E45;
                round = 1.1f;
                break;
            case NOW:       // black into purple, the focus teal
                bgTop = 0xFF1E1236;
                bg = 0xFF050308;
                surface = 0x1FFFFFFF;
                cellNow = 0xFF2B2245;
                cellLater = 0xFF1F1933;
                cellPast = 0xFF130F20;
                tile = 0xFF16C7B0;
                ink = 0xFF041210;
                text = 0xFF3FE0CB;
                round = 1.3f;
                break;
            case SKY:       // Sky Q's blue, the focus bright blue, square corners
                bgTop = 0xFF0E3270;
                bg = 0xFF030B1F;
                surface = 0x1F8FB8FF;
                cellNow = 0xFF1A3F7A;
                cellLater = 0xFF12305F;
                cellPast = 0xFF0A1C3C;
                tile = 0xFF1E7BF2;
                text = 0xFF8EC0FF;
                round = 0.45f;
                break;
            case SLING:     // navy, the focus yellow
                bg = bgTop = 0xFF0A1322;
                surface = 0x1F9FB8E0;
                cellNow = 0xFF22324F;
                cellLater = 0xFF19253D;
                cellPast = 0xFF101929;
                tile = 0xFFFFC21A;
                ink = 0xFF0A1322;
                text = 0xFFFFC21A;
                round = 0.8f;
                break;
            case HULU:      // black, the focus green
                bg = bgTop = 0xFF0B0C0F;
                surface = 0x1AFFFFFF;
                cellNow = 0xFF262931;
                cellLater = 0xFF1C1E23;
                cellPast = 0xFF131418;
                tile = 0xFF1CE783;
                ink = 0xFF0B0C0F;
                text = 0xFF1CE783;
                round = 1.3f;
                break;
            default:
                break;
        }
        int sa = surface >>> 24;
        int pr = (((bgTop >> 16) & 255) * (255 - sa) + ((surface >> 16) & 255) * sa) / 255;
        int pg = (((bgTop >> 8) & 255) * (255 - sa) + ((surface >> 8) & 255) * sa) / 255;
        int pb = ((bgTop & 255) * (255 - sa) + (surface & 255) * sa) / 255;
        panel = 0xFF000000 | (pr << 16) | (pg << 8) | pb;
        railColor = mix(bgTop, panel, 0.5f);
        column = 0xFF000000 | (bgTop & 0x00FFFFFF);
        card = panel;
        pane = mix(bgTop, bg, 0.5f);
    }

    /** Between two opaque colors (`f` 0 is `a`). */
    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * f);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * f);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** A dialog in the theme: its list's focus on the theme's tile instead of the system's grey. */
    static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert) {
            @Override public AlertDialog create() {
                AlertDialog d = super.create();
                ListView list = d.getListView();
                if (list != null) {
                    list.setSelector(selector(c));
                    list.setOnHierarchyChangeListener(new android.view.ViewGroup.OnHierarchyChangeListener() {
                        @Override public void onChildViewAdded(android.view.View parent, android.view.View child) {
                            if (child instanceof android.widget.TextView) ((android.widget.TextView) child).setTextColor(onFocus());
                        }

                        @Override public void onChildViewRemoved(android.view.View parent, android.view.View child) {}
                    });
                }
                float dp = c.getResources().getDisplayMetrics().density;
                GradientDrawable card = effective() == ZERO ? new GradientDrawable()
                        : new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {panel, bg});
                if (effective() == ZERO) {      // Glassio: its dialogs' dark glass with the light edge
                    card.setColor(sheet());
                    card.setStroke(Math.max(1, Math.round(0.8f * dp)), sheetRim());
                }
                card.setCornerRadius(panelRound(24 * dp));
                if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(card);
                // Its buttons (made as it shows): the focused one on the theme's tile too, not the system's white.
                d.setOnShowListener(x -> {
                    for (int which : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
                        android.widget.Button b = d.getButton(which);
                        if (b == null) continue;
                        b.setBackground(button(c));
                        b.setTextColor(onFocus());
                    }
                });
                return d;
            }
        };
    }

    /** White text, the focus' ink when focused. */
    private static android.content.res.ColorStateList onFocus() {
        return new android.content.res.ColorStateList(new int[][] {{android.R.attr.state_focused}, {android.R.attr.state_pressed},
                {android.R.attr.state_selected}, {}}, new int[] {ink, ink, ink, 0xFFF5F5F7});
    }

    private static StateListDrawable selector(Context c) {
        float dp = c.getResources().getDisplayMetrics().density;
        GradientDrawable plate = new GradientDrawable();
        plate.setColor(tile);
        plate.setCornerRadius(Math.min(14, 10 * round) * dp);
        // Inset from the card's edges, as Glassio's own lists: a plate under the row, not a band across the card.
        android.graphics.drawable.Drawable on = new android.graphics.drawable.InsetDrawable(plate,
                Math.round(12 * dp), Math.round(3 * dp), Math.round(12 * dp), Math.round(3 * dp));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, on);
        s.addState(new int[] {android.R.attr.state_pressed}, on);
        s.addState(new int[] {android.R.attr.state_selected}, on);
        s.addState(new int[0], new ColorDrawable(0));
        return s;
    }

    /** A dialog button: a pill, the theme's tile when focused, a faint one otherwise. */
    private static StateListDrawable button(Context c) {
        float r = 28 * Math.min(1, round) * c.getResources().getDisplayMetrics().density;
        GradientDrawable on = new GradientDrawable(), off = new GradientDrawable();
        on.setColor(tile);
        on.setCornerRadius(r);
        off.setColor(surface | 0x0F000000);
        off.setCornerRadius(r);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, on);
        s.addState(new int[] {android.R.attr.state_pressed}, on);
        s.addState(new int[0], off);
        return s;
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "live-theme.txt");
    }
}
