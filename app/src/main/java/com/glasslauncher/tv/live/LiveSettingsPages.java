package com.glasslauncher.tv.live;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Settings' two pages of their own: the connection check (what the last 20 seconds and the week say, and what to
 * change) and the Guide page (how much of the list has a guide, whether it's on time, and what's missing).
 */
final class LiveSettingsPages {
    private final Live live;
    private final LiveView view;
    private final LiveSettingsHome cards;
    private final LiveCheck check;
    private final RectF r = new RectF();
    private final TextPaint fit = new TextPaint();
    private Paint title, big, label, value, small;
    private TextPaint words;
    private float k = 1;
    private int focus;
    /** What the check's suggestion replaced, for Undo; applied or not. */
    private int oldBuffer;
    private String oldFormat;
    private boolean applied;
    /** The guide's numbers, worked out away from the screen: channels, those with a guide, those without. */
    private volatile int total, have;
    private volatile List<String> missing = new ArrayList<>();

    /** A guide offset of `dir` steps, in the settings. */
    interface Shift {
        void by(int dir);
    }

    private final Shift shift;

    LiveSettingsPages(Live live, LiveView view, LiveSettingsHome cards, Shift shift) {
        this.live = live;
        this.view = view;
        this.cards = cards;
        this.shift = shift;
        check = new LiveCheck(live, view);
    }

    void size(float scale) {
        k = scale;
        title = Ui.text(Ui.displaySemibold, 62 * k, 0xFFFFFFFF);
        big = Ui.text(Ui.displaySemibold, 74 * k, 0xFFFFFFFF);
        label = Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF);
        value = Ui.text(Ui.medium, 28 * k, 0xFFFFFFFF);
        value.setTextAlign(Paint.Align.RIGHT);
        small = Ui.text(Ui.medium, 26 * k, 0xE6FFFFFF);
        words = new TextPaint(Ui.text(Ui.regular, 28 * k, 0xFFFFFFFF));
    }

    /** The guide's numbers, off the main thread; the home screen's card reads them too. */
    void countGuide() {
        final Xtream.Lineup lineup = view.lineup();
        if (lineup == null) return;
        new Thread(() -> {
            Set<String> guided = XmltvStore.guided(live);
            List<Xtream.Channel> all = lineup.all();
            List<String> without = new ArrayList<>();
            Set<String> locked = LiveOptions.locked(live);      // never by name on this page, PIN or not
            locked.addAll(LiveOptions.adultIds(lineup.categories));
            java.util.Map<String, String> groups = new java.util.HashMap<>();
            for (Xtream.Category g : lineup.categories) groups.put(g.id, g.name);
            int with = 0, expected = 0;
            for (Xtream.Channel ch : new ArrayList<>(all)) {
                if (ch.epg != null && guided.contains(ch.epg.toLowerCase(Locale.ROOT))) {
                    with++;
                    expected++;
                } else if (LiveGuideMatch.expectsGuide(groups.get(ch.category), ch.name)) {
                    expected++;
                    if (!isIn(locked, ch)) without.add((ch.num > 0 ? ch.num + "  " : "") + view.nameOf(ch));
                }
            }
            total = expected;
            have = with;
            missing = without;
            view.postInvalidate();
        }, "settings-guide").start();
    }

    private static boolean isIn(Set<String> categories, Xtream.Channel ch) {
        if (categories.contains(ch.category)) return true;
        if (ch.sources != null) for (Xtream.Channel s : ch.sources) if (categories.contains(s.category)) return true;
        return false;
    }

    boolean guideKnown() {
        return total > 0 && have > 0;
    }

    int guidePercent() {
        return LiveCheck.percent(have, total);
    }

    int guideMissing() {
        return total - have;
    }

    // MARK: - Connection check

    void openCheck() {
        focus = 0;
        applied = false;
        check.start();
    }

    void closeCheck() {
        check.stop();
    }

    void drawCheck(Canvas c) {
        c.drawText(Ui.tr("Verificar a conexão", "Connection check"), 96 * k, 130 * k, title);
        Xtream.Channel on = live.playing();
        LiveCheck.Advice a = check.advice();
        String under = on == null ? Ui.tr("Comece um canal primeiro: a verificação usa o que está passando.", "Start a channel first: the check uses what's playing.")
                : check.running() ? Ui.tr("Testando em ", "Testing on ") + view.nameOf(on) + "…  " + check.secondsDone() + " / " + LiveCheck.SECONDS + " s"
                : Ui.tr("Testado em ", "Tested on ") + view.nameOf(on) + Ui.tr(" por 20 segundos. O canal continuou passando.", " for 20 seconds. The channel kept playing.");
        c.drawText(ellipsize(under, small, 1720 * k), 96 * k, 184 * k, small);
        if (on == null) return;
        String[][] rows = {
                {Ui.tr("Velocidade do canal", "Stream speed"), check.mbps() > 0 ? String.format(Locale.ROOT, "%.1f Mbps", check.mbps()) : "—"},
                {Ui.tr("Menor reserva", "Lowest reserve"), String.format(Locale.ROOT, "%.1f s", check.lowest() / 1000f)},
                {Ui.tr("Paradas agora", "Stops during the check"), String.valueOf(check.stopsNow())},
                {Ui.tr("Paradas nesta semana", "Stops this week"), check.channelStops() + Ui.tr(" aqui", " here") + "  ·  " + check.weekStops() + Ui.tr(" em todos os canais", " on all channels")}};
        float y = 236;
        for (String[] row : rows) {
            r.set(96 * k, y * k, 1020 * k, (y + 118) * k);
            cards.card(c, r, false, 18);
            c.drawText(row[0], r.left + 30 * k, r.centerY() + Ui.halfCap(label), label);
            c.drawText(row[1], r.right - 30 * k, r.centerY() + Ui.halfCap(value), value);
            y += 136;
        }
        if (a == null) return;
        r.set(1060 * k, 236 * k, 1824 * k, 1000 * k);
        cards.card(c, r, false, 26);
        float x = r.left + 40 * k, width = r.width() - 80 * k;
        String head = a.good ? Ui.tr("Tudo certo", "All good") : Ui.tr("Sugerimos uma mudança", "We suggest a change");
        label.setColor(a.good ? LiveSettingsHome.GOOD : LiveSettingsHome.WARN);
        c.drawText(head, x, r.top + 66 * k, label);
        label.setColor(0xFFFFFFFF);
        String text = a.good ? Ui.tr("O sinal ficou estável. Nada a mudar.", "The stream stayed steady. There's nothing to change.")
                : LiveCheck.troubleNow(check.stopsNow(), check.lowest())
                ? Ui.tr("O canal parou ou ficou com pouca reserva. Mais buffer ajuda, com uma espera um pouco maior ao trocar de canal.",
                "The stream stopped or ran low on reserve. More buffer helps, with a slightly longer wait when you change channel.")
                : Ui.tr("O canal foi bem agora, mas parou muitas vezes nesta semana. Mais buffer ajuda, com uma espera um pouco maior ao trocar de canal.",
                "This channel played smoothly just now, but it has stopped often this week. More buffer helps, with a slightly longer wait when you change channel.");
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), words, Math.max(1, Math.round(width)))
                .setLineSpacing(0, 1.15f).setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build();
        c.save();
        c.translate(x, r.top + 96 * k);
        layout.draw(c);
        c.restore();
        float at = r.top + 110 * k + layout.getHeight() + 34 * k;
        if (!a.good) {
            at = change(c, Ui.tr("Buffer", "Buffer"), bufferName(applied ? oldBuffer : LiveOptions.buffer(live)), bufferName(a.buffer), x, r.right - 40 * k, at);
            if (!a.format.equals(oldOrNow())) at = change(c, Ui.tr("Formato", "Stream format"), formatName(oldOrNow()), formatName(a.format), x, r.right - 40 * k, at);
        }
        float by = r.bottom - 184 * k;
        if (!a.good) {
            button(c, applied ? Ui.tr("Feito. Desfazer", "Done. Undo") : Ui.tr("Usar estes", "Use these"), x, by, width, focus == 0);
            button(c, Ui.tr("Voltar", "Back"), x, by + 84 * k, width, focus == 1);
        } else {
            button(c, Ui.tr("Voltar", "Back"), x, by + 84 * k, width, true);
        }
    }

    private String oldOrNow() {
        return applied ? oldFormat : LiveOptions.format(live);
    }

    private float change(Canvas c, String name, String from, String to, float left, float right, float y) {
        c.drawText(name, left, y, label);
        c.drawText(from.equals(to) ? to : from + " → " + to, right, y, value);
        return y + 50 * k;
    }

    private void button(Canvas c, String text, float x, float y, float width, boolean on) {
        r.set(x, y, x + width, y + 72 * k);
        cards.card(c, r, on, 16);
        label.setColor(on ? LiveTheme.ink : 0xFFFFFFFF);
        c.drawText(text, r.centerX() - label.measureText(text) / 2, r.centerY() + Ui.halfCap(label), label);
        label.setColor(0xFFFFFFFF);
    }

    private static String bufferName(int b) {
        return b == LiveOptions.EXTRA ? Ui.tr("Extra grande", "Extra large") : b == LiveOptions.LARGE ? Ui.tr("Grande", "Large") : Ui.tr("Normal", "Normal");
    }

    private static String formatName(String f) {
        return LiveOptions.HLS.equals(f) ? "HLS" : LiveOptions.TS.equals(f) ? "TS" : Ui.tr("Automático", "Auto");
    }

    /** ▲ ▼ between the buttons, OK presses; false: Back, on OK when nothing's left to do. */
    boolean checkKey(int code, Runnable back) {
        LiveCheck.Advice a = check.advice();
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (a != null && !a.good) focus = code == KeyEvent.KEYCODE_DPAD_UP ? 0 : 1;
            view.invalidate();
            return true;
        }
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (!ok || a == null) return true;
        if (a.good || focus == 1) {
            back.run();
            return true;
        }
        if (applied) {
            LiveOptions.setBuffer(live, oldBuffer);
            LiveOptions.setFormat(live, oldFormat);
        } else {
            oldBuffer = LiveOptions.buffer(live);
            oldFormat = LiveOptions.format(live);
            LiveOptions.setBuffer(live, a.buffer);
            LiveOptions.setFormat(live, a.format);
        }
        applied = !applied;
        live.playbackChanged();
        view.invalidate();
        return true;
    }

    // MARK: - Guide page

    private static final int UPDATE = 0, OFFSET = 1, MISSING = 2, HIDE = 3, ROWS = 4;

    void openGuide() {
        focus = UPDATE;
        countGuide();
    }

    void drawGuide(Canvas c) {
        c.drawText(Ui.tr("Guia", "Guide"), 96 * k, 130 * k, title);
        r.set(96 * k, 170 * k, 700 * k, 470 * k);
        cards.card(c, r, false, 24);
        boolean known = guideKnown();
        c.drawText(known ? guidePercent() + "%" : "—", r.left + 32 * k, r.top + 104 * k, big);
        c.drawText(known ? Ui.tr("dos seus canais de TV têm guia", "of your TV channels have a guide")
                : Ui.tr("Ligue o guia completo para ver", "Turn on the full guide to see this"), r.left + 32 * k, r.top + 156 * k, small);
        float bar = r.left + 32 * k, barW = r.width() - 64 * k, barY = r.top + 200 * k;
        cards.card(c, bar(bar, barY, barW), false, 4);
        if (known) {
            r.set(bar, barY, bar + barW * guidePercent() / 100f, barY + 12 * k);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(LiveSettingsHome.GOOD);
            c.drawRoundRect(r, 6 * k, 6 * k, p);
        }
        long at = XmltvStore.updatedAt(live);
        r.set(96 * k, 490 * k, 700 * k, 760 * k);
        cards.card(c, r, false, 24);
        float left = r.left + 32 * k, right = r.right - 32 * k, y = r.top + 66 * k;
        line(c, Ui.tr("Atualizado", "Last update"), at == 0 ? Ui.tr("Ainda não", "Not yet") : Ui.time(at), left, right, y);
        line(c, Ui.tr("Programas", "Shows"), String.valueOf(XmltvStore.count(live)), left, right, y + 64 * k);
        line(c, Ui.tr("Dias à frente", "Days ahead"), String.valueOf(LiveOptions.daysAhead(live)), left, right, y + 128 * k);
        String now = onNow();
        r.set(96 * k, 780 * k, 700 * k, 1000 * k);
        cards.card(c, r, false, 24);
        c.drawText(Ui.tr("O guia está na hora certa?", "Is the guide on time?"), r.left + 32 * k, r.top + 60 * k, label);
        c.drawText(ellipsize(Ui.tr("Passando agora: ", "On now: ") + now, small, r.width() - 64 * k), r.left + 32 * k, r.top + 112 * k, small);
        c.drawText(Ui.tr("Se não for isso, ajuste abaixo.", "If that's wrong, move the guide."), r.left + 32 * k, r.top + 160 * k, small);
        String[] names = {XmltvStore.running() ? Ui.tr("Atualizando…", "Updating…") : Ui.tr("Atualizar agora", "Update now"),
                Ui.tr("Ajustar o horário do guia", "Guide time offset"),
                Ui.tr("Canais sem guia", "Channels without a guide"),
                Ui.tr("Esconder canais sem guia", "Hide channels with no guide")};
        String[] values = {"", "◀  " + LiveOptions.shiftName(LiveOptions.guideShiftMinutes(live)) + "  ▶",
                known ? String.valueOf(guideMissing()) : "—", LiveOptions.hideNoGuide(live) ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off")};
        for (int i = 0; i < ROWS; i++) {
            float top = 170 + i * 112;
            r.set(740 * k, top * k, 1824 * k, (top + 96) * k);
            boolean on = i == focus;
            cards.card(c, r, on, 16);
            label.setColor(on ? LiveTheme.ink : 0xFFFFFFFF);
            value.setColor(on ? LiveTheme.ink : 0xFFFFFFFF);
            c.drawText(names[i], r.left + 30 * k, r.centerY() + Ui.halfCap(label), label);
            c.drawText(values[i], r.right - 30 * k, r.centerY() + Ui.halfCap(value), value);
        }
        label.setColor(0xFFFFFFFF);
        value.setColor(0xFFFFFFFF);
        if (XmltvStore.running()) view.postInvalidateDelayed(500);
    }

    private RectF bar(float x, float y, float width) {
        r.set(x, y, x + width, y + 12 * k);
        return r;
    }

    private void line(Canvas c, String name, String text, float left, float right, float y) {
        c.drawText(name, left, y, label);
        c.drawText(text, right, y, value);
    }

    private String onNow() {
        Xtream.Program p = view.playingShow();
        return p == null ? "—" : p.title;
    }

    /** ▲ ▼ between the rows, ◀ ▶ the offset, OK presses. */
    void guideKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                focus = Math.max(0, focus - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                focus = Math.min(ROWS - 1, focus + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (focus == OFFSET) shift.by(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                press();
                break;
            default:
                break;
        }
        view.invalidate();
    }

    private void press() {
        if (focus == UPDATE) {
            if (!LiveOptions.fullGuide(live)) view.flash(Ui.tr("Primeiro ligue o guia completo.", "Turn on the full guide first."));
            else if (!XmltvStore.running()) live.updateGuide(true);
        } else if (focus == OFFSET) {
            shift.by(1);
        } else if (focus == MISSING) {
            List<String> list = missing;
            if (list.isEmpty()) return;
            LiveSheet.menu(view.getContext()).asList().setTitle(Ui.tr("Canais sem guia", "Channels without a guide"))
                    .setItems(list.subList(0, Math.min(300, list.size())).toArray(new String[0]), (d, which) -> {})
                    .show();
        } else {
            LiveOptions.setHideNoGuide(live, !LiveOptions.hideNoGuide(live));
            live.relist();
        }
    }

    private String ellipsize(String s, Paint p, float width) {
        fit.set(p);
        return TextUtils.ellipsize(s == null ? "" : s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
    }
}
