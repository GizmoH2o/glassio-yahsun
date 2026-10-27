package com.glasslauncher.tv.live;

import android.app.SearchManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.BaseColumns;

import java.util.List;

/**
 * Live TV's channels in the TV's own search ("TLC", "TLC USA"): a few that fit, each opening Live TV on it
 * (LiveVoiceActivity). Only the system's search may read it (GLOBAL_SEARCH, in the manifest); whether Google TV shows
 * apps' results there is the TV's choice. Off with the voice setting (LiveVoice.apply).
 */
public final class LiveSearchProvider extends ContentProvider {
    private static final String[] COLUMNS = {BaseColumns._ID, SearchManager.SUGGEST_COLUMN_TEXT_1, SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE, SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID,
            SearchManager.SUGGEST_COLUMN_IS_LIVE, SearchManager.SUGGEST_COLUMN_CONTENT_TYPE};

    @Override public boolean onCreate() {
        return true;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor out = new MatrixCursor(COLUMNS);
        android.content.Context c = getContext();
        if (c == null || !LiveOptions.voiceChannels(c) || !Xtream.ready(c)) return out;
        String said = args != null && args.length > 0 ? args[0] : uri.getLastPathSegment();
        if (said == null || said.trim().isEmpty() || SearchManager.SUGGEST_URI_PATH_QUERY.equals(said)) return out;
        LiveVoice.Catalog cat = LiveVoice.catalog(c);
        if (cat == null) return out;
        List<Xtream.Channel> found = cat.some(LiveVoice.parse(said), 5);
        int row = 0;
        for (Xtream.Channel ch : found) {
            String cc = cat.countryOf(ch);
            String name = LiveCountry.split(ch.name).name;
            String line = "Live TV" + (ch.num > 0 ? "  ·  " + ch.num : "") + (cc.isEmpty() ? "" : "  ·  " + cc);
            out.addRow(new Object[] {row++, name.isEmpty() ? ch.name : name, line, ch.logo == null ? "" : ch.logo,
                    String.valueOf(ch.id), 1, "video/mp2t"});
        }
        return out;
    }

    @Override public String getType(Uri uri) {
        return SearchManager.SUGGEST_MIME_TYPE;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
