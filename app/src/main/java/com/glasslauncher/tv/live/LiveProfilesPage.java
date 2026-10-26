package com.glasslauncher.tv.live;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.List;

/**
 * Who's watching? (Apple TV's, Netflix's): each profile a colored circle with its initial and name, a Kids one marked,
 * then Add profile. OK switches (leaving a Kids profile asks for the parental PIN); held OK (or Menu) renames or
 * removes one; Back closes.
 */
final class LiveProfilesPage extends Dialog {
    private final LiveView view;
    private final Live live;
    private final Page page;
    private List<LiveProfiles.Profile> profiles;
    private int focus;
    private boolean okDown, okHeld;

    LiveProfilesPage(Context c, LiveView view, Live live) {
        super(c, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        this.view = view;
        this.live = live;
        page = new Page(c);
        reload();
        String active = LiveProfiles.active(c).id;
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).id.equals(active)) focus = i;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(page);
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        page.setFocusable(true);
        page.requestFocus();
        page.announceForAccessibility(Ui.tr("Quem está assistindo?", "Who's watching?"));
    }

    private void reload() {
        profiles = LiveProfiles.all(getContext());
        focus = Math.max(0, Math.min(focus, profiles.size()));
        page.invalidate();
    }

    // MARK: - Keys

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down && !e.isCanceled()) dismiss();
            return true;
        }
        if (code == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down) {     // OK acts when let go; held, the profile's choices
                if (e.getRepeatCount() == 0) {
                    okDown = true;
                    okHeld = false;
                } else if (okDown && !okHeld) {
                    okHeld = true;
                    choices();
                }
            } else {
                if (okDown && !okHeld && !e.isCanceled()) pick();
                okDown = okHeld = false;
            }
            return true;
        }
        if (!down) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: focus = Math.max(0, focus - 1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: focus = Math.min(profiles.size(), focus + 1); break;
            case KeyEvent.KEYCODE_MENU: choices(); break;
            default: break;
        }
        page.announceForAccessibility(focus < profiles.size() ? profiles.get(focus).name : Ui.tr("Adicionar perfil", "Add profile"));
        page.invalidate();
        return true;
    }

    /** OK: that profile watches (from Kids, after the PIN); on Add profile, a new one. */
    private void pick() {
        if (focus >= profiles.size()) {
            add();
            return;
        }
        final LiveProfiles.Profile to = profiles.get(focus);
        Context c = getContext();
        LiveProfiles.Profile now = LiveProfiles.active(c);
        if (to.id.equals(now.id)) {
            dismiss();
            return;
        }
        Runnable go = () -> {
            dismiss();
            live.switchProfile(to.id);
            view.flash(Ui.tr("Assistindo como ", "Watching as ") + to.name);
        };
        if (now.kids && LiveOptions.hasPin(c)) view.askPin(go, null);       // Kids can't leave on its own
        else go.run();
    }

    /** Add profile: an ordinary one or a Kids one, then its name. */
    private void add() {
        LiveSheet.menu(getContext())
                .setTitle(Ui.tr("Adicionar perfil", "Add profile"))
                .setIcons("person", "kids")
                .setItems(new String[] {Ui.tr("Perfil", "Profile"), Ui.tr("Perfil infantil", "Kids profile")},
                        (d, which) -> askName(null, which == 1))
                .show();
    }

    /** The name, typed (a new profile when `p` is null, else `p` renamed). */
    private void askName(final LiveProfiles.Profile p, final boolean kids) {
        final Context c = getContext();
        String start = p != null ? p.name : kids ? Ui.tr("Crianças", "Kids") : "";
        LiveNamePad.ask(c, p != null ? Ui.tr("Renomear", "Rename") : Ui.tr("Nome do perfil", "Profile name"), start, typed -> {
            String name = typed.isEmpty() ? (kids ? Ui.tr("Crianças", "Kids") : Ui.tr("Perfil ", "Profile ") + (profiles.size() + 1)) : typed;
            if (p != null) {
                LiveProfiles.rename(c, p.id, name);
            } else {
                LiveProfiles.add(c, name, kids);
                focus = profiles.size();
                // A Kids profile is only shut with a PIN: set one now when there's none.
                if (kids && !LiveOptions.hasPin(c)) {
                    view.askNewPin(() -> view.flash(Ui.tr("PIN pronto: sair do perfil infantil pede ele", "PIN set: leaving the Kids profile asks for it")));
                }
            }
            reload();
        });
    }

    /** Held OK on a profile: rename it, or remove it (not the one watching). */
    private void choices() {
        if (focus >= profiles.size()) return;
        final LiveProfiles.Profile p = profiles.get(focus);
        final Context c = getContext();
        boolean watching = p.id.equals(LiveProfiles.active(c).id);
        String[] items = watching ? new String[] {Ui.tr("Renomear", "Rename")} : new String[] {Ui.tr("Renomear", "Rename"), Ui.tr("Remover", "Remove")};
        LiveSheet.menu(c)
                .setTitle(p.name)
                .setMessage(watching ? Ui.tr("Assistindo agora", "Watching now") : "")
                .setIcons("edit", "delete")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        askName(p, p.kids);
                    } else {
                        LiveProfiles.remove(c, p.id);
                        view.flash(Ui.tr("Perfil removido", "Profile removed"));
                        reload();
                    }
                })
                .show();
    }

    // MARK: - Drawing

    /** The page, drawn by hand (1920 × 1080 space, k = width / 1920). */
    private final class Page extends View {
        private static final float D = 200, GAP = 72, CY = 540;
        private final TextPaint fit = new TextPaint();
        private final Spring in = new Spring(0.32f, 0.9f, 0), at = new Spring(0.28f, 0.9f, 0);
        private Paint fill, ring, title, initial, name, nameOn, pill, hint;
        private float k;
        private long last;

        Page(Context c) {
            super(c);
            in.target = 1;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(6 * k);
            title = Ui.text(Ui.displaySemibold, 64 * k, 0xFFF5F5F7);
            title.setTextAlign(Paint.Align.CENTER);
            initial = Ui.text(Ui.displaySemibold, 92 * k, 0xFFFFFFFF);
            initial.setTextAlign(Paint.Align.CENTER);
            name = Ui.text(Ui.medium, 30 * k, 0x99FFFFFF);
            name.setTextAlign(Paint.Align.CENTER);
            nameOn = Ui.text(Ui.semibold, 30 * k, 0xFFF5F5F7);
            nameOn.setTextAlign(Paint.Align.CENTER);
            pill = Ui.text(Ui.semibold, 18 * k, 0xFFFFFFFF);
            hint = Ui.text(Ui.regular, 24 * k, 0x80FFFFFF);
            hint.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            if (fill == null) return;
            long up = SystemClock.uptimeMillis();
            float dt = last == 0 ? 1 / 60f : Math.min(0.05f, (up - last) / 1000f);
            last = up;
            at.target = focus;
            boolean moving = in.step(dt) | at.step(dt);
            float a = Ui.clamp(in.value), w = getWidth();
            LiveTheme.background(c, getWidth(), getHeight(), a);
            c.save();
            c.translate(0, (1 - a) * 40 * k);
            c.drawText(Ui.tr("Quem está assistindo?", "Who's watching?"), w / 2, 300 * k, title);
            int n = profiles.size() + 1;
            float total = n * D + (n - 1) * GAP, x0 = (1920 - total) / 2 + D / 2;
            String active = LiveProfiles.active(getContext()).id;
            for (int i = 0; i < n; i++) {
                float cx = (x0 + i * (D + GAP)) * k, cy = CY * k;
                float on = Math.max(0, 1 - Math.abs(at.value - i));     // 1 on the focused one, gliding
                float r = (D / 2) * k * (1 + 0.1f * on);
                boolean add = i == profiles.size();
                LiveProfiles.Profile p = add ? null : profiles.get(i);
                fill.setColor(add ? LiveTheme.surface | 0x05000000 : p.color);
                c.drawCircle(cx, cy, r, fill);
                if (on > 0.01f) {
                    ring.setColor(Ui.alpha(LiveTheme.tile, on));     // the focus in the style's color
                    c.drawCircle(cx, cy, r + 10 * k, ring);
                }
                if (add) {
                    Symbols.draw(getContext(), c, "add", cx, cy, 72 * k, 0xE6FFFFFF);
                } else {
                    String letter = p.name.isEmpty() ? "?" : p.name.substring(0, 1).toUpperCase(Ui.locale());
                    c.drawText(letter, cx, cy + Ui.halfCap(initial), initial);
                }
                Paint np = i == focus ? nameOn : name;
                String label = add ? Ui.tr("Adicionar perfil", "Add profile") : p.name;
                c.drawText(ellipsize(label, np, (D + GAP - 10) * k), cx, cy + r + 56 * k, np);
                float tagY = cy + r + 76 * k;
                if (!add && p.kids) tagY = drawPill(c, Ui.tr("INFANTIL", "KIDS"), cx, tagY, 0xFFF2A100);
                if (!add && p.id.equals(active)) drawPill(c, Ui.tr("ASSISTINDO", "WATCHING"), cx, tagY, 0x33FFFFFF);
            }
            c.drawText(Ui.tr("Segure OK num perfil para renomear ou remover", "Hold OK on a profile to rename or remove it"),
                    w / 2, 960 * k, hint);
            c.restore();
            if (moving) postInvalidateOnAnimation(); else last = 0;
        }

        /** A small tag centered on cx, its top at y; returns where the next one goes. */
        private float drawPill(Canvas c, String text, float cx, float y, int color) {
            float tw = pill.measureText(text), pw = tw + 22 * k, ph = 30 * k;
            fill.setColor(color);
            c.drawRoundRect(cx - pw / 2, y, cx + pw / 2, y + ph, 8 * k, 8 * k, fill);
            c.drawText(text, cx - tw / 2, y + ph / 2 + Ui.halfCap(pill), pill);
            return y + ph + 10 * k;
        }

        private String ellipsize(String s, Paint p, float width) {
            fit.set(p);
            return TextUtils.ellipsize(s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
        }
    }
}
