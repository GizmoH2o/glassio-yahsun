package com.glasslauncher.tv.live;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.List;

/**
 * The player's Match Centre: over the lower part of the picture, the score, then the match's timeline, stats, lineups
 * and table; ◀ ▶ change section, ▲ ▼ move along a long one, Back closes. Read once a minute while it's open. While it
 * would spoil the match it asks first, showing no score. The design's colors, corners and focus.
 */
final class LivePlayerMatch {
    private static final float X = 96, R = 1824, TOP = 330, BOTTOM = 1040, PAD = 40, BODY = 500, LINE = 50;
    private static final int TEXT = 0xFFF5F5F7, SOFT = 0xB3F5F5F7, GOAL = 0xFF30D158, YELLOW = 0xFFFFD60A, RED = 0xFFFF453A;
    private static final long EVERY = 60_000;

    private final LiveView view;
    private final Spring slide = new Spring(0.28f, 0.9f, 0);
    private final RectF r = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint fit = new TextPaint();
    private Paint score, teams, small, row, rowSoft, head, pill, ask, say, button;
    private float k = 1;
    private boolean open, locked, asking;
    private String event = "";
    private Runnable reveal;
    private LiveMatchCentre.Data data;
    private int[] sections = new int[0];
    private int section, first;
    private long readAt;

    LivePlayerMatch(LiveView view) {
        this.view = view;
    }

    void size(float scale) {
        k = scale;
        score = Ui.text(Ui.displaySemibold, 56 * k, TEXT);
        teams = Ui.text(Ui.displaySemibold, 36 * k, TEXT);
        small = Ui.text(Ui.medium, 24 * k, SOFT);
        row = Ui.text(Ui.medium, 28 * k, TEXT);
        rowSoft = Ui.text(Ui.medium, 26 * k, SOFT);
        head = Ui.text(Ui.semibold, 22 * k, SOFT);
        pill = Ui.text(Ui.semibold, 26 * k, TEXT);
        ask = Ui.text(Ui.displaySemibold, 34 * k, TEXT);
        say = Ui.text(Ui.medium, 26 * k, SOFT);
        button = Ui.text(Ui.semibold, 28 * k, TEXT);
    }

    boolean open() {
        return open;
    }

    /** `locked`: asks first, and OK runs `reveal`. */
    void show(String event, boolean locked, Runnable reveal) {
        this.event = event;
        this.locked = locked;
        this.reveal = reveal;
        data = null;
        sections = new int[0];
        section = first = 0;
        readAt = 0;
        slide.snap(0);
        slide.target = 1;
        open = true;
        read();
        view.announceForAccessibility(Ui.tr("Central da partida", "Match Centre"));
        view.invalidate();
    }

    void unlock() {
        locked = false;
        view.invalidate();
    }

    void close() {
        open = false;
        slide.target = 0;
    }

    private void read() {
        if (asking) return;
        asking = true;
        readAt = System.currentTimeMillis();
        LiveMatchCentre.load(view.getContext(), event, d -> view.post(() -> {
            asking = false;
            if (!open || d == null) {
                view.invalidate();
                return;
            }
            boolean firstRead = data == null;
            int was = sections.length == 0 ? -1 : sections[Math.min(section, sections.length - 1)];
            data = d;
            sections = LiveMatchCentre.sections(d);
            section = 0;
            for (int i = 0; i < sections.length; i++) if (sections[i] == was) section = i;
            if (firstRead) first = landing();
            view.invalidate();
        }));
    }

    private int current() {
        return sections.length == 0 ? -1 : sections[Math.min(section, sections.length - 1)];
    }

    /** Where a section opens: the table on the match's own sides, the rest at the top. */
    private int landing() {
        return current() == LiveMatchCentre.TABLE ? Math.max(0, LiveMatchCentre.firstMine(data.table) - 2) : 0;
    }

    /** How many rows the section has, and how many fit. */
    private int count() {
        int s = current();
        return s == LiveMatchCentre.TIMELINE ? data.events.size() : s == LiveMatchCentre.TABLE ? data.table.size() : 0;
    }

    private static int room() {
        return (int) ((BODY - 44) / LINE);
    }

    boolean step(float dt) {
        if (open && !locked && !asking && System.currentTimeMillis() - readAt > EVERY) read();
        return slide.step(dt);
    }

    /** The section a press lands on. Pure. */
    static int stepSection(int section, int dir, int count) {
        return count == 0 ? 0 : Math.max(0, Math.min(count - 1, section + dir));
    }

    /** ◀ ▶ ▲ ▼ and OK; true for those, so they don't reach what's under it. */
    boolean key(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                if (locked || sections.length == 0) return true;
                int to = stepSection(section, code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, sections.length);
                if (to != section) {
                    section = to;
                    first = landing();
                    view.announceForAccessibility(name(current()));
                    view.invalidate();
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                if (locked || data == null) return true;
                first = LiveMatchCentre.top(first + (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1), room(), count());
                view.invalidate();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (locked) reveal.run();
                return true;
            default:
                return false;
        }
    }

    private static String name(int s) {
        switch (s) {
            case LiveMatchCentre.TIMELINE: return Ui.tr("Linha do tempo", "Timeline");
            case LiveMatchCentre.STATS: return Ui.tr("Estatísticas", "Stats");
            case LiveMatchCentre.LINEUPS: return Ui.tr("Escalações", "Lineups");
            default: return Ui.tr("Tabela", "Table");
        }
    }

    void draw(Canvas c) {
        float a = Ui.clamp(slide.value);
        c.save();
        c.translate(0, (1 - a) * (1 - a) * 60 * k);
        r.set(X * k, TOP * k, R * k, BOTTOM * k);
        float round = LiveTheme.panelRound(28 * k);
        fill.setColor((LiveTheme.card & 0x00FFFFFF) | 0xF2000000);
        c.drawRoundRect(r, round, round, fill);
        LiveTheme.rim(c, r, round, 1.5f * k);
        if (locked) drawAsk(c);
        else if (data == null) drawNote(c, asking ? Ui.tr("Carregando a partida…", "Loading the match…") : Ui.tr("Não foi possível ler a partida", "Couldn't read this match"));
        else {
            drawHeader(c);
            if (sections.length == 0) drawNote(c, Ui.tr("Mais detalhes no início da partida", "More details at kick-off"));
            else {
                drawPills(c);
                switch (current()) {
                    case LiveMatchCentre.TIMELINE: drawTimeline(c); break;
                    case LiveMatchCentre.STATS: drawStats(c); break;
                    case LiveMatchCentre.LINEUPS: drawLineups(c); break;
                    default: drawTable(c); break;
                }
            }
        }
        c.restore();
    }

    private void drawNote(Canvas c, String words) {
        c.drawText(words, (X + PAD) * k, (TOP + 200) * k, say);
    }

    private void drawHeader(Canvas c) {
        float y = (TOP + 74) * k, x = (X + PAD) * k;
        String line = data.state.equals("pre") ? data.home + " v " + data.away : data.home + "  " + data.homeScore + "–" + data.awayScore + "  " + data.away;
        c.drawText(line, x, y, score);
        x += score.measureText(line) + 32 * k;
        c.drawText(ellipsize(data.clock + (data.league.isEmpty() ? "" : "  ·  " + data.league), small, (R - PAD) * k - x), x, y, small);
    }

    private void drawPills(Canvas c) {
        float x = (X + PAD) * k, y = (TOP + 106) * k, h = 52 * k;
        for (int i = 0; i < sections.length; i++) {
            String words = name(sections[i]);
            float w = pill.measureText(words) + 44 * k;
            r.set(x, y, x + w, y + h);
            boolean on = i == section;
            fill.setColor(on ? LiveTheme.tile : 0x1FFFFFFF);
            c.drawRoundRect(r, h / 2, h / 2, fill);
            pill.setColor(on ? LiveTheme.ink : TEXT);
            c.drawText(words, x + 22 * k, y + h / 2 + Ui.halfCap(pill), pill);
            x += w + 14 * k;
        }
        int more = count() - room();
        if (more > 0) {
            head.setTextAlign(Paint.Align.RIGHT);
            c.drawText(Ui.tr("▲ ▼ para rolar", "▲ ▼ to scroll"), (R - PAD) * k, y + h / 2 + Ui.halfCap(head), head);
            head.setTextAlign(Paint.Align.LEFT);
        }
    }

    private float bodyTop() {
        return (TOP + 190) * k;
    }

    private void drawTimeline(Canvas c) {
        List<LiveMatchCentre.Event> list = data.events;
        float y = bodyTop(), mid = (X + R) / 2 * k;
        int show = Math.min(list.size(), first + room());
        for (int i = first; i < show; i++) {
            LiveMatchCentre.Event e = list.get(i);
            float line = y + (i - first + 1) * LINE * k - 10 * k;
            float half = 330 * k;
            float dot = e.home ? mid - 60 * k : mid + 60 * k;
            fill.setColor(e.kind == LiveMatchCentre.GOAL ? GOAL : e.kind == LiveMatchCentre.YELLOW ? YELLOW : e.kind == LiveMatchCentre.RED ? RED : 0x66FFFFFF);
            c.drawCircle(dot, line - 9 * k, 9 * k, fill);
            rowSoft.setTextAlign(Paint.Align.CENTER);
            c.drawText(e.minute, mid, line, rowSoft);
            rowSoft.setTextAlign(Paint.Align.LEFT);
            String who = (e.kind == LiveMatchCentre.GOAL ? Ui.tr("Gol: ", "Goal: ") : e.kind == LiveMatchCentre.CHANGE ? "" : e.kind == LiveMatchCentre.RED ? Ui.tr("Vermelho: ", "Red: ") : "") + e.who;
            row.setTextAlign(e.home ? Paint.Align.RIGHT : Paint.Align.LEFT);
            c.drawText(ellipsize(who, row, half + 180 * k), e.home ? mid - 84 * k : mid + 84 * k, line, row);
            row.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawStats(Canvas c) {
        List<LiveMatchCentre.Stat> list = data.stats;
        float y = bodyTop(), left = (X + PAD) * k, right = (R - PAD) * k, mid = (left + right) / 2, step = 54 * k;
        float barL = left + 90 * k, barR = right - 90 * k, labelW = 150 * k;
        int rows = Math.min(list.size(), 9);
        for (int i = 0; i < rows; i++) {
            LiveMatchCentre.Stat s = list.get(i);
            float line = y + (i + 1) * step - 16 * k;
            float share = LiveMatchCentre.share(s.home, s.away);
            c.drawText(s.home, left, line, row);
            row.setTextAlign(Paint.Align.RIGHT);
            c.drawText(s.away, right, line, row);
            row.setTextAlign(Paint.Align.LEFT);
            rowSoft.setTextAlign(Paint.Align.CENTER);
            c.drawText(Ui.tr(pt(s.label), s.label), mid, line, rowSoft);
            rowSoft.setTextAlign(Paint.Align.LEFT);
            float t = line - 8 * k, b = line + 2 * k;
            float homeEnd = mid - labelW, awayStart = mid + labelW;
            fill.setColor(0x1FFFFFFF);
            c.drawRoundRect(barL, t, homeEnd, b, 5 * k, 5 * k, fill);
            c.drawRoundRect(awayStart, t, barR, b, 5 * k, 5 * k, fill);
            if (LiveMatchCentre.none(s)) continue;      // 0–0: bars that say so, not half each
            fill.setColor(LiveTheme.tile);
            float len = homeEnd - barL;
            c.drawRoundRect(homeEnd - len * share, t, homeEnd, b, 5 * k, 5 * k, fill);
            fill.setColor(TEXT);
            c.drawRoundRect(awayStart, t, awayStart + (barR - awayStart) * (1 - share), b, 5 * k, 5 * k, fill);
        }
    }

    private static String pt(String label) {
        switch (label) {
            case "Possession": return "Posse de bola";
            case "Shots": return "Finalizações";
            case "On target": return "No alvo";
            case "Corners": return "Escanteios";
            case "Saves": return "Defesas";
            case "Fouls": return "Faltas";
            case "Yellow cards": return "Cartões amarelos";
            case "Red cards": return "Cartões vermelhos";
            case "Offsides": return "Impedimentos";
            default: return label;
        }
    }

    private void drawLineups(Canvas c) {
        float mid = (X + R) / 2 * k;
        drawSide(c, data.homeName, data.homeLine, (X + PAD) * k, mid - 20 * k);
        drawSide(c, data.awayName, data.awayLine, mid + 20 * k, (R - PAD) * k);
    }

    private void drawSide(Canvas c, String team, LiveMatchCentre.Lineup line, float left, float right) {
        float y = bodyTop() + 22 * k, step = 40 * k;
        String title = team + (line.formation.isEmpty() ? "" : "  ·  " + line.formation);
        c.drawText(ellipsize(title, head, right - left), left, y, head);
        // Two columns of six and five: all eleven fit under the title.
        int half = (line.starters.size() + 1) / 2;
        float width = (right - left) / 2;
        for (int i = 0; i < line.starters.size(); i++) {
            LiveMatchCentre.Player p = line.starters.get(i);
            int col = i < half ? 0 : 1, at = i < half ? i : i - half;
            float x = left + col * width, ly = y + (at + 1) * step + 12 * k;
            rowSoft.setTextAlign(Paint.Align.RIGHT);
            c.drawText(p.number, x + 44 * k, ly, rowSoft);
            rowSoft.setTextAlign(Paint.Align.LEFT);
            c.drawText(ellipsize(p.name, row, width - 70 * k), x + 60 * k, ly, row);
        }
    }

    private void drawTable(Canvas c) {
        float left = (X + PAD) * k, right = (R - PAD) * k, y = bodyTop() + 22 * k;
        float[] cols = {right - 480 * k, right - 400 * k, right - 320 * k, right - 240 * k, right - 150 * k, right};
        String[] names = {"P", "W", "D", "L", "GD", "Pts"};
        head.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i < cols.length; i++) c.drawText(names[i], cols[i], y, head);
        head.setTextAlign(Paint.Align.LEFT);
        int show = Math.min(data.table.size(), first + room());
        for (int i = first; i < show; i++) {
            LiveMatchCentre.Row t = data.table.get(i);
            float line = y + (i - first + 1) * LINE * k;
            if (t.mine) {
                fill.setColor(0x1FFFFFFF);
                r.set(left - 16 * k, line - 36 * k, right + 16 * k, line + 12 * k);
                c.drawRoundRect(r, 12 * k, 12 * k, fill);
            }
            rowSoft.setTextAlign(Paint.Align.RIGHT);
            c.drawText(t.rank, left + 34 * k, line, rowSoft);
            rowSoft.setTextAlign(Paint.Align.LEFT);
            c.drawText(ellipsize(t.team, row, cols[0] - left - 150 * k), left + 56 * k, line, row);
            String[] vals = {t.played, t.won, t.drawn, t.lost, t.difference, t.points};
            row.setTextAlign(Paint.Align.RIGHT);
            for (int j = 0; j < vals.length; j++) c.drawText(vals[j], cols[j], line, row);
            row.setTextAlign(Paint.Align.LEFT);
        }
    }

    /** Asked first, so opening it never spoils the match. */
    private void drawAsk(Canvas c) {
        float left = (X + PAD) * k, y = (TOP + 150) * k;
        c.drawText(Ui.tr("A central da partida pode estragar o jogo", "Match Centre can spoil the match"), left, y, ask);
        c.drawText(Ui.tr("O placar e os lances ficam escondidos até você pedir.", "The score and events stay hidden until you say so."), left, y + 48 * k, say);
        String words = Ui.tr("Mostrar a partida", "Show the match");
        float w = button.measureText(words) + 64 * k;
        r.set(left, y + 90 * k, left + w, y + 170 * k);
        fill.setColor(LiveTheme.tile);
        float round = LiveTheme.panelRound(16 * k);
        c.drawRoundRect(r, round, round, fill);
        button.setColor(LiveTheme.ink);
        c.drawText(words, r.left + 32 * k, r.centerY() + Ui.halfCap(button), button);
    }

    private String ellipsize(String s, Paint p, float width) {
        fit.set(p);
        return TextUtils.ellipsize(s == null ? "" : s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
    }
}
