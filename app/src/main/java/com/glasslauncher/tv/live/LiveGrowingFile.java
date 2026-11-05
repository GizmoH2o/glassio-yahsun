package com.glasslauncher.tv.live;

import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * A past show's file the provider is still adding to: a show on the air, whose address answers with the size it has so
 * far. Told that size, the player's MPEG-TS reader goes to the file's end to learn how long it is, finds it has moved,
 * and goes again, for ever, and nothing ever plays. So the size isn't passed on; and when the bytes that were there run
 * out, it asks again from the same byte, since there are more by then. Apart from the player, to be tested.
 */
final class LiveGrowingFile {
    /** What the player's sources say for "no length" and for "the end" (C.LENGTH_UNSET, C.RESULT_END_OF_INPUT). */
    static final long UNKNOWN = -1;
    private static final int END = -1;

    interface Bytes {
        long open(long position) throws IOException;

        int read(byte[] into, int offset, int length) throws IOException;

        void close() throws IOException;
    }

    interface Pause {
        void run() throws IOException;
    }

    private final Bytes bytes;
    private final Pause pause;
    private final int patience;
    private boolean following, connected;
    private long position;

    /** `patience`: how many times to `pause` and ask again before it's the end. */
    LiveGrowingFile(Bytes bytes, Pause pause, int patience) {
        this.bytes = bytes;
        this.pause = pause;
        this.patience = patience;
    }

    long open(long position, boolean growing) throws IOException {
        this.position = position;
        long length = bytes.open(position);
        connected = true;
        following = growing && length != UNKNOWN;       // no length of its own: a stream, which ends when it ends
        return following ? UNKNOWN : length;
    }

    int read(byte[] into, int offset, int length) throws IOException {
        int tries = 0;
        while (true) {
            int n = connected ? bytes.read(into, offset, length) : END;
            if (n != END) {
                position += n;
                return n;
            }
            if (!following || tries >= patience) return END;
            tries++;
            pause.run();
            bytes.close();
            try {
                bytes.open(position);
                connected = true;
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException e) {       // nothing past there yet
                connected = false;
            }
        }
    }

    void close() throws IOException {
        connected = false;
        bytes.close();
    }
}
