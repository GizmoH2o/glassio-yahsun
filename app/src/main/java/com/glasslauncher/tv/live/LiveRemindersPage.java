package com.glasslauncher.tv.live;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Reminders, a page of their own: by day, each with its show, its channel, where it comes from and how early it's told.
 * OK: its channel, its time, record it, switch by itself, cancel (▶ puts a cancelled one back for a moment). Back closes.
 */
final class LiveRemindersPage extends LiveDialog {
    private static final long UNDO_MS = 8_000;

    private final LiveView view;
    private final Page page;
    private final List<LiveSchedule.Item> items = new ArrayList<>();
    private int at;
    private LiveSchedule.Item undo;
    private String told = "";
    private long undoUntil;

    LiveRemindersPage(Context c, LiveView view) {
        super(c);
        this.view = view;
        page = new Page(c);
        reload();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(page);
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        page.setFocusable(true);
        page.requestFocus();
        LiveSpeech.say(page, Ui.tr("Lembretes", "Reminders"));
    }

    /** A word at the foot for a moment (Live TV's own is behind this page). */
    private void tell(String what) {
        undo = null;
        told = what;
        undoUntil = SystemClock.uptimeMillis() + 3_000;
        page.postDelayed(page::invalidate, 3_050);
        page.invalidate();
        LiveSpeech.say(page, what);
    }

    private void reload() {
        items.clear();
        for (LiveSchedule.Item it : LiveSchedule.all(getContext())) {
            if (!LiveSchedule.RECORD.equals(it.kind)) items.add(it);
        }
        at = Math.max(0, Math.min(at, items.size() - 1));
        page.invalidate();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down && !e.isCanceled()) dismiss();
            return true;
        }
        if (code == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
        if (!down) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                at = Math.max(0, at - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                at = Math.max(0, Math.min(items.size() - 1, at + 1));
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (undo != null && SystemClock.uptimeMillis() < undoUntil) putBack();
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0 && at < items.size()) choices(items.get(at));
                break;
            default:
                break;
        }
        if ((code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) && at < items.size()) {
            LiveSchedule.Item it = items.get(at);
            LiveSpeech.say(page, LiveSpeech.join(it.title, dayOf(it.start) + " " + Ui.time(it.start), view.nameWithCountry(it.ch)));
        }
        page.invalidate();
        return true;
    }

    private void choices(LiveSchedule.Item it) {
        final Context c = getContext();
        List<String> labels = new ArrayList<>(), icons = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        if (LiveSchedule.REMIND.equals(it.kind)) {       // a switch about to come: cancelled, no more
        labels.add(Ui.tr("Canal", "Channel"));
        icons.add("list");
        acts.add(() -> channel(it));
        labels.add(Ui.tr("Lembrar…", "Remind me…"));
        icons.add("bell");
        acts.add(() -> lead(it));
        labels.add(Ui.tr("Gravar", "Record it"));
        icons.add("record");
        acts.add(() -> {
            LiveSchedule.cancel(c, it.key());       // (its rule doesn't set it again)
            LiveSchedule.Item rec = it.as(LiveSchedule.RECORD);
            rec.series = null;
            LiveSchedule.add(c, rec);
            tell(Ui.tr("Grava às ", "Records at ") + Ui.time(it.start));
            reload();
        });
        labels.add(it.auto ? Ui.tr("Perguntar antes", "Ask first") : Ui.tr("Mudar sozinho", "Switch by itself"));
        icons.add("play");
        acts.add(() -> {
            it.auto = !it.auto;
            LiveSchedule.add(c, it);
            reload();
        });
        }
        labels.add(Ui.tr("Cancelar", "Cancel"));
        icons.add("close");
        acts.add(() -> {
            LiveSchedule.cancel(c, it.key());
            undo = it;
            undoUntil = SystemClock.uptimeMillis() + UNDO_MS;
            page.postDelayed(page::invalidate, UNDO_MS + 50);
            reload();
        });
        LiveSheet.menu(c).setTitle(it.title).setMessage(dayOf(it.start) + ", " + Ui.time(it.start) + "  ·  " + view.nameWithCountry(it.ch))
                .setIcons(icons.toArray(new String[0])).setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
    }

    private void putBack() {
        Context c = getContext();
        LiveSeries.unskip(c, undo);
        LiveFollow.unskip(c, undo);
        LiveSchedule.add(c, undo);
        undo = null;
        tell(Ui.tr("Lembrete de volta", "Reminder back"));
        reload();
    }

    /** Every channel showing it then, the best first. */
    private void channel(LiveSchedule.Item it) {
        final Context c = getContext(), app = c.getApplicationContext();
        new Thread(() -> {
            final List<Object[]> found = LiveFollow.showing(app, it);
            page.post(() -> {
                if (!isShowing()) return;
                if (found.size() < 2) {
                    tell(Ui.tr("Nenhum outro canal no guia", "No other channel in the guide"));
                    return;
                }
                String[] names = new String[found.size()];
                for (int i = 0; i < names.length; i++) names[i] = view.nameWithCountry((Xtream.Channel) found.get(i)[0]);
                LiveSheet.menu(c).asList().setTitle(it.title).setItems(names, (d, which) -> {
                    // What's followed isn't set twice anyway, and mustn't be left out should the guide move it.
                    if (LiveFollow.isAlert(it)) LiveSchedule.remove(c, it.key());
                    else LiveSchedule.cancel(c, it.key());
                    it.mine = true;
                    it.ch = (Xtream.Channel) found.get(which)[0];
                    LiveSchedule.add(c, it);
                    reload();
                }).show();
            });
        }, "reminder-channels").start();
    }

    private void lead(LiveSchedule.Item it) {
        final Context c = getContext();
        final int[] mins = LiveOptions.REMIND_LEAD;
        String[] names = new String[mins.length + 1];
        names[0] = Ui.tr("Como nos ajustes", "As in Settings");
        int now = 0;
        for (int i = 0; i < mins.length; i++) {
            names[i + 1] = mins[i] == 0 ? Ui.tr("Na hora", "As it starts") : mins[i] + Ui.tr(" min antes", " min before");
            if (it.lead == mins[i]) now = i + 1;
        }
        LiveSheet.menu(c).setTitle(Ui.tr("Lembrar…", "Remind me…")).setSingleChoiceItems(names, now, (d, which) -> {
            it.lead = which == 0 ? -1 : mins[which - 1];
            LiveSchedule.add(c, it);
            d.dismiss();
            reload();
        }).show();
    }

    private static String dayOf(long t) {
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyyDDD", java.util.Locale.ROOT);
        String d = day.format(new java.util.Date(t));
        long now = System.currentTimeMillis();
        if (d.equals(day.format(new java.util.Date(now)))) return Ui.tr("Hoje", "Today");
        if (d.equals(day.format(new java.util.Date(now + 24 * 3600_000L)))) return Ui.tr("Amanhã", "Tomorrow");
        return new java.text.SimpleDateFormat("EEEE d MMMM", Ui.locale()).format(new java.util.Date(t));
    }

    private String from(LiveSchedule.Item it) {
        if (it.was > 0 && it.was != it.start) return Ui.tr("mudou das ", "moved from ") + Ui.time(it.was);
        if (LiveFollow.isAlert(it)) return LiveFollow.named(getContext(), it.series.substring(LiveFollow.SERIES.length()));
        return it.series != null ? Ui.tr("todos os episódios", "every episode") : "";
    }

    private String lead(Context c, LiveSchedule.Item it) {
        int m = it.lead >= 0 ? it.lead : LiveOptions.remindLead(c);
        return (it.auto || LiveOptions.remindAuto(c) && !LiveFollow.isAlert(it) ? Ui.tr("Muda sozinho  ·  ", "Switches by itself  ·  ") : "")
                + (m == 0 ? Ui.tr("na hora", "as it starts") : m + Ui.tr(" min antes", " min before"));
    }

    private final class Page extends View {
        private static final float X = 96, R = 1824, TOP = 226, ROW = 92, STEP = 100, HEAD = 62;
        private final RectF r = new RectF();
        private final TextPaint fit = new TextPaint();
        private final Spring scroll = new Spring(0.3f, 1f, 0);
        private Paint fill, title, meta, heading, name, sub, time, line;
        private float k;
        private long last;

        Page(Context c) {
            super(c);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(3 * k);
            title = Ui.text(Ui.displaySemibold, 54 * k, 0xFFF5F5F7);
            meta = Ui.text(Ui.regular, 26 * k, 0x8CFFFFFF);
            heading = Ui.text(Ui.semibold, 28 * k, 0xE6FFFFFF);
            name = Ui.text(Ui.semibold, 28 * k, 0xFFF5F5F7);
            sub = Ui.text(Ui.regular, 23 * k, 0xFFB3B3B8);
            time = Ui.numbers(Ui.medium, 27 * k, 0xFFF5F5F7);
        }

        @Override protected void onDraw(Canvas c) {
            if (fill == null) return;
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 1 / 60f : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            boolean moving = scroll.step(dt);
            LiveTheme.background(c, getWidth(), getHeight(), 1);
            c.drawText(Ui.tr("Lembretes", "Reminders"), X * k, 132 * k, title);
            c.drawText(items.size() + Ui.tr(" marcados", " set"), X * k + title.measureText(Ui.tr("Lembretes", "Reminders")) + 28 * k, 132 * k, meta);
            if (items.isEmpty()) {
                String none = Ui.tr("Nada marcado: segure OK num programa do guia.", "Nothing set: hold OK on a show in the guide.");
                c.drawText(none, (getWidth() - name.measureText(none)) / 2, getHeight() * 0.45f, name);
            }
            // Where each row goes, its day's heading before the first of it.
            float y = 0, focusTop = 0, bottom = 0;
            float[] tops = new float[items.size()];
            String day = "";
            for (int i = 0; i < items.size(); i++) {
                String d = dayOf(items.get(i).start);
                if (!d.equals(day)) y += HEAD;
                day = d;
                tops[i] = y;
                if (i == at) focusTop = y;
                y += STEP;
            }
            bottom = y;
            float room = 1080 - TOP - 70;
            scroll.target = Math.max(0, Math.min(Math.max(0, bottom - room), focusTop - room * 0.4f));
            c.save();
            c.clipRect(0, (TOP - 10) * k, getWidth(), (TOP + room) * k);
            day = "";
            boolean soft = LiveTheme.cinema() && LiveOptions.guideSoft(getContext()) && !LiveTheme.highContrast;
            for (int i = 0; i < items.size(); i++) {
                LiveSchedule.Item it = items.get(i);
                float top = TOP + tops[i] - scroll.value;
                String d = dayOf(it.start);
                if (!d.equals(day)) c.drawText(d, X * k, (top - 18) * k, heading);
                day = d;
                if (top > 1080 || top + ROW < TOP - 80) continue;
                boolean on = i == at, dark = on && !soft;
                r.set(X * k, top * k, R * k, (top + ROW) * k);
                float rr = 16 * k * LiveTheme.round;
                fill.setColor(on ? soft ? 0xFF3A3A40 : LiveTheme.tile : LiveTheme.cellLater);
                c.drawRoundRect(r, rr, rr, fill);
                if (on && soft) {
                    line.setColor(LiveTheme.tile);
                    c.drawRoundRect(r, rr, rr, line);
                }
                name.setColor(dark ? LiveTheme.ink : 0xFFF5F5F7);
                time.setColor(dark ? LiveTheme.ink : 0xFFF5F5F7);
                sub.setColor(dark ? LiveTheme.inkSoft() : 0xFFB3B3B8);
                float mid = r.centerY();
                c.drawText(Ui.time(it.start), r.left + 28 * k, mid + Ui.halfCap(time), time);
                String told = lead(getContext(), it);
                float tw = sub.measureText(told), x = r.left + 210 * k, w = r.right - 30 * k - tw - 40 * k - x;
                c.drawText(told, r.right - 30 * k - tw, mid + Ui.halfCap(sub), sub);
                c.drawText(cut(it.title.isEmpty() ? it.ch.name : it.title, name, w), x, mid - 6 * k, name);
                String from = from(it);
                c.drawText(cut((it.ch.num > 0 ? it.ch.num + "  " : "") + view.nameWithCountry(it.ch) + (from.isEmpty() ? "" : "  ·  " + from), sub, w), x, mid + 26 * k, sub);
            }
            c.restore();
            if ((undo != null || !told.isEmpty()) && now < undoUntil) {
                String t = undo == null ? told : cut(Ui.tr("Cancelado: ", "Cancelled: ") + undo.title, name, 1100 * k) + "  ·  ▶ " + Ui.tr("desfaz", "undo");
                float tw = name.measureText(t);
                r.set((getWidth() - tw) / 2 - 36 * k, 968 * k, (getWidth() + tw) / 2 + 36 * k, 1036 * k);
                fill.setColor(0xF22C2C30);
                c.drawRoundRect(r, 34 * k, 34 * k, fill);
                name.setColor(0xFFF5F5F7);
                c.drawText(t, r.left + 36 * k, r.centerY() + Ui.halfCap(name), name);
            }
            if (moving) postInvalidateOnAnimation(); else last = 0;
        }

        private String cut(String s, Paint p, float w) {
            fit.set(p);
            return TextUtils.ellipsize(s, fit, Math.max(1, w), TextUtils.TruncateAt.END).toString();
        }
    }
}
