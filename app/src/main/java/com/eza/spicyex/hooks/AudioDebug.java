package com.eza.spicyex.hooks;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only window onto AudioReactiveController for the settings panel's "Audio analysis"
 * diagnostic: which AudioTrack#write overloads Spotify actually calls, the stream format, and
 * the latest analysis. While a diagnostic view is open ({@link #watch}), analysis runs even with
 * the lyrics screen closed.
 */
public final class AudioDebug {
    static final String[] WRITE_KINDS = {"byte[]", "short[]", "float[]", "ByteBuffer"};
    static final AtomicLong[] WRITE_CALLS = {
            new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};
    static volatile String format = "";
    private static volatile int watchers;

    public static volatile float loudness;
    public static volatile float beat;
    public static volatile float accent;
    public static volatile float[] spectrum = new float[AudioReactiveController.BANDS];

    private AudioDebug() {
    }

    public static synchronized void watch(boolean on) {
        watchers = Math.max(0, watchers + (on ? 1 : -1));
    }

    static boolean watching() {
        return watchers > 0;
    }

    /** Total write() calls per overload since the process started, in {@link #kinds()} order. */
    public static long[] writeCalls() {
        long[] out = new long[WRITE_CALLS.length];
        for (int i = 0; i < out.length; i++) out[i] = WRITE_CALLS[i].get();
        return out;
    }

    public static String[] kinds() {
        return WRITE_KINDS.clone();
    }

    public static String format() {
        return format;
    }
}
