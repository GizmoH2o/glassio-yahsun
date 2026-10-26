package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The recordings (RecordService makes them, Live TV's Recordings category lists and plays them): each one's show,
 * channel and times, its file (in the app's own storage) and how it's going. A small index file, shared by the
 * recorder's process (:rec) and Live TV's (:live).
 */
final class LiveRecordings {
    private LiveRecordings() {}

    static final String RECORDING = "recording", DONE = "done", FAILED = "failed";

    static final class Rec {
        String id, title = "", about = "", file, state = RECORDING;
        /** Why it failed, was cut short or missed ("" when all went well): the Recordings page and the notice say so. */
        String why = "";
        Xtream.Channel ch;
        long start, end, size;

        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("title", title).put("about", about).put("file", file).put("state", state)
                    .put("ch", Xtream.json(ch)).put("start", start).put("end", end).put("size", size)
                    .put("why", why);
        }

        static Rec of(JSONObject o) {
            JSONObject ch = o.optJSONObject("ch");
            if (ch == null) return null;
            Rec r = new Rec();
            r.id = o.optString("id");
            r.title = o.optString("title");
            r.about = o.optString("about");
            r.file = o.optString("file");
            r.state = o.optString("state", DONE);
            r.ch = Xtream.channel(ch);
            r.start = o.optLong("start");
            r.end = o.optLong("end");
            r.size = o.optLong("size");
            r.why = o.optString("why");
            return r;
        }
    }

    static final String PROBLEM = "com.glasslauncher.tv.live.action.RECORDING_PROBLEM", EXTRA_HEAD = "head", EXTRA_LINE = "line";

    /**
     * A recording that failed, was cut short or was missed: a notice (the home screen shows it, Glassio's settings list
     * it) and, with Live TV open, a card there. Any process.
     */
    static void tell(Context c, Rec r, String head) {
        String what = (r.title.isEmpty() ? r.ch.name : r.title) + " (" + r.ch.name + ")";
        HomeMode.notice(c, head + ": " + what + ", " + r.why + ".");
        c.sendBroadcast(new android.content.Intent(PROBLEM).setPackage(c.getPackageName())
                .putExtra(EXTRA_HEAD, head).putExtra(EXTRA_LINE, what + ": " + r.why));
    }

    /** Every recording, the latest first. */
    static synchronized List<Rec> all(Context c) {
        List<Rec> out = new ArrayList<>();
        File f = index(c);
        if (!f.exists()) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONArray a = new JSONArray(Xtream.read(in));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                Rec r = o == null ? null : Rec.of(o);
                if (r != null) out.add(r);
            }
        } catch (IOException | JSONException e) {
            AppLog.w("live: recordings unreadable");
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.start, a.start));
        return out;
    }

    static synchronized Rec find(Context c, String id) {
        for (Rec r : all(c)) if (r.id.equals(id)) return r;
        return null;
    }

    /** Adds or updates `r` (by its id). */
    static synchronized void put(Context c, Rec r) {
        List<Rec> list = all(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(r.id)) list.remove(i);
        list.add(r);
        save(c, list);
    }

    /** Takes it out, and its file. */
    static synchronized void delete(Context c, String id) {
        List<Rec> list = all(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (!list.get(i).id.equals(id)) continue;
            //noinspection ResultOfMethodCallIgnored
            new File(list.get(i).file).delete();
            list.remove(i);
        }
        save(c, list);
    }

    /** The id of the recording of the show on channel `channel` from `start` (the show's own start). */
    static String idOf(int channel, long start) {
        return "rec:" + channel + ":" + start;
    }

    /** Whether the show on channel `channel` from `start` is being recorded now. */
    static boolean recording(Context c, int channel, long start) {
        String id = idOf(channel, start);
        for (Rec r : all(c)) if (RECORDING.equals(r.state) && r.id.equals(id)) return true;
        return false;
    }

    /** When the recordings last changed (the list follows). */
    static long stamp(Context c) {
        File f = index(c);
        return f.exists() ? f.lastModified() * 31 + f.length() : 0;
    }

    /** Where recordings go: the app's own storage on the TV (removed with the app). */
    /** The room the recordings take (their folder's files, a level down too). */
    static long bytes(Context c) {
        long n = 0;
        File[] files = folder(c).listFiles();
        for (int i = 0; files != null && i < files.length; i++) {
            if (files[i].isFile()) {
                n += files[i].length();
                continue;
            }
            File[] inner = files[i].listFiles();
            for (int j = 0; inner != null && j < inner.length; j++) n += inner[j].length();
        }
        return n;
    }

    static File folder(Context c) {
        File dir = c.getExternalFilesDir("Recordings");
        if (dir == null) dir = new File(c.getFilesDir(), "recordings");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static void save(Context c, List<Rec> list) {
        JSONArray a = new JSONArray();
        try {
            for (Rec r : list) a.put(r.json());
            Xtream.write(index(c), a.toString());
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't save the recordings");
        }
    }

    private static File index(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "live-recordings.json");
    }
}
