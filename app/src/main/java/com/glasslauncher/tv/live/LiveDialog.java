package com.glasslauncher.tv.live;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * Live TV's full-screen panels (a sheet, a show's page, search, the guide's panel…): each comes in with its own spring,
 * and now goes the same way rather than vanishing: Back (or anything that closes it) fades it and lets it settle back
 * a little, then it really closes. Keys while it goes are its own (nothing behind it moves yet).
 */
class LiveDialog extends Dialog {
    /** How long going takes, at Glassio's own speed (the TV's animation scale left out, 0 = off kept). */
    static final long LEAVE_MS = 180;
    private boolean leaving;

    LiveDialog(Context c) {
        super(c, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
    }

    @Override
    public void dismiss() {
        if (leaving) return;
        View v = getWindow() != null ? getWindow().getDecorView() : null;
        Activity a = activityOf(getContext());
        float scale = animatorScale(getContext());
        if (v == null || !isShowing() || scale == 0f || Spring.still || (a != null && (a.isFinishing() || a.isDestroyed())) || !v.isAttachedToWindow()) {
            super.dismiss();
            return;
        }
        leaving = true;
        v.animate().alpha(0f).scaleX(0.985f).scaleY(0.985f).translationY(v.getHeight() * 0.012f)
                .setDuration((long) (LEAVE_MS / scale))
                .setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f))
                .withEndAction(() -> {
                    try { super.dismiss(); } catch (RuntimeException ignored) { }
                })
                .start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (leaving) return true;
        return super.dispatchKeyEvent(e);
    }

    /** True while it's on its way out (it's still showing then). */
    boolean leaving() { return leaving; }

    private static float animatorScale(Context c) {
        try { return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f); }
        catch (RuntimeException e) { return 1f; }
    }

    private static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
