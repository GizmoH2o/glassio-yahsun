package com.glasslauncher.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Live TV's process woken ahead (the home screen's Live TV page is open): its saved channel list read and the guide's
 * lists it matches against, so Watch opens Live TV at once instead of starting it all from nothing.
 */
public final class LiveWarm extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        final PendingResult done = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (Xtream.signedIn(app)) {
                    Xtream.saved(app);
                    XmltvStore.guided(app);
                }
            } catch (RuntimeException ignored) {     // only a head start
            } finally {
                done.finish();
            }
        }, "live-warm").start();
    }

    /** From the home screen: wake Live TV's process and let it get ready. */
    public static void ask(Context c) {
        try {
            c.sendBroadcast(new Intent(c, LiveWarm.class));
        } catch (RuntimeException ignored) {
        }
    }
}
