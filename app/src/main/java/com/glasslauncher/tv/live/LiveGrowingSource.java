package com.glasslauncher.tv.live;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** The player's sources, reading a file that's still growing as LiveGrowingFile says. Loading thread. */
final class LiveGrowingSource implements DataSource {
    private static final long WAIT_MS = 1000;
    private static final int PATIENCE = 8;

    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstream;
        private final BooleanSupplier growing;

        Factory(DataSource.Factory upstream, BooleanSupplier growing) {
            this.upstream = upstream;
            this.growing = growing;
        }

        @Override public DataSource createDataSource() {
            return new LiveGrowingSource(upstream.createDataSource(), growing);
        }
    }

    private final DataSource upstream;
    private final BooleanSupplier growing;
    private DataSpec spec;
    private final LiveGrowingFile file;

    private LiveGrowingSource(DataSource upstream, BooleanSupplier growing) {
        this.upstream = upstream;
        this.growing = growing;
        file = new LiveGrowingFile(new LiveGrowingFile.Bytes() {
            @Override public long open(long position) throws IOException {
                return upstream.open(spec.buildUpon().setPosition(position).build());
            }

            @Override public int read(byte[] into, int offset, int length) throws IOException {
                return upstream.read(into, offset, length);
            }

            @Override public void close() throws IOException {
                upstream.close();
            }
        }, () -> {
            try {
                Thread.sleep(WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
        }, PATIENCE);
    }

    @Override public void addTransferListener(TransferListener listener) {
        upstream.addTransferListener(listener);
    }

    @Override public long open(DataSpec dataSpec) throws IOException {
        spec = dataSpec;
        return file.open(dataSpec.position, dataSpec.length == LiveGrowingFile.UNKNOWN && growing.getAsBoolean());
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        return file.read(buffer, offset, length);
    }

    @Nullable @Override public Uri getUri() {
        return upstream.getUri();
    }

    @Override public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override public void close() throws IOException {
        file.close();
    }
}
