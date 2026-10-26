package com.glasslauncher.tv.live;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** Opening Live TV (on a channel, or on its guide). */
final class HomeApps {
    private HomeApps() {}

    static Intent liveIntent(Context c, int channel, boolean guide) {
        Intent i = new Intent().setComponent(new ComponentName(c, Live.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (channel >= 0) i.putExtra(Live.EXTRA_CHANNEL, channel);
        if (guide) i.putExtra(Live.EXTRA_GUIDE, true);
        return i;
    }
}
