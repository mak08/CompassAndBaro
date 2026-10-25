package net.bitsailor.comparo;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Pressure history persisted on the device; samples use wall-clock timestamps. */
public final class PressureHistory {
    public static final class Sample {
        public final long timeMs;
        public final float pa;

        Sample(long timeMs, float pa) {
            this.timeMs = timeMs;
            this.pa = pa;
        }
    }

    public static final long INTERVAL_MS = 10_000;
    public static final long BACKGROUND_INTERVAL_MS = 10 * 60_000;
    public static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final long SAVE_INTERVAL_MS = 60_000;
    private static final String FILE_NAME = "pressure_history.bin";

    private static final List<Sample> SAMPLES = new ArrayList<>();
    private static boolean loaded;
    private static long lastSaveMs;

    private PressureHistory() {
    }

    public static synchronized void load(Context context) {
        if (loaded) {
            return;
        }
        loaded = true;
        File file = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
        if (!file.exists()) {
            return;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                SAMPLES.add(new Sample(in.readLong(), in.readFloat()));
            }
        } catch (IOException e) {
            // Keep whatever was read; a truncated file is acceptable.
        }
        prune(System.currentTimeMillis());
    }

    /** Adds a sample if at least minIntervalMs passed since the last one; returns true if added. */
    public static synchronized boolean add(Context context, long timeMs, float pa,
                                           long minIntervalMs) {
        load(context);
        if (!SAMPLES.isEmpty()
                && Math.abs(timeMs - SAMPLES.get(SAMPLES.size() - 1).timeMs) < minIntervalMs) {
            return false;
        }
        SAMPLES.add(new Sample(timeMs, pa));
        prune(timeMs);
        if (timeMs - lastSaveMs >= SAVE_INTERVAL_MS) {
            save(context);
        }
        return true;
    }

    private static void prune(long nowMs) {
        int drop = 0;
        while (drop < SAMPLES.size() && nowMs - SAMPLES.get(drop).timeMs > MAX_AGE_MS) {
            drop++;
        }
        if (drop > 0) {
            SAMPLES.subList(0, drop).clear();
        }
    }

    public static synchronized void save(Context context) {
        lastSaveMs = System.currentTimeMillis();
        File dir = context.getApplicationContext().getFilesDir();
        File tmp = new File(dir, FILE_NAME + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(SAMPLES.size());
            for (Sample s : SAMPLES) {
                out.writeLong(s.timeMs);
                out.writeFloat(s.pa);
            }
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(new File(dir, FILE_NAME))) {
            tmp.delete();
        }
    }

    public static synchronized List<Sample> snapshot(Context context) {
        load(context);
        return new ArrayList<>(SAMPLES);
    }
}
