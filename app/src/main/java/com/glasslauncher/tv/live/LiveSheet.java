package com.glasslauncher.tv.live;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * Live TV's menus as Apple TV's bottom sheet: a card rising from the bottom over the dimmed video, a title and a line
 * of text, then the choices as tiles in a row (an icon in each, its name under it), the focused one bigger in the
 * theme's color. ← → choose, OK picks, Back closes. Built like an AlertDialog (title, message, items or two buttons),
 * so the menus moved over as they were; the dialogs that need typing stay AlertDialogs.
 */
final class LiveSheet extends Dialog {
    private CharSequence title = "", message = "";
    private CharSequence[] labels = new CharSequence[0];
    private int checked = -1;
    private static final int MAX_TILES = 12;
    private String[] icons;
    private DialogInterface.OnClickListener onItem;
    private final int[] which;        // what each tile reports (the item's index, or BUTTON_POSITIVE / NEGATIVE)
    private final Sheet sheet;

    static Builder menu(Context c) {
        return new Builder(c);
    }

    /** As AlertDialog.Builder: setTitle, setMessage, setItems (or the two buttons), setIcons, show. */
    static final class Builder {
        private final Context c;
        private CharSequence title = "", message = "";
        private CharSequence[] items;
        private String[] icons;
        private DialogInterface.OnClickListener onItem, onPositive, onNegative;
        private CharSequence positive, negative;
        private DialogInterface.OnCancelListener onCancel;
        private int checked = -1;

        Builder(Context c) {
            this.c = c;
        }

        private boolean list;

        /** Long lines (a sentence each): the scrolling list, whatever how many. */
        Builder asList() {
            list = true;
            return this;
        }

        Builder setTitle(CharSequence t) {
            title = t == null ? "" : t;
            return this;
        }

        Builder setMessage(CharSequence m) {
            message = m == null ? "" : m;
            return this;
        }

        Builder setItems(CharSequence[] items, DialogInterface.OnClickListener l) {
            this.items = items;
            onItem = l;
            return this;
        }

        /** The items with the current one checked (and the focus on it first). */
        Builder setSingleChoiceItems(CharSequence[] items, int checked, DialogInterface.OnClickListener l) {
            this.checked = checked;
            return setItems(items, l);
        }

        /** One symbol name per item (Symbols; null for none), or per button: positive first. */
        Builder setIcons(String... names) {
            icons = names;
            return this;
        }

        Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener l) {
            positive = text;
            onPositive = l;
            return this;
        }

        Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener l) {
            negative = text;
            onNegative = l;
            return this;
        }

        Builder setOnCancelListener(DialogInterface.OnCancelListener l) {
            onCancel = l;
            return this;
        }

        LiveSheet create() {
            int n = items != null ? items.length : (positive != null ? 1 : 0) + (negative != null ? 1 : 0);
            LiveSheet s = new LiveSheet(c, n);
            s.title = title;
            s.message = message;
            s.icons = icons;
            s.checked = checked;
            if (checked >= 0 && checked < n) {
                s.sheet.focus = checked;
                s.sheet.at.snap(checked);       // the highlight starts there too
            }
            if (items != null) {
                s.labels = items;
                s.onItem = onItem;
                for (int i = 0; i < n; i++) s.which[i] = i;
            } else {
                s.labels = new CharSequence[n];
                int i = 0;
                if (positive != null) {
                    s.labels[i] = positive;
                    s.which[i++] = DialogInterface.BUTTON_POSITIVE;
                }
                if (negative != null) {
                    s.labels[i] = negative;
                    s.which[i] = DialogInterface.BUTTON_NEGATIVE;
                }
                final DialogInterface.OnClickListener pos = onPositive, neg = onNegative;
                s.onItem = (d, w) -> {
                    DialogInterface.OnClickListener l = w == DialogInterface.BUTTON_POSITIVE ? pos : neg;
                    if (l != null) l.onClick(d, w);
                };
            }
            if (onCancel != null) s.setOnCancelListener(onCancel);
            return s;
        }

        /** The sheet; a long list (more than a row of tiles) as the scrolling list instead. */
        DialogInterface show() {
            if (items != null && (items.length > MAX_TILES || list)) {
                android.app.AlertDialog.Builder b = LiveTheme.dialog(c).setTitle(title);
                if (checked >= 0) b.setSingleChoiceItems(items, checked, onItem);
                else b.setItems(items, onItem);
                if (onCancel != null) b.setOnCancelListener(onCancel);
                return b.show();
            }
            LiveSheet s = create();
            s.show();
            return s;
        }
    }

    private LiveSheet(Context c, int n) {
        super(c, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        which = new int[n];
        sheet = new Sheet(c);
    }

    private boolean isChecked(int i) {
        return i == checked;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(sheet);
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        sheet.setFocusable(true);
        sheet.requestFocus();
        sheet.announceForAccessibility(title + (message.length() > 0 ? ". " + message : "")
                + (labels.length > 0 ? ". " + labels[0] : ""));
    }

    /** The text under the title, again (a countdown). */
    void setMessage(CharSequence m) {
        message = m == null ? "" : m;
        sheet.layout = null;
        sheet.invalidate();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) cancel();
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                sheet.move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                sheet.move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() > 0 || labels.length == 0) return true;
                int w = which[sheet.focus];
                dismiss();
                if (onItem != null) onItem.onClick(this, w);
                return true;
            case KeyEvent.KEYCODE_HOME:
                return super.dispatchKeyEvent(e);
            default:
                return true;        // ↑ ↓ and the rest: nothing behind the sheet
        }
    }

    /** The card, drawn by hand (1920 × 1080 space, k = width / 1920). */
    private final class Sheet extends View {
        int focus;
        StaticLayout layout;
        private final Spring in = new Spring(0.38f, 0.9f, 0), at = new Spring(0.26f, 0.9f, 0), scroll = new Spring(0.3f, 1f, 0);
        private final RectF card = new RectF(), tile = new RectF();
        private final TextPaint fit = new TextPaint();
        private Paint fill, shadow, titlePaint, label, labelOn, inside, insideOn;
        private TextPaint messagePaint;
        private float k;
        private long last;

        Sheet(Context c) {
            super(c);
            in.target = 1;
        }

        void move(int dir) {
            int n = labels.length;
            if (n == 0) return;
            int next = Math.max(0, Math.min(n - 1, focus + dir));
            if (next == focus) return;
            focus = next;
            at.target = focus;
            announceForAccessibility(labels[focus]);
            invalidate();
        }

        private boolean withIcons() {
            return icons != null && icons.length >= labels.length;
        }

        private float tileW() {
            return withIcons() ? 210 : 0;
        }

        /** A tile's width: the icon tiles all alike; text-only ones fit their label. */
        private float widthOf(int i) {
            if (withIcons()) return tileW();
            return Math.max(240, Math.min(560, inside.measureText(labels[i].toString()) / k + 72));
        }

        private float xOf(int i) {
            float x = 0;
            for (int j = 0; j < i; j++) x += widthOf(j) + GAP;
            return x;
        }

        private static final float LEFT = 96, RIGHT = 1824, PAD = 64, GAP = 28;

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setShadowLayer(40 * k, 0, 16 * k, 0x80000000);
            titlePaint = Ui.text(Ui.displaySemibold, 40 * k, 0xFFF5F5F7);
            messagePaint = new TextPaint(Ui.text(Ui.regular, 27 * k, 0xA6FFFFFF));
            label = Ui.text(Ui.medium, 24 * k, 0x99FFFFFF);
            label.setTextAlign(Paint.Align.CENTER);
            labelOn = Ui.text(Ui.semibold, 25 * k, 0xFFF5F5F7);
            labelOn.setTextAlign(Paint.Align.CENTER);
            inside = Ui.text(Ui.medium, 27 * k, 0xD9FFFFFF);
            inside.setTextAlign(Paint.Align.CENTER);
            insideOn = Ui.text(Ui.semibold, 27 * k, 0xFFF5F5F7);
            insideOn.setTextAlign(Paint.Align.CENTER);
            layout = null;
        }

        @Override protected void onDraw(Canvas c) {
            if (fill == null) return;
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 1 / 60f : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            boolean moving = in.step(dt) | at.step(dt) | scroll.step(dt);
            float a = Ui.clamp(in.value), w = getWidth(), h = getHeight();
            c.drawColor(Ui.alpha(0x99000000, a));       // the video behind, dimmed
            float inner = (RIGHT - LEFT - 2 * PAD) * k;
            if (layout == null) {
                String m = message.toString();
                layout = m.isEmpty() ? null : new StaticLayout(m, messagePaint, Math.max(1, Math.round(inner)),
                        Layout.Alignment.ALIGN_NORMAL, 1.1f, 0, false);
            }
            boolean icons = withIcons();
            float tileH = (icons ? 150 : 104) * k, labelH = icons ? (twoLines() ? 86 : 52) * k : 0;
            float textH = (title.length() > 0 ? 56 * k : 0) + (layout != null ? layout.getHeight() + 14 * k : 0);
            float gapToTiles = textH > 0 ? 30 * k : 0;
            float cardH = PAD * k + textH + (labels.length > 0 ? gapToTiles + tileH + labelH : 0) + 52 * k;
            float bottom = h - 48 * k, top = bottom - cardH;
            c.save();
            c.translate(0, (1 - a) * (cardH + 80 * k));
            card.set(LEFT * k, top, RIGHT * k, bottom);
            float cr = LiveTheme.panelRound(40 * k);
            shadow.setColor(LiveTheme.sheet());      // the style's card (Glassio's: dark glass)
            c.drawRoundRect(card, cr, cr, shadow);
            if (LiveTheme.sheetRim() != 0) {       // glass: its light edge all round
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(1.5f * k);
                fill.setColor(LiveTheme.sheetRim());
                c.drawRoundRect(card, cr, cr, fill);
                fill.setStyle(Paint.Style.FILL);
            } else {
                fill.setColor(0x14FFFFFF);      // a fine light edge along the top, as frosted glass has
                c.drawRect(card.left + cr, card.top, card.right - cr, card.top + 1.5f * k, fill);
            }
            float x0 = (LEFT + PAD) * k, y = top + PAD * k;
            if (title.length() > 0) {
                c.drawText(ellipsize(title.toString(), titlePaint, inner), x0, y + 36 * k, titlePaint);
                y += 56 * k;
            }
            if (layout != null) {
                c.save();
                c.translate(x0, y);
                layout.draw(c);
                c.restore();
                y += layout.getHeight() + 14 * k;
            }
            if (labels.length > 0) {
                y += gapToTiles;
                // The row scrolls so the focused tile stays in the card.
                float total = xOf(labels.length - 1) + widthOf(labels.length - 1);
                float fx = xOf(focus), fw = widthOf(focus);
                float target = scroll.target;
                if ((fx - target) * k < 0) target = fx;
                if ((fx + fw - target) * k > inner) target = fx + fw - inner / k;
                target = Math.max(0, Math.min(target, Math.max(0, total - inner / k)));
                scroll.target = target;
                c.save();
                c.clipRect(card.left + 8 * k, y - 30 * k, card.right - 8 * k, bottom);
                for (int i = 0; i < labels.length; i++) {
                    float tw = widthOf(i) * k, tx = x0 + (xOf(i) - scroll.value) * k;
                    float on = Math.max(0, 1 - Math.abs(at.value - i));      // 1 on the focused one, gliding
                    float grow = on * 10 * k;
                    tile.set(tx - grow, y - grow, tx + tw + grow, y + tileH + grow);
                    fill.setColor(on > 0.01f ? mix(LiveTheme.surface, LiveTheme.tile, on) : LiveTheme.surface);
                    c.drawRoundRect(tile, 26 * k * Math.min(1.3f, LiveTheme.round), 26 * k * Math.min(1.3f, LiveTheme.round), fill);
                    String text = labels[i].toString();
                    if (isChecked(i)) {     // the current one: a check at the tile's corner
                        Symbols.draw(getContext(), c, "check", tile.right - 26 * k, tile.top + 26 * k, 20 * k, LiveTheme.onTile(0xFFF5F5F7, on));
                    }
                    if (icons) {
                        if (icons_(i) != null) Symbols.draw(getContext(), c, icons_(i), tile.centerX(), tile.centerY(), 46 * k,
                                LiveTheme.onTile(0xCCFFFFFF, on));
                        Paint p = i == focus ? labelOn : label;
                        float lw = tw + 40 * k;
                        int cut = p.measureText(text) > lw ? splitAt(text) : -1;
                        if (cut > 0) {      // too long for one line: two, split at the space nearest the middle
                            c.drawText(ellipsize(text.substring(0, cut), p, lw), tile.centerX(), y + tileH + 42 * k, p);
                            c.drawText(ellipsize(text.substring(cut + 1), p, lw), tile.centerX(), y + tileH + 76 * k, p);
                        } else {
                            c.drawText(ellipsize(text, p, lw), tile.centerX(), y + tileH + 42 * k, p);
                        }
                    } else {
                        Paint p = i == focus ? insideOn : inside;
                        insideOn.setColor(LiveTheme.ink);      // on the focus
                        c.drawText(ellipsize(text, p, tw - 40 * k), tile.centerX(), tile.centerY() + Ui.halfCap(p), p);
                    }
                }
                c.restore();
            }
            c.restore();
            if (moving) postInvalidateOnAnimation();
            else last = 0;
        }

        private String icons_(int i) {
            return icons[i];
        }

        private int mix(int from, int to, float t) {
            int a = Math.round(((from >>> 24) & 0xFF) * (1 - t) + ((to >>> 24) & 0xFF) * t);
            int r = Math.round(((from >> 16) & 0xFF) * (1 - t) + ((to >> 16) & 0xFF) * t);
            int g = Math.round(((from >> 8) & 0xFF) * (1 - t) + ((to >> 8) & 0xFF) * t);
            int b = Math.round((from & 0xFF) * (1 - t) + (to & 0xFF) * t);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        /** The space nearest the middle of `s` (-1: none). */
        private int splitAt(String s) {
            int best = -1;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == ' ' && (best < 0 || Math.abs(i - s.length() / 2) < Math.abs(best - s.length() / 2))) best = i;
            }
            return best;
        }

        /** Whether a tile's name needs a second line (the card grows for it). */
        private boolean twoLines() {
            if (label == null) return false;
            float lw = (tileW() + 40) * k;
            for (CharSequence l : labels) {
                String t = l.toString();
                if (labelOn.measureText(t) > lw && splitAt(t) > 0) return true;
            }
            return false;
        }

        private String ellipsize(String s, Paint p, float width) {
            fit.set(p);
            return TextUtils.ellipsize(s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
        }
    }
}
