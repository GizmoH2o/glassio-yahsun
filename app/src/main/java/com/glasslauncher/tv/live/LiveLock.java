package com.glasslauncher.tv.live;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One change at a time to Live TV's small files (the schedule, the recordings, the series, favorites and history), in
 * whichever process: the home screen, Live TV and the recorder each read a list, change it and write it back, and two at
 * once lost one of the changes. Always taken before any of the classes' own locks, so they never wait for each other.
 */
final class LiveLock {
    private LiveLock() {}

    private static final ReentrantLock HERE = new ReentrantLock();

    static void run(Context c, Runnable change) {
        HERE.lock();
        try {
            if (HERE.getHoldCount() > 1) {      // inside another change of this thread's: it has the file
                change.run();
                return;
            }
            // A thread being stopped (Stop recording) still locks: an interrupt would close the file under the lock.
            boolean interrupted = Thread.interrupted();
            try (RandomAccessFile f = new RandomAccessFile(new File(c.getApplicationContext().getFilesDir(), "live.lock"), "rw")) {
                try {
                    f.getChannel().lock();      // let go of as the file closes
                } catch (IOException e) {
                    AppLog.w("live: lock: " + e.getMessage());
                }
                change.run();
            } catch (IOException e) {
                AppLog.w("live: lock: " + e.getMessage());
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        } finally {
            HERE.unlock();
        }
    }
}
