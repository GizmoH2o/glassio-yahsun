package com.glasslauncher.tv.live;

import android.app.Activity;
import android.app.SearchManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Where Google's voice and Google TV's search hand a channel to Glassio: "Play TLC on Glassio Yahsun"
 * (MEDIA_PLAY_FROM_SEARCH), a search typed or said (SEARCH), or one of Glassio's results in Google TV's search
 * (glassioyahsun://live/channel/ID). Nothing of its own shows: it finds the channel (LiveVoice) and opens Live TV on
 * it; no fit, Live TV's search with the words; nothing said, the channel watched last. Off in Live TV's settings, it
 * isn't there at all (LiveVoice.apply).
 */
public final class LiveVoiceActivity extends Activity {
    static final String SCHEME = "glassioyahsun";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        final android.content.Context app = getApplicationContext();
        final String said = in == null ? null : in.getStringExtra(SearchManager.QUERY);
        final Uri data = in == null ? null : in.getData();
        // A search typed or said tunes only with "watch" first ("watch TLC"); a name on its own is Live TV's search.
        // ("Play TLC on Glassio Yahsun" comes as MEDIA_PLAY_FROM_SEARCH: Google has asked to play it already.)
        final boolean search = in != null && Intent.ACTION_SEARCH.equals(in.getAction());
        if (search && said != null && !said.trim().isEmpty() && LiveVoice.afterWatch(said) == null
                && LiveOptions.voiceChannels(app)) {
            try {
                startActivity(HomeApps.liveIntent(app, -1, false).putExtra(Live.EXTRA_SEARCH, said.trim()));
            } catch (RuntimeException e) {
                AppLog.w("live: voice couldn't open Live TV: " + e.getMessage());
            }
            finish();
            return;
        }
        new Thread(() -> {
            Intent open = target(app, said, data);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    startActivity(open);
                } catch (RuntimeException e) {
                    AppLog.w("live: voice couldn't open Live TV: " + e.getMessage());
                }
                finish();
            });
        }, "live-voice").start();
    }

    /** Live TV on the channel asked for (blocking: it may read the whole channel list). */
    static Intent target(android.content.Context c, String said, Uri data) {
        if (!LiveOptions.voiceChannels(c)) return HomeApps.liveIntent(c, -1, false);
        // One of Glassio's own results in Google TV's search: its channel.
        if (data != null && SCHEME.equals(data.getScheme()) && "live".equals(data.getHost())) {
            String last = data.getLastPathSegment();
            int id = -1;
            try {
                id = last == null ? -1 : Integer.parseInt(last);
            } catch (NumberFormatException ignored) {
            }
            return HomeApps.liveIntent(c, id, false);
        }
        if (said == null || said.trim().isEmpty()) return HomeApps.liveIntent(c, -1, false);
        LiveVoice.Query q = LiveVoice.parse(said);
        LiveVoice.Catalog cat = Xtream.ready(c) ? LiveVoice.catalog(c) : null;
        if (cat == null) return HomeApps.liveIntent(c, -1, false);
        Xtream.Channel ch = cat.find(q);
        AppLog.w("live: voice \"" + said + "\" → " + (ch == null ? "no channel" : ch.num + " " + ch.name));
        if (ch != null) return HomeApps.liveIntent(c, ch.id, false);
        return HomeApps.liveIntent(c, -1, false).putExtra(Live.EXTRA_SEARCH, q.name.isEmpty() ? said : q.name);
    }
}
