package com.glasslauncher.tv.live;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Playlists, a page of cards: each with its name, whether it's in use, the days it has left, its streams, channels, guide
 * and what's set on it. OK: use it, rename it, remove it. The last card adds one. Back closes.
 */
final class LivePlaylistsPage extends LiveDialog {
    private static final int COLUMNS = 3;

    private final LiveView view;
    private final Live live;
    private final Page page;
    private List<LivePlaylists.Playlist> list = new ArrayList<>();
    private final List<LivePlaylists.Info> infos = new ArrayList<>();
    private String active = "", pending = "";
    private int at;

    LivePlaylistsPage(Context c, LiveView view, Live live) {
        super(c);
        this.view = view;
        this.live = live;
        page = new Page(c);
        reload();
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(active)) at = i;
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
        LiveSpeech.say(page, Ui.tr("Listas", "Playlists"));
    }

    private void reload() {
        Context c = getContext();
        list = LivePlaylists.all(c);
        active = LivePlaylists.active(c);
        pending = LivePlaylists.pending(c);
        infos.clear();
        for (LivePlaylists.Playlist p : list) infos.add(LivePlaylists.info(c, p));
        at = Math.max(0, Math.min(at, list.size()));
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
        int was = at;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: at = Math.max(0, at - 1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: at = Math.min(list.size(), at + 1); break;
            case KeyEvent.KEYCODE_DPAD_UP: if (at >= COLUMNS) at -= COLUMNS; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (at + COLUMNS <= list.size()) at += COLUMNS; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) pick();
                break;
            default: break;
        }
        if (at != was) LiveSpeech.say(page, at < list.size() ? list.get(at).name() : Ui.tr("Adicionar uma lista", "Add a playlist"));
        page.invalidate();
        return true;
    }

    private void pick() {
        final Context c = getContext();
        if (at >= list.size()) {
            dismiss();
            view.closeSettings();
            live.addPlaylist();
            return;
        }
        final LivePlaylists.Playlist p = list.get(at);
        final boolean on = p.id.equals(active);
        List<String> labels = new ArrayList<>(), icons = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        if (!on && p.id.equals(pending)) {
            labels.add(Ui.tr("Não mudar mais", "Don't switch after all"));
            icons.add("close");
            acts.add(() -> {
                LivePlaylists.setPending(c, "");
                reload();
            });
        }
        if (!on) {
            labels.add(Ui.tr("Usar esta lista", "Use this playlist"));
            icons.add("play");
            acts.add(() -> {
                dismiss();
                view.closeSettings();
                live.switchPlaylist(p.id);
            });
        }
        labels.add(Ui.tr("Renomear", "Rename"));
        icons.add("list");
        acts.add(() -> LiveNamePad.ask(c, Ui.tr("Nome da lista", "Playlist name"), p.label, name -> {
            LivePlaylists.rename(c, p.id, name);
            reload();
        }));
        labels.add(on ? Ui.tr("Sair da conta", "Sign out") : Ui.tr("Tirar esta lista", "Remove this playlist"));
        icons.add("delete");
        acts.add(() -> {
            if (on) {
                dismiss();
                view.confirmSignOut();
                return;
            }
            LiveSheet.menu(c).setTitle(Ui.tr("Tirar esta lista?", "Remove this playlist?"))
                    .setMessage(p.name() + ": " + Ui.tr("os favoritos, o Histórico e os lembretes dela somem.", "its favorites, History and reminders go."))
                    .setIcons("delete", "close")
                    .setItems(new String[] {Ui.tr("Tirar", "Remove"), Ui.tr("Cancelar", "Cancel")}, (d, w) -> {
                        if (w != 0) return;
                        LivePlaylists.remove(c, p.id);
                        view.flash(Ui.tr("Lista tirada", "Playlist removed"));
                        reload();
                    }).show();
        });
        LiveSheet.menu(c).setTitle(p.name()).setMessage(p.host())
                .setIcons(icons.toArray(new String[0])).setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
    }

    private final class Page extends View {
        private static final float X = 96, TOP = 226, W = 560, H = 300, GAP = 24;
        private final RectF r = new RectF();
        private final TextPaint fit = new TextPaint();
        private Paint fill, title, name, sub, line, state;
        private float k;

        Page(Context c) {
            super(c);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setStyle(Paint.Style.STROKE);
            title = Ui.text(Ui.displaySemibold, 54 * k, 0xFFF5F5F7);
            name = Ui.text(Ui.semibold, 34 * k, 0xFFF5F5F7);
            sub = Ui.text(Ui.regular, 24 * k, 0xFFB3B3B8);
            state = Ui.text(Ui.medium, 26 * k, 0xFFF5F5F7);
        }

        @Override protected void onDraw(Canvas c) {
            if (fill == null) return;
            LiveTheme.background(c, getWidth(), getHeight(), 1);
            c.drawText(Ui.tr("Listas", "Playlists"), X * k, 132 * k, title);
            boolean soft = LiveTheme.cinema() && LiveOptions.guideSoft(getContext()) && !LiveTheme.highContrast;
            for (int i = 0; i <= list.size(); i++) {
                float x = X + (i % COLUMNS) * (W + GAP), y = TOP + (i / COLUMNS) * (H + GAP);
                r.set(x * k, y * k, (x + W) * k, (y + H) * k);
                boolean on = i == at, dark = on && !soft;
                float rr = 22 * k * LiveTheme.round;
                fill.setColor(on ? soft ? 0xFF3A3A40 : LiveTheme.tile : LiveTheme.cellLater);
                c.drawRoundRect(r, rr, rr, fill);
                if (on && soft) {
                    line.setStrokeWidth(3 * k);
                    line.setColor(LiveTheme.tile);
                    c.drawRoundRect(r, rr, rr, line);
                }
                name.setColor(dark ? LiveTheme.ink : 0xFFF5F5F7);
                state.setColor(dark ? LiveTheme.ink : 0xFFF5F5F7);
                sub.setColor(dark ? LiveTheme.inkSoft() : 0xFFB3B3B8);
                if (i == list.size()) {
                    String t = "+  " + Ui.tr("Adicionar uma lista", "Add a playlist");
                    c.drawText(t, r.centerX() - state.measureText(t) / 2, r.centerY() + Ui.halfCap(state), state);
                    continue;
                }
                LivePlaylists.Playlist p = list.get(i);
                LivePlaylists.Info n = infos.get(i);
                float tx = r.left + 34 * k, w = r.width() - 68 * k;
                c.drawText(cut(p.name(), name, w), tx, r.top + 64 * k, name);
                c.drawText(cut(p.label.isEmpty() ? "" : p.host(), sub, w), tx, r.top + 100 * k, sub);
                boolean used = p.id.equals(active), soon = n.daysLeft >= 0 && n.daysLeft <= 14;
                int dot = n.daysLeft >= 0 && n.daysLeft <= 3 ? 0xFFFF453A : soon ? 0xFFFFB340 : used ? 0xFF4CD07D : 0xFF8E8E93;
                fill.setColor(dot);
                c.drawCircle(tx + 8 * k, r.top + 151 * k, 8 * k, fill);
                String st = soon && n.daysLeft < 2 ? n.daysLeft == 0 ? Ui.tr("Termina hoje", "Ends today") : Ui.tr("Termina amanhã", "Ends tomorrow") : soon ? Ui.tr("Termina em ", "Ends in ") + n.daysLeft + Ui.tr(" dias", " days") : used ? Ui.tr("Em uso", "In use")
                        : p.id.equals(pending) ? Ui.tr("Muda quando a gravação terminar", "Switches when the recording ends") : Ui.tr("Guardada", "Put aside");
                c.drawText(cut(st, state, w - 30 * k), tx + 30 * k, r.top + 160 * k, state);
                StringBuilder a = new StringBuilder();
                if (n.channels > 0) a.append(java.text.NumberFormat.getInstance(Ui.locale()).format(n.channels)).append(Ui.tr(" canais", " channels"));
                if (n.daysLeft >= 0 && !soon) a.append(a.length() > 0 ? "  ·  " : "").append(n.daysLeft).append(Ui.tr(" dias restantes", " days left"));
                c.drawText(cut(a.toString(), sub, w), tx, r.top + 208 * k, sub);
                StringBuilder b = new StringBuilder();
                if (n.max > 0) b.append(n.max).append(n.max == 1 ? Ui.tr(" canal por vez", " stream at once") : Ui.tr(" canais ao mesmo tempo", " streams at once"));
                if (n.guideAt > 0) b.append(b.length() > 0 ? "  ·  " : "").append(Ui.tr("guia ", "guide ")).append(Ui.time(n.guideAt));
                c.drawText(cut(b.toString(), sub, w), tx, r.top + 240 * k, sub);
                StringBuilder d = new StringBuilder();
                if (n.recordings > 0) d.append(n.recordings).append(n.recordings == 1 ? Ui.tr(" gravação", " recording") : Ui.tr(" gravações", " recordings"));
                if (n.reminders > 0) d.append(d.length() > 0 ? ", " : "").append(n.reminders).append(n.reminders == 1 ? Ui.tr(" lembrete", " reminder") : Ui.tr(" lembretes", " reminders"));
                if (d.length() > 0) c.drawText(cut(d + Ui.tr(" marcados", " set"), sub, w), tx, r.top + 272 * k, sub);
            }
        }

        private String cut(String s, Paint p, float w) {
            fit.set(p);
            return TextUtils.ellipsize(s, fit, Math.max(1, w), TextUtils.TruncateAt.END).toString();
        }
    }
}
