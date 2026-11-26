package com.glasslauncher.tv.live;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;

/** A recording read from some byte on while it's still being made: at its newest, the rest as it comes. Loading thread. */
final class LiveRecordSource extends BaseDataSource {
    static DataSource.Factory factory(Context c, String id, File file, long from) {
        final Context app = c.getApplicationContext();
        return () -> new LiveRecordSource(app, id, file, from);
    }

    private final Context app;
    private final String id;
    private final File file;
    private final long from;
    private Uri uri;
    private RandomAccessFile in;
    private boolean opened;
    private long asked;
    private boolean over;

    private LiveRecordSource(Context app, String id, File file, long from) {
        super(false);
        this.app = app;
        this.id = id;
        this.file = file;
        this.from = from;
    }

    @Override public long open(DataSpec spec) throws IOException {
        uri = spec.uri;
        transferInitializing(spec);
        in = new RandomAccessFile(file, "r");
        in.seek(from + spec.position);
        over = false;
        opened = true;
        transferStarted(spec);
        return C.LENGTH_UNSET;
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        while (true) {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                bytesTransferred(n);
                return n;
            }
            if (over) return C.RESULT_END_OF_INPUT;     // read once more after it ended: nothing left
            long now = SystemClock.uptimeMillis();
            if (now - asked > 2000) {
                asked = now;
                LiveRecordings.Rec r = LiveRecordings.find(app, id);
                over = r == null || !LiveRecordings.RECORDING.equals(r.state);
                if (over) continue;
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            SystemClock.sleep(60);
        }
    }

    @Override public Uri getUri() {
        return uri;
    }

    @Override public void close() throws IOException {
        if (in != null) in.close();
        in = null;
        if (opened) {
            opened = false;
            transferEnded();
        }
    }
}
