package com.glasslauncher.tv.live;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Multiview's rules: which boxes use the video chip, saved views, and the wall of live matches. Pure. */
final class LiveMultiRules {
    private LiveMultiRules() {}

    /** How many views are kept. */
    static final int SAVED = 6;

    /** A saved Multiview: its channels (the first box first) and whether it's picture-in-picture. */
    static final class View {
        String name = "";
        boolean pip;
        final List<Integer> channels = new ArrayList<>();
    }

    /**
     * Whether a box decodes on the video chip. This TV runs two at full speed (measured: 50 fps each, against 7 in
     * software), so all of them do, unless the setting's off or the chip already refused this box.
     */
    static boolean hardware(boolean allOn, boolean refused, int box, int focus) {
        if (refused) return false;
        return allOn || box == focus;
    }

    static final long FROZEN_MS = 12_000, STUCK_MS = 25_000;
    static final int TRIES = 5;

    /**
     * Whether a box's picture stands still: nothing new drawn for a while though it plays, or waiting for data longer
     * than a slow moment (`since`: when it last moved). Pure.
     */
    static boolean frozen(boolean ready, boolean began, long since, long now) {
        return began && since > 0 && now - since > (ready ? FROZEN_MS : STUCK_MS);
    }

    /** The seconds of a wait, at least one till it's over. Pure. */
    static int seconds(long at, long now) {
        return at <= now ? 0 : (int) ((at - now + 999) / 1000);
    }

    /** Where button `at` of `n` goes with ◀ or ▶; -1: past the end, to the next box. Pure. */
    static int button(int at, int n, boolean right) {
        int to = at + (right ? 1 : -1);
        return to < 0 || to >= n ? -1 : to;
    }

    /** The channels for the wall: each once, no more than the account allows. */
    static List<Integer> wall(List<Integer> onNow, int allowed) {
        List<Integer> out = new ArrayList<>();
        for (int ch : onNow) if (out.size() < allowed && !out.contains(ch)) out.add(ch);
        return out;
    }

    /** A view's name: its channels. */
    static String name(List<String> channels) {
        return String.join(" + ", channels);
    }

    /** `views` with `v` first; the same channels again replace the old one. None without channels. */
    static List<View> add(List<View> views, View v) {
        if (v.channels.isEmpty()) return views;
        List<View> out = new ArrayList<>();
        out.add(v);
        for (View o : views) if (!o.channels.equals(v.channels) && out.size() < SAVED) out.add(o);
        return out;
    }

    static List<View> remove(List<View> views, int at) {
        List<View> out = new ArrayList<>(views);
        if (at >= 0 && at < out.size()) out.remove(at);
        return out;
    }

    static String write(List<View> views) {
        JSONArray a = new JSONArray();
        try {
            for (View v : views) {
                JSONArray ch = new JSONArray();
                for (int id : v.channels) ch.put(id);
                a.put(new JSONObject().put("name", v.name).put("pip", v.pip).put("ch", ch));
            }
        } catch (JSONException ignored) {
        }
        return a.toString();
    }

    static List<View> parse(String json) {
        List<View> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                View v = new View();
                v.name = o.optString("name");
                v.pip = o.optBoolean("pip");
                JSONArray ch = o.getJSONArray("ch");
                for (int j = 0; j < ch.length(); j++) v.channels.add(ch.getInt(j));
                out.add(v);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** Picture-in-picture: box 0 the whole screen, box 1 small at the bottom right (1920 space). */
    static float[] placePip(int box) {
        return box == 0 ? new float[] {0, 0, 1920, 1080} : new float[] {1296, 680, 1872, 1004};
    }
}
