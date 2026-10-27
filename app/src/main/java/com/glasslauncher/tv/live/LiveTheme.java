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
 * Live TV's design: Glassio's own, or one like Apple TV's, YouTube TV's, NOW's, Sky's, Sling's, Hulu's, Netflix's,
 * Prime Video's, Disney+'s, Max's, Plex's or Pluto TV's. Each is the whole look: its colors (the background, the
 * tiles, the guide's programs), its focus and the text on it, how round things are, its guide (where the video and
 * the details are, how tall the rows, how many hours across), its info bar and its lettering. Custom takes each of
 * those parts from any design. In Glassio's colors of focus, the color picked. LIVE, the "now" line and recording
 * stay red whatever the design. (In the code a design is still a "style", the name it had first.)
 * In a file ("color,style,…"), not the preferences: Live TV sets it, the main process (the reminder card) reads it too.
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

    /**
     * The designs, each with its own colors, focus, corners, guide, info bar and lettering (Custom: each part from the
     * design picked for it). The number is what's saved, so a new design only ever goes at the end.
     */
    static final int ZERO = 0, APPLE = 1, YOUTUBE = 2, NOW = 3, SKY = 4, SLING = 5, HULU = 6, CUSTOM = 7,
            NETFLIX = 8, PRIME = 9, DISNEY = 10, MAX = 11, PLEX = 12, PLUTO = 13, STYLES = 14;

    /** The styles in the order the lists show them: Custom last. */
    private static final int[] ORDER = {ZERO, APPLE, YOUTUBE, NOW, SKY, SLING, HULU, NETFLIX, PRIME, DISNEY, MAX, PLEX, PLUTO, CUSTOM};

    /** The style at place `i` of the lists. */
    static int ordered(int i) {
        return ORDER[Math.max(0, Math.min(ORDER.length - 1, i))];
    }

    /** Where style `s` sits in the lists (-1: none, for "the same"). */
    static int placeOf(int s) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i] == s) return i;
        return -1;
    }

    /** The style before (-1) or after (1) `s` in the lists, round; with `orNone`, "the same" (-1) comes before the first. */
    static int step(int s, int dir, boolean orNone) {
        int n = ORDER.length + (orNone ? 1 : 0);
        int at = placeOf(s) + (orNone ? 1 : 0);
        int next = ((at + dir) % n + n) % n - (orNone ? 1 : 0);
        return next < 0 ? -1 : ORDER[next];
    }

    /**
     * A design's parts. Custom takes each from the design picked for it: the colors (background, tiles, the guide's
     * programs), the focus (its color and the text on it; Glassio's: the focus color picked), the corners, the guide
     * (where the video and the details are, the rows, the hours across; the channel list and search sized to go with
     * it), the info bar and the lettering.
     */
    static final int COLORS = 0, FOCUS = 1, CORNERS = 2, GUIDE = 3, INFO = 4, LETTERS = 5, PARTS = 6;

    /** The designs a part can come from: all but Custom, in the lists' order. */
    static final int[] SOURCES = {ZERO, APPLE, YOUTUBE, NOW, SKY, SLING, HULU, NETFLIX, PRIME, DISNEY, MAX, PLEX, PLUTO};

    /** Custom's parts: the design each comes from. */
    private static final int[] custom = new int[PARTS];

    /** Where design `s` sits in SOURCES (0, Glassio's, when it isn't there). */
    static int sourceIndex(int s) {
        for (int i = 0; i < SOURCES.length; i++) if (SOURCES[i] == s) return i;
        return 0;
    }

    /** A part's design as saved or typed: Glassio's when it isn't one a part can come from. */
    private static int source(int s) {
        return s < 0 || s >= STYLES || s == CUSTOM ? ZERO : s;
    }

    static int customPart(int part) {
        return custom[part];
    }

    static void setCustomPart(Context c, int part, int design) {
        custom[part] = source(design);
        use(current);
        save(c);
    }

    /** The design part `p` comes from on screen now (Custom's: the one picked for it; any other: itself). */
    static int part(int p) {
        int s = effective();
        return s == CUSTOM ? custom[p] : s;
    }

    /**
     * Before designs, Custom had a background of its own (graphite, midnight blue, deep purple, forest, burgundy,
     * black), three roundnesses and a borrowed layout: the closest designs to them.
     */
    private static final int[] OLD_BG = {YOUTUBE, MAX, NOW, HULU, NETFLIX, PLUTO};
    private static final int[] OLD_ROUND = {SKY, YOUTUBE, APPLE};

    private static void fromOld(int bg, int round, int layout) {
        custom[COLORS] = OLD_BG[Math.max(0, Math.min(OLD_BG.length - 1, bg))];
        custom[FOCUS] = ZERO;       // the focus color picked, as it was
        custom[CORNERS] = OLD_ROUND[Math.max(0, Math.min(OLD_ROUND.length - 1, round))];
        custom[GUIDE] = custom[INFO] = custom[LETTERS] = layout < 0 || layout >= CUSTOM ? ZERO : layout;
    }

    /** For the focus color's pictures. */
    static int tileOf(int i) {
        return TILES[Math.max(0, Math.min(COUNT - 1, i))];
    }

    /** Design as it first was: Glassio, white, the same all day, Custom all Glassio's. */
    static void resetAll(Context c) {
        style = ZERO;
        night = morning = late = -1;
        java.util.Arrays.fill(custom, ZERO);
        tick(c);
        use(WHITE);
        save(c);
    }

    /** The design whose guide shows now. */
    static int layoutStyle() {
        return part(GUIDE);
    }

    /** The design whose info bar shows now. */
    static int infoStyle() {
        return part(INFO);
    }

    /** Whether the colors are Glassio's own (its dark glass for menus and dialogs, its faint chips). */
    static boolean glassio() {
        return part(COLORS) == ZERO;
    }

    /** Whether the focus color picked applies (the focus is Glassio's; the others bring their own). */
    static boolean ownColor() {
        return part(FOCUS) == ZERO;
    }

    /** The info bar's panels: only the fade, a floating glass card, a solid band (with a line on top), a side shade. */
    static final int BAR_FADE = 0, BAR_CARD = 1, BAR_BAND_LINE = 2, BAR_BAND = 3, BAR_SIDE = 4;

    /**
     * The info bar's panel now: Apple TV's and Disney+'s a floating card of glass, Sky Q's and Pluto TV's a solid band
     * with a line of the focus color on top, Plex's a solid band, Netflix's a shade from the left; the rest the fade.
     */
    static int infoBar() {
        switch (infoStyle()) {
            case APPLE:
            case DISNEY: return BAR_CARD;
            case SKY:
            case PLUTO: return BAR_BAND_LINE;
            case PLEX: return BAR_BAND;
            case NETFLIX: return BAR_SIDE;
            default: return BAR_FADE;
        }
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

    /** An info tag's color (10+, 1080p, Dolby): Glassio's faint white, the other designs' accent, faint. */
    static int chip() {
        return glassio() ? 0x33FFFFFF : 0x47000000 | (text & 0x00FFFFFF);
    }

    /** A menu's or a dialog's card: Glassio's dark glass in its own colors (as its home screen's dialogs), else [panel]. */
    static int sheet() {
        return glassio() ? 0xF0181820 : panel;
    }

    /** That card's light edge (0: none): Glassio's a little brighter than a tile's, as its dialogs have. */
    static int sheetRim() {
        return glassio() ? 0x38FFFFFF : rim;
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

    /** How many codes there are: each part one of SOURCES, then the focus color. */
    private static final long CODES = (long) Math.pow(SOURCES.length, PARTS) * COUNT;

    /**
     * A design to share: eight digits, Custom's parts and the focus color ("04127355"); typed on another TV, it makes
     * the same Custom design there.
     */
    static String code() {
        long n = 0;
        for (int p = PARTS - 1; p >= 0; p--) n = n * SOURCES.length + sourceIndex(custom[p]);
        return String.format(java.util.Locale.ROOT, "%08d", n * COUNT + current);
    }

    /** Custom from a code (or a four-digit one from before designs), saved; false when it isn't one. */
    static boolean useCode(Context c, String code) {
        if (!readCode(code)) return false;
        save(c);
        return true;
    }

    /** Custom from a code on screen (its parts and focus color), not saved yet; false when it isn't one. */
    static boolean readCode(String code) {
        if (code == null || !code.matches("\\d{4}|\\d{8}")) return false;
        int f;
        if (code.length() == 4) {       // background, corners, layout, focus color
            int b = code.charAt(0) - '0', r = code.charAt(1) - '0', l = code.charAt(2) - '0';
            f = code.charAt(3) - '0';
            if (b >= OLD_BG.length || r >= OLD_ROUND.length || l >= CUSTOM || f >= COUNT) return false;
            fromOld(b, r, l);
        } else {
            long n = Long.parseLong(code);
            if (n >= CODES) return false;
            f = (int) (n % COUNT);
            n /= COUNT;
            for (int p = 0; p < PARTS; p++) {
                custom[p] = SOURCES[(int) (n % SOURCES.length)];
                n /= SOURCES.length;
            }
        }
        style = CUSTOM;
        use(f);
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

    /** Draws as Custom would with part `p` from design `d` (the pictures of Custom's choices), then back. */
    static void asCustomPart(int p, int d, Runnable draw) {
        int was = custom[p];
        custom[p] = source(d);
        preview = CUSTOM;
        use(current);
        LiveView.applyLayout();
        try {
            draw.run();
        } finally {
            custom[p] = was;
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
     * The lettering of the design on screen (Live TV's process only: these are Ui's faces): YouTube TV's, Prime
     * Video's and Plex's Roboto, Netflix's a bold Roboto, Sling's narrower Roboto, Pluto TV's narrower and bolder, NOW's,
     * Sky's, Hulu's and Max's Inter a weight bolder, Glassio's, Apple TV's and Disney+'s Inter as it is.
     */
    static void applyFonts(Context c) {
        int d = part(LETTERS);
        Ui.restyle(c, family(d), bolder(d));
    }

    /** Design `d`'s typeface family (null: Inter) and how much bolder than Inter's weights. */
    static String family(int d) {
        switch (d) {
            case YOUTUBE:
            case PRIME:
            case PLEX:
            case NETFLIX: return "sans-serif";
            case SLING:
            case PLUTO: return "sans-serif-condensed";
            default: return null;
        }
    }

    static int bolder(int d) {
        switch (d) {
            case NOW:
            case SKY:
            case HULU:
            case MAX:
            case NETFLIX:
            case PLUTO: return 100;
            default: return 0;
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
            case NETFLIX: return "Netflix";
            case PRIME: return "Prime Video";
            case DISNEY: return "Disney+";
            case MAX: return "Max";
            case PLEX: return "Plex";
            case PLUTO: return "Pluto TV";
            case -1: return Ui.tr("Desligado", "Off");
            default: return "Glassio";
        }
    }

    /** What a design looks like, in a few words (the setting's line). */
    static String styleAbout(int i) {
        switch (i) {
            case APPLE: return Ui.tr("Vidro claro sobre azul profundo, foco branco, cantos bem redondos; a barra num cartão de vidro.",
                    "Clear glass over deep blue, a white focus, very round corners; the info bar on a floating glass card.");
            case YOUTUBE: return Ui.tr("Quase preto, foco branco, detalhes vermelhos, letras Roboto; o guia com o topo compacto e 2 h e meia.",
                    "Near black, a white focus, red details, Roboto lettering; a guide with a compact top and 2½ hours across.");
            case NOW: return Ui.tr("Preto e roxo, foco verde-água; a imagem grande à esquerda.",
                    "Black and purple, a teal focus; a big picture at the left.");
            case SKY: return Ui.tr("O azul do Sky Q, foco azul vivo, cantos retos; o vídeo à direita, linhas finas, a barra numa faixa.",
                    "Sky Q's blue, a bright blue focus, square corners; the video at the right, slim rows, the info bar on a band.");
            case SLING: return Ui.tr("Azul-marinho, foco amarelo, letras estreitas; um vídeo pequeno à direita e muitas linhas.",
                    "Navy, a yellow focus, narrow lettering; a small video at the right and many rows.");
            case HULU: return Ui.tr("Preto, foco verde; a imagem grande à direita.", "Black, a green focus; the large picture at the right.");
            case NETFLIX: return Ui.tr("Quase preto, foco vermelho, cantos quase retos, letras fortes; a imagem maior à direita, a barra com sombra à esquerda.",
                    "Near black, a red focus, nearly square corners, bold lettering; the biggest picture at the right, the info bar shaded from the left.");
            case PRIME: return Ui.tr("Azul-marinho profundo, foco azul vivo; vídeo à esquerda com a imagem do programa, linhas finas.",
                    "Deep navy, a bright blue focus; the video at the left with the show's picture, slim rows.");
            case DISNEY: return Ui.tr("Azul-real que vira noite, vidro com borda clara, foco azul; o vídeo à direita, a barra num cartão de vidro.",
                    "Royal blue into night, glass with a light edge, a blue focus; the video at the right, the info bar on a glass card.");
            case MAX: return Ui.tr("Preto que vira azul profundo, foco azul; a imagem grande à direita e 2 h e meia no guia.",
                    "Black into deep blue, a blue focus; the large picture at the right and 2½ hours in the guide.");
            case PLEX: return Ui.tr("Cinza-escuro, foco âmbar; um vídeo pequeno, 3 horas e muitas linhas, a barra numa faixa.",
                    "Dark grey, an amber focus; a small video, 3 hours and many rows, the info bar on a band.");
            case PLUTO: return Ui.tr("Preto, foco amarelo, letras estreitas e fortes; linhas finas, 1 h e meia, a barra numa faixa.",
                    "Black, a yellow focus, narrow bold lettering; slim rows, 1½ hours, the info bar on a band.");
            case CUSTOM: return Ui.tr("O seu: cada parte de um design, nas linhas abaixo.",
                    "Yours: each part from any design, in the rows below.");
            default: return Ui.tr("O do Glassio: vidro claro sobre grafite, o foco na cor escolhida (branco).",
                    "Glassio's own: clear glass over graphite, the focus in the color you pick (white).");
        }
    }

    /**
     * The info bar as the design on screen draws it, in miniature in `r`: a picture, the fade into the background,
     * the panel (infoBar), bars for the title and the details, the progress and the buttons with the focus on one.
     */
    static void drawInfoSample(android.graphics.Canvas c, android.graphics.RectF r, android.graphics.Paint p) {
        float s = r.width() / 1920f;
        c.save();
        c.clipRect(r);
        c.translate(r.left, r.top);
        c.scale(s, s);
        p.setColor(0xFF000000);        // a shader takes the paint's alpha: opaque, whatever the last picture left
        p.setShader(new android.graphics.LinearGradient(0, 0, 1920, 1080, 0xFF5A6E86, 0xFF1A2230, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1920, 1080, p);
        int base = bg & 0x00FFFFFF;
        p.setShader(new android.graphics.LinearGradient(0, 480, 0, 1080, new int[] {base, 0xB3000000 | base, 0xF2000000 | base},
                new float[] {0, 0.4f, 1}, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 480, 1920, 1080, p);
        p.setShader(null);
        android.graphics.RectF box = new android.graphics.RectF();
        switch (infoBar()) {
            case BAR_CARD: {
                float rr = infoStyle() == DISNEY ? 28 : 44;
                box.set(56, 596, 1864, 1044);
                p.setColor((bgTop & 0x00FFFFFF) | 0x8C000000);
                c.drawRoundRect(box, rr, rr, p);
                p.setColor(0x1AFFFFFF);
                c.drawRoundRect(box, rr, rr, p);
                break;
            }
            case BAR_BAND_LINE:
            case BAR_BAND:
                p.setShader(new android.graphics.LinearGradient(0, 590, 0, 1080, (bgTop & 0x00FFFFFF) | 0xF0000000,
                        base | 0xFA000000, android.graphics.Shader.TileMode.CLAMP));
                c.drawRect(0, 590, 1920, 1080, p);
                p.setShader(null);
                if (infoBar() == BAR_BAND_LINE) {
                    p.setColor(tile);
                    c.drawRect(0, 590, 1920, 594, p);
                }
                break;
            case BAR_SIDE:
                p.setShader(new android.graphics.ComposeShader(
                        new android.graphics.LinearGradient(0, 0, 1190, 0, base | 0xE6000000, base, android.graphics.Shader.TileMode.CLAMP),
                        new android.graphics.LinearGradient(0, 480, 0, 720, 0, 0xFF000000, android.graphics.Shader.TileMode.CLAMP),
                        android.graphics.PorterDuff.Mode.DST_IN));
                c.drawRect(0, 480, 1190, 1080, p);
                p.setShader(null);
                break;
            default:
                break;
        }
        // The title, the details, the progress (red, as LIVE stays) and the buttons, the focus on the first.
        p.setColor(0xF2FFFFFF);
        c.drawRoundRect(320, 680, 1100, 736, 14, 14, p);
        p.setColor(0x99FFFFFF);
        c.drawRoundRect(320, 770, 900, 794, 10, 10, p);
        p.setColor(0x40FFFFFF);
        c.drawRect(96, 852, 1824, 860, p);
        p.setColor(0xFFFF3B30);
        c.drawRect(96, 852, 820, 860, p);
        float rad = 36, br = rad * Math.min(1, round);
        for (int i = 0; i < 6; i++) {
            float x = 96 + i * 92;
            p.setColor(i == 0 ? tile : (glassio() ? 0x2EFFFFFF : (surface | 0x14000000)));
            c.drawRoundRect(x, 956 - rad, x + rad * 2, 956 + rad, br, br, p);
        }
        c.restore();
    }

    /** A part's name (Custom's rows). */
    static String partName(int p) {
        switch (p) {
            case FOCUS: return Ui.tr("Foco", "Focus");
            case CORNERS: return Ui.tr("Cantos", "Corners");
            case GUIDE: return Ui.tr("Guia", "Guide");
            case INFO: return Ui.tr("Barra de informações", "Info bar");
            case LETTERS: return Ui.tr("Letras", "Lettering");
            default: return Ui.tr("Cores", "Colors");
        }
    }

    /** What a part is (its row's line). */
    static String partAbout(int p) {
        switch (p) {
            case FOCUS: return Ui.tr("De qual design vem o foco e o texto nele; o do Glassio tem a cor do foco escolhida (← → para trocar).",
                    "Whose focus, and the text on it; Glassio's takes the focus color you pick (← → to change it).");
            case CORNERS: return Ui.tr("De qual design vem o quanto os blocos e o foco são redondos (← → para trocar).",
                    "Whose corners: how round the tiles and the focus are (← → to change it).");
            case GUIDE: return Ui.tr("De qual design vem o guia: onde ficam o vídeo e os detalhes, as linhas, as horas (← → para trocar).",
                    "Whose guide: where the video and the details are, the rows, the hours across (← → to change it).");
            case INFO: return Ui.tr("De qual design vem a barra que aparece sobre o canal ao assistir (← → para trocar).",
                    "Whose info bar, the one over the channel as you watch (← → to change it).");
            case LETTERS: return Ui.tr("De qual design vêm as letras (← → para trocar).", "Whose lettering (← → to change it).");
            default: return Ui.tr("De qual design vêm o fundo, os blocos e as cores do guia (← → para trocar).",
                    "Whose background, tiles and guide colors (← → to change it).");
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

    /** The light edge's pen, made as it's first needed (the design's code is checked in tests, without Android). */
    private static android.graphics.Paint rimPen;

    /** A tile at rest: its light edge, `width` thick, when the design has one (glass). */
    static void rim(android.graphics.Canvas c, android.graphics.RectF r, float radius, float width) {
        if (rim == 0) return;
        if (rimPen == null) {
            rimPen = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            rimPen.setStyle(android.graphics.Paint.Style.STROKE);
        }
        rimPen.setStrokeWidth(width);
        drawRim(c, r, radius, rimPen);
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
                if (parts.length >= 8 + PARTS) {        // Custom's parts
                    for (int p = 0; p < PARTS; p++) custom[p] = source(Integer.parseInt(parts[8 + p].trim()));
                } else if (parts.length > 5) {      // saved before designs: its background, corners and layout
                    fromOld(Integer.parseInt(parts[3].trim()), Integer.parseInt(parts[4].trim()), Integer.parseInt(parts[5].trim()));
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
            // Places 3 to 5 were Custom's background, corners and layout before designs: kept, read only from old files.
            StringBuilder s = new StringBuilder().append(current).append(',').append(style).append(',').append(night)
                    .append(",0,1,0,").append(morning).append(',').append(late);
            for (int p = 0; p < PARTS; p++) s.append(',').append(custom[p]);
            Xtream.write(file(c), s.toString());
        } catch (IOException e) {
            AppLog.w("live: couldn't save the theme");
        }
    }

    private static void use(int i) {
        current = i < 0 || i >= COUNT ? 0 : i;
        tile = TILES[current];
        text = TEXTS[current];
        colors(part(COLORS));
        focus(part(FOCUS));
        round = corners(part(CORNERS));
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

    /** Design `d`'s colors: the background, the tiles at rest, the guide's programs, the glass edge. */
    private static void colors(int d) {
        rim = 0;
        switch (d) {
            case APPLE:     // Liquid Glass: light glass tiles with a bright edge over deep blue
                bgTop = 0xFF1C2742;
                bg = 0xFF06070B;
                surface = 0x24FFFFFF;
                cellNow = 0x33FFFFFF;
                cellLater = 0x1FFFFFFF;
                cellPast = 0x0FFFFFFF;
                rim = 0x2EFFFFFF;
                break;
            case YOUTUBE:   // flat near-black
                bg = bgTop = 0xFF0F0F0F;
                surface = 0x1AFFFFFF;
                cellNow = 0xFF2A2A2A;
                cellLater = 0xFF212121;
                cellPast = 0xFF161616;
                break;
            case NOW:       // black into purple
                bgTop = 0xFF1E1236;
                bg = 0xFF050308;
                surface = 0x1FFFFFFF;
                cellNow = 0xFF2B2245;
                cellLater = 0xFF1F1933;
                cellPast = 0xFF130F20;
                break;
            case SKY:       // Sky Q's blue
                bgTop = 0xFF0E3270;
                bg = 0xFF030B1F;
                surface = 0x1F8FB8FF;
                cellNow = 0xFF1A3F7A;
                cellLater = 0xFF12305F;
                cellPast = 0xFF0A1C3C;
                break;
            case SLING:     // navy
                bg = bgTop = 0xFF0A1322;
                surface = 0x1F9FB8E0;
                cellNow = 0xFF22324F;
                cellLater = 0xFF19253D;
                cellPast = 0xFF101929;
                break;
            case HULU:      // black
                bg = bgTop = 0xFF0B0C0F;
                surface = 0x1AFFFFFF;
                cellNow = 0xFF262931;
                cellLater = 0xFF1C1E23;
                cellPast = 0xFF131418;
                break;
            case NETFLIX:   // near black
                bg = bgTop = 0xFF141414;
                surface = 0x1AFFFFFF;
                cellNow = 0xFF2B2B2B;
                cellLater = 0xFF222222;
                cellPast = 0xFF181818;
                break;
            case PRIME:     // deep navy
                bgTop = 0xFF1B2733;
                bg = 0xFF0B1118;
                surface = 0x1F9EC4E6;
                cellNow = 0xFF233140;
                cellLater = 0xFF1A2632;
                cellPast = 0xFF111A23;
                break;
            case DISNEY:    // royal blue into night, glass with a light edge
                bgTop = 0xFF14307A;
                bg = 0xFF040714;
                surface = 0x24FFFFFF;
                cellNow = 0x33FFFFFF;
                cellLater = 0x1FFFFFFF;
                cellPast = 0x0FFFFFFF;
                rim = 0x33FFFFFF;
                break;
            case MAX:       // black into deep blue
                bgTop = 0xFF0B1650;
                bg = 0xFF02040F;
                surface = 0x1F94A8FF;
                cellNow = 0xFF1B2660;
                cellLater = 0xFF141D4A;
                cellPast = 0xFF0C1232;
                break;
            case PLEX:      // dark grey
                bgTop = 0xFF2A2A2A;
                bg = 0xFF141414;
                surface = 0x1AFFFFFF;
                cellNow = 0xFF333333;
                cellLater = 0xFF2A2A2A;
                cellPast = 0xFF1E1E1E;
                break;
            case PLUTO:     // black
                bgTop = 0xFF101010;
                bg = 0xFF000000;
                surface = 0x1AFFFFFF;
                cellNow = 0xFF262626;
                cellLater = 0xFF1C1C1C;
                cellPast = 0xFF121212;
                break;
            default:        // Glassio: clear glass tiles with a light edge over graphite
                bgTop = 0xFF2B2B30;
                bg = 0xFF0E0E11;
                surface = 0x24FFFFFF;
                cellNow = 0x33FFFFFF;
                cellLater = 0x1FFFFFFF;
                cellPast = 0x0FFFFFFF;
                rim = 0x2EFFFFFF;
                break;
        }
    }

    /** Design `d`'s focus, the text on it and its highlights (Glassio's: the focus color picked). */
    private static void focus(int d) {
        switch (d) {
            case APPLE: tile = 0xF2F5F5F7; ink = 0xFF1C1C1E; text = 0xFF64D2FF; break;     // white
            case YOUTUBE: tile = 0xFFF1F1F1; ink = 0xFF0F0F0F; text = 0xFFFF4E45; break;   // white, red
            case NOW: tile = 0xFF16C7B0; ink = 0xFF041210; text = 0xFF3FE0CB; break;       // teal
            case SKY: tile = 0xFF1E7BF2; ink = 0xFFF5F5F7; text = 0xFF8EC0FF; break;       // bright blue
            case SLING: tile = 0xFFFFC21A; ink = 0xFF0A1322; text = 0xFFFFC21A; break;     // yellow
            case HULU: tile = 0xFF1CE783; ink = 0xFF0B0C0F; text = 0xFF1CE783; break;      // green
            case NETFLIX: tile = 0xFFE50914; ink = 0xFFFFFFFF; text = 0xFFFF3D47; break;   // red
            case PRIME: tile = 0xFF1A98FF; ink = 0xFFFFFFFF; text = 0xFF5AB4FF; break;     // bright blue
            case DISNEY: tile = 0xFF0072D2; ink = 0xFFFFFFFF; text = 0xFF6FC3FF; break;    // Disney's blue
            case MAX: tile = 0xFF002BE7; ink = 0xFFFFFFFF; text = 0xFF7D96FF; break;       // Max's blue
            case PLEX: tile = 0xFFE5A00D; ink = 0xFF1F1F1F; text = 0xFFF0B429; break;      // amber
            case PLUTO: tile = 0xFFFFF200; ink = 0xFF000000; text = 0xFFFFF200; break;     // yellow
            default: {      // the color picked; a light one (white) takes dark text on it
                int lum = ((tile >> 16) & 255) * 3 + ((tile >> 8) & 255) * 6 + (tile & 255);
                ink = lum > 1700 ? 0xFF1C1C1E : 0xFFF5F5F7;
                break;
            }
        }
    }

    /** How round design `d`'s corners are (1: about Glassio's home screen's lists). */
    private static float corners(int d) {
        switch (d) {
            case APPLE: return 1.8f;
            case YOUTUBE: return 1.1f;
            case NOW:
            case HULU: return 1.3f;
            case SKY: return 0.45f;
            case SLING: return 0.8f;
            case NETFLIX: return 0.55f;
            case PRIME:
            case MAX: return 0.9f;
            case DISNEY: return 1.2f;
            case PLEX: return 0.7f;
            case PLUTO: return 0.6f;
            default: return 1.5f;       // Glassio: round like the home screen's
        }
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
                GradientDrawable card = glassio() ? new GradientDrawable()
                        : new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {panel, bg});
                if (glassio()) {      // Glassio: its dialogs' dark glass with the light edge
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
