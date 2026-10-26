package com.glasslauncher.tv.live;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * Typing something (a sign-in field, the guide's link, a channel to find), the way Glassio's own text entry looks: a
 * dark glass card with a light rim, the title and a line in Inter, a rounded frosted field, and buttons that take the
 * white plate when selected. Sits near the top, so the TV's keyboard never covers it.
 */
final class LiveTextEntry {
    private LiveTextEntry() {}

    private static final int INK = 0xFF111114, SECONDARY = 0xFFB3B3B8;

    /**
     * Shows `input` (its text, hint and keyboard type already set) under `title` and `message` (null for none); OK, or
     * the keyboard's Done, hands what was typed to `onOk`. Cancel and Back just close.
     */
    static void show(Context c, String title, String message, EditText input, Consumer<String> onOk) {
        Dialog d = new Dialog(c, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(c, 22);
        card.setPadding(pad, dp(c, 20), pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0181820);
        bg.setCornerRadius(dp(c, 20));
        bg.setStroke(Math.max(1, dp(c, 0.8f)), 0x38FFFFFF);
        card.setBackground(bg);

        TextView head = text(c, title, 17, Color.WHITE, Ui.semibold);
        card.addView(head);
        if (message != null && !message.isEmpty()) {
            TextView line = text(c, message, 11.5f, SECONDARY, Ui.medium);
            line.setPadding(0, dp(c, 5), 0, 0);
            card.addView(line);
        }

        // The field: frosted, rounded; a little brighter with a light rim while it has the focus.
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0x73FFFFFF);
        if (Ui.medium != null) input.setTypeface(Ui.medium);
        // (Single line is the caller's to set, before the keyboard type: set again here, it dropped a password's dots.)
        int variation = input.getInputType() & android.text.InputType.TYPE_MASK_VARIATION;
        if ((input.getInputType() & android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_TEXT
                && (variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            input.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        }
        input.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        input.setBackground(field(c));
        if (Build.VERSION.SDK_INT >= 29) {
            GradientDrawable caret = new GradientDrawable();
            caret.setColor(Color.WHITE);
            caret.setSize(dp(c, 2), dp(c, 20));
            input.setTextCursorDrawable(caret);
            input.setHighlightColor(0x55FFFFFF);
        }
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 14);
        card.addView(input, lp);

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(c, 16);
        card.addView(buttons, bp);
        final boolean[] done = {false};
        Runnable ok = () -> {
            if (done[0]) return;
            done[0] = true;
            d.dismiss();
            onOk.accept(input.getText().toString());
        };
        TextView okButton = button(c, "OK");
        okButton.setOnClickListener(v -> ok.run());
        TextView cancel = button(c, Ui.tr("Cancelar", "Cancel"));
        cancel.setOnClickListener(v -> d.dismiss());
        buttons.addView(okButton);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = dp(c, 10);
        buttons.addView(cancel, gap);
        input.setOnEditorActionListener((v, action, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
            if (action == EditorInfo.IME_ACTION_DONE || enter) {
                ok.run();
                return true;
            }
            return false;
        });

        d.setContentView(card);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(dp(c, 520), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            WindowManager.LayoutParams attrs = w.getAttributes();
            attrs.y = dp(c, 48);
            attrs.dimAmount = 0.55f;
            w.setAttributes(attrs);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        input.requestFocus();
        input.setSelection(input.getText().length());
        d.show();
    }

    private static TextView text(Context c, String s, float sp, int color, android.graphics.Typeface face) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxLines(3);
        return t;
    }

    /** A button: a faint glass pill, the white plate with dark text when selected (Glassio's dialog buttons). */
    private static TextView button(Context c, String label) {
        TextView b = text(c, label, 13, Color.WHITE, Ui.semibold);
        b.setMaxLines(1);
        b.setFocusable(true);
        b.setClickable(true);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(c, 84));
        b.setPadding(dp(c, 18), dp(c, 8), dp(c, 18), dp(c, 8));
        float r = dp(c, 10);
        GradientDrawable on = new GradientDrawable();
        on.setColor(0xF2FFFFFF);
        on.setCornerRadius(r);
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x14FFFFFF);
        off.setCornerRadius(r);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, on);
        s.addState(new int[] {android.R.attr.state_pressed}, on);
        s.addState(new int[0], off);
        b.setBackground(s);
        b.setTextColor(new android.content.res.ColorStateList(new int[][] {{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[] {INK, INK, Color.WHITE}));
        b.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(140).start());
        return b;
    }

    /** The field's background: frosted at rest, brighter with a light rim while typing in it. */
    private static StateListDrawable field(Context c) {
        float r = dp(c, 12);
        GradientDrawable on = new GradientDrawable();
        on.setColor(0x29FFFFFF);
        on.setCornerRadius(r);
        on.setStroke(Math.max(1, dp(c, 1)), 0x8CFFFFFF);
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x1AFFFFFF);
        off.setCornerRadius(r);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, on);
        s.addState(new int[0], off);
        return s;
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
